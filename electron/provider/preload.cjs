// The preload script of the provider window. The window runs sandboxed with
// context isolation and without Node integration, so this script is its only
// bridge: it exposes `window.urnetworkProvider` with the provider controls and
// status, and nothing else. A sandboxed preload cannot import local modules,
// so the channel names repeat ipc.mjs; ipc.test.mjs checks that both agree.

const {contextBridge, ipcRenderer} = require("electron");

// the channel names of ipc.mjs
const ipcChannels = {
  getStatus: "provider:get-status",
  start: "provider:start",
  stop: "provider:stop",
  importClientJwt: "provider:import-client-jwt",
  setStartAtLogin: "provider:set-start-at-login",
  status: "provider:status",
};

contextBridge.exposeInMainWorld("urnetworkProvider", {
  // The current status payload (ipc.mjs statusPayload).
  getStatus: () => ipcRenderer.invoke(ipcChannels.getStatus),
  // Starts providing; resolves with the new status payload.
  start: () => ipcRenderer.invoke(ipcChannels.start),
  // Stops providing; resolves with the new status payload.
  stop: () => ipcRenderer.invoke(ipcChannels.stop),
  // Asks the main process to pick and import a client.jwt file; resolves with
  // the new status payload. The token never reaches the window.
  importClientJwt: () => ipcRenderer.invoke(ipcChannels.importClientJwt),
  // Turns start at login on or off; resolves with the new status payload.
  setStartAtLogin: enabled => ipcRenderer.invoke(ipcChannels.setStartAtLogin, enabled === true),
  // Calls listener with each new status payload; returns a function that
  // removes it.
  onStatus: listener => {
    const statusListener = (_event, payload) => listener(payload);
    ipcRenderer.on(ipcChannels.status, statusListener);
    return () => ipcRenderer.removeListener(ipcChannels.status, statusListener);
  },
});
