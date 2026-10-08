// A running embedded Device (EMBED_CONTRACT.md, "App lifecycle"): the network
// space manager, the local Device with the installation's client JWT, the
// listeners that keep the credential and refresh the caps, and the status
// loop. SDK listeners run on SDK goroutines, so the fields they write are
// guarded by the session's stateLock.
package main

import (
	"context"
	"fmt"
	"net/http"
	"os"
	"sync"
	"time"

	sdk "github.com/urnetwork/sdk/v2026"
)

// How often the status is read, and the longest gap between status lines.
const statusPollInterval = 1 * time.Second
const statusRepeatInterval = 60 * time.Second

// How often the caps are read again. A contract status change also reads
// them, within 5 seconds.
const capReadInterval = 5 * time.Minute

// The device description and spec recorded for this installation's device.
const deviceDescription = "Go embed example"
const deviceSpec = "urnetwork-examples/go-embed"
const appVersion = "1"

// One run of the embedded Device. Created by newEmbedSession, which starts
// the Device; Run shows the status; Close stops it.
type embedSession struct {
	config     *embedConfig
	clientId   string
	manager    *sdk.NetworkSpaceManager
	device     *sdk.DeviceLocal
	subs       []sdk.Sub
	httpClient *http.Client

	// closed when the server rejects the client credential
	logout     chan struct{}
	logoutOnce sync.Once
	// a contract status change asks for a cap read
	capRefresh chan struct{}

	stateLock sync.Mutex
	// the latest client JWT; the SDK refreshes it
	clientJwt string
	caps      capState
}

// Creates the local Device with the client JWT, adds the listeners and sets
// the connect location to best available, the destination of the app's own
// traffic. An embed app does not provide: the provide mode stays at its
// default.
func newEmbedSession(config *embedConfig, credential *clientCredential) (*embedSession, error) {
	instanceId, err := sdk.ParseId(config.instanceId)
	if err != nil {
		return nil, err
	}
	manager := sdk.NewNetworkSpaceManagerNoStorage()
	space := manager.UpdateNetworkSpaceValues(
		sdk.NewNetworkSpaceKey("ur.network", "main"),
		&sdk.NetworkSpaceValues{MigrationHostName: "bringyour.com"},
	)
	space.GetApi().SetByJwt(credential.clientJwt)
	device, err := sdk.NewDeviceLocalWithDefaults(
		space,
		credential.clientJwt,
		deviceDescription,
		deviceSpec,
		appVersion,
		instanceId,
		false,
	)
	if err != nil {
		manager.Close()
		return nil, err
	}
	session := &embedSession{
		config:     config,
		clientId:   credential.clientId,
		manager:    manager,
		device:     device,
		httpClient: newHttpClient(),
		logout:     make(chan struct{}),
		capRefresh: make(chan struct{}, 1),
		clientJwt:  credential.clientJwt,
	}
	if credential.firstCapReading != nil {
		// the token server's data_cap serves as the first reading
		session.caps.Record(credential.firstCapReading, nil)
	}
	session.subs = []sdk.Sub{
		device.AddJwtRefreshListener(&jwtRefreshListener{session: session}),
		device.AddAuthLogoutListener(&authLogoutListener{session: session}),
		device.AddContractStatusChangeListener(&contractStatusListener{session: session}),
	}
	device.SetConnectLocation(&sdk.ConnectLocation{
		ConnectLocationId: &sdk.ConnectLocationId{BestAvailable: true},
	})
	return session, nil
}

// Reads the status from the device getters and the cap state.
func (self *embedSession) Status() *embedStatus {
	status := &embedStatus{started: true}
	// "client_limit_exceeded" with the hold's end in RetryTime while the
	// platform holds this client off for its network's concurrent client limit
	if clientLimitStatus := self.device.GetClientLimitStatus(); clientLimitStatus != nil {
		status.clientLimitStatus = clientLimitStatus.Status
		status.clientLimitRetryTime = clientLimitStatus.RetryTime
	}
	// nil before the window exists
	if windowStatus := self.device.GetWindowStatus(); windowStatus != nil {
		status.providersAdded = windowStatus.ProviderStateAdded
	}
	self.stateLock.Lock()
	defer self.stateLock.Unlock()
	status.caps = self.caps
	return status
}

// Prints the status until the context ends or the server rejects the
// credential, and returns the process exit code. A line prints when any
// field's text changes, and otherwise once a minute.
func (self *embedSession) Run(ctx context.Context) int {
	readerCtx, stopReader := context.WithCancel(ctx)
	defer stopReader()
	go self.readCaps(readerCtx)
	lastLine := ""
	var lastPrintTime time.Time
	for {
		line := self.Status().String()
		now := time.Now()
		if line != lastLine || statusRepeatInterval <= now.Sub(lastPrintTime) {
			fmt.Println(line)
			lastLine = line
			lastPrintTime = now
		}
		select {
		case <-ctx.Done():
			return exitStopped
		case <-self.logout:
			fmt.Fprintln(os.Stderr, "the server rejected the client credential; sign in again to get a new client JWT from your backend")
			return exitConfig
		case <-time.After(statusPollInterval):
		}
	}
}

// Reads the caps at start (unless the token server's data_cap is the first
// reading), every 5 minutes and after a contract status change.
func (self *embedSession) readCaps(ctx context.Context) {
	haveFirstReading := func() bool {
		self.stateLock.Lock()
		defer self.stateLock.Unlock()
		return self.caps.attempted
	}()
	if !haveFirstReading {
		self.readCapsOnce(ctx)
	}
	for {
		select {
		case <-ctx.Done():
			return
		case <-self.capRefresh:
		case <-time.After(capReadInterval):
		}
		self.readCapsOnce(ctx)
	}
}

// Reads the caps once with the latest client JWT and records the reading.
func (self *embedSession) readCapsOnce(ctx context.Context) {
	clientJwt := func() string {
		self.stateLock.Lock()
		defer self.stateLock.Unlock()
		return self.clientJwt
	}()
	reading, err := readOwnDataCap(ctx, self.httpClient, self.config.apiOrigin, clientJwt, self.clientId)
	select {
	case <-ctx.Done():
		// stopping: a canceled read is not a failed reading
		return
	default:
	}
	self.stateLock.Lock()
	defer self.stateLock.Unlock()
	self.caps.Record(reading, err)
}

// Stops the Device: closes the subscriptions, the device, then the manager.
// The final flush keeps a short run's last SDK log lines in the logs
// directory.
func (self *embedSession) Close() {
	for _, sub := range self.subs {
		sub.Close()
	}
	self.device.Close()
	self.manager.Close()
	sdk.FlushGlog()
}

// Keeps the refreshed credential, so the next start and the cap reads use a
// valid token.
func (self *embedSession) jwtRefreshed(clientJwt string) {
	func() {
		self.stateLock.Lock()
		defer self.stateLock.Unlock()
		self.clientJwt = clientJwt
	}()
	if err := saveClientJwt(self.config.stateDir, clientJwt); err != nil {
		// never print the token itself
		fmt.Fprintf(os.Stderr, "could not save the refreshed client credential: %v\n", err)
	}
}

// Ends the run: the server no longer accepts this client's credential.
func (self *embedSession) authLogout() {
	self.logoutOnce.Do(func() {
		close(self.logout)
	})
}

// Asks for a cap read; requests while one is pending coalesce.
func (self *embedSession) contractStatusChanged() {
	select {
	case self.capRefresh <- struct{}{}:
	default:
	}
}

// Saves refreshed client credentials.
type jwtRefreshListener struct {
	session *embedSession
}

// Saves the refreshed token.
func (self *jwtRefreshListener) JwtRefreshed(clientJwt string) {
	self.session.jwtRefreshed(clientJwt)
}

// Ends the run when the server rejects the client credential.
type authLogoutListener struct {
	session *embedSession
}

// Ends the run with a credential error.
func (self *authLogoutListener) AuthLogout() {
	self.session.authLogout()
}

// Reads the caps again when the contract status changes, for example when a
// cap stops new contracts.
type contractStatusListener struct {
	session *embedSession
}

// Asks for a cap read.
func (self *contractStatusListener) ContractStatusChanged(contractStatus *sdk.ContractStatus) {
	self.session.contractStatusChanged()
}
