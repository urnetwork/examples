// The provider status that the Electron provider shows, with the exact text
// rules of PROVIDER_CONTRACT.md ("Status"): providing state, clients served,
// data provided and the payout wallet, read only. The main process reads the
// device values from the companion's /provider-status route, maps them here
// and the window shows them. Everything here is pure, so the unit tests check
// it without Electron, a network or credentials; the text rules mirror
// go/provider/status.go.

// Shown next to the control that starts providing, and in the README. The app
// that integrates a provider owns the consent screen; this example starts
// without asking.
export const consentDisclaimer = `Consent disclaimer: an app that integrates a URnetwork provider must collect the user's consent before it provides.
Providing shares the user's internet connection: other URnetwork users' traffic exits through the user's device and IP address.
This example starts providing without asking, because the consent screen belongs to your app.`;

// the sdk's provide modes (ProvideModeNone, ProvideModeNetwork, ProvideModePublic)
export const provideModeNone = 0;
export const provideModeNetwork = 1;
export const provideModePublic = 3;

// the sdk's client limit statuses (ClientLimitStatusNone, ClientLimitStatusExceeded)
export const clientLimitStatusNone = "";
export const clientLimitStatusExceeded = "client_limit_exceeded";

export const providerStateStopped = "stopped";
// the platform disconnected this client for its network's client limit, and
// the sdk holds off reconnecting until the retry time
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

// The consent_scope of a network's hotkey delegation entry in GET /sn/wallet.
// Hotkey delegations come with a later server and sdk change; the app only
// labels the entry.
export const snWalletConsentScopeHotkey = "hotkey";

// The status field text: the state, and for the client limit state the time
// the sdk retries, for example "client limit, retry at 19:05 UTC". The retry
// time (unix milliseconds) is rounded up to the next whole minute in UTC, so
// the shown time is never before the real retry; a retry time of 0 shows
// "client limit".
export function providerStatusText(state, clientLimitRetryTime) {
  if (state !== providerStateClientLimit || !(0 < clientLimitRetryTime)) {
    return state;
  }
  const minuteMillis = 60 * 1000;
  const retryMinute = Math.ceil(clientLimitRetryTime / minuteMillis);
  const retryTime = new Date(retryMinute * minuteMillis);
  const hours = String(retryTime.getUTCHours()).padStart(2, "0");
  const minutes = String(retryTime.getUTCMinutes()).padStart(2, "0");
  return `${providerStateClientLimit}, retry at ${hours}:${minutes} UTC`;
}

// Binary units with one decimal: "0 B", "1023 B", "1.0 KiB", "12.4 MiB". A
// value that rounds to 1024.0 moves to the next unit. The tenth is rounded
// from the exact value with ties to even, as the contract and Go's "%.1f"
// say: 1280 bytes is "1.2 KiB" and 1792 bytes "1.8 KiB", where
// Number.toFixed would round the first tie up.
export function formatByteCount(byteCount) {
  if (byteCount < 1024) {
    return `${byteCount} B`;
  }
  const units = ["KiB", "MiB", "GiB", "TiB", "PiB", "EiB"];
  let value = byteCount / 1024;
  let unitIndex = 0;
  while (unitIndex < units.length - 1 && 1024 <= Math.round(value * 10) / 10) {
    value /= 1024;
    unitIndex += 1;
  }
  // value is exactly byteCount / 1024^(unitIndex + 1): round tenths of it
  const divisor = 1024n ** BigInt(unitIndex + 1);
  const tenths = BigInt(Math.trunc(byteCount)) * 10n;
  let roundedTenths = tenths / divisor;
  const twiceRemainder = (tenths % divisor) * 2n;
  if (divisor < twiceRemainder || (twiceRemainder === divisor && roundedTenths % 2n === 1n)) {
    roundedTenths += 1n;
  }
  return `${roundedTenths / 10n}.${roundedTenths % 10n} ${units[unitIndex]}`;
}

// The providing state from the device values, in this order: stopped unless
// the provide mode is public; client limit while the sdk holds this client off
// for its network's client limit; paused while paused; providing once the
// provider is enabled and connected to the platform; starting otherwise.
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
  if (walletClientId.toLowerCase() === String(clientId).toLowerCase()) {
    return payoutWalletScopeProvider;
  }
  return payoutWalletScopeAnotherProvider;
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

// The device values of the companion's /provider-status body
// (javascript/integration/companion, provider mode), checked: the provide
// mode, enabled and paused state, the provider connection and the client
// limit status with the sdk's Go field names, the data provided from the two
// byte counts of its provider packet stats (null without a provider), and the
// clients served that the companion counts with the contract's peer rules.
// Throws for any other body, which the app reads as a failed status read.
export function parseProviderStatus(body) {
  const clientLimitStatus = body?.ClientLimitStatus;
  const providerPacketStats = body?.ProviderPacketStats;
  const count = value => Number.isSafeInteger(value) && 0 <= value;
  const validPacketStats = providerPacketStats === null || (typeof providerPacketStats === "object" &&
    count(providerPacketStats?.RemoteEgressByteCount) && count(providerPacketStats?.RemoteIngressByteCount));
  if (!Number.isInteger(body?.ProvideMode) || typeof body.ProvideEnabled !== "boolean" ||
      typeof body.ProvidePaused !== "boolean" || typeof body.ProviderConnected !== "boolean" ||
      typeof clientLimitStatus?.Status !== "string" || !count(clientLimitStatus.RetryTime) || !validPacketStats ||
      !count(body.ClientsServed) || typeof body.ClientsServedAtLimit !== "boolean") {
    throw new Error("the companion answered an unexpected provider status");
  }
  return {
    provideMode: body.ProvideMode,
    provideEnabled: body.ProvideEnabled,
    providePaused: body.ProvidePaused,
    providerConnected: body.ProviderConnected,
    clientLimitStatus: clientLimitStatus.Status,
    clientLimitRetryTime: clientLimitStatus.RetryTime,
    dataProvidedByteCount: dataProvidedByteCount(providerPacketStats),
    clientsServed: body.ClientsServed,
    clientsServedAtLimit: body.ClientsServedAtLimit,
  };
}

// The four fields as display text, the same text the console status line
// shows, for one status snapshot: {state, clientLimitRetryTime, clientsServed,
// clientsServedAtLimit, dataProvidedByteCount, payoutWallet, payoutWalletScope}.
// payoutWalletScope is "" unless payoutWallet is an address.
export function statusFields(status) {
  const payoutWallet = status.payoutWalletScope
    ? `${status.payoutWallet} (${status.payoutWalletScope})`
    : status.payoutWallet;
  return {
    status: providerStatusText(status.state, status.clientLimitRetryTime),
    clientsServed: `${status.clientsServed}${status.clientsServedAtLimit ? "+" : ""}`,
    dataProvided: formatByteCount(status.dataProvidedByteCount),
    payoutWallet,
  };
}

// The contract's status line, for example "status: providing | clients
// served: 3 | data provided: 12.4 MiB | payout wallet: 5Grw... (network)". The
// tray tooltip and the main process log show it.
export function statusLine(status) {
  const fields = statusFields(status);
  return `status: ${fields.status} | clients served: ${fields.clientsServed} | data provided: ${fields.dataProvided} | payout wallet: ${fields.payoutWallet}`;
}
