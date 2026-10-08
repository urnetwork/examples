// The token fetch against a stand-in token server on loopback: the request,
// client.jwt saved privately, a mismatched claim refused, and the answers
// mapped to signed out (401, 409) or stopped (anything else).

import {test} from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import http from "node:http";
import os from "node:os";
import path from "node:path";
import {clientJwtFileName, ensureStateDir, readPrivateFile} from "../state.mjs";
import {TokenError, fetchClientJwt} from "../token.mjs";

const clientA = "11111111-1111-1111-1111-111111111111";
const clientB = "22222222-2222-2222-2222-222222222222";
const instanceId = "33333333-3333-3333-3333-333333333333";
const demoSession = "d".repeat(32);
const jwtFor = payload => `e30.${Buffer.from(JSON.stringify(payload)).toString("base64url")}.test`;

// A stand-in token server answering reply(); records the requests.
async function tokenServer(t, reply) {
  const requests = [];
  const server = http.createServer((request, response) => {
    const chunks = [];
    request.on("data", chunk => chunks.push(chunk));
    request.on("end", () => {
      requests.push({method: request.method, url: request.url, authorization: request.headers.authorization, body: Buffer.concat(chunks).toString("utf8")});
      const {status, body} = reply();
      response.writeHead(status, {"Content-Type": "application/json"});
      response.end(body);
    });
  });
  await new Promise(resolve => server.listen(0, "127.0.0.1", resolve));
  t.after(() => new Promise(resolve => server.close(resolve)));
  return {url: `http://127.0.0.1:${server.address().port}`, requests};
}

// A private state directory.
function stateDir(t) {
  const dir = path.join(fs.mkdtempSync(path.join(os.tmpdir(), "ur-electron-embed-token-")), "embed");
  t.after(() => fs.rmSync(path.dirname(dir), {recursive: true, force: true}));
  ensureStateDir(dir);
  return dir;
}

test("a token server answer is checked and saved as client.jwt", async t => {
  let reply = {status: 200, body: JSON.stringify({client_id: clientA, by_client_jwt: jwtFor({client_id: clientA}), data_cap: {client_id: clientA, total_byte_limit: 10, capped: false}})};
  const server = await tokenServer(t, () => reply);
  const dir = stateDir(t);
  const fetched = await fetchClientJwt({tokenServerUrl: server.url, demoSession, instanceId, stateDir: dir});
  assert.equal(fetched.clientId, clientA);
  assert.equal(fetched.dataCap.totalByteLimit, 10);
  assert.deepEqual(server.requests[0], {method: "POST", url: "/urnetwork/client-token", authorization: `Bearer ${demoSession}`, body: JSON.stringify({installation_id: instanceId})});
  assert.equal(readPrivateFile(path.join(dir, clientJwtFileName)).toString("utf8").trim(), jwtFor({client_id: clientA}));

  for (const [answer, signedOut] of [
    [{status: 200, body: JSON.stringify({client_id: clientB, by_client_jwt: jwtFor({client_id: clientA}), data_cap: null})}, false],
    [{status: 401, body: '{"error":{"code":"unauthorized","message":"unknown demo session"}}'}, true],
    [{status: 409, body: '{"error":{"code":"installation_limit","message":"too many installations"}}'}, true],
    [{status: 409, body: '{"error":{"code":"client_limit","message":"see https://ur.io/services"}}'}, true],
    [{status: 502, body: '{"error":{"code":"upstream","message":"api failed"}}'}, false],
    [{status: 200, body: "not json"}, false],
  ]) {
    reply = answer;
    await assert.rejects(fetchClientJwt({tokenServerUrl: server.url, demoSession, instanceId, stateDir: dir}),
      error => error instanceof TokenError && error.signedOut === signedOut, answer.body);
  }
});
