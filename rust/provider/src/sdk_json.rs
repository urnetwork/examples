//! The C ABI values that the provider reads, parsed from their JSON (Go field names,
//! PROVIDER_CONTRACT.md "App lifecycle"). Pure functions over copied strings, so the self-test
//! checks them without the native runtime.

use serde::Deserialize;

use crate::status::{ContractDetails, PayoutWallet, payout_wallet_scope};

/// `urnet_device_get_client_limit_status`.
#[derive(Default, Deserialize)]
#[serde(rename_all = "PascalCase")]
struct ClientLimitStatus {
    #[serde(default)]
    status: String,
    #[serde(default)]
    retry_time: i64,
}

/// The two counters of `urnet_device_get_provider_packet_stats` that the app shows.
#[derive(Deserialize)]
#[serde(rename_all = "PascalCase")]
struct PacketStats {
    #[serde(default)]
    remote_egress_byte_count: u64,
    #[serde(default)]
    remote_ingress_byte_count: u64,
}

/// `urnet_device_local_get_sn_wallet`.
#[derive(Deserialize)]
struct SnWallet {
    #[serde(default)]
    coldkey_ss58: String,
    client_id: Option<String>,
    consent_scope: Option<String>,
}

/// The part of an `SnGetWalletResult` that tells whether the read succeeded: a missing or null
/// `error`.
#[derive(Deserialize)]
struct SnGetWalletResult {
    error: Option<serde_json::Value>,
}

/// The client limit status and its retry time (unix milliseconds). NULL, which the C ABI returns
/// only when the call cannot run, reads as no limit.
pub fn parse_client_limit_status(json: Option<&str>) -> (String, i64) {
    let status = json
        .and_then(|json| serde_json::from_str::<ClientLimitStatus>(json).ok())
        .unwrap_or_default();
    (status.status, status.retry_time)
}

/// Bytes relayed for clients in both directions since the device started; 0 when the stats are
/// null.
pub fn parse_data_provided_byte_count(json: Option<&str>) -> u64 {
    json.and_then(|json| serde_json::from_str::<PacketStats>(json).ok())
        .map(|packet_stats| {
            packet_stats
                .remote_egress_byte_count
                .saturating_add(packet_stats.remote_ingress_byte_count)
        })
        .unwrap_or(0)
}

/// The effective payout wallet that the SDK cached, labeled for `client_id`: not set when there is
/// none.
pub fn parse_payout_wallet(json: Option<&str>, client_id: &str) -> PayoutWallet {
    match json.and_then(|json| serde_json::from_str::<SnWallet>(json).ok()) {
        Some(wallet) if !wallet.coldkey_ss58.is_empty() => PayoutWallet::Mapped {
            scope: payout_wallet_scope(
                wallet.consent_scope.as_deref(),
                wallet.client_id.as_deref(),
                client_id,
            ),
            coldkey_ss58: wallet.coldkey_ss58,
        },
        _ => PayoutWallet::NotSet,
    }
}

/// Whether a payout wallet read succeeded: no error, a result, and no API error in it.
pub fn wallet_read_succeeded(result_json: Option<&str>, error: Option<&str>) -> bool {
    if error.is_some() {
        return false;
    }
    result_json
        .and_then(|json| serde_json::from_str::<SnGetWalletResult>(json).ok())
        .is_some_and(|result| result.error.is_none())
}

/// One provider contract from a contract details listener; none when the JSON is not a contract.
pub fn parse_contract_details(json: &str) -> Option<ContractDetails> {
    serde_json::from_str(json).ok()
}
