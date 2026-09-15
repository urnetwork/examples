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
