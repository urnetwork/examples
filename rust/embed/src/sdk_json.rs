//! The C ABI values that the embedded Device's status reads, parsed from their JSON (Go field
//! names, EMBED_CONTRACT.md "App lifecycle"). Pure functions over copied strings, so the self-test
//! checks them without the native runtime.

use serde::Deserialize;

/// `urnet_device_get_client_limit_status`.
#[derive(Default, Deserialize)]
#[serde(rename_all = "PascalCase")]
struct ClientLimitStatus {
    #[serde(default)]
    status: String,
    #[serde(default)]
    retry_time: i64,
}

/// The part of `urnet_device_get_window_status` that the status reads.
#[derive(Deserialize)]
#[serde(rename_all = "PascalCase")]
struct WindowStatus {
    #[serde(default)]
    provider_state_added: i64,
}

/// The client limit status and its retry time (unix milliseconds). NULL reads as no limit.
pub fn parse_client_limit_status(json: Option<&str>) -> (String, i64) {
    let status = json
        .and_then(|json| serde_json::from_str::<ClientLimitStatus>(json).ok())
        .unwrap_or_default();
    (status.status, status.retry_time)
}

/// The window's providers added (`ProviderStateAdded`); 0 before the window exists (NULL) or for a
/// value that does not parse.
pub fn parse_providers_added(json: Option<&str>) -> i64 {
    json.and_then(|json| serde_json::from_str::<WindowStatus>(json).ok())
        .map(|window_status| window_status.provider_state_added)
        .unwrap_or(0)
}

/// The SDK's app kind for `GetLicenses` on the host OS (EMBED_CONTRACT.md, "License").
pub fn licenses_app_kind() -> &'static str {
    if cfg!(any(target_os = "macos", target_os = "ios")) {
        "apple"
    } else if cfg!(target_os = "windows") {
        "windows"
    } else if cfg!(target_os = "android") {
        "android"
    } else {
        "linux"
    }
}
