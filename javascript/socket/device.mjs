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

export async function streamEcho(device, endpoint) {
  const transport = device.webTransport(endpoint, {timeoutMillis: 30000});
  let timer;
  try {
    await transport.ready;
    const stream = await transport.createBidirectionalStream();
    const writer = stream.writable.getWriter();
    const reader = stream.readable.getReader();
    const reply = reader.read();
    await writer.write(new TextEncoder().encode("hello from URnetwork"));
    await writer.close();
    const result = await Promise.race([reply, new Promise((_, reject) => {
      timer = setTimeout(() => reject(new Error("WebTransport echo timed out")), 30000);
    })]);
    return result.done ? "(stream ended)" : new TextDecoder().decode(result.value);
  } finally {clearTimeout(timer); transport.close(); await transport.closed.catch(() => {});}
}
