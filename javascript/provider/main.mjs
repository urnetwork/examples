// The JavaScript provider example: a Node 24 console app for Windows, macOS
// and Linux that runs a URnetwork provider for the developer's network and
// shows its status (PROVIDER_CONTRACT.md). It provides publicly with the scoped
// client credential that the developer's backend issued for this
// installation; the payout wallet is mapped by the backend and is only
// displayed here. The JavaScript SDK cannot provide by itself, so the native
// companion (../integration/companion, provider mode) owns the provider device
// as this program's child process (session.mjs).
//
// Usage: node main.mjs [run] | --self-test | --version. All installation state
// is in the private directory named by URNETWORK_PROVIDER_STATE_DIR
// (state.mjs).
//
// Exit codes, for supervisors: 0 stopped on request, 78 configuration or
// credential problem (restarting does not help), 1 any other failure.

import {checkCompanionPath, companionPath, companionVersion} from "./companion.mjs";
import {runSelfTest} from "./selftest.mjs";
import {ProviderSession, defaultApiUrl, exitConfig, exitFailure, exitStopped} from "./session.mjs";
import {ConfigurationError, loadProviderConfig} from "./state.mjs";
import {consentDisclaimer} from "./status.mjs";

// Writes one line to stdout, which carries this program's own lines only.
function writeLine(line) {
  process.stdout.write(`${line}\n`);
}

// Runs one command and returns the exit code. The options replace the process
// environment and console, and the API origin of the wallet read, for tests.
export async function run(args, {environment = process.env, log = writeLine, error = console.error, apiUrl = defaultApiUrl} = {}) {
  if (args.length === 1 && args[0] === "--self-test") {
    try {
      await runSelfTest();
    } catch (selfTestError) {
      error(`provider self-test failed: ${selfTestError.message}`);
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
      error(versionError.message);
      return exitFailure;
    }
    return exitStopped;
  }
  if (!(args.length === 0 || (args.length === 1 && args[0] === "run"))) {
    error("usage: node main.mjs [run] | --self-test | --version");
    return exitConfig;
  }

  log(consentDisclaimer);
  let config;
  try {
    config = await loadProviderConfig(environment.URNETWORK_PROVIDER_STATE_DIR);
  } catch (configError) {
    error(configError.message);
    return configError instanceof ConfigurationError ? exitConfig : exitFailure;
  }
  const path = companionPath(environment);
  try {
    await checkCompanionPath(path);
  } catch (companionError) {
    error(companionError.message);
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
  const session = new ProviderSession(config, {companionPath: path, apiUrl, log, error});
  try {
    await session.start(environment);
    log(`provider client ${config.clientId}, instance ${config.instanceId}`);
    return await session.run(controller.signal);
  } catch (runError) {
    error(`could not run the provider: ${runError.message}`);
    return exitFailure;
  } finally {
    await session.close();
    console.log = consoleLog;
    process.removeListener("SIGINT", stop);
    process.removeListener("SIGTERM", stop);
  }
}

if (import.meta.main) {
  process.exitCode = await run(process.argv.slice(2));
}
