// The credential-free self-test (EMBED_CONTRACT.md, "Self-test"). It checks
// the byte, reset time, client limit and status line vectors, the status rules
// and their order, the data fields, the cap object parsing, the JWT client_id
// claim, the token fetch against a stand-in token server on loopback, the
// state directory, the configuration errors and the exit codes. It needs no
// credentials and no network, and starts no companion and no device.
// `--self-test` runs every check; embed.test.mjs runs each one as a test.

import {chmod, lstat, mkdtemp, readdir, rm, stat, symlink} from "node:fs/promises";
import {createServer} from "node:http";
import {tmpdir} from "node:os";
import {join} from "node:path";
import {parseCommand} from "./command.mjs";
import {companionEnvironment} from "./companion.mjs";
import {companionExitCode, exitConfig, exitFailure, exitStopped, loadEmbedSettings, obtainInstallation} from "./session.mjs";
import {
  ConfigurationError,
  checkStateDir,
  clientJwtFileName,
  instanceIdFileName,
  loadClientJwt,
  loadOrCreateInstanceId,
  parseClientJwtClientId,
  readPrivateFile,
  writePrivateFile,
} from "./state.mjs";
import {
  CapReadings,
  clientLimitText,
  dataFields,
  formatDataAmount,
  parseCapObject,
  parseEmbedStatus,
  resetText,
  statusLine,
  statusText,
} from "./status.mjs";
import {TokenError, fetchClientJwt, tokenServerOrigin, tokenServerSettings} from "./token.mjs";

const clientA = "11111111-1111-1111-1111-111111111111";
const clientB = "22222222-2222-2222-2222-222222222222";
const installationA = "33333333-3333-3333-3333-333333333333";

// a synthetic demo session token of the minimum length
const demoSession = "d".repeat(32);

// Fails the check with message unless ok.
function check(ok, message) {
  if (!ok) {
    throw new Error(message);
  }
}

// Resolves with the rejection of promise; fails the check with message when it
// resolves.
async function rejection(promise, message) {
  try {
    await promise;
  } catch (error) {
    return error;
  }
  throw new Error(message);
}

// A synthetic, unsigned JWT with the given payload json.
export function selfTestJwt(payloadJson) {
  return `e30.${Buffer.from(payloadJson).toString("base64url")}.test`;
}

// A private, empty state directory; remove deletes it.
async function selfTestStateDir() {
  const stateDir = await mkdtemp(join(tmpdir(), "ur-embed-selftest-"));
  await chmod(stateDir, 0o700);
  return {stateDir, remove: () => rm(stateDir, {recursive: true, force: true})};
}

// A cap object with the given fields over no caps and no usage.
function capObject(fields) {
  return parseCapObject({
    client_id: clientA,
    monthly_byte_limit: null,
    monthly_used_byte_count: 0,
    monthly_period_start: "2026-10-01T00:00:00Z",
    monthly_period_end: "2026-11-01T00:00:00Z",
    total_byte_limit: null,
    total_used_byte_count: 0,
    total_period_start: "2026-09-15T00:00:00Z",
    capped: false,
    capped_reason: "",
    ...fields,
  });
}

// The status line for a console app (started, not signed out) with a cap
// reading.
function consoleLine({clientLimitStatus = "", clientLimitRetryTime = 0, cap = null, firstReadingFailed = false, providerStateAdded = 0}) {
  const capReadings = new CapReadings();
  if (cap !== null) {
    capReadings.succeeded(cap);
  } else if (firstReadingFailed) {
    capReadings.failed();
  }
  return statusLine({
    status: statusText({clientLimitStatus, clientLimitRetryTime, cap: capReadings.cap, providerStateAdded}),
    ...dataFields(capReadings),
  });
}

// The byte vectors: decimal units, one decimal, ties to even, the next unit at
// 1000.0.
export function checkDataAmounts() {
  const vectors = [
    [0, "0 B"], [999, "999 B"], [1000, "1.0 kB"], [999949, "999.9 kB"],
    [1250, "1.2 kB"], [1750, "1.8 kB"],
    [999999, "1.0 MB"],
    [1234567890, "1.2 GB"], [5000000000, "5.0 GB"], [10000000000, "10.0 GB"], [3000000000000, "3.0 TB"],
  ];
  for (const [byteCount, text] of vectors) {
    check(formatDataAmount(byteCount) === text, `${byteCount} bytes shows ${formatDataAmount(byteCount)}, want ${text}`);
  }
}

// The reset time vectors: UTC, rounded up to the next whole minute; a time that
// does not parse has no reset text.
export function checkResetText() {
  const vectors = [
    ["2026-11-01T00:00:00Z", "resets 2026-11-01 00:00 UTC"],
    ["2026-10-31T23:59:00.001Z", "resets 2026-11-01 00:00 UTC"],
    ["2026-10-31T19:00:00-05:00", "resets 2026-11-01 00:00 UTC"],
  ];
  for (const [end, text] of vectors) {
    check(resetText(end) === text, `reset for ${end} is ${resetText(end)}, want ${text}`);
  }
  for (const end of ["", "soon", "2026-11-01", "2026-02-30T00:00:00Z", "2026-11-01T24:00:00Z"]) {
    check(resetText(end) === null, `reset for ${JSON.stringify(end)} parsed`);
  }
}

// The client limit vectors: the retry time rounded up to the next whole minute
// in UTC, plain "client limit" without a retry time.
export function checkClientLimitText() {
  const vectors = [
    [1791313500000, "client limit, retry at 19:05 UTC"],
    [1791313440001, "client limit, retry at 19:05 UTC"],
    [0, "client limit"],
  ];
  for (const [retryTime, text] of vectors) {
    check(clientLimitText(retryTime) === text, `client limit for ${retryTime} is ${clientLimitText(retryTime)}, want ${text}`);
  }
}

// The status line vectors.
export function checkStatusLines() {
  const vectors = [
    [{}, "status: connecting | data this month: checking | data total: checking"],
    [{providerStateAdded: 2, cap: capObject({monthly_byte_limit: 5000000000, monthly_used_byte_count: 1234567890})},
      "status: connected | data this month: 1.2 GB of 5.0 GB | data total: no cap"],
    [{providerStateAdded: 2, firstReadingFailed: true}, "status: connected | data this month: unavailable | data total: unavailable"],
    [{providerStateAdded: 2, cap: capObject({monthly_byte_limit: 5000000000, monthly_used_byte_count: 5000000000, capped: true, capped_reason: "monthly"})},
      "status: data cap reached, resets 2026-11-01 00:00 UTC | data this month: 5.0 GB of 5.0 GB | data total: no cap"],
    [{providerStateAdded: 2, cap: capObject({total_byte_limit: 10000000000, total_used_byte_count: 10000000000, capped: true, capped_reason: "total"})},
      "status: data cap reached | data this month: no cap | data total: 10.0 GB of 10.0 GB"],
    [{providerStateAdded: 2, cap: capObject({monthly_byte_limit: 0, monthly_used_byte_count: 0, capped: true, capped_reason: "monthly"})},
      "status: paused | data this month: 0 B of 0 B | data total: no cap"],
    [{clientLimitStatus: "client_limit_exceeded", clientLimitRetryTime: 1791313500000, cap: capObject({monthly_byte_limit: 5000000000, monthly_used_byte_count: 0})},
      "status: client limit, retry at 19:05 UTC | data this month: 0 B of 5.0 GB | data total: no cap"],
  ];
  for (const [input, line] of vectors) {
    const got = consoleLine(input);
    check(got === line, `status line ${got}, want ${line}`);
  }
}

// The status rule vectors: the order of the rules, with the cap that
// capped_reason names deciding "paused".
export function checkStatusRules() {
  const cap = fields => capObject(fields);
  const vectors = [
    [{started: false, signedOut: false}, "stopped"],
    [{started: false, signedOut: true}, "signed out"],
    [{clientLimitStatus: "client_limit_exceeded", clientLimitRetryTime: 1791313500000, cap: cap({monthly_byte_limit: 0, capped: true, capped_reason: "monthly"}), providerStateAdded: 3},
      "client limit, retry at 19:05 UTC"],
    [{cap: cap({monthly_byte_limit: 0, capped: true, capped_reason: "monthly"}), providerStateAdded: 3}, "paused"],
    [{cap: cap({total_byte_limit: 0, monthly_byte_limit: 5000000000, capped: true, capped_reason: "total"}), providerStateAdded: 3}, "paused"],
    [{cap: cap({monthly_byte_limit: 5000000000, capped: true, capped_reason: "monthly"}), providerStateAdded: 3}, "data cap reached, resets 2026-11-01 00:00 UTC"],
    [{cap: cap({total_byte_limit: 10000000000, capped: true, capped_reason: "total"}), providerStateAdded: 0}, "data cap reached"],
    [{cap: cap({}), providerStateAdded: 1}, "connected"],
    [{cap: null, providerStateAdded: 0}, "connecting"],
    // a monthly cap whose end does not parse has no reset time
    [{cap: cap({monthly_byte_limit: 5000000000, monthly_period_end: "soon", capped: true, capped_reason: "monthly"}), providerStateAdded: 3}, "data cap reached"],
    // an unknown reason reads as capped without a reset time, never paused
    [{cap: cap({monthly_byte_limit: 0, capped: true, capped_reason: "weekly"}), providerStateAdded: 3}, "data cap reached"],
  ];
  for (const [input, status] of vectors) {
    const got = statusText({started: true, signedOut: false, ...input});
    check(got === status, `status ${got}, want ${status} for ${JSON.stringify(input)}`);
  }
}

// The data fields: checking, unavailable, a later failure keeping the last
// value, no cap for a null limit even with a used count, and used of limit.
export function checkDataFields() {
  const capReadings = new CapReadings();
  let fields = dataFields(capReadings);
  check(fields.dataThisMonth === "checking" && fields.dataTotal === "checking", "the fields before a reading are not checking");
  capReadings.failed();
  fields = dataFields(capReadings);
  check(fields.dataThisMonth === "unavailable" && fields.dataTotal === "unavailable", "a failed first reading is not unavailable");
  capReadings.succeeded(capObject({monthly_byte_limit: 5000000000, monthly_used_byte_count: 1000, total_byte_limit: null, total_used_byte_count: 999999}));
  capReadings.failed();
  fields = dataFields(capReadings);
  check(fields.dataThisMonth === "1.0 kB of 5.0 GB", `a later failure did not keep the last value: ${fields.dataThisMonth}`);
  check(fields.dataTotal === "no cap", `a null limit with a used count shows ${fields.dataTotal}`);
}

// The cap object: null or absent limits, capped and capped_reason, an unknown
// reason kept; anything else is refused.
export function checkCapObject() {
  const minimal = parseCapObject({client_id: clientA});
  check(minimal.monthlyByteLimit === null && minimal.totalByteLimit === null && minimal.monthlyUsedByteCount === 0 &&
    !minimal.capped && minimal.cappedReason === "", "an absent field did not read as no cap");
  const capped = parseCapObject({client_id: clientA, monthly_byte_limit: 7, monthly_used_byte_count: 8, capped: true, capped_reason: "monthly"});
  check(capped.monthlyByteLimit === 7 && capped.monthlyUsedByteCount === 8 && capped.capped && capped.cappedReason === "monthly", "capped fields differ");
  const unknown = parseCapObject({capped: true, capped_reason: "weekly"});
  check(unknown.capped && unknown.cappedReason === "weekly", "an unknown capped_reason was not kept");
  for (const body of [null, [], "x", {error: {message: "no"}}, {monthly_byte_limit: -1}, {monthly_byte_limit: 1.5},
    {monthly_byte_limit: "10"}, {capped: "yes"}, {total_used_byte_count: -2}]) {
    let refused = false;
    try {
      parseCapObject(body);
    } catch {
      refused = true;
    }
    check(refused, `cap object ${JSON.stringify(body)} was accepted`);
  }
}

// The JWT client_id claim: a client JWT is accepted; a network JWT, a malformed
// token and an invalid UUID are refused.
export function checkClientJwtClaims() {
  check(parseClientJwtClientId(selfTestJwt(`{"client_id":"${clientA.toUpperCase()}"}`)) === clientA, "a client JWT was refused");
  for (const refused of [selfTestJwt(`{"network_id":"${clientA}"}`), "not.a", "a.b.c.d", selfTestJwt(`{"client_id":"not-a-uuid"}`), "e30.!!!.x"]) {
    let threw = false;
    try {
      parseClientJwtClientId(refused);
    } catch (error) {
      threw = error instanceof ConfigurationError;
    }
    check(threw, `${refused} was accepted`);
  }
}

// The companion's /embed-status body.
export function checkEmbedStatus() {
  const status = parseEmbedStatus(JSON.stringify({
    ClientId: clientA.toUpperCase(),
    InstanceId: installationA,
    WindowStatus: {ProviderStateAdded: 3, TargetSize: 4},
    ClientLimitStatus: {Status: "client_limit_exceeded", RetryTime: 1791313500000},
    ContractStatus: {InsufficientBalance: false, NoPermission: false, Premium: false},
    Licenses: [{Name: "example"}],
  }));
  check(status.clientId === clientA && status.providerStateAdded === 3 && status.clientLimitStatus === "client_limit_exceeded" &&
    status.clientLimitRetryTime === 1791313500000 && status.licenses.length === 1, "the embed status differs");
  const starting = parseEmbedStatus(`{"ClientId":"${clientA}","InstanceId":"${installationA}","WindowStatus":null,"ClientLimitStatus":{"Status":"","RetryTime":0},"ContractStatus":null}`);
  check(starting.providerStateAdded === 0 && starting.licenses === null && starting.contractStatusKey === "null", "the starting embed status differs");
  for (const body of ["{}", "null", `{"ClientId":"x","InstanceId":"${installationA}","WindowStatus":null,"ClientLimitStatus":{"Status":"","RetryTime":0},"ContractStatus":null}`,
    `{"ClientId":"${clientA}","InstanceId":"${installationA}","WindowStatus":{},"ClientLimitStatus":{"Status":"","RetryTime":0},"ContractStatus":null}`]) {
    let refused = false;
    try {
      parseEmbedStatus(body);
    } catch {
      refused = true;
    }
    check(refused, `embed status ${body} was accepted`);
  }
}

// A stand-in token server on loopback: answer(request, body) returns
// {status, body}; requests records each request. close stops it.
export async function standInTokenServer(answer) {
  const requests = [];
  const server = createServer((request, response) => {
    const chunks = [];
    request.on("data", chunk => chunks.push(chunk));
    request.on("end", () => {
      const body = Buffer.concat(chunks).toString("utf8");
      requests.push({method: request.method, url: request.url, authorization: request.headers.authorization, contentType: request.headers["content-type"], body});
      const reply = answer(request, body);
      response.statusCode = reply.status;
      response.setHeader("Content-Type", "application/json");
      response.setHeader("Cache-Control", "no-store");
      response.end(reply.body);
    });
  });
  await new Promise((resolve, reject) => {
    server.once("error", reject);
    server.listen(0, "127.0.0.1", resolve);
  });
  const {port} = server.address();
  return {
    url: `http://127.0.0.1:${port}`,
    requests,
    close: () => new Promise(resolve => server.close(resolve)),
  };
}

// The token fetch: the request, client.jwt saved atomically and privately, a
// client_id that does not match the claim refused, and the answers mapped to
// the exit codes.
export async function checkTokenFetch() {
  const clientJwt = selfTestJwt(`{"client_id":"${clientA}"}`);
  const success = JSON.stringify({client_id: clientA, by_client_jwt: clientJwt, data_cap: {client_id: clientA, monthly_byte_limit: 5000000000, monthly_used_byte_count: 1, capped: false, capped_reason: ""}});
  let reply = {status: 200, body: success};
  const tokenServer = await standInTokenServer(() => reply);
  const {stateDir, remove} = await selfTestStateDir();
  try {
    const fetchOnce = () => fetchClientJwt({tokenServerUrl: tokenServer.url, demoSession, instanceId: installationA, stateDir});
    const fetched = await fetchOnce();
    check(fetched.clientId === clientA && fetched.clientJwt === clientJwt && fetched.dataCap?.monthlyByteLimit === 5000000000, "the fetched credential differs");
    const request = tokenServer.requests[0];
    check(request.method === "POST" && request.url === "/urnetwork/client-token" && request.authorization === `Bearer ${demoSession}` &&
      request.contentType === "application/json" && JSON.parse(request.body).installation_id === installationA, "the token request differs");
    const saved = (await readPrivateFile(join(stateDir, clientJwtFileName))).toString("utf8").trim();
    check(saved === clientJwt, "client.jwt was not saved");
    check((await readdir(stateDir)).every(name => !name.startsWith(".")), "a temporary file was left behind");
    if (process.platform !== "win32") {
      check(((await stat(join(stateDir, clientJwtFileName))).mode & 0o077) === 0, "client.jwt is not private");
    }

    // a data_cap of null is no first reading
    reply = {status: 200, body: JSON.stringify({client_id: clientA, by_client_jwt: clientJwt, data_cap: null})};
    check((await fetchOnce()).dataCap === null, "a null data_cap was read");

    const answers = [
      [{status: 200, body: JSON.stringify({client_id: clientB, by_client_jwt: clientJwt, data_cap: null})}, exitFailure, "a client_id that does not match the claim"],
      [{status: 200, body: JSON.stringify({client_id: clientA, by_client_jwt: selfTestJwt(`{"network_id":"${clientA}"}`), data_cap: null})}, exitFailure, "a network JWT"],
      [{status: 200, body: "not json"}, exitFailure, "an answer that is not JSON"],
      [{status: 401, body: '{"error":{"code":"unauthorized","message":"unknown session"}}'}, exitConfig, "401"],
      [{status: 409, body: '{"error":{"code":"installation_limit","message":"too many installations"}}'}, exitConfig, "409 installation_limit"],
      [{status: 409, body: '{"error":{"code":"client_limit","message":"client limit; see https://ur.io/services"}}'}, exitConfig, "409 client_limit"],
      [{status: 502, body: '{"error":{"code":"upstream","message":"api failed"}}'}, exitFailure, "502"],
      [{status: 503, body: '{"error":{"code":"busy","message":"retry"}}'}, exitFailure, "503"],
    ];
    for (const [answer, exitCode, name] of answers) {
      reply = answer;
      const error = await rejection(fetchOnce(), `${name} was accepted`);
      check(error instanceof TokenError && error.exitCode === exitCode, `${name} gave ${error.message} with exit ${error.exitCode}, want ${exitCode}`);
      check(error.signedOut === (exitCode === exitConfig), `${name} maps to the wrong GUI state`);
    }
    // a refused answer leaves the saved credential alone
    check((await readPrivateFile(join(stateDir, clientJwtFileName))).toString("utf8").trim() === clientJwt, "a refused answer replaced client.jwt");
  } finally {
    await tokenServer.close();
    await remove();
  }
  // an unreachable token server
  const {stateDir: otherStateDir, remove: removeOther} = await selfTestStateDir();
  try {
    const closed = await standInTokenServer(() => ({status: 200, body: "{}"}));
    await closed.close();
    const error = await rejection(fetchClientJwt({tokenServerUrl: closed.url, demoSession, instanceId: installationA, stateDir: otherStateDir}), "an unreachable token server was accepted");
    check(error instanceof TokenError && error.exitCode === exitFailure && !error.signedOut, "an unreachable token server maps to the wrong exit code");
  } finally {
    await removeOther();
  }
  // the token server URL rules
  check(tokenServerOrigin("https://tokens.example.com") === "https://tokens.example.com", "an HTTPS origin was refused");
  check(tokenServerOrigin("http://localhost:8790") === "http://localhost:8790", "loopback HTTP was refused");
  for (const url of ["http://tokens.example.com", "https://tokens.example.com/path", "https://user@tokens.example.com", "not a url"]) {
    let refused = false;
    try {
      tokenServerOrigin(url);
    } catch (error) {
      refused = error instanceof ConfigurationError;
    }
    check(refused, `token server URL ${url} was accepted`);
  }
}

// The state directory: private permissions on POSIX, atomic replacement,
// instance-id created once and reused, a symlinked file refused.
export async function checkStateFiles() {
  const {stateDir, remove} = await selfTestStateDir();
  try {
    const instanceId = await loadOrCreateInstanceId(stateDir);
    check(/^[0-9a-f-]{36}$/.test(instanceId), "the new instance id is not a UUID");
    check(await loadOrCreateInstanceId(stateDir) === instanceId, "the instance id changed on the second start");
    await writePrivateFile(join(stateDir, instanceIdFileName), "not a uuid\n");
    let refused = false;
    try {
      await loadOrCreateInstanceId(stateDir);
    } catch (error) {
      refused = error instanceof ConfigurationError;
    }
    check(refused, "an instance-id that is not a UUID was accepted");

    // atomic replacement leaves no temporary file
    const path = join(stateDir, clientJwtFileName);
    await writePrivateFile(path, "first\n");
    await writePrivateFile(path, "second\n");
    check((await readPrivateFile(path)).toString("utf8") === "second\n", "the replacement was not written");
    check((await readdir(stateDir)).every(name => !name.startsWith(".")), "a temporary file was left behind");

    if (process.platform !== "win32") {
      // a file others can read is refused
      await chmod(path, 0o644);
      await rejection(readPrivateFile(path), "a file others can read was accepted");
      await chmod(path, 0o600);
      // a symlinked credential could be redirected to another file
      const target = join(stateDir, "elsewhere.jwt");
      await writePrivateFile(target, selfTestJwt(`{"client_id":"${clientA}"}`));
      await rm(path);
      await symlink(target, path);
      check((await lstat(path)).isSymbolicLink(), "the symlink was not created");
      await rejection(loadClientJwt(stateDir), "a symlinked client.jwt was accepted");
      // a directory others can access is refused
      await chmod(stateDir, 0o755);
      await rejection(checkStateDir(stateDir), "a state directory others can access was accepted");
      await chmod(stateDir, 0o700);
    }
  } finally {
    await remove();
  }
}

// The configuration errors and the usage exit code 78.
export async function checkConfiguration() {
  for (const environment of [{}, {URNETWORK_EMBED_STATE_DIR: join("relative", "state")}]) {
    const error = await rejection(loadEmbedSettings(environment), `state directory ${environment.URNETWORK_EMBED_STATE_DIR} was accepted`);
    check(error instanceof ConfigurationError, "a state directory error is not a configuration error");
  }
  const {stateDir, remove} = await selfTestStateDir();
  try {
    // no token server and no client.jwt
    const settings = await loadEmbedSettings({URNETWORK_EMBED_STATE_DIR: stateDir});
    check(settings.tokenServer === null && settings.apiUrl === "https://api.bringyour.com", "the default settings differ");
    const error = await rejection(obtainInstallation(settings), "no token server and no client.jwt was accepted");
    check(error instanceof ConfigurationError, "a missing client.jwt is not a configuration error");
    // a network JWT is a configuration error
    await writePrivateFile(join(stateDir, clientJwtFileName), `${selfTestJwt(`{"network_id":"${clientA}"}`)}\n`);
    check((await rejection(obtainInstallation(settings), "a network JWT was accepted")) instanceof ConfigurationError, "a network JWT is not a configuration error");
    // a client.jwt from the backend tool's provision
    const clientJwt = selfTestJwt(`{"client_id":"${clientA}"}`);
    await writePrivateFile(join(stateDir, clientJwtFileName), `${clientJwt}\n`);
    const installation = await obtainInstallation(settings);
    check(installation.clientId === clientA && installation.clientJwt === clientJwt && installation.dataCap === null, "the installation differs");
    // only one of the two token server settings, or an invalid API URL
    for (const environment of [
      {URNETWORK_EMBED_STATE_DIR: stateDir, URNETWORK_TOKEN_SERVER_URL: "http://127.0.0.1:8790"},
      {URNETWORK_EMBED_STATE_DIR: stateDir, URNETWORK_DEMO_SESSION: demoSession},
      {URNETWORK_EMBED_STATE_DIR: stateDir, URNETWORK_API_URL: "http://api.example.com"},
    ]) {
      check((await rejection(loadEmbedSettings(environment), `${JSON.stringify(environment)} was accepted`)) instanceof ConfigurationError, "a settings error is not a configuration error");
    }
    check(tokenServerSettings({URNETWORK_TOKEN_SERVER_URL: "http://127.0.0.1:8790", URNETWORK_DEMO_SESSION: demoSession})?.session === demoSession, "the token server settings differ");
  } finally {
    await remove();
  }
  // the commands; main.mjs exits with 78 (exitConfig) for a usage error, which
  // embed.test.mjs checks on the real entry point
  check(parseCommand([]) === "run" && parseCommand(["run"]) === "run" && parseCommand(["--self-test"]) === "self-test" &&
    parseCommand(["--version"]) === "version", "a command was not recognized");
  for (const args of [["--unknown"], ["run", "extra"], ["--self-test", "--version"]]) {
    check(parseCommand(args) === null, `${args.join(" ")} was accepted`);
  }
}

// The companion's environment and exit codes.
export function checkCompanion() {
  const environment = companionEnvironment({PATH: "/bin", URNETWORK_ROOT_JWT: "root", URNETWORK_DEMO_SESSION: demoSession, urnetwork_companion_provide: "public"},
    {stateDir: "/state", token: "t".repeat(64), address: "127.0.0.1:9999"});
  check(environment.PATH === "/bin" && environment.URNETWORK_COMPANION_EMBED === "1" && environment.URNETWORK_EMBED_STATE_DIR === "/state" &&
    environment.URNETWORK_COMPANION_TOKEN === "t".repeat(64) && environment.URNETWORK_COMPANION_ADDRESS === "127.0.0.1:9999" &&
    environment.URNETWORK_COMPANION_STOP_ON_STDIN_CLOSE === "1", "the companion environment differs");
  check(environment.URNETWORK_ROOT_JWT === undefined && environment.URNETWORK_DEMO_SESSION === undefined && environment.urnetwork_companion_provide === undefined,
    "a parent URNETWORK_ setting reached the companion");
  const exitCodes = [
    [{code: 0, signal: null, error: null}, exitStopped],
    [{code: 78, signal: null, error: null}, exitConfig],
    [{code: 1, signal: null, error: null}, exitFailure],
    [{code: null, signal: "SIGKILL", error: null}, exitFailure],
    [{code: null, signal: null, error: new Error("spawn failed")}, exitFailure],
  ];
  for (const [exitInfo, exitCode] of exitCodes) {
    check(companionExitCode(exitInfo) === exitCode, `companion exit ${JSON.stringify(exitInfo)} maps to ${companionExitCode(exitInfo)}`);
  }
}

// Every check, in order.
export const selfTestChecks = [
  checkDataAmounts,
  checkResetText,
  checkClientLimitText,
  checkStatusLines,
  checkStatusRules,
  checkDataFields,
  checkCapObject,
  checkClientJwtClaims,
  checkEmbedStatus,
  checkTokenFetch,
  checkStateFiles,
  checkConfiguration,
  checkCompanion,
];

// Runs every check and rejects with the first failure.
export async function runSelfTest() {
  for (const selfTestCheck of selfTestChecks) {
    await selfTestCheck();
  }
}
