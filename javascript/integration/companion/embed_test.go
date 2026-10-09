// Credential-free tests of embed mode: mode selection, settings, the
// installation state, the /embed-status route and its licenses, the device rpc
// authorization and the credential listeners. None of them creates a device or
// uses the network.
package main

import (
	"bytes"
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"testing"

	sdk "github.com/urnetwork/sdk/v2026"
)

// Fixed device values for the /embed-status route.
type fixedEmbedStatusSource struct {
	windowStatus      *sdk.WindowStatus
	clientLimitStatus *sdk.ClientLimitStatus
	contractStatus    *sdk.ContractStatus
}

// The fixed window status.
func (self *fixedEmbedStatusSource) GetWindowStatus() *sdk.WindowStatus {
	return self.windowStatus
}

// The fixed client limit status.
func (self *fixedEmbedStatusSource) GetClientLimitStatus() *sdk.ClientLimitStatus {
	return self.clientLimitStatus
}

// The fixed contract status.
func (self *fixedEmbedStatusSource) GetContractStatus() *sdk.ContractStatus {
	return self.contractStatus
}

// URNETWORK_COMPANION_EMBED=1 selects embed mode, never with
// URNETWORK_COMPANION_PROVIDE; unset, the provide setting decides as before.
func TestEmbedCompanionMode(t *testing.T) {
	cases := []struct {
		provide string
		embed   string
		mode    companionMode
		valid   bool
	}{
		{provide: "", embed: "", mode: companionModeMessaging, valid: true},
		{provide: "public", embed: "", mode: companionModeProvider, valid: true},
		{provide: "", embed: "1", mode: companionModeEmbed, valid: true},
		// embed mode cannot be combined with provider mode
		{provide: "public", embed: "1", valid: false},
		{provide: "network", embed: "1", valid: false},
		// a typo never starts any mode
		{provide: "", embed: "true", valid: false},
		{provide: "", embed: "0", valid: false},
		{provide: "", embed: " 1", valid: false},
		{provide: "PUBLIC", embed: "", valid: false},
	}
	for _, c := range cases {
		mode, err := selectCompanionMode(c.provide, c.embed)
		if c.valid && (err != nil || mode != c.mode) {
			t.Fatalf("mode for provide %q embed %q is %v (%v), want %v", c.provide, c.embed, mode, err, c.mode)
		}
		if !c.valid && err == nil {
			t.Fatalf("mode for provide %q embed %q was accepted", c.provide, c.embed)
		}
	}
}

// Embed mode runs without arguments, prints for --version or --licenses, and
// refuses anything else as a usage error.
func TestEmbedCommand(t *testing.T) {
	cases := []struct {
		args    []string
		command string
		valid   bool
	}{
		{args: nil, command: "", valid: true},
		{args: []string{"--version"}, command: "--version", valid: true},
		{args: []string{"--licenses"}, command: "--licenses", valid: true},
		{args: []string{"--licenses", "extra"}, valid: false},
		{args: []string{"--version", "--licenses"}, valid: false},
		{args: []string{"run"}, valid: false},
		{args: []string{"--license"}, valid: false},
	}
	for _, c := range cases {
		command, ok := embedCommand(c.args)
		if ok != c.valid || (ok && command != c.command) {
			t.Fatalf("command for %q is %q %v, want %q %v", c.args, command, ok, c.command, c.valid)
		}
	}
	if !strings.Contains(embedUsage, "--licenses") {
		t.Fatalf("the usage does not name --licenses: %s", embedUsage)
	}
}

// --licenses writes one line: the json array that /embed-status carries.
func TestWriteEmbedLicenses(t *testing.T) {
	var out bytes.Buffer
	if code := writeEmbedLicenses(&out, runtime.GOOS); code != exitStopped {
		t.Fatalf("exit code %d", code)
	}
	if !strings.HasSuffix(out.String(), "\n") || strings.Count(out.String(), "\n") != 1 {
		t.Fatalf("the licenses are not one line")
	}
	var licenses []map[string]any
	if err := json.Unmarshal(out.Bytes(), &licenses); err != nil || len(licenses) == 0 {
		t.Fatalf("the licenses are not a json array: %v", err)
	}
	want, err := embedLicensesJson(runtime.GOOS)
	if err != nil || strings.TrimSpace(out.String()) != string(want) {
		t.Fatalf("--licenses differs from the /embed-status licenses")
	}
}

// Embed mode reads its settings with the defaults and refuses invalid ones.
func TestEmbedCompanionSettings(t *testing.T) {
	stateDir := filepath.Join(string(filepath.Separator), "private", "embed-state")
	getenv := func(environment map[string]string) func(string) string {
		return func(key string) string {
			return environment[key]
		}
	}

	settings, err := loadEmbedCompanionSettings(getenv(map[string]string{
		"URNETWORK_EMBED_STATE_DIR": stateDir,
		"URNETWORK_COMPANION_TOKEN": testCompanionToken,
	}))
	if err != nil {
		t.Fatal(err)
	}
	if settings.stateDir != stateDir || settings.token != testCompanionToken || settings.address != "127.0.0.1:8787" ||
		settings.origin != "" || settings.stopOnStdinClose {
		t.Fatalf("default settings %+v", settings)
	}

	settings, err = loadEmbedCompanionSettings(getenv(map[string]string{
		"URNETWORK_EMBED_STATE_DIR":               stateDir,
		"URNETWORK_COMPANION_TOKEN":               testCompanionToken,
		"URNETWORK_COMPANION_ADDRESS":             "127.0.0.1:0",
		"URNETWORK_COMPANION_STOP_ON_STDIN_CLOSE": "1",
	}))
	if err != nil {
		t.Fatal(err)
	}
	if settings.address != "127.0.0.1:0" || !settings.stopOnStdinClose {
		t.Fatalf("configured settings %+v", settings)
	}

	invalid := []map[string]string{
		// no token, and a short one
		{"URNETWORK_EMBED_STATE_DIR": stateDir},
		{"URNETWORK_EMBED_STATE_DIR": stateDir, "URNETWORK_COMPANION_TOKEN": strings.Repeat("a", 31)},
		// not loopback, not numeric
		{"URNETWORK_COMPANION_TOKEN": testCompanionToken, "URNETWORK_COMPANION_ADDRESS": "0.0.0.0:8787"},
		{"URNETWORK_COMPANION_TOKEN": testCompanionToken, "URNETWORK_COMPANION_ADDRESS": "localhost:8787"},
		// an origin with a path
		{"URNETWORK_COMPANION_TOKEN": testCompanionToken, "URNETWORK_COMPANION_ORIGIN": "http://localhost:5173/app"},
		// only 1 stops on a closed input
		{"URNETWORK_COMPANION_TOKEN": testCompanionToken, "URNETWORK_COMPANION_STOP_ON_STDIN_CLOSE": "true"},
	}
	for _, environment := range invalid {
		if _, err := loadEmbedCompanionSettings(getenv(environment)); err == nil {
			t.Fatalf("settings %v were accepted", environment)
		}
	}
}

// The installation state: a private absolute directory with a scoped client
// JWT; instance-id is created once and reused; a network JWT, a malformed token
// and a symlinked credential are refused, and the errors name the embed
// setting.
func TestEmbedConfig(t *testing.T) {
	if _, err := loadEmbedConfig(""); err == nil || !strings.Contains(err.Error(), "URNETWORK_EMBED_STATE_DIR") {
		t.Fatalf("a missing state directory: %v", err)
	}
	if _, err := loadEmbedConfig(filepath.Join("relative", "state")); err == nil || !strings.Contains(err.Error(), "URNETWORK_EMBED_STATE_DIR") {
		t.Fatalf("a relative state directory: %v", err)
	}

	stateDir := testStateDir(t)
	if _, err := loadEmbedConfig(stateDir); err == nil {
		t.Fatal("a state directory without client.jwt was accepted")
	}
	clientJwtPath := filepath.Join(stateDir, clientJwtFileName)
	for _, refused := range []string{
		// a network jwt has no client_id claim
		testJwt(`{"network_id":"22222222-2222-2222-2222-222222222222"}`),
		"not a jwt",
		testJwt(`{"client_id":"not-a-uuid"}`),
	} {
		if err := writePrivateFile(clientJwtPath, []byte(refused+"\n")); err != nil {
			t.Fatal(err)
		}
		if _, err := loadEmbedConfig(stateDir); err == nil {
			t.Fatalf("client.jwt %q was accepted", refused)
		}
	}

	clientJwt := testJwt(`{"client_id":"11111111-1111-1111-1111-111111111111"}`)
	if err := writePrivateFile(clientJwtPath, []byte("  "+clientJwt+"\n")); err != nil {
		t.Fatal(err)
	}
	config, err := loadEmbedConfig(stateDir)
	if err != nil {
		t.Fatal(err)
	}
	if config.clientJwt != clientJwt || config.clientId != "11111111-1111-1111-1111-111111111111" || config.instanceId == "" {
		t.Fatalf("config %+v", config)
	}
	again, err := loadEmbedConfig(stateDir)
	if err != nil || again.instanceId != config.instanceId {
		t.Fatalf("the instance id changed on the second load: %v %v", again, err)
	}
	// an instance-id the app created first is kept
	appInstanceId := "33333333-3333-3333-3333-333333333333"
	if err := writePrivateFile(filepath.Join(stateDir, instanceIdFileName), []byte(appInstanceId+"\n")); err != nil {
		t.Fatal(err)
	}
	if config, err := loadEmbedConfig(stateDir); err != nil || config.instanceId != appInstanceId {
		t.Fatalf("the app's instance id was not used: %v %v", config, err)
	}

	if runtime.GOOS != "windows" {
		// a symlinked credential could be redirected to another file
		target := filepath.Join(stateDir, "elsewhere.jwt")
		if err := writePrivateFile(target, []byte(clientJwt)); err != nil {
			t.Fatal(err)
		}
		if err := os.Remove(clientJwtPath); err != nil {
			t.Fatal(err)
		}
		if err := os.Symlink(target, clientJwtPath); err != nil {
			t.Fatal(err)
		}
		if _, err := loadEmbedConfig(stateDir); err == nil {
			t.Fatal("a symlinked client.jwt was accepted")
		}
		// a directory others can read is refused
		openDir := t.TempDir()
		if err := os.Chmod(openDir, 0o755); err != nil {
			t.Fatal(err)
		}
		if _, err := loadEmbedConfig(openDir); err == nil {
			t.Fatal("a state directory others can access was accepted")
		}
	}
}

// The status route needs the token, answers from the start with the contract's
// shape (no window and no contract status yet, the client limit status never
// null), and then reports the device values with the SDK's Go field names.
func TestEmbedStatusRoute(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	deviceRpc := sdk.NewHostedDeviceRpcListener(ctx)
	defer deviceRpc.Close()
	statusSource := &fixedEmbedStatusSource{}
	licenses := json.RawMessage(`[{"Name":"example","Text":"license text"}]`)
	handler := newEmbedHttpHandler(statusSource, "11111111-1111-1111-1111-111111111111", "33333333-3333-3333-3333-333333333333", licenses, deviceRpc, testCompanionToken, "")
	get := func(url string) *httptest.ResponseRecorder {
		w := httptest.NewRecorder()
		handler.ServeHTTP(w, httptest.NewRequest(http.MethodGet, url, nil))
		return w
	}

	for _, url := range []string{
		"http://127.0.0.1/embed-status",
		"http://127.0.0.1/embed-status?token=" + strings.Repeat("b", 32),
	} {
		if w := get(url); w.Code != http.StatusUnauthorized {
			t.Fatalf("status %d for %s", w.Code, url)
		}
	}
	post := httptest.NewRecorder()
	handler.ServeHTTP(post, httptest.NewRequest(http.MethodPost, "http://127.0.0.1/embed-status?token="+testCompanionToken, nil))
	if post.Code != http.StatusUnauthorized {
		t.Fatalf("status %d for a post", post.Code)
	}

	// before the window exists: null window and contract status, no hold
	w := get("http://127.0.0.1/embed-status?token=" + testCompanionToken)
	body := `{"ClientId":"11111111-1111-1111-1111-111111111111","InstanceId":"33333333-3333-3333-3333-333333333333","WindowStatus":null,"ClientLimitStatus":{"Status":"","RetryTime":0},"ContractStatus":null,"Licenses":[{"Name":"example","Text":"license text"}]}`
	if w.Code != http.StatusOK || w.Body.String() != body || w.Header().Get("Content-Type") != "application/json" || w.Header().Get("Cache-Control") != "no-store" {
		t.Fatalf("status %d %q %q %q", w.Code, w.Body.String(), w.Header().Get("Content-Type"), w.Header().Get("Cache-Control"))
	}

	// the per-second read leaves the licenses out
	w = get("http://127.0.0.1/embed-status?licenses=0&token=" + testCompanionToken)
	body = `{"ClientId":"11111111-1111-1111-1111-111111111111","InstanceId":"33333333-3333-3333-3333-333333333333","WindowStatus":null,"ClientLimitStatus":{"Status":"","RetryTime":0},"ContractStatus":null}`
	if w.Code != http.StatusOK || w.Body.String() != body {
		t.Fatalf("status %d %q without licenses", w.Code, w.Body.String())
	}

	// connected, under a client limit hold, with a contract status
	statusSource.windowStatus = &sdk.WindowStatus{TargetSize: 4, MinSatisfied: true, ProviderStateAdded: 3}
	statusSource.clientLimitStatus = &sdk.ClientLimitStatus{Status: sdk.ClientLimitStatusExceeded, RetryTime: 1791313500000}
	statusSource.contractStatus = &sdk.ContractStatus{InsufficientBalance: true}
	w = get("http://127.0.0.1/embed-status?token=" + testCompanionToken)
	var decoded struct {
		ClientId          string
		InstanceId        string
		WindowStatus      *struct{ ProviderStateAdded int }
		ClientLimitStatus struct {
			Status    string
			RetryTime int64
		}
		ContractStatus *struct{ InsufficientBalance bool }
		Licenses       []struct{ Name string }
	}
	if err := json.Unmarshal(w.Body.Bytes(), &decoded); err != nil {
		t.Fatal(err)
	}
	if w.Code != http.StatusOK || decoded.WindowStatus == nil || decoded.WindowStatus.ProviderStateAdded != 3 ||
		decoded.ClientLimitStatus.Status != "client_limit_exceeded" || decoded.ClientLimitStatus.RetryTime != 1791313500000 ||
		decoded.ContractStatus == nil || !decoded.ContractStatus.InsufficientBalance ||
		len(decoded.Licenses) != 1 || decoded.Licenses[0].Name != "example" {
		t.Fatalf("status %d %s", w.Code, w.Body.String())
	}
}

// The device rpc needs the token and a get, like every route.
func TestEmbedDeviceRpcRouteNeedsToken(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	deviceRpc := sdk.NewHostedDeviceRpcListener(ctx)
	defer deviceRpc.Close()
	handler := newEmbedHttpHandler(&fixedEmbedStatusSource{}, "11111111-1111-1111-1111-111111111111", "33333333-3333-3333-3333-333333333333", json.RawMessage(`[]`), deviceRpc, testCompanionToken, "")
	for _, request := range []*http.Request{
		httptest.NewRequest(http.MethodGet, "http://127.0.0.1/device-rpc", nil),
		httptest.NewRequest(http.MethodGet, "http://127.0.0.1/device-rpc?token="+strings.Repeat("b", 32), nil),
		httptest.NewRequest(http.MethodPost, "http://127.0.0.1/device-rpc?token="+testCompanionToken, nil),
	} {
		w := httptest.NewRecorder()
		handler.ServeHTTP(w, request)
		if w.Code != http.StatusUnauthorized {
			t.Fatalf("status %d for %s %s", w.Code, request.Method, request.URL)
		}
	}
	// a browser Origin other than the allowed one is refused
	request := httptest.NewRequest(http.MethodGet, "http://127.0.0.1/device-rpc?token="+testCompanionToken, nil)
	request.Header.Set("Origin", "https://unrelated.example")
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, request)
	if w.Code != http.StatusUnauthorized {
		t.Fatalf("status %d for a foreign origin", w.Code)
	}
}

// Every OS maps to a GetLicenses app kind; the companion is a desktop binary.
func TestHostLicenseApp(t *testing.T) {
	for goos, app := range map[string]string{
		"darwin":  "apple",
		"ios":     "apple",
		"windows": "windows",
		"linux":   "linux",
		"freebsd": "linux",
		"android": "android",
	} {
		if got := hostLicenseApp(goos); got != app {
			t.Fatalf("app kind for %s is %q, want %q", goos, got, app)
		}
	}
}

// The licenses are the C ABI's json: an array of the SDK's LicenseInfo with
// the Go field names, never empty for a desktop OS.
func TestEmbedLicensesJson(t *testing.T) {
	for _, goos := range []string{"darwin", "windows", "linux"} {
		licensesJson, err := embedLicensesJson(goos)
		if err != nil {
			t.Fatal(err)
		}
		var licenses []struct {
			Name string
			Kind string
			Text string
		}
		if err := json.Unmarshal(licensesJson, &licenses); err != nil {
			t.Fatalf("licenses for %s are not an array: %v", goos, err)
		}
		if len(licenses) == 0 || licenses[0].Name == "" {
			t.Fatalf("no licenses for %s", goos)
		}
		t.Logf("licenses for %s: %d entries, %d bytes of json", goos, len(licenses), len(licensesJson))
	}
}

// A refreshed credential replaces client.jwt privately; the logout closes once.
func TestEmbedCredentialListeners(t *testing.T) {
	stateDir := testStateDir(t)
	companion := &embedCompanion{
		config: &embedConfig{stateDir: stateDir},
		logout: make(chan struct{}),
	}
	refreshed := testJwt(`{"client_id":"11111111-1111-1111-1111-111111111111","exp":2}`)
	(&embedJwtRefreshListener{companion: companion}).JwtRefreshed(refreshed)
	data, err := readPrivateFile(filepath.Join(stateDir, clientJwtFileName))
	if err != nil || strings.TrimSpace(string(data)) != refreshed {
		t.Fatalf("client.jwt after a refresh: %q %v", data, err)
	}

	listener := &embedAuthLogoutListener{companion: companion}
	listener.AuthLogout()
	listener.AuthLogout()
	select {
	case <-companion.logout:
	default:
		t.Fatal("the logout did not end the run")
	}
}
