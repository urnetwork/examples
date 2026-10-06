// Runs each self-test check as a test, so `npm test` and `--self-test` cover
// the same behavior, and tests the exit codes and the companion child process
// with a stand-in companion script: no SDK device, credentials or network.

import assert from "node:assert/strict";
import {chmod, mkdir, mkdtemp, rm, writeFile} from "node:fs/promises";
import {type AddressInfo} from "node:net";
import {createServer} from "node:http";
import {tmpdir} from "node:os";
import {join} from "node:path";
import {test} from "node:test";
import {fileURLToPath} from "node:url";
import {CompanionProcess, companionEnvironment, freeLoopbackAddress, readCompanionStatus} from "./companion.ts";
import {isEntryPoint, run} from "./main.ts";
import {selfTestChecks, selfTestJwt} from "./selftest.ts";
import {companionExitCode} from "./session.ts";
import {clientJwtFileName, writePrivateFile} from "./state.ts";
import {type CompanionStatus} from "./status.ts";

for (const selfTestCheck of selfTestChecks) {
  test(selfTestCheck.name, async () => {
    await selfTestCheck();
  });
}

// the stand-in companion: it serves /provider-status like the companion in
// provider mode, exits 0 when its standard input closes, and with
// FAKE_EXIT_CODE exits with that code after its second status read
const fakeCompanionSource = `#!/usr/bin/env node
import {createServer} from "node:http";
const [host, port] = process.env.URNETWORK_COMPANION_ADDRESS.split(":");
const token = process.env.URNETWORK_COMPANION_TOKEN;
const exitCode = process.env.FAKE_EXIT_CODE === undefined ? -1 : Number(process.env.FAKE_EXIT_CODE);
let statusReadCount = 0;
const server = createServer((request, response) => {
  const url = new URL(request.url, "http://127.0.0.1");
  if (url.pathname !== "/provider-status" || url.searchParams.get("token") !== token || process.env.URNETWORK_COMPANION_STOP_ON_STDIN_CLOSE !== "1") {
    response.statusCode = 401;
    response.end();
    return;
  }
  statusReadCount += 1;
  response.setHeader("Content-Type", "application/json");
  response.end(process.env.FAKE_PROVIDER_STATUS);
  if (0 <= exitCode && statusReadCount === 2) {
    response.on("finish", () => process.exit(exitCode));
  }
});
server.listen(Number(port), host);
process.stdin.on("end", () => process.exit(0));
process.stdin.resume();
`;

const clientLimitStatusBody = '{"ProvideMode":3,"ProviderConnected":false,"ClientLimitStatus":{"Status":"client_limit_exceeded","RetryTime":1791313500000},"DeviceRpcStarted":false}';

// A private state directory with a synthetic client.jwt, and the stand-in
// companion beside it; remove deletes both.
async function fakeInstallation(): Promise<{stateDir: string; companionPath: string; remove: () => Promise<void>}> {
  const rootDir = await mkdtemp(join(tmpdir(), "ur-provider-test-"));
  const stateDir = join(rootDir, "state");
  await mkdir(stateDir, {mode: 0o700});
  await chmod(stateDir, 0o700);
  await writePrivateFile(join(stateDir, clientJwtFileName), `${selfTestJwt('{"client_id":"11111111-1111-1111-1111-111111111111"}')}\n`);
  const companionPath = join(rootDir, "fake-companion.mjs");
  await writeFile(companionPath, fakeCompanionSource);
  await chmod(companionPath, 0o755);
  return {stateDir, companionPath, remove: () => rm(rootDir, {recursive: true, force: true})};
}

// Reads the companion's status until it answers; the deadline only bounds a
// failure.
async function waitForCompanionStatus(address: string, token: string): Promise<CompanionStatus> {
  const deadline = Date.now() + 20 * 1000;
  while (true) {
    try {
      return await readCompanionStatus(address, token);
    } catch (error) {
      if (deadline <= Date.now()) {
        throw error;
      }
      await new Promise(resolve => setTimeout(resolve, 50));
    }
  }
}

test("the program runs as the entry point without import.meta.main", () => {
  const url = new URL("./main.ts", import.meta.url).href;
  const path = fileURLToPath(url);
  assert.equal(isEntryPoint({main: true, url}, undefined), true);
  assert.equal(isEntryPoint({main: false, url}, path), false);
  // Node 24.0 and 24.1 have no import.meta.main
  assert.equal(isEntryPoint({url}, path), true);
  assert.equal(isEntryPoint({url}, fileURLToPath(new URL("./status.ts", import.meta.url))), false);
  assert.equal(isEntryPoint({url}, undefined), false);
});

test("a usage error exits with 78", async () => {
  const errors: string[] = [];
  assert.equal(await run(["--unknown"], {log: () => {}, error: message => errors.push(message)}), 78);
  assert.match(errors.join("\n"), /usage/);
});

test("a configuration error exits with 78 before the companion starts", async () => {
  const lines: string[] = [];
  const log = (message: string) => {
    lines.push(message);
  };
  assert.equal(await run([], {environment: {}, log, error: log}), 78);
  assert.equal(await run(["run"], {environment: {URNETWORK_PROVIDER_STATE_DIR: join("relative", "state")}, log, error: log}), 78);
  const installation = await fakeInstallation();
  try {
    const environment = {URNETWORK_PROVIDER_STATE_DIR: installation.stateDir, URNETWORK_COMPANION_PATH: join(installation.stateDir, "missing-companion")};
    assert.equal(await run([], {environment, log, error: log}), 78);
    assert.match(lines.join("\n"), /build the native companion first/);
  } finally {
    await installation.remove();
  }
});

test("the companion stops when its standard input closes", {skip: process.platform === "win32"}, async () => {
  const installation = await fakeInstallation();
  try {
    const token = "t".repeat(64);
    const address = await freeLoopbackAddress();
    const companion = new CompanionProcess(installation.companionPath, companionEnvironment(
      {...process.env, FAKE_PROVIDER_STATUS: clientLimitStatusBody},
      {stateDir: installation.stateDir, token, address},
    ));
    const companionStatus = await waitForCompanionStatus(address, token);
    assert.equal(companionStatus.clientLimitStatus, "client_limit_exceeded");
    assert.equal(companionStatus.clientLimitRetryTime, 1791313500000);
    await assert.rejects(readCompanionStatus(address, "u".repeat(64)), /401/);
    assert.equal(companion.exitInfo, null);
    const exitInfo = await companion.stop();
    assert.equal(exitInfo.code, 0);
    assert.equal(companionExitCode(exitInfo), 0);
  } finally {
    await installation.remove();
  }
});

test("a credential problem reported by the companion exits the run with 78", {skip: process.platform === "win32"}, async () => {
  const installation = await fakeInstallation();
  // a local stand-in for GET /sn/wallet, so the run never reaches the network
  const walletAuthorizations: (string | undefined)[] = [];
  const walletServer = createServer((request, response) => {
    walletAuthorizations.push(request.headers.authorization);
    response.setHeader("Content-Type", "application/json");
    response.end('{"wallet":{"coldkey_ss58":"5GrwvaEF5zXb26Fz9rcQpDWS57CtERHpNehXCPcNoHGKutQY","set_at_millis":1,"consent_scope":"network"},"wallets":[]}');
  });
  await new Promise<void>(resolve => walletServer.listen(0, "127.0.0.1", resolve));
  try {
    const lines: string[] = [];
    const log = (message: string) => {
      lines.push(message);
    };
    const environment = {
      ...process.env,
      URNETWORK_PROVIDER_STATE_DIR: installation.stateDir,
      URNETWORK_COMPANION_PATH: installation.companionPath,
      FAKE_PROVIDER_STATUS: clientLimitStatusBody,
      FAKE_EXIT_CODE: "78",
    };
    const apiUrl = `http://127.0.0.1:${(walletServer.address() as AddressInfo).port}`;
    assert.equal(await run([], {environment, log, error: log, apiUrl}), 78);
    const output = lines.join("\n");
    assert.match(output, /^Consent disclaimer: /);
    assert.match(output, /^provider client 11111111-1111-1111-1111-111111111111, instance [0-9a-f-]{36}$/m);
    assert.match(output, /^status: client limit, retry at 19:05 UTC \| clients served: 0 \| data provided: 0 B \| payout wallet: /m);
    assert.match(output, /the native companion exited \(78\)/);
    assert.match(output, /^status: stopped$/m);
    assert.deepEqual(walletAuthorizations, [`Bearer ${selfTestJwt('{"client_id":"11111111-1111-1111-1111-111111111111"}')}`]);
  } finally {
    await new Promise(resolve => walletServer.close(resolve));
    await installation.remove();
  }
});
