// The embed window's script, loaded as a module without a bundler. It calls the Rust core's commands through
// window.__TAURI__ (app.withGlobalTauri) and shows the embed view that the core sends every second as the
// 'embed-view' event. The core formats every value; this script only shows them. The demo session goes to the core
// once, on start, and never comes back: the view only says whether one is saved.

const { invoke } = window.__TAURI__.core;
const { listen } = window.__TAURI__.event;

const tokenServerUrlInput = document.getElementById('token-server-url');
const demoSessionInput = document.getElementById('demo-session');
const startButton = document.getElementById('start');
const stopButton = document.getElementById('stop');
const message = document.getElementById('message');
const licenses = document.getElementById('licenses');
const licensesText = document.getElementById('licenses-text');

/** Shows a problem under the fields, or hides the message when there is none. */
function showMessage(text) {
  message.textContent = text ?? '';
  message.hidden = !text;
}

/** Shows one embed view: the fields, the identity, the token server settings and the controls' state. */
function showView(view) {
  document.getElementById('status').textContent = view.status;
  document.getElementById('data-this-month').textContent = view.dataThisMonth;
  document.getElementById('data-total').textContent = view.dataTotal;
  document.getElementById('client-id').textContent = view.clientId;
  document.getElementById('installation-id').textContent = view.installationId;
  document.getElementById('state-dir').textContent = view.stateDir;
  if (!tokenServerUrlInput.value) {
    tokenServerUrlInput.value = view.tokenServerUrl;
  }
  demoSessionInput.placeholder = view.hasDemoSession ? 'saved; leave empty to keep it' : 'demo session token';
  startButton.disabled = view.running;
  stopButton.disabled = !view.running;
  tokenServerUrlInput.disabled = view.running;
  demoSessionInput.disabled = view.running;
  showMessage(view.message);
}

startButton.addEventListener('click', async () => {
  const view = await invoke('start_embed', {
    tokenServerUrl: tokenServerUrlInput.value,
    demoSession: demoSessionInput.value,
  });
  // the core saved the session; keep no copy in the page
  demoSessionInput.value = '';
  showView(view);
});

stopButton.addEventListener('click', async () => {
  showView(await invoke('stop_embed'));
});

licenses.addEventListener('toggle', async () => {
  if (!licenses.open || licensesText.dataset.loaded) {
    return;
  }
  try {
    licensesText.textContent = await invoke('licenses');
    licensesText.dataset.loaded = 'true';
  } catch (error) {
    licensesText.textContent = `could not read the licenses: ${error}`;
  }
});

await listen('embed-view', (event) => showView(event.payload));
showView(await invoke('embed_view'));
