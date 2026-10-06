// Credential-free tests of provider mode: mode selection, settings, the
// installation state files, the two loopback routes and the stop on input
// close. None of them creates a device or uses the network.
package main

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"testing"
	"time"

	sdk "github.com/urnetwork/sdk/v2026"
)

// sha-256 of the consent disclaimer (utf-8, lf line breaks, no trailing
// newline), published in PROVIDER_CONTRACT.md for every example to check
const consentDisclaimerSha256 = "83edee1e45cccd5deb6b86755cc5f6a91b6ade26e7eefc1e7670b5833a95502c"

// A synthetic companion token of the minimum length.
var testCompanionToken = strings.Repeat("a", 32)

// A synthetic, unsigned JWT with the given payload json.
func testJwt(payloadJson string) string {
	return "e30." + base64.RawURLEncoding.EncodeToString([]byte(payloadJson)) + ".test"
}

// A private, empty state directory that the test removes.
func testStateDir(t *testing.T) string {
	stateDir := t.TempDir()
	if err := os.Chmod(stateDir, 0o700); err != nil {
		t.Fatal(err)
	}
	return stateDir
}

// Fixed device values for the /provider-status route.
type fixedProviderStatusSource struct {
	provideMode         sdk.ProvideMode
	provideEnabled      bool
	providePaused       bool
	providerConnected   bool
	clientLimitStatus   *sdk.ClientLimitStatus
	providerPacketStats *sdk.PacketStats
}

// The fixed provide mode.
func (self *fixedProviderStatusSource) GetProvideMode() sdk.ProvideMode {
	return self.provideMode
}

// The fixed provide enabled state.
func (self *fixedProviderStatusSource) GetProvideEnabled() bool {
	return self.provideEnabled
}

// The fixed provide paused state.
func (self *fixedProviderStatusSource) GetProvidePaused() bool {
	return self.providePaused
}

// The fixed provider packet stats.
func (self *fixedProviderStatusSource) GetProviderPacketStats() *sdk.PacketStats {
	return self.providerPacketStats
}

// The fixed provider connection.
func (self *fixedProviderStatusSource) GetProviderConnected() bool {
	return self.providerConnected
}

// The fixed client limit status.
func (self *fixedProviderStatusSource) GetClientLimitStatus() *sdk.ClientLimitStatus {
	return self.clientLimitStatus
}

// A synthetic id from its text.
func testId(t *testing.T, text string) *sdk.Id {
	id, err := sdk.ParseId(text)
	if err != nil {
		t.Fatal(err)
	}
	return id
}

// Synthetic provider contract details; nil ids are absent.
func testContract(t *testing.T, contractId string, sourceId *sdk.Id, destinationId *sdk.Id, streamId *sdk.Id) *sdk.ContractDetails {
	return &sdk.ContractDetails{
		ContractId:           testId(t, contractId),
		ContractTransferPath: sdk.NewTransferPath(sourceId, destinationId, streamId),
		Status:               sdk.ContractStatusOpen,
	}
}

// Only an unset or "public" URNETWORK_COMPANION_PROVIDE selects a mode.
func TestCompanionMode(t *testing.T) {
	cases := []struct {
		provide string
		mode    companionMode
		valid   bool
	}{
		{provide: "", mode: companionModeMessaging, valid: true},
		{provide: "public", mode: companionModeProvider, valid: true},
		// a typo or another provide mode never starts either mode
		{provide: "PUBLIC", valid: false},
		{provide: " public", valid: false},
		{provide: "network", valid: false},
		{provide: "1", valid: false},
	}
	for _, c := range cases {
		mode, err := parseCompanionMode(c.provide)
		if c.valid && (err != nil || mode != c.mode) {
			t.Fatalf("mode for %q is %v (%v), want %v", c.provide, mode, err, c.mode)
		}
		if !c.valid && err == nil {
			t.Fatalf("mode %q was accepted", c.provide)
		}
	}
}

// Provider mode reads its settings with the defaults and refuses invalid ones.
func TestProviderCompanionSettings(t *testing.T) {
	stateDir := filepath.Join(string(filepath.Separator), "private", "provider-state")
	getenv := func(environment map[string]string) func(string) string {
		return func(key string) string {
			return environment[key]
		}
	}

	settings, err := loadProviderCompanionSettings(getenv(map[string]string{
		"URNETWORK_PROVIDER_STATE_DIR": stateDir,
		"URNETWORK_COMPANION_TOKEN":    testCompanionToken,
	}))
	if err != nil {
		t.Fatal(err)
	}
	if settings.stateDir != stateDir || settings.token != testCompanionToken || settings.origin != "" || settings.address != "127.0.0.1:8787" || settings.stopOnStdinClose {
		t.Fatalf("default settings %+v", settings)
	}

	settings, err = loadProviderCompanionSettings(getenv(map[string]string{
		"URNETWORK_PROVIDER_STATE_DIR":            stateDir,
		"URNETWORK_COMPANION_TOKEN":               testCompanionToken,
		"URNETWORK_COMPANION_ORIGIN":              "http://localhost:5173",
		"URNETWORK_COMPANION_ADDRESS":             "[::1]:8788",
		"URNETWORK_COMPANION_STOP_ON_STDIN_CLOSE": "1",
	}))
	if err != nil {
		t.Fatal(err)
	}
	if settings.origin != "http://localhost:5173" || settings.address != "[::1]:8788" || !settings.stopOnStdinClose {
		t.Fatalf("settings %+v", settings)
	}

	invalidEnvironments := []map[string]string{
		{},
		{"URNETWORK_COMPANION_TOKEN": strings.Repeat("a", 31)},
		{"URNETWORK_COMPANION_TOKEN": testCompanionToken, "URNETWORK_COMPANION_ORIGIN": "http://localhost:5173/app"},
		{"URNETWORK_COMPANION_TOKEN": testCompanionToken, "URNETWORK_COMPANION_ADDRESS": "0.0.0.0:8787"},
		{"URNETWORK_COMPANION_TOKEN": testCompanionToken, "URNETWORK_COMPANION_ADDRESS": "localhost:8787"},
		{"URNETWORK_COMPANION_TOKEN": testCompanionToken, "URNETWORK_COMPANION_ADDRESS": "192.0.2.1:8787"},
		{"URNETWORK_COMPANION_TOKEN": testCompanionToken, "URNETWORK_COMPANION_STOP_ON_STDIN_CLOSE": "true"},
	}
	for _, environment := range invalidEnvironments {
		if _, err := loadProviderCompanionSettings(getenv(environment)); err == nil {
			t.Fatalf("settings %v were accepted", environment)
		}
	}
}

// The disclaimer is the contract's exact text.
func TestConsentDisclaimer(t *testing.T) {
	digest := sha256.Sum256([]byte(consentDisclaimer))
	if hex.EncodeToString(digest[:]) != consentDisclaimerSha256 {
		t.Fatal("consent disclaimer differs from PROVIDER_CONTRACT.md")
	}
}

// Only a JWT with a valid client_id claim is a client credential.
func TestClientJwtClaims(t *testing.T) {
	clientId, err := parseClientJwtClientId(testJwt(`{"client_id":"11111111-1111-1111-1111-111111111111","network_id":"22222222-2222-2222-2222-222222222222"}`))
	if err != nil || clientId != "11111111-1111-1111-1111-111111111111" {
		t.Fatalf("client jwt claim %q (%v)", clientId, err)
	}
	invalidJwts := []string{
		"",
		"not-a-jwt",
		// a network jwt has no client_id claim
		testJwt(`{"network_id":"22222222-2222-2222-2222-222222222222"}`),
		testJwt(`{"client_id":"not-a-uuid"}`),
		"e30.%%%.test",
	}
	for _, invalidJwt := range invalidJwts {
		if _, err := parseClientJwtClientId(invalidJwt); err == nil {
			t.Fatalf("invalid client jwt %q accepted", invalidJwt)
		}
	}
}

// State files are private, replaced atomically, created once and bound to
// their client.
func TestStateFiles(t *testing.T) {
	stateDir := testStateDir(t)

	// an atomic private write
	path := filepath.Join(stateDir, clientJwtFileName)
	if err := writePrivateFile(path, []byte("first\n")); err != nil {
		t.Fatal(err)
	}
	if err := writePrivateFile(path, []byte("second\n")); err != nil {
		t.Fatal(err)
	}
	data, err := readPrivateFile(path)
	if err != nil || !bytes.Equal(data, []byte("second\n")) {
		t.Fatalf("private file round trip %q (%v)", data, err)
	}
	if runtime.GOOS != "windows" {
		info, err := os.Stat(path)
		if err != nil || info.Mode().Perm() != 0o600 {
			t.Fatalf("private file mode %v (%v)", info.Mode().Perm(), err)
		}
		// a file or directory that others can read is refused, as is a symlink
		if err := os.Chmod(path, 0o644); err != nil {
			t.Fatal(err)
		}
		if _, err := readPrivateFile(path); err == nil {
			t.Fatal("a group-readable credential file was accepted")
		}
		if err := os.Chmod(path, 0o600); err != nil {
			t.Fatal(err)
		}
		linkPath := filepath.Join(stateDir, "linked.jwt")
		if err := os.Symlink(path, linkPath); err != nil {
			t.Fatal(err)
		}
		if _, err := readPrivateFile(linkPath); err == nil {
			t.Fatal("a symlinked credential file was accepted")
		}
		if err := os.Chmod(stateDir, 0o755); err != nil {
			t.Fatal(err)
		}
		if err := checkStateDir(stateDir); err == nil {
			t.Fatal("a group-readable state directory was accepted")
		}
		if err := os.Chmod(stateDir, 0o700); err != nil {
			t.Fatal(err)
		}
	}
	if err := checkStateDir(stateDir); err != nil {
		t.Fatal(err)
	}

	// the instance id is created once and reused
	instanceId, err := loadOrCreateInstanceId(stateDir)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := sdk.ParseId(instanceId); err != nil {
		t.Fatalf("instance id %q is not a uuid", instanceId)
	}
	again, err := loadOrCreateInstanceId(stateDir)
	if err != nil || again != instanceId {
		t.Fatalf("instance id changed from %q to %q (%v)", instanceId, again, err)
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
		t.Fatal(err)
	}
	loaded, err := loadProviderIdentity(stateDir, clientId)
	if err != nil || loaded == nil || !bytes.Equal(loaded.ClientKeySeed, identity.ClientKeySeed) || !bytes.Equal(loaded.ProvideTlsPrivateKeyPem, identity.ProvideTlsPrivateKeyPem) {
		t.Fatalf("identity round trip failed (%v)", err)
	}
	if keyMaterial := loaded.keyMaterial(); keyMaterial == nil {
		t.Fatal("a stored identity passes no key material")
	}
	other, err := loadProviderIdentity(stateDir, "22222222-2222-2222-2222-222222222222")
	if err != nil || other != nil {
		t.Fatalf("another client's identity was used (%v)", err)
	}
	// without an identity the device gets no key material and makes a new identity
	if keyMaterial := other.keyMaterial(); keyMaterial != nil {
		t.Fatal("a first run passes key material")
	}
	invalidIdentities := []string{
		`{"version":1}`,
		`{"version":2,"client_id":"11111111-1111-1111-1111-111111111111","client_key_seed":"AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE="}`,
		`{"version":1,"client_id":"11111111-1111-1111-1111-111111111111","client_key_seed":"AQEB"}`,
		`not json`,
	}
	for _, invalidIdentity := range invalidIdentities {
		if err := writePrivateFile(filepath.Join(stateDir, identityFileName), []byte(invalidIdentity)); err != nil {
			t.Fatal(err)
		}
		if _, err := loadProviderIdentity(stateDir, clientId); err == nil {
			t.Fatalf("invalid identity %q was accepted", invalidIdentity)
		}
	}
}

// A missing or incomplete installation state is refused; a first run loads
// without an identity and a second start keeps the instance id.
func TestProviderConfig(t *testing.T) {
	if _, err := loadProviderConfig(""); err == nil {
		t.Fatal("a missing state directory was accepted")
	}
	if _, err := loadProviderConfig(filepath.Join("relative", "state")); err == nil {
		t.Fatal("a relative state directory was accepted")
	}
	stateDir := testStateDir(t)
	if _, err := loadProviderConfig(stateDir); err == nil {
		t.Fatal("a state directory without client.jwt was accepted")
	}
	networkJwt := testJwt(`{"network_id":"22222222-2222-2222-2222-222222222222"}`)
	if err := writePrivateFile(filepath.Join(stateDir, clientJwtFileName), []byte(networkJwt+"\n")); err != nil {
		t.Fatal(err)
	}
	if _, err := loadProviderConfig(stateDir); err == nil {
		t.Fatal("a network jwt was accepted")
	}
	clientJwt := testJwt(`{"client_id":"11111111-1111-1111-1111-111111111111"}`)
	if err := writePrivateFile(filepath.Join(stateDir, clientJwtFileName), []byte(clientJwt+"\n")); err != nil {
		t.Fatal(err)
	}
	config, err := loadProviderConfig(stateDir)
	if err != nil {
		t.Fatal(err)
	}
	if config.clientJwt != clientJwt || config.clientId != "11111111-1111-1111-1111-111111111111" || config.instanceId == "" || config.identity != nil {
		t.Fatalf("first-run configuration %+v", config)
	}
	// the second start keeps the instance id
	again, err := loadProviderConfig(stateDir)
	if err != nil || again.instanceId != config.instanceId {
		t.Fatalf("instance id changed from %q (%v)", config.instanceId, err)
	}
}

// /provider-status needs the token and serves the device values, including
// the client limit hold while the provider is not connected, with the
// contract's status fields under the SDK's Go field names.
func TestProviderStatusRoute(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	deviceRpc := sdk.NewHostedDeviceRpcListener(ctx)
	defer deviceRpc.Close()
	statusSource := &fixedProviderStatusSource{
		provideMode:       sdk.ProvideModePublic,
		providerConnected: false,
		// 2026-10-06 19:05:00.000 UTC
		clientLimitStatus: &sdk.ClientLimitStatus{Status: sdk.ClientLimitStatusExceeded, RetryTime: 1791313500000},
	}
	served := newClientsServed(clientsServedLimit)
	deviceRpcStarted := false
	handler := newProviderHttpHandler(statusSource, served, deviceRpc, func() bool { return deviceRpcStarted }, testCompanionToken, "")
	get := func(target string, origin string) *httptest.ResponseRecorder {
		r := httptest.NewRequest(http.MethodGet, target, nil)
		if origin != "" {
			r.Header.Set("Origin", origin)
		}
		w := httptest.NewRecorder()
		handler.ServeHTTP(w, r)
		return w
	}

	unauthorizedTargets := []string{
		"http://127.0.0.1/provider-status",
		"http://127.0.0.1/provider-status?token=" + strings.Repeat("b", 32),
	}
	for _, target := range unauthorizedTargets {
		if w := get(target, ""); w.Code != http.StatusUnauthorized {
			t.Fatalf("status %d for %s", w.Code, target)
		}
	}
	if w := get("http://127.0.0.1/provider-status?token="+testCompanionToken, "https://unrelated.example"); w.Code != http.StatusUnauthorized {
		t.Fatalf("status %d for a browser origin", w.Code)
	}
	post := httptest.NewRequest(http.MethodPost, "http://127.0.0.1/provider-status?token="+testCompanionToken, nil)
	postRecorder := httptest.NewRecorder()
	handler.ServeHTTP(postRecorder, post)
	if postRecorder.Code != http.StatusUnauthorized {
		t.Fatalf("status %d for a post", postRecorder.Code)
	}

	// the client limit hold is served while the provider is not connected,
	// before the device has provider stats
	w := get("http://127.0.0.1/provider-status?token="+testCompanionToken, "")
	body := `{"ProvideMode":3,"ProvideEnabled":false,"ProvidePaused":false,"ProviderConnected":false,"ClientLimitStatus":{"Status":"client_limit_exceeded","RetryTime":1791313500000},"ProviderPacketStats":null,"ClientsServed":0,"ClientsServedAtLimit":false,"DeviceRpcStarted":false}`
	if w.Code != http.StatusOK || w.Body.String() != body || w.Header().Get("Content-Type") != "application/json" {
		t.Fatalf("status %d %q %q", w.Code, w.Body.String(), w.Header().Get("Content-Type"))
	}

	// providing: two clients, bytes in both directions
	statusSource.provideEnabled = true
	statusSource.providerConnected = true
	statusSource.clientLimitStatus = &sdk.ClientLimitStatus{Status: sdk.ClientLimitStatusNone, RetryTime: 0}
	statusSource.providerPacketStats = &sdk.PacketStats{RemoteEgressPacketCount: 9, RemoteEgressByteCount: 13002335, RemoteIngressByteCount: 7}
	provider := testId(t, "11111111-1111-1111-1111-111111111111")
	served.Add(testContract(t, "55555555-5555-5555-5555-555555555555", testId(t, "22222222-2222-2222-2222-222222222222"), provider, nil), true)
	served.Add(testContract(t, "66666666-6666-6666-6666-666666666666", provider, testId(t, "33333333-3333-3333-3333-333333333333"), nil), false)
	deviceRpcStarted = true
	w = get("http://127.0.0.1/provider-status?token="+testCompanionToken, "")
	if w.Code != http.StatusOK {
		t.Fatalf("status %d %q", w.Code, w.Body.String())
	}
	fields := map[string]json.RawMessage{}
	if err := json.Unmarshal(w.Body.Bytes(), &fields); err != nil {
		t.Fatal(err)
	}
	fieldNames := []string{"ProvideMode", "ProvideEnabled", "ProvidePaused", "ProviderConnected", "ClientLimitStatus", "ProviderPacketStats", "ClientsServed", "ClientsServedAtLimit", "DeviceRpcStarted"}
	if len(fields) != len(fieldNames) {
		t.Fatalf("status fields %q", w.Body.String())
	}
	for _, fieldName := range fieldNames {
		if _, ok := fields[fieldName]; !ok {
			t.Fatalf("status has no %s: %q", fieldName, w.Body.String())
		}
	}
	var providerStatus struct {
		ProvideMode       int
		ProvideEnabled    bool
		ProvidePaused     bool
		ProviderConnected bool
		ClientLimitStatus struct {
			Status    string
			RetryTime int64
		}
		ProviderPacketStats  map[string]int64
		ClientsServed        int
		ClientsServedAtLimit bool
		DeviceRpcStarted     bool
	}
	if err := json.Unmarshal(w.Body.Bytes(), &providerStatus); err != nil {
		t.Fatal(err)
	}
	packetStats := providerStatus.ProviderPacketStats
	if providerStatus.ProvideMode != sdk.ProvideModePublic || !providerStatus.ProvideEnabled || providerStatus.ProvidePaused ||
		!providerStatus.ProviderConnected || providerStatus.ClientLimitStatus.Status != "" || providerStatus.ClientLimitStatus.RetryTime != 0 ||
		packetStats["RemoteEgressByteCount"] != 13002335 || packetStats["RemoteIngressByteCount"] != 7 || packetStats["RemoteEgressPacketCount"] != 9 ||
		providerStatus.ClientsServed != 2 || providerStatus.ClientsServedAtLimit || !providerStatus.DeviceRpcStarted {
		t.Fatalf("providing status %q", w.Body.String())
	}
}

// Contract peers resolve by direction with the contract's fallbacks
// (PROVIDER_CONTRACT.md, peer vectors).
func TestContractPeerKey(t *testing.T) {
	provider := testId(t, "11111111-1111-1111-1111-111111111111")
	clientA := testId(t, "22222222-2222-2222-2222-222222222222")
	stream := testId(t, "44444444-4444-4444-4444-444444444444")
	zero := testId(t, zeroIdString)
	cases := []struct {
		details *sdk.ContractDetails
		receive bool
		peerKey string
	}{
		{details: testContract(t, "55555555-5555-5555-5555-555555555555", clientA, provider, nil), receive: true, peerKey: "22222222-2222-2222-2222-222222222222"},
		{details: testContract(t, "66666666-6666-6666-6666-666666666666", provider, clientA, nil), receive: false, peerKey: "22222222-2222-2222-2222-222222222222"},
		{details: testContract(t, "77777777-7777-7777-7777-777777777777", zero, provider, stream), receive: true, peerKey: "stream:44444444-4444-4444-4444-444444444444"},
		{details: &sdk.ContractDetails{ContractId: testId(t, "88888888-8888-8888-8888-888888888888"), Status: sdk.ContractStatusOpen}, receive: true, peerKey: "contract:88888888-8888-8888-8888-888888888888"},
		{details: &sdk.ContractDetails{Status: sdk.ContractStatusOpen}, receive: true, peerKey: ""},
	}
	for _, c := range cases {
		if peerKey := contractPeerKey(c.details, c.receive); peerKey != c.peerKey {
			t.Fatalf("contract peer key %q, want %q", peerKey, c.peerKey)
		}
	}
}

// Both directions of one client count once, a second client counts two, and
// the count stops at the limit; the listeners feed it by direction.
func TestClientsServed(t *testing.T) {
	provider := testId(t, "11111111-1111-1111-1111-111111111111")
	clientA := testId(t, "22222222-2222-2222-2222-222222222222")
	clientB := testId(t, "33333333-3333-3333-3333-333333333333")
	stream := testId(t, "44444444-4444-4444-4444-444444444444")
	zero := testId(t, zeroIdString)
	served := newClientsServed(2)
	ingress := &contractDetailsListener{clientsServed: served, receive: true}
	egress := &contractDetailsListener{clientsServed: served, receive: false}

	ingress.ContractDetailsChanged(testContract(t, "55555555-5555-5555-5555-555555555555", clientA, provider, nil))
	egress.ContractDetailsChanged(testContract(t, "66666666-6666-6666-6666-666666666666", provider, clientA, nil))
	ingress.ContractDetailsChanged(testContract(t, "99999999-9999-9999-9999-999999999999", clientA, provider, nil))
	ingress.ContractDetailsChanged(nil)
	if count, atLimit := served.Count(); count != 1 || atLimit {
		t.Fatalf("one client counted as %d (at limit %t)", count, atLimit)
	}
	ingress.ContractDetailsChanged(testContract(t, "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", clientB, provider, nil))
	if count, atLimit := served.Count(); count != 2 || atLimit {
		t.Fatalf("two clients counted as %d (at limit %t)", count, atLimit)
	}
	// a third distinct peer reaches the limit of 2
	ingress.ContractDetailsChanged(testContract(t, "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb", zero, provider, stream))
	if count, atLimit := served.Count(); count != 2 || !atLimit {
		t.Fatalf("limited count %d (at limit %t)", count, atLimit)
	}
}

// /device-rpc needs the token and answers 503 until the device rpc starts.
func TestDeviceRpcRouteWaitsForProvider(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	deviceRpc := sdk.NewHostedDeviceRpcListener(ctx)
	defer deviceRpc.Close()
	statusSource := &fixedProviderStatusSource{
		provideMode:       sdk.ProvideModePublic,
		clientLimitStatus: &sdk.ClientLimitStatus{Status: sdk.ClientLimitStatusNone},
	}
	handler := newProviderHttpHandler(statusSource, newClientsServed(clientsServedLimit), deviceRpc, func() bool { return false }, testCompanionToken, "")

	r := httptest.NewRequest(http.MethodGet, "http://127.0.0.1/device-rpc?token="+testCompanionToken, nil)
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, r)
	// the sdk's DeviceRemote dials again until the provider connects
	if w.Code != http.StatusServiceUnavailable {
		t.Fatalf("status %d before the provider connected", w.Code)
	}

	r = httptest.NewRequest(http.MethodGet, "http://127.0.0.1/device-rpc", nil)
	w = httptest.NewRecorder()
	handler.ServeHTTP(w, r)
	if w.Code != http.StatusUnauthorized {
		t.Fatalf("status %d without the token", w.Code)
	}
}

// The run stops when the parent app closes the companion's standard input,
// and not before.
func TestStopOnInputClose(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	inputReader, inputWriter := io.Pipe()
	stopOnInputClose(inputReader, cancel)

	// input before the end is read and ignored
	if _, err := inputWriter.Write([]byte("ignored\n")); err != nil {
		t.Fatal(err)
	}
	select {
	case <-ctx.Done():
		t.Fatal("stopped while the input was open")
	default:
	}

	// the parent app closed the pipe or exited
	inputWriter.Close()
	select {
	case <-ctx.Done():
	case <-time.After(10 * time.Second):
		t.Fatal("did not stop when the input closed")
	}
}

// Usage and configuration errors exit with 78 before any device is created.
func TestProviderExitCodes(t *testing.T) {
	if code := runProvider([]string{"--unknown"}); code != exitConfig {
		t.Fatalf("usage error exit code %d, want %d", code, exitConfig)
	}

	// configuration errors stop before any device is created
	t.Setenv("URNETWORK_COMPANION_TOKEN", "")
	t.Setenv("URNETWORK_PROVIDER_STATE_DIR", "")
	if code := runProvider(nil); code != exitConfig {
		t.Fatalf("missing token exit code %d, want %d", code, exitConfig)
	}
	t.Setenv("URNETWORK_COMPANION_TOKEN", testCompanionToken)
	if code := runProvider(nil); code != exitConfig {
		t.Fatalf("missing state directory exit code %d, want %d", code, exitConfig)
	}
	t.Setenv("URNETWORK_PROVIDER_STATE_DIR", testStateDir(t))
	if code := runProvider(nil); code != exitConfig {
		t.Fatalf("missing client.jwt exit code %d, want %d", code, exitConfig)
	}
}
