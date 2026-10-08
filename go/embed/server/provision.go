// Issuing a key's client (EMBED_CONTRACT.md, "Backend: provision clients" and
// the token server's "Behavior"), shared by the token server and the
// provision command. The caller holds the map lock and passes the loaded map.
package main

import (
	"fmt"
)

// What one key's client is issued with: the api, the map store, the default
// caps, and the description and device spec to send. A reissue sends the
// same description and device spec as the provision.
type issuer struct {
	api         *apiClient
	store       *mapStore
	defaults    defaultCaps
	description string
	deviceSpec  string
}

// The client a key holds after an issue.
type issuedClient struct {
	clientId  string
	clientJwt string
}

// The default caps of a new client could not be applied. The key stays in
// pending_caps, and the next issue applies them before it returns a token.
type capsError struct {
	err error
}

// The cause on one line.
func (self *capsError) Error() string {
	return fmt.Sprintf("the default caps could not be applied: %v", self.err)
}

// The cause, so a refused root credential is still a configuration error.
func (self *capsError) Unwrap() error {
	return self.err
}

// Issues key's client in the locked map:
//
//  1. A mapped key is reissued. When its client no longer exists, the
//     mapping is dropped and the key continues as a new key.
//  2. A new key passes beforeNew (the token server's installation limit; nil
//     for none), is provisioned, and is mapped, together with pending_caps
//     when default caps are configured, in one save.
//  3. A key in pending_caps gets the default caps, and leaves pending_caps
//     once they apply. A key never stays uncapped without a record.
func (self *issuer) Issue(clients *clientMap, key string, beforeNew func(*clientMap) error) (*issuedClient, error) {
	issued, err := self.reissueOrProvision(clients, key, beforeNew)
	if err != nil {
		return nil, err
	}
	if clients.Pending(key) {
		if err := self.applyDefaultCaps(clients, key, issued.clientId); err != nil {
			return nil, err
		}
	}
	return issued, nil
}

// Steps 1 and 2 of Issue.
func (self *issuer) reissueOrProvision(clients *clientMap, key string, beforeNew func(*clientMap) error) (*issuedClient, error) {
	if storedClientId, ok := clients.Clients[key]; ok {
		clientId, clientJwt, err := self.api.AuthClient(&authClientArgs{
			ClientId:    storedClientId,
			Description: self.description,
			DeviceSpec:  self.deviceSpec,
		})
		if err == nil {
			return &issuedClient{clientId: clientId, clientJwt: clientJwt}, nil
		}
		if !isClientDoesNotExist(err) {
			return nil, err
		}
		// the client is gone (deactivated after 30 days without connecting,
		// or removed): the key gets a new client
		clients.Remove(key)
		if err := self.store.Save(clients); err != nil {
			return nil, err
		}
	}
	if beforeNew != nil {
		if err := beforeNew(clients); err != nil {
			return nil, err
		}
	}
	clientId, clientJwt, err := self.api.AuthClient(&authClientArgs{
		Description: self.description,
		DeviceSpec:  self.deviceSpec,
	})
	if err != nil {
		return nil, err
	}
	if _, mapped := clients.KeyOf(clientId); mapped {
		return nil, upstreamErrorf("the URnetwork API answered a new client that another key already maps")
	}
	clients.Clients[key] = clientId
	clients.SetPending(key, self.defaults.configured())
	if err := self.store.Save(clients); err != nil {
		return nil, err
	}
	return &issuedClient{clientId: clientId, clientJwt: clientJwt}, nil
}

// Applies the default caps that key owes (only the configured fields), then
// clears its pending record. Without configured defaults there is nothing to
// apply, and the record is cleared.
func (self *issuer) applyDefaultCaps(clients *clientMap, key string, clientId string) error {
	if self.defaults.configured() {
		body, err := self.defaults.body(clientId)
		if err != nil {
			return err
		}
		if _, err := self.api.SetDataCap(clientId, body); err != nil {
			return &capsError{err: err}
		}
	}
	clients.SetPending(key, false)
	return self.store.Save(clients)
}
