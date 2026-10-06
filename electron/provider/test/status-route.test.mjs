// Root cause of clients served staying 0: the app read clients served and
// data provided through the device rpc, whose browser state remote gets no
// provider contract details or packet stats. The companion's /provider-status
// carries them; the window must show the route's values.

import {test} from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import {ProviderController} from "../controller.mjs";
import {clientJwtFileName, writePrivateFile} from "../state.mjs";

test("clients served and data provided come from the companion's status route", async t => {
  const userDataDir = fs.mkdtempSync(path.join(os.tmpdir(), "ur-electron-route-test-"));
  t.after(() => fs.rmSync(userDataDir, {recursive: true, force: true}));
  const stateDir = path.join(userDataDir, "provider");
  fs.mkdirSync(stateDir, {mode: 0o700});
  writePrivateFile(path.join(stateDir, clientJwtFileName), `e30.${Buffer.from('{"client_id":"11111111-1111-1111-1111-111111111111"}').toString("base64url")}.test\n`);
  const pending = [];
  let resolveRoutes;
  const controller = new ProviderController({
    stateDir,
    parentEnv: {},
    timers: {setTimeout: callback => pending.push(callback), clearTimeout: () => {}, setInterval: () => 0, clearInterval: () => {}},
    startCompanion: () => ({
      routes: new Promise(resolve => {
        resolveRoutes = resolve;
      }),
      exited: new Promise(() => {}),
      stop: () => new Promise(() => {}),
      lastErrorLine: "",
    }),
    fetchProviderStatus: async () => ({
      ProvideMode: 3,
      ProvideEnabled: true,
      ProvidePaused: false,
      ProviderConnected: true,
      ClientLimitStatus: {Status: "", RetryTime: 0},
      ProviderPacketStats: {RemoteEgressByteCount: 13000000, RemoteIngressByteCount: 2342},
      ClientsServed: 2,
      ClientsServedAtLimit: false,
      DeviceRpcStarted: true,
    }),
    // what the sdk's browser state remote reads over the device rpc: no
    // provider contract rows and no packet stats, so no peer to count
    openDevice: async () => ({
      read: () => ({provideEnabled: true, providePaused: false, dataProvidedByteCount: 0}),
      close: async () => {},
    }),
    clientsServed: {count: () => ({count: 0, atLimit: false}), addContractRows: () => {}},
    payoutWallet: {get: () => ({payoutWallet: "checking", payoutWalletScope: ""}), sync: async () => {}, reset: () => {}},
    startAtLogin: {available: () => false, enabled: () => false, setEnabled: () => {}},
  });
  await controller.start();
  resolveRoutes({statusUrl: "http://127.0.0.1:54321/provider-status", deviceRpcUrl: "ws://127.0.0.1:54321/device-rpc"});
  for (let i = 0; i < 3; i += 1) {
    for (const callback of pending.splice(0)) {
      await callback();
    }
    await new Promise(resolve => setImmediate(resolve));
  }
  const fields = controller.payload().fields;
  assert.equal(fields.status, "providing");
  assert.equal(fields.clientsServed, "2");
  assert.equal(fields.dataProvided, "12.4 MiB");
});
