// The TypeScript embed example: a Node 24 console app for Windows, macOS and
// Linux that embeds URnetwork in your product (EMBED_CONTRACT.md). It obtains
// this installation's scoped client JWT from your backend's token server (or
// uses the client.jwt your backend tool wrote), starts the embedded device and
// shows its status and its own data caps. The device carries only the app's
// own traffic: no VPN. The JavaScript SDK cannot run a local Device, so the
// native companion (javascript/integration/companion, embed mode) owns the
// device as this program's child process and serves its status (session.ts).
//
// Usage: node main.ts [run] | --self-test | --licenses | --version. --licenses
// prints the SDK's licenses and data attributions, as JSON from the companion,
// to publish with the app. All installation state is in the private directory
// named by URNETWORK_EMBED_STATE_DIR (state.ts).
//
// Exit codes, for supervisors: 0 stopped on request, 78 configuration or
// credential problem (restarting does not help), 1 any other failure.

import {realpathSync} from "node:fs";
import {fileURLToPath} from "node:url";
import {parseCommand, usage} from "./command.ts";
import {type Environment, checkCompanionPath, companionLicenses, companionPath, companionVersion} from "./companion.ts";
import {runSelfTest} from "./selftest.ts";
import {EmbedSession, type EmbedSettings, type Installation, type LineWriter, exitConfig, exitFailure, exitStopped, loadEmbedSettings, obtainInstallation} from "./session.ts";
import {ConfigurationError} from "./state.ts";
import {startLine} from "./status.ts";
import {type FetchFunction, TokenError} from "./token.ts";

// The options of run, for tests.
export interface RunOptions {
  environment?: Environment;
  log?: LineWriter;
  error?: LineWriter;
  fetchFunction?: FetchFunction;
}

// The message of an error.
function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

// The exit code for an error that stopped the run before the device started.
export function startExitCode(startError: unknown): number {
  if (startError instanceof ConfigurationError) {
    return exitConfig;
  }
  if (startError instanceof TokenError) {
    return startError.exitCode;
  }
  return exitFailure;
}

// Runs one command and returns the exit code. The options replace the process
// environment, the console and fetch, for tests.
export async function run(args: readonly string[], {environment = process.env, log = console.log, error = console.error, fetchFunction = globalThis.fetch}: RunOptions = {}): Promise<number> {
  const command = parseCommand(args);
  if (command === "self-test") {
    try {
      await runSelfTest();
    } catch (selfTestError) {
      error(`embed self-test failed: ${errorMessage(selfTestError)}`);
      return exitFailure;
    }
    log("embed self-test passed");
    return exitStopped;
  }
  if (command === "version" || command === "licenses") {
    const path = companionPath(environment);
    try {
      await checkCompanionPath(path);
      log(await (command === "version" ? companionVersion(path) : companionLicenses(path)));
    } catch (companionError) {
      error(errorMessage(companionError));
      return exitFailure;
    }
    return exitStopped;
  }
  if (command === null) {
    error(usage);
    return exitConfig;
  }

  let settings: EmbedSettings;
  let installation: Installation;
  const path = companionPath(environment);
  try {
    settings = await loadEmbedSettings(environment);
    // the companion is checked before a token fetch provisions a client
    await checkCompanionPath(path).catch((companionError: unknown) => {
      throw new ConfigurationError(errorMessage(companionError));
    });
    installation = await obtainInstallation(settings, fetchFunction);
  } catch (startError) {
    error(errorMessage(startError));
    return startExitCode(startError);
  }

  const controller = new AbortController();
  const stop = (): void => controller.abort();
  process.once("SIGINT", stop);
  process.once("SIGTERM", stop);
  const session = new EmbedSession({
    stateDir: settings.stateDir,
    companionPath: path,
    apiUrl: settings.apiUrl,
    initialDataCap: installation.dataCap,
    log,
    error,
    fetchFunction,
  });
  try {
    log(startLine(installation.clientId, installation.instanceId));
    await session.start(environment);
    return await session.run(controller.signal);
  } catch (runError) {
    error(`could not run the embedded device: ${errorMessage(runError)}`);
    return exitFailure;
  } finally {
    await session.close();
    process.removeListener("SIGINT", stop);
    process.removeListener("SIGTERM", stop);
  }
}

// Whether the module of meta is the program's entry point. Node 24.2 and later
// say so in import.meta.main; earlier Node 24 releases leave it undefined, so
// the entry script's real path decides.
export function isEntryPoint(meta: {main?: boolean; url: string}, entryPath: string | undefined): boolean {
  if (typeof meta.main === "boolean") {
    return meta.main;
  }
  return entryPath !== undefined && realpathSync(entryPath) === fileURLToPath(meta.url);
}

if (isEntryPoint(import.meta, process.argv[1])) {
  process.exitCode = await run(process.argv.slice(2));
}
