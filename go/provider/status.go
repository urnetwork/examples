// The provider status that every provider example shows, with the exact text
// rules of PROVIDER_CONTRACT.md ("Status"): providing state, clients served,
// data provided and the payout wallet, read only. These functions are pure so
// the self-test can check them without a network or credentials.
package main

import (
	"fmt"
	"math"
	"sync"
	"time"

	sdk "github.com/urnetwork/sdk/v2026"
)

// Shown once at start, and in every example's README. The app that integrates
// a provider owns the consent screen; this example starts without asking.
const consentDisclaimer = `Consent disclaimer: an app that integrates a URnetwork provider must collect the user's consent before it provides.
Providing shares the user's internet connection: other URnetwork users' traffic exits through the user's device and IP address.
This example starts providing without asking, because the consent screen belongs to your app.`

const (
	providerStateStopped = "stopped"
	// the platform disconnected this client for its network's client limit,
	// and the sdk holds off reconnecting until the retry time
	providerStateClientLimit = "client limit"
	providerStateStarting    = "starting"
	providerStatePaused      = "paused"
	providerStateProviding   = "providing"
)

// the payout wallet before the first wallet read finishes
const payoutWalletChecking = "checking"

// the payout wallet when the first wallet read failed
const payoutWalletUnavailable = "unavailable"

// the payout wallet when no wallet is mapped
const payoutWalletNotSet = "not set"

const (
	payoutWalletScopeHotkey          = "hotkey"
	payoutWalletScopeProvider        = "this provider"
	payoutWalletScopeNetwork         = "network"
	payoutWalletScopeAnotherProvider = "another provider"
)

// The consent_scope of a network's hotkey delegation entry in GET /sn/wallet.
// Hotkey delegations come with a later server and sdk change, which adds the
// sdk's own constant; the app only labels the entry.
const snWalletConsentScopeHotkey = "hotkey"

// Distinct clients are counted up to this many; beyond it the count is a lower
// bound, shown with a trailing "+".
const clientsServedLimit = 100 * 1000

const zeroIdString = "00000000-0000-0000-0000-000000000000"

// One status snapshot.
type providerStatus struct {
	state string
	// the end of the client limit hold in unix milliseconds, shown with the
	// client limit state; 0 when unknown
	clientLimitRetryTime  int64
	clientsServed         int
	clientsServedAtLimit  bool
	dataProvidedByteCount int64
	// a coldkey ss58 address, or payoutWalletChecking, payoutWalletUnavailable,
	// payoutWalletNotSet
	payoutWallet string
	// "" unless payoutWallet is an address
	payoutWalletScope string
}

// The status line, for example
// "status: providing | clients served: 3 | data provided: 12.4 MiB | payout wallet: 5Grw... (network)".
func (self *providerStatus) String() string {
	clientsServed := fmt.Sprintf("%d", self.clientsServed)
	if self.clientsServedAtLimit {
		clientsServed += "+"
	}
	payoutWallet := self.payoutWallet
	if self.payoutWalletScope != "" {
		payoutWallet = fmt.Sprintf("%s (%s)", self.payoutWallet, self.payoutWalletScope)
	}
	return fmt.Sprintf(
		"status: %s | clients served: %s | data provided: %s | payout wallet: %s",
		providerStatusText(self.state, self.clientLimitRetryTime),
		clientsServed,
		formatByteCount(self.dataProvidedByteCount),
		payoutWallet,
	)
}

// The fields that change rarely. A change prints a status line at once; the
// data counter alone only prints on the periodic line. The status text carries
// the client limit retry time, so a new retry time prints too.
func (self *providerStatus) key() string {
	return fmt.Sprintf(
		"%s|%d|%t|%s|%s",
		providerStatusText(self.state, self.clientLimitRetryTime),
		self.clientsServed,
		self.clientsServedAtLimit,
		self.payoutWallet,
		self.payoutWalletScope,
	)
}

// The status field text: the state, and for the client limit state the time
// the sdk retries, for example "client limit, retry at 19:05 UTC". The retry
// time (unix milliseconds) is rounded up to the next whole minute in UTC, so
// the shown time is never before the real retry; a retry time of 0 shows
// "client limit".
func providerStatusText(state string, clientLimitRetryTime int64) string {
	if state != providerStateClientLimit || clientLimitRetryTime <= 0 {
		return state
	}
	minuteMillis := int64(60 * 1000)
	retryMinute := (clientLimitRetryTime + minuteMillis - 1) / minuteMillis
	retryTime := time.Unix(retryMinute*60, 0).UTC()
	return fmt.Sprintf("%s, retry at %s UTC", providerStateClientLimit, retryTime.Format("15:04"))
}

// Binary units with one decimal: "0 B", "1023 B", "1.0 KiB", "12.4 MiB". A
// value that rounds to 1024.0 moves to the next unit.
func formatByteCount(byteCount int64) string {
	if byteCount < 1024 {
		return fmt.Sprintf("%d B", byteCount)
	}
	units := []string{"KiB", "MiB", "GiB", "TiB", "PiB", "EiB"}
	value := float64(byteCount) / 1024
	unitIndex := 0
	for unitIndex < len(units)-1 && 1024 <= math.Round(value*10)/10 {
		value /= 1024
		unitIndex += 1
	}
	return fmt.Sprintf("%.1f %s", value, units[unitIndex])
}

// The providing state from the device getters, in this order: stopped unless
// the provide mode is public; client limit while the sdk holds this client off
// for its network's client limit; paused while paused; providing once the
// provider is enabled and its platform carrier is connected; starting
// otherwise.
func providerState(provideMode int, clientLimitStatus string, providePaused bool, provideEnabled bool, providerConnected bool) string {
	switch {
	case provideMode != sdk.ProvideModePublic:
		return providerStateStopped
	case clientLimitStatus == sdk.ClientLimitStatusExceeded:
		return providerStateClientLimit
	case providePaused:
		return providerStatePaused
	case provideEnabled && providerConnected:
		return providerStateProviding
	default:
		return providerStateStarting
	}
}

// Which owner the effective payout wallet belongs to. The consent scope comes
// first: a hotkey delegation is network-level, with no client id, but is not
// the network's wallet. Otherwise by the wallet's client id: this provider's
// own mapping, the network's wallet, or another provider of the network.
func payoutWalletScope(walletConsentScope string, walletClientId string, clientId string) string {
	switch {
	case walletConsentScope == snWalletConsentScopeHotkey:
		return payoutWalletScopeHotkey
	case walletClientId == "":
		return payoutWalletScopeNetwork
	case walletClientId == clientId:
		return payoutWalletScopeProvider
	default:
		return payoutWalletScopeAnotherProvider
	}
}

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

// The distinct clients that held a contract with this provider since the app
// started. Safe for concurrent use: the SDK delivers contract details on its
// own goroutines.
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
