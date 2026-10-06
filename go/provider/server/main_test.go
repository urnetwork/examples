// Runs each self-test check as a test, so `go test` and `--self-test` cover
// the same behavior.
package main

import (
	"testing"
)

func TestDecodeSs58(t *testing.T) {
	if err := checkDecodeSs58(); err != nil {
		t.Fatal(err)
	}
}

func TestNormalizeSignature(t *testing.T) {
	if err := checkNormalizeSignature(); err != nil {
		t.Fatal(err)
	}
}

func TestApiOrigin(t *testing.T) {
	if err := checkApiOrigin(); err != nil {
		t.Fatal(err)
	}
}

func TestChallengeAndAccept(t *testing.T) {
	if err := checkChallengeAndAccept(); err != nil {
		t.Fatal(err)
	}
}

func TestRefusedConsent(t *testing.T) {
	if err := checkRefusedConsent(); err != nil {
		t.Fatal(err)
	}
}

func TestShowWallets(t *testing.T) {
	if err := checkShowWallets(); err != nil {
		t.Fatal(err)
	}
}
