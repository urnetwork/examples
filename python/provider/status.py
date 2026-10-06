"""The provider status that every provider example shows, with the exact text
rules of PROVIDER_CONTRACT.md ("Status"): providing state, clients served, data
provided and the payout wallet, read only. Everything here is pure Python, so
the self-test checks it without the native SDK, a network or credentials.
ClientsServed is the one stateful type; it is safe for use from SDK threads."""

from dataclasses import dataclass
import json
import threading

# Shown once at start, and in every example's README. The app that integrates a
# provider owns the consent screen; this example starts without asking.
CONSENT_DISCLAIMER = """Consent disclaimer: an app that integrates a URnetwork provider must collect the user's consent before it provides.
Providing shares the user's internet connection: other URnetwork users' traffic exits through the user's device and IP address.
This example starts providing without asking, because the consent screen belongs to your app."""

# provide modes of the C ABI (URNET_PROVIDE_MODE_NONE, _NETWORK and _PUBLIC)
PROVIDE_MODE_NONE = 0
PROVIDE_MODE_NETWORK = 1
PROVIDE_MODE_PUBLIC = 3

# client limit status values (URNET_CLIENT_LIMIT_STATUS_NONE and _EXCEEDED)
CLIENT_LIMIT_STATUS_NONE = ""
CLIENT_LIMIT_STATUS_EXCEEDED = "client_limit_exceeded"

# consent_scope values of a GET /sn/wallet entry (URNET_SN_WALLET_CONSENT_SCOPE_NETWORK
# and _PROVIDER). Hotkey delegations come with a later server and SDK change,
# which adds the SDK's own constant; the app only labels the entry.
SN_WALLET_CONSENT_SCOPE_NETWORK = "network"
SN_WALLET_CONSENT_SCOPE_PROVIDER = "provider"
SN_WALLET_CONSENT_SCOPE_HOTKEY = "hotkey"

PROVIDER_STATE_STOPPED = "stopped"
# the platform disconnected this client for its network's client limit, and the
# SDK holds off reconnecting until the retry time
PROVIDER_STATE_CLIENT_LIMIT = "client limit"
PROVIDER_STATE_STARTING = "starting"
PROVIDER_STATE_PAUSED = "paused"
PROVIDER_STATE_PROVIDING = "providing"

# the payout wallet before the first wallet read finishes
PAYOUT_WALLET_CHECKING = "checking"
# the payout wallet when the first wallet read failed
PAYOUT_WALLET_UNAVAILABLE = "unavailable"
# the payout wallet when no wallet is mapped
PAYOUT_WALLET_NOT_SET = "not set"

PAYOUT_WALLET_SCOPE_HOTKEY = "hotkey"
PAYOUT_WALLET_SCOPE_PROVIDER = "this provider"
PAYOUT_WALLET_SCOPE_NETWORK = "network"
PAYOUT_WALLET_SCOPE_ANOTHER_PROVIDER = "another provider"

# Distinct clients are counted up to this many; beyond it the count is a lower
# bound, shown with a trailing "+".
CLIENTS_SERVED_LIMIT = 100 * 1000

ZERO_ID = "00000000-0000-0000-0000-000000000000"

BYTE_UNITS = ("KiB", "MiB", "GiB", "TiB", "PiB", "EiB")


@dataclass(frozen=True)
class ProviderStatus:
    """One status snapshot."""

    state: str
    # the end of the client limit hold in unix milliseconds, shown with the
    # client limit state; 0 when unknown
    client_limit_retry_time: int = 0
    clients_served: int = 0
    clients_served_at_limit: bool = False
    data_provided_byte_count: int = 0
    # a coldkey ss58 address, or one of the PAYOUT_WALLET_* texts
    payout_wallet: str = PAYOUT_WALLET_CHECKING
    # "" unless payout_wallet is an address
    payout_wallet_scope: str = ""

    def line(self) -> str:
        """The status line, for example
        "status: providing | clients served: 3 | data provided: 12.4 MiB | payout wallet: 5Grw... (network)"."""
        clients_served = str(self.clients_served)
        if self.clients_served_at_limit:
            clients_served += "+"
        payout_wallet = self.payout_wallet
        if self.payout_wallet_scope:
            payout_wallet = f"{self.payout_wallet} ({self.payout_wallet_scope})"
        status_text = provider_status_text(self.state, self.client_limit_retry_time)
        data_provided = format_byte_count(self.data_provided_byte_count)
        return f"status: {status_text} | clients served: {clients_served} | data provided: {data_provided} | payout wallet: {payout_wallet}"

    def key(self) -> tuple:
        """The fields that change rarely. A change prints a status line at once;
        the data counter alone only prints on the periodic line. The status text
        carries the client limit retry time, so a new retry time prints too."""
        return (
            provider_status_text(self.state, self.client_limit_retry_time),
            self.clients_served,
            self.clients_served_at_limit,
            self.payout_wallet,
            self.payout_wallet_scope,
        )


def provider_status_text(state: str, client_limit_retry_time: int) -> str:
    """The status field text: the state, and for the client limit state the time
    the SDK retries, for example "client limit, retry at 19:05 UTC". The retry
    time (unix milliseconds) is rounded up to the next whole minute in UTC, so
    the shown time is never before the real retry; 0 shows "client limit"."""
    if state != PROVIDER_STATE_CLIENT_LIMIT or client_limit_retry_time <= 0:
        return state
    minute_millis = 60 * 1000
    retry_minute = (client_limit_retry_time + minute_millis - 1) // minute_millis
    # unix time has no leap seconds, so every utc day is 24 * 60 minutes
    minute_of_day = retry_minute % (24 * 60)
    return f"{PROVIDER_STATE_CLIENT_LIMIT}, retry at {minute_of_day // 60:02d}:{minute_of_day % 60:02d} UTC"


def format_byte_count(byte_count: int) -> str:
    """Binary units with one decimal: "0 B", "1023 B", "1.0 KiB", "12.4 MiB". A
    value that rounds to 1024.0 moves to the next unit."""
    if byte_count < 1024:
        return f"{byte_count} B"
    value = byte_count / 1024
    unit_index = 0
    while unit_index < len(BYTE_UNITS) - 1 and 1024 <= round(value * 10) / 10:
        value /= 1024
        unit_index += 1
    return f"{value:.1f} {BYTE_UNITS[unit_index]}"


def provider_state(
    provide_mode: int,
    client_limit_status: str,
    provide_paused: bool,
    provide_enabled: bool,
    provider_connected: bool,
) -> str:
    """The providing state from the device getters, in this order: stopped unless
    the provide mode is public; client limit while the SDK holds this client off
    for its network's client limit; paused while paused; providing once the
    provider is enabled and its platform carrier is connected; starting
    otherwise."""
    if provide_mode != PROVIDE_MODE_PUBLIC:
        return PROVIDER_STATE_STOPPED
    if client_limit_status == CLIENT_LIMIT_STATUS_EXCEEDED:
        return PROVIDER_STATE_CLIENT_LIMIT
    if provide_paused:
        return PROVIDER_STATE_PAUSED
    if provide_enabled and provider_connected:
        return PROVIDER_STATE_PROVIDING
    return PROVIDER_STATE_STARTING


def payout_wallet_scope(wallet_consent_scope: str, wallet_client_id: str, client_id: str) -> str:
    """Which owner the effective payout wallet belongs to. The consent scope comes
    first: a hotkey delegation is network-level, with no client id, but is not
    the network's wallet. Otherwise by the wallet's client id: this provider's
    own mapping, the network's wallet, or another provider of the network."""
    if wallet_consent_scope == SN_WALLET_CONSENT_SCOPE_HOTKEY:
        return PAYOUT_WALLET_SCOPE_HOTKEY
    if not wallet_client_id:
        return PAYOUT_WALLET_SCOPE_NETWORK
    if wallet_client_id == client_id:
        return PAYOUT_WALLET_SCOPE_PROVIDER
    return PAYOUT_WALLET_SCOPE_ANOTHER_PROVIDER


def contract_peer_key(details: dict, receive: bool) -> str:
    """The peer of one provider contract (the C ABI's ContractDetails JSON object),
    by direction as the SDK's contract screens resolve it: the source of a
    receive (ingress) contract, the destination of a send (egress) contract. A
    path without that client id is keyed by its stream id, then by the contract
    id; "" when the contract names none of them."""

    def present(value) -> bool:
        return isinstance(value, str) and value != "" and value != ZERO_ID

    path = details.get("ContractTransferPath")
    if isinstance(path, dict):
        peer_id = path.get("SourceId") if receive else path.get("DestinationId")
        if present(peer_id):
            return peer_id
        if present(path.get("StreamId")):
            return "stream:" + path["StreamId"]
    if present(details.get("ContractId")):
        return "contract:" + details["ContractId"]
    return ""


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
    """The client limit status and its retry time (unix milliseconds) from the
    JSON of urnet_device_get_client_limit_status. The C ABI returns NULL only
    when the call cannot run, and NULL reads as no limit."""
    status = parse_json_object(status_json)
    if status is None:
        return CLIENT_LIMIT_STATUS_NONE, 0
    return json_text(status.get("Status")), json_int(status.get("RetryTime"))


def parse_data_provided_byte_count(packet_stats_json) -> int:
    """Bytes relayed for clients in both directions since the device started, from
    the JSON of urnet_device_get_provider_packet_stats; 0 when the stats are
    null."""
    packet_stats = parse_json_object(packet_stats_json)
    if packet_stats is None:
        return 0
    return json_int(packet_stats.get("RemoteEgressByteCount")) + json_int(packet_stats.get("RemoteIngressByteCount"))


class ClientsServed:
    """The distinct clients that held a contract with this provider since the app
    started. Safe for concurrent use: the SDK delivers contract details on its
    own threads."""

    def __init__(self, limit: int):
        """An empty count that keeps at most limit distinct peers."""
        self._limit = limit
        self._state_lock = threading.Lock()
        # set of contract_peer_key values
        self._peer_keys = set()
        self._at_limit = False

    def add(self, details: dict | None, receive: bool):
        """Counts the peer of one provider contract."""
        if details is None:
            return
        peer_key = contract_peer_key(details, receive)
        if not peer_key:
            return
        with self._state_lock:
            if peer_key in self._peer_keys:
                return
            if self._limit <= len(self._peer_keys):
                self._at_limit = True
                return
            self._peer_keys.add(peer_key)

    def count(self) -> tuple[int, bool]:
        """The distinct count, and whether the count stopped at the limit."""
        with self._state_lock:
            return len(self._peer_keys), self._at_limit
