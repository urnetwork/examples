// The provider controller with fake companions and timers that run only when
// the test fires them: configuration errors, the companion's environment, the
// whole status from /provider-status, stop, the restart after a failure,
// configuration exits, the client JWT import and a launch at login. The state
// directory is a real temporary directory.

import {test} from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import {ProviderController, restartDelayMillis, statusPollIntervalMillis} from "../controller.mjs";
import {clientJwtFileName, instanceIdFileName, writePrivateFile} from "../state.mjs";

const clientId = "11111111-1111-1111-1111-111111111111";
const clientJwt = `e30.${Buffer.from(`{"client_id":"${clientId}"}`).toString("base64url")}.test`;
const routes = {statusUrl: "http://127.0.0.1:54321/provider-status"};

// A /provider-status body in the companion's shape, with changes.
function providerStatusBody(changes = {}) {
  return {
    ProvideMode: 3,
    ProvideEnabled: true,
    ProvidePaused: false,
    ProviderConnected: true,
    ClientLimitStatus: {Status: "", RetryTime: 0},
    ProviderPacketStats: {RemoteEgressByteCount: 13000000, RemoteIngressByteCount: 2342},
    ClientsServed: 2,
    ClientsServedAtLimit: false,
    DeviceRpcStarted: true,
    ...changes,
  };
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
    // Whether a timer with this delay is pending.
    has: millis => [...pending.values()].some(timer => timer.millis === millis),
    // Runs the pending timers with this delay once.
    fire: async millis => {
      for (const [id, timer] of [...pending.entries()]) {
        if (timer.millis === millis) {
          pending.delete(id);
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
  const companion = {
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
  return companion;
}

// Lets promise callbacks run.
async function settle() {
  for (let i = 0; i < 5; i += 1) {
    await new Promise(resolve => setImmediate(resolve));
  }
}

// A controller on a new state directory with the given client.jwt, and its fakes.
function setup(t, {jwt = clientJwt, parentEnv = {PATH: "/usr/bin"}} = {}) {
  const userDataDir = fs.mkdtempSync(path.join(os.tmpdir(), "ur-electron-controller-test-"));
  t.after(() => fs.rmSync(userDataDir, {recursive: true, force: true}));
  const stateDir = path.join(userDataDir, "provider");
  fs.mkdirSync(stateDir, {mode: 0o700});
  if (jwt) {
    writePrivateFile(path.join(stateDir, clientJwtFileName), `${jwt}\n`);
  }
  const fixture = {
    stateDir,
    timers: fakeTimers(),
    companions: [],
    starts: [],
    providerStatus: null,
    walletSyncs: 0,
    walletResets: 0,
    payloads: [],
    lines: [],
  };
  fixture.controller = new ProviderController({
    stateDir,
    parentEnv,
    timers: fixture.timers,
    startCompanion: ({env}) => {
      fixture.starts.push(env);
      const companion = fakeCompanion();
      fixture.companions.push(companion);
      return companion;
    },
    fetchProviderStatus: async (statusUrl, token) => {
      assert.equal(statusUrl, routes.statusUrl);
      assert.equal(token, fixture.starts.at(-1).URNETWORK_COMPANION_TOKEN);
      if (!fixture.providerStatus) {
        throw new Error("not answering");
      }
      return fixture.providerStatus;
    },
    payoutWallet: {
      get: () => ({payoutWallet: "5GrwvaEF5zXb26Fz9rcQpDWS57CtERHpNehXCPcNoHGKutQY", payoutWalletScope: "network"}),
      sync: async () => {
        fixture.walletSyncs += 1;
      },
      reset: () => {
        fixture.walletResets += 1;
      },
    },
    startAtLogin: {available: () => true, enabled: () => false, setEnabled: () => {}},
    onChange: (payload, line) => {
      fixture.payloads.push(payload);
      fixture.lines.push(line);
    },
  });
  return fixture;
}

// Runs one status read of the current run.
async function poll(fixture) {
  await fixture.timers.fire(statusPollIntervalMillis);
  await settle();
}

test("start without client.jwt shows the configuration error and starts nothing", async t => {
  const fixture = setup(t, {jwt: null});
  const payload = await fixture.controller.start();
  assert.equal(fixture.starts.length, 0);
  assert.equal(payload.running, false);
  assert.equal(payload.fields.status, "stopped");
  assert.match(payload.message, /client\.jwt is missing/);
});

test("start runs the companion in provider mode without a credential in its environment", async t => {
  const fixture = setup(t, {parentEnv: {PATH: "/usr/bin", URNETWORK_CLIENT_JWT: "from-the-developer-shell"}});
  const payload = await fixture.controller.start();
  assert.equal(fixture.starts.length, 1);
  const env = fixture.starts[0];
  assert.equal(env.URNETWORK_COMPANION_PROVIDE, "public");
  assert.equal(env.URNETWORK_PROVIDER_STATE_DIR, fixture.stateDir);
  assert.equal(env.URNETWORK_COMPANION_ADDRESS, "127.0.0.1:0");
  assert.equal(env.URNETWORK_COMPANION_STOP_ON_STDIN_CLOSE, "1");
  assert.match(env.URNETWORK_COMPANION_TOKEN, /^[0-9a-f]{64}$/);
  assert.equal(env.URNETWORK_CLIENT_JWT, undefined);
  assert.equal(env.PATH, "/usr/bin");
  // the companion runs in public mode, so the status is starting until it answers
  assert.equal(payload.running, true);
  assert.equal(payload.fields.status, "starting");
  assert.ok(fs.existsSync(path.join(fixture.stateDir, instanceIdFileName)));
  // a start reads the payout wallet
  await settle();
  assert.equal(fixture.walletSyncs, 1);
  // a second start while running starts nothing
  await fixture.controller.start();
  assert.equal(fixture.starts.length, 1);
  assert.equal(fixture.walletSyncs, 1);
});

test("the companion's client limit status shows its retry time", async t => {
  const fixture = setup(t);
  await fixture.controller.start();
  fixture.providerStatus = providerStatusBody({
    ProvideEnabled: false,
    ProviderConnected: false,
    ClientLimitStatus: {Status: "client_limit_exceeded", RetryTime: 1791313500000},
    ProviderPacketStats: null,
    ClientsServed: 0,
    DeviceRpcStarted: false,
  });
  fixture.companions[0].listen();
  await settle();
  assert.equal(fixture.controller.payload().fields.status, "client limit, retry at 19:05 UTC");
  assert.equal(fixture.lines.at(-1), "status: client limit, retry at 19:05 UTC | clients served: 0 | data provided: 0 B | payout wallet: 5GrwvaEF5zXb26Fz9rcQpDWS57CtERHpNehXCPcNoHGKutQY (network)");
});

test("the whole status follows the companion's status route", async t => {
  const fixture = setup(t);
  await fixture.controller.start();
  fixture.companions[0].listen();
  await settle();
  // the companion does not answer yet: starting
  assert.equal(fixture.controller.payload().fields.status, "starting");
  fixture.providerStatus = providerStatusBody();
  await poll(fixture);
  let fields = fixture.controller.payload().fields;
  assert.deepEqual([fields.status, fields.clientsServed, fields.dataProvided], ["providing", "2", "12.4 MiB"]);
  // the companion's count reached its limit
  fixture.providerStatus = providerStatusBody({ClientsServed: 100 * 1000, ClientsServedAtLimit: true});
  await poll(fixture);
  assert.equal(fixture.controller.payload().fields.clientsServed, "100000+");
  // paused, then the provider reconnects
  fixture.providerStatus = providerStatusBody({ProvidePaused: true});
  await poll(fixture);
  assert.equal(fixture.controller.payload().fields.status, "paused");
  fixture.providerStatus = providerStatusBody({ProviderConnected: false});
  await poll(fixture);
  assert.equal(fixture.controller.payload().fields.status, "starting");
  // an answer in another shape keeps the last status
  fixture.providerStatus = {ProvideMode: 3, ProviderConnected: true};
  await poll(fixture);
  fields = fixture.controller.payload().fields;
  assert.deepEqual([fields.status, fields.clientsServed, fields.dataProvided], ["starting", "2", "12.4 MiB"]);
});

test("a stop stops the companion and shows stopped", async t => {
  const fixture = setup(t);
  await fixture.controller.start();
  fixture.providerStatus = providerStatusBody();
  fixture.companions[0].listen();
  await settle();
  assert.equal(fixture.controller.payload().fields.status, "providing");
  const stopped = fixture.controller.stop();
  await settle();
  assert.equal(fixture.companions[0].stopCalls, 1);
  assert.equal(fixture.controller.payload().fields.status, "stopped");
  assert.equal(fixture.controller.payload().message, "stopping the provider");
  assert.equal(fixture.timers.has(statusPollIntervalMillis), false);
  fixture.companions[0].exit({code: 0});
  const payload = await stopped;
  assert.equal(payload.running, false);
  assert.equal(payload.message, "");
  assert.equal(payload.fields.dataProvided, "0 B");
  assert.equal(payload.fields.clientsServed, "0");
  assert.equal(fixture.timers.has(restartDelayMillis), false);
});

test("a failed companion starts again after 30 seconds and a stop cancels that", async t => {
  const fixture = setup(t);
  await fixture.controller.start();
  fixture.companions[0].exit({code: 1});
  await settle();
  let payload = fixture.controller.payload();
  assert.equal(payload.running, true);
  assert.equal(payload.fields.status, "stopped");
  assert.equal(payload.message, "the provider stopped unexpectedly (exit code 1); it starts again in 30 seconds");
  assert.ok(fixture.timers.has(restartDelayMillis));
  await fixture.timers.fire(restartDelayMillis);
  assert.equal(fixture.starts.length, 2);
  assert.equal(fixture.controller.payload().fields.status, "starting");

  // killed by a signal: the same
  fixture.companions[1].exit({code: null, signal: "SIGSEGV"});
  await settle();
  assert.match(fixture.controller.payload().message, /signal SIGSEGV/);
  payload = await fixture.controller.stop();
  assert.equal(payload.running, false);
  assert.equal(payload.message, "");
  assert.equal(fixture.timers.has(restartDelayMillis), false);
  assert.equal(fixture.starts.length, 2);
});

test("a configuration exit shows the companion's message and does not start again", async t => {
  const fixture = setup(t);
  await fixture.controller.start();
  fixture.companions[0].lastErrorLine = "the server rejected the client credential; issue a new scoped client JWT from your backend";
  fixture.companions[0].exit({code: 78});
  await settle();
  const payload = fixture.controller.payload();
  assert.equal(payload.running, false);
  assert.equal(payload.message, "the server rejected the client credential; issue a new scoped client JWT from your backend");
  assert.equal(fixture.timers.has(restartDelayMillis), false);

  // a binary that cannot run is a configuration error too
  await fixture.controller.start();
  fixture.companions[1].lastErrorLine = "could not run the companion at /opt/ur-companion: spawn ENOENT";
  fixture.companions[1].exit({code: null, spawnFailed: true});
  await settle();
  assert.equal(fixture.controller.payload().message, "could not run the companion at /opt/ur-companion: spawn ENOENT");
  assert.equal(fixture.timers.has(restartDelayMillis), false);
});

test("a client JWT is imported only while stopped and resets the payout wallet", async t => {
  const fixture = setup(t, {jwt: null});
  const otherClientJwt = `e30.${Buffer.from('{"client_id":"22222222-2222-2222-2222-222222222222"}').toString("base64url")}.test`;
  let payload = fixture.controller.importClientJwt(otherClientJwt);
  assert.equal(payload.message, "imported the client JWT of provider client 22222222-2222-2222-2222-222222222222");
  assert.equal(fixture.walletResets, 1);
  await settle();
  assert.equal(fixture.walletSyncs, 1);
  await fixture.controller.start();
  payload = fixture.controller.importClientJwt(clientJwt);
  assert.equal(payload.message, "stop providing before importing a client JWT");
  assert.equal(fs.readFileSync(path.join(fixture.stateDir, clientJwtFileName), "utf8"), `${otherClientJwt}\n`);
  // a network JWT is refused
  fixture.companions[0].exit({code: 0});
  await settle();
  payload = fixture.controller.importClientJwt(`e30.${Buffer.from('{"network_id":"33333333-3333-3333-3333-333333333333"}').toString("base64url")}.test`);
  assert.match(payload.message, /not a network JWT/);
});

test("a launch at login starts providing and the wallet is read at start and every 10 minutes", async t => {
  const fixture = setup(t);
  await fixture.controller.begin({startProviding: true});
  assert.equal(fixture.starts.length, 1);
  await settle();
  assert.equal(fixture.walletSyncs, 1);
  await fixture.timers.fire(10 * 60 * 1000);
  await settle();
  assert.equal(fixture.walletSyncs, 2);
  // quitting stops the wallet reads and the companion
  const shutdown = fixture.controller.shutdown();
  await settle();
  assert.equal(fixture.companions[0].stopCalls, 1);
  assert.equal(fixture.timers.has(10 * 60 * 1000), false);
  fixture.companions[0].exit({code: 0});
  assert.equal((await shutdown).running, false);
});

test("without client.jwt the wallet is not read and stays checking", async t => {
  const fixture = setup(t, {jwt: null});
  await fixture.controller.begin({startProviding: false});
  await settle();
  assert.equal(fixture.walletSyncs, 0);
  assert.equal(fixture.starts.length, 0);
});

test("the payload is sent only when it changes", async t => {
  const fixture = setup(t);
  await fixture.controller.start();
  fixture.providerStatus = providerStatusBody({ProviderConnected: false, DeviceRpcStarted: false});
  fixture.companions[0].listen();
  await settle();
  const sent = fixture.payloads.length;
  await poll(fixture);
  await poll(fixture);
  assert.equal(fixture.payloads.length, sent);
  fixture.providerStatus = providerStatusBody({ProviderConnected: false, ClientLimitStatus: {Status: "client_limit_exceeded", RetryTime: 1791313500000}});
  await poll(fixture);
  assert.equal(fixture.payloads.length, sent + 1);
});
