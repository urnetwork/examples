// The opaque transport reaches a full local provider. Production browser apps
// can supply the same SDK transport contract from their native extension.
export function companionTransport(url, token, WebSocketClass = globalThis.WebSocket) {
  const endpoint = new URL(url);
  if (!["ws:", "wss:"].includes(endpoint.protocol) || !["127.0.0.1", "[::1]"].includes(endpoint.hostname) || endpoint.username || endpoint.password) {
    throw new Error("The example companion URL must use ws(s) and a numeric loopback host");
  }
  if (typeof token !== "string" || token.length < 32) throw new Error("Set URNETWORK_COMPANION_TOKEN to the companion's random token");
  endpoint.searchParams.set("token", token);
  return {open(callbacks) {
    const ws = new WebSocketClass(endpoint.href);
    ws.binaryType = "arraybuffer";
    let closed = false;
    const opened = () => callbacks.opened();
    const message = event => {
      if (closed) return;
      if (!(event.data instanceof ArrayBuffer)) { terminate("Companion sent a non-binary frame"); return; }
      callbacks.message(new Uint8Array(event.data).slice());
    };
    const ended = () => terminate("Companion connection closed");
    const failed = () => terminate("Companion connection failed");
    const terminate = (reason, notify = true) => {
      if (closed) return;
      closed = true;
      ws.removeEventListener("open", opened); ws.removeEventListener("message", message);
      ws.removeEventListener("close", ended); ws.removeEventListener("error", failed);
      ws.close(); if (notify) callbacks.closed(reason);
    };
    ws.addEventListener("open", opened); ws.addEventListener("message", message);
    ws.addEventListener("close", ended); ws.addEventListener("error", failed);
    return {
      send(bytes) {
        if (closed || ws.readyState !== 1) throw new Error("Companion connection is closed");
        if (ws.bufferedAmount + bytes.byteLength > 1024*1024) { terminate("Companion send queue exceeded 1 MiB"); throw new Error("Companion send queue overflow"); }
        ws.send(bytes.slice());
      },
      // SDK-initiated close releases its callback functions synchronously.
      // Do not reenter callbacks.closed or retain socket event listeners.
      close() { terminate("SDK closed companion transport", false); },
    };
  }};
}

export async function openMessageDevice(URNetwork, config, token, wasmOptions = {}) {
  for (const key of ["apiUrl", "platformUrl", "byJwt", "instanceId", "companionUrl"]) {
    if (typeof config[key] !== "string" || !config[key]) throw new Error("Message device configuration requires " + key);
  }
  const transport = companionTransport(config.companionUrl, token);
  const sdk = await URNetwork.init(wasmOptions);
  let device;
  try {
    device = sdk.createExtensionDeviceRemote({...config, transport});
    // Return before first sync so the caller can install peer listeners.
    // Browser listener registration otherwise forces a new RPC generation.
    return {sdk, device, close() {device.close(); sdk.close();}};
  } catch (error) {device?.close(); sdk.close(); throw error;}
}
