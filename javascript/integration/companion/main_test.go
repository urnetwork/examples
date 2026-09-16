package main

import (
	"net/http/httptest"
	"strings"
	"testing"
)

func TestCompanionAuthenticationAndOrigin(t *testing.T) {
	token := strings.Repeat("a", 32)
	for _, tc := range []struct {
		token, origin string
		want          bool
	}{
		{token, "", true}, {token, "http://localhost:5173", true},
		{"", "", false}, {strings.Repeat("b", 32), "", false},
		{token, "https://unrelated.example", false}, {token, "null", false},
	} {
		r := httptest.NewRequest("GET", "http://127.0.0.1/device-rpc?token="+tc.token, nil)
		r.Header.Set("Origin", tc.origin)
		if got := allowedRequest(r, token, "http://localhost:5173"); got != tc.want {
			t.Fatalf("origin %q authentication = %v", tc.origin, got)
		}
	}
}
