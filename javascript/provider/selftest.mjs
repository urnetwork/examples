// The credential-free self-test (PROVIDER_CONTRACT.md, "Self-test"). It checks
// the disclaimer, the status text, the providing state, the payout wallet, the
// clients-served count, the companion's status and exit codes, and the
// installation state files without a network, credentials, the native
// companion or the SDK runtime. `--self-test` runs every check;
// provider.test.mjs runs each one as a test.

import {createHash} from "node:crypto";
import {chmod, lstat, mkdtemp, rm, stat, symlink} from "node:fs/promises";
import {tmpdir} from "node:os";
import {join} from "node:path";
import {companionEnvironment} from "./companion.mjs";
import {companionExitCode, exitConfig, exitFailure, exitStopped} from "./session.mjs";
import {
  checkStateDir,
  clientJwtFileName,
  identityFileName,
  loadOrCreateInstanceId,
  loadProviderConfig,
  loadProviderIdentity,
  parseClientJwtClientId,
  readPrivateFile,
  writePrivateFile,
} from "./state.mjs";
import {
  ClientsServed,
  clientLimitStatusExceeded,
  clientsServedLimit,
  consentDisclaimer,
  contractRowPeerKeys,
  formatByteCount,
  parseCompanionStatus,
  payoutWalletChecking,
  payoutWalletFromResult,
  payoutWalletNotSet,
  payoutWalletScope,
  payoutWalletScopeAnotherProvider,
  payoutWalletScopeHotkey,
  payoutWalletScopeNetwork,
  payoutWalletScopeProvider,
  provideModePublic,
  providerState,
  providerStateClientLimit,
  providerStatePaused,
  providerStateProviding,
  providerStateStarting,
  providerStateStopped,
  providerStatusText,
  snWalletConsentScopeHotkey,
  statusKey,
  statusLine,
} from "./status.mjs";

// sha-256 of the consent disclaimer (utf-8, lf line breaks, no trailing
// newline), published in PROVIDER_CONTRACT.md for every example to check
const consentDisclaimerSha256 = "83edee1e45cccd5deb6b86755cc5f6a91b6ade26e7eefc1e7670b5833a95502c";

// the public Substrate development account, used only as test data
const walletA = "5GrwvaEF5zXb26Fz9rcQpDWS57CtERHpNehXCPcNoHGKutQY";

const provider = "11111111-1111-1111-1111-111111111111";
const clientA = "22222222-2222-2222-2222-222222222222";
const clientB = "33333333-3333-3333-3333-333333333333";
const zeroId = "00000000-0000-0000-0000-000000000000";

// the SDK's ProvideModeNone and ProvideModeNetwork
const provideModeNone = 0;
const provideModeNetwork = 1;

// Fails the check with message unless ok.
function check(ok, message) {
  if (!ok) {
    throw new Error(message);
  }
}

// Resolves when promise rejects; fails the check with message when it resolves.
async function checkRejects(promise, message) {
  let rejected = false;
  try {
    await promise;
  } catch {
    rejected = true;
  }
  check(rejected, message);
}

// A synthetic, unsigned JWT with the given payload json.
export function selfTestJwt(payloadJson) {
  return `e30.${Buffer.from(payloadJson).toString("base64url")}.test`;
}

// A private, empty state directory, removed by the returned function.
async function selfTestStateDir() {
  const stateDir = await mkdtemp(join(tmpdir(), "ur-provider-self-test-"));
  await chmod(stateDir, 0o700);
  return {stateDir, remove: () => rm(stateDir, {recursive: true, force: true})};
}

// The disclaimer is the contract's exact text.
export function checkConsentDisclaimer() {
  const digest = createHash("sha256").update(consentDisclaimer, "utf8").digest("hex");
  check(digest === consentDisclaimerSha256, "consent disclaimer differs from PROVIDER_CONTRACT.md");
}

// Byte counts use binary units with one decimal. The ties and the unit
// boundary match the Go reference.
export function checkFormatByteCount() {
  const cases = [
    [0, "0 B"],
    [1023, "1023 B"],
    [1024, "1.0 KiB"],
    [1536, "1.5 KiB"],
    [1048575, "1.0 MiB"],
    [13002342, "12.4 MiB"],
    [5 * 1024 * 1024 * 1024, "5.0 GiB"],
    [3 * 1024 * 1024 * 1024 * 1024, "3.0 TiB"],
    // exact ties round to the even tenth, as Go's %.1f does (the contract's
    // tie vectors 1280 and 1792); toFixed(1) alone would give "1.3 KiB"
    [1280, "1.2 KiB"],
    [1792, "1.8 KiB"],
    [2304, "2.2 KiB"],
    // 1023.94 KiB stays, 1023.95 KiB rounds to 1024.0 and moves on
    [1048524, "1023.9 KiB"],
    [1048525, "1.0 MiB"],
  ];
  for (const [byteCount, text] of cases) {
    const formatted = formatByteCount(byteCount);
    check(formatted === text, `byte count ${byteCount} formats as "${formatted}", want "${text}"`);
  }
}

// The client limit status names the SDK's retry time in UTC, rounded up to the
// next whole minute; other states never show it.
export function checkStatusText() {
  const cases = [
    // 2026-10-06 19:05:00.000 UTC, exactly on a minute
    [providerStateClientLimit, 1791313500000, "client limit, retry at 19:05 UTC"],
    // 19:04:00.001 rounds up, so the shown time is never before the retry
    [providerStateClientLimit, 1791313440001, "client limit, retry at 19:05 UTC"],
    // no retry time
    [providerStateClientLimit, 0, "client limit"],
    // 23:59:00.001 rolls over the hour and the day
    [providerStateClientLimit, 1791331140001, "client limit, retry at 00:00 UTC"],
    [providerStateStarting, 1791313500000, "starting"],
  ];
  for (const [state, clientLimitRetryTime, text] of cases) {
    const statusText = providerStatusText(state, clientLimitRetryTime);
    check(statusText === text, `status text "${statusText}" for "${state}" retrying at ${clientLimitRetryTime}, want "${text}"`);
  }
}

// The status line matches the contract's golden lines.
export function checkStatusLines() {
  const status = fields => ({
    state: providerStateStarting,
    clientLimitRetryTime: 0,
    clientsServed: 0,
    clientsServedAtLimit: false,
    dataProvidedByteCount: 0,
    payoutWallet: payoutWalletChecking,
    payoutWalletScope: "",
    ...fields,
  });
  const cases = [
    [status({}), "status: starting | clients served: 0 | data provided: 0 B | payout wallet: checking"],
    [
      status({state: providerStateProviding, clientsServed: 3, dataProvidedByteCount: 13002342, payoutWallet: walletA, payoutWalletScope: payoutWalletScopeNetwork}),
      `status: providing | clients served: 3 | data provided: 12.4 MiB | payout wallet: ${walletA} (network)`,
    ],
    [
      status({state: providerStateProviding, clientsServed: 3, dataProvidedByteCount: 13002342, payoutWallet: walletA, payoutWalletScope: payoutWalletScopeHotkey}),
      `status: providing | clients served: 3 | data provided: 12.4 MiB | payout wallet: ${walletA} (hotkey)`,
    ],
    [
      status({state: providerStatePaused, clientsServed: clientsServedLimit, clientsServedAtLimit: true, dataProvidedByteCount: 1536, payoutWallet: walletA, payoutWalletScope: payoutWalletScopeProvider}),
      `status: paused | clients served: 100000+ | data provided: 1.5 KiB | payout wallet: ${walletA} (this provider)`,
    ],
    [
      status({state: providerStateStopped, payoutWallet: payoutWalletNotSet}),
      "status: stopped | clients served: 0 | data provided: 0 B | payout wallet: not set",
    ],
    [
      status({state: providerStateClientLimit, clientLimitRetryTime: 1791313500000, payoutWallet: walletA, payoutWalletScope: payoutWalletScopeNetwork}),
      `status: client limit, retry at 19:05 UTC | clients served: 0 | data provided: 0 B | payout wallet: ${walletA} (network)`,
    ],
  ];
  for (const [providerStatus, line] of cases) {
    const statusTextLine = statusLine(providerStatus);
    check(statusTextLine === line, `status line "${statusTextLine}", want "${line}"`);
  }
}

// A change of the status text prints a line at once, including a new client
// limit retry time; the data counter alone does not.
export function checkStatusKey() {
  const status = {
    state: providerStateClientLimit,
    clientLimitRetryTime: 1791313500000,
    clientsServed: 0,
    clientsServedAtLimit: false,
    dataProvidedByteCount: 0,
    payoutWallet: payoutWalletChecking,
    payoutWalletScope: "",
  };
  // 19:25 UTC
  check(statusKey(status) !== statusKey({...status, clientLimitRetryTime: 1791314700000}), "a new client limit retry time does not print a status line");
  check(statusKey(status) === statusKey({...status, dataProvidedByteCount: 1536}), "the data counter alone prints a status line");
}

// The providing state follows the provide mode, client limit, pause, enable
// and connected rules, in that order.
export function checkProviderState() {
  const cases = [
    [{provideMode: provideModeNone, clientLimitStatus: "", providePaused: false, provideEnabled: false, providerConnected: false}, providerStateStopped],
    [{provideMode: provideModeNetwork, clientLimitStatus: "", providePaused: false, provideEnabled: true, providerConnected: true}, providerStateStopped],
    [{provideMode: provideModePublic, clientLimitStatus: "", providePaused: false, provideEnabled: true, providerConnected: false}, providerStateStarting],
    [{provideMode: provideModePublic, clientLimitStatus: "", providePaused: false, provideEnabled: false, providerConnected: true}, providerStateStarting],
    [{provideMode: provideModePublic, clientLimitStatus: "", providePaused: false, provideEnabled: true, providerConnected: true}, providerStateProviding],
    [{provideMode: provideModePublic, clientLimitStatus: "", providePaused: true, provideEnabled: true, providerConnected: true}, providerStatePaused],
    // the client limit comes after stopped and before every other state
    [{provideMode: provideModeNetwork, clientLimitStatus: clientLimitStatusExceeded, providePaused: false, provideEnabled: true, providerConnected: true}, providerStateStopped],
    [{provideMode: provideModePublic, clientLimitStatus: clientLimitStatusExceeded, providePaused: true, provideEnabled: true, providerConnected: true}, providerStateClientLimit],
    [{provideMode: provideModePublic, clientLimitStatus: clientLimitStatusExceeded, providePaused: false, provideEnabled: false, providerConnected: false}, providerStateClientLimit],
    [{provideMode: provideModePublic, clientLimitStatus: "", providePaused: true, provideEnabled: true, providerConnected: true}, providerStatePaused],
  ];
  for (const [values, state] of cases) {
    const providingState = providerState(values);
    check(providingState === state, `provider state "${providingState}" for ${JSON.stringify(values)}, want "${state}"`);
  }
}

// The payout wallet is labeled by its consent scope first, then by the owner
// of its mapping, and read from the effective wallet of GET /sn/wallet.
export function checkPayoutWallet() {
  const cases = [
    // a hotkey delegation is network-level but not the network's wallet
    [snWalletConsentScopeHotkey, "", payoutWalletScopeHotkey],
    [snWalletConsentScopeHotkey, provider, payoutWalletScopeHotkey],
    ["network", "", payoutWalletScopeNetwork],
    ["", "", payoutWalletScopeNetwork],
    ["provider", provider, payoutWalletScopeProvider],
    ["", provider, payoutWalletScopeProvider],
    ["provider", clientA, payoutWalletScopeAnotherProvider],
  ];
  for (const [walletConsentScope, walletClientId, scope] of cases) {
    const walletScope = payoutWalletScope(walletConsentScope, walletClientId, provider);
    check(walletScope === scope, `payout wallet scope "${walletScope}" for consent scope "${walletConsentScope}" and client "${walletClientId}", want "${scope}"`);
  }

  const results = [
    [{wallet: {coldkey_ss58: walletA, set_at_millis: 1, consent_scope: "network"}, wallets: []}, {payoutWallet: walletA, payoutWalletScope: payoutWalletScopeNetwork}],
    [{wallet: {coldkey_ss58: walletA, client_id: provider, set_at_millis: 1, consent_scope: "provider"}, wallets: []}, {payoutWallet: walletA, payoutWalletScope: payoutWalletScopeProvider}],
    [{wallet: {coldkey_ss58: walletA, set_at_millis: 1, consent_scope: "hotkey"}, wallets: []}, {payoutWallet: walletA, payoutWalletScope: payoutWalletScopeHotkey}],
    [{wallets: []}, {payoutWallet: payoutWalletNotSet, payoutWalletScope: ""}],
    [{wallet: {coldkey_ss58: "", set_at_millis: 1}, wallets: []}, {payoutWallet: payoutWalletNotSet, payoutWalletScope: ""}],
    // an error is a failed read
    [{wallets: [], error: {message: "synthetic error"}}, null],
    [null, null],
  ];
  for (const [result, wallet] of results) {
    const payoutWallet = payoutWalletFromResult(result, provider);
    check(JSON.stringify(payoutWallet) === JSON.stringify(wallet), `payout wallet ${JSON.stringify(payoutWallet)} for ${JSON.stringify(result)}, want ${JSON.stringify(wallet)}`);
  }
}

// Provider contract rows count once per client, up to the limit. The rows are
// the ones the SDK's provider contract details view controller makes for the
// contract's peer vectors.
export function checkClientsServed() {
  const row = (clientId, sendContractIds, receiveContractIds) => ({
    clientId,
    sendContracts: sendContractIds.map(contractId => ({contractId, usedByteCount: 0, totalByteCount: 0, bitRate: 0, hasStream: clientId === zeroId})),
    receiveContracts: receiveContractIds.map(contractId => ({contractId, usedByteCount: 0, totalByteCount: 0, bitRate: 0, hasStream: clientId === zeroId})),
    sendByteCount: 0,
    receiveByteCount: 0,
    lastActivityMillis: 0,
    closing: false,
  });
  const keyCases = [
    // receive: SourceId 2222, DestinationId 1111
    [row(clientA, [], ["55555555-5555-5555-5555-555555555555"]), [clientA]],
    // send: SourceId 1111, DestinationId 2222
    [row(clientA, ["66666666-6666-6666-6666-666666666666"], []), [clientA]],
    // receive: SourceId 0000, StreamId 4444; the rows carry no stream id, so
    // the contract id is the key
    [row(zeroId, [], ["77777777-7777-7777-7777-777777777777"]), ["contract:77777777-7777-7777-7777-777777777777"]],
    // no path: the sdk keys the row by the contract id
    [row("88888888-8888-8888-8888-888888888888", [], ["88888888-8888-8888-8888-888888888888"]), ["contract:88888888-8888-8888-8888-888888888888"]],
    // upper case ids are the same peer
    [row(clientA.toUpperCase(), [], ["99999999-9999-9999-9999-999999999999"]), [clientA]],
  ];
  for (const [contractRow, peerKeys] of keyCases) {
    const keys = contractRowPeerKeys(contractRow);
    check(JSON.stringify(keys) === JSON.stringify(peerKeys), `contract row peer keys ${JSON.stringify(keys)}, want ${JSON.stringify(peerKeys)}`);
  }

  // both directions of one client count once
  const served = new ClientsServed(2);
  served.addRows([row(clientA, ["66666666-6666-6666-6666-666666666666"], ["55555555-5555-5555-5555-555555555555"])]);
  served.addRows([row(clientA, [], ["99999999-9999-9999-9999-999999999999"])]);
  let {count, atLimit} = served.count();
  check(count === 1 && !atLimit, `one client counted as ${count} (at limit ${atLimit})`);
  served.addRows([row(clientB, [], ["aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"])]);
  ({count, atLimit} = served.count());
  check(count === 2 && !atLimit, `two clients counted as ${count} (at limit ${atLimit})`);
  // a third distinct peer reaches the limit of 2
  served.addRows([row(zeroId, [], ["bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"])]);
  ({count, atLimit} = served.count());
  check(count === 2 && atLimit, `limited count ${count} (at limit ${atLimit})`);
}

// The companion's status route is read strictly, and the companion's exit
// maps to this program's exit code.
export function checkCompanion() {
  const companionStatus = parseCompanionStatus('{"ProvideMode":3,"ProviderConnected":false,"ClientLimitStatus":{"Status":"client_limit_exceeded","RetryTime":1791313500000},"DeviceRpcStarted":false}');
  check(companionStatus.provideMode === provideModePublic && !companionStatus.providerConnected &&
    companionStatus.clientLimitStatus === clientLimitStatusExceeded && companionStatus.clientLimitRetryTime === 1791313500000 &&
    !companionStatus.deviceRpcStarted, `companion status ${JSON.stringify(companionStatus)}`);
  const invalidBodies = [
    "",
    "{}",
    '{"ProvideMode":3,"ProviderConnected":false,"DeviceRpcStarted":false}',
    '{"ProvideMode":"3","ProviderConnected":false,"ClientLimitStatus":{"Status":"","RetryTime":0},"DeviceRpcStarted":false}',
    '{"ProvideMode":3,"ProviderConnected":false,"ClientLimitStatus":{"Status":"","RetryTime":0.5},"DeviceRpcStarted":false}',
  ];
  for (const body of invalidBodies) {
    let refused = false;
    try {
      parseCompanionStatus(body);
    } catch {
      refused = true;
    }
    check(refused, `companion status ${JSON.stringify(body)} accepted`);
  }

  const exitCases = [
    [{code: 0, signal: null, error: null}, exitStopped],
    [{code: 78, signal: null, error: null}, exitConfig],
    [{code: 1, signal: null, error: null}, exitFailure],
    [{code: 2, signal: null, error: null}, exitFailure],
    [{code: null, signal: "SIGKILL", error: null}, exitFailure],
    [{code: null, signal: null, error: new Error("spawn failed")}, exitFailure],
  ];
  for (const [exitInfo, code] of exitCases) {
    check(companionExitCode(exitInfo) === code, `companion exit ${JSON.stringify(exitInfo)} maps to ${companionExitCode(exitInfo)}, want ${code}`);
  }

  const environment = companionEnvironment({PATH: "/usr/bin", URNETWORK_COMPANION_PROVIDE: ""}, {stateDir: "/private/state", token: "t".repeat(64), address: "127.0.0.1:49152"});
  check(environment.URNETWORK_COMPANION_PROVIDE === "public" && environment.URNETWORK_PROVIDER_STATE_DIR === "/private/state" &&
    environment.URNETWORK_COMPANION_TOKEN === "t".repeat(64) && environment.URNETWORK_COMPANION_ADDRESS === "127.0.0.1:49152" &&
    environment.URNETWORK_COMPANION_STOP_ON_STDIN_CLOSE === "1" && environment.PATH === "/usr/bin", `companion environment ${JSON.stringify(environment)}`);
}

// Only a JWT with a valid client_id claim is a client credential.
export function checkClientJwtClaims() {
  const clientId = parseClientJwtClientId(selfTestJwt(`{"client_id":"${provider}","network_id":"${clientA}"}`));
  check(clientId === provider, `client jwt claim "${clientId}"`);
  const invalidJwts = [
    "",
    "not-a-jwt",
    // a network jwt has no client_id claim
    selfTestJwt(`{"network_id":"${clientA}"}`),
    selfTestJwt('{"client_id":"not-a-uuid"}'),
    selfTestJwt('{"client_id":7}'),
    "e30.%%%.test",
  ];
  for (const invalidJwt of invalidJwts) {
    let refused = false;
    try {
      parseClientJwtClientId(invalidJwt);
    } catch {
      refused = true;
    }
    check(refused, `invalid client jwt "${invalidJwt}" accepted`);
  }
}

// State files are private, replaced atomically, created once and bound to
// their client.
export async function checkStateFiles() {
  const {stateDir, remove} = await selfTestStateDir();
  try {
    // an atomic private write
    const path = join(stateDir, clientJwtFileName);
    await writePrivateFile(path, "first\n");
    await writePrivateFile(path, "second\n");
    const data = await readPrivateFile(path);
    check(data.toString("utf8") === "second\n", `private file round trip "${data}"`);
    if (process.platform !== "win32") {
      check(((await stat(path)).mode & 0o777) === 0o600, "a private file is not mode 600");
      // a file or directory that others can read is refused, as is a symlink
      await chmod(path, 0o644);
      await checkRejects(readPrivateFile(path), "a group-readable credential file was accepted");
      await chmod(path, 0o600);
      const linkPath = join(stateDir, "linked.jwt");
      await symlink(path, linkPath);
      check((await lstat(linkPath)).isSymbolicLink(), "the test link is not a symlink");
      await checkRejects(readPrivateFile(linkPath), "a symlinked credential file was accepted");
      await chmod(stateDir, 0o755);
      await checkRejects(checkStateDir(stateDir), "a group-readable state directory was accepted");
      await chmod(stateDir, 0o700);
    }
    await checkStateDir(stateDir);

    // the instance id is created once and reused
    const instanceId = await loadOrCreateInstanceId(stateDir);
    check(/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(instanceId), `instance id "${instanceId}" is not a uuid`);
    const again = await loadOrCreateInstanceId(stateDir);
    check(again === instanceId, `instance id changed from "${instanceId}" to "${again}"`);

    // the identity that the companion writes belongs to its client
    const identity = {
      version: 1,
      client_id: provider,
      client_key_seed: Buffer.alloc(32, 1).toString("base64"),
      provide_tls_certificate_pem: Buffer.from("synthetic certificate").toString("base64"),
      provide_tls_private_key_pem: Buffer.from("synthetic private key").toString("base64"),
    };
    await writePrivateFile(join(stateDir, identityFileName), JSON.stringify(identity));
    const loaded = await loadProviderIdentity(stateDir, provider);
    check(loaded !== null && loaded.client_key_seed === identity.client_key_seed &&
      loaded.provide_tls_private_key_pem === identity.provide_tls_private_key_pem, "identity round trip failed");
    // another client's identity is not used: on that client's first run the
    // companion gives the device no key material and saves a new identity
    check(await loadProviderIdentity(stateDir, clientA) === null, "another client's identity was used");
    const invalidIdentities = [
      '{"version":1}',
      JSON.stringify({...identity, version: 2}),
      JSON.stringify({...identity, client_key_seed: Buffer.alloc(31, 1).toString("base64")}),
      JSON.stringify({...identity, client_key_seed: "not base64!"}),
      "not json",
    ];
    for (const invalidIdentity of invalidIdentities) {
      await writePrivateFile(join(stateDir, identityFileName), invalidIdentity);
      await checkRejects(loadProviderIdentity(stateDir, provider), `invalid identity ${invalidIdentity} was accepted`);
    }
  } finally {
    await remove();
  }
}

// A missing or incomplete installation state is refused; a first run loads
// without an identity.
export async function checkProviderConfig() {
  await checkRejects(loadProviderConfig(""), "a missing state directory was accepted");
  await checkRejects(loadProviderConfig(join("relative", "state")), "a relative state directory was accepted");
  const {stateDir, remove} = await selfTestStateDir();
  try {
    await checkRejects(loadProviderConfig(stateDir), "a state directory without client.jwt was accepted");
    // a network jwt has no client_id claim
    await writePrivateFile(join(stateDir, clientJwtFileName), `${selfTestJwt(`{"network_id":"${clientA}"}`)}\n`);
    await checkRejects(loadProviderConfig(stateDir), "a network jwt was accepted");
    const clientJwt = selfTestJwt(`{"client_id":"${provider}"}`);
    await writePrivateFile(join(stateDir, clientJwtFileName), `${clientJwt}\n`);
    const config = await loadProviderConfig(stateDir);
    check(config.clientJwt === clientJwt && config.clientId === provider && config.instanceId !== "" && config.identity === null,
      "first-run configuration differs");
    const again = await loadProviderConfig(stateDir);
    check(again.instanceId === config.instanceId, "the instance id changed on the second start");
  } finally {
    await remove();
  }
}

// Every check, in order.
export const selfTestChecks = [
  checkConsentDisclaimer,
  checkFormatByteCount,
  checkStatusText,
  checkStatusLines,
  checkStatusKey,
  checkProviderState,
  checkPayoutWallet,
  checkClientsServed,
  checkCompanion,
  checkClientJwtClaims,
  checkStateFiles,
  checkProviderConfig,
];

// Runs every check and rejects with the first failure.
export async function runSelfTest() {
  for (const selfTestCheck of selfTestChecks) {
    await selfTestCheck();
  }
}
