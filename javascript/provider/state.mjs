// Installation state for one provider install, kept in one private directory
// named by URNETWORK_PROVIDER_STATE_DIR (PROVIDER_CONTRACT.md, "Installation
// state"), with the rules of the Go provider's state.go:
//
// - client.jwt: the scoped client credential that the developer's backend
//   issued for this installation. The developer writes it; the native
//   companion rewrites it whenever the SDK refreshes the token.
// - instance-id: this installation's UUID, created on first run.
// - identity.json: the provider identity, which the native companion creates
//   on first run and reuses, so the provider keeps one identity across
//   restarts. This program only checks it.
// - logs/: the SDK's bounded log files, written by the companion.
//
// This program checks the whole state before it starts the companion, so a
// configuration error stops it with exit code 78. Files are replaced
// atomically with owner-only permissions. On POSIX the directory and its files
// must not be accessible to group or others; Windows relies on the access
// control of the user's profile directory.

import {randomBytes, randomUUID} from "node:crypto";
import {lstat, open, readFile, rename, stat, unlink} from "node:fs/promises";
import {basename, dirname, isAbsolute, join} from "node:path";
import {normalizeId} from "./status.mjs";

export const clientJwtFileName = "client.jwt";
export const instanceIdFileName = "instance-id";
export const identityFileName = "identity.json";

// the largest state file the app reads
const stateFileByteLimit = 64 * 1024;

const providerIdentityVersion = 1;

// Checks of POSIX permission bits do not apply on Windows.
const posix = process.platform !== "win32";

// A configuration problem that restarting does not fix (exit code 78).
export class ConfigurationError extends Error {}

// The state directory must be an existing absolute directory, private to its
// owner on POSIX.
export async function checkStateDir(stateDir) {
  if (!stateDir) {
    throw new ConfigurationError("set URNETWORK_PROVIDER_STATE_DIR to this installation's private state directory");
  }
  if (!isAbsolute(stateDir)) {
    throw new ConfigurationError("URNETWORK_PROVIDER_STATE_DIR must be an absolute path");
  }
  let info;
  try {
    info = await stat(stateDir);
  } catch (error) {
    throw new ConfigurationError(`state directory: ${error.message}`);
  }
  if (!info.isDirectory()) {
    throw new ConfigurationError("URNETWORK_PROVIDER_STATE_DIR is not a directory");
  }
  if (posix && (info.mode & 0o077) !== 0) {
    throw new ConfigurationError("the state directory must be private to its owner (chmod 700)");
  }
}

// Reads a regular, private state file of bounded size. A symlink is refused
// so that the credential cannot be redirected to another file. A missing file
// rejects with the ENOENT error.
export async function readPrivateFile(path) {
  const info = await lstat(path);
  if (!info.isFile()) {
    throw new Error("not a regular file");
  }
  if (stateFileByteLimit < info.size) {
    throw new Error("file is too large");
  }
  if (posix && (info.mode & 0o077) !== 0) {
    throw new Error("file must be private to its owner (chmod 600)");
  }
  return readFile(path);
}

// Replaces a state file atomically: a private temporary file in the same
// directory is written, synced and renamed over the old file.
export async function writePrivateFile(path, data) {
  const tempPath = join(dirname(path), `.${basename(path)}.${randomBytes(6).toString("hex")}`);
  const file = await open(tempPath, "wx", 0o600);
  try {
    try {
      await file.writeFile(data);
      await file.sync();
    } finally {
      await file.close();
    }
    await rename(tempPath, path);
  } catch (error) {
    await unlink(tempPath).catch(() => {});
    throw error;
  }
}

// The client_id claim of a scoped client JWT. This checks the token's shape
// and claim only; the SDK and the server verify the token itself.
export function parseClientJwtClientId(clientJwt) {
  const notJwt = "client.jwt does not hold a JWT; write the scoped client JWT from your backend";
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
    throw new ConfigurationError("client.jwt has no client_id claim; write a scoped client JWT, not a network JWT");
  }
  const clientId = normalizeId(claims.client_id);
  if (clientId === "") {
    throw new ConfigurationError("client.jwt has an invalid client_id claim");
  }
  return clientId;
}

// Reads the scoped client JWT, trimmed, and its client_id claim.
export async function loadClientJwt(stateDir) {
  let data;
  try {
    data = await readPrivateFile(join(stateDir, clientJwtFileName));
  } catch (error) {
    throw new ConfigurationError(`read ${clientJwtFileName} from the state directory: ${error.message}`);
  }
  const clientJwt = data.toString("utf8").trim();
  return {clientJwt, clientId: parseClientJwtClientId(clientJwt)};
}

// Reads instance-id, creating it on first run. An installation keeps one
// instance id for its lifetime.
export async function loadOrCreateInstanceId(stateDir) {
  const path = join(stateDir, instanceIdFileName);
  let data;
  try {
    data = await readPrivateFile(path);
  } catch (error) {
    if (error?.code !== "ENOENT") {
      throw new ConfigurationError(`read ${instanceIdFileName} from the state directory: ${error.message}`);
    }
    const instanceId = randomUUID();
    try {
      await writePrivateFile(path, `${instanceId}\n`);
    } catch (writeError) {
      throw new ConfigurationError(`write ${instanceIdFileName}: ${writeError.message}`);
    }
    return instanceId;
  }
  const instanceId = normalizeId(data.toString("utf8").trim());
  if (instanceId === "") {
    throw new ConfigurationError(`${instanceIdFileName} does not hold a UUID`);
  }
  return instanceId;
}

// Strict standard base64, as Go decodes identity.json byte fields.
function decodeBase64(text) {
  if (typeof text !== "string" || text.length % 4 !== 0 || !/^[A-Za-z0-9+/]*={0,2}$/.test(text)) {
    return null;
  }
  return Buffer.from(text, "base64");
}

// Reads identity.json for clientId. A missing file, or an identity of another
// client, returns null: the companion then gives the device no key material,
// the device makes a new identity, and the companion saves it.
export async function loadProviderIdentity(stateDir, clientId) {
  let data;
  try {
    data = await readPrivateFile(join(stateDir, identityFileName));
  } catch (error) {
    if (error?.code === "ENOENT") {
      return null;
    }
    throw new ConfigurationError(`read ${identityFileName} from the state directory: ${error.message}`);
  }
  const invalid = `${identityFileName} is not a valid provider identity; remove it to create a new one`;
  let identity;
  try {
    identity = JSON.parse(data.toString("utf8"));
  } catch {
    throw new ConfigurationError(invalid);
  }
  const optionalBytes = value => value === undefined || value === null || decodeBase64(value) !== null;
  const clientKeySeed = identity === null || typeof identity !== "object" ? null : decodeBase64(identity.client_key_seed);
  if (clientKeySeed === null || clientKeySeed.length !== 32 || identity.version !== providerIdentityVersion ||
      (identity.client_id !== undefined && typeof identity.client_id !== "string") ||
      !optionalBytes(identity.provide_tls_certificate_pem) || !optionalBytes(identity.provide_tls_private_key_pem) ||
      !optionalBytes(identity.extender_key_seed)) {
    throw new ConfigurationError(invalid);
  }
  if (identity.client_id !== clientId) {
    return null;
  }
  return identity;
}

// Loads and checks the installation state, creating instance-id on first run.
// Every error is a ConfigurationError.
export async function loadProviderConfig(stateDir) {
  await checkStateDir(stateDir);
  const {clientJwt, clientId} = await loadClientJwt(stateDir);
  const instanceId = await loadOrCreateInstanceId(stateDir);
  const identity = await loadProviderIdentity(stateDir, clientId);
  return {stateDir, clientJwt, clientId, instanceId, identity};
}
