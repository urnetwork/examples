// The Electron embed app's main process: the window, the ipc handlers and the
// app lifecycle around EmbedController (controller.mjs), which obtains the
// client JWT from the token server, starts the native companion in embed mode
// and reads its status (EMBED_CONTRACT.md, "Platform notes", Electron). The
// window runs sandboxed with context isolation and no Node integration; its
// preload script is its only bridge. The embedded device lives with the app:
// closing the window quits, and quitting closes the device first. Electron
// loads this module as its main script and its top-level code starts the app,
// with no import.meta.main check: Electron reads import.meta.main as false in
// its main script.

import {BrowserWindow, app, ipcMain, session} from "electron";
import path from "node:path";
import {fileURLToPath} from "node:url";
import {CompanionProcess, companionPath} from "./companion.mjs";
import {EmbedController} from "./controller.mjs";
import {ipcChannels} from "./ipc.mjs";
import {embedStateDir} from "./state.mjs";

const appDir = path.dirname(fileURLToPath(import.meta.url));
const productName = "URnetwork Embed";

// the longest token server URL and demo session the window may send
const settingLengthLimit = 4096;

// On Windows the installation state stays out of the roaming profile, under
// %LOCALAPPDATA% (EMBED_CONTRACT.md, "Installation state").
if (process.platform === "win32" && process.env.LOCALAPPDATA) {
  app.setPath("userData", path.join(process.env.LOCALAPPDATA, productName));
}

if (app.requestSingleInstanceLock()) {
  runApp();
} else {
  // another instance owns this installation's device; it shows its window
  app.quit();
}

// Wires the controller to the window and the app lifecycle.
function runApp() {
  const stateDir = embedStateDir(app.getPath("userData"));
  let window = null;
  // set once Quit has started and the device is closed
  let quitting = false;

  const controller = new EmbedController({
    stateDir,
    apiUrl: process.env.URNETWORK_API_URL || undefined,
    startCompanion: ({env}) => new CompanionProcess({
      command: companionPath({
        env: process.env,
        isPackaged: app.isPackaged,
        resourcesPath: process.resourcesPath,
        appPath: app.getAppPath(),
        platform: process.platform,
        arch: process.arch,
      }),
      env,
    }),
    onChange: payload => {
      if (window && !window.isDestroyed()) {
        window.webContents.send(ipcChannels.status, payload);
      }
    },
  });

  // The embed window: sandboxed, isolated, without Node, local content only.
  const createWindow = () => {
    window = new BrowserWindow({
      width: 720,
      height: 640,
      title: productName,
      show: false,
      webPreferences: {
        preload: path.join(appDir, "preload.cjs"),
        contextIsolation: true,
        sandbox: true,
        nodeIntegration: false,
      },
    });
    window.webContents.setWindowOpenHandler(() => ({action: "deny"}));
    window.webContents.on("will-navigate", event => event.preventDefault());
    window.once("ready-to-show", () => window?.show());
    window.on("closed", () => {
      window = null;
    });
    window.loadFile(path.join(appDir, "renderer", "index.html"));
  };

  // Accepts ipc only from the embed window's own page.
  const checkSender = event => {
    if (!window || event.sender !== window.webContents || !event.senderFrame?.url.startsWith("file:")) {
      throw new Error("ipc from an unknown sender");
    }
  };

  // A setting from the window: a string of bounded length.
  const settingText = value => {
    if (typeof value !== "string" || settingLengthLimit < value.length) {
      throw new Error("the token server settings are strings");
    }
    return value;
  };

  ipcMain.handle(ipcChannels.getStatus, event => {
    checkSender(event);
    return controller.payload();
  });
  ipcMain.handle(ipcChannels.start, event => {
    checkSender(event);
    return controller.start();
  });
  ipcMain.handle(ipcChannels.stop, event => {
    checkSender(event);
    return controller.stop();
  });
  ipcMain.handle(ipcChannels.saveTokenServer, (event, url, demoSession) => {
    checkSender(event);
    // the main process saves the session; the window never gets it back
    return controller.saveTokenServer(settingText(url), settingText(demoSession));
  });
  ipcMain.handle(ipcChannels.getLicenses, event => {
    checkSender(event);
    return controller.getLicenses();
  });

  app.on("second-instance", () => {
    if (window) {
      window.show();
      window.focus();
    }
  });
  // macOS: the Dock icon opens the window
  app.on("activate", () => {
    if (!window) {
      createWindow();
    }
  });
  // the device lives with the app: the last window closing quits
  app.on("window-all-closed", () => app.quit());
  app.on("before-quit", event => {
    if (quitting) {
      return;
    }
    event.preventDefault();
    quitting = true;
    controller.shutdown().finally(() => app.quit());
  });

  app.whenReady().then(() => {
    // the window loads only local files and needs no permission
    session.defaultSession.setPermissionRequestHandler((_webContents, _permission, callback) => callback(false));
    session.defaultSession.setPermissionCheckHandler(() => false);
    controller.begin();
    createWindow();
  });
}
