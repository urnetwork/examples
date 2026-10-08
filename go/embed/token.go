// Obtaining the installation's client JWT (EMBED_CONTRACT.md, "Obtaining the
// client JWT"). With a token server configured (URNETWORK_TOKEN_SERVER_URL
// and URNETWORK_DEMO_SESSION), fetchClientJwt posts the installation's
// instance-id with the demo session and saves the answered JWT as client.jwt.
// It fetches on every start, so a start reissues the client. Otherwise the
// app uses the client.jwt already in its state. fetchClientJwt is the one
// place to replace with your own sign-in.
package main

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"
)

const tokenRoute = "/urnetwork/client-token"

// the largest answer the app reads from the token server or the api
const responseByteLimit = 64 * 1024

const httpTimeout = 30 * time.Second

// The token server the app signs in with.
type tokenServerConfig struct {
	// an https origin, or explicit loopback http for local testing
	origin string
	// the demo session token; your app uses its own sign-in instead
	session string
}

// The client credential the app starts with.
type clientCredential struct {
	clientJwt string
	// the client_id claim of clientJwt
	clientId string
	// the token server's data_cap, the first cap reading; nil without one
	firstCapReading *capReading
}

// A token server refusal that a restart does not fix: 401 unauthorized, or
// 409 installation_limit or client_limit. The app exits 78; a GUI app shows
// "signed out" with the message.
type tokenServerRefusal struct {
	status  int
	code    string
	message string
}

// The refusal on one line.
func (self *tokenServerRefusal) Error() string {
	return fmt.Sprintf("the token server refused this installation (http %d %s): %s", self.status, self.code, oneLine(self.message))
}

// Reads URNETWORK_TOKEN_SERVER_URL and URNETWORK_DEMO_SESSION: both, or
// neither to use client.jwt.
func loadTokenServerConfig(getenv func(string) string) (*tokenServerConfig, error) {
	tokenServerUrl := getenv("URNETWORK_TOKEN_SERVER_URL")
	session := getenv("URNETWORK_DEMO_SESSION")
	if tokenServerUrl == "" && session == "" {
		return nil, nil
	}
	if tokenServerUrl == "" || session == "" {
		return nil, configErrorf("set both URNETWORK_TOKEN_SERVER_URL and URNETWORK_DEMO_SESSION, or neither to use the client.jwt in the state directory")
	}
	origin, err := httpOrigin(tokenServerUrl)
	if err != nil {
		return nil, configErrorf("URNETWORK_TOKEN_SERVER_URL must be an https origin, or explicit loopback http (localhost, 127.0.0.1, [::1]) for local testing")
	}
	if strings.ContainsAny(session, " \t\r\n") {
		return nil, configErrorf("URNETWORK_DEMO_SESSION must not contain whitespace")
	}
	return &tokenServerConfig{origin: origin, session: session}, nil
}

// An https origin without credentials, path, query or fragment. Plain http is
// accepted only for an explicit loopback host.
func httpOrigin(rawUrl string) (string, error) {
	parsed, err := url.Parse(rawUrl)
	if err != nil {
		return "", err
	}
	hostName := parsed.Hostname()
	loopback := hostName == "localhost" || hostName == "127.0.0.1" || hostName == "::1"
	if hostName == "" || parsed.User != nil || (parsed.Path != "" && parsed.Path != "/") || parsed.RawQuery != "" || parsed.Fragment != "" || (parsed.Scheme != "https" && !(parsed.Scheme == "http" && loopback)) {
		return "", errors.New("not an https origin or a loopback http origin")
	}
	return strings.TrimSuffix(rawUrl, "/"), nil
}

// An http client for the token server and the api. Redirects are not
// followed, so a credential only goes to the configured origin.
func newHttpClient() *http.Client {
	return &http.Client{
		Timeout: httpTimeout,
		CheckRedirect: func(*http.Request, []*http.Request) error {
			return http.ErrUseLastResponse
		},
	}
}

// The client credential the app starts with: fetched from the token server
// when one is configured, otherwise the client.jwt in the state directory.
func obtainClientJwt(config *embedConfig, client *http.Client) (*clientCredential, error) {
	if config.tokenServer != nil {
		return fetchClientJwt(client, config.tokenServer, config.stateDir, config.instanceId)
	}
	return loadClientJwt(config.stateDir)
}

// Posts the installation's instance-id to the token server with the demo
// session as the bearer token, checks that the answered JWT's client_id
// claim names the answered client, and saves it as client.jwt. The answer's
// data_cap, when present, is the first cap reading.
func fetchClientJwt(client *http.Client, tokenServer *tokenServerConfig, stateDir string, instanceId string) (*clientCredential, error) {
	body, err := json.Marshal(map[string]string{"installation_id": instanceId})
	if err != nil {
		return nil, err
	}
	request, err := http.NewRequest(http.MethodPost, tokenServer.origin+tokenRoute, bytes.NewReader(body))
	if err != nil {
		return nil, err
	}
	request.Header.Set("Authorization", "Bearer "+tokenServer.session)
	request.Header.Set("Content-Type", "application/json")
	response, err := client.Do(request)
	if err != nil {
		return nil, fmt.Errorf("the token server could not be reached: %v", err)
	}
	defer response.Body.Close()
	answer, err := io.ReadAll(io.LimitReader(response.Body, responseByteLimit+1))
	if err != nil || responseByteLimit < len(answer) {
		return nil, errors.New("the token server's answer could not be read")
	}
	switch response.StatusCode {
	case http.StatusOK:
	case http.StatusUnauthorized, http.StatusConflict:
		var refused struct {
			Error *struct {
				Code    string `json:"code"`
				Message string `json:"message"`
			} `json:"error"`
		}
		refusal := &tokenServerRefusal{status: response.StatusCode}
		if json.Unmarshal(answer, &refused) == nil && refused.Error != nil {
			refusal.code = refused.Error.Code
			refusal.message = refused.Error.Message
		}
		return nil, refusal
	default:
		return nil, fmt.Errorf("the token server answered http %d", response.StatusCode)
	}
	var issued struct {
		ClientId    string          `json:"client_id"`
		ByClientJwt string          `json:"by_client_jwt"`
		DataCap     json.RawMessage `json:"data_cap"`
	}
	if err := json.Unmarshal(answer, &issued); err != nil {
		return nil, errors.New("the token server answered something invalid")
	}
	clientId, err := parseClientJwtClientId(issued.ByClientJwt)
	if err != nil || clientId != issued.ClientId {
		return nil, errors.New("the token server answered a client JWT that does not name its client_id")
	}
	if err := saveClientJwt(stateDir, issued.ByClientJwt); err != nil {
		return nil, fmt.Errorf("could not save %s: %v", clientJwtFileName, err)
	}
	credential := &clientCredential{clientJwt: issued.ByClientJwt, clientId: clientId}
	if len(issued.DataCap) != 0 && string(issued.DataCap) != "null" {
		if reading, err := parseCapObject(issued.DataCap, clientId); err == nil {
			credential.firstCapReading = reading
		}
	}
	return credential, nil
}

// The exit code of a startup failure: 78 for a configuration problem or a
// token server refusal (401, 409), 1 for anything else (unreachable, 5xx, an
// invalid answer).
func startupExitCode(err error) int {
	var configErr *configError
	var refusal *tokenServerRefusal
	if errors.As(err, &configErr) || errors.As(err, &refusal) {
		return exitConfig
	}
	return exitFailure
}

// Collapses text to one line of at most 300 characters.
func oneLine(text string) string {
	text = strings.Join(strings.Fields(text), " ")
	if 300 < len(text) {
		text = text[:300] + "..."
	}
	return text
}
