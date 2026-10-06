//! The provider status that every provider example shows, with the exact text rules of
//! PROVIDER_CONTRACT.md ("Status"): providing state, clients served, data provided and the payout
//! wallet, read only. Everything here is pure, so the self-test checks it without the native SDK, a
//! network or credentials.

use std::{
    collections::HashSet,
    fmt,
    sync::{Mutex, PoisonError},
    time::{Duration, Instant},
};

use serde::Deserialize;

use crate::id::{ZERO_ID, parse_id};

/// Shown at start by the console app, next to the start control in the GUI, and in every README.
/// The app that integrates a provider owns the consent screen; this example starts without asking.
pub const CONSENT_DISCLAIMER: &str = "Consent disclaimer: an app that integrates a URnetwork provider must collect the user's consent before it provides.
Providing shares the user's internet connection: other URnetwork users' traffic exits through the user's device and IP address.
This example starts providing without asking, because the consent screen belongs to your app.";

/// Provide mode off (C ABI `URNET_PROVIDE_MODE_NONE`).
pub const PROVIDE_MODE_NONE: i64 = 0;
/// Provide to the device's own network only (`URNET_PROVIDE_MODE_NETWORK`).
pub const PROVIDE_MODE_NETWORK: i64 = 1;
/// Provide to everyone (`URNET_PROVIDE_MODE_PUBLIC`); the SDK then declares provide intent by
/// itself.
pub const PROVIDE_MODE_PUBLIC: i64 = 3;

/// No client limit hold (C ABI `URNET_CLIENT_LIMIT_STATUS_NONE`).
pub const CLIENT_LIMIT_STATUS_NONE: &str = "";
/// The platform disconnected this client for its network's client limit
/// (`URNET_CLIENT_LIMIT_STATUS_EXCEEDED`).
pub const CLIENT_LIMIT_STATUS_EXCEEDED: &str = "client_limit_exceeded";

/// The `consent_scope` of a network's hotkey delegation in `GET /sn/wallet`. Hotkey delegations
/// come with a later server and SDK change; the app only labels them.
pub const SN_WALLET_CONSENT_SCOPE_HOTKEY: &str = "hotkey";
/// The `consent_scope` of the network consent.
pub const SN_WALLET_CONSENT_SCOPE_NETWORK: &str = "network";
/// The `consent_scope` of a per-provider consent.
pub const SN_WALLET_CONSENT_SCOPE_PROVIDER: &str = "provider";

/// Distinct clients are counted up to this many; beyond it the count is a lower bound, shown with a
/// trailing `+`.
pub const CLIENTS_SERVED_LIMIT: usize = 100 * 1000;

/// How long the console waits before it repeats an unchanged status line.
pub const STATUS_REPEAT_INTERVAL: Duration = Duration::from_secs(60);

/// The providing state, from the device getters.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum ProviderState {
    /// The provide mode is not public.
    Stopped,
    /// The platform disconnected this client for its network's client limit, and the SDK holds off
    /// reconnecting until the retry time.
    ClientLimit,
    /// Public, but not yet enabled and connected to the platform.
    Starting,
    /// Providing is paused.
    Paused,
    /// Enabled and connected to the platform.
    Providing,
}

impl ProviderState {
    /// The state's text in the status line.
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Stopped => "stopped",
            Self::ClientLimit => "client limit",
            Self::Starting => "starting",
            Self::Paused => "paused",
            Self::Providing => "providing",
        }
    }
}

/// Which owner the effective payout wallet belongs to.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum PayoutWalletScope {
    /// The network's hotkey delegation.
    Hotkey,
    /// This provider client's own mapping.
    ThisProvider,
    /// The network's wallet.
    Network,
    /// Another provider client of the network.
    AnotherProvider,
}

impl PayoutWalletScope {
    /// The label after the address, without its parentheses.
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Hotkey => "hotkey",
            Self::ThisProvider => "this provider",
            Self::Network => "network",
            Self::AnotherProvider => "another provider",
        }
    }
}

/// The payout wallet as the app shows it, read only.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum PayoutWallet {
    /// Before the first wallet read finishes.
    Checking,
    /// The first wallet read failed; a later failure keeps the last value instead.
    Unavailable,
    /// No wallet is mapped.
    NotSet,
    /// The mapped coldkey (ss58) and the owner of its mapping.
    Mapped {
        coldkey_ss58: String,
        scope: PayoutWalletScope,
    },
}

impl fmt::Display for PayoutWallet {
    /// `checking`, `unavailable`, `not set`, or the address with its scope in parentheses, such as
    /// `5Grw... (network)`.
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Checking => formatter.write_str("checking"),
            Self::Unavailable => formatter.write_str("unavailable"),
            Self::NotSet => formatter.write_str("not set"),
            Self::Mapped {
                coldkey_ss58,
                scope,
            } => write!(formatter, "{coldkey_ss58} ({})", scope.as_str()),
        }
    }
}

/// One status snapshot.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ProviderStatus {
    pub state: ProviderState,
    /// The end of the client limit hold in unix milliseconds, shown with the client limit state; 0
    /// when unknown.
    pub client_limit_retry_time: i64,
    pub clients_served: usize,
    pub clients_served_at_limit: bool,
    pub data_provided_byte_count: u64,
    pub payout_wallet: PayoutWallet,
}

impl ProviderStatus {
    /// A stopped provider that has not served anyone yet and has not read its wallet.
    pub fn stopped() -> Self {
        Self {
            state: ProviderState::Stopped,
            client_limit_retry_time: 0,
            clients_served: 0,
            clients_served_at_limit: false,
            data_provided_byte_count: 0,
            payout_wallet: PayoutWallet::Checking,
        }
    }

    /// The status field, with the retry time for the client limit state.
    pub fn status_text(&self) -> String {
        provider_status_text(self.state, self.client_limit_retry_time)
    }

    /// The clients-served field, with a trailing `+` once the count stopped at its limit.
    pub fn clients_served_text(&self) -> String {
        if self.clients_served_at_limit {
            format!("{}+", self.clients_served)
        } else {
            self.clients_served.to_string()
        }
    }

    /// The data-provided field.
    pub fn data_provided_text(&self) -> String {
        format_byte_count(self.data_provided_byte_count)
    }

    /// The payout wallet field.
    pub fn payout_wallet_text(&self) -> String {
        self.payout_wallet.to_string()
    }

    /// The fields that change rarely. A change prints a status line at once; the data counter alone
    /// only prints on the periodic line. The status text carries the client limit retry time,
    /// so a new retry time prints too.
    pub fn key(&self) -> String {
        format!(
            "{}|{}|{}",
            self.status_text(),
            self.clients_served_text(),
            self.payout_wallet_text()
        )
    }
}

impl fmt::Display for ProviderStatus {
    /// The status line, for example:
    ///
    /// ```text
    /// status: providing | clients served: 3 | data provided: 12.4 MiB | payout wallet: 5Grw... (network)
    /// ```
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(
            formatter,
            "status: {} | clients served: {} | data provided: {} | payout wallet: {}",
            self.status_text(),
            self.clients_served_text(),
            self.data_provided_text(),
            self.payout_wallet_text(),
        )
    }
}

/// Decides when the console prints a status line: at once when the status text, the clients-served
/// count or the payout wallet changes, otherwise once every [`STATUS_REPEAT_INTERVAL`].
#[derive(Default)]
pub struct StatusLines {
    last_key: Option<String>,
    last_print_time: Option<Instant>,
}

impl StatusLines {
    /// The line to print for the status read at `now`, or none.
    pub fn next_line(&mut self, status: &ProviderStatus, now: Instant) -> Option<String> {
        let key = status.key();
        let changed = self.last_key.as_deref() != Some(key.as_str());
        let repeat_due = self.last_print_time.is_none_or(|last_print_time| {
            STATUS_REPEAT_INTERVAL <= now.saturating_duration_since(last_print_time)
        });
        if !changed && !repeat_due {
            return None;
        }
        self.last_key = Some(key);
        self.last_print_time = Some(now);
        Some(status.to_string())
    }
}

/// The status field text: the state, and for the client limit state the time the SDK retries, for
/// example `client limit, retry at 19:05 UTC`. The retry time (unix milliseconds) is rounded up to
/// the next whole minute in UTC, so the shown time is never before the real retry; a retry time of
/// 0 shows `client limit`.
pub fn provider_status_text(state: ProviderState, client_limit_retry_time: i64) -> String {
    if state != ProviderState::ClientLimit || client_limit_retry_time <= 0 {
        return state.as_str().to_string();
    }
    let retry_minute = (client_limit_retry_time as u64).div_ceil(60 * 1000);
    let hour = (retry_minute / 60) % 24;
    let minute = retry_minute % 60;
    format!(
        "{}, retry at {hour:02}:{minute:02} UTC",
        ProviderState::ClientLimit.as_str()
    )
}

/// Binary units with one decimal: `0 B`, `1023 B`, `1.0 KiB`, `12.4 MiB`. Values round to the
/// nearest tenth with ties to even, as Go's `%.1f` does: 1280 bytes (1.25 KiB) is `1.2 KiB` and
/// 1792 bytes (1.75 KiB) is `1.8 KiB`. Rust's `{:.1}` rounds the exact binary value that way. A
/// value that rounds to 1024.0 moves to the next unit.
pub fn format_byte_count(byte_count: u64) -> String {
    if byte_count < 1024 {
        return format!("{byte_count} B");
    }
    let units = ["KiB", "MiB", "GiB", "TiB", "PiB", "EiB"];
    let mut value = byte_count as f64 / 1024.0;
    let mut unit_index = 0;
    while unit_index < units.len() - 1 && 1024.0 <= (value * 10.0).round_ties_even() / 10.0 {
        value /= 1024.0;
        unit_index += 1;
    }
    format!("{value:.1} {}", units[unit_index])
}

/// The providing state from the device getters, in this order: stopped unless the provide mode is
/// public; client limit while the SDK holds this client off for its network's client limit; paused
/// while paused; providing once the provider is enabled and its platform carrier is connected;
/// starting otherwise.
pub fn provider_state(
    provide_mode: i64,
    client_limit_status: &str,
    provide_paused: bool,
    provide_enabled: bool,
    provider_connected: bool,
) -> ProviderState {
    if provide_mode != PROVIDE_MODE_PUBLIC {
        ProviderState::Stopped
    } else if client_limit_status == CLIENT_LIMIT_STATUS_EXCEEDED {
        ProviderState::ClientLimit
    } else if provide_paused {
        ProviderState::Paused
    } else if provide_enabled && provider_connected {
        ProviderState::Providing
    } else {
        ProviderState::Starting
    }
}

/// Which owner the effective payout wallet belongs to. The consent scope comes first: a hotkey
/// delegation is network-level, with no client id, but is not the network's wallet. Otherwise by
/// the wallet's client id: this provider's own mapping, the network's wallet, or another provider
/// of the network.
pub fn payout_wallet_scope(
    wallet_consent_scope: Option<&str>,
    wallet_client_id: Option<&str>,
    client_id: &str,
) -> PayoutWalletScope {
    let wallet_client_id = wallet_client_id.unwrap_or("");
    if wallet_consent_scope == Some(SN_WALLET_CONSENT_SCOPE_HOTKEY) {
        PayoutWalletScope::Hotkey
    } else if wallet_client_id.is_empty() {
        PayoutWalletScope::Network
    } else if wallet_client_id == client_id
        || parse_id(wallet_client_id).as_deref() == Some(client_id)
    {
        PayoutWalletScope::ThisProvider
    } else {
        PayoutWalletScope::AnotherProvider
    }
}

/// One provider contract, as the C ABI's contract details listeners deliver it (Go field names).
#[derive(Clone, Debug, Default, Deserialize)]
#[serde(rename_all = "PascalCase")]
pub struct ContractDetails {
    pub contract_id: Option<String>,
    pub contract_transfer_path: Option<TransferPath>,
}

/// The source, destination and stream of a contract.
#[derive(Clone, Debug, Default, Deserialize)]
#[serde(rename_all = "PascalCase")]
pub struct TransferPath {
    pub source_id: Option<String>,
    pub destination_id: Option<String>,
    pub stream_id: Option<String>,
}

/// The peer of one provider contract, by direction as the SDK's contract screens resolve it: the
/// source of a receive (ingress) contract, the destination of a send (egress) contract. A path
/// without that client id is keyed by its stream id, then by the contract id. None when the
/// contract names no id at all.
pub fn contract_peer_key(details: &ContractDetails, receive: bool) -> Option<String> {
    // an id that parses and is not the all-zero id, in canonical form
    let present = |id: &Option<String>| id.as_deref().and_then(parse_id).filter(|id| id != ZERO_ID);
    if let Some(path) = &details.contract_transfer_path {
        let peer_id = if receive {
            &path.source_id
        } else {
            &path.destination_id
        };
        if let Some(peer_id) = present(peer_id) {
            return Some(peer_id);
        }
        if let Some(stream_id) = present(&path.stream_id) {
            return Some(format!("stream:{stream_id}"));
        }
    }
    present(&details.contract_id).map(|contract_id| format!("contract:{contract_id}"))
}

/// The distinct clients that held a contract with this provider since the app started. Safe for
/// concurrent use: the SDK delivers contract details on its own threads.
pub struct ClientsServed {
    limit: usize,
    state: Mutex<ClientsServedState>,
}

/// The counted peers, guarded by [`ClientsServed::state`].
#[derive(Default)]
struct ClientsServedState {
    /// set of [`contract_peer_key`] values
    peer_keys: HashSet<String>,
    at_limit: bool,
}

impl ClientsServed {
    /// An empty count that keeps at most `limit` distinct peers.
    pub fn new(limit: usize) -> Self {
        Self {
            limit,
            state: Mutex::new(ClientsServedState::default()),
        }
    }

    /// Counts the peer of one provider contract.
    pub fn add(&self, details: &ContractDetails, receive: bool) {
        let Some(peer_key) = contract_peer_key(details, receive) else {
            return;
        };
        let mut state = self.state.lock().unwrap_or_else(PoisonError::into_inner);
        if state.peer_keys.contains(&peer_key) {
            return;
        }
        if self.limit <= state.peer_keys.len() {
            state.at_limit = true;
            return;
        }
        state.peer_keys.insert(peer_key);
    }

    /// The distinct count, and whether the count stopped at the limit.
    pub fn count(&self) -> (usize, bool) {
        let state = self.state.lock().unwrap_or_else(PoisonError::into_inner);
        (state.peer_keys.len(), state.at_limit)
    }
}
