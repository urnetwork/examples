// The credential-free self-test (EMBED_CONTRACT.md, the token server's
// "Self-test" and "Backend tools"). The token server's HTTP handler runs in
// process and the commands run against a mock API: no credentials, no
// network and no listener. `--self-test` runs every check; main_test.go runs
// each one as a test.
package main

import (
	"bytes"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"sync"
)

// A stand-in root credential: no answer, output or log line may contain it.
const selfTestRootCredential = "urn_selftest_root_credential_never_printed_0123456789"

// demo session tokens of the self-test
const selfTestAliceToken = "alice-demo-session-token-0123456789abcdef"
const selfTestBobToken = "bob-demo-session-token-0123456789abcdef00"

// installation ids of the self-test
const (
	selfTestInstallationA = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
	selfTestInstallationB = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
	selfTestInstallationC = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
	selfTestInstallationD = "dddddddd-dddd-4ddd-8ddd-dddddddddddd"
	selfTestInstallationE = "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"
	selfTestInstallationF = "ffffffff-ffff-4fff-8fff-ffffffffffff"
)

const selfTestClientId = "11111111-1111-1111-1111-111111111111"

// Runs every check and returns the first failure.
func runSelfTest() error {
	checks := []func() error{
		checkKeysAndOptions,
		checkMergeBodies,
		checkCapObjects,
		checkApiOrigin,
		checkRootCredentialKinds,
		checkClientMap,
		checkDemoSessions,
		checkTokenServer,
		checkTokenServerDefaultCaps,
		checkTokenServerAclGroups,
		checkTokenServerLimits,
		checkCommands,
		checkCommandAclGroups,
		checkUsageAll,
	}
	for _, check := range checks {
		if err := check(); err != nil {
			return err
		}
	}
	return nil
}

// A synthetic, unsigned JWT with the given payload json.
func selfTestJwt(payloadJson string) string {
	return "e30." + base64.RawURLEncoding.EncodeToString([]byte(payloadJson)) + ".test"
}

// The scoped client JWT that the mock issues for clientId.
func selfTestClientJwt(clientId string) string {
	return selfTestJwt(fmt.Sprintf(`{"client_id":%q,"network_id":"22222222-2222-2222-2222-222222222222"}`, clientId))
}

// One request the mock received.
type mockCall struct {
	method string
	path   string
	body   string
}

// One page of GET /network/client-data-caps.
type mockPage struct {
	clientIds  []string
	nextCursor *string
}

// A client's caps in the mock; nil means no cap.
type mockCaps struct {
	monthly *int64
	total   *int64
}

// A URnetwork API in memory, enough for the backend's calls.
type mockApi struct {
	mutex sync.Mutex
	calls []mockCall
	// the clients that exist
	clients map[string]bool
	created int
	// "", "client_limit_exceeded" or "upgrade_required": refuse new clients
	refuseNew string
	// the next setCapFailures POST /network/client-data-cap calls answer 500
	setCapFailures int
	getCapFails    bool
	// when not 0, every call answers this status (401: the root credential refused)
	status int
	pages  map[string]mockPage
	caps   map[string]*mockCaps
	// the clients' ACL groups; a client absent here is "default"
	aclGroups map[string]string
	// the next setAclFailures POST /network/client-acl-group calls answer 500
	setAclFailures int
	// a server without ACL groups: their route answers 404
	aclUnsupported bool
	// list calls so far; past mockListCallBudget the list answers 500, so a
	// paging loop that never stops fails instead of hanging
	listCalls int
}

// the most GET /network/client-data-caps calls the mock answers
const mockListCallBudget = 20

// An empty mock.
func newMockApi() *mockApi {
	return &mockApi{
		clients:   map[string]bool{},
		pages:     map[string]mockPage{},
		caps:      map[string]*mockCaps{},
		aclGroups: map[string]string{},
	}
}

// The calls to one route (the path without its query).
func (self *mockApi) callsTo(route string) []mockCall {
	self.mutex.Lock()
	defer self.mutex.Unlock()
	var calls []mockCall
	for _, call := range self.calls {
		if strings.SplitN(call.path, "?", 2)[0] == route {
			calls = append(calls, call)
		}
	}
	return calls
}

// Drops a client, as the server does after 30 days without connecting.
func (self *mockApi) deleteClient(clientId string) {
	self.mutex.Lock()
	defer self.mutex.Unlock()
	delete(self.clients, clientId)
}

// The cap object of clientId.
func (self *mockApi) capObjectJson(clientId string) string {
	caps := self.caps[clientId]
	if caps == nil {
		caps = &mockCaps{}
	}
	limit := func(value *int64) string {
		if value == nil {
			return "null"
		}
		return fmt.Sprintf("%d", *value)
	}
	return fmt.Sprintf(`{"client_id":%q,"monthly_byte_limit":%s,"monthly_used_byte_count":0,"monthly_period_start":"2026-10-01T00:00:00Z","monthly_period_end":"2026-11-01T00:00:00Z","total_byte_limit":%s,"total_used_byte_count":0,"total_period_start":"2026-09-15T00:00:00Z","capped":false,"capped_reason":""}`, clientId, limit(caps.monthly), limit(caps.total))
}

// Answers one request.
func (self *mockApi) handle(method string, path string, body []byte) (int, []byte, error) {
	self.mutex.Lock()
	defer self.mutex.Unlock()
	self.calls = append(self.calls, mockCall{method: method, path: path, body: string(body)})
	if self.status != 0 {
		return self.status, []byte(`{}`), nil
	}
	route, query, _ := strings.Cut(path, "?")
	values, _ := url.ParseQuery(query)
	answer := func(clientId string) []byte {
		data, _ := json.Marshal(map[string]string{"client_id": clientId, "by_client_jwt": selfTestClientJwt(clientId)})
		return data
	}
	switch {
	case method == http.MethodPost && route == "/network/auth-client":
		var args map[string]any
		if json.Unmarshal(body, &args) != nil {
			return http.StatusBadRequest, []byte(`{}`), nil
		}
		if clientId, ok := args["client_id"].(string); ok {
			if !self.clients[clientId] {
				return http.StatusOK, []byte(`{"error":{"client_limit_exceeded":false,"message":"Client does not exist."}}`), nil
			}
			return http.StatusOK, answer(clientId), nil
		}
		switch self.refuseNew {
		case "client_limit_exceeded":
			return http.StatusOK, []byte(`{"error":{"client_limit_exceeded":true,"message":"Client limit exceeded."}}`), nil
		case "upgrade_required":
			return http.StatusOK, []byte(`{"error":{"client_limit_exceeded":false,"upgrade_required":true,"message":"Upgrade required."}}`), nil
		}
		self.created += 1
		clientId := fmt.Sprintf("%08x-1111-4111-8111-%012x", self.created, self.created)
		self.clients[clientId] = true
		return http.StatusOK, answer(clientId), nil
	case method == http.MethodPost && route == "/network/client-data-cap":
		if 0 < self.setCapFailures {
			self.setCapFailures -= 1
			return http.StatusInternalServerError, []byte(`{}`), nil
		}
		var args map[string]json.RawMessage
		if json.Unmarshal(body, &args) != nil {
			return http.StatusBadRequest, []byte(`{}`), nil
		}
		var clientId string
		json.Unmarshal(args["client_id"], &clientId)
		caps := self.caps[clientId]
		if caps == nil {
			caps = &mockCaps{}
			self.caps[clientId] = caps
		}
		for name, value := range map[string]**int64{"monthly_byte_limit": &caps.monthly, "total_byte_limit": &caps.total} {
			raw, present := args[name]
			if !present {
				continue
			}
			var parsed *int64
			json.Unmarshal(raw, &parsed)
			*value = parsed
		}
		return http.StatusOK, []byte(self.capObjectJson(clientId)), nil
	case method == http.MethodGet && route == "/network/client-data-cap":
		if self.getCapFails {
			return http.StatusInternalServerError, []byte(`{}`), nil
		}
		return http.StatusOK, []byte(self.capObjectJson(values.Get("client_id"))), nil
	case method == http.MethodGet && route == "/network/client-data-caps":
		self.listCalls += 1
		if mockListCallBudget < self.listCalls {
			return http.StatusInternalServerError, []byte(`{}`), nil
		}
		page := self.pages[values.Get("cursor")]
		capObjects := []json.RawMessage{}
		for _, clientId := range page.clientIds {
			capObjects = append(capObjects, json.RawMessage(self.capObjectJson(clientId)))
		}
		data, _ := json.Marshal(map[string]any{"clients": capObjects, "next_cursor": page.nextCursor})
		return http.StatusOK, data, nil
	case route == "/network/client-acl-group" && self.aclUnsupported:
		return http.StatusNotFound, []byte(`404 page not found`), nil
	case method == http.MethodPost && route == "/network/client-acl-group":
		if 0 < self.setAclFailures {
			self.setAclFailures -= 1
			return http.StatusInternalServerError, []byte(`{}`), nil
		}
		var args map[string]string
		if json.Unmarshal(body, &args) != nil || (args["acl_group"] != "default" && args["acl_group"] != "isolated") {
			return http.StatusOK, []byte(`{"error":{"message":"Invalid ACL group."}}`), nil
		}
		if !self.clients[args["client_id"]] {
			return http.StatusOK, []byte(`{"error":{"message":"Client does not exist."}}`), nil
		}
		self.aclGroups[args["client_id"]] = args["acl_group"]
		data, _ := json.Marshal(map[string]string{"client_id": args["client_id"], "acl_group": args["acl_group"]})
		return http.StatusOK, data, nil
	case method == http.MethodPost && route == "/network/remove-client":
		var args map[string]string
		json.Unmarshal(body, &args)
		if !self.clients[args["client_id"]] {
			return http.StatusOK, []byte(`{"error":{"message":"Client does not exist."}}`), nil
		}
		delete(self.clients, args["client_id"])
		return http.StatusOK, []byte(`{}`), nil
	}
	return http.StatusNotFound, []byte(`{}`), nil
}

// A private state directory with a client map path and a demo session file
// for alice and bob.
type selfTestFiles struct {
	dir          string
	mapPath      string
	sessionsPath string
}

// Creates the self-test files; remove dir when done.
func newSelfTestFiles() (*selfTestFiles, error) {
	dir, err := os.MkdirTemp("", "ur-embed-token-server-self-test-")
	if err != nil {
		return nil, err
	}
	if err := os.Chmod(dir, 0o700); err != nil {
		os.RemoveAll(dir)
		return nil, err
	}
	files := &selfTestFiles{
		dir:          dir,
		mapPath:      filepath.Join(dir, "clients.json"),
		sessionsPath: filepath.Join(dir, "sessions.json"),
	}
	sessions := fmt.Sprintf(`{"version":1,"sessions":{%q:"alice",%q:"bob"}}`, selfTestAliceToken, selfTestBobToken)
	if err := writePrivateFile(files.sessionsPath, []byte(sessions)); err != nil {
		os.RemoveAll(dir)
		return nil, err
	}
	return files, nil
}

// An environment over the mock api, with the given settings and captured
// output. Every call checks that it carries the root credential.
func selfTestEnvironment(api *mockApi, settings map[string]string) (*environment, *bytes.Buffer, *bytes.Buffer) {
	stdout := &bytes.Buffer{}
	stderr := &bytes.Buffer{}
	return &environment{
		getenv: func(name string) string {
			return settings[name]
		},
		stdout: stdout,
		stderr: stderr,
		transport: func(origin string, rootCredential string) apiTransport {
			return func(method string, path string, body []byte) (int, []byte, error) {
				if rootCredential != selfTestRootCredential {
					return http.StatusUnauthorized, []byte(`{}`), nil
				}
				return api.handle(method, path, body)
			}
		},
	}, stdout, stderr
}

// The settings that every check uses.
func (self *selfTestFiles) settings() map[string]string {
	return map[string]string{
		"URNETWORK_ROOT_JWT":      selfTestRootCredential,
		"URNETWORK_CLIENT_MAP":    self.mapPath,
		"URNETWORK_DEMO_SESSIONS": self.sessionsPath,
		"URNETWORK_API_URL":       "https://api.example.test",
	}
}

// Keys follow the allocator pattern; cap options are strict.
func checkKeysAndOptions() error {
	for _, key := range []string{"user:alice", "user:alice:" + selfTestInstallationA, "user:a.b@c_d-e"} {
		if !keyPattern.MatchString(key) {
			return fmt.Errorf("key %q refused", key)
		}
	}
	for _, key := range []string{selfTestClientId, "user:", "user:../a", "user:a b", "alice", "user:" + strings.Repeat("a", 124)} {
		if keyPattern.MatchString(key) {
			return fmt.Errorf("key %q accepted", key)
		}
	}
	invalid := [][]string{
		{},
		{"--monthly"},
		{"--monthly", "10GB"},
		{"--monthly", "-1"},
		{"--monthly", "+1"},
		{"--monthly", "01"},
		{"--monthly", "9223372036854775808"},
		{"--monthly", "1", "--monthly", "2"},
		{"--reset-total", "--reset-total"},
		{"--weekly", "1"},
	}
	for _, args := range invalid {
		_, err := parseCapOptions(args)
		var configErr *configError
		if err == nil || !errors.As(err, &configErr) {
			return fmt.Errorf("cap options %q accepted or not a configuration error (%v)", args, err)
		}
	}
	valid := [][]string{
		{"--monthly", "9223372036854775807"},
		{"--total", "null"},
		{"--reset-total"},
		{"--monthly", "0", "--total", "null", "--reset-total"},
	}
	for _, args := range valid {
		if _, err := parseCapOptions(args); err != nil {
			return fmt.Errorf("cap options %q refused: %v", args, err)
		}
	}
	return nil
}

// An omitted option is absent from the JSON, null is JSON null, byte counts
// are integers, and reset_total appears only when given.
func checkMergeBodies() error {
	cases := []struct {
		args []string
		body string
	}{
		{args: []string{"--monthly", "10000000000"}, body: `{"client_id":"11111111-1111-1111-1111-111111111111","monthly_byte_limit":10000000000}`},
		{args: []string{"--total", "null"}, body: `{"client_id":"11111111-1111-1111-1111-111111111111","total_byte_limit":null}`},
		{args: []string{"--reset-total"}, body: `{"client_id":"11111111-1111-1111-1111-111111111111","reset_total":true}`},
		{args: []string{"--monthly", "0", "--total", "5", "--reset-total"}, body: `{"client_id":"11111111-1111-1111-1111-111111111111","monthly_byte_limit":0,"reset_total":true,"total_byte_limit":5}`},
	}
	for _, c := range cases {
		options, err := parseCapOptions(c.args)
		if err != nil {
			return err
		}
		body, err := options.body(selfTestClientId)
		if err != nil || string(body) != c.body {
			return fmt.Errorf("cap body %s for %q, want %s (%v)", body, c.args, c.body, err)
		}
	}
	monthly := int64(10)
	total := int64(20)
	for _, c := range []struct {
		defaults defaultCaps
		body     string
	}{
		{defaults: defaultCaps{monthly: &monthly}, body: `{"client_id":"11111111-1111-1111-1111-111111111111","monthly_byte_limit":10}`},
		{defaults: defaultCaps{total: &total}, body: `{"client_id":"11111111-1111-1111-1111-111111111111","total_byte_limit":20}`},
	} {
		body, err := c.defaults.body(selfTestClientId)
		if err != nil || string(body) != c.body {
			return fmt.Errorf("default caps body %s, want %s (%v)", body, c.body, err)
		}
	}
	return nil
}

// Cap objects are checked and returned with every field, compacted.
func checkCapObjects() error {
	full := "{\n \"client_id\": \"11111111-1111-1111-1111-111111111111\", \"monthly_byte_limit\": 5000000000, \"monthly_used_byte_count\": 1234567890,\n \"monthly_period_start\": \"2026-10-01T00:00:00Z\", \"monthly_period_end\": \"2026-11-01T00:00:00Z\", \"total_byte_limit\": null, \"total_used_byte_count\": 1234567890, \"total_period_start\": \"2026-09-15T00:00:00Z\", \"capped\": false, \"capped_reason\": \"\", \"future_field\": 1}"
	compact, err := parseCapObject([]byte(full), selfTestClientId)
	if err != nil || strings.ContainsAny(string(compact), "\n ") || !strings.Contains(string(compact), `"future_field":1`) || !strings.Contains(string(compact), `"total_byte_limit":null`) {
		return fmt.Errorf("cap object %s (%v)", compact, err)
	}
	if _, err := parseCapObject([]byte(`{"client_id":"11111111-1111-1111-1111-111111111111"}`), ""); err != nil {
		return fmt.Errorf("a cap object without limits refused: %v", err)
	}
	var refusal *apiRefusal
	if _, err := parseCapObject([]byte(`{"error":{"message":"no such client"}}`), ""); !errors.As(err, &refusal) {
		return fmt.Errorf("a cap error object is not a refusal (%v)", err)
	}
	for _, invalid := range []string{`not json`, `{"client_id":"not-a-uuid"}`, `{"client_id":"22222222-2222-2222-2222-222222222222"}`, `{"client_id":"11111111-1111-1111-1111-111111111111","monthly_byte_limit":"5"}`} {
		if _, err := parseCapObject([]byte(invalid), selfTestClientId); err == nil {
			return fmt.Errorf("invalid cap object %s accepted", invalid)
		}
	}
	return nil
}

// The api url is an https origin, or explicit loopback http.
func checkApiOrigin() error {
	for apiUrl, origin := range map[string]string{
		"https://api.bringyour.com":  "https://api.bringyour.com",
		"https://api.bringyour.com/": "https://api.bringyour.com",
		"http://127.0.0.1:8080":      "http://127.0.0.1:8080",
		"http://localhost":           "http://localhost",
		"http://[::1]:1":             "http://[::1]:1",
	} {
		if got, err := apiOrigin(apiUrl); err != nil || got != origin {
			return fmt.Errorf("api url %q is %q (%v)", apiUrl, got, err)
		}
	}
	for _, apiUrl := range []string{"http://example.com", "https://example.com/path", "https://user@example.com", "https://example.com?x=1", "https://example.com#f", "ftp://example.com"} {
		if _, err := apiOrigin(apiUrl); err == nil {
			return fmt.Errorf("api url %q accepted", apiUrl)
		}
	}
	return nil
}

// An API key or a network JWT is a root credential; a client JWT is not.
func checkRootCredentialKinds() error {
	for _, rootCredential := range []string{"urn_0123456789abcdef", selfTestJwt(`{"network_id":"22222222-2222-2222-2222-222222222222"}`)} {
		if _, err := checkRootCredential(rootCredential); err != nil {
			return fmt.Errorf("root credential refused: %v", err)
		}
	}
	for _, rootCredential := range []string{"", "urn_a b", selfTestClientJwt(selfTestClientId)} {
		_, err := checkRootCredential(rootCredential)
		var configErr *configError
		if !errors.As(err, &configErr) {
			return fmt.Errorf("invalid root credential accepted (%v)", err)
		}
	}
	return nil
}

// The map is private, atomic and strict, always written with pending_caps,
// and its lock directory makes another process wait.
func checkClientMap() error {
	files, err := newSelfTestFiles()
	if err != nil {
		return err
	}
	defer os.RemoveAll(files.dir)
	store := newMapStore(files.mapPath)
	clients, err := store.Load()
	if err != nil || len(clients.Clients) != 0 {
		return fmt.Errorf("a missing map is not empty (%v)", err)
	}
	key := "user:alice:" + selfTestInstallationA
	clients.Clients[key] = selfTestClientId
	clients.SetPending(key, true)
	if err := store.Save(clients); err != nil {
		return err
	}
	loaded, err := store.Load()
	if err != nil || loaded.Clients[key] != selfTestClientId || !loaded.Pending(key) {
		return fmt.Errorf("map round trip failed (%v)", err)
	}
	loaded.SetPending(key, false)
	if err := store.Save(loaded); err != nil {
		return err
	}
	data, err := os.ReadFile(files.mapPath)
	if err != nil || !strings.Contains(string(data), `"pending_caps":[]`) {
		return fmt.Errorf("the saved map has no pending_caps: %s", data)
	}
	if runtime.GOOS != "windows" {
		info, err := os.Stat(files.mapPath)
		if err != nil || info.Mode().Perm() != 0o600 {
			return fmt.Errorf("map mode %v (%v)", info.Mode().Perm(), err)
		}
	}
	// an allocator map, without pending_caps, loads
	if err := writePrivateFile(files.mapPath, []byte(`{"version":1,"clients":{"user:alice":"11111111-1111-1111-1111-111111111111"}}`)); err != nil {
		return err
	}
	if _, err := store.Load(); err != nil {
		return fmt.Errorf("a map without pending_caps refused: %v", err)
	}
	invalidMaps := []string{
		`{"version":1,"clients":{},"pending_caps":[],"extra":true}`,
		`{"version":2,"clients":{}}`,
		`{"version":1}`,
		`{"version":1,"clients":{"alice":"11111111-1111-1111-1111-111111111111"}}`,
		`{"version":1,"clients":{"user:a":"11111111-1111-1111-1111-111111111111","user:b":"11111111-1111-1111-1111-111111111111"}}`,
		`{"version":1,"clients":{},"pending_caps":["user:a"]}`,
		`{"version":1,"clients":{}} {}`,
	}
	for _, invalidMap := range invalidMaps {
		if err := writePrivateFile(files.mapPath, []byte(invalidMap)); err != nil {
			return err
		}
		_, err := store.Load()
		var mapErr *mapError
		if !errors.As(err, &mapErr) {
			return fmt.Errorf("invalid map %s accepted (%v)", invalidMap, err)
		}
	}
	if runtime.GOOS != "windows" {
		if err := writePrivateFile(files.mapPath, []byte(`{"version":1,"clients":{}}`)); err != nil {
			return err
		}
		if err := os.Chmod(files.mapPath, 0o644); err != nil {
			return err
		}
		if _, err := store.Load(); err == nil {
			return errors.New("a group-readable map was accepted")
		}
		os.Remove(files.mapPath)
		target := filepath.Join(files.dir, "target.json")
		if err := writePrivateFile(target, []byte(`{"version":1,"clients":{}}`)); err != nil {
			return err
		}
		if err := os.Symlink(target, files.mapPath); err != nil {
			return err
		}
		if _, err := store.Load(); err == nil {
			return errors.New("a symlinked map was accepted")
		}
		os.Remove(files.mapPath)
	}
	// another process's lock directory makes the lock busy
	if err := os.Mkdir(files.mapPath+".lock", 0o700); err != nil {
		return err
	}
	var busy *busyError
	if _, err := store.Lock(); !errors.As(err, &busy) {
		return fmt.Errorf("a held lock is not busy (%v)", err)
	}
	os.Remove(files.mapPath + ".lock")
	unlock, err := store.Lock()
	if err != nil {
		return err
	}
	unlock()
	if _, err := os.Stat(files.mapPath + ".lock"); !errors.Is(err, os.ErrNotExist) {
		return errors.New("the lock directory stays after unlock")
	}
	return nil
}

// Demo sessions authenticate in constant time and refuse bad files.
func checkDemoSessions() error {
	files, err := newSelfTestFiles()
	if err != nil {
		return err
	}
	defer os.RemoveAll(files.dir)
	sessions, err := loadDemoSessions(files.sessionsPath)
	if err != nil {
		return err
	}
	if userId, ok := sessions.UserId(selfTestAliceToken); !ok || userId != "alice" {
		return fmt.Errorf("alice's session is %q (%t)", userId, ok)
	}
	if userId, ok := sessions.UserId(selfTestBobToken); !ok || userId != "bob" {
		return fmt.Errorf("bob's session is %q (%t)", userId, ok)
	}
	for _, unknown := range []string{"", "unknown-demo-session-token-0123456789", selfTestAliceToken[:31]} {
		if _, ok := sessions.UserId(unknown); ok {
			return fmt.Errorf("unknown session %q accepted", unknown)
		}
	}
	invalidFiles := []string{
		`{"version":1,"sessions":{"short":"alice"}}`,
		fmt.Sprintf(`{"version":1,"sessions":{%q:"alice:laptop"}}`, selfTestAliceToken),
		fmt.Sprintf(`{"version":1,"sessions":{%q:""}}`, selfTestAliceToken),
		fmt.Sprintf(`{"version":1,"sessions":{%q:"alice"}}`, "token with spaces 0123456789abcdef0123"),
		fmt.Sprintf(`{"version":2,"sessions":{%q:"alice"}}`, selfTestAliceToken),
		`{"version":1,"sessions":{}}`,
		`not json`,
	}
	for _, invalidFile := range invalidFiles {
		if err := writePrivateFile(files.sessionsPath, []byte(invalidFile)); err != nil {
			return err
		}
		_, err := loadDemoSessions(files.sessionsPath)
		var configErr *configError
		if !errors.As(err, &configErr) {
			return fmt.Errorf("invalid session file %s accepted (%v)", invalidFile, err)
		}
	}
	if runtime.GOOS != "windows" {
		if err := writePrivateFile(files.sessionsPath, []byte(fmt.Sprintf(`{"version":1,"sessions":{%q:"alice"}}`, selfTestAliceToken))); err != nil {
			return err
		}
		if err := os.Chmod(files.sessionsPath, 0o644); err != nil {
			return err
		}
		if _, err := loadDemoSessions(files.sessionsPath); err == nil {
			return errors.New("a group-readable session file was accepted")
		}
	}
	return nil
}

// One token request against the server's handler.
type selfTestAnswer struct {
	status  int
	header  http.Header
	body    string
	decoded struct {
		ClientId    string          `json:"client_id"`
		ByClientJwt string          `json:"by_client_jwt"`
		DataCap     json.RawMessage `json:"data_cap"`
		Error       *struct {
			Code    string `json:"code"`
			Message string `json:"message"`
		} `json:"error"`
	}
}

// Posts body to path with an authorization header ("" for none) and records
// every answer, so the check can scan them for the root credential.
func selfTestRequest(server *tokenServer, answers *[]*selfTestAnswer, method string, path string, authorization string, body string) *selfTestAnswer {
	request := httptest.NewRequest(method, path, strings.NewReader(body))
	if authorization != "" {
		request.Header.Set("Authorization", authorization)
	}
	recorder := httptest.NewRecorder()
	server.ServeHTTP(recorder, request)
	answer := &selfTestAnswer{status: recorder.Code, header: recorder.Header(), body: recorder.Body.String()}
	json.Unmarshal(recorder.Body.Bytes(), &answer.decoded)
	*answers = append(*answers, answer)
	return answer
}

// Posts a token request for an installation with a session token.
func selfTestTokenRequest(server *tokenServer, answers *[]*selfTestAnswer, token string, installationId string) *selfTestAnswer {
	return selfTestRequest(server, answers, http.MethodPost, tokenRoute, "Bearer "+token, fmt.Sprintf(`{"installation_id":%q}`, installationId))
}

// A token server over the mock with captured logs.
func newSelfTestTokenServer(api *mockApi, settings map[string]string) (*tokenServer, *bytes.Buffer, error) {
	env, _, _ := selfTestEnvironment(api, settings)
	server, _, err := newTokenServer(env)
	if err != nil {
		return nil, nil, err
	}
	logs := &bytes.Buffer{}
	server.logf = func(format string, args ...any) {
		fmt.Fprintf(logs, format+"\n", args...)
	}
	return server, logs, nil
}

// Checks one answer's status, error code and Cache-Control.
func expectAnswer(answer *selfTestAnswer, status int, code string) error {
	if answer.status != status || answer.header.Get("Cache-Control") != "no-store" || answer.header.Get("Content-Type") != "application/json" {
		return fmt.Errorf("answer %d (Cache-Control %q), want %d: %s", answer.status, answer.header.Get("Cache-Control"), status, answer.body)
	}
	if code == "" {
		if answer.decoded.Error != nil || !uuidPattern.MatchString(answer.decoded.ClientId) {
			return fmt.Errorf("a success answer without a client: %s", answer.body)
		}
		var claims struct {
			ClientId string `json:"client_id"`
		}
		if err := decodeJwtClaims(answer.decoded.ByClientJwt, &claims); err != nil || claims.ClientId != answer.decoded.ClientId {
			return fmt.Errorf("the answered JWT does not name the client: %s", answer.body)
		}
		return nil
	}
	if answer.decoded.Error == nil || answer.decoded.Error.Code != code || answer.decoded.ByClientJwt != "" {
		return fmt.Errorf("answer %s, want error code %q", answer.body, code)
	}
	return nil
}

// Checks that no answer, header or log line holds the root credential.
func expectNoRootCredential(answers []*selfTestAnswer, outputs ...string) error {
	for _, answer := range answers {
		header, _ := json.Marshal(answer.header)
		if strings.Contains(answer.body, selfTestRootCredential) || strings.Contains(string(header), selfTestRootCredential) {
			return errors.New("an answer contains the root credential")
		}
	}
	for _, output := range outputs {
		if strings.Contains(output, selfTestRootCredential) {
			return errors.New("output contains the root credential")
		}
	}
	return nil
}

// The token server's answers: routes, methods, sessions and bodies; a new key
// versus a reissue with the right wire fields; a deactivated client
// re-provisioned; data_cap null when the cap read fails; busy and upstream.
func checkTokenServer() error {
	files, err := newSelfTestFiles()
	if err != nil {
		return err
	}
	defer os.RemoveAll(files.dir)
	api := newMockApi()
	server, logs, err := newSelfTestTokenServer(api, files.settings())
	if err != nil {
		return err
	}
	var answers []*selfTestAnswer
	body := fmt.Sprintf(`{"installation_id":%q}`, selfTestInstallationA)

	if err := expectAnswer(selfTestRequest(server, &answers, http.MethodPost, "/other", "Bearer "+selfTestAliceToken, body), http.StatusNotFound, "not_found"); err != nil {
		return err
	}
	notAllowed := selfTestRequest(server, &answers, http.MethodGet, tokenRoute, "Bearer "+selfTestAliceToken, "")
	if err := expectAnswer(notAllowed, http.StatusMethodNotAllowed, "method_not_allowed"); err != nil {
		return err
	}
	if notAllowed.header.Get("Allow") != http.MethodPost {
		return errors.New("405 without Allow: POST")
	}
	for _, authorization := range []string{"", "Bearer unknown-demo-session-token-0123456789", "Basic " + selfTestAliceToken, "Bearer"} {
		if err := expectAnswer(selfTestRequest(server, &answers, http.MethodPost, tokenRoute, authorization, body), http.StatusUnauthorized, "unauthorized"); err != nil {
			return err
		}
	}
	invalidBodies := []string{
		"not json",
		`{"installation_id":"AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA"}`,
		`{"installation_id":"not-a-uuid"}`,
		`{}`,
		`{"installation_id":42}`,
		body + " {}",
		fmt.Sprintf(`{"installation_id":%q,"padding":%q}`, selfTestInstallationA, strings.Repeat("x", 4*1024)),
	}
	for _, invalidBody := range invalidBodies {
		if err := expectAnswer(selfTestRequest(server, &answers, http.MethodPost, tokenRoute, "Bearer "+selfTestAliceToken, invalidBody), http.StatusBadRequest, "invalid_request"); err != nil {
			return err
		}
	}
	if 0 < len(api.callsTo("/network/auth-client")) {
		return errors.New("a refused request reached the URnetwork API")
	}

	// a new key: no client_id and no source_client_id on the wire
	first := selfTestTokenRequest(server, &answers, selfTestAliceToken, selfTestInstallationA)
	if err := expectAnswer(first, http.StatusOK, ""); err != nil {
		return err
	}
	authCalls := api.callsTo("/network/auth-client")
	if len(authCalls) != 1 || strings.Contains(authCalls[0].body, "client_id") || !strings.Contains(authCalls[0].body, `"description":"embed installation"`) || !strings.Contains(authCalls[0].body, `"device_spec":"urnetwork-examples/embed-token-server"`) {
		return fmt.Errorf("new client request %v", authCalls)
	}
	var dataCap struct {
		ClientId string `json:"client_id"`
	}
	if json.Unmarshal(first.decoded.DataCap, &dataCap) != nil || dataCap.ClientId != first.decoded.ClientId {
		return fmt.Errorf("data_cap is not the client's cap object: %s", first.body)
	}
	clients, err := newMapStore(files.mapPath).Load()
	if err != nil || clients.Clients["user:alice:"+selfTestInstallationA] != first.decoded.ClientId {
		return fmt.Errorf("the new client is not mapped (%v)", err)
	}

	// a reissue: only the stored client_id is added; no caps without defaults
	second := selfTestTokenRequest(server, &answers, selfTestAliceToken, selfTestInstallationA)
	if err := expectAnswer(second, http.StatusOK, ""); err != nil {
		return err
	}
	authCalls = api.callsTo("/network/auth-client")
	if second.decoded.ClientId != first.decoded.ClientId || len(authCalls) != 2 || !strings.Contains(authCalls[1].body, fmt.Sprintf(`"client_id":%q`, first.decoded.ClientId)) || strings.Contains(authCalls[1].body, "source_client_id") {
		return fmt.Errorf("reissue request %v", authCalls)
	}
	for _, call := range api.callsTo("/network/client-data-cap") {
		if call.method != http.MethodGet {
			return errors.New("caps were set without default caps")
		}
	}

	// a reissue that fails for any reason but "Client does not exist." keeps
	// the mapping and creates no client
	api.status = http.StatusInternalServerError
	failedReissue := selfTestTokenRequest(server, &answers, selfTestAliceToken, selfTestInstallationA)
	api.status = 0
	if err := expectAnswer(failedReissue, http.StatusBadGateway, "upstream"); err != nil {
		return err
	}
	clients, err = newMapStore(files.mapPath).Load()
	if err != nil || clients.Clients["user:alice:"+selfTestInstallationA] != first.decoded.ClientId || api.created != 1 {
		return fmt.Errorf("a failed reissue dropped the mapping or created a client (%v)", err)
	}

	// the cap read never blocks the token
	api.getCapFails = true
	withoutCap := selfTestTokenRequest(server, &answers, selfTestAliceToken, selfTestInstallationA)
	if err := expectAnswer(withoutCap, http.StatusOK, ""); err != nil {
		return err
	}
	if !strings.Contains(withoutCap.body, `"data_cap":null`) {
		return fmt.Errorf("a failed cap read answered %s", withoutCap.body)
	}
	api.getCapFails = false

	// a deactivated client is replaced by a new client
	api.deleteClient(first.decoded.ClientId)
	replaced := selfTestTokenRequest(server, &answers, selfTestAliceToken, selfTestInstallationA)
	if err := expectAnswer(replaced, http.StatusOK, ""); err != nil {
		return err
	}
	clients, err = newMapStore(files.mapPath).Load()
	if err != nil || replaced.decoded.ClientId == first.decoded.ClientId || clients.Clients["user:alice:"+selfTestInstallationA] != replaced.decoded.ClientId {
		return fmt.Errorf("a deactivated client was not re-provisioned (%v)", err)
	}

	// another process holds the map lock
	if err := os.Mkdir(files.mapPath+".lock", 0o700); err != nil {
		return err
	}
	busy := selfTestTokenRequest(server, &answers, selfTestAliceToken, selfTestInstallationA)
	os.Remove(files.mapPath + ".lock")
	if err := expectAnswer(busy, http.StatusServiceUnavailable, "busy"); err != nil {
		return err
	}

	// the URnetwork API fails
	api.status = http.StatusInternalServerError
	upstream := selfTestTokenRequest(server, &answers, selfTestAliceToken, selfTestInstallationB)
	api.status = 0
	if err := expectAnswer(upstream, http.StatusBadGateway, "upstream"); err != nil {
		return err
	}
	if !strings.Contains(logs.String(), "POST "+tokenRoute+" 200") || strings.Contains(logs.String(), selfTestInstallationA) || strings.Contains(logs.String(), "alice") {
		return fmt.Errorf("log lines carry more than the route, status and latency:\n%s", logs.String())
	}
	return expectNoRootCredential(answers, logs.String())
}

// Default caps go once to each new client, never to a reissue; pending_caps
// is set before and cleared after, and a failed apply is retried on the next
// request.
func checkTokenServerDefaultCaps() error {
	files, err := newSelfTestFiles()
	if err != nil {
		return err
	}
	defer os.RemoveAll(files.dir)
	settings := files.settings()
	settings["URNETWORK_DEFAULT_MONTHLY_BYTE_LIMIT"] = "10000000000"
	api := newMockApi()
	server, logs, err := newSelfTestTokenServer(api, settings)
	if err != nil {
		return err
	}
	var answers []*selfTestAnswer
	setCaps := func() []mockCall {
		var calls []mockCall
		for _, call := range api.callsTo("/network/client-data-cap") {
			if call.method == http.MethodPost {
				calls = append(calls, call)
			}
		}
		return calls
	}

	first := selfTestTokenRequest(server, &answers, selfTestAliceToken, selfTestInstallationA)
	if err := expectAnswer(first, http.StatusOK, ""); err != nil {
		return err
	}
	if calls := setCaps(); len(calls) != 1 || calls[0].body != fmt.Sprintf(`{"client_id":%q,"monthly_byte_limit":10000000000}`, first.decoded.ClientId) {
		return fmt.Errorf("default caps request %v", calls)
	}
	if !strings.Contains(first.body, `"monthly_byte_limit":10000000000`) {
		return fmt.Errorf("data_cap does not show the default cap: %s", first.body)
	}
	clients, err := newMapStore(files.mapPath).Load()
	if err != nil || len(clients.PendingCaps) != 0 {
		return fmt.Errorf("pending_caps not cleared after the default caps applied (%v)", err)
	}
	if err := expectAnswer(selfTestTokenRequest(server, &answers, selfTestAliceToken, selfTestInstallationA), http.StatusOK, ""); err != nil {
		return err
	}
	if len(setCaps()) != 1 {
		return errors.New("a reissue applied the default caps again")
	}

	// the apply fails: no token, and the key stays pending
	api.setCapFailures = 1
	failed := selfTestTokenRequest(server, &answers, selfTestAliceToken, selfTestInstallationB)
	if err := expectAnswer(failed, http.StatusBadGateway, "upstream"); err != nil {
		return err
	}
	key := "user:alice:" + selfTestInstallationB
	clients, err = newMapStore(files.mapPath).Load()
	if err != nil || clients.Clients[key] == "" || !clients.Pending(key) {
		return fmt.Errorf("a failed apply did not keep the key pending (%v)", err)
	}
	pendingClientId := clients.Clients[key]
	authCount := len(api.callsTo("/network/auth-client"))

	// the next request reissues and applies the default caps first
	retried := selfTestTokenRequest(server, &answers, selfTestAliceToken, selfTestInstallationB)
	if err := expectAnswer(retried, http.StatusOK, ""); err != nil {
		return err
	}
	authCalls := api.callsTo("/network/auth-client")
	if retried.decoded.ClientId != pendingClientId || len(authCalls) != authCount+1 || !strings.Contains(authCalls[authCount].body, pendingClientId) {
		return errors.New("the retry did not reissue the pending client")
	}
	if calls := setCaps(); len(calls) != 3 || !strings.Contains(calls[2].body, pendingClientId) {
		return fmt.Errorf("the retry did not apply the default caps: %v", calls)
	}
	clients, err = newMapStore(files.mapPath).Load()
	if err != nil || clients.Pending(key) {
		return fmt.Errorf("pending_caps not cleared after the retry (%v)", err)
	}
	return expectNoRootCredential(answers, logs.String())
}

// The installation limit, the client limit for both refusal flags, and the
// private settings files.
func checkTokenServerLimits() error {
	files, err := newSelfTestFiles()
	if err != nil {
		return err
	}
	defer os.RemoveAll(files.dir)
	settings := files.settings()
	settings["URNETWORK_MAX_INSTALLATIONS_PER_USER"] = "2"
	api := newMockApi()
	server, logs, err := newSelfTestTokenServer(api, settings)
	if err != nil {
		return err
	}
	var answers []*selfTestAnswer
	for _, installationId := range []string{selfTestInstallationA, selfTestInstallationB} {
		if err := expectAnswer(selfTestTokenRequest(server, &answers, selfTestAliceToken, installationId), http.StatusOK, ""); err != nil {
			return err
		}
	}
	if err := expectAnswer(selfTestTokenRequest(server, &answers, selfTestAliceToken, selfTestInstallationC), http.StatusConflict, "installation_limit"); err != nil {
		return err
	}
	// a mapped installation still reissues at the limit, and other users are unaffected
	if err := expectAnswer(selfTestTokenRequest(server, &answers, selfTestAliceToken, selfTestInstallationA), http.StatusOK, ""); err != nil {
		return err
	}
	if err := expectAnswer(selfTestTokenRequest(server, &answers, selfTestBobToken, selfTestInstallationD), http.StatusOK, ""); err != nil {
		return err
	}
	for refusal, installationId := range map[string]string{"client_limit_exceeded": selfTestInstallationE, "upgrade_required": selfTestInstallationF} {
		api.refuseNew = refusal
		answer := selfTestTokenRequest(server, &answers, selfTestBobToken, installationId)
		if err := expectAnswer(answer, http.StatusConflict, "client_limit"); err != nil {
			return err
		}
		if !strings.Contains(answer.decoded.Error.Message, "https://ur.io/services") {
			return fmt.Errorf("the client limit answer does not point to the Services page: %s", answer.body)
		}
	}
	api.refuseNew = ""
	if err := expectNoRootCredential(answers, logs.String()); err != nil {
		return err
	}

	// settings are refused when invalid or not private
	invalidSettings := []map[string]string{
		{"URNETWORK_MAX_INSTALLATIONS_PER_USER": "0"},
		{"URNETWORK_DEMO_SESSIONS": "sessions.json"},
		{"URNETWORK_DEFAULT_TOTAL_BYTE_LIMIT": "10GB"},
		{"URNETWORK_CLIENT_MAP": "clients.json"},
		{"URNETWORK_API_URL": "http://api.example.test"},
	}
	for _, overrides := range invalidSettings {
		changed := files.settings()
		for name, value := range overrides {
			changed[name] = value
		}
		env, _, _ := selfTestEnvironment(api, changed)
		_, _, err := newTokenServer(env)
		var configErr *configError
		if !errors.As(err, &configErr) {
			return fmt.Errorf("token server settings %v accepted (%v)", overrides, err)
		}
	}
	if runtime.GOOS != "windows" {
		if err := os.Chmod(files.sessionsPath, 0o644); err != nil {
			return err
		}
		env, _, _ := selfTestEnvironment(api, files.settings())
		if _, _, err := newTokenServer(env); err == nil {
			return errors.New("a group-readable session file was accepted")
		}
		if err := os.Chmod(files.sessionsPath, 0o600); err != nil {
			return err
		}
		if err := os.Chmod(files.dir, 0o755); err != nil {
			return err
		}
		_, _, err := newTokenServer(env)
		os.Chmod(files.dir, 0o700)
		if err == nil {
			return errors.New("a group-readable state directory was accepted")
		}
	}
	return nil
}

// The commands: provision, cap, usage and remove, their refusals and their
// exit codes; the client JWT goes only to its private file.
func checkCommands() error {
	files, err := newSelfTestFiles()
	if err != nil {
		return err
	}
	defer os.RemoveAll(files.dir)
	api := newMockApi()
	var outputs []string
	runWith := func(settings map[string]string, args ...string) (int, string, string) {
		env, stdout, stderr := selfTestEnvironment(api, settings)
		code := run(args, env)
		outputs = append(outputs, stdout.String(), stderr.String())
		return code, stdout.String(), stderr.String()
	}
	settings := files.settings()
	key := "user:alice:" + selfTestInstallationA
	jwtPath := filepath.Join(files.dir, "alice.jwt")

	missing := files.settings()
	delete(missing, "URNETWORK_ROOT_JWT")
	for _, c := range []struct {
		settings map[string]string
		args     []string
	}{
		{settings: missing, args: []string{"usage", key}},
		{settings: settings, args: []string{"provision", "not-a-key", jwtPath}},
		{settings: settings, args: []string{"frobnicate"}},
		{settings: settings, args: []string{"cap", key}},
		{settings: settings, args: []string{"usage", key}},
		{settings: map[string]string{"URNETWORK_ROOT_JWT": selfTestClientJwt(selfTestClientId), "URNETWORK_CLIENT_MAP": files.mapPath}, args: []string{"usage", key}},
	} {
		if code, _, stderr := runWith(c.settings, c.args...); code != exitConfig || strings.Count(stderr, "\n") != 1 {
			return fmt.Errorf("%q exit %d, want %d, with one stderr line: %q", c.args, code, exitConfig, stderr)
		}
	}

	// provision: a new client, its JWT only in the private file
	code, stdout, stderr := runWith(settings, "provision", key, jwtPath)
	if code != exitSuccess {
		return fmt.Errorf("provision exit %d: %s", code, stderr)
	}
	clients, err := newMapStore(files.mapPath).Load()
	if err != nil {
		return err
	}
	clientId := clients.Clients[key]
	if stdout != fmt.Sprintf("{\"client_id\":%q}\n", clientId) {
		return fmt.Errorf("provision printed %q", stdout)
	}
	authCalls := api.callsTo("/network/auth-client")
	if len(authCalls) != 1 || strings.Contains(authCalls[0].body, "client_id") || !strings.Contains(authCalls[0].body, `"description":"embed client"`) || !strings.Contains(authCalls[0].body, `"device_spec":"urnetwork-examples/go-embed-server"`) {
		return fmt.Errorf("provision request %v", authCalls)
	}
	jwt, err := readPrivateFile(jwtPath)
	if err != nil || string(jwt) != selfTestClientJwt(clientId)+"\n" {
		return fmt.Errorf("the client JWT file (%v)", err)
	}
	if strings.Contains(stdout+stderr, selfTestClientJwt(clientId)) {
		return errors.New("provision printed the client JWT")
	}
	if code, _, _ := runWith(settings, "provision", key, jwtPath); code != exitSuccess {
		return errors.New("a second provision failed")
	}
	authCalls = api.callsTo("/network/auth-client")
	if len(authCalls) != 2 || !strings.Contains(authCalls[1].body, fmt.Sprintf(`"client_id":%q`, clientId)) {
		return fmt.Errorf("a second provision did not reissue: %v", authCalls)
	}
	api.deleteClient(clientId)
	if code, stdout, _ := runWith(settings, "provision", key, jwtPath); code != exitSuccess || strings.Contains(stdout, clientId) {
		return fmt.Errorf("a deactivated client was not re-provisioned: %q", stdout)
	}
	clients, err = newMapStore(files.mapPath).Load()
	if err != nil {
		return err
	}
	clientId = clients.Clients[key]

	// cap and usage print the cap object
	code, stdout, stderr = runWith(settings, "cap", key, "--monthly", "5000000000")
	var printed struct {
		ClientId         string `json:"client_id"`
		MonthlyByteLimit *int64 `json:"monthly_byte_limit"`
	}
	if code != exitSuccess || json.Unmarshal([]byte(stdout), &printed) != nil || printed.ClientId != clientId || printed.MonthlyByteLimit == nil || *printed.MonthlyByteLimit != 5000000000 {
		return fmt.Errorf("cap exit %d printed %q (%s)", code, stdout, stderr)
	}
	var setCalls []mockCall
	for _, call := range api.callsTo("/network/client-data-cap") {
		if call.method == http.MethodPost {
			setCalls = append(setCalls, call)
		}
	}
	if len(setCalls) != 1 || setCalls[0].body != fmt.Sprintf(`{"client_id":%q,"monthly_byte_limit":5000000000}`, clientId) {
		return fmt.Errorf("cap request %v", setCalls)
	}
	if code, stdout, _ := runWith(settings, "usage", key); code != exitSuccess || !strings.Contains(stdout, `"monthly_byte_limit":5000000000`) || strings.Count(stdout, "\n") != 1 {
		return fmt.Errorf("usage exit %d printed %q", code, stdout)
	}

	// refusals and exit codes
	api.refuseNew = "upgrade_required"
	if code, _, stderr := runWith(settings, "provision", "user:alice:"+selfTestInstallationB, filepath.Join(files.dir, "b.jwt")); code != exitConfig || stderr != clientLimitMessage+"\n" {
		return fmt.Errorf("a client limit refusal exit %d: %q", code, stderr)
	}
	api.refuseNew = ""
	api.status = http.StatusUnauthorized
	if code, _, _ := runWith(settings, "usage", key); code != exitConfig {
		return fmt.Errorf("a refused root credential exit %d", code)
	}
	api.status = http.StatusInternalServerError
	if code, _, _ := runWith(settings, "usage", key); code != exitFailure {
		return fmt.Errorf("a server error exit %d", code)
	}
	api.status = http.StatusNotFound
	if code, _, stderr := runWith(settings, "usage", key); code != exitFailure || stderr != "/network/client-data-cap answered 404: the server predates the data-cap routes\n" {
		return fmt.Errorf("a server without the cap routes exit %d: %q", code, stderr)
	}
	api.status = 0
	for _, args := range [][]string{{"cap", "user:nobody", "--monthly", "1"}, {"usage", "user:nobody"}, {"remove", "user:nobody"}} {
		if code, _, stderr := runWith(settings, args...); code != exitConfig || stderr != unmappedKeyMessage+"\n" {
			return fmt.Errorf("%q for an unmapped key exit %d: %q", args, code, stderr)
		}
	}
	if err := os.Mkdir(files.mapPath+".lock", 0o700); err != nil {
		return err
	}
	code, _, _ = runWith(settings, "provision", key, jwtPath)
	os.Remove(files.mapPath + ".lock")
	if code != exitFailure {
		return fmt.Errorf("a busy map exit %d", code)
	}

	// remove drops the mapping, also when the client is already gone
	if code, stdout, _ := runWith(settings, "remove", key); code != exitSuccess || stdout != fmt.Sprintf("{\"removed\":%q}\n", clientId) {
		return fmt.Errorf("remove exit %d printed %q", code, stdout)
	}
	otherKey := "user:bob"
	if code, _, _ := runWith(settings, "provision", otherKey, filepath.Join(files.dir, "bob.jwt")); code != exitSuccess {
		return errors.New("provision of a user key failed")
	}
	clients, err = newMapStore(files.mapPath).Load()
	if err != nil {
		return err
	}
	api.deleteClient(clients.Clients[otherKey])
	if code, _, _ := runWith(settings, "remove", otherKey); code != exitSuccess {
		return errors.New("remove of a missing client failed")
	}
	clients, err = newMapStore(files.mapPath).Load()
	if err != nil || len(clients.Clients) != 0 {
		return fmt.Errorf("remove kept a mapping (%v)", err)
	}

	// a map with an unknown field is refused
	if err := writePrivateFile(files.mapPath, []byte(`{"version":1,"clients":{},"pending_caps":[],"owner":"allocator"}`)); err != nil {
		return err
	}
	if code, _, _ := runWith(settings, "provision", key, jwtPath); code != exitConfig {
		return fmt.Errorf("a map with an unknown field exit %d", code)
	}
	return expectNoRootCredential(nil, outputs...)
}

// usage-all pages until a null cursor, and stops at a repeated one.
func checkUsageAll() error {
	files, err := newSelfTestFiles()
	if err != nil {
		return err
	}
	defer os.RemoveAll(files.dir)
	cursor := func(text string) *string {
		return &text
	}
	ids := []string{"11111111-1111-1111-1111-111111111111", "22222222-2222-2222-2222-222222222222", "33333333-3333-3333-3333-333333333333"}
	for _, c := range []struct {
		pages map[string]mockPage
		lines int
		calls int
	}{
		{
			pages: map[string]mockPage{
				"":   {clientIds: ids[:2], nextCursor: cursor("c1")},
				"c1": {clientIds: ids[2:], nextCursor: cursor("c2")},
				"c2": {nextCursor: nil},
			},
			lines: 3,
			calls: 3,
		},
		{
			pages: map[string]mockPage{
				"":   {clientIds: ids[:1], nextCursor: cursor("c1")},
				"c1": {clientIds: ids[1:2], nextCursor: cursor("c1")},
			},
			lines: 2,
			calls: 2,
		},
	} {
		api := newMockApi()
		api.pages = c.pages
		env, stdout, stderr := selfTestEnvironment(api, files.settings())
		if code := run([]string{"usage-all"}, env); code != exitSuccess {
			return fmt.Errorf("usage-all exit %d: %s", code, stderr)
		}
		lines := strings.Split(strings.TrimSuffix(stdout.String(), "\n"), "\n")
		calls := api.callsTo("/network/client-data-caps")
		if len(lines) != c.lines || len(calls) != c.calls {
			return fmt.Errorf("usage-all printed %d lines in %d calls, want %d in %d", len(lines), len(calls), c.lines, c.calls)
		}
		for i, line := range lines {
			if !strings.Contains(line, ids[i]) {
				return fmt.Errorf("usage-all line %d is %s", i, line)
			}
		}
		if !strings.Contains(calls[0].path, "limit=1000") || strings.Contains(calls[0].path, "cursor=") || !strings.Contains(calls[1].path, "cursor=c1") {
			return fmt.Errorf("usage-all requests %v", calls)
		}
	}
	return nil
}

// The ACL route's calls with a body (POSTs).
func (self *mockApi) aclSets() []mockCall {
	var calls []mockCall
	for _, call := range self.callsTo("/network/client-acl-group") {
		if call.method == http.MethodPost {
			calls = append(calls, call)
		}
	}
	return calls
}

// The default ACL group: isolated for each new client, never again on a
// reissue, recorded in pending_acl until it applies, retried after a failure,
// reported without failing the token on a server without ACL groups, and
// skipped when the default is "default".
func checkTokenServerAclGroups() error {
	files, err := newSelfTestFiles()
	if err != nil {
		return err
	}
	defer os.RemoveAll(files.dir)
	api := newMockApi()
	server, logs, err := newSelfTestTokenServer(api, files.settings())
	if err != nil {
		return err
	}
	var answers []*selfTestAnswer
	first := selfTestTokenRequest(server, &answers, selfTestAliceToken, selfTestInstallationA)
	if err := expectAnswer(first, http.StatusOK, ""); err != nil {
		return err
	}
	if calls := api.aclSets(); len(calls) != 1 || calls[0].body != fmt.Sprintf(`{"acl_group":"isolated","client_id":%q}`, first.decoded.ClientId) {
		return fmt.Errorf("default ACL group request %v", calls)
	}
	clients, err := newMapStore(files.mapPath).Load()
	if err != nil || len(clients.PendingAcl) != 0 {
		return fmt.Errorf("pending_acl not cleared after the ACL group applied (%v)", err)
	}
	if err := expectAnswer(selfTestTokenRequest(server, &answers, selfTestAliceToken, selfTestInstallationA), http.StatusOK, ""); err != nil {
		return err
	}
	if len(api.aclSets()) != 1 {
		return errors.New("a reissue applied the default ACL group again")
	}

	// the apply fails: no token, and the key stays in pending_acl
	api.setAclFailures = 1
	if err := expectAnswer(selfTestTokenRequest(server, &answers, selfTestAliceToken, selfTestInstallationB), http.StatusBadGateway, "upstream"); err != nil {
		return err
	}
	key := "user:alice:" + selfTestInstallationB
	clients, err = newMapStore(files.mapPath).Load()
	if err != nil || clients.Clients[key] == "" || !clients.AclPending(key) {
		return fmt.Errorf("a failed ACL apply did not keep the key pending (%v)", err)
	}
	retried := selfTestTokenRequest(server, &answers, selfTestAliceToken, selfTestInstallationB)
	if err := expectAnswer(retried, http.StatusOK, ""); err != nil {
		return err
	}
	if calls := api.aclSets(); len(calls) != 3 || !strings.Contains(calls[2].body, retried.decoded.ClientId) {
		return fmt.Errorf("the retry did not apply the ACL group: %v", calls)
	}
	if clients, err = newMapStore(files.mapPath).Load(); err != nil || clients.AclPending(key) {
		return fmt.Errorf("pending_acl not cleared after the retry (%v)", err)
	}

	// a server without ACL groups: the token still answers, once reported,
	// and the key stays pending until the server has them
	api.aclUnsupported = true
	bobKey := "user:bob:" + selfTestInstallationC
	for i := 0; i < 2; i += 1 {
		if err := expectAnswer(selfTestTokenRequest(server, &answers, selfTestBobToken, selfTestInstallationC), http.StatusOK, ""); err != nil {
			return err
		}
	}
	if clients, err = newMapStore(files.mapPath).Load(); err != nil || !clients.AclPending(bobKey) {
		return fmt.Errorf("a server without ACL groups did not keep the key pending (%v)", err)
	}
	if strings.Count(logs.String(), "predates ACL groups") != 1 {
		return fmt.Errorf("a server without ACL groups was not reported once: %q", logs.String())
	}
	api.aclUnsupported = false
	if err := expectAnswer(selfTestTokenRequest(server, &answers, selfTestBobToken, selfTestInstallationC), http.StatusOK, ""); err != nil {
		return err
	}
	if clients, err = newMapStore(files.mapPath).Load(); err != nil || clients.AclPending(bobKey) {
		return fmt.Errorf("pending_acl not cleared once the server had ACL groups (%v)", err)
	}

	// URNETWORK_DEFAULT_ACL_GROUP=default: no call and no record
	defaultFiles, err := newSelfTestFiles()
	if err != nil {
		return err
	}
	defer os.RemoveAll(defaultFiles.dir)
	defaultSettings := defaultFiles.settings()
	defaultSettings["URNETWORK_DEFAULT_ACL_GROUP"] = "default"
	defaultApi := newMockApi()
	defaultServer, _, err := newSelfTestTokenServer(defaultApi, defaultSettings)
	if err != nil {
		return err
	}
	if err := expectAnswer(selfTestTokenRequest(defaultServer, &answers, selfTestAliceToken, selfTestInstallationA), http.StatusOK, ""); err != nil {
		return err
	}
	if len(defaultApi.aclSets()) != 0 {
		return errors.New("the default group \"default\" still called the ACL route")
	}
	if clients, err := newMapStore(defaultFiles.mapPath).Load(); err != nil || len(clients.PendingAcl) != 0 {
		return fmt.Errorf("the default group \"default\" left a pending_acl record (%v)", err)
	}
	badSettings := defaultFiles.settings()
	badSettings["URNETWORK_DEFAULT_ACL_GROUP"] = "private"
	if _, _, err := newSelfTestTokenServer(newMockApi(), badSettings); err == nil {
		return errors.New("an invalid URNETWORK_DEFAULT_ACL_GROUP was accepted")
	}
	return expectNoRootCredential(answers, logs.String())
}

// The acl command and provision's default ACL group: the request, the
// printed answer, the usage and unmapped-key errors, and a server without
// ACL groups (exit 1 with the fixed text, no client JWT written, the key kept
// in pending_acl; a default of "default" then provisions).
func checkCommandAclGroups() error {
	files, err := newSelfTestFiles()
	if err != nil {
		return err
	}
	defer os.RemoveAll(files.dir)
	api := newMockApi()
	runWith := func(settings map[string]string, args ...string) (int, string, string) {
		env, stdout, stderr := selfTestEnvironment(api, settings)
		code := run(args, env)
		return code, stdout.String(), stderr.String()
	}
	settings := files.settings()
	key := "user:alice:" + selfTestInstallationA
	if code, _, stderr := runWith(settings, "provision", key, filepath.Join(files.dir, "a.jwt")); code != exitSuccess {
		return fmt.Errorf("provision exit %d: %s", code, stderr)
	}
	clients, err := newMapStore(files.mapPath).Load()
	if err != nil {
		return err
	}
	clientId := clients.Clients[key]
	if calls := api.aclSets(); len(calls) != 1 || !strings.Contains(calls[0].body, `"acl_group":"isolated"`) || len(clients.PendingAcl) != 0 {
		return fmt.Errorf("provision did not apply the default ACL group: %v", calls)
	}
	if code, stdout, stderr := runWith(settings, "acl", key, "default"); code != exitSuccess || stdout != fmt.Sprintf("{\"acl_group\":\"default\",\"client_id\":%q}\n", clientId) {
		return fmt.Errorf("acl exit %d printed %q (%s)", code, stdout, stderr)
	}
	if calls := api.aclSets(); len(calls) != 2 || calls[1].body != fmt.Sprintf(`{"acl_group":"default","client_id":%q}`, clientId) {
		return fmt.Errorf("acl request %v", calls)
	}
	if code, _, _ := runWith(settings, "acl", key, "private"); code != exitConfig {
		return fmt.Errorf("acl with an invalid group exit %d", code)
	}
	if code, _, stderr := runWith(settings, "acl", "user:nobody", "isolated"); code != exitConfig || stderr != unmappedKeyMessage+"\n" {
		return fmt.Errorf("acl for an unmapped key exit %d: %q", code, stderr)
	}

	api.aclUnsupported = true
	if code, _, stderr := runWith(settings, "acl", key, "isolated"); code != exitFailure || stderr != errAclUnsupported.Error()+"\n" {
		return fmt.Errorf("acl on a server without ACL groups exit %d: %q", code, stderr)
	}
	newKey := "user:alice:" + selfTestInstallationB
	newJwtPath := filepath.Join(files.dir, "b.jwt")
	if code, _, stderr := runWith(settings, "provision", newKey, newJwtPath); code != exitFailure || stderr != errAclUnsupported.Error()+"\n" {
		return fmt.Errorf("provision on a server without ACL groups exit %d: %q", code, stderr)
	}
	if _, err := os.Stat(newJwtPath); !errors.Is(err, os.ErrNotExist) {
		return errors.New("provision wrote a client JWT for a client outside its ACL group")
	}
	if clients, err = newMapStore(files.mapPath).Load(); err != nil || !clients.AclPending(newKey) {
		return fmt.Errorf("provision on a server without ACL groups did not keep the key pending (%v)", err)
	}
	defaultSettings := files.settings()
	defaultSettings["URNETWORK_DEFAULT_ACL_GROUP"] = "default"
	if code, _, stderr := runWith(defaultSettings, "provision", newKey, newJwtPath); code != exitSuccess {
		return fmt.Errorf("provision with the default group \"default\" exit %d: %s", code, stderr)
	}
	if clients, err = newMapStore(files.mapPath).Load(); err != nil || clients.AclPending(newKey) {
		return fmt.Errorf("the default group \"default\" did not clear pending_acl (%v)", err)
	}
	if _, err := os.Stat(newJwtPath); err != nil {
		return errors.New("provision with the default group \"default\" wrote no client JWT")
	}
	return nil
}
