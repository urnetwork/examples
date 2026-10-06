// A running provider: the network space manager, the provider device with the
// installation's identity, and the listeners that feed its status. SDK
// listeners run on SDK goroutines, so the status fields they write are guarded
// by the session's stateLock.
package main

import (
	"context"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"sync"
	"time"

	sdk "github.com/urnetwork/sdk/v2026"
)

// How often the status is read, and the longest gap between status lines.
const statusPollInterval = 1 * time.Second
const statusRepeatInterval = 60 * time.Second

// How often the payout wallet is read again. The wallet is fixed; a reread
// shows a mapping that the backend completes while the provider runs.
const walletSyncInterval = 10 * time.Minute

// The device description and spec recorded for this installation's device.
const deviceDescription = "Go provider example"
const deviceSpec = "urnetwork-examples/go-provider"

// One run of the provider. Created by newProviderSession, which starts
// providing; Run shows the status; Close stops providing.
type providerSession struct {
	config        *providerConfig
	manager       *sdk.NetworkSpaceManager
	device        *sdk.DeviceLocal
	clientsServed *clientsServed
	subs          []sdk.Sub

	// closed when the server rejects the client credential
	logout     chan struct{}
	logoutOnce sync.Once

	stateLock sync.Mutex
	// payoutWalletChecking, payoutWalletUnavailable, payoutWalletNotSet or the
	// mapped coldkey
	payoutWallet      string
	payoutWalletScope string
}

// Creates the provider device with the installation's identity, saves a new
// identity on first run, and starts providing publicly.
func newProviderSession(config *providerConfig) (*providerSession, error) {
	instanceId, err := sdk.ParseId(config.instanceId)
	if err != nil {
		return nil, err
	}
	manager := sdk.NewNetworkSpaceManagerNoStorage()
	space := manager.UpdateNetworkSpaceValues(
		sdk.NewNetworkSpaceKey("ur.network", "main"),
		&sdk.NetworkSpaceValues{MigrationHostName: "bringyour.com"},
	)
	space.GetApi().SetByJwt(config.clientJwt)
	var device *sdk.DeviceLocal
	if config.identity != nil {
		device, err = sdk.NewDeviceLocalWithKeyMaterial(space, config.clientJwt, deviceDescription, deviceSpec, "1", instanceId, false, config.identity.keyMaterial())
	} else {
		device, err = sdk.NewDeviceLocalWithDefaults(space, config.clientJwt, deviceDescription, deviceSpec, "1", instanceId, false)
	}
	if err != nil {
		manager.Close()
		return nil, err
	}
	if config.identity == nil {
		// keep the new identity, so later starts present the same provider
		identity := newProviderIdentity(config.clientId, device.GetKeyMaterial())
		if len(identity.ClientKeySeed) != 32 {
			device.Close()
			manager.Close()
			return nil, errors.New("the device has no provider identity to save")
		}
		if err := saveProviderIdentity(config.stateDir, identity); err != nil {
			device.Close()
			manager.Close()
			return nil, fmt.Errorf("save %s: %w", identityFileName, err)
		}
	}

	session := &providerSession{
		config:        config,
		manager:       manager,
		device:        device,
		clientsServed: newClientsServed(clientsServedLimit),
		logout:        make(chan struct{}),
		payoutWallet:  payoutWalletChecking,
	}
	session.subs = []sdk.Sub{
		device.AddJwtRefreshListener(&jwtRefreshListener{session: session}),
		device.AddAuthLogoutListener(&authLogoutListener{session: session}),
		device.AddProviderIngressContractDetailsChangeListener(&contractDetailsListener{clientsServed: session.clientsServed, receive: true}),
		device.AddProviderEgressContractDetailsChangeListener(&contractDetailsListener{clientsServed: session.clientsServed, receive: false}),
	}
	device.SetProvideMode(sdk.ProvideModePublic)
	session.SyncWallet()
	return session, nil
}

// Reads the status from the device getters and the listener state.
func (self *providerSession) Status() *providerStatus {
	device := self.device
	// GetProviderReady also waits for processed client key registration, which
	// default device settings do not enable, so the connected carrier is the
	// readiness signal here
	state := providerState(
		device.GetProvideMode(),
		device.GetProvidePaused(),
		device.GetProvideEnabled(),
		device.GetProviderConnected(),
	)
	var dataProvidedByteCount int64
	if packetStats := device.GetProviderPacketStats(); packetStats != nil {
		// bytes relayed for clients, in both directions, since the device started
		dataProvidedByteCount = packetStats.RemoteEgressByteCount + packetStats.RemoteIngressByteCount
	}
	clientsServed, clientsServedAtLimit := self.clientsServed.Count()
	status := &providerStatus{
		state:                 state,
		clientsServed:         clientsServed,
		clientsServedAtLimit:  clientsServedAtLimit,
		dataProvidedByteCount: dataProvidedByteCount,
	}
	func() {
		self.stateLock.Lock()
		defer self.stateLock.Unlock()
		status.payoutWallet = self.payoutWallet
		status.payoutWalletScope = self.payoutWalletScope
	}()
	return status
}

// Reads the payout wallet (GET /sn/wallet with the client credential). The
// app only displays the wallet: the backend maps it, never the app.
func (self *providerSession) SyncWallet() {
	self.device.SyncSnWallet(&walletSyncCallback{session: self})
}

// Prints status lines until the context ends or the server rejects the
// credential, and returns the process exit code.
func (self *providerSession) Run(ctx context.Context) int {
	lastKey := ""
	var lastPrintTime time.Time
	nextWalletSyncTime := time.Now().Add(walletSyncInterval)
	for {
		status := self.Status()
		now := time.Now()
		if key := status.key(); key != lastKey || statusRepeatInterval <= now.Sub(lastPrintTime) {
			fmt.Println(status.String())
			lastKey = key
			lastPrintTime = now
		}
		if !now.Before(nextWalletSyncTime) {
			self.SyncWallet()
			nextWalletSyncTime = now.Add(walletSyncInterval)
		}
		select {
		case <-ctx.Done():
			return exitStopped
		case <-self.logout:
			fmt.Fprintln(os.Stderr, "the server rejected the client credential; issue a new scoped client JWT from your backend")
			return exitConfig
		case <-time.After(statusPollInterval):
		}
	}
}

// Stops providing and releases the device, then the manager.
func (self *providerSession) Close() {
	self.device.SetProvideMode(sdk.ProvideModeNone)
	for _, sub := range self.subs {
		sub.Close()
	}
	self.device.Close()
	self.manager.Close()
	fmt.Println("status: stopped")
}

// Keeps the refreshed credential, so the next start uses a valid token.
func (self *providerSession) jwtRefreshed(clientJwt string) {
	if err := writePrivateFile(filepath.Join(self.config.stateDir, clientJwtFileName), []byte(clientJwt+"\n")); err != nil {
		// never print the token itself
		fmt.Fprintf(os.Stderr, "could not save the refreshed client credential: %v\n", err)
	}
}

// Ends the run: the server no longer accepts this client's credential.
func (self *providerSession) authLogout() {
	self.logoutOnce.Do(func() {
		close(self.logout)
	})
}

// Records the wallet read. A failure keeps the last known wallet.
func (self *providerSession) walletSynced(result *sdk.SnGetWalletResult, err error) {
	if err != nil || result == nil || result.Error != nil {
		func() {
			self.stateLock.Lock()
			defer self.stateLock.Unlock()
			if self.payoutWallet == payoutWalletChecking {
				self.payoutWallet = payoutWalletUnavailable
			}
		}()
		return
	}
	// the sdk caches the effective wallet before the callback: this client's
	// own mapping, else the network's wallet
	wallet := self.device.GetSnWallet()
	self.stateLock.Lock()
	defer self.stateLock.Unlock()
	if wallet == nil || wallet.ColdkeySs58 == "" {
		self.payoutWallet = payoutWalletNotSet
		self.payoutWalletScope = ""
		return
	}
	self.payoutWallet = wallet.ColdkeySs58
	self.payoutWalletScope = payoutWalletScope(wallet.ClientId, self.config.clientId)
}

// Saves refreshed client credentials.
type jwtRefreshListener struct {
	session *providerSession
}

// Saves the refreshed token.
func (self *jwtRefreshListener) JwtRefreshed(clientJwt string) {
	self.session.jwtRefreshed(clientJwt)
}

// Ends the run when the server rejects the client credential.
type authLogoutListener struct {
	session *providerSession
}

// Ends the run with a credential error.
func (self *authLogoutListener) AuthLogout() {
	self.session.authLogout()
}

// Receives payout wallet reads.
type walletSyncCallback struct {
	session *providerSession
}

// Records the wallet read.
func (self *walletSyncCallback) Result(result *sdk.SnGetWalletResult, err error) {
	self.session.walletSynced(result, err)
}
