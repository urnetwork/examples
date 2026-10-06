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

func TestJwtNetworkId(t *testing.T) {
	if err := checkJwtNetworkId(); err != nil {
		t.Fatal(err)
	}
}

func TestProviderMap(t *testing.T) {
	if err := checkProviderMap(); err != nil {
		t.Fatal(err)
	}
}

func TestProvision(t *testing.T) {
	if err := checkProvision(); err != nil {
		t.Fatal(err)
	}
}

func TestProvisionDeactivatedClient(t *testing.T) {
	if err := checkProvisionDeactivatedClient(); err != nil {
		t.Fatal(err)
	}
}

func TestProvisionRefusals(t *testing.T) {
	if err := checkProvisionRefusals(); err != nil {
		t.Fatal(err)
	}
}

func TestNetworkChallengeAndAccept(t *testing.T) {
	if err := checkNetworkChallengeAndAccept(); err != nil {
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
