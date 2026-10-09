// The native companion in embed mode (../integration/companion, "Embed
// mode"): the child process that owns the embedded device and the
// installation state, and its /embed-status route. The JavaScript SDK cannot
// run a local Device, so this program starts the companion, reads its status
// and stops it when it stops.

import {execFile, spawn} from "node:child_process";
import {access, constants} from "node:fs/promises";
import {createServer} from "node:net";
import {fileURLToPath} from "node:url";
import {parseEmbedStatus} from "./status.mjs";

// How long a graceful stop may take before the companion is killed.
export const companionStopTimeoutMillis = 15 * 1000;

// How long one /embed-status read may take.
const companionStatusTimeoutMillis = 5 * 1000;

// The companion binary that the companion README builds, unless
// URNETWORK_COMPANION_PATH names another one. A packaged app ships it as
// bin/<platform>-<arch>/ur-companion next to the app.
export function companionPath(environment = process.env, platform = process.platform) {
  if (environment.URNETWORK_COMPANION_PATH) {
    return environment.URNETWORK_COMPANION_PATH;
  }
  const fileName = platform === "win32" ? "ur-companion.exe" : "ur-companion";
  return fileURLToPath(new URL(`../integration/companion/bin/${fileName}`, import.meta.url));
}

// Resolves when the companion binary can be run; rejects with a message that
// says how to build it.
export async function checkCompanionPath(path) {
  try {
    await access(path, process.platform === "win32" ? constants.F_OK : constants.X_OK);
  } catch {
    throw new Error(`build the native companion first (javascript/integration/companion/README.md): ${path} is missing or not executable`);
  }
}

// The companion's version line in embed mode, for --version.
export function companionVersion(path) {
  return companionOutput(path, "--version");
}

// The SDK's licenses and data attributions for the host OS, as the JSON array
// that the companion prints in embed mode, for --licenses.
export function companionLicenses(path) {
  return companionOutput(path, "--licenses");
}

// What the companion prints in embed mode for one argument.
function companionOutput(path, argument) {
  return new Promise((resolve, reject) => {
    execFile(path, [argument], {
      env: {...process.env, URNETWORK_COMPANION_EMBED: "1", URNETWORK_COMPANION_PROVIDE: ""},
      // the licenses are hundreds of kilobytes
      maxBuffer: 16 * 1024 * 1024,
      timeout: 30 * 1000,
      windowsHide: true,
    }, (error, stdout) => error ? reject(error) : resolve(stdout.trim()));
  });
}

// A free numeric loopback address for the companion, chosen by the system.
export async function freeLoopbackAddress() {
  const server = createServer();
  await new Promise((resolve, reject) => {
    server.once("error", reject);
    server.listen(0, "127.0.0.1", resolve);
  });
  const {port} = server.address();
  await new Promise(resolve => server.close(resolve));
  return `127.0.0.1:${port}`;
}

// The environment of a companion in embed mode for one launch of this program:
// the parent's environment without its URNETWORK_ settings (so a root
// credential or a demo session never reaches the child), embed mode, the state
// directory, the per-launch token and loopback address, and the stop on a
// closed standard input. It names no credential: the companion reads
// client.jwt from the state directory.
export function companionEnvironment(environment, {stateDir, token, address}) {
  const childEnvironment = {};
  for (const [name, value] of Object.entries(environment)) {
    if (!name.toUpperCase().startsWith("URNETWORK_")) {
      childEnvironment[name] = value;
    }
  }
  return {
    ...childEnvironment,
    URNETWORK_COMPANION_EMBED: "1",
    URNETWORK_EMBED_STATE_DIR: stateDir,
    URNETWORK_COMPANION_TOKEN: token,
    URNETWORK_COMPANION_ADDRESS: address,
    URNETWORK_COMPANION_STOP_ON_STDIN_CLOSE: "1",
  };
}

// A running companion child process. Its standard input is a pipe that only
// this program holds: closing it closes the device, and the companion also
// stops when this program exits for any reason. Its standard output is
// dropped; its standard error (SDK errors and its own messages) is shown.
export class CompanionProcess {
  #child;
  #exitInfo = null;
  #exited;

  // Starts the companion at path with the given environment.
  constructor(path, environment) {
    this.#child = spawn(path, [], {env: environment, stdio: ["pipe", "ignore", "inherit"], windowsHide: true});
    // a pipe error only means the companion is gone, which exit reports
    this.#child.stdin.on("error", () => {});
    this.#exited = new Promise(resolve => {
      this.#child.once("error", error => {
        this.#exitInfo ??= {code: null, signal: null, error};
        resolve(this.#exitInfo);
      });
      this.#child.once("exit", (code, signal) => {
        this.#exitInfo ??= {code, signal, error: null};
        resolve(this.#exitInfo);
      });
    });
  }

  // Resolves with {code, signal, error} when the companion has exited.
  get exited() {
    return this.#exited;
  }

  // The exit of the companion, or null while it runs.
  get exitInfo() {
    return this.#exitInfo;
  }

  // Closes the device: closes the companion's standard input, waits for it to
  // exit, and kills it after timeoutMillis. Resolves with its exit.
  async stop(timeoutMillis = companionStopTimeoutMillis) {
    if (this.#exitInfo === null) {
      this.#child.stdin.end();
    }
    let timer;
    const timedOut = new Promise(resolve => {
      timer = setTimeout(() => resolve(null), timeoutMillis);
    });
    try {
      const exitInfo = await Promise.race([this.#exited, timedOut]);
      if (exitInfo !== null) {
        return exitInfo;
      }
      this.#child.kill("SIGKILL");
      return await this.#exited;
    } finally {
      clearTimeout(timer);
    }
  }
}

// Reads the companion's /embed-status without the licenses (status.mjs
// parseEmbedStatus). Rejects while the companion is not listening yet, and
// when it is gone.
export async function readEmbedStatus(address, token) {
  const url = `http://${address}/embed-status?licenses=0&token=${encodeURIComponent(token)}`;
  const response = await fetch(url, {signal: AbortSignal.timeout(companionStatusTimeoutMillis)});
  if (!response.ok) {
    throw new Error(`the companion answered ${response.status}`);
  }
  return parseEmbedStatus(await response.text());
}
