//! The credential-free self-test (PROVIDER_CONTRACT.md, "Self-test"). It checks the disclaimer, the
//! status text, the providing state, the payout wallet labels, the clients-served count, the C ABI
//! JSON that the status reads and the installation state files without a network, credentials, a
//! device or the native SDK runtime. `--self-test` runs every check; `cargo test` runs each one as
//! a test (`tests/self_test.rs`).

use std::{
    fs,
    path::Path,
    time::{Duration, Instant},
};

use base64::{Engine, engine::general_purpose::URL_SAFE_NO_PAD};
use sha2::{Digest, Sha256};

use crate::{
    id::{ZERO_ID, parse_id},
    sdk_json::{
        parse_client_limit_status, parse_contract_details, parse_data_provided_byte_count,
        parse_payout_wallet, wallet_read_succeeded,
    },
    state::{
        CLIENT_JWT_FILE_NAME, IDENTITY_FILE_NAME, ProviderIdentity, check_state_dir, key_material,
        load_or_create_instance_id, load_provider_config, load_provider_identity,
        parse_client_jwt_client_id, read_private_file, save_provider_identity, write_private_file,
    },
    status::{
        CLIENT_LIMIT_STATUS_EXCEEDED, CLIENT_LIMIT_STATUS_NONE, CLIENTS_SERVED_LIMIT,
        CONSENT_DISCLAIMER, ClientsServed, ContractDetails, PROVIDE_MODE_NETWORK,
        PROVIDE_MODE_NONE, PROVIDE_MODE_PUBLIC, PayoutWallet, PayoutWalletScope, ProviderState,
        ProviderStatus, SN_WALLET_CONSENT_SCOPE_HOTKEY, SN_WALLET_CONSENT_SCOPE_NETWORK,
        SN_WALLET_CONSENT_SCOPE_PROVIDER, StatusLines, TransferPath, contract_peer_key,
        format_byte_count, payout_wallet_scope, provider_state, provider_status_text,
    },
};

/// sha-256 of the consent disclaimer (utf-8, lf line breaks, no trailing newline), published in
/// PROVIDER_CONTRACT.md for every example to check.
pub const CONSENT_DISCLAIMER_SHA256: &str =
    "83edee1e45cccd5deb6b86755cc5f6a91b6ade26e7eefc1e7670b5833a95502c";

/// The public Substrate development account, used only as test data.
const TEST_COLDKEY_SS58: &str = "5GrwvaEF5zXb26Fz9rcQpDWS57CtERHpNehXCPcNoHGKutQY";

/// The provider client of the test vectors.
const TEST_CLIENT_ID: &str = "11111111-1111-1111-1111-111111111111";

/// A failed check's message.
pub type CheckResult = Result<(), String>;

/// One self-test check.
pub type Check = fn() -> CheckResult;

/// Every check with its name, in the order `--self-test` runs them.
pub const CHECKS: &[(&str, Check)] = &[
    ("consent disclaimer", check_consent_disclaimer),
    ("byte count", check_format_byte_count),
    ("status text", check_status_text),
    ("status lines", check_status_lines),
    ("status key", check_status_key),
    ("provider state", check_provider_state),
    ("payout wallet scope", check_payout_wallet_scope),
    ("clients served", check_clients_served),
    ("sdk json", check_sdk_json),
    ("client jwt claims", check_client_jwt_claims),
    ("state files", check_state_files),
    ("provider config", check_provider_config),
];

/// Runs every check and returns the first failure.
pub fn run_self_test() -> CheckResult {
    for (name, check) in CHECKS {
        check().map_err(|message| format!("{name}: {message}"))?;
    }
    Ok(())
}

/// The disclaimer is the contract's exact text.
pub fn check_consent_disclaimer() -> CheckResult {
    let digest = Sha256::digest(CONSENT_DISCLAIMER.as_bytes());
    let digest_hex: String = digest.iter().map(|byte| format!("{byte:02x}")).collect();
    if digest_hex != CONSENT_DISCLAIMER_SHA256 {
        return Err("consent disclaimer differs from PROVIDER_CONTRACT.md".to_string());
    }
    Ok(())
}

/// Byte counts use binary units with one decimal, rounded to the nearest tenth with ties to even.
pub fn check_format_byte_count() -> CheckResult {
    let cases: &[(u64, &str)] = &[
        (0, "0 B"),
        (1023, "1023 B"),
        (1024, "1.0 KiB"),
        (1536, "1.5 KiB"),
        // exact halves round to the even tenth: 1.25 KiB and 1.75 KiB
        (1280, "1.2 KiB"),
        (1792, "1.8 KiB"),
        // 1.25 MiB
        (1310720, "1.2 MiB"),
        (1048575, "1.0 MiB"),
        (13002342, "12.4 MiB"),
        (5 * 1024 * 1024 * 1024, "5.0 GiB"),
        (3 * 1024 * 1024 * 1024 * 1024, "3.0 TiB"),
    ];
    for &(byte_count, text) in cases {
        let formatted = format_byte_count(byte_count);
        if formatted != text {
            return Err(format!(
                "byte count {byte_count} formats as {formatted:?}, want {text:?}"
            ));
        }
    }
    Ok(())
}

/// The client limit status names the SDK's retry time in UTC, rounded up to the next whole minute;
/// other states never show it.
pub fn check_status_text() -> CheckResult {
    let cases: &[(ProviderState, i64, &str)] = &[
        // 2026-10-06 19:05:00.000 UTC, exactly on a minute
        (
            ProviderState::ClientLimit,
            1791313500000,
            "client limit, retry at 19:05 UTC",
        ),
        // 19:04:00.001 rounds up, so the shown time is never before the retry
        (
            ProviderState::ClientLimit,
            1791313440001,
            "client limit, retry at 19:05 UTC",
        ),
        // no retry time
        (ProviderState::ClientLimit, 0, "client limit"),
        // 23:59:00.001 rolls over the hour and the day
        (
            ProviderState::ClientLimit,
            1791331140001,
            "client limit, retry at 00:00 UTC",
        ),
        (ProviderState::Starting, 1791313500000, "starting"),
    ];
    for &(state, client_limit_retry_time, text) in cases {
        let status_text = provider_status_text(state, client_limit_retry_time);
        if status_text != text {
            return Err(format!(
                "status text {status_text:?} for {state:?} retrying at {client_limit_retry_time}, want {text:?}"
            ));
        }
    }
    Ok(())
}

/// A status with the given fields and the rest zero.
fn test_status(state: ProviderState, payout_wallet: PayoutWallet) -> ProviderStatus {
    ProviderStatus {
        state,
        payout_wallet,
        ..ProviderStatus::stopped()
    }
}

/// The test coldkey with a scope.
fn test_wallet(scope: PayoutWalletScope) -> PayoutWallet {
    PayoutWallet::Mapped {
        coldkey_ss58: TEST_COLDKEY_SS58.to_string(),
        scope,
    }
}

/// The status line matches the contract's golden lines.
pub fn check_status_lines() -> CheckResult {
    let providing = |scope| ProviderStatus {
        clients_served: 3,
        data_provided_byte_count: 13002342,
        ..test_status(ProviderState::Providing, test_wallet(scope))
    };
    let cases = [
        (
            test_status(ProviderState::Starting, PayoutWallet::Checking),
            "status: starting | clients served: 0 | data provided: 0 B | payout wallet: checking",
        ),
        (
            providing(PayoutWalletScope::Network),
            "status: providing | clients served: 3 | data provided: 12.4 MiB | payout wallet: 5GrwvaEF5zXb26Fz9rcQpDWS57CtERHpNehXCPcNoHGKutQY (network)",
        ),
        (
            providing(PayoutWalletScope::Hotkey),
            "status: providing | clients served: 3 | data provided: 12.4 MiB | payout wallet: 5GrwvaEF5zXb26Fz9rcQpDWS57CtERHpNehXCPcNoHGKutQY (hotkey)",
        ),
        (
            ProviderStatus {
                clients_served: CLIENTS_SERVED_LIMIT,
                clients_served_at_limit: true,
                data_provided_byte_count: 1536,
                ..test_status(
                    ProviderState::Paused,
                    test_wallet(PayoutWalletScope::ThisProvider),
                )
            },
            "status: paused | clients served: 100000+ | data provided: 1.5 KiB | payout wallet: 5GrwvaEF5zXb26Fz9rcQpDWS57CtERHpNehXCPcNoHGKutQY (this provider)",
        ),
        (
            test_status(ProviderState::Stopped, PayoutWallet::NotSet),
            "status: stopped | clients served: 0 | data provided: 0 B | payout wallet: not set",
        ),
        (
            ProviderStatus {
                client_limit_retry_time: 1791313500000,
                ..test_status(
                    ProviderState::ClientLimit,
                    test_wallet(PayoutWalletScope::Network),
                )
            },
            "status: client limit, retry at 19:05 UTC | clients served: 0 | data provided: 0 B | payout wallet: 5GrwvaEF5zXb26Fz9rcQpDWS57CtERHpNehXCPcNoHGKutQY (network)",
        ),
    ];
    for (status, line) in cases {
        let status_line = status.to_string();
        if status_line != line {
            return Err(format!("status line {status_line:?}, want {line:?}"));
        }
    }
    Ok(())
}

/// A change of the status text prints a line at once, including a new client limit retry time; the
/// data counter alone does not, and an unchanged line repeats once a minute.
pub fn check_status_key() -> CheckResult {
    let status = ProviderStatus {
        client_limit_retry_time: 1791313500000,
        ..test_status(ProviderState::ClientLimit, PayoutWallet::Checking)
    };
    // 19:25 UTC
    let retried = ProviderStatus {
        client_limit_retry_time: 1791314700000,
        ..status.clone()
    };
    if status.key() == retried.key() {
        return Err("a new client limit retry time does not print a status line".to_string());
    }
    let counted = ProviderStatus {
        data_provided_byte_count: 1536,
        ..status.clone()
    };
    if status.key() != counted.key() {
        return Err("the data counter alone prints a status line".to_string());
    }

    let start_time = Instant::now();
    let at = |seconds: u64| start_time + Duration::from_secs(seconds);
    let mut status_lines = StatusLines::default();
    let printed = [
        status_lines.next_line(&status, at(0)).is_some(),
        status_lines.next_line(&counted, at(1)).is_some(),
        status_lines.next_line(&retried, at(2)).is_some(),
        status_lines.next_line(&retried, at(3)).is_some(),
        status_lines.next_line(&retried, at(62)).is_some(),
    ];
    // first line, data counter alone, new retry time, unchanged, a minute later
    if printed != [true, false, true, false, true] {
        return Err(format!("status lines printed as {printed:?}"));
    }
    Ok(())
}

/// The providing state follows the provide mode, client limit, pause, enable and connected rules,
/// in that order.
pub fn check_provider_state() -> CheckResult {
    // provide mode, client limit status, paused, enabled, connected, state
    let cases: &[(i64, &str, bool, bool, bool, ProviderState)] = &[
        (
            PROVIDE_MODE_NONE,
            CLIENT_LIMIT_STATUS_NONE,
            false,
            false,
            false,
            ProviderState::Stopped,
        ),
        (
            PROVIDE_MODE_NETWORK,
            CLIENT_LIMIT_STATUS_NONE,
            false,
            true,
            true,
            ProviderState::Stopped,
        ),
        (
            PROVIDE_MODE_PUBLIC,
            CLIENT_LIMIT_STATUS_NONE,
            false,
            true,
            false,
            ProviderState::Starting,
        ),
        (
            PROVIDE_MODE_PUBLIC,
            CLIENT_LIMIT_STATUS_NONE,
            false,
            false,
            true,
            ProviderState::Starting,
        ),
        (
            PROVIDE_MODE_PUBLIC,
            CLIENT_LIMIT_STATUS_NONE,
            false,
            true,
            true,
            ProviderState::Providing,
        ),
        (
            PROVIDE_MODE_PUBLIC,
            CLIENT_LIMIT_STATUS_NONE,
            true,
            true,
            true,
            ProviderState::Paused,
        ),
        // the client limit comes after stopped and before every other state
        (
            PROVIDE_MODE_NETWORK,
            CLIENT_LIMIT_STATUS_EXCEEDED,
            false,
            true,
            true,
            ProviderState::Stopped,
        ),
        (
            PROVIDE_MODE_PUBLIC,
            CLIENT_LIMIT_STATUS_EXCEEDED,
            true,
            true,
            true,
            ProviderState::ClientLimit,
        ),
        (
            PROVIDE_MODE_PUBLIC,
            CLIENT_LIMIT_STATUS_EXCEEDED,
            false,
            false,
            false,
            ProviderState::ClientLimit,
        ),
        (
            PROVIDE_MODE_PUBLIC,
            CLIENT_LIMIT_STATUS_NONE,
            true,
            true,
            true,
            ProviderState::Paused,
        ),
    ];
    for &(
        provide_mode,
        client_limit_status,
        provide_paused,
        provide_enabled,
        provider_connected,
        want,
    ) in cases
    {
        let state = provider_state(
            provide_mode,
            client_limit_status,
            provide_paused,
            provide_enabled,
            provider_connected,
        );
        if state != want {
            return Err(format!(
                "provider state {state:?} for mode {provide_mode}, client limit {client_limit_status:?}, paused \
                 {provide_paused}, enabled {provide_enabled}, connected {provider_connected}; want {want:?}"
            ));
        }
    }
    Ok(())
}

/// The payout wallet is labeled by its consent scope first, then by the owner of its mapping.
pub fn check_payout_wallet_scope() -> CheckResult {
    let other_client_id = "22222222-2222-2222-2222-222222222222";
    let cases: &[(Option<&str>, Option<&str>, PayoutWalletScope)] = &[
        // a hotkey delegation is network-level but not the network's wallet
        (
            Some(SN_WALLET_CONSENT_SCOPE_HOTKEY),
            None,
            PayoutWalletScope::Hotkey,
        ),
        (
            Some(SN_WALLET_CONSENT_SCOPE_HOTKEY),
            Some(TEST_CLIENT_ID),
            PayoutWalletScope::Hotkey,
        ),
        (
            Some(SN_WALLET_CONSENT_SCOPE_NETWORK),
            None,
            PayoutWalletScope::Network,
        ),
        (None, None, PayoutWalletScope::Network),
        (
            Some(SN_WALLET_CONSENT_SCOPE_PROVIDER),
            Some(TEST_CLIENT_ID),
            PayoutWalletScope::ThisProvider,
        ),
        (None, Some(TEST_CLIENT_ID), PayoutWalletScope::ThisProvider),
        (
            Some(SN_WALLET_CONSENT_SCOPE_PROVIDER),
            Some(other_client_id),
            PayoutWalletScope::AnotherProvider,
        ),
    ];
    for &(wallet_consent_scope, wallet_client_id, want) in cases {
        let scope = payout_wallet_scope(wallet_consent_scope, wallet_client_id, TEST_CLIENT_ID);
        if scope != want {
            return Err(format!(
                "payout wallet scope {scope:?} for consent scope {wallet_consent_scope:?} and client \
                 {wallet_client_id:?}, want {want:?}"
            ));
        }
    }
    Ok(())
}

/// A contract with the given ids.
fn test_contract(
    contract_id: &str,
    source_id: Option<&str>,
    destination_id: Option<&str>,
    stream_id: Option<&str>,
) -> ContractDetails {
    ContractDetails {
        contract_id: Some(contract_id.to_string()),
        contract_transfer_path: Some(TransferPath {
            source_id: source_id.map(str::to_string),
            destination_id: destination_id.map(str::to_string),
            stream_id: stream_id.map(str::to_string),
        }),
    }
}

/// Contract peers resolve by direction and count once per client, up to the limit.
pub fn check_clients_served() -> CheckResult {
    let provider = Some(TEST_CLIENT_ID);
    let client_a = "22222222-2222-2222-2222-222222222222";
    let client_b = "33333333-3333-3333-3333-333333333333";
    let stream = "44444444-4444-4444-4444-444444444444";

    // the peer is the source of a receive contract and the destination of a send contract
    let key_cases = [
        (
            test_contract(
                "55555555-5555-5555-5555-555555555555",
                Some(client_a),
                provider,
                None,
            ),
            true,
            Some(client_a.to_string()),
        ),
        (
            test_contract(
                "66666666-6666-6666-6666-666666666666",
                provider,
                Some(client_a),
                None,
            ),
            false,
            Some(client_a.to_string()),
        ),
        (
            test_contract(
                "77777777-7777-7777-7777-777777777777",
                Some(ZERO_ID),
                provider,
                Some(stream),
            ),
            true,
            Some(format!("stream:{stream}")),
        ),
        (
            ContractDetails {
                contract_id: Some("88888888-8888-8888-8888-888888888888".to_string()),
                contract_transfer_path: None,
            },
            true,
            Some("contract:88888888-8888-8888-8888-888888888888".to_string()),
        ),
    ];
    for (details, receive, want) in key_cases {
        let peer_key = contract_peer_key(&details, receive);
        if peer_key != want {
            return Err(format!("contract peer key {peer_key:?}, want {want:?}"));
        }
    }

    // both directions of one client count once
    let clients_served = ClientsServed::new(2);
    clients_served.add(
        &test_contract(
            "55555555-5555-5555-5555-555555555555",
            Some(client_a),
            provider,
            None,
        ),
        true,
    );
    clients_served.add(
        &test_contract(
            "66666666-6666-6666-6666-666666666666",
            provider,
            Some(client_a),
            None,
        ),
        false,
    );
    clients_served.add(
        &test_contract(
            "99999999-9999-9999-9999-999999999999",
            Some(client_a),
            provider,
            None,
        ),
        true,
    );
    if clients_served.count() != (1, false) {
        return Err(format!(
            "one client counted as {:?}",
            clients_served.count()
        ));
    }
    clients_served.add(
        &test_contract(
            "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
            Some(client_b),
            provider,
            None,
        ),
        true,
    );
    if clients_served.count() != (2, false) {
        return Err(format!(
            "two clients counted as {:?}",
            clients_served.count()
        ));
    }
    // a third distinct peer reaches the limit of 2
    clients_served.add(
        &test_contract(
            "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
            Some(ZERO_ID),
            provider,
            Some(stream),
        ),
        true,
    );
    if clients_served.count() != (2, true) {
        return Err(format!("limited count {:?}", clients_served.count()));
    }
    Ok(())
}

/// The C ABI JSON that the status reads, in the contract's shapes: contract details, the client
/// limit status, the provider packet stats, the payout wallet and the wallet read result.
pub fn check_sdk_json() -> CheckResult {
    let details = parse_contract_details(
        r#"{"ContractId": "33333333-3333-3333-3333-333333333333", "ContractTransferPath": {"SourceId": "22222222-2222-2222-2222-222222222222", "DestinationId": "11111111-1111-1111-1111-111111111111", "StreamId": null}, "Status": "open"}"#,
    )
    .ok_or("the contract's contract details JSON does not parse")?;
    if contract_peer_key(&details, true).as_deref() != Some("22222222-2222-2222-2222-222222222222")
    {
        return Err("the contract details JSON resolves another receive peer".to_string());
    }

    let client_limit_cases = [
        (
            Some(r#"{"Status": "client_limit_exceeded", "RetryTime": 1791313500000}"#),
            (CLIENT_LIMIT_STATUS_EXCEEDED.to_string(), 1791313500000),
        ),
        (
            Some(r#"{"Status": "", "RetryTime": 0}"#),
            (CLIENT_LIMIT_STATUS_NONE.to_string(), 0),
        ),
        // NULL only when the call cannot run: no limit
        (None, (CLIENT_LIMIT_STATUS_NONE.to_string(), 0)),
    ];
    for (json, want) in client_limit_cases {
        let client_limit_status = parse_client_limit_status(json);
        if client_limit_status != want {
            return Err(format!(
                "client limit status {client_limit_status:?} for {json:?}, want {want:?}"
            ));
        }
    }

    let data_cases = [
        (
            Some(
                r#"{"RemoteEgressByteCount": 5, "RemoteIngressByteCount": 7, "LocalEgressByteCount": 100}"#,
            ),
            12,
        ),
        (None, 0),
        (Some("null"), 0),
    ];
    for (json, want) in data_cases {
        let byte_count = parse_data_provided_byte_count(json);
        if byte_count != want {
            return Err(format!(
                "data provided {byte_count} for {json:?}, want {want}"
            ));
        }
    }

    let wallet_cases = [
        (
            Some(
                r#"{"coldkey_ss58": "5GrwvaEF5zXb26Fz9rcQpDWS57CtERHpNehXCPcNoHGKutQY", "client_id": "11111111-1111-1111-1111-111111111111", "set_at_millis": 1, "consent_scope": "provider"}"#,
            ),
            test_wallet(PayoutWalletScope::ThisProvider),
        ),
        (
            Some(
                r#"{"coldkey_ss58": "5GrwvaEF5zXb26Fz9rcQpDWS57CtERHpNehXCPcNoHGKutQY", "set_at_millis": 1, "consent_scope": "network"}"#,
            ),
            test_wallet(PayoutWalletScope::Network),
        ),
        (
            Some(
                r#"{"coldkey_ss58": "5GrwvaEF5zXb26Fz9rcQpDWS57CtERHpNehXCPcNoHGKutQY", "set_at_millis": 1, "consent_scope": "hotkey"}"#,
            ),
            test_wallet(PayoutWalletScope::Hotkey),
        ),
        (
            Some(r#"{"coldkey_ss58": "", "set_at_millis": 0}"#),
            PayoutWallet::NotSet,
        ),
        (None, PayoutWallet::NotSet),
    ];
    for (json, want) in wallet_cases {
        let payout_wallet = parse_payout_wallet(json, TEST_CLIENT_ID);
        if payout_wallet != want {
            return Err(format!(
                "payout wallet {payout_wallet} for {json:?}, want {want}"
            ));
        }
    }

    let wallet_read_cases = [
        (Some(r#"{"wallet": null}"#), None, true),
        (Some(r#"{"wallet": null, "error": null}"#), None, true),
        (
            Some(r#"{"error": {"message": "unavailable"}}"#),
            None,
            false,
        ),
        (None, Some("context deadline exceeded"), false),
        (None, None, false),
    ];
    for (result_json, error, want) in wallet_read_cases {
        if wallet_read_succeeded(result_json, error) != want {
            return Err(format!(
                "wallet read {result_json:?} with error {error:?} is not {want}"
            ));
        }
    }
    Ok(())
}

/// A synthetic, unsigned JWT with the given payload JSON.
pub fn self_test_jwt(payload_json: &str) -> String {
    format!("e30.{}.test", URL_SAFE_NO_PAD.encode(payload_json))
}

/// Only a JWT with a valid `client_id` claim is a client credential.
pub fn check_client_jwt_claims() -> CheckResult {
    let client_jwt = self_test_jwt(
        r#"{"client_id":"11111111-1111-1111-1111-111111111111","network_id":"22222222-2222-2222-2222-222222222222"}"#,
    );
    match parse_client_jwt_client_id(&client_jwt) {
        Ok(client_id) if client_id == TEST_CLIENT_ID => {}
        other => return Err(format!("client jwt claim {other:?}")),
    }
    let invalid_jwts = [
        String::new(),
        "not-a-jwt".to_string(),
        // a network jwt has no client_id claim
        self_test_jwt(r#"{"network_id":"22222222-2222-2222-2222-222222222222"}"#),
        self_test_jwt(r#"{"client_id":"not-a-uuid"}"#),
        "e30.%%%.test".to_string(),
    ];
    for invalid_jwt in invalid_jwts {
        if parse_client_jwt_client_id(&invalid_jwt).is_ok() {
            return Err(format!("invalid client jwt {invalid_jwt:?} accepted"));
        }
    }
    Ok(())
}

/// A private temporary state directory, removed on drop.
fn temp_state_dir() -> Result<tempfile::TempDir, String> {
    let state_dir = tempfile::Builder::new()
        .prefix("ur-provider-self-test-")
        .tempdir()
        .map_err(|error| error.to_string())?;
    set_mode(state_dir.path(), 0o700)?;
    Ok(state_dir)
}

/// Sets POSIX permissions; nothing on Windows.
fn set_mode(path: &Path, mode: u32) -> CheckResult {
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        fs::set_permissions(path, fs::Permissions::from_mode(mode))
            .map_err(|error| error.to_string())?;
    }
    #[cfg(not(unix))]
    {
        let _ = (path, mode);
    }
    Ok(())
}

/// State files are private, replaced atomically, created once and bound to their client.
pub fn check_state_files() -> CheckResult {
    let temp_dir = temp_state_dir()?;
    let state_dir = temp_dir.path();

    // an atomic private write leaves only the file
    let path = state_dir.join(CLIENT_JWT_FILE_NAME);
    write_private_file(&path, b"first\n").map_err(|error| error.to_string())?;
    write_private_file(&path, b"second\n").map_err(|error| error.to_string())?;
    let data = read_private_file(&path).map_err(|error| error.to_string())?;
    if data != b"second\n" {
        return Err(format!(
            "private file round trip {:?}",
            String::from_utf8_lossy(&data)
        ));
    }
    let file_names: Vec<String> = fs::read_dir(state_dir)
        .map_err(|error| error.to_string())?
        .filter_map(|entry| {
            entry
                .ok()
                .map(|entry| entry.file_name().to_string_lossy().into_owned())
        })
        .collect();
    if file_names != [CLIENT_JWT_FILE_NAME] {
        return Err(format!("a replacement left {file_names:?}"));
    }
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        let mode = fs::metadata(&path)
            .map_err(|error| error.to_string())?
            .permissions()
            .mode()
            & 0o777;
        if mode != 0o600 {
            return Err(format!("private file mode {mode:o}"));
        }
        // a file or directory that others can read is refused
        set_mode(&path, 0o644)?;
        if read_private_file(&path).is_ok() {
            return Err("a group-readable credential file was accepted".to_string());
        }
        set_mode(&path, 0o600)?;
        // a symlink is refused, even to a private file
        let link_path = state_dir.join("linked.jwt");
        std::os::unix::fs::symlink(&path, &link_path).map_err(|error| error.to_string())?;
        if read_private_file(&link_path).is_ok() {
            return Err("a symlinked credential file was accepted".to_string());
        }
        fs::remove_file(&link_path).map_err(|error| error.to_string())?;
        set_mode(state_dir, 0o755)?;
        if check_state_dir(state_dir).is_ok() {
            return Err("a group-readable state directory was accepted".to_string());
        }
        set_mode(state_dir, 0o700)?;
    }
    check_state_dir(state_dir).map_err(|error| error.to_string())?;

    // the instance id is created once and reused
    let instance_id = load_or_create_instance_id(state_dir).map_err(|error| error.to_string())?;
    if parse_id(&instance_id).as_deref() != Some(instance_id.as_str()) {
        return Err(format!(
            "instance id {instance_id:?} is not a canonical uuid"
        ));
    }
    let again = load_or_create_instance_id(state_dir).map_err(|error| error.to_string())?;
    if again != instance_id {
        return Err(format!(
            "instance id changed from {instance_id:?} to {again:?}"
        ));
    }

    // the identity belongs to its client
    let identity = ProviderIdentity::new(
        TEST_CLIENT_ID.to_string(),
        vec![1; 32],
        b"synthetic certificate".to_vec(),
        b"synthetic private key".to_vec(),
        Vec::new(),
    );
    save_provider_identity(state_dir, &identity).map_err(|error| error.to_string())?;
    let loaded = load_provider_identity(state_dir, TEST_CLIENT_ID)
        .map_err(|error| error.to_string())?
        .ok_or("the saved identity did not load")?;
    if loaded != identity {
        return Err("identity round trip differs".to_string());
    }
    match key_material(Some(&loaded)) {
        Some(key_material)
            if key_material.client_key_seed == identity.client_key_seed.as_slice()
                && key_material.provide_tls_private_key_pem
                    == identity.provide_tls_private_key_pem.as_slice() => {}
        _ => return Err("identity key material differs".to_string()),
    }
    let other = load_provider_identity(state_dir, "22222222-2222-2222-2222-222222222222")
        .map_err(|error| error.to_string())?;
    if other.is_some() {
        return Err("another client's identity was used".to_string());
    }
    // without an identity the device gets no key material and makes a new identity
    if key_material(other.as_ref()).is_some() {
        return Err("a first run passes key material".to_string());
    }
    write_private_file(&state_dir.join(IDENTITY_FILE_NAME), br#"{"version":1}"#)
        .map_err(|error| error.to_string())?;
    if load_provider_identity(state_dir, TEST_CLIENT_ID).is_ok() {
        return Err("an invalid identity was accepted".to_string());
    }
    Ok(())
}

/// A missing or incomplete installation state is refused; a first run loads.
pub fn check_provider_config() -> CheckResult {
    if load_provider_config(Path::new("")).is_ok() {
        return Err("a missing state directory was accepted".to_string());
    }
    if load_provider_config(Path::new("relative/state")).is_ok() {
        return Err("a relative state directory was accepted".to_string());
    }
    let temp_dir = temp_state_dir()?;
    let state_dir = temp_dir.path();
    if load_provider_config(state_dir).is_ok() {
        return Err("a state directory without client.jwt was accepted".to_string());
    }
    // a network jwt has no client_id claim
    let network_jwt = self_test_jwt(r#"{"network_id":"22222222-2222-2222-2222-222222222222"}"#);
    write_private_file(
        &state_dir.join(CLIENT_JWT_FILE_NAME),
        format!("{network_jwt}\n").as_bytes(),
    )
    .map_err(|error| error.to_string())?;
    if load_provider_config(state_dir).is_ok() {
        return Err("a network jwt was accepted".to_string());
    }
    let client_jwt = self_test_jwt(r#"{"client_id":"11111111-1111-1111-1111-111111111111"}"#);
    write_private_file(
        &state_dir.join(CLIENT_JWT_FILE_NAME),
        format!("{client_jwt}\n").as_bytes(),
    )
    .map_err(|error| error.to_string())?;
    let config = load_provider_config(state_dir).map_err(|error| error.to_string())?;
    if config.client_jwt != client_jwt
        || config.client_id != TEST_CLIENT_ID
        || config.instance_id.is_empty()
        || config.identity.is_some()
    {
        return Err(format!("first-run configuration differs: {config:?}"));
    }
    Ok(())
}
