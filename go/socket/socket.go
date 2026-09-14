package main

import (
	"context"
	"io"
	"net"
	"net/http"
	"time"
)

// A sdk.Device satisfies Dialer. Keeping this interface local makes the
// integration usable with any compatible implementation and easy to test.
type Dialer interface {
	DialContext(context.Context, string, string) (net.Conn, error)
}

func httpRequest(ctx context.Context, url string) (*http.Request, error) {
	return http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
}

func HTTPClient(device Dialer) *http.Client {
	transport := http.DefaultTransport.(*http.Transport).Clone()
	transport.Proxy = nil
	transport.DialContext = device.DialContext
	transport.ForceAttemptHTTP2 = true
	return &http.Client{Transport: transport, Timeout: 30 * time.Second}
}

func Echo(ctx context.Context, device Dialer, address string) ([]byte, error) {
	conn, err := device.DialContext(ctx, "tcp", address)
	if err != nil {
		return nil, err
	}
	defer conn.Close()
	if err := conn.SetDeadline(time.Now().Add(10 * time.Second)); err != nil {
		return nil, err
	}
	if _, err := io.WriteString(conn, "hello"); err != nil {
		return nil, err
	}
	reply := make([]byte, 5)
	_, err = io.ReadFull(conn, reply)
	return reply, err
}
