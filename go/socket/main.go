package main

import (
	"context"
	"crypto/tls"
	"flag"
	"fmt"
	"io"
	"net"
	"os"
	"os/signal"
	"path/filepath"
	"time"

	"github.com/go-resty/resty/v2"
	sdk "github.com/urnetwork/sdk/v2026"
)

func main() {
	if err := run(); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}
func run() error {
	mode := flag.String("mode", "http", "http, tls, udp, dtls, resty, or proxy")
	target := flag.String("target", "https://example.com/", "HTTP URL or host:port for raw sockets")
	version := flag.Bool("version", false, "load SDK and print its version without connecting")
	flag.Parse()
	if *version {
		fmt.Println(sdk.Version)
		return nil
	}
	jwt := os.Getenv("URNETWORK_JWT")
	if jwt == "" {
		return fmt.Errorf("set URNETWORK_JWT to your account JWT; see README.md")
	}
	ctx, cancel := signal.NotifyContext(context.Background(), os.Interrupt)
	defer cancel()
	manager := sdk.NewNetworkSpaceManagerNoStorage()
	defer manager.Close()
	space := manager.UpdateNetworkSpaceValues(sdk.NewNetworkSpaceKey("ur.network", "main"), &sdk.NetworkSpaceValues{MigrationHostName: "bringyour.com"})
	space.GetApi().SetByJwt(jwt)
	id, err := persistentID()
	if err != nil {
		return err
	}
	device, err := sdk.NewDeviceLocalWithDefaults(space, jwt, "Go socket example", "go", "1", id, false)
	if err != nil {
		return err
	}
	defer device.Close()
	device.SetConnectLocation(&sdk.ConnectLocation{ConnectLocationId: &sdk.ConnectLocationId{BestAvailable: true}})
	switch *mode {
	case "proxy":
		address, closeProxy, err := StartProxy(ctx, device)
		if err != nil {
			return err
		}
		defer closeProxy()
		fmt.Println("URNETWORK_HTTP_PROXY=" + address)
		<-ctx.Done()
		return nil
	case "http", "resty":
		client := HTTPClient(device)
		defer client.CloseIdleConnections()
		if *mode == "resty" {
			response, err := resty.NewWithClient(client).R().SetContext(ctx).Get(*target)
			if err != nil {
				return err
			}
			fmt.Println(response.Status())
			fmt.Println(response.String())
			return nil
		}
		request, err := httpRequest(ctx, *target)
		if err != nil {
			return err
		}
		response, err := client.Do(request)
		if err != nil {
			return err
		}
		defer response.Body.Close()
		fmt.Println(response.Status)
		_, err = io.Copy(os.Stdout, io.LimitReader(response.Body, 1<<20))
		return err
	case "tls", "udp", "dtls":
		network := "tcp"
		if *mode != "tls" {
			network = "udp"
		}
		timeout, stop := context.WithTimeout(ctx, 30*time.Second)
		defer stop()
		var conn net.Conn
		if *mode == "udp" {
			conn, err = device.DialContext(timeout, network, *target)
		} else {
			conn, err = device.DialTlsContext(timeout, network, *target, &tls.Config{})
		}
		if err != nil {
			return err
		}
		defer conn.Close()
		if err = conn.SetDeadline(time.Now().Add(10 * time.Second)); err != nil {
			return err
		}
		if _, err = conn.Write([]byte("hello")); err != nil {
			return err
		}
		buffer := make([]byte, 65535)
		n, err := conn.Read(buffer)
		fmt.Printf("reply from %s: %q\n", conn.RemoteAddr(), buffer[:n])
		return err
	default:
		return fmt.Errorf("unknown mode %q", *mode)
	}
}
func persistentID() (*sdk.Id, error) {
	dir, err := os.UserConfigDir()
	if err != nil {
		return nil, err
	}
	name := filepath.Join(dir, "urnetwork-examples", "go-instance-id")
	if data, err := os.ReadFile(name); err == nil {
		return sdk.ParseId(string(data))
	}
	if err = os.MkdirAll(filepath.Dir(name), 0700); err != nil {
		return nil, err
	}
	id := sdk.NewId()
	f, err := os.OpenFile(name, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0600)
	if os.IsExist(err) {
		data, e := os.ReadFile(name)
		if e != nil {
			return nil, e
		}
		return sdk.ParseId(string(data))
	}
	if err != nil {
		return nil, err
	}
	_, err = f.WriteString(id.String())
	closeErr := f.Close()
	if err != nil {
		return nil, err
	}
	return id, closeErr
}
