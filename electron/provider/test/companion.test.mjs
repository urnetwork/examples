// The companion child process: where the binary is, its environment (no
// credential, provider mode, a loopback port, stop on input close), its
// routes, the device rpc transport, the exit policy, and starting and
// stopping it, with a fake child and with a real process running
// fake-companion.mjs.

import {test} from "node:test";
import assert from "node:assert/strict";
import {EventEmitter} from "node:events";
import {PassThrough} from "node:stream";
import {fileURLToPath} from "node:url";
import {
  CompanionProcess,
  companionEnvironment,
  companionExitAction,
  companionPath,
  companionTransport,
  fetchProviderStatus,
  newCompanionToken,
  parseCompanionRoutes,
} from "../companion.mjs";

const token = "a".repeat(64);

// the companion stand-in that a real child process runs
const fakeCompanionPath = fileURLToPath(new URL("./fake-companion.mjs", import.meta.url));

// A child process double: piped standard streams, a pid, recorded kills.
function fakeChild() {
  const child = new EventEmitter();
  child.pid = 4242;
  child.stdin = new PassThrough();
  child.stdout = new PassThrough();
  child.stderr = new PassThrough();
  child.stdinEnded = false;
  child.stdin.on("finish", () => {
    child.stdinEnded = true;
  });
  child.kills = [];
  child.kill = signal => child.kills.push(signal);
  return child;
}

// Timers that run only when the test fires them.
function fakeTimers() {
  const pending = new Map();
  let nextId = 1;
  return {
    pending,
    setTimeout: (callback, millis) => {
      const id = nextId;
      nextId += 1;
      pending.set(id, {callback, millis});
      return id;
    },
    clearTimeout: id => pending.delete(id),
    // Runs every pending timer once.
    fire: () => {
      const timers = [...pending.entries()];
      pending.clear();
      for (const [, timer] of timers) {
        timer.callback();
      }
    },
  };
}

// Resolves after the readline interfaces have read what was written.
function settle() {
  return new Promise(resolve => setImmediate(resolve));
}

test("the companion binary is in the resources when packaged and in bin/<platform>-<arch> in development", () => {
  assert.equal(
    companionPath({env: {}, isPackaged: true, resourcesPath: "/Applications/URnetwork Provider.app/Contents/Resources", appPath: "/ignored", platform: "darwin", arch: "arm64"}),
    "/Applications/URnetwork Provider.app/Contents/Resources/ur-companion",
  );
  assert.equal(
    companionPath({env: {}, isPackaged: false, resourcesPath: "/ignored", appPath: "/src/electron/provider", platform: "linux", arch: "x64"}),
    "/src/electron/provider/bin/linux-x64/ur-companion",
  );
  assert.match(
    companionPath({env: {}, isPackaged: false, resourcesPath: "/ignored", appPath: "/src/electron/provider", platform: "win32", arch: "x64"}),
    /bin[\\/]win32-x64[\\/]ur-companion\.exe$/,
  );
  assert.equal(companionPath({env: {URNETWORK_COMPANION_PATH: "/opt/ur-companion"}, isPackaged: true, resourcesPath: "/ignored", appPath: "/ignored", platform: "linux", arch: "x64"}), "/opt/ur-companion");
  assert.throws(() => companionPath({env: {URNETWORK_COMPANION_PATH: "bin/ur-companion"}, isPackaged: false, resourcesPath: "", appPath: "/src", platform: "linux", arch: "x64"}));
});

test("the companion environment selects provider mode and names no credential", () => {
  const parentEnv = {
    PATH: "/usr/bin",
    HOME: "/home/user",
    URNETWORK_CLIENT_JWT: "a-jwt-from-the-developer-shell",
    urnetwork_instance_id: "33333333-3333-3333-3333-333333333333",
    URNETWORK_COMPANION_ADDRESS: "127.0.0.1:8787",
  };
  assert.deepEqual(companionEnvironment(parentEnv, {stateDir: "/state/provider", token}), {
    PATH: "/usr/bin",
    HOME: "/home/user",
    URNETWORK_COMPANION_PROVIDE: "public",
    URNETWORK_PROVIDER_STATE_DIR: "/state/provider",
    URNETWORK_COMPANION_TOKEN: token,
    URNETWORK_COMPANION_ADDRESS: "127.0.0.1:0",
    URNETWORK_COMPANION_STOP_ON_STDIN_CLOSE: "1",
  });
});

test("a companion token is 32 random bytes in hex, new for each launch", () => {
  const first = newCompanionToken();
  assert.match(first, /^[0-9a-f]{64}$/);
  assert.notEqual(newCompanionToken(), first);
});

test("the routes come from the companion's listening line on a numeric loopback address", () => {
  assert.deepEqual(parseCompanionRoutes("companion listening at http://127.0.0.1:54321/provider-status and ws://127.0.0.1:54321/device-rpc"), {
    statusUrl: "http://127.0.0.1:54321/provider-status",
    deviceRpcUrl: "ws://127.0.0.1:54321/device-rpc",
  });
  assert.deepEqual(parseCompanionRoutes("companion listening at http://[::1]:8787/provider-status and ws://[::1]:8787/device-rpc\r"), {
    statusUrl: "http://[::1]:8787/provider-status",
    deviceRpcUrl: "ws://[::1]:8787/device-rpc",
  });
  for (const line of [
    "provider client 11111111-1111-1111-1111-111111111111, instance 22222222-2222-2222-2222-222222222222",
    "companion listening at http://127.0.0.1:1/provider-status and ws://127.0.0.1:2/device-rpc",
    "companion listening at http://192.0.2.1:1/provider-status and ws://192.0.2.1:1/device-rpc",
    "Companion for client 11111111-1111-1111-1111-111111111111, instance 1 listening at ws://127.0.0.1:8787/device-rpc",
  ]) {
    assert.equal(parseCompanionRoutes(line), null, line);
  }
});

test("the provider status read sends the token to the loopback route and checks the answer", async () => {
  const requests = [];
  const answers = [
    {ok: true, status: 200, json: async () => ({ProvideMode: 3, ProviderConnected: true, ClientLimitStatus: {Status: "", RetryTime: 0}, DeviceRpcStarted: true})},
    {ok: false, status: 401, json: async () => ({})},
    {ok: true, status: 200, json: async () => ({unexpected: true})},
  ];
  const fetchFunction = async url => {
    requests.push(url);
    return answers.shift();
  };
  const providerStatus = await fetchProviderStatus("http://127.0.0.1:54321/provider-status", token, fetchFunction);
  assert.equal(providerStatus.DeviceRpcStarted, true);
  assert.deepEqual(requests, [`http://127.0.0.1:54321/provider-status?token=${token}`]);
  await assert.rejects(fetchProviderStatus("http://127.0.0.1:54321/provider-status", token, fetchFunction), /401/);
  await assert.rejects(fetchProviderStatus("http://127.0.0.1:54321/provider-status", token, fetchFunction), /not provider status/);
  await assert.rejects(fetchProviderStatus("http://203.0.113.1:54321/provider-status", token, fetchFunction), /loopback/);
  await assert.rejects(fetchProviderStatus("http://127.0.0.1:54321/provider-status", "short", fetchFunction), /32 characters/);
});

test("the device rpc transport carries owned binary frames and releases its socket", () => {
  let socket;
  // A WebSocket double that records what the transport does.
  class WebSocket extends EventTarget {
    readyState = 1;
    bufferedAmount = 0;
    sent = [];
    closes = 0;
    constructor(url) {
      super();
      this.url = url;
      socket = this;
    }
    send(bytes) {
      this.sent.push(bytes);
    }
    close() {
      this.closes += 1;
    }
  }
  const seen = [];
  const closed = [];
  const connection = companionTransport("ws://127.0.0.1:54321/device-rpc", token, WebSocket).open({
    opened: () => seen.push("open"),
    message: bytes => seen.push(bytes),
    closed: reason => closed.push(reason),
  });
  assert.equal(socket.url, `ws://127.0.0.1:54321/device-rpc?token=${token}`);
  socket.dispatchEvent(new Event("open"));
  socket.dispatchEvent(new MessageEvent("message", {data: Uint8Array.of(0, 255, 9).buffer}));
  const frame = Uint8Array.of(1, 2, 3);
  connection.send(frame);
  frame.fill(0);
  assert.deepEqual(seen, ["open", Uint8Array.of(0, 255, 9)]);
  assert.deepEqual(socket.sent, [Uint8Array.of(1, 2, 3)]);
  // the sdk closes: no callback after that, one socket close
  connection.close();
  connection.close();
  socket.dispatchEvent(new MessageEvent("message", {data: new ArrayBuffer(1)}));
  assert.equal(seen.length, 2);
  assert.deepEqual(closed, []);
  assert.equal(socket.closes, 1);
  assert.throws(() => connection.send(frame), /closed/);

  // a text frame ends the connection and tells the sdk
  const textClosed = [];
  companionTransport("ws://127.0.0.1:54321/device-rpc", token, WebSocket).open({
    opened: () => {},
    message: () => {},
    closed: reason => textClosed.push(reason),
  });
  socket.dispatchEvent(new MessageEvent("message", {data: "text"}));
  assert.deepEqual(textClosed, ["the companion sent a non-binary frame"]);
  assert.throws(() => companionTransport("ws://203.0.113.1:54321/device-rpc", token, WebSocket), /loopback/);
});

test("the app restarts a failed companion but not after a stop or a configuration error", () => {
  const cases = [
    {exit: {code: 0, spawnFailed: false, stopRequested: false}, action: "stopped"},
    {exit: {code: 1, spawnFailed: false, stopRequested: true}, action: "stopped"},
    {exit: {code: null, spawnFailed: false, stopRequested: true}, action: "stopped"},
    {exit: {code: 78, spawnFailed: false, stopRequested: false}, action: "config"},
    {exit: {code: null, spawnFailed: true, stopRequested: false}, action: "config"},
    {exit: {code: 1, spawnFailed: false, stopRequested: false}, action: "restart"},
    {exit: {code: null, spawnFailed: false, stopRequested: false}, action: "restart"},
  ];
  for (const c of cases) {
    assert.equal(companionExitAction(c.exit), c.action, JSON.stringify(c.exit));
  }
});

test("a stop closes the companion's input and kills it only after the timeout", async () => {
  const children = [];
  const timers = fakeTimers();
  const spawnFunction = (command, args, options) => {
    const child = fakeChild();
    child.spawned = {command, args, options};
    children.push(child);
    return child;
  };

  // a companion that exits on its own after the input closes
  const companion = new CompanionProcess({command: "/opt/ur-companion", env: {URNETWORK_COMPANION_PROVIDE: "public"}, spawnFunction, timers});
  const child = children[0];
  assert.deepEqual(child.spawned.args, []);
  assert.deepEqual(child.spawned.options.stdio, ["pipe", "pipe", "pipe"]);
  assert.equal(child.spawned.options.windowsHide, true);
  child.stdout.write("companion listening at http://127.0.0.1:54321/provider-status and ws://127.0.0.1:54321/device-rpc\n");
  assert.deepEqual(await companion.routes, {statusUrl: "http://127.0.0.1:54321/provider-status", deviceRpcUrl: "ws://127.0.0.1:54321/device-rpc"});
  const stopped = companion.stop();
  await settle();
  assert.equal(child.stdinEnded, true);
  assert.equal(timers.pending.size, 1);
  child.emit("close", 0, null);
  assert.deepEqual(await stopped, {code: 0, signal: null, spawnFailed: false});
  assert.equal(timers.pending.size, 0);
  assert.deepEqual(child.kills, []);

  // a companion that does not exit is killed when the timeout fires
  const stuck = new CompanionProcess({command: "/opt/ur-companion", env: {}, spawnFunction, timers});
  const stuckChild = children[1];
  const stuckStopped = stuck.stop();
  stuck.stop();
  assert.equal(timers.pending.size, 1);
  timers.fire();
  assert.deepEqual(stuckChild.kills, ["SIGKILL"]);
  stuckChild.emit("close", null, "SIGKILL");
  assert.deepEqual(await stuckStopped, {code: null, signal: "SIGKILL", spawnFailed: false});
  await assert.rejects(stuck.routes, /before it listened/);
});

test("the companion's last own error line explains its exit, not a later sdk log line", async () => {
  const child = fakeChild();
  const companion = new CompanionProcess({command: "/opt/ur-companion", env: {}, spawnFunction: () => child, timers: fakeTimers()});
  child.stderr.write("the server rejected the client credential; issue a new scoped client JWT from your backend\n");
  child.stderr.write("E1006 19:05:00.000001 1 device_local.go:1] synthetic sdk error while closing\n");
  child.stdout.write("status line on stdout\n");
  await settle();
  child.emit("close", 78, null);
  assert.equal((await companion.exited).code, 78);
  assert.equal(companion.lastErrorLine, "the server rejected the client credential; issue a new scoped client JWT from your backend");
});

test("a companion binary that cannot run is a configuration error", async () => {
  // a process that never started has no pid
  const child = fakeChild();
  child.pid = undefined;
  const companion = new CompanionProcess({command: "/opt/missing/ur-companion", env: {}, spawnFunction: () => child, timers: fakeTimers()});
  child.emit("error", Object.assign(new Error("spawn /opt/missing/ur-companion ENOENT"), {code: "ENOENT"}));
  child.emit("close", -2, null);
  const exit = await companion.exited;
  assert.deepEqual(exit, {code: null, signal: null, spawnFailed: true});
  assert.equal(companionExitAction({...exit, stopRequested: false}), "config");
  assert.match(companion.lastErrorLine, /could not run the companion at \/opt\/missing\/ur-companion/);

  // the same with a real spawn of a missing binary
  const missing = new CompanionProcess({command: "/nonexistent.example/ur-companion", env: {}});
  assert.deepEqual(await missing.exited, {code: null, signal: null, spawnFailed: true});
  assert.match(missing.lastErrorLine, /ENOENT/);
});

test("a real companion process reports its routes and status and stops when its input closes", async () => {
  const companion = new CompanionProcess({
    command: process.execPath,
    args: [fakeCompanionPath],
    env: companionEnvironment(process.env, {stateDir: "/state/provider", token}),
  });
  const routes = await companion.routes;
  const providerStatus = await fetchProviderStatus(routes.statusUrl, token);
  assert.deepEqual(providerStatus.ClientLimitStatus, {Status: "client_limit_exceeded", RetryTime: 1791313500000});
  await assert.rejects(fetchProviderStatus(routes.statusUrl, "b".repeat(64)), /401/);
  const exit = await companion.stop();
  assert.equal(exit.code, 0);
  assert.equal(companionExitAction({...exit, stopRequested: companion.stopRequested}), "stopped");
  assert.ok(companion.recentLines.includes("status: stopped"));
});

test("a real companion with a configuration error exits 78 with its message", async () => {
  const companion = new CompanionProcess({
    command: process.execPath,
    args: [fakeCompanionPath],
    env: {...companionEnvironment(process.env, {stateDir: "/state/provider", token}), FAKE_COMPANION_EXIT: "78"},
  });
  const exit = await companion.exited;
  assert.equal(exit.code, 78);
  assert.equal(companionExitAction({...exit, stopRequested: false}), "config");
  assert.equal(companion.lastErrorLine, "client.jwt is missing; write the scoped client JWT from your backend");
  await assert.rejects(companion.routes, /before it listened/);
});
