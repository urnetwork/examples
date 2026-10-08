// The installation state: the private directory, atomic private files, the
// instance ID, the client JWT and the token server settings.

import {test} from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import {
  ConfigurationError,
  clientJwtFileName,
  embedStateDir,
  ensureStateDir,
  loadClientJwt,
  loadOrCreateInstanceId,
  loadTokenServerConfig,
  parseClientJwtClientId,
  readPrivateFile,
  saveClientJwt,
  saveTokenServerConfig,
  tokenServerFileName,
  writePrivateFile,
} from "../state.mjs";

const clientId = "11111111-1111-1111-1111-111111111111";
const clientJwt = `e30.${Buffer.from(`{"client_id":"${clientId}"}`).toString("base64url")}.test`;
const posix = process.platform !== "win32";

// A new user data directory with its embed state directory.
function userData(t) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "ur-electron-embed-state-"));
  t.after(() => fs.rmSync(dir, {recursive: true, force: true}));
  return {dir, stateDir: embedStateDir(dir)};
}

test("the state directory is created private and a shared one is refused", {skip: !posix}, t => {
  const {dir, stateDir} = userData(t);
  assert.equal(stateDir, path.join(dir, "embed"));
  ensureStateDir(stateDir);
  assert.equal(fs.statSync(stateDir).mode & 0o777, 0o700);
  fs.chmodSync(stateDir, 0o755);
  assert.throws(() => ensureStateDir(stateDir), ConfigurationError);
  assert.throws(() => ensureStateDir("relative/embed"), ConfigurationError);
});

test("files are replaced atomically and privately; a shared or symlinked file is refused", {skip: !posix}, t => {
  const {stateDir} = userData(t);
  ensureStateDir(stateDir);
  const file = path.join(stateDir, clientJwtFileName);
  writePrivateFile(file, "first\n");
  writePrivateFile(file, "second\n");
  assert.equal(readPrivateFile(file).toString("utf8"), "second\n");
  assert.equal(fs.statSync(file).mode & 0o777, 0o600);
  assert.deepEqual(fs.readdirSync(stateDir).filter(name => name.startsWith(".")), []);
  fs.chmodSync(file, 0o644);
  assert.throws(() => readPrivateFile(file));
  fs.chmodSync(file, 0o600);
  const target = path.join(stateDir, "elsewhere");
  writePrivateFile(target, clientJwt);
  fs.rmSync(file);
  fs.symlinkSync(target, file);
  assert.throws(() => loadClientJwt(stateDir), ConfigurationError);
});

test("the instance ID is created once and kept", t => {
  const {stateDir} = userData(t);
  ensureStateDir(stateDir);
  const instanceId = loadOrCreateInstanceId(stateDir);
  assert.match(instanceId, /^[0-9a-f-]{36}$/);
  assert.equal(loadOrCreateInstanceId(stateDir), instanceId);
});

test("the client JWT needs a client_id claim", t => {
  const {stateDir} = userData(t);
  ensureStateDir(stateDir);
  saveClientJwt(stateDir, clientJwt);
  assert.deepEqual(loadClientJwt(stateDir), {clientJwt, clientId});
  const networkJwt = `e30.${Buffer.from(`{"network_id":"${clientId}"}`).toString("base64url")}.test`;
  for (const refused of [networkJwt, "not.a", `e30.${Buffer.from('{"client_id":"x"}').toString("base64url")}.test`]) {
    assert.throws(() => parseClientJwtClientId(refused), ConfigurationError, refused);
  }
});

test("the token server settings round trip, and an invalid file is refused", t => {
  const {stateDir} = userData(t);
  ensureStateDir(stateDir);
  assert.equal(loadTokenServerConfig(stateDir), null);
  saveTokenServerConfig(stateDir, {url: "https://tokens.example.com", session: "d".repeat(32)});
  assert.deepEqual(loadTokenServerConfig(stateDir), {url: "https://tokens.example.com", session: "d".repeat(32)});
  writePrivateFile(path.join(stateDir, tokenServerFileName), '{"url":""}');
  assert.throws(() => loadTokenServerConfig(stateDir), ConfigurationError);
});
