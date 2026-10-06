// The ipc between the main process and the window: the status payload's shape
// and the preload script's bridge, which runs here in a vm context with a fake
// electron module instead of a sandboxed window.

import {test} from "node:test";
import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
import vm from "node:vm";
import {ipcChannels, samePayload, statusPayload} from "../ipc.mjs";

// Runs preload.cjs against a fake contextBridge and ipcRenderer and returns
// the exposed api with the recorded calls.
function loadPreload() {
  const exposed = {};
  const invocations = [];
  const listeners = new Map();
  const electron = {
    contextBridge: {
      exposeInMainWorld: (name, api) => {
        exposed[name] = api;
      },
    },
    ipcRenderer: {
      invoke: (channel, ...args) => {
        invocations.push({channel, args});
        return Promise.resolve({channel});
      },
      on: (channel, listener) => listeners.set(channel, listener),
      removeListener: (channel, listener) => {
        if (listeners.get(channel) === listener) {
          listeners.delete(channel);
        }
      },
    },
  };
  const require = name => {
    if (name !== "electron") {
      throw new Error(`the sandboxed preload cannot require ${name}`);
    }
    return electron;
  };
  const source = readFileSync(new URL("../preload.cjs", import.meta.url), "utf8");
  vm.runInNewContext(source, {require});
  return {exposed, invocations, listeners};
}

test("the status payload carries the four fields and the controls as plain values", () => {
  const payload = statusPayload({
    fields: {status: "providing", clientsServed: "3", dataProvided: "12.4 MiB", payoutWallet: "5GrwvaEF5zXb26Fz9rcQpDWS57CtERHpNehXCPcNoHGKutQY (network)"},
    running: true,
    message: "",
    startAtLogin: false,
    startAtLoginAvailable: true,
    stateDir: "/home/user.example/.config/URnetwork Provider/provider",
  });
  assert.deepEqual(payload, {
    fields: {status: "providing", clientsServed: "3", dataProvided: "12.4 MiB", payoutWallet: "5GrwvaEF5zXb26Fz9rcQpDWS57CtERHpNehXCPcNoHGKutQY (network)"},
    running: true,
    message: "",
    startAtLogin: false,
    startAtLoginAvailable: true,
    stateDir: "/home/user.example/.config/URnetwork Provider/provider",
  });
  // it survives the structured clone of ipc unchanged
  assert.deepEqual(structuredClone(payload), payload);
  assert.ok(samePayload(payload, structuredClone(payload)));
  assert.ok(!samePayload(payload, {...payload, running: false}));
});

test("the preload exposes only the provider controls on the ipc channels", async () => {
  const {exposed, invocations, listeners} = loadPreload();
  assert.deepEqual(Object.keys(exposed), ["urnetworkProvider"]);
  const provider = exposed.urnetworkProvider;
  assert.deepEqual(Object.keys(provider).sort(), ["getStatus", "importClientJwt", "onStatus", "setStartAtLogin", "start", "stop"]);

  await provider.getStatus();
  await provider.start();
  await provider.stop();
  await provider.importClientJwt();
  // only a boolean crosses for start at login
  await provider.setStartAtLogin(true);
  await provider.setStartAtLogin("yes");
  assert.deepEqual(invocations, [
    {channel: ipcChannels.getStatus, args: []},
    {channel: ipcChannels.start, args: []},
    {channel: ipcChannels.stop, args: []},
    {channel: ipcChannels.importClientJwt, args: []},
    {channel: ipcChannels.setStartAtLogin, args: [true]},
    {channel: ipcChannels.setStartAtLogin, args: [false]},
  ]);

  // status pushes reach the listener without the ipc event, until removed
  const payloads = [];
  const remove = provider.onStatus(payload => payloads.push(payload));
  listeners.get(ipcChannels.status)({sender: "ipc event"}, {running: true});
  assert.deepEqual(payloads, [{running: true}]);
  remove();
  assert.equal(listeners.has(ipcChannels.status), false);
});
