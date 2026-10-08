// The demo session file (EMBED_CONTRACT.md, "Configuration"): it stands in
// for your service's real sign-in. Your service maps its own session (a
// cookie, an OAuth access token) to its service user id instead. The file is
// one private JSON object:
//
//	{"version": 1, "sessions": {"<random token, at least 32 characters>": "alice"}}
package main

import (
	"bytes"
	"crypto/subtle"
	"encoding/json"
	"regexp"
)

const demoSessionsVersion = 1

// the shortest demo session token
const demoSessionTokenMinLength = 32

// A demo session token: visible ASCII, so it travels in an Authorization
// header as is.
var demoSessionTokenPattern = regexp.MustCompile(`^[\x21-\x7e]+$`)

// A service user id. No colon, so user:<service-user-id>:<installation-id>
// splits one way and stays within the map key pattern.
var serviceUserIdPattern = regexp.MustCompile(`^[A-Za-z0-9][A-Za-z0-9_.@-]{0,72}$`)

// The demo sessions: tokens and the service user each one signs in.
type demoSessions struct {
	tokens  [][]byte
	userIds []string
}

// Reads and validates the demo session file. Every problem is a
// configuration error.
func loadDemoSessions(path string) (*demoSessions, error) {
	if path == "" {
		return nil, configErrorf("set URNETWORK_DEMO_SESSIONS to the absolute filename of the private demo session file")
	}
	data, err := readPrivateFile(path)
	if err != nil {
		return nil, configErrorf("read the demo session file: %v", err)
	}
	decoder := json.NewDecoder(bytes.NewReader(data))
	decoder.DisallowUnknownFields()
	var file struct {
		Version  int               `json:"version"`
		Sessions map[string]string `json:"sessions"`
	}
	if err := decoder.Decode(&file); err != nil || decoder.More() || file.Version != demoSessionsVersion || len(file.Sessions) == 0 {
		return nil, configErrorf("the demo session file is not a version 1 file with at least one session")
	}
	sessions := &demoSessions{}
	for token, userId := range file.Sessions {
		if len(token) < demoSessionTokenMinLength || !demoSessionTokenPattern.MatchString(token) {
			return nil, configErrorf("every demo session token must have at least %d visible characters", demoSessionTokenMinLength)
		}
		if !serviceUserIdPattern.MatchString(userId) {
			return nil, configErrorf("every demo session's service user id must match [A-Za-z0-9][A-Za-z0-9_.@-]{0,72}")
		}
		sessions.tokens = append(sessions.tokens, []byte(token))
		sessions.userIds = append(sessions.userIds, userId)
	}
	return sessions, nil
}

// The service user of a presented token. Every token is compared in constant
// time, so the answer time does not tell which token prefix matched.
func (self *demoSessions) UserId(presentedToken string) (string, bool) {
	presented := []byte(presentedToken)
	matched := -1
	for i, token := range self.tokens {
		if subtle.ConstantTimeCompare(presented, token) == 1 {
			matched = i
		}
	}
	if matched < 0 {
		return "", false
	}
	return self.userIds[matched], true
}
