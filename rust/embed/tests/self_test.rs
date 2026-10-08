//! Runs each self-test check as a test, so `cargo test` and `--self-test` cover the same behavior.
//! None of them loads the native SDK runtime.

use urnetwork_embed::self_test;

/// Fails the test with the check's message.
fn check(result: self_test::CheckResult) {
    if let Err(message) = result {
        panic!("{message}");
    }
}

#[test]
fn format_byte_count() {
    check(self_test::check_format_byte_count());
}

#[test]
fn reset_time() {
    check(self_test::check_reset_time());
}

#[test]
fn client_limit_text() {
    check(self_test::check_client_limit_text());
}

#[test]
fn status_lines() {
    check(self_test::check_status_lines());
}

#[test]
fn status_rules() {
    check(self_test::check_status_rules());
}

#[test]
fn data_fields() {
    check(self_test::check_data_fields());
}

#[test]
fn cap_object() {
    check(self_test::check_cap_object());
}

#[test]
fn sdk_json() {
    check(self_test::check_sdk_json());
}

#[test]
fn client_jwt_claims() {
    check(self_test::check_client_jwt_claims());
}

#[test]
fn origins() {
    check(self_test::check_origins());
}

#[test]
fn token_fetch() {
    check(self_test::check_token_fetch());
}

#[test]
fn cap_read() {
    check(self_test::check_cap_read());
}

#[test]
fn cap_read_schedule() {
    check(self_test::check_cap_read_schedule());
}

#[test]
fn status_line_cadence() {
    check(self_test::check_status_line_cadence());
}

#[test]
fn state_files() {
    check(self_test::check_state_files());
}

#[test]
fn configuration() {
    check(self_test::check_configuration());
}

#[test]
fn every_check_runs_in_the_self_test() {
    assert_eq!(self_test::CHECKS.len(), 16);
    check(self_test::run_self_test());
}
