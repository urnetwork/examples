// Issuing a key's client (EMBED_CONTRACT.md, "Backend: provision clients" and
// the token server's "Behavior"), shared by the token server and the
// provision command. The caller holds the map lock and passes the loaded map.
package main

import (
	"errors"
	"fmt"
)

// What one key's client is issued with: the api, the map store, the default
// caps and ACL group, and the description and device spec to send. A reissue
// sends the same description and device spec as the provision.
type issuer struct {
	api         *apiClient
	store       *mapStore
	defaults    defaultCaps
	aclGroup    string
	description string
	deviceSpec  string
}

// The client a key holds after an issue.
type issuedClient struct {
	clientId  string
	clientJwt string
	// the server predates ACL groups, so the key still owes its default ACL
	// group (pending_acl keeps it, and a later issue applies it)
	aclUnsupported bool
	// Embed isn't enabled for the network, so the key still owes its default
	// ACL group and caps (pending_acl and pending_caps keep it, and an issue
	// after the team enables Embed applies them)
	embedNotEnabled bool
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

// The default ACL group of a new client could not be applied. The key stays
// in pending_acl, and the next issue applies it before it returns a token.
type aclError struct {
	err error
}

// The cause on one line.
func (self *aclError) Error() string {
	return fmt.Sprintf("the default ACL group could not be applied: %v", self.err)
}

// The cause, so a refused root credential is still a configuration error.
func (self *aclError) Unwrap() error {
	return self.err
}

// Issues key's client in the locked map:
//
//  1. A mapped key is reissued. When its client no longer exists, the
//     mapping is dropped and the key continues as a new key.
//  2. A new key passes beforeNew (the token server's installation limit; nil
//     for none), is provisioned, and is mapped, together with pending_acl
//     when the default ACL group is isolated and pending_caps when default
//     caps are configured, in one save.
//  3. A key in pending_acl gets the default ACL group and leaves pending_acl
//     once it applies; on a server without ACL groups it stays there and the
//     issued client says so. A key in pending_caps then gets the default
//     caps. A key never stays in the wrong group or uncapped without a record.
func (self *issuer) Issue(clients *clientMap, key string, beforeNew func(*clientMap) error) (*issuedClient, error) {
	issued, err := self.reissueOrProvision(clients, key, beforeNew)
	if err != nil {
		return nil, err
	}
	if clients.AclPending(key) {
		unsupported, notEnabled, err := self.applyDefaultAclGroup(clients, key, issued.clientId)
		if err != nil {
			return nil, err
		}
		issued.aclUnsupported = unsupported
		issued.embedNotEnabled = notEnabled
	}
	if clients.Pending(key) {
		notEnabled, err := self.applyDefaultCaps(clients, key, issued.clientId)
		if err != nil {
			return nil, err
		}
		issued.embedNotEnabled = issued.embedNotEnabled || notEnabled
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
	clients.SetAclPending(key, self.aclGroup == aclGroupIsolated)
	clients.SetPending(key, self.defaults.configured())
	if err := self.store.Save(clients); err != nil {
		return nil, err
	}
	return &issuedClient{clientId: clientId, clientJwt: clientJwt}, nil
}

// Applies the default ACL group that key owes, then clears its pending
// record. A new client is already in the default group, so a default of
// "default" only clears the record. The record stays on a server without ACL
// groups (unsupported) and while Embed isn't enabled for the network
// (notEnabled).
func (self *issuer) applyDefaultAclGroup(clients *clientMap, key string, clientId string) (unsupported bool, notEnabled bool, err error) {
	if self.aclGroup == aclGroupIsolated {
		_, err := self.api.SetAclGroup(clientId, aclGroupIsolated)
		if errors.Is(err, errAclUnsupported) {
			return true, false, nil
		}
		if isEmbedNotEnabled(err) {
			return false, true, nil
		}
		if err != nil {
			return false, false, &aclError{err: err}
		}
	}
	clients.SetAclPending(key, false)
	return false, false, self.store.Save(clients)
}

// Applies the default caps that key owes (only the configured fields), then
// clears its pending record. Without configured defaults there is nothing to
// apply, and the record is cleared. While Embed isn't enabled for the network
// the record stays and it returns true.
func (self *issuer) applyDefaultCaps(clients *clientMap, key string, clientId string) (bool, error) {
	if self.defaults.configured() {
		body, err := self.defaults.body(clientId)
		if err != nil {
			return false, err
		}
		if _, err := self.api.SetDataCap(clientId, body); err != nil {
			if isEmbedNotEnabled(err) {
				return true, nil
			}
			return false, &capsError{err: err}
		}
	}
	clients.SetPending(key, false)
	return false, self.store.Save(clients)
}
