import {randomBytes} from "node:crypto";

function deferred() { let resolve; const promise = new Promise(done => {resolve = done;}); return {promise, resolve}; }
async function bounded(promise, millis, label) {
  let timer;
  try { return await Promise.race([promise, new Promise((_, reject) => {timer = setTimeout(() => reject(new Error(label + " timed out")), millis);})]); }
  finally { clearTimeout(timer); }
}
export function validateCommand(args) {
  if (!args.length || !["self", "peers", "watch", "send"].includes(args[0]) ||
      (args[0] === "send" ? args.length < 3 : args.length !== 1)) {
    throw new Error("usage: --self-test | --version | self | peers | watch | send CLIENT_ID TEXT");
  }
  if (args[0] === "send" && !/^[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}$/i.test(args[1])) throw new Error("CLIENT_ID must be a UUID");
}

// No socket emulation: the application handles discrete raw subprotocol frames.
// The same command controller is shared by JS and TS; each supplies its codec.
export async function runMessages(device, codec, args, options = {}) {
  validateCommand(args);
  const {log = console.log, error = console.error, timeoutMillis = 10000, signal} = options;
  const ready = deferred(), peersReady = deferred(), ack = deferred(), stopped = deferred();
  let subscription, pending, currentPeers, receivedPeers = false;
  let remoteConnected = device.getRemoteConnected();
  let lastPeers;
  const printPeers = peers => {
    currentPeers = remoteConnected ? peers : null;
    const value = !remoteConnected || peers == null ? "peers: unavailable" : "peers: " + JSON.stringify(peers);
    if (value !== lastPeers) {log(value); lastPeers = value;}
    if (remoteConnected && peers != null) {receivedPeers = true; peersReady.resolve();}
  };
  const remoteSub = device.addRemoteChangeListener(connected => {remoteConnected = connected; if (!connected) printPeers(null);});
  // Register live updates before the snapshot. The browser RPC resync also
  // replays the latest snapshot after registration reaches the companion.
  const peersSub = device.addNetworkPeersChangeListener(printPeers);
  const stop = () => stopped.resolve();
  signal?.addEventListener("abort", stop, {once: true});
  if (signal?.aborted) stop();
  try {
    const expires = Date.now() + 30000;
    while (!device.getRemoteConnected()) {
      if (device.getSyncError()) throw new Error(device.getSyncError());
      if (Date.now() >= expires) throw new Error("Native companion did not sync within 30 seconds; check its process, token, client JWT, and instance ID");
      if (signal?.aborted) return;
      await new Promise(resolve => setTimeout(resolve, 50));
    }
    remoteConnected = true;
    subscription = await device.enableSubprotocol(codec.SUBPROTOCOL, async message => {
      const owned = message.bytes.slice(); // Copy immediately before any await.
      await ready.promise;
      let frame;
      try {frame = codec.decode(owned);} catch (err) {error("rejected frame from " + message.sourceClientId + ": " + err.message); return;}
      if (frame.kind === 1) {
        log("text from " + message.sourceClientId + " id=" + frame.id + " " + JSON.stringify(frame.text));
        if (!await subscription.send(message.sourceClientId, codec.encode(2, frame.id))) throw new Error("SDK did not enqueue ACK");
      } else if (pending && message.sourceClientId.toLowerCase() === pending.clientId && frame.id === pending.id) {
        log("ACK from " + message.sourceClientId + " id=" + frame.id);
        ack.resolve();
      }
      // An ACK is never itself acknowledged.
    });
    ready.resolve();
    log("self: " + device.getClientId());
    if (args[0] === "self") return;
    printPeers(device.getNetworkPeers());
    const failed = subscription.closed.then(() => {throw new Error("Subprotocol subscription closed");});
    void failed.catch(() => {});
    if (args[0] === "peers") {
      if (!receivedPeers) await bounded(Promise.race([peersReady.promise, failed, stopped.promise]), timeoutMillis, "Peer snapshot");
    } else if (args[0] === "watch") {
      await Promise.race([failed, stopped.promise]);
    } else {
      const clientId = args[1].toLowerCase();
      if (!receivedPeers) await bounded(Promise.race([peersReady.promise, failed, stopped.promise]), timeoutMillis, "Peer snapshot");
      const connected = Array.isArray(currentPeers?.connected) ? currentPeers.connected : [];
      if (!connected.some(peer => typeof peer?.clientId === "string" && peer.clientId.toLowerCase() === clientId)) {
        throw new Error("Selected peer is not currently connected: " + args[1]);
      }
      const protocols = await Promise.race([subscription.querySubprotocols(clientId, timeoutMillis), failed]);
      if (protocols === null) throw new Error("Peer subprotocol query was unanswered or unavailable");
      if (!protocols.includes(codec.SUBPROTOCOL)) throw new Error("Peer does not advertise subprotocol " + codec.SUBPROTOCOL);
      let id; do {id = randomBytes(8).readBigUInt64BE();} while (id === 0n);
      const frame = codec.encode(1, id, args.slice(2).join(" "));
      pending = {clientId, id};
      if (!await subscription.send(clientId, frame)) throw new Error("SDK did not enqueue TEXT");
      log("sent id=" + id + "; waiting for ACK");
      await bounded(Promise.race([ack.promise, failed, stopped.promise]), timeoutMillis, "ACK");
    }
  } finally {
    pending = undefined;
    signal?.removeEventListener("abort", stop);
    try {
      await subscription?.close();
      await subscription?.closed.catch(() => {});
    } finally {peersSub(); remoteSub();}
  }
}
