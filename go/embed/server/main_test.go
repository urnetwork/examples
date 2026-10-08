// Runs each self-test check as a test, so `go test` and `--self-test` cover
// the same behavior.
package main

import (
	"testing"
)

func TestKeysAndOptions(t *testing.T) {
	if err := checkKeysAndOptions(); err != nil {
		t.Fatal(err)
	}
}

func TestMergeBodies(t *testing.T) {
	if err := checkMergeBodies(); err != nil {
		t.Fatal(err)
	}
}

func TestCapObjects(t *testing.T) {
	if err := checkCapObjects(); err != nil {
		t.Fatal(err)
	}
}

func TestApiOrigin(t *testing.T) {
	if err := checkApiOrigin(); err != nil {
		t.Fatal(err)
	}
}

func TestRootCredentialKinds(t *testing.T) {
	if err := checkRootCredentialKinds(); err != nil {
		t.Fatal(err)
	}
}

func TestClientMap(t *testing.T) {
	if err := checkClientMap(); err != nil {
		t.Fatal(err)
	}
}

func TestDemoSessions(t *testing.T) {
	if err := checkDemoSessions(); err != nil {
		t.Fatal(err)
	}
}

func TestTokenServer(t *testing.T) {
	if err := checkTokenServer(); err != nil {
		t.Fatal(err)
	}
}

func TestTokenServerDefaultCaps(t *testing.T) {
	if err := checkTokenServerDefaultCaps(); err != nil {
		t.Fatal(err)
	}
}

func TestTokenServerLimits(t *testing.T) {
	if err := checkTokenServerLimits(); err != nil {
		t.Fatal(err)
	}
}

func TestCommands(t *testing.T) {
	if err := checkCommands(); err != nil {
		t.Fatal(err)
	}
}

func TestUsageAll(t *testing.T) {
	if err := checkUsageAll(); err != nil {
		t.Fatal(err)
	}
}
