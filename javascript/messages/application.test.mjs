import {test} from "node:test";
import assert from "node:assert/strict";
import * as javascript from "./codec.mjs";
import * as typescript from "../../typescript/messages/codec.ts";
import {runMessages} from "./application.mjs";

const peer = "00000000-0000-0000-0000-000000000001";
const other = "00000000-0000-0000-0000-000000000002";
function fakeDevice() {
  let listener, finish;
  const closed = new Promise(resolve => {finish = resolve;});
  const events = [], sent = [];
  const subscription = {
    closed, close: async () => {events.push("close"); finish();},
    querySubprotocols: async () => [4096],
    send: async (destination, bytes) => {sent.push({destination, bytes: bytes.slice()}); return true;},
  };
  const device = {
    getClientId: () => other, getRemoteConnected: () => true,
    getNetworkPeers: () => {events.push("snapshot"); return {connected: [{clientId: peer}, {clientId: other}], disconnectedCount: 2};},
    addRemoteChangeListener: () => () => events.push("remove-remote"),
    addNetworkPeersChangeListener: () => {events.push("subscribe-peers"); return () => events.push("remove-peers");},
    enableSubprotocol: async (_id, callback) => {listener = callback; return subscription;},
  };
  return {device, subscription, events, sent, deliver: (sourceClientId, bytes) => listener({sourceClientId, bytes})};
}
for (const [language, codec] of Object.entries({javascript, typescript})) {
  test(language + " sender queries support and only accepts the selected peer's matching ACK", async () => {
    const f = fakeDevice(), logs = [];
    f.subscription.send = async (destination, bytes) => {
      f.sent.push({destination, bytes});
      const frame = codec.decode(bytes);
      await f.deliver(other, codec.encode(2, frame.id));
      await f.deliver(peer, codec.encode(2, frame.id === 1n ? 2n : 1n));
      assert.equal(logs.filter(s => s.startsWith("ACK")).length, 0);
      await f.deliver(peer, codec.encode(2, frame.id));
      return true;
    };
    await runMessages(f.device, codec, ["send", peer, "hé🙂"], {log: s => logs.push(s)});
    assert.equal(codec.decode(f.sent[0].bytes).text, "hé🙂");
    assert.equal(f.sent.length, 1); // ACKs were never ACKed.
    assert.equal(logs.filter(s => s.startsWith("ACK")).length, 1);
    assert.ok(f.events.indexOf("subscribe-peers") < f.events.indexOf("snapshot"));
    assert.ok(f.events.includes("remove-peers"));
  });
  test(language + " watcher copies callback input and acknowledges accepted TEXT only", async () => {
    const f = fakeDevice(), signal = new AbortController(), errors = [];
    const run = runMessages(f.device, codec, ["watch"], {signal: signal.signal, log: () => {}, error: s => errors.push(s)});
    await new Promise(resolve => setImmediate(resolve));
    const bytes = codec.encode(1, 1n, "hi");
    const receiving = f.deliver(peer, bytes); bytes.fill(0); await receiving;
    assert.equal(Buffer.from(f.sent[0].bytes).toString("hex"), "55524d53010200000000000000000001");
    await f.deliver(peer, Uint8Array.of(0));
    await f.deliver(peer, codec.encode(2, 1n));
    assert.equal(f.sent.length, 1); assert.equal(errors.length, 1);
    signal.abort(); await run;
  });
  test(language + " unanswered support query never sends", async () => {
    const f = fakeDevice(); f.subscription.querySubprotocols = async () => null;
    await assert.rejects(runMessages(f.device, codec, ["send", peer, "hi"], {log: () => {}}), /unanswered/);
    assert.equal(f.sent.length, 0);
  });
  test(language + " refuses a destination absent from the live peer snapshot", async () => {
    const f = fakeDevice();
    f.device.getNetworkPeers = () => ({connected: [{clientId: other}], disconnectedCount: 1});
    let queried = false;
    f.subscription.querySubprotocols = async () => {queried = true; return [4096];};
    await assert.rejects(runMessages(f.device, codec, ["send", peer, "hi"], {log: () => {}}), /not currently connected/);
    assert.equal(queried, false);
    assert.equal(f.sent.length, 0);
  });
}
