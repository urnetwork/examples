// The URnetwork API calls of the embed backend, made with the root
// credential: provision and reissue clients (POST /network/auth-client), set
// and read data caps (POST and GET /network/client-data-cap, GET
// /network/client-data-caps) and remove clients (POST /network/remove-client).
// The field names follow connect's api/bringyour.yml.
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
	"strings"
	"time"
)

// the largest response the backend reads
const byteLimit = 1024 * 1024

const httpTimeout = 30 * time.Second

// The server answers a reissue of a deactivated (30 days without connecting)
// or removed client, and a remove of a missing client, with this message.
const clientDoesNotExistMessage = "Client does not exist."

// A server without ACL groups answers their route with 404 (EMBED_CONTRACT.md,
// "ACL groups"). The commands exit 1 with this text; the token server reports
// it and still answers the token.
var errAclUnsupported = errors.New("/network/client-acl-group answered 404: the server predates ACL groups")

// Sends one api request: the method, the path (with its query) below the api
// origin and an optional json body. Returns the http status and the bounded
// response body.
type apiTransport func(method string, path string, body []byte) (int, []byte, error)

// A call that the server answered with its error object.
type apiRefusal struct {
	message string
	// error.client_limit_exceeded or error.upgrade_required: the network is
	// at its client limit or its plan's concurrent client limit
	clientLimit bool
}

// The server's message on one line.
func (self *apiRefusal) Error() string {
	return fmt.Sprintf("the URnetwork API refused the request: %s", oneLine(self.message))
}

// Whether err is the server's "Client does not exist." refusal.
func isClientDoesNotExist(err error) bool {
	var refusal *apiRefusal
	return errors.As(err, &refusal) && refusal.message == clientDoesNotExistMessage
}

// Whether err is a client limit refusal, for either flag.
func isClientLimit(err error) bool {
	var refusal *apiRefusal
	return errors.As(err, &refusal) && refusal.clientLimit
}

// The URnetwork API failed: it could not be reached, answered an unexpected
// status or something invalid, or refused the root credential. The token
// server answers these with 502; a refused root credential also unwraps to
// a configError, so the commands exit 78 for it.
type upstreamError struct {
	err error
}

// The cause on one line.
func (self *upstreamError) Error() string {
	return self.err.Error()
}

// The cause.
func (self *upstreamError) Unwrap() error {
	return self.err
}

// An upstream error with a formatted message.
func upstreamErrorf(format string, args ...any) error {
	return &upstreamError{err: fmt.Errorf(format, args...)}
}

// The api calls, over one transport.
type apiClient struct {
	transport apiTransport
}

// Sends one request and returns the 2xx response body. A 401 or 403 means the
// server refused the root credential: a configuration error.
func (self *apiClient) call(method string, path string, body []byte) ([]byte, error) {
	status, response, err := self.transport(method, path, body)
	if err != nil {
		return nil, upstreamErrorf("the URnetwork API could not be reached: %v", err)
	}
	if status == http.StatusUnauthorized || status == http.StatusForbidden {
		return nil, &upstreamError{err: configErrorf("the URnetwork API refused the root credential (http %d)", status)}
	}
	if status == http.StatusNotFound && strings.HasPrefix(path, "/network/client-acl-group") {
		return nil, &upstreamError{err: errAclUnsupported}
	}
	if status == http.StatusNotFound && strings.HasPrefix(path, "/network/client-data-cap") {
		// a server without the data-cap routes (EMBED_CONTRACT.md, "Backend tools")
		route, _, _ := strings.Cut(path, "?")
		return nil, upstreamErrorf("%s answered 404: the server predates the data-cap routes", route)
	}
	if status < 200 || 300 <= status {
		return nil, upstreamErrorf("the URnetwork API answered http %d", status)
	}
	return response, nil
}

// One POST /network/auth-client request for a top-level client. ClientId is
// set only to reissue; source_client_id is never sent.
type authClientArgs struct {
	ClientId    string `json:"client_id,omitempty"`
	Description string `json:"description"`
	DeviceSpec  string `json:"device_spec"`
}

// Provisions a new client (args.ClientId empty) or reissues args.ClientId,
// and returns the client id and its scoped client JWT. A refusal answers 200
// with the error object. The JWT's client_id claim must name the answered
// client, a reissue must answer the stored client, and the claim is read
// without verifying the token, which the server signs and the SDK verifies.
func (self *apiClient) AuthClient(args *authClientArgs) (string, string, error) {
	request, err := json.Marshal(args)
	if err != nil {
		return "", "", err
	}
	body, err := self.call(http.MethodPost, "/network/auth-client", request)
	if err != nil {
		return "", "", err
	}
	var result struct {
		ClientId    string `json:"client_id"`
		ByClientJwt string `json:"by_client_jwt"`
		Error       *struct {
			ClientLimitExceeded bool   `json:"client_limit_exceeded"`
			UpgradeRequired     bool   `json:"upgrade_required"`
			Message             string `json:"message"`
		} `json:"error"`
	}
	if err := json.Unmarshal(body, &result); err != nil {
		return "", "", upstreamErrorf("the URnetwork API answered auth-client with something invalid")
	}
	if result.Error != nil {
		return "", "", &apiRefusal{
			message:     result.Error.Message,
			clientLimit: result.Error.ClientLimitExceeded || result.Error.UpgradeRequired,
		}
	}
	if !uuidPattern.MatchString(result.ClientId) {
		return "", "", upstreamErrorf("the URnetwork API answered auth-client with no valid client_id")
	}
	if args.ClientId != "" && result.ClientId != args.ClientId {
		return "", "", upstreamErrorf("the URnetwork API reissued another client")
	}
	var claims struct {
		ClientId string `json:"client_id"`
	}
	if err := decodeJwtClaims(result.ByClientJwt, &claims); err != nil || claims.ClientId != result.ClientId {
		return "", "", upstreamErrorf("the client JWT does not name the answered client")
	}
	return result.ClientId, result.ByClientJwt, nil
}

// Posts a POST /network/client-data-cap body and returns the client's cap
// object after the change.
func (self *apiClient) SetDataCap(clientId string, request []byte) (json.RawMessage, error) {
	body, err := self.call(http.MethodPost, "/network/client-data-cap", request)
	if err != nil {
		return nil, err
	}
	return parseCapObject(body, clientId)
}

// Reads one client's cap object.
func (self *apiClient) GetDataCap(clientId string) (json.RawMessage, error) {
	body, err := self.call(http.MethodGet, "/network/client-data-cap?client_id="+url.QueryEscape(clientId), nil)
	if err != nil {
		return nil, err
	}
	return parseCapObject(body, clientId)
}

// Reads one page of GET /network/client-data-caps with limit=1000: the cap
// objects of the network's capped clients and the next cursor, nil on the
// last page.
func (self *apiClient) ListDataCaps(cursor string) ([]json.RawMessage, *string, error) {
	path := "/network/client-data-caps?limit=1000"
	if cursor != "" {
		path += "&cursor=" + url.QueryEscape(cursor)
	}
	body, err := self.call(http.MethodGet, path, nil)
	if err != nil {
		return nil, nil, err
	}
	var result struct {
		Clients    []json.RawMessage `json:"clients"`
		NextCursor *string           `json:"next_cursor"`
		Error      *struct {
			Message string `json:"message"`
		} `json:"error"`
	}
	if err := json.Unmarshal(body, &result); err != nil {
		return nil, nil, upstreamErrorf("the URnetwork API answered client-data-caps with something invalid")
	}
	if result.Error != nil {
		return nil, nil, &apiRefusal{message: result.Error.Message}
	}
	capObjects := make([]json.RawMessage, 0, len(result.Clients))
	for _, raw := range result.Clients {
		capObject, err := parseCapObject(raw, "")
		if err != nil {
			return nil, nil, err
		}
		capObjects = append(capObjects, capObject)
	}
	return capObjects, result.NextCursor, nil
}

// Sets one client's ACL group with POST /network/client-acl-group and
// returns the answer, {"client_id": "...", "acl_group": "..."}, after
// checking that it names the client and the group.
func (self *apiClient) SetAclGroup(clientId string, aclGroup string) (json.RawMessage, error) {
	request, err := json.Marshal(map[string]string{"client_id": clientId, "acl_group": aclGroup})
	if err != nil {
		return nil, err
	}
	body, err := self.call(http.MethodPost, "/network/client-acl-group", request)
	if err != nil {
		return nil, err
	}
	var result struct {
		ClientId string `json:"client_id"`
		AclGroup string `json:"acl_group"`
		Error    *struct {
			Message string `json:"message"`
		} `json:"error"`
	}
	if err := json.Unmarshal(body, &result); err != nil {
		return nil, upstreamErrorf("the URnetwork API answered client-acl-group with something invalid")
	}
	if result.Error != nil {
		return nil, &apiRefusal{message: result.Error.Message}
	}
	if result.ClientId != clientId || result.AclGroup != aclGroup {
		return nil, upstreamErrorf("the URnetwork API answered client-acl-group for another client or group")
	}
	return json.Marshal(map[string]string{"client_id": result.ClientId, "acl_group": result.AclGroup})
}

// Removes one client. A missing client answers the "Client does not exist."
// refusal.
func (self *apiClient) RemoveClient(clientId string) error {
	request, err := json.Marshal(map[string]string{"client_id": clientId})
	if err != nil {
		return err
	}
	body, err := self.call(http.MethodPost, "/network/remove-client", request)
	if err != nil {
		return err
	}
	var result struct {
		Error *struct {
			Message string `json:"message"`
		} `json:"error"`
	}
	if err := json.Unmarshal(body, &result); err != nil {
		return upstreamErrorf("the URnetwork API answered remove-client with something invalid")
	}
	if result.Error != nil {
		return &apiRefusal{message: result.Error.Message}
	}
	return nil
}

// The fields of a cap object (EMBED_CONTRACT.md, "The cap object") that the
// backend checks before it prints or returns the object.
type capObject struct {
	ClientId             string `json:"client_id"`
	MonthlyByteLimit     *int64 `json:"monthly_byte_limit"`
	MonthlyUsedByteCount int64  `json:"monthly_used_byte_count"`
	MonthlyPeriodStart   string `json:"monthly_period_start"`
	MonthlyPeriodEnd     string `json:"monthly_period_end"`
	TotalByteLimit       *int64 `json:"total_byte_limit"`
	TotalUsedByteCount   int64  `json:"total_used_byte_count"`
	TotalPeriodStart     string `json:"total_period_start"`
	Capped               bool   `json:"capped"`
	CappedReason         string `json:"capped_reason"`
	Error                *struct {
		Message string `json:"message"`
	} `json:"error"`
}

// Checks one cap object and returns it compacted, with every field the
// server sent. expectedClientId, when set, must be its client.
func parseCapObject(raw []byte, expectedClientId string) (json.RawMessage, error) {
	var parsed capObject
	if err := json.Unmarshal(raw, &parsed); err != nil {
		return nil, upstreamErrorf("the URnetwork API answered an invalid cap object")
	}
	if parsed.Error != nil {
		return nil, &apiRefusal{message: parsed.Error.Message}
	}
	if !uuidPattern.MatchString(parsed.ClientId) || (expectedClientId != "" && parsed.ClientId != expectedClientId) {
		return nil, upstreamErrorf("the URnetwork API answered a cap object for another client")
	}
	compact := &bytes.Buffer{}
	if err := json.Compact(compact, raw); err != nil {
		return nil, err
	}
	return json.RawMessage(compact.Bytes()), nil
}

// Decodes the claims of a JWT into claims, without verifying the token.
func decodeJwtClaims(jwt string, claims any) error {
	parts := strings.Split(jwt, ".")
	if len(parts) != 3 || parts[0] == "" || parts[1] == "" || parts[2] == "" {
		return errors.New("not a JWT")
	}
	payload, err := base64.RawURLEncoding.DecodeString(strings.TrimRight(parts[1], "="))
	if err != nil {
		return errors.New("not a JWT")
	}
	if err := json.Unmarshal(payload, claims); err != nil {
		return errors.New("not a JWT")
	}
	return nil
}

// An api transport over https with the root credential. Redirects are not
// followed, so the credential only goes to the configured origin.
func newHttpTransport(origin string, rootCredential string) apiTransport {
	client := &http.Client{
		Timeout: httpTimeout,
		CheckRedirect: func(*http.Request, []*http.Request) error {
			return http.ErrUseLastResponse
		},
	}
	return func(method string, path string, body []byte) (int, []byte, error) {
		var requestBody io.Reader
		if body != nil {
			requestBody = bytes.NewReader(body)
		}
		request, err := http.NewRequest(method, origin+path, requestBody)
		if err != nil {
			return 0, nil, err
		}
		request.Header.Set("Authorization", "Bearer "+rootCredential)
		if body != nil {
			request.Header.Set("Content-Type", "application/json")
		}
		response, err := client.Do(request)
		if err != nil {
			return 0, nil, err
		}
		defer response.Body.Close()
		responseBody, err := io.ReadAll(io.LimitReader(response.Body, byteLimit+1))
		if err != nil {
			return 0, nil, err
		}
		if byteLimit < len(responseBody) {
			return 0, nil, errors.New("response too large")
		}
		return response.StatusCode, responseBody, nil
	}
}

// An https origin without credentials, path, query or fragment. Plain http is
// accepted only for an explicit loopback mock.
func apiOrigin(apiUrl string) (string, error) {
	parsed, err := url.Parse(apiUrl)
	if err != nil {
		return "", configErrorf("URNETWORK_API_URL is not a URL")
	}
	hostName := parsed.Hostname()
	loopback := hostName == "localhost" || hostName == "127.0.0.1" || hostName == "::1"
	if hostName == "" || parsed.User != nil || (parsed.Path != "" && parsed.Path != "/") || parsed.RawQuery != "" || parsed.Fragment != "" || (parsed.Scheme != "https" && !(parsed.Scheme == "http" && loopback)) {
		return "", configErrorf("URNETWORK_API_URL must be an https origin, or explicit loopback http for a mock")
	}
	return strings.TrimSuffix(apiUrl, "/"), nil
}
