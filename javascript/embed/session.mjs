// A running embed installation: the native companion child process that owns
// the embedded device, its /embed-status route, and the app's own data cap
// reads. The companion reads the device values in process with the full SDK;
// this program does not load the JavaScript SDK. It prints a status line when
// any field's text changes, and otherwise once a minute.

import {randomBytes} from "node:crypto";
import {setTimeout as sleep} from "node:timers/promises";
import {apiOrigin, defaultApiUrl, readDataCap} from "./caps.mjs";
import {CompanionProcess, companionEnvironment, freeLoopbackAddress, readEmbedStatus} from "./companion.mjs";
import {checkStateDir, loadClientJwt, loadOrCreateInstanceId} from "./state.mjs";
import {CapReadings, dataChecking, dataFields, statusLine, statusText} from "./status.mjs";
import {fetchClientJwt, tokenServerSettings} from "./token.mjs";

export const exitStopped = 0;
export const exitFailure = 1;
// sysexits EX_CONFIG
export const exitConfig = 78;

// How often the status is read, and the longest gap between status lines.
const statusPollIntervalMillis = 1000;
const statusRepeatIntervalMillis = 60 * 1000;

// How often the caps are read again. A change in the device's contract status
// reads them at the next status read, within 5 seconds.
export const capSyncIntervalMillis = 5 * 60 * 1000;

// This program's exit code after the companion exited by itself: 0 after a
// requested stop (for example a signal sent to the companion), 78 for a
// configuration or credential problem it reported, such as an auth logout,
// and 1 for any other failure.
export function companionExitCode(exitInfo) {
  if (exitInfo?.code === exitStopped || exitInfo?.code === exitConfig) {
    return exitInfo.code;
  }
  return exitFailure;
}

// The settings of one run from the environment, checked before anything
// starts: the state directory, the API origin for the cap reads, and the
// token server (null without one). Rejects with a ConfigurationError.
export async function loadEmbedSettings(environment) {
  const stateDir = environment.URNETWORK_EMBED_STATE_DIR;
  await checkStateDir(stateDir);
  return {
    stateDir,
    apiUrl: apiOrigin(environment.URNETWORK_API_URL || defaultApiUrl),
    tokenServer: tokenServerSettings(environment),
  };
}

// The installation's identity and credential (EMBED_CONTRACT.md, "App
// lifecycle" 1 and 2): instance-id, created on first run, and the client JWT,
// fetched from the token server on every start when one is configured, else
// the client.jwt already in the state. Resolves with {instanceId, clientJwt,
// clientId, dataCap}; dataCap is the token server's first cap reading or
// null. Rejects with a ConfigurationError or a TokenError.
export async function obtainInstallation(settings, fetchFunction = globalThis.fetch) {
  const instanceId = await loadOrCreateInstanceId(settings.stateDir);
  if (settings.tokenServer !== null) {
    const {clientJwt, clientId, dataCap} = await fetchClientJwt({
      tokenServerUrl: settings.tokenServer.url,
      demoSession: settings.tokenServer.session,
      instanceId,
      stateDir: settings.stateDir,
      fetchFunction,
    });
    return {instanceId, clientJwt, clientId, dataCap};
  }
  const {clientJwt, clientId} = await loadClientJwt(settings.stateDir);
  return {instanceId, clientJwt, clientId, dataCap: null};
}

// One run of the embedded device. start starts the companion; run shows the
// status; close closes the device.
export class EmbedSession {
  #stateDir;
  #companionPath;
  #apiUrl;
  #log;
  #error;
  #fetchFunction;
  // authorizes this launch's requests to the companion; never stored
  #token = randomBytes(32).toString("hex");
  #address = "";
  #companion = null;
  // the last /embed-status read, null until the first one
  #companionStatus = null;
  #capReadings = new CapReadings();
  #capSync = null;

  // A session for the installation's state directory and the companion binary
  // at companionPath. apiUrl is the URnetwork API origin of the cap reads;
  // initialDataCap is the token server's first cap reading or null; tests
  // replace fetchFunction.
  constructor({stateDir, companionPath, apiUrl = defaultApiUrl, initialDataCap = null, log, error, fetchFunction = globalThis.fetch}) {
    this.#stateDir = stateDir;
    this.#companionPath = companionPath;
    this.#apiUrl = apiUrl;
    this.#log = log;
    this.#error = error;
    this.#fetchFunction = fetchFunction;
    if (initialDataCap !== null) {
      this.#capReadings.succeeded(initialDataCap);
    }
  }

  // Starts the companion in embed mode on a free loopback port. The companion
  // creates the device and connects to the best available location.
  async start(environment) {
    this.#address = await freeLoopbackAddress();
    this.#companion = new CompanionProcess(this.#companionPath, companionEnvironment(environment, {
      stateDir: this.#stateDir,
      token: this.#token,
      address: this.#address,
    }));
  }

  // Reads the status from the companion and returns the shown fields. Until
  // the companion's first answer the status is "connecting"; when a read
  // fails the last one stays.
  async status() {
    try {
      this.#companionStatus = await readEmbedStatus(this.#address, this.#token);
    } catch {
      // not listening yet, or gone; run reports a companion that exited
    }
    const companionStatus = this.#companionStatus;
    return {
      status: statusText({
        clientLimitStatus: companionStatus?.clientLimitStatus ?? "",
        clientLimitRetryTime: companionStatus?.clientLimitRetryTime ?? 0,
        cap: this.#capReadings.cap,
        providerStateAdded: companionStatus?.providerStateAdded ?? 0,
      }),
      ...dataFields(this.#capReadings),
    };
  }

  // Reads this client's caps with the client JWT that the companion keeps
  // refreshed in client.jwt. A failure keeps the last good reading; only a
  // first failure shows "unavailable".
  syncCaps() {
    this.#capSync ??= (async () => {
      try {
        const {clientJwt} = await loadClientJwt(this.#stateDir);
        this.#capReadings.succeeded(await readDataCap({apiUrl: this.#apiUrl, clientJwt, fetchFunction: this.#fetchFunction}));
      } catch {
        this.#capReadings.failed();
      } finally {
        this.#capSync = null;
      }
    })();
    return this.#capSync;
  }

  // Prints status lines until the signal aborts or the companion exits, and
  // returns the process exit code.
  async run(signal) {
    let lastLine = "";
    let lastPrintTime = 0;
    let lastContractStatusKey;
    let nextCapSyncTime = Date.now() + capSyncIntervalMillis;
    if (this.#capReadings.state === dataChecking) {
      this.syncCaps();
    }
    while (true) {
      const fields = await this.status();
      const now = Date.now();
      // a change in the contract status (for example a cap reached) reads
      // the caps again now
      const contractStatusKey = this.#companionStatus?.contractStatusKey;
      if (contractStatusKey !== undefined) {
        if (lastContractStatusKey !== undefined && contractStatusKey !== lastContractStatusKey) {
          this.syncCaps();
          nextCapSyncTime = now + capSyncIntervalMillis;
        }
        lastContractStatusKey = contractStatusKey;
      }
      if (nextCapSyncTime <= now) {
        this.syncCaps();
        nextCapSyncTime = now + capSyncIntervalMillis;
      }
      const line = statusLine(fields);
      if (line !== lastLine || statusRepeatIntervalMillis <= now - lastPrintTime) {
        this.#log(line);
        lastLine = line;
        lastPrintTime = now;
      }
      await Promise.race([
        sleep(statusPollIntervalMillis, undefined, {signal}).catch(() => {}),
        this.#companion.exited,
      ]);
      if (signal.aborted) {
        return exitStopped;
      }
      const exitInfo = this.#companion.exitInfo;
      if (exitInfo !== null) {
        this.#error(exitInfo.error
          ? `could not start the native companion: ${exitInfo.error.message}`
          : `the native companion exited (${exitInfo.code ?? exitInfo.signal}); its errors are above and in logs/ in the state directory`);
        return companionExitCode(exitInfo);
      }
    }
  }

  // Closes the device: the companion closes its subscriptions, the device and
  // the manager, and exits.
  async close() {
    await this.#companion?.stop();
  }
}
