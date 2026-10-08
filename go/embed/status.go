// The status that every embed example shows, with the exact text rules of
// EMBED_CONTRACT.md ("Status"): the status, the data used this month and the
// data total. These functions are pure, so the self-test checks them without
// a network, credentials or a device.
package main

import (
	"fmt"
	"strconv"
	"time"

	sdk "github.com/urnetwork/sdk/v2026"
)

const (
	// GUI and Android only: after an auth logout or a 401 or 409 from the
	// token server, until started again
	statusSignedOut = "signed out"
	// GUI and Android only: before start or after stop
	statusStopped = "stopped"
	// the platform disconnected this client for its network's concurrent
	// client limit, and the SDK holds off until the retry time
	statusClientLimit    = "client limit"
	statusPaused         = "paused"
	statusDataCapReached = "data cap reached"
	statusConnected      = "connected"
	statusConnecting     = "connecting"
)

const (
	// a data field before the first cap reading finishes
	dataChecking = "checking"
	// a data field when the first cap reading failed
	dataUnavailable = "unavailable"
	// a data field without a cap; a used count is never shown without one
	dataNoCap = "no cap"
)

// One status snapshot.
type embedStatus struct {
	started   bool
	signedOut bool
	// the SDK's client limit status: sdk.ClientLimitStatusNone or
	// sdk.ClientLimitStatusExceeded
	clientLimitStatus string
	// the end of the client limit hold in unix milliseconds; 0 for none
	clientLimitRetryTime int64
	caps                 capState
	// the window status's ProviderStateAdded
	providersAdded int
}

// The status field: the first rule that applies.
func (self *embedStatus) StatusText() string {
	switch {
	case self.signedOut:
		return statusSignedOut
	case !self.started:
		return statusStopped
	case self.clientLimitStatus == sdk.ClientLimitStatusExceeded:
		return clientLimitText(self.clientLimitRetryTime)
	}
	if reading := self.caps.reading; reading != nil && reading.capped {
		if reading.paused() {
			return statusPaused
		}
		if reading.cappedReason == cappedReasonMonthly {
			if reset, ok := resetText(reading.monthlyPeriodEnd); ok {
				return statusDataCapReached + ", " + reset
			}
		}
		return statusDataCapReached
	}
	if 1 <= self.providersAdded {
		return statusConnected
	}
	return statusConnecting
}

// The "data this month" field.
func (self *embedStatus) MonthlyText() string {
	if self.caps.reading == nil {
		return dataFieldPending(self.caps.attempted)
	}
	return dataFieldText(self.caps.reading.monthlyByteLimit, self.caps.reading.monthlyUsedByteCount)
}

// The "data total" field.
func (self *embedStatus) TotalText() string {
	if self.caps.reading == nil {
		return dataFieldPending(self.caps.attempted)
	}
	return dataFieldText(self.caps.reading.totalByteLimit, self.caps.reading.totalUsedByteCount)
}

// The console status line, for example
// "status: connected | data this month: 1.2 GB of 5.0 GB | data total: no cap".
func (self *embedStatus) String() string {
	return fmt.Sprintf("status: %s | data this month: %s | data total: %s", self.StatusText(), self.MonthlyText(), self.TotalText())
}

// A data field before the first successful reading.
func dataFieldPending(attempted bool) string {
	if attempted {
		return dataUnavailable
	}
	return dataChecking
}

// A data field from a reading: "no cap" without a limit, otherwise
// "<used> of <limit>".
func dataFieldText(byteLimit *int64, usedByteCount int64) string {
	if byteLimit == nil {
		return dataNoCap
	}
	return fmt.Sprintf("%s of %s", formatByteCount(usedByteCount), formatByteCount(*byteLimit))
}

// The client limit status text: "client limit, retry at HH:MM UTC" with the
// retry time (unix milliseconds) rounded up to the next whole minute, so the
// shown time is never before the real retry; "client limit" when the retry
// time is 0.
func clientLimitText(retryTime int64) string {
	if retryTime <= 0 {
		return statusClientLimit
	}
	minuteMillis := int64(60 * 1000)
	retryMinute := (retryTime + minuteMillis - 1) / minuteMillis
	return fmt.Sprintf("%s, retry at %s UTC", statusClientLimit, time.Unix(retryMinute*60, 0).UTC().Format("15:04"))
}

// The monthly reset text from monthly_period_end: "resets YYYY-MM-DD HH:MM
// UTC", in UTC with the seconds rounded up to the next whole minute. false
// when the time does not parse.
func resetText(monthlyPeriodEnd string) (string, bool) {
	periodEnd, err := time.Parse(time.RFC3339Nano, monthlyPeriodEnd)
	if err != nil {
		return "", false
	}
	periodEnd = periodEnd.UTC()
	resetMinute := periodEnd.Truncate(time.Minute)
	if resetMinute.Before(periodEnd) {
		resetMinute = resetMinute.Add(time.Minute)
	}
	return fmt.Sprintf("resets %s UTC", resetMinute.Format("2006-01-02 15:04")), true
}

// Decimal units, because data plans and the Embed plan's monthly data budget
// are sold in them: below 1000 "N B", otherwise kB, MB, GB, TB, PB or EB in
// powers of 1000 with one decimal, moving to the next unit when the rounded
// value reaches 1000.0. The tenth rounds to nearest with ties to even, as
// strconv does: 1250 bytes shows "1.2 kB" and 1750 bytes "1.8 kB".
func formatByteCount(byteCount int64) string {
	if byteCount < 1000 {
		return fmt.Sprintf("%d B", byteCount)
	}
	units := []string{"kB", "MB", "GB", "TB", "PB", "EB"}
	scale := 1000.0
	for i, unit := range units {
		text := strconv.FormatFloat(float64(byteCount)/scale, 'f', 1, 64)
		if value, _ := strconv.ParseFloat(text, 64); value < 1000 || i == len(units)-1 {
			return text + " " + unit
		}
		scale *= 1000
	}
	// unreachable: the last unit always returns
	return ""
}
