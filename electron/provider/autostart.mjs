// Start at login. macOS and Windows register the app as a login item with
// app.setLoginItemSettings; Linux writes an XDG autostart entry. The app
// launched at login starts providing and stays in the tray: Windows and Linux
// pass `--login` on its command line, and macOS reports the launch with
// wasOpenedAtLogin. On macOS only the packaged app can be a login item: the
// login item names the app bundle, which in development is Electron itself.

import fs from "node:fs";
import os from "node:os";
import path from "node:path";

// the command line argument of a launch at login
export const loginArgument = "--login";

// the XDG autostart entry's file name
export const autostartFileName = "urnetwork-electron-provider.desktop";

// The command line that launches this app at login: the executable (the
// AppImage itself when the app runs from one), the app directory when it runs
// unpackaged with `electron .`, then loginArgument.
export function loginLaunchCommand({isPackaged, execPath, appPath, appImagePath}) {
  if (isPackaged) {
    return [appImagePath || execPath, loginArgument];
  }
  return [execPath, appPath, loginArgument];
}

// One Exec argument of a desktop entry: quoted when it has a reserved
// character, with the quoting escapes and then the string escapes the Desktop
// Entry Specification asks for, and "%" doubled so no field code is read.
export function desktopExecArgument(argument) {
  if (/[\n\r]/.test(argument)) {
    throw new Error("a desktop entry argument cannot contain a line break");
  }
  const fieldSafe = argument.replaceAll("%", "%%");
  if (fieldSafe !== "" && !/[\s"'\\><~|&;$*?#()`]/.test(fieldSafe)) {
    return fieldSafe;
  }
  const quoted = fieldSafe.replace(/["`$\\]/g, character => `\\${character}`);
  return `"${quoted.replaceAll("\\", "\\\\")}"`;
}

// The XDG autostart entry that runs command at login.
export function desktopEntry({name, command}) {
  return `[Desktop Entry]
Type=Application
Version=1.0
Name=${name}
Comment=Provides with URnetwork from login
Exec=${command.map(desktopExecArgument).join(" ")}
Terminal=false
X-GNOME-Autostart-enabled=true
`;
}

// The XDG autostart directory: $XDG_CONFIG_HOME/autostart, or
// ~/.config/autostart when XDG_CONFIG_HOME is unset or not absolute.
export function xdgAutostartDir(env, homeDir) {
  const configHome = env.XDG_CONFIG_HOME && path.isAbsolute(env.XDG_CONFIG_HOME)
    ? env.XDG_CONFIG_HOME
    : path.join(homeDir, ".config");
  return path.join(configHome, "autostart");
}

// A Windows command line argument, quoted when it has a space; the login item
// stores the arguments as one command line.
export function windowsArgument(argument) {
  return /[\s"]/.test(argument) ? `"${argument.replaceAll('"', '\\"')}"` : argument;
}

// The start at login setting of this app on this OS. app is Electron's app
// (or a test double with the same login item methods).
export class StartAtLogin {
  // platform, env, homeDir and argv default to this process.
  constructor({app, name, platform = process.platform, env = process.env, homeDir = os.homedir(), argv = process.argv}) {
    this.app = app;
    this.name = name;
    this.platform = platform;
    this.env = env;
    this.homeDir = homeDir;
    this.argv = argv;
  }

  // The command line this app launches with at login.
  command() {
    return loginLaunchCommand({
      isPackaged: this.app.isPackaged,
      execPath: process.execPath,
      appPath: this.app.getAppPath(),
      appImagePath: this.platform === "linux" ? this.env.APPIMAGE : undefined,
    });
  }

  // The Windows login item's executable and arguments.
  windowsLoginItem() {
    const [executable, ...args] = this.command();
    return {path: executable, args: args.map(windowsArgument)};
  }

  // The XDG autostart entry's path.
  autostartPath() {
    return path.join(xdgAutostartDir(this.env, this.homeDir), autostartFileName);
  }

  // Whether this OS and build can start the app at login.
  available() {
    switch (this.platform) {
      case "darwin":
        return this.app.isPackaged;
      case "win32":
      case "linux":
        return true;
      default:
        return false;
    }
  }

  // Whether the app starts at login now.
  enabled() {
    switch (this.platform) {
      case "darwin":
        return this.app.getLoginItemSettings().openAtLogin;
      case "win32":
        return this.app.getLoginItemSettings(this.windowsLoginItem()).openAtLogin;
      case "linux":
        return fs.existsSync(this.autostartPath());
      default:
        return false;
    }
  }

  // Turns start at login on or off.
  setEnabled(enabled) {
    if (!this.available()) {
      throw new Error("start at login is not available for this build");
    }
    switch (this.platform) {
      case "darwin":
        this.app.setLoginItemSettings({openAtLogin: enabled});
        return;
      case "win32":
        this.app.setLoginItemSettings({openAtLogin: enabled, ...this.windowsLoginItem()});
        return;
      case "linux": {
        const autostartPath = this.autostartPath();
        if (!enabled) {
          fs.rmSync(autostartPath, {force: true});
          return;
        }
        fs.mkdirSync(path.dirname(autostartPath), {recursive: true});
        const tempPath = `${autostartPath}.${process.pid}.tmp`;
        fs.writeFileSync(tempPath, desktopEntry({name: this.name, command: this.command()}));
        fs.renameSync(tempPath, autostartPath);
        return;
      }
    }
  }

  // Whether this launch is a launch at login.
  openedAtLogin() {
    if (this.platform === "darwin") {
      return Boolean(this.app.getLoginItemSettings().wasOpenedAtLogin);
    }
    return this.argv.includes(loginArgument);
  }
}
