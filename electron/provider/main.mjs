// The Electron provider's main process: the window, the tray, the ipc
// handlers and the app lifecycle around ProviderController (controller.mjs),
// which starts the native companion in provider mode and reads its status
// (PROVIDER_CONTRACT.md, "Platform notes", Electron). The window runs
// sandboxed with context isolation and no Node integration; its preload
// script is its only bridge. Closing the window keeps providing in the tray;
// Quit stops providing first. Electron loads this module as its main script
// and its top-level code starts the app, with no import.meta.main check:
// Electron 44 reads import.meta.main as false in its main script, and Node
// before 24.2 does not define it.

import {BrowserWindow, Menu, Tray, app, dialog, ipcMain, nativeImage, session} from "electron";
import fs from "node:fs";
import path from "node:path";
import {fileURLToPath} from "node:url";
import {StartAtLogin} from "./autostart.mjs";
import {CompanionProcess, companionPath} from "./companion.mjs";
import {ProviderController} from "./controller.mjs";
import {trayIconBitmap} from "./icon.mjs";
import {ipcChannels} from "./ipc.mjs";
import {loadClientJwt, providerStateDir} from "./state.mjs";
import {PayoutWallet} from "./wallet.mjs";

const appDir = path.dirname(fileURLToPath(import.meta.url));
const productName = "URnetwork Provider";

// the largest file the import control reads as a client JWT
const clientJwtFileByteLimit = 64 * 1024;

// On Windows the installation state stays out of the roaming profile, under
// %LOCALAPPDATA% (PROVIDER_CONTRACT.md, "Installation state").
if (process.platform === "win32" && process.env.LOCALAPPDATA) {
  app.setPath("userData", path.join(process.env.LOCALAPPDATA, productName));
}

if (app.requestSingleInstanceLock()) {
  runApp();
} else {
  // another instance runs this installation's provider; it shows its window
  app.quit();
}

// Wires the provider controller to the window, the tray and the app lifecycle.
function runApp() {
  const stateDir = providerStateDir(app.getPath("userData"));
  const startAtLogin = new StartAtLogin({app, name: productName});
  let window = null;
  let tray = null;
  // the status and controls the tray menu shows, so it is rebuilt only when they change
  let trayMenuKey = "";
  // set once Quit has started, so the window closes instead of hiding
  let quitting = false;

  const controller = new ProviderController({
    stateDir,
    startAtLogin,
    payoutWallet: new PayoutWallet({readClientJwt: () => loadClientJwt(stateDir).clientJwt}),
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
    onChange: (payload, line) => {
      if (window && !window.isDestroyed()) {
        window.webContents.send(ipcChannels.status, payload);
      }
      if (tray) {
        tray.setToolTip(line);
        updateTrayMenu(payload);
      }
    },
  });

  // Shows the window, creating it when needed.
  const showWindow = () => {
    if (window) {
      window.show();
      window.focus();
    } else {
      createWindow();
    }
    app.dock?.show();
  };

  // The provider window: sandboxed, isolated, without Node, local content only.
  const createWindow = () => {
    window = new BrowserWindow({
      width: 680,
      height: 560,
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
    window.on("close", event => {
      // closing the window keeps providing in the tray
      if (!quitting) {
        event.preventDefault();
        window.hide();
        app.dock?.hide();
      }
    });
    window.on("closed", () => {
      window = null;
    });
    window.loadFile(path.join(appDir, "renderer", "index.html"));
  };

  // The tray menu for the current payload. Start opens the window, where
  // the consent disclaimer is next to the Start control.
  const updateTrayMenu = payload => {
    const key = `${payload.fields.status}|${payload.running}`;
    if (key === trayMenuKey) {
      return;
    }
    trayMenuKey = key;
    tray.setContextMenu(Menu.buildFromTemplate([
      {label: `Status: ${payload.fields.status}`, enabled: false},
      {type: "separator"},
      {label: "Open URnetwork Provider", click: showWindow},
      payload.running
        ? {label: "Stop providing", click: () => controller.stop()}
        : {label: "Start providing…", click: showWindow},
      {type: "separator"},
      {label: "Quit", click: () => app.quit()},
    ]));
  };

  // Accepts ipc only from the provider window's own page.
  const checkSender = event => {
    if (!window || event.sender !== window.webContents || !event.senderFrame?.url.startsWith("file:")) {
      throw new Error("ipc from an unknown sender");
    }
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
  ipcMain.handle(ipcChannels.importClientJwt, async event => {
    checkSender(event);
    // the main process reads the file; the token never reaches the window
    const result = await dialog.showOpenDialog(window, {
      title: "Import the scoped client JWT from your backend",
      properties: ["openFile"],
    });
    if (result.canceled || result.filePaths.length !== 1) {
      return controller.payload();
    }
    const filePath = result.filePaths[0];
    let text;
    try {
      if (clientJwtFileByteLimit < fs.statSync(filePath).size) {
        return controller.showMessage("that file is too large to hold a client JWT");
      }
      text = fs.readFileSync(filePath, "utf8");
    } catch (error) {
      return controller.showMessage(`could not read ${path.basename(filePath)}: ${error.message}`);
    }
    return controller.importClientJwt(text);
  });
  ipcMain.handle(ipcChannels.setStartAtLogin, (event, enabled) => {
    checkSender(event);
    if (typeof enabled !== "boolean") {
      throw new Error("start at login takes a boolean");
    }
    return controller.setStartAtLogin(enabled);
  });

  app.on("second-instance", showWindow);
  // macOS: the Dock icon opens the window
  app.on("activate", showWindow);
  // the tray keeps the app and the provider running without a window
  app.on("window-all-closed", () => {});
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
    const icon = nativeImage.createFromBitmap(trayIconBitmap(32), {width: 32, height: 32, scaleFactor: 2});
    if (process.platform === "darwin") {
      icon.setTemplateImage(true);
    }
    tray = new Tray(icon);
    tray.setToolTip(productName);
    tray.on("click", showWindow);
    updateTrayMenu(controller.payload());
    // a launch at login provides in the tray without a window
    const openedAtLogin = startAtLogin.openedAtLogin();
    if (openedAtLogin) {
      app.dock?.hide();
    } else {
      showWindow();
    }
    controller.begin({startProviding: openedAtLogin}).catch(error => controller.showMessage(error.message));
  });
}
