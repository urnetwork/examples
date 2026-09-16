// DeviceRemote needs backend-issued hosted proxy configuration in addition to
// the scoped client JWT. The caller supplies the SDK to keep one WASM runtime.
export function clientConfig(environment, hosted) {
  const byJwt = environment.URNETWORK_CLIENT_JWT;
  const instanceId = environment.URNETWORK_INSTANCE_ID;
  if (!byJwt || !instanceId) throw new Error("Set URNETWORK_CLIENT_JWT and a persistent URNETWORK_INSTANCE_ID");
  return {...hosted, byJwt, instanceId};
}

export async function openDevice(URNetwork, config, wasmOptions = {}) {
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
