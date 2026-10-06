// The ipc between the main process and the window: the channel names and the
// status payload the window shows. The preload script repeats the channel
// names, because a sandboxed preload cannot import this module; ipc.test.mjs
// checks that both agree.

// Channel names. The window invokes the first five; the main process sends
// `status` whenever the shown values change.
export const ipcChannels = Object.freeze({
  getStatus: "provider:get-status",
  start: "provider:start",
  stop: "provider:stop",
  importClientJwt: "provider:import-client-jwt",
  setStartAtLogin: "provider:set-start-at-login",
  status: "provider:status",
});

// The status payload for the window: the contract's four fields as display
// text, the controls' state, a notice and the state directory to configure.
// Plain strings and booleans only, so it crosses ipc unchanged and never
// carries a credential.
export function statusPayload({fields, running, message, startAtLogin, startAtLoginAvailable, stateDir}) {
  return {
    fields: {
      status: String(fields.status),
      clientsServed: String(fields.clientsServed),
      dataProvided: String(fields.dataProvided),
      payoutWallet: String(fields.payoutWallet),
    },
    running: Boolean(running),
    message: String(message ?? ""),
    startAtLogin: Boolean(startAtLogin),
    startAtLoginAvailable: Boolean(startAtLoginAvailable),
    stateDir: String(stateDir ?? ""),
  };
}

// Whether two payloads show the same thing, so the main process sends only
// changes.
export function samePayload(a, b) {
  return JSON.stringify(a) === JSON.stringify(b);
}
