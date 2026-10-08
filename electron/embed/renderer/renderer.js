// The embed window's script. It shows each status payload from the main
// process (ipc.mjs statusPayload) and sends the controls' actions. It runs
// sandboxed without Node; the preload script's window.urnetworkEmbed is its
// only bridge. The demo session goes to the main process once, on Save, and
// never comes back; the client JWT never reaches the window.
"use strict";

const embed = window.urnetworkEmbed;

const elements = {
  tokenServerUrl: document.getElementById("token-server-url"),
  demoSession: document.getElementById("demo-session"),
  saveTokenServer: document.getElementById("save-token-server"),
  start: document.getElementById("start"),
  stop: document.getElementById("stop"),
  message: document.getElementById("message"),
  stateDir: document.getElementById("state-dir"),
  showLicenses: document.getElementById("show-licenses"),
  licenses: document.getElementById("licenses"),
  fields: {
    status: document.getElementById("status"),
    dataThisMonth: document.getElementById("data-this-month"),
    dataTotal: document.getElementById("data-total"),
    clientId: document.getElementById("client-id"),
    installationId: document.getElementById("installation-id"),
  },
};

// whether an action is waiting for the main process
let pending = false;
// the payload shown last
let shown = null;
// whether the user is editing the token server URL, so a payload does not
// replace it
let urlEdited = false;

// Shows one status payload.
function render(payload) {
  shown = payload;
  for (const [name, element] of Object.entries(elements.fields)) {
    element.textContent = payload.fields[name];
  }
  if (!urlEdited) {
    elements.tokenServerUrl.value = payload.tokenServerUrl;
  }
  elements.demoSession.placeholder = payload.sessionSaved ? "saved; leave empty to keep it" : "";
  elements.start.disabled = pending || payload.running;
  elements.stop.disabled = pending || !payload.running;
  elements.saveTokenServer.disabled = pending || payload.running;
  elements.tokenServerUrl.disabled = payload.running;
  elements.demoSession.disabled = payload.running;
  elements.showLicenses.disabled = pending || !payload.licensesAvailable;
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

// One license entry: its name, version and license, its notice, and its text
// behind a disclosure. Text only, never markup.
function licenseItem(license) {
  const item = document.createElement("li");
  const title = document.createElement("strong");
  title.textContent = [license.name, license.version].filter(Boolean).join(" ");
  item.appendChild(title);
  if (license.spdx) {
    const spdx = document.createElement("span");
    spdx.textContent = ` (${license.spdx})`;
    item.appendChild(spdx);
  }
  if (license.notice) {
    const notice = document.createElement("p");
    notice.textContent = license.notice;
    item.appendChild(notice);
  }
  if (license.text) {
    const details = document.createElement("details");
    const summary = document.createElement("summary");
    summary.textContent = "License text";
    const text = document.createElement("pre");
    text.textContent = license.text;
    details.appendChild(summary);
    details.appendChild(text);
    item.appendChild(details);
  }
  return item;
}

// Lists the licenses that /embed-status returned.
async function showLicenses() {
  const licenses = await embed.getLicenses();
  elements.licenses.replaceChildren(...licenses.map(licenseItem));
}

elements.tokenServerUrl.addEventListener("input", () => {
  urlEdited = true;
});
elements.saveTokenServer.addEventListener("click", () => {
  const url = elements.tokenServerUrl.value;
  const demoSession = elements.demoSession.value;
  // the session leaves the window here and is not kept
  elements.demoSession.value = "";
  urlEdited = false;
  act(() => embed.saveTokenServer(url, demoSession));
});
elements.start.addEventListener("click", () => act(() => embed.start()));
elements.stop.addEventListener("click", () => act(() => embed.stop()));
elements.showLicenses.addEventListener("click", () => {
  showLicenses().catch(error => {
    elements.message.textContent = error instanceof Error ? error.message : String(error);
  });
});

// pushed payloads show at once; the buttons stay disabled while an action waits
embed.onStatus(render);
embed.getStatus().then(render);
