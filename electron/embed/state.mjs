// Installation state of the Electron embed app, kept in one private `embed`
// directory inside the app's user data directory (EMBED_CONTRACT.md,
// "Installation state"). The companion uses it as its state directory:
//
//   - client.jwt: the scoped client JWT that the token server answered; the
//     companion rewrites it when the SDK refreshes the token.
//   - instance-id: this installation's UUID, created on first start and kept
//     for the life of the installation; the installation ID sent to the token
//     server.
//   - token-server.json: {"url", "session"}, the token server origin and the
//     demo session token, saved from the window. The session never goes back
//     to the window.
//   - logs/: the SDK's bounded log files, written by the companion.
//
// Every file is replaced atomically with owner-only permissions. On POSIX the
// directory and its files must not be accessible to group or others; Windows
// relies on the access control of the user's profile directory, under
// %LOCALAPPDATA%. Every error here is a configuration error: restarting does
// not fix it.

import {randomBytes, randomUUID} from "node:crypto";
import fs from "node:fs";
import path from "node:path";

export const clientJwtFileName = "client.jwt";
export const instanceIdFileName = "instance-id";
export const tokenServerFileName = "token-server.json";
export const logDirName = "logs";

// the state directory's name inside the app's user data directory
export const stateDirName = "embed";

// the largest state file the app reads
const stateFileByteLimit = 64 * 1024;

const uuidPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

// A configuration or credential problem that a restart does not fix: the
// console examples exit with 78 for it, this app shows it.
export class ConfigurationError extends Error {
  // A configuration error with the message to show.
  constructor(message) {
    super(message);
    this.name = "ConfigurationError";
  }
}

// The private state directory inside the app's user data directory.
export function embedStateDir(userDataDir) {
  return path.join(userDataDir, stateDirName);
}

// Whether a mode lets group or others in. Windows modes do not carry the
// profile's access control, so they are not checked there.
function accessibleToOthers(mode, platform) {
  return platform !== "win32" && (mode & 0o077) !== 0;
}

// The lowercase form of a UUID, or "" for anything else.
function normalizeId(text) {
  return typeof text === "string" && uuidPattern.test(text) ? text.toLowerCase() : "";
}

// The state directory must be an existing absolute directory, private to its
// owner on POSIX.
export function checkStateDir(stateDir, platform = process.platform) {
  if (!stateDir || !path.isAbsolute(stateDir)) {
    throw new ConfigurationError("the embed state directory must be an absolute path");
  }
  let info;
  try {
    info = fs.statSync(stateDir);
  } catch (error) {
    throw new ConfigurationError(`state directory: ${error.message}`);
  }
  if (!info.isDirectory()) {
    throw new ConfigurationError(`${stateDir} is not a directory`);
  }
  if (accessibleToOthers(info.mode, platform)) {
    throw new ConfigurationError(`the state directory must be private to its owner (chmod 700 "${stateDir}")`);
  }
}

// Creates the state directory with owner-only permissions on first start, then
// checks it. An existing directory that others can access is refused, not
// repaired.
export function ensureStateDir(stateDir, platform = process.platform) {
  if (!stateDir || !path.isAbsolute(stateDir)) {
    throw new ConfigurationError("the embed state directory must be an absolute path");
  }
  fs.mkdirSync(stateDir, {recursive: true, mode: 0o700});
  checkStateDir(stateDir, platform);
}

// Reads a regular, private state file of bounded size. A symlink is refused
// so that the credential cannot be redirected to another file; where the OS
// has O_NOFOLLOW the file is checked through the descriptor it reads.
export function readPrivateFile(filePath, platform = process.platform) {
  const noFollow = fs.constants.O_NOFOLLOW ?? 0;
  let fd;
  try {
    const linkInfo = fs.lstatSync(filePath);
    if (!linkInfo.isFile()) {
      throw new Error("not a regular file");
    }
    fd = fs.openSync(filePath, fs.constants.O_RDONLY | noFollow);
  } catch (error) {
    if (error.code === "ELOOP") {
      throw new Error("not a regular file");
    }
    throw error;
  }
  try {
    const info = fs.fstatSync(fd);
    if (!info.isFile()) {
      throw new Error("not a regular file");
    }
    if (stateFileByteLimit < info.size) {
      throw new Error("file is too large");
    }
    if (accessibleToOthers(info.mode, platform)) {
      throw new Error("file must be private to its owner (chmod 600)");
    }
    return fs.readFileSync(fd);
  } finally {
    fs.closeSync(fd);
  }
}

// Replaces a state file atomically: a private temporary file in the same
// directory is written, synced and renamed over the old file.
export function writePrivateFile(filePath, data) {
  const tempPath = path.join(path.dirname(filePath), `.${path.basename(filePath)}.${randomBytes(8).toString("hex")}`);
  let fd = fs.openSync(tempPath, "wx", 0o600);
  try {
    fs.fchmodSync(fd, 0o600);
    fs.writeFileSync(fd, data);
    fs.fsyncSync(fd);
    fs.closeSync(fd);
    fd = undefined;
    fs.renameSync(tempPath, filePath);
  } catch (error) {
    if (fd !== undefined) {
      fs.closeSync(fd);
    }
    fs.rmSync(tempPath, {force: true});
    throw error;
  }
}

// The client_id claim of a scoped client JWT. This checks the token's shape
// and claim only; the SDK and the server verify the token itself.
export function parseClientJwtClientId(clientJwt) {
  const notJwt = "the client JWT is not a JWT; the token server must answer a scoped client JWT";
  const parts = typeof clientJwt === "string" ? clientJwt.split(".") : [];
  if (parts.length !== 3 || parts.some(part => part === "")) {
    throw new ConfigurationError(notJwt);
  }
  const payload = parts[1].replace(/=+$/, "");
  if (!/^[A-Za-z0-9_-]+$/.test(payload)) {
    throw new ConfigurationError(notJwt);
  }
  let claims;
  try {
    claims = JSON.parse(Buffer.from(payload, "base64url").toString("utf8"));
  } catch {
    claims = null;
  }
  if (claims === null || typeof claims !== "object" || typeof claims.client_id !== "string" || claims.client_id === "") {
    throw new ConfigurationError("the client JWT has no client_id claim: a network JWT is not a client JWT");
  }
  const clientId = normalizeId(claims.client_id);
  if (clientId === "") {
    throw new ConfigurationError("the client JWT has an invalid client_id claim");
  }
  return clientId;
}

// Reads client.jwt, trimmed, and its client_id claim.
export function loadClientJwt(stateDir) {
  let data;
  try {
    data = readPrivateFile(path.join(stateDir, clientJwtFileName));
  } catch (error) {
    throw new ConfigurationError(`read ${clientJwtFileName}: ${error.message}`);
  }
  const clientJwt = data.toString("utf8").trim();
  return {clientJwt, clientId: parseClientJwtClientId(clientJwt)};
}

// Saves a scoped client JWT as client.jwt.
export function saveClientJwt(stateDir, clientJwt) {
  writePrivateFile(path.join(stateDir, clientJwtFileName), `${clientJwt}\n`);
}

// Reads instance-id, creating it on first start.
export function loadOrCreateInstanceId(stateDir) {
  const filePath = path.join(stateDir, instanceIdFileName);
  let data;
  try {
    data = readPrivateFile(filePath);
  } catch (error) {
    if (error.code !== "ENOENT") {
      throw new ConfigurationError(`read ${instanceIdFileName}: ${error.message}`);
    }
    const instanceId = randomUUID();
    writePrivateFile(filePath, `${instanceId}\n`);
    return instanceId;
  }
  const instanceId = normalizeId(data.toString("utf8").trim());
  if (instanceId === "") {
    throw new ConfigurationError(`${instanceIdFileName} does not hold a UUID`);
  }
  return instanceId;
}

// Reads token-server.json: {url, session}, or null before the window saved
// one. The caller checks the url.
export function loadTokenServerConfig(stateDir) {
  let data;
  try {
    data = readPrivateFile(path.join(stateDir, tokenServerFileName));
  } catch (error) {
    if (error.code === "ENOENT") {
      return null;
    }
    throw new ConfigurationError(`read ${tokenServerFileName}: ${error.message}`);
  }
  let config;
  try {
    config = JSON.parse(data.toString("utf8"));
  } catch {
    config = null;
  }
  if (config === null || typeof config !== "object" || typeof config.url !== "string" || typeof config.session !== "string" ||
      config.url === "" || config.session === "") {
    throw new ConfigurationError(`${tokenServerFileName} must hold a token server url and a demo session`);
  }
  return {url: config.url, session: config.session};
}

// Saves token-server.json.
export function saveTokenServerConfig(stateDir, {url, session}) {
  writePrivateFile(path.join(stateDir, tokenServerFileName), JSON.stringify({url, session}));
}
