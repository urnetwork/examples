// The provider window's script, loaded as a module without a bundler. It calls the Rust core's commands through
// window.__TAURI__ (app.withGlobalTauri) and shows the provider view that the core sends every second as the
// 'provider-view' event. The core formats every value; this script only shows them.

const { invoke } = window.__TAURI__.core;
const { listen } = window.__TAURI__.event;

const startButton = document.getElementById('start');
const stopButton = document.getElementById('stop');
const startAtLoginBox = document.getElementById('start-at-login');
const message = document.getElementById('message');

/** Shows a problem under the fields, or hides the message when there is none. */
function showMessage(text) {
  message.textContent = text ?? '';
  message.hidden = !text;
}

/** Shows one provider view: the four fields, the controls' state and any message. */
function showView(view) {
  document.getElementById('status').textContent = view.status;
  document.getElementById('clients-served').textContent = view.clientsServed;
  document.getElementById('data-provided').textContent = view.dataProvided;
  document.getElementById('payout-wallet').textContent = view.payoutWallet;
  document.getElementById('state-dir').textContent = view.stateDir;
  startButton.disabled = view.running;
  stopButton.disabled = !view.running;
  showMessage(view.message);
}

startButton.addEventListener('click', async () => {
  showView(await invoke('start_providing'));
});

stopButton.addEventListener('click', async () => {
  showView(await invoke('stop_providing'));
});

startAtLoginBox.addEventListener('change', async () => {
  try {
    startAtLoginBox.checked = await invoke('set_start_at_login', { enabled: startAtLoginBox.checked });
  } catch (error) {
    startAtLoginBox.checked = !startAtLoginBox.checked;
    showMessage(`could not change start at login: ${error}`);
  }
});

await listen('provider-view', (event) => showView(event.payload));
showView(await invoke('provider_view'));
try {
  startAtLoginBox.checked = await invoke('start_at_login');
} catch (error) {
  showMessage(`could not read start at login: ${error}`);
}
