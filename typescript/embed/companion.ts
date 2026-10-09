// The native companion in embed mode (../../javascript/integration/companion,
// "Embed mode"): the child process that owns the embedded device and the
// installation state, and its /embed-status route. The JavaScript SDK cannot
// run a local Device, so this program starts the companion, reads its status
// and stops it when it stops.

import {type ChildProcess, execFile, spawn} from "node:child_process";
import {access, constants} from "node:fs/promises";
import {type AddressInfo, createServer} from "node:net";
import {fileURLToPath} from "node:url";
import {type EmbedStatus, parseEmbedStatus} from "./status.ts";

// How long a graceful stop may take before the companion is killed.
export const companionStopTimeoutMillis = 15 * 1000;

// How long one /embed-status read may take.
const companionStatusTimeoutMillis = 5 * 1000;

// How the companion ended: its exit code or signal, or the error that kept it
// from starting.
export interface CompanionExit {
  code: number | null;
  signal: NodeJS.Signals | null;
  error: Error | null;
}

// A process environment.
export type Environment = Record<string, string | undefined>;

// The companion binary that the companion README builds, unless
// URNETWORK_COMPANION_PATH names another one. A packaged app ships it as
// bin/<platform>-<arch>/ur-companion next to the app.
export function companionPath(environment: Environment = process.env, platform: NodeJS.Platform = process.platform): string {
  if (environment.URNETWORK_COMPANION_PATH) {
    return environment.URNETWORK_COMPANION_PATH;
  }
  const fileName = platform === "win32" ? "ur-companion.exe" : "ur-companion";
  return fileURLToPath(new URL(`../../javascript/integration/companion/bin/${fileName}`, import.meta.url));
}

// Resolves when the companion binary can be run; rejects with a message that
// says how to build it.
export async function checkCompanionPath(path: string): Promise<void> {
  try {
    await access(path, process.platform === "win32" ? constants.F_OK : constants.X_OK);
  } catch {
    throw new Error(`build the native companion first (javascript/integration/companion/README.md): ${path} is missing or not executable`);
  }
}

// The companion's version line in embed mode, for --version.
export function companionVersion(path: string): Promise<string> {
  return companionOutput(path, "--version");
}

// The SDK's licenses and data attributions for the host OS, as the JSON array
// that the companion prints in embed mode, for --licenses.
export function companionLicenses(path: string): Promise<string> {
  return companionOutput(path, "--licenses");
}

// What the companion prints in embed mode for one argument.
function companionOutput(path: string, argument: string): Promise<string> {
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
export async function freeLoopbackAddress(): Promise<string> {
  const server = createServer();
  await new Promise<void>((resolve, reject) => {
    server.once("error", reject);
    server.listen(0, "127.0.0.1", resolve);
  });
  const {port} = server.address() as AddressInfo;
  await new Promise(resolve => server.close(resolve));
  return `127.0.0.1:${port}`;
}

// The environment of a companion in embed mode for one launch of this program:
// the parent's environment without its URNETWORK_ settings (so a root
// credential or a demo session never reaches the child), embed mode, the state
// directory, the per-launch token and loopback address, and the stop on a
// closed standard input. It names no credential: the companion reads
// client.jwt from the state directory.
export function companionEnvironment(environment: Environment, {stateDir, token, address}: {stateDir: string; token: string; address: string}): Environment {
  const childEnvironment: Environment = {};
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
  #child: ChildProcess;
  #exitInfo: CompanionExit | null = null;
  #exited: Promise<CompanionExit>;

  // Starts the companion at path with the given environment.
  constructor(path: string, environment: Environment) {
    this.#child = spawn(path, [], {env: environment, stdio: ["pipe", "ignore", "inherit"], windowsHide: true});
    // a pipe error only means the companion is gone, which exit reports
    this.#child.stdin?.on("error", () => {});
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

  // Resolves when the companion has exited.
  get exited(): Promise<CompanionExit> {
    return this.#exited;
  }

  // The exit of the companion, or null while it runs.
  get exitInfo(): CompanionExit | null {
    return this.#exitInfo;
  }

  // Closes the device: closes the companion's standard input, waits for it to
  // exit, and kills it after timeoutMillis. Resolves with its exit.
  async stop(timeoutMillis: number = companionStopTimeoutMillis): Promise<CompanionExit> {
    if (this.#exitInfo === null) {
      this.#child.stdin?.end();
    }
    let timer: NodeJS.Timeout | undefined;
    const timedOut = new Promise<null>(resolve => {
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

// Reads the companion's /embed-status without the licenses. Rejects while the
// companion is not listening yet, and when it is gone.
export async function readEmbedStatus(address: string, token: string): Promise<EmbedStatus> {
  const url = `http://${address}/embed-status?licenses=0&token=${encodeURIComponent(token)}`;
  const response = await fetch(url, {signal: AbortSignal.timeout(companionStatusTimeoutMillis)});
  if (!response.ok) {
    throw new Error(`the companion answered ${response.status}`);
  }
  return parseEmbedStatus(await response.text());
}
