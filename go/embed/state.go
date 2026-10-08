// Installation state for one installation of your app, kept in one private
// directory named by URNETWORK_EMBED_STATE_DIR (EMBED_CONTRACT.md,
// "Installation state"):
//
//   - client.jwt: the installation's scoped client JWT. The token fetch, a
//     backend tool or the developer writes it; the app rewrites it whenever
//     the SDK refreshes the token. A bearer secret: never printed or logged.
//   - instance-id: this installation's UUID, created on first run and kept
//     for its life. It is also the installation ID the app sends to the
//     token server.
//   - logs/: the SDK's bounded log files.
//
// Every file is replaced atomically with owner-only permissions. On POSIX the
// directory and its files must not be accessible to group or others, and a
// symlinked file is refused; Windows relies on the access control of the
// user's profile directory, so keep the directory under %LOCALAPPDATA%.
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

const clientJwtFileName = "client.jwt"
const instanceIdFileName = "instance-id"
const logsDirName = "logs"

// the largest state file the app reads
const stateFileByteLimit = 64 * 1024

const defaultApiUrl = "https://api.bringyour.com"

// A configuration or credential problem that a restart does not fix (exit 78).
type configError struct {
	message string
}

// The problem on one line.
func (self *configError) Error() string {
	return self.message
}

// A configuration error with a formatted message.
func configErrorf(format string, args ...any) error {
	return &configError{message: fmt.Sprintf(format, args...)}
}

// The installation's configuration, loaded at start.
type embedConfig struct {
	stateDir   string
	instanceId string
	// nil when the app uses the client.jwt already in its state
	tokenServer *tokenServerConfig
	// the api origin for the app's own cap reads
	apiOrigin string
}

// Loads the configuration from the environment and the state directory,
// creating instance-id on first run. Every error is a configuration error.
func loadEmbedConfig(getenv func(string) string) (*embedConfig, error) {
	stateDir := getenv("URNETWORK_EMBED_STATE_DIR")
	if err := checkStateDir(stateDir); err != nil {
		return nil, err
	}
	tokenServer, err := loadTokenServerConfig(getenv)
	if err != nil {
		return nil, err
	}
	apiUrl := getenv("URNETWORK_API_URL")
	if apiUrl == "" {
		apiUrl = defaultApiUrl
	}
	apiOrigin, err := httpOrigin(apiUrl)
	if err != nil {
		return nil, configErrorf("URNETWORK_API_URL must be an https origin, or explicit loopback http for a mock")
	}
	instanceId, err := loadOrCreateInstanceId(stateDir)
	if err != nil {
		return nil, err
	}
	return &embedConfig{
		stateDir:    stateDir,
		instanceId:  instanceId,
		tokenServer: tokenServer,
		apiOrigin:   apiOrigin,
	}, nil
}

// The state directory must be an existing absolute directory, private to its
// owner on POSIX.
func checkStateDir(stateDir string) error {
	if stateDir == "" {
		return configErrorf("set URNETWORK_EMBED_STATE_DIR to this installation's private state directory")
	}
	if !filepath.IsAbs(stateDir) {
		return configErrorf("URNETWORK_EMBED_STATE_DIR must be an absolute path")
	}
	info, err := os.Stat(stateDir)
	if err != nil {
		return configErrorf("state directory: %v", err)
	}
	if !info.IsDir() {
		return configErrorf("URNETWORK_EMBED_STATE_DIR is not a directory")
	}
	if runtime.GOOS != "windows" && info.Mode().Perm()&0o077 != 0 {
		return configErrorf("the state directory must be private to its owner (chmod 700)")
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
// and claim only; the SDK and the server verify the token itself. A network
// JWT has no client_id claim and is refused.
func parseClientJwtClientId(clientJwt string) (string, error) {
	notJwt := errors.New("the client JWT is not a JWT")
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
		return "", errors.New("the client JWT has no client_id claim; use a scoped client JWT, not a network JWT")
	}
	clientId, err := sdk.ParseId(claims.ClientId)
	if err != nil {
		return "", errors.New("the client JWT has an invalid client_id claim")
	}
	return clientId.String(), nil
}

// Reads client.jwt from the state directory, for an app without a token
// server. A missing, unreadable or network JWT is a configuration error.
func loadClientJwt(stateDir string) (*clientCredential, error) {
	data, err := readPrivateFile(filepath.Join(stateDir, clientJwtFileName))
	if err != nil {
		return nil, configErrorf("read %s from the state directory (%v); configure a token server with URNETWORK_TOKEN_SERVER_URL and URNETWORK_DEMO_SESSION, or write client.jwt with token-server provision", clientJwtFileName, err)
	}
	clientJwt := strings.TrimSpace(string(data))
	clientId, err := parseClientJwtClientId(clientJwt)
	if err != nil {
		return nil, configErrorf("%s: %v", clientJwtFileName, err)
	}
	return &clientCredential{clientJwt: clientJwt, clientId: clientId}, nil
}

// Saves the client JWT as client.jwt.
func saveClientJwt(stateDir string, clientJwt string) error {
	return writePrivateFile(filepath.Join(stateDir, clientJwtFileName), []byte(clientJwt+"\n"))
}

// Reads instance-id, creating it on first run. An installation keeps one
// instance id for its lifetime.
func loadOrCreateInstanceId(stateDir string) (string, error) {
	path := filepath.Join(stateDir, instanceIdFileName)
	data, err := readPrivateFile(path)
	if err == nil {
		instanceId, err := sdk.ParseId(strings.TrimSpace(string(data)))
		if err != nil {
			return "", configErrorf("%s does not hold a UUID", instanceIdFileName)
		}
		return instanceId.String(), nil
	}
	if !errors.Is(err, fs.ErrNotExist) {
		return "", configErrorf("read %s from the state directory: %v", instanceIdFileName, err)
	}
	instanceId := sdk.NewId().String()
	if err := writePrivateFile(path, []byte(instanceId+"\n")); err != nil {
		return "", configErrorf("write %s: %v", instanceIdFileName, err)
	}
	return instanceId, nil
}
