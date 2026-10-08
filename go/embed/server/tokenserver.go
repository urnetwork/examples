// The HTTP token server (EMBED_CONTRACT.md, "The token server"): the shape of
// a real service's sign-in endpoint. An embed app posts its installation id
// with its demo session, and gets its installation's scoped client JWT:
//
//	POST /urnetwork/client-token
//	Authorization: Bearer <demo session token>
//	{"installation_id": "<the installation's instance-id>"}
//
// Each installation is keyed user:<service-user-id>:<installation-id>, so a
// user's installations that run at the same time each get their own client.
// Serve the internet through your own HTTPS front end, never this listener
// directly. There is no browser CORS support: the callers are native apps.
package main

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"strconv"
	"strings"
	"syscall"
	"time"
)

const tokenRoute = "/urnetwork/client-token"

// the largest request body
const tokenRequestByteLimit = 4 * 1024

// what the token server's provisions send
const tokenDescription = "embed installation"
const tokenDeviceSpec = "urnetwork-examples/embed-token-server"

const defaultTokenServerAddress = "127.0.0.1:8790"
const defaultMaxInstallationsPerUser = 5

// The user already has the maximum number of installations.
type installationLimitError struct {
	maxInstallations int
}

// The limit.
func (self *installationLimitError) Error() string {
	return fmt.Sprintf("this user already has the most installations allowed (%d)", self.maxInstallations)
}

// The token server's settings and state.
type tokenServer struct {
	sessions         *demoSessions
	store            *mapStore
	issuer           *issuer
	api              *apiClient
	maxInstallations int
	// one line per request: the route, the status and the latency, never
	// keys, ids or tokens
	logf func(format string, args ...any)
}

// The answer of a successful request. DataCap is the client's cap object, or
// null when it could not be read.
type clientTokenAnswer struct {
	ClientId    string          `json:"client_id"`
	ByClientJwt string          `json:"by_client_jwt"`
	DataCap     json.RawMessage `json:"data_cap"`
}

// Validates the token server's settings and returns the server and its bind
// address.
func newTokenServer(env *environment) (*tokenServer, string, error) {
	shared, err := loadSettings(env.getenv)
	if err != nil {
		return nil, "", err
	}
	sessionsPath := env.getenv("URNETWORK_DEMO_SESSIONS")
	if sessionsPath == "" || !filepath.IsAbs(sessionsPath) {
		return nil, "", configErrorf("set URNETWORK_DEMO_SESSIONS to the absolute filename of the private demo session file")
	}
	if err := checkPrivateDir(filepath.Dir(sessionsPath), "URNETWORK_DEMO_SESSIONS's directory"); err != nil {
		return nil, "", err
	}
	sessions, err := loadDemoSessions(sessionsPath)
	if err != nil {
		return nil, "", err
	}
	maxInstallations := defaultMaxInstallationsPerUser
	if text := env.getenv("URNETWORK_MAX_INSTALLATIONS_PER_USER"); text != "" {
		maxInstallations, err = strconv.Atoi(text)
		if err != nil || maxInstallations < 1 || 1000000 < maxInstallations {
			return nil, "", configErrorf("URNETWORK_MAX_INSTALLATIONS_PER_USER must be an integer from 1 to 1000000")
		}
	}
	address := env.getenv("URNETWORK_TOKEN_SERVER_ADDRESS")
	if address == "" {
		address = defaultTokenServerAddress
	}
	api := &apiClient{transport: env.transport(shared.apiOrigin, shared.rootCredential)}
	store := newMapStore(shared.mapPath)
	logger := log.New(env.stderr, "", log.LstdFlags)
	return &tokenServer{
		sessions: sessions,
		store:    store,
		issuer: &issuer{
			api:         api,
			store:       store,
			defaults:    shared.defaults,
			description: tokenDescription,
			deviceSpec:  tokenDeviceSpec,
		},
		api:              api,
		maxInstallations: maxInstallations,
		logf:             logger.Printf,
	}, address, nil
}

// Runs the token server until SIGINT or SIGTERM.
func serve(env *environment) error {
	server, address, err := newTokenServer(env)
	if err != nil {
		return err
	}
	listener, err := net.Listen("tcp", address)
	if err != nil {
		return fmt.Errorf("could not listen on %s: %v", address, err)
	}
	httpServer := &http.Server{
		Handler:           server,
		ReadHeaderTimeout: 10 * time.Second,
		ReadTimeout:       30 * time.Second,
		// a request waits for the map lock and up to three api calls
		WriteTimeout:   3 * time.Minute,
		IdleTimeout:    60 * time.Second,
		MaxHeaderBytes: 16 * 1024,
	}
	fmt.Fprintf(env.stdout, "token server listening on http://%s%s\n", listener.Addr(), tokenRoute)
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	served := make(chan error, 1)
	go func() {
		served <- httpServer.Serve(listener)
	}()
	select {
	case <-ctx.Done():
		shutdownCtx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()
		httpServer.Shutdown(shutdownCtx)
		return nil
	case err := <-served:
		return err
	}
}

// Answers one request and logs its route, status and latency.
func (self *tokenServer) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	startTime := time.Now()
	status, code := self.answer(w, r)
	route := r.Method + " (other path)"
	if r.URL.Path == tokenRoute {
		route = r.Method + " " + tokenRoute
	}
	result := fmt.Sprintf("%d", status)
	if code != "" {
		result += " " + code
	}
	self.logf("%s %s %s", route, result, time.Since(startTime).Round(time.Millisecond))
}

// Answers one request; returns the status and the error code, empty on
// success. Every answer carries Cache-Control: no-store.
func (self *tokenServer) answer(w http.ResponseWriter, r *http.Request) (int, string) {
	w.Header().Set("Cache-Control", "no-store")
	if r.URL.Path != tokenRoute {
		return writeError(w, http.StatusNotFound, "not_found", "unknown path")
	}
	if r.Method != http.MethodPost {
		w.Header().Set("Allow", http.MethodPost)
		return writeError(w, http.StatusMethodNotAllowed, "method_not_allowed", "use POST")
	}
	userId, ok := self.authenticate(r)
	if !ok {
		return writeError(w, http.StatusUnauthorized, "unauthorized", "missing or unknown session")
	}
	installationId, ok := readInstallationId(w, r)
	if !ok {
		return writeError(w, http.StatusBadRequest, "invalid_request", `send {"installation_id": "<lowercase uuid>"} as a JSON body of at most 4 KiB`)
	}
	answer, err := self.clientToken("user:"+userId+":"+installationId, "user:"+userId+":")
	if err != nil {
		return writeIssueError(w, err)
	}
	return writeJson(w, http.StatusOK, answer), ""
}

// The service user of the request's demo session.
func (self *tokenServer) authenticate(r *http.Request) (string, bool) {
	scheme, token, found := strings.Cut(r.Header.Get("Authorization"), " ")
	if !found || !strings.EqualFold(scheme, "Bearer") || token == "" {
		return "", false
	}
	return self.sessions.UserId(token)
}

// The request's installation_id: a lowercase uuid in a JSON body of at most
// 4 KiB.
func readInstallationId(w http.ResponseWriter, r *http.Request) (string, bool) {
	data, err := io.ReadAll(http.MaxBytesReader(w, r.Body, tokenRequestByteLimit))
	if err != nil {
		return "", false
	}
	decoder := json.NewDecoder(bytes.NewReader(data))
	var body struct {
		InstallationId *string `json:"installation_id"`
	}
	if err := decoder.Decode(&body); err != nil || body.InstallationId == nil {
		return "", false
	}
	// one JSON value, nothing after it
	if _, err := decoder.Token(); !errors.Is(err, io.EOF) {
		return "", false
	}
	if !uuidPattern.MatchString(*body.InstallationId) {
		return "", false
	}
	return *body.InstallationId, true
}

// Issues key's client under the map lock, then reads its cap object with the
// root credential. The cap read never blocks the token: a failure answers
// data_cap as null.
func (self *tokenServer) clientToken(key string, userPrefix string) (*clientTokenAnswer, error) {
	unlock, err := self.store.Lock()
	if err != nil {
		return nil, err
	}
	defer unlock()
	clients, err := self.store.Load()
	if err != nil {
		return nil, err
	}
	issued, err := self.issuer.Issue(clients, key, func(clients *clientMap) error {
		if self.maxInstallations <= clients.CountPrefix(userPrefix) {
			return &installationLimitError{maxInstallations: self.maxInstallations}
		}
		return nil
	})
	if err != nil {
		return nil, err
	}
	dataCap, err := self.api.GetDataCap(issued.clientId)
	if err != nil {
		dataCap = nil
	}
	return &clientTokenAnswer{
		ClientId:    issued.clientId,
		ByClientJwt: issued.clientJwt,
		DataCap:     dataCap,
	}, nil
}

// Answers an issue failure with its status and code.
func writeIssueError(w http.ResponseWriter, err error) (int, string) {
	var busy *busyError
	var installationLimit *installationLimitError
	var upstream *upstreamError
	var refusal *apiRefusal
	var caps *capsError
	switch {
	case errors.As(err, &busy):
		return writeError(w, http.StatusServiceUnavailable, "busy", "another process holds the client map; retry")
	case errors.As(err, &installationLimit):
		return writeError(w, http.StatusConflict, "installation_limit", installationLimit.Error())
	case isClientLimit(err):
		return writeError(w, http.StatusConflict, "client_limit", "your network is at its client limit; see https://ur.io/services")
	case errors.As(err, &upstream) || errors.As(err, &refusal) || errors.As(err, &caps):
		return writeError(w, http.StatusBadGateway, "upstream", "the URnetwork API failed; retry later")
	default:
		return writeError(w, http.StatusInternalServerError, "internal", "the token server failed; check its client map with token-server usage-all")
	}
}

// Answers {"error": {"code": ..., "message": ...}}.
func writeError(w http.ResponseWriter, status int, code string, message string) (int, string) {
	writeJson(w, status, map[string]any{
		"error": map[string]string{
			"code":    code,
			"message": message,
		},
	})
	return status, code
}

// Answers value as JSON with status.
func writeJson(w http.ResponseWriter, status int, value any) int {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	json.NewEncoder(w).Encode(value)
	return status
}
