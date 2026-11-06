"""SERVER ONLY: hosted proxies with unique exit IPs.

    python unique_exit.py --count 3 [--country de] [--replace auto|manual]
                          [--rotate] [--echo-url URL] [--keep]
    python unique_exit.py --self-test

Creates N hosted proxies with `proxy_config.unique_exit`, prints each proxy's
exit location and `exit_id`, sends one request through each proxy's HTTPS and
SOCKS5 URLs to an IP echo to show that the exits are distinct, optionally
rotates one proxy's exit, and removes the proxies again unless --keep.

Unique exits are a Pro feature. A Pro network holds up to 1,000 active hosted
proxies and creates up to 20 a minute; this program paces its creates to that
rate. Each unique-exit proxy exits through one provider whose egress IP no
other active unique-exit proxy of the network uses. The API never returns the
exit IP itself: it reports the exit's location and an opaque `exit_id`, and the
IP is visible only through a request over the proxy, as below.

Set URNETWORK_ROOT_JWT, the network credential (only a backend holds it), and
optionally URNETWORK_API_URL (default https://api.bringyour.com).
"""
import argparse
import http.server
import json
import os
import re
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request

# The API's names (connect api/bringyour.yml).
AUTH_CLIENT_PATH = "/network/auth-client"
ROTATE_EXIT_PATH = "/network/proxy/rotate-exit"
REMOVE_CLIENT_PATH = "/network/remove-client"

# Pro plan limits on hosted proxies (server model/network_client_proxy_limit.go).
PRO_ACTIVE_PROXY_CAP = 1000
PRO_CREATES_PER_MINUTE = 20

# Any password works on the SOCKS5 leg; the token is the username. Some
# clients (Python requests with PySocks) send no credentials at all without a
# password, so a placeholder is always set.
SOCKS_PLACEHOLDER_PASSWORD = "x"

DEFAULT_API_URL = "https://api.bringyour.com"
DEFAULT_ECHO_URL = "https://api.ipify.org?format=json"

ID = re.compile(r"[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\Z")
COUNTRY = re.compile(r"[A-Za-z]{2}\Z")
REPLACE_MODES = ("auto", "manual")


class Refused(Exception):
    """The API refused the call, with the server's message and its flags:
    `limit` (client_limit_exceeded) is the proxy cap or the create rate;
    `upgrade` (upgrade_required) means the network is not on Pro;
    `no_unique_supply` means no unused exit IP is available in the location;
    `rate_limited` means a rotate came too soon (once a minute, 60 a day)."""

    def __init__(self, message, limit=False, upgrade=False, no_unique_supply=False, rate_limited=False):
        super().__init__(message)
        self.limit = limit
        self.upgrade = upgrade
        self.no_unique_supply = no_unique_supply
        self.rate_limited = rate_limited


def endpoint(base, path):
    u = urllib.parse.urlsplit(base)
    if (not u.hostname or u.username is not None or u.password is not None
            or u.path not in ("", "/") or u.query or u.fragment
            or (u.scheme != "https" and not (u.scheme == "http" and u.hostname in ("localhost", "127.0.0.1", "::1")))):
        raise ValueError("API URL must be an HTTPS origin; HTTP is allowed only for explicit loopback mocks")
    _ = u.port
    return base.rstrip("/") + path


def create_body(index, country=None, replace=None):
    """The /network/auth-client request for one unique-exit proxy. Without a
    country the exit is drawn from any location (best available)."""
    if country is not None:
        if not COUNTRY.fullmatch(country):
            raise ValueError("country must be a two-letter country code")
        state = {"country_code": country.lower()}
    else:
        state = {"location": {"connect_location_id": {"best_available": True}}}
    proxy_config = {"unique_exit": True, "initial_device_state": state}
    if replace is not None:
        if replace not in REPLACE_MODES:
            raise ValueError("replace must be auto or manual")
        proxy_config["unique_exit_replace"] = replace
    return {
        "description": "unique exit %d" % index,
        "device_spec": "urnetwork-examples/python-unique-exit",
        "proxy_config": proxy_config,
    }


def refusal(obj):
    error = obj.get("error")
    if error is None:
        return None
    if not isinstance(error, dict):
        return Refused("the API refused the call")
    return Refused(str(error.get("message") or "the API refused the call"),
                   limit=bool(error.get("client_limit_exceeded")),
                   upgrade=bool(error.get("upgrade_required")),
                   no_unique_supply=bool(error.get("no_unique_supply")),
                   rate_limited=bool(error.get("rate_limited")))


def parse_created(obj):
    """The proxy's client id, proxy URLs and exit from an auth-client result."""
    refused = refusal(obj)
    if refused is not None:
        raise refused
    result = obj.get("proxy_config_result")
    client = obj.get("client_id")
    if not isinstance(result, dict) or not isinstance(client, str) or not ID.fullmatch(client):
        raise ValueError("missing proxy result")
    token, host = result.get("auth_token"), result.get("proxy_host")
    if not isinstance(token, str) or not token or not isinstance(host, str) or not host:
        raise ValueError("missing proxy credentials")
    socks_port = result.get("socks_proxy_port")
    socks_url = None
    if isinstance(socks_port, int) and socks_port > 0:
        socks_url = "socks5h://%s:%s@%s:%d" % (token, SOCKS_PLACEHOLDER_PASSWORD, host, socks_port)
    return {
        "client_id": client,
        "auth_token": token,
        "https_proxy_url": result.get("https_proxy_url"),
        "socks_proxy_url": socks_url,
        "exit": result.get("exit") if isinstance(result.get("exit"), dict) else None,
    }


def describe_exit(exit_):
    """One line for an `exit` object: where it is, its id, and its state. While
    a failed exit waits for a new one, `state` is replacing or no_supply and
    `state_reason` says why."""
    if not exit_:
        return "no unique exit reported (is the network on Pro, and the server current?)"
    place = " / ".join(part for part in (exit_.get("country_code"), exit_.get("region"), exit_.get("city")) if part)
    state = exit_.get("state")
    if exit_.get("state_reason"):
        state = "%s: %s" % (state, exit_["state_reason"])
    return "%s (exit_id %s, %s since %s, replace %s)" % (
        place or "no exit yet", exit_.get("exit_id"), state, exit_.get("exit_since"), exit_.get("replace_mode"))


def distinct_report(observed):
    """`observed` maps a proxy label to the exit IP seen through it. Returns
    (the number of distinct IPs, a map of each IP seen more than once to its
    proxies)."""
    by_ip = {}
    for label, ip in observed.items():
        if ip:
            by_ip.setdefault(ip, []).append(label)
    return len(by_ip), {ip: labels for ip, labels in by_ip.items() if len(labels) > 1}


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class Api:
    def __init__(self, base, root, opener=None, timeout=30):
        if not root or any(c.isspace() for c in root):
            raise ValueError("set a valid URNETWORK_ROOT_JWT on the backend")
        self.base, self.root, self.timeout = base, root, timeout
        self.opener = opener or urllib.request.build_opener(NoRedirect)

    def post(self, path, body):
        request = urllib.request.Request(
            endpoint(self.base, path), json.dumps(body).encode(),
            {"Authorization": "Bearer " + self.root, "Content-Type": "application/json"}, method="POST")
        try:
            with self.opener.open(request, timeout=self.timeout) as response:
                return json.loads(response.read(1 << 20))
        except urllib.error.HTTPError as e:
            raise Refused("HTTP %d from %s" % (e.code, path)) from None

    def create(self, body):
        return parse_created(self.post(AUTH_CLIENT_PATH, body))

    def rotate(self, client_id):
        obj = self.post(ROTATE_EXIT_PATH, {"client_id": client_id})
        refused = refusal(obj)
        if refused is not None:
            raise refused
        return obj.get("exit")

    def remove(self, client_id):
        refused = refusal(self.post(REMOVE_CLIENT_PATH, {"client_id": client_id}))
        if refused is not None:
            raise refused


def echo_through(proxy_url, echo_url, timeout=30):
    """The exit IP seen through one proxy URL, with Requests (pip install
    'requests[socks]'). Requests sends the HTTPS proxy's token over TLS and the
    SOCKS5 token with the placeholder password."""
    import requests  # imported here so the self-test and unit tests need no packages

    response = requests.get(echo_url, proxies={"http": proxy_url, "https": proxy_url}, timeout=timeout)
    response.raise_for_status()
    try:
        return str(response.json()["ip"])
    except (ValueError, KeyError, TypeError):
        return response.text.strip()


def create_proxies(api, count, country, replace, out, sleep=time.sleep):
    """Creates `count` unique-exit proxies at the Pro creation rate. Stops at
    the first refusal that waiting cannot fix, and returns what was created."""
    pace = 60.0 / PRO_CREATES_PER_MINUTE if count > PRO_CREATES_PER_MINUTE else 0.0
    created = []
    for index in range(1, count + 1):
        if index > 1 and pace:
            sleep(pace)
        body = create_body(index, country, replace)
        try:
            proxy = api.create(body)
        except Refused as e:
            # the create rate refuses with a message about the minute; the cap,
            # the Pro gate (upgrade) and no_unique_supply cannot be waited out
            if e.limit and not e.upgrade and "minute" in str(e).lower():
                print("create %d: %s; waiting a minute" % (index, e), file=out)
                sleep(60)
                try:
                    proxy = api.create(body)
                except Refused as again:
                    print("create %d refused: %s" % (index, again), file=out)
                    break
            else:
                if e.no_unique_supply:
                    print("create %d: no unique exit IP is left in this location; "
                          "try another location or best available" % index, file=out)
                print("create %d refused: %s" % (index, e), file=out)
                break
        created.append(proxy)
        print("proxy %d %s: %s" % (index, proxy["client_id"], describe_exit(proxy["exit"])), file=out)
    return created


def run(api, count, country, replace, rotate, echo_url, keep, out, echo=echo_through, sleep=time.sleep):
    created = create_proxies(api, count, country, replace, out, sleep=sleep)
    try:
        observed = {}
        for index, proxy in enumerate(created, 1):
            for leg in ("https_proxy_url", "socks_proxy_url"):
                if proxy.get(leg):
                    try:
                        observed["proxy %d %s" % (index, leg.split("_")[0])] = echo(proxy[leg], echo_url)
                    except Exception as e:  # one failed request must not hide the others
                        print("proxy %d %s request failed: %s" % (index, leg.split("_")[0], type(e).__name__), file=out)
        # both legs of one proxy share its exit, so count by proxy
        per_proxy = {}
        for label, ip in observed.items():
            per_proxy.setdefault(label.rsplit(" ", 1)[0], set()).add(ip)
        exits = {proxy: ips.pop() for proxy, ips in per_proxy.items() if len(ips) == 1}
        distinct, duplicates = distinct_report(exits)
        for label, ip in sorted(observed.items()):
            print("%s exits from %s" % (label, ip), file=out)
        print("%d proxies, %d distinct exit IPs" % (len(exits), distinct), file=out)
        for ip, labels in duplicates.items():
            print("duplicate exit %s: %s" % (ip, ", ".join(labels)), file=out)
        if rotate and created:
            first = created[0]
            try:
                print("rotated proxy 1: %s" % describe_exit(api.rotate(first["client_id"])), file=out)
            except Refused as e:
                if e.rate_limited:
                    print("rotate proxy 1: rate limited (once a minute, 60 a day); try again later", file=out)
                elif e.no_unique_supply:
                    print("rotate proxy 1: no other unique exit in this location; it keeps its exit", file=out)
                else:
                    print("rotate proxy 1 refused: %s" % e, file=out)
        return created
    finally:
        if not keep:
            for proxy in created:
                api.remove(proxy["client_id"])
            print("removed %d proxies" % len(created), file=out)


# A loopback stand-in for the API, for --self-test and the tests: it checks the
# request shapes and answers as the server does.
class MockApi(http.server.BaseHTTPRequestHandler):
    calls = []
    lock = threading.Lock()
    serial = [0]
    # set to an `error` object to refuse rotates, e.g. {"rate_limited": True, ...}
    rotate_refusal = None

    def log_message(self, *args):
        pass

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        with self.lock:
            self.calls.append((self.path, self.headers.get("Authorization"), body))
            self.serial[0] += 1
            n = self.serial[0]
        if self.path == AUTH_CLIENT_PATH:
            config = body.get("proxy_config") or {}
            if config.get("unique_exit") is not True:
                answer = {"error": {"client_limit_exceeded": False, "message": "expected unique_exit"}}
            else:
                client = "019a5d6e-1d8e-7a3c-9c44-%012x" % n
                answer = {"client_id": client, "proxy_config_result": {
                    "keepalive_seconds": 300, "proxy_host": "proxy.example", "auth_token": "token%d" % n,
                    "https_proxy_url": "https://token%d.proxy.example:443" % n, "socks_proxy_port": 1080,
                    "exit": {"exit_id": "019a5d70-2c11-7b0e-8a51-%012x" % n, "country_code": "de",
                             "region": "Berlin", "city": "Berlin", "exit_since": "2026-11-05T19:00:00Z",
                             "state": "assigned", "replace_mode": config.get("unique_exit_replace", "auto")}}}
        elif self.path == ROTATE_EXIT_PATH:
            if self.rotate_refusal is not None:
                answer = {"error": dict(self.rotate_refusal)}
            else:
                answer = {"exit": {"exit_id": "019a5d70-2c11-7b0e-8a51-%012x" % n, "country_code": "fr",
                                   "state": "assigned", "replace_mode": "auto"}}
        elif self.path == REMOVE_CLIENT_PATH:
            answer = {}
        else:
            self.send_error(404)
            return
        raw = json.dumps(answer).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)


def mock_echo(proxy_url, echo_url):
    """The exit a mock proxy reports: one documentation address per proxy,
    the same on its HTTPS and SOCKS5 legs."""
    return "192.0.2.%s" % re.search(r"token(\d+)", proxy_url).group(1)


def start_mock():
    server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), MockApi)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    return server, "http://127.0.0.1:%d" % server.server_address[1]


def self_test(out=sys.stdout):
    server, base = start_mock()
    try:
        MockApi.calls.clear()
        api = Api(base, "self-test-root-jwt")
        # each mock proxy exits from its own documentation address
        created = run(api, 3, None, "auto", True, DEFAULT_ECHO_URL, False, out,
                      echo=mock_echo, sleep=lambda seconds: None)
        paths = [path for path, _, _ in MockApi.calls]
        assert paths.count(AUTH_CLIENT_PATH) == 3, paths
        assert paths.count(ROTATE_EXIT_PATH) == 1, paths
        assert paths.count(REMOVE_CLIENT_PATH) == 3, paths
        assert all(body["proxy_config"]["unique_exit"] is True
                   for path, _, body in MockApi.calls if path == AUTH_CLIENT_PATH)
        assert len(created) == 3
        print("self-test ok", file=out)
    finally:
        server.shutdown()
        server.server_close()


def main(argv=None):
    parser = argparse.ArgumentParser(description="Create hosted proxies with unique exit IPs (Pro).")
    parser.add_argument("--count", type=int, default=3, help="proxies to create (Pro: at most 1,000 active)")
    parser.add_argument("--country", help="two-letter country code for the exits (default: best available)")
    parser.add_argument("--replace", choices=REPLACE_MODES, help="on exit failure: auto (default) or manual")
    parser.add_argument("--rotate", action="store_true", help="rotate the first proxy's exit")
    parser.add_argument("--echo-url", default=DEFAULT_ECHO_URL, help="an IP echo reached through each proxy")
    parser.add_argument("--keep", action="store_true", help="keep the proxies instead of removing them")
    parser.add_argument("--self-test", action="store_true", help="run against a loopback mock API; no network")
    args = parser.parse_args(argv)
    if args.self_test:
        self_test()
        return 0
    if not 1 <= args.count <= PRO_ACTIVE_PROXY_CAP:
        parser.error("--count must be between 1 and %d" % PRO_ACTIVE_PROXY_CAP)
    api = Api(os.environ.get("URNETWORK_API_URL", DEFAULT_API_URL), os.environ.get("URNETWORK_ROOT_JWT", ""))
    run(api, args.count, args.country, args.replace, args.rotate, args.echo_url, args.keep, sys.stdout)
    return 0


if __name__ == "__main__":
    sys.exit(main())
