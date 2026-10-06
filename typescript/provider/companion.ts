// The native companion in provider mode (javascript/integration/companion,
// "Provider mode"): the child process that owns the provider device and the
// installation state, and its two loopback routes. The JavaScript SDK cannot
// provide by itself, so this program starts the companion, reads its status
// and talks to its device rpc, and stops it when it stops.

import {type ChildProcess, execFile, spawn} from "node:child_process";
import {access, constants} from "node:fs/promises";
import {createServer} from "node:net";
import {fileURLToPath} from "node:url";
import {type CompanionStatus, parseCompanionStatus} from "./status.ts";

// How long a graceful stop may take before the companion is killed.
export const companionStopTimeoutMillis = 15 * 1000;

// How long one /provider-status read may take.
const companionStatusTimeoutMillis = 5 * 1000;

// How the companion ended: its exit code or signal, or the error that kept it
// from starting.
export interface CompanionExit {
  code: number | null;
  signal: NodeJS.Signals | null;
  error: Error | null;
}

// The environment of one process.
export type Environment = Record<string, string | undefined>;

// The companion binary that the companion README builds, unless
// URNETWORK_COMPANION_PATH names another one.
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

// The companion's version line, for --version.
export function companionVersion(path: string): Promise<string> {
  return new Promise((resolve, reject) => {
    execFile(path, ["--version"], {env: {...process.env, URNETWORK_COMPANION_PROVIDE: "public"}, timeout: 30 * 1000, windowsHide: true},
      (error, stdout) => error ? reject(error) : resolve(stdout.trim()));
  });
}

// A free numeric loopback address for the companion, chosen by the system.
export async function freeLoopbackAddress(): Promise<string> {
  const server = createServer();
  await new Promise<void>((resolve, reject) => {
    server.once("error", reject);
    server.listen(0, "127.0.0.1", resolve);
  });
  const serverAddress = server.address();
  const port = typeof serverAddress === "object" && serverAddress !== null ? serverAddress.port : 0;
  await new Promise(resolve => server.close(resolve));
  return `127.0.0.1:${port}`;
}

// The environment of a companion in provider mode for one launch of this
// program: the state directory, the per-launch token and loopback address, and
// the stop on a closed standard input.
export function companionEnvironment(
  environment: Environment,
  launch: {stateDir: string; token: string; address: string},
): Environment {
  return {
    ...environment,
    URNETWORK_COMPANION_PROVIDE: "public",
    URNETWORK_PROVIDER_STATE_DIR: launch.stateDir,
    URNETWORK_COMPANION_TOKEN: launch.token,
    URNETWORK_COMPANION_ADDRESS: launch.address,
    URNETWORK_COMPANION_STOP_ON_STDIN_CLOSE: "1",
  };
}

// A running companion child process. Its standard input is a pipe that only
// this program holds: closing it stops providing, and the companion also stops
// when this program exits for any reason. Its standard output, which repeats
// the consent disclaimer, is dropped; its standard error (SDK errors and the
// extender role's listeners) is shown.
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

  // Stops providing: closes the companion's standard input, waits for it to
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

// Reads the companion's /provider-status. Rejects while the companion is not
// listening yet, and when it is gone.
export async function readCompanionStatus(address: string, token: string): Promise<CompanionStatus> {
  const url = `http://${address}/provider-status?token=${encodeURIComponent(token)}`;
  const response = await fetch(url, {signal: AbortSignal.timeout(companionStatusTimeoutMillis)});
  if (!response.ok) {
    throw new Error(`the companion answered ${response.status}`);
  }
  return parseCompanionStatus(await response.text());
}
