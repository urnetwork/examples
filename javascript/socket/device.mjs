import {URNetwork} from "@urnetwork/sdk";

export async function openDevice(config, wasmOptions = {}) {
  for (const key of ["apiUrl", "platformUrl", "byJwt", "proxyUrl", "signedProxyId", "instanceId"]) {
    if (typeof config[key] !== "string" || !config[key]) throw new Error("Device configuration requires " + key);
  }
  const sdk = await URNetwork.init(wasmOptions);
  const device = sdk.createPlatformDeviceRemote(config);
  try {
    const expires = Date.now() + 30000;
    while (!device.getRemoteConnected()) {
      if (Date.now() >= expires) throw new Error("Hosted Device did not connect within 30 seconds");
      await new Promise(resolve => setTimeout(resolve, 50));
    }
    device.setConnectLocation({bestAvailable: true});
    return {sdk, device, close() {device.close(); sdk.close();}};
  } catch (error) {device.close(); sdk.close(); throw error;}
}

export async function directSocketEcho(device, protocol, endpoint, timeoutMillis = 30000) {
  if (protocol !== "tcp" && protocol !== "udp") throw new TypeError("Expected tcp or udp");
  const address = new URL(protocol + "://" + endpoint);
  if (!address.hostname || !address.port || address.username || address.password || address.search || address.hash || (address.pathname && address.pathname !== "/")) throw new TypeError("Expected host:port or [IPv6]:port");
  const host = address.hostname.replace(/^\[|\]$/g, ""), port = Number(address.port);
  const {TCPSocket, UDPSocket} = device.directSockets;
  const socket = protocol === "udp"
    ? new UDPSocket({remoteAddress: host, remotePort: port})
    : new TCPSocket(host, port);
  const {readable, writable} = await socket.opened;
  const reader = readable.getReader(), writer = writable.getWriter();
  let timer;
  try {
    const echo = async () => {
      const payload = new TextEncoder().encode("hello from URnetwork");
      await writer.write(protocol === "udp" ? {data: payload} : payload);
      if (protocol === "tcp") await writer.close();
      const parts = []; let received = 0;
      while (received < payload.length) {
        const result = await reader.read();
        if (result.done) throw new Error("Echo closed before the whole reply");
        const data = protocol === "udp" ? result.value.data : result.value;
        parts.push(data); received += data.length;
        if (protocol === "udp") break; // One UDPMessage is one datagram, even if empty.
      }
      const reply = new Uint8Array(received); let offset = 0;
      for (const part of parts) {reply.set(part, offset); offset += part.length;}
      return new TextDecoder().decode(reply);
    };
    return await Promise.race([echo(), new Promise((_, reject) => {
      timer = setTimeout(() => reject(new Error("Direct Sockets echo timed out")), timeoutMillis);
    })]);
  } finally {
    clearTimeout(timer);
    // Cancel/abort pending I/O before releasing the standard stream locks.
    await Promise.allSettled([reader.cancel(), writer.abort()]);
    reader.releaseLock(); writer.releaseLock();
    await socket.close(); await socket.closed;
  }
}
