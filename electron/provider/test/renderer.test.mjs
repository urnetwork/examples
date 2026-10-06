// The window's script with a fake page and bridge, in a vm context: it shows
// the status payload, disables the controls while an action waits, and still
// shows the payloads the main process pushes meanwhile, such as the stopping
// notice while Stop waits for the companion to exit.

import {test} from "node:test";
import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
import vm from "node:vm";

const elementIds = [
  "start",
  "stop",
  "import-client-jwt",
  "start-at-login",
  "message",
  "state-dir",
  "status",
  "clients-served",
  "data-provided",
  "payout-wallet",
];

// A status payload with the given status, running state and message.
function payload(status, running, message = "") {
  return {
    fields: {status, clientsServed: "0", dataProvided: "0 B", payoutWallet: "checking"},
    running,
    message,
    startAtLogin: false,
    startAtLoginAvailable: true,
    stateDir: "/state/provider",
  };
}

// Runs renderer.js against fake elements and a bridge whose actions the test
// settles; returns the elements, the bridge and the pushed-status listener.
function loadRenderer(initialPayload) {
  const elements = {};
  for (const id of elementIds) {
    const listeners = {};
    elements[id] = {
      textContent: "",
      disabled: false,
      checked: false,
      addEventListener: (type, listener) => {
        listeners[type] = listener;
      },
      dispatch: type => listeners[type](),
    };
  }
  const bridge = {pendingActions: [], statusListener: null};
  // An action that resolves when the test settles it.
  const action = name => (...args) => new Promise(resolve => {
    bridge.pendingActions.push({name, args, resolve});
  });
  const urnetworkProvider = {
    getStatus: async () => initialPayload,
    start: action("start"),
    stop: action("stop"),
    importClientJwt: action("importClientJwt"),
    setStartAtLogin: action("setStartAtLogin"),
    onStatus: listener => {
      bridge.statusListener = listener;
      return () => {};
    },
  };
  const source = readFileSync(new URL("../renderer/renderer.js", import.meta.url), "utf8");
  vm.runInNewContext(source, {
    window: {urnetworkProvider},
    document: {getElementById: id => elements[id]},
  });
  return {elements, bridge};
}

// Lets promise callbacks run.
async function settle() {
  for (let i = 0; i < 3; i += 1) {
    await new Promise(resolve => setImmediate(resolve));
  }
}

test("the window shows pushed payloads while an action waits, with the controls disabled", async () => {
  const {elements, bridge} = loadRenderer(payload("client limit, retry at 19:05 UTC", true));
  await settle();
  assert.equal(elements.status.textContent, "client limit, retry at 19:05 UTC");
  assert.equal(elements.start.disabled, true);
  assert.equal(elements.stop.disabled, false);

  // Stop waits for the companion; the main process pushes the stopping notice
  elements.stop.dispatch("click");
  assert.equal(elements.start.disabled, true);
  assert.equal(elements.stop.disabled, true);
  bridge.statusListener(payload("stopped", true, "stopping the provider"));
  assert.equal(elements.status.textContent, "stopped");
  assert.equal(elements.message.textContent, "stopping the provider");
  assert.equal(elements.start.disabled, true);
  assert.equal(elements.stop.disabled, true);

  // the stop finished
  bridge.pendingActions[0].resolve(payload("stopped", false));
  await settle();
  assert.equal(elements.message.textContent, "");
  assert.equal(elements.start.disabled, false);
  assert.equal(elements.stop.disabled, true);
  assert.equal(elements["import-client-jwt"].disabled, false);
});

test("start at login sends the checkbox state and shows a refused action's message", async () => {
  const {elements, bridge} = loadRenderer(payload("stopped", false));
  await settle();
  elements["start-at-login"].checked = true;
  elements["start-at-login"].dispatch("change");
  assert.deepEqual(bridge.pendingActions.map(pending => [pending.name, ...pending.args]), [["setStartAtLogin", true]]);
  assert.equal(elements["start-at-login"].disabled, true);
  bridge.pendingActions[0].resolve(payload("stopped", false, "could not change start at login: synthetic refusal"));
  await settle();
  assert.equal(elements.message.textContent, "could not change start at login: synthetic refusal");
  assert.equal(elements["start-at-login"].checked, false);
  assert.equal(elements["start-at-login"].disabled, false);
});
