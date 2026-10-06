// The payout wallet field (PROVIDER_CONTRACT.md, "Status"): "checking" until
// the first read, "unavailable" if it fails, the last value after a later
// failure, "not set" without a mapping, and the scope labels from GET
// /sn/wallet. A fake fetch stands in for the api.

import {test} from "node:test";
import assert from "node:assert/strict";
import {PayoutWallet, payoutWalletFromResult} from "../wallet.mjs";

// the public Substrate development account, test data only
const walletA = "5GrwvaEF5zXb26Fz9rcQpDWS57CtERHpNehXCPcNoHGKutQY";
const clientId = "11111111-1111-1111-1111-111111111111";
const clientJwt = `e30.${Buffer.from(`{"client_id":"${clientId}"}`).toString("base64url")}.test`;
const apiUrl = "https://api.example";

// A fetch that answers each call with the next queued answer: a json body, an
// http status, or a thrown error. It records the requests.
function fakeFetch(answers) {
  const requests = [];
  const fetchFunction = async (url, options) => {
    requests.push({url, authorization: options.headers.Authorization});
    const answer = answers.shift();
    if (answer instanceof Error) {
      throw answer;
    }
    return {
      ok: answer.status === undefined,
      status: answer.status ?? 200,
      json: async () => answer.body,
    };
  };
  return {fetchFunction, requests};
}

test("a wallet result is labeled for this client", () => {
  const cases = [
    {result: {wallet: {coldkey_ss58: walletA, consent_scope: "network"}}, wallet: {payoutWallet: walletA, payoutWalletScope: "network"}},
    {result: {wallet: {coldkey_ss58: walletA, consent_scope: "hotkey"}}, wallet: {payoutWallet: walletA, payoutWalletScope: "hotkey"}},
    {result: {wallet: {coldkey_ss58: walletA, client_id: clientId, consent_scope: "provider"}}, wallet: {payoutWallet: walletA, payoutWalletScope: "this provider"}},
    {result: {wallet: {coldkey_ss58: walletA, client_id: "22222222-2222-2222-2222-222222222222", consent_scope: "provider"}}, wallet: {payoutWallet: walletA, payoutWalletScope: "another provider"}},
    {result: {wallet: {coldkey_ss58: walletA, set_at_millis: 1}}, wallet: {payoutWallet: walletA, payoutWalletScope: "network"}},
    {result: {}, wallet: {payoutWallet: "not set", payoutWalletScope: ""}},
    {result: {wallet: {coldkey_ss58: ""}}, wallet: {payoutWallet: "not set", payoutWalletScope: ""}},
  ];
  for (const c of cases) {
    assert.deepEqual(payoutWalletFromResult(c.result, clientId), c.wallet, JSON.stringify(c.result));
  }
  assert.throws(() => payoutWalletFromResult({error: {message: "refused"}}, clientId));
  assert.throws(() => payoutWalletFromResult(null, clientId));
});

test("the wallet is checking, then unavailable after a failed first read", async () => {
  const {fetchFunction, requests} = fakeFetch([new Error("offline")]);
  const payoutWallet = new PayoutWallet({apiUrl, readClientJwt: () => clientJwt, fetchFunction});
  assert.deepEqual(payoutWallet.get(), {payoutWallet: "checking", payoutWalletScope: ""});
  await payoutWallet.sync();
  assert.deepEqual(payoutWallet.get(), {payoutWallet: "unavailable", payoutWalletScope: ""});
  assert.deepEqual(requests, [{url: `${apiUrl}/sn/wallet`, authorization: `Bearer ${clientJwt}`}]);
});

test("a later failed read keeps the last wallet", async () => {
  const {fetchFunction} = fakeFetch([
    {body: {wallet: {coldkey_ss58: walletA, consent_scope: "network"}}},
    {status: 503},
    {body: {error: {message: "refused"}}},
    new Error("offline"),
    {body: {}},
  ]);
  const payoutWallet = new PayoutWallet({apiUrl, readClientJwt: () => clientJwt, fetchFunction});
  await payoutWallet.sync();
  assert.deepEqual(payoutWallet.get(), {payoutWallet: walletA, payoutWalletScope: "network"});
  for (let i = 0; i < 3; i += 1) {
    await payoutWallet.sync();
    assert.deepEqual(payoutWallet.get(), {payoutWallet: walletA, payoutWalletScope: "network"});
  }
  // a read that finds no mapping replaces the wallet
  await payoutWallet.sync();
  assert.deepEqual(payoutWallet.get(), {payoutWallet: "not set", payoutWalletScope: ""});
});

test("a missing credential fails the read without a request", async () => {
  const {fetchFunction, requests} = fakeFetch([]);
  const payoutWallet = new PayoutWallet({
    apiUrl,
    readClientJwt: () => {
      throw new Error("client.jwt is missing");
    },
    fetchFunction,
  });
  await payoutWallet.sync();
  assert.deepEqual(payoutWallet.get(), {payoutWallet: "unavailable", payoutWalletScope: ""});
  assert.equal(requests.length, 0);
});

test("a reset drops the result of a read in flight", async () => {
  let answer;
  const fetchFunction = () => new Promise(resolve => {
    answer = resolve;
  });
  const payoutWallet = new PayoutWallet({apiUrl, readClientJwt: () => clientJwt, fetchFunction});
  const sync = payoutWallet.sync();
  // a credential for another client is imported while the read waits
  payoutWallet.reset();
  answer({ok: true, status: 200, json: async () => ({wallet: {coldkey_ss58: walletA, client_id: clientId, consent_scope: "provider"}})});
  await sync;
  assert.deepEqual(payoutWallet.get(), {payoutWallet: "checking", payoutWalletScope: ""});
});
