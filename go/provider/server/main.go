// SERVER ONLY: the backend tool that maps a provider client of the developer's
// network to the fixed Bittensor payout coldkey through the signed consent
// flow (PROVIDER_CONTRACT.md, "Payout wallet mapping"):
//
//	wallet challenge <client-id>               POST /sn/wallet/consent; saves the exact message to sign
//	wallet accept <client-id> <signature-hex>  POST /sn/wallet with the saved message and its signature
//	wallet show                                GET /sn/wallet; the network's mapped wallets
//	wallet --self-test                         credential-free checks
//
// The coldkey owner signs the saved message offline with their own wallet
// tool. The coldkey's secret never reaches this tool, the backend or the app.
//
// Settings: URNETWORK_ROOT_JWT (the network credential, backend only),
// URNETWORK_PAYOUT_COLDKEY (the coldkey's ss58 address), URNETWORK_WALLET_DIR
// (an absolute, existing, private directory for the consent messages) and
// optionally URNETWORK_API_URL (default https://api.bringyour.com). The root
// JWT is never printed.
package main

import (
	"bytes"
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

// The server issues consent messages with this first line.
const consentPrefix = "Approve URnetwork provider wallet mapping\n"

// A consent covers at most 65,536 epochs (about 1,256 years of 7-day epochs).
const consentEpochCount = 65536

// Bittensor addresses use the generic substrate ss58 prefix.
const bittensorSs58Prefix = 42

// the largest response or file the tool reads
const byteLimit = 1024 * 1024

const httpTimeout = 30 * time.Second

var clientIdPattern = regexp.MustCompile(`^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$`)
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

// The receipt saved after the server accepts a consent.
type consentReceipt struct {
	ClientId          string `json:"client_id"`
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
		return errors.New("usage: wallet challenge <client-id> | accept <client-id> <signature-hex> | show | --self-test")
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
	tool, err := newWalletTool(os.Getenv("URNETWORK_PAYOUT_COLDKEY"), os.Getenv("URNETWORK_WALLET_DIR"), transport, os.Stdout)
	if err != nil {
		return err
	}
	switch {
	case args[0] == "challenge" && len(args) == 2:
		return tool.Challenge(args[1])
	case args[0] == "accept" && len(args) == 3:
		return tool.Accept(args[1], args[2])
	default:
		return errors.New("usage: wallet challenge <client-id> | accept <client-id> <signature-hex> | show | --self-test")
	}
}

// Validates the coldkey and the private consent directory.
func newWalletTool(coldkeySs58 string, walletDir string, transport apiTransport, out io.Writer) (*walletTool, error) {
	coldkey, err := decodeSs58(coldkeySs58)
	if err != nil {
		return nil, fmt.Errorf("URNETWORK_PAYOUT_COLDKEY: %w", err)
	}
	if !filepath.IsAbs(walletDir) {
		return nil, errors.New("set URNETWORK_WALLET_DIR to an absolute, existing, private directory")
	}
	info, err := os.Stat(walletDir)
	if err != nil || !info.IsDir() {
		return nil, errors.New("URNETWORK_WALLET_DIR must be an existing directory")
	}
	if runtime.GOOS != "windows" && info.Mode().Perm()&0o077 != 0 {
		return nil, errors.New("URNETWORK_WALLET_DIR must be private to its owner (chmod 700)")
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
	if !clientIdPattern.MatchString(clientId) {
		return errors.New("expected a provider client id (lowercase uuid)")
	}
	status, body, err := self.transport(http.MethodGet, "/sn/epoch", nil)
	if err := apiResult(status, body, err); err != nil {
		return fmt.Errorf("read the subnet epoch: %w", err)
	}
	var epoch struct {
		Epoch *uint64 `json:"epoch"`
	}
	if err := json.Unmarshal(body, &epoch); err != nil || epoch.Epoch == nil {
		return errors.New("read the subnet epoch: unexpected response")
	}
	fromEpoch := *epoch.Epoch + 1
	throughEpoch := *epoch.Epoch + consentEpochCount
	request, err := json.Marshal(map[string]any{
		"client_id":     clientId,
		"coldkey_ss58":  self.coldkeySs58,
		"from_epoch":    fromEpoch,
		"through_epoch": throughEpoch,
	})
	if err != nil {
		return err
	}
	status, body, err = self.transport(http.MethodPost, "/sn/wallet/consent", request)
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
	if !clientIdPattern.MatchString(clientId) {
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
	status, body, err := self.transport(http.MethodPost, "/sn/wallet", request)
	if err := apiResult(status, body, err); err != nil {
		return fmt.Errorf("submit the consent: %w", err)
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
		return errors.New("submit the consent: unexpected response")
	}
	if result.Error != nil {
		if result.Error.Code != "" {
			return fmt.Errorf("the server refused the consent (%s): %s", result.Error.Code, oneLine(result.Error.Message))
		}
		return fmt.Errorf("the server refused the consent: %s", oneLine(result.Error.Message))
	}
	if len(result.MappingHash) != 64 || result.MappingGeneration == 0 {
		return errors.New("submit the consent: the server did not confirm a mapping")
	}
	receipt, err := json.Marshal(&consentReceipt{
		ClientId:          clientId,
		ColdkeySs58:       self.coldkeySs58,
		Message:           string(message),
		Signature:         normalizedSignature,
		MappingHash:       result.MappingHash,
		MappingGeneration: result.MappingGeneration,
	})
	if err != nil {
		return err
	}
	if err := writePrivateFile(filepath.Join(self.walletDir, "consent-"+clientId+".accepted.json"), receipt); err != nil {
		return err
	}
	fmt.Fprintf(self.out, "mapped provider client %s to %s (mapping generation %d, hash %s)\n", clientId, self.coldkeySs58, result.MappingGeneration, result.MappingHash)
	return nil
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

// Prints the network wallet and every provider client's wallet.
func showWallets(transport apiTransport, out io.Writer) error {
	status, body, err := transport(http.MethodGet, "/sn/wallet", nil)
	if err := apiResult(status, body, err); err != nil {
		return fmt.Errorf("read the wallets: %w", err)
	}
	var result struct {
		Wallets []struct {
			ColdkeySs58 string `json:"coldkey_ss58"`
			ClientId    string `json:"client_id"`
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
		if wallet.ClientId == "" {
			fmt.Fprintf(out, "network wallet: %s\n", wallet.ColdkeySs58)
		} else {
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
