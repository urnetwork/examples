// The installation's own data caps (EMBED_CONTRACT.md, "Backend: per-user
// data caps" and "The cap object"). The backend sets them with the root
// credential; the app only reads its own, with its client JWT, from GET
// /network/client-data-cap. Usage is accounted when transfer contracts
// settle, so the used counts lag live traffic.
package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
)

const (
	cappedReasonMonthly = "monthly"
	cappedReasonTotal   = "total"
)

// The server refuses the cap read with this message while the team has not
// enabled Embed for the network (EMBED_CONTRACT.md, "Embed enablement"). It
// clears the last reading, so both data fields read unavailable.
var errEmbedNotEnabled = errors.New("Embed isn't enabled for this network.")

// One cap reading: the fields of the cap object that the status uses.
type capReading struct {
	// nil for no cap
	monthlyByteLimit     *int64
	monthlyUsedByteCount int64
	// RFC 3339; when monthly usage resets
	monthlyPeriodEnd string
	// nil for no cap
	totalByteLimit     *int64
	totalUsedByteCount int64
	capped             bool
	// "monthly", "total", or "" when not capped; another value reads as
	// capped without a reset time
	cappedReason string
}

// Whether the cap that capped_reason names is 0: the backend paused this
// installation.
func (self *capReading) paused() bool {
	switch self.cappedReason {
	case cappedReasonMonthly:
		return self.monthlyByteLimit != nil && *self.monthlyByteLimit == 0
	case cappedReasonTotal:
		return self.totalByteLimit != nil && *self.totalByteLimit == 0
	default:
		return false
	}
}

// Parses a cap object. A null or absent limit is no cap; an error object is
// a failed reading. expectedClientId must be its client.
func parseCapObject(raw []byte, expectedClientId string) (*capReading, error) {
	var parsed struct {
		ClientId             string `json:"client_id"`
		MonthlyByteLimit     *int64 `json:"monthly_byte_limit"`
		MonthlyUsedByteCount int64  `json:"monthly_used_byte_count"`
		MonthlyPeriodEnd     string `json:"monthly_period_end"`
		TotalByteLimit       *int64 `json:"total_byte_limit"`
		TotalUsedByteCount   int64  `json:"total_used_byte_count"`
		Capped               bool   `json:"capped"`
		CappedReason         string `json:"capped_reason"`
		Error                *struct {
			Message string `json:"message"`
		} `json:"error"`
	}
	if err := json.Unmarshal(raw, &parsed); err != nil {
		return nil, errors.New("invalid cap object")
	}
	if parsed.Error != nil {
		if parsed.Error.Message == errEmbedNotEnabled.Error() {
			return nil, errEmbedNotEnabled
		}
		return nil, fmt.Errorf("the cap read failed: %s", oneLine(parsed.Error.Message))
	}
	if parsed.ClientId != expectedClientId {
		return nil, errors.New("the cap object is for another client")
	}
	return &capReading{
		monthlyByteLimit:     parsed.MonthlyByteLimit,
		monthlyUsedByteCount: parsed.MonthlyUsedByteCount,
		monthlyPeriodEnd:     parsed.MonthlyPeriodEnd,
		totalByteLimit:       parsed.TotalByteLimit,
		totalUsedByteCount:   parsed.TotalUsedByteCount,
		capped:               parsed.Capped,
		cappedReason:         parsed.CappedReason,
	}, nil
}

// Reads this installation's caps with its client JWT. A server without the
// cap routes answers 404, which is a failed reading.
func readOwnDataCap(ctx context.Context, client *http.Client, apiOrigin string, clientJwt string, clientId string) (*capReading, error) {
	request, err := http.NewRequestWithContext(ctx, http.MethodGet, apiOrigin+"/network/client-data-cap", nil)
	if err != nil {
		return nil, err
	}
	request.Header.Set("Authorization", "Bearer "+clientJwt)
	response, err := client.Do(request)
	if err != nil {
		return nil, err
	}
	defer response.Body.Close()
	body, err := io.ReadAll(io.LimitReader(response.Body, responseByteLimit+1))
	if err != nil || responseByteLimit < len(body) {
		return nil, errors.New("the cap answer could not be read")
	}
	if response.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("the cap read answered http %d", response.StatusCode)
	}
	return parseCapObject(body, clientId)
}

// The latest cap reading, as the data fields show it: checking until the
// first reading finishes, unavailable if it failed, and the last successful
// reading after that, which a later failure keeps. The Embed-not-enabled
// refusal is not a passing failure: it clears the last reading.
type capState struct {
	// the latest successful reading; nil before the first success
	reading *capReading
	// true once a reading finished, successfully or not
	attempted bool
}

// Records one reading.
func (self *capState) Record(reading *capReading, err error) {
	self.attempted = true
	if errors.Is(err, errEmbedNotEnabled) {
		self.reading = nil
		return
	}
	if err == nil && reading != nil {
		self.reading = reading
	}
}
