package main

import (
	"context"
	"errors"
	"io"
	"net"
	"net/http"
	"strings"
	"time"
)

// StartProxy adapts clients that cannot accept a userspace stream. The local
// kernel listener is loopback-only; every destination dial uses device.
// It is an application example, not a Device listener/server API.
func StartProxy(parent context.Context, device Dialer) (proxyURL string, closeProxy func() error, err error) {
	listener, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		return "", nil, err
	}
	ctx, cancel := context.WithCancel(parent)
	transport := http.DefaultTransport.(*http.Transport).Clone()
	transport.Proxy = nil
	transport.DialContext = device.DialContext
	handler := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method == http.MethodConnect {
			if _, _, err := net.SplitHostPort(r.Host); err != nil {
				http.Error(w, "CONNECT requires host:port", http.StatusBadRequest)
				return
			}
			dialCtx, cancelDial := context.WithTimeout(r.Context(), 30*time.Second)
			upstream, err := device.DialContext(dialCtx, "tcp", r.Host)
			cancelDial()
			if err != nil {
				http.Error(w, "destination unavailable", http.StatusBadGateway)
				return
			}
			defer upstream.Close()
			hijacker, ok := w.(http.Hijacker)
			if !ok {
				http.Error(w, "CONNECT requires HTTP/1.1", http.StatusHTTPVersionNotSupported)
				return
			}
			client, buffered, err := hijacker.Hijack()
			if err != nil {
				return
			}
			defer client.Close()
			stop := context.AfterFunc(ctx, func() { client.Close(); upstream.Close() })
			defer stop()
			if _, err = buffered.WriteString("HTTP/1.1 200 Connection Established\r\n\r\n"); err != nil {
				return
			}
			if err = buffered.Flush(); err != nil {
				return
			}
			done := make(chan struct{})
			go func() {
				defer close(done)
				_, _ = io.Copy(upstream, buffered)
				// An application closing its write half can still receive a reply.
				if half, ok := upstream.(interface{ CloseWrite() error }); ok {
					_ = half.CloseWrite()
				} else {
					_ = upstream.Close()
				}
			}()
			_, _ = io.Copy(client, upstream)
			_ = client.Close()
			_ = upstream.Close()
			<-done
			return
		}
		if r.URL.Scheme != "http" || r.URL.Host == "" || r.URL.User != nil {
			http.Error(w, "absolute HTTP proxy URL required", http.StatusBadRequest)
			return
		}
		out := r.Clone(r.Context())
		out.RequestURI = ""
		out.Header = r.Header.Clone()
		removeHopHeaders(out.Header)
		resp, err := transport.RoundTrip(out)
		if err != nil {
			http.Error(w, "destination unavailable", http.StatusBadGateway)
			return
		}
		defer resp.Body.Close()
		removeHopHeaders(resp.Header)
		for name, values := range resp.Header {
			for _, value := range values {
				w.Header().Add(name, value)
			}
		}
		w.WriteHeader(resp.StatusCode)
		_, _ = io.Copy(w, resp.Body)
	})
	server := &http.Server{Handler: handler, ReadHeaderTimeout: 10 * time.Second, BaseContext: func(net.Listener) context.Context { return ctx }}
	go func() {
		<-ctx.Done()
		_ = server.Close()
		transport.CloseIdleConnections()
	}()
	go func() {
		if err := server.Serve(listener); err != nil && !errors.Is(err, http.ErrServerClosed) {
			cancel()
		}
	}()
	return "http://" + listener.Addr().String(), func() error { cancel(); return server.Close() }, nil
}

func removeHopHeaders(header http.Header) {
	for _, value := range header.Values("Connection") {
		for _, name := range strings.Split(value, ",") {
			header.Del(strings.TrimSpace(name))
		}
	}
	for _, name := range []string{"Connection", "Proxy-Connection", "Proxy-Authenticate", "Proxy-Authorization", "Keep-Alive", "TE", "Trailer", "Transfer-Encoding", "Upgrade"} {
		header.Del(name)
	}
}
