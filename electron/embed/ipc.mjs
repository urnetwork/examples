// The ipc between the main process and the window: the channel names, the
// status payload the window shows and the licenses it lists. The preload
// script repeats the channel names, because a sandboxed preload cannot import
// this module; ipc.test.mjs checks that both agree.

// Channel names. The window invokes the first five; the main process sends
// `status` whenever the shown values change.
export const ipcChannels = Object.freeze({
  getStatus: "embed:get-status",
  start: "embed:start",
  stop: "embed:stop",
  saveTokenServer: "embed:save-token-server",
  getLicenses: "embed:get-licenses",
  status: "embed:status",
});

// The status payload for the window: the contract's status fields as display
// text, the client and installation IDs, the controls' state, a notice, the
// saved token server URL (never the demo session, only whether one is saved),
// the state directory and whether the licenses can be listed. Plain strings
// and booleans only, so it crosses ipc unchanged and never carries a
// credential.
export function statusPayload({fields, running, message, tokenServerUrl, sessionSaved, stateDir, licensesAvailable}) {
  return {
    fields: {
      status: String(fields.status),
      dataThisMonth: String(fields.dataThisMonth),
      dataTotal: String(fields.dataTotal),
      clientId: String(fields.clientId ?? ""),
      installationId: String(fields.installationId ?? ""),
    },
    running: Boolean(running),
    message: String(message ?? ""),
    tokenServerUrl: String(tokenServerUrl ?? ""),
    sessionSaved: Boolean(sessionSaved),
    stateDir: String(stateDir ?? ""),
    licensesAvailable: Boolean(licensesAvailable),
  };
}

// Whether two payloads show the same thing, so the main process sends only
// changes.
export function samePayload(a, b) {
  return JSON.stringify(a) === JSON.stringify(b);
}

// The licenses for the window: the SDK's LicenseInfo entries as plain strings,
// in the order GetLicenses returns them (data attributions first).
export function licensesForWindow(licenses) {
  if (!Array.isArray(licenses)) {
    return [];
  }
  const text = value => typeof value === "string" ? value : "";
  return licenses
    .filter(entry => entry !== null && typeof entry === "object")
    .map(entry => ({
      name: text(entry.Name),
      version: text(entry.Version),
      kind: text(entry.Kind),
      spdx: text(entry.Spdx),
      url: text(entry.Url),
      copyright: text(entry.Copyright),
      notice: text(entry.Notice),
      text: text(entry.Text),
    }));
}
