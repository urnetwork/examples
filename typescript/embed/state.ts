// Installation state of one embed installation, kept in one private directory
// named by URNETWORK_EMBED_STATE_DIR (EMBED_CONTRACT.md, "Installation
// state"), with the provider contract's rules:
//
// - client.jwt: the scoped client credential. The token fetch writes it, or
//   the backend tool's provision or the developer; the native companion
//   rewrites it whenever the SDK refreshes the token.
// - instance-id: this installation's UUID, created on first run and kept for
//   the life of the installation. It is also the installation ID the app sends
//   to the token server.
// - logs/: the SDK's bounded log files, written by the companion.
//
// Files are replaced atomically with owner-only permissions. On POSIX the
// directory and its files must not be accessible to group or others, and a
// symlinked file is refused; Windows relies on the access control of the
// user's profile directory, under %LOCALAPPDATA%.

import {randomBytes, randomUUID} from "node:crypto";
import {lstat, open, readFile, rename, stat, unlink} from "node:fs/promises";
import {basename, dirname, isAbsolute, join} from "node:path";
import {normalizeId} from "./status.ts";

export const clientJwtFileName = "client.jwt";
export const instanceIdFileName = "instance-id";

// the largest state file the app reads
const stateFileByteLimit = 64 * 1024;

// Checks of POSIX permission bits do not apply on Windows.
const posix = process.platform !== "win32";

// A configuration problem that restarting does not fix (exit code 78).
export class ConfigurationError extends Error {}

// The scoped client JWT and its client_id claim.
export interface ClientCredential {
  clientJwt: string;
  clientId: string;
}

// The error code of a file system error, if any.
function errorCode(error: unknown): string | undefined {
  return typeof error === "object" && error !== null && "code" in error ? String((error as {code: unknown}).code) : undefined;
}

// The message of an error.
function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

// The state directory must be an existing absolute directory, private to its
// owner on POSIX.
export async function checkStateDir(stateDir: string | undefined): Promise<void> {
  if (!stateDir) {
    throw new ConfigurationError("set URNETWORK_EMBED_STATE_DIR to this installation's private state directory");
  }
  if (!isAbsolute(stateDir)) {
    throw new ConfigurationError("URNETWORK_EMBED_STATE_DIR must be an absolute path");
  }
  let info;
  try {
    info = await stat(stateDir);
  } catch (error) {
    throw new ConfigurationError(`state directory: ${errorMessage(error)}`);
  }
  if (!info.isDirectory()) {
    throw new ConfigurationError("URNETWORK_EMBED_STATE_DIR is not a directory");
  }
  if (posix && (info.mode & 0o077) !== 0) {
    throw new ConfigurationError("the state directory must be private to its owner (chmod 700)");
  }
}

// Reads a regular, private state file of bounded size. A symlink is refused
// so that the credential cannot be redirected to another file. A missing file
// rejects with the ENOENT error.
export async function readPrivateFile(path: string): Promise<Buffer> {
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
export async function writePrivateFile(path: string, data: string): Promise<void> {
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
export function parseClientJwtClientId(clientJwt: unknown): string {
  const notJwt = "client.jwt does not hold a JWT; write the scoped client JWT from your backend";
  const parts = typeof clientJwt === "string" ? clientJwt.split(".") : [];
  if (parts.length !== 3 || parts.some(part => part === "")) {
    throw new ConfigurationError(notJwt);
  }
  const payload = parts[1].replace(/=+$/, "");
  if (!/^[A-Za-z0-9_-]+$/.test(payload)) {
    throw new ConfigurationError(notJwt);
  }
  let claims: unknown;
  try {
    claims = JSON.parse(Buffer.from(payload, "base64url").toString("utf8"));
  } catch {
    claims = null;
  }
  const claimedId = claims !== null && typeof claims === "object" ? (claims as Record<string, unknown>).client_id : undefined;
  if (typeof claimedId !== "string" || claimedId === "") {
    throw new ConfigurationError("client.jwt has no client_id claim; write a scoped client JWT, not a network JWT");
  }
  const clientId = normalizeId(claimedId);
  if (clientId === "") {
    throw new ConfigurationError("client.jwt has an invalid client_id claim");
  }
  return clientId;
}

// Reads the scoped client JWT, trimmed, and its client_id claim.
export async function loadClientJwt(stateDir: string): Promise<ClientCredential> {
  let data;
  try {
    data = await readPrivateFile(join(stateDir, clientJwtFileName));
  } catch (error) {
    if (errorCode(error) === "ENOENT") {
      throw new ConfigurationError(`there is no ${clientJwtFileName} in the state directory: configure a token server (URNETWORK_TOKEN_SERVER_URL and URNETWORK_DEMO_SESSION) or write the client JWT from your backend tool's provision`);
    }
    throw new ConfigurationError(`read ${clientJwtFileName} from the state directory: ${errorMessage(error)}`);
  }
  const clientJwt = data.toString("utf8").trim();
  return {clientJwt, clientId: parseClientJwtClientId(clientJwt)};
}

// Saves a scoped client JWT as client.jwt, atomically and privately.
export async function saveClientJwt(stateDir: string, clientJwt: string): Promise<void> {
  await writePrivateFile(join(stateDir, clientJwtFileName), `${clientJwt}\n`);
}

// Reads instance-id, creating it on first run. An installation keeps one
// instance id for its lifetime.
export async function loadOrCreateInstanceId(stateDir: string): Promise<string> {
  const path = join(stateDir, instanceIdFileName);
  let data;
  try {
    data = await readPrivateFile(path);
  } catch (error) {
    if (errorCode(error) !== "ENOENT") {
      throw new ConfigurationError(`read ${instanceIdFileName} from the state directory: ${errorMessage(error)}`);
    }
    const instanceId = randomUUID();
    try {
      await writePrivateFile(path, `${instanceId}\n`);
    } catch (writeError) {
      throw new ConfigurationError(`write ${instanceIdFileName}: ${errorMessage(writeError)}`);
    }
    return instanceId;
  }
  const instanceId = normalizeId(data.toString("utf8").trim());
  if (instanceId === "") {
    throw new ConfigurationError(`${instanceIdFileName} does not hold a UUID`);
  }
  return instanceId;
}
