// The embedded device of the Electron app, without Electron
// (EMBED_CONTRACT.md, "Platform notes", Electron): Start obtains the client
// JWT from the token server and starts the native companion in embed mode,
// the status is read every second from the companion's /embed-status route,
// the caps every 5 minutes and within 5 seconds of a change in the contract
// status, the licenses once, and Stop closes the device. main.mjs passes in
// the Electron parts; the unit tests pass fakes. It runs on the main process's
// event loop, so its state needs no lock.

import {defaultApiUrl, readDataCap} from "./caps.mjs";
import {
  companionEnvironment,
  companionExitAction,
  fetchEmbedStatus as fetchCompanionEmbedStatus,
  newCompanionToken,
} from "./companion.mjs";
import {licensesForWindow, samePayload, statusPayload} from "./ipc.mjs";
import {
  ConfigurationError,
  ensureStateDir,
  loadClientJwt,
  loadOrCreateInstanceId,
  loadTokenServerConfig,
  saveTokenServerConfig,
} from "./state.mjs";
import {CapReadings, dataChecking, dataFields, parseEmbedStatus, statusText} from "./status.mjs";
import {TokenError, fetchClientJwt, tokenServerOrigin} from "./token.mjs";

// How often the status is read while the device runs.
export const statusPollIntervalMillis = 1000;

// How often the caps are read again while the device runs.
export const capSyncIntervalMillis = 5 * 60 * 1000;

// The message of an error.
function errorMessage(error) {
  return error instanceof Error ? error.message : String(error);
}

// The embedded device's start, stop and status for one installation.
export class EmbedController {
  // stateDir is the installation's private state directory.
  // startCompanion({env}) starts a companion and returns a CompanionProcess.
  // fetchEmbedStatus(statusUrl, token, {licenses}) reads the /embed-status body.
  // fetchToken is fetchClientJwt, readCaps is readDataCap, apiUrl the API of
  // the cap reads. onChange(payload) receives each new status payload.
  constructor({
    stateDir,
    startCompanion,
    fetchEmbedStatus = fetchCompanionEmbedStatus,
    fetchToken = fetchClientJwt,
    readCaps = readDataCap,
    apiUrl = defaultApiUrl,
    parentEnv = process.env,
    timers = globalThis,
    onChange = () => {},
  }) {
    this.stateDir = stateDir;
    this.startCompanion = startCompanion;
    this.fetchEmbedStatus = fetchEmbedStatus;
    this.fetchToken = fetchToken;
    this.readCaps = readCaps;
    this.apiUrl = apiUrl;
    this.parentEnv = parentEnv;
    this.timers = timers;
    this.onChange = onChange;

    // the companion run, null while stopped
    this.run = null;
    // set while Start obtains the client JWT
    this.starting = false;
    // a Stop during that fetch
    this.stopAfterStart = false;
    // after an auth logout or a 401 or 409 from the token server, until the
    // next Start
    this.signedOut = false;
    // a notice for the window; never a credential
    this.message = "";
    this.capReadings = new CapReadings();
    this.capSync = null;
    this.capTimer = null;
    this.clientId = "";
    this.installationId = "";
    // the licenses that /embed-status returned, read once
    this.licenses = null;
    this.lastPayload = null;
  }

  // Prepares the state directory and shows the installation's IDs.
  begin() {
    try {
      ensureStateDir(this.stateDir);
      this.installationId = loadOrCreateInstanceId(this.stateDir);
      this.clientId = loadClientJwt(this.stateDir).clientId;
    } catch (error) {
      // no client.jwt before the first Start
      if (this.installationId === "") {
        this.message = errorMessage(error);
      }
    }
    this.publish();
  }

  // The saved token server, or null.
  tokenServerConfig() {
    try {
      return loadTokenServerConfig(this.stateDir);
    } catch {
      return null;
    }
  }

  // Saves the token server URL and demo session from the window. An empty
  // session keeps the saved one, so the URL alone can change; the session
  // never goes back to the window.
  saveTokenServer(url, session) {
    if (this.run || this.starting) {
      this.message = "stop before changing the token server";
    } else {
      try {
        const origin = tokenServerOrigin(String(url ?? "").trim());
        const enteredSession = String(session ?? "").trim();
        const savedSession = enteredSession === "" ? this.tokenServerConfig()?.session ?? "" : enteredSession;
        if (savedSession === "" || /\s/.test(savedSession)) {
          throw new ConfigurationError("enter the demo session: one token without spaces");
        }
        ensureStateDir(this.stateDir);
        saveTokenServerConfig(this.stateDir, {url: origin, session: savedSession});
        this.message = "saved the token server";
      } catch (error) {
        this.message = errorMessage(error);
      }
    }
    this.publish();
    return this.payload();
  }

  // Obtains the client JWT and starts the companion in embed mode, unless the
  // device runs already. A 401 or 409 from the token server shows "signed
  // out"; any other failure "stopped", with the message.
  async start() {
    if (this.run || this.starting) {
      return this.payload();
    }
    let config;
    try {
      ensureStateDir(this.stateDir);
      this.installationId = loadOrCreateInstanceId(this.stateDir);
      config = loadTokenServerConfig(this.stateDir);
    } catch (error) {
      this.message = errorMessage(error);
      this.publish();
      return this.payload();
    }
    if (config === null) {
      this.message = "save the token server URL and demo session first";
      this.publish();
      return this.payload();
    }
    this.starting = true;
    this.stopAfterStart = false;
    this.signedOut = false;
    this.message = "";
    this.publish();
    let fetched;
    try {
      fetched = await this.fetchToken({
        tokenServerUrl: config.url,
        demoSession: config.session,
        instanceId: this.installationId,
        stateDir: this.stateDir,
      });
    } catch (error) {
      this.starting = false;
      this.signedOut = error instanceof TokenError && error.signedOut;
      this.message = errorMessage(error);
      this.publish();
      return this.payload();
    }
    if (this.stopAfterStart) {
      this.starting = false;
      this.stopAfterStart = false;
      this.publish();
      return this.payload();
    }
    this.clientId = fetched.clientId;
    this.capReadings = new CapReadings();
    if (fetched.dataCap !== null) {
      this.capReadings.succeeded(fetched.dataCap);
    }
    const token = newCompanionToken();
    let companion;
    try {
      companion = this.startCompanion({env: companionEnvironment(this.parentEnv, {stateDir: this.stateDir, token})});
    } catch (error) {
      this.starting = false;
      this.message = `could not start the embedded device: ${errorMessage(error)}`;
      this.publish();
      return this.payload();
    }
    const run = {
      companion,
      token,
      // {statusUrl} once the companion listens
      routes: null,
      // the last good /embed-status read
      embedStatus: null,
      lastContractStatusKey: undefined,
      stopRequested: false,
      pollTimer: null,
    };
    this.run = run;
    this.starting = false;
    companion.routes.then(routes => {
      run.routes = routes;
      this.poll(run);
      this.loadLicenses(run);
    }, () => {});
    companion.exited.then(exit => this.companionExited(run, exit));
    this.schedulePoll(run);
    this.capTimer = this.timers.setInterval(() => this.syncCaps(run), capSyncIntervalMillis);
    if (this.capReadings.state === dataChecking) {
      this.syncCaps(run);
    }
    this.publish();
    return this.payload();
  }

  // Closes the device: stops the companion and waits for it to exit. A stop
  // during the token fetch ends the start when the fetch returns.
  async stop() {
    if (this.starting) {
      this.stopAfterStart = true;
      return this.payload();
    }
    const run = this.run;
    if (!run) {
      return this.payload();
    }
    if (!run.stopRequested) {
      run.stopRequested = true;
      this.cancelPoll(run);
      this.cancelCapTimer();
      this.message = "stopping the embedded device";
      this.publish();
      run.companion.stop();
    }
    await run.companion.exited;
    return this.payload();
  }

  // Closes the device, for quitting the app; resolves with the last payload.
  async shutdown() {
    return this.stop();
  }

  // The licenses for the window, once /embed-status returned them.
  getLicenses() {
    return licensesForWindow(this.licenses ?? []);
  }

  // The shown fields, by the contract's status rules for a GUI app.
  fields() {
    const run = this.run;
    const started = this.starting || (run !== null && !run.stopRequested);
    const embedStatus = run?.embedStatus ?? null;
    return {
      status: statusText({
        started,
        signedOut: this.signedOut,
        clientLimitStatus: embedStatus?.clientLimitStatus ?? "",
        clientLimitRetryTime: embedStatus?.clientLimitRetryTime ?? 0,
        cap: this.capReadings.cap,
        providerStateAdded: embedStatus?.providerStateAdded ?? 0,
      }),
      ...dataFields(this.capReadings),
      clientId: this.clientId,
      installationId: this.installationId,
    };
  }

  // The status payload for the window.
  payload() {
    const config = this.tokenServerConfig();
    return statusPayload({
      fields: this.fields(),
      running: this.run !== null || this.starting,
      message: this.message,
      tokenServerUrl: config?.url ?? "",
      sessionSaved: config !== null,
      stateDir: this.stateDir,
      licensesAvailable: this.licenses !== null,
    });
  }

  // Sends the payload when it changed.
  publish() {
    const payload = this.payload();
    if (this.lastPayload && samePayload(payload, this.lastPayload)) {
      return;
    }
    this.lastPayload = payload;
    this.onChange(payload);
  }

  // Reads the status once a second while the run lasts. A read or a window
  // update that fails does not end the reads.
  schedulePoll(run) {
    run.pollTimer = this.timers.setTimeout(async () => {
      run.pollTimer = null;
      try {
        await this.poll(run);
      } catch {
        // the next read tries again
      }
      if (this.run === run && !run.stopRequested && run.pollTimer === null) {
        this.schedulePoll(run);
      }
    }, statusPollIntervalMillis);
  }

  // Stops the status reads of a run.
  cancelPoll(run) {
    if (run.pollTimer !== null) {
      this.timers.clearTimeout(run.pollTimer);
      run.pollTimer = null;
    }
  }

  // Stops the periodic cap reads.
  cancelCapTimer() {
    if (this.capTimer !== null) {
      this.timers.clearInterval(this.capTimer);
      this.capTimer = null;
    }
  }

  // Reads /embed-status without the licenses and publishes the status. A
  // change in the contract status reads the caps again. A read that fails
  // keeps the last status: the companion is starting or stopping.
  async poll(run) {
    if (this.run !== run || run.stopRequested) {
      return;
    }
    if (run.routes) {
      try {
        const embedStatus = parseEmbedStatus(await this.fetchEmbedStatus(run.routes.statusUrl, run.token, {licenses: false}));
        if (this.run === run) {
          run.embedStatus = embedStatus;
          if (run.lastContractStatusKey !== undefined && embedStatus.contractStatusKey !== run.lastContractStatusKey) {
            this.syncCaps(run);
          }
          run.lastContractStatusKey = embedStatus.contractStatusKey;
        }
      } catch {
        // the last status stands
      }
      if (this.run !== run || run.stopRequested) {
        return;
      }
    }
    this.publish();
  }

  // Reads the licenses once, from /embed-status with the licenses.
  async loadLicenses(run) {
    if (this.licenses !== null || !run.routes) {
      return;
    }
    try {
      const embedStatus = parseEmbedStatus(await this.fetchEmbedStatus(run.routes.statusUrl, run.token, {licenses: true}));
      this.licenses = embedStatus.licenses ?? [];
    } catch {
      // the next start tries again
      return;
    }
    this.publish();
  }

  // Reads this client's caps with the client JWT that the companion keeps
  // refreshed in client.jwt. A failure keeps the last good reading; only a
  // first failure shows "unavailable".
  syncCaps(run) {
    if (this.run !== run) {
      return Promise.resolve();
    }
    this.capSync ??= (async () => {
      try {
        const {clientJwt} = loadClientJwt(this.stateDir);
        this.capReadings.succeeded(await this.readCaps({apiUrl: this.apiUrl, clientJwt}));
      } catch {
        this.capReadings.failed();
      } finally {
        this.capSync = null;
      }
      this.publish();
    })();
    return this.capSync;
  }

  // Records the end of a run: stopped, signed out (the companion exited with
  // 78, for example after an auth logout), or a failure, with its message.
  companionExited(run, exit) {
    if (this.run !== run) {
      return;
    }
    this.run = null;
    this.cancelPoll(run);
    this.cancelCapTimer();
    switch (companionExitAction({code: exit.code, spawnFailed: exit.spawnFailed, stopRequested: run.stopRequested})) {
      case "stopped":
        this.message = "";
        break;
      case "signed out":
        this.signedOut = true;
        this.message = run.companion.lastErrorLine || "the server rejected the client credential";
        break;
      default: {
        const cause = exit.spawnFailed ? "" : exit.code === null ? ` (signal ${exit.signal})` : ` (exit code ${exit.code})`;
        this.message = exit.spawnFailed
          ? run.companion.lastErrorLine || "could not run the companion"
          : `the embedded device stopped unexpectedly${cause}: ${run.companion.lastErrorLine || "see logs/ in the state directory"}`;
      }
    }
    this.publish();
  }
}
