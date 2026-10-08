// Embed mode (URNETWORK_COMPANION_EMBED=1). The JavaScript SDK cannot run a
// local Device, so for the JavaScript and TypeScript embed examples and the
// Electron embed app (EMBED_CONTRACT.md, "Platform notes") the companion owns
// the embedded device and the installation state in URNETWORK_EMBED_STATE_DIR,
// connects to the best available location as the installation's client, and
// the app shows the status. The device carries only the app's own traffic and
// does not provide: an embed app leaves the provide mode at its default.
//
// It serves two routes on its numeric loopback address, both authorized by
// URNETWORK_COMPANION_TOKEN like the messaging routes:
//   - /embed-status: json with the client and instance ids, the window
//     status, the client limit status and the contract status, read in
//     process with the full SDK, and the licenses of GetLicenses for the host
//     OS's app kind. It answers from the start, so the app can show
//     "connecting" and "client limit" before the window forms. The licenses
//     are hundreds of kilobytes, so a read with ?licenses=0 leaves them out:
//     the apps read the status every second that way, and the licenses once.
//   - /device-rpc: the SDK device rpc, for what a browser state DeviceRemote
//     gets, also from the start. The remote cannot change the route or the
//     provide settings over it.
//
// The companion waits for the platform without a time limit: under the client
// limit hold the SDK keeps the device off for 15 to 20 minutes and then
// reconnects by itself. Ctrl-C and SIGTERM stop it, and so does a closed
// standard input with URNETWORK_COMPANION_STOP_ON_STDIN_CLOSE=1, so the device
// never outlives the app that shows it. When the server rejects the client
// credential, the companion closes the device and exits with 78.
//
// Exit codes, for supervisors: 0 stopped on request, 78 configuration or
// credential problem (restarting does not help), 1 any other failure.
package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"runtime"
	"strings"
	"sync"
	"syscall"
	"time"

	"github.com/gorilla/websocket"
	sdk "github.com/urnetwork/sdk/v2026"
)

// The companion mode that URNETWORK_COMPANION_EMBED=1 selects.
const companionModeEmbed = companionModeProvider + 1

// The only URNETWORK_COMPANION_EMBED value that selects embed mode.
const companionEmbedOn = "1"

// The device description, spec and app version recorded for this installation's
// device (EMBED_CONTRACT.md, "JavaScript and TypeScript").
const embedDeviceDescription = "JavaScript embed companion"
const embedDeviceSpec = "urnetwork-examples/node-embed-companion"
const embedAppVersion = "1"

// The mode for the two mode settings: URNETWORK_COMPANION_EMBED=1 is embed
// mode, which cannot be combined with URNETWORK_COMPANION_PROVIDE; without it
// URNETWORK_COMPANION_PROVIDE decides as before. Any other embed value is a
// configuration error, so a typo never starts another mode.
func selectCompanionMode(provide string, embed string) (companionMode, error) {
	switch embed {
	case "":
		return parseCompanionMode(provide)
	case companionEmbedOn:
		if provide != "" {
			return companionModeMessaging, errors.New("URNETWORK_COMPANION_EMBED cannot be combined with URNETWORK_COMPANION_PROVIDE")
		}
		return companionModeEmbed, nil
	default:
		return companionModeMessaging, fmt.Errorf("URNETWORK_COMPANION_EMBED must be %q or unset", companionEmbedOn)
	}
}

// The embed companion's settings from its environment. The installation state
// itself is checked by loadEmbedConfig.
type embedCompanionSettings struct {
	stateDir         string
	token            string
	origin           string
	address          string
	stopOnStdinClose bool
}

// Reads and checks the embed companion's settings. Every error is a
// configuration error.
func loadEmbedCompanionSettings(getenv func(string) string) (*embedCompanionSettings, error) {
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
	return &embedCompanionSettings{
		stateDir:         getenv("URNETWORK_EMBED_STATE_DIR"),
		token:            token,
		origin:           origin,
		address:          address,
		stopOnStdinClose: stopOnStdinClose,
	}, nil
}

// The installation state loaded at start (EMBED_CONTRACT.md, "Installation
// state"): the scoped client.jwt that the app obtained and instance-id, which
// the app creates on first run (the companion creates it when it is missing).
// The companion rewrites client.jwt when the SDK refreshes it and keeps the
// SDK's logs in logs/.
type embedConfig struct {
	stateDir  string
	clientJwt string
	// the client_id claim of clientJwt
	clientId   string
	instanceId string
}

// Loads the installation state with the provider mode's rules (private
// directory and files, no symlinks, atomic replacement). Every error is a
// configuration error: restarting does not fix it.
func loadEmbedConfig(stateDir string) (*embedConfig, error) {
	if err := checkStateDirFor(stateDir, "URNETWORK_EMBED_STATE_DIR"); err != nil {
		return nil, err
	}
	clientJwtBytes, err := readPrivateFile(filepath.Join(stateDir, clientJwtFileName))
	if err != nil {
		return nil, fmt.Errorf("read %s from the state directory: %w", clientJwtFileName, err)
	}
	clientJwt := strings.TrimSpace(string(clientJwtBytes))
	clientId, err := parseClientJwtClientId(clientJwt)
	if err != nil {
		return nil, err
	}
	instanceId, err := loadOrCreateInstanceId(stateDir)
	if err != nil {
		return nil, err
	}
	return &embedConfig{
		stateDir:   stateDir,
		clientJwt:  clientJwt,
		clientId:   clientId,
		instanceId: instanceId,
	}, nil
}

// The GetLicenses app kind of the OS the companion runs on: the companion ships
// with desktop apps, so every POSIX OS other than Apple's reads as linux.
func hostLicenseApp(goos string) string {
	switch goos {
	case "darwin", "ios":
		return sdk.LicenseAppApple
	case "windows":
		return sdk.LicenseAppWindows
	case "android":
		return sdk.LicenseAppAndroid
	default:
		return sdk.LicenseAppLinux
	}
}

// The licenses for the host OS as json, an array of the SDK's LicenseInfo with
// the Go field names, exactly as the C ABI's urnet_get_licenses returns them.
// It is marshaled once, since every /embed-status answer carries it.
func embedLicensesJson(goos string) (json.RawMessage, error) {
	licensesJson, err := json.Marshal(sdk.GetLicenses(hostLicenseApp(goos)))
	if err != nil {
		return nil, err
	}
	return json.RawMessage(licensesJson), nil
}

// Runs embed mode and returns the exit code.
func runEmbed(args []string) int {
	switch {
	case len(args) == 1 && args[0] == "--version":
		version := sdk.Version
		if version == "" {
			version = "development SDK"
		}
		fmt.Println("URnetwork native embed companion", version)
		return exitStopped
	case len(args) != 0:
		fmt.Fprintln(os.Stderr, "usage: ur-companion [--version]")
		return exitConfig
	}

	settings, err := loadEmbedCompanionSettings(os.Getenv)
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		return exitConfig
	}
	config, err := loadEmbedConfig(settings.stateDir)
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		return exitConfig
	}
	licensesJson, err := embedLicensesJson(runtime.GOOS)
	if err != nil {
		fmt.Fprintf(os.Stderr, "could not read the licenses: %v\n", err)
		return exitFailure
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
	companion, err := newEmbedCompanion(config)
	if err != nil {
		fmt.Fprintf(os.Stderr, "could not start the embedded device: %v\n", err)
		return exitFailure
	}
	defer companion.Close()
	fmt.Printf("embed client %s, instance %s\n", config.clientId, config.instanceId)
	return companion.Serve(ctx, listener, settings, licensesJson)
}

// One run of the embed companion: the network space manager, the embedded
// device and the listeners that keep the installation state current. Created
// by newEmbedCompanion, which sets the destination; Serve serves the app;
// Close closes the device.
type embedCompanion struct {
	config  *embedConfig
	manager *sdk.NetworkSpaceManager
	device  *sdk.DeviceLocal
	subs    []sdk.Sub

	// closed when the server rejects the client credential
	logout     chan struct{}
	logoutOnce sync.Once
}

// Creates the embedded device for the installation (EMBED_CONTRACT.md, "App
// lifecycle"): the ur.network/main space with the scoped client JWT, the
// device with the provide mode left at its default, the credential listeners,
// and the best available location as the destination of the app's traffic.
func newEmbedCompanion(config *embedConfig) (*embedCompanion, error) {
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
	device, err := sdk.NewDeviceLocalWithDefaults(
		space,
		config.clientJwt,
		embedDeviceDescription,
		embedDeviceSpec,
		embedAppVersion,
		instanceId,
		false,
	)
	if err != nil {
		manager.Close()
		return nil, err
	}
	companion := &embedCompanion{
		config:  config,
		manager: manager,
		device:  device,
		logout:  make(chan struct{}),
	}
	companion.subs = []sdk.Sub{
		device.AddJwtRefreshListener(&embedJwtRefreshListener{companion: companion}),
		device.AddAuthLogoutListener(&embedAuthLogoutListener{companion: companion}),
	}
	device.SetConnectLocation(&sdk.ConnectLocation{
		ConnectLocationId: &sdk.ConnectLocationId{BestAvailable: true},
	})
	return companion, nil
}

// Serves the app until the context ends, the server rejects the client
// credential or the http server fails, and returns the exit code.
func (self *embedCompanion) Serve(ctx context.Context, listener net.Listener, settings *embedCompanionSettings, licensesJson json.RawMessage) int {
	deviceRpc := sdk.NewHostedDeviceRpcListener(ctx)
	// this transport entry point disables the remote route and provide
	// setters, so a remote reads the device but cannot redirect it
	self.device.StartHostedRpc(deviceRpc, sdk.NewId().String())
	httpServer := &http.Server{
		Handler:           newEmbedHttpHandler(self.device, self.config.clientId, self.config.instanceId, licensesJson, deviceRpc, settings.token, settings.origin),
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
	fmt.Printf("companion listening at http://%s/embed-status and ws://%s/device-rpc\n", listener.Addr(), listener.Addr())

	select {
	case <-ctx.Done():
		return exitStopped
	case <-self.logout:
		fmt.Fprintln(os.Stderr, "the server rejected the client credential; obtain a new scoped client JWT from your backend")
		return exitConfig
	case err := <-serveErrs:
		fmt.Fprintf(os.Stderr, "the companion server stopped: %v\n", err)
		return exitFailure
	}
}

// Closes the subscriptions, the device, then the manager.
func (self *embedCompanion) Close() {
	for _, sub := range self.subs {
		sub.Close()
	}
	self.device.Close()
	self.manager.Close()
}

// Keeps the refreshed credential in client.jwt, so the next start and the
// app's cap reads use a valid token.
func (self *embedCompanion) jwtRefreshed(clientJwt string) {
	if err := writePrivateFile(filepath.Join(self.config.stateDir, clientJwtFileName), []byte(clientJwt+"\n")); err != nil {
		// never print the token itself
		fmt.Fprintf(os.Stderr, "could not save the refreshed client credential: %v\n", err)
	}
}

// Ends the run: the server no longer accepts this client's credential.
func (self *embedCompanion) authLogout() {
	self.logoutOnce.Do(func() {
		close(self.logout)
	})
}

// Saves refreshed client credentials.
type embedJwtRefreshListener struct {
	companion *embedCompanion
}

// Saves the refreshed token.
func (self *embedJwtRefreshListener) JwtRefreshed(clientJwt string) {
	self.companion.jwtRefreshed(clientJwt)
}

// Ends the run when the server rejects the client credential.
type embedAuthLogoutListener struct {
	companion *embedCompanion
}

// Ends the run with a credential error.
func (self *embedAuthLogoutListener) AuthLogout() {
	self.companion.authLogout()
}

// The device values behind /embed-status. *sdk.DeviceLocal has them.
type embedStatusSource interface {
	GetWindowStatus() *sdk.WindowStatus
	GetClientLimitStatus() *sdk.ClientLimitStatus
	GetContractStatus() *sdk.ContractStatus
}

// The /embed-status body, with the SDK's Go field names as the C ABI uses them
// (EMBED_CONTRACT.md, "JavaScript and TypeScript"), for example
// {"ClientId":"…","InstanceId":"…","WindowStatus":{…,"ProviderStateAdded":3,…},
// "ClientLimitStatus":{"Status":"","RetryTime":0},"ContractStatus":null,"Licenses":[…]}.
type embedStatusResponse struct {
	ClientId   string `json:"ClientId"`
	InstanceId string `json:"InstanceId"`
	// null before the device's window exists; the app reads connected from
	// ProviderStateAdded
	WindowStatus *sdk.WindowStatus `json:"WindowStatus"`
	// Status "client_limit_exceeded" with the hold's end in RetryTime (unix
	// milliseconds) while the SDK holds the client off for its network's
	// client limit; Status "" and RetryTime 0 otherwise. Never null.
	ClientLimitStatus *sdk.ClientLimitStatus `json:"ClientLimitStatus"`
	// null before the first contract status; a change prompts the app to read
	// its data caps again
	ContractStatus *sdk.ContractStatus `json:"ContractStatus"`
	// GetLicenses for the host OS, as urnet_get_licenses returns them; absent
	// for a read with ?licenses=0
	Licenses json.RawMessage `json:"Licenses,omitempty"`
}

// The embed mode routes. Every route needs the companion token, and the device
// rpc admits one app connection at a time.
func newEmbedHttpHandler(
	statusSource embedStatusSource,
	clientId string,
	instanceId string,
	licensesJson json.RawMessage,
	deviceRpc *sdk.HostedDeviceRpcListener,
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
	mux.HandleFunc("/embed-status", func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodGet || !allowedRequest(r, token, origin) {
			http.Error(w, "unauthorized", http.StatusUnauthorized)
			return
		}
		clientLimitStatus := statusSource.GetClientLimitStatus()
		if clientLimitStatus == nil {
			clientLimitStatus = &sdk.ClientLimitStatus{Status: sdk.ClientLimitStatusNone, RetryTime: 0}
		}
		embedStatus := &embedStatusResponse{
			ClientId:          clientId,
			InstanceId:        instanceId,
			WindowStatus:      statusSource.GetWindowStatus(),
			ClientLimitStatus: clientLimitStatus,
			ContractStatus:    statusSource.GetContractStatus(),
		}
		if r.URL.Query().Get("licenses") != "0" {
			embedStatus.Licenses = licensesJson
		}
		body, err := json.Marshal(embedStatus)
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
