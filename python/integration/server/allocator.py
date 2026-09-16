"""SERVER ONLY: python allocator.py user:<authenticated-service-user-id> | --self-test.
The trusted backend supplies the user key after authentication, never from a raw
request field. Set URNETWORK_ROOT_JWT, URNETWORK_CLIENT_MAP (absolute file path),
and optionally URNETWORK_API_URL (default https://api.bringyour.com).
The existing map parent directory must be owned by the service. An interrupted
process can leave a .lock directory; remove it only after confirming it is stale.
"""
import base64
from contextlib import contextmanager
import json
import os
from pathlib import Path
import re
import tempfile
import urllib.parse
import urllib.request

LIMIT = 1 << 20
USER = re.compile(r"user:[A-Za-z0-9][A-Za-z0-9_.:@-]{0,122}\Z")
ID = re.compile(r"[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\Z")


def service_user(args):
    if len(args) != 1 or not USER.fullmatch(args[0]):
        raise ValueError("expected one authenticated service key: user:<service-user-id>")
    return args[0]


def endpoint(base):
    u = urllib.parse.urlsplit(base)
    if (not u.hostname or u.username is not None or u.password is not None
            or u.path not in ("", "/") or u.query or u.fragment
            or (u.scheme != "https" and not (u.scheme == "http" and u.hostname in ("localhost", "127.0.0.1", "::1")))):
        raise ValueError("API URL must be an HTTPS origin; HTTP is allowed only for explicit loopback mocks")
    _ = u.port
    return base.rstrip("/") + "/network/auth-client"


def request_for(user, client=None):
    service_user([user])
    body = {"description": "service " + user, "device_spec": "urnetwork-examples/python-server"}
    if client is not None:
        if not ID.fullmatch(client):
            raise ValueError("invalid mapped client ID")
        body["client_id"] = client
    return body


def parse_response(raw, expected=None):
    obj = json.loads(raw)
    if not isinstance(obj, dict) or obj.get("error") is not None:
        raise ValueError("provisioning API returned an error")
    client, jwt = obj.get("client_id"), obj.get("by_client_jwt")
    if not isinstance(client, str) or not ID.fullmatch(client) or not isinstance(jwt, str):
        raise ValueError("missing scoped client result")
    parts = jwt.split(".")
    if len(parts) != 3 or not all(parts) or not re.fullmatch(r"[A-Za-z0-9_-]+", parts[1]):
        raise ValueError("invalid scoped client JWT")
    claims = json.loads(base64.urlsafe_b64decode(parts[1] + "=" * (-len(parts[1]) % 4)))
    if not isinstance(claims, dict) or claims.get("client_id") != client or (expected and client != expected):
        raise ValueError("scoped client identity mismatch")
    # Claim checking is a consistency check, not local signature verification.
    return {"client_id": client, "by_client_jwt": jwt}


def load_map(path):
    if path.is_symlink():
        raise ValueError("mapping must not be a symlink")
    if not path.exists():
        return {"version": 1, "clients": {}}
    if os.name == "posix" and path.stat().st_mode & 0o077:
        raise ValueError("mapping must have private permissions (0600)")
    if path.stat().st_size > LIMIT:
        raise ValueError("mapping too large")
    data = json.loads(path.read_bytes())
    if not isinstance(data, dict) or type(data.get("version")) is not int or data["version"] != 1 or not isinstance(data.get("clients"), dict):
        raise ValueError("invalid mapping")
    seen = set()
    for user, client in data["clients"].items():
        service_user([user])
        if not isinstance(client, str) or not ID.fullmatch(client) or client in seen:
            raise ValueError("invalid or duplicate mapped client")
        seen.add(client)
    return data


def save_map(path, data):
    fd, name = tempfile.mkstemp(prefix=path.name + ".", dir=path.parent)
    try:
        with os.fdopen(fd, "w") as output:
            json.dump(data, output, separators=(",", ":"))
            output.flush()
            os.fsync(output.fileno())
        os.replace(name, path)
    finally:
        Path(name).unlink(missing_ok=True)


@contextmanager
def map_lock(path):
    if not path.is_absolute() or not path.parent.is_dir():
        raise ValueError("URNETWORK_CLIENT_MAP must be absolute with an existing service-owned parent")
    lock = Path(str(path) + ".lock")
    lock.mkdir(mode=0o700)
    try:
        yield
    finally:
        lock.rmdir()


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def post(url, root, body):
    if not root or any(c.isspace() for c in root):
        raise ValueError("set a valid URNETWORK_ROOT_JWT on the backend")
    request = urllib.request.Request(url, json.dumps(body).encode(), {"Authorization": "Bearer " + root, "Content-Type": "application/json"}, method="POST")
    with urllib.request.build_opener(NoRedirect).open(request, timeout=15) as response:
        if not 200 <= response.status < 300:
            raise ValueError("provisioning HTTP failure")
        result = response.read(LIMIT + 1)
        if len(result) > LIMIT:
            raise ValueError("provisioning response too large")
        return result


def allocate(user, path, call):
    with map_lock(path):
        data = load_map(path)
        old = data["clients"].get(user)
        result = parse_response(call(request_for(user, old)), old)
        if old is None:
            if result["client_id"] in data["clients"].values():
                raise ValueError("client already assigned to another user")
            data["clients"][user] = result["client_id"]
            save_map(path, data)
        return result


def self_test():
    client = "11111111-1111-1111-1111-111111111111"
    jwt = "e30." + base64.urlsafe_b64encode(json.dumps({"client_id": client}).encode()).decode().rstrip("=") + ".test"
    response = json.dumps({"client_id": client, "by_client_jwt": jwt})
    calls = []
    def mock(body):
        calls.append(body)
        return response
    with tempfile.TemporaryDirectory() as directory:
        path = Path(directory) / "clients.json"
        assert allocate("user:alice", path, mock)["client_id"] == client
        assert allocate("user:alice", path, mock)["by_client_jwt"] == jwt
        assert "client_id" not in calls[0] and "source_client_id" not in calls[0]
        assert calls[1]["client_id"] == client and "source_client_id" not in calls[1]
        assert load_map(path)["clients"] == {"user:alice": client}
        assert endpoint("http://127.0.0.1:1234") == "http://127.0.0.1:1234/network/auth-client"
        for invalid in [lambda: service_user([client]), lambda: service_user(["user:a", "--client-id", client]), lambda: service_user(["user:../a"]), lambda: endpoint("http://example.com"), lambda: endpoint("https://example.com/path"), lambda: parse_response('{"error":{}}'), lambda: parse_response(response, "22222222-2222-2222-2222-222222222222")]:
            try:
                invalid()
            except Exception:
                pass
            else:
                raise AssertionError("invalid input accepted")
    print("allocator self-test passed")


if __name__ == "__main__":
    import sys
    try:
        if sys.argv[1:] == ["--self-test"]:
            self_test()
        else:
            user = service_user(sys.argv[1:])
            url = endpoint(os.environ.get("URNETWORK_API_URL", "https://api.bringyour.com"))
            root = os.environ["URNETWORK_ROOT_JWT"]
            path = Path(os.environ["URNETWORK_CLIENT_MAP"])
            print(json.dumps(allocate(user, path, lambda body: post(url, root, body))))
    except Exception:
        # Neither remote bodies nor exception messages may expose credentials.
        print("allocator failed: check service key, private mapping and backend API configuration", file=sys.stderr)
        sys.exit(1)
