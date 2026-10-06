// Start at login on each OS, with a fake Electron app: the login item
// settings on macOS and Windows, the XDG autostart entry on Linux (written in
// a temporary home), and the detection of a launch at login.

import {test} from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import {
  StartAtLogin,
  desktopEntry,
  desktopExecArgument,
  loginArgument,
  loginLaunchCommand,
  windowsArgument,
  xdgAutostartDir,
} from "../autostart.mjs";

// A fake Electron app that keeps login item settings in memory.
function fakeApp({isPackaged, wasOpenedAtLogin = false}) {
  const calls = [];
  let openAtLogin = false;
  return {
    calls,
    isPackaged,
    getAppPath: () => "/opt/app example/resources/app",
    getLoginItemSettings: options => {
      calls.push({getLoginItemSettings: options});
      return {openAtLogin, wasOpenedAtLogin};
    },
    setLoginItemSettings: settings => {
      calls.push({setLoginItemSettings: settings});
      openAtLogin = settings.openAtLogin;
    },
  };
}

test("the login command runs the packaged app, an AppImage, or electron with the app directory", () => {
  assert.deepEqual(loginLaunchCommand({isPackaged: true, execPath: "/opt/URnetwork Provider/urnetwork-provider", appPath: "/ignored"}), ["/opt/URnetwork Provider/urnetwork-provider", loginArgument]);
  assert.deepEqual(loginLaunchCommand({isPackaged: true, execPath: "/tmp/.mount_x/urnetwork-provider", appPath: "/ignored", appImagePath: "/home/user/Apps/provider.AppImage"}), ["/home/user/Apps/provider.AppImage", loginArgument]);
  assert.deepEqual(loginLaunchCommand({isPackaged: false, execPath: "/src/node_modules/electron/dist/electron", appPath: "/src/electron/provider"}), ["/src/node_modules/electron/dist/electron", "/src/electron/provider", loginArgument]);
});

test("desktop entry arguments are quoted and escaped as the desktop entry specification says", () => {
  const cases = [
    {argument: "/usr/bin/provider", text: "/usr/bin/provider"},
    {argument: "--login", text: "--login"},
    {argument: "/opt/URnetwork Provider/provider", text: '"/opt/URnetwork Provider/provider"'},
    {argument: "/opt/a$b", text: '"/opt/a\\\\$b"'},
    {argument: '/opt/a"b', text: '"/opt/a\\\\"b"'},
    {argument: "/opt/a\\b", text: '"/opt/a\\\\\\\\b"'},
    {argument: "/opt/a`b", text: '"/opt/a\\\\`b"'},
    {argument: "/opt/100%", text: "/opt/100%%"},
    {argument: "", text: '""'},
  ];
  for (const c of cases) {
    assert.equal(desktopExecArgument(c.argument), c.text, JSON.stringify(c.argument));
  }
  assert.throws(() => desktopExecArgument("/opt/a\nb"));
});

test("the autostart entry runs the login command in the xdg config directory", () => {
  assert.equal(xdgAutostartDir({}, "/home/user"), "/home/user/.config/autostart");
  assert.equal(xdgAutostartDir({XDG_CONFIG_HOME: "/data/config"}, "/home/user"), "/data/config/autostart");
  assert.equal(xdgAutostartDir({XDG_CONFIG_HOME: "relative"}, "/home/user"), "/home/user/.config/autostart");
  assert.equal(desktopEntry({name: "URnetwork Provider", command: ["/opt/URnetwork Provider/provider", "--login"]}), `[Desktop Entry]
Type=Application
Version=1.0
Name=URnetwork Provider
Comment=Provides with URnetwork from login
Exec="/opt/URnetwork Provider/provider" --login
Terminal=false
X-GNOME-Autostart-enabled=true
`);
});

test("linux writes and removes the autostart entry and reads --login", {skip: process.platform === "win32"}, t => {
  const homeDir = fs.mkdtempSync(path.join(os.tmpdir(), "ur-electron-autostart-test-"));
  t.after(() => fs.rmSync(homeDir, {recursive: true, force: true}));
  const app = fakeApp({isPackaged: true});
  const startAtLogin = new StartAtLogin({app, name: "URnetwork Provider", platform: "linux", env: {}, homeDir, argv: ["/opt/provider", loginArgument]});
  assert.ok(startAtLogin.available());
  assert.equal(startAtLogin.enabled(), false);
  startAtLogin.setEnabled(true);
  assert.ok(startAtLogin.enabled());
  const entry = fs.readFileSync(path.join(homeDir, ".config", "autostart", "urnetwork-electron-provider.desktop"), "utf8");
  assert.match(entry, /^Exec=.* --login$/m);
  startAtLogin.setEnabled(false);
  assert.equal(startAtLogin.enabled(), false);
  assert.ok(startAtLogin.openedAtLogin());
  assert.equal(new StartAtLogin({app, name: "URnetwork Provider", platform: "linux", env: {}, homeDir, argv: ["/opt/provider"]}).openedAtLogin(), false);
  assert.deepEqual(app.calls, []);
});

test("windows registers the login item with the login argument", () => {
  const app = fakeApp({isPackaged: false});
  const startAtLogin = new StartAtLogin({app, name: "URnetwork Provider", platform: "win32", env: {}, homeDir: "C:\\Users\\user", argv: ["electron.exe", "."]});
  assert.ok(startAtLogin.available());
  startAtLogin.setEnabled(true);
  assert.ok(startAtLogin.enabled());
  const loginItem = {path: process.execPath, args: ['"/opt/app example/resources/app"', loginArgument]};
  assert.deepEqual(app.calls, [
    {setLoginItemSettings: {openAtLogin: true, ...loginItem}},
    {getLoginItemSettings: loginItem},
  ]);
  assert.equal(startAtLogin.openedAtLogin(), false);
  assert.equal(windowsArgument("C:\\Program Files\\URnetwork Provider\\provider.exe"), '"C:\\Program Files\\URnetwork Provider\\provider.exe"');
});

test("macos uses the login item of the packaged app and wasOpenedAtLogin", () => {
  const development = new StartAtLogin({app: fakeApp({isPackaged: false}), name: "URnetwork Provider", platform: "darwin"});
  assert.equal(development.available(), false);
  assert.throws(() => development.setEnabled(true));
  const app = fakeApp({isPackaged: true, wasOpenedAtLogin: true});
  const startAtLogin = new StartAtLogin({app, name: "URnetwork Provider", platform: "darwin", argv: ["/Applications/URnetwork Provider.app/Contents/MacOS/URnetwork Provider"]});
  startAtLogin.setEnabled(true);
  assert.ok(startAtLogin.enabled());
  assert.ok(startAtLogin.openedAtLogin());
  assert.deepEqual(app.calls[0], {setLoginItemSettings: {openAtLogin: true}});
});
