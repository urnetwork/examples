// Installation state of the provider companion, kept in one private directory
// named by URNETWORK_PROVIDER_STATE_DIR (PROVIDER_CONTRACT.md, "Installation
// state"), as in the Go provider example (go/provider/state.go):
//
//   - client.jwt: the scoped client credential that the developer's backend
//     issued for this installation. The developer or the app writes it; the
//     companion rewrites it whenever the SDK refreshes the token.
//   - instance-id: this installation's UUID, created on first run.
//   - identity.json: the provider identity (client key seed, provide TLS
//     certificate and key, extender seed), created on first run so the
//     provider keeps one identity across restarts.
//   - logs/: the SDK's bounded log files.
//
// Every file is replaced atomically with owner-only permissions. On POSIX the
// directory and its files must not be accessible to group or others; Windows
// relies on the access control of the user's profile directory.
package main

import (
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io/fs"
	"os"
	"path/filepath"
	"runtime"
	"strings"

	sdk "github.com/urnetwork/sdk/v2026"
)

// Printed at start in provider mode, and in every provider example's README.
// The app that integrates a provider owns the consent screen; the examples
// start without asking.
const consentDisclaimer = `Consent disclaimer: an app that integrates a URnetwork provider must collect the user's consent before it provides.
Providing shares the user's internet connection: other URnetwork users' traffic exits through the user's device and IP address.
This example starts providing without asking, because the consent screen belongs to your app.`

const clientJwtFileName = "client.jwt"
const instanceIdFileName = "instance-id"
const identityFileName = "identity.json"

// the largest state file the companion reads
const stateFileByteLimit = 64 * 1024

const providerIdentityVersion = 1

// identity.json. Byte fields are standard base64 in json. An identity belongs
// to one client: a newly provisioned client gets a new identity.
type providerIdentity struct {
	Version                  int    `json:"version"`
	ClientId                 string `json:"client_id"`
	ClientKeySeed            []byte `json:"client_key_seed"`
	ProvideTlsCertificatePem []byte `json:"provide_tls_certificate_pem"`
	ProvideTlsPrivateKeyPem  []byte `json:"provide_tls_private_key_pem"`
	ExtenderKeySeed          []byte `json:"extender_key_seed,omitempty"`
}

// The installation state loaded at start.
type providerConfig struct {
	stateDir  string
	clientJwt string
	// the client_id claim of clientJwt
	clientId   string
	instanceId string
	// nil on first run, and when the stored identity belongs to another client
	identity *providerIdentity
}

// Loads the installation state, creating instance-id on first run. Every
// error is a configuration error: restarting does not fix it.
func loadProviderConfig(stateDir string) (*providerConfig, error) {
	if err := checkStateDir(stateDir); err != nil {
		return nil, err
	}
	clientJwtBytes, err := readPrivateFile(filepath.Join(stateDir, clientJwtFileName))
	if err != nil {
		return nil, fmt.Errorf("read %s from the state directory: %w", clientJwtFileName, err)
	}
	clientJwt := strings.TrimSpace(string(clientJwtBytes))
	clientId, err := parseClientJwtClientId(clientJwt)
	if err != nil {
		return nil, err
	}
	instanceId, err := loadOrCreateInstanceId(stateDir)
	if err != nil {
		return nil, err
	}
	identity, err := loadProviderIdentity(stateDir, clientId)
	if err != nil {
		return nil, err
	}
	return &providerConfig{
		stateDir:   stateDir,
		clientJwt:  clientJwt,
		clientId:   clientId,
		instanceId: instanceId,
		identity:   identity,
	}, nil
}

// The state directory must be an existing absolute directory, private to its
// owner on POSIX.
func checkStateDir(stateDir string) error {
	if stateDir == "" {
		return errors.New("set URNETWORK_PROVIDER_STATE_DIR to this installation's private state directory")
	}
	if !filepath.IsAbs(stateDir) {
		return errors.New("URNETWORK_PROVIDER_STATE_DIR must be an absolute path")
	}
	info, err := os.Stat(stateDir)
	if err != nil {
		return fmt.Errorf("state directory: %w", err)
	}
	if !info.IsDir() {
		return errors.New("URNETWORK_PROVIDER_STATE_DIR is not a directory")
	}
	if runtime.GOOS != "windows" && info.Mode().Perm()&0o077 != 0 {
		return errors.New("the state directory must be private to its owner (chmod 700)")
	}
	return nil
}

// Reads a regular, private state file of bounded size. A symlink is refused
// so that the credential cannot be redirected to another file.
func readPrivateFile(path string) ([]byte, error) {
	info, err := os.Lstat(path)
	if err != nil {
		return nil, err
	}
	if !info.Mode().IsRegular() {
		return nil, errors.New("not a regular file")
	}
	if stateFileByteLimit < info.Size() {
		return nil, errors.New("file is too large")
	}
	if runtime.GOOS != "windows" && info.Mode().Perm()&0o077 != 0 {
		return nil, errors.New("file must be private to its owner (chmod 600)")
	}
	return os.ReadFile(path)
}

// Replaces a state file atomically: a private temporary file in the same
// directory is written, synced and renamed over the old file.
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

// The client_id claim of a scoped client JWT. This checks the token's shape
// and claim only; the SDK and the server verify the token itself.
func parseClientJwtClientId(clientJwt string) (string, error) {
	notJwt := errors.New("client.jwt does not hold a JWT; write the scoped client JWT from your backend")
	parts := strings.Split(clientJwt, ".")
	if len(parts) != 3 || parts[0] == "" || parts[1] == "" || parts[2] == "" {
		return "", notJwt
	}
	payload, err := base64.RawURLEncoding.DecodeString(strings.TrimRight(parts[1], "="))
	if err != nil {
		return "", notJwt
	}
	var claims struct {
		ClientId string `json:"client_id"`
	}
	if err := json.Unmarshal(payload, &claims); err != nil || claims.ClientId == "" {
		return "", errors.New("client.jwt has no client_id claim; write a scoped client JWT, not a network JWT")
	}
	clientId, err := sdk.ParseId(claims.ClientId)
	if err != nil {
		return "", errors.New("client.jwt has an invalid client_id claim")
	}
	return clientId.String(), nil
}

// Reads instance-id, creating it on first run. An installation keeps one
// instance id for its lifetime.
func loadOrCreateInstanceId(stateDir string) (string, error) {
	path := filepath.Join(stateDir, instanceIdFileName)
	data, err := readPrivateFile(path)
	if err == nil {
		instanceId, err := sdk.ParseId(strings.TrimSpace(string(data)))
		if err != nil {
			return "", fmt.Errorf("%s does not hold a UUID", instanceIdFileName)
		}
		return instanceId.String(), nil
	}
	if !errors.Is(err, fs.ErrNotExist) {
		return "", fmt.Errorf("read %s from the state directory: %w", instanceIdFileName, err)
	}
	instanceId := sdk.NewId().String()
	if err := writePrivateFile(path, []byte(instanceId+"\n")); err != nil {
		return "", fmt.Errorf("write %s: %w", instanceIdFileName, err)
	}
	return instanceId, nil
}

// Reads identity.json for clientId. A missing file, or an identity of another
// client, returns nil: the device then creates a new identity, which the
// companion saves.
func loadProviderIdentity(stateDir string, clientId string) (*providerIdentity, error) {
	data, err := readPrivateFile(filepath.Join(stateDir, identityFileName))
	if errors.Is(err, fs.ErrNotExist) {
		return nil, nil
	}
	if err != nil {
		return nil, fmt.Errorf("read %s from the state directory: %w", identityFileName, err)
	}
	identity := &providerIdentity{}
	if err := json.Unmarshal(data, identity); err != nil || identity.Version != providerIdentityVersion || len(identity.ClientKeySeed) != 32 {
		return nil, fmt.Errorf("%s is not a valid provider identity; remove it to create a new one", identityFileName)
	}
	if identity.ClientId != clientId {
		return nil, nil
	}
	return identity, nil
}

// Writes identity.json.
func saveProviderIdentity(stateDir string, identity *providerIdentity) error {
	data, err := json.Marshal(identity)
	if err != nil {
		return err
	}
	return writePrivateFile(filepath.Join(stateDir, identityFileName), data)
}

// The identity of a running device, for identity.json.
func newProviderIdentity(clientId string, keyMaterial *sdk.DeviceLocalKeyMaterial) *providerIdentity {
	return &providerIdentity{
		Version:                  providerIdentityVersion,
		ClientId:                 clientId,
		ClientKeySeed:            keyMaterial.GetClientKeySeed(),
		ProvideTlsCertificatePem: keyMaterial.GetProvideTlsCertificatePem(),
		ProvideTlsPrivateKeyPem:  keyMaterial.GetProvideTlsPrivateKeyPem(),
		ExtenderKeySeed:          keyMaterial.GetExtenderKeySeed(),
	}
}

// The key material that recreates the device's identity. nil without an
// identity (the first run, or another client's identity): the device then
// makes a new identity, which the companion saves.
func (self *providerIdentity) keyMaterial() *sdk.DeviceLocalKeyMaterial {
	if self == nil {
		return nil
	}
	keyMaterial := sdk.NewDeviceLocalKeyMaterial(
		self.ClientKeySeed,
		self.ProvideTlsCertificatePem,
		self.ProvideTlsPrivateKeyPem,
	)
	keyMaterial.SetExtenderKeySeed(self.ExtenderKeySeed)
	return keyMaterial
}
