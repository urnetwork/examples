import {test} from "node:test";
import assert from "node:assert/strict";
import {companionTransport} from "../integration/companion.mjs";

test("companion transport owns bytes, stops callbacks, and releases its socket", () => {
  let socket;
  class WebSocket extends EventTarget {
    readyState = 1; bufferedAmount = 0; sent = []; closes = 0;
    constructor(url) {super(); this.url = url; socket = this;}
    send(bytes) {this.sent.push(bytes);}
    close() {this.closes++;}
  }
  const seen = [], closed = [];
  const connection = companionTransport("ws://127.0.0.1:8787/device-rpc", "a".repeat(32), WebSocket).open({
    opened: () => seen.push("open"), message: bytes => seen.push(bytes), closed: reason => closed.push(reason),
  });
  socket.dispatchEvent(new Event("open"));
  const bytes = Uint8Array.of(0,255,9);
  socket.dispatchEvent(new MessageEvent("message", {data: bytes.buffer}));
  connection.send(bytes); bytes.fill(0);
  assert.deepEqual(seen, ["open", Uint8Array.of(0,255,9)]);
  assert.deepEqual(socket.sent, [Uint8Array.of(0,255,9)]);
  connection.close(); connection.close();
  socket.dispatchEvent(new MessageEvent("message", {data: new ArrayBuffer(0)}));
  assert.equal(seen.length, 2); assert.equal(closed.length, 0); assert.equal(socket.closes, 1);
  assert.throws(() => connection.send(bytes), /closed/);
  assert.throws(() => companionTransport("ws://example.com/device-rpc", "a".repeat(32)), /loopback/);
});
