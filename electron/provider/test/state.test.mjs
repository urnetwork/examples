// The installation state rules of PROVIDER_CONTRACT.md ("Installation state",
// "Self-test"): the JWT client_id claim, private permissions on POSIX, atomic
// replacement, instance-id created once and reused, and the configuration
// errors. Each test works in its own temporary directory.

import {test} from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import {
  ProviderConfigError,
  checkStateDir,
  clientJwtFileName,
  ensureStateDir,
  importClientJwt,
  instanceIdFileName,
  loadOrCreateInstanceId,
  loadProviderConfig,
  parseClientJwtClientId,
  parseId,
  providerStateDir,
  readPrivateFile,
  writePrivateFile,
} from "../state.mjs";

const posix = process.platform !== "win32";

// A synthetic, unsigned JWT with the given payload json.
function testJwt(payloadJson) {
  return `e30.${Buffer.from(payloadJson, "utf8").toString("base64url")}.test`;
}

const clientJwt = testJwt('{"client_id":"11111111-1111-1111-1111-111111111111","network_id":"22222222-2222-2222-2222-222222222222"}');
// a network JWT has no client_id claim
const networkJwt = testJwt('{"network_id":"22222222-2222-2222-2222-222222222222"}');

// A new private directory for one test, removed after it.
function tempStateDir(t) {
  const stateDir = fs.mkdtempSync(path.join(os.tmpdir(), "ur-electron-provider-test-"));
  fs.chmodSync(stateDir, 0o700);
  t.after(() => fs.rmSync(stateDir, {recursive: true, force: true}));
  return stateDir;
}

test("only a JWT with a valid client_id claim is a client credential", () => {
  assert.equal(parseClientJwtClientId(clientJwt), "11111111-1111-1111-1111-111111111111");
  // the claim is read in the sdk's canonical form
  assert.equal(parseClientJwtClientId(testJwt('{"client_id":"AAAAAAAABBBBCCCCDDDDEEEEEEEEEEEE"}')), "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
  const invalidJwts = [
    "",
    "not-a-jwt",
    networkJwt,
    testJwt('{"client_id":"not-a-uuid"}'),
    testJwt('{"client_id":11}'),
    testJwt("[]"),
    testJwt("null"),
    "e30.%%%.test",
    "e30..test",
    `e30.${Buffer.from('{"client_id":"11111111-1111-1111-1111-111111111111"}').toString("base64url")}*.test`,
  ];
  for (const invalidJwt of invalidJwts) {
    assert.throws(() => parseClientJwtClientId(invalidJwt), ProviderConfigError, `accepted ${JSON.stringify(invalidJwt)}`);
  }
});

test("ids parse like the sdk's ParseId", () => {
  assert.equal(parseId("11111111-1111-1111-1111-111111111111"), "11111111-1111-1111-1111-111111111111");
  assert.equal(parseId("ABCDEF00112233445566778899AABBCC"), "abcdef00-1122-3344-5566-778899aabbcc");
  for (const text of ["", "1111", "11111111-1111-1111-1111-11111111111g", "11111111-1111-1111-1111-1111111111111"]) {
    assert.throws(() => parseId(text), `accepted ${JSON.stringify(text)}`);
  }
});

test("state files are replaced atomically with owner-only permissions", t => {
  const stateDir = tempStateDir(t);
  const filePath = path.join(stateDir, clientJwtFileName);
  writePrivateFile(filePath, "first\n");
  writePrivateFile(filePath, "second\n");
  assert.equal(readPrivateFile(filePath).toString("utf8"), "second\n");
  // no temporary file is left behind
  assert.deepEqual(fs.readdirSync(stateDir), [clientJwtFileName]);
  if (posix) {
    assert.equal(fs.statSync(filePath).mode & 0o777, 0o600);
  }
});

test("a file or directory that others can access is refused on posix", {skip: !posix}, t => {
  const stateDir = tempStateDir(t);
  const filePath = path.join(stateDir, clientJwtFileName);
  writePrivateFile(filePath, `${clientJwt}\n`);
  fs.chmodSync(filePath, 0o644);
  assert.throws(() => readPrivateFile(filePath), /private to its owner/);
  fs.chmodSync(filePath, 0o600);
  fs.chmodSync(stateDir, 0o755);
  assert.throws(() => checkStateDir(stateDir), ProviderConfigError);
  assert.throws(() => ensureStateDir(stateDir), ProviderConfigError);
  fs.chmodSync(stateDir, 0o700);
  checkStateDir(stateDir);
});

test("a symlinked state file is refused", {skip: !posix}, t => {
  const stateDir = tempStateDir(t);
  const targetPath = path.join(stateDir, "elsewhere");
  writePrivateFile(targetPath, `${clientJwt}\n`);
  fs.symlinkSync(targetPath, path.join(stateDir, clientJwtFileName));
  assert.throws(() => readPrivateFile(path.join(stateDir, clientJwtFileName)), /not a regular file/);
  assert.throws(() => loadProviderConfig(stateDir), ProviderConfigError);
});

test("the state directory is created private inside the user data directory", t => {
  const userDataDir = tempStateDir(t);
  const stateDir = providerStateDir(userDataDir);
  assert.equal(stateDir, path.join(userDataDir, "provider"));
  ensureStateDir(stateDir);
  ensureStateDir(stateDir);
  assert.ok(fs.statSync(stateDir).isDirectory());
  if (posix) {
    assert.equal(fs.statSync(stateDir).mode & 0o777, 0o700);
  }
});

test("instance-id is created once and reused", t => {
  const stateDir = tempStateDir(t);
  const instanceId = loadOrCreateInstanceId(stateDir);
  assert.equal(parseId(instanceId), instanceId);
  assert.equal(loadOrCreateInstanceId(stateDir), instanceId);
  assert.equal(readPrivateFile(path.join(stateDir, instanceIdFileName)).toString("utf8"), `${instanceId}\n`);
  writePrivateFile(path.join(stateDir, instanceIdFileName), "not a uuid\n");
  assert.throws(() => loadOrCreateInstanceId(stateDir), ProviderConfigError);
});

test("a missing or incomplete installation state is refused and a first start loads", t => {
  assert.throws(() => loadProviderConfig(""), ProviderConfigError);
  assert.throws(() => loadProviderConfig("relative/state"), ProviderConfigError);
  const stateDir = tempStateDir(t);
  assert.throws(() => loadProviderConfig(path.join(stateDir, "missing")), ProviderConfigError);
  assert.throws(() => loadProviderConfig(stateDir), /client\.jwt is missing/);
  writePrivateFile(path.join(stateDir, clientJwtFileName), `${networkJwt}\n`);
  assert.throws(() => loadProviderConfig(stateDir), /not a network JWT/);
  writePrivateFile(path.join(stateDir, clientJwtFileName), `  ${clientJwt}\n`);
  const config = loadProviderConfig(stateDir);
  assert.equal(config.stateDir, stateDir);
  assert.equal(config.clientJwt, clientJwt);
  assert.equal(config.clientId, "11111111-1111-1111-1111-111111111111");
  assert.equal(loadOrCreateInstanceId(stateDir), config.instanceId);
});

test("importing a client JWT saves it privately and refuses a network JWT", t => {
  const stateDir = tempStateDir(t);
  assert.throws(() => importClientJwt(stateDir, networkJwt), ProviderConfigError);
  assert.equal(fs.existsSync(path.join(stateDir, clientJwtFileName)), false);
  assert.equal(importClientJwt(stateDir, `\n${clientJwt}\n\n`), "11111111-1111-1111-1111-111111111111");
  assert.equal(readPrivateFile(path.join(stateDir, clientJwtFileName)).toString("utf8"), `${clientJwt}\n`);
  if (posix) {
    assert.equal(fs.statSync(path.join(stateDir, clientJwtFileName)).mode & 0o777, 0o600);
  }
});
