"""Obtaining the client JWT (EMBED_CONTRACT.md, "Obtaining the client JWT").

fetch_client_jwt is the one function to replace with your own sign-in. It posts
the installation's instance-id to the token server's POST /urnetwork/client-token
with the demo session as the bearer token, checks that the answer's
by_client_jwt carries a client_id claim equal to its client_id, and saves the
token as client.jwt. The app fetches at every start, so a start reissues the
client. Pure Python apart from the transport it is given (transport.py)."""

from dataclasses import dataclass
import json

from caps import CapReading, parse_cap_object
from state import ConfigError, parse_client_jwt_client_id, parse_uuid, save_client_jwt

TOKEN_ROUTE = "/urnetwork/client-token"

# the longest token server error message the app shows
ERROR_MESSAGE_LIMIT = 300


class TokenServerRefused(Exception):
    """The token server refused this installation: 401 unauthorized, or 409
    installation_limit or client_limit. Restarting does not help (exit 78)."""


class TokenServerError(Exception):
    """The token server could not be reached, failed, or answered something
    invalid (exit 1)."""


@dataclass(frozen=True)
class FetchedClient:
    """A token server answer that passed its checks."""

    client_jwt: str
    client_id: str
    # the client's cap object, the app's first cap reading; None when the token
    # server could not read it
    data_cap: CapReading | None


def fetch_client_jwt(
    state_dir: str,
    instance_id: str,
    token_server_origin: str,
    demo_session: str,
    transport,
) -> FetchedClient:
    """Obtains this installation's client JWT from the token server and saves it as
    client.jwt. Raises TokenServerRefused or TokenServerError; the message never
    carries the session or a token."""
    body = json.dumps({"installation_id": instance_id}).encode()
    headers = {
        "Authorization": "Bearer " + demo_session,
        "Content-Type": "application/json",
        "Accept": "application/json",
    }
    try:
        status, answer = transport("POST", token_server_origin + TOKEN_ROUTE, headers, body)
    except OSError as error:
        raise TokenServerError(f"could not reach the token server: {error}") from None
    message = error_message(answer)
    if status in (401, 409):
        raise TokenServerRefused(message or f"the token server refused this installation (HTTP {status})")
    if status != 200:
        raise TokenServerError(message or f"the token server failed (HTTP {status})")

    try:
        fields = json.loads(answer)
    except ValueError:
        fields = None
    if not isinstance(fields, dict):
        raise TokenServerError("the token server's answer is not a JSON object")
    client_id = parse_uuid(fields.get("client_id"))
    client_jwt = fields.get("by_client_jwt")
    if client_id is None or not isinstance(client_jwt, str) or not client_jwt:
        raise TokenServerError("the token server's answer has no client_id or by_client_jwt")
    try:
        claim_client_id = parse_client_jwt_client_id(client_jwt)
    except ConfigError:
        raise TokenServerError("the token server's by_client_jwt is not a scoped client JWT") from None
    if claim_client_id != client_id:
        raise TokenServerError("the token server's client_id does not match the client_id claim of its client JWT")
    try:
        save_client_jwt(state_dir, client_jwt)
    except OSError as error:
        raise TokenServerError(f"save client.jwt: {error}") from None
    return FetchedClient(client_jwt=client_jwt, client_id=client_id, data_cap=parse_cap_object(fields.get("data_cap")))


def error_message(answer: bytes) -> str:
    """The error.message of a token server error answer on one bounded line, or ""."""
    try:
        fields = json.loads(answer)
    except ValueError:
        return ""
    error = fields.get("error") if isinstance(fields, dict) else None
    message = error.get("message") if isinstance(error, dict) else None
    if not isinstance(message, str):
        return ""
    return " ".join(message.split())[:ERROR_MESSAGE_LIMIT]
