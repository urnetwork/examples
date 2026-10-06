// The credential-free self-test: ss58 decoding, consent checks, signature
// form, and the challenge, accept and show requests against a mock api. No
// network or credentials are used.
package main

import (
	"bytes"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
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

// One request seen by the mock api.
type selfTestRequest struct {
	method string
	path   string
	body   []byte
}

// A mock api with canned responses by method and path.
type selfTestApi struct {
	responses map[string][]byte
	statuses  map[string]int
	requests  []*selfTestRequest
}

// A mock api without responses.
func newSelfTestApi() *selfTestApi {
	return &selfTestApi{
		responses: map[string][]byte{},
		statuses:  map[string]int{},
	}
}

// Records the request and returns the canned response.
func (self *selfTestApi) transport(method string, path string, body []byte) (int, []byte, error) {
	self.requests = append(self.requests, &selfTestRequest{method: method, path: path, body: bytes.Clone(body)})
	key := method + " " + path
	response, ok := self.responses[key]
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

// Runs every check and returns the first failure.
func runSelfTest() error {
	checks := []func() error{
		checkDecodeSs58,
		checkNormalizeSignature,
		checkApiOrigin,
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
	api.responses["GET /sn/wallet"] = []byte(`{"wallets":[]}`)
	out.Reset()
	if err := showWallets(api.transport, out); err != nil || out.String() != "no payout wallet is mapped in this network\n" {
		return fmt.Errorf("empty show output %q (%v)", out.String(), err)
	}
	return nil
}
