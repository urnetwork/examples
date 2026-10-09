"""The credential-free self-test (EMBED_CONTRACT.md, "Self-test"). It checks the
golden vectors and status rules, the data fields, the cap object, the JWT
claim, the token fetch against a stand-in server, the installation state files
and the configuration exit codes without a network, credentials, a device or
the native SDK. `main.py --self-test` runs every check; test_embed.py runs each
one as a test."""

import base64
import contextlib
import ctypes as C
import io
import json
import os
import platform
import shutil
import subprocess
import sys
import tempfile

from caps import (
    CAPPED_REASON_MONTHLY,
    CAPPED_REASON_TOTAL,
    EMBED_NOT_ENABLED,
    CapReading,
    is_embed_not_enabled,
    parse_cap_answer,
    parse_cap_object,
    read_own_caps,
)
from client_token import TOKEN_ROUTE, TokenServerError, TokenServerRefused, fetch_client_jwt
from sdk_load import EXIT_CONFIG as SDK_EXIT_CONFIG, SdkLoadError, load_urnetwork, sdk_mismatch_message
from state import (
    CLIENT_JWT_FILE_NAME,
    INSTANCE_ID_FILE_NAME,
    POSIX,
    STATE_FILE_BYTE_LIMIT,
    ConfigError,
    StateFileError,
    check_state_dir,
    load_client_jwt,
    load_or_create_instance_id,
    parse_client_jwt_client_id,
    read_private_file,
    write_private_file,
)
from status import (
    CLIENT_LIMIT_STATUS_EXCEEDED,
    CLIENT_LIMIT_STATUS_NONE,
    CapState,
    client_limit_text,
    data_field_text,
    embed_status,
    format_byte_count,
    license_app,
    parse_client_limit_status,
    parse_providers_added,
    reset_time_text,
    start_line,
    status_snapshot,
)
from transport import check_origin

TEST_CLIENT_ID = "11111111-1111-1111-1111-111111111111"
TEST_OTHER_CLIENT_ID = "22222222-2222-2222-2222-222222222222"
TEST_INSTANCE_ID = "33333333-3333-3333-3333-333333333333"
TEST_TOKEN_SERVER = "http://127.0.0.1:8790"
TEST_API = "http://127.0.0.1:8791"
# a stand-in demo session; real sessions come from your service's sign-in
TEST_SESSION = "self-test-demo-session-0123456789abcdef"

# 2026-10-06 19:05:00.000 UTC
TEST_RETRY_TIME = 1791313500000

# the settings that run() reads
EMBED_SETTINGS = (
    "URNETWORK_EMBED_STATE_DIR",
    "URNETWORK_TOKEN_SERVER_URL",
    "URNETWORK_DEMO_SESSION",
    "URNETWORK_API_URL",
)


class SelfTestError(Exception):
    """A self-test check that failed."""


def run_self_test():
    """Runs every check and raises the first failure."""
    for check in CHECKS:
        check()


def self_test_jwt(payload_json: str) -> str:
    """An unsigned JWT with payload_json as its claims, for the claim checks only."""

    def segment(text: str) -> str:
        return base64.urlsafe_b64encode(text.encode()).decode().rstrip("=")

    header = segment('{"alg":"none"}')
    return f"{header}.{segment(payload_json)}.c2lnbmF0dXJl"


TEST_CLIENT_JWT = self_test_jwt(f'{{"client_id":"{TEST_CLIENT_ID}"}}')


def cap_reading(**fields) -> CapReading:
    """A cap reading from cap object fields, through the real parser."""
    reading = parse_cap_object({"client_id": TEST_CLIENT_ID, **fields})
    if reading is None:
        raise SelfTestError(f"the self-test's own cap object does not parse: {fields}")
    return reading


def expect(condition: bool, message: str):
    """Raises SelfTestError with message unless condition holds."""
    if not condition:
        raise SelfTestError(message)


def expect_raises(error_type, function, message: str):
    """Raises SelfTestError unless function raises error_type."""
    try:
        function()
    except error_type:
        return
    except Exception as error:
        raise SelfTestError(f"{message}: raised {type(error).__name__} instead") from None
    raise SelfTestError(message)


@contextlib.contextmanager
def private_dir():
    """A private temporary directory (0700), removed afterwards."""
    directory = tempfile.mkdtemp(prefix="ur-embed-self-test-")
    try:
        if POSIX:
            os.chmod(directory, 0o700)
        yield directory
    finally:
        shutil.rmtree(directory, ignore_errors=True)


@contextlib.contextmanager
def embed_environment(**values):
    """The embed settings set to values, and the other embed settings unset, for
    the duration; the previous environment is restored afterwards."""
    saved = {name: os.environ.get(name) for name in EMBED_SETTINGS}
    try:
        for name in EMBED_SETTINGS:
            os.environ.pop(name, None)
        for name, value in values.items():
            os.environ[name] = value
        yield
    finally:
        for name, value in saved.items():
            if value is None:
                os.environ.pop(name, None)
            else:
                os.environ[name] = value


class StandInServer:
    """A stand-in for the token server and the API: a transport callable that logs
    each request and answers from a queue of (status, body) answers. An answer
    of an OSError instance is raised, as an unreachable server."""

    def __init__(self, *answers):
        self.answers = list(answers)
        self.requests = []

    def __call__(self, method, url, headers, body):
        self.requests.append({"method": method, "url": url, "headers": dict(headers), "body": body})
        answer = self.answers.pop(0)
        if isinstance(answer, OSError):
            raise answer
        status, value = answer
        if not isinstance(value, bytes):
            value = json.dumps(value).encode()
        return status, value


def token_answer(client_id: str = TEST_CLIENT_ID, client_jwt: str = TEST_CLIENT_JWT, data_cap="default") -> dict:
    """A token server success answer."""
    if data_cap == "default":
        data_cap = {"client_id": client_id, "monthly_byte_limit": 10000000000, "monthly_used_byte_count": 0}
    return {"client_id": client_id, "by_client_jwt": client_jwt, "data_cap": data_cap}


def check_byte_vectors():
    """Data amounts use decimal units, one decimal, ties to even on the exact
    integer."""
    cases = [
        (0, "0 B"),
        (999, "999 B"),
        (1000, "1.0 kB"),
        (999949, "999.9 kB"),
        # exact halves round to even on the integer; a float formatter rounds the
        # binary value of 1.05, just above 1.05, up to 1.1
        (1050, "1.0 kB"),
        (1150, "1.2 kB"),
        (1250, "1.2 kB"),
        (1750, "1.8 kB"),
        # 999.95 kB ties to the even 1000.0 kB and moves to the next unit, as does 999.999 kB
        (999950, "1.0 MB"),
        (999999, "1.0 MB"),
        (1234567890, "1.2 GB"),
        (5000000000, "5.0 GB"),
        (10000000000, "10.0 GB"),
        (3000000000000, "3.0 TB"),
        (9223372036854775807, "9.2 EB"),
    ]
    for byte_count, text in cases:
        expect(format_byte_count(byte_count) == text, f"{byte_count} bytes format as {format_byte_count(byte_count)!r}, want {text!r}")


def check_reset_vectors():
    """The monthly reset time is in UTC with the seconds rounded up; text that does
    not parse has no reset time."""
    cases = [
        ("2026-11-01T00:00:00Z", "resets 2026-11-01 00:00 UTC"),
        # rounds up to the next whole minute
        ("2026-10-31T23:59:00.001Z", "resets 2026-11-01 00:00 UTC"),
        # converted to UTC
        ("2026-10-31T19:00:00-05:00", "resets 2026-11-01 00:00 UTC"),
        ("2026-10-31T23:59:59.999999999Z", "resets 2026-11-01 00:00 UTC"),
        # rounding up rolls over the day and the year
        ("2026-12-31T23:59:30+00:00", "resets 2027-01-01 00:00 UTC"),
    ]
    for period_end, text in cases:
        expect(reset_time_text(period_end) == text, f"{period_end!r} resets as {reset_time_text(period_end)!r}, want {text!r}")
    for unparsed in ("", "2026-11-01", "not a time", "2026-13-01T00:00:00Z", "2026-02-30T00:00:00Z", "2026-11-01T00:00:00"):
        expect(reset_time_text(unparsed) is None, f"{unparsed!r} must not parse as a reset time")


def check_client_limit_vectors():
    """The client limit status names the SDK's retry time in UTC, rounded up."""
    cases = [
        (TEST_RETRY_TIME, "client limit, retry at 19:05 UTC"),
        # 19:04:00.001 UTC rounds up
        (1791313440001, "client limit, retry at 19:05 UTC"),
        (0, "client limit"),
    ]
    for retry_time, text in cases:
        expect(client_limit_text(retry_time) == text, f"retry {retry_time} shows {client_limit_text(retry_time)!r}, want {text!r}")


def check_status_lines():
    """The contract's console status line vectors."""
    failed = CapState()
    failed.record(None)
    # the first reading answers the Embed-not-enabled refusal; and a capped
    # monthly reading, then the refusal
    refused = CapState()
    refused.record(EMBED_NOT_ENABLED)
    cleared = CapState(
        cap_reading(
            monthly_byte_limit=5000000000,
            monthly_used_byte_count=5000000000,
            monthly_period_end="2026-11-01T00:00:00Z",
            capped=True,
            capped_reason=CAPPED_REASON_MONTHLY,
        )
    )
    cleared.record(EMBED_NOT_ENABLED)
    cases = [
        # connecting, caps not read yet
        ("", 0, CapState(), 0, "status: connecting | data this month: checking | data total: checking"),
        (
            "",
            0,
            CapState(cap_reading(monthly_byte_limit=5000000000, monthly_used_byte_count=1234567890, total_byte_limit=None)),
            1,
            "status: connected | data this month: 1.2 GB of 5.0 GB | data total: no cap",
        ),
        ("", 0, failed, 1, "status: connected | data this month: unavailable | data total: unavailable"),
        ("", 0, refused, 1, "status: connected | data this month: unavailable | data total: unavailable"),
        ("", 0, cleared, 1, "status: connected | data this month: unavailable | data total: unavailable"),
        (
            "",
            0,
            CapState(
                cap_reading(
                    monthly_byte_limit=5000000000,
                    monthly_used_byte_count=5000000000,
                    monthly_period_end="2026-11-01T00:00:00Z",
                    capped=True,
                    capped_reason=CAPPED_REASON_MONTHLY,
                )
            ),
            3,
            "status: data cap reached, resets 2026-11-01 00:00 UTC | data this month: 5.0 GB of 5.0 GB | data total: no cap",
        ),
        (
            "",
            0,
            CapState(
                cap_reading(
                    total_byte_limit=10000000000,
                    total_used_byte_count=10000000000,
                    capped=True,
                    capped_reason=CAPPED_REASON_TOTAL,
                )
            ),
            3,
            "status: data cap reached | data this month: no cap | data total: 10.0 GB of 10.0 GB",
        ),
        (
            "",
            0,
            CapState(cap_reading(monthly_byte_limit=0, monthly_used_byte_count=0, capped=True, capped_reason=CAPPED_REASON_MONTHLY)),
            3,
            "status: paused | data this month: 0 B of 0 B | data total: no cap",
        ),
        (
            CLIENT_LIMIT_STATUS_EXCEEDED,
            TEST_RETRY_TIME,
            CapState(cap_reading(monthly_byte_limit=5000000000, monthly_used_byte_count=0)),
            0,
            "status: client limit, retry at 19:05 UTC | data this month: 0 B of 5.0 GB | data total: no cap",
        ),
    ]
    for client_limit_status, retry_time, cap_state, providers_added, line in cases:
        actual = status_snapshot(True, False, client_limit_status, retry_time, cap_state, providers_added).line()
        expect(actual == line, f"status line {actual!r}, want {line!r}")


def check_status_rules():
    """The contract's status rule vectors, in rule order."""
    capped_monthly_zero = cap_reading(monthly_byte_limit=0, capped=True, capped_reason=CAPPED_REASON_MONTHLY)
    capped_total_zero = cap_reading(
        total_byte_limit=0,
        monthly_byte_limit=5000000000,
        capped=True,
        capped_reason=CAPPED_REASON_TOTAL,
    )
    capped_monthly = cap_reading(
        monthly_byte_limit=5000000000,
        monthly_period_end="2026-11-01T00:00:00Z",
        capped=True,
        capped_reason=CAPPED_REASON_MONTHLY,
    )
    capped_total = cap_reading(total_byte_limit=10000000000, capped=True, capped_reason=CAPPED_REASON_TOTAL)
    not_capped = cap_reading()
    cases = [
        (False, False, CLIENT_LIMIT_STATUS_NONE, 0, None, 0, "stopped"),
        (False, True, CLIENT_LIMIT_STATUS_NONE, 0, None, 0, "signed out"),
        # client limit before paused
        (True, False, CLIENT_LIMIT_STATUS_EXCEEDED, TEST_RETRY_TIME, capped_monthly_zero, 3, "client limit, retry at 19:05 UTC"),
        (True, False, CLIENT_LIMIT_STATUS_NONE, 0, capped_monthly_zero, 3, "paused"),
        # the cap that capped_reason names decides paused
        (True, False, CLIENT_LIMIT_STATUS_NONE, 0, capped_total_zero, 3, "paused"),
        # paused before data cap reached
        (True, False, CLIENT_LIMIT_STATUS_NONE, 0, capped_monthly, 3, "data cap reached, resets 2026-11-01 00:00 UTC"),
        (True, False, CLIENT_LIMIT_STATUS_NONE, 0, capped_total, 0, "data cap reached"),
        (True, False, CLIENT_LIMIT_STATUS_NONE, 0, not_capped, 1, "connected"),
        (True, False, CLIENT_LIMIT_STATUS_NONE, 0, None, 0, "connecting"),
    ]
    for started, signed_out, client_limit_status, retry_time, reading, providers_added, status in cases:
        actual = embed_status(started, signed_out, client_limit_status, retry_time, reading, providers_added)
        expect(actual == status, f"status {actual!r}, want {status!r} for started={started} signed_out={signed_out} reading={reading}")
    # a monthly cap whose period end does not parse is reached without a reset time
    unparsed_end = cap_reading(monthly_byte_limit=5, monthly_period_end="soon", capped=True, capped_reason=CAPPED_REASON_MONTHLY)
    expect(embed_status(True, False, "", 0, unparsed_end, 1) == "data cap reached", "an unparsed period end must show plain data cap reached")


def check_data_fields():
    """checking, unavailable, a later failure keeping the last value, the
    Embed-not-enabled refusal clearing it, no cap for an unset limit even with a
    used count, and <used> of <limit>."""
    cap_state = CapState()
    expect(data_field_text(cap_state, True) == "checking", "data this month before a reading must be checking")
    expect(data_field_text(cap_state, False) == "checking", "data total before a reading must be checking")
    cap_state.record(None)
    expect(data_field_text(cap_state, True) == "unavailable", "a failed first reading must be unavailable")
    cap_state.record(None)
    expect(data_field_text(cap_state, False) == "unavailable", "a second failure before any success stays unavailable")
    cap_state.record(cap_reading(monthly_byte_limit=2000, monthly_used_byte_count=1250, total_byte_limit=None, total_used_byte_count=999))
    expect(data_field_text(cap_state, True) == "1.2 kB of 2.0 kB", "a reading must show <used> of <limit>")
    expect(data_field_text(cap_state, False) == "no cap", "an unset cap shows no cap, never its used count")
    cap_state.record(None)
    expect(data_field_text(cap_state, True) == "1.2 kB of 2.0 kB", "a later failure must keep the last value")
    cap_state.record(EMBED_NOT_ENABLED)
    expect(
        cap_state.reading is None and data_field_text(cap_state, True) == "unavailable" and data_field_text(cap_state, False) == "unavailable",
        "the Embed-not-enabled refusal must clear the last reading",
    )


def check_cap_parsing():
    """Null or absent limits, capped and capped_reason, an unknown reason read as
    capped without a reset time, and objects that are not cap objects."""
    minimal = parse_cap_object({"client_id": TEST_CLIENT_ID})
    expect(minimal is not None, "a cap object with only client_id must parse")
    expect(minimal.monthly_byte_limit is None and minimal.total_byte_limit is None, "absent limits must be no cap")
    expect(minimal.monthly_used_byte_count == 0 and not minimal.capped and minimal.capped_reason == "", "absent counts and flags must default")
    nulls = parse_cap_object({"client_id": TEST_CLIENT_ID, "monthly_byte_limit": None, "total_byte_limit": 5, "capped": None})
    expect(nulls is not None and nulls.monthly_byte_limit is None and nulls.total_byte_limit == 5, "a null limit must be no cap")
    capped = parse_cap_answer(
        json.dumps(
            {
                "client_id": TEST_CLIENT_ID,
                "monthly_byte_limit": 10,
                "monthly_used_byte_count": 10,
                "monthly_period_start": "2026-10-01T00:00:00Z",
                "monthly_period_end": "2026-11-01T00:00:00Z",
                "total_byte_limit": None,
                "total_used_byte_count": 10,
                "total_period_start": "2026-09-15T00:00:00Z",
                "capped": True,
                "capped_reason": "monthly",
            }
        ).encode()
    )
    expect(capped is not None and capped.capped and capped.capped_reason == "monthly", "capped and capped_reason must parse")
    unknown = parse_cap_object(
        {
            "client_id": TEST_CLIENT_ID,
            "monthly_byte_limit": 10,
            "monthly_period_end": "2026-11-01T00:00:00Z",
            "capped": True,
            "capped_reason": "weekly",
        }
    )
    expect(unknown is not None, "an unknown capped_reason must still parse")
    expect(embed_status(True, False, "", 0, unknown, 1) == "data cap reached", "an unknown capped_reason must read as capped without a reset time")
    for invalid in (
        {"error": {"message": "no permission"}},
        {"client_id": TEST_CLIENT_ID, "monthly_byte_limit": "5"},
        {"client_id": TEST_CLIENT_ID, "monthly_byte_limit": -1},
        {"client_id": TEST_CLIENT_ID, "total_byte_limit": True},
        {"client_id": TEST_CLIENT_ID, "capped": "yes"},
        {"client_id": TEST_CLIENT_ID, "capped_reason": 3},
        [TEST_CLIENT_ID],
        None,
    ):
        expect(parse_cap_object(invalid) is None and not is_embed_not_enabled(invalid), f"{invalid!r} must not parse as a cap object")
    # the Embed-not-enabled refusal is no cap object, and is recognized
    refusal = {"error": {"message": "Embed isn't enabled for this network."}}
    expect(parse_cap_object(refusal) is None and is_embed_not_enabled(refusal), "the Embed-not-enabled refusal must be recognized")
    expect(read_own_caps(TEST_API, TEST_CLIENT_JWT, StandInServer((200, refusal))) is EMBED_NOT_ENABLED, "the cap read must mark the Embed-not-enabled refusal")
    expect(read_own_caps(TEST_API, TEST_CLIENT_JWT, StandInServer((200, {"error": {"message": "no permission"}}))) is None, "another refusal must be a failed read")
    expect(parse_cap_answer(b"not json") is None, "an answer that is not JSON must not parse")
    # the app's own read: a 404 from a server without the cap routes is a failure
    expect(read_own_caps(TEST_API, TEST_CLIENT_JWT, StandInServer((404, b"not found"))) is None, "a 404 must be a failed read")
    expect(read_own_caps(TEST_API, TEST_CLIENT_JWT, StandInServer(OSError("unreachable"))) is None, "an unreachable API must be a failed read")
    server = StandInServer((200, {"client_id": TEST_CLIENT_ID, "monthly_byte_limit": 7}))
    reading = read_own_caps(TEST_API, TEST_CLIENT_JWT, server)
    expect(reading is not None and reading.monthly_byte_limit == 7, "a cap answer must be read")
    request = server.requests[0]
    expect(request["method"] == "GET" and request["url"] == TEST_API + "/network/client-data-cap", "the cap read must GET /network/client-data-cap")
    expect(request["headers"].get("Authorization") == "Bearer " + TEST_CLIENT_JWT, "the cap read must use the client JWT")


def check_sdk_status_json():
    """The client limit status and the window status in the C ABI's JSON."""
    expect(parse_client_limit_status(None) == (CLIENT_LIMIT_STATUS_NONE, 0), "NULL must read as no client limit")
    expect(
        parse_client_limit_status(f'{{"Status":"client_limit_exceeded","RetryTime":{TEST_RETRY_TIME}}}')
        == (CLIENT_LIMIT_STATUS_EXCEEDED, TEST_RETRY_TIME),
        "the client limit status must parse",
    )
    expect(parse_client_limit_status("not json") == (CLIENT_LIMIT_STATUS_NONE, 0), "invalid JSON must read as no client limit")
    expect(parse_providers_added(None) == 0, "no window yet must be no providers")
    expect(parse_providers_added(b'{"ProviderStateAdded":2,"TargetSize":4}') == 2, "ProviderStateAdded must parse")
    expect(parse_providers_added('{"ProviderStateAdded":true}') == 0, "a non-integer ProviderStateAdded must be 0")


def check_client_jwt_claims():
    """A client JWT is accepted; a network JWT, a malformed token and an invalid
    UUID are refused."""
    expect(parse_client_jwt_client_id(TEST_CLIENT_JWT) == TEST_CLIENT_ID, "a client JWT must be accepted")
    expect(
        parse_client_jwt_client_id(self_test_jwt(f'{{"client_id":"{TEST_CLIENT_ID.upper()}"}}')) == TEST_CLIENT_ID,
        "a client_id claim must be read in canonical form",
    )
    refused = [
        self_test_jwt('{"network_id":"44444444-4444-4444-4444-444444444444"}'),
        self_test_jwt('{"client_id":"not-a-uuid"}'),
        self_test_jwt('{"client_id":""}'),
        self_test_jwt('"a string"'),
        "not-a-jwt",
        "a.b",
        "a..c",
        "a.!!!.c",
    ]
    for client_jwt in refused:
        expect_raises(ConfigError, lambda: parse_client_jwt_client_id(client_jwt), f"{client_jwt!r} must be refused")


def check_origins():
    """The token server and API URLs are HTTPS origins, or explicit loopback HTTP."""
    accepted = [
        ("https://tokens.example.com", "https://tokens.example.com"),
        ("https://tokens.example.com/", "https://tokens.example.com"),
        ("https://tokens.example.com:8443", "https://tokens.example.com:8443"),
        ("http://127.0.0.1:8790", "http://127.0.0.1:8790"),
        ("http://localhost:8790", "http://localhost:8790"),
        ("http://[::1]:8790", "http://[::1]:8790"),
    ]
    for url, origin in accepted:
        expect(check_origin(url, "TEST") == origin, f"{url!r} must be accepted as {origin!r}")
    refused = [
        "",
        "http://example.com",
        "https://example.com/path",
        "https://user:password@example.com",
        "https://example.com?query=1",
        "https://example.com?",
        "https://example.com#fragment",
        "ftp://example.com",
        "https://example.com:notaport",
    ]
    for url in refused:
        expect_raises(ConfigError, lambda: check_origin(url, "TEST"), f"{url!r} must be refused")


def check_token_fetch():
    """The token fetch against a stand-in token server: the request, saving
    client.jwt atomically, the client_id claim check, and the answers mapped to
    the exit codes."""
    with private_dir() as state_dir:
        server = StandInServer((200, token_answer()))
        fetched = fetch_client_jwt(state_dir, TEST_INSTANCE_ID, TEST_TOKEN_SERVER, TEST_SESSION, server)
        request = server.requests[0]
        expect(request["method"] == "POST", "the token fetch must POST")
        expect(request["url"] == TEST_TOKEN_SERVER + TOKEN_ROUTE, "the token fetch must post to /urnetwork/client-token")
        expect(request["headers"].get("Authorization") == "Bearer " + TEST_SESSION, "the demo session must be the bearer token")
        expect(json.loads(request["body"]) == {"installation_id": TEST_INSTANCE_ID}, "installation_id must equal the instance-id")
        expect(fetched.client_id == TEST_CLIENT_ID and fetched.client_jwt == TEST_CLIENT_JWT, "the answer must be returned")
        expect(fetched.data_cap is not None and fetched.data_cap.monthly_byte_limit == 10000000000, "data_cap must be the first reading")
        saved = read_private_file(os.path.join(state_dir, CLIENT_JWT_FILE_NAME))
        expect(saved == (TEST_CLIENT_JWT + "\n").encode(), "client.jwt must hold the fetched token")
        if POSIX:
            expect(os.stat(os.path.join(state_dir, CLIENT_JWT_FILE_NAME)).st_mode & 0o777 == 0o600, "client.jwt must be 0600")
        expect(sorted(os.listdir(state_dir)) == [CLIENT_JWT_FILE_NAME], "the atomic write must leave no temporary file")

        # data_cap null, from a token server that could not read the caps
        fetched = fetch_client_jwt(state_dir, TEST_INSTANCE_ID, TEST_TOKEN_SERVER, TEST_SESSION, StandInServer((200, token_answer(data_cap=None))))
        expect(fetched.data_cap is None, "a null data_cap must leave no first reading")

        # a client_id that does not match the claim is refused, and client.jwt is kept
        mismatch = StandInServer((200, token_answer(client_id=TEST_OTHER_CLIENT_ID)))
        expect_raises(
            TokenServerError,
            lambda: fetch_client_jwt(state_dir, TEST_INSTANCE_ID, TEST_TOKEN_SERVER, TEST_SESSION, mismatch),
            "a client_id that does not match the claim must be refused",
        )
        expect(read_private_file(os.path.join(state_dir, CLIENT_JWT_FILE_NAME)) == (TEST_CLIENT_JWT + "\n").encode(), "a refused answer must not replace client.jwt")
        network_jwt = self_test_jwt('{"network_id":"44444444-4444-4444-4444-444444444444"}')
        for refused_answer, error_type in [
            ((401, {"error": {"code": "unauthorized", "message": "unknown session"}}), TokenServerRefused),
            ((409, {"error": {"code": "installation_limit", "message": "too many installations"}}), TokenServerRefused),
            ((409, {"error": {"code": "client_limit", "message": "client limit; see https://ur.io/services"}}), TokenServerRefused),
            ((503, {"error": {"code": "busy", "message": "retry"}}), TokenServerError),
            ((502, {"error": {"code": "upstream", "message": "upstream failed"}}), TokenServerError),
            ((500, b"internal error"), TokenServerError),
            ((200, b"not json"), TokenServerError),
            ((200, {"client_id": TEST_CLIENT_ID}), TokenServerError),
            ((200, token_answer(client_jwt=network_jwt)), TokenServerError),
            (OSError("connection refused"), TokenServerError),
        ]:
            answers = StandInServer(refused_answer)
            expect_raises(
                error_type,
                lambda: fetch_client_jwt(state_dir, TEST_INSTANCE_ID, TEST_TOKEN_SERVER, TEST_SESSION, answers),
                f"the answer {refused_answer!r} must raise {error_type.__name__}",
            )
        # the server's message is passed on
        try:
            fetch_client_jwt(state_dir, TEST_INSTANCE_ID, TEST_TOKEN_SERVER, TEST_SESSION, StandInServer((401, {"error": {"code": "unauthorized", "message": "unknown session"}})))
        except TokenServerRefused as error:
            expect(str(error) == "unknown session", "the token server's error message must be shown")

    # the run command maps the answers to the exit codes, and never prints the session
    from main import EXIT_CONFIG, EXIT_FAILURE, run

    for answer, exit_code in [
        ((401, {"error": {"code": "unauthorized", "message": "unknown session"}}), EXIT_CONFIG),
        ((409, {"error": {"code": "client_limit", "message": "client limit"}}), EXIT_CONFIG),
        ((503, {"error": {"code": "busy", "message": "retry"}}), EXIT_FAILURE),
        (OSError("connection refused"), EXIT_FAILURE),
    ]:
        with private_dir() as state_dir:
            output = io.StringIO()
            with embed_environment(
                URNETWORK_EMBED_STATE_DIR=state_dir,
                URNETWORK_TOKEN_SERVER_URL=TEST_TOKEN_SERVER,
                URNETWORK_DEMO_SESSION=TEST_SESSION,
            ), contextlib.redirect_stdout(output), contextlib.redirect_stderr(output):
                actual = run(["run"], transport=StandInServer(answer))
            expect(actual == exit_code, f"the answer {answer!r} must exit {exit_code}, got {actual}")
            expect(TEST_SESSION not in output.getvalue(), "the demo session must never be printed")


def check_state_files():
    """Private permissions on POSIX, atomic replacement, instance-id created once
    and reused, and symlinked or oversized files refused."""
    with private_dir() as state_dir:
        check_state_dir(state_dir)
        path = os.path.join(state_dir, CLIENT_JWT_FILE_NAME)
        expect_raises(FileNotFoundError, lambda: read_private_file(path), "a missing file must raise FileNotFoundError")
        expect(load_client_jwt(state_dir) is None, "a missing client.jwt must load as None")
        write_private_file(path, b"first\n")
        write_private_file(path, b"  second  \n")
        expect(read_private_file(path) == b"  second  \n", "the second write must replace the first")
        expect(load_client_jwt(state_dir) == "second", "client.jwt must load without surrounding whitespace")
        expect(sorted(os.listdir(state_dir)) == [CLIENT_JWT_FILE_NAME], "the atomic writes must leave no temporary file")

        instance_id = load_or_create_instance_id(state_dir)
        expect(load_or_create_instance_id(state_dir) == instance_id, "instance-id must be created once and reused")
        write_private_file(os.path.join(state_dir, INSTANCE_ID_FILE_NAME), b"not a uuid\n")
        expect_raises(ConfigError, lambda: load_or_create_instance_id(state_dir), "an invalid instance-id must be refused")

        large = os.path.join(state_dir, "large")
        write_private_file(large, b"x" * (STATE_FILE_BYTE_LIMIT + 1))
        expect_raises(StateFileError, lambda: read_private_file(large), "an oversized state file must be refused")

        if POSIX:
            write_private_file(path, b"token\n")
            os.chmod(path, 0o644)
            expect_raises(StateFileError, lambda: read_private_file(path), "a file readable by others must be refused")
            expect_raises(ConfigError, lambda: load_client_jwt(state_dir), "an unprotected client.jwt must be a configuration error")
            os.chmod(path, 0o600)
            os.remove(path)
            target = os.path.join(state_dir, "target")
            write_private_file(target, b"token\n")
            os.symlink(target, path)
            expect_raises(StateFileError, lambda: read_private_file(path), "a symlinked state file must be refused")
            os.chmod(state_dir, 0o755)
            expect_raises(ConfigError, lambda: check_state_dir(state_dir), "a state directory others can read must be refused")
            os.chmod(state_dir, 0o700)

    expect_raises(ConfigError, lambda: check_state_dir(""), "a missing state directory must be refused")
    expect_raises(ConfigError, lambda: check_state_dir(os.path.join("relative", "state")), "a relative state directory must be refused")
    with private_dir() as parent:
        expect_raises(ConfigError, lambda: check_state_dir(os.path.join(parent, "missing")), "a nonexistent state directory must be refused")


def check_config_errors():
    """The configuration errors exit with 78 before any SDK call, and so does a
    usage error."""
    from main import EXIT_CONFIG, run

    def run_quietly(args, **settings) -> int:
        with embed_environment(**settings), contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            return run(args, transport=StandInServer())

    expect(run_quietly(["run"]) == EXIT_CONFIG, "a missing state directory must exit 78")
    expect(run_quietly(["run"], URNETWORK_EMBED_STATE_DIR=os.path.join("relative", "state")) == EXIT_CONFIG, "a relative state directory must exit 78")
    with private_dir() as state_dir:
        expect(run_quietly([], URNETWORK_EMBED_STATE_DIR=state_dir) == EXIT_CONFIG, "no token server and no client.jwt must exit 78")
        expect(
            run_quietly(["run"], URNETWORK_EMBED_STATE_DIR=state_dir, URNETWORK_TOKEN_SERVER_URL=TEST_TOKEN_SERVER) == EXIT_CONFIG,
            "a token server URL without a demo session must exit 78",
        )
        expect(
            run_quietly(
                ["run"],
                URNETWORK_EMBED_STATE_DIR=state_dir,
                URNETWORK_TOKEN_SERVER_URL="http://example.com",
                URNETWORK_DEMO_SESSION=TEST_SESSION,
            )
            == EXIT_CONFIG,
            "a token server URL that is not HTTPS must exit 78",
        )
        expect(
            run_quietly(["run"], URNETWORK_EMBED_STATE_DIR=state_dir, URNETWORK_API_URL="https://example.com/path") == EXIT_CONFIG,
            "an invalid API URL must exit 78",
        )
        write_private_file(
            os.path.join(state_dir, CLIENT_JWT_FILE_NAME),
            (self_test_jwt('{"network_id":"44444444-4444-4444-4444-444444444444"}') + "\n").encode(),
        )
        expect(run_quietly(["run"], URNETWORK_EMBED_STATE_DIR=state_dir) == EXIT_CONFIG, "a network JWT in client.jwt must exit 78")
    expect(run_quietly(["bogus"]) == EXIT_CONFIG, "an unknown command must exit 78")
    expect(run_quietly(["run", "extra"]) == EXIT_CONFIG, "extra arguments must exit 78")


def check_start_line():
    """The start line and the kind of app whose licenses --licenses prints."""
    from main import USAGE

    expect(
        start_line(TEST_CLIENT_ID, TEST_INSTANCE_ID) == f"embed client {TEST_CLIENT_ID}, installation {TEST_INSTANCE_ID}",
        "the start line must be the contract's",
    )
    expect(
        [license_app(system) for system in ("Darwin", "Windows", "Linux", "FreeBSD", "")] == ["apple", "windows", "linux", "linux", "linux"],
        "--licenses must ask for the platform's kind of app",
    )
    expect("--licenses" in USAGE, "the usage must name --licenses")


# a urnetwork package whose bindings name a function that the native library
# lacks, as when the library is older than the package: ctypes raises its own
# AttributeError on every platform
STALE_PACKAGE = """
import ctypes
import os

_library = ctypes.WinDLL("kernel32") if os.name == "nt" else ctypes.CDLL(None)
_library.urnet_self_test_newer_function.restype = ctypes.c_void_p
"""

# a urnetwork package with the two C ABI functions that --licenses calls;
# SELF_TEST_LICENSES "null" answers NULL and "missing" is a library without
# urnet_get_licenses
LICENSES_PACKAGE = """
import ctypes
import json
import os

_strings = []


class _Raw:
    def urnet_get_licenses(self, app):
        mode = os.environ.get("SELF_TEST_LICENSES", "")
        if mode == "missing":
            library = ctypes.WinDLL("kernel32") if os.name == "nt" else ctypes.CDLL(None)
            return library.urnet_get_licenses(app)
        if mode == "null":
            return None
        text = ctypes.create_string_buffer(json.dumps([{"app": app.decode()}]).encode())
        _strings.append(text)
        return ctypes.addressof(text)

    def urnet_free_string(self, pointer):
        pass


raw = _Raw()


def version():
    return "self-test"
"""


def run_main(package_source: str, args: list, **settings):
    """Runs main.py in a child process with a stand-in urnetwork package first on
    the import path; returns the exit code, stdout and stderr."""
    with private_dir() as package_root:
        os.mkdir(os.path.join(package_root, "urnetwork"))
        with open(os.path.join(package_root, "urnetwork", "__init__.py"), "w") as file:
            file.write(package_source)
        environment = {name: value for name, value in os.environ.items() if not name.startswith("URNETWORK_")}
        environment.update(settings, PYTHONPATH=package_root, PYTHONDONTWRITEBYTECODE="1")
        main_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "main.py")
        completed = subprocess.run([sys.executable, main_path, *args], env=environment, capture_output=True, text=True, timeout=60)
        return completed.returncode, completed.stdout, completed.stderr


def check_sdk_mismatch():
    """A native library older than the urnetwork package exits 78 with the SDK
    version mismatch line, not a traceback, from every command that loads the
    package; other load failures exit 1."""
    library = C.WinDLL("kernel32") if os.name == "nt" else C.CDLL(None)
    try:
        library.urnet_self_test_newer_function
    except AttributeError as error:
        missing = error
    else:
        raise SelfTestError("the platform library has the self-test's function")
    message = sdk_mismatch_message(missing)
    expect(message is not None and "SDK version mismatch" in message and "urnet_self_test_newer_function" in message, f"a missing C ABI function must be a version mismatch: {missing}")
    expect(sdk_mismatch_message(AttributeError("'NoneType' object has no attribute 'raw'")) is None, "another AttributeError is not a version mismatch")
    expect(sdk_mismatch_message(OSError("dlopen failed: urnet_x")) is None, "a library that does not load is not a version mismatch")

    def raising(error):
        def importer(_name):
            raise error

        return importer

    for error, exit_code in [(missing, SDK_EXIT_CONFIG), (ImportError("No module named 'urnetwork'"), 1), (OSError("no library"), 1)]:
        try:
            load_urnetwork(raising(error))
        except SdkLoadError as load_error:
            expect(load_error.exit_code == exit_code, f"{error!r} must exit {exit_code}, got {load_error.exit_code}")
        else:
            raise SelfTestError(f"{error!r} must not load")

    with private_dir() as state_dir:
        write_private_file(os.path.join(state_dir, CLIENT_JWT_FILE_NAME), (TEST_CLIENT_JWT + "\n").encode())
        for args, settings in [(["--version"], {}), (["--licenses"], {}), (["run"], {"URNETWORK_EMBED_STATE_DIR": state_dir})]:
            code, out, err = run_main(STALE_PACKAGE, args, **settings)
            expect(code == SDK_EXIT_CONFIG, f"{args} with a stale native library must exit 78, got {code}: {err}")
            expect(err.strip().startswith("SDK version mismatch") and len(err.strip().splitlines()) == 1 and "Traceback" not in err, f"{args} must print the one mismatch line: {err}")
            expect("urnet_self_test_newer_function" in err and out == "", f"{args} must name the missing function and print nothing else")

    code, out, err = run_main(LICENSES_PACKAGE, ["--licenses"])
    expect(code == 0 and json.loads(out) == [{"app": license_app(platform.system())}], f"--licenses must print the sdk's JSON for this kind of app: {code} {out} {err}")
    code, _, err = run_main(LICENSES_PACKAGE, ["--licenses"], SELF_TEST_LICENSES="null")
    expect(code == 1 and err.strip() == "the sdk returned no licenses", f"no licenses must exit 1: {code} {err}")
    code, _, err = run_main(LICENSES_PACKAGE, ["--licenses"], SELF_TEST_LICENSES="missing")
    expect(code == SDK_EXIT_CONFIG and err.startswith("SDK version mismatch") and "urnet_get_licenses" in err, f"a library without urnet_get_licenses must exit 78: {code} {err}")


CHECKS = [
    check_byte_vectors,
    check_reset_vectors,
    check_client_limit_vectors,
    check_status_lines,
    check_status_rules,
    check_data_fields,
    check_cap_parsing,
    check_sdk_status_json,
    check_client_jwt_claims,
    check_origins,
    check_token_fetch,
    check_state_files,
    check_config_errors,
    check_start_line,
    check_sdk_mismatch,
]
