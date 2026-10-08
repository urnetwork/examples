// The preload script of the embed window. The window runs sandboxed with
// context isolation and without Node integration, so this script is its only
// bridge: it exposes `window.urnetworkEmbed` with the controls, the status and
// the licenses, and nothing else. A sandboxed preload cannot import local
// modules, so the channel names repeat ipc.mjs; ipc.test.mjs checks that both
// agree.

const {contextBridge, ipcRenderer} = require("electron");

// the channel names of ipc.mjs
const ipcChannels = {
  getStatus: "embed:get-status",
  start: "embed:start",
  stop: "embed:stop",
  saveTokenServer: "embed:save-token-server",
  getLicenses: "embed:get-licenses",
  status: "embed:status",
};

contextBridge.exposeInMainWorld("urnetworkEmbed", {
  // The current status payload (ipc.mjs statusPayload).
  getStatus: () => ipcRenderer.invoke(ipcChannels.getStatus),
  // Obtains the client JWT and starts the device; resolves with the new
  // status payload.
  start: () => ipcRenderer.invoke(ipcChannels.start),
  // Closes the device; resolves with the new status payload.
  stop: () => ipcRenderer.invoke(ipcChannels.stop),
  // Saves the token server URL and demo session in the main process; resolves
  // with the new status payload, which never carries the session.
  saveTokenServer: (url, demoSession) => ipcRenderer.invoke(ipcChannels.saveTokenServer, String(url), String(demoSession)),
  // The licenses (ipc.mjs licensesForWindow).
  getLicenses: () => ipcRenderer.invoke(ipcChannels.getLicenses),
  // Calls listener with each new status payload; returns a function that
  // removes it.
  onStatus: listener => {
    const statusListener = (_event, payload) => listener(payload);
    ipcRenderer.on(ipcChannels.status, statusListener);
    return () => ipcRenderer.removeListener(ipcChannels.status, statusListener);
  },
});
