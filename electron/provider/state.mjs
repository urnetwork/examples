// Installation state of the Electron provider, kept in one private `provider`
// directory inside the app's user data directory (PROVIDER_CONTRACT.md,
// "Installation state"). The companion uses it as its state directory:
//
//   - client.jwt: the scoped client credential from the developer's backend,
//     imported by the user; the companion rewrites it when the sdk refreshes
//     the token.
//   - instance-id: this installation's UUID, created here on first start and
//     kept for the life of the installation.
//   - identity.json: the provider identity, created and read by the companion.
//   - logs/: the sdk's bounded log files, written by the companion.
//
// Every file is replaced atomically with owner-only permissions. On POSIX the
// directory and its files must not be accessible to group or others; Windows
// relies on the access control of the user's profile directory. Mirrors
// go/provider/state.go. Every error here is a configuration error: restarting
// does not fix it.

import {randomBytes, randomUUID} from "node:crypto";
import fs from "node:fs";
import path from "node:path";

export const clientJwtFileName = "client.jwt";
export const instanceIdFileName = "instance-id";
export const identityFileName = "identity.json";
export const logDirName = "logs";

// the state directory's name inside the app's user data directory
export const stateDirName = "provider";

// the largest state file the app reads
const stateFileByteLimit = 64 * 1024;

// A configuration or credential problem that a restart does not fix: the
// console examples exit with 78 for it, this app shows it and does not start.
export class ProviderConfigError extends Error {
  // A configuration error with the message to show.
  constructor(message) {
    super(message);
    this.name = "ProviderConfigError";
  }
}

// The private state directory inside the app's user data directory.
export function providerStateDir(userDataDir) {
  return path.join(userDataDir, stateDirName);
}

// Whether a mode lets group or others in. Windows modes do not carry the
// profile's access control, so they are not checked there.
function accessibleToOthers(mode, platform) {
  return platform !== "win32" && (mode & 0o077) !== 0;
}

// The state directory must be an existing absolute directory, private to its
// owner on POSIX.
export function checkStateDir(stateDir, platform = process.platform) {
  if (!stateDir) {
    throw new ProviderConfigError("the provider state directory is not set");
  }
  if (!path.isAbsolute(stateDir)) {
    throw new ProviderConfigError("the provider state directory must be an absolute path");
  }
  let info;
  try {
    info = fs.statSync(stateDir);
  } catch (error) {
    throw new ProviderConfigError(`state directory: ${error.message}`);
  }
  if (!info.isDirectory()) {
    throw new ProviderConfigError(`${stateDir} is not a directory`);
  }
  if (accessibleToOthers(info.mode, platform)) {
    throw new ProviderConfigError(`the state directory must be private to its owner (chmod 700 "${stateDir}")`);
  }
}

// Creates the state directory with owner-only permissions on first start, then
// checks it. An existing directory that others can access is refused, not
// repaired.
export function ensureStateDir(stateDir, platform = process.platform) {
  if (!path.isAbsolute(stateDir)) {
    throw new ProviderConfigError("the provider state directory must be an absolute path");
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

// The canonical form of a UUID, as the sdk's ParseId reads it: 36 characters
// with the separators at 8, 13, 18 and 23, or 32 hex digits. Throws for
// anything else.
export function parseId(text) {
  let hex = text;
  if (typeof text === "string" && text.length === 36) {
    hex = text.slice(0, 8) + text.slice(9, 13) + text.slice(14, 18) + text.slice(19, 23) + text.slice(24);
  }
  if (typeof hex !== "string" || !/^[0-9a-fA-F]{32}$/.test(hex)) {
    throw new Error(`cannot parse UUID ${text}`);
  }
  hex = hex.toLowerCase();
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

// The client_id claim of a scoped client JWT. This checks the token's shape
// and claim only; the sdk and the server verify the token itself.
export function parseClientJwtClientId(clientJwt) {
  const notJwt = new ProviderConfigError("client.jwt does not hold a JWT; import the scoped client JWT from your backend");
  const parts = String(clientJwt).split(".");
  if (parts.length !== 3 || parts.some(part => part === "")) {
    throw notJwt;
  }
  // unpadded base64url, as Go's base64.RawURLEncoding reads it
  const payload = parts[1].replace(/=+$/, "");
  if (!/^[A-Za-z0-9_-]+$/.test(payload) || payload.length % 4 === 1) {
    throw notJwt;
  }
  let claims;
  try {
    claims = JSON.parse(Buffer.from(payload, "base64url").toString("utf8"));
  } catch {
    throw notJwt;
  }
  const clientId = claims !== null && typeof claims === "object" && !Array.isArray(claims) ? claims.client_id : undefined;
  if (typeof clientId !== "string" || clientId === "") {
    throw new ProviderConfigError("client.jwt has no client_id claim; import a scoped client JWT, not a network JWT");
  }
  try {
    return parseId(clientId);
  } catch {
    throw new ProviderConfigError("client.jwt has an invalid client_id claim");
  }
}

// Reads instance-id, creating it on first start. An installation keeps one
// instance id for its lifetime.
export function loadOrCreateInstanceId(stateDir, platform = process.platform) {
  const instanceIdPath = path.join(stateDir, instanceIdFileName);
  let data;
  try {
    data = readPrivateFile(instanceIdPath, platform);
  } catch (error) {
    if (error.code !== "ENOENT") {
      throw new ProviderConfigError(`read ${instanceIdFileName} from the state directory: ${error.message}`);
    }
    const instanceId = randomUUID();
    writePrivateFile(instanceIdPath, `${instanceId}\n`);
    return instanceId;
  }
  try {
    return parseId(data.toString("utf8").trim());
  } catch {
    throw new ProviderConfigError(`${instanceIdFileName} does not hold a UUID`);
  }
}

// Reads and checks client.jwt.
export function loadClientJwt(stateDir, platform = process.platform) {
  let data;
  try {
    data = readPrivateFile(path.join(stateDir, clientJwtFileName), platform);
  } catch (error) {
    if (error.code === "ENOENT") {
      throw new ProviderConfigError(`${clientJwtFileName} is missing; import the scoped client JWT from your backend`);
    }
    throw new ProviderConfigError(`read ${clientJwtFileName} from the state directory: ${error.message}`);
  }
  const clientJwt = data.toString("utf8").trim();
  return {clientJwt, clientId: parseClientJwtClientId(clientJwt)};
}

// Loads the installation state before the companion starts, creating
// instance-id on first start: {stateDir, clientJwt, clientId, instanceId}.
export function loadProviderConfig(stateDir, platform = process.platform) {
  checkStateDir(stateDir, platform);
  const {clientJwt, clientId} = loadClientJwt(stateDir, platform);
  const instanceId = loadOrCreateInstanceId(stateDir, platform);
  return {stateDir, clientJwt, clientId, instanceId};
}

// Saves a scoped client JWT as client.jwt after checking its client_id claim,
// and returns the client id. The token itself is never shown or logged.
export function importClientJwt(stateDir, text, platform = process.platform) {
  checkStateDir(stateDir, platform);
  const clientJwt = String(text).trim();
  const clientId = parseClientJwtClientId(clientJwt);
  writePrivateFile(path.join(stateDir, clientJwtFileName), `${clientJwt}\n`);
  return clientId;
}
