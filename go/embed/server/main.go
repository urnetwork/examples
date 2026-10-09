// SERVER ONLY: the reference embed backend (EMBED_CONTRACT.md, "The token
// server" and "Backend tools"). One binary, standard library only, no
// URnetwork SDK:
//
//	token-server [serve]                            the HTTP token server: POST /urnetwork/client-token
//	token-server provision <key> <client-jwt-file>  reissues or provisions the key's client and writes its client JWT
//	token-server cap <key> [--monthly <bytes>|--monthly null] [--total <bytes>|--total null] [--reset-total]
//	token-server usage <key>                        prints the key's cap object
//	token-server usage-all                          prints the cap object of every capped client, one per line
//	token-server remove <key>                       removes the key's client and its mapping
//	token-server acl <key> default|isolated         sets the ACL group of the key's client
//	token-server status                             prints the network's Embed state
//	token-server --self-test                        credential-free checks
//
// The token server and the commands share one private client map, so a key
// that the token server provisioned can be capped, read and removed here.
//
// Settings, from the environment:
//
//	URNETWORK_ROOT_JWT                    the root credential: an API key (production) or a network JWT
//	URNETWORK_CLIENT_MAP                  absolute filename of this server's own map, in a private directory
//	URNETWORK_API_URL                     optional https origin, default https://api.bringyour.com
//	URNETWORK_DEFAULT_MONTHLY_BYTE_LIMIT  optional default caps, applied once to each new client
//	URNETWORK_DEFAULT_TOTAL_BYTE_LIMIT
//	URNETWORK_DEFAULT_ACL_GROUP           optional ACL group of each new client: isolated (default) or default
//	URNETWORK_DEMO_SESSIONS               token server: absolute filename of the private demo session file
//	URNETWORK_TOKEN_SERVER_ADDRESS        token server: optional bind address, default 127.0.0.1:8790
//	URNETWORK_MAX_INSTALLATIONS_PER_USER  token server: optional, default 5
//
// Exit codes: 0 success, 78 a configuration or credential problem (missing
// settings, an invalid key or map, the root credential refused, the client
// limit), 1 any other failure. Errors are one stderr line. The root
// credential, session tokens and client JWTs are never printed or logged.
package main

import (
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"regexp"
	"runtime"
	"strconv"
	"strings"
)

const (
	exitSuccess = 0
	exitFailure = 1
	// sysexits EX_CONFIG
	exitConfig = 78
)

const defaultApiUrl = "https://api.bringyour.com"

// the command forms
const usage = "usage: token-server [serve] | provision <key> <client-jwt-file> | cap <key> [--monthly <bytes>|null] [--total <bytes>|null] [--reset-total] | usage <key> | usage-all | remove <key> | acl <key> default|isolated | status | --self-test"

// What every tool prints for a client limit refusal (either flag).
const clientLimitMessage = "client limit reached: your network is at its client limit; see https://ur.io/services"

// What every tool prints for the Embed-not-enabled refusal from cap, usage,
// usage-all and acl (EMBED_CONTRACT.md, "Embed enablement"); exit 78.
const embedNotEnabledLine = "embed not enabled: Embed isn't enabled for this network; see https://ur.io/services"

// What provision prints on stderr when it succeeds but the client's default
// ACL group or caps stay pending because Embed isn't enabled; exit 0.
const embedPendingLine = "embed not enabled: the client's defaults stay pending until Embed is enabled; see https://ur.io/services"

// The description and device spec that the commands send, as every language's
// backend tool does. The token server's own requests use theirs
// (tokenserver.go).
const commandDescription = "embed client"
const commandDeviceSpec = "urnetwork-examples/go-embed-server"

// A byte count setting or option: a decimal integer from 0 to the largest
// int64, with no sign, units or leading zeros.
var byteCountPattern = regexp.MustCompile(`^(0|[1-9][0-9]*)$`)

// A configuration or credential problem that a restart does not fix: missing
// or invalid settings or arguments, the root credential refused, or the
// client limit.
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

// What a command runs with: the environment, the output streams and the api
// transport. The self-test runs the commands with a mock transport.
type environment struct {
	getenv    func(string) string
	stdout    io.Writer
	stderr    io.Writer
	transport func(origin string, rootCredential string) apiTransport
}

// Exits with the code of the command.
func main() {
	os.Exit(run(os.Args[1:], &environment{
		getenv:    os.Getenv,
		stdout:    os.Stdout,
		stderr:    os.Stderr,
		transport: newHttpTransport,
	}))
}

// Runs one command, prints a failure as one stderr line and returns the exit
// code.
func run(args []string, env *environment) int {
	err := runCommand(args, env)
	if err != nil {
		fmt.Fprintln(env.stderr, oneLine(err.Error()))
	}
	return exitCode(err)
}

// The exit code of a command's error.
func exitCode(err error) int {
	if err == nil {
		return exitSuccess
	}
	var configErr *configError
	var mapErr *mapError
	if errors.As(err, &configErr) || errors.As(err, &mapErr) {
		return exitConfig
	}
	return exitFailure
}

// Dispatches one command.
func runCommand(args []string, env *environment) error {
	if len(args) == 1 && args[0] == "--self-test" {
		if err := runSelfTest(); err != nil {
			return fmt.Errorf("token-server self-test failed: %w", err)
		}
		fmt.Fprintln(env.stdout, "token-server self-test passed")
		return nil
	}
	if len(args) == 0 || (len(args) == 1 && args[0] == "serve") {
		return serve(env)
	}
	command := args[0]
	switch {
	case command == "provision" && len(args) == 3:
	case command == "cap" && 3 <= len(args):
	case (command == "usage" || command == "remove") && len(args) == 2:
	case command == "usage-all" && len(args) == 1:
	case command == "acl" && len(args) == 3:
	case command == "status" && len(args) == 1:
	default:
		return configErrorf("%s", usage)
	}
	tool, err := newBackendTool(env)
	if err != nil {
		return err
	}
	switch command {
	case "provision":
		return tool.Provision(args[1], args[2])
	case "cap":
		return tool.Cap(args[1], args[2:])
	case "usage":
		return tool.Usage(args[1])
	case "usage-all":
		return tool.UsageAll()
	case "acl":
		return tool.Acl(args[1], args[2])
	case "status":
		return tool.Status()
	default:
		return tool.Remove(args[1])
	}
}

// The settings that the token server and the commands share.
type settings struct {
	apiOrigin      string
	rootCredential string
	mapPath        string
	defaults       defaultCaps
	// the ACL group of each new client: aclGroupIsolated or aclGroupDefault
	defaultAclGroup string
}

// Reads and validates the shared settings.
func loadSettings(getenv func(string) string) (*settings, error) {
	apiUrl := getenv("URNETWORK_API_URL")
	if apiUrl == "" {
		apiUrl = defaultApiUrl
	}
	origin, err := apiOrigin(apiUrl)
	if err != nil {
		return nil, err
	}
	rootCredential, err := checkRootCredential(getenv("URNETWORK_ROOT_JWT"))
	if err != nil {
		return nil, err
	}
	mapPath, err := checkMapPath(getenv("URNETWORK_CLIENT_MAP"))
	if err != nil {
		return nil, err
	}
	defaults, err := loadDefaultCaps(getenv)
	if err != nil {
		return nil, err
	}
	defaultAclGroup, err := loadDefaultAclGroup(getenv)
	if err != nil {
		return nil, err
	}
	return &settings{
		apiOrigin:       origin,
		rootCredential:  rootCredential,
		mapPath:         mapPath,
		defaults:        defaults,
		defaultAclGroup: defaultAclGroup,
	}, nil
}

// The root credential is an API key or a network JWT. A client JWT (with a
// client_id claim) is refused: it cannot provision. Other values go to the
// server, which refuses what it does not accept.
func checkRootCredential(rootCredential string) (string, error) {
	if rootCredential == "" || strings.ContainsAny(rootCredential, " \t\r\n") {
		return "", configErrorf("set URNETWORK_ROOT_JWT to the backend's root credential: an API key or the network JWT")
	}
	var claims struct {
		ClientId *string `json:"client_id"`
	}
	if decodeJwtClaims(rootCredential, &claims) == nil && claims.ClientId != nil {
		return "", configErrorf("URNETWORK_ROOT_JWT is a client JWT; set an API key or the network JWT")
	}
	return rootCredential, nil
}

// The client map is an absolute filename in an existing directory that, on
// POSIX, only its owner can access.
func checkMapPath(mapPath string) (string, error) {
	if mapPath == "" || !filepath.IsAbs(mapPath) {
		return "", configErrorf("set URNETWORK_CLIENT_MAP to an absolute filename in a private, service-owned directory")
	}
	if err := checkPrivateDir(filepath.Dir(mapPath), "URNETWORK_CLIENT_MAP's directory"); err != nil {
		return "", err
	}
	return mapPath, nil
}

// An existing directory that, on POSIX, only its owner can access.
func checkPrivateDir(dir string, name string) error {
	info, err := os.Stat(dir)
	if err != nil || !info.IsDir() {
		return configErrorf("%s must be an existing directory", name)
	}
	if runtime.GOOS != "windows" && info.Mode().Perm()&0o077 != 0 {
		return configErrorf("%s must be private to its owner (chmod 700)", name)
	}
	return nil
}

// The optional caps that each new client gets once.
type defaultCaps struct {
	// nil when not configured
	monthly *int64
	total   *int64
}

// The ACL groups (EMBED_CONTRACT.md, "ACL groups"). A new client is
// "default"; an "isolated" client never appears in the network's peer list,
// receives none, and cannot use Messages.
const (
	aclGroupDefault  = "default"
	aclGroupIsolated = "isolated"
)

// Reads URNETWORK_DEFAULT_ACL_GROUP, the ACL group of each new client:
// "isolated" when unset, so your users stay out of each other's peer list;
// an app that uses Messages sets "default".
func loadDefaultAclGroup(getenv func(string) string) (string, error) {
	switch group := getenv("URNETWORK_DEFAULT_ACL_GROUP"); group {
	case "":
		return aclGroupIsolated, nil
	case aclGroupDefault, aclGroupIsolated:
		return group, nil
	default:
		return "", configErrorf("URNETWORK_DEFAULT_ACL_GROUP must be default or isolated")
	}
}

// Reads URNETWORK_DEFAULT_MONTHLY_BYTE_LIMIT and URNETWORK_DEFAULT_TOTAL_BYTE_LIMIT.
func loadDefaultCaps(getenv func(string) string) (defaultCaps, error) {
	var defaults defaultCaps
	for _, setting := range []struct {
		name  string
		value **int64
	}{
		{name: "URNETWORK_DEFAULT_MONTHLY_BYTE_LIMIT", value: &defaults.monthly},
		{name: "URNETWORK_DEFAULT_TOTAL_BYTE_LIMIT", value: &defaults.total},
	} {
		text := getenv(setting.name)
		if text == "" {
			continue
		}
		byteCount, err := parseByteCount(text)
		if err != nil {
			return defaultCaps{}, configErrorf("%s must be a byte count: a decimal integer from 0 to 9223372036854775807", setting.name)
		}
		*setting.value = &byteCount
	}
	return defaults, nil
}

// Whether either default cap is configured.
func (self defaultCaps) configured() bool {
	return self.monthly != nil || self.total != nil
}

// The POST /network/client-data-cap body that sets only the configured caps.
func (self defaultCaps) body(clientId string) ([]byte, error) {
	options := &capOptions{}
	if self.monthly != nil {
		options.monthly = optionalLimit{set: true, value: *self.monthly}
	}
	if self.total != nil {
		options.total = optionalLimit{set: true, value: *self.total}
	}
	return options.body(clientId)
}

// A decimal byte count from 0 to the largest int64.
func parseByteCount(text string) (int64, error) {
	if !byteCountPattern.MatchString(text) {
		return 0, errors.New("not a byte count")
	}
	return strconv.ParseInt(text, 10, 64)
}

// Collapses text to one line of at most 300 characters.
func oneLine(text string) string {
	text = strings.Join(strings.Fields(text), " ")
	if 300 < len(text) {
		text = text[:300] + "..."
	}
	return text
}
