// The native companion (javascript/integration/companion) in provider mode, as
// a child process of the main process. The companion owns the provider device
// and the installation state, and serves the whole provider status on its
// /provider-status route, on a numeric loopback address with a random token
// per launch. The app needs nothing else from it: the JavaScript sdk's remotes
// get no provider packet stats or provider contract details over the
// companion's /device-rpc. This module finds the binary, builds its
// environment, parses its status route, and starts and stops it.

import {spawn} from "node:child_process";
import {randomBytes} from "node:crypto";
import path from "node:path";
import readline from "node:readline";

// the companion's exit codes (PROVIDER_CONTRACT.md, "Exit codes")
export const exitStopped = 0;
export const exitConfig = 78;

// How long a stop waits for the companion to stop providing and exit before
// it is killed.
export const companionStopTimeoutMillis = 15 * 1000;

// how many of the companion's last output lines are kept for messages
const recentLineLimit = 20;

// A glog line of the sdk, for example "E1006 19:05:00.000001 12345 file.go:1] ...".
// The companion's own messages are the lines without this prefix.
const sdkLogLinePattern = /^[IWEF]\d{4} \d{2}:\d{2}:\d{2}\.\d+ /;

// The companion binary's file name on platform.
export function companionFileName(platform) {
  return platform === "win32" ? "ur-companion.exe" : "ur-companion";
}

// Where the companion binary is: URNETWORK_COMPANION_PATH when set (an
// absolute path); in the packaged app's resources directory; else in
// bin/<platform>-<arch>/ of the app directory, where the README's build puts it.
export function companionPath({env, isPackaged, resourcesPath, appPath, platform, arch}) {
  const configured = env.URNETWORK_COMPANION_PATH;
  if (configured) {
    if (!path.isAbsolute(configured)) {
      throw new Error("URNETWORK_COMPANION_PATH must be an absolute path");
    }
    return configured;
  }
  if (isPackaged) {
    return path.join(resourcesPath, companionFileName(platform));
  }
  return path.join(appPath, "bin", `${platform}-${arch}`, companionFileName(platform));
}

// A new random companion token: 32 bytes as 64 hex characters. A token lives
// for one companion launch and never leaves the main process and the child's
// environment.
export function newCompanionToken() {
  return randomBytes(32).toString("hex");
}

// The companion's environment: the parent's, without any URNETWORK_ setting
// (on Windows the names are case-insensitive), plus provider mode, the state
// directory, the token, an ephemeral numeric loopback port, and a stop when
// its standard input closes. It names no credential: the companion reads
// client.jwt from the state directory.
export function companionEnvironment(parentEnv, {stateDir, token}) {
  const env = {};
  for (const [name, value] of Object.entries(parentEnv)) {
    if (!name.toUpperCase().startsWith("URNETWORK_")) {
      env[name] = value;
    }
  }
  return {
    ...env,
    URNETWORK_COMPANION_PROVIDE: "public",
    URNETWORK_PROVIDER_STATE_DIR: stateDir,
    URNETWORK_COMPANION_TOKEN: token,
    URNETWORK_COMPANION_ADDRESS: "127.0.0.1:0",
    URNETWORK_COMPANION_STOP_ON_STDIN_CLOSE: "1",
  };
}

// The status route from the companion's listening line, "companion listening
// at http://127.0.0.1:<port>/provider-status and ws://127.0.0.1:<port>/device-rpc":
// {statusUrl}, or null for any other line.
export function parseCompanionRoutes(line) {
  const match = /^companion listening at (http:\/\/(127\.0\.0\.1|\[::1\]):(\d{1,5})\/provider-status) and ws:\/\/(127\.0\.0\.1|\[::1\]):(\d{1,5})\/device-rpc$/.exec(line.trim());
  if (!match || match[2] !== match[4] || match[3] !== match[5]) {
    return null;
  }
  return {statusUrl: match[1]};
}

// The status route's url with the token, which the companion reads from the
// query. Only a numeric loopback http url is accepted.
export function companionRouteUrl(url, token) {
  const endpoint = new URL(url);
  if (endpoint.protocol !== "http:" || !["127.0.0.1", "[::1]"].includes(endpoint.hostname) || endpoint.username || endpoint.password) {
    throw new Error("a companion route must use a numeric loopback address");
  }
  if (typeof token !== "string" || token.length < 32) {
    throw new Error("the companion token must have at least 32 characters");
  }
  endpoint.searchParams.set("token", token);
  return endpoint.href;
}

// Reads the /provider-status json body, for example {"ProvideMode":3,
// "ProvideEnabled":true,"ProvidePaused":false,"ProviderConnected":true,
// "ClientLimitStatus":{"Status":"","RetryTime":0},"ProviderPacketStats":
// {"RemoteEgressByteCount":5,"RemoteIngressByteCount":7},"ClientsServed":2,
// "ClientsServedAtLimit":false,"DeviceRpcStarted":true}; status.mjs
// parseProviderStatus checks it. Rejects when the companion does not answer.
export async function fetchProviderStatus(statusUrl, token, fetchFunction = globalThis.fetch) {
  const response = await fetchFunction(companionRouteUrl(statusUrl, token), {
    cache: "no-store",
    signal: AbortSignal.timeout(5 * 1000),
  });
  if (!response.ok) {
    throw new Error(`the companion status answered HTTP ${response.status}`);
  }
  return response.json();
}

// What the app does after the companion exited: "stopped" after a requested
// stop or a clean exit, "config" for a configuration or credential problem
// that a restart does not fix (exit 78, or a binary that cannot run),
// "restart" for any other failure.
export function companionExitAction({code, spawnFailed, stopRequested}) {
  if (stopRequested || code === exitStopped) {
    return "stopped";
  }
  if (spawnFailed || code === exitConfig) {
    return "config";
  }
  return "restart";
}

// One companion process. The constructor starts it; `routes` resolves with
// its loopback routes once it listens, `exited` with {code, signal,
// spawnFailed} once it exited and its output ended. stop closes its standard
// input, which stops providing on every OS, and kills it if it has not exited
// after stopTimeoutMillis.
export class CompanionProcess {
  // command is the binary, args its arguments (none for the companion) and
  // env its environment; spawnFunction and timers are child_process.spawn and
  // the global timers, or test doubles.
  constructor({command, args = [], env, spawnFunction = spawn, timers = globalThis, stopTimeoutMillis = companionStopTimeoutMillis}) {
    this.timers = timers;
    this.stopTimeoutMillis = stopTimeoutMillis;
    this.stopRequested = false;
    this.exitResult = null;
    this.recentLines = [];
    this.lastErrorLine = "";
    this.killTimer = null;

    let resolveRoutes;
    let rejectRoutes;
    this.routes = new Promise((resolve, reject) => {
      resolveRoutes = resolve;
      rejectRoutes = reject;
    });
    // a run that never listens must not report an unhandled rejection
    this.routes.catch(() => {});
    let resolveExited;
    this.exited = new Promise(resolve => {
      resolveExited = resolve;
    });

    // Records the exit once, from "close" or a spawn "error".
    const finish = exitResult => {
      if (this.exitResult) {
        return;
      }
      this.exitResult = exitResult;
      if (this.killTimer !== null) {
        this.timers.clearTimeout(this.killTimer);
        this.killTimer = null;
      }
      rejectRoutes(new Error("the companion exited before it listened"));
      resolveExited(exitResult);
    };

    this.child = spawnFunction(command, args, {env, stdio: ["pipe", "pipe", "pipe"], windowsHide: true});
    this.child.on("error", error => {
      // a process that never started has no pid; other errors (a failed
      // kill) leave the run to its close
      if (this.child.pid === undefined) {
        this.lastErrorLine = `could not run the companion at ${command}: ${error.message}`;
        finish({code: null, signal: null, spawnFailed: true});
      }
    });
    // after the exit and the end of its output, so the last lines are read
    this.child.on("close", (code, signal) => finish({code, signal, spawnFailed: false}));
    // a companion that already exited closes its input; the exit reports it
    this.child.stdin.on("error", () => {});
    for (const [stream, stderr] of [[this.child.stdout, false], [this.child.stderr, true]]) {
      readline.createInterface({input: stream, crlfDelay: Infinity}).on("line", line => {
        this.recentLines.push(line);
        if (recentLineLimit < this.recentLines.length) {
          this.recentLines.shift();
        }
        // the companion's own last error explains an exit; sdk log lines
        // that follow it while it closes do not
        if (stderr && line.trim() !== "" && !sdkLogLinePattern.test(line)) {
          this.lastErrorLine = line.trim();
        }
        const routes = stderr ? null : parseCompanionRoutes(line);
        if (routes) {
          resolveRoutes(routes);
        }
      });
    }
  }

  // Asks the companion to stop providing and exit; resolves with its exit.
  stop() {
    if (!this.exitResult && !this.stopRequested) {
      this.stopRequested = true;
      this.child.stdin.end();
      this.killTimer = this.timers.setTimeout(() => {
        this.killTimer = null;
        this.child.kill("SIGKILL");
      }, this.stopTimeoutMillis);
    }
    return this.exited;
  }
}
