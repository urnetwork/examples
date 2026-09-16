// SERVER ONLY: go run . user:<authenticated-service-user-id> | --self-test
// The backend supplies the service key after authentication, never from an untrusted
// request field. Set URNETWORK_ROOT_JWT, URNETWORK_CLIENT_MAP (absolute file path in
// an existing service-owned directory), and optionally URNETWORK_API_URL.
// A crash may leave .lock; remove it only after confirming that no allocator owns it.
package main

import (
	"bytes"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"time"
)

const limit = 1 << 20

var users = regexp.MustCompile(`^user:[A-Za-z0-9][A-Za-z0-9_.:@-]{0,122}$`)
var ids = regexp.MustCompile(`^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$`)

type clientMap struct {
	Version int               `json:"version"`
	Clients map[string]string `json:"clients"`
}
type requestBody struct {
	Description string `json:"description"`
	DeviceSpec  string `json:"device_spec"`
	ClientID    string `json:"client_id,omitempty"`
}
type result struct {
	ClientID string `json:"client_id"`
	JWT      string `json:"by_client_jwt"`
}

func serviceUser(args []string) (string, error) {
	if len(args) != 1 || !users.MatchString(args[0]) {
		return "", errors.New("expected one user:<service-user-id>")
	}
	return args[0], nil
}
func endpoint(base string) (string, error) {
	u, e := url.Parse(base)
	if e != nil {
		return "", e
	}
	local := u.Hostname() == "localhost" || u.Hostname() == "127.0.0.1" || u.Hostname() == "::1"
	if u.Hostname() == "" || u.User != nil || (u.Path != "" && u.Path != "/") || u.RawQuery != "" || u.Fragment != "" || (u.Scheme != "https" && !(u.Scheme == "http" && local)) {
		return "", errors.New("API must be an HTTPS origin, or explicit loopback HTTP mock")
	}
	return strings.TrimSuffix(base, "/") + "/network/auth-client", nil
}
func requestFor(user, client string) (requestBody, error) {
	if _, e := serviceUser([]string{user}); e != nil {
		return requestBody{}, e
	}
	if client != "" && !ids.MatchString(client) {
		return requestBody{}, errors.New("invalid mapped client")
	}
	return requestBody{"service " + user, "urnetwork-examples/go-server", client}, nil
}
func parseResponse(raw []byte, expected string) (result, error) {
	var obj struct {
		ClientID string          `json:"client_id"`
		JWT      string          `json:"by_client_jwt"`
		Error    json.RawMessage `json:"error"`
	}
	if e := json.Unmarshal(raw, &obj); e != nil {
		return result{}, e
	}
	if (len(obj.Error) > 0 && string(obj.Error) != "null") || !ids.MatchString(obj.ClientID) {
		return result{}, errors.New("invalid API result")
	}
	parts := strings.Split(obj.JWT, ".")
	if len(parts) != 3 || parts[0] == "" || parts[1] == "" || parts[2] == "" {
		return result{}, errors.New("invalid scoped JWT")
	}
	payload, e := base64.RawURLEncoding.DecodeString(parts[1])
	if e != nil {
		return result{}, e
	}
	var claims struct {
		ClientID string `json:"client_id"`
	}
	if e = json.Unmarshal(payload, &claims); e != nil {
		return result{}, e
	}
	if claims.ClientID != obj.ClientID || (expected != "" && expected != obj.ClientID) {
		return result{}, errors.New("scoped identity mismatch")
	}
	// The claim comparison is consistency checking, not local signature verification.
	return result{obj.ClientID, obj.JWT}, nil
}
func loadMap(file string) (clientMap, error) {
	empty := clientMap{1, map[string]string{}}
	stat, e := os.Lstat(file)
	if errors.Is(e, os.ErrNotExist) {
		return empty, nil
	}
	if e != nil {
		return empty, e
	}
	if !stat.Mode().IsRegular() || stat.Size() > limit || stat.Mode().Perm()&0077 != 0 {
		return empty, errors.New("mapping must be private (0600)")
	}
	raw, e := os.ReadFile(file)
	if e != nil {
		return empty, e
	}
	var m clientMap
	if e = json.Unmarshal(raw, &m); e != nil {
		return empty, e
	}
	if m.Version != 1 || m.Clients == nil {
		return empty, errors.New("invalid mapping")
	}
	seen := map[string]bool{}
	for user, id := range m.Clients {
		if !users.MatchString(user) || !ids.MatchString(id) || seen[id] {
			return empty, errors.New("invalid mapped client")
		}
		seen[id] = true
	}
	return m, nil
}
func saveMap(file string, m clientMap) error {
	raw, e := json.Marshal(m)
	if e != nil {
		return e
	}
	f, e := os.CreateTemp(filepath.Dir(file), "clients-*.json")
	if e != nil {
		return e
	}
	defer os.Remove(f.Name())
	if e = f.Chmod(0600); e != nil {
		f.Close()
		return e
	}
	if _, e = f.Write(raw); e != nil {
		f.Close()
		return e
	}
	if e = f.Sync(); e != nil {
		f.Close()
		return e
	}
	if e = f.Close(); e != nil {
		return e
	}
	return os.Rename(f.Name(), file)
}
func allocate(user, file string, call func(requestBody) ([]byte, error)) (result, error) {
	if !filepath.IsAbs(file) {
		return result{}, errors.New("mapping needs an absolute path")
	}
	lock := file + ".lock"
	if e := os.Mkdir(lock, 0700); e != nil {
		return result{}, e
	}
	defer os.Remove(lock)
	m, e := loadMap(file)
	if e != nil {
		return result{}, e
	}
	old := m.Clients[user]
	body, e := requestFor(user, old)
	if e != nil {
		return result{}, e
	}
	raw, e := call(body)
	if e != nil {
		return result{}, e
	}
	answer, e := parseResponse(raw, old)
	if e != nil {
		return result{}, e
	}
	if old == "" {
		for _, id := range m.Clients {
			if id == answer.ClientID {
				return result{}, errors.New("client assigned to another user")
			}
		}
		m.Clients[user] = answer.ClientID
		if e = saveMap(file, m); e != nil {
			return result{}, e
		}
	}
	return answer, nil
}
func post(endpoint, root string, body requestBody) ([]byte, error) {
	if root == "" || strings.ContainsAny(root, " \t\r\n") {
		return nil, errors.New("set backend root JWT")
	}
	raw, e := json.Marshal(body)
	if e != nil {
		return nil, e
	}
	req, e := http.NewRequest(http.MethodPost, endpoint, bytes.NewReader(raw))
	if e != nil {
		return nil, e
	}
	req.Header.Set("Authorization", "Bearer "+root)
	req.Header.Set("Content-Type", "application/json")
	client := http.Client{Timeout: 15 * time.Second, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}
	response, e := client.Do(req)
	if e != nil {
		return nil, e
	}
	defer response.Body.Close()
	if response.StatusCode < 200 || response.StatusCode >= 300 {
		return nil, errors.New("provisioning HTTP failure")
	}
	raw, e = io.ReadAll(io.LimitReader(response.Body, limit+1))
	if e != nil {
		return nil, e
	}
	if len(raw) > limit {
		return nil, errors.New("response too large")
	}
	return raw, nil
}
func selfTest() error {
	id := "11111111-1111-1111-1111-111111111111"
	payload, _ := json.Marshal(map[string]string{"client_id": id})
	jwt := "e30." + base64.RawURLEncoding.EncodeToString(payload) + ".test"
	raw, _ := json.Marshal(result{id, jwt})
	dir, e := os.MkdirTemp("", "ur-allocator-")
	if e != nil {
		return e
	}
	defer os.RemoveAll(dir)
	file := filepath.Join(dir, "clients.json")
	var calls []requestBody
	mock := func(body requestBody) ([]byte, error) { calls = append(calls, body); return raw, nil }
	a, e := allocate("user:alice", file, mock)
	if e != nil {
		return e
	}
	b, e := allocate("user:alice", file, mock)
	if e != nil {
		return e
	}
	m, e := loadMap(file)
	if e != nil {
		return e
	}
	if a.ClientID != id || b.JWT != jwt || calls[0].ClientID != "" || calls[1].ClientID != id || m.Clients["user:alice"] != id {
		return errors.New("round-trip/request failure")
	}
	first, _ := json.Marshal(calls[0])
	second, _ := json.Marshal(calls[1])
	if bytes.Contains(first, []byte("client_id")) || bytes.Contains(second, []byte("source_client_id")) {
		return errors.New("wrong wire fields")
	}
	if u, e := endpoint("http://127.0.0.1:1234"); e != nil || u != "http://127.0.0.1:1234/network/auth-client" {
		return errors.New("bad mock endpoint")
	}
	invalid := []func() error{func() error { _, e := serviceUser([]string{id}); return e }, func() error { _, e := serviceUser([]string{"user:a", "--client-id", id}); return e }, func() error { _, e := serviceUser([]string{"user:../a"}); return e }, func() error { _, e := endpoint("http://example.com"); return e }, func() error { _, e := endpoint("https://example.com/path"); return e }, func() error { _, e := parseResponse([]byte(`{"error":{}}`), ""); return e }, func() error { _, e := parseResponse(raw, "22222222-2222-2222-2222-222222222222"); return e }}
	for _, f := range invalid {
		if f() == nil {
			return errors.New("invalid input accepted")
		}
	}
	fmt.Println("allocator self-test passed")
	return nil
}
func run() error {
	args := os.Args[1:]
	if len(args) == 1 && args[0] == "--self-test" {
		return selfTest()
	}
	user, e := serviceUser(args)
	if e != nil {
		return e
	}
	base := os.Getenv("URNETWORK_API_URL")
	if base == "" {
		base = "https://api.bringyour.com"
	}
	url, e := endpoint(base)
	if e != nil {
		return e
	}
	root, file := os.Getenv("URNETWORK_ROOT_JWT"), os.Getenv("URNETWORK_CLIENT_MAP")
	if root == "" || file == "" {
		return errors.New("set backend environment")
	}
	r, e := allocate(user, file, func(body requestBody) ([]byte, error) { return post(url, root, body) })
	if e != nil {
		return e
	}
	return json.NewEncoder(os.Stdout).Encode(r)
}
func main() {
	if run() != nil {
		fmt.Fprintln(os.Stderr, "allocator failed: check service key, private mapping and backend API configuration")
		os.Exit(1)
	}
}
