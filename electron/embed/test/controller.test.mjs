// The embed controller with a fake companion, a fake token fetch, fake cap
// reads and timers that run only when the test fires them: the token server
// settings, the token server's answers, the companion's environment, the
// status from /embed-status, the cap reads, the licenses, stop and the
// companion's exits. The state directory is a real temporary directory.

import {test} from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import {EmbedController, capSyncIntervalMillis, statusPollIntervalMillis} from "../controller.mjs";
import {clientJwtFileName, loadTokenServerConfig, tokenServerFileName, writePrivateFile} from "../state.mjs";
import {parseCapObject} from "../status.mjs";
import {TokenError} from "../token.mjs";

const clientId = "11111111-1111-1111-1111-111111111111";
const clientJwt = `e30.${Buffer.from(`{"client_id":"${clientId}"}`).toString("base64url")}.test`;
const routes = {statusUrl: "http://127.0.0.1:54321/embed-status"};
const demoSession = "d".repeat(32);

// An /embed-status body in the companion's shape.
function embedStatusBody({providerStateAdded = 2, clientLimitStatus = "", retryTime = 0, contractStatus = null, licenses} = {}) {
  const body = {
    ClientId: clientId,
    InstanceId: "22222222-2222-2222-2222-222222222222",
    WindowStatus: {TargetSize: 4, MinSatisfied: true, ProviderStateAdded: providerStateAdded},
    ClientLimitStatus: {Status: clientLimitStatus, RetryTime: retryTime},
    ContractStatus: contractStatus,
  };
  if (licenses !== undefined) {
    body.Licenses = licenses;
  }
  return JSON.stringify(body);
}

// Timers that run only when the test fires them, by delay.
function fakeTimers() {
  const pending = new Map();
  let nextId = 1;
  const add = (callback, millis) => {
    const id = nextId;
    nextId += 1;
    pending.set(id, {callback, millis});
    return id;
  };
  return {
    pending,
    setTimeout: add,
    clearTimeout: id => pending.delete(id),
    setInterval: add,
    clearInterval: id => pending.delete(id),
    has: millis => [...pending.values()].some(timer => timer.millis === millis),
    // Runs the pending timers with this delay once.
    fire: async millis => {
      for (const [id, timer] of [...pending.entries()]) {
        if (timer.millis === millis) {
          if (millis !== capSyncIntervalMillis) {
            pending.delete(id);
          }
          await timer.callback();
        }
      }
    },
  };
}

// A companion double that the test makes listen and exit.
function fakeCompanion() {
  let resolveRoutes;
  let resolveExited;
  return {
    routes: new Promise(resolve => {
      resolveRoutes = resolve;
    }),
    exited: new Promise(resolve => {
      resolveExited = resolve;
    }),
    lastErrorLine: "",
    stopCalls: 0,
    stop() {
      this.stopCalls += 1;
      return this.exited;
    },
    listen: () => resolveRoutes(routes),
    exit: exit => resolveExited({signal: null, spawnFailed: false, ...exit}),
  };
}

// Lets promise callbacks run.
async function settle() {
  for (let i = 0; i < 8; i += 1) {
    await new Promise(resolve => setImmediate(resolve));
  }
}

// A controller on a new state directory, optionally with a saved token server,
// and its fakes.
function setup(t, {tokenServer = true, tokenAnswer = null, dataCap = null} = {}) {
  const userDataDir = fs.mkdtempSync(path.join(os.tmpdir(), "ur-electron-embed-controller-"));
  t.after(() => fs.rmSync(userDataDir, {recursive: true, force: true}));
  const stateDir = path.join(userDataDir, "embed");
  fs.mkdirSync(stateDir, {mode: 0o700});
  if (tokenServer) {
    writePrivateFile(path.join(stateDir, tokenServerFileName), JSON.stringify({url: "http://127.0.0.1:8790", session: demoSession}));
  }
  const fixture = {
    stateDir,
    timers: fakeTimers(),
    companions: [],
    tokenRequests: [],
    capReads: [],
    statusReads: [],
    embedStatus: embedStatusBody(),
    capAnswer: {client_id: clientId, monthly_byte_limit: 5000000000, monthly_used_byte_count: 1234567890, capped: false, capped_reason: ""},
    payloads: [],
  };
  fixture.controller = new EmbedController({
    stateDir,
    timers: fixture.timers,
    parentEnv: {PATH: "/usr/bin", URNETWORK_ROOT_JWT: "never forwarded"},
    startCompanion: ({env}) => {
      const companion = fakeCompanion();
      companion.env = env;
      fixture.companions.push(companion);
      return companion;
    },
    fetchToken: async options => {
      fixture.tokenRequests.push(options);
      if (tokenAnswer instanceof Error) {
        throw tokenAnswer;
      }
      writePrivateFile(path.join(stateDir, clientJwtFileName), `${clientJwt}\n`);
      return {clientJwt, clientId, dataCap: dataCap === null ? null : parseCapObject(dataCap)};
    },
    readCaps: async ({apiUrl, clientJwt: jwt}) => {
      fixture.capReads.push({apiUrl, jwt});
      if (fixture.capAnswer instanceof Error) {
        throw fixture.capAnswer;
      }
      return parseCapObject(fixture.capAnswer);
    },
    fetchEmbedStatus: async (statusUrl, token, {licenses}) => {
      fixture.statusReads.push({statusUrl, token, licenses});
      return licenses ? embedStatusBody({licenses: [{Name: "github.com/urnetwork/sdk", Version: "v2026", Spdx: "MPL-2.0", Text: "license text"}]}) : fixture.embedStatus;
    },
    onChange: payload => fixture.payloads.push(payload),
  });
  fixture.controller.begin();
  return fixture;
}

test("Start needs a saved token server; Save checks the URL and never returns the session", async t => {
  const {controller, stateDir} = setup(t, {tokenServer: false});
  let payload = await controller.start();
  assert.equal(payload.fields.status, "stopped");
  assert.equal(payload.message, "save the token server URL and demo session first");
  assert.equal(payload.sessionSaved, false);

  payload = controller.saveTokenServer("http://tokens.example.com", demoSession);
  assert.match(payload.message, /HTTPS origin/);
  payload = controller.saveTokenServer("http://127.0.0.1:8790", "");
  assert.match(payload.message, /enter the demo session/);
  payload = controller.saveTokenServer("https://tokens.example.com/", demoSession);
  assert.equal(payload.message, "saved the token server");
  assert.equal(payload.tokenServerUrl, "https://tokens.example.com");
  assert.equal(payload.sessionSaved, true);
  assert.ok(!JSON.stringify(payload).includes(demoSession), "the payload carries the session");
  if (process.platform !== "win32") {
    assert.equal(fs.statSync(path.join(stateDir, tokenServerFileName)).mode & 0o077, 0);
  }
  // an empty session keeps the saved one
  payload = controller.saveTokenServer("http://localhost:8790", "");
  assert.deepEqual(loadTokenServerConfig(stateDir), {url: "http://localhost:8790", session: demoSession});
});

test("a 401 or 409 from the token server shows signed out; any other failure stopped", async t => {
  const signedOut = setup(t, {tokenAnswer: new TokenError("unknown demo session", 78)});
  let payload = await signedOut.controller.start();
  assert.equal(payload.fields.status, "signed out");
  assert.equal(payload.message, "unknown demo session");
  assert.equal(payload.running, false);
  assert.equal(signedOut.companions.length, 0);

  const failed = setup(t, {tokenAnswer: new TokenError("the token server answered HTTP 502: the URnetwork API failed", 1)});
  payload = await failed.controller.start();
  assert.equal(payload.fields.status, "stopped");
  assert.match(payload.message, /HTTP 502/);
});

test("Start fetches the client JWT, starts the companion in embed mode and shows the status, caps and licenses", async t => {
  const fixture = setup(t, {dataCap: {client_id: clientId, monthly_byte_limit: 5000000000, monthly_used_byte_count: 1234567890, capped: false, capped_reason: ""}});
  const {controller, timers} = fixture;
  let payload = await controller.start();
  const request = fixture.tokenRequests[0];
  assert.equal(request.tokenServerUrl, "http://127.0.0.1:8790");
  assert.equal(request.demoSession, demoSession);
  assert.equal(request.instanceId, payload.fields.installationId);
  assert.equal(request.stateDir, fixture.stateDir);
  // the companion's environment: embed mode, no parent URNETWORK_ setting
  const env = fixture.companions[0].env;
  assert.equal(env.URNETWORK_COMPANION_EMBED, "1");
  assert.equal(env.URNETWORK_EMBED_STATE_DIR, fixture.stateDir);
  assert.equal(env.URNETWORK_ROOT_JWT, undefined);
  assert.match(env.URNETWORK_COMPANION_TOKEN, /^[0-9a-f]{64}$/);
  // before the companion listens: connecting, with the token server's caps
  assert.equal(payload.fields.status, "connecting");
  assert.equal(payload.fields.dataThisMonth, "1.2 GB of 5.0 GB");
  assert.equal(payload.fields.clientId, clientId);
  assert.equal(payload.running, true);
  assert.equal(fixture.capReads.length, 0, "the token server's data_cap is the first reading");

  fixture.companions[0].listen();
  await settle();
  payload = controller.payload();
  assert.equal(payload.fields.status, "connected");
  assert.equal(payload.licensesAvailable, true);
  assert.deepEqual(controller.getLicenses().map(license => [license.name, license.spdx]), [["github.com/urnetwork/sdk", "MPL-2.0"]]);
  // the status reads leave the licenses out; the licenses are read once
  assert.ok(fixture.statusReads.filter(read => read.licenses).length === 1);
  assert.ok(fixture.statusReads.some(read => !read.licenses));

  // a contract status change reads the caps again, with the client JWT
  fixture.embedStatus = embedStatusBody({contractStatus: {InsufficientBalance: false, NoPermission: true, Premium: false}});
  fixture.capAnswer = {client_id: clientId, monthly_byte_limit: 0, monthly_used_byte_count: 0, capped: true, capped_reason: "monthly"};
  await timers.fire(statusPollIntervalMillis);
  await settle();
  assert.equal(fixture.capReads.length, 1);
  assert.equal(fixture.capReads[0].jwt, clientJwt);
  assert.equal(controller.payload().fields.status, "paused");

  // the periodic cap read
  assert.ok(timers.has(capSyncIntervalMillis));
  fixture.capAnswer = new Error("the cap read answered HTTP 404");
  await timers.fire(capSyncIntervalMillis);
  await settle();
  assert.equal(fixture.capReads.length, 2);
  // a later failure keeps the last reading
  assert.equal(controller.payload().fields.dataThisMonth, "0 B of 0 B");

  // Stop closes the device
  const stopping = controller.stop();
  await settle();
  assert.equal(fixture.companions[0].stopCalls, 1);
  assert.equal(controller.payload().fields.status, "stopped");
  fixture.companions[0].exit({code: 0});
  payload = await stopping;
  assert.equal(payload.fields.status, "stopped");
  assert.equal(payload.running, false);
  assert.equal(payload.message, "");
  assert.ok(!timers.has(capSyncIntervalMillis), "the cap timer outlived the run");
});

test("a companion exit with 78 shows signed out with its message; another exit stopped", async t => {
  const fixture = setup(t);
  await fixture.controller.start();
  fixture.companions[0].lastErrorLine = "the server rejected the client credential; obtain a new scoped client JWT from your backend";
  fixture.companions[0].exit({code: 78});
  await settle();
  let payload = fixture.controller.payload();
  assert.equal(payload.fields.status, "signed out");
  assert.match(payload.message, /rejected the client credential/);
  assert.equal(payload.running, false);

  // the next Start clears signed out
  await fixture.controller.start();
  assert.equal(fixture.controller.payload().fields.status, "connecting");
  fixture.companions[1].exit({code: 1});
  await settle();
  payload = fixture.controller.payload();
  assert.equal(payload.fields.status, "stopped");
  assert.match(payload.message, /stopped unexpectedly \(exit code 1\)/);
});

test("a Stop during the token fetch ends the start without a companion", async t => {
  const fixture = setup(t);
  let resolveToken;
  fixture.controller.fetchToken = () => new Promise(resolve => {
    resolveToken = resolve;
  });
  const starting = fixture.controller.start();
  assert.equal(fixture.controller.payload().fields.status, "connecting");
  await fixture.controller.stop();
  resolveToken({clientJwt, clientId, dataCap: null});
  const payload = await starting;
  assert.equal(payload.fields.status, "stopped");
  assert.equal(payload.running, false);
  assert.equal(fixture.companions.length, 0);
});

test("the token server cannot change while the device runs", async t => {
  const fixture = setup(t);
  await fixture.controller.start();
  const payload = fixture.controller.saveTokenServer("https://other.example.com", "e".repeat(32));
  assert.equal(payload.message, "stop before changing the token server");
  assert.deepEqual(loadTokenServerConfig(fixture.stateDir), {url: "http://127.0.0.1:8790", session: demoSession});
});
