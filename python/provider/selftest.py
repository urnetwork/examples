"""The credential-free self-test (PROVIDER_CONTRACT.md, "Self-test"). It checks
the disclaimer, the status text, the providing state, the clients-served count,
the installation state files and the usage exit code without a network,
credentials, a device or the native SDK. `main.py --self-test` runs every
check; test_provider.py runs each one as a test."""

import base64
import contextlib
import hashlib
import io
import json
import os
import shutil
import tempfile

from state import (
    CLIENT_JWT_FILE_NAME,
    IDENTITY_FILE_NAME,
    POSIX,
    ConfigError,
    ProviderIdentity,
    StateFileError,
    check_state_dir,
    load_or_create_instance_id,
    load_provider_config,
    load_provider_identity,
    parse_client_jwt_client_id,
    parse_uuid,
    provider_key_material,
    read_private_file,
    save_provider_identity,
    write_private_file,
)
from status import (
    CLIENT_LIMIT_STATUS_EXCEEDED,
    CLIENT_LIMIT_STATUS_NONE,
    CLIENTS_SERVED_LIMIT,
    CONSENT_DISCLAIMER,
    PAYOUT_WALLET_CHECKING,
    PAYOUT_WALLET_NOT_SET,
    PAYOUT_WALLET_SCOPE_ANOTHER_PROVIDER,
    PAYOUT_WALLET_SCOPE_HOTKEY,
    PAYOUT_WALLET_SCOPE_NETWORK,
    PAYOUT_WALLET_SCOPE_PROVIDER,
    PROVIDE_MODE_NETWORK,
    PROVIDE_MODE_NONE,
    PROVIDE_MODE_PUBLIC,
    PROVIDER_STATE_CLIENT_LIMIT,
    PROVIDER_STATE_PAUSED,
    PROVIDER_STATE_PROVIDING,
    PROVIDER_STATE_STARTING,
    PROVIDER_STATE_STOPPED,
    SN_WALLET_CONSENT_SCOPE_HOTKEY,
    SN_WALLET_CONSENT_SCOPE_NETWORK,
    SN_WALLET_CONSENT_SCOPE_PROVIDER,
    ClientsServed,
    ProviderStatus,
    contract_peer_key,
    format_byte_count,
    parse_client_limit_status,
    parse_data_provided_byte_count,
    parse_json_object,
    payout_wallet_scope,
    provider_state,
    provider_status_text,
)

# sha-256 of the consent disclaimer (utf-8, lf line breaks, no trailing
# newline), published in PROVIDER_CONTRACT.md for every example to check
CONSENT_DISCLAIMER_SHA256 = "83edee1e45cccd5deb6b86755cc5f6a91b6ade26e7eefc1e7670b5833a95502c"

# the public substrate development account, test data only
TEST_WALLET = "5GrwvaEF5zXb26Fz9rcQpDWS57CtERHpNehXCPcNoHGKutQY"

TEST_PROVIDER_ID = "11111111-1111-1111-1111-111111111111"
TEST_CLIENT_A_ID = "22222222-2222-2222-2222-222222222222"
TEST_CLIENT_B_ID = "33333333-3333-3333-3333-333333333333"
TEST_STREAM_ID = "44444444-4444-4444-4444-444444444444"
TEST_ZERO_ID = "00000000-0000-0000-0000-000000000000"


class SelfTestError(Exception):
    """A self-test check that failed."""


def run_self_test():
    """Runs every check and raises the first failure."""
    checks = [
        check_consent_disclaimer,
        check_format_byte_count,
        check_status_text,
        check_status_lines,
        check_status_key,
        check_provider_state,
        check_payout_wallet_scope,
        check_clients_served,
        check_sdk_status_json,
        check_client_jwt_claims,
        check_state_files,
        check_provider_config,
        check_usage_exit_code,
    ]
    for check in checks:
        check()


def check_consent_disclaimer():
    """The disclaimer is the contract's exact text."""
    if hashlib.sha256(CONSENT_DISCLAIMER.encode("utf-8")).hexdigest() != CONSENT_DISCLAIMER_SHA256:
        raise SelfTestError("consent disclaimer differs from PROVIDER_CONTRACT.md")


def check_format_byte_count():
    """Byte counts use binary units with one decimal."""
    cases = [
        (0, "0 B"),
        (1023, "1023 B"),
        (1024, "1.0 KiB"),
        (1536, "1.5 KiB"),
        (1048575, "1.0 MiB"),
        (13002342, "12.4 MiB"),
        (5 * 1024 * 1024 * 1024, "5.0 GiB"),
        (3 * 1024 * 1024 * 1024 * 1024, "3.0 TiB"),
    ]
    for byte_count, text in cases:
        if format_byte_count(byte_count) != text:
            raise SelfTestError(f"byte count {byte_count} formats as {format_byte_count(byte_count)!r}, want {text!r}")


def check_status_text():
    """The client limit status names the SDK's retry time in UTC, rounded up to the
    next whole minute; other states never show it."""
    cases = [
        # 2026-10-06 19:05:00.000 UTC, exactly on a minute
        (PROVIDER_STATE_CLIENT_LIMIT, 1791313500000, "client limit, retry at 19:05 UTC"),
        # 19:04:00.001 rounds up, so the shown time is never before the retry
        (PROVIDER_STATE_CLIENT_LIMIT, 1791313440001, "client limit, retry at 19:05 UTC"),
        # no retry time
        (PROVIDER_STATE_CLIENT_LIMIT, 0, "client limit"),
        # 23:59:00.001 rolls over the hour and the day
        (PROVIDER_STATE_CLIENT_LIMIT, 1791331140001, "client limit, retry at 00:00 UTC"),
        (PROVIDER_STATE_STARTING, 1791313500000, "starting"),
    ]
    for state, client_limit_retry_time, text in cases:
        if provider_status_text(state, client_limit_retry_time) != text:
            raise SelfTestError(
                f"status text {provider_status_text(state, client_limit_retry_time)!r} for {state!r} retrying at {client_limit_retry_time}, want {text!r}"
            )


def check_status_lines():
    """The status line matches the contract's golden lines."""
    cases = [
        (
            ProviderStatus(state=PROVIDER_STATE_STARTING, payout_wallet=PAYOUT_WALLET_CHECKING),
            "status: starting | clients served: 0 | data provided: 0 B | payout wallet: checking",
        ),
        (
            ProviderStatus(
                state=PROVIDER_STATE_PROVIDING,
                clients_served=3,
                data_provided_byte_count=13002342,
                payout_wallet=TEST_WALLET,
                payout_wallet_scope=PAYOUT_WALLET_SCOPE_NETWORK,
            ),
            f"status: providing | clients served: 3 | data provided: 12.4 MiB | payout wallet: {TEST_WALLET} (network)",
        ),
        (
            ProviderStatus(
                state=PROVIDER_STATE_PROVIDING,
                clients_served=3,
                data_provided_byte_count=13002342,
                payout_wallet=TEST_WALLET,
                payout_wallet_scope=PAYOUT_WALLET_SCOPE_HOTKEY,
            ),
            f"status: providing | clients served: 3 | data provided: 12.4 MiB | payout wallet: {TEST_WALLET} (hotkey)",
        ),
        (
            ProviderStatus(
                state=PROVIDER_STATE_PAUSED,
                clients_served=CLIENTS_SERVED_LIMIT,
                clients_served_at_limit=True,
                data_provided_byte_count=1536,
                payout_wallet=TEST_WALLET,
                payout_wallet_scope=PAYOUT_WALLET_SCOPE_PROVIDER,
            ),
            f"status: paused | clients served: 100000+ | data provided: 1.5 KiB | payout wallet: {TEST_WALLET} (this provider)",
        ),
        (
            ProviderStatus(state=PROVIDER_STATE_STOPPED, payout_wallet=PAYOUT_WALLET_NOT_SET),
            "status: stopped | clients served: 0 | data provided: 0 B | payout wallet: not set",
        ),
        (
            ProviderStatus(
                state=PROVIDER_STATE_CLIENT_LIMIT,
                client_limit_retry_time=1791313500000,
                payout_wallet=TEST_WALLET,
                payout_wallet_scope=PAYOUT_WALLET_SCOPE_NETWORK,
            ),
            f"status: client limit, retry at 19:05 UTC | clients served: 0 | data provided: 0 B | payout wallet: {TEST_WALLET} (network)",
        ),
    ]
    for status, line in cases:
        if status.line() != line:
            raise SelfTestError(f"status line {status.line()!r}, want {line!r}")


def check_status_key():
    """A change of the status text prints a line at once, including a new client
    limit retry time; the data counter alone does not."""
    status = ProviderStatus(
        state=PROVIDER_STATE_CLIENT_LIMIT,
        client_limit_retry_time=1791313500000,
        payout_wallet=PAYOUT_WALLET_CHECKING,
    )
    # 19:25 UTC
    retried = ProviderStatus(
        state=PROVIDER_STATE_CLIENT_LIMIT,
        client_limit_retry_time=1791314700000,
        payout_wallet=PAYOUT_WALLET_CHECKING,
    )
    if status.key() == retried.key():
        raise SelfTestError("a new client limit retry time does not print a status line")
    counted = ProviderStatus(
        state=PROVIDER_STATE_CLIENT_LIMIT,
        client_limit_retry_time=1791313500000,
        data_provided_byte_count=1536,
        payout_wallet=PAYOUT_WALLET_CHECKING,
    )
    if status.key() != counted.key():
        raise SelfTestError("the data counter alone prints a status line")


def check_provider_state():
    """The providing state follows the provide mode, client limit, pause, enable and
    connected rules, in that order."""
    # (provide mode, client limit status, paused, enabled, connected, state)
    cases = [
        (PROVIDE_MODE_NONE, CLIENT_LIMIT_STATUS_NONE, False, False, False, PROVIDER_STATE_STOPPED),
        (PROVIDE_MODE_NETWORK, CLIENT_LIMIT_STATUS_NONE, False, True, True, PROVIDER_STATE_STOPPED),
        (PROVIDE_MODE_PUBLIC, CLIENT_LIMIT_STATUS_NONE, False, True, False, PROVIDER_STATE_STARTING),
        (PROVIDE_MODE_PUBLIC, CLIENT_LIMIT_STATUS_NONE, False, False, True, PROVIDER_STATE_STARTING),
        (PROVIDE_MODE_PUBLIC, CLIENT_LIMIT_STATUS_NONE, False, True, True, PROVIDER_STATE_PROVIDING),
        (PROVIDE_MODE_PUBLIC, CLIENT_LIMIT_STATUS_NONE, True, True, True, PROVIDER_STATE_PAUSED),
        # the client limit comes after stopped and before every other state
        (PROVIDE_MODE_NETWORK, CLIENT_LIMIT_STATUS_EXCEEDED, False, True, True, PROVIDER_STATE_STOPPED),
        (PROVIDE_MODE_PUBLIC, CLIENT_LIMIT_STATUS_EXCEEDED, True, True, True, PROVIDER_STATE_CLIENT_LIMIT),
        (PROVIDE_MODE_PUBLIC, CLIENT_LIMIT_STATUS_EXCEEDED, False, False, False, PROVIDER_STATE_CLIENT_LIMIT),
        (PROVIDE_MODE_PUBLIC, CLIENT_LIMIT_STATUS_NONE, True, True, True, PROVIDER_STATE_PAUSED),
    ]
    for provide_mode, client_limit_status, paused, enabled, connected, state in cases:
        if provider_state(provide_mode, client_limit_status, paused, enabled, connected) != state:
            raise SelfTestError(
                f"provider state {provider_state(provide_mode, client_limit_status, paused, enabled, connected)!r} for mode {provide_mode}, client limit {client_limit_status!r}, paused {paused}, enabled {enabled}, connected {connected}; want {state!r}"
            )


def check_payout_wallet_scope():
    """The payout wallet is labeled by its consent scope first, then by the owner of
    its mapping."""
    cases = [
        # a hotkey delegation is network-level but not the network's wallet
        (SN_WALLET_CONSENT_SCOPE_HOTKEY, "", PAYOUT_WALLET_SCOPE_HOTKEY),
        (SN_WALLET_CONSENT_SCOPE_HOTKEY, TEST_PROVIDER_ID, PAYOUT_WALLET_SCOPE_HOTKEY),
        (SN_WALLET_CONSENT_SCOPE_NETWORK, "", PAYOUT_WALLET_SCOPE_NETWORK),
        ("", "", PAYOUT_WALLET_SCOPE_NETWORK),
        (SN_WALLET_CONSENT_SCOPE_PROVIDER, TEST_PROVIDER_ID, PAYOUT_WALLET_SCOPE_PROVIDER),
        ("", TEST_PROVIDER_ID, PAYOUT_WALLET_SCOPE_PROVIDER),
        (SN_WALLET_CONSENT_SCOPE_PROVIDER, TEST_CLIENT_A_ID, PAYOUT_WALLET_SCOPE_ANOTHER_PROVIDER),
    ]
    for wallet_consent_scope, wallet_client_id, scope in cases:
        if payout_wallet_scope(wallet_consent_scope, wallet_client_id, TEST_PROVIDER_ID) != scope:
            raise SelfTestError(
                f"payout wallet scope {payout_wallet_scope(wallet_consent_scope, wallet_client_id, TEST_PROVIDER_ID)!r} for consent scope {wallet_consent_scope!r} and client {wallet_client_id!r}, want {scope!r}"
            )


def contract_json(contract_id: str, source_id, destination_id, stream_id) -> str:
    """A provider contract as the C ABI's contract details listeners deliver it."""
    return json.dumps(
        {
            "ContractId": contract_id,
            "ContractUsedByteCount": 0,
            "ContractByteCount": 0,
            "ContractBitRate": 0,
            "ContractTransferPath": {
                "SourceId": source_id,
                "DestinationId": destination_id,
                "StreamId": stream_id,
            },
            "Status": "open",
        }
    )


def check_clients_served():
    """Contract peers resolve by direction and count once per client, up to the
    limit."""
    # the peer is the source of a receive contract and the destination of a send contract
    key_cases = [
        (contract_json("55555555-5555-5555-5555-555555555555", TEST_CLIENT_A_ID, TEST_PROVIDER_ID, None), True, TEST_CLIENT_A_ID),
        (contract_json("66666666-6666-6666-6666-666666666666", TEST_PROVIDER_ID, TEST_CLIENT_A_ID, None), False, TEST_CLIENT_A_ID),
        (contract_json("77777777-7777-7777-7777-777777777777", TEST_ZERO_ID, TEST_PROVIDER_ID, TEST_STREAM_ID), True, "stream:" + TEST_STREAM_ID),
        (contract_json("88888888-8888-8888-8888-888888888888", None, None, None), True, "contract:88888888-8888-8888-8888-888888888888"),
        ('{"ContractId": "88888888-8888-8888-8888-888888888888", "ContractTransferPath": null, "Status": "open"}', True, "contract:88888888-8888-8888-8888-888888888888"),
    ]
    for details_json, receive, peer_key in key_cases:
        if contract_peer_key(parse_json_object(details_json), receive) != peer_key:
            raise SelfTestError(f"contract peer key {contract_peer_key(parse_json_object(details_json), receive)!r}, want {peer_key!r}")

    # both directions of one client count once
    served = ClientsServed(2)
    served.add(parse_json_object(contract_json("55555555-5555-5555-5555-555555555555", TEST_CLIENT_A_ID, TEST_PROVIDER_ID, None)), True)
    served.add(parse_json_object(contract_json("66666666-6666-6666-6666-666666666666", TEST_PROVIDER_ID, TEST_CLIENT_A_ID, None)), False)
    served.add(parse_json_object(contract_json("99999999-9999-9999-9999-999999999999", TEST_CLIENT_A_ID, TEST_PROVIDER_ID, None)), True)
    if served.count() != (1, False):
        raise SelfTestError(f"one client counted as {served.count()}")
    served.add(parse_json_object(contract_json("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", TEST_CLIENT_B_ID, TEST_PROVIDER_ID, None)), True)
    if served.count() != (2, False):
        raise SelfTestError(f"two clients counted as {served.count()}")
    # a third distinct peer reaches the limit of 2
    served.add(parse_json_object(contract_json("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb", TEST_ZERO_ID, TEST_PROVIDER_ID, TEST_STREAM_ID)), True)
    if served.count() != (2, True):
        raise SelfTestError(f"limited count {served.count()}")
    # null details from the sdk count nothing
    served.add(parse_json_object("null"), True)
    if served.count() != (2, True):
        raise SelfTestError(f"null contract details changed the count to {served.count()}")


def check_sdk_status_json():
    """The client limit status and the provider packet stats read from the C ABI's
    JSON: NULL is no limit and null stats are 0 bytes."""
    client_limit_cases = [
        (None, (CLIENT_LIMIT_STATUS_NONE, 0)),
        (b'{"Status": "", "RetryTime": 0}', (CLIENT_LIMIT_STATUS_NONE, 0)),
        (b'{"Status": "client_limit_exceeded", "RetryTime": 1791313500000}', (CLIENT_LIMIT_STATUS_EXCEEDED, 1791313500000)),
    ]
    for status_json, client_limit in client_limit_cases:
        if parse_client_limit_status(status_json) != client_limit:
            raise SelfTestError(f"client limit status {parse_client_limit_status(status_json)!r} from {status_json!r}, want {client_limit!r}")
    packet_stats_cases = [
        (None, 0),
        (b"null", 0),
        (b'{"RemoteEgressByteCount": 5, "RemoteIngressByteCount": 7, "LocalEgressByteCount": 11}', 12),
    ]
    for packet_stats_json, byte_count in packet_stats_cases:
        if parse_data_provided_byte_count(packet_stats_json) != byte_count:
            raise SelfTestError(f"data provided {parse_data_provided_byte_count(packet_stats_json)} from {packet_stats_json!r}, want {byte_count}")


def self_test_jwt(payload_json: str) -> str:
    """A synthetic, unsigned JWT with the given payload JSON."""
    payload = base64.urlsafe_b64encode(payload_json.encode()).decode().rstrip("=")
    return f"e30.{payload}.test"


def check_client_jwt_claims():
    """Only a JWT with a valid client_id claim is a client credential."""
    client_id = parse_client_jwt_client_id(self_test_jwt(f'{{"client_id":"{TEST_PROVIDER_ID}","network_id":"{TEST_CLIENT_A_ID}"}}'))
    if client_id != TEST_PROVIDER_ID:
        raise SelfTestError(f"client jwt claim {client_id!r}")
    invalid_jwts = [
        "",
        "not-a-jwt",
        self_test_jwt(f'{{"network_id":"{TEST_CLIENT_A_ID}"}}'),
        self_test_jwt('{"client_id":"not-a-uuid"}'),
        "e30.%%%.test",
    ]
    for invalid_jwt in invalid_jwts:
        try:
            parse_client_jwt_client_id(invalid_jwt)
        except ConfigError:
            continue
        raise SelfTestError(f"invalid client jwt {invalid_jwt!r} accepted")


def expect_refused(check, message: str, *errors):
    """Runs check, which must raise one of errors (ConfigError by default)."""
    try:
        check()
    except errors or (ConfigError,):
        return
    raise SelfTestError(message)


def check_state_files():
    """State files are private, replaced atomically, created once and bound to
    their client."""
    state_dir = tempfile.mkdtemp(prefix="ur-provider-self-test-")
    try:
        if POSIX:
            os.chmod(state_dir, 0o700)

        # an atomic private write leaves no temporary file behind
        path = os.path.join(state_dir, CLIENT_JWT_FILE_NAME)
        write_private_file(path, b"first\n")
        write_private_file(path, b"second\n")
        if read_private_file(path) != b"second\n":
            raise SelfTestError(f"private file round trip {read_private_file(path)!r}")
        if os.listdir(state_dir) != [CLIENT_JWT_FILE_NAME]:
            raise SelfTestError(f"private writes left {os.listdir(state_dir)}")
        if POSIX:
            if os.stat(path).st_mode & 0o777 != 0o600:
                raise SelfTestError(f"private file mode {os.stat(path).st_mode & 0o777:o}")
            # a file or directory that others can read is refused
            os.chmod(path, 0o644)
            expect_refused(lambda: read_private_file(path), "a group-readable credential file was accepted", StateFileError)
            os.chmod(path, 0o600)
            os.chmod(state_dir, 0o755)
            expect_refused(lambda: check_state_dir(state_dir), "a group-readable state directory was accepted")
            os.chmod(state_dir, 0o700)
            # a symlink could redirect the credential to another file
            target = os.path.join(state_dir, "target")
            write_private_file(target, b"redirected\n")
            link = os.path.join(state_dir, "link.jwt")
            os.symlink(target, link)
            expect_refused(lambda: read_private_file(link), "a symlinked credential file was accepted", StateFileError)
        check_state_dir(state_dir)

        # the instance id is created once and reused
        instance_id = load_or_create_instance_id(state_dir)
        if parse_uuid(instance_id) != instance_id:
            raise SelfTestError(f"instance id {instance_id!r} is not a uuid")
        again = load_or_create_instance_id(state_dir)
        if again != instance_id:
            raise SelfTestError(f"instance id changed from {instance_id!r} to {again!r}")

        # the identity belongs to its client
        identity = ProviderIdentity(
            client_id=TEST_PROVIDER_ID,
            client_key_seed=bytes([1]) * 32,
            provide_tls_certificate_pem=b"synthetic certificate",
            provide_tls_private_key_pem=b"synthetic private key",
            extender_key_seed=bytes([2]) * 32,
        )
        save_provider_identity(state_dir, identity)
        loaded = load_provider_identity(state_dir, TEST_PROVIDER_ID)
        if loaded != identity:
            raise SelfTestError("identity round trip failed")
        key_material = provider_key_material(loaded)
        if key_material is None or key_material.client_key_seed != identity.client_key_seed or key_material.extender_key_seed != identity.extender_key_seed:
            raise SelfTestError("identity key material differs")
        other = load_provider_identity(state_dir, TEST_CLIENT_A_ID)
        if other is not None:
            raise SelfTestError("another client's identity was used")
        # without an identity the device gets no key material and makes a new identity
        if provider_key_material(other) is not None:
            raise SelfTestError("a first run passes key material")
        write_private_file(os.path.join(state_dir, IDENTITY_FILE_NAME), b'{"version":1}')
        expect_refused(lambda: load_provider_identity(state_dir, TEST_PROVIDER_ID), "an invalid identity was accepted")
    finally:
        shutil.rmtree(state_dir, ignore_errors=True)


def check_provider_config():
    """A missing or incomplete installation state is refused; a first run loads."""
    expect_refused(lambda: load_provider_config(""), "a missing state directory was accepted")
    expect_refused(lambda: load_provider_config(os.path.join("relative", "state")), "a relative state directory was accepted")
    state_dir = tempfile.mkdtemp(prefix="ur-provider-self-test-")
    try:
        if POSIX:
            os.chmod(state_dir, 0o700)
        expect_refused(lambda: load_provider_config(state_dir), "a state directory without client.jwt was accepted")
        # a network jwt has no client_id claim
        network_jwt = self_test_jwt(f'{{"network_id":"{TEST_CLIENT_A_ID}"}}')
        write_private_file(os.path.join(state_dir, CLIENT_JWT_FILE_NAME), (network_jwt + "\n").encode())
        expect_refused(lambda: load_provider_config(state_dir), "a network jwt was accepted")
        client_jwt = self_test_jwt(f'{{"client_id":"{TEST_PROVIDER_ID}"}}')
        write_private_file(os.path.join(state_dir, CLIENT_JWT_FILE_NAME), (client_jwt + "\n").encode())
        config = load_provider_config(state_dir)
        if config.client_jwt != client_jwt or config.client_id != TEST_PROVIDER_ID or not config.instance_id or config.identity is not None:
            raise SelfTestError("first-run configuration differs")
    finally:
        shutil.rmtree(state_dir, ignore_errors=True)


def check_usage_exit_code():
    """An unknown command is a usage error with exit code 78, before anything starts."""
    # imported here: main imports this module
    import main

    with contextlib.redirect_stderr(io.StringIO()):
        exit_code = main.run(["--unknown"])
    if exit_code != main.EXIT_CONFIG:
        raise SelfTestError(f"usage error exit code {exit_code}, want {main.EXIT_CONFIG}")
