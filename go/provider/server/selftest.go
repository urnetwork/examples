// The credential-free self-test: ss58 decoding, consent checks, signature
// form, the network JWT claim, provider install provisioning and its map, and
// the network and per-provider challenge, accept and show requests against a
// mock api. No network or credentials are used.
package main

import (
	"bytes"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io/fs"
	"net/http"
	"os"
	"path/filepath"
	"runtime"
	"strings"
)

// The public substrate development accounts Alice and Bob: synthetic, well
// known test identities.
const selfTestAliceSs58 = "5GrwvaEF5zXb26Fz9rcQpDWS57CtERHpNehXCPcNoHGKutQY"
const selfTestAliceKeyHex = "d43593c715fdd31c61141abd04a99fd6822c8558854ccde39a5684e7a56da27d"
const selfTestBobSs58 = "5FHneW46xGXgs5mUiveU4sbTyGBzmstUspZC92UhjJM694ty"

const selfTestClientId = "11111111-1111-1111-1111-111111111111"

const selfTestNetworkId = "99999999-9999-9999-9999-999999999999"

// an installation key and the provider installs the mock api issues for it
const selfTestInstallationKey = "user:alice:laptop-1"
const selfTestProviderClientId = "33333333-3333-3333-3333-333333333333"
const selfTestNewProviderClientId = "44444444-4444-4444-4444-444444444444"

// One request seen by the mock api.
type selfTestRequest struct {
	method string
	path   string
	body   []byte
}

// A mock api with canned responses by method and path. Queued responses are
// answered first, each once, before the canned response.
type selfTestApi struct {
	responses      map[string][]byte
	responseQueues map[string][][]byte
	statuses       map[string]int
	requests       []*selfTestRequest
}

// A mock api without responses.
func newSelfTestApi() *selfTestApi {
	return &selfTestApi{
		responses:      map[string][]byte{},
		responseQueues: map[string][][]byte{},
		statuses:       map[string]int{},
	}
}

// Records the request and returns the next queued or the canned response.
func (self *selfTestApi) transport(method string, path string, body []byte) (int, []byte, error) {
	self.requests = append(self.requests, &selfTestRequest{method: method, path: path, body: bytes.Clone(body)})
	key := method + " " + path
	response, ok := self.responses[key]
	if queue := self.responseQueues[key]; 0 < len(queue) {
		response, ok = queue[0], true
		self.responseQueues[key] = queue[1:]
	}
	if !ok {
		return 0, nil, fmt.Errorf("unexpected request %s", key)
	}
	status := http.StatusOK
	if code, ok := self.statuses[key]; ok {
		status = code
	}
	return status, response, nil
}

// A consent message in the server's form for the given client, coldkey and
// epochs.
func selfTestConsentMessage(clientId string, coldkeyHex string, fromEpoch uint64, throughEpoch uint64) string {
	var clientIdBytes [16]byte
	clientIdRaw, _ := hex.DecodeString(strings.ReplaceAll(clientId, "-", ""))
	copy(clientIdBytes[:], clientIdRaw)
	var coldkey [32]byte
	coldkeyRaw, _ := hex.DecodeString(coldkeyHex)
	copy(coldkey[:], coldkeyRaw)
	statement, _ := json.Marshal(map[string]any{
		"schema":        "urnetwork-provider-wallet-mapping-consent-v1",
		"client_id":     clientIdBytes,
		"coldkey":       coldkey,
		"from_epoch":    fromEpoch,
		"through_epoch": throughEpoch,
		"issued_at":     1700000000,
		"expires_at":    1700000300,
	})
	return consentPrefix + string(statement)
}

// A network consent message in the server's form. extra fields are added to
// the statement, to build messages the tool must refuse.
func selfTestNetworkConsentMessage(networkId string, coldkeyHex string, fromEpoch uint64, throughEpoch uint64, extra map[string]any) string {
	var networkIdBytes [16]byte
	networkIdRaw, _ := hex.DecodeString(strings.ReplaceAll(networkId, "-", ""))
	copy(networkIdBytes[:], networkIdRaw)
	var coldkey [32]byte
	coldkeyRaw, _ := hex.DecodeString(coldkeyHex)
	copy(coldkey[:], coldkeyRaw)
	fields := map[string]any{
		"schema":        "urnetwork-network-wallet-mapping-consent-v1",
		"scope":         "network",
		"network_id":    networkIdBytes,
		"coldkey":       coldkey,
		"from_epoch":    fromEpoch,
		"through_epoch": throughEpoch,
		"issued_at":     1700000000,
		"expires_at":    1700000300,
	}
	for key, value := range extra {
		fields[key] = value
	}
	statement, _ := json.Marshal(fields)
	return networkConsentPrefix + string(statement)
}

// An unsigned token with the given claims; only its claims are read.
func selfTestJwt(claims string) string {
	encode := base64.RawURLEncoding.EncodeToString
	return encode([]byte(`{"alg":"none"}`)) + "." + encode([]byte(claims)) + "." + encode([]byte("signature"))
}

// Runs every check and returns the first failure.
func runSelfTest() error {
	checks := []func() error{
		checkDecodeSs58,
		checkNormalizeSignature,
		checkApiOrigin,
		checkJwtNetworkId,
		checkProviderMap,
		checkProvision,
		checkProvisionDeactivatedClient,
		checkProvisionRefusals,
		checkNetworkChallengeAndAccept,
		checkChallengeAndAccept,
		checkRefusedConsent,
		checkShowWallets,
	}
	for _, check := range checks {
		if err := check(); err != nil {
			return err
		}
	}
	return nil
}

// Bittensor addresses decode to their public key; other strings are refused.
func checkDecodeSs58() error {
	key, err := decodeSs58(selfTestAliceSs58)
	if err != nil || hex.EncodeToString(key[:]) != selfTestAliceKeyHex {
		return fmt.Errorf("ss58 decode %x (%v)", key, err)
	}
	for _, invalid := range []string{"", "0OIl", "15oF4uVJwmo4TdGW7VfQxNLavjCXviqxT9S1MgbjMNHr6Sp5", "5GrwvaEF5zXb26Fz9rcQpDWS57CtERHpNehXCPcNoHGKut", "1111111111111111111111111111111111111111111111"} {
		if _, err := decodeSs58(invalid); err == nil {
			return fmt.Errorf("invalid ss58 address %q accepted", invalid)
		}
	}
	return nil
}

// Signatures are 64-byte hex with an optional 0x.
func checkNormalizeSignature() error {
	signature := strings.Repeat("Ab", 64)
	for _, form := range []string{signature, "0x" + signature, " 0X" + signature + "\n"} {
		normalized, err := normalizeSignature(form)
		if err != nil || normalized != "0x"+strings.ToLower(signature) {
			return fmt.Errorf("signature %q normalized to %q (%v)", form, normalized, err)
		}
	}
	for _, invalid := range []string{"", "0x", strings.Repeat("a", 127), strings.Repeat("g", 128), strings.Repeat("a", 130)} {
		if _, err := normalizeSignature(invalid); err == nil {
			return fmt.Errorf("invalid signature %q accepted", invalid)
		}
	}
	return nil
}

// The api is an https origin, or loopback http for a mock.
func checkApiOrigin() error {
	if origin, err := apiOrigin("https://api.example/"); err != nil || origin != "https://api.example" {
		return fmt.Errorf("https origin %q (%v)", origin, err)
	}
	if origin, err := apiOrigin("http://127.0.0.1:8080"); err != nil || origin != "http://127.0.0.1:8080" {
		return fmt.Errorf("loopback origin %q (%v)", origin, err)
	}
	for _, invalid := range []string{"http://api.example", "https://api.example/path", "https://user@api.example", "https://api.example?a=b", "ftp://api.example"} {
		if _, err := apiOrigin(invalid); err == nil {
			return fmt.Errorf("invalid api url %q accepted", invalid)
		}
	}
	return nil
}

// A network JWT names its network; a client JWT, a token without a network
// and a malformed token are refused.
func checkJwtNetworkId() error {
	networkId, err := jwtNetworkId(selfTestJwt(`{"network_id":"` + selfTestNetworkId + `","user_id":"22222222-2222-2222-2222-222222222222"}`))
	if err != nil || networkId != selfTestNetworkId {
		return fmt.Errorf("network JWT read as %q (%v)", networkId, err)
	}
	for _, invalid := range []string{
		selfTestJwt(`{"network_id":"` + selfTestNetworkId + `","client_id":"` + selfTestClientId + `"}`),
		selfTestJwt(`{"user_id":"22222222-2222-2222-2222-222222222222"}`),
		selfTestJwt(`{"network_id":"NOT-A-UUID"}`),
		selfTestJwt(`not json`),
		"not-a-jwt",
	} {
		if _, err := jwtNetworkId(invalid); err == nil {
			return fmt.Errorf("token %q accepted for a network consent", invalid)
		}
	}
	return nil
}

// A private wallet directory for one check, and its cleanup.
func selfTestWalletDir() (string, func(), error) {
	walletDir, err := os.MkdirTemp("", "ur-wallet-self-test-")
	if err != nil {
		return "", nil, err
	}
	cleanup := func() {
		os.RemoveAll(walletDir)
	}
	if err := os.Chmod(walletDir, 0o700); err != nil {
		cleanup()
		return "", nil, err
	}
	return walletDir, cleanup, nil
}

// An unsigned client JWT naming clientId; only its claims are read.
func selfTestClientJwt(clientId string) string {
	return selfTestJwt(`{"client_id":"` + clientId + `","network_id":"` + selfTestNetworkId + `"}`)
}

// An auth-client result that issues clientId.
func selfTestAuthClientResult(clientId string) []byte {
	result, _ := json.Marshal(map[string]string{
		"client_id":     clientId,
		"by_client_jwt": selfTestClientJwt(clientId),
	})
	return result
}

// The json fields of one recorded request.
func selfTestRequestFields(request *selfTestRequest) (map[string]any, error) {
	fields := map[string]any{}
	if err := json.Unmarshal(request.body, &fields); err != nil {
		return nil, err
	}
	return fields, nil
}

// providers.json round trips privately. A missing map is empty; an invalid
// map, and on POSIX a map that others can read, is refused.
func checkProviderMap() error {
	walletDir, cleanup, err := selfTestWalletDir()
	if err != nil {
		return err
	}
	defer cleanup()
	path := filepath.Join(walletDir, providerMapFile)
	empty, err := loadProviderMap(path)
	if err != nil || empty.Version != providerMapVersion || len(empty.InstallationKeyClientIds) != 0 {
		return fmt.Errorf("a missing provider map loaded as %+v (%v)", empty, err)
	}
	providers := &providerMap{
		Version: providerMapVersion,
		InstallationKeyClientIds: map[string]string{
			selfTestInstallationKey: selfTestProviderClientId,
			"user:bob:desktop":      selfTestNewProviderClientId,
		},
	}
	if err := saveProviderMap(path, providers); err != nil {
		return err
	}
	loaded, err := loadProviderMap(path)
	if err != nil || len(loaded.InstallationKeyClientIds) != 2 || loaded.InstallationKeyClientIds[selfTestInstallationKey] != selfTestProviderClientId || loaded.InstallationKeyClientIds["user:bob:desktop"] != selfTestNewProviderClientId {
		return fmt.Errorf("provider map round trip %+v (%v)", loaded, err)
	}
	if runtime.GOOS != "windows" {
		if info, err := os.Stat(path); err != nil || info.Mode().Perm() != 0o600 {
			return errors.New("the provider map is not private")
		}
		if err := os.Chmod(path, 0o644); err != nil {
			return err
		}
		if _, err := loadProviderMap(path); err == nil {
			return errors.New("a provider map that others can read was accepted")
		}
	}
	for _, invalid := range []string{
		`not json`,
		`{"version":2,"clients":{}}`,
		`{"version":1}`,
		`{"version":1,"clients":{"` + selfTestProviderClientId + `":"` + selfTestProviderClientId + `"}}`,
		`{"version":1,"clients":{"` + selfTestInstallationKey + `":"NOT-A-UUID"}}`,
		`{"version":1,"clients":{"` + selfTestInstallationKey + `":"` + selfTestProviderClientId + `","user:bob:desktop":"` + selfTestProviderClientId + `"}}`,
	} {
		if err := writePrivateFile(path, []byte(invalid)); err != nil {
			return err
		}
		if _, err := loadProviderMap(path); err == nil {
			return fmt.Errorf("an invalid provider map was accepted: %s", invalid)
		}
	}
	return nil
}

// The first provision creates a provider install with provide intent, maps it
// and writes its client JWT privately; the next one reissues the stored client
// by its id, without the flag that the server ignores on a reissue.
func checkProvision() error {
	walletDir, cleanup, err := selfTestWalletDir()
	if err != nil {
		return err
	}
	defer cleanup()
	api := newSelfTestApi()
	api.responses["POST /network/auth-client"] = selfTestAuthClientResult(selfTestProviderClientId)
	out := &bytes.Buffer{}
	provisioner, err := newProviderProvisioner(walletDir, api.transport, out)
	if err != nil {
		return err
	}
	clientJwtPath := filepath.Join(walletDir, "client.jwt")
	if err := provisioner.Provision(selfTestInstallationKey, clientJwtPath); err != nil {
		return err
	}
	// a new top-level client: description, spec and provide intent, no client ids
	if len(api.requests) != 1 || api.requests[0].method != http.MethodPost || api.requests[0].path != "/network/auth-client" {
		return errors.New("create requests differ")
	}
	createRequest, err := selfTestRequestFields(api.requests[0])
	if err != nil || len(createRequest) != 3 || createRequest["provide_intent"] != true || createRequest["description"] != "provider "+selfTestInstallationKey || createRequest["device_spec"] != providerDeviceSpec {
		return fmt.Errorf("create request %v (%v)", createRequest, err)
	}
	mapPath := filepath.Join(walletDir, providerMapFile)
	providers, err := loadProviderMap(mapPath)
	if err != nil || len(providers.InstallationKeyClientIds) != 1 || providers.InstallationKeyClientIds[selfTestInstallationKey] != selfTestProviderClientId {
		return fmt.Errorf("the provider install was not mapped (%v)", err)
	}
	clientJwt, err := readPrivateFile(clientJwtPath)
	if err != nil || string(clientJwt) != selfTestClientJwt(selfTestProviderClientId)+"\n" {
		return fmt.Errorf("the client JWT was not written privately (%v)", err)
	}
	if runtime.GOOS != "windows" {
		for _, path := range []string{mapPath, clientJwtPath} {
			if info, err := os.Stat(path); err != nil || info.Mode().Perm() != 0o600 {
				return fmt.Errorf("%s is not private", filepath.Base(path))
			}
		}
	}
	if _, err := os.Stat(mapPath + ".lock"); !errors.Is(err, fs.ErrNotExist) {
		return errors.New("the provider map lock was not released")
	}

	if err := provisioner.Provision(selfTestInstallationKey, clientJwtPath); err != nil {
		return err
	}
	if len(api.requests) != 2 {
		return errors.New("reissue requests differ")
	}
	reissueRequest, err := selfTestRequestFields(api.requests[1])
	if err != nil || len(reissueRequest) != 3 || reissueRequest["client_id"] != selfTestProviderClientId || reissueRequest["description"] != "provider "+selfTestInstallationKey || reissueRequest["device_spec"] != providerDeviceSpec {
		return fmt.Errorf("reissue request %v (%v)", reissueRequest, err)
	}
	providers, err = loadProviderMap(mapPath)
	if err != nil || len(providers.InstallationKeyClientIds) != 1 || providers.InstallationKeyClientIds[selfTestInstallationKey] != selfTestProviderClientId {
		return fmt.Errorf("a reissue changed the mapping (%v)", err)
	}
	if !strings.Contains(out.String(), "created provider install "+selfTestProviderClientId) || !strings.Contains(out.String(), "reissued provider install "+selfTestProviderClientId) {
		return fmt.Errorf("provision output %q", out.String())
	}
	if strings.Contains(out.String(), selfTestClientJwt(selfTestProviderClientId)) {
		return errors.New("the client JWT was printed")
	}
	return nil
}

// A reissue that the server answers with "Client does not exist." (a client
// deactivated after 30 days without connecting) drops the mapping and creates
// a new provider install for the installation.
func checkProvisionDeactivatedClient() error {
	walletDir, cleanup, err := selfTestWalletDir()
	if err != nil {
		return err
	}
	defer cleanup()
	mapPath := filepath.Join(walletDir, providerMapFile)
	if err := saveProviderMap(mapPath, &providerMap{
		Version: providerMapVersion,
		InstallationKeyClientIds: map[string]string{
			selfTestInstallationKey: selfTestProviderClientId,
		},
	}); err != nil {
		return err
	}
	api := newSelfTestApi()
	api.responseQueues["POST /network/auth-client"] = [][]byte{
		[]byte(`{"error":{"client_limit_exceeded":false,"message":"Client does not exist."}}`),
		selfTestAuthClientResult(selfTestNewProviderClientId),
	}
	out := &bytes.Buffer{}
	provisioner, err := newProviderProvisioner(walletDir, api.transport, out)
	if err != nil {
		return err
	}
	clientJwtPath := filepath.Join(walletDir, "client.jwt")
	if err := provisioner.Provision(selfTestInstallationKey, clientJwtPath); err != nil {
		return err
	}
	if len(api.requests) != 2 {
		return fmt.Errorf("%d requests, want a reissue and a create", len(api.requests))
	}
	reissueRequest, err := selfTestRequestFields(api.requests[0])
	if err != nil || reissueRequest["client_id"] != selfTestProviderClientId {
		return fmt.Errorf("reissue request %v (%v)", reissueRequest, err)
	}
	createRequest, err := selfTestRequestFields(api.requests[1])
	if _, named := createRequest["client_id"]; err != nil || named || createRequest["provide_intent"] != true {
		return fmt.Errorf("create request %v (%v)", createRequest, err)
	}
	providers, err := loadProviderMap(mapPath)
	if err != nil || len(providers.InstallationKeyClientIds) != 1 || providers.InstallationKeyClientIds[selfTestInstallationKey] != selfTestNewProviderClientId {
		return fmt.Errorf("the new provider install was not mapped (%v)", err)
	}
	clientJwt, err := readPrivateFile(clientJwtPath)
	if err != nil || string(clientJwt) != selfTestClientJwt(selfTestNewProviderClientId)+"\n" {
		return fmt.Errorf("the new client JWT was not written (%v)", err)
	}
	if !strings.Contains(out.String(), "provider install "+selfTestProviderClientId+" of "+selfTestInstallationKey+" no longer exists") {
		return fmt.Errorf("provision output %q", out.String())
	}

	// the dropped mapping stays dropped when the new create fails
	api.responseQueues["POST /network/auth-client"] = [][]byte{
		[]byte(`{"error":{"client_limit_exceeded":false,"message":"Client does not exist."}}`),
		[]byte(`{"error":{"client_limit_exceeded":true,"message":"Client limit exceeded."}}`),
	}
	if err := provisioner.Provision(selfTestInstallationKey, clientJwtPath); err == nil {
		return errors.New("a failed create after a dropped mapping was accepted")
	}
	providers, err = loadProviderMap(mapPath)
	if err != nil || len(providers.InstallationKeyClientIds) != 0 {
		return fmt.Errorf("the dropped mapping is still in the map (%v)", err)
	}
	return nil
}

// Invalid input and a held lock send nothing; a refused or mismatched answer
// fails and leaves the map and the client JWT as they were.
func checkProvisionRefusals() error {
	walletDir, cleanup, err := selfTestWalletDir()
	if err != nil {
		return err
	}
	defer cleanup()
	api := newSelfTestApi()
	provisioner, err := newProviderProvisioner(walletDir, api.transport, &bytes.Buffer{})
	if err != nil {
		return err
	}
	mapPath := filepath.Join(walletDir, providerMapFile)
	clientJwtPath := filepath.Join(walletDir, "client.jwt")

	invalidCases := []struct {
		installationKey string
		clientJwtPath   string
	}{
		{installationKey: selfTestProviderClientId, clientJwtPath: clientJwtPath},
		{installationKey: "user:../alice", clientJwtPath: clientJwtPath},
		{installationKey: selfTestInstallationKey, clientJwtPath: "relative/client.jwt"},
	}
	for _, c := range invalidCases {
		if err := provisioner.Provision(c.installationKey, c.clientJwtPath); err == nil {
			return fmt.Errorf("invalid provision %+v accepted", c)
		}
	}
	if err := os.Mkdir(mapPath+".lock", 0o700); err != nil {
		return err
	}
	if err := provisioner.Provision(selfTestInstallationKey, clientJwtPath); err == nil {
		return errors.New("a provision ran while another held the lock")
	}
	if err := os.Remove(mapPath + ".lock"); err != nil {
		return err
	}
	if len(api.requests) != 0 {
		return errors.New("invalid input reached the api")
	}

	createFailures := []struct {
		status int
		body   string
		text   string
	}{
		{status: http.StatusOK, body: `{"error":{"client_limit_exceeded":true,"message":"Client limit exceeded."}}`, text: "the server refused the client: Client limit exceeded."},
		{status: http.StatusInternalServerError, body: "unavailable\n", text: "http 500: unavailable"},
		{status: http.StatusOK, body: `{"client_id":"NOT-A-UUID","by_client_jwt":"` + selfTestClientJwt("NOT-A-UUID") + `"}`, text: "no valid client id"},
		{status: http.StatusOK, body: `{"client_id":"` + selfTestProviderClientId + `","by_client_jwt":"` + selfTestClientJwt(selfTestNewProviderClientId) + `"}`, text: "does not name the client"},
	}
	for _, failure := range createFailures {
		api.statuses["POST /network/auth-client"] = failure.status
		api.responses["POST /network/auth-client"] = []byte(failure.body)
		if err := provisioner.Provision(selfTestInstallationKey, clientJwtPath); err == nil || !strings.Contains(err.Error(), failure.text) {
			return fmt.Errorf("a failed create reported as %v, want %q", err, failure.text)
		}
		for _, path := range []string{mapPath, clientJwtPath} {
			if _, err := os.Stat(path); !errors.Is(err, fs.ErrNotExist) {
				return fmt.Errorf("a failed create wrote %s", filepath.Base(path))
			}
		}
	}
	api.statuses["POST /network/auth-client"] = http.StatusOK

	// a new client that the map already gives to another installation
	if err := saveProviderMap(mapPath, &providerMap{
		Version: providerMapVersion,
		InstallationKeyClientIds: map[string]string{
			"user:bob:desktop": selfTestProviderClientId,
		},
	}); err != nil {
		return err
	}
	api.responses["POST /network/auth-client"] = selfTestAuthClientResult(selfTestProviderClientId)
	if err := provisioner.Provision(selfTestInstallationKey, clientJwtPath); err == nil || !strings.Contains(err.Error(), "already mapped to user:bob:desktop") {
		return fmt.Errorf("a client mapped to another installation reported as %v", err)
	}

	// a reissue refused for another reason, or answered for another client,
	// keeps the mapping and creates nothing
	if err := saveProviderMap(mapPath, &providerMap{
		Version: providerMapVersion,
		InstallationKeyClientIds: map[string]string{
			selfTestInstallationKey: selfTestProviderClientId,
		},
	}); err != nil {
		return err
	}
	for _, body := range [][]byte{
		[]byte(`{"error":{"client_limit_exceeded":false,"message":"Device does not exist."}}`),
		selfTestAuthClientResult(selfTestNewProviderClientId),
	} {
		api.responses["POST /network/auth-client"] = body
		requestCount := len(api.requests)
		if err := provisioner.Provision(selfTestInstallationKey, clientJwtPath); err == nil {
			return fmt.Errorf("a failed reissue was accepted: %s", body)
		}
		if len(api.requests) != requestCount+1 {
			return errors.New("a failed reissue created a client")
		}
		providers, err := loadProviderMap(mapPath)
		if err != nil || len(providers.InstallationKeyClientIds) != 1 || providers.InstallationKeyClientIds[selfTestInstallationKey] != selfTestProviderClientId {
			return fmt.Errorf("a failed reissue changed the mapping (%v)", err)
		}
		if _, err := os.Stat(clientJwtPath); !errors.Is(err, fs.ErrNotExist) {
			return errors.New("a failed reissue wrote a client JWT")
		}
	}
	return nil
}

// Network challenge saves the exact network message for the network and
// coldkey; network accept submits it without a client id.
func checkNetworkChallengeAndAccept() error {
	walletDir, cleanup, err := selfTestWalletDir()
	if err != nil {
		return err
	}
	defer cleanup()
	api := newSelfTestApi()
	message := selfTestNetworkConsentMessage(selfTestNetworkId, selfTestAliceKeyHex, 101, 100+consentEpochCount, nil)
	api.responses["GET /sn/epoch"] = []byte(`{"epoch":100,"start_block":1}`)
	consentResponse, _ := json.Marshal(map[string]string{"message": message})
	api.responses["POST /sn/wallet/network-consent"] = consentResponse
	api.responses["POST /sn/wallet"] = []byte(`{"mapping_hash":"` + strings.Repeat("d", 64) + `","mapping_generation":1}`)
	out := &bytes.Buffer{}
	tool, err := newWalletTool(selfTestAliceSs58, walletDir, api.transport, out)
	if err != nil {
		return err
	}
	tool.networkId = selfTestNetworkId
	if err := tool.NetworkChallenge(); err != nil {
		return err
	}
	// the request names the coldkey and the next 65,536 epochs, and no client
	var consentRequest map[string]any
	if len(api.requests) != 2 || api.requests[1].path != "/sn/wallet/network-consent" || json.Unmarshal(api.requests[1].body, &consentRequest) != nil {
		return errors.New("network challenge requests differ")
	}
	if _, named := consentRequest["client_id"]; named || consentRequest["coldkey_ss58"] != selfTestAliceSs58 || consentRequest["from_epoch"] != float64(101) || consentRequest["through_epoch"] != float64(100+consentEpochCount) {
		return fmt.Errorf("network consent request %v", consentRequest)
	}
	path := filepath.Join(walletDir, networkConsentFile)
	saved, err := readPrivateFile(path)
	if err != nil || string(saved) != message {
		return fmt.Errorf("saved network consent message differs (%v)", err)
	}
	signature := strings.Repeat("cd", 64)
	if err := tool.NetworkAccept(signature); err != nil {
		return err
	}
	var acceptRequest map[string]any
	if len(api.requests) != 3 || api.requests[2].path != "/sn/wallet" || json.Unmarshal(api.requests[2].body, &acceptRequest) != nil {
		return errors.New("network accept requests differ")
	}
	if _, named := acceptRequest["client_id"]; named || acceptRequest["message"] != message || acceptRequest["signature"] != "0x"+signature || acceptRequest["coldkey_ss58"] != selfTestAliceSs58 {
		return fmt.Errorf("network accept request %v", acceptRequest)
	}
	if !strings.Contains(out.String(), "mapped network "+selfTestNetworkId) {
		return fmt.Errorf("network accept output %q", out.String())
	}
	if _, err := readPrivateFile(filepath.Join(walletDir, networkConsentReceiptFile)); err != nil {
		return fmt.Errorf("network receipt not saved (%v)", err)
	}
	// a message for another network, coldkey or interval, a per-provider
	// message, a client field and another scope are refused before saving
	providerMessage := selfTestConsentMessage(selfTestClientId, selfTestAliceKeyHex, 101, 100+consentEpochCount)
	for _, wrongMessage := range []string{
		selfTestNetworkConsentMessage("88888888-8888-8888-8888-888888888888", selfTestAliceKeyHex, 101, 100+consentEpochCount, nil),
		selfTestNetworkConsentMessage(selfTestNetworkId, strings.Repeat("0", 64), 101, 100+consentEpochCount, nil),
		selfTestNetworkConsentMessage(selfTestNetworkId, selfTestAliceKeyHex, 102, 101+consentEpochCount, nil),
		selfTestNetworkConsentMessage(selfTestNetworkId, selfTestAliceKeyHex, 101, 100+consentEpochCount, map[string]any{"client_id": [16]byte{1}}),
		selfTestNetworkConsentMessage(selfTestNetworkId, selfTestAliceKeyHex, 101, 100+consentEpochCount, map[string]any{"scope": "provider"}),
		providerMessage,
		networkConsentPrefix + strings.TrimPrefix(providerMessage, consentPrefix),
	} {
		response, _ := json.Marshal(map[string]string{"message": wrongMessage})
		api.responses["POST /sn/wallet/network-consent"] = response
		if err := tool.NetworkChallenge(); err == nil {
			return fmt.Errorf("a network consent message that differs from the request was accepted: %q", wrongMessage)
		}
	}
	saved, err = readPrivateFile(path)
	if err != nil || string(saved) != message {
		return errors.New("a refused network message replaced the saved one")
	}
	// the per-provider accept never submits a network message
	if err := writePrivateFile(tool.consentPath(selfTestClientId), []byte(message)); err != nil {
		return err
	}
	requests := len(api.requests)
	if err := tool.Accept(selfTestClientId, signature); err == nil || !strings.Contains(err.Error(), "provider wallet mapping line") || len(api.requests) != requests {
		return fmt.Errorf("a network consent was submitted as a per-provider consent (%v)", err)
	}
	return nil
}

// Challenge saves the exact message for the requested client and coldkey; accept submits it.
func checkChallengeAndAccept() error {
	walletDir, cleanup, err := selfTestWalletDir()
	if err != nil {
		return err
	}
	defer cleanup()
	api := newSelfTestApi()
	message := selfTestConsentMessage(selfTestClientId, selfTestAliceKeyHex, 101, 100+consentEpochCount)
	api.responses["GET /sn/epoch"] = []byte(`{"epoch":100,"start_block":1}`)
	consentResponse, _ := json.Marshal(map[string]string{"message": message})
	api.responses["POST /sn/wallet/consent"] = consentResponse
	api.responses["POST /sn/wallet"] = []byte(`{"mapping_hash":"` + strings.Repeat("c", 64) + `","mapping_generation":1}`)
	out := &bytes.Buffer{}
	tool, err := newWalletTool(selfTestAliceSs58, walletDir, api.transport, out)
	if err != nil {
		return err
	}
	if err := tool.Challenge(selfTestClientId); err != nil {
		return err
	}
	// the consent request names the client, the coldkey and the next 65,536 epochs
	var consentRequest map[string]any
	if len(api.requests) != 2 || json.Unmarshal(api.requests[1].body, &consentRequest) != nil {
		return errors.New("challenge requests differ")
	}
	if consentRequest["client_id"] != selfTestClientId || consentRequest["coldkey_ss58"] != selfTestAliceSs58 || consentRequest["from_epoch"] != float64(101) || consentRequest["through_epoch"] != float64(100+consentEpochCount) {
		return fmt.Errorf("consent request %v", consentRequest)
	}
	// the exact message bytes are saved privately for the signer
	path := filepath.Join(walletDir, "consent-"+selfTestClientId+".txt")
	saved, err := readPrivateFile(path)
	if err != nil || string(saved) != message {
		return fmt.Errorf("saved consent message differs (%v)", err)
	}
	if runtime.GOOS != "windows" {
		if info, err := os.Stat(path); err != nil || info.Mode().Perm() != 0o600 {
			return errors.New("the consent message is not private")
		}
	}

	signature := strings.Repeat("ab", 64)
	if err := tool.Accept(selfTestClientId, signature); err != nil {
		return err
	}
	var acceptRequest map[string]any
	if len(api.requests) != 3 || json.Unmarshal(api.requests[2].body, &acceptRequest) != nil {
		return errors.New("accept requests differ")
	}
	if acceptRequest["message"] != message || acceptRequest["signature"] != "0x"+signature || acceptRequest["client_id"] != selfTestClientId || acceptRequest["coldkey_ss58"] != selfTestAliceSs58 {
		return fmt.Errorf("accept request %v", acceptRequest)
	}
	if !strings.Contains(out.String(), "mapped provider client "+selfTestClientId) {
		return fmt.Errorf("accept output %q", out.String())
	}
	if _, err := readPrivateFile(filepath.Join(walletDir, "consent-"+selfTestClientId+".accepted.json")); err != nil {
		return fmt.Errorf("receipt not saved (%v)", err)
	}

	// a message for another client or coldkey is refused before saving
	for _, wrongMessage := range []string{
		selfTestConsentMessage("22222222-2222-2222-2222-222222222222", selfTestAliceKeyHex, 101, 100+consentEpochCount),
		selfTestConsentMessage(selfTestClientId, strings.Repeat("0", 64), 101, 100+consentEpochCount),
		selfTestConsentMessage(selfTestClientId, selfTestAliceKeyHex, 102, 101+consentEpochCount),
		strings.TrimPrefix(message, consentPrefix),
	} {
		response, _ := json.Marshal(map[string]string{"message": wrongMessage})
		api.responses["POST /sn/wallet/consent"] = response
		if err := tool.Challenge(selfTestClientId); err == nil {
			return errors.New("a consent message that differs from the request was accepted")
		}
	}
	if _, err := newWalletTool(selfTestBobSs58, "relative/dir", api.transport, out); err == nil {
		return errors.New("a relative wallet directory was accepted")
	}
	if err := tool.Challenge("not-a-client-id"); err == nil {
		return errors.New("an invalid client id was accepted")
	}
	return nil
}

// Refusals and unconfirmed answers are errors that name the server's reason.
func checkRefusedConsent() error {
	walletDir, cleanup, err := selfTestWalletDir()
	if err != nil {
		return err
	}
	defer cleanup()
	api := newSelfTestApi()
	message := selfTestConsentMessage(selfTestClientId, selfTestAliceKeyHex, 101, 100+consentEpochCount)
	if err := writePrivateFile(filepath.Join(walletDir, "consent-"+selfTestClientId+".txt"), []byte(message)); err != nil {
		return err
	}
	tool, err := newWalletTool(selfTestAliceSs58, walletDir, api.transport, &bytes.Buffer{})
	if err != nil {
		return err
	}
	// a coded refusal names its code
	api.responses["POST /sn/wallet"] = []byte(`{"error":{"code":"signature_mismatch","message":"The signature does not match this coldkey address."}}`)
	if err := tool.Accept(selfTestClientId, strings.Repeat("ab", 64)); err == nil || !strings.Contains(err.Error(), "signature_mismatch") {
		return fmt.Errorf("coded refusal reported as %v", err)
	}
	// an http failure reports the server's text on one line
	api.responses["POST /sn/wallet"] = []byte("original wallet mapping\nauthority is unavailable\n")
	api.statuses["POST /sn/wallet"] = http.StatusInternalServerError
	if err := tool.Accept(selfTestClientId, strings.Repeat("ab", 64)); err == nil || !strings.Contains(err.Error(), "http 500: original wallet mapping authority is unavailable") {
		return fmt.Errorf("http failure reported as %v", err)
	}
	// an unconfirmed mapping is not success
	api.statuses["POST /sn/wallet"] = http.StatusOK
	api.responses["POST /sn/wallet"] = []byte(`{}`)
	if err := tool.Accept(selfTestClientId, strings.Repeat("ab", 64)); err == nil {
		return errors.New("an unconfirmed mapping was reported as success")
	}
	return nil
}

// Show lists the network wallet and each provider client's wallet.
func checkShowWallets() error {
	api := newSelfTestApi()
	api.responses["GET /sn/wallet"] = []byte(`{"wallet":{"coldkey_ss58":"` + selfTestAliceSs58 + `","set_at_millis":1},"wallets":[{"coldkey_ss58":"` + selfTestAliceSs58 + `","set_at_millis":1},{"coldkey_ss58":"` + selfTestAliceSs58 + `","client_id":"` + selfTestClientId + `","set_at_millis":2}]}`)
	out := &bytes.Buffer{}
	if err := showWallets(api.transport, out); err != nil {
		return err
	}
	expected := "network wallet: " + selfTestAliceSs58 + "\nprovider client " + selfTestClientId + ": " + selfTestAliceSs58 + "\n"
	if out.String() != expected {
		return fmt.Errorf("show output %q", out.String())
	}
	// consents carry their scope and epochs
	api.responses["GET /sn/wallet"] = []byte(`{"wallets":[{"coldkey_ss58":"` + selfTestAliceSs58 + `","consent_scope":"network","from_epoch":101,"through_epoch":65636,"set_at_millis":1},{"coldkey_ss58":"` + selfTestBobSs58 + `","client_id":"` + selfTestClientId + `","consent_scope":"provider","from_epoch":102,"through_epoch":65637,"set_at_millis":2}]}`)
	out.Reset()
	expected = "network consent: " + selfTestAliceSs58 + " (epochs 101-65636, every provider client without its own consent)\nprovider client " + selfTestClientId + ": " + selfTestBobSs58 + " (consent, epochs 102-65637)\n"
	if err := showWallets(api.transport, out); err != nil || out.String() != expected {
		return fmt.Errorf("consent show output %q (%v)", out.String(), err)
	}
	api.responses["GET /sn/wallet"] = []byte(`{"wallets":[]}`)
	out.Reset()
	if err := showWallets(api.transport, out); err != nil || out.String() != "no payout wallet is mapped in this network\n" {
		return fmt.Errorf("empty show output %q (%v)", out.String(), err)
	}
	return nil
}
