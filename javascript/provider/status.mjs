// The provider status that every provider example shows, with the exact text
// rules of PROVIDER_CONTRACT.md ("Status"): providing state, clients served,
// data provided and the payout wallet, read only. These functions mirror the
// Go provider's status.go and are pure, so the self-test checks them without a
// network, credentials or the native companion. The companion counts the
// clients served with the contract's peer rules and serves every device value
// on its /provider-status route.

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

// The companion counts distinct clients up to this many; beyond it the count is
// a lower bound, shown with a trailing "+".
export const clientsServedLimit = 100 * 1000;

const uuidPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

// The lowercase form of a UUID, or "" for anything else.
export function normalizeId(text) {
  return typeof text === "string" && uuidPattern.test(text) ? text.toLowerCase() : "";
}

// Binary units with one decimal: "0 B", "1023 B", "1.0 KiB", "12.4 MiB". A
// value that rounds to 1024.0 moves to the next unit. A tie rounds to the even
// tenth, as Go's %.1f does in the reference, so 1280 bytes is "1.2 KiB"; the
// tenths are exact because the byte count is divided by powers of two.
export function formatByteCount(byteCount) {
  if (byteCount < 1024) {
    return `${byteCount} B`;
  }
  const units = ["KiB", "MiB", "GiB", "TiB", "PiB", "EiB"];
  const roundTenths = value => {
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
export function providerStatusText(state, clientLimitRetryTime) {
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
export function providerState({provideMode, clientLimitStatus, providePaused, provideEnabled, providerConnected}) {
  if (provideMode !== provideModePublic) {
    return providerStateStopped;
  }
  if (clientLimitStatus === clientLimitStatusExceeded) {
    return providerStateClientLimit;
  }
  if (providePaused) {
    return providerStatePaused;
  }
  if (provideEnabled && providerConnected) {
    return providerStateProviding;
  }
  return providerStateStarting;
}

// Which owner the effective payout wallet belongs to. The consent scope comes
// first: a hotkey delegation is network-level, with no client id, but is not
// the network's wallet. Otherwise by the wallet's client id: this provider's
// own mapping, the network's wallet, or another provider of the network.
export function payoutWalletScope(walletConsentScope, walletClientId, clientId) {
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
export function payoutWalletFromResult(result, clientId) {
  if (result === null || typeof result !== "object" || result.error) {
    return null;
  }
  const wallet = result.wallet;
  if (wallet === null || typeof wallet !== "object" || typeof wallet.coldkey_ss58 !== "string" || wallet.coldkey_ss58 === "") {
    return {payoutWallet: payoutWalletNotSet, payoutWalletScope: ""};
  }
  const walletConsentScope = typeof wallet.consent_scope === "string" ? wallet.consent_scope : "";
  const walletClientId = typeof wallet.client_id === "string" ? wallet.client_id : "";
  return {
    payoutWallet: wallet.coldkey_ss58,
    payoutWalletScope: payoutWalletScope(walletConsentScope, walletClientId, clientId),
  };
}

// Data provided: the bytes relayed for clients in both directions since the
// device started, RemoteEgressByteCount + RemoteIngressByteCount of the
// provider packet stats; 0 when the stats are null.
export function dataProvidedByteCount(providerPacketStats) {
  if (providerPacketStats === null || providerPacketStats === undefined) {
    return 0;
  }
  return providerPacketStats.RemoteEgressByteCount + providerPacketStats.RemoteIngressByteCount;
}

// The status line, for example
// "status: providing | clients served: 3 | data provided: 12.4 MiB | payout wallet: 5Grw... (network)".
// A status has state, clientLimitRetryTime (unix milliseconds, 0 when
// unknown), clientsServed, clientsServedAtLimit, dataProvidedByteCount,
// payoutWallet (an address, or checking, unavailable, not set) and
// payoutWalletScope ("" unless payoutWallet is an address).
export function statusLine(status) {
  const clientsServed = `${status.clientsServed}${status.clientsServedAtLimit ? "+" : ""}`;
  const payoutWallet = status.payoutWalletScope ? `${status.payoutWallet} (${status.payoutWalletScope})` : status.payoutWallet;
  return `status: ${providerStatusText(status.state, status.clientLimitRetryTime)} | clients served: ${clientsServed} | data provided: ${formatByteCount(status.dataProvidedByteCount)} | payout wallet: ${payoutWallet}`;
}

// The fields that change rarely. A change prints a status line at once; the
// data counter alone only prints on the periodic line. The status text carries
// the client limit retry time, so a new retry time prints too.
export function statusKey(status) {
  return JSON.stringify([
    providerStatusText(status.state, status.clientLimitRetryTime),
    status.clientsServed,
    status.clientsServedAtLimit,
    status.payoutWallet,
    status.payoutWalletScope,
  ]);
}

// The companion's /provider-status body (javascript/integration/companion,
// provider mode), checked: every device value of the status, with the SDK's Go
// field names, the clients served that the companion counts, and whether its
// device rpc is served.
export function parseCompanionStatus(text) {
  const body = JSON.parse(text);
  const clientLimitStatus = body?.ClientLimitStatus;
  const providerPacketStats = body?.ProviderPacketStats;
  const byteCount = value => Number.isSafeInteger(value) && 0 <= value;
  const validPacketStats = providerPacketStats === null || (typeof providerPacketStats === "object" &&
    byteCount(providerPacketStats.RemoteEgressByteCount) && byteCount(providerPacketStats.RemoteIngressByteCount));
  if (!Number.isInteger(body?.ProvideMode) || typeof body.ProvideEnabled !== "boolean" || typeof body.ProvidePaused !== "boolean" ||
      typeof body.ProviderConnected !== "boolean" || typeof clientLimitStatus?.Status !== "string" ||
      !Number.isSafeInteger(clientLimitStatus.RetryTime) || !validPacketStats || !byteCount(body.ClientsServed) ||
      typeof body.ClientsServedAtLimit !== "boolean" || typeof body.DeviceRpcStarted !== "boolean") {
    throw new Error("the companion answered an unexpected provider status");
  }
  return {
    provideMode: body.ProvideMode,
    provideEnabled: body.ProvideEnabled,
    providePaused: body.ProvidePaused,
    providerConnected: body.ProviderConnected,
    clientLimitStatus: clientLimitStatus.Status,
    clientLimitRetryTime: clientLimitStatus.RetryTime,
    providerPacketStats,
    clientsServed: body.ClientsServed,
    clientsServedAtLimit: body.ClientsServedAtLimit,
    deviceRpcStarted: body.DeviceRpcStarted,
  };
}

// The status from the companion's last /provider-status read (null before the
// first one, which shows "starting") and the payout wallet read.
export function providerStatus(companionStatus, payoutWallet, payoutWalletScope) {
  if (companionStatus === null) {
    return {
      state: providerStateStarting,
      clientLimitRetryTime: 0,
      clientsServed: 0,
      clientsServedAtLimit: false,
      dataProvidedByteCount: 0,
      payoutWallet,
      payoutWalletScope,
    };
  }
  return {
    state: providerState(companionStatus),
    clientLimitRetryTime: companionStatus.clientLimitRetryTime,
    clientsServed: companionStatus.clientsServed,
    clientsServedAtLimit: companionStatus.clientsServedAtLimit,
    dataProvidedByteCount: dataProvidedByteCount(companionStatus.providerPacketStats),
    payoutWallet,
    payoutWalletScope,
  };
}
