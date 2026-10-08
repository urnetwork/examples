// The token server's client map (EMBED_CONTRACT.md, "The token server"): the
// allocators' map (INTEGRATION_CONTRACT.md) from key to client_id, plus
// pending_caps, the new clients that still owe their default caps. It stores
// client ids, never tokens.
//
// Every provision holds the map lock: an in-process mutex and the allocators'
// exclusive <map>.lock directory, through the remote calls and the map
// update, so two provisions of one key cannot create two clients. The map is
// replaced atomically and is private to its owner.
package main

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"io/fs"
	"os"
	"path/filepath"
	"regexp"
	"runtime"
	"slices"
	"strings"
	"sync"
)

const clientMapVersion = 1

// A map key, as the allocators accept it: user:<service-user-id>, or
// user:<service-user-id>:<installation-id> for one client per installation.
var keyPattern = regexp.MustCompile(`^user:[A-Za-z0-9][A-Za-z0-9_.:@-]{0,122}$`)

// a lowercase uuid, as client ids and installation ids are written
var uuidPattern = regexp.MustCompile(`^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$`)

// An invalid or unreadable client map: a configuration error for the
// commands, an internal error for the token server.
type mapError struct {
	message string
}

// The problem on one line.
func (self *mapError) Error() string {
	return self.message
}

// Another process holds the map lock.
type busyError struct {
	lockPath string
}

// What to do about it.
func (self *busyError) Error() string {
	return fmt.Sprintf("another process holds %s; retry, and remove it only after confirming that no process owns it", self.lockPath)
}

// The map file. PendingCaps is always written, so a language backend tool,
// which accepts only version and clients, refuses this map.
type clientMap struct {
	Version     int               `json:"version"`
	Clients     map[string]string `json:"clients"`
	PendingCaps []string          `json:"pending_caps"`
}

// An empty map.
func newClientMap() *clientMap {
	return &clientMap{
		Version:     clientMapVersion,
		Clients:     map[string]string{},
		PendingCaps: []string{},
	}
}

// Whether key still owes its default caps.
func (self *clientMap) Pending(key string) bool {
	return slices.Contains(self.PendingCaps, key)
}

// Records or clears that key owes its default caps.
func (self *clientMap) SetPending(key string, pending bool) {
	self.PendingCaps = slices.DeleteFunc(self.PendingCaps, func(pendingKey string) bool {
		return pendingKey == key
	})
	if pending {
		self.PendingCaps = append(self.PendingCaps, key)
	}
}

// Drops key's mapping and its pending caps.
func (self *clientMap) Remove(key string) {
	delete(self.Clients, key)
	self.SetPending(key, false)
}

// The key that maps clientId, if any.
func (self *clientMap) KeyOf(clientId string) (string, bool) {
	for key, mappedClientId := range self.Clients {
		if mappedClientId == clientId {
			return key, true
		}
	}
	return "", false
}

// The number of mapped keys that begin with prefix, such as one user's
// installations.
func (self *clientMap) CountPrefix(prefix string) int {
	count := 0
	for key := range self.Clients {
		if strings.HasPrefix(key, prefix) {
			count += 1
		}
	}
	return count
}

// The map file and its lock.
type mapStore struct {
	path  string
	mutex sync.Mutex
}

// A store for the validated map path (checkMapPath).
func newMapStore(path string) *mapStore {
	return &mapStore{path: path}
}

// Takes the map lock: the in-process mutex, then the exclusive lock
// directory. Returns the function that releases both. When another process
// holds the lock directory, it returns a busyError.
func (self *mapStore) Lock() (func(), error) {
	self.mutex.Lock()
	lockPath := self.path + ".lock"
	if err := os.Mkdir(lockPath, 0o700); err != nil {
		self.mutex.Unlock()
		if errors.Is(err, fs.ErrExist) {
			return nil, &busyError{lockPath: lockPath}
		}
		return nil, fmt.Errorf("could not create %s: %w", lockPath, err)
	}
	return func() {
		os.Remove(lockPath)
		self.mutex.Unlock()
	}, nil
}

// Reads the map; a missing file is an empty map. Keys must match the key
// pattern, client ids must be distinct lowercase uuids, and pending keys must
// be mapped. A field other than version, clients and pending_caps is refused.
func (self *mapStore) Load() (*clientMap, error) {
	data, err := readPrivateFile(self.path)
	if errors.Is(err, fs.ErrNotExist) {
		return newClientMap(), nil
	}
	if err != nil {
		return nil, &mapError{message: fmt.Sprintf("read the client map: %v", err)}
	}
	decoder := json.NewDecoder(bytes.NewReader(data))
	decoder.DisallowUnknownFields()
	loaded := &clientMap{}
	if err := decoder.Decode(loaded); err != nil || decoder.More() || loaded.Version != clientMapVersion || loaded.Clients == nil {
		return nil, &mapError{message: "the client map is not a version 1 map with only version, clients and pending_caps"}
	}
	mappedClientIds := map[string]bool{}
	for key, clientId := range loaded.Clients {
		if !keyPattern.MatchString(key) || !uuidPattern.MatchString(clientId) || mappedClientIds[clientId] {
			return nil, &mapError{message: "the client map maps an invalid key or client"}
		}
		mappedClientIds[clientId] = true
	}
	if loaded.PendingCaps == nil {
		loaded.PendingCaps = []string{}
	}
	pendingKeys := map[string]bool{}
	for _, key := range loaded.PendingCaps {
		if _, mapped := loaded.Clients[key]; !mapped || pendingKeys[key] {
			return nil, &mapError{message: "the client map has an invalid pending_caps entry"}
		}
		pendingKeys[key] = true
	}
	return loaded, nil
}

// Replaces the map privately and atomically.
func (self *mapStore) Save(saved *clientMap) error {
	if saved.PendingCaps == nil {
		saved.PendingCaps = []string{}
	}
	data, err := json.Marshal(saved)
	if err != nil {
		return err
	}
	if err := writePrivateFile(self.path, data); err != nil {
		return fmt.Errorf("save the client map: %w", err)
	}
	return nil
}

// Reads a regular, private file of bounded size. A symlink is refused, so the
// file cannot be redirected elsewhere.
func readPrivateFile(path string) ([]byte, error) {
	info, err := os.Lstat(path)
	if err != nil {
		return nil, err
	}
	if !info.Mode().IsRegular() || byteLimit < info.Size() {
		return nil, errors.New("not a regular file of bounded size")
	}
	if runtime.GOOS != "windows" && info.Mode().Perm()&0o077 != 0 {
		return nil, errors.New("the file must be private to its owner (chmod 600)")
	}
	return os.ReadFile(path)
}

// Replaces a file atomically with owner-only permissions: a private temporary
// file in the same directory is written, synced and renamed over the old one.
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
