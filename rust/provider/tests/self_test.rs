//! Runs each self-test check as a test, so `cargo test` and `--self-test` cover the same behavior.
//! None of them loads the native SDK runtime.

use urnetwork_provider::self_test;

/// Fails the test with the check's message.
fn check(result: self_test::CheckResult) {
    if let Err(message) = result {
        panic!("{message}");
    }
}

#[test]
fn consent_disclaimer() {
    check(self_test::check_consent_disclaimer());
}

#[test]
fn format_byte_count() {
    check(self_test::check_format_byte_count());
}

#[test]
fn status_text() {
    check(self_test::check_status_text());
}

#[test]
fn status_lines() {
    check(self_test::check_status_lines());
}

#[test]
fn status_key() {
    check(self_test::check_status_key());
}

#[test]
fn provider_state() {
    check(self_test::check_provider_state());
}

#[test]
fn payout_wallet_scope() {
    check(self_test::check_payout_wallet_scope());
}

#[test]
fn clients_served() {
    check(self_test::check_clients_served());
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
fn state_files() {
    check(self_test::check_state_files());
}

#[test]
fn provider_config() {
    check(self_test::check_provider_config());
}

#[test]
fn every_check_runs_in_the_self_test() {
    assert_eq!(self_test::CHECKS.len(), 12);
    check(self_test::run_self_test());
}
