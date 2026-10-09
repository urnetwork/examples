//! The credential-free self-test (EMBED_CONTRACT.md, "Self-test"). It checks the status text
//! vectors and rules, the data fields, the cap object, the C ABI JSON that the status reads, the
//! client JWT claim, the token fetch and the cap read against a loopback stand-in, the installation
//! state files and the configuration errors, without a network, credentials, a device or the native
//! SDK runtime. `--self-test` runs every check; `cargo test` runs each one as a test
//! (`tests/self_test.rs`).

use std::{
    fs,
    net::TcpListener,
    path::Path,
    time::{Duration, Instant},
};

use base64::{Engine, engine::general_purpose::URL_SAFE_NO_PAD};
use serde_json::json;

use crate::{
    EXIT_CONFIG, EXIT_FAILURE,
    caps::{
        CapReadError, CapReading, DataCap, is_embed_not_enabled, parse_data_cap,
        parse_data_cap_json, read_own_data_cap,
    },
    config::{StartError, api_origin_from_setting, load_embed_config, token_server_from_settings},
    http::{DEFAULT_API_ORIGIN, parse_origin},
    id::parse_id,
    sdk_json::{parse_client_limit_status, parse_providers_added},
    session::{CAP_READ_INTERVAL, CAP_READ_MIN_INTERVAL, CapReadSchedule, STATUS_POLL_INTERVAL},
    stand_in::{StandInAnswer, StandInServer},
    state::{
        CLIENT_JWT_FILE_NAME, INSTANCE_ID_FILE_NAME, TokenServerFile, check_state_dir,
        load_client_jwt, load_or_create_instance_id, load_token_server_file,
        parse_client_jwt_client_id, read_private_file, save_token_server_file, write_private_file,
    },
    status::{
        CLIENT_LIMIT_STATUS_EXCEEDED, CLIENT_LIMIT_STATUS_NONE, DataField, EmbedStatus,
        STATUS_REPEAT_INTERVAL, StatusInputs, StatusLines, client_limit_text, data_field_text,
        format_byte_count, parse_rfc3339, reset_time_text, start_line, status_text,
    },
    token::{TokenError, TokenServer, fetch_client_jwt, parse_token_answer},
};

/// The client of the test vectors.
const TEST_CLIENT_ID: &str = "11111111-1111-1111-1111-111111111111";

/// A demo session token of the right length, used only as test data.
const TEST_SESSION: &str = "synthetic-demo-session-token-0123456789";

/// A failed check's message.
pub type CheckResult = Result<(), String>;

/// One self-test check.
pub type Check = fn() -> CheckResult;

/// Every check with its name, in the order `--self-test` runs them.
pub const CHECKS: &[(&str, Check)] = &[
    ("byte count", check_format_byte_count),
    ("reset time", check_reset_time),
    ("client limit text", check_client_limit_text),
    ("status lines", check_status_lines),
    ("status rules", check_status_rules),
    ("data fields", check_data_fields),
    ("cap object", check_cap_object),
    ("sdk json", check_sdk_json),
    ("client jwt claims", check_client_jwt_claims),
    ("origins", check_origins),
    ("token fetch", check_token_fetch),
    ("cap read", check_cap_read),
    ("cap read schedule", check_cap_read_schedule),
    ("status line cadence", check_status_line_cadence),
    ("state files", check_state_files),
    ("configuration", check_configuration),
    ("start line", check_start_line),
];

/// Runs every check and returns the first failure.
pub fn run_self_test() -> CheckResult {
    for (name, check) in CHECKS {
        check().map_err(|message| format!("{name}: {message}"))?;
    }
    Ok(())
}

/// A cap object for the vectors.
pub fn test_cap(
    monthly_byte_limit: Option<u64>,
    monthly_used_byte_count: u64,
    total_byte_limit: Option<u64>,
    total_used_byte_count: u64,
    capped_reason: &str,
    monthly_period_end: &str,
) -> DataCap {
    DataCap {
        client_id: TEST_CLIENT_ID.to_string(),
        monthly_byte_limit,
        monthly_used_byte_count,
        monthly_period_start: Some("2026-10-01T00:00:00Z".to_string()),
        monthly_period_end: Some(monthly_period_end.to_string()),
        total_byte_limit,
        total_used_byte_count,
        total_period_start: Some("2026-09-15T00:00:00Z".to_string()),
        capped: !capped_reason.is_empty(),
        capped_reason: capped_reason.to_string(),
    }
}

/// A started console device with a cap reading and providers added.
fn inputs(cap_reading: CapReading, providers_added: i64) -> StatusInputs {
    StatusInputs {
        cap_reading,
        providers_added,
        ..StatusInputs::started()
    }
}

/// The start line that the console app prints once the device runs.
pub fn check_start_line() -> CheckResult {
    let client_id = "11111111-1111-1111-1111-111111111111";
    let instance_id = "33333333-3333-3333-3333-333333333333";
    let line = start_line(client_id, instance_id);
    if line != format!("embed client {client_id}, installation {instance_id}") {
        return Err(format!("the start line is {line:?}"));
    }
    Ok(())
}

/// Byte counts use decimal units with one decimal, rounded to the nearest tenth with ties to even
/// on the exact integer, moving to the next unit when the rounded value reaches 1000.0.
pub fn check_format_byte_count() -> CheckResult {
    let cases: &[(u64, &str)] = &[
        (0, "0 B"),
        (999, "999 B"),
        (1000, "1.0 kB"),
        (999949, "999.9 kB"),
        // exact halves round to the even tenth on the integer; rounding the binary value of 1.05,
        // just above 1.05, would give 1.1
        (1050, "1.0 kB"),
        (1150, "1.2 kB"),
        (1250, "1.2 kB"),
        (1750, "1.8 kB"),
        // 999.95 kB ties to the even 1000.0 kB and moves to the next unit, as does 999.999 kB
        (999950, "1.0 MB"),
        (999999, "1.0 MB"),
        (1234567890, "1.2 GB"),
        (5000000000, "5.0 GB"),
        (10000000000, "10.0 GB"),
        (3000000000000, "3.0 TB"),
        (9223372036854775807, "9.2 EB"),
        (u64::MAX, "18.4 EB"),
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

/// The monthly reset time is `monthly_period_end` in UTC, rounded up to the next whole minute.
pub fn check_reset_time() -> CheckResult {
    let cases: &[(&str, Option<&str>)] = &[
        ("2026-11-01T00:00:00Z", Some("resets 2026-11-01 00:00 UTC")),
        // rounds up, so the shown time is never before the real reset
        (
            "2026-10-31T23:59:00.001Z",
            Some("resets 2026-11-01 00:00 UTC"),
        ),
        // converted to UTC
        (
            "2026-10-31T19:00:00-05:00",
            Some("resets 2026-11-01 00:00 UTC"),
        ),
        (
            "2026-12-01T05:30:00+05:30",
            Some("resets 2026-12-01 00:00 UTC"),
        ),
        // a year boundary and a leap day
        (
            "2026-12-31T23:59:59.999Z",
            Some("resets 2027-01-01 00:00 UTC"),
        ),
        ("2028-03-01T00:00:00Z", Some("resets 2028-03-01 00:00 UTC")),
        ("2028-02-29T23:00:00Z", Some("resets 2028-02-29 23:00 UTC")),
        ("2026-02-29T00:00:00Z", None),
        ("2026-11-01", None),
        ("2026-11-01T00:00:00", None),
        ("2026-13-01T00:00:00Z", None),
        ("not a time", None),
        ("", None),
    ];
    for &(period_end, want) in cases {
        let text = reset_time_text(period_end);
        if text.as_deref() != want {
            return Err(format!(
                "reset time for {period_end:?} is {text:?}, want {want:?}"
            ));
        }
    }
    // fractions round up only when they are not zero
    match parse_rfc3339("2026-11-01T00:00:00.000000000Z") {
        Some((_, 0)) => {}
        other => return Err(format!("a zero fraction parsed as {other:?}")),
    }
    Ok(())
}

/// The client limit text names the SDK's retry time in UTC, rounded up to the next whole minute.
pub fn check_client_limit_text() -> CheckResult {
    let cases: &[(i64, &str)] = &[
        // 2026-10-06 19:05:00.000 UTC, exactly on a minute
        (1791313500000, "client limit, retry at 19:05 UTC"),
        // 19:04:00.001 rounds up
        (1791313440001, "client limit, retry at 19:05 UTC"),
        (0, "client limit"),
        // 23:59:00.001 rolls over the hour and the day
        (1791331140001, "client limit, retry at 00:00 UTC"),
    ];
    for &(retry_time, text) in cases {
        let formatted = client_limit_text(retry_time);
        if formatted != text {
            return Err(format!(
                "client limit text for {retry_time} is {formatted:?}, want {text:?}"
            ));
        }
    }
    Ok(())
}

/// A cap reading after the Embed-not-enabled refusal.
fn after_refusal(mut cap_reading: CapReading) -> CapReading {
    cap_reading.apply(Err(CapReadError::EmbedNotEnabled));
    cap_reading
}

/// The contract's status line vectors.
pub fn check_status_lines() -> CheckResult {
    let monthly_end = "2026-11-01T00:00:00Z";
    let client_limited = StatusInputs {
        client_limit_status: CLIENT_LIMIT_STATUS_EXCEEDED.to_string(),
        client_limit_retry_time: 1791313500000,
        ..inputs(
            CapReading::Read(test_cap(Some(5000000000), 0, None, 0, "", monthly_end)),
            3,
        )
    };
    let cases = [
        (
            inputs(CapReading::Checking, 0),
            "status: connecting | data this month: checking | data total: checking",
        ),
        (
            inputs(
                CapReading::Read(test_cap(
                    Some(5000000000),
                    1234567890,
                    None,
                    0,
                    "",
                    monthly_end,
                )),
                2,
            ),
            "status: connected | data this month: 1.2 GB of 5.0 GB | data total: no cap",
        ),
        (
            inputs(CapReading::Unavailable, 1),
            "status: connected | data this month: unavailable | data total: unavailable",
        ),
        // the first reading answers the Embed-not-enabled refusal
        (
            inputs(after_refusal(CapReading::Checking), 1),
            "status: connected | data this month: unavailable | data total: unavailable",
        ),
        // a capped monthly reading, then the refusal
        (
            inputs(
                after_refusal(CapReading::Read(test_cap(
                    Some(5000000000),
                    5000000000,
                    None,
                    0,
                    "monthly",
                    monthly_end,
                ))),
                1,
            ),
            "status: connected | data this month: unavailable | data total: unavailable",
        ),
        (
            inputs(
                CapReading::Read(test_cap(
                    Some(5000000000),
                    5000000000,
                    None,
                    0,
                    "monthly",
                    monthly_end,
                )),
                3,
            ),
            "status: data cap reached, resets 2026-11-01 00:00 UTC | data this month: 5.0 GB of 5.0 GB | data total: no cap",
        ),
        (
            inputs(
                CapReading::Read(test_cap(
                    None,
                    0,
                    Some(10000000000),
                    10000000000,
                    "total",
                    monthly_end,
                )),
                3,
            ),
            "status: data cap reached | data this month: no cap | data total: 10.0 GB of 10.0 GB",
        ),
        (
            inputs(
                CapReading::Read(test_cap(Some(0), 0, None, 0, "monthly", monthly_end)),
                3,
            ),
            "status: paused | data this month: 0 B of 0 B | data total: no cap",
        ),
        (
            client_limited,
            "status: client limit, retry at 19:05 UTC | data this month: 0 B of 5.0 GB | data total: no cap",
        ),
    ];
    for (status_inputs, line) in cases {
        let formatted = EmbedStatus::new(&status_inputs).to_string();
        if formatted != line {
            return Err(format!("status line {formatted:?}, want {line:?}"));
        }
    }
    Ok(())
}

/// The status rules apply in their order: signed out, stopped, client limit, paused, data cap
/// reached, connected, connecting.
pub fn check_status_rules() -> CheckResult {
    let monthly_end = "2026-11-01T00:00:00Z";
    let rule = |started: bool,
                signed_out: bool,
                client_limit: Option<i64>,
                cap_reading: CapReading,
                providers_added: i64| StatusInputs {
        started,
        signed_out,
        client_limit_status: client_limit
            .map(|_| CLIENT_LIMIT_STATUS_EXCEEDED)
            .unwrap_or(CLIENT_LIMIT_STATUS_NONE)
            .to_string(),
        client_limit_retry_time: client_limit.unwrap_or(0),
        cap_reading,
        providers_added,
    };
    let cases = [
        (rule(false, false, None, CapReading::Checking, 0), "stopped"),
        (
            rule(false, true, None, CapReading::Checking, 0),
            "signed out",
        ),
        // client limit before paused
        (
            rule(
                true,
                false,
                Some(1791313500000),
                CapReading::Read(test_cap(Some(0), 0, None, 0, "monthly", monthly_end)),
                3,
            ),
            "client limit, retry at 19:05 UTC",
        ),
        (
            rule(
                true,
                false,
                None,
                CapReading::Read(test_cap(Some(0), 0, None, 0, "monthly", monthly_end)),
                3,
            ),
            "paused",
        ),
        // the cap that capped_reason names decides paused
        (
            rule(
                true,
                false,
                None,
                CapReading::Read(test_cap(
                    Some(5000000000),
                    0,
                    Some(0),
                    0,
                    "total",
                    monthly_end,
                )),
                3,
            ),
            "paused",
        ),
        (
            rule(
                true,
                false,
                None,
                CapReading::Read(test_cap(
                    Some(5000000000),
                    5000000000,
                    None,
                    0,
                    "monthly",
                    monthly_end,
                )),
                3,
            ),
            "data cap reached, resets 2026-11-01 00:00 UTC",
        ),
        (
            rule(
                true,
                false,
                None,
                CapReading::Read(test_cap(
                    None,
                    0,
                    Some(10000000000),
                    10000000000,
                    "total",
                    monthly_end,
                )),
                0,
            ),
            "data cap reached",
        ),
        (
            rule(
                true,
                false,
                None,
                CapReading::Read(test_cap(Some(5000000000), 0, None, 0, "", monthly_end)),
                1,
            ),
            "connected",
        ),
        (
            rule(true, false, None, CapReading::Checking, 0),
            "connecting",
        ),
        // a monthly cap with a period end that does not parse has no reset time
        (
            rule(
                true,
                false,
                None,
                CapReading::Read(test_cap(Some(1), 1, None, 0, "monthly", "soon")),
                1,
            ),
            "data cap reached",
        ),
        // an unknown reason reads as capped without a reset time, and never as paused
        (
            rule(
                true,
                false,
                None,
                CapReading::Read(test_cap(Some(0), 0, None, 0, "network", monthly_end)),
                1,
            ),
            "data cap reached",
        ),
        // a monthly limit of 0 that is not the capping reason is not a pause
        (
            rule(
                true,
                false,
                None,
                CapReading::Read(test_cap(Some(0), 0, Some(10), 10, "total", monthly_end)),
                1,
            ),
            "data cap reached",
        ),
        // a failed cap reading never caps
        (
            rule(true, false, None, CapReading::Unavailable, 2),
            "connected",
        ),
    ];
    for (status_inputs, want) in cases {
        let text = status_text(&status_inputs);
        if text != want {
            return Err(format!(
                "status {text:?} for {status_inputs:?}, want {want:?}"
            ));
        }
    }
    Ok(())
}

/// The data fields: `checking`, `unavailable` after a failed first reading, the last value kept
/// after a later failure, the Embed-not-enabled refusal clearing it, `no cap` for a null limit,
/// `<used> of <limit>` otherwise.
pub fn check_data_fields() -> CheckResult {
    let fields = |cap_reading: &CapReading| {
        (
            data_field_text(cap_reading, DataField::Monthly),
            data_field_text(cap_reading, DataField::Total),
        )
    };
    let mut cap_reading = CapReading::Checking;
    if fields(&cap_reading) != ("checking".to_string(), "checking".to_string()) {
        return Err(format!("before a reading: {:?}", fields(&cap_reading)));
    }
    cap_reading.apply(Err(CapReadError::Failed("synthetic failure".to_string())));
    if fields(&cap_reading) != ("unavailable".to_string(), "unavailable".to_string()) {
        return Err(format!(
            "after a failed first reading: {:?}",
            fields(&cap_reading)
        ));
    }
    // a null limit shows no cap, never the used count
    let read = test_cap(
        Some(5000000000),
        1234567890,
        None,
        999,
        "",
        "2026-11-01T00:00:00Z",
    );
    cap_reading.apply(Ok(read.clone()));
    if fields(&cap_reading) != ("1.2 GB of 5.0 GB".to_string(), "no cap".to_string()) {
        return Err(format!("after a reading: {:?}", fields(&cap_reading)));
    }
    // a later failure keeps the last value
    cap_reading.apply(Err(CapReadError::Failed("synthetic failure".to_string())));
    if cap_reading != CapReading::Read(read) {
        return Err("a later failure dropped the last reading".to_string());
    }
    // the Embed-not-enabled refusal clears it
    cap_reading.apply(Err(CapReadError::EmbedNotEnabled));
    if cap_reading != CapReading::Unavailable
        || fields(&cap_reading) != ("unavailable".to_string(), "unavailable".to_string())
    {
        return Err(format!(
            "the Embed-not-enabled refusal kept {:?}",
            fields(&cap_reading)
        ));
    }
    let zero = CapReading::Read(test_cap(Some(0), 0, Some(10000000000), 1750, "", ""));
    if fields(&zero) != ("0 B of 0 B".to_string(), "1.8 kB of 10.0 GB".to_string()) {
        return Err(format!("zero and partial limits: {:?}", fields(&zero)));
    }
    Ok(())
}

/// The cap object: null or absent limits, `capped` and `capped_reason`, an error refused.
pub fn check_cap_object() -> CheckResult {
    let parsed = parse_data_cap(&json!({
        "client_id": "11111111111111111111111111111111",
        "monthly_byte_limit": 10000000000u64,
        "monthly_used_byte_count": 1250,
        "monthly_period_start": "2026-10-01T00:00:00Z",
        "monthly_period_end": "2026-11-01T00:00:00Z",
        "total_byte_limit": null,
        "total_used_byte_count": 0,
        "total_period_start": "2026-09-15T00:00:00Z",
        "capped": false,
        "capped_reason": ""
    }))
    .ok_or("a full cap object did not parse")?;
    if parsed.client_id != TEST_CLIENT_ID
        || parsed.monthly_byte_limit != Some(10000000000)
        || parsed.monthly_used_byte_count != 1250
        || parsed.total_byte_limit.is_some()
        || parsed.capped
        || parsed.monthly_period_end.as_deref() != Some("2026-11-01T00:00:00Z")
    {
        return Err(format!("full cap object parsed as {parsed:?}"));
    }
    // absent fields: no caps, nothing used, not capped
    let sparse = parse_data_cap(&json!({"client_id": TEST_CLIENT_ID}))
        .ok_or("a sparse cap object did not parse")?;
    if sparse.monthly_byte_limit.is_some() || sparse.total_byte_limit.is_some() || sparse.capped {
        return Err(format!("sparse cap object parsed as {sparse:?}"));
    }
    let capped = parse_data_cap_json(
        br#"{"client_id":"11111111-1111-1111-1111-111111111111","total_byte_limit":0,"capped":true,"capped_reason":"total"}"#,
    )
    .ok_or("a capped cap object did not parse")?;
    if !capped.capped
        || capped.capped_reason != "total"
        || capped.named_cap_limit() != Some(Some(0))
    {
        return Err(format!("capped cap object parsed as {capped:?}"));
    }
    // an unknown reason names no cap: capped, but without a reset time and never paused
    let unknown = parse_data_cap(
        &json!({"monthly_byte_limit": 0, "capped": true, "capped_reason": "future"}),
    )
    .ok_or("an unknown reason did not parse")?;
    if unknown.named_cap_limit().is_some()
        || status_text(&inputs(CapReading::Read(unknown), 1)) != "data cap reached"
    {
        return Err(
            "an unknown capped_reason is not read as capped without a reset time".to_string(),
        );
    }
    let refused = [
        json!({"error": {"message": "Client does not exist."}}),
        json!({"monthly_byte_limit": -1}),
        json!({"monthly_used_byte_count": "12"}),
        json!([1, 2]),
        json!("text"),
    ];
    for value in refused {
        if parse_data_cap(&value).is_some() || is_embed_not_enabled(&value) {
            return Err(format!("an invalid cap object {value} was accepted"));
        }
    }
    // the Embed-not-enabled refusal is no cap object, and is recognized
    let refusal = json!({"error": {"message": "Embed isn't enabled for this network."}});
    if parse_data_cap(&refusal).is_some() || !is_embed_not_enabled(&refusal) {
        return Err("the Embed-not-enabled refusal was misread".to_string());
    }
    if parse_data_cap_json(b"not json").is_some() {
        return Err("non-JSON was accepted as a cap object".to_string());
    }
    Ok(())
}

/// The C ABI JSON that the status reads.
pub fn check_sdk_json() -> CheckResult {
    let client_limit_cases = [
        (
            Some(r#"{"Status": "client_limit_exceeded", "RetryTime": 1791313500000}"#),
            (CLIENT_LIMIT_STATUS_EXCEEDED.to_string(), 1791313500000),
        ),
        (
            Some(r#"{"Status": "", "RetryTime": 0}"#),
            (String::new(), 0),
        ),
        (None, (String::new(), 0)),
        (Some("not json"), (String::new(), 0)),
    ];
    for (json, want) in client_limit_cases {
        let parsed = parse_client_limit_status(json);
        if parsed != want {
            return Err(format!(
                "client limit status {parsed:?} for {json:?}, want {want:?}"
            ));
        }
    }
    let window_cases = [
        (
            Some(
                r#"{"ConnectionGeneration": 2, "TargetSize": 4, "MinSatisfied": true, "ProviderStateInEvaluation": 1, "ProviderStateAdded": 3}"#,
            ),
            3,
        ),
        (Some(r#"{"TargetSize": 4}"#), 0),
        (Some("null"), 0),
        (None, 0),
    ];
    for (json, want) in window_cases {
        let added = parse_providers_added(json);
        if added != want {
            return Err(format!("providers added {added} for {json:?}, want {want}"));
        }
    }
    Ok(())
}

/// A synthetic, unsigned JWT with the given payload JSON.
pub fn self_test_jwt(payload_json: &str) -> String {
    format!("e30.{}.test", URL_SAFE_NO_PAD.encode(payload_json))
}

/// A synthetic client JWT for the test client.
fn test_client_jwt() -> String {
    self_test_jwt(&format!(r#"{{"client_id":"{TEST_CLIENT_ID}"}}"#))
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

/// The token server and API origins: HTTPS, or explicit loopback HTTP for local testing.
pub fn check_origins() -> CheckResult {
    let accepted = [
        ("https://tokens.example.com", "https://tokens.example.com"),
        ("https://tokens.example.com/", "https://tokens.example.com"),
        (
            "https://tokens.example.com:8443",
            "https://tokens.example.com:8443",
        ),
        ("http://127.0.0.1:8790", "http://127.0.0.1:8790"),
        ("http://localhost:8790/", "http://localhost:8790"),
        ("http://[::1]:8790", "http://[::1]:8790"),
    ];
    for (text, want) in accepted {
        match parse_origin(text, "the URL") {
            Ok(origin) if origin == want => {}
            other => {
                return Err(format!(
                    "origin {text:?} parsed as {other:?}, want {want:?}"
                ));
            }
        }
    }
    let refused = [
        "http://tokens.example.com",
        "https://tokens.example.com/path",
        "https://tokens.example.com?query",
        "https://tokens.example.com#fragment",
        "https://user:pass@tokens.example.com",
        "ftp://tokens.example.com",
        "https://",
        "https://tokens.example.com:0",
        "https://tokens.example.com:99999",
        "tokens.example.com",
        "",
    ];
    for text in refused {
        if parse_origin(text, "the URL").is_ok() {
            return Err(format!("origin {text:?} was accepted"));
        }
    }
    match api_origin_from_setting(None) {
        Ok(origin) if origin == DEFAULT_API_ORIGIN => {}
        other => return Err(format!("default api origin {other:?}")),
    }
    if api_origin_from_setting(Some("http://api.example.com")).is_ok() {
        return Err("a non-loopback HTTP api origin was accepted".to_string());
    }
    Ok(())
}

/// A private temporary state directory, removed on drop.
fn temp_state_dir() -> Result<tempfile::TempDir, String> {
    let state_dir = tempfile::Builder::new()
        .prefix("ur-embed-self-test-")
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

/// An origin with nothing listening, for the unreachable case.
fn unreachable_origin() -> Result<String, String> {
    let listener = TcpListener::bind("127.0.0.1:0").map_err(|error| error.to_string())?;
    let address = listener.local_addr().map_err(|error| error.to_string())?;
    drop(listener);
    Ok(format!("http://{address}"))
}

/// The token fetch: the request, saving `client.jwt`, refusing a mismatched answer, and mapping
/// every answer to the console exit code and the GUI state.
pub fn check_token_fetch() -> CheckResult {
    let temp_dir = temp_state_dir()?;
    let state_dir = temp_dir.path();
    let client_jwt = test_client_jwt();
    let ok_body = json!({
        "client_id": TEST_CLIENT_ID,
        "by_client_jwt": client_jwt,
        "data_cap": {"client_id": TEST_CLIENT_ID, "monthly_byte_limit": 10000000000u64, "monthly_used_byte_count": 0, "capped": false, "capped_reason": ""}
    })
    .to_string();

    // a 200: the request carries the session and the instance id, and client.jwt is saved
    let stand_in = StandInServer::start(vec![StandInAnswer::json(200, ok_body.clone())])
        .map_err(|error| error.to_string())?;
    let token_server =
        TokenServer::new(stand_in.origin(), TEST_SESSION).map_err(|error| error.to_string())?;
    let config =
        load_embed_config(state_dir, Some(&token_server)).map_err(|error| error.to_string())?;
    let instance_id = load_or_create_instance_id(state_dir).map_err(|error| error.to_string())?;
    let requests = stand_in.requests();
    let request = requests.first().ok_or("the token server got no request")?;
    if request.method != "POST" || request.target != "/urnetwork/client-token" {
        return Err(format!(
            "token request {} {}",
            request.method, request.target
        ));
    }
    if request.header("authorization") != Some(&format!("Bearer {TEST_SESSION}")[..]) {
        return Err(
            "the token request does not carry the demo session as its bearer token".to_string(),
        );
    }
    match request.json() {
        Some(body) if body == json!({"installation_id": instance_id}) => {}
        other => return Err(format!("token request body {other:?}")),
    }
    if config.client_id != TEST_CLIENT_ID
        || config.client_jwt != client_jwt
        || config.instance_id != instance_id
        || config
            .initial_data_cap
            .as_ref()
            .and_then(|data_cap| data_cap.monthly_byte_limit)
            != Some(10000000000)
    {
        return Err(format!("token configuration differs: {config:?}"));
    }
    match load_client_jwt(state_dir).map_err(|error| error.to_string())? {
        Some((saved, client_id)) if saved == client_jwt && client_id == TEST_CLIENT_ID => {}
        _ => return Err("the fetched client JWT was not saved as client.jwt".to_string()),
    }
    drop(stand_in);

    // the answers mapped to exit codes and GUI states
    let mismatched = json!({
        "client_id": "22222222-2222-2222-2222-222222222222",
        "by_client_jwt": client_jwt,
        "data_cap": null
    })
    .to_string();
    let network_jwt = json!({
        "client_id": TEST_CLIENT_ID,
        "by_client_jwt": self_test_jwt(r#"{"network_id":"22222222-2222-2222-2222-222222222222"}"#),
    })
    .to_string();
    let cases: Vec<(StandInAnswer, i32, bool)> = vec![
        (
            StandInAnswer::json(
                401,
                r#"{"error":{"code":"unauthorized","message":"Unknown session."}}"#,
            ),
            EXIT_CONFIG,
            true,
        ),
        (
            StandInAnswer::json(
                409,
                r#"{"error":{"code":"installation_limit","message":"Too many installations."}}"#,
            ),
            EXIT_CONFIG,
            true,
        ),
        (
            StandInAnswer::json(
                409,
                r#"{"error":{"code":"client_limit","message":"Your network is at its client limit; see https://ur.io/services"}}"#,
            ),
            EXIT_CONFIG,
            true,
        ),
        (
            StandInAnswer::json(
                502,
                r#"{"error":{"code":"upstream","message":"The URnetwork API failed."}}"#,
            ),
            EXIT_FAILURE,
            false,
        ),
        (
            StandInAnswer::json(503, r#"{"error":{"code":"busy","message":"Retry."}}"#),
            EXIT_FAILURE,
            false,
        ),
        (StandInAnswer::json(200, "not json"), EXIT_FAILURE, false),
        // a client_id that does not match the JWT's claim is refused
        (StandInAnswer::json(200, mismatched), EXIT_FAILURE, false),
        // a network JWT has no client_id claim
        (StandInAnswer::json(200, network_jwt), EXIT_FAILURE, false),
    ];
    for (answer, exit_code, signs_out) in cases {
        let status = answer.status;
        let stand_in = StandInServer::start(vec![answer]).map_err(|error| error.to_string())?;
        let token_server =
            TokenServer::new(stand_in.origin(), TEST_SESSION).map_err(|error| error.to_string())?;
        match fetch_client_jwt(&token_server, &instance_id) {
            Ok(_) => return Err(format!("a {status} answer was accepted")),
            Err(error) => {
                if error.exit_code() != exit_code || error.signs_out() != signs_out {
                    return Err(format!(
                        "a {status} answer maps to exit {} signed out {}, want exit {exit_code} signed out {signs_out}",
                        error.exit_code(),
                        error.signs_out()
                    ));
                }
                if error.to_string().contains(TEST_SESSION) {
                    return Err("an error message contains the demo session".to_string());
                }
            }
        }
    }
    // unreachable: a failure that a restart may fix
    let token_server = TokenServer::new(&unreachable_origin()?, TEST_SESSION)
        .map_err(|error| error.to_string())?;
    match fetch_client_jwt(&token_server, &instance_id) {
        Err(TokenError::Failed(_)) => {}
        other => return Err(format!("an unreachable token server gave {other:?}")),
    }
    // a refused fetch leaves no new client.jwt behind and maps through the start error
    let stand_in = StandInServer::start(vec![StandInAnswer::json(
        401,
        r#"{"error":{"code":"unauthorized","message":"Unknown session."}}"#,
    )])
    .map_err(|error| error.to_string())?;
    let token_server =
        TokenServer::new(stand_in.origin(), TEST_SESSION).map_err(|error| error.to_string())?;
    match load_embed_config(state_dir, Some(&token_server)) {
        Err(error @ StartError::Token(_))
            if error.exit_code() == EXIT_CONFIG && error.signs_out() => {}
        other => return Err(format!("a refused start gave {other:?}")),
    }
    // the answer parser alone refuses a missing JWT
    if parse_token_answer(format!(r#"{{"client_id":"{TEST_CLIENT_ID}"}}"#).as_bytes()).is_ok() {
        return Err("an answer without by_client_jwt was accepted".to_string());
    }
    Ok(())
}

/// The app reads its own caps with its client JWT; a server without the cap routes is a failure,
/// and the Embed-not-enabled refusal is its own error.
pub fn check_cap_read() -> CheckResult {
    let client_jwt = test_client_jwt();
    let stand_in = StandInServer::start(vec![
        StandInAnswer::json(
            200,
            json!({"client_id": TEST_CLIENT_ID, "monthly_byte_limit": 5000000000u64, "monthly_used_byte_count": 1234567890u64, "total_byte_limit": null, "capped": false, "capped_reason": ""}).to_string(),
        ),
        StandInAnswer::json(404, r#"{"error":{"message":"Not found."}}"#),
        StandInAnswer::json(200, r#"{"error":{"message":"Client does not exist."}}"#),
        StandInAnswer::json(
            200,
            r#"{"error":{"message":"Embed isn't enabled for this network."}}"#,
        ),
    ])
    .map_err(|error| error.to_string())?;
    let data_cap =
        read_own_data_cap(stand_in.origin(), &client_jwt).map_err(|error| error.to_string())?;
    if data_cap.monthly_byte_limit != Some(5000000000)
        || data_cap.monthly_used_byte_count != 1234567890
    {
        return Err(format!("cap read parsed as {data_cap:?}"));
    }
    match read_own_data_cap(stand_in.origin(), &client_jwt) {
        Err(CapReadError::Failed(message)) if !message.contains(&client_jwt) => {}
        other => return Err(format!("a 404 cap read gave {other:?}")),
    }
    match read_own_data_cap(stand_in.origin(), &client_jwt) {
        Err(CapReadError::Failed(_)) => {}
        other => return Err(format!("a cap read answering an error gave {other:?}")),
    }
    match read_own_data_cap(stand_in.origin(), &client_jwt) {
        Err(CapReadError::EmbedNotEnabled) => {}
        other => return Err(format!("the Embed-not-enabled refusal gave {other:?}")),
    }
    let requests = stand_in.requests();
    let request = requests.first().ok_or("the API got no request")?;
    if request.method != "GET"
        || request.target != "/network/client-data-cap"
        || request.header("authorization") != Some(&format!("Bearer {client_jwt}")[..])
    {
        return Err(format!(
            "cap read request {} {} without the client JWT",
            request.method, request.target
        ));
    }
    Ok(())
}

/// Caps are read at start, every 5 minutes, and soon after a contract change, never twice at once.
pub fn check_cap_read_schedule() -> CheckResult {
    let start = Instant::now();
    let mut schedule = CapReadSchedule::new(start);
    if !schedule.start_due(start) {
        return Err("no read at start".to_string());
    }
    // one read at a time
    if schedule.start_due(start + Duration::from_secs(400)) {
        return Err("a second read started while one was in flight".to_string());
    }
    schedule.finished(start + Duration::from_secs(1));
    if schedule.next_read_time() != start + CAP_READ_INTERVAL {
        return Err("the next read is not 5 minutes after the last".to_string());
    }
    // a contract change reads again within 5 seconds, at most the minimum interval after the last
    let change_time = start + Duration::from_secs(2);
    schedule.contract_changed(change_time);
    let next = schedule.next_read_time();
    // the run loop starts the read at its first poll at or after `next`
    let read_delay = next - change_time + STATUS_POLL_INTERVAL;
    if next != start + CAP_READ_MIN_INTERVAL || Duration::from_secs(5) < read_delay {
        return Err(format!("a contract change is read {read_delay:?} after it"));
    }
    if !schedule.start_due(next) {
        return Err("the read after a contract change did not start".to_string());
    }
    // a change during a read is read again after that read
    schedule.contract_changed(next + Duration::from_millis(100));
    schedule.finished(next + Duration::from_millis(200));
    if schedule.next_read_time() != next + CAP_READ_MIN_INTERVAL {
        return Err("a change during a read was not read again".to_string());
    }
    // much later, a change reads at once
    let mut idle = CapReadSchedule::new(start);
    idle.start_due(start);
    idle.finished(start);
    let late = start + Duration::from_secs(120);
    idle.contract_changed(late);
    if idle.next_read_time() != late {
        return Err("a change long after the last read did not read at once".to_string());
    }
    Ok(())
}

/// The console prints a line when a field's text changes, and otherwise once a minute.
pub fn check_status_line_cadence() -> CheckResult {
    let start = Instant::now();
    let connecting = EmbedStatus::new(&inputs(CapReading::Checking, 0));
    let connected = EmbedStatus::new(&inputs(CapReading::Checking, 1));
    let mut status_lines = StatusLines::default();
    if status_lines.next_line(&connecting, start).is_none() {
        return Err("the first status was not printed".to_string());
    }
    if status_lines
        .next_line(&connecting, start + Duration::from_secs(5))
        .is_some()
    {
        return Err("an unchanged status was printed again within a minute".to_string());
    }
    if status_lines
        .next_line(&connected, start + Duration::from_secs(6))
        .is_none()
    {
        return Err("a changed status was not printed".to_string());
    }
    if status_lines
        .next_line(
            &connected,
            start + Duration::from_secs(6) + STATUS_REPEAT_INTERVAL,
        )
        .is_none()
    {
        return Err("an unchanged status was not printed after a minute".to_string());
    }
    Ok(())
}

/// State files are private, replaced atomically, and the instance id is created once.
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
    write_private_file(&state_dir.join(INSTANCE_ID_FILE_NAME), b"not-a-uuid\n")
        .map_err(|error| error.to_string())?;
    if load_or_create_instance_id(state_dir).is_ok() {
        return Err("an invalid instance-id was accepted".to_string());
    }

    // token-server.json round trip, private like the other files
    let file = TokenServerFile {
        url: "http://127.0.0.1:8790".to_string(),
        session: TEST_SESSION.to_string(),
    };
    save_token_server_file(state_dir, &file).map_err(|error| error.to_string())?;
    match load_token_server_file(state_dir).map_err(|error| error.to_string())? {
        Some(loaded) if loaded == file => {}
        other => return Err(format!("token-server.json round trip gave {other:?}")),
    }
    if format!("{file:?}").contains(TEST_SESSION) {
        return Err("the token server file's debug text shows the session".to_string());
    }
    Ok(())
}

/// A missing or incomplete installation is refused with a configuration error.
pub fn check_configuration() -> CheckResult {
    let config_error = |result: Result<_, StartError>, what: &str| match result {
        Err(error @ StartError::Config(_))
            if error.exit_code() == EXIT_CONFIG && !error.signs_out() =>
        {
            Ok(())
        }
        Err(other) => Err(format!("{what} gave {other:?}")),
        Ok(_) => Err(format!("{what} was accepted")),
    };
    config_error(
        load_embed_config(Path::new(""), None),
        "a missing state directory",
    )?;
    config_error(
        load_embed_config(Path::new("relative/state"), None),
        "a relative state directory",
    )?;
    let temp_dir = temp_state_dir()?;
    let state_dir = temp_dir.path();
    // no token server and no client.jwt
    config_error(
        load_embed_config(state_dir, None),
        "no token server and no client.jwt",
    )?;
    // a network jwt has no client_id claim
    let network_jwt = self_test_jwt(r#"{"network_id":"22222222-2222-2222-2222-222222222222"}"#);
    write_private_file(
        &state_dir.join(CLIENT_JWT_FILE_NAME),
        format!("{network_jwt}\n").as_bytes(),
    )
    .map_err(|error| error.to_string())?;
    config_error(load_embed_config(state_dir, None), "a network jwt")?;
    // a client jwt from a backend tool loads, with whitespace ignored
    let client_jwt = test_client_jwt();
    write_private_file(
        &state_dir.join(CLIENT_JWT_FILE_NAME),
        format!("  {client_jwt}\n\n").as_bytes(),
    )
    .map_err(|error| error.to_string())?;
    let config = load_embed_config(state_dir, None).map_err(|error| error.to_string())?;
    if config.client_jwt != client_jwt
        || config.client_id != TEST_CLIENT_ID
        || config.instance_id.is_empty()
        || config.initial_data_cap.is_some()
    {
        return Err(format!("a backend tool's client.jwt loaded as {config:?}"));
    }
    if format!("{config:?}").contains(&client_jwt) {
        return Err("the configuration's debug text shows the client JWT".to_string());
    }
    // the token server settings come together
    match token_server_from_settings(None, None) {
        Ok(None) => {}
        other => return Err(format!("no token server settings gave {other:?}")),
    }
    for (url, session) in [
        (Some("http://127.0.0.1:8790"), None),
        (None, Some(TEST_SESSION)),
        (Some("http://tokens.example.com"), Some(TEST_SESSION)),
        (Some("http://127.0.0.1:8790"), Some("two words")),
    ] {
        if token_server_from_settings(url, session).is_ok() {
            return Err(format!("token server settings {url:?} were accepted"));
        }
    }
    match token_server_from_settings(Some("http://127.0.0.1:8790"), Some(TEST_SESSION)) {
        Ok(Some(token_server)) if token_server.origin() == "http://127.0.0.1:8790" => {
            if format!("{token_server:?}").contains(TEST_SESSION) {
                return Err("the token server's debug text shows the session".to_string());
            }
        }
        other => return Err(format!("valid token server settings gave {other:?}")),
    }
    Ok(())
}
