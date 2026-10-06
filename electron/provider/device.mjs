// The companion's provider device, read with @urnetwork/sdk over the
// companion's loopback device rpc: whether provide is enabled and paused, the
// provider packet stats (data provided) and the provider contract rows
// (clients served). The sdk's extension remote reads its synchronized copy of
// the device, so no read here waits on the rpc. The companion serves the rpc
// only once the provider is connected, and the remote cannot change the
// provide settings.

import {companionTransport} from "./companion.mjs";

// The platform of the `ur.network`/`main` network space (migration host bringyour.com).
export const defaultPlatformUrl = "wss://connect.bringyour.com";

// The total of one provider packet stats object: bytes relayed for clients in
// both directions since the device started, 0 for null stats.
export function providedByteCount(packetStats) {
  if (!packetStats) {
    return 0;
  }
  return packetStats.remoteEgressByteCount + packetStats.remoteIngressByteCount;
}

// One open device remote with its contract view controllers. read() returns
// {provideEnabled, providePaused, dataProvidedByteCount} and counts the
// contract rows' peers into clientsServed.
export class CompanionDevice {
  // Takes the opened remote and controllers; openCompanionDevice builds them.
  constructor({device, contractViewController, contractDetailsViewController, clientsServed}) {
    this.device = device;
    this.contractViewController = contractViewController;
    this.contractDetailsViewController = contractDetailsViewController;
    this.clientsServed = clientsServed;
    // The total only grows while one device runs. The extension remote's
    // contract view controller reads the stats as null between the device's
    // pushes, so the largest total read stands until a larger one arrives.
    this.dataProvidedByteCount = 0;
    this.removeRowsListener = contractDetailsViewController.addContractRowsListener(() => this.countRows());
  }

  // Counts the peers of the provider contract rows shown now.
  countRows() {
    this.clientsServed.addContractRows(this.contractDetailsViewController.getContractRows());
  }

  // The device values the window shows, read now.
  read() {
    this.countRows();
    this.dataProvidedByteCount = Math.max(
      this.dataProvidedByteCount,
      providedByteCount(this.contractViewController.getProviderPacketStats()),
    );
    return {
      provideEnabled: this.device.getProvideEnabled(),
      providePaused: this.device.getProvidePaused(),
      dataProvidedByteCount: this.dataProvidedByteCount,
    };
  }

  // Closes the controllers, then the remote. The companion keeps providing
  // until it is stopped.
  async close() {
    this.removeRowsListener();
    await Promise.allSettled([this.contractDetailsViewController.close(), this.contractViewController.close()]);
    await this.device.close();
  }
}

// Opens the companion's device: an sdk extension remote over /device-rpc with
// the companion token, as the installation's client (the companion checks
// that the client id and instance id are its own).
export async function openCompanionDevice({URNetwork, apiUrl, platformUrl = defaultPlatformUrl, clientJwt, instanceId, deviceRpcUrl, token, clientsServed, WebSocketClass = globalThis.WebSocket}) {
  const sdk = await URNetwork.init();
  const device = sdk.createExtensionDeviceRemote({
    apiUrl,
    platformUrl,
    byJwt: clientJwt,
    instanceId,
    transport: companionTransport(deviceRpcUrl, token, WebSocketClass),
  });
  const viewControllers = [];
  try {
    const contractViewController = device.openContractViewController();
    viewControllers.push(contractViewController);
    contractViewController.start();
    const contractDetailsViewController = device.openProviderContractDetailsViewController();
    viewControllers.push(contractDetailsViewController);
    const companionDevice = new CompanionDevice({device, contractViewController, contractDetailsViewController, clientsServed});
    contractDetailsViewController.start();
    return companionDevice;
  } catch (error) {
    await Promise.allSettled(viewControllers.map(viewController => viewController.close()));
    await device.close();
    throw error;
  }
}
