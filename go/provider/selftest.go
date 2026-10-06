// The credential-free self-test (PROVIDER_CONTRACT.md, "Self-test"). It checks
// the disclaimer, the status text, the providing state, the clients-served
// count and the installation state files without a network, credentials or a
// running device. `--self-test` runs every check; provider_test.go runs each
// one as a test.
package main

import (
	"bytes"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"runtime"

	sdk "github.com/urnetwork/sdk/v2026"
)

// sha-256 of the consent disclaimer (utf-8, lf line breaks, no trailing
// newline), published in PROVIDER_CONTRACT.md for every example to check
const consentDisclaimerSha256 = "83edee1e45cccd5deb6b86755cc5f6a91b6ade26e7eefc1e7670b5833a95502c"

// Runs every check and returns the first failure.
func runSelfTest() error {
	checks := []func() error{
		checkConsentDisclaimer,
		checkFormatByteCount,
		checkStatusText,
		checkStatusLines,
		checkStatusKey,
		checkProviderState,
		checkPayoutWalletScope,
		checkClientsServed,
		checkClientJwtClaims,
		checkStateFiles,
		checkProviderConfig,
	}
	for _, check := range checks {
		if err := check(); err != nil {
			return err
		}
	}
	return nil
}

// The disclaimer is the contract's exact text.
func checkConsentDisclaimer() error {
	digest := sha256.Sum256([]byte(consentDisclaimer))
	if hex.EncodeToString(digest[:]) != consentDisclaimerSha256 {
		return errors.New("consent disclaimer differs from PROVIDER_CONTRACT.md")
	}
	return nil
}

// Byte counts use binary units with one decimal.
func checkFormatByteCount() error {
	cases := []struct {
		byteCount int64
		text      string
	}{
		{byteCount: 0, text: "0 B"},
		{byteCount: 1023, text: "1023 B"},
		{byteCount: 1024, text: "1.0 KiB"},
		{byteCount: 1536, text: "1.5 KiB"},
		{byteCount: 1048575, text: "1.0 MiB"},
		{byteCount: 13002342, text: "12.4 MiB"},
		{byteCount: 5 * 1024 * 1024 * 1024, text: "5.0 GiB"},
		{byteCount: 3 * 1024 * 1024 * 1024 * 1024, text: "3.0 TiB"},
	}
	for _, c := range cases {
		if text := formatByteCount(c.byteCount); text != c.text {
			return fmt.Errorf("byte count %d formats as %q, want %q", c.byteCount, text, c.text)
		}
	}
	return nil
}

// The client limit status names the sdk's retry time in UTC, rounded up to the
// next whole minute; other states never show it.
func checkStatusText() error {
	cases := []struct {
		state                string
		clientLimitRetryTime int64
		text                 string
	}{
		// 2026-10-06 19:05:00.000 UTC, exactly on a minute
		{state: providerStateClientLimit, clientLimitRetryTime: 1791313500000, text: "client limit, retry at 19:05 UTC"},
		// 19:04:00.001 rounds up, so the shown time is never before the retry
		{state: providerStateClientLimit, clientLimitRetryTime: 1791313440001, text: "client limit, retry at 19:05 UTC"},
		// no retry time
		{state: providerStateClientLimit, clientLimitRetryTime: 0, text: "client limit"},
		// 23:59:00.001 rolls over the hour and the day
		{state: providerStateClientLimit, clientLimitRetryTime: 1791331140001, text: "client limit, retry at 00:00 UTC"},
		{state: providerStateStarting, clientLimitRetryTime: 1791313500000, text: "starting"},
	}
	for _, c := range cases {
		if text := providerStatusText(c.state, c.clientLimitRetryTime); text != c.text {
			return fmt.Errorf("status text %q for %q retrying at %d, want %q", text, c.state, c.clientLimitRetryTime, c.text)
		}
	}
	return nil
}

// The status line matches the contract's golden lines.
func checkStatusLines() error {
	cases := []struct {
		status providerStatus
		line   string
	}{
		{
			status: providerStatus{state: providerStateStarting, payoutWallet: payoutWalletChecking},
			line:   "status: starting | clients served: 0 | data provided: 0 B | payout wallet: checking",
		},
		{
			status: providerStatus{state: providerStateProviding, clientsServed: 3, dataProvidedByteCount: 13002342, payoutWallet: "5GrwvaEF5zXb26Fz9rcQpDWS57CtERHpNehXCPcNoHGKutQY", payoutWalletScope: payoutWalletScopeNetwork},
			line:   "status: providing | clients served: 3 | data provided: 12.4 MiB | payout wallet: 5GrwvaEF5zXb26Fz9rcQpDWS57CtERHpNehXCPcNoHGKutQY (network)",
		},
		{
			status: providerStatus{state: providerStateProviding, clientsServed: 3, dataProvidedByteCount: 13002342, payoutWallet: "5GrwvaEF5zXb26Fz9rcQpDWS57CtERHpNehXCPcNoHGKutQY", payoutWalletScope: payoutWalletScopeHotkey},
			line:   "status: providing | clients served: 3 | data provided: 12.4 MiB | payout wallet: 5GrwvaEF5zXb26Fz9rcQpDWS57CtERHpNehXCPcNoHGKutQY (hotkey)",
		},
		{
			status: providerStatus{state: providerStatePaused, clientsServed: clientsServedLimit, clientsServedAtLimit: true, dataProvidedByteCount: 1536, payoutWallet: "5GrwvaEF5zXb26Fz9rcQpDWS57CtERHpNehXCPcNoHGKutQY", payoutWalletScope: payoutWalletScopeProvider},
			line:   "status: paused | clients served: 100000+ | data provided: 1.5 KiB | payout wallet: 5GrwvaEF5zXb26Fz9rcQpDWS57CtERHpNehXCPcNoHGKutQY (this provider)",
		},
		{
			status: providerStatus{state: providerStateStopped, payoutWallet: payoutWalletNotSet},
			line:   "status: stopped | clients served: 0 | data provided: 0 B | payout wallet: not set",
		},
		{
			status: providerStatus{state: providerStateClientLimit, clientLimitRetryTime: 1791313500000, payoutWallet: "5GrwvaEF5zXb26Fz9rcQpDWS57CtERHpNehXCPcNoHGKutQY", payoutWalletScope: payoutWalletScopeNetwork},
			line:   "status: client limit, retry at 19:05 UTC | clients served: 0 | data provided: 0 B | payout wallet: 5GrwvaEF5zXb26Fz9rcQpDWS57CtERHpNehXCPcNoHGKutQY (network)",
		},
	}
	for _, c := range cases {
		if line := c.status.String(); line != c.line {
			return fmt.Errorf("status line %q, want %q", line, c.line)
		}
	}
	return nil
}

// A change of the status text prints a line at once, including a new client
// limit retry time; the data counter alone does not.
func checkStatusKey() error {
	status := providerStatus{state: providerStateClientLimit, clientLimitRetryTime: 1791313500000, payoutWallet: payoutWalletChecking}
	// 19:25 UTC
	retried := status
	retried.clientLimitRetryTime = 1791314700000
	if status.key() == retried.key() {
		return errors.New("a new client limit retry time does not print a status line")
	}
	counted := status
	counted.dataProvidedByteCount = 1536
	if status.key() != counted.key() {
		return errors.New("the data counter alone prints a status line")
	}
	return nil
}

// The providing state follows the provide mode, client limit, pause, enable
// and connected rules, in that order.
func checkProviderState() error {
	cases := []struct {
		provideMode       int
		clientLimitStatus string
		providePaused     bool
		provideEnabled    bool
		providerConnected bool
		state             string
	}{
		{provideMode: sdk.ProvideModeNone, provideEnabled: false, providerConnected: false, state: providerStateStopped},
		{provideMode: sdk.ProvideModeNetwork, provideEnabled: true, providerConnected: true, state: providerStateStopped},
		{provideMode: sdk.ProvideModePublic, provideEnabled: true, providerConnected: false, state: providerStateStarting},
		{provideMode: sdk.ProvideModePublic, provideEnabled: false, providerConnected: true, state: providerStateStarting},
		{provideMode: sdk.ProvideModePublic, provideEnabled: true, providerConnected: true, state: providerStateProviding},
		{provideMode: sdk.ProvideModePublic, providePaused: true, provideEnabled: true, providerConnected: true, state: providerStatePaused},
		// the client limit comes after stopped and before every other state
		{provideMode: sdk.ProvideModeNetwork, clientLimitStatus: sdk.ClientLimitStatusExceeded, provideEnabled: true, providerConnected: true, state: providerStateStopped},
		{provideMode: sdk.ProvideModePublic, clientLimitStatus: sdk.ClientLimitStatusExceeded, providePaused: true, provideEnabled: true, providerConnected: true, state: providerStateClientLimit},
		{provideMode: sdk.ProvideModePublic, clientLimitStatus: sdk.ClientLimitStatusExceeded, provideEnabled: false, providerConnected: false, state: providerStateClientLimit},
		{provideMode: sdk.ProvideModePublic, clientLimitStatus: sdk.ClientLimitStatusNone, providePaused: true, provideEnabled: true, providerConnected: true, state: providerStatePaused},
	}
	for _, c := range cases {
		if state := providerState(c.provideMode, c.clientLimitStatus, c.providePaused, c.provideEnabled, c.providerConnected); state != c.state {
			return fmt.Errorf("provider state %q for %+v", state, c)
		}
	}
	return nil
}

// The payout wallet is labeled by its consent scope first, then by the owner
// of its mapping.
func checkPayoutWalletScope() error {
	clientId := "11111111-1111-1111-1111-111111111111"
	cases := []struct {
		walletConsentScope string
		walletClientId     string
		scope              string
	}{
		// a hotkey delegation is network-level but not the network's wallet
		{walletConsentScope: snWalletConsentScopeHotkey, walletClientId: "", scope: payoutWalletScopeHotkey},
		{walletConsentScope: snWalletConsentScopeHotkey, walletClientId: clientId, scope: payoutWalletScopeHotkey},
		{walletConsentScope: sdk.SnWalletConsentScopeNetwork, walletClientId: "", scope: payoutWalletScopeNetwork},
		{walletConsentScope: "", walletClientId: "", scope: payoutWalletScopeNetwork},
		{walletConsentScope: sdk.SnWalletConsentScopeProvider, walletClientId: clientId, scope: payoutWalletScopeProvider},
		{walletConsentScope: "", walletClientId: clientId, scope: payoutWalletScopeProvider},
		{walletConsentScope: sdk.SnWalletConsentScopeProvider, walletClientId: "22222222-2222-2222-2222-222222222222", scope: payoutWalletScopeAnotherProvider},
	}
	for _, c := range cases {
		if scope := payoutWalletScope(c.walletConsentScope, c.walletClientId, clientId); scope != c.scope {
			return fmt.Errorf("payout wallet scope %q for consent scope %q and client %q, want %q", scope, c.walletConsentScope, c.walletClientId, c.scope)
		}
	}
	return nil
}

// Contract peers resolve by direction and count once per client, up to the limit.
func checkClientsServed() error {
	parseId := func(text string) *sdk.Id {
		id, err := sdk.ParseId(text)
		if err != nil {
			panic(err)
		}
		return id
	}
	provider := parseId("11111111-1111-1111-1111-111111111111")
	clientA := parseId("22222222-2222-2222-2222-222222222222")
	clientB := parseId("33333333-3333-3333-3333-333333333333")
	stream := parseId("44444444-4444-4444-4444-444444444444")
	zero := parseId(zeroIdString)
	contract := func(contractId string, sourceId *sdk.Id, destinationId *sdk.Id, streamId *sdk.Id) *sdk.ContractDetails {
		return &sdk.ContractDetails{
			ContractId:           parseId(contractId),
			ContractTransferPath: sdk.NewTransferPath(sourceId, destinationId, streamId),
			Status:               sdk.ContractStatusOpen,
		}
	}

	// the peer is the source of a receive contract and the destination of a send contract
	keyCases := []struct {
		details *sdk.ContractDetails
		receive bool
		peerKey string
	}{
		{details: contract("55555555-5555-5555-5555-555555555555", clientA, provider, nil), receive: true, peerKey: clientA.String()},
		{details: contract("66666666-6666-6666-6666-666666666666", provider, clientA, nil), receive: false, peerKey: clientA.String()},
		{details: contract("77777777-7777-7777-7777-777777777777", zero, provider, stream), receive: true, peerKey: "stream:" + stream.String()},
		{details: contract("88888888-8888-8888-8888-888888888888", nil, nil, nil), receive: true, peerKey: "contract:88888888-8888-8888-8888-888888888888"},
	}
	for _, c := range keyCases {
		if peerKey := contractPeerKey(c.details, c.receive); peerKey != c.peerKey {
			return fmt.Errorf("contract peer key %q, want %q", peerKey, c.peerKey)
		}
	}

	// both directions of one client count once
	served := newClientsServed(2)
	served.Add(contract("55555555-5555-5555-5555-555555555555", clientA, provider, nil), true)
	served.Add(contract("66666666-6666-6666-6666-666666666666", provider, clientA, nil), false)
	served.Add(contract("99999999-9999-9999-9999-999999999999", clientA, provider, nil), true)
	if count, atLimit := served.Count(); count != 1 || atLimit {
		return fmt.Errorf("one client counted as %d (at limit %t)", count, atLimit)
	}
	served.Add(contract("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", clientB, provider, nil), true)
	if count, atLimit := served.Count(); count != 2 || atLimit {
		return fmt.Errorf("two clients counted as %d (at limit %t)", count, atLimit)
	}
	// a third distinct peer reaches the limit of 2
	served.Add(contract("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb", zero, provider, stream), true)
	if count, atLimit := served.Count(); count != 2 || !atLimit {
		return fmt.Errorf("limited count %d (at limit %t)", count, atLimit)
	}
	return nil
}

// A synthetic, unsigned JWT with the given payload json.
func selfTestJwt(payloadJson string) string {
	return "e30." + base64.RawURLEncoding.EncodeToString([]byte(payloadJson)) + ".test"
}

// Only a JWT with a valid client_id claim is a client credential.
func checkClientJwtClaims() error {
	clientId, err := parseClientJwtClientId(selfTestJwt(`{"client_id":"11111111-1111-1111-1111-111111111111","network_id":"22222222-2222-2222-2222-222222222222"}`))
	if err != nil || clientId != "11111111-1111-1111-1111-111111111111" {
		return fmt.Errorf("client jwt claim %q (%v)", clientId, err)
	}
	invalidJwts := []string{
		"",
		"not-a-jwt",
		selfTestJwt(`{"network_id":"22222222-2222-2222-2222-222222222222"}`),
		selfTestJwt(`{"client_id":"not-a-uuid"}`),
		"e30.%%%.test",
	}
	for _, invalidJwt := range invalidJwts {
		if _, err := parseClientJwtClientId(invalidJwt); err == nil {
			return fmt.Errorf("invalid client jwt %q accepted", invalidJwt)
		}
	}
	return nil
}

// State files are private, replaced atomically, created once and bound to their client.
func checkStateFiles() error {
	stateDir, err := os.MkdirTemp("", "ur-provider-self-test-")
	if err != nil {
		return err
	}
	defer os.RemoveAll(stateDir)
	if err := os.Chmod(stateDir, 0o700); err != nil {
		return err
	}

	// an atomic private write
	path := filepath.Join(stateDir, clientJwtFileName)
	if err := writePrivateFile(path, []byte("first\n")); err != nil {
		return err
	}
	if err := writePrivateFile(path, []byte("second\n")); err != nil {
		return err
	}
	data, err := readPrivateFile(path)
	if err != nil || !bytes.Equal(data, []byte("second\n")) {
		return fmt.Errorf("private file round trip %q (%v)", data, err)
	}
	if runtime.GOOS != "windows" {
		info, err := os.Stat(path)
		if err != nil || info.Mode().Perm() != 0o600 {
			return fmt.Errorf("private file mode %v (%v)", info.Mode().Perm(), err)
		}
		// a file or directory that others can read is refused
		if err := os.Chmod(path, 0o644); err != nil {
			return err
		}
		if _, err := readPrivateFile(path); err == nil {
			return errors.New("a group-readable credential file was accepted")
		}
		if err := os.Chmod(stateDir, 0o755); err != nil {
			return err
		}
		if err := checkStateDir(stateDir); err == nil {
			return errors.New("a group-readable state directory was accepted")
		}
		if err := os.Chmod(stateDir, 0o700); err != nil {
			return err
		}
	}
	if err := checkStateDir(stateDir); err != nil {
		return err
	}

	// the instance id is created once and reused
	instanceId, err := loadOrCreateInstanceId(stateDir)
	if err != nil {
		return err
	}
	if _, err := sdk.ParseId(instanceId); err != nil {
		return fmt.Errorf("instance id %q is not a uuid", instanceId)
	}
	again, err := loadOrCreateInstanceId(stateDir)
	if err != nil || again != instanceId {
		return fmt.Errorf("instance id changed from %q to %q (%v)", instanceId, again, err)
	}

	// the identity belongs to its client
	clientId := "11111111-1111-1111-1111-111111111111"
	identity := &providerIdentity{
		Version:                  providerIdentityVersion,
		ClientId:                 clientId,
		ClientKeySeed:            bytes.Repeat([]byte{1}, 32),
		ProvideTlsCertificatePem: []byte("synthetic certificate"),
		ProvideTlsPrivateKeyPem:  []byte("synthetic private key"),
	}
	if err := saveProviderIdentity(stateDir, identity); err != nil {
		return err
	}
	loaded, err := loadProviderIdentity(stateDir, clientId)
	if err != nil || loaded == nil || !bytes.Equal(loaded.ClientKeySeed, identity.ClientKeySeed) || !bytes.Equal(loaded.ProvideTlsPrivateKeyPem, identity.ProvideTlsPrivateKeyPem) {
		return fmt.Errorf("identity round trip failed (%v)", err)
	}
	if keyMaterial := loaded.keyMaterial(); !bytes.Equal(keyMaterial.GetClientKeySeed(), identity.ClientKeySeed) {
		return errors.New("identity key material differs")
	}
	other, err := loadProviderIdentity(stateDir, "22222222-2222-2222-2222-222222222222")
	if err != nil || other != nil {
		return fmt.Errorf("another client's identity was used (%v)", err)
	}
	// without an identity the device gets no key material and makes a new identity
	if keyMaterial := other.keyMaterial(); keyMaterial != nil {
		return errors.New("a first run passes key material")
	}
	if err := writePrivateFile(filepath.Join(stateDir, identityFileName), []byte(`{"version":1}`)); err != nil {
		return err
	}
	if _, err := loadProviderIdentity(stateDir, clientId); err == nil {
		return errors.New("an invalid identity was accepted")
	}
	return nil
}

// A missing or incomplete installation state is refused; a first run loads.
func checkProviderConfig() error {
	if _, err := loadProviderConfig(""); err == nil {
		return errors.New("a missing state directory was accepted")
	}
	if _, err := loadProviderConfig("relative/state"); err == nil {
		return errors.New("a relative state directory was accepted")
	}
	stateDir, err := os.MkdirTemp("", "ur-provider-self-test-")
	if err != nil {
		return err
	}
	defer os.RemoveAll(stateDir)
	if err := os.Chmod(stateDir, 0o700); err != nil {
		return err
	}
	if _, err := loadProviderConfig(stateDir); err == nil {
		return errors.New("a state directory without client.jwt was accepted")
	}
	// a network jwt has no client_id claim
	networkJwt := selfTestJwt(`{"network_id":"22222222-2222-2222-2222-222222222222"}`)
	if err := writePrivateFile(filepath.Join(stateDir, clientJwtFileName), []byte(networkJwt+"\n")); err != nil {
		return err
	}
	if _, err := loadProviderConfig(stateDir); err == nil {
		return errors.New("a network jwt was accepted")
	}
	clientJwt := selfTestJwt(`{"client_id":"11111111-1111-1111-1111-111111111111"}`)
	if err := writePrivateFile(filepath.Join(stateDir, clientJwtFileName), []byte(clientJwt+"\n")); err != nil {
		return err
	}
	config, err := loadProviderConfig(stateDir)
	if err != nil {
		return err
	}
	if config.clientJwt != clientJwt || config.clientId != "11111111-1111-1111-1111-111111111111" || config.instanceId == "" || config.identity != nil {
		return errors.New("first-run configuration differs")
	}
	return nil
}
