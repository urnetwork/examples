// The backend commands (EMBED_CONTRACT.md, "Backend tools"): provision a
// key's client, set and read its data caps, read every capped client, and
// remove a client. They use the token server's map and settings, so they
// manage the clients the token server provisions.
package main

import (
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"path/filepath"
)

// The settings, api and map of one command.
type backendTool struct {
	api    *apiClient
	store  *mapStore
	issuer *issuer
	out    io.Writer
}

// Validates the shared settings.
func newBackendTool(env *environment) (*backendTool, error) {
	shared, err := loadSettings(env.getenv)
	if err != nil {
		return nil, err
	}
	api := &apiClient{transport: env.transport(shared.apiOrigin, shared.rootCredential)}
	store := newMapStore(shared.mapPath)
	return &backendTool{
		api:   api,
		store: store,
		issuer: &issuer{
			api:         api,
			store:       store,
			defaults:    shared.defaults,
			description: commandDescription,
			deviceSpec:  commandDeviceSpec,
		},
		out: env.stdout,
	}, nil
}

// Reissues the key's client, or provisions a new one (also when the server no
// longer has the mapped client), applies any default caps it still owes, and
// writes its client JWT to clientJwtPath, owner-only and atomically. Prints
// {"client_id": "..."}; the JWT is never printed.
func (self *backendTool) Provision(key string, clientJwtPath string) error {
	if !keyPattern.MatchString(key) {
		return configErrorf("expected a key such as user:alice or user:alice:22222222-2222-2222-2222-222222222222")
	}
	if clientJwtPath == "" {
		return configErrorf("expected the client JWT file to write")
	}
	unlock, err := self.store.Lock()
	if err != nil {
		return err
	}
	defer unlock()
	clients, err := self.store.Load()
	if err != nil {
		return err
	}
	issued, err := self.issuer.Issue(clients, key, nil)
	if err != nil {
		return commandError(err)
	}
	// the mapping is saved before the token is written, so a failed write is
	// repaired by provisioning again (a reissue)
	if err := writePrivateFile(filepath.Clean(clientJwtPath), []byte(issued.clientJwt+"\n")); err != nil {
		return fmt.Errorf("could not write the client JWT file (provision again to reissue): %v", err)
	}
	return printJson(self.out, map[string]string{"client_id": issued.clientId})
}

// Posts only the given cap options for the key's client and prints its cap
// object.
func (self *backendTool) Cap(key string, args []string) error {
	clientId, err := self.mappedClientId(key)
	if err != nil {
		return err
	}
	options, err := parseCapOptions(args)
	if err != nil {
		return err
	}
	body, err := options.body(clientId)
	if err != nil {
		return err
	}
	capObject, err := self.api.SetDataCap(clientId, body)
	if err != nil {
		return commandError(err)
	}
	return printLine(self.out, capObject)
}

// Prints the key's cap object, read with the root credential.
func (self *backendTool) Usage(key string) error {
	clientId, err := self.mappedClientId(key)
	if err != nil {
		return err
	}
	capObject, err := self.api.GetDataCap(clientId)
	if err != nil {
		return commandError(err)
	}
	return printLine(self.out, capObject)
}

// Pages through GET /network/client-data-caps and prints one cap object per
// line, stopping at a null cursor or one it has already seen.
func (self *backendTool) UsageAll() error {
	seenCursors := map[string]bool{}
	cursor := ""
	for {
		capObjects, nextCursor, err := self.api.ListDataCaps(cursor)
		if err != nil {
			return commandError(err)
		}
		for _, capObject := range capObjects {
			if err := printLine(self.out, capObject); err != nil {
				return err
			}
		}
		if nextCursor == nil || *nextCursor == "" || seenCursors[*nextCursor] {
			return nil
		}
		seenCursors[*nextCursor] = true
		cursor = *nextCursor
	}
}

// Removes the key's client, then its mapping, also when the server answers
// that the client does not exist. Prints {"removed": "<client_id>"}.
func (self *backendTool) Remove(key string) error {
	if !keyPattern.MatchString(key) {
		return configErrorf("expected a key such as user:alice or user:alice:22222222-2222-2222-2222-222222222222")
	}
	unlock, err := self.store.Lock()
	if err != nil {
		return err
	}
	defer unlock()
	clients, err := self.store.Load()
	if err != nil {
		return err
	}
	clientId, ok := clients.Clients[key]
	if !ok {
		return configErrorf("%s has no client in the map", key)
	}
	if err := self.api.RemoveClient(clientId); err != nil && !isClientDoesNotExist(err) {
		return commandError(err)
	}
	clients.Remove(key)
	if err := self.store.Save(clients); err != nil {
		return err
	}
	return printJson(self.out, map[string]string{"removed": clientId})
}

// The client id that the map holds for key.
func (self *backendTool) mappedClientId(key string) (string, error) {
	if !keyPattern.MatchString(key) {
		return "", configErrorf("expected a key such as user:alice or user:alice:22222222-2222-2222-2222-222222222222")
	}
	clients, err := self.store.Load()
	if err != nil {
		return "", err
	}
	clientId, ok := clients.Clients[key]
	if !ok {
		return "", configErrorf("%s has no client in the map; provision it first", key)
	}
	return clientId, nil
}

// A client limit refusal (either flag) is a configuration error with the
// tools' fixed message.
func commandError(err error) error {
	if isClientLimit(err) {
		return &configError{message: clientLimitMessage}
	}
	return err
}

// The cap options of one cap command.
type capOptions struct {
	monthly    optionalLimit
	total      optionalLimit
	resetTotal bool
}

// One limit option: unset (absent from the request), null (clears the cap),
// or a byte count.
type optionalLimit struct {
	set   bool
	null  bool
	value int64
}

// Parses [--monthly <bytes>|null] [--total <bytes>|null] [--reset-total]:
// at least one option, each at most once.
func parseCapOptions(args []string) (*capOptions, error) {
	options := &capOptions{}
	if len(args) == 0 {
		return nil, configErrorf("cap needs at least one of --monthly, --total and --reset-total")
	}
	for i := 0; i < len(args); i += 1 {
		switch args[i] {
		case "--monthly", "--total":
			limit := &options.monthly
			if args[i] == "--total" {
				limit = &options.total
			}
			if limit.set || len(args) <= i+1 {
				return nil, configErrorf("%s takes one value: a byte count or null, at most once", args[i])
			}
			i += 1
			parsed, err := parseLimitValue(args[i])
			if err != nil {
				return nil, err
			}
			*limit = parsed
		case "--reset-total":
			if options.resetTotal {
				return nil, configErrorf("--reset-total is given more than once")
			}
			options.resetTotal = true
		default:
			return nil, configErrorf("unknown cap option %q", args[i])
		}
	}
	return options, nil
}

// A limit option's value: null, or a byte count from 0 to 9223372036854775807
// without units.
func parseLimitValue(text string) (optionalLimit, error) {
	if text == "null" {
		return optionalLimit{set: true, null: true}, nil
	}
	byteCount, err := parseByteCount(text)
	if err != nil {
		return optionalLimit{}, configErrorf("a cap is null or a byte count: a decimal integer from 0 to 9223372036854775807, with no units")
	}
	return optionalLimit{set: true, value: byteCount}, nil
}

// The POST /network/client-data-cap body. Each limit merges on the server:
// an unset option is absent, null is JSON null, a byte count is a JSON
// integer, and reset_total appears only when given.
func (self *capOptions) body(clientId string) ([]byte, error) {
	if !uuidPattern.MatchString(clientId) {
		return nil, errors.New("invalid client id")
	}
	body := map[string]any{"client_id": clientId}
	for _, limit := range []struct {
		name   string
		option optionalLimit
	}{
		{name: "monthly_byte_limit", option: self.monthly},
		{name: "total_byte_limit", option: self.total},
	} {
		switch {
		case !limit.option.set:
		case limit.option.null:
			body[limit.name] = nil
		default:
			body[limit.name] = limit.option.value
		}
	}
	if self.resetTotal {
		body["reset_total"] = true
	}
	return json.Marshal(body)
}

// Prints value as one line of JSON.
func printJson(out io.Writer, value any) error {
	data, err := json.Marshal(value)
	if err != nil {
		return err
	}
	return printLine(out, data)
}

// Prints JSON bytes as one line.
func printLine(out io.Writer, data []byte) error {
	_, err := fmt.Fprintf(out, "%s\n", data)
	return err
}
