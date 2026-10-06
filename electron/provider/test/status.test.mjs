// The status rules of PROVIDER_CONTRACT.md ("Status", "Self-test"): the
// disclaimer, the byte, status text, status line, status rule and payout
// wallet scope vectors, and reading the companion's /provider-status body into
// the four fields. The peer key and clients-served vectors are the
// companion's (javascript/integration/companion, provider_test.go), which
// counts the clients served. No Electron, network or credentials.

import {test} from "node:test";
import assert from "node:assert/strict";
import {createHash} from "node:crypto";
import {readFileSync} from "node:fs";
import {
  clientLimitStatusExceeded,
  clientLimitStatusNone,
  consentDisclaimer,
  dataProvidedByteCount,
  formatByteCount,
  parseProviderStatus,
  payoutWalletChecking,
  payoutWalletNotSet,
  payoutWalletScope,
  payoutWalletScopeAnotherProvider,
  payoutWalletScopeHotkey,
  payoutWalletScopeNetwork,
  payoutWalletScopeProvider,
  provideModeNetwork,
  provideModeNone,
  provideModePublic,
  providerState,
  providerStateClientLimit,
  providerStatePaused,
  providerStateProviding,
  providerStateStarting,
  providerStateStopped,
  providerStatusText,
  statusFields,
  statusLine,
} from "../status.mjs";

// sha-256 of the consent disclaimer (utf-8, lf line breaks, no trailing
// newline), published in PROVIDER_CONTRACT.md for every example to check
const consentDisclaimerSha256 = "83edee1e45cccd5deb6b86755cc5f6a91b6ade26e7eefc1e7670b5833a95502c";

// the public Substrate development account, test data only
const walletA = "5GrwvaEF5zXb26Fz9rcQpDWS57CtERHpNehXCPcNoHGKutQY";

const provider = "11111111-1111-1111-1111-111111111111";
const clientA = "22222222-2222-2222-2222-222222222222";

// A /provider-status body in the companion's shape, with changes.
function providerStatusBody(changes = {}) {
  return {
    ProvideMode: 3,
    ProvideEnabled: true,
    ProvidePaused: false,
    ProviderConnected: true,
    ClientLimitStatus: {Status: "", RetryTime: 0},
    ProviderPacketStats: {RemoteEgressByteCount: 13000000, RemoteIngressByteCount: 2342},
    ClientsServed: 3,
    ClientsServedAtLimit: false,
    DeviceRpcStarted: true,
    ...changes,
  };
}

// The four fields for a /provider-status body and a payout wallet.
function routeFields(body, payoutWallet = walletA, payoutWalletScope = payoutWalletScopeNetwork) {
  const deviceValues = parseProviderStatus(body);
  return statusFields({
    state: providerState(deviceValues),
    clientLimitRetryTime: deviceValues.clientLimitRetryTime,
    clientsServed: deviceValues.clientsServed,
    clientsServedAtLimit: deviceValues.clientsServedAtLimit,
    dataProvidedByteCount: deviceValues.dataProvidedByteCount,
    payoutWallet,
    payoutWalletScope,
  });
}

test("the consent disclaimer is the contract's exact text", () => {
  const digest = createHash("sha256").update(consentDisclaimer, "utf8").digest("hex");
  assert.equal(digest, consentDisclaimerSha256);
});

test("the window shows the disclaimer next to start with the same text", () => {
  const html = readFileSync(new URL("../renderer/index.html", import.meta.url), "utf8");
  const match = /<p id="disclaimer"[^>]*>([^<]*)<\/p>/.exec(html);
  assert.ok(match, "index.html has the disclaimer paragraph");
  assert.equal(match[1].replaceAll("\r\n", "\n"), consentDisclaimer);
  assert.ok(html.indexOf('id="disclaimer"') < html.indexOf('id="start"'));
});

test("byte counts use binary units with one decimal", () => {
  const cases = [
    {byteCount: 0, text: "0 B"},
    {byteCount: 1023, text: "1023 B"},
    {byteCount: 1024, text: "1.0 KiB"},
    {byteCount: 1536, text: "1.5 KiB"},
    // exact halves round to even (contract addendum 45c5d3f); toFixed would show 1.3 KiB
    {byteCount: 1280, text: "1.2 KiB"},
    {byteCount: 1792, text: "1.8 KiB"},
    {byteCount: 1048575, text: "1.0 MiB"},
    {byteCount: 13002342, text: "12.4 MiB"},
    {byteCount: 5 * 1024 * 1024 * 1024, text: "5.0 GiB"},
    {byteCount: 3 * 1024 * 1024 * 1024 * 1024, text: "3.0 TiB"},
  ];
  for (const c of cases) {
    assert.equal(formatByteCount(c.byteCount), c.text, `${c.byteCount} bytes`);
  }
});

test("byte counts round like the go provider's %.1f on more halves and at the unit edges", () => {
  // the go provider's output for these counts
  const cases = [
    {byteCount: 1331, text: "1.3 KiB"},
    {byteCount: 2304, text: "2.2 KiB"},
    {byteCount: 2816, text: "2.8 KiB"},
    {byteCount: 1048524, text: "1023.9 KiB"},
    {byteCount: 2 ** 62, text: "4.0 EiB"},
    {byteCount: 2 ** 63, text: "8.0 EiB"},
  ];
  for (const c of cases) {
    assert.equal(formatByteCount(c.byteCount), c.text, `${c.byteCount} bytes`);
  }
});

test("the client limit status names the retry time rounded up to the minute in utc", () => {
  const cases = [
    // 2026-10-06 19:05:00.000 UTC, exactly on a minute
    {state: providerStateClientLimit, clientLimitRetryTime: 1791313500000, text: "client limit, retry at 19:05 UTC"},
    // 19:04:00.001 rounds up, so the shown time is never before the retry
    {state: providerStateClientLimit, clientLimitRetryTime: 1791313440001, text: "client limit, retry at 19:05 UTC"},
    // no retry time
    {state: providerStateClientLimit, clientLimitRetryTime: 0, text: "client limit"},
    // 23:59:00.001 rolls over the hour and the day
    {state: providerStateClientLimit, clientLimitRetryTime: 1791331140001, text: "client limit, retry at 00:00 UTC"},
    {state: providerStateStarting, clientLimitRetryTime: 1791313500000, text: "starting"},
  ];
  for (const c of cases) {
    assert.equal(providerStatusText(c.state, c.clientLimitRetryTime), c.text, `${c.state} retrying at ${c.clientLimitRetryTime}`);
  }
});

test("the status line and fields match the contract's golden lines", () => {
  const cases = [
    {
      status: {state: providerStateStarting, clientLimitRetryTime: 0, clientsServed: 0, clientsServedAtLimit: false, dataProvidedByteCount: 0, payoutWallet: payoutWalletChecking, payoutWalletScope: ""},
      line: "status: starting | clients served: 0 | data provided: 0 B | payout wallet: checking",
    },
    {
      status: {state: providerStateProviding, clientLimitRetryTime: 0, clientsServed: 3, clientsServedAtLimit: false, dataProvidedByteCount: 13002342, payoutWallet: walletA, payoutWalletScope: payoutWalletScopeNetwork},
      line: `status: providing | clients served: 3 | data provided: 12.4 MiB | payout wallet: ${walletA} (network)`,
    },
    {
      status: {state: providerStateProviding, clientLimitRetryTime: 0, clientsServed: 3, clientsServedAtLimit: false, dataProvidedByteCount: 13002342, payoutWallet: walletA, payoutWalletScope: payoutWalletScopeHotkey},
      line: `status: providing | clients served: 3 | data provided: 12.4 MiB | payout wallet: ${walletA} (hotkey)`,
    },
    {
      status: {state: providerStatePaused, clientLimitRetryTime: 0, clientsServed: 100 * 1000, clientsServedAtLimit: true, dataProvidedByteCount: 1536, payoutWallet: walletA, payoutWalletScope: payoutWalletScopeProvider},
      line: `status: paused | clients served: 100000+ | data provided: 1.5 KiB | payout wallet: ${walletA} (this provider)`,
    },
    {
      status: {state: providerStateStopped, clientLimitRetryTime: 0, clientsServed: 0, clientsServedAtLimit: false, dataProvidedByteCount: 0, payoutWallet: payoutWalletNotSet, payoutWalletScope: ""},
      line: "status: stopped | clients served: 0 | data provided: 0 B | payout wallet: not set",
    },
    {
      status: {state: providerStateClientLimit, clientLimitRetryTime: 1791313500000, clientsServed: 0, clientsServedAtLimit: false, dataProvidedByteCount: 0, payoutWallet: walletA, payoutWalletScope: payoutWalletScopeNetwork},
      line: `status: client limit, retry at 19:05 UTC | clients served: 0 | data provided: 0 B | payout wallet: ${walletA} (network)`,
    },
  ];
  for (const c of cases) {
    assert.equal(statusLine(c.status), c.line);
    const fields = statusFields(c.status);
    assert.equal(`status: ${fields.status} | clients served: ${fields.clientsServed} | data provided: ${fields.dataProvided} | payout wallet: ${fields.payoutWallet}`, c.line);
  }
});

test("a new client limit retry time changes the status field", () => {
  const status = {state: providerStateClientLimit, clientLimitRetryTime: 1791313500000, clientsServed: 0, clientsServedAtLimit: false, dataProvidedByteCount: 0, payoutWallet: payoutWalletChecking, payoutWalletScope: ""};
  // 19:25 UTC
  const retried = {...status, clientLimitRetryTime: 1791314700000};
  assert.notEqual(statusFields(retried).status, statusFields(status).status);
  assert.equal(statusFields(retried).status, "client limit, retry at 19:25 UTC");
});

test("the providing state follows the mode, client limit, pause, enable and connected rules in order", () => {
  const cases = [
    {provideMode: provideModeNone, clientLimitStatus: clientLimitStatusNone, providePaused: false, provideEnabled: false, providerConnected: false, state: providerStateStopped},
    {provideMode: provideModeNetwork, clientLimitStatus: clientLimitStatusNone, providePaused: false, provideEnabled: true, providerConnected: true, state: providerStateStopped},
    {provideMode: provideModePublic, clientLimitStatus: clientLimitStatusNone, providePaused: false, provideEnabled: true, providerConnected: false, state: providerStateStarting},
    {provideMode: provideModePublic, clientLimitStatus: clientLimitStatusNone, providePaused: false, provideEnabled: false, providerConnected: true, state: providerStateStarting},
    {provideMode: provideModePublic, clientLimitStatus: clientLimitStatusNone, providePaused: false, provideEnabled: true, providerConnected: true, state: providerStateProviding},
    {provideMode: provideModePublic, clientLimitStatus: clientLimitStatusNone, providePaused: true, provideEnabled: true, providerConnected: true, state: providerStatePaused},
    // the contract's status rule vectors: the client limit comes after
    // stopped and before every other state
    {provideMode: provideModeNetwork, clientLimitStatus: clientLimitStatusExceeded, providePaused: false, provideEnabled: true, providerConnected: true, state: providerStateStopped},
    {provideMode: provideModePublic, clientLimitStatus: clientLimitStatusExceeded, providePaused: true, provideEnabled: true, providerConnected: true, state: providerStateClientLimit},
    {provideMode: provideModePublic, clientLimitStatus: clientLimitStatusExceeded, providePaused: false, provideEnabled: false, providerConnected: false, state: providerStateClientLimit},
    {provideMode: provideModePublic, clientLimitStatus: clientLimitStatusNone, providePaused: true, provideEnabled: true, providerConnected: true, state: providerStatePaused},
  ];
  for (const c of cases) {
    const {state, ...readout} = c;
    assert.equal(providerState(readout), state, JSON.stringify(readout));
  }
});

test("the payout wallet is labeled by its consent scope first, then by the owner of its mapping", () => {
  const cases = [
    // a hotkey delegation is network-level but not the network's wallet
    {walletConsentScope: "hotkey", walletClientId: "", scope: payoutWalletScopeHotkey},
    {walletConsentScope: "hotkey", walletClientId: provider, scope: payoutWalletScopeHotkey},
    {walletConsentScope: "network", walletClientId: "", scope: payoutWalletScopeNetwork},
    {walletConsentScope: "", walletClientId: "", scope: payoutWalletScopeNetwork},
    {walletConsentScope: "provider", walletClientId: provider, scope: payoutWalletScopeProvider},
    {walletConsentScope: "", walletClientId: provider, scope: payoutWalletScopeProvider},
    {walletConsentScope: "provider", walletClientId: clientA, scope: payoutWalletScopeAnotherProvider},
  ];
  for (const c of cases) {
    assert.equal(payoutWalletScope(c.walletConsentScope, c.walletClientId, provider), c.scope, JSON.stringify(c));
  }
});

test("the companion's status body reads into the device values", () => {
  assert.deepEqual(parseProviderStatus(providerStatusBody()), {
    provideMode: 3,
    provideEnabled: true,
    providePaused: false,
    providerConnected: true,
    clientLimitStatus: "",
    clientLimitRetryTime: 0,
    dataProvidedByteCount: 13002342,
    clientsServed: 3,
    clientsServedAtLimit: false,
  });
  // no provider: null stats provide 0 bytes
  assert.equal(parseProviderStatus(providerStatusBody({ProviderPacketStats: null})).dataProvidedByteCount, 0);
  assert.equal(dataProvidedByteCount(null), 0);
  assert.equal(dataProvidedByteCount({RemoteEgressByteCount: 5, RemoteIngressByteCount: 7}), 12);
});

test("the companion's status body shows the contract's fields", () => {
  const cases = [
    {body: providerStatusBody(), status: "providing", clientsServed: "3", dataProvided: "12.4 MiB"},
    {body: providerStatusBody({ClientsServed: 100 * 1000, ClientsServedAtLimit: true}), status: "providing", clientsServed: "100000+", dataProvided: "12.4 MiB"},
    {body: providerStatusBody({ProviderPacketStats: null, ClientsServed: 0, ProviderConnected: false}), status: "starting", clientsServed: "0", dataProvided: "0 B"},
    {body: providerStatusBody({ProvideEnabled: false}), status: "starting", clientsServed: "3", dataProvided: "12.4 MiB"},
    {body: providerStatusBody({ProvidePaused: true, ProviderPacketStats: {RemoteEgressByteCount: 1280, RemoteIngressByteCount: 0}}), status: "paused", clientsServed: "3", dataProvided: "1.2 KiB"},
    // the client limit comes after stopped and before paused, with its retry time
    {body: providerStatusBody({ProvidePaused: true, ClientLimitStatus: {Status: "client_limit_exceeded", RetryTime: 1791313440001}}), status: "client limit, retry at 19:05 UTC", clientsServed: "3", dataProvided: "12.4 MiB"},
    {body: providerStatusBody({ProviderConnected: false, ClientLimitStatus: {Status: "client_limit_exceeded", RetryTime: 0}}), status: "client limit", clientsServed: "3", dataProvided: "12.4 MiB"},
    {body: providerStatusBody({ProvideMode: 1, ClientLimitStatus: {Status: "client_limit_exceeded", RetryTime: 1791313500000}}), status: "stopped", clientsServed: "3", dataProvided: "12.4 MiB"},
    {body: providerStatusBody({ProvideMode: 0, ProvideEnabled: false, ProviderConnected: false}), status: "stopped", clientsServed: "3", dataProvided: "12.4 MiB"},
  ];
  for (const c of cases) {
    const fields = routeFields(c.body);
    assert.deepEqual(
      {status: fields.status, clientsServed: fields.clientsServed, dataProvided: fields.dataProvided},
      {status: c.status, clientsServed: c.clientsServed, dataProvided: c.dataProvided},
      JSON.stringify(c.body),
    );
  }
  // the payout wallet is the app's own read, labeled for this client
  assert.equal(routeFields(providerStatusBody(), walletA, payoutWalletScope("provider", clientA, provider)).payoutWallet, `${walletA} (another provider)`);
});

test("a status body that is not the companion's shape is refused", () => {
  const invalidBodies = [
    null,
    [],
    "providing",
    {},
    providerStatusBody({ProvideMode: "3"}),
    providerStatusBody({ProvideEnabled: undefined}),
    providerStatusBody({ProvidePaused: 0}),
    providerStatusBody({ProviderConnected: "true"}),
    providerStatusBody({ClientLimitStatus: null}),
    providerStatusBody({ClientLimitStatus: {Status: "", RetryTime: "0"}}),
    providerStatusBody({ClientLimitStatus: {Status: "", RetryTime: -1}}),
    providerStatusBody({ProviderPacketStats: {RemoteEgressByteCount: 5}}),
    providerStatusBody({ProviderPacketStats: {RemoteEgressByteCount: 5, RemoteIngressByteCount: 1.5}}),
    providerStatusBody({ProviderPacketStats: 12}),
    providerStatusBody({ClientsServed: -1}),
    providerStatusBody({ClientsServed: undefined}),
    providerStatusBody({ClientsServedAtLimit: "false"}),
  ];
  for (const body of invalidBodies) {
    assert.throws(() => parseProviderStatus(body), /unexpected provider status/, JSON.stringify(body));
  }
});
