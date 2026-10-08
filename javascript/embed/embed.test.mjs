// Runs each self-test check as a test, so `npm test` and `--self-test` cover
// the same behavior, and tests the exit codes, the companion child process and
// whole runs against a stand-in companion script, a stand-in token server and
// a stand-in cap API on loopback: no SDK device, credentials or network.

import assert from "node:assert/strict";
import {chmod, mkdir, mkdtemp, readFile, rm, writeFile} from "node:fs/promises";
import {createServer} from "node:http";
import {tmpdir} from "node:os";
import {join} from "node:path";
import {test} from "node:test";
import {fileURLToPath} from "node:url";
import {CompanionProcess, companionEnvironment, freeLoopbackAddress, readEmbedStatus} from "./companion.mjs";
import {isEntryPoint, run} from "./main.mjs";
import {selfTestChecks, selfTestJwt} from "./selftest.mjs";
import {companionExitCode} from "./session.mjs";
import {clientJwtFileName, writePrivateFile} from "./state.mjs";

for (const selfTestCheck of selfTestChecks) {
  test(selfTestCheck.name, async () => {
    await selfTestCheck();
  });
}

const clientA = "11111111-1111-1111-1111-111111111111";
const clientJwtA = selfTestJwt(`{"client_id":"${clientA}"}`);
const demoSession = "d".repeat(32);

// the stand-in companion: it serves /embed-status like the companion in embed
// mode (the k-th read answers the k-th body of FAKE_EMBED_STATUSES, then the
// last one), exits 0 when its standard input closes, and with FAKE_EXIT_CODE
// exits with that code after FAKE_EXIT_AFTER status reads
const fakeCompanionSource = `#!/usr/bin/env node
import {createServer} from "node:http";
const [host, port] = process.env.URNETWORK_COMPANION_ADDRESS.split(":");
const token = process.env.URNETWORK_COMPANION_TOKEN;
const statuses = JSON.parse(process.env.FAKE_EMBED_STATUSES);
const exitCode = process.env.FAKE_EXIT_CODE === undefined ? -1 : Number(process.env.FAKE_EXIT_CODE);
const exitAfter = Number(process.env.FAKE_EXIT_AFTER ?? "2");
let statusReadCount = 0;
const server = createServer((request, response) => {
  const url = new URL(request.url, "http://127.0.0.1");
  if (url.pathname !== "/embed-status" || url.searchParams.get("token") !== token ||
      process.env.URNETWORK_COMPANION_EMBED !== "1" || process.env.URNETWORK_COMPANION_STOP_ON_STDIN_CLOSE !== "1" ||
      process.env.URNETWORK_ROOT_JWT !== undefined || process.env.URNETWORK_DEMO_SESSION !== undefined) {
    response.statusCode = 401;
    response.end();
    return;
  }
  const body = statuses[Math.min(statusReadCount, statuses.length - 1)];
  statusReadCount += 1;
  response.setHeader("Content-Type", "application/json");
  response.end(JSON.stringify(body));
  if (0 <= exitCode && statusReadCount === exitAfter) {
    response.on("finish", () => process.exit(exitCode));
  }
});
server.listen(Number(port), host);
process.stdin.on("end", () => process.exit(0));
process.stdin.resume();
`;

// An /embed-status body.
function embedStatus({providerStateAdded = null, clientLimitStatus = "", retryTime = 0, contractStatus = null} = {}) {
  return {
    ClientId: clientA,
    InstanceId: "33333333-3333-3333-3333-333333333333",
    WindowStatus: providerStateAdded === null ? null : {TargetSize: 4, MinSatisfied: true, ProviderStateAdded: providerStateAdded},
    ClientLimitStatus: {Status: clientLimitStatus, RetryTime: retryTime},
    ContractStatus: contractStatus,
  };
}

// A private state directory, optionally with a client.jwt, and the stand-in
// companion beside it; remove deletes both.
async function fakeInstallation({clientJwt = clientJwtA} = {}) {
  const rootDir = await mkdtemp(join(tmpdir(), "ur-embed-test-"));
  const stateDir = join(rootDir, "state");
  await mkdir(stateDir, {mode: 0o700});
  await chmod(stateDir, 0o700);
  if (clientJwt !== null) {
    await writePrivateFile(join(stateDir, clientJwtFileName), `${clientJwt}\n`);
  }
  const companionPath = join(rootDir, "fake-companion.mjs");
  await writeFile(companionPath, fakeCompanionSource);
  await chmod(companionPath, 0o755);
  return {stateDir, companionPath, remove: () => rm(rootDir, {recursive: true, force: true})};
}

// A stand-in backend on loopback: the token server's POST
// /urnetwork/client-token and the API's GET /network/client-data-cap, which
// answer the given replies. Records the requests.
async function standInBackend({tokenReply, capReply}) {
  const requests = [];
  const server = createServer((request, response) => {
    const chunks = [];
    request.on("data", chunk => chunks.push(chunk));
    request.on("end", () => {
      requests.push({method: request.method, url: request.url, authorization: request.headers.authorization, body: Buffer.concat(chunks).toString("utf8")});
      const reply = request.url === "/urnetwork/client-token" ? tokenReply : capReply;
      response.statusCode = reply.status;
      response.setHeader("Content-Type", "application/json");
      response.end(reply.body);
    });
  });
  await new Promise(resolve => server.listen(0, "127.0.0.1", resolve));
  return {
    url: `http://127.0.0.1:${server.address().port}`,
    requests,
    close: () => new Promise(resolve => server.close(resolve)),
  };
}

// Reads the companion's status until it answers; the deadline only bounds a
// failure.
async function waitForEmbedStatus(address, token) {
  const deadline = Date.now() + 20 * 1000;
  while (true) {
    try {
      return await readEmbedStatus(address, token);
    } catch (error) {
      if (deadline <= Date.now()) {
        throw error;
      }
      await new Promise(resolve => setTimeout(resolve, 50));
    }
  }
}

test("the program runs as the entry point without import.meta.main", () => {
  const url = new URL("./main.mjs", import.meta.url).href;
  const path = fileURLToPath(url);
  assert.equal(isEntryPoint({main: true, url}, undefined), true);
  assert.equal(isEntryPoint({main: false, url}, path), false);
  // Node 24.0 and 24.1 have no import.meta.main
  assert.equal(isEntryPoint({url}, path), true);
  assert.equal(isEntryPoint({url}, fileURLToPath(new URL("./status.mjs", import.meta.url))), false);
  assert.equal(isEntryPoint({url}, undefined), false);
});

test("a usage error exits with 78", async () => {
  const errors = [];
  assert.equal(await run(["--unknown"], {log: () => {}, error: message => errors.push(message)}), 78);
  assert.match(errors.join("\n"), /usage/);
});

test("a configuration error exits with 78 before the companion starts", async () => {
  const lines = [];
  const log = message => lines.push(message);
  assert.equal(await run([], {environment: {}, log, error: log}), 78);
  assert.equal(await run(["run"], {environment: {URNETWORK_EMBED_STATE_DIR: join("relative", "state")}, log, error: log}), 78);
  const installation = await fakeInstallation();
  try {
    const missingCompanion = {URNETWORK_EMBED_STATE_DIR: installation.stateDir, URNETWORK_COMPANION_PATH: join(installation.stateDir, "missing-companion")};
    assert.equal(await run([], {environment: missingCompanion, log, error: log}), 78);
    assert.match(lines.join("\n"), /build the native companion first/);
    // one of the two token server settings
    const halfTokenServer = {URNETWORK_EMBED_STATE_DIR: installation.stateDir, URNETWORK_COMPANION_PATH: installation.companionPath, URNETWORK_DEMO_SESSION: demoSession};
    assert.equal(await run([], {environment: halfTokenServer, log, error: log}), 78);
  } finally {
    await installation.remove();
  }
});

test("token server answers map to the exit codes before the companion starts", {skip: process.platform === "win32"}, async () => {
  for (const [tokenReply, exitCode] of [
    [{status: 401, body: '{"error":{"code":"unauthorized","message":"unknown demo session"}}'}, 78],
    [{status: 409, body: '{"error":{"code":"client_limit","message":"your network is at its client limit; see https://ur.io/services"}}'}, 78],
    [{status: 502, body: '{"error":{"code":"upstream","message":"the URnetwork API failed"}}'}, 1],
  ]) {
    const installation = await fakeInstallation({clientJwt: null});
    const backend = await standInBackend({tokenReply, capReply: {status: 404, body: "{}"}});
    try {
      const lines = [];
      const log = message => lines.push(message);
      const environment = {
        URNETWORK_EMBED_STATE_DIR: installation.stateDir,
        URNETWORK_COMPANION_PATH: installation.companionPath,
        URNETWORK_TOKEN_SERVER_URL: backend.url,
        URNETWORK_DEMO_SESSION: demoSession,
      };
      assert.equal(await run([], {environment, log, error: log}), exitCode);
      const tokenError = JSON.parse(tokenReply.body).error.message;
      assert.match(lines.join("\n"), new RegExp(tokenError.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")));
      // the demo session is never printed
      assert.doesNotMatch(lines.join("\n"), new RegExp(demoSession));
    } finally {
      await backend.close();
      await installation.remove();
    }
  }
});

test("the companion stops when its standard input closes", {skip: process.platform === "win32"}, async () => {
  const installation = await fakeInstallation();
  try {
    const token = "t".repeat(64);
    const address = await freeLoopbackAddress();
    const companion = new CompanionProcess(installation.companionPath, companionEnvironment(
      {...process.env, URNETWORK_ROOT_JWT: "never forwarded", FAKE_EMBED_STATUSES: JSON.stringify([embedStatus({clientLimitStatus: "client_limit_exceeded", retryTime: 1791313500000})])},
      {stateDir: installation.stateDir, token, address},
    ));
    const status = await waitForEmbedStatus(address, token);
    assert.equal(status.clientLimitStatus, "client_limit_exceeded");
    assert.equal(status.clientLimitRetryTime, 1791313500000);
    await assert.rejects(readEmbedStatus(address, "u".repeat(64)), /401/);
    assert.equal(companion.exitInfo, null);
    const exitInfo = await companion.stop();
    assert.equal(exitInfo.code, 0);
    assert.equal(companionExitCode(exitInfo), 0);
  } finally {
    await installation.remove();
  }
});

test("a run with the token server fetches the client JWT, shows the caps and exits with the companion's 78", {skip: process.platform === "win32"}, async () => {
  const installation = await fakeInstallation({clientJwt: null});
  const dataCap = {client_id: clientA, monthly_byte_limit: 5000000000, monthly_used_byte_count: 1234567890, total_byte_limit: null, total_used_byte_count: 1234567890, capped: false, capped_reason: ""};
  const backend = await standInBackend({
    tokenReply: {status: 200, body: JSON.stringify({client_id: clientA, by_client_jwt: clientJwtA, data_cap: dataCap})},
    capReply: {status: 200, body: JSON.stringify(dataCap)},
  });
  try {
    const lines = [];
    const log = message => lines.push(message);
    const environment = {
      ...process.env,
      URNETWORK_EMBED_STATE_DIR: installation.stateDir,
      URNETWORK_COMPANION_PATH: installation.companionPath,
      URNETWORK_TOKEN_SERVER_URL: backend.url,
      URNETWORK_DEMO_SESSION: demoSession,
      URNETWORK_API_URL: backend.url,
      FAKE_EMBED_STATUSES: JSON.stringify([embedStatus({providerStateAdded: 2})]),
      FAKE_EXIT_CODE: "78",
      FAKE_EXIT_AFTER: "2",
    };
    const code = await run([], {environment, log, error: log});
    const output = lines.join("\n");
    assert.equal(code, 78);
    assert.match(output, new RegExp(`^embed client ${clientA}, installation [0-9a-f-]{36}$`, "m"));
    assert.match(output, /^status: connected \| data this month: 1\.2 GB of 5\.0 GB \| data total: no cap$/m);
    assert.match(output, /the native companion exited \(78\)/);
    // the token request carried the demo session and the installation's instance-id
    const instanceId = (await readFile(join(installation.stateDir, "instance-id"), "utf8")).trim();
    const tokenRequest = backend.requests.find(request => request.url === "/urnetwork/client-token");
    assert.equal(tokenRequest.authorization, `Bearer ${demoSession}`);
    assert.deepEqual(JSON.parse(tokenRequest.body), {installation_id: instanceId});
    assert.equal((await readFile(join(installation.stateDir, clientJwtFileName), "utf8")).trim(), clientJwtA);
    // the token server's data_cap is the first reading: no cap read at start
    assert.equal(backend.requests.filter(request => request.url === "/network/client-data-cap").length, 0);
    assert.doesNotMatch(output, new RegExp(demoSession));
  } finally {
    await backend.close();
    await installation.remove();
  }
});

test("a run without a token server reads the caps with the client JWT, again on a contract status change", {skip: process.platform === "win32"}, async () => {
  const installation = await fakeInstallation();
  const backend = await standInBackend({
    tokenReply: {status: 500, body: "{}"},
    capReply: {status: 200, body: JSON.stringify({client_id: clientA, monthly_byte_limit: 0, monthly_used_byte_count: 0, capped: true, capped_reason: "monthly"})},
  });
  try {
    const lines = [];
    const log = message => lines.push(message);
    const environment = {
      ...process.env,
      URNETWORK_EMBED_STATE_DIR: installation.stateDir,
      URNETWORK_COMPANION_PATH: installation.companionPath,
      URNETWORK_API_URL: backend.url,
      FAKE_EMBED_STATUSES: JSON.stringify([
        embedStatus({providerStateAdded: 0, clientLimitStatus: "client_limit_exceeded", retryTime: 1791313500000}),
        embedStatus({providerStateAdded: 3}),
        embedStatus({providerStateAdded: 3, contractStatus: {InsufficientBalance: false, NoPermission: true, Premium: false}}),
      ]),
      FAKE_EXIT_CODE: "1",
      FAKE_EXIT_AFTER: "4",
    };
    const code = await run([], {environment, log, error: log});
    const output = lines.join("\n");
    // a companion that fails exits the run with 1
    assert.equal(code, 1);
    assert.match(output, /^status: client limit, retry at 19:05 UTC \| data this month: (checking|0 B of 0 B) \| data total: (checking|no cap)$/m);
    assert.match(output, /^status: paused \| data this month: 0 B of 0 B \| data total: no cap$/m);
    assert.match(output, /the native companion exited \(1\)/);
    const capRequests = backend.requests.filter(request => request.url === "/network/client-data-cap");
    // read at start, and again when the contract status changed
    assert.ok(2 <= capRequests.length, `${capRequests.length} cap reads`);
    assert.ok(capRequests.every(request => request.authorization === `Bearer ${clientJwtA}`));
    assert.equal(backend.requests.filter(request => request.url === "/urnetwork/client-token").length, 0);
  } finally {
    await backend.close();
    await installation.remove();
  }
});
