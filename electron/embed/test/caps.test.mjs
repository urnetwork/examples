// The cap read with a stand-in fetch: the client JWT as the bearer, the cap
// object read, any failure rejected, and the Embed-not-enabled refusal
// rejected with its own error.

import {test} from "node:test";
import assert from "node:assert/strict";
import {EmbedNotEnabledError, readDataCap} from "../caps.mjs";

const clientId = "11111111-1111-1111-1111-111111111111";

// A fetch that answers status and body, and records the requests.
function answering(requests, status, body) {
  return async (url, options) => {
    requests.push({url, authorization: options.headers.Authorization});
    return new Response(JSON.stringify(body), {status, headers: {"content-type": "application/json"}});
  };
}

test("the cap read presents the client JWT and reads the cap object", async () => {
  const requests = [];
  const cap = await readDataCap({apiUrl: "http://127.0.0.1:1", clientJwt: "client.jwt.token",
    fetchFunction: answering(requests, 200, {client_id: clientId, monthly_byte_limit: 7, capped: false})});
  assert.equal(cap.monthlyByteLimit, 7);
  assert.deepEqual(requests, [{url: "http://127.0.0.1:1/network/client-data-cap", authorization: "Bearer client.jwt.token"}]);
});

test("a failed cap read rejects, and the Embed-not-enabled refusal rejects with EmbedNotEnabledError", async () => {
  for (const [status, body, embedNotEnabled] of [
    [404, {error: {message: "not found"}}, false],
    [200, {error: {message: "no permission"}}, false],
    [200, {error: {message: "Embed isn't enabled for this network."}}, true],
  ]) {
    await assert.rejects(readDataCap({apiUrl: "http://127.0.0.1:1", clientJwt: "client.jwt.token", fetchFunction: answering([], status, body)}),
      error => (error instanceof EmbedNotEnabledError) === embedNotEnabled, JSON.stringify(body));
  }
});
