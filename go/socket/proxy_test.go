package main

import (
	"context"
	"crypto/tls"
	"crypto/x509"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"net/url"
	"sync"
	"testing"
	"time"
)

type recordingDialer struct {
	target string
	mu     sync.Mutex
	calls  []string
}

func (d *recordingDialer) DialContext(ctx context.Context, network, address string) (net.Conn, error) {
	d.mu.Lock()
	d.calls = append(d.calls, address)
	d.mu.Unlock()
	return (&net.Dialer{}).DialContext(ctx, network, d.target)
}
func TestHTTPAndHTTPSProxyDialOriginalHostThroughFactory(t *testing.T) {
	for _, secure := range []bool{false, true} {
		t.Run(map[bool]string{false: "http", true: "https"}[secure], func(t *testing.T) {
			server := httptest.NewUnstartedServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				if r.Host != "unresolved.example:443" {
					t.Error(r.Host)
				}
				_, _ = io.WriteString(w, "through UR factory")
			}))
			if secure {
				server.StartTLS()
			} else {
				server.Start()
			}
			defer server.Close()
			dialer := &recordingDialer{target: server.Listener.Addr().String()}
			proxy, closeProxy, err := StartProxy(context.Background(), dialer)
			if err != nil {
				t.Fatal(err)
			}
			defer closeProxy()
			proxyURL, _ := url.Parse(proxy)
			transport := &http.Transport{Proxy: http.ProxyURL(proxyURL)}
			if secure {
				roots := x509.NewCertPool()
				roots.AddCert(server.Certificate())
				// The fixture's certificate is issued to example.com; verification stays enabled.
				transport.TLSClientConfig = &tls.Config{RootCAs: roots, ServerName: "example.com"}
			}
			defer transport.CloseIdleConnections()
			client := &http.Client{Transport: transport, Timeout: 5 * time.Second}
			scheme := "http"
			if secure {
				scheme = "https"
			}
			response, err := client.Get(scheme + "://unresolved.example:443/")
			if err != nil {
				t.Fatal(err)
			}
			data, err := io.ReadAll(response.Body)
			response.Body.Close()
			if err != nil || string(data) != "through UR factory" {
				t.Fatalf("%q %v", data, err)
			}
			dialer.mu.Lock()
			defer dialer.mu.Unlock()
			if len(dialer.calls) != 1 || dialer.calls[0] != "unresolved.example:443" {
				t.Fatal(dialer.calls)
			}
		})
	}
}
