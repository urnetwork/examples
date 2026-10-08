"""HTTP for the embed app: the origin rule shared by the token server URL and the
API URL, and one small JSON client over urllib that refuses redirects and
bounds the answer. The app passes a transport around as a callable, so the
self-test runs the request and answer handling against a stand-in without a
socket.

A transport is called as transport(method, url, headers, body) and returns
(status, answer bytes), also for an HTTP error status. It raises OSError when
the server cannot be reached."""

import urllib.error
import urllib.parse
import urllib.request

from state import ConfigError

DEFAULT_API_URL = "https://api.bringyour.com"

# the largest answer the app reads
ANSWER_BYTE_LIMIT = 1024 * 1024

# seconds for one request
REQUEST_TIMEOUT_SECONDS = 15

LOOPBACK_HOSTS = ("localhost", "127.0.0.1", "::1")


def check_origin(url: str, setting: str) -> str:
    """The origin of url, without a trailing slash: an HTTPS origin, or explicit
    loopback HTTP (localhost, 127.0.0.1, [::1]) for local testing, with no
    credentials, path, query or fragment. Raises ConfigError naming the
    setting."""
    try:
        parts = urllib.parse.urlsplit(url)
        _ = parts.port
    except ValueError:
        raise ConfigError(f"{setting} is not a valid URL") from None
    loopback_http = parts.scheme == "http" and parts.hostname in LOOPBACK_HOSTS
    if (
        not parts.hostname
        or parts.username is not None
        or parts.password is not None
        or parts.path not in ("", "/")
        or parts.query
        or parts.fragment
        or "?" in url
        or "#" in url
        or (parts.scheme != "https" and not loopback_http)
    ):
        raise ConfigError(f"{setting} must be an HTTPS origin; HTTP is allowed only for localhost, 127.0.0.1 or [::1]")
    return f"{parts.scheme}://{parts.netloc}"


class NoRedirect(urllib.request.HTTPRedirectHandler):
    """Refuses redirects: a redirect could carry the bearer credential to another
    origin."""

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def urllib_transport(method: str, url: str, headers: dict, body: bytes | None) -> tuple[int, bytes]:
    """Sends one request and returns the status and the answer. Raises OSError
    when the server cannot be reached or the answer is too large."""
    request = urllib.request.Request(url, data=body, headers=headers, method=method)
    opener = urllib.request.build_opener(NoRedirect)
    try:
        response = opener.open(request, timeout=REQUEST_TIMEOUT_SECONDS)
    except urllib.error.HTTPError as error:
        # an error status still has an answer, such as the token server's error object
        response = error
    try:
        status = response.getcode()
        answer = response.read(ANSWER_BYTE_LIMIT + 1)
    finally:
        response.close()
    if ANSWER_BYTE_LIMIT < len(answer):
        raise OSError("the answer is too large")
    return status, answer
