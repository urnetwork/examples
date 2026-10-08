// The ipc between the main process and the window: the status payload's
// shape, the licenses for the window, and the preload script's bridge, which
// runs here in a vm context with a fake electron module instead of a
// sandboxed window.

import {test} from "node:test";
import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
import vm from "node:vm";
import {ipcChannels, licensesForWindow, samePayload, statusPayload} from "../ipc.mjs";

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
  const source = readFileSync(new URL("../preload.cjs", import.meta.url), "utf8");
  vm.runInNewContext(source, {require: name => {
    assert.equal(name, "electron");
    return electron;
  }});
  return {api: exposed.urnetworkEmbed, invocations, listeners};
}

test("the preload exposes only the embed controls, on the channels of ipc.mjs", async () => {
  const {api, invocations, listeners} = loadPreload();
  assert.deepEqual(Object.keys(api).sort(), ["getLicenses", "getStatus", "onStatus", "saveTokenServer", "start", "stop"]);
  await api.getStatus();
  await api.start();
  await api.stop();
  await api.saveTokenServer("https://tokens.example.com", "d".repeat(32));
  await api.getLicenses();
  assert.deepEqual(invocations.map(call => call.channel),
    [ipcChannels.getStatus, ipcChannels.start, ipcChannels.stop, ipcChannels.saveTokenServer, ipcChannels.getLicenses]);
  assert.deepEqual(invocations[3].args, ["https://tokens.example.com", "d".repeat(32)]);
  const received = [];
  const remove = api.onStatus(payload => received.push(payload));
  listeners.get(ipcChannels.status)(null, {fields: {}});
  assert.equal(received.length, 1);
  remove();
  assert.equal(listeners.has(ipcChannels.status), false);
});

test("the status payload is plain strings and booleans, and changes are detected", () => {
  const payload = statusPayload({
    fields: {status: "connected", dataThisMonth: "1.2 GB of 5.0 GB", dataTotal: "no cap", clientId: "c", installationId: "i"},
    running: 1,
    message: undefined,
    tokenServerUrl: "https://tokens.example.com",
    sessionSaved: "yes",
    stateDir: "/state/embed",
    licensesAvailable: 0,
  });
  assert.deepEqual(payload, {
    fields: {status: "connected", dataThisMonth: "1.2 GB of 5.0 GB", dataTotal: "no cap", clientId: "c", installationId: "i"},
    running: true,
    message: "",
    tokenServerUrl: "https://tokens.example.com",
    sessionSaved: true,
    stateDir: "/state/embed",
    licensesAvailable: false,
  });
  assert.ok(samePayload(payload, structuredClone(payload)));
  assert.ok(!samePayload(payload, {...payload, running: false}));
});

test("the licenses reach the window as plain strings", () => {
  assert.deepEqual(licensesForWindow([{Name: "GeoLite2", Kind: "data", Notice: "This product includes GeoLite2 data", Text: "CC BY-SA", Spdx: 5}, null, "x"]), [
    {name: "GeoLite2", version: "", kind: "data", spdx: "", url: "", copyright: "", notice: "This product includes GeoLite2 data", text: "CC BY-SA"},
  ]);
  assert.deepEqual(licensesForWindow(undefined), []);
});
