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

// The cap object, reduced to what the status needs.
export interface CapObject {
  clientId: string;
  // null for no monthly cap
  monthlyByteLimit: number | null;
  monthlyUsedByteCount: number;
  // RFC 3339, or "" when absent
  monthlyPeriodEnd: string;
  // null for no running-total cap
  totalByteLimit: number | null;
  totalUsedByteCount: number;
  capped: boolean;
  // "monthly", "total", "" when not capped, or a reason this app does not know
  cappedReason: string;
}

// The state of the data fields: before any reading, after a failed first
// reading, or with a reading.
export type CapReadingState = typeof dataChecking | typeof dataUnavailable | "read";

// The shown fields.
export interface StatusFields {
  status: string;
  dataThisMonth: string;
  dataTotal: string;
}

// The inputs of the status rules.
export interface StatusInput {
  // a GUI app before start or after stop; a console app is always started
  started?: boolean;
  // a GUI app after an auth logout or a 401 or 409 from the token server
  signedOut?: boolean;
  clientLimitStatus?: string;
  // unix milliseconds, 0 without a hold
  clientLimitRetryTime?: number;
  cap?: CapObject | null;
  providerStateAdded?: number;
}

// The companion's /embed-status body, reduced to what the app needs.
export interface EmbedStatus {
  clientId: string;
  instanceId: string;
  providerStateAdded: number;
  clientLimitStatus: string;
  clientLimitRetryTime: number;
  // the contract status as json, compared to notice a change
  contractStatusKey: string;
  // the licenses as the companion sends them, or null when left out
  licenses: unknown[] | null;
}

const uuidPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

// The lowercase form of a UUID, or "" for anything else.
export function normalizeId(text: unknown): string {
  return typeof text === "string" && uuidPattern.test(text) ? text.toLowerCase() : "";
}

// Decimal units with one decimal, because data plans are sold in them: "0 B",
// "999 B", "1.0 kB", "1.2 GB". The tenths round half to even on the exact
// integer (bigint), never on a binary fraction, so 1050 bytes is "1.0 kB" and
// 1150 bytes "1.2 kB"; a value that rounds to 1000.0 moves to the next unit,
// so 999999 bytes is "1.0 MB".
export function formatDataAmount(byteCount: number | bigint): string {
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

// The line the console app prints once the device runs (EMBED_CONTRACT.md,
// "Status").
export function startLine(clientId: string, instanceId: string): string {
  return `embed client ${clientId}, installation ${instanceId}`;
}

// The time an RFC 3339 timestamp names, in unix milliseconds rounded up to the
// next whole minute, or null when it does not parse. The fraction counts in
// full, so 23:59:00.0001 rounds up like 23:59:00.001.
export function roundUpRfc3339ToMinute(text: unknown): number | null {
  const match = typeof text === "string"
    ? /^(\d{4})-(\d{2})-(\d{2})[Tt](\d{2}):(\d{2}):(\d{2})(?:\.(\d+))?(Z|z|[+-]\d{2}:\d{2})$/.exec(text)
    : null;
  if (!match) {
    return null;
  }
  const fields = match.slice(1, 7).map(Number);
  const fraction = match[7] ?? "";
  const zone = match[8] ?? "Z";
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
  // a day that does not exist, such as February 30, does not parse
  const check = new Date(Date.UTC(fields[0], fields[1] - 1, fields[2]));
  if (check.getUTCMonth() !== fields[1] - 1 || check.getUTCDate() !== fields[2]) {
    return null;
  }
  const utcMillis = Date.UTC(fields[0], fields[1] - 1, fields[2], fields[3], fields[4], fields[5]) - offsetMinutes * 60 * 1000;
  const minuteMillis = 60 * 1000;
  const pastMinute = fields[5] !== 0 || /[1-9]/.test(fraction);
  return pastMinute ? (Math.floor(utcMillis / minuteMillis) + 1) * minuteMillis : utcMillis;
}

// Two digits.
function twoDigits(value: number): string {
  return String(value).padStart(2, "0");
}

// "resets YYYY-MM-DD HH:MM UTC" for a monthly_period_end, or null when it does
// not parse.
export function resetText(monthlyPeriodEnd: unknown): string | null {
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
export function clientLimitText(retryTime: number): string {
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
function byteCountValue(value: unknown): value is number {
  return typeof value === "number" && Number.isInteger(value) && 0 <= value;
}

// The server refuses the cap read with this message while the team has not
// enabled Embed for the network (EMBED_CONTRACT.md, "Embed enablement").
export const embedNotEnabledMessage = "Embed isn't enabled for this network.";

// Whether an answer is the Embed-not-enabled refusal,
// {"error": {"message": "Embed isn't enabled for this network."}}.
export function isEmbedNotEnabled(input: unknown): boolean {
  if (input === null || typeof input !== "object") {
    return false;
  }
  const error = (input as Record<string, unknown>).error;
  return error !== null && typeof error === "object" && (error as Record<string, unknown>).message === embedNotEnabledMessage;
}

// The cap object (EMBED_CONTRACT.md, "The cap object") checked and reduced to
// what the status needs. Limits are null when absent or null; a used count
// that is absent reads as 0, since a server need not report usage for an
// uncapped client. An unknown capped_reason stays as it is: it reads as capped
// without a reset time. Throws for anything that is not a cap object, or an
// error answer.
export function parseCapObject(input: unknown): CapObject {
  if (input === null || typeof input !== "object" || Array.isArray(input)) {
    throw new Error("not a cap object");
  }
  const body = input as Record<string, unknown>;
  if (body.error !== undefined && body.error !== null) {
    throw new Error("not a cap object");
  }
  const limit = (value: unknown): number | null => {
    if (value === undefined || value === null) {
      return null;
    }
    if (!byteCountValue(value)) {
      throw new Error("a cap limit is not a byte count");
    }
    return value;
  };
  const used = (value: unknown): number => {
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
  #cap: CapObject | null = null;
  #firstFailed = false;

  // Records a good reading.
  succeeded(cap: CapObject): void {
    this.#cap = cap;
  }

  // Records a failed reading: only the first one changes what is shown.
  failed(): void {
    if (this.#cap === null) {
      this.#firstFailed = true;
    }
  }

  // Records the Embed-not-enabled refusal: it clears the last reading, so both
  // data fields read "unavailable" and the status rules see no cap reading.
  notEnabled(): void {
    this.#cap = null;
    this.#firstFailed = true;
  }

  // The latest good cap object, or null.
  get cap(): CapObject | null {
    return this.#cap;
  }

  // The data fields' state: "checking" before any reading, "unavailable" when
  // the first reading failed and none succeeded since, else "read".
  get state(): CapReadingState {
    if (this.#cap !== null) {
      return "read";
    }
    return this.#firstFailed ? dataUnavailable : dataChecking;
  }
}

// One data field: "checking", "unavailable", "no cap" for a null limit (even
// with a used count), or "<used> of <limit>".
export function dataFieldText(state: CapReadingState, limit: number | null, used: number): string {
  if (state !== "read") {
    return state;
  }
  if (limit === null) {
    return dataNoCap;
  }
  return `${formatDataAmount(used)} of ${formatDataAmount(limit)}`;
}

// The data fields of a CapReadings.
export function dataFields(capReadings: CapReadings): Pick<StatusFields, "dataThisMonth" | "dataTotal"> {
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
export function statusText({started = true, signedOut = false, clientLimitStatus = "", clientLimitRetryTime = 0, cap = null, providerStateAdded = 0}: StatusInput): string {
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
export function statusLine({status, dataThisMonth, dataTotal}: StatusFields): string {
  return `status: ${status} | data this month: ${dataThisMonth} | data total: ${dataTotal}`;
}

// The companion's /embed-status body (javascript/integration/companion, embed
// mode), checked and reduced to what the status needs, with the SDK's Go field
// names: the window's providers added, the client limit status and the
// contract status (a change prompts a cap read). The licenses stay as the
// array the companion sends, or null when the read left them out.
export function parseEmbedStatus(text: string): EmbedStatus {
  const body = JSON.parse(text) as Record<string, unknown> | null;
  if (body === null || typeof body !== "object") {
    throw new Error("the companion answered an unexpected embed status");
  }
  const windowStatus = body.WindowStatus as Record<string, unknown> | null | undefined;
  const clientLimitStatus = body.ClientLimitStatus as Record<string, unknown> | null | undefined;
  const contractStatus = body.ContractStatus;
  if (normalizeId(body.ClientId) === "" || normalizeId(body.InstanceId) === "" ||
      (windowStatus !== null && (typeof windowStatus !== "object" || !Number.isInteger(windowStatus.ProviderStateAdded))) ||
      typeof clientLimitStatus?.Status !== "string" || !Number.isSafeInteger(clientLimitStatus.RetryTime) ||
      (contractStatus !== null && typeof contractStatus !== "object") ||
      (body.Licenses !== undefined && !Array.isArray(body.Licenses))) {
    throw new Error("the companion answered an unexpected embed status");
  }
  return {
    clientId: normalizeId(body.ClientId),
    instanceId: normalizeId(body.InstanceId),
    providerStateAdded: windowStatus === null ? 0 : windowStatus.ProviderStateAdded as number,
    clientLimitStatus: clientLimitStatus.Status as string,
    clientLimitRetryTime: clientLimitStatus.RetryTime as number,
    contractStatusKey: JSON.stringify(contractStatus),
    licenses: body.Licenses === undefined ? null : body.Licenses as unknown[],
  };
}
