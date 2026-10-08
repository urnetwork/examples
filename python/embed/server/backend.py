"""SERVER ONLY. The Python embed backend tool (EMBED_CONTRACT.md, "Backend
tools"). It extends the integration allocator (../../integration/server/
allocator.py): the same settings, map format, key pattern, lock and response
checks, plus the embed commands.

  python3 backend.py provision <key> <client-jwt-file>
  python3 backend.py cap <key> [--monthly <bytes>|--monthly null] [--total <bytes>|--total null] [--reset-total]
  python3 backend.py usage <key>
  python3 backend.py usage-all
  python3 backend.py remove <key>
  python3 backend.py --self-test

<key> is user:<service-user-id>, or user:<service-user-id>:<installation-id>
for one client per running installation. Your service authenticates the user
and supplies the key itself, never from a raw request field.

Settings:
- URNETWORK_ROOT_JWT: the root credential, an API key (urn_...) or a network
  JWT, from the backend's secret store. Never sent to an app.
- URNETWORK_CLIENT_MAP: absolute filename of this tool's private client map,
  in an existing private, service-owned directory. Not the token server's map.
- URNETWORK_API_URL: optional HTTPS origin, default https://api.bringyour.com.

Exit codes: 0 success; 78 a configuration or credential problem (missing
settings, an invalid key or map, the root credential refused, the client
limit); 1 any other failure. Each failure prints one stderr line, which never
carries a secret."""

import base64
import contextlib
import io
import json
import os
from pathlib import Path
import re
import secrets
import sys
import tempfile
import urllib.error
import urllib.parse
import urllib.request

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "integration" / "server"))
import allocator  # noqa: E402  the integration allocator this tool extends

DESCRIPTION = "embed client"
DEVICE_SPEC = "urnetwork-examples/python-embed-server"

AUTH_CLIENT_ROUTE = "/network/auth-client"
REMOVE_CLIENT_ROUTE = "/network/remove-client"
CAP_ROUTE = "/network/client-data-cap"
CAPS_ROUTE = "/network/client-data-caps"
USAGE_ALL_PAGE_LIMIT = 1000

MAX_BYTE_COUNT = 9223372036854775807
BYTE_COUNT_PATTERN = re.compile(r"[0-9]{1,19}")

CLIENT_DOES_NOT_EXIST = "Client does not exist."
CLIENT_LIMIT_MESSAGE = "client limit reached: your network is at its client limit; see https://ur.io/services"

# a language tool's map has only these fields; the token server's adds pending_caps
MAP_FIELDS = {"version", "clients"}

EXIT_OK = 0
EXIT_FAILURE = 1
# sysexits EX_CONFIG
EXIT_CONFIG = 78

# the longest API error message the tool shows
ERROR_MESSAGE_LIMIT = 300

USAGE = (
    "usage: backend.py provision <key> <client-jwt-file> | cap <key> [--monthly <bytes>|--monthly null] "
    "[--total <bytes>|--total null] [--reset-total] | usage <key> | usage-all | remove <key> | --self-test"
)


class ToolError(Exception):
    """A failure with its exit code and its stderr line, which never carries a
    secret."""

    def __init__(self, exit_code: int, message: str):
        super().__init__(message)
        self.exit_code = exit_code
        self.message = message


def config_problem(message: str) -> ToolError:
    return ToolError(EXIT_CONFIG, message)


def failure(message: str) -> ToolError:
    return ToolError(EXIT_FAILURE, message)


def check_key(key: str) -> str:
    """A map key in the allocator's pattern."""
    if not isinstance(key, str) or not allocator.USER.fullmatch(key):
        raise config_problem("invalid key: use user:<service-user-id> or user:<service-user-id>:<installation-id>")
    return key


def api_origin(base: str) -> str:
    """The API origin with the allocator's rules: HTTPS, or explicit loopback HTTP
    for local mocks; no credentials, path, query or fragment."""
    try:
        allocator.endpoint(base)
    except ValueError:
        raise config_problem("URNETWORK_API_URL must be an HTTPS origin; HTTP is allowed only for explicit loopback mocks") from None
    if "?" in base or "#" in base:
        raise config_problem("URNETWORK_API_URL must be an origin, with no query or fragment")
    parts = urllib.parse.urlsplit(base)
    return f"{parts.scheme}://{parts.netloc}"


def load_map(path: Path) -> dict:
    """The allocator's private map, refusing one with fields other than version
    and clients (the token server's map has pending_caps), so that two tools
    never rewrite each other's map."""
    try:
        data = allocator.load_map(path)
    except (OSError, ValueError) as error:
        raise config_problem(f"invalid client map: {error}") from None
    extra = sorted(set(data) - MAP_FIELDS)
    if extra:
        raise config_problem(f"the client map has fields other than version and clients ({', '.join(extra)}); give this tool its own map")
    return data


def save_map(path: Path, data: dict):
    try:
        allocator.save_map(path, data)
    except OSError as error:
        raise failure(f"save the client map: {error}") from None


@contextlib.contextmanager
def map_lock(path: Path):
    """The allocator's exclusive <map>.lock, held through the remote call and the
    map update."""
    try:
        lock = allocator.map_lock(path)
        lock.__enter__()
    except ValueError as error:
        raise config_problem(str(error)) from None
    except FileExistsError:
        raise failure("the client map is locked by another run; retry when it finishes") from None
    except OSError as error:
        raise failure(f"lock the client map: {error}") from None
    try:
        yield
    finally:
        lock.__exit__(None, None, None)


def write_private_file(path: str, data: bytes):
    """Replaces a file atomically with owner-only permissions: a private temporary
    file in the same directory is written, synced and renamed over it."""
    directory = os.path.dirname(os.path.abspath(path))
    temp_path = os.path.join(directory, f".{os.path.basename(path)}.{secrets.token_hex(8)}")
    fd = os.open(temp_path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_BINARY", 0), 0o600)
    try:
        with os.fdopen(fd, "wb") as file:
            if os.name != "nt":
                os.fchmod(file.fileno(), 0o600)
            file.write(data)
            file.flush()
            os.fsync(file.fileno())
        os.replace(temp_path, path)
    except BaseException:
        with contextlib.suppress(OSError):
            os.remove(temp_path)
        raise


def message_of(error) -> str:
    """An API error object's message on one bounded line."""
    message = error.get("message") if isinstance(error, dict) else None
    if not isinstance(message, str):
        return "no message"
    return " ".join(message.split())[:ERROR_MESSAGE_LIMIT]


def is_client_does_not_exist(answer: dict) -> bool:
    error = answer.get("error")
    return isinstance(error, dict) and error.get("message") == CLIENT_DOES_NOT_EXIST


def check_client_limit(answer: dict):
    """A refusal for either client limit flag is a configuration problem."""
    error = answer.get("error")
    if isinstance(error, dict) and (error.get("client_limit_exceeded") is True or error.get("upgrade_required") is True):
        raise config_problem(CLIENT_LIMIT_MESSAGE)


def check_cap_object(value) -> dict:
    """A cap object: a JSON object with a client_id and no error."""
    if isinstance(value, dict) and value.get("error") is not None:
        raise failure("the cap request was refused: " + message_of(value["error"]))
    if not isinstance(value, dict) or not isinstance(value.get("client_id"), str):
        raise failure("the API answered something that is not a cap object")
    for name in ("monthly_byte_limit", "total_byte_limit"):
        limit = value.get(name)
        if limit is not None and (not isinstance(limit, int) or isinstance(limit, bool)):
            raise failure(f"the cap object has an invalid {name}")
    return value


def compact(value) -> str:
    return json.dumps(value, separators=(",", ":"))


def parse_byte_limit(text: str):
    """A byte count option: a decimal integer from 0 to 9223372036854775807 with no
    units, or null to clear the cap."""
    if text == "null":
        return None
    if not BYTE_COUNT_PATTERN.fullmatch(text) or MAX_BYTE_COUNT < int(text):
        raise config_problem("a byte count is a decimal integer from 0 to 9223372036854775807, with no units, or null")
    return int(text)


def parse_cap_options(args: list) -> dict:
    """The fields to merge: only the options given, so an omitted cap is absent
    from the request and keeps its value."""
    fields = {}
    index = 0
    while index < len(args):
        option = args[index]
        if option in ("--monthly", "--total"):
            name = "monthly_byte_limit" if option == "--monthly" else "total_byte_limit"
            if name in fields:
                raise config_problem(f"{option} is given twice")
            if len(args) <= index + 1:
                raise config_problem(f"{option} needs a byte count or null")
            fields[name] = parse_byte_limit(args[index + 1])
            index += 2
        elif option == "--reset-total":
            if "reset_total" in fields:
                raise config_problem("--reset-total is given twice")
            fields["reset_total"] = True
            index += 1
        else:
            raise config_problem(USAGE)
    if not fields:
        raise config_problem("cap needs at least one of --monthly, --total or --reset-total")
    return fields


class Api:
    """The URnetwork API with the root credential. transport(method, path, body)
    returns (status, answer bytes); the self-test passes a stand-in."""

    def __init__(self, transport):
        self._transport = transport

    def call(self, method: str, path: str, body=None) -> dict:
        data = None if body is None else json.dumps(body).encode()
        status, answer = self._transport(method, path, data)
        route = path.split("?", 1)[0]
        if status in (401, 403):
            raise config_problem("the root credential was refused; check URNETWORK_ROOT_JWT")
        if status == 404:
            raise failure(f"{route} answered 404: the server needs a release with this route")
        if not 200 <= status < 300:
            raise failure(f"{route} failed with HTTP {status}")
        try:
            value = json.loads(answer)
        except ValueError:
            value = None
        if not isinstance(value, dict):
            raise failure(f"{route} answered something that is not a JSON object")
        return value


def urllib_transport(origin: str, root: str):
    """The real transport: urllib with the allocator's no-redirect handler and its
    answer size limit."""
    if not root or any(character.isspace() for character in root):
        raise config_problem("set a valid URNETWORK_ROOT_JWT on the backend")
    opener = urllib.request.build_opener(allocator.NoRedirect)

    def send(method: str, path: str, body: bytes | None):
        headers = {"Authorization": "Bearer " + root, "Accept": "application/json"}
        if body is not None:
            headers["Content-Type"] = "application/json"
        request = urllib.request.Request(origin + path, data=body, headers=headers, method=method)
        try:
            response = opener.open(request, timeout=15)
        except urllib.error.HTTPError as error:
            response = error
        except OSError:
            # the reason can name the host; it never carries the credential
            raise failure("could not reach the URnetwork API; check URNETWORK_API_URL and the network") from None
        try:
            status = response.getcode()
            answer = response.read(allocator.LIMIT + 1)
        finally:
            response.close()
        if allocator.LIMIT < len(answer):
            raise failure("the API answer is too large")
        return status, answer

    return send


def provision(api: Api, path: Path, key: str, client_jwt_file: str):
    """Reissues the key's client, or provisions a new one; on Client does not
    exist. it drops the mapping and provisions a new client. Writes the client
    JWT to client_jwt_file and prints only the client ID."""
    with map_lock(path):
        data = load_map(path)
        old = data["clients"].get(key)
        answer = None
        if old is not None:
            answer = api.call("POST", AUTH_CLIENT_ROUTE, {"client_id": old, "description": DESCRIPTION, "device_spec": DEVICE_SPEC})
            if is_client_does_not_exist(answer):
                # deactivated after 30 days without connecting, or removed
                del data["clients"][key]
                save_map(path, data)
                old = None
        if old is None:
            # a new top-level client: neither client_id nor source_client_id
            answer = api.call("POST", AUTH_CLIENT_ROUTE, {"description": DESCRIPTION, "device_spec": DEVICE_SPEC})
        check_client_limit(answer)
        if answer.get("error") is not None:
            raise failure("provisioning was refused: " + message_of(answer["error"]))
        try:
            result = allocator.parse_response(json.dumps(answer), old)
        except (ValueError, TypeError) as error:
            raise failure(f"the provisioning answer failed its checks: {error}") from None
        if old is None:
            if result["client_id"] in data["clients"].values():
                raise failure("the API returned a client that the map assigns to another key")
            data["clients"][key] = result["client_id"]
            save_map(path, data)
        try:
            write_private_file(client_jwt_file, (result["by_client_jwt"] + "\n").encode())
        except OSError as error:
            raise failure(f"write the client JWT file: {error}") from None
    print(compact({"client_id": result["client_id"]}))


def mapped_client(path: Path, key: str) -> str:
    client = load_map(path)["clients"].get(key)
    if client is None:
        raise config_problem("no client is mapped for that key; run provision first")
    return client


def cap(api: Api, path: Path, key: str, fields: dict):
    """Posts only the given fields to POST /network/client-data-cap and prints the
    cap object."""
    with map_lock(path):
        client = mapped_client(path, key)
        answer = api.call("POST", CAP_ROUTE, {"client_id": client, **fields})
    print(compact(check_cap_object(answer)))


def usage(api: Api, path: Path, key: str):
    """Prints the key's cap object, read with the root credential."""
    client = mapped_client(path, key)
    answer = api.call("GET", CAP_ROUTE + "?" + urllib.parse.urlencode({"client_id": client}))
    print(compact(check_cap_object(answer)))


def usage_all(api: Api):
    """Pages through GET /network/client-data-caps and prints one cap object per
    line, stopping at a null cursor or a repeated one."""
    cursor = None
    seen = set()
    while True:
        query = {"limit": str(USAGE_ALL_PAGE_LIMIT)}
        if cursor is not None:
            query["cursor"] = cursor
        answer = api.call("GET", CAPS_ROUTE + "?" + urllib.parse.urlencode(query))
        if answer.get("error") is not None:
            raise failure("the cap list was refused: " + message_of(answer["error"]))
        clients = answer.get("clients")
        if not isinstance(clients, list):
            raise failure("the cap list has no clients array")
        for value in clients:
            print(compact(check_cap_object(value)))
        next_cursor = answer.get("next_cursor")
        if next_cursor is None:
            return
        if not isinstance(next_cursor, str):
            raise failure("the cap list has an invalid next_cursor")
        if next_cursor in seen:
            # a server that repeats a cursor would page forever
            return
        seen.add(next_cursor)
        cursor = next_cursor


def remove(api: Api, path: Path, key: str):
    """Removes the key's client, then its mapping, also when the client is already
    gone."""
    with map_lock(path):
        data = load_map(path)
        client = data["clients"].get(key)
        if client is None:
            raise config_problem("no client is mapped for that key")
        answer = api.call("POST", REMOVE_CLIENT_ROUTE, {"client_id": client})
        if answer.get("error") is not None and not is_client_does_not_exist(answer):
            raise failure("remove was refused: " + message_of(answer["error"]))
        del data["clients"][key]
        save_map(path, data)
    print(compact({"removed": client}))


def run(args: list, environ=None, transport_factory=urllib_transport) -> int:
    """Runs one command and returns the exit code. transport_factory(origin, root)
    makes the API transport; the self-test passes a stand-in."""
    environ = os.environ if environ is None else environ
    try:
        if args == ["--self-test"]:
            try:
                self_test()
            except Exception as error:
                # test data only: the self-test has no credentials to expose
                print(f"embed backend self-test failed: {error}", file=sys.stderr)
                return EXIT_FAILURE
            print("embed backend self-test passed")
            return EXIT_OK
        if not args or args[0] not in ("provision", "cap", "usage", "usage-all", "remove"):
            raise config_problem(USAGE)
        command, operands = args[0], args[1:]
        if command == "provision" and len(operands) != 2:
            raise config_problem(USAGE)
        if command in ("usage", "remove") and len(operands) != 1:
            raise config_problem(USAGE)
        if command == "cap" and not operands:
            raise config_problem(USAGE)
        if command == "usage-all" and operands:
            raise config_problem(USAGE)
        key = check_key(operands[0]) if command != "usage-all" else None
        fields = parse_cap_options(operands[1:]) if command == "cap" else None

        root = environ.get("URNETWORK_ROOT_JWT", "")
        if not root:
            raise config_problem("set URNETWORK_ROOT_JWT from the backend's secret store")
        origin = api_origin(environ.get("URNETWORK_API_URL") or "https://api.bringyour.com")
        path = None
        if command != "usage-all":
            map_name = environ.get("URNETWORK_CLIENT_MAP", "")
            if not map_name:
                raise config_problem("set URNETWORK_CLIENT_MAP to this tool's private client map")
            path = Path(map_name)
            if not path.is_absolute() or not path.parent.is_dir():
                raise config_problem("URNETWORK_CLIENT_MAP must be absolute, in an existing service-owned directory")
        api = Api(transport_factory(origin, root))
        if command == "provision":
            provision(api, path, key, operands[1])
        elif command == "cap":
            cap(api, path, key, fields)
        elif command == "usage":
            usage(api, path, key)
        elif command == "usage-all":
            usage_all(api)
        else:
            remove(api, path, key)
        return EXIT_OK
    except ToolError as error:
        print(error.message, file=sys.stderr)
        return error.exit_code
    except Exception:
        # neither remote bodies nor exception text may expose a credential
        print("embed backend failed: check the key, the private map and the API configuration", file=sys.stderr)
        return EXIT_FAILURE


# ---------------------------------------------------------------- self-test

SELF_TEST_ROOT = "urn_self-test-root-credential"
SELF_TEST_CLIENT = "11111111-1111-1111-1111-111111111111"
SELF_TEST_OTHER_CLIENT = "22222222-2222-2222-2222-222222222222"
SELF_TEST_KEY = "user:alice:33333333-3333-3333-3333-333333333333"


class SelfTestError(Exception):
    """A self-test check that failed."""


def self_test_jwt(client_id: str) -> str:
    """An unsigned client JWT carrying client_id, for the claim checks only."""
    payload = json.dumps({"client_id": client_id}).encode()
    return "e30." + base64.urlsafe_b64encode(payload).decode().rstrip("=") + ".c2lnbmF0dXJl"


class StandInApi:
    """A stand-in URnetwork API: answers each request from a queue of (status,
    JSON value) answers and logs the requests with their parsed bodies."""

    def __init__(self, *answers):
        self.answers = list(answers)
        self.requests = []

    def factory(self, origin: str, root: str):
        if root != SELF_TEST_ROOT:
            raise SelfTestError("the tool passed a different root credential")
        return self

    def __call__(self, method, path, body):
        self.requests.append({"method": method, "path": path, "body": None if body is None else json.loads(body)})
        if not self.answers:
            raise SelfTestError(f"unexpected request {method} {path}")
        answer = self.answers.pop(0)
        if isinstance(answer, OSError):
            raise answer
        status, value = answer
        return status, json.dumps(value).encode()


def self_test():
    """The credential-free self-test: allocator rules, re-provision, the client JWT
    file, the merge request bodies, cap objects, paging, remove, map refusal and
    the exit codes."""
    with tempfile.TemporaryDirectory() as directory:
        if os.name != "nt":
            os.chmod(directory, 0o700)
        map_path = os.path.join(directory, "clients.json")
        jwt_path = os.path.join(directory, "client.jwt")
        environ = {"URNETWORK_ROOT_JWT": SELF_TEST_ROOT, "URNETWORK_CLIENT_MAP": map_path, "URNETWORK_API_URL": "http://127.0.0.1:1"}

        def tool(args, api):
            out, err = io.StringIO(), io.StringIO()
            with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
                code = run(args, environ, api.factory)
            text = out.getvalue() + err.getvalue()
            if SELF_TEST_ROOT in text:
                raise SelfTestError("the root credential reached the output")
            return code, out.getvalue(), err.getvalue()

        def expect(condition, message):
            if not condition:
                raise SelfTestError(message)

        client_jwt = self_test_jwt(SELF_TEST_CLIENT)
        provisioned = {"client_id": SELF_TEST_CLIENT, "by_client_jwt": client_jwt}

        # a new key provisions a top-level client; the token goes only to the file
        api = StandInApi((200, provisioned))
        code, out, err = tool(["provision", SELF_TEST_KEY, jwt_path], api)
        expect(code == EXIT_OK and json.loads(out) == {"client_id": SELF_TEST_CLIENT}, f"provision must print the client ID: {code} {err}")
        body = api.requests[0]["body"]
        expect(api.requests[0]["path"] == AUTH_CLIENT_ROUTE, "provision must post /network/auth-client")
        expect(body == {"description": DESCRIPTION, "device_spec": DEVICE_SPEC}, "a new client sends neither client_id nor source_client_id")
        expect(client_jwt not in out + err, "the client JWT must never reach stdout or stderr")
        expect(Path(jwt_path).read_text() == client_jwt + "\n", "the client JWT file must hold the token")
        if os.name != "nt":
            expect(os.stat(jwt_path).st_mode & 0o777 == 0o600, "the client JWT file must be private (0600)")
            expect(os.stat(map_path).st_mode & 0o077 == 0, "the map must be private")
        expect(load_map(Path(map_path))["clients"] == {SELF_TEST_KEY: SELF_TEST_CLIENT}, "the map must round-trip")

        # a mapped key reissues with its client_id
        api = StandInApi((200, provisioned))
        code, _, _ = tool(["provision", SELF_TEST_KEY, jwt_path], api)
        expect(code == EXIT_OK and api.requests[0]["body"].get("client_id") == SELF_TEST_CLIENT, "a mapped key must reissue with its client_id")
        expect("source_client_id" not in api.requests[0]["body"], "a reissue never sends source_client_id")

        # Client does not exist. drops the mapping and provisions a new client
        other = {"client_id": SELF_TEST_OTHER_CLIENT, "by_client_jwt": self_test_jwt(SELF_TEST_OTHER_CLIENT)}
        api = StandInApi((200, {"error": {"message": CLIENT_DOES_NOT_EXIST}}), (200, other))
        code, out, _ = tool(["provision", SELF_TEST_KEY, jwt_path], api)
        expect(code == EXIT_OK and json.loads(out)["client_id"] == SELF_TEST_OTHER_CLIENT, "a deactivated client must be re-provisioned")
        expect("client_id" not in api.requests[1]["body"], "the re-provision must create a new client")
        expect(load_map(Path(map_path))["clients"][SELF_TEST_KEY] == SELF_TEST_OTHER_CLIENT, "the map must hold the new client")

        # response and claim checks
        mismatch = {"client_id": SELF_TEST_CLIENT, "by_client_jwt": self_test_jwt("44444444-4444-4444-4444-444444444444")}
        for bad in (mismatch, {"client_id": SELF_TEST_CLIENT}, {"by_client_jwt": client_jwt}, {"client_id": "not-a-uuid", "by_client_jwt": client_jwt}):
            code, _, _ = tool(["provision", "user:bob", jwt_path], StandInApi((200, bad)))
            expect(code == EXIT_FAILURE, f"a provisioning answer {bad} must fail its checks")
        # a client the map already assigns to another key is refused
        taken = {"client_id": SELF_TEST_OTHER_CLIENT, "by_client_jwt": self_test_jwt(SELF_TEST_OTHER_CLIENT)}
        code, _, _ = tool(["provision", "user:carol", jwt_path], StandInApi((200, taken)))
        expect(code == EXIT_FAILURE, "a client assigned to another key must be refused")

        # the client limit: either flag, exit 78 with the exact line
        for flags in ({"client_limit_exceeded": True, "message": "Client limit exceeded."}, {"client_limit_exceeded": False, "upgrade_required": True, "message": "upgrade"}):
            code, _, err = tool(["provision", "user:dave", jwt_path], StandInApi((200, {"error": flags})))
            expect(code == EXIT_CONFIG and err.strip() == CLIENT_LIMIT_MESSAGE, f"a client limit refusal must exit 78 with the limit line: {flags}")

        # the merge request bodies: only the given fields, null as JSON null, integers
        cap_answer = {"client_id": SELF_TEST_OTHER_CLIENT, "monthly_byte_limit": 10000000000, "total_byte_limit": None, "capped": False, "capped_reason": ""}
        cases = [
            (["--monthly", "10000000000"], {"monthly_byte_limit": 10000000000}),
            (["--total", "null"], {"total_byte_limit": None}),
            (["--monthly", "0"], {"monthly_byte_limit": 0}),
            (["--reset-total"], {"reset_total": True}),
            (["--monthly", "20000000000", "--total", "9223372036854775807", "--reset-total"], {"monthly_byte_limit": 20000000000, "total_byte_limit": 9223372036854775807, "reset_total": True}),
        ]
        for options, fields in cases:
            api = StandInApi((200, cap_answer))
            code, out, err = tool(["cap", SELF_TEST_KEY, *options], api)
            expect(code == EXIT_OK, f"cap {options} must succeed: {err}")
            sent = api.requests[0]["body"]
            expect(api.requests[0]["path"] == CAP_ROUTE and api.requests[0]["method"] == "POST", "cap must post /network/client-data-cap")
            expect(sent == {"client_id": SELF_TEST_OTHER_CLIENT, **fields}, f"cap {options} must send {fields}, sent {sent}")
            for name, value in fields.items():
                expect(type(sent[name]) is type(value), f"{name} must keep its JSON type")
            expect(json.loads(out) == cap_answer, "cap must print the cap object")
        for options in ([], ["--monthly"], ["--monthly", "-1"], ["--monthly", "1e3"], ["--monthly", "10GB"], ["--total", "9223372036854775808"], ["--monthly", "1", "--monthly", "2"], ["--weekly", "1"]):
            code, _, _ = tool(["cap", SELF_TEST_KEY, *options], StandInApi())
            expect(code == EXIT_CONFIG, f"cap {options} must be a usage error")
        code, _, _ = tool(["cap", SELF_TEST_KEY, "--monthly", "1"], StandInApi((200, {"error": {"message": "no such client"}})))
        expect(code == EXIT_FAILURE, "a refused cap request must fail")
        code, _, _ = tool(["cap", SELF_TEST_KEY, "--monthly", "1"], StandInApi((404, {})))
        expect(code == EXIT_FAILURE, "a server without the cap routes must fail")

        # usage reads the key's client with the root credential
        api = StandInApi((200, cap_answer))
        code, out, _ = tool(["usage", SELF_TEST_KEY], api)
        expect(code == EXIT_OK and json.loads(out) == cap_answer, "usage must print the cap object")
        expect(api.requests[0] == {"method": "GET", "path": CAP_ROUTE + "?client_id=" + SELF_TEST_OTHER_CLIENT, "body": None}, "usage must GET the key's client")

        # usage-all pages until a null cursor, and stops at a repeated cursor
        page = lambda client, cursor: (200, {"clients": [{**cap_answer, "client_id": client}], "next_cursor": cursor})  # noqa: E731
        api = StandInApi(page("a", "c1"), page("b", "c2"), page("c", None))
        code, out, _ = tool(["usage-all"], api)
        expect(code == EXIT_OK and [json.loads(line)["client_id"] for line in out.splitlines()] == ["a", "b", "c"], "usage-all must print every page")
        expect([request["path"] for request in api.requests] == [CAPS_ROUTE + "?limit=1000", CAPS_ROUTE + "?limit=1000&cursor=c1", CAPS_ROUTE + "?limit=1000&cursor=c2"], "usage-all must page with the cursor")
        api = StandInApi(page("a", "c1"), page("b", "c1"))
        code, out, _ = tool(["usage-all"], api)
        expect(code == EXIT_OK and len(out.splitlines()) == 2 and len(api.requests) == 2, "usage-all must stop at a repeated cursor")

        # remove drops the mapping for both answers, and keeps it on a refusal
        code, _, _ = tool(["remove", SELF_TEST_KEY], StandInApi((200, {"error": {"message": "not allowed"}})))
        expect(code == EXIT_FAILURE and SELF_TEST_KEY in load_map(Path(map_path))["clients"], "a refused remove must keep the mapping")
        api = StandInApi((200, {}))
        code, out, _ = tool(["remove", SELF_TEST_KEY], api)
        expect(code == EXIT_OK and json.loads(out) == {"removed": SELF_TEST_OTHER_CLIENT}, "remove must print the removed client")
        expect(api.requests[0]["body"] == {"client_id": SELF_TEST_OTHER_CLIENT}, "remove must post the client_id")
        expect(SELF_TEST_KEY not in load_map(Path(map_path))["clients"], "remove must drop the mapping")
        tool(["provision", SELF_TEST_KEY, jwt_path], StandInApi((200, provisioned)))
        code, _, _ = tool(["remove", SELF_TEST_KEY], StandInApi((200, {"error": {"message": CLIENT_DOES_NOT_EXIST}})))
        expect(code == EXIT_OK and SELF_TEST_KEY not in load_map(Path(map_path))["clients"], "remove must drop the mapping of a client that is already gone")

        # a map with other fields, such as the token server's, is refused
        token_server_map = os.path.join(directory, "token-server.json")
        write_private_file(token_server_map, json.dumps({"version": 1, "clients": {}, "pending_caps": []}).encode())
        code, _, _ = run_with(tool, environ, token_server_map, ["usage", SELF_TEST_KEY])
        expect(code == EXIT_CONFIG, "a map with pending_caps must be refused")

        # key and argument rejection, settings, credential refusal and the network
        for args in (["provision", SELF_TEST_CLIENT, jwt_path], ["provision", "user:../a", jwt_path], ["provision", SELF_TEST_KEY], ["usage"], ["usage-all", "extra"], ["bogus"], []):
            code, _, _ = tool(args, StandInApi())
            expect(code == EXIT_CONFIG, f"{args} must be a usage error")
        missing_root = dict(environ, URNETWORK_ROOT_JWT="")
        expect(run_quietly(["usage-all"], missing_root) == EXIT_CONFIG, "a missing root credential must exit 78")
        relative_map = dict(environ, URNETWORK_CLIENT_MAP="clients.json")
        expect(run_quietly(["usage", SELF_TEST_KEY], relative_map) == EXIT_CONFIG, "a relative map must exit 78")
        bad_api = dict(environ, URNETWORK_API_URL="http://example.com")
        expect(run_quietly(["usage-all"], bad_api) == EXIT_CONFIG, "a non-HTTPS API must exit 78")
        code, _, _ = tool(["usage-all"], StandInApi((401, {})))
        expect(code == EXIT_CONFIG, "a refused root credential must exit 78")
        code, _, _ = tool(["usage-all"], StandInApi(failure_transport_error()))
        expect(code == EXIT_FAILURE, "an unreachable API must exit 1")


def failure_transport_error():
    """What the real transport raises for an unreachable API."""
    return OSError("connection refused")


def run_with(tool, environ, map_path, args):
    """Runs args against another map."""
    other = dict(environ, URNETWORK_CLIENT_MAP=map_path)
    out, err = io.StringIO(), io.StringIO()
    with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
        code = run(args, other, StandInApi().factory)
    return code, out.getvalue(), err.getvalue()


def run_quietly(args, environ) -> int:
    with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
        return run(args, environ, StandInApi().factory)


if __name__ == "__main__":
    sys.exit(run(sys.argv[1:]))
