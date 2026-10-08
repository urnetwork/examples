// The contract's status vectors for the window: the data amounts, the reset
// and client limit text, the data fields and the status rules, including the
// GUI's signed out and stopped.

import {test} from "node:test";
import assert from "node:assert/strict";
import {CapReadings, clientLimitText, dataFields, formatDataAmount, parseCapObject, resetText, statusText} from "../status.mjs";

// A cap object over no caps and no usage.
const cap = fields => parseCapObject({monthly_period_end: "2026-11-01T00:00:00Z", capped: false, capped_reason: "", ...fields});

test("data amounts are decimal with one decimal and ties to even", () => {
  for (const [byteCount, text] of [[0, "0 B"], [999, "999 B"], [1000, "1.0 kB"], [999949, "999.9 kB"], [1250, "1.2 kB"], [1750, "1.8 kB"],
    [999999, "1.0 MB"], [1234567890, "1.2 GB"], [5000000000, "5.0 GB"], [10000000000, "10.0 GB"], [3000000000000, "3.0 TB"]]) {
    assert.equal(formatDataAmount(byteCount), text);
  }
});

test("the reset and client limit times round up to the minute in UTC", () => {
  assert.equal(resetText("2026-11-01T00:00:00Z"), "resets 2026-11-01 00:00 UTC");
  assert.equal(resetText("2026-10-31T23:59:00.001Z"), "resets 2026-11-01 00:00 UTC");
  assert.equal(resetText("2026-10-31T19:00:00-05:00"), "resets 2026-11-01 00:00 UTC");
  assert.equal(resetText("soon"), null);
  assert.equal(clientLimitText(1791313500000), "client limit, retry at 19:05 UTC");
  assert.equal(clientLimitText(1791313440001), "client limit, retry at 19:05 UTC");
  assert.equal(clientLimitText(0), "client limit");
});

test("the status rules apply in the contract's order", () => {
  for (const [input, status] of [
    [{started: false, signedOut: false}, "stopped"],
    [{started: false, signedOut: true}, "signed out"],
    [{clientLimitStatus: "client_limit_exceeded", clientLimitRetryTime: 1791313500000, cap: cap({monthly_byte_limit: 0, capped: true, capped_reason: "monthly"}), providerStateAdded: 3}, "client limit, retry at 19:05 UTC"],
    [{cap: cap({monthly_byte_limit: 0, capped: true, capped_reason: "monthly"}), providerStateAdded: 3}, "paused"],
    [{cap: cap({total_byte_limit: 0, monthly_byte_limit: 5000000000, capped: true, capped_reason: "total"}), providerStateAdded: 3}, "paused"],
    [{cap: cap({monthly_byte_limit: 5000000000, capped: true, capped_reason: "monthly"}), providerStateAdded: 3}, "data cap reached, resets 2026-11-01 00:00 UTC"],
    [{cap: cap({total_byte_limit: 10000000000, capped: true, capped_reason: "total"}), providerStateAdded: 0}, "data cap reached"],
    [{cap: cap({}), providerStateAdded: 1}, "connected"],
    [{cap: null, providerStateAdded: 0}, "connecting"],
  ]) {
    assert.equal(statusText({started: true, signedOut: false, ...input}), status, JSON.stringify(input));
  }
});

test("the data fields: checking, unavailable, the last value kept, no cap, used of limit", () => {
  const readings = new CapReadings();
  assert.deepEqual(dataFields(readings), {dataThisMonth: "checking", dataTotal: "checking"});
  readings.failed();
  assert.deepEqual(dataFields(readings), {dataThisMonth: "unavailable", dataTotal: "unavailable"});
  readings.succeeded(cap({monthly_byte_limit: 5000000000, monthly_used_byte_count: 1234567890, total_used_byte_count: 7}));
  readings.failed();
  assert.deepEqual(dataFields(readings), {dataThisMonth: "1.2 GB of 5.0 GB", dataTotal: "no cap"});
});
