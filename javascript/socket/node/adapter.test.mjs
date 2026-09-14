import {test} from "node:test";
import assert from "node:assert/strict";
import {once} from "node:events";
import {UrNodeSocket, connector} from "../ur_node_socket.mjs";
import {parseResponse} from "../browser/http.mjs";

test("Node adapter preserves bytes, EOF, write half-close and full close", async () => {
  const writes = []; let closed = 0, half = 0;
  const reads = [Uint8Array.of(1, 2), null];
  const conn = {async read() {return reads.shift();}, async write(b) {writes.push([...b]);}, async closeWrite() {half++;}, async close() {closed++;}};
  const socket = new UrNodeSocket(conn);
  const chunks = []; socket.on("data", b => chunks.push(...b));
  const done = once(socket, "close"); socket.end(Buffer.from([3, 4])); await done;
  assert.deepEqual(chunks, [1, 2]); assert.deepEqual(writes, [[3, 4]]);
  assert.equal(half, 1); assert.equal(closed, 1);
});
test("Connector preserves hostnames and TLS verification defaults", async () => {
  let args;
  const device = {async dialTls(...values) {args = values; return {async close() {}};}};
  const socket = await new Promise((resolve, reject) => connector(device)({hostname: "dual.example", protocol: "https:", port: 443}, (e, c) => e ? reject(e) : resolve(c)));
  assert.equal(args[1], "dual.example:443"); assert.equal(args[2].serverName, "dual.example"); socket.destroy();
});
test("Browser adapter decodes chunked HTTP bytes without losing multibyte text", () => {
  const bytes = new TextEncoder().encode("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n3\r\nhé\r\n0\r\n\r\n");
  assert.equal(parseResponse(bytes).data, "hé");
  assert.throws(() => parseResponse(new TextEncoder().encode("HTTP/1.1 200 OK\r\nContent-Length: 3\r\n\r\nx")), /Incomplete/);
});
