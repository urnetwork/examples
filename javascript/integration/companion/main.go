// The native companion owns the connected DeviceLocal. JavaScript owns each
// application's codec, receive callback, peer selection, and acknowledgments.
package main

import (
	"context"
	"crypto/subtle"
	"errors"
	"fmt"
	"log"
	"net"
	"net/http"
	"net/url"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/gorilla/websocket"
	"github.com/urnetwork/sdk"
)

func allowedRequest(r *http.Request, token, origin string) bool {
	got := r.URL.Query().Get("token")
	return len(token) >= 32 && subtle.ConstantTimeCompare([]byte(got), []byte(token)) == 1 && (r.Header.Get("Origin") == "" || r.Header.Get("Origin") == origin)
}

func run() error {
	if len(os.Args) == 2 && os.Args[1] == "--version" {
		version := sdk.Version
		if version == "" {
			version = "development SDK"
		}
		fmt.Println("URnetwork native messaging companion", version)
		return nil
	}
	jwt, instance := os.Getenv("URNETWORK_CLIENT_JWT"), os.Getenv("URNETWORK_INSTANCE_ID")
	token := os.Getenv("URNETWORK_COMPANION_TOKEN")
	if jwt == "" || instance == "" || len(token) < 32 {
		return errors.New("set scoped URNETWORK_CLIENT_JWT, persistent URNETWORK_INSTANCE_ID, and a random URNETWORK_COMPANION_TOKEN of at least 32 characters")
	}
	id, err := sdk.ParseId(instance)
	if err != nil {
		return err
	}
	origin := os.Getenv("URNETWORK_COMPANION_ORIGIN")
	if origin != "" {
		u, err := url.Parse(origin)
		if err != nil || (u.Scheme != "http" && u.Scheme != "https") || u.Host == "" || u.Path != "" || u.RawQuery != "" || u.Fragment != "" || u.User != nil {
			return errors.New("URNETWORK_COMPANION_ORIGIN must be an exact http(s) origin without path")
		}
	}
	address := os.Getenv("URNETWORK_COMPANION_ADDRESS")
	if address == "" {
		address = "127.0.0.1:8787"
	}
	host, _, err := net.SplitHostPort(address)
	if err != nil || net.ParseIP(host) == nil || !net.ParseIP(host).IsLoopback() {
		return errors.New("URNETWORK_COMPANION_ADDRESS must bind a numeric loopback address")
	}
	listener, err := net.Listen("tcp", address)
	if err != nil {
		return err
	}
	defer listener.Close()
	ctx, cancel := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer cancel()
	manager := sdk.NewNetworkSpaceManagerNoStorage()
	defer manager.Close()
	space := manager.UpdateNetworkSpaceValues(sdk.NewNetworkSpaceKey("ur.network", "main"), &sdk.NetworkSpaceValues{MigrationHostName: "bringyour.com"})
	space.GetApi().SetByJwt(jwt)
	device, err := sdk.NewDeviceLocalWithDefaults(space, jwt, "JavaScript messaging companion", "node-companion", "1", id, false)
	if err != nil {
		return err
	}
	defer device.Close()
	device.SetProvideMode(sdk.ProvideModeNetwork)
	readyDeadline := time.Now().Add(30 * time.Second)
	for !device.GetProviderConnected() {
		if time.Now().After(readyDeadline) {
			return errors.New("native provider did not connect within 30 seconds")
		}
		select {
		case <-ctx.Done():
			return nil
		case <-time.After(50 * time.Millisecond):
		}
	}
	rpc := sdk.NewHostedDeviceRpcListener(ctx)
	defer rpc.Close()
	// This transport entry point disables remote route/provide setters. The
	// underlying local device retains its ordinary full provider identity.
	device.StartHostedRpc(rpc, sdk.NewId().String())
	active := make(chan struct{}, 1)
	upgrader := websocket.Upgrader{HandshakeTimeout: 10 * time.Second, CheckOrigin: func(r *http.Request) bool { return allowedRequest(r, token, origin) }}
	mux := http.NewServeMux()
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
		_ = rpc.ServeWs(ws)
	})
	server := &http.Server{Handler: mux, ReadHeaderTimeout: 10 * time.Second, IdleTimeout: 30 * time.Second}
	go func() {
		<-ctx.Done()
		_ = rpc.Close()
		stop, done := context.WithTimeout(context.Background(), 3*time.Second)
		defer done()
		_ = server.Shutdown(stop)
	}()
	fmt.Printf("Companion for client %s, instance %s listening at ws://%s/device-rpc\n", device.GetClientId(), id, listener.Addr())
	err = server.Serve(listener)
	if errors.Is(err, http.ErrServerClosed) {
		return nil
	}
	return err
}

func main() {
	if err := run(); err != nil {
		log.Print(err)
		os.Exit(1)
	}
}
