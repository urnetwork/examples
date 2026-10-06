// A running provider: the native companion child process that owns the
// provider device, its /provider-status route, and the payout wallet read.
// The companion reads every status value in process with the full SDK and
// counts the clients served; this program does not load the JavaScript SDK,
// whose remotes run in browser state only mode and get no provider packet
// stats or provider contract details over the companion's device rpc.
// Mirrors the Go provider's session.go.

import {randomBytes} from "node:crypto";
import {setTimeout as sleep} from "node:timers/promises";
import {type CompanionExit, CompanionProcess, type Environment, companionEnvironment, freeLoopbackAddress, readCompanionStatus} from "./companion.ts";
import {type ProviderConfig, loadClientJwt} from "./state.ts";
import {
  type CompanionStatus,
  type ProviderStatus,
  payoutWalletChecking,
  payoutWalletFromResult,
  payoutWalletUnavailable,
  providerStatus,
  statusKey,
  statusLine,
} from "./status.ts";

export const exitStopped = 0;
export const exitFailure = 1;
// sysexits EX_CONFIG
export const exitConfig = 78;

// The API of the companion's network space (ur.network, main), for the wallet
// read.
export const defaultApiUrl = "https://api.bringyour.com";

// How often the status is read, and the longest gap between status lines.
const statusPollIntervalMillis = 1000;
const statusRepeatIntervalMillis = 60 * 1000;

// How often the payout wallet is read again. The wallet is fixed; a reread
// shows a mapping that the backend completes while the provider runs.
const walletSyncIntervalMillis = 10 * 60 * 1000;
const walletReadTimeoutMillis = 30 * 1000;

// Writes one line of output.
export type LineWriter = (line: string) => void;

// This program's exit code after the companion exited by itself: 0 after a
// requested stop (for example a signal sent to the companion), 78 for a
// configuration or credential problem it reported, such as the server
// rejecting the client credential, and 1 for any other failure.
export function companionExitCode(exitInfo: CompanionExit | null): number {
  if (exitInfo?.code === exitStopped || exitInfo?.code === exitConfig) {
    return exitInfo.code;
  }
  return exitFailure;
}

// One run of the provider. start starts the companion; run shows the status;
// close stops providing.
export class ProviderSession {
  #config: ProviderConfig;
  #companionPath: string;
  #apiUrl: string;
  #log: LineWriter;
  #error: LineWriter;
  // authorizes this launch's requests to the companion; never stored
  #token = randomBytes(32).toString("hex");
  #address = "";
  #companion: CompanionProcess | null = null;
  // the last /provider-status read, null until the first one
  #companionStatus: CompanionStatus | null = null;
  // payoutWalletChecking, payoutWalletUnavailable, payoutWalletNotSet or the
  // mapped coldkey
  #payoutWallet = payoutWalletChecking;
  #payoutWalletScope = "";
  #walletSync: Promise<void> | null = null;

  // A session for the loaded installation state (state.ts) and the companion
  // binary at companionPath. apiUrl is the URnetwork API origin of the
  // companion's network space; tests replace it with a local stand-in.
  constructor(config: ProviderConfig, options: {companionPath: string; apiUrl?: string; log: LineWriter; error: LineWriter}) {
    this.#config = config;
    this.#companionPath = options.companionPath;
    this.#apiUrl = options.apiUrl ?? defaultApiUrl;
    this.#log = options.log;
    this.#error = options.error;
  }

  // Starts the companion in provider mode on a free loopback port. The
  // companion starts providing publicly.
  async start(environment: Environment): Promise<void> {
    this.#address = await freeLoopbackAddress();
    this.#companion = new CompanionProcess(this.#companionPath, companionEnvironment(environment, {
      stateDir: this.#config.stateDir,
      token: this.#token,
      address: this.#address,
    }));
  }

  // Reads the status from the companion. Until its first answer the status is
  // "starting"; when a read fails the last one stays.
  async status(): Promise<ProviderStatus> {
    try {
      this.#companionStatus = await readCompanionStatus(this.#address, this.#token);
    } catch {
      // not listening yet, or gone; run reports a companion that exited
    }
    return providerStatus(this.#companionStatus, this.#payoutWallet, this.#payoutWalletScope);
  }

  // Reads the payout wallet (GET /sn/wallet with the client credential, which
  // the companion keeps refreshed in client.jwt). The app only displays the
  // wallet: the backend maps it, never the app. A failure keeps the last known
  // wallet.
  syncWallet(): Promise<void> {
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
  async run(signal: AbortSignal): Promise<number> {
    const companion = this.#companion;
    if (companion === null) {
      throw new Error("the provider is not started");
    }
    let lastKey = "";
    let lastPrintTime = 0;
    let nextWalletSyncTime = Date.now() + walletSyncIntervalMillis;
    void this.syncWallet();
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
        void this.syncWallet();
        nextWalletSyncTime = now + walletSyncIntervalMillis;
      }
      await Promise.race([
        sleep(statusPollIntervalMillis, undefined, {signal}).catch(() => {}),
        companion.exited,
      ]);
      if (signal.aborted) {
        return exitStopped;
      }
      const exitInfo = companion.exitInfo;
      if (exitInfo !== null) {
        this.#error(exitInfo.error
          ? `could not start the native companion: ${exitInfo.error.message}`
          : `the native companion exited (${exitInfo.code ?? exitInfo.signal}); its errors are above and in logs/ in the state directory`);
        return companionExitCode(exitInfo);
      }
    }
  }

  // Stops providing: the companion sets the provide mode to none, closes the
  // device and exits.
  async close(): Promise<void> {
    await this.#companion?.stop();
    this.#log("status: stopped");
  }
}
