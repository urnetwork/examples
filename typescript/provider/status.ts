// The provider status that every provider example shows, with the exact text
// rules of PROVIDER_CONTRACT.md ("Status"): providing state, clients served,
// data provided and the payout wallet, read only. These functions mirror the
// Go provider's status.go and are pure, so the self-test checks them without a
// network, credentials, the native companion or the SDK.

// Shown once at start, and in every example's README. The app that integrates
// a provider owns the consent screen; this example starts without asking.
export const consentDisclaimer = `Consent disclaimer: an app that integrates a URnetwork provider must collect the user's consent before it provides.
Providing shares the user's internet connection: other URnetwork users' traffic exits through the user's device and IP address.
This example starts providing without asking, because the consent screen belongs to your app.`;

export const providerStateStopped = "stopped";
// the platform disconnected this client for its network's client limit, and
// the SDK holds off reconnecting until the retry time
export const providerStateClientLimit = "client limit";
export const providerStateStarting = "starting";
export const providerStatePaused = "paused";
export const providerStateProviding = "providing";

// the payout wallet before the first wallet read finishes
export const payoutWalletChecking = "checking";
// the payout wallet when the first wallet read failed
export const payoutWalletUnavailable = "unavailable";
// the payout wallet when no wallet is mapped
export const payoutWalletNotSet = "not set";

export const payoutWalletScopeHotkey = "hotkey";
export const payoutWalletScopeProvider = "this provider";
export const payoutWalletScopeNetwork = "network";
export const payoutWalletScopeAnotherProvider = "another provider";

// the SDK's ProvideModePublic
export const provideModePublic = 3;
// the SDK's ClientLimitStatusExceeded
export const clientLimitStatusExceeded = "client_limit_exceeded";
// The consent_scope of a network's hotkey delegation entry in GET /sn/wallet.
// Hotkey delegations come with a later server and SDK change; the app only
// labels the entry.
export const snWalletConsentScopeHotkey = "hotkey";

// Distinct clients are counted up to this many; beyond it the count is a lower
// bound, shown with a trailing "+".
export const clientsServedLimit = 100 * 1000;

const zeroId = "00000000-0000-0000-0000-000000000000";
const uuidPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

// One status snapshot.
export interface ProviderStatus {
  state: string;
  // the end of the client limit hold in unix milliseconds, shown with the
  // client limit state; 0 when unknown
  clientLimitRetryTime: number;
  clientsServed: number;
  clientsServedAtLimit: boolean;
  dataProvidedByteCount: number;
  // a coldkey ss58 address, or checking, unavailable, not set
  payoutWallet: string;
  // "" unless payoutWallet is an address
  payoutWalletScope: string;
}

// The device values that decide the providing state.
export interface ProviderStateValues {
  provideMode: number;
  clientLimitStatus: string;
  providePaused: boolean;
  provideEnabled: boolean;
  providerConnected: boolean;
}

// The payout wallet as the status shows it.
export interface PayoutWallet {
  payoutWallet: string;
  payoutWalletScope: string;
}

// One row of the SDK's provider contract details view controller, as far as
// counting needs it; the SDK's ContractPeerRow has these fields.
export interface ContractRow {
  clientId: string;
  sendContracts: {contractId: string}[];
  receiveContracts: {contractId: string}[];
}

// The companion's /provider-status body, checked.
export interface CompanionStatus {
  provideMode: number;
  providerConnected: boolean;
  clientLimitStatus: string;
  clientLimitRetryTime: number;
  deviceRpcStarted: boolean;
}

// The lowercase form of a UUID, or "" for anything else.
export function normalizeId(text: unknown): string {
  return typeof text === "string" && uuidPattern.test(text) ? text.toLowerCase() : "";
}

// Binary units with one decimal: "0 B", "1023 B", "1.0 KiB", "12.4 MiB". A
// value that rounds to 1024.0 moves to the next unit. A tie rounds to the even
// tenth, as Go's %.1f does in the reference, so 1280 bytes is "1.2 KiB"; the
// tenths are exact because the byte count is divided by powers of two.
export function formatByteCount(byteCount: number): string {
  if (byteCount < 1024) {
    return `${byteCount} B`;
  }
  const units = ["KiB", "MiB", "GiB", "TiB", "PiB", "EiB"];
  const roundTenths = (value: number): number => {
    const tenths = value * 10;
    const whole = Math.floor(tenths);
    const fraction = tenths - whole;
    return fraction < 0.5 || (fraction === 0.5 && whole % 2 === 0) ? whole : whole + 1;
  };
  let value = byteCount / 1024;
  let unitIndex = 0;
  while (unitIndex < units.length - 1 && 10240 <= roundTenths(value)) {
    value /= 1024;
    unitIndex += 1;
  }
  return `${(roundTenths(value) / 10).toFixed(1)} ${units[unitIndex]}`;
}

// The status field text: the state, and for the client limit state the time
// the SDK retries, for example "client limit, retry at 19:05 UTC". The retry
// time (unix milliseconds) is rounded up to the next whole minute in UTC, so
// the shown time is never before the real retry; a retry time of 0 shows
// "client limit".
export function providerStatusText(state: string, clientLimitRetryTime: number): string {
  if (state !== providerStateClientLimit || !(0 < clientLimitRetryTime)) {
    return state;
  }
  const minuteMillis = 60 * 1000;
  const retryMinute = Math.floor((clientLimitRetryTime + minuteMillis - 1) / minuteMillis);
  const retryTime = new Date(retryMinute * minuteMillis);
  const hours = String(retryTime.getUTCHours()).padStart(2, "0");
  const minutes = String(retryTime.getUTCMinutes()).padStart(2, "0");
  return `${providerStateClientLimit}, retry at ${hours}:${minutes} UTC`;
}

// The providing state, in this order: stopped unless the provide mode is
// public; client limit while the SDK holds this client off for its network's
// client limit; paused while paused; providing once the provider is enabled
// and its platform carrier is connected; starting otherwise.
export function providerState(values: ProviderStateValues): string {
  if (values.provideMode !== provideModePublic) {
    return providerStateStopped;
  }
  if (values.clientLimitStatus === clientLimitStatusExceeded) {
    return providerStateClientLimit;
  }
  if (values.providePaused) {
    return providerStatePaused;
  }
  if (values.provideEnabled && values.providerConnected) {
    return providerStateProviding;
  }
  return providerStateStarting;
}

// Which owner the effective payout wallet belongs to. The consent scope comes
// first: a hotkey delegation is network-level, with no client id, but is not
// the network's wallet. Otherwise by the wallet's client id: this provider's
// own mapping, the network's wallet, or another provider of the network.
export function payoutWalletScope(walletConsentScope: string, walletClientId: string, clientId: string): string {
  if (walletConsentScope === snWalletConsentScopeHotkey) {
    return payoutWalletScopeHotkey;
  }
  if (!walletClientId) {
    return payoutWalletScopeNetwork;
  }
  if (walletClientId.toLowerCase() === clientId.toLowerCase()) {
    return payoutWalletScopeProvider;
  }
  return payoutWalletScopeAnotherProvider;
}

// The payout wallet of a GET /sn/wallet result read with this client's JWT.
// Its wallet is the effective wallet of the client session (the contract's
// order: own provider consent, network consent, hotkey delegation, own other
// wallet, network-level copy). Returns null for an error result, which the
// app treats as a failed read.
export function payoutWalletFromResult(result: unknown, clientId: string): PayoutWallet | null {
  if (result === null || typeof result !== "object" || ("error" in result && result.error)) {
    return null;
  }
  const wallet = "wallet" in result ? result.wallet : undefined;
  if (wallet === null || typeof wallet !== "object" || !("coldkey_ss58" in wallet) ||
      typeof wallet.coldkey_ss58 !== "string" || wallet.coldkey_ss58 === "") {
    return {payoutWallet: payoutWalletNotSet, payoutWalletScope: ""};
  }
  const walletConsentScope = "consent_scope" in wallet && typeof wallet.consent_scope === "string" ? wallet.consent_scope : "";
  const walletClientId = "client_id" in wallet && typeof wallet.client_id === "string" ? wallet.client_id : "";
  return {
    payoutWallet: wallet.coldkey_ss58,
    payoutWalletScope: payoutWalletScope(walletConsentScope, walletClientId, clientId),
  };
}

// The peers of one row of the SDK's provider contract details view
// controller. The SDK groups a row by the contract's peer as the contract
// resolves it: the source of a receive (ingress) contract and the destination
// of a send (egress) contract, so both directions of one client share a row.
// A path without a peer gives the all-zero id, or, without a path, the
// contract id as the row's id. The JavaScript SDK does not expose the transfer
// path's stream id, so such a row counts each of its contracts as
// "contract:<id>", the contract's last fallback, instead of "stream:<id>".
export function contractRowPeerKeys(row: ContractRow): string[] {
  const contractIds = [...(row.sendContracts ?? []), ...(row.receiveContracts ?? [])]
    .map(entry => normalizeId(entry?.contractId))
    .filter(contractId => contractId !== "");
  const clientId = normalizeId(row.clientId);
  if (clientId !== "" && clientId !== zeroId && !contractIds.includes(clientId)) {
    return [clientId];
  }
  return contractIds.map(contractId => `contract:${contractId}`);
}

// The distinct clients that held a contract with this provider since the app
// started. The count stops at the limit and is then a lower bound.
export class ClientsServed {
  #limit: number;
  // set of contractRowPeerKeys values
  #peerKeys = new Set<string>();
  #atLimit = false;

  // An empty count that keeps at most limit distinct peers.
  constructor(limit: number = clientsServedLimit) {
    this.#limit = limit;
  }

  // Counts the peers of the current provider contract rows.
  addRows(rows: readonly ContractRow[] | null | undefined): void {
    for (const row of rows ?? []) {
      for (const peerKey of contractRowPeerKeys(row)) {
        this.add(peerKey);
      }
    }
  }

  // Counts one peer.
  add(peerKey: string): void {
    if (!peerKey || this.#peerKeys.has(peerKey)) {
      return;
    }
    if (this.#limit <= this.#peerKeys.size) {
      this.#atLimit = true;
      return;
    }
    this.#peerKeys.add(peerKey);
  }

  // The distinct count, and whether the count stopped at the limit.
  count(): {count: number; atLimit: boolean} {
    return {count: this.#peerKeys.size, atLimit: this.#atLimit};
  }
}

// The status line, for example
// "status: providing | clients served: 3 | data provided: 12.4 MiB | payout wallet: 5Grw... (network)".
export function statusLine(status: ProviderStatus): string {
  const clientsServed = `${status.clientsServed}${status.clientsServedAtLimit ? "+" : ""}`;
  const payoutWallet = status.payoutWalletScope ? `${status.payoutWallet} (${status.payoutWalletScope})` : status.payoutWallet;
  return `status: ${providerStatusText(status.state, status.clientLimitRetryTime)} | clients served: ${clientsServed} | data provided: ${formatByteCount(status.dataProvidedByteCount)} | payout wallet: ${payoutWallet}`;
}

// The fields that change rarely. A change prints a status line at once; the
// data counter alone only prints on the periodic line. The status text carries
// the client limit retry time, so a new retry time prints too.
export function statusKey(status: ProviderStatus): string {
  return JSON.stringify([
    providerStatusText(status.state, status.clientLimitRetryTime),
    status.clientsServed,
    status.clientsServedAtLimit,
    status.payoutWallet,
    status.payoutWalletScope,
  ]);
}

// The companion's /provider-status body (javascript/integration/companion,
// provider mode), checked: the provide mode, the provider's platform
// connection, the client limit status and whether the device rpc is served.
export function parseCompanionStatus(text: string): CompanionStatus {
  const body: unknown = JSON.parse(text);
  const fields = typeof body === "object" && body !== null ? body as Record<string, unknown> : {};
  const clientLimitStatus = typeof fields.ClientLimitStatus === "object" && fields.ClientLimitStatus !== null
    ? fields.ClientLimitStatus as Record<string, unknown>
    : {};
  const {ProvideMode: provideMode, ProviderConnected: providerConnected, DeviceRpcStarted: deviceRpcStarted} = fields;
  const {Status: status, RetryTime: retryTime} = clientLimitStatus;
  if (typeof provideMode !== "number" || !Number.isInteger(provideMode) || typeof providerConnected !== "boolean" ||
      typeof deviceRpcStarted !== "boolean" || typeof status !== "string" ||
      typeof retryTime !== "number" || !Number.isSafeInteger(retryTime)) {
    throw new Error("the companion answered an unexpected provider status");
  }
  return {
    provideMode,
    providerConnected,
    clientLimitStatus: status,
    clientLimitRetryTime: retryTime,
    deviceRpcStarted,
  };
}
