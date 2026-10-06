// The provider of the Electron app, without Electron: Start checks the
// installation state and starts the companion in provider mode, the status is
// read every second from the companion's /provider-status route and its
// device rpc, Stop stops the companion, and a companion that fails is started
// again after 30 seconds (not after a requested stop or a configuration
// error, like the background templates). It also keeps the payout wallet,
// counts the clients served since the app started, and builds the status
// payload for the window and the status line for the tray. main.mjs passes in
// the Electron parts; the unit tests pass fakes. It runs on the main process's
// event loop, so its state needs no lock.

import {
  companionEnvironment,
  companionExitAction,
  fetchProviderStatus as fetchCompanionStatus,
  newCompanionToken,
} from "./companion.mjs";
import {samePayload, statusPayload} from "./ipc.mjs";
import {
  ensureStateDir,
  importClientJwt,
  loadClientJwt,
  loadProviderConfig,
} from "./state.mjs";
import {
  clientLimitStatusNone,
  provideModeNone,
  provideModePublic,
  providerState,
  statusFields,
  statusLine,
} from "./status.mjs";
import {walletSyncIntervalMillis} from "./wallet.mjs";

// How often the status is read while the companion runs.
export const statusPollIntervalMillis = 1000;

// How long the app waits before it starts a failed companion again.
export const restartDelayMillis = 30 * 1000;

// How many status reads pass before a device rpc that failed to open is tried again.
const deviceOpenRetryPolls = 5;

// The provider's start, stop and status for one installation.
export class ProviderController {
  // stateDir is the installation's private state directory.
  // startCompanion({env}) starts a companion and returns a CompanionProcess.
  // openDevice({config, deviceRpcUrl, token}) resolves with a CompanionDevice.
  // fetchProviderStatus(statusUrl, token) reads /provider-status.
  // payoutWallet is a PayoutWallet, clientsServed a ClientsServed, and
  // startAtLogin a StartAtLogin. onChange(payload, line) receives each new
  // status payload with its status line.
  constructor({
    stateDir,
    startCompanion,
    openDevice,
    fetchProviderStatus = fetchCompanionStatus,
    payoutWallet,
    clientsServed,
    startAtLogin,
    parentEnv = process.env,
    timers = globalThis,
    onChange = () => {},
  }) {
    this.stateDir = stateDir;
    this.startCompanion = startCompanion;
    this.openDevice = openDevice;
    this.fetchProviderStatus = fetchProviderStatus;
    this.payoutWallet = payoutWallet;
    this.clientsServed = clientsServed;
    this.startAtLogin = startAtLogin;
    this.parentEnv = parentEnv;
    this.timers = timers;
    this.onChange = onChange;

    // the companion run, null while stopped
    this.run = null;
    // pending start after a failure, null otherwise
    this.restartTimer = null;
    this.walletTimer = null;
    // a notice for the window; never a credential
    this.message = "";
    this.startAtLoginEnabled = false;
    this.lastPayload = null;
  }

  // Prepares the state directory and the payout wallet reads, then starts
  // providing when startProviding is set (a launch at login). The wallet is
  // read now, at each Start and every 10 minutes.
  async begin({startProviding}) {
    try {
      ensureStateDir(this.stateDir);
    } catch (error) {
      this.message = error.message;
    }
    this.startAtLoginEnabled = this.readStartAtLogin();
    this.walletTimer = this.timers.setInterval(() => this.syncWallet(), walletSyncIntervalMillis);
    if (startProviding) {
      await this.start();
    } else {
      this.syncWallet();
    }
    this.publish();
  }

  // Whether the app starts at login now, false when the OS cannot tell.
  readStartAtLogin() {
    try {
      return this.startAtLogin.available() && this.startAtLogin.enabled();
    } catch {
      return false;
    }
  }

  // Reads the payout wallet once, when a client credential exists: without one
  // the wallet stays "checking".
  syncWallet() {
    try {
      loadClientJwt(this.stateDir);
    } catch {
      return;
    }
    this.payoutWallet.sync().then(() => this.publish());
  }

  // Starts the companion in provider mode, unless it runs already. A start
  // during the wait after a failure starts at once.
  async start() {
    if (this.run) {
      return this.payload();
    }
    this.cancelRestart();
    let config;
    try {
      ensureStateDir(this.stateDir);
      config = loadProviderConfig(this.stateDir);
    } catch (error) {
      this.message = error.message;
      this.publish();
      return this.payload();
    }
    const token = newCompanionToken();
    let companion;
    try {
      companion = this.startCompanion({env: companionEnvironment(this.parentEnv, {stateDir: this.stateDir, token})});
    } catch (error) {
      this.message = `could not start the provider: ${error.message}`;
      this.publish();
      return this.payload();
    }
    const run = {
      companion,
      token,
      config,
      // {statusUrl, deviceRpcUrl} once the companion listens
      routes: null,
      // the last /provider-status json
      providerStatus: null,
      // the CompanionDevice once the device rpc is open
      device: null,
      deviceOpening: false,
      // status reads to skip before opening the device rpc again
      deviceOpenWait: 0,
      deviceError: "",
      stopRequested: false,
      pollTimer: null,
    };
    this.run = run;
    this.message = "";
    this.syncWallet();
    companion.routes.then(routes => {
      run.routes = routes;
      this.poll(run);
    }, () => {});
    companion.exited.then(exit => this.companionExited(run, exit));
    this.schedulePoll(run);
    this.publish();
    return this.payload();
  }

  // Stops providing: closes the device rpc, then stops the companion and waits
  // for it to exit. A stop during the wait after a failure cancels the start.
  async stop() {
    if (this.restartTimer !== null) {
      this.cancelRestart();
      this.message = "";
      this.publish();
      return this.payload();
    }
    const run = this.run;
    if (!run) {
      return this.payload();
    }
    if (!run.stopRequested) {
      run.stopRequested = true;
      this.cancelPoll(run);
      this.message = "stopping the provider";
      this.publish();
      const device = run.device;
      run.device = null;
      if (device) {
        await device.close().catch(() => {});
      }
      run.companion.stop();
    }
    await run.companion.exited;
    return this.payload();
  }

  // Stops providing and the wallet reads, for quitting the app; resolves with
  // the last payload.
  async shutdown() {
    if (this.walletTimer !== null) {
      this.timers.clearInterval(this.walletTimer);
      this.walletTimer = null;
    }
    return this.stop();
  }

  // Saves a scoped client JWT as the installation's client.jwt. Refused while
  // providing: the running companion owns the credential.
  importClientJwt(text) {
    if (this.run) {
      this.message = "stop providing before importing a client JWT";
    } else {
      try {
        ensureStateDir(this.stateDir);
        const clientId = importClientJwt(this.stateDir, text);
        this.message = `imported the client JWT of provider client ${clientId}`;
        this.payoutWallet.reset();
        this.syncWallet();
      } catch (error) {
        this.message = error.message;
      }
    }
    this.publish();
    return this.payload();
  }

  // Shows a notice in the window, for example a refused file.
  showMessage(message) {
    this.message = message;
    this.publish();
    return this.payload();
  }

  // Turns start at login on or off.
  setStartAtLogin(enabled) {
    try {
      this.startAtLogin.setEnabled(enabled);
    } catch (error) {
      this.message = `could not change start at login: ${error.message}`;
    }
    this.startAtLoginEnabled = this.readStartAtLogin();
    this.publish();
    return this.payload();
  }

  // The status snapshot (status.mjs) from the companion's status and device.
  status() {
    const run = this.run;
    let provideMode = provideModeNone;
    let clientLimitStatus = clientLimitStatusNone;
    let clientLimitRetryTime = 0;
    let providerConnected = false;
    let provideEnabled = false;
    let providePaused = false;
    let dataProvidedByteCount = 0;
    if (run && !run.stopRequested) {
      const providerStatus = run.providerStatus;
      // the companion runs in public mode; until it answers, that is the mode
      provideMode = providerStatus ? providerStatus.ProvideMode : provideModePublic;
      clientLimitStatus = providerStatus?.ClientLimitStatus?.Status ?? clientLimitStatusNone;
      clientLimitRetryTime = providerStatus?.ClientLimitStatus?.RetryTime ?? 0;
      providerConnected = providerStatus?.ProviderConnected === true;
      if (run.device) {
        try {
          ({provideEnabled, providePaused, dataProvidedByteCount} = run.device.read());
        } catch {
          // a device rpc that broke reads as not yet enabled
        }
      }
    }
    const {count, atLimit} = this.clientsServed.count();
    return {
      state: providerState({provideMode, clientLimitStatus, providePaused, provideEnabled, providerConnected}),
      clientLimitRetryTime,
      clientsServed: count,
      clientsServedAtLimit: atLimit,
      dataProvidedByteCount,
      ...this.payoutWallet.get(),
    };
  }

  // The status payload for the window.
  payload() {
    return this.payloadFor(this.status());
  }

  // The payload for one status snapshot.
  payloadFor(status) {
    const run = this.run;
    const message = this.message || run?.deviceError || "";
    return statusPayload({
      fields: statusFields(status),
      running: run !== null || this.restartTimer !== null,
      message,
      startAtLogin: this.startAtLoginEnabled,
      startAtLoginAvailable: this.startAtLogin.available(),
      stateDir: this.stateDir,
    });
  }

  // Sends the payload and the status line when the payload changed.
  publish() {
    const status = this.status();
    const payload = this.payloadFor(status);
    if (this.lastPayload && samePayload(payload, this.lastPayload)) {
      return;
    }
    this.lastPayload = payload;
    this.onChange(payload, statusLine(status));
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

  // Reads /provider-status, opens the device rpc once the companion serves
  // it, and publishes the status.
  async poll(run) {
    if (this.run !== run || run.stopRequested) {
      return;
    }
    if (run.routes) {
      try {
        run.providerStatus = await this.fetchProviderStatus(run.routes.statusUrl, run.token);
      } catch {
        // the companion is starting or stopping; the last status stands
      }
      if (this.run !== run || run.stopRequested) {
        return;
      }
      if (run.providerStatus?.DeviceRpcStarted && !run.device && !run.deviceOpening) {
        if (0 < run.deviceOpenWait) {
          run.deviceOpenWait -= 1;
        } else {
          this.openRunDevice(run);
        }
      }
    }
    this.publish();
  }

  // Opens the device rpc of a run; a failure is tried again a few reads later.
  openRunDevice(run) {
    run.deviceOpening = true;
    this.openDevice({config: run.config, deviceRpcUrl: run.routes.deviceRpcUrl, token: run.token}).then(device => {
      run.deviceOpening = false;
      if (this.run !== run || run.stopRequested) {
        device.close().catch(() => {});
        return;
      }
      run.device = device;
      run.deviceError = "";
      this.publish();
    }, error => {
      run.deviceOpening = false;
      run.deviceOpenWait = deviceOpenRetryPolls;
      run.deviceError = `could not read the provider device: ${error.message}`;
      this.publish();
    });
  }

  // Records the end of a run: stopped, a configuration error to fix, or a
  // failure that starts the companion again after restartDelayMillis.
  companionExited(run, exit) {
    if (this.run !== run) {
      return;
    }
    this.run = null;
    this.cancelPoll(run);
    if (run.device) {
      run.device.close().catch(() => {});
      run.device = null;
    }
    switch (companionExitAction({code: exit.code, spawnFailed: exit.spawnFailed, stopRequested: run.stopRequested})) {
      case "stopped":
        this.message = "";
        break;
      case "config":
        this.message = run.companion.lastErrorLine || `the provider stopped with exit code ${exit.code}`;
        break;
      default: {
        const cause = exit.code === null ? `signal ${exit.signal}` : `exit code ${exit.code}`;
        this.message = `the provider stopped unexpectedly (${cause}); it starts again in 30 seconds`;
        this.restartTimer = this.timers.setTimeout(() => {
          this.restartTimer = null;
          this.start();
        }, restartDelayMillis);
      }
    }
    this.publish();
  }

  // Cancels a pending start after a failure.
  cancelRestart() {
    if (this.restartTimer !== null) {
      this.timers.clearTimeout(this.restartTimer);
      this.restartTimer = null;
    }
  }
}
