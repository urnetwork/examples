// The companion module: where the binary is, its environment, its listening
// line and route url, the exit actions, and a real child process (the
// stand-in companion run by Node) that listens, answers /embed-status with and
// without the licenses, and stops when its standard input closes.

import {test} from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import {fileURLToPath} from "node:url";
import {
  CompanionProcess,
  companionEnvironment,
  companionExitAction,
  companionPath,
  companionRouteUrl,
  fetchEmbedStatus,
  newCompanionToken,
  parseCompanionRoutes,
} from "../companion.mjs";
import {parseEmbedStatus} from "../status.mjs";

const fakeCompanion = fileURLToPath(new URL("./fake-companion.mjs", import.meta.url));

test("the companion binary is found from the setting, the packaged resources or bin/", () => {
  const base = {env: {}, isPackaged: false, resourcesPath: "/resources", appPath: "/app", platform: "darwin", arch: "arm64"};
  assert.equal(companionPath(base), path.join("/app", "bin", "darwin-arm64", "ur-companion"));
  assert.equal(companionPath({...base, platform: "win32", arch: "x64"}), path.join("/app", "bin", "win32-x64", "ur-companion.exe"));
  assert.equal(companionPath({...base, isPackaged: true}), path.join("/resources", "ur-companion"));
  assert.equal(companionPath({...base, env: {URNETWORK_COMPANION_PATH: "/opt/ur-companion"}}), "/opt/ur-companion");
  assert.throws(() => companionPath({...base, env: {URNETWORK_COMPANION_PATH: "relative/ur-companion"}}));
});

test("the environment selects embed mode and passes no parent URNETWORK_ setting", () => {
  const token = newCompanionToken();
  assert.match(token, /^[0-9a-f]{64}$/);
  const env = companionEnvironment({PATH: "/usr/bin", URNETWORK_ROOT_JWT: "root", urnetwork_companion_provide: "public"}, {stateDir: "/state/embed", token});
  assert.deepEqual(env, {
    PATH: "/usr/bin",
    URNETWORK_COMPANION_EMBED: "1",
    URNETWORK_EMBED_STATE_DIR: "/state/embed",
    URNETWORK_COMPANION_TOKEN: token,
    URNETWORK_COMPANION_ADDRESS: "127.0.0.1:0",
    URNETWORK_COMPANION_STOP_ON_STDIN_CLOSE: "1",
  });
});

test("the listening line names the status route; the route url carries the token", () => {
  assert.deepEqual(parseCompanionRoutes("companion listening at http://127.0.0.1:5000/embed-status and ws://127.0.0.1:5000/device-rpc"),
    {statusUrl: "http://127.0.0.1:5000/embed-status"});
  for (const line of [
    "companion listening at http://127.0.0.1:5000/embed-status and ws://127.0.0.1:5001/device-rpc",
    "companion listening at http://10.0.0.1:5000/embed-status and ws://10.0.0.1:5000/device-rpc",
    "companion listening at http://127.0.0.1:5000/provider-status and ws://127.0.0.1:5000/device-rpc",
    "embed client 11111111-1111-1111-1111-111111111111, instance 22222222-2222-2222-2222-222222222222",
  ]) {
    assert.equal(parseCompanionRoutes(line), null, line);
  }
  const token = "t".repeat(64);
  assert.equal(companionRouteUrl("http://127.0.0.1:5000/embed-status", token), `http://127.0.0.1:5000/embed-status?licenses=0&token=${token}`);
  assert.equal(companionRouteUrl("http://127.0.0.1:5000/embed-status", token, {licenses: true}), `http://127.0.0.1:5000/embed-status?token=${token}`);
  assert.throws(() => companionRouteUrl("http://example.com:5000/embed-status", token));
  assert.throws(() => companionRouteUrl("http://127.0.0.1:5000/embed-status", "short"));
});

test("a companion exit is stopped, signed out with 78, or a failure", () => {
  assert.equal(companionExitAction({code: 0, spawnFailed: false, stopRequested: false}), "stopped");
  assert.equal(companionExitAction({code: 1, spawnFailed: false, stopRequested: true}), "stopped");
  assert.equal(companionExitAction({code: 78, spawnFailed: false, stopRequested: false}), "signed out");
  assert.equal(companionExitAction({code: 1, spawnFailed: false, stopRequested: false}), "failed");
  assert.equal(companionExitAction({code: null, spawnFailed: true, stopRequested: false}), "failed");
});

// A temporary state directory for a companion process.
function stateDir(t) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "ur-electron-embed-companion-"));
  t.after(() => fs.rmSync(dir, {recursive: true, force: true}));
  return dir;
}

test("a companion process listens, answers the status with and without the licenses, and stops on a closed input", async t => {
  const token = newCompanionToken();
  const companion = new CompanionProcess({
    command: process.execPath,
    args: [fakeCompanion],
    env: companionEnvironment(process.env, {stateDir: stateDir(t), token}),
  });
  const routes = await companion.routes;
  const status = parseEmbedStatus(await fetchEmbedStatus(routes.statusUrl, token));
  assert.equal(status.providerStateAdded, 2);
  assert.equal(status.licenses, null);
  const withLicenses = parseEmbedStatus(await fetchEmbedStatus(routes.statusUrl, token, {licenses: true}));
  assert.equal(withLicenses.licenses[0].Spdx, "MPL-2.0");
  await assert.rejects(fetchEmbedStatus(routes.statusUrl, "u".repeat(64)), /401/);
  const exit = await companion.stop();
  assert.equal(exit.code, 0);
  assert.equal(companionExitAction({code: exit.code, spawnFailed: exit.spawnFailed, stopRequested: companion.stopRequested}), "stopped");
});

test("an exit with 78 keeps the companion's own last error, not a later SDK log line", async t => {
  const companion = new CompanionProcess({
    command: process.execPath,
    args: [fakeCompanion],
    env: {...companionEnvironment(process.env, {stateDir: stateDir(t), token: newCompanionToken()}), FAKE_COMPANION_EXIT: "78"},
  });
  const exit = await companion.exited;
  assert.equal(exit.code, 78);
  await assert.rejects(companion.routes);
  assert.equal(companion.lastErrorLine, "the server rejected the client credential; obtain a new scoped client JWT from your backend");
});

test("a binary that cannot run is a spawn failure", async t => {
  const companion = new CompanionProcess({
    command: path.join(stateDir(t), "missing-companion"),
    env: companionEnvironment(process.env, {stateDir: "/state", token: newCompanionToken()}),
  });
  const exit = await companion.exited;
  assert.equal(exit.spawnFailed, true);
  assert.match(companion.lastErrorLine, /could not run the companion/);
});
