// The TypeScript provider example: a Node 24 console app for Windows, macOS
// and Linux that runs a URnetwork provider for the developer's network and
// shows its status (PROVIDER_CONTRACT.md). It provides publicly with the scoped
// client credential that the developer's backend issued for this
// installation; the payout wallet is mapped by the backend and is only
// displayed here. The JavaScript SDK cannot provide by itself, so the native
// companion (javascript/integration/companion, provider mode) owns the
// provider device as this program's child process (session.ts). Node runs
// this file directly with type stripping.
//
// Usage: node main.ts [run] | --self-test | --version. All installation state
// is in the private directory named by URNETWORK_PROVIDER_STATE_DIR
// (state.ts).
//
// Exit codes, for supervisors: 0 stopped on request, 78 configuration or
// credential problem (restarting does not help), 1 any other failure.

import {realpathSync} from "node:fs";
import {fileURLToPath} from "node:url";
import {type Environment, checkCompanionPath, companionPath, companionVersion} from "./companion.ts";
import {runSelfTest} from "./selftest.ts";
import {type LineWriter, ProviderSession, defaultApiUrl, exitConfig, exitFailure, exitStopped} from "./session.ts";
import {ConfigurationError, type ProviderConfig, loadProviderConfig} from "./state.ts";
import {consentDisclaimer} from "./status.ts";

// The replaceable parts of a run: the process environment and console, and
// the API origin of the wallet read, for tests.
export interface RunOptions {
  environment?: Environment;
  log?: LineWriter;
  error?: LineWriter;
  apiUrl?: string;
}

// The message of a caught value.
function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

// Writes one line to stdout, which carries this program's own lines only.
function writeLine(line: string): void {
  process.stdout.write(`${line}\n`);
}

// Runs one command and returns the exit code.
export async function run(args: string[], options: RunOptions = {}): Promise<number> {
  const environment = options.environment ?? process.env;
  const log = options.log ?? writeLine;
  const error = options.error ?? ((line: string) => console.error(line));
  if (args.length === 1 && args[0] === "--self-test") {
    try {
      await runSelfTest();
    } catch (selfTestError) {
      error(`provider self-test failed: ${errorMessage(selfTestError)}`);
      return exitFailure;
    }
    log("provider self-test passed");
    return exitStopped;
  }
  if (args.length === 1 && args[0] === "--version") {
    const path = companionPath(environment);
    try {
      await checkCompanionPath(path);
      log(await companionVersion(path));
    } catch (versionError) {
      error(errorMessage(versionError));
      return exitFailure;
    }
    return exitStopped;
  }
  if (!(args.length === 0 || (args.length === 1 && args[0] === "run"))) {
    error("usage: node main.ts [run] | --self-test | --version");
    return exitConfig;
  }

  log(consentDisclaimer);
  let config: ProviderConfig;
  try {
    config = await loadProviderConfig(environment.URNETWORK_PROVIDER_STATE_DIR);
  } catch (configError) {
    error(errorMessage(configError));
    return configError instanceof ConfigurationError ? exitConfig : exitFailure;
  }
  const path = companionPath(environment);
  try {
    await checkCompanionPath(path);
  } catch (companionError) {
    error(errorMessage(companionError));
    return exitConfig;
  }
  const controller = new AbortController();
  const stop = () => controller.abort();
  process.once("SIGINT", stop);
  process.once("SIGTERM", stop);
  // the WASM SDK prints its log lines with console.log: keep stdout for the
  // status lines and send the SDK's lines to stderr, where the other bindings
  // copy theirs
  const consoleLog = console.log;
  console.log = console.error;
  const session = new ProviderSession(config, {companionPath: path, apiUrl: options.apiUrl ?? defaultApiUrl, log, error});
  try {
    await session.start(environment);
    log(`provider client ${config.clientId}, instance ${config.instanceId}`);
    return await session.run(controller.signal);
  } catch (runError) {
    error(`could not run the provider: ${errorMessage(runError)}`);
    return exitFailure;
  } finally {
    await session.close();
    console.log = consoleLog;
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
