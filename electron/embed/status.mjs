// The same module as javascript/embed/status.mjs: a packaged app ships only its own
// directory, so the Electron app keeps a copy.
//
// The embed status that every embed example shows, with the exact rules of
// EMBED_CONTRACT.md ("Status"): the status, the data used this month and the
// data used in the running total. These functions are pure, so the self-test
// checks them without a network, credentials or the native companion; the
// companion reads the device values in process and serves them on its
// /embed-status route, and the app reads the data caps over HTTP.

export const statusSignedOut = "signed out";
export const statusStopped = "stopped";
// the platform disconnected this client for its network's client limit, and
// the SDK holds off reconnecting until the retry time
export const statusClientLimit = "client limit";
export const statusPaused = "paused";
export const statusDataCapReached = "data cap reached";
export const statusConnected = "connected";
export const statusConnecting = "connecting";

// a data field before the first cap reading
export const dataChecking = "checking";
// a data field when the first cap reading failed
export const dataUnavailable = "unavailable";
// a data field whose cap is not set
export const dataNoCap = "no cap";

// the SDK's ClientLimitStatusExceeded
export const clientLimitStatusExceeded = "client_limit_exceeded";

// the capped_reason values of the cap object
export const cappedReasonMonthly = "monthly";
export const cappedReasonTotal = "total";

const uuidPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

// The lowercase form of a UUID, or "" for anything else.
export function normalizeId(text) {
  return typeof text === "string" && uuidPattern.test(text) ? text.toLowerCase() : "";
}

// Decimal units with one decimal, because data plans are sold in them: "0 B",
// "999 B", "1.0 kB", "1.2 GB". The tenths round half to even on the exact
// integer (BigInt), never on a binary fraction, so 1050 bytes is "1.0 kB" and
// 1150 bytes "1.2 kB"; a value that rounds to 1000.0 moves to the next unit,
// so 999999 bytes is "1.0 MB".
export function formatDataAmount(byteCount) {
  if (byteCount < 1000) {
    return `${byteCount} B`;
  }
  const units = ["kB", "MB", "GB", "TB", "PB", "EB"];
  const bytes = BigInt(byteCount);
  // the bytes in a tenth of the unit
  let tenth = 100n;
  for (let unitIndex = 0; ; unitIndex += 1) {
    let tenths = bytes / tenth;
    const remainder = bytes % tenth;
    if (tenth < 2n * remainder || (2n * remainder === tenth && tenths % 2n === 1n)) {
      tenths += 1n;
    }
    if (tenths < 10000n || unitIndex === units.length - 1) {
      return `${tenths / 10n}.${tenths % 10n} ${units[unitIndex]}`;
    }
    tenth *= 1000n;
  }
}

// The time an RFC 3339 timestamp names, in unix milliseconds rounded up to the
// next whole minute, or null when it does not parse. The fraction counts in
// full, so 23:59:00.0001 rounds up like 23:59:00.001.
export function roundUpRfc3339ToMinute(text) {
  const match = typeof text === "string"
    ? /^(\d{4})-(\d{2})-(\d{2})[Tt](\d{2}):(\d{2}):(\d{2})(?:\.(\d+))?(Z|z|[+-]\d{2}:\d{2})$/.exec(text)
    : null;
  if (!match) {
    return null;
  }
  const [, year, month, day, hour, minute, second, fraction = "", zone] = match.map(part => part ?? "");
  const fields = [year, month, day, hour, minute, second].map(Number);
  if (12 < fields[1] || fields[1] < 1 || 31 < fields[2] || fields[2] < 1 || 23 < fields[3] || 59 < fields[4] || 59 < fields[5]) {
    return null;
  }
  let offsetMinutes = 0;
  if (zone !== "Z" && zone !== "z") {
    const sign = zone[0] === "-" ? -1 : 1;
    const [offsetHours, offsetMins] = zone.slice(1).split(":").map(Number);
    if (23 < offsetHours || 59 < offsetMins) {
      return null;
    }
    offsetMinutes = sign * (offsetHours * 60 + offsetMins);
  }
  const utcMillis = Date.UTC(fields[0], fields[1] - 1, fields[2], fields[3], fields[4], fields[5]) - offsetMinutes * 60 * 1000;
  // a day that does not exist, such as February 30, does not parse
  const check = new Date(Date.UTC(fields[0], fields[1] - 1, fields[2]));
  if (check.getUTCMonth() !== fields[1] - 1 || check.getUTCDate() !== fields[2]) {
    return null;
  }
  const minuteMillis = 60 * 1000;
  const pastMinute = fields[5] !== 0 || /[1-9]/.test(fraction);
  return pastMinute ? (Math.floor(utcMillis / minuteMillis) + 1) * minuteMillis : utcMillis;
}

// Two digits.
function twoDigits(value) {
  return String(value).padStart(2, "0");
}

// "resets YYYY-MM-DD HH:MM UTC" for a monthly_period_end, or null when it does
// not parse.
export function resetText(monthlyPeriodEnd) {
  const millis = roundUpRfc3339ToMinute(monthlyPeriodEnd);
  if (millis === null) {
    return null;
  }
  const time = new Date(millis);
  return `resets ${time.getUTCFullYear()}-${twoDigits(time.getUTCMonth() + 1)}-${twoDigits(time.getUTCDate())} ${twoDigits(time.getUTCHours())}:${twoDigits(time.getUTCMinutes())} UTC`;
}

// "client limit, retry at HH:MM UTC" with the retry time (unix milliseconds)
// rounded up to the next whole minute, as the provider contract does, or plain
// "client limit" when the retry time is 0.
export function clientLimitText(retryTime) {
  if (!(0 < retryTime)) {
    return statusClientLimit;
  }
  const minuteMillis = 60 * 1000;
  const retryMinute = Math.floor((retryTime + minuteMillis - 1) / minuteMillis);
  const time = new Date(retryMinute * minuteMillis);
  return `${statusClientLimit}, retry at ${twoDigits(time.getUTCHours())}:${twoDigits(time.getUTCMinutes())} UTC`;
}

// A byte count of the cap object: a whole number of 0 or more. Counts beyond
// 2^53 lose precision in a JavaScript number, which only matters for display
// beyond the first decimal and never for the decisions here.
function byteCountValue(value) {
  return typeof value === "number" && Number.isInteger(value) && 0 <= value;
}

// The cap object (EMBED_CONTRACT.md, "The cap object") checked and reduced to
// what the status needs. Limits are null when absent or null; a used count
// that is absent reads as 0, since a server need not report usage for an
// uncapped client. An unknown capped_reason stays as it is: it reads as capped
// without a reset time. Throws for anything that is not a cap object, or an
// error answer.
export function parseCapObject(body) {
  if (body === null || typeof body !== "object" || Array.isArray(body) || (body.error !== undefined && body.error !== null)) {
    throw new Error("not a cap object");
  }
  const limit = value => {
    if (value === undefined || value === null) {
      return null;
    }
    if (!byteCountValue(value)) {
      throw new Error("a cap limit is not a byte count");
    }
    return value;
  };
  const used = value => {
    if (value === undefined || value === null) {
      return 0;
    }
    if (!byteCountValue(value)) {
      throw new Error("a used count is not a byte count");
    }
    return value;
  };
  if (body.capped !== undefined && typeof body.capped !== "boolean") {
    throw new Error("capped is not a boolean");
  }
  if (body.capped_reason !== undefined && body.capped_reason !== null && typeof body.capped_reason !== "string") {
    throw new Error("capped_reason is not a string");
  }
  return {
    clientId: typeof body.client_id === "string" ? body.client_id : "",
    monthlyByteLimit: limit(body.monthly_byte_limit),
    monthlyUsedByteCount: used(body.monthly_used_byte_count),
    monthlyPeriodEnd: typeof body.monthly_period_end === "string" ? body.monthly_period_end : "",
    totalByteLimit: limit(body.total_byte_limit),
    totalUsedByteCount: used(body.total_used_byte_count),
    capped: body.capped === true,
    cappedReason: typeof body.capped_reason === "string" ? body.capped_reason : "",
  };
}

// The cap readings of a run: the latest good cap object, and whether the first
// reading failed. A later failure keeps the last good value.
export class CapReadings {
  #cap = null;
  #firstFailed = false;

  // Records a good reading.
  succeeded(cap) {
    this.#cap = cap;
  }

  // Records a failed reading: only the first one changes what is shown.
  failed() {
    if (this.#cap === null) {
      this.#firstFailed = true;
    }
  }

  // The latest good cap object, or null.
  get cap() {
    return this.#cap;
  }

  // The data fields' state: "checking" before any reading, "unavailable" when
  // the first reading failed and none succeeded since, else "read".
  get state() {
    if (this.#cap !== null) {
      return "read";
    }
    return this.#firstFailed ? dataUnavailable : dataChecking;
  }
}

// One data field: "checking", "unavailable", "no cap" for a null limit (even
// with a used count), or "<used> of <limit>".
export function dataFieldText(state, limit, used) {
  if (state !== "read") {
    return state;
  }
  if (limit === null) {
    return dataNoCap;
  }
  return `${formatDataAmount(used)} of ${formatDataAmount(limit)}`;
}

// The data fields of a CapReadings.
export function dataFields(capReadings) {
  const cap = capReadings.cap;
  const state = capReadings.state;
  return {
    dataThisMonth: dataFieldText(state, cap?.monthlyByteLimit ?? null, cap?.monthlyUsedByteCount ?? 0),
    dataTotal: dataFieldText(state, cap?.totalByteLimit ?? null, cap?.totalUsedByteCount ?? 0),
  };
}

// The status, by the first rule that applies (EMBED_CONTRACT.md, "Status"):
// signed out and stopped (GUI apps only; a console app passes started and not
// signed out), client limit, paused (the cap that capped_reason names is 0),
// data cap reached (with the reset time for the monthly cap), connected while
// the window has at least one provider added, otherwise connecting.
export function statusText({started = true, signedOut = false, clientLimitStatus = "", clientLimitRetryTime = 0, cap = null, providerStateAdded = 0}) {
  if (signedOut) {
    return statusSignedOut;
  }
  if (!started) {
    return statusStopped;
  }
  if (clientLimitStatus === clientLimitStatusExceeded) {
    return clientLimitText(clientLimitRetryTime);
  }
  if (cap !== null && cap.capped) {
    const namedLimit = cap.cappedReason === cappedReasonMonthly
      ? cap.monthlyByteLimit
      : cap.cappedReason === cappedReasonTotal ? cap.totalByteLimit : null;
    if (namedLimit === 0) {
      return statusPaused;
    }
    if (cap.cappedReason === cappedReasonMonthly) {
      const reset = resetText(cap.monthlyPeriodEnd);
      if (reset !== null) {
        return `${statusDataCapReached}, ${reset}`;
      }
    }
    return statusDataCapReached;
  }
  if (1 <= providerStateAdded) {
    return statusConnected;
  }
  return statusConnecting;
}

// The console status line, for example
// "status: connected | data this month: 1.2 GB of 5.0 GB | data total: no cap".
export function statusLine({status, dataThisMonth, dataTotal}) {
  return `status: ${status} | data this month: ${dataThisMonth} | data total: ${dataTotal}`;
}

// The companion's /embed-status body (javascript/integration/companion, embed
// mode), checked and reduced to what the status needs, with the SDK's Go field
// names: the window's providers added, the client limit status and the
// contract status (a change prompts a cap read). The licenses stay as the
// array the companion sends, or null when the read left them out.
export function parseEmbedStatus(text) {
  const body = JSON.parse(text);
  const windowStatus = body?.WindowStatus;
  const clientLimitStatus = body?.ClientLimitStatus;
  const contractStatus = body?.ContractStatus;
  if (body === null || typeof body !== "object" || normalizeId(body.ClientId) === "" || normalizeId(body.InstanceId) === "" ||
      (windowStatus !== null && (typeof windowStatus !== "object" || !Number.isInteger(windowStatus.ProviderStateAdded))) ||
      typeof clientLimitStatus?.Status !== "string" || !Number.isSafeInteger(clientLimitStatus.RetryTime) ||
      (contractStatus !== null && typeof contractStatus !== "object") ||
      (body.Licenses !== undefined && !Array.isArray(body.Licenses))) {
    throw new Error("the companion answered an unexpected embed status");
  }
  return {
    clientId: normalizeId(body.ClientId),
    instanceId: normalizeId(body.InstanceId),
    providerStateAdded: windowStatus === null ? 0 : windowStatus.ProviderStateAdded,
    clientLimitStatus: clientLimitStatus.Status,
    clientLimitRetryTime: clientLimitStatus.RetryTime,
    contractStatusKey: JSON.stringify(contractStatus),
    licenses: body.Licenses === undefined ? null : body.Licenses,
  };
}
