"""The status that every embed example shows, with the exact rules of
EMBED_CONTRACT.md ("Status"): the status field, data this month and data total.
Pure Python, so the self-test checks every rule and golden vector without the
native SDK, a network or credentials."""

from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
import json
import re

from caps import CAPPED_REASON_MONTHLY, CAPPED_REASON_TOTAL, CapReading

# the status field, in rule order. Console examples never show the first two.
STATUS_SIGNED_OUT = "signed out"
STATUS_STOPPED = "stopped"
# the platform disconnected this client for its network's concurrent client
# limit, and the SDK holds its platform connections until the retry time
STATUS_CLIENT_LIMIT = "client limit"
STATUS_PAUSED = "paused"
STATUS_DATA_CAP_REACHED = "data cap reached"
STATUS_CONNECTED = "connected"
STATUS_CONNECTING = "connecting"

# the data fields before a reading, when the first reading failed, and for a
# cap that is not set
DATA_CHECKING = "checking"
DATA_UNAVAILABLE = "unavailable"
DATA_NO_CAP = "no cap"

# client limit status values (URNET_CLIENT_LIMIT_STATUS_NONE and _EXCEEDED)
CLIENT_LIMIT_STATUS_NONE = ""
CLIENT_LIMIT_STATUS_EXCEEDED = "client_limit_exceeded"

# decimal units, because data plans and the Embed plan's budget are sold in them
DECIMAL_UNITS = ("kB", "MB", "GB", "TB", "PB", "EB")

# RFC 3339 with optional fractional seconds, as Go writes time.Time in JSON
RFC3339_PATTERN = re.compile(
    r"\A(\d{4})-(\d{2})-(\d{2})[Tt](\d{2}):(\d{2}):(\d{2})(?:\.(\d+))?(?:([Zz])|([+-])(\d{2}):(\d{2}))\Z"
)

MINUTE_MILLIS = 60 * 1000
MINUTES_PER_DAY = 24 * 60


def format_byte_count(byte_count: int) -> str:
    """Decimal units with one decimal: "0 B", "999 B", "1.0 kB", "1.2 GB". Ties
    round to even, as Go's %.1f does, so 1250 bytes is "1.2 kB"; a value that
    rounds to 1000.0 moves to the next unit, so 999999 bytes is "1.0 MB"."""
    if byte_count < 1000:
        return f"{byte_count} B"
    for unit_index, unit in enumerate(DECIMAL_UNITS):
        # int / int is the correctly rounded quotient, and format rounds its exact
        # binary value half to even
        text = f"{byte_count / 1000 ** (unit_index + 1):.1f}"
        if float(text) < 1000.0 or unit_index == len(DECIMAL_UNITS) - 1:
            return f"{text} {unit}"
    raise AssertionError("unreachable")


def reset_time_text(monthly_period_end: str) -> str | None:
    """"resets YYYY-MM-DD HH:MM UTC" for a monthly period end in RFC 3339: in UTC,
    with the seconds rounded up to the next whole minute, so the shown time is
    never before the reset. None when the text does not parse."""
    match = RFC3339_PATTERN.match(monthly_period_end) if isinstance(monthly_period_end, str) else None
    if match is None:
        return None
    year, month, day, hour, minute, second = (int(group) for group in match.groups()[:6])
    fraction = match.group(7) or ""
    try:
        if match.group(8):
            offset = timedelta(0)
        else:
            offset_hours, offset_minutes = int(match.group(10)), int(match.group(11))
            if 23 < offset_hours or 59 < offset_minutes:
                return None
            offset = timedelta(hours=offset_hours, minutes=offset_minutes)
            if match.group(9) == "-":
                offset = -offset
        instant = datetime(year, month, day, hour, minute, second, tzinfo=timezone(offset))
        reset = instant.astimezone(timezone.utc).replace(second=0)
        if second or fraction.strip("0"):
            reset += timedelta(minutes=1)
    except (ValueError, OverflowError):
        return None
    return f"resets {reset:%Y-%m-%d %H:%M} UTC"


def client_limit_text(client_limit_retry_time: int) -> str:
    """"client limit, retry at HH:MM UTC": the retry time (unix milliseconds)
    rounded up to the next whole minute in UTC, as the provider contract rounds
    it, so the shown time is never before the retry; 0 shows "client limit"."""
    if client_limit_retry_time <= 0:
        return STATUS_CLIENT_LIMIT
    retry_minute = (client_limit_retry_time + MINUTE_MILLIS - 1) // MINUTE_MILLIS
    # unix time has no leap seconds, so every utc day is 24 * 60 minutes
    minute_of_day = retry_minute % MINUTES_PER_DAY
    return f"{STATUS_CLIENT_LIMIT}, retry at {minute_of_day // 60:02d}:{minute_of_day % 60:02d} UTC"


def capped_limit(reading: CapReading) -> int | None:
    """The limit of the cap that capped_reason names; None for an unknown reason
    or an unset cap."""
    if reading.capped_reason == CAPPED_REASON_MONTHLY:
        return reading.monthly_byte_limit
    if reading.capped_reason == CAPPED_REASON_TOTAL:
        return reading.total_byte_limit
    return None


def embed_status(
    started: bool,
    signed_out: bool,
    client_limit_status: str,
    client_limit_retry_time: int,
    reading: CapReading | None,
    providers_added: int,
) -> str:
    """The status field: the first rule that applies. signed out; stopped; client
    limit while the SDK holds this client off; paused while the latest reading
    is capped and the cap that capped_reason names is 0; data cap reached while
    capped, with the reset time for the monthly cap; connected once the window
    has a provider added; connecting otherwise."""
    if signed_out:
        return STATUS_SIGNED_OUT
    if not started:
        return STATUS_STOPPED
    if client_limit_status == CLIENT_LIMIT_STATUS_EXCEEDED:
        return client_limit_text(client_limit_retry_time)
    if reading is not None and reading.capped:
        if capped_limit(reading) == 0:
            return STATUS_PAUSED
        if reading.capped_reason == CAPPED_REASON_MONTHLY:
            reset = reset_time_text(reading.monthly_period_end)
            if reset is not None:
                return f"{STATUS_DATA_CAP_REACHED}, {reset}"
        return STATUS_DATA_CAP_REACHED
    if 1 <= providers_added:
        return STATUS_CONNECTED
    return STATUS_CONNECTING


class CapState:
    """The cap readings behind the data fields: none yet, a first reading that
    failed, or the latest successful reading, which a later failure keeps. Owned
    by the run loop's thread."""

    def __init__(self, reading: CapReading | None = None):
        """No reading yet, or a first reading such as the token server's data_cap."""
        self.reading = reading
        self.failed = False

    def record(self, reading: CapReading | None):
        """Records a reading; None is a failed read."""
        if reading is not None:
            self.reading = reading
            self.failed = False
        elif self.reading is None:
            self.failed = True


def data_field_text(cap_state: CapState, monthly: bool) -> str:
    """Data this month (monthly) or data total: checking until the first reading;
    unavailable when it failed; no cap for an unset cap, never a used count;
    otherwise "<used> of <limit>"."""
    reading = cap_state.reading
    if reading is None:
        return DATA_UNAVAILABLE if cap_state.failed else DATA_CHECKING
    if monthly:
        limit, used = reading.monthly_byte_limit, reading.monthly_used_byte_count
    else:
        limit, used = reading.total_byte_limit, reading.total_used_byte_count
    if limit is None:
        return DATA_NO_CAP
    return f"{format_byte_count(used)} of {format_byte_count(limit)}"


@dataclass(frozen=True)
class EmbedStatus:
    """One status snapshot."""

    status: str
    data_this_month: str
    data_total: str

    def line(self) -> str:
        """The console status line."""
        return f"status: {self.status} | data this month: {self.data_this_month} | data total: {self.data_total}"


def status_snapshot(
    started: bool,
    signed_out: bool,
    client_limit_status: str,
    client_limit_retry_time: int,
    cap_state: CapState,
    providers_added: int,
) -> EmbedStatus:
    """The three status fields."""
    return EmbedStatus(
        status=embed_status(
            started,
            signed_out,
            client_limit_status,
            client_limit_retry_time,
            cap_state.reading,
            providers_added,
        ),
        data_this_month=data_field_text(cap_state, monthly=True),
        data_total=data_field_text(cap_state, monthly=False),
    )


def parse_json_object(text) -> dict | None:
    """A JSON object from the C ABI (str or bytes), or None for NULL, JSON null or
    anything that is not an object."""
    if not text:
        return None
    try:
        value = json.loads(text)
    except ValueError:
        return None
    return value if isinstance(value, dict) else None


def json_int(value) -> int:
    """A JSON integer field, or 0 when it is missing or not an integer."""
    if isinstance(value, int) and not isinstance(value, bool):
        return value
    return 0


def json_text(value) -> str:
    """A JSON string field, or "" when it is missing or not a string."""
    return value if isinstance(value, str) else ""


def parse_client_limit_status(status_json) -> tuple[str, int]:
    """The client limit status and its retry time (unix milliseconds) from the JSON
    of urnet_device_get_client_limit_status; NULL reads as no limit."""
    status = parse_json_object(status_json)
    if status is None:
        return CLIENT_LIMIT_STATUS_NONE, 0
    return json_text(status.get("Status")), json_int(status.get("RetryTime"))


def parse_providers_added(window_status_json) -> int:
    """ProviderStateAdded from the JSON of urnet_device_get_window_status; 0 before
    the window exists (NULL)."""
    window_status = parse_json_object(window_status_json)
    if window_status is None:
        return 0
    return json_int(window_status.get("ProviderStateAdded"))
