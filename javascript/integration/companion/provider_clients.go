// The clients-served count of provider mode (PROVIDER_CONTRACT.md, "Status"),
// with the peer rules of the Go provider's status.go. The companion counts it
// from the device's provider ingress and egress contract details listeners
// and serves it on /provider-status: the JavaScript SDK's remotes run in
// browser state only mode and get no provider contract details over the
// device rpc, so the app cannot count them itself.
package main

import (
	"sync"

	sdk "github.com/urnetwork/sdk/v2026"
)

// Distinct clients are counted up to this many; beyond it the count is a lower
// bound, which the app shows with a trailing "+".
const clientsServedLimit = 100 * 1000

const zeroIdString = "00000000-0000-0000-0000-000000000000"

// The peer of one provider contract, by direction as the SDK's contract
// screens resolve it: the source of a receive (ingress) contract, the
// destination of a send (egress) contract. A path without that client id is
// keyed by its stream id, then by the contract id.
func contractPeerKey(details *sdk.ContractDetails, receive bool) string {
	present := func(id *sdk.Id) bool {
		return id != nil && id.String() != zeroIdString
	}
	if path := details.ContractTransferPath; path != nil {
		peerId := path.DestinationId
		if receive {
			peerId = path.SourceId
		}
		if present(peerId) {
			return peerId.String()
		}
		if present(path.StreamId) {
			return "stream:" + path.StreamId.String()
		}
	}
	if present(details.ContractId) {
		return "contract:" + details.ContractId.String()
	}
	return ""
}

// The distinct clients that held a contract with this provider since the
// companion started, which is when the app that started it started. Safe for
// concurrent use: the SDK delivers contract details on its own goroutines.
type clientsServed struct {
	limit int

	stateLock sync.Mutex
	// set of contractPeerKey values
	peerKeys map[string]bool
	atLimit  bool
}

// An empty count that keeps at most limit distinct peers.
func newClientsServed(limit int) *clientsServed {
	return &clientsServed{
		limit:    limit,
		peerKeys: map[string]bool{},
	}
}

// Counts the peer of one provider contract.
func (self *clientsServed) Add(details *sdk.ContractDetails, receive bool) {
	if details == nil {
		return
	}
	peerKey := contractPeerKey(details, receive)
	if peerKey == "" {
		return
	}
	self.stateLock.Lock()
	defer self.stateLock.Unlock()
	if self.peerKeys[peerKey] {
		return
	}
	if self.limit <= len(self.peerKeys) {
		self.atLimit = true
		return
	}
	self.peerKeys[peerKey] = true
}

// The distinct count, and whether the count stopped at the limit.
func (self *clientsServed) Count() (count int, atLimit bool) {
	self.stateLock.Lock()
	defer self.stateLock.Unlock()
	return len(self.peerKeys), self.atLimit
}

// Feeds one direction of provider contract details into clientsServed.
type contractDetailsListener struct {
	clientsServed *clientsServed
	receive       bool
}

// Counts the contract's peer.
func (self *contractDetailsListener) ContractDetailsChanged(details *sdk.ContractDetails) {
	self.clientsServed.Add(details, self.receive)
}
