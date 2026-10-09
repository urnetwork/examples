"""The client's data caps (EMBED_CONTRACT.md, "Backend: per-user data caps"): the
cap object, and the app's read of its own caps with its client JWT. The
backend sets caps with the root credential; the app only reads them. Pure
Python apart from the transport it is given (transport.py)."""

from dataclasses import dataclass
import json

# GET with the client JWT reads that client's own caps
CAP_ROUTE = "/network/client-data-cap"

CAPPED_REASON_MONTHLY = "monthly"
CAPPED_REASON_TOTAL = "total"

# The server refuses the cap read with this message while the team has not
# enabled Embed for the network (EMBED_CONTRACT.md, "Embed enablement").
EMBED_NOT_ENABLED_MESSAGE = "Embed isn't enabled for this network."


class EmbedNotEnabled:
    """What read_own_caps returns for the Embed-not-enabled refusal. Unlike a
    failed read, it clears the last reading, so both data fields read
    unavailable."""


EMBED_NOT_ENABLED = EmbedNotEnabled()


@dataclass(frozen=True)
class CapReading:
    """One cap object. A limit is None when that cap is not set; a used count is
    in bytes."""

    client_id: str
    monthly_byte_limit: int | None
    monthly_used_byte_count: int
    # RFC 3339 in UTC: when the monthly usage resets
    monthly_period_end: str
    total_byte_limit: int | None
    total_used_byte_count: int
    # true while a cap is reached: the client gets no new transfer contracts
    capped: bool
    # "monthly", "total", or "" when the client is not capped
    capped_reason: str


# a field value of the wrong type
_INVALID = object()


def _byte_limit(value):
    """A byte limit: None for null or absent, an integer of 0 or more, or _INVALID."""
    if value is None:
        return None
    if isinstance(value, int) and not isinstance(value, bool) and 0 <= value:
        return value
    return _INVALID


def _byte_count(value):
    """A used byte count: 0 for null or absent, an integer of 0 or more, or _INVALID."""
    if value is None:
        return 0
    return _byte_limit(value)


def _text(value):
    """A text field: "" for null or absent, the string, or _INVALID."""
    if value is None:
        return ""
    return value if isinstance(value, str) else _INVALID


def parse_cap_object(fields) -> CapReading | None:
    """The cap object in parsed JSON, or None when it is not one: not an object,
    an error answer, or a field of the wrong type. A null or absent limit is no
    cap, and a null or absent used count is 0. An unknown capped_reason is kept
    as it is: the status reads it as capped without a reset time."""
    if not isinstance(fields, dict) or fields.get("error") is not None:
        return None
    values = {
        "client_id": _text(fields.get("client_id")),
        "monthly_byte_limit": _byte_limit(fields.get("monthly_byte_limit")),
        "monthly_used_byte_count": _byte_count(fields.get("monthly_used_byte_count")),
        "monthly_period_end": _text(fields.get("monthly_period_end")),
        "total_byte_limit": _byte_limit(fields.get("total_byte_limit")),
        "total_used_byte_count": _byte_count(fields.get("total_used_byte_count")),
        "capped_reason": _text(fields.get("capped_reason")),
    }
    capped = fields.get("capped")
    if capped is None:
        capped = False
    if not isinstance(capped, bool) or any(value is _INVALID for value in values.values()):
        return None
    return CapReading(capped=capped, **values)


def is_embed_not_enabled(fields) -> bool:
    """Whether parsed JSON is the Embed-not-enabled refusal,
    {"error": {"message": "Embed isn't enabled for this network."}}."""
    error = fields.get("error") if isinstance(fields, dict) else None
    return isinstance(error, dict) and error.get("message") == EMBED_NOT_ENABLED_MESSAGE


def parse_cap_answer(answer: bytes) -> CapReading | None:
    """The cap object in an answer body, or None."""
    try:
        return parse_cap_object(json.loads(answer))
    except ValueError:
        return None


def read_own_caps(api_origin: str, client_jwt: str, transport) -> CapReading | EmbedNotEnabled | None:
    """GET /network/client-data-cap with the client JWT: this client's own caps.
    None for any failure: the API unreachable, an HTTP error (a server without
    the cap routes answers 404), or an answer that is not a cap object;
    EMBED_NOT_ENABLED for the Embed-not-enabled refusal."""
    headers = {"Authorization": "Bearer " + client_jwt, "Accept": "application/json"}
    try:
        status, answer = transport("GET", api_origin + CAP_ROUTE, headers, None)
    except (OSError, ValueError):
        return None
    if status != 200:
        return None
    try:
        fields = json.loads(answer)
    except ValueError:
        return None
    if is_embed_not_enabled(fields):
        return EMBED_NOT_ENABLED
    return parse_cap_object(fields)
