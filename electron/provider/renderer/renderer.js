// The provider window's script. It shows each status payload from the main
// process (ipc.mjs statusPayload) and sends the controls' actions. It runs
// sandboxed without Node; the preload script's window.urnetworkProvider is its
// only bridge, and no credential ever reaches it.
"use strict";

const provider = window.urnetworkProvider;

const elements = {
  start: document.getElementById("start"),
  stop: document.getElementById("stop"),
  importClientJwt: document.getElementById("import-client-jwt"),
  startAtLogin: document.getElementById("start-at-login"),
  message: document.getElementById("message"),
  stateDir: document.getElementById("state-dir"),
  fields: {
    status: document.getElementById("status"),
    clientsServed: document.getElementById("clients-served"),
    dataProvided: document.getElementById("data-provided"),
    payoutWallet: document.getElementById("payout-wallet"),
  },
};

// whether an action is waiting for the main process
let pending = false;
// the payload shown last
let shown = null;

// Shows one status payload.
function render(payload) {
  shown = payload;
  for (const [name, element] of Object.entries(elements.fields)) {
    element.textContent = payload.fields[name];
  }
  elements.start.disabled = pending || payload.running;
  elements.stop.disabled = pending || !payload.running;
  elements.importClientJwt.disabled = pending || payload.running;
  elements.startAtLogin.checked = payload.startAtLogin;
  elements.startAtLogin.disabled = pending || !payload.startAtLoginAvailable;
  elements.message.textContent = payload.message;
  elements.stateDir.textContent = payload.stateDir;
}

// Runs one action of the main process and shows the payload it returns.
async function act(action) {
  pending = true;
  if (shown) {
    render(shown);
  }
  try {
    const payload = await action();
    pending = false;
    render(payload);
  } catch (error) {
    pending = false;
    if (shown) {
      render(shown);
    }
    elements.message.textContent = error instanceof Error ? error.message : String(error);
  }
}

elements.start.addEventListener("click", () => act(() => provider.start()));
elements.stop.addEventListener("click", () => act(() => provider.stop()));
elements.importClientJwt.addEventListener("click", () => act(() => provider.importClientJwt()));
elements.startAtLogin.addEventListener("change", () => {
  const enabled = elements.startAtLogin.checked;
  act(() => provider.setStartAtLogin(enabled));
});

// pushed payloads show at once; the buttons stay disabled while an action waits
provider.onStatus(render);
provider.getStatus().then(render);
