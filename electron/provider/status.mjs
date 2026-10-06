// The provider status that the Electron provider shows, with the exact text
// rules of PROVIDER_CONTRACT.md ("Status"): providing state, clients served,
// data provided and the payout wallet, read only. The main process computes
// it and the window shows it. Everything here is pure, so the unit tests check
// it without Electron, a network or credentials; it mirrors go/provider/status.go.

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

// Distinct clients are counted up to this many; beyond it the count is a
// lower bound, shown with a trailing "+".
export const clientsServedLimit = 100 * 1000;

const zeroIdString = "00000000-0000-0000-0000-000000000000";

// A client, stream or contract id that names something: a non-empty id that
// is not all zero.
function presentId(id) {
  return typeof id === "string" && id !== "" && id.toLowerCase() !== zeroIdString;
}

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

// The providing state from the device readout, in this order: stopped unless
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

// The peer of one provider contract, by direction as the sdk's contract
// screens resolve it: the source of a receive (ingress) contract, the
// destination of a send (egress) contract. A path without that client id is
// keyed by its stream id, then by the contract id. The details use the sdk's
// json field names: ContractId and ContractTransferPath with SourceId,
// DestinationId and StreamId.
export function contractPeerKey(details, receive) {
  const path = details.ContractTransferPath;
  if (path) {
    const peerId = receive ? path.SourceId : path.DestinationId;
    if (presentId(peerId)) {
      return peerId.toLowerCase();
    }
    if (presentId(path.StreamId)) {
      return `stream:${path.StreamId.toLowerCase()}`;
    }
  }
  if (presentId(details.ContractId)) {
    return `contract:${details.ContractId.toLowerCase()}`;
  }
  return "";
}

// The peer keys of one row of the JavaScript sdk's provider contract details
// view controller. A row holds the open contracts of one peer, which the sdk
// resolves by direction as contractPeerKey does, so its client id is the peer
// key. A row without a client id (an all-zero id) holds contracts whose path
// names no client; rows carry no stream ids, so each of those contracts is
// keyed by its contract id.
export function contractRowPeerKeys(row) {
  if (presentId(row.clientId)) {
    return [row.clientId.toLowerCase()];
  }
  const contracts = [...(row.receiveContracts ?? []), ...(row.sendContracts ?? [])];
  return contracts
    .filter(contract => presentId(contract.contractId))
    .map(contract => `contract:${contract.contractId.toLowerCase()}`);
}

// The distinct clients that held a contract with this provider since the app
// started, up to a limit. The main process feeds it from one thread.
export class ClientsServed {
  // An empty count that keeps at most limit distinct peers.
  constructor(limit = clientsServedLimit) {
    this.limit = limit;
    this.peerKeys = new Set();
    this.atLimit = false;
  }

  // Counts one peer key; an empty key names no peer.
  addPeerKey(peerKey) {
    if (!peerKey || this.peerKeys.has(peerKey)) {
      return;
    }
    if (this.limit <= this.peerKeys.size) {
      this.atLimit = true;
      return;
    }
    this.peerKeys.add(peerKey);
  }

  // Counts the peer of one provider contract.
  addContractDetails(details, receive) {
    if (details) {
      this.addPeerKey(contractPeerKey(details, receive));
    }
  }

  // Counts the peers of the provider contract rows the sdk shows now. Rows
  // leave when their contracts close; the count keeps their peers.
  addContractRows(rows) {
    for (const row of rows ?? []) {
      for (const peerKey of contractRowPeerKeys(row)) {
        this.addPeerKey(peerKey);
      }
    }
  }

  // The distinct count, and whether the count stopped at the limit.
  count() {
    return {count: this.peerKeys.size, atLimit: this.atLimit};
  }
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
