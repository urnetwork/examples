// The credential-free self-test (EMBED_CONTRACT.md, "Self-test"). It checks
// the status and data field texts against the contract's vectors, the cap
// object parsing, the client JWT claim, the token fetch against an in-process
// stand-in server, the installation state files and the configuration
// errors: no network, no credentials and no device. `--self-test` runs every
// check; embed_test.go runs each one as a test.
package main

import (
	"bytes"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"runtime"

	sdk "github.com/urnetwork/sdk/v2026"
)

const selfTestClientId = "11111111-1111-1111-1111-111111111111"
const selfTestInstanceId = "33333333-3333-4333-8333-333333333333"
const selfTestSession = "demo-session-token-0123456789abcdef0123"

// Runs every check and returns the first failure.
func runSelfTest() error {
	checks := []func() error{
		checkFormatByteCount,
		checkResetText,
		checkClientLimitText,
		checkStatusLines,
		checkStatusRules,
		checkDataFields,
		checkCapObjects,
		checkClientJwtClaims,
		checkTokenFetch,
		checkStateFiles,
		checkConfigErrors,
		checkLicenseApp,
		checkStartLine,
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

// A scoped client JWT for clientId.
func selfTestClientJwt(clientId string) string {
	return selfTestJwt(fmt.Sprintf(`{"client_id":%q,"network_id":"22222222-2222-2222-2222-222222222222"}`, clientId))
}

// A pointer to a byte count.
func byteLimit(byteCount int64) *int64 {
	return &byteCount
}

// A reading that the status rules use.
func selfTestReading(capped bool, cappedReason string, monthlyByteLimit *int64, monthlyUsedByteCount int64, totalByteLimit *int64, totalUsedByteCount int64, monthlyPeriodEnd string) *capReading {
	return &capReading{
		monthlyByteLimit:     monthlyByteLimit,
		monthlyUsedByteCount: monthlyUsedByteCount,
		monthlyPeriodEnd:     monthlyPeriodEnd,
		totalByteLimit:       totalByteLimit,
		totalUsedByteCount:   totalUsedByteCount,
		capped:               capped,
		cappedReason:         cappedReason,
	}
}

// A cap state with one successful reading.
func readCaps(reading *capReading) capState {
	caps := capState{}
	caps.Record(reading, nil)
	return caps
}

// Decimal units, one decimal, ties to even on the exact value, moving up at
// 1000.0.
func checkFormatByteCount() error {
	cases := []struct {
		byteCount int64
		text      string
	}{
		{byteCount: 0, text: "0 B"},
		{byteCount: 999, text: "999 B"},
		{byteCount: 1000, text: "1.0 kB"},
		{byteCount: 999949, text: "999.9 kB"},
		{byteCount: 1050, text: "1.0 kB"},
		{byteCount: 1150, text: "1.2 kB"},
		{byteCount: 1250, text: "1.2 kB"},
		{byteCount: 1750, text: "1.8 kB"},
		{byteCount: 999950, text: "1.0 MB"},
		{byteCount: 999999, text: "1.0 MB"},
		{byteCount: 1234567890, text: "1.2 GB"},
		{byteCount: 5000000000, text: "5.0 GB"},
		{byteCount: 10000000000, text: "10.0 GB"},
		{byteCount: 3000000000000, text: "3.0 TB"},
		{byteCount: 9223372036854775807, text: "9.2 EB"},
	}
	for _, c := range cases {
		if text := formatByteCount(c.byteCount); text != c.text {
			return fmt.Errorf("byte count %d formats as %q, want %q", c.byteCount, text, c.text)
		}
	}
	return nil
}

// The monthly reset is in UTC, with the seconds rounded up to the minute.
func checkResetText() error {
	cases := []struct {
		monthlyPeriodEnd string
		text             string
	}{
		{monthlyPeriodEnd: "2026-11-01T00:00:00Z", text: "resets 2026-11-01 00:00 UTC"},
		{monthlyPeriodEnd: "2026-10-31T23:59:00.001Z", text: "resets 2026-11-01 00:00 UTC"},
		{monthlyPeriodEnd: "2026-10-31T19:00:00-05:00", text: "resets 2026-11-01 00:00 UTC"},
	}
	for _, c := range cases {
		if text, ok := resetText(c.monthlyPeriodEnd); !ok || text != c.text {
			return fmt.Errorf("reset text %q for %q, want %q", text, c.monthlyPeriodEnd, c.text)
		}
	}
	for _, invalid := range []string{"", "2026-11-01", "next month"} {
		if _, ok := resetText(invalid); ok {
			return fmt.Errorf("monthly_period_end %q parsed", invalid)
		}
	}
	return nil
}

// The client limit names the SDK's retry time in UTC, rounded up.
func checkClientLimitText() error {
	cases := []struct {
		retryTime int64
		text      string
	}{
		// 2026-10-06 19:05:00.000 UTC
		{retryTime: 1791313500000, text: "client limit, retry at 19:05 UTC"},
		// 19:04:00.001 rounds up
		{retryTime: 1791313440001, text: "client limit, retry at 19:05 UTC"},
		{retryTime: 0, text: "client limit"},
	}
	for _, c := range cases {
		if text := clientLimitText(c.retryTime); text != c.text {
			return fmt.Errorf("client limit text %q for %d, want %q", text, c.retryTime, c.text)
		}
	}
	return nil
}

// The status line matches the contract's golden lines.
func checkStatusLines() error {
	failed := capState{}
	failed.Record(nil, errors.New("http 404"))
	cases := []struct {
		status embedStatus
		line   string
	}{
		{
			status: embedStatus{started: true},
			line:   "status: connecting | data this month: checking | data total: checking",
		},
		{
			status: embedStatus{started: true, providersAdded: 3, caps: readCaps(selfTestReading(false, "", byteLimit(5000000000), 1234567890, nil, 1234567890, "2026-11-01T00:00:00Z"))},
			line:   "status: connected | data this month: 1.2 GB of 5.0 GB | data total: no cap",
		},
		{
			status: embedStatus{started: true, providersAdded: 3, caps: failed},
			line:   "status: connected | data this month: unavailable | data total: unavailable",
		},
		{
			status: embedStatus{started: true, providersAdded: 3, caps: readCaps(selfTestReading(true, cappedReasonMonthly, byteLimit(5000000000), 5000000000, nil, 0, "2026-11-01T00:00:00Z"))},
			line:   "status: data cap reached, resets 2026-11-01 00:00 UTC | data this month: 5.0 GB of 5.0 GB | data total: no cap",
		},
		{
			status: embedStatus{started: true, caps: readCaps(selfTestReading(true, cappedReasonTotal, nil, 0, byteLimit(10000000000), 10000000000, "2026-11-01T00:00:00Z"))},
			line:   "status: data cap reached | data this month: no cap | data total: 10.0 GB of 10.0 GB",
		},
		{
			status: embedStatus{started: true, providersAdded: 3, caps: readCaps(selfTestReading(true, cappedReasonMonthly, byteLimit(0), 0, nil, 0, "2026-11-01T00:00:00Z"))},
			line:   "status: paused | data this month: 0 B of 0 B | data total: no cap",
		},
		{
			status: embedStatus{started: true, clientLimitStatus: sdk.ClientLimitStatusExceeded, clientLimitRetryTime: 1791313500000, caps: readCaps(selfTestReading(false, "", byteLimit(5000000000), 0, nil, 0, "2026-11-01T00:00:00Z"))},
			line:   "status: client limit, retry at 19:05 UTC | data this month: 0 B of 5.0 GB | data total: no cap",
		},
	}
	for _, c := range cases {
		if line := c.status.String(); line != c.line {
			return fmt.Errorf("status line %q, want %q", line, c.line)
		}
	}
	return nil
}

// The status rules apply in the contract's order.
func checkStatusRules() error {
	checking := capState{}
	cases := []struct {
		status embedStatus
		text   string
	}{
		{status: embedStatus{started: false}, text: "stopped"},
		{status: embedStatus{started: false, signedOut: true}, text: "signed out"},
		{
			status: embedStatus{started: true, clientLimitStatus: sdk.ClientLimitStatusExceeded, clientLimitRetryTime: 1791313500000, providersAdded: 3, caps: readCaps(selfTestReading(true, cappedReasonMonthly, byteLimit(0), 0, nil, 0, "2026-11-01T00:00:00Z"))},
			text:   "client limit, retry at 19:05 UTC",
		},
		{
			status: embedStatus{started: true, clientLimitStatus: sdk.ClientLimitStatusNone, providersAdded: 3, caps: readCaps(selfTestReading(true, cappedReasonMonthly, byteLimit(0), 0, nil, 0, "2026-11-01T00:00:00Z"))},
			text:   "paused",
		},
		{
			status: embedStatus{started: true, providersAdded: 3, caps: readCaps(selfTestReading(true, cappedReasonTotal, byteLimit(5000000000), 0, byteLimit(0), 0, "2026-11-01T00:00:00Z"))},
			text:   "paused",
		},
		{
			status: embedStatus{started: true, providersAdded: 3, caps: readCaps(selfTestReading(true, cappedReasonMonthly, byteLimit(5000000000), 5000000000, nil, 0, "2026-11-01T00:00:00Z"))},
			text:   "data cap reached, resets 2026-11-01 00:00 UTC",
		},
		{
			status: embedStatus{started: true, caps: readCaps(selfTestReading(true, cappedReasonTotal, nil, 0, byteLimit(10000000000), 10000000000, ""))},
			text:   "data cap reached",
		},
		{
			status: embedStatus{started: true, providersAdded: 1, caps: readCaps(selfTestReading(false, "", nil, 0, nil, 0, ""))},
			text:   "connected",
		},
		{status: embedStatus{started: true, caps: checking}, text: "connecting"},
		// the cap that capped_reason names decides paused: a monthly cap of 0
		// does not pause a total cap
		{
			status: embedStatus{started: true, caps: readCaps(selfTestReading(true, cappedReasonTotal, byteLimit(0), 0, byteLimit(10000000000), 10000000000, ""))},
			text:   "data cap reached",
		},
		// an unknown capped_reason is capped without a reset time
		{
			status: embedStatus{started: true, providersAdded: 3, caps: readCaps(selfTestReading(true, "weekly", byteLimit(0), 0, nil, 0, "2026-11-01T00:00:00Z"))},
			text:   "data cap reached",
		},
		// a monthly_period_end that does not parse shows no reset time
		{
			status: embedStatus{started: true, caps: readCaps(selfTestReading(true, cappedReasonMonthly, byteLimit(5000000000), 5000000000, nil, 0, "soon"))},
			text:   "data cap reached",
		},
	}
	for _, c := range cases {
		if text := c.status.StatusText(); text != c.text {
			return fmt.Errorf("status %q for %+v, want %q", text, c.status, c.text)
		}
	}
	return nil
}

// checking, unavailable, a later failure keeping the last value, no cap for
// a null limit even with a used count, and used of limit.
func checkDataFields() error {
	status := embedStatus{started: true}
	if status.MonthlyText() != "checking" || status.TotalText() != "checking" {
		return errors.New("the data fields are not checking before the first reading")
	}
	status.caps.Record(nil, errors.New("http 404"))
	if status.MonthlyText() != "unavailable" || status.TotalText() != "unavailable" {
		return errors.New("a failed first reading is not unavailable")
	}
	status.caps.Record(selfTestReading(false, "", byteLimit(5000000000), 1234567890, nil, 7000000000, ""), nil)
	if status.MonthlyText() != "1.2 GB of 5.0 GB" || status.TotalText() != "no cap" {
		return fmt.Errorf("data fields %q and %q", status.MonthlyText(), status.TotalText())
	}
	status.caps.Record(nil, errors.New("http 502"))
	if status.MonthlyText() != "1.2 GB of 5.0 GB" {
		return errors.New("a later failure did not keep the last value")
	}
	return nil
}

// Cap objects: null or absent limits, capped and capped_reason, errors.
func checkCapObjects() error {
	reading, err := parseCapObject([]byte(`{"client_id":"11111111-1111-1111-1111-111111111111","monthly_byte_limit":5000000000,"monthly_used_byte_count":1234567890,"monthly_period_start":"2026-10-01T00:00:00Z","monthly_period_end":"2026-11-01T00:00:00Z","total_byte_limit":null,"total_used_byte_count":1234567890,"total_period_start":"2026-09-15T00:00:00Z","capped":false,"capped_reason":""}`), selfTestClientId)
	if err != nil || reading.monthlyByteLimit == nil || *reading.monthlyByteLimit != 5000000000 || reading.monthlyUsedByteCount != 1234567890 || reading.totalByteLimit != nil || reading.capped || reading.monthlyPeriodEnd != "2026-11-01T00:00:00Z" {
		return fmt.Errorf("cap object parse %+v (%v)", reading, err)
	}
	reading, err = parseCapObject([]byte(`{"client_id":"11111111-1111-1111-1111-111111111111"}`), selfTestClientId)
	if err != nil || reading.monthlyByteLimit != nil || reading.totalByteLimit != nil {
		return fmt.Errorf("absent limits parse %+v (%v)", reading, err)
	}
	reading, err = parseCapObject([]byte(`{"client_id":"11111111-1111-1111-1111-111111111111","total_byte_limit":10,"total_used_byte_count":10,"capped":true,"capped_reason":"total"}`), selfTestClientId)
	if err != nil || !reading.capped || reading.cappedReason != cappedReasonTotal {
		return fmt.Errorf("capped object parse %+v (%v)", reading, err)
	}
	for _, invalid := range []string{
		`{"error":{"message":"Not allowed."}}`,
		`{"client_id":"22222222-2222-2222-2222-222222222222"}`,
		`{"client_id":"11111111-1111-1111-1111-111111111111","monthly_byte_limit":"5"}`,
		`not json`,
	} {
		if _, err := parseCapObject([]byte(invalid), selfTestClientId); err == nil {
			return fmt.Errorf("invalid cap object %s accepted", invalid)
		}
	}
	return nil
}

// Only a JWT with a valid client_id claim is a client credential.
func checkClientJwtClaims() error {
	clientId, err := parseClientJwtClientId(selfTestClientJwt(selfTestClientId))
	if err != nil || clientId != selfTestClientId {
		return fmt.Errorf("client jwt claim %q (%v)", clientId, err)
	}
	for _, invalidJwt := range []string{
		"",
		"not-a-jwt",
		selfTestJwt(`{"network_id":"22222222-2222-2222-2222-222222222222"}`),
		selfTestJwt(`{"client_id":"not-a-uuid"}`),
		"e30.%%%.test",
	} {
		if _, err := parseClientJwtClientId(invalidJwt); err == nil {
			return fmt.Errorf("invalid client jwt %q accepted", invalidJwt)
		}
	}
	return nil
}

// Serves requests in process with a handler: a stand-in server with no
// listener.
type handlerTransport struct {
	handler http.Handler
}

// Answers the request with the handler.
func (self *handlerTransport) RoundTrip(request *http.Request) (*http.Response, error) {
	recorder := httptest.NewRecorder()
	self.handler.ServeHTTP(recorder, request)
	return recorder.Result(), nil
}

// A server that cannot be reached.
type unreachableTransport struct{}

// Fails the request.
func (self *unreachableTransport) RoundTrip(*http.Request) (*http.Response, error) {
	return nil, errors.New("connection refused")
}

// The token fetch: the request, saving client.jwt atomically, refusing a
// client_id that does not match the claim, and the answers mapped to exit
// codes.
func checkTokenFetch() error {
	stateDir, err := os.MkdirTemp("", "ur-embed-self-test-")
	if err != nil {
		return err
	}
	defer os.RemoveAll(stateDir)
	if err := os.Chmod(stateDir, 0o700); err != nil {
		return err
	}
	tokenServer := &tokenServerConfig{origin: "https://tokens.example.test", session: selfTestSession}
	type standIn struct {
		status int
		body   string
	}
	var answer standIn
	var seen struct {
		method        string
		path          string
		authorization string
		installation  string
	}
	client := &http.Client{Transport: &handlerTransport{handler: http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		seen.method = r.Method
		seen.path = r.URL.Path
		seen.authorization = r.Header.Get("Authorization")
		var body struct {
			InstallationId string `json:"installation_id"`
		}
		data, _ := io.ReadAll(r.Body)
		json.Unmarshal(data, &body)
		seen.installation = body.InstallationId
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(answer.status)
		io.WriteString(w, answer.body)
	})}}
	capObject := `{"client_id":"11111111-1111-1111-1111-111111111111","monthly_byte_limit":5000000000,"monthly_used_byte_count":0,"total_byte_limit":null,"total_used_byte_count":0,"capped":false,"capped_reason":""}`

	answer = standIn{status: http.StatusOK, body: fmt.Sprintf(`{"client_id":%q,"by_client_jwt":%q,"data_cap":%s}`, selfTestClientId, selfTestClientJwt(selfTestClientId), capObject)}
	credential, err := fetchClientJwt(client, tokenServer, stateDir, selfTestInstanceId)
	if err != nil {
		return err
	}
	if seen.method != http.MethodPost || seen.path != tokenRoute || seen.authorization != "Bearer "+selfTestSession || seen.installation != selfTestInstanceId {
		return fmt.Errorf("token request %+v", seen)
	}
	if credential.clientId != selfTestClientId || credential.clientJwt != selfTestClientJwt(selfTestClientId) || credential.firstCapReading == nil || *credential.firstCapReading.monthlyByteLimit != 5000000000 {
		return fmt.Errorf("token answer %+v", credential)
	}
	saved, err := readPrivateFile(filepath.Join(stateDir, clientJwtFileName))
	if err != nil || string(saved) != selfTestClientJwt(selfTestClientId)+"\n" {
		return fmt.Errorf("client.jwt %q (%v)", saved, err)
	}

	// a client_id that does not match the claim is refused, and client.jwt stays
	answer = standIn{status: http.StatusOK, body: fmt.Sprintf(`{"client_id":"22222222-2222-2222-2222-222222222222","by_client_jwt":%q,"data_cap":null}`, selfTestClientJwt(selfTestClientId))}
	if _, err := fetchClientJwt(client, tokenServer, stateDir, selfTestInstanceId); err == nil || startupExitCode(err) != exitFailure {
		return fmt.Errorf("a mismatched client_id was accepted (%v)", err)
	}
	if saved, _ := readPrivateFile(filepath.Join(stateDir, clientJwtFileName)); string(saved) != selfTestClientJwt(selfTestClientId)+"\n" {
		return errors.New("a refused answer replaced client.jwt")
	}

	// no data_cap: no first reading
	answer = standIn{status: http.StatusOK, body: fmt.Sprintf(`{"client_id":%q,"by_client_jwt":%q,"data_cap":null}`, selfTestClientId, selfTestClientJwt(selfTestClientId))}
	if credential, err := fetchClientJwt(client, tokenServer, stateDir, selfTestInstanceId); err != nil || credential.firstCapReading != nil {
		return fmt.Errorf("a null data_cap gave a reading (%v)", err)
	}

	cases := []struct {
		answer   standIn
		exitCode int
	}{
		{answer: standIn{status: http.StatusUnauthorized, body: `{"error":{"code":"unauthorized","message":"missing or unknown session"}}`}, exitCode: exitConfig},
		{answer: standIn{status: http.StatusConflict, body: `{"error":{"code":"installation_limit","message":"this user already has 5 installations"}}`}, exitCode: exitConfig},
		{answer: standIn{status: http.StatusConflict, body: `{"error":{"code":"client_limit","message":"your network is at its client limit; see https://ur.io/services"}}`}, exitCode: exitConfig},
		{answer: standIn{status: http.StatusBadGateway, body: `{"error":{"code":"upstream","message":"the URnetwork API failed"}}`}, exitCode: exitFailure},
		{answer: standIn{status: http.StatusServiceUnavailable, body: `{"error":{"code":"busy","message":"retry"}}`}, exitCode: exitFailure},
		{answer: standIn{status: http.StatusOK, body: `not json`}, exitCode: exitFailure},
	}
	for _, c := range cases {
		answer = c.answer
		_, err := fetchClientJwt(client, tokenServer, stateDir, selfTestInstanceId)
		if err == nil || startupExitCode(err) != c.exitCode {
			return fmt.Errorf("token server answer %d %s exits %d (%v), want %d", c.answer.status, c.answer.body, startupExitCode(err), err, c.exitCode)
		}
	}
	unreachable := &http.Client{Transport: &unreachableTransport{}}
	if _, err := fetchClientJwt(unreachable, tokenServer, stateDir, selfTestInstanceId); err == nil || startupExitCode(err) != exitFailure {
		return fmt.Errorf("an unreachable token server exits %d (%v)", startupExitCode(err), err)
	}

	// the token server url and the demo session
	for _, settings := range []map[string]string{
		{"URNETWORK_TOKEN_SERVER_URL": "https://tokens.example.test", "URNETWORK_DEMO_SESSION": selfTestSession},
		{"URNETWORK_TOKEN_SERVER_URL": "http://127.0.0.1:8790", "URNETWORK_DEMO_SESSION": selfTestSession},
		{"URNETWORK_TOKEN_SERVER_URL": "http://localhost:8790/", "URNETWORK_DEMO_SESSION": selfTestSession},
		{"URNETWORK_TOKEN_SERVER_URL": "http://[::1]:8790", "URNETWORK_DEMO_SESSION": selfTestSession},
	} {
		if config, err := loadTokenServerConfig(func(name string) string { return settings[name] }); err != nil || config == nil {
			return fmt.Errorf("token server settings %v refused (%v)", settings, err)
		}
	}
	for _, settings := range []map[string]string{
		{"URNETWORK_TOKEN_SERVER_URL": "http://tokens.example.test", "URNETWORK_DEMO_SESSION": selfTestSession},
		{"URNETWORK_TOKEN_SERVER_URL": "https://tokens.example.test/token", "URNETWORK_DEMO_SESSION": selfTestSession},
		{"URNETWORK_TOKEN_SERVER_URL": "https://tokens.example.test"},
		{"URNETWORK_DEMO_SESSION": selfTestSession},
		{"URNETWORK_TOKEN_SERVER_URL": "https://tokens.example.test", "URNETWORK_DEMO_SESSION": "a b"},
	} {
		_, err := loadTokenServerConfig(func(name string) string { return settings[name] })
		var configErr *configError
		if !errors.As(err, &configErr) {
			return fmt.Errorf("token server settings %v accepted (%v)", settings, err)
		}
	}
	return nil
}

// State files are private and replaced atomically; instance-id is created
// once; a symlinked file is refused.
func checkStateFiles() error {
	stateDir, err := os.MkdirTemp("", "ur-embed-self-test-")
	if err != nil {
		return err
	}
	defer os.RemoveAll(stateDir)
	if err := os.Chmod(stateDir, 0o700); err != nil {
		return err
	}
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
	entries, err := os.ReadDir(stateDir)
	if err != nil || len(entries) != 1 {
		return fmt.Errorf("the atomic write left temporary files (%d entries, %v)", len(entries), err)
	}
	if runtime.GOOS != "windows" {
		info, err := os.Stat(path)
		if err != nil || info.Mode().Perm() != 0o600 {
			return fmt.Errorf("private file mode %v (%v)", info.Mode().Perm(), err)
		}
		if err := os.Chmod(path, 0o644); err != nil {
			return err
		}
		if _, err := readPrivateFile(path); err == nil {
			return errors.New("a group-readable credential file was accepted")
		}
		target := filepath.Join(stateDir, "target.jwt")
		if err := writePrivateFile(target, []byte("target\n")); err != nil {
			return err
		}
		link := filepath.Join(stateDir, "link.jwt")
		if err := os.Symlink(target, link); err != nil {
			return err
		}
		if _, err := readPrivateFile(link); err == nil {
			return errors.New("a symlinked state file was accepted")
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
	return nil
}

// A missing or relative state directory, no token server and no client.jwt,
// a network JWT and unknown arguments are configuration errors (exit 78).
func checkConfigErrors() error {
	for _, stateDir := range []string{"", "relative/state"} {
		_, err := loadEmbedConfig(func(name string) string {
			if name == "URNETWORK_EMBED_STATE_DIR" {
				return stateDir
			}
			return ""
		})
		var configErr *configError
		if !errors.As(err, &configErr) {
			return fmt.Errorf("state directory %q accepted (%v)", stateDir, err)
		}
	}
	stateDir, err := os.MkdirTemp("", "ur-embed-self-test-")
	if err != nil {
		return err
	}
	defer os.RemoveAll(stateDir)
	if err := os.Chmod(stateDir, 0o700); err != nil {
		return err
	}
	config, err := loadEmbedConfig(func(name string) string {
		if name == "URNETWORK_EMBED_STATE_DIR" {
			return stateDir
		}
		return ""
	})
	if err != nil || config.tokenServer != nil || config.apiOrigin != defaultApiUrl {
		return fmt.Errorf("a state directory without a token server did not load (%v)", err)
	}
	// no token server and no client.jwt
	if _, err := obtainClientJwt(config, newHttpClient()); err == nil || startupExitCode(err) != exitConfig {
		return fmt.Errorf("no client.jwt exits %d (%v)", startupExitCode(err), err)
	}
	networkJwt := selfTestJwt(`{"network_id":"22222222-2222-2222-2222-222222222222"}`)
	if err := saveClientJwt(stateDir, networkJwt); err != nil {
		return err
	}
	if _, err := obtainClientJwt(config, newHttpClient()); err == nil || startupExitCode(err) != exitConfig {
		return fmt.Errorf("a network jwt exits %d (%v)", startupExitCode(err), err)
	}
	if err := saveClientJwt(stateDir, selfTestClientJwt(selfTestClientId)); err != nil {
		return err
	}
	credential, err := obtainClientJwt(config, newHttpClient())
	if err != nil || credential.clientId != selfTestClientId {
		return fmt.Errorf("a client.jwt from a backend tool did not load (%v)", err)
	}
	for _, args := range [][]string{{"--unknown"}, {"run", "now"}, {"--self-test", "--version"}} {
		if _, ok := commandOf(args); ok {
			return fmt.Errorf("arguments %q are not a usage error", args)
		}
	}
	for _, args := range [][]string{{}, {"run"}, {"--self-test"}, {"--licenses"}, {"--version"}} {
		if _, ok := commandOf(args); !ok {
			return fmt.Errorf("arguments %q are a usage error", args)
		}
	}
	return nil
}

// --licenses names the GetLicenses app kind of the host OS.
func checkLicenseApp() error {
	for goos, app := range map[string]string{"darwin": "apple", "windows": "windows", "linux": "linux", "freebsd": "linux"} {
		if got := licenseApp(goos); got != app {
			return fmt.Errorf("the license app kind of %s is %q, want %q", goos, got, app)
		}
	}
	return nil
}

// The start line names the client and the installation.
func checkStartLine() error {
	if line := startLine("11111111-1111-1111-1111-111111111111", "22222222-2222-2222-2222-222222222222"); line != "embed client 11111111-1111-1111-1111-111111111111, installation 22222222-2222-2222-2222-222222222222" {
		return fmt.Errorf("start line %q", line)
	}
	return nil
}
