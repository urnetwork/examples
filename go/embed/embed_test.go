// Runs each self-test check as a test, so `go test` and `--self-test` cover
// the same behavior.
package main

import (
	"testing"
)

func TestFormatByteCount(t *testing.T) {
	if err := checkFormatByteCount(); err != nil {
		t.Fatal(err)
	}
}

func TestResetText(t *testing.T) {
	if err := checkResetText(); err != nil {
		t.Fatal(err)
	}
}

func TestClientLimitText(t *testing.T) {
	if err := checkClientLimitText(); err != nil {
		t.Fatal(err)
	}
}

func TestStatusLines(t *testing.T) {
	if err := checkStatusLines(); err != nil {
		t.Fatal(err)
	}
}

func TestStatusRules(t *testing.T) {
	if err := checkStatusRules(); err != nil {
		t.Fatal(err)
	}
}

func TestDataFields(t *testing.T) {
	if err := checkDataFields(); err != nil {
		t.Fatal(err)
	}
}

func TestCapObjects(t *testing.T) {
	if err := checkCapObjects(); err != nil {
		t.Fatal(err)
	}
}

func TestClientJwtClaims(t *testing.T) {
	if err := checkClientJwtClaims(); err != nil {
		t.Fatal(err)
	}
}

func TestTokenFetch(t *testing.T) {
	if err := checkTokenFetch(); err != nil {
		t.Fatal(err)
	}
}

func TestStateFiles(t *testing.T) {
	if err := checkStateFiles(); err != nil {
		t.Fatal(err)
	}
}

func TestConfigErrors(t *testing.T) {
	if err := checkConfigErrors(); err != nil {
		t.Fatal(err)
	}
}

func TestUsageExitCode(t *testing.T) {
	if code := run([]string{"--unknown"}); code != exitConfig {
		t.Fatalf("usage error exit code %d, want %d", code, exitConfig)
	}
}
