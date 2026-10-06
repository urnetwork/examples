// The payout wallet, read only. The JavaScript sdk does not bind the device's
// wallet read, so the main process reads GET /sn/wallet with the
// installation's scoped client JWT, as PROVIDER_CONTRACT.md allows for such
// bindings. The response's `wallet` is the effective wallet for this client:
// its own provider consent, else the network consent, else the network's
// hotkey delegation, else a non-consent wallet. The app never sets a wallet.

import {
  payoutWalletChecking,
  payoutWalletNotSet,
  payoutWalletScope,
  payoutWalletUnavailable,
} from "./status.mjs";
import {parseClientJwtClientId} from "./state.mjs";

// The api of the `ur.network`/`main` network space (migration host bringyour.com).
export const defaultApiUrl = "https://api.bringyour.com";

// How often the wallet is read again. The wallet is fixed; a reread shows a
// mapping that the backend completes while the app runs.
export const walletSyncIntervalMillis = 10 * 60 * 1000;

// how long one wallet read may take
const walletReadTimeoutMillis = 30 * 1000;

// The wallet text and scope from a GET /sn/wallet result for clientId:
// {payoutWallet, payoutWalletScope}. Throws for a refused read, which answers
// with an error object.
export function payoutWalletFromResult(result, clientId) {
  if (result === null || typeof result !== "object" || result.error) {
    throw new Error("the wallet read was refused");
  }
  const wallet = result.wallet;
  if (!wallet || typeof wallet.coldkey_ss58 !== "string" || wallet.coldkey_ss58 === "") {
    return {payoutWallet: payoutWalletNotSet, payoutWalletScope: ""};
  }
  return {
    payoutWallet: wallet.coldkey_ss58,
    payoutWalletScope: payoutWalletScope(wallet.consent_scope ?? "", wallet.client_id ?? "", clientId),
  };
}

// The payout wallet field: "checking" until the first read, "unavailable" if
// that read fails, and the last good value after a later failure.
export class PayoutWallet {
  // apiUrl is the api base url; readClientJwt returns the current client.jwt
  // (the companion rewrites it on refresh); fetchFunction is fetch.
  constructor({apiUrl = defaultApiUrl, readClientJwt, fetchFunction = globalThis.fetch}) {
    this.apiUrl = apiUrl;
    this.readClientJwt = readClientJwt;
    this.fetchFunction = fetchFunction;
    this.payoutWallet = payoutWalletChecking;
    this.payoutWalletScope = "";
    // only the newest read records its result
    this.syncGeneration = 0;
  }

  // The current field value: {payoutWallet, payoutWalletScope}.
  get() {
    return {payoutWallet: this.payoutWallet, payoutWalletScope: this.payoutWalletScope};
  }

  // Back to "checking", for a newly imported credential that may name another
  // client. A read in flight no longer records its result.
  reset() {
    this.syncGeneration += 1;
    this.payoutWallet = payoutWalletChecking;
    this.payoutWalletScope = "";
  }

  // Reads the wallet once and records the result. Never rejects: a failure
  // only changes the field as the contract says.
  async sync() {
    this.syncGeneration += 1;
    const syncGeneration = this.syncGeneration;
    let wallet;
    try {
      const clientJwt = this.readClientJwt();
      const clientId = parseClientJwtClientId(clientJwt);
      const response = await this.fetchFunction(`${this.apiUrl}/sn/wallet`, {
        headers: {Authorization: `Bearer ${clientJwt}`},
        signal: AbortSignal.timeout(walletReadTimeoutMillis),
      });
      if (!response.ok) {
        throw new Error(`the wallet read answered HTTP ${response.status}`);
      }
      wallet = payoutWalletFromResult(await response.json(), clientId);
    } catch {
      wallet = undefined;
    }
    if (syncGeneration !== this.syncGeneration) {
      return;
    }
    if (wallet) {
      this.payoutWallet = wallet.payoutWallet;
      this.payoutWalletScope = wallet.payoutWalletScope;
    } else if (this.payoutWallet === payoutWalletChecking) {
      this.payoutWallet = payoutWalletUnavailable;
      this.payoutWalletScope = "";
    }
  }
}
