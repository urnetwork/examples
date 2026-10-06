// A running provider: the native companion child process that owns the
// provider device, the SDK's DeviceRemote over the companion's device rpc with
// the provider's contract view controllers, and the payout wallet read. The
// status combines the companion's /provider-status (the provide mode, the
// provider's platform connection and the client limit status, which the
// JavaScript SDK does not bind) with the SDK getters (provide paused and
// enabled), the provider packet stats (data provided) and the provider
// contract rows (clients served). Mirrors the Go provider's session.go.

import {randomBytes} from "node:crypto";
import {setTimeout as sleep} from "node:timers/promises";
import {companionTransport} from "../integration/companion.mjs";
import {CompanionProcess, companionEnvironment, freeLoopbackAddress, readCompanionStatus} from "./companion.mjs";
import {loadClientJwt} from "./state.mjs";
import {
  ClientsServed,
  clientsServedLimit,
  payoutWalletChecking,
  payoutWalletFromResult,
  payoutWalletUnavailable,
  providerState,
  providerStateStarting,
  statusKey,
  statusLine,
} from "./status.mjs";

export const exitStopped = 0;
export const exitFailure = 1;
// sysexits EX_CONFIG
export const exitConfig = 78;

// The companion's network space (ur.network, main), for the SDK's remote and
// the wallet read.
export const defaultApiUrl = "https://api.bringyour.com";
export const platformUrl = "wss://connect.bringyour.com";

// How often the status is read, and the longest gap between status lines.
const statusPollIntervalMillis = 1000;
const statusRepeatIntervalMillis = 60 * 1000;

// How often the payout wallet is read again. The wallet is fixed; a reread
// shows a mapping that the backend completes while the provider runs.
const walletSyncIntervalMillis = 10 * 60 * 1000;
const walletReadTimeoutMillis = 30 * 1000;

// This program's exit code after the companion exited by itself: 0 after a
// requested stop (for example a signal sent to the companion), 78 for a
// configuration or credential problem it reported, such as the server
// rejecting the client credential, and 1 for any other failure.
export function companionExitCode(exitInfo) {
  if (exitInfo?.code === exitStopped || exitInfo?.code === exitConfig) {
    return exitInfo.code;
  }
  return exitFailure;
}

// One run of the provider. start starts the companion; run shows the status;
// close stops providing.
export class ProviderSession {
  #config;
  #companionPath;
  #apiUrl;
  #log;
  #error;
  // authorizes this launch's requests to the companion; never stored
  #token = randomBytes(32).toString("hex");
  #address = "";
  #urNetwork = null;
  #companion = null;
  // the last /provider-status read, null until the first one
  #companionStatus = null;
  #sdk = null;
  #device = null;
  #contractViewController = null;
  #contractDetailsViewController = null;
  #unsubscribes = [];
  #clientsServed = new ClientsServed(clientsServedLimit);
  // payoutWalletChecking, payoutWalletUnavailable, payoutWalletNotSet or the
  // mapped coldkey
  #payoutWallet = payoutWalletChecking;
  #payoutWalletScope = "";
  #walletSync = null;

  // A session for the loaded installation state (state.mjs) and the companion
  // binary at companionPath. apiUrl is the URnetwork API origin of the
  // companion's network space; tests replace it with a local stand-in.
  constructor(config, {companionPath, apiUrl = defaultApiUrl, log, error}) {
    this.#config = config;
    this.#companionPath = companionPath;
    this.#apiUrl = apiUrl;
    this.#log = log;
    this.#error = error;
  }

  // Loads the SDK, then starts the companion in provider mode on a free
  // loopback port. The companion starts providing publicly.
  async start(environment) {
    ({URNetwork: this.#urNetwork} = await import("@urnetwork/sdk"));
    this.#address = await freeLoopbackAddress();
    this.#companion = new CompanionProcess(this.#companionPath, companionEnvironment(environment, {
      stateDir: this.#config.stateDir,
      token: this.#token,
      address: this.#address,
    }));
  }

  // Reads the status: the companion's device values, then the SDK's once the
  // companion serves its device rpc, which it does only after the provider
  // connected.
  async status() {
    try {
      this.#companionStatus = await readCompanionStatus(this.#address, this.#token);
    } catch {
      // not listening yet, or gone; run reports a companion that exited
    }
    if (this.#companionStatus?.deviceRpcStarted && this.#device === null) {
      await this.#openDevice();
    }
    const device = this.#device;
    const remoteConnected = device?.getRemoteConnected() ?? false;
    const companionStatus = this.#companionStatus;
    const state = companionStatus === null ? providerStateStarting : providerState({
      provideMode: companionStatus.provideMode,
      clientLimitStatus: companionStatus.clientLimitStatus,
      providePaused: remoteConnected && device.getProvidePaused(),
      provideEnabled: remoteConnected && device.getProvideEnabled(),
      providerConnected: companionStatus.providerConnected,
    });
    // the rows listener counts every change; this read also counts rows that
    // were already open when the controller started
    this.#clientsServed.addRows(this.#contractDetailsViewController?.getContractRows());
    const packetStats = this.#contractViewController?.getProviderPacketStats() ?? null;
    // bytes relayed for clients, in both directions, since the device started
    const dataProvidedByteCount = packetStats === null ? 0 : packetStats.remoteEgressByteCount + packetStats.remoteIngressByteCount;
    const {count, atLimit} = this.#clientsServed.count();
    return {
      state,
      clientLimitRetryTime: companionStatus?.clientLimitRetryTime ?? 0,
      clientsServed: count,
      clientsServedAtLimit: atLimit,
      dataProvidedByteCount,
      payoutWallet: this.#payoutWallet,
      payoutWalletScope: this.#payoutWalletScope,
    };
  }

  // Connects the SDK's DeviceRemote to the companion's device rpc and opens the
  // provider's contract view controllers. The companion serves the rpc with
  // the provide setters disabled: the app reads the provider but cannot change
  // it.
  async #openDevice() {
    this.#sdk = await this.#urNetwork.init();
    this.#device = this.#sdk.createExtensionDeviceRemote({
      apiUrl: this.#apiUrl,
      platformUrl,
      byJwt: this.#config.clientJwt,
      instanceId: this.#config.instanceId,
      transport: companionTransport(`ws://${this.#address}/device-rpc`, this.#token),
    });
    this.#contractViewController = this.#device.openContractViewController();
    this.#contractViewController.start();
    const contractDetailsViewController = this.#device.openProviderContractDetailsViewController();
    this.#contractDetailsViewController = contractDetailsViewController;
    this.#unsubscribes.push(contractDetailsViewController.addContractRowsListener(() => {
      this.#clientsServed.addRows(contractDetailsViewController.getContractRows());
    }));
    contractDetailsViewController.start();
  }

  // Reads the payout wallet (GET /sn/wallet with the client credential, which
  // the companion keeps refreshed in client.jwt). The app only displays the
  // wallet: the backend maps it, never the app. A failure keeps the last known
  // wallet.
  syncWallet() {
    this.#walletSync ??= (async () => {
      try {
        const {clientJwt} = await loadClientJwt(this.#config.stateDir);
        const response = await fetch(`${this.#apiUrl}/sn/wallet`, {
          headers: {Authorization: `Bearer ${clientJwt}`},
          signal: AbortSignal.timeout(walletReadTimeoutMillis),
        });
        const wallet = response.ok ? payoutWalletFromResult(await response.json(), this.#config.clientId) : null;
        if (wallet === null) {
          throw new Error("the wallet read failed");
        }
        this.#payoutWallet = wallet.payoutWallet;
        this.#payoutWalletScope = wallet.payoutWalletScope;
      } catch {
        if (this.#payoutWallet === payoutWalletChecking) {
          this.#payoutWallet = payoutWalletUnavailable;
        }
      } finally {
        this.#walletSync = null;
      }
    })();
    return this.#walletSync;
  }

  // Prints status lines until the signal aborts or the companion exits, and
  // returns the process exit code.
  async run(signal) {
    let lastKey = "";
    let lastPrintTime = 0;
    let nextWalletSyncTime = Date.now() + walletSyncIntervalMillis;
    this.syncWallet();
    while (true) {
      const status = await this.status();
      const now = Date.now();
      const key = statusKey(status);
      if (key !== lastKey || statusRepeatIntervalMillis <= now - lastPrintTime) {
        this.#log(statusLine(status));
        lastKey = key;
        lastPrintTime = now;
      }
      if (nextWalletSyncTime <= now) {
        this.syncWallet();
        nextWalletSyncTime = now + walletSyncIntervalMillis;
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

  // Stops providing: closes the SDK objects, then stops the companion, which
  // sets the provide mode to none and closes the device.
  async close() {
    for (const unsubscribe of this.#unsubscribes.splice(0)) {
      unsubscribe();
    }
    await this.#contractDetailsViewController?.close();
    await this.#contractViewController?.close();
    await this.#device?.close();
    this.#sdk?.close();
    await this.#companion?.stop();
    this.#log("status: stopped");
  }
}
