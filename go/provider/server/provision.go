// Provisioning of provider installs, backend only (PROVIDER_CONTRACT.md,
// "Backend: provision provider clients"). Each installation gets one provider
// install: a top-level client created with POST /network/auth-client and
// "provide_intent": true. The server stores the flag with the new client for
// its life: the client never joins the network's peer list, does not count
// toward the top-level client cap, and is judged as a provider when it
// connects providing publicly. A reissue sends the stored client id without
// the flag, which the server ignores on a reissue.
//
// providers.json in the wallet directory maps installation keys to client ids,
// in the shape of the language allocators' maps (INTEGRATION_CONTRACT.md). A
// provision holds providers.json.lock while it runs, so two provisions of one
// installation cannot create two clients. The client JWT goes only to a
// private file, never to the output.
package main

import (
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"io/fs"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
)

// the provider map in the wallet directory
const providerMapFile = "providers.json"

const providerMapVersion = 1

// A provider install's device is described by its installation key, with the
// spec of the provider example app.
const providerDescriptionPrefix = "provider "
const providerDeviceSpec = "urnetwork-examples/go-provider"

// The server answers a reissue of a deactivated (30 days without connecting)
// or removed client with this message.
const clientDoesNotExistMessage = "Client does not exist."

// An installation key, in the form of the allocators' service keys with the
// installation appended, for example user:alice:laptop-1.
var installationKeyPattern = regexp.MustCompile(`^user:[A-Za-z0-9][A-Za-z0-9_.:@-]{0,122}$`)

// providers.json: the provider install of each installation.
type providerMap struct {
	Version                  int               `json:"version"`
	InstallationKeyClientIds map[string]string `json:"clients"`
}

// One POST /network/auth-client request. ProvideIntent is sent only to create
// a provider install; the server ignores it on a reissue.
type authClientArgs struct {
	ClientId      string `json:"client_id,omitempty"`
	Description   string `json:"description"`
	DeviceSpec    string `json:"device_spec"`
	ProvideIntent bool   `json:"provide_intent,omitempty"`
}

// A refusal that the server answered in the result's error object.
type authClientRefusal struct {
	message string
}

// The server's message on one line.
func (self *authClientRefusal) Error() string {
	return fmt.Sprintf("the server refused the client: %s", oneLine(self.message))
}

// The settings and transport of one provision.
type providerProvisioner struct {
	walletDir string
	transport apiTransport
	out       io.Writer
}

// Validates the private wallet directory that holds providers.json.
func newProviderProvisioner(walletDir string, transport apiTransport, out io.Writer) (*providerProvisioner, error) {
	if err := checkWalletDir(walletDir); err != nil {
		return nil, err
	}
	return &providerProvisioner{
		walletDir: walletDir,
		transport: transport,
		out:       out,
	}, nil
}

// Issues the client JWT of one installation's provider install and writes it
// to clientJwtPath, privately and atomically. A mapped installation is
// reissued for its stored client id. An unmapped installation, or one whose
// client the server no longer has, gets a new provider install, which is
// mapped before its JWT is written, so a failed write is repaired by
// provisioning again (a reissue).
func (self *providerProvisioner) Provision(installationKey string, clientJwtPath string) error {
	if !installationKeyPattern.MatchString(installationKey) {
		return errors.New("expected an installation key such as user:alice:laptop-1")
	}
	if !filepath.IsAbs(clientJwtPath) {
		return errors.New("expected an absolute path for the client JWT file")
	}
	mapPath := filepath.Join(self.walletDir, providerMapFile)
	lockPath := mapPath + ".lock"
	if err := os.Mkdir(lockPath, 0o700); err != nil {
		return fmt.Errorf("another provision holds %s; remove it only after confirming that none is running: %w", lockPath, err)
	}
	defer os.Remove(lockPath)

	providers, err := loadProviderMap(mapPath)
	if err != nil {
		return err
	}
	description := providerDescriptionPrefix + installationKey
	if storedClientId, ok := providers.InstallationKeyClientIds[installationKey]; ok {
		_, clientJwt, err := self.authClient(&authClientArgs{
			ClientId:    storedClientId,
			Description: description,
			DeviceSpec:  providerDeviceSpec,
		}, storedClientId)
		if err == nil {
			if err := writePrivateFile(clientJwtPath, []byte(clientJwt+"\n")); err != nil {
				return fmt.Errorf("write the client JWT: %w", err)
			}
			fmt.Fprintf(self.out, "reissued provider install %s for %s and wrote its client JWT to %s\n", storedClientId, installationKey, clientJwtPath)
			return nil
		}
		var refusal *authClientRefusal
		if !errors.As(err, &refusal) || refusal.message != clientDoesNotExistMessage {
			return fmt.Errorf("reissue provider install %s: %w", storedClientId, err)
		}
		// the client is gone: the installation gets a new provider install
		delete(providers.InstallationKeyClientIds, installationKey)
		if err := saveProviderMap(mapPath, providers); err != nil {
			return err
		}
		fmt.Fprintf(self.out, "provider install %s of %s no longer exists; removed its mapping\n", storedClientId, installationKey)
	}

	clientId, clientJwt, err := self.authClient(&authClientArgs{
		Description:   description,
		DeviceSpec:    providerDeviceSpec,
		ProvideIntent: true,
	}, "")
	if err != nil {
		return fmt.Errorf("create a provider install: %w", err)
	}
	for mappedInstallationKey, mappedClientId := range providers.InstallationKeyClientIds {
		if mappedClientId == clientId {
			return fmt.Errorf("the new client %s is already mapped to %s", clientId, mappedInstallationKey)
		}
	}
	providers.InstallationKeyClientIds[installationKey] = clientId
	if err := saveProviderMap(mapPath, providers); err != nil {
		return err
	}
	if err := writePrivateFile(clientJwtPath, []byte(clientJwt+"\n")); err != nil {
		return fmt.Errorf("write the client JWT (provision again to reissue it): %w", err)
	}
	fmt.Fprintf(self.out, "created provider install %s for %s and wrote its client JWT to %s\n", clientId, installationKey, clientJwtPath)
	return nil
}

// Posts one auth-client request and returns the issued client id and client
// JWT. The JWT's client_id claim must name the result's client, and a reissue
// must answer the stored client. The claim is read without verifying the
// token, which the server signs and the sdk verifies.
func (self *providerProvisioner) authClient(args *authClientArgs, expectedClientId string) (string, string, error) {
	request, err := json.Marshal(args)
	if err != nil {
		return "", "", err
	}
	status, body, err := self.transport(http.MethodPost, "/network/auth-client", request)
	if err := apiResult(status, body, err); err != nil {
		return "", "", err
	}
	var result struct {
		ClientId    string `json:"client_id"`
		ByClientJwt string `json:"by_client_jwt"`
		Error       *struct {
			Message string `json:"message"`
		} `json:"error"`
	}
	if err := json.Unmarshal(body, &result); err != nil {
		return "", "", errors.New("unexpected response")
	}
	if result.Error != nil {
		return "", "", &authClientRefusal{message: result.Error.Message}
	}
	if !uuidPattern.MatchString(result.ClientId) {
		return "", "", errors.New("the server answered no valid client id")
	}
	if expectedClientId != "" && result.ClientId != expectedClientId {
		return "", "", errors.New("the server answered another client")
	}
	var claims struct {
		ClientId string `json:"client_id"`
	}
	if err := decodeJwtClaims(result.ByClientJwt, &claims); err != nil || claims.ClientId != result.ClientId {
		return "", "", errors.New("the client JWT does not name the client")
	}
	return result.ClientId, result.ByClientJwt, nil
}

// Reads providers.json; a missing file is an empty map. Every key must be an
// installation key and every client id a distinct lowercase uuid.
func loadProviderMap(path string) (*providerMap, error) {
	data, err := readPrivateFile(path)
	if errors.Is(err, fs.ErrNotExist) {
		return &providerMap{
			Version:                  providerMapVersion,
			InstallationKeyClientIds: map[string]string{},
		}, nil
	}
	if err != nil {
		return nil, fmt.Errorf("read %s: %w", providerMapFile, err)
	}
	providers := &providerMap{}
	if err := json.Unmarshal(data, providers); err != nil || providers.Version != providerMapVersion || providers.InstallationKeyClientIds == nil {
		return nil, fmt.Errorf("%s is not a provider map", providerMapFile)
	}
	mappedClientIds := map[string]bool{}
	for installationKey, clientId := range providers.InstallationKeyClientIds {
		if !installationKeyPattern.MatchString(installationKey) || !uuidPattern.MatchString(clientId) || mappedClientIds[clientId] {
			return nil, fmt.Errorf("%s maps an invalid installation key or client", providerMapFile)
		}
		mappedClientIds[clientId] = true
	}
	return providers, nil
}

// Replaces providers.json privately and atomically.
func saveProviderMap(path string, providers *providerMap) error {
	data, err := json.Marshal(providers)
	if err != nil {
		return err
	}
	return writePrivateFile(path, data)
}
