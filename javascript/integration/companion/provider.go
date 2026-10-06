// Provider mode (URNETWORK_COMPANION_PROVIDE=public). The JavaScript SDK cannot
// provide by itself, so for the JavaScript and TypeScript provider examples and
// the Electron provider (PROVIDER_CONTRACT.md, "Platform notes") the companion
// owns the provider device and the installation state in
// URNETWORK_PROVIDER_STATE_DIR (provider_state.go), provides publicly as the
// installation's provider client, and the app shows the status.
//
// It serves two routes on its numeric loopback address, both authorized by
// URNETWORK_COMPANION_TOKEN like the messaging routes:
//   - /provider-status: json with every status value of the contract, read
//     in process with the full SDK: the provide mode, enabled and paused
//     state, the provider connection, the client limit status, the provider
//     packet stats, the clients served (provider_clients.go) and whether
//     /device-rpc is served. The JavaScript SDK binds neither the provider
//     connection nor the client limit status, and its remotes run in browser
//     state only mode, which gets no provider packet stats or provider
//     contract details over the device rpc, so the app reads the status here.
//     It answers from the start, so the app can show "client limit" and
//     "starting" while the provider is not connected.
//   - /device-rpc: the SDK device rpc, served only once the provider has
//     connected to the platform, for what a browser state remote does get.
//     Until then the route answers 503 and the SDK's DeviceRemote dials again.
//
// The companion waits for the provider without a time limit: under the client
// limit hold the SDK keeps the provider off for 15 to 20 minutes and then
// reconnects by itself. Ctrl-C and SIGTERM stop providing. An app that starts
// the companion as a child process sets URNETWORK_COMPANION_STOP_ON_STDIN_CLOSE=1
// and keeps a pipe to its standard input: closing the pipe stops providing, and
// so does the app exiting for any reason, so the provider never outlives the
// app that shows it.
//
// Exit codes, for supervisors: 0 stopped on request, 78 configuration or
// credential problem (restarting does not help), 1 any other failure.
package main

import (
	"context"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"sync"
	"sync/atomic"
	"syscall"
	"time"

	"github.com/gorilla/websocket"
	sdk "github.com/urnetwork/sdk/v2026"
)

const (
	exitStopped = 0
	exitFailure = 1
	// sysexits EX_CONFIG
	exitConfig = 78
)

// The URNETWORK_COMPANION_PROVIDE value that selects provider mode.
const companionProvidePublic = "public"

// How often the companion checks whether the provider has connected.
const providerConnectedPollInterval = 500 * time.Millisecond

// The device description and spec recorded for this installation's device.
const providerDeviceDescription = "JavaScript provider companion"
const providerDeviceSpec = "urnetwork-examples/javascript-provider-companion"

// The provider extender role's two device settings (PROVIDER_CONTRACT.md,
// "Extender role"), passed explicitly and both on. provideExtenderEnabled is
// the embedder's hard switch: false means the role never runs.
// defaultProvideExtender is the setting the device uses until the user sets
// one: false turns the default off.
const provideExtenderEnabled = true
const defaultProvideExtender = true

// The job the companion does, selected by URNETWORK_COMPANION_PROVIDE.
type companionMode int

const (
	// the messaging companion (main.go)
	companionModeMessaging companionMode = iota
	// the provider companion of the provider examples
	companionModeProvider
)

// The mode for a URNETWORK_COMPANION_PROVIDE value: unset is messaging and
// "public" is provider. Anything else is a configuration error, so a typo
// never starts the other mode.
func parseCompanionMode(provide string) (companionMode, error) {
	switch provide {
	case "":
		return companionModeMessaging, nil
	case companionProvidePublic:
		return companionModeProvider, nil
	default:
		return companionModeMessaging, fmt.Errorf("URNETWORK_COMPANION_PROVIDE must be %q or unset", companionProvidePublic)
	}
}

// The provider companion's settings from its environment. The installation
// state itself is checked by loadProviderConfig.
type providerCompanionSettings struct {
	stateDir         string
	token            string
	origin           string
	address          string
	stopOnStdinClose bool
}

// Reads and checks the provider companion's settings. Every error is a
// configuration error.
func loadProviderCompanionSettings(getenv func(string) string) (*providerCompanionSettings, error) {
	token := getenv("URNETWORK_COMPANION_TOKEN")
	if len(token) < 32 {
		return nil, errors.New("set URNETWORK_COMPANION_TOKEN to a random token of at least 32 characters")
	}
	origin := getenv("URNETWORK_COMPANION_ORIGIN")
	if err := checkCompanionOrigin(origin); err != nil {
		return nil, err
	}
	address, err := companionAddress(getenv("URNETWORK_COMPANION_ADDRESS"))
	if err != nil {
		return nil, err
	}
	stopOnStdinClose := false
	switch getenv("URNETWORK_COMPANION_STOP_ON_STDIN_CLOSE") {
	case "":
	case "1":
		stopOnStdinClose = true
	default:
		return nil, errors.New("URNETWORK_COMPANION_STOP_ON_STDIN_CLOSE must be 1 or unset")
	}
	return &providerCompanionSettings{
		stateDir:         getenv("URNETWORK_PROVIDER_STATE_DIR"),
		token:            token,
		origin:           origin,
		address:          address,
		stopOnStdinClose: stopOnStdinClose,
	}, nil
}

// Runs provider mode and returns the exit code.
func runProvider(args []string) int {
	switch {
	case len(args) == 1 && args[0] == "--version":
		version := sdk.Version
		if version == "" {
			version = "development SDK"
		}
		fmt.Println("URnetwork native provider companion", version)
		return exitStopped
	case len(args) != 0:
		fmt.Fprintln(os.Stderr, "usage: ur-companion [--version]")
		return exitConfig
	}

	fmt.Println(consentDisclaimer)
	settings, err := loadProviderCompanionSettings(os.Getenv)
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		return exitConfig
	}
	config, err := loadProviderConfig(settings.stateDir)
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		return exitConfig
	}
	listener, err := net.Listen("tcp", settings.address)
	if err != nil {
		fmt.Fprintf(os.Stderr, "could not listen at %s: %v\n", settings.address, err)
		return exitFailure
	}
	defer listener.Close()
	if err := configureSdkLogs(filepath.Join(config.stateDir, "logs")); err != nil {
		fmt.Fprintf(os.Stderr, "could not set the sdk log directory: %v\n", err)
		return exitFailure
	}
	ctx, cancel := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer cancel()
	if settings.stopOnStdinClose {
		stopOnInputClose(os.Stdin, cancel)
	}
	companion, err := newProviderCompanion(config)
	if err != nil {
		fmt.Fprintf(os.Stderr, "could not start the provider: %v\n", err)
		return exitFailure
	}
	defer companion.Close()
	fmt.Printf("provider client %s, instance %s\n", config.clientId, config.instanceId)
	return companion.Serve(ctx, listener, settings)
}

// Keeps the SDK's log files in logDir, which the SDK bounds (16 MiB files, the
// newest four kept at each start), instead of the system temp directory. The
// console keeps SDK errors; other SDK lines go to the files.
func configureSdkLogs(logDir string) error {
	if err := os.MkdirAll(logDir, 0o700); err != nil {
		return err
	}
	if err := flag.Set("alsologtostderr", "false"); err != nil {
		return err
	}
	if err := flag.Set("stderrthreshold", "ERROR"); err != nil {
		return err
	}
	return sdk.SetLogDir(logDir)
}

// Stops the run when input ends: the app that started the companion closed
// its pipe to the companion's standard input, or exited. The read ends only at
// the end of the input or an error; process exit ends a read that never does.
func stopOnInputClose(input io.Reader, stop context.CancelFunc) {
	go func() {
		defer stop()
		io.Copy(io.Discard, input)
	}()
}

// One run of the provider companion: the network space manager, the provider
// device with the installation's identity, and the listeners that keep the
// installation state current. Created by newProviderCompanion, which starts
// providing; Serve serves the app; Close stops providing.
type providerCompanion struct {
	config  *providerConfig
	manager *sdk.NetworkSpaceManager
	device  *sdk.DeviceLocal
	subs    []sdk.Sub

	// closed when the server rejects the client credential
	logout     chan struct{}
	logoutOnce sync.Once

	// set once the device rpc is started, after the provider first connected
	deviceRpcStarted atomic.Bool

	// counted from the provider contract details listeners since the start
	clientsServed *clientsServed
}

// Creates the provider device with the installation's identity and the
// extender role's settings on, saves a new identity on first run, and starts
// providing publicly. The SDK declares provide intent on the device's platform
// connections by itself while the provide mode is public.
func newProviderCompanion(config *providerConfig) (*providerCompanion, error) {
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
	// one call for both runs: on first run there is no identity, the key
	// material is nil and the device makes a new identity, saved below
	device, err := sdk.NewDeviceLocalWithProvideExtender(
		space,
		config.clientJwt,
		providerDeviceDescription,
		providerDeviceSpec,
		"1",
		instanceId,
		false,
		config.identity.keyMaterial(),
		provideExtenderEnabled,
		defaultProvideExtender,
	)
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

	companion := &providerCompanion{
		config:        config,
		manager:       manager,
		device:        device,
		logout:        make(chan struct{}),
		clientsServed: newClientsServed(clientsServedLimit),
	}
	companion.subs = []sdk.Sub{
		device.AddJwtRefreshListener(&jwtRefreshListener{companion: companion}),
		device.AddAuthLogoutListener(&authLogoutListener{companion: companion}),
		device.AddProviderIngressContractDetailsChangeListener(&contractDetailsListener{clientsServed: companion.clientsServed, receive: true}),
		device.AddProviderEgressContractDetailsChangeListener(&contractDetailsListener{clientsServed: companion.clientsServed, receive: false}),
	}
	device.SetProvideMode(sdk.ProvideModePublic)
	return companion, nil
}

// Serves the app until the context ends, the server rejects the client
// credential or the http server fails, and returns the exit code. Starts the
// device rpc the first time the provider is connected.
func (self *providerCompanion) Serve(ctx context.Context, listener net.Listener, settings *providerCompanionSettings) int {
	deviceRpc := sdk.NewHostedDeviceRpcListener(ctx)
	httpServer := &http.Server{
		Handler:           newProviderHttpHandler(self.device, self.clientsServed, deviceRpc, self.deviceRpcStarted.Load, settings.token, settings.origin),
		ReadHeaderTimeout: 10 * time.Second,
		IdleTimeout:       30 * time.Second,
	}
	serveErrs := make(chan error, 1)
	go func() {
		serveErrs <- httpServer.Serve(listener)
	}()
	defer func() {
		// end the rpc sessions first: their handlers wait on the listener
		deviceRpc.Close()
		shutdownCtx, shutdownCancel := context.WithTimeout(context.Background(), 3*time.Second)
		defer shutdownCancel()
		httpServer.Shutdown(shutdownCtx)
	}()
	fmt.Printf("companion listening at http://%s/provider-status and ws://%s/device-rpc\n", listener.Addr(), listener.Addr())

	for {
		if !self.deviceRpcStarted.Load() && self.device.GetProviderConnected() {
			// this transport entry point disables the remote route and provide
			// setters, so the app reads the provider but cannot change it
			self.device.StartHostedRpc(deviceRpc, sdk.NewId().String())
			self.deviceRpcStarted.Store(true)
			fmt.Println("provider connected; serving the device rpc")
		}
		select {
		case <-ctx.Done():
			return exitStopped
		case <-self.logout:
			fmt.Fprintln(os.Stderr, "the server rejected the client credential; issue a new scoped client JWT from your backend")
			return exitConfig
		case err := <-serveErrs:
			fmt.Fprintf(os.Stderr, "the companion server stopped: %v\n", err)
			return exitFailure
		case <-time.After(providerConnectedPollInterval):
		}
	}
}

// Stops providing and releases the device, then the manager.
func (self *providerCompanion) Close() {
	self.device.SetProvideMode(sdk.ProvideModeNone)
	for _, sub := range self.subs {
		sub.Close()
	}
	self.device.Close()
	self.manager.Close()
}

// Keeps the refreshed credential, so the next start, and the app's wallet
// read, use a valid token.
func (self *providerCompanion) jwtRefreshed(clientJwt string) {
	if err := writePrivateFile(filepath.Join(self.config.stateDir, clientJwtFileName), []byte(clientJwt+"\n")); err != nil {
		// never print the token itself
		fmt.Fprintf(os.Stderr, "could not save the refreshed client credential: %v\n", err)
	}
}

// Ends the run: the server no longer accepts this client's credential.
func (self *providerCompanion) authLogout() {
	self.logoutOnce.Do(func() {
		close(self.logout)
	})
}

// Saves refreshed client credentials.
type jwtRefreshListener struct {
	companion *providerCompanion
}

// Saves the refreshed token.
func (self *jwtRefreshListener) JwtRefreshed(clientJwt string) {
	self.companion.jwtRefreshed(clientJwt)
}

// Ends the run when the server rejects the client credential.
type authLogoutListener struct {
	companion *providerCompanion
}

// Ends the run with a credential error.
func (self *authLogoutListener) AuthLogout() {
	self.companion.authLogout()
}

// The device values behind /provider-status. *sdk.DeviceLocal has them.
type providerStatusSource interface {
	GetProvideMode() sdk.ProvideMode
	GetProvideEnabled() bool
	GetProvidePaused() bool
	GetProviderConnected() bool
	GetClientLimitStatus() *sdk.ClientLimitStatus
	GetProviderPacketStats() *sdk.PacketStats
}

// The /provider-status body, with the SDK's Go field names as the C ABI uses
// them (PROVIDER_CONTRACT.md, "App lifecycle"), for example
// {"ProvideMode":3,"ProvideEnabled":true,"ProvidePaused":false,"ProviderConnected":true,
// "ClientLimitStatus":{"Status":"","RetryTime":0},"ProviderPacketStats":{"RemoteEgressPacketCount":4,
// "RemoteEgressByteCount":5,...},"ClientsServed":3,"ClientsServedAtLimit":false,"DeviceRpcStarted":true}.
type providerStatusResponse struct {
	// sdk.ProvideModePublic (3) while providing publicly
	ProvideMode sdk.ProvideMode `json:"ProvideMode"`
	// GetProvideEnabled: the device has a provider
	ProvideEnabled bool `json:"ProvideEnabled"`
	// GetProvidePaused
	ProvidePaused bool `json:"ProvidePaused"`
	// the provider's platform carrier has a registered route
	ProviderConnected bool `json:"ProviderConnected"`
	// Status "client_limit_exceeded" with the hold's end in RetryTime (unix
	// milliseconds) while the SDK holds the client off for its network's
	// client limit; Status "" and RetryTime 0 otherwise
	ClientLimitStatus *sdk.ClientLimitStatus `json:"ClientLimitStatus"`
	// GetProviderPacketStats, with every field of sdk.PacketStats; null without
	// a provider. Data provided is RemoteEgressByteCount plus
	// RemoteIngressByteCount: bytes relayed for clients since the start.
	ProviderPacketStats *sdk.PacketStats `json:"ProviderPacketStats"`
	// distinct client peers of provider contracts since the start, at most
	// clientsServedLimit
	ClientsServed int `json:"ClientsServed"`
	// the count stopped at clientsServedLimit and is a lower bound
	ClientsServedAtLimit bool `json:"ClientsServedAtLimit"`
	// /device-rpc is served
	DeviceRpcStarted bool `json:"DeviceRpcStarted"`
}

// The provider mode routes. Every route needs the companion token, and the
// device rpc admits one app connection at a time.
func newProviderHttpHandler(
	statusSource providerStatusSource,
	served *clientsServed,
	deviceRpc *sdk.HostedDeviceRpcListener,
	deviceRpcStarted func() bool,
	token string,
	origin string,
) http.Handler {
	active := make(chan struct{}, 1)
	upgrader := websocket.Upgrader{
		HandshakeTimeout: 10 * time.Second,
		CheckOrigin: func(r *http.Request) bool {
			return allowedRequest(r, token, origin)
		},
	}
	mux := http.NewServeMux()
	mux.HandleFunc("/provider-status", func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodGet || !allowedRequest(r, token, origin) {
			http.Error(w, "unauthorized", http.StatusUnauthorized)
			return
		}
		clientsServedCount, clientsServedAtLimit := served.Count()
		providerStatus := &providerStatusResponse{
			ProvideMode:          statusSource.GetProvideMode(),
			ProvideEnabled:       statusSource.GetProvideEnabled(),
			ProvidePaused:        statusSource.GetProvidePaused(),
			ProviderConnected:    statusSource.GetProviderConnected(),
			ClientLimitStatus:    statusSource.GetClientLimitStatus(),
			ProviderPacketStats:  statusSource.GetProviderPacketStats(),
			ClientsServed:        clientsServedCount,
			ClientsServedAtLimit: clientsServedAtLimit,
			DeviceRpcStarted:     deviceRpcStarted(),
		}
		body, err := json.Marshal(providerStatus)
		if err != nil {
			http.Error(w, "status unavailable", http.StatusInternalServerError)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		w.Header().Set("Cache-Control", "no-store")
		w.Write(body)
	})
	mux.HandleFunc("/device-rpc", func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodGet || !allowedRequest(r, token, origin) {
			http.Error(w, "unauthorized", http.StatusUnauthorized)
			return
		}
		if !deviceRpcStarted() {
			http.Error(w, "the provider is not connected yet", http.StatusServiceUnavailable)
			return
		}
		select {
		case active <- struct{}{}:
			defer func() { <-active }()
		default:
			http.Error(w, "companion already in use", http.StatusConflict)
			return
		}
		ws, err := upgrader.Upgrade(w, r, nil)
		if err != nil {
			return
		}
		defer ws.Close()
		deviceRpc.ServeWs(ws)
	})
	return mux
}
