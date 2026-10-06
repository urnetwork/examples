// SERVER ONLY: the backend tool that provisions the developer's provider
// installs (provision.go) and maps them to the fixed Bittensor payout coldkey
// through the signed consent flow (PROVIDER_CONTRACT.md, "Payout wallet
// mapping"). A network consent covers every provider client of the network
// with one signature; a per-provider consent covers one client and takes
// precedence over the network consent:
//
//	wallet provision <installation-key> <client-jwt-file>  POST /network/auth-client; writes the installation's client JWT
//	wallet network-challenge                               POST /sn/wallet/network-consent; saves the exact message to sign
//	wallet network-accept <signature-hex>                  POST /sn/wallet with the saved network message and its signature
//	wallet challenge <client-id>                           POST /sn/wallet/consent; saves the exact message to sign
//	wallet accept <client-id> <signature-hex>              POST /sn/wallet with the saved message and its signature
//	wallet show                                            GET /sn/wallet; the network's mapped wallets
//	wallet --self-test                                     credential-free checks
//
// The coldkey owner signs the saved message offline with their own wallet
// tool. The coldkey's secret never reaches this tool, the backend or the app.
//
// Settings: URNETWORK_ROOT_JWT (the network credential, backend only),
// URNETWORK_PAYOUT_COLDKEY (the coldkey's ss58 address, not needed to
// provision), URNETWORK_WALLET_DIR (an absolute, existing, private directory
// for the consent messages and the provider map providers.json) and optionally
// URNETWORK_API_URL (default https://api.bringyour.com). The root JWT and the
// client JWTs are never printed.
package main

import (
	"bytes"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"math/big"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"regexp"
	"runtime"
	"strings"
	"time"
)

const defaultApiUrl = "https://api.bringyour.com"

// The server issues per-provider consent messages with this first line.
const consentPrefix = "Approve URnetwork provider wallet mapping\n"

// The server issues network consent messages with this first line.
const networkConsentPrefix = "Approve URnetwork network wallet mapping\n"

// the scope field of a network consent message
const networkConsentScope = "network"

// the saved network consent message and its receipt in the wallet directory
const networkConsentFile = "network-consent.txt"
const networkConsentReceiptFile = "network-consent.accepted.json"

// the command forms
const usage = "usage: wallet provision <installation-key> <client-jwt-file> | network-challenge | network-accept <signature-hex> | challenge <client-id> | accept <client-id> <signature-hex> | show | --self-test"

// A consent covers at most 65,536 epochs (about 1,256 years of 7-day epochs).
const consentEpochCount = 65536

// Bittensor addresses use the generic substrate ss58 prefix.
const bittensorSs58Prefix = 42

// the largest response or file the tool reads
const byteLimit = 1024 * 1024

const httpTimeout = 30 * time.Second

// a lowercase uuid, as client and network ids are written
var uuidPattern = regexp.MustCompile(`^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$`)
var signaturePattern = regexp.MustCompile(`^[0-9a-fA-F]{128}$`)

// Sends one api request: the method, the path below the api origin and an
// optional json body. Returns the http status and the bounded response body.
type apiTransport func(method string, path string, body []byte) (int, []byte, error)

// The settings and transport of one command.
type walletTool struct {
	coldkeySs58 string
	// the coldkey's 32-byte public key, decoded from coldkeySs58
	coldkey   [32]byte
	walletDir string
	// the network of the root JWT, which a network consent must name
	networkId string
	transport apiTransport
	out       io.Writer
}

// The fields of a consent message that this tool checks before the coldkey
// owner signs it. The server and the subnet verify the full statement.
type consentStatement struct {
	ClientId     [16]byte `json:"client_id"`
	Coldkey      [32]byte `json:"coldkey"`
	FromEpoch    uint64   `json:"from_epoch"`
	ThroughEpoch uint64   `json:"through_epoch"`
	ExpiresAt    int64    `json:"expires_at"`
}

// The fields of a network consent message that this tool checks before the
// coldkey owner signs it. A network consent names no client.
type networkConsentStatement struct {
	Scope        string   `json:"scope"`
	NetworkId    [16]byte `json:"network_id"`
	Coldkey      [32]byte `json:"coldkey"`
	FromEpoch    uint64   `json:"from_epoch"`
	ThroughEpoch uint64   `json:"through_epoch"`
	ExpiresAt    int64    `json:"expires_at"`
}

// The receipt saved after the server accepts a consent. ClientId is empty for
// a network consent.
type consentReceipt struct {
	ClientId          string `json:"client_id,omitempty"`
	NetworkId         string `json:"network_id,omitempty"`
	ColdkeySs58       string `json:"coldkey_ss58"`
	Message           string `json:"message"`
	Signature         string `json:"signature"`
	MappingHash       string `json:"mapping_hash"`
	MappingGeneration uint64 `json:"mapping_generation"`
}

// Exits non-zero when the command fails.
func main() {
	if err := run(os.Args[1:]); err != nil {
		fmt.Fprintf(os.Stderr, "wallet failed: %v\n", err)
		os.Exit(1)
	}
}

// Runs one command.
func run(args []string) error {
	if len(args) == 1 && args[0] == "--self-test" {
		if err := runSelfTest(); err != nil {
			return err
		}
		fmt.Println("wallet self-test passed")
		return nil
	}
	if len(args) == 0 {
		return errors.New(usage)
	}
	apiUrl := os.Getenv("URNETWORK_API_URL")
	if apiUrl == "" {
		apiUrl = defaultApiUrl
	}
	origin, err := apiOrigin(apiUrl)
	if err != nil {
		return err
	}
	rootJwt := os.Getenv("URNETWORK_ROOT_JWT")
	if rootJwt == "" || strings.ContainsAny(rootJwt, " \t\r\n") {
		return errors.New("set URNETWORK_ROOT_JWT to the network credential from the backend secret store")
	}
	transport := newHttpTransport(origin, rootJwt)
	if args[0] == "show" && len(args) == 1 {
		return showWallets(transport, os.Stdout)
	}
	if args[0] == "provision" && len(args) == 3 {
		// provider installs are created with the network credential only
		if _, err := jwtNetworkId(rootJwt); err != nil {
			return err
		}
		provisioner, err := newProviderProvisioner(os.Getenv("URNETWORK_WALLET_DIR"), transport, os.Stdout)
		if err != nil {
			return err
		}
		return provisioner.Provision(args[1], args[2])
	}
	tool, err := newWalletTool(os.Getenv("URNETWORK_PAYOUT_COLDKEY"), os.Getenv("URNETWORK_WALLET_DIR"), transport, os.Stdout)
	if err != nil {
		return err
	}
	switch {
	case (args[0] == "network-challenge" && len(args) == 1) || (args[0] == "network-accept" && len(args) == 2):
		tool.networkId, err = jwtNetworkId(rootJwt)
		if err != nil {
			return err
		}
		if args[0] == "network-challenge" {
			return tool.NetworkChallenge()
		}
		return tool.NetworkAccept(args[1])
	case args[0] == "challenge" && len(args) == 2:
		return tool.Challenge(args[1])
	case args[0] == "accept" && len(args) == 3:
		return tool.Accept(args[1], args[2])
	default:
		return errors.New(usage)
	}
}

// The network_id claim of a network JWT. The claims are read only to check
// the consent message and the credential's kind; the server verifies the
// token. A client JWT (with a client_id claim) can neither request a network
// consent nor provision clients.
func jwtNetworkId(jwt string) (string, error) {
	var claims struct {
		NetworkId string  `json:"network_id"`
		ClientId  *string `json:"client_id"`
	}
	if err := decodeJwtClaims(jwt, &claims); err != nil {
		return "", errors.New("URNETWORK_ROOT_JWT is not a JWT")
	}
	if claims.ClientId != nil {
		return "", errors.New("URNETWORK_ROOT_JWT is a client JWT; this command needs the network JWT")
	}
	if !uuidPattern.MatchString(claims.NetworkId) {
		return "", errors.New("URNETWORK_ROOT_JWT has no network_id claim")
	}
	return claims.NetworkId, nil
}

// Decodes the claims of a JWT into claims, without verifying the token.
func decodeJwtClaims(jwt string, claims any) error {
	parts := strings.Split(jwt, ".")
	if len(parts) != 3 {
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

// The wallet directory must be absolute, existing and, on POSIX, private to
// its owner. It holds the consent messages, their receipts and providers.json.
func checkWalletDir(walletDir string) error {
	if !filepath.IsAbs(walletDir) {
		return errors.New("set URNETWORK_WALLET_DIR to an absolute, existing, private directory")
	}
	info, err := os.Stat(walletDir)
	if err != nil || !info.IsDir() {
		return errors.New("URNETWORK_WALLET_DIR must be an existing directory")
	}
	if runtime.GOOS != "windows" && info.Mode().Perm()&0o077 != 0 {
		return errors.New("URNETWORK_WALLET_DIR must be private to its owner (chmod 700)")
	}
	return nil
}

// Validates the coldkey and the private consent directory.
func newWalletTool(coldkeySs58 string, walletDir string, transport apiTransport, out io.Writer) (*walletTool, error) {
	coldkey, err := decodeSs58(coldkeySs58)
	if err != nil {
		return nil, fmt.Errorf("URNETWORK_PAYOUT_COLDKEY: %w", err)
	}
	if err := checkWalletDir(walletDir); err != nil {
		return nil, err
	}
	return &walletTool{
		coldkeySs58: coldkeySs58,
		coldkey:     coldkey,
		walletDir:   walletDir,
		transport:   transport,
		out:         out,
	}, nil
}

// Requests a consent message for one provider client and saves its exact
// bytes for the coldkey owner to sign.
func (self *walletTool) Challenge(clientId string) error {
	if !uuidPattern.MatchString(clientId) {
		return errors.New("expected a provider client id (lowercase uuid)")
	}
	fromEpoch, throughEpoch, err := self.consentEpochs()
	if err != nil {
		return err
	}
	request, err := json.Marshal(map[string]any{
		"client_id":     clientId,
		"coldkey_ss58":  self.coldkeySs58,
		"from_epoch":    fromEpoch,
		"through_epoch": throughEpoch,
	})
	if err != nil {
		return err
	}
	status, body, err := self.transport(http.MethodPost, "/sn/wallet/consent", request)
	if err := apiResult(status, body, err); err != nil {
		return fmt.Errorf("request the consent message: %w", err)
	}
	var challenge struct {
		Message string `json:"message"`
	}
	if err := json.Unmarshal(body, &challenge); err != nil || challenge.Message == "" {
		return errors.New("request the consent message: unexpected response")
	}
	statement, err := self.checkConsent(challenge.Message, clientId)
	if err != nil {
		return err
	}
	if statement.FromEpoch != fromEpoch || statement.ThroughEpoch != throughEpoch {
		return errors.New("the consent message names other epochs than requested")
	}
	path := self.consentPath(clientId)
	if err := writePrivateFile(path, []byte(challenge.Message)); err != nil {
		return err
	}
	fmt.Fprintf(
		self.out,
		"saved the consent message for provider client %s to %s\nsign its exact bytes with coldkey %s before %s, then run: wallet accept %s <signature-hex>\n",
		clientId,
		path,
		self.coldkeySs58,
		time.Unix(statement.ExpiresAt, 0).UTC().Format(time.RFC3339),
		clientId,
	)
	return nil
}

// Submits the saved consent message with the coldkey owner's signature. A
// retry with the same signature replays the same consent.
func (self *walletTool) Accept(clientId string, signature string) error {
	if !uuidPattern.MatchString(clientId) {
		return errors.New("expected a provider client id (lowercase uuid)")
	}
	normalizedSignature, err := normalizeSignature(signature)
	if err != nil {
		return err
	}
	message, err := readPrivateFile(self.consentPath(clientId))
	if err != nil {
		return fmt.Errorf("read the saved consent message (run wallet challenge first): %w", err)
	}
	if _, err := self.checkConsent(string(message), clientId); err != nil {
		return err
	}
	request, err := json.Marshal(map[string]any{
		"coldkey_ss58": self.coldkeySs58,
		"client_id":    clientId,
		"message":      string(message),
		"signature":    normalizedSignature,
	})
	if err != nil {
		return err
	}
	mappingHash, mappingGeneration, err := self.submitConsent(request)
	if err != nil {
		return err
	}
	receipt, err := json.Marshal(&consentReceipt{
		ClientId:          clientId,
		ColdkeySs58:       self.coldkeySs58,
		Message:           string(message),
		Signature:         normalizedSignature,
		MappingHash:       mappingHash,
		MappingGeneration: mappingGeneration,
	})
	if err != nil {
		return err
	}
	if err := writePrivateFile(filepath.Join(self.walletDir, "consent-"+clientId+".accepted.json"), receipt); err != nil {
		return err
	}
	fmt.Fprintf(self.out, "mapped provider client %s to %s (mapping generation %d, hash %s)\n", clientId, self.coldkeySs58, mappingGeneration, mappingHash)
	return nil
}

// The next 65,536 epochs from the current epoch.
func (self *walletTool) consentEpochs() (uint64, uint64, error) {
	status, body, err := self.transport(http.MethodGet, "/sn/epoch", nil)
	if err := apiResult(status, body, err); err != nil {
		return 0, 0, fmt.Errorf("read the subnet epoch: %w", err)
	}
	var epoch struct {
		Epoch *uint64 `json:"epoch"`
	}
	if err := json.Unmarshal(body, &epoch); err != nil || epoch.Epoch == nil {
		return 0, 0, errors.New("read the subnet epoch: unexpected response")
	}
	return *epoch.Epoch + 1, *epoch.Epoch + consentEpochCount, nil
}

// Requests the network consent message, which covers every provider client
// of the network, and saves its exact bytes for the coldkey owner to sign.
func (self *walletTool) NetworkChallenge() error {
	fromEpoch, throughEpoch, err := self.consentEpochs()
	if err != nil {
		return err
	}
	request, err := json.Marshal(map[string]any{
		"coldkey_ss58":  self.coldkeySs58,
		"from_epoch":    fromEpoch,
		"through_epoch": throughEpoch,
	})
	if err != nil {
		return err
	}
	status, body, err := self.transport(http.MethodPost, "/sn/wallet/network-consent", request)
	if err := apiResult(status, body, err); err != nil {
		return fmt.Errorf("request the network consent message: %w", err)
	}
	var challenge struct {
		Message string `json:"message"`
	}
	if err := json.Unmarshal(body, &challenge); err != nil || challenge.Message == "" {
		return errors.New("request the network consent message: unexpected response")
	}
	statement, err := self.checkNetworkConsent(challenge.Message)
	if err != nil {
		return err
	}
	if statement.FromEpoch != fromEpoch || statement.ThroughEpoch != throughEpoch {
		return errors.New("the network consent message names other epochs than requested")
	}
	path := filepath.Join(self.walletDir, networkConsentFile)
	if err := writePrivateFile(path, []byte(challenge.Message)); err != nil {
		return err
	}
	fmt.Fprintf(
		self.out,
		"saved the network consent message for network %s to %s\nsign its exact bytes with coldkey %s before %s, then run: wallet network-accept <signature-hex>\n",
		self.networkId,
		path,
		self.coldkeySs58,
		time.Unix(statement.ExpiresAt, 0).UTC().Format(time.RFC3339),
	)
	return nil
}

// Submits the saved network consent message with the coldkey owner's
// signature, without a client id. A retry replays the same consent.
func (self *walletTool) NetworkAccept(signature string) error {
	normalizedSignature, err := normalizeSignature(signature)
	if err != nil {
		return err
	}
	message, err := readPrivateFile(filepath.Join(self.walletDir, networkConsentFile))
	if err != nil {
		return fmt.Errorf("read the saved network consent message (run wallet network-challenge first): %w", err)
	}
	if _, err := self.checkNetworkConsent(string(message)); err != nil {
		return err
	}
	request, err := json.Marshal(map[string]any{
		"coldkey_ss58": self.coldkeySs58,
		"message":      string(message),
		"signature":    normalizedSignature,
	})
	if err != nil {
		return err
	}
	mappingHash, mappingGeneration, err := self.submitConsent(request)
	if err != nil {
		return err
	}
	receipt, err := json.Marshal(&consentReceipt{
		NetworkId:         self.networkId,
		ColdkeySs58:       self.coldkeySs58,
		Message:           string(message),
		Signature:         normalizedSignature,
		MappingHash:       mappingHash,
		MappingGeneration: mappingGeneration,
	})
	if err != nil {
		return err
	}
	if err := writePrivateFile(filepath.Join(self.walletDir, networkConsentReceiptFile), receipt); err != nil {
		return err
	}
	fmt.Fprintf(self.out, "mapped network %s to %s for every provider client without its own consent (mapping generation %d, hash %s)\n", self.networkId, self.coldkeySs58, mappingGeneration, mappingHash)
	return nil
}

// Checks that a network consent message names this network and coldkey and
// no client.
func (self *walletTool) checkNetworkConsent(message string) (*networkConsentStatement, error) {
	if !strings.HasPrefix(message, networkConsentPrefix) {
		return nil, errors.New("the consent message does not start with the network wallet mapping line")
	}
	raw := []byte(message[len(networkConsentPrefix):])
	var fields map[string]json.RawMessage
	if err := json.Unmarshal(raw, &fields); err != nil {
		return nil, errors.New("the consent message is not a network wallet mapping statement")
	}
	if _, ok := fields["client_id"]; ok {
		return nil, errors.New("the network consent message names a provider client")
	}
	statement := &networkConsentStatement{}
	if err := json.Unmarshal(raw, statement); err != nil || statement.Scope != networkConsentScope {
		return nil, errors.New("the consent message is not a network wallet mapping statement")
	}
	networkIdBytes, err := hex.DecodeString(strings.ReplaceAll(self.networkId, "-", ""))
	if err != nil || !bytes.Equal(statement.NetworkId[:], networkIdBytes) {
		return nil, errors.New("the network consent message names another network")
	}
	if statement.Coldkey != self.coldkey {
		return nil, errors.New("the consent message names another coldkey")
	}
	return statement, nil
}

// Posts a signed consent to POST /sn/wallet. Returns the confirmed mapping.
func (self *walletTool) submitConsent(request []byte) (string, uint64, error) {
	status, body, err := self.transport(http.MethodPost, "/sn/wallet", request)
	if err := apiResult(status, body, err); err != nil {
		return "", 0, fmt.Errorf("submit the consent: %w", err)
	}
	var result struct {
		Error *struct {
			Code    string `json:"code"`
			Message string `json:"message"`
		} `json:"error"`
		MappingHash       string `json:"mapping_hash"`
		MappingGeneration uint64 `json:"mapping_generation"`
	}
	if err := json.Unmarshal(body, &result); err != nil {
		return "", 0, errors.New("submit the consent: unexpected response")
	}
	if result.Error != nil {
		if result.Error.Code != "" {
			return "", 0, fmt.Errorf("the server refused the consent (%s): %s", result.Error.Code, oneLine(result.Error.Message))
		}
		return "", 0, fmt.Errorf("the server refused the consent: %s", oneLine(result.Error.Message))
	}
	if len(result.MappingHash) != 64 || result.MappingGeneration == 0 {
		return "", 0, errors.New("submit the consent: the server did not confirm a mapping")
	}
	return result.MappingHash, result.MappingGeneration, nil
}

// Checks that a consent message names this client and coldkey.
func (self *walletTool) checkConsent(message string, clientId string) (*consentStatement, error) {
	if !strings.HasPrefix(message, consentPrefix) {
		return nil, errors.New("the consent message does not start with the provider wallet mapping line")
	}
	statement := &consentStatement{}
	if err := json.Unmarshal([]byte(message[len(consentPrefix):]), statement); err != nil {
		return nil, errors.New("the consent message is not a provider wallet mapping statement")
	}
	clientIdBytes, err := hex.DecodeString(strings.ReplaceAll(clientId, "-", ""))
	if err != nil || !bytes.Equal(statement.ClientId[:], clientIdBytes) {
		return nil, errors.New("the consent message names another provider client")
	}
	if statement.Coldkey != self.coldkey {
		return nil, errors.New("the consent message names another coldkey")
	}
	return statement, nil
}

// The saved consent message of one provider client.
func (self *walletTool) consentPath(clientId string) string {
	return filepath.Join(self.walletDir, "consent-"+clientId+".txt")
}

// Prints the network consent, the network wallet and every provider client's
// wallet. Consents show the epochs they pay.
func showWallets(transport apiTransport, out io.Writer) error {
	status, body, err := transport(http.MethodGet, "/sn/wallet", nil)
	if err := apiResult(status, body, err); err != nil {
		return fmt.Errorf("read the wallets: %w", err)
	}
	var result struct {
		Wallets []struct {
			ColdkeySs58  string `json:"coldkey_ss58"`
			ClientId     string `json:"client_id"`
			ConsentScope string `json:"consent_scope"`
			FromEpoch    uint64 `json:"from_epoch"`
			ThroughEpoch uint64 `json:"through_epoch"`
		} `json:"wallets"`
		Error *struct {
			Message string `json:"message"`
		} `json:"error"`
	}
	if err := json.Unmarshal(body, &result); err != nil {
		return errors.New("read the wallets: unexpected response")
	}
	if result.Error != nil {
		return fmt.Errorf("read the wallets: %s", oneLine(result.Error.Message))
	}
	if len(result.Wallets) == 0 {
		fmt.Fprintln(out, "no payout wallet is mapped in this network")
		return nil
	}
	for _, wallet := range result.Wallets {
		switch {
		case wallet.ClientId == "" && wallet.ConsentScope == networkConsentScope:
			fmt.Fprintf(out, "network consent: %s (epochs %d-%d, every provider client without its own consent)\n", wallet.ColdkeySs58, wallet.FromEpoch, wallet.ThroughEpoch)
		case wallet.ClientId == "":
			fmt.Fprintf(out, "network wallet: %s\n", wallet.ColdkeySs58)
		case wallet.ConsentScope != "":
			fmt.Fprintf(out, "provider client %s: %s (consent, epochs %d-%d)\n", wallet.ClientId, wallet.ColdkeySs58, wallet.FromEpoch, wallet.ThroughEpoch)
		default:
			fmt.Fprintf(out, "provider client %s: %s\n", wallet.ClientId, wallet.ColdkeySs58)
		}
	}
	return nil
}

// An error for a failed request or a non-2xx status, with the server's
// message on one bounded line. Server messages carry no credentials.
func apiResult(status int, body []byte, err error) error {
	if err != nil {
		return err
	}
	if status < 200 || 300 <= status {
		return fmt.Errorf("http %d: %s", status, oneLine(string(body)))
	}
	return nil
}

// Collapses text to one line of at most 300 characters.
func oneLine(text string) string {
	text = strings.Join(strings.Fields(text), " ")
	if 300 < len(text) {
		text = text[:300] + "..."
	}
	return text
}

// The coldkey's sr25519 signature as 0x-prefixed lowercase hex of 64 bytes.
func normalizeSignature(signature string) (string, error) {
	signature = strings.TrimSpace(signature)
	signature = strings.TrimPrefix(strings.TrimPrefix(signature, "0x"), "0X")
	if !signaturePattern.MatchString(signature) {
		return "", errors.New("expected the coldkey's 64-byte sr25519 signature as 128 hex characters (0x optional)")
	}
	return "0x" + strings.ToLower(signature), nil
}

// The 32-byte public key of a Bittensor ss58 address: base58 of the prefix
// byte, the key and a two-byte checksum. The checksum (blake2b) is left to
// the server, which validates every address it accepts.
func decodeSs58(address string) ([32]byte, error) {
	var key [32]byte
	const alphabet = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
	if address == "" {
		return key, errors.New("set the coldkey's ss58 address")
	}
	value := new(big.Int)
	for _, character := range address {
		index := strings.IndexRune(alphabet, character)
		if index < 0 {
			return key, errors.New("not an ss58 address")
		}
		value.Mul(value, big.NewInt(58))
		value.Add(value, big.NewInt(int64(index)))
	}
	decoded := value.Bytes()
	// leading '1' characters encode leading zero bytes
	for _, character := range address {
		if character != '1' {
			break
		}
		decoded = append([]byte{0}, decoded...)
	}
	if len(decoded) != 1+32+2 || decoded[0] != bittensorSs58Prefix {
		return key, errors.New("not a Bittensor (prefix 42) ss58 address")
	}
	copy(key[:], decoded[1:33])
	return key, nil
}

// An api transport over https with the root credential. Redirects are not
// followed, so the credential is only sent to the configured origin.
func newHttpTransport(origin string, rootJwt string) apiTransport {
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
		request.Header.Set("Authorization", "Bearer "+rootJwt)
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
		return "", err
	}
	hostName := parsed.Hostname()
	loopback := hostName == "localhost" || hostName == "127.0.0.1" || hostName == "::1"
	if hostName == "" || parsed.User != nil || (parsed.Path != "" && parsed.Path != "/") || parsed.RawQuery != "" || parsed.Fragment != "" || (parsed.Scheme != "https" && !(parsed.Scheme == "http" && loopback)) {
		return "", errors.New("URNETWORK_API_URL must be an https origin, or explicit loopback http for a mock")
	}
	return strings.TrimSuffix(apiUrl, "/"), nil
}

// Reads a regular, private file of bounded size.
func readPrivateFile(path string) ([]byte, error) {
	info, err := os.Lstat(path)
	if err != nil {
		return nil, err
	}
	if !info.Mode().IsRegular() || byteLimit < info.Size() {
		return nil, errors.New("not a regular file of bounded size")
	}
	if runtime.GOOS != "windows" && info.Mode().Perm()&0o077 != 0 {
		return nil, errors.New("file must be private to its owner (chmod 600)")
	}
	return os.ReadFile(path)
}

// Replaces a file atomically with owner-only permissions.
func writePrivateFile(path string, data []byte) (returnErr error) {
	file, err := os.CreateTemp(filepath.Dir(path), "."+filepath.Base(path)+".*")
	if err != nil {
		return err
	}
	tempPath := file.Name()
	defer func() {
		if returnErr != nil {
			os.Remove(tempPath)
		}
	}()
	if err := file.Chmod(0o600); err != nil {
		file.Close()
		return err
	}
	if _, err := file.Write(data); err != nil {
		file.Close()
		return err
	}
	if err := file.Sync(); err != nil {
		file.Close()
		return err
	}
	if err := file.Close(); err != nil {
		return err
	}
	return os.Rename(tempPath, path)
}
