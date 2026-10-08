// The window's script with a fake page and bridge, in a vm context: it shows
// the status payload, disables the controls while an action waits, sends the
// token server settings once and clears the session field, and lists the
// licenses as text.

import {test} from "node:test";
import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
import vm from "node:vm";

const elementIds = [
  "token-server-url",
  "demo-session",
  "save-token-server",
  "start",
  "stop",
  "message",
  "state-dir",
  "show-licenses",
  "licenses",
  "status",
  "data-this-month",
  "data-total",
  "client-id",
  "installation-id",
];

// A status payload.
function payload(status, running, {message = "", sessionSaved = false, licensesAvailable = false, tokenServerUrl = ""} = {}) {
  return {
    fields: {status, dataThisMonth: "checking", dataTotal: "checking", clientId: "", installationId: "22222222-2222-2222-2222-222222222222"},
    running,
    message,
    tokenServerUrl,
    sessionSaved,
    stateDir: "/state/embed",
    licensesAvailable,
  };
}

// A fake element: text, value, disabled, children, listeners.
function fakeElement(tag = "div") {
  const listeners = {};
  return {
    tag,
    textContent: "",
    value: "",
    placeholder: "",
    disabled: false,
    children: [],
    appendChild(child) {
      this.children.push(child);
      return child;
    },
    replaceChildren(...children) {
      this.children = children;
    },
    addEventListener: (type, listener) => {
      listeners[type] = listener;
    },
    dispatch: type => listeners[type](),
  };
}

// Runs renderer.js against fake elements and a bridge whose actions the test
// settles; returns the elements and the bridge.
function loadRenderer(initialPayload, licenses = []) {
  const elements = {};
  for (const id of elementIds) {
    elements[id] = fakeElement();
  }
  const bridge = {pendingActions: [], statusListener: null};
  // An action that resolves when the test settles it.
  const action = name => (...args) => new Promise(resolve => {
    bridge.pendingActions.push({name, args, resolve});
  });
  const urnetworkEmbed = {
    getStatus: async () => initialPayload,
    start: action("start"),
    stop: action("stop"),
    saveTokenServer: action("saveTokenServer"),
    getLicenses: async () => licenses,
    onStatus: listener => {
      bridge.statusListener = listener;
      return () => {};
    },
  };
  const source = readFileSync(new URL("../renderer/renderer.js", import.meta.url), "utf8");
  vm.runInNewContext(source, {
    window: {urnetworkEmbed},
    document: {getElementById: id => elements[id], createElement: tag => fakeElement(tag)},
  });
  return {elements, bridge};
}

// Lets promise callbacks run.
async function settle() {
  for (let i = 0; i < 3; i += 1) {
    await new Promise(resolve => setImmediate(resolve));
  }
}

test("the window shows the payload and disables the controls while an action waits", async () => {
  const {elements, bridge} = loadRenderer(payload("stopped", false, {tokenServerUrl: "https://tokens.example.com", sessionSaved: true}));
  await settle();
  assert.equal(elements.status.textContent, "stopped");
  assert.equal(elements["installation-id"].textContent, "22222222-2222-2222-2222-222222222222");
  assert.equal(elements["token-server-url"].value, "https://tokens.example.com");
  assert.match(elements["demo-session"].placeholder, /saved/);
  assert.equal(elements.start.disabled, false);
  assert.equal(elements.stop.disabled, true);

  elements.start.dispatch("click");
  assert.equal(elements.start.disabled, true);
  assert.equal(elements.stop.disabled, true);
  bridge.statusListener(payload("connecting", true));
  assert.equal(elements.status.textContent, "connecting");
  assert.equal(elements["save-token-server"].disabled, true);
  bridge.pendingActions[0].resolve(payload("connected", true));
  await settle();
  assert.equal(elements.status.textContent, "connected");
  assert.equal(elements.stop.disabled, false);
  assert.equal(elements["token-server-url"].disabled, true);
});

test("Save sends the settings once and clears the session field", async () => {
  const {elements, bridge} = loadRenderer(payload("stopped", false));
  await settle();
  elements["token-server-url"].value = "http://127.0.0.1:8790";
  elements["token-server-url"].dispatch("input");
  elements["demo-session"].value = "d".repeat(32);
  elements["save-token-server"].dispatch("click");
  assert.deepEqual(bridge.pendingActions.map(pending => [pending.name, ...pending.args]), [["saveTokenServer", "http://127.0.0.1:8790", "d".repeat(32)]]);
  assert.equal(elements["demo-session"].value, "");
  bridge.pendingActions[0].resolve(payload("stopped", false, {message: "saved the token server", sessionSaved: true, tokenServerUrl: "http://127.0.0.1:8790"}));
  await settle();
  assert.equal(elements.message.textContent, "saved the token server");
  assert.equal(elements["token-server-url"].value, "http://127.0.0.1:8790");
});

test("the licenses are listed as text once available", async () => {
  const licenses = [{name: "github.com/urnetwork/sdk", version: "v2026", spdx: "MPL-2.0", notice: "", text: "<b>Mozilla Public License</b>"}];
  const {elements} = loadRenderer(payload("connected", true, {licensesAvailable: true}), licenses);
  await settle();
  assert.equal(elements["show-licenses"].disabled, false);
  elements["show-licenses"].dispatch("click");
  await settle();
  const [item] = elements.licenses.children;
  assert.equal(item.children[0].textContent, "github.com/urnetwork/sdk v2026");
  assert.equal(item.children[1].textContent, " (MPL-2.0)");
  // the license text stays text, never markup
  assert.equal(item.children[2].children[1].textContent, "<b>Mozilla Public License</b>");
});
