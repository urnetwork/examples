// Runs each self-test check as a test, so `go test` and `--self-test` cover
// the same behavior.
package main

import (
	"testing"
)

func TestConsentDisclaimer(t *testing.T) {
	if err := checkConsentDisclaimer(); err != nil {
		t.Fatal(err)
	}
}

func TestFormatByteCount(t *testing.T) {
	if err := checkFormatByteCount(); err != nil {
		t.Fatal(err)
	}
}

func TestStatusLines(t *testing.T) {
	if err := checkStatusLines(); err != nil {
		t.Fatal(err)
	}
}

func TestProviderState(t *testing.T) {
	if err := checkProviderState(); err != nil {
		t.Fatal(err)
	}
}

func TestPayoutWalletScope(t *testing.T) {
	if err := checkPayoutWalletScope(); err != nil {
		t.Fatal(err)
	}
}

func TestClientsServed(t *testing.T) {
	if err := checkClientsServed(); err != nil {
		t.Fatal(err)
	}
}

func TestClientJwtClaims(t *testing.T) {
	if err := checkClientJwtClaims(); err != nil {
		t.Fatal(err)
	}
}

func TestStateFiles(t *testing.T) {
	if err := checkStateFiles(); err != nil {
		t.Fatal(err)
	}
}

func TestProviderConfig(t *testing.T) {
	if err := checkProviderConfig(); err != nil {
		t.Fatal(err)
	}
}

func TestUsageExitCode(t *testing.T) {
	if code := run([]string{"--unknown"}); code != exitConfig {
		t.Fatalf("usage error exit code %d, want %d", code, exitConfig)
	}
}
