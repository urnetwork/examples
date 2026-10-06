// The companion's device read through @urnetwork/sdk, with a fake sdk: the
// extension remote's options, the values read for the status, the provider
// contract rows counted as clients served, and closing.

import {test} from "node:test";
import assert from "node:assert/strict";
import {CompanionDevice, openCompanionDevice, providedByteCount} from "../device.mjs";
import {ClientsServed} from "../status.mjs";

const token = "a".repeat(64);

// A fake URNetwork whose extension remote and controllers are scripted by
// the test.
function fakeSdk({failProviderContractDetails = false} = {}) {
  const events = [];
  const state = {
    provideEnabled: true,
    providePaused: false,
    packetStats: null,
    rows: [],
    rowsListener: null,
  };
  const contractViewController = {
    start: () => events.push("contract start"),
    getProviderPacketStats: () => state.packetStats,
    close: async () => events.push("contract close"),
  };
  const contractDetailsViewController = {
    start: () => events.push("rows start"),
    getContractRows: () => state.rows,
    addContractRowsListener: listener => {
      state.rowsListener = listener;
      return () => {
        state.rowsListener = null;
        events.push("rows listener removed");
      };
    },
    close: async () => events.push("rows close"),
  };
  const device = {
    getProvideEnabled: () => state.provideEnabled,
    getProvidePaused: () => state.providePaused,
    openContractViewController: () => contractViewController,
    openProviderContractDetailsViewController: () => {
      if (failProviderContractDetails) {
        throw new Error("synthetic open failure");
      }
      return contractDetailsViewController;
    },
    close: async () => events.push("device close"),
  };
  const URNetwork = {
    init: async () => ({
      createExtensionDeviceRemote: options => {
        state.options = options;
        return device;
      },
    }),
  };
  return {URNetwork, state, events};
}

// Provider packet stats with the given relayed byte counts.
function packetStats(remoteEgressByteCount, remoteIngressByteCount) {
  return {remoteEgressByteCount, remoteIngressByteCount, localEgressByteCount: 9, localIngressByteCount: 9, blockEgressByteCount: 9, blockIngressByteCount: 9};
}

// Opens a device on the fake sdk.
function open(sdk, clientsServed) {
  return openCompanionDevice({
    URNetwork: sdk.URNetwork,
    apiUrl: "https://api.example",
    platformUrl: "wss://connect.example",
    clientJwt: "e30.e30.test",
    instanceId: "22222222-2222-2222-2222-222222222222",
    deviceRpcUrl: "ws://127.0.0.1:54321/device-rpc",
    token,
    clientsServed,
    WebSocketClass: class {},
  });
}

test("data provided is the relayed bytes in both directions, 0 without stats", () => {
  assert.equal(providedByteCount(null), 0);
  assert.equal(providedByteCount(packetStats(5, 7)), 12);
});

test("the device opens an extension remote on the companion's rpc as the installation's client", async () => {
  const sdk = fakeSdk();
  const device = await open(sdk, new ClientsServed());
  assert.ok(device instanceof CompanionDevice);
  assert.equal(sdk.state.options.apiUrl, "https://api.example");
  assert.equal(sdk.state.options.platformUrl, "wss://connect.example");
  assert.equal(sdk.state.options.byJwt, "e30.e30.test");
  assert.equal(sdk.state.options.instanceId, "22222222-2222-2222-2222-222222222222");
  assert.equal(typeof sdk.state.options.transport.open, "function");
  assert.deepEqual(sdk.events, ["contract start", "rows start"]);
});

test("a read counts the provider rows and keeps the largest data total of the device", async () => {
  const sdk = fakeSdk();
  const clientsServed = new ClientsServed();
  const device = await open(sdk, clientsServed);
  assert.deepEqual(device.read(), {provideEnabled: true, providePaused: false, dataProvidedByteCount: 0});

  sdk.state.packetStats = packetStats(13000000, 2342);
  sdk.state.rows = [{clientId: "33333333-3333-3333-3333-333333333333", receiveContracts: [{contractId: "55555555-5555-5555-5555-555555555555"}], sendContracts: []}];
  assert.equal(device.read().dataProvidedByteCount, 13002342);
  assert.deepEqual(clientsServed.count(), {count: 1, atLimit: false});

  // between the device's pushes the controller reads null stats
  sdk.state.packetStats = null;
  sdk.state.providePaused = true;
  assert.deepEqual(device.read(), {provideEnabled: true, providePaused: true, dataProvidedByteCount: 13002342});

  // a row change counts at once, without a read
  sdk.state.rows = [{clientId: "44444444-4444-4444-4444-444444444444", receiveContracts: [], sendContracts: [{contractId: "66666666-6666-6666-6666-666666666666"}]}];
  sdk.state.rowsListener();
  assert.deepEqual(clientsServed.count(), {count: 2, atLimit: false});
});

test("closing releases the rows listener, the controllers, then the remote", async () => {
  const sdk = fakeSdk();
  const device = await open(sdk, new ClientsServed());
  sdk.events.length = 0;
  await device.close();
  assert.deepEqual(sdk.events, ["rows listener removed", "rows close", "contract close", "device close"]);
});

test("a failed open closes what it opened", async () => {
  const sdk = fakeSdk({failProviderContractDetails: true});
  await assert.rejects(open(sdk, new ClientsServed()), /synthetic open failure/);
  assert.deepEqual(sdk.events, ["contract start", "contract close", "device close"]);
});
