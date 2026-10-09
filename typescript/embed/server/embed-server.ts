// SERVER ONLY. The embed backend tool (EMBED_CONTRACT.md, "Backend tools"). It
// extends the TypeScript integration allocator (../../integration/server) with
// the same settings, map format, key pattern, lock and response checks, and
// adds per-user data caps and removal. Your authenticated backend supplies the
// key internally, user:<service-user-id>:<installation-id> (or
// user:<service-user-id> for a service whose users run one installation);
// never pass an untrusted request field or a URnetwork client ID as the key.
//
//   provision <key> <client-jwt-file>   reissue or provision the key's client
//   cap <key> [--monthly <bytes>|--monthly null] [--total <bytes>|--total null] [--reset-total]
//   usage <key>                         the key's cap object
//   usage-all                           every capped client's cap object, one per line
//   remove <key>                        remove the key's client and its mapping
//   acl <key> default|isolated          set the ACL group of the key's client
//   --self-test                         credential-free, no network
//
// Settings: URNETWORK_ROOT_JWT (an API key or a network JWT; never in an app),
// URNETWORK_CLIENT_MAP (absolute path in an existing private, service-owned
// directory), optional URNETWORK_API_URL and optional
// URNETWORK_DEFAULT_ACL_GROUP (the ACL group of each new client: isolated, the
// default, or default). A crash may leave <map>.lock; remove it only after
// confirming that no tool still owns it.
//
// Exit codes: 0 success, 78 configuration or credential problem (missing
// settings, an invalid key or map, the root credential refused, the client
// limit), 1 any other failure, with one stderr line that never holds a secret.

import assert from "node:assert/strict";
import {randomUUID} from "node:crypto";
import * as fs from "node:fs";
import {createServer} from "node:http";
import type {AddressInfo} from "node:net";
import * as os from "node:os";
import * as path from "node:path";
import {fileURLToPath} from "node:url";

const exitSuccess = 0;
const exitFailure = 1;
// sysexits EX_CONFIG
const exitConfig = 78;

// the largest map or API answer the tool reads
const limit = 1 << 20;
const keyPattern = /^user:[A-Za-z0-9][A-Za-z0-9_.:@-]{0,122}(?![\s\S])/;
const idPattern = /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?![\s\S])/;
// a byte count: a decimal integer from 0 to 2^63 - 1, without units
const byteCountPattern = /^(?:0|[1-9][0-9]{0,18})(?![\s\S])/;
const byteCountMax = 9223372036854775807n;

const description = "embed client";
const deviceSpec = "urnetwork-examples/typescript-embed-server";
const clientLimitMessage = "client limit reached: your network is at its client limit; see https://ur.io/services";
const clientDoesNotExist = "Client does not exist.";
const unmappedMessage = "no client is mapped for that key; run provision first";
const aclUnsupportedMessage = "/network/client-acl-group answered 404: the server predates ACL groups";
const aclPath = "/network/client-acl-group";
const capPath = "/network/client-data-cap";
const capsPath = "/network/client-data-caps";
const aclGroupDefault = "default";
const aclGroupIsolated = "isolated";
const aclGroups: readonly string[] = [aclGroupDefault, aclGroupIsolated];

// A JSON value as this tool reads it: integers beyond 2^53 are BigInt.
type JsonValue = null | boolean | number | bigint | string | JsonValue[] | {[key: string]: JsonValue};
type JsonObject = {[key: string]: JsonValue};

// An API call with the root credential.
type ApiCall = (method: string, apiPath: string, body?: string) => Promise<JsonValue>;

// A line of output.
type LineWriter = (line: string) => void;

// The client map: version, clients and pending_acl, the keys whose new
// clients still owe their default ACL group (saved only while it is not
// empty).
interface ClientMap {
  version: 1;
  clients: Record<string, string>;
  pending_acl: string[];
}

// The cap command's options; byte counts stay as their decimal text.
interface CapOptions {
  monthly?: string;
  total?: string;
  resetTotal?: boolean;
}

// What every command needs.
interface CommandContext {
  file: string;
  call: ApiCall;
  out: LineWriter;
  aclGroup: string;
}

// The options of runCommand, for the self-test.
interface RunOptions {
  environment?: Record<string, string | undefined>;
  call?: ApiCall;
  out?: LineWriter;
  err?: LineWriter;
}

// A configuration or credential problem: exit code 78.
class ConfigError extends Error {}

// An error whose message is one of the contract's exact lines, printed
// without the tool's prefix.
class ExactError extends Error {}

// An exact line that is a configuration problem: exit code 78.
class ExactConfigError extends ConfigError {}

// The error for a route that answered 404: a server that predates it
// (EMBED_CONTRACT.md, "Backend tools").
function notFoundError(apiPath: string): Error {
  const route = apiPath.split("?")[0];
  if (route === aclPath) return new ExactError(aclUnsupportedMessage);
  if (route === capPath || route === capsPath) return new ExactError(`${route} answered 404: the server predates the data-cap routes`);
  return new Error(`${route} answered 404`);
}

// A refusal answered by the API, with its message.
class RefusalError extends Error {
  readonly apiMessage: unknown;

  // A refusal with the tool's message and the API's.
  constructor(message: string, apiMessage: unknown) {
    super(message);
    this.apiMessage = apiMessage;
  }
}

// JSON.rawJSON and the reviver's source text (Node 24), which TypeScript's lib
// does not declare yet.
const jsonWithSource = JSON as unknown as {
  parse(text: string, reviver: (key: string, value: unknown, context: {source: string}) => unknown): unknown;
  rawJSON(text: string): unknown;
};

// JSON with exact int64 byte counts: integers beyond 2^53 parse as BigInt and
// print back unchanged.
function parseJson(text: string): JsonValue {
  return jsonWithSource.parse(text, (_key, value, context) =>
    typeof value === "number" && Number.isInteger(value) && !Number.isSafeInteger(value) ? BigInt(context.source) : value) as JsonValue;
}
function printJson(value: JsonValue): string {
  return JSON.stringify(value, (_key, member: unknown) => typeof member === "bigint" ? jsonWithSource.rawJSON(String(member)) : member);
}

// Whether a value is a JSON object.
function isObject(value: JsonValue | undefined): value is JsonObject {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

// The error object of an answer, if any.
function answerError(answer: JsonObject): JsonObject | null {
  const error = answer.error;
  return error === undefined || error === null ? null : isObject(error) ? error : {};
}

// The key, checked against the allocator's pattern.
function checkKey(key: string | undefined): string {
  if (typeof key !== "string" || !keyPattern.test(key)) {
    throw new ConfigError("expected a key user:<service-user-id>[:<installation-id>]");
  }
  return key;
}

// The API origin: HTTPS, or explicit loopback HTTP for local mocks.
function apiOrigin(base: string): string {
  let u: URL;
  try {
    u = new URL(base);
  } catch {
    throw new ConfigError("URNETWORK_API_URL is not a URL");
  }
  if (u.username || u.password || u.pathname !== "/" || u.search || u.hash ||
      (u.protocol !== "https:" && !(u.protocol === "http:" && ["localhost", "127.0.0.1", "[::1]"].includes(u.hostname))))
    throw new ConfigError("URNETWORK_API_URL must be an HTTPS origin (explicit loopback HTTP mocks are allowed)");
  return u.origin;
}

// Loads the map: version, clients and an optional pending_acl, so that two
// tools (or the token server, whose map adds pending_caps) never rewrite each
// other's map.
function loadMap(file: string): ClientMap {
  if (!fs.existsSync(file)) return {version: 1, clients: {}, pending_acl: []};
  const stat = fs.lstatSync(file);
  if (!stat.isFile() || stat.isSymbolicLink() || stat.size > limit ||
      (process.platform !== "win32" && (stat.mode & 0o077))) throw new ConfigError("the client map must be a private regular file (0600)");
  let map: unknown;
  try {
    map = JSON.parse(fs.readFileSync(file, "utf8"));
  } catch {
    throw new ConfigError("the client map is not JSON");
  }
  if (!map || typeof map !== "object" || Array.isArray(map)) throw new ConfigError("invalid client map");
  const candidate = map as Record<string, unknown>;
  if (candidate.version !== 1 || !candidate.clients || Array.isArray(candidate.clients) || typeof candidate.clients !== "object") throw new ConfigError("invalid client map");
  const unknown = Object.keys(candidate).filter(name => name !== "version" && name !== "clients" && name !== "pending_acl");
  if (unknown.length) throw new ConfigError(`the client map has fields this tool does not own (${unknown.join(", ")}); use a map of its own`);
  const clients = candidate.clients as Record<string, unknown>;
  const seen = new Set<string>();
  for (const [key, client] of Object.entries(clients)) {
    if (!keyPattern.test(key) || typeof client !== "string" || !idPattern.test(client) || seen.has(client)) throw new ConfigError("invalid client map entry");
    seen.add(client);
  }
  const pending = candidate.pending_acl ?? [];
  if (!Array.isArray(pending) || new Set(pending).size !== pending.length ||
      pending.some(key => typeof key !== "string" || !Object.hasOwn(clients, key)))
    throw new ConfigError("the client map's pending_acl is not a list of mapped keys");
  return {version: 1, clients: clients as Record<string, string>, pending_acl: pending as string[]};
}

// Saves the map atomically: pending_acl only while it is not empty.
function saveMap(file: string, map: ClientMap): void {
  writePrivate(file, JSON.stringify(map.pending_acl.length ? map : {version: map.version, clients: map.clients}));
}

// Records that key's new client owes its default ACL group, or drops the
// record.
function setAclPending(map: ClientMap, key: string, pending: boolean): void {
  map.pending_acl = map.pending_acl.filter(entry => entry !== key);
  if (pending) map.pending_acl.push(key);
}

// Writes a file atomically with owner-only permissions.
function writePrivate(file: string, data: string): void {
  const temporary = path.join(path.dirname(file), `.${path.basename(file)}.${randomUUID()}`);
  const fd = fs.openSync(temporary, "wx", 0o600);
  try {
    fs.writeFileSync(fd, data);
    fs.fsyncSync(fd);
  } finally { fs.closeSync(fd); }
  try { fs.renameSync(temporary, file); }
  finally { if (fs.existsSync(temporary)) fs.unlinkSync(temporary); }
}

// Runs work with the map's exclusive lock, held through the remote calls and
// the atomic map update.
async function withLock<T>(file: string, work: () => Promise<T>): Promise<T> {
  if (!path.isAbsolute(file) || !fs.statSync(path.dirname(file), {throwIfNoEntry: false})?.isDirectory())
    throw new ConfigError("URNETWORK_CLIENT_MAP needs an absolute path in an existing directory");
  try {
    fs.mkdirSync(file + ".lock", {mode: 0o700});
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code === "EEXIST") throw new Error("the client map is locked by another tool; retry");
    throw error;
  }
  try { return await work(); }
  finally { fs.rmdirSync(file + ".lock"); }
}

// The JWT's client_id claim; this checks consistency, not the signature.
function claimedClientId(jwt: string): string {
  const parts = jwt.split(".");
  if (parts.length !== 3 || parts.some(part => !part) || !/^[A-Za-z0-9_-]+(?![\s\S])/.test(parts[1])) throw new Error("invalid client JWT");
  let claims: unknown;
  try {
    claims = JSON.parse(Buffer.from(parts[1], "base64url").toString("utf8"));
  } catch {
    throw new Error("invalid client JWT");
  }
  const claimed = claims !== null && typeof claims === "object" ? (claims as Record<string, unknown>).client_id : undefined;
  return typeof claimed === "string" ? claimed.toLowerCase() : "";
}

// The auth-client request for a key: a reissue sends the mapped client_id,
// a new client sends neither client_id nor source_client_id.
function authClientBody(client: string | undefined): string {
  const body: Record<string, string> = {description, device_spec: deviceSpec};
  if (client !== undefined) {
    if (!idPattern.test(client)) throw new ConfigError("invalid mapped client ID");
    body.client_id = client;
  }
  return JSON.stringify(body);
}

// Checks an auth-client answer: a refusal throws (the client limit flags as a
// configuration error), a success must carry a client JWT whose claim matches.
function checkAuthClient(answer: JsonValue, expected: string | undefined): {client_id: string; by_client_jwt: string} {
  if (!isObject(answer)) throw new Error("invalid API response");
  const error = answerError(answer);
  if (error !== null) {
    if (error.client_limit_exceeded || error.upgrade_required) throw new ExactConfigError(clientLimitMessage);
    throw new RefusalError(typeof error.message === "string" ? `provisioning refused: ${error.message}` : "provisioning refused", error.message);
  }
  const clientId = answer.client_id;
  const clientJwt = answer.by_client_jwt;
  if (typeof clientId !== "string" || !idPattern.test(clientId) || typeof clientJwt !== "string") throw new Error("invalid API response");
  if (claimedClientId(clientJwt) !== clientId || (expected !== undefined && expected !== clientId))
    throw new Error("scoped client identity mismatch");
  return {client_id: clientId, by_client_jwt: clientJwt};
}

// The cap request body: only the given fields, byte counts exact.
function capBody(client: string, {monthly, total, resetTotal}: CapOptions): string {
  let body = `{"client_id":${JSON.stringify(client)}`;
  if (monthly !== undefined) body += `,"monthly_byte_limit":${monthly}`;
  if (total !== undefined) body += `,"total_byte_limit":${total}`;
  if (resetTotal) body += `,"reset_total":true`;
  return body + "}";
}

// The cap command's options: at least one, each once.
function capOptions(args: readonly string[]): CapOptions {
  const options: CapOptions = {};
  const byteCount = (value: string | undefined): string => {
    if (value === "null") return "null";
    if (typeof value !== "string" || !byteCountPattern.test(value) || BigInt(value) > byteCountMax)
      throw new ConfigError("a byte count is a decimal integer from 0 to 9223372036854775807, or null");
    return value;
  };
  for (let i = 0; i < args.length; i += 1) {
    const option = args[i];
    if ((option === "--monthly" || option === "--total") && i + 1 < args.length) {
      const name = option === "--monthly" ? "monthly" : "total";
      if (options[name] !== undefined) throw new ConfigError(`${option} given twice`);
      i += 1;
      options[name] = byteCount(args[i]);
    } else if (option === "--reset-total" && !options.resetTotal) {
      options.resetTotal = true;
    } else {
      throw new ConfigError(`unexpected argument ${JSON.stringify(option)}`);
    }
  }
  if (options.monthly === undefined && options.total === undefined && !options.resetTotal)
    throw new ConfigError("cap needs at least one of --monthly, --total, --reset-total");
  return options;
}

// Checks a cap object answer for the client.
function checkCapObject(answer: JsonValue, client?: string): JsonObject {
  if (!isObject(answer)) throw new Error("invalid cap answer");
  const error = answerError(answer);
  if (error !== null) throw new Error(typeof error.message === "string" ? `the cap request was refused: ${error.message}` : "the cap request was refused");
  if (client !== undefined && answer.client_id !== client) throw new Error("the cap answer is for another client");
  for (const field of ["monthly_byte_limit", "total_byte_limit"]) {
    const value = answer[field];
    if (!(value === undefined || value === null || typeof value === "bigint" || (typeof value === "number" && Number.isInteger(value) && 0 <= value))) throw new Error("invalid cap answer");
  }
  return answer;
}

// The mapped client of a key, or a configuration error.
function mappedClient(map: ClientMap, key: string): string {
  const client = map.clients[key];
  if (client === undefined) throw new ExactConfigError(unmappedMessage);
  return client;
}

// An API call with the root credential. A 401 is the root credential refused
// (78).
function apiCaller(origin: string, root: string | undefined): ApiCall {
  if (!root || /\s/.test(root)) throw new ConfigError("set URNETWORK_ROOT_JWT to the backend's root credential");
  return async (method, apiPath, body) => {
    const headers: Record<string, string> = {authorization: "Bearer " + root};
    if (body !== undefined) headers["content-type"] = "application/json";
    const response = await fetch(origin + apiPath, {method, headers, body, redirect: "error", signal: AbortSignal.timeout(15000)});
    if (response.status === 401) throw new ConfigError("the URnetwork API refused the root credential");
    const text = await response.text();
    if (response.status === 404) throw notFoundError(apiPath);
    if (text.length > limit) throw new Error("API response too large");
    if (!response.ok) throw new Error(`the URnetwork API answered HTTP ${response.status}`);
    return parseJson(text);
  };
}

// provision: reissue the key's client, or provision a new one; on "Client does
// not exist." drop the mapping and provision a new client. A new client goes
// into aclGroup, with a pending_acl record until the group is applied, before
// any client JWT is written. Writes the client JWT to the file, never to the
// output.
async function provision(key: string, clientJwtFile: string, {file, call, out, aclGroup}: CommandContext): Promise<void> {
  checkKey(key);
  if (clientJwtFile === "") throw new ConfigError("expected provision <key> <client-jwt-file>");
  const target = path.resolve(clientJwtFile);
  await withLock(file, async () => {
    const map = loadMap(file);
    let old: string | undefined = map.clients[key];
    let result;
    try {
      result = checkAuthClient(await call("POST", "/network/auth-client", authClientBody(old)), old);
    } catch (error) {
      if (old === undefined || !(error instanceof RefusalError) || error.apiMessage !== clientDoesNotExist) throw error;
      // deactivated after 30 days without connecting: provision a new client
      delete map.clients[key];
      setAclPending(map, key, false);
      saveMap(file, map);
      old = undefined;
      result = checkAuthClient(await call("POST", "/network/auth-client", authClientBody(undefined)), undefined);
    }
    if (old === undefined) {
      if (Object.values(map.clients).includes(result.client_id)) throw new Error("client assigned to another key");
      map.clients[key] = result.client_id;
      // the mapping and the record that the client owes its group, in one save
      setAclPending(map, key, aclGroup === aclGroupIsolated);
      saveMap(file, map);
    }
    if (map.pending_acl.includes(key)) {
      // a new client is "default": only an isolated default needs the request.
      // A failure throws with the record kept, before any client JWT is written.
      if (aclGroup === aclGroupIsolated) await postAclGroup(call, result.client_id, aclGroupIsolated);
      setAclPending(map, key, false);
      saveMap(file, map);
    }
    writePrivate(target, result.by_client_jwt + "\n");
    out(printJson({client_id: result.client_id}));
  });
}

// Sets the client's ACL group; the answer must name the client and the group.
async function postAclGroup(call: ApiCall, client: string, group: string): Promise<void> {
  const answer = await call("POST", aclPath, JSON.stringify({client_id: client, acl_group: group}));
  if (!isObject(answer)) throw new Error("invalid ACL group answer");
  const error = answerError(answer);
  if (error !== null)
    throw new Error(typeof error.message === "string" ? `the ACL group request was refused: ${error.message}` : "the ACL group request was refused");
  if (answer.client_id !== client || answer.acl_group !== group) throw new Error("the API answered another client or ACL group");
}

// acl: set the ACL group of the key's client; prints {"client_id": ...,
// "acl_group": ...}. An explicit group settles a pending default group, so
// the record is dropped.
async function acl(key: string, group: string, {file, call, out}: CommandContext): Promise<void> {
  checkKey(key);
  if (!aclGroups.includes(group)) throw new ConfigError("expected acl <key> default|isolated");
  await withLock(file, async () => {
    const map = loadMap(file);
    const client = mappedClient(map, key);
    await postAclGroup(call, client, group);
    if (map.pending_acl.includes(key)) {
      setAclPending(map, key, false);
      saveMap(file, map);
    }
    out(printJson({client_id: client, acl_group: group}));
  });
}

// cap: post only the given fields; prints the cap object.
async function cap(key: string, args: readonly string[], {file, call, out}: CommandContext): Promise<void> {
  checkKey(key);
  const options = capOptions(args);
  const client = mappedClient(loadMap(file), key);
  out(printJson(checkCapObject(await call("POST", "/network/client-data-cap", capBody(client, options)), client)));
}

// usage: the key's cap object, read with the root credential.
async function usage(key: string, {file, call, out}: CommandContext): Promise<void> {
  checkKey(key);
  const client = mappedClient(loadMap(file), key);
  out(printJson(checkCapObject(await call("GET", `/network/client-data-cap?client_id=${client}`), client)));
}

// usage-all: every capped client's cap object, one per line, paging with
// limit=1000 until a null cursor or a repeated one.
async function usageAll({call, out}: CommandContext): Promise<void> {
  const seen = new Set<string>();
  let cursor: string | null = null;
  while (true) {
    const page = await call("GET", `/network/client-data-caps?limit=1000${cursor === null ? "" : `&cursor=${encodeURIComponent(cursor)}`}`);
    if (!isObject(page) || answerError(page) !== null || !Array.isArray(page.clients) ||
        !(page.next_cursor === null || page.next_cursor === undefined || typeof page.next_cursor === "string")) throw new Error("invalid cap list answer");
    for (const item of page.clients) out(printJson(checkCapObject(item)));
    cursor = typeof page.next_cursor === "string" ? page.next_cursor : null;
    if (cursor === null || seen.has(cursor)) return;
    seen.add(cursor);
  }
}

// remove: remove the key's client, then its mapping (also when the client is
// already gone).
async function remove(key: string, {file, call, out}: CommandContext): Promise<void> {
  checkKey(key);
  await withLock(file, async () => {
    const map = loadMap(file);
    const client = mappedClient(map, key);
    const answer = await call("POST", "/network/remove-client", JSON.stringify({client_id: client}));
    if (!isObject(answer)) throw new Error("invalid remove answer");
    const error = answerError(answer);
    if (error !== null && error.message !== clientDoesNotExist)
      throw new Error(typeof error.message === "string" ? `removal refused: ${error.message}` : "removal refused");
    delete map.clients[key];
    setAclPending(map, key, false);
    saveMap(file, map);
    out(printJson({removed: client}));
  });
}

// Runs one command; resolves with the exit code. The options replace the
// environment, the API caller and the output, for the self-test.
export async function runCommand(args: readonly string[], {environment = process.env, call, out = line => console.log(line), err = line => console.error(line)}: RunOptions = {}): Promise<number> {
  try {
    const [command, ...rest] = args;
    if (command === "--self-test" && rest.length === 0) {
      await selfTest();
      out("embed server self-test passed");
      return exitSuccess;
    }
    const handlers: Record<string, ((context: CommandContext) => Promise<void>) | null> = {
      provision: rest.length === 2 ? context => provision(rest[0], rest[1], context) : null,
      cap: rest.length >= 1 ? context => cap(rest[0], rest.slice(1), context) : null,
      usage: rest.length === 1 ? context => usage(rest[0], context) : null,
      "usage-all": rest.length === 0 ? context => usageAll(context) : null,
      remove: rest.length === 1 ? context => remove(rest[0], context) : null,
      acl: rest.length === 2 && aclGroups.includes(rest[1]) ? context => acl(rest[0], rest[1], context) : null,
    };
    const handler = command !== undefined && Object.hasOwn(handlers, command) ? handlers[command] : null;
    if (!handler) throw new ConfigError("usage: embed-server provision <key> <client-jwt-file> | cap <key> [--monthly <bytes>|null] [--total <bytes>|null] [--reset-total] | usage <key> | usage-all | remove <key> | acl <key> default|isolated | --self-test");
    const file = environment.URNETWORK_CLIENT_MAP;
    if (!file) throw new ConfigError("set URNETWORK_CLIENT_MAP to the absolute path of this tool's private client map");
    const aclGroup = environment.URNETWORK_DEFAULT_ACL_GROUP || aclGroupIsolated;
    if (!aclGroups.includes(aclGroup)) throw new ConfigError("URNETWORK_DEFAULT_ACL_GROUP must be default or isolated");
    const apiCall = call ?? apiCaller(apiOrigin(environment.URNETWORK_API_URL || "https://api.bringyour.com"), environment.URNETWORK_ROOT_JWT);
    await handler({file, call: apiCall, out, aclGroup});
    return exitSuccess;
  } catch (error) {
    // one line, never a token: messages here never include the root credential or a JWT
    // the contract's exact lines (the client limit, an unmapped key, an older server) print as they are
    const message = error instanceof Error ? error.message : "";
    err(error instanceof ExactError || error instanceof ExactConfigError ? message : message ? `embed server: ${message}` : "embed server failed");
    return error instanceof ConfigError ? exitConfig : exitFailure;
  }
}

// The credential-free self-test: a mock API, no network.
async function selfTest(): Promise<void> {
  const client = "11111111-1111-1111-1111-111111111111";
  const other = "22222222-2222-2222-2222-222222222222";
  const jwt = (id: string): string => "e30." + Buffer.from(JSON.stringify({client_id: id})).toString("base64url") + ".test";
  const root = "urn_" + "r".repeat(40);
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), "ur-embed-server-"));
  fs.chmodSync(directory, 0o700);
  const file = path.join(directory, "clients.json");
  const jwtFile = path.join(directory, "client.jwt");
  const key = "user:alice:33333333-3333-3333-3333-333333333333";
  const capAnswer = (id: string): string => `{"client_id":"${id}","monthly_byte_limit":9223372036854775807,"monthly_used_byte_count":12,"total_byte_limit":null,"total_used_byte_count":12,"capped":false,"capped_reason":""}`;
  try {
    // a scripted API: answers[i] answers the i-th call; calls records them
    const calls: {method: string; apiPath: string; body: string | undefined}[] = [];
    let answers: (string | Error)[] = [];
    const call: ApiCall = async (method, apiPath, body) => {
      calls.push({method, apiPath, body});
      const next = answers.shift();
      if (next instanceof Error) throw next;
      if (next === undefined) throw new Error("no scripted answer");
      return parseJson(next);
    };
    const lastCall = () => calls[calls.length - 1];
    const outputs: string[] = [];
    const command = async (args: string[]): Promise<number> => {
      outputs.length = 0;
      // these checks keep new clients in "default", which sends no ACL request
      return runCommand(args, {environment: {URNETWORK_CLIENT_MAP: file, URNETWORK_ROOT_JWT: root, URNETWORK_DEFAULT_ACL_GROUP: aclGroupDefault}, call, out: line => outputs.push(line), err: line => outputs.push(line)});
    };
    // the same with the default group unset, so isolated, and its own map
    const aclFile = path.join(directory, "acl.json");
    const isolated = async (args: string[]): Promise<number> => {
      outputs.length = 0;
      return runCommand(args, {environment: {URNETWORK_CLIENT_MAP: aclFile, URNETWORK_ROOT_JWT: root}, call, out: line => outputs.push(line), err: line => outputs.push(line)});
    };
    const bodyOf = (index: number): unknown => JSON.parse(calls[index].body ?? "null");

    // new, then reissue: client_id only on the reissue, never source_client_id
    answers = [JSON.stringify({client_id: client, by_client_jwt: jwt(client)})];
    assert.equal(await command(["provision", key, jwtFile]), exitSuccess);
    assert.deepEqual(outputs, [`{"client_id":"${client}"}`]);
    assert.deepEqual(bodyOf(0), {description, device_spec: deviceSpec});
    assert.equal(fs.readFileSync(jwtFile, "utf8").trim(), jwt(client));
    if (process.platform !== "win32") {
      assert.equal(fs.statSync(jwtFile).mode & 0o077, 0);
      assert.equal(fs.statSync(file).mode & 0o077, 0);
    }
    answers = [JSON.stringify({client_id: client, by_client_jwt: jwt(client)})];
    assert.equal(await command(["provision", key, jwtFile]), exitSuccess);
    assert.deepEqual(bodyOf(1), {description, device_spec: deviceSpec, client_id: client});
    assert.deepEqual(loadMap(file).clients, {[key]: client});
    // the token never reaches the output
    assert.ok(outputs.every(line => !line.includes(jwt(client))));

    // a deactivated client: the reissue answers "Client does not exist.", a new client is provisioned
    answers = [JSON.stringify({error: {message: clientDoesNotExist}}), JSON.stringify({client_id: other, by_client_jwt: jwt(other)})];
    assert.equal(await command(["provision", key, jwtFile]), exitSuccess);
    assert.equal(calls[3].body, authClientBody(undefined));
    assert.deepEqual(loadMap(file).clients, {[key]: other});

    // response and claim checks, the client limit (78), a refusal (1), the root credential refused (78)
    const provisionCases: [string | Error, number][] = [
      [JSON.stringify({client_id: other, by_client_jwt: jwt(client)}), exitFailure],
      [JSON.stringify({error: {client_limit_exceeded: true, message: "Client limit exceeded."}}), exitConfig],
      [JSON.stringify({error: {upgrade_required: true, message: "Upgrade required."}}), exitConfig],
      [JSON.stringify({error: {message: "Invalid location"}}), exitFailure],
      [new ConfigError("the URnetwork API refused the root credential"), exitConfig],
    ];
    for (const [answer, code] of provisionCases) {
      answers = [answer];
      assert.equal(await command(["provision", key, jwtFile]), code);
      assert.equal(outputs.length, 1);
    }
    answers = [JSON.stringify({error: {client_limit_exceeded: true}})];
    assert.equal(await command(["provision", "user:bob", path.join(directory, "bob.jwt")]), exitConfig);
    assert.deepEqual(outputs, [clientLimitMessage]);

    // key and argument rejection
    for (const args of [["provision", other, jwtFile], ["provision", "user:../a", jwtFile], ["provision", "user:alice\n", jwtFile],
      ["provision", key], ["usage", key, "--client-id", other], ["cap", key], ["cap", key, "--monthly", "10GB"], ["cap", key, "--monthly", "-1"],
      ["cap", key, "--monthly", "9223372036854775808"], ["cap", key, "--monthly", "1", "--monthly", "2"], ["remove", "user:nobody"], ["unknown"]]) {
      assert.equal(await command(args), exitConfig, args.join(" "));
    }

    // merge bodies: omitted absent, null is null, exact integers, reset_total only when given
    const capCases: [string[], string][] = [
      [["--monthly", "10000000000"], `{"client_id":"${other}","monthly_byte_limit":10000000000}`],
      [["--total", "null"], `{"client_id":"${other}","total_byte_limit":null}`],
      [["--monthly", "0", "--reset-total"], `{"client_id":"${other}","monthly_byte_limit":0,"reset_total":true}`],
      [["--reset-total"], `{"client_id":"${other}","reset_total":true}`],
      [["--monthly", "9223372036854775807", "--total", "5"], `{"client_id":"${other}","monthly_byte_limit":9223372036854775807,"total_byte_limit":5}`],
    ];
    for (const [args, body] of capCases) {
      answers = [capAnswer(other)];
      assert.equal(await command(["cap", key, ...args]), exitSuccess);
      assert.equal(lastCall().body, body);
      // the cap object prints with the exact byte counts
      assert.equal(outputs[0], capAnswer(other));
    }
    answers = [capAnswer(client)];
    assert.equal(await command(["cap", key, "--monthly", "1"]), exitFailure);
    answers = [capAnswer(other)];
    assert.equal(await command(["usage", key]), exitSuccess);
    assert.equal(lastCall().apiPath, `/network/client-data-cap?client_id=${other}`);

    // usage-all pages until a null cursor, and stops on a repeated cursor
    answers = [JSON.stringify({clients: [JSON.parse(capAnswer(client).replace("9223372036854775807", "1"))], next_cursor: "a"}),
      JSON.stringify({clients: [JSON.parse(capAnswer(other).replace("9223372036854775807", "1"))], next_cursor: null})];
    assert.equal(await command(["usage-all"]), exitSuccess);
    assert.equal(outputs.length, 2);
    assert.equal(calls[calls.length - 2].apiPath, "/network/client-data-caps?limit=1000");
    assert.equal(lastCall().apiPath, "/network/client-data-caps?limit=1000&cursor=a");
    answers = [JSON.stringify({clients: [], next_cursor: "b"}), JSON.stringify({clients: [], next_cursor: "b"}), JSON.stringify({clients: [], next_cursor: "c"})];
    assert.equal(await command(["usage-all"]), exitSuccess);
    assert.equal(answers.length, 1, "usage-all did not stop on the repeated cursor");

    // remove drops the mapping for both answers
    answers = [JSON.stringify({})];
    assert.equal(await command(["remove", key]), exitSuccess);
    assert.deepEqual(outputs, [`{"removed":"${other}"}`]);
    assert.deepEqual(JSON.parse(lastCall().body ?? "null"), {client_id: other});
    assert.deepEqual(loadMap(file).clients, {});
    writePrivate(file, JSON.stringify({version: 1, clients: {[key]: client}}));
    answers = [JSON.stringify({error: {message: clientDoesNotExist}})];
    assert.equal(await command(["remove", key]), exitSuccess);
    assert.deepEqual(loadMap(file).clients, {});

    // a map with fields this tool does not own, such as the token server's pending_caps
    writePrivate(file, JSON.stringify({version: 1, clients: {}, pending_caps: []}));
    answers = [JSON.stringify({clients: [], next_cursor: null})];
    assert.equal(await command(["usage-all"]), exitSuccess, "usage-all needs no map");
    assert.equal(await command(["provision", key, jwtFile]), exitConfig);
    assert.match(outputs[0], /pending_caps/);

    // the default ACL group: one request after a new client, then no record; none on a reissue
    const ivanJwt = path.join(directory, "ivan.jwt");
    answers = [JSON.stringify({client_id: client, by_client_jwt: jwt(client)}), JSON.stringify({client_id: client, acl_group: aclGroupIsolated})];
    assert.equal(await isolated(["provision", "user:ivan", ivanJwt]), exitSuccess);
    assert.deepEqual(calls.slice(-2).map(c => c.apiPath), ["/network/auth-client", aclPath]);
    assert.deepEqual(JSON.parse(lastCall().body ?? "null"), {client_id: client, acl_group: aclGroupIsolated});
    assert.deepEqual(loadMap(aclFile).pending_acl, []);
    assert.ok(!fs.readFileSync(aclFile, "utf8").includes("pending_acl"));
    assert.equal(fs.readFileSync(ivanJwt, "utf8").trim(), jwt(client));
    answers = [JSON.stringify({client_id: client, by_client_jwt: jwt(client)})];
    const callCount = calls.length;
    assert.equal(await isolated(["provision", "user:ivan", ivanJwt]), exitSuccess);
    assert.equal(calls.length, callCount + 1, "a reissue sent an ACL request");

    // a failed request keeps the record and writes no client JWT; the next issue retries
    const judyJwt = path.join(directory, "judy.jwt");
    answers = [JSON.stringify({client_id: other, by_client_jwt: jwt(other)}), new Error("the URnetwork API answered HTTP 500")];
    assert.equal(await isolated(["provision", "user:judy", judyJwt]), exitFailure);
    assert.ok(!fs.existsSync(judyJwt));
    assert.deepEqual(loadMap(aclFile).pending_acl, ["user:judy"]);
    assert.equal(loadMap(aclFile).clients["user:judy"], other);
    answers = [JSON.stringify({client_id: other, by_client_jwt: jwt(other)}), JSON.stringify({client_id: other, acl_group: aclGroupIsolated})];
    assert.equal(await isolated(["provision", "user:judy", judyJwt]), exitSuccess);
    assert.equal(lastCall().apiPath, aclPath);
    assert.deepEqual(loadMap(aclFile).pending_acl, []);
    assert.ok(fs.existsSync(judyJwt));

    // a server without ACL groups: exit 1 with the exact line and the record kept
    const kim = "55555555-5555-5555-5555-555555555555";
    answers = [JSON.stringify({client_id: kim, by_client_jwt: jwt(kim)}), notFoundError(aclPath)];
    assert.equal(await isolated(["provision", "user:kim", path.join(directory, "kim.jwt")]), exitFailure);
    assert.deepEqual(outputs, [aclUnsupportedMessage]);
    assert.deepEqual(loadMap(aclFile).pending_acl, ["user:kim"]);
    assert.ok(!fs.existsSync(path.join(directory, "kim.jwt")));
    // a default of default settles the record without a request
    answers = [JSON.stringify({client_id: kim, by_client_jwt: jwt(kim)})];
    const beforeDefault = calls.length;
    assert.equal(await runCommand(["provision", "user:kim", path.join(directory, "kim.jwt")],
      {environment: {URNETWORK_CLIENT_MAP: aclFile, URNETWORK_ROOT_JWT: root, URNETWORK_DEFAULT_ACL_GROUP: aclGroupDefault}, call, out: () => {}, err: () => {}}), exitSuccess);
    assert.equal(calls.length, beforeDefault + 1);
    assert.deepEqual(loadMap(aclFile).pending_acl, []);

    // the acl command: the request, the printed answer, refusals
    answers = [JSON.stringify({client_id: client, acl_group: aclGroupDefault})];
    assert.equal(await isolated(["acl", "user:ivan", aclGroupDefault]), exitSuccess);
    assert.deepEqual(outputs, [`{"client_id":"${client}","acl_group":"default"}`]);
    assert.deepEqual(lastCall(), {method: "POST", apiPath: aclPath, body: JSON.stringify({client_id: client, acl_group: aclGroupDefault})});
    for (const args of [["acl"], ["acl", "user:ivan"], ["acl", "user:ivan", "public"], ["acl", "user:ivan", "Default"], ["acl", "user:ivan", "default", "extra"], ["acl", "alice", "default"]]) {
      assert.equal(await isolated(args), exitConfig, args.join(" "));
    }
    answers = [JSON.stringify({client_id: client, acl_group: aclGroupDefault})];
    assert.equal(await isolated(["acl", "user:ivan", aclGroupIsolated]), exitFailure, "an answer for another group was accepted");
    answers = [JSON.stringify({error: {message: clientDoesNotExist}})];
    assert.equal(await isolated(["acl", "user:ivan", aclGroupIsolated]), exitFailure);
    answers = [notFoundError(aclPath)];
    assert.equal(await isolated(["acl", "user:ivan", aclGroupIsolated]), exitFailure);
    assert.deepEqual(outputs, [aclUnsupportedMessage]);

    // an explicit group settles a pending record, so a later issue keeps it
    const mia = "66666666-6666-6666-6666-666666666666";
    answers = [JSON.stringify({client_id: mia, by_client_jwt: jwt(mia)}), new Error("the URnetwork API answered HTTP 500")];
    await isolated(["provision", "user:mia", path.join(directory, "mia.jwt")]);
    assert.deepEqual(loadMap(aclFile).pending_acl, ["user:mia"]);
    answers = [JSON.stringify({client_id: mia, acl_group: aclGroupDefault})];
    assert.equal(await isolated(["acl", "user:mia", aclGroupDefault]), exitSuccess);
    assert.deepEqual(loadMap(aclFile).pending_acl, []);
    answers = [JSON.stringify({client_id: mia, by_client_jwt: jwt(mia)})];
    const beforeSettled = calls.length;
    assert.equal(await isolated(["provision", "user:mia", path.join(directory, "mia.jwt")]), exitSuccess);
    assert.equal(calls.length, beforeSettled + 1, "a settled group was overridden");

    // remove drops a pending record with its mapping
    const noor = "77777777-7777-7777-7777-777777777777";
    answers = [JSON.stringify({client_id: noor, by_client_jwt: jwt(noor)}), new Error("the URnetwork API answered HTTP 500")];
    await isolated(["provision", "user:noor", path.join(directory, "noor.jwt")]);
    answers = [JSON.stringify({})];
    assert.equal(await isolated(["remove", "user:noor"]), exitSuccess);
    assert.deepEqual(loadMap(aclFile).pending_acl, []);

    // a pending_acl that is not a list of distinct mapped keys is refused untouched
    const pendingFile = path.join(directory, "pending.json");
    for (const map of [
      {version: 1, clients: {}, pending_acl: ["user:x"]},
      {version: 1, clients: {"user:x": client}, pending_acl: ["user:x", "user:x"]},
      {version: 1, clients: {"user:x": client}, pending_acl: "user:x"},
      {version: 1, clients: {"user:x": client}, pending_acl: [1]},
    ]) {
      writePrivate(pendingFile, JSON.stringify(map));
      const before = calls.length;
      assert.equal(await runCommand(["provision", "user:x", jwtFile], {environment: {URNETWORK_CLIENT_MAP: pendingFile, URNETWORK_ROOT_JWT: root}, call, out: () => {}, err: () => {}}), exitConfig);
      assert.equal(fs.readFileSync(pendingFile, "utf8"), JSON.stringify(map));
      assert.equal(calls.length, before);
    }

    // the unmapped-key line, exactly, for each command that needs a mapping
    for (const args of [["cap", "user:nobody", "--monthly", "1"], ["usage", "user:nobody"], ["remove", "user:nobody"], ["acl", "user:nobody", "default"]]) {
      assert.equal(await isolated(args), exitConfig, args.join(" "));
      assert.deepEqual(outputs, [unmappedMessage]);
    }
    // an invalid default ACL group
    assert.equal(await runCommand(["provision", "user:x", jwtFile], {environment: {URNETWORK_CLIENT_MAP: aclFile, URNETWORK_ROOT_JWT: root, URNETWORK_DEFAULT_ACL_GROUP: "private"}, call, out: () => {}, err: () => {}}), exitConfig);

    // missing settings
    assert.equal(await runCommand(["usage-all"], {environment: {}, out: () => {}, err: () => {}}), exitConfig);
    assert.equal(await runCommand(["usage-all"], {environment: {URNETWORK_CLIENT_MAP: file}, out: () => {}, err: () => {}}), exitConfig);
    // the root credential never reaches the output
    assert.ok(outputs.every(line => !line.includes(root)));
    assert.throws(() => apiOrigin("http://example.com"));
    assert.throws(() => apiOrigin("https://example.com/path"));
    assert.equal(apiOrigin("http://127.0.0.1:1234"), "http://127.0.0.1:1234");

    // the HTTP layer against a loopback stand-in: the root credential as the
    // bearer, the exact body, int64 answers kept, a 401 as a credential error
    const seen: {authorization: string | undefined; body: string}[] = [];
    let olderServer = false;
    const standIn = createServer((request, response) => {
      const chunks: Buffer[] = [];
      request.on("data", (chunk: Buffer) => chunks.push(chunk));
      request.on("end", () => {
        seen.push({authorization: request.headers.authorization, body: Buffer.concat(chunks).toString("utf8")});
        if (olderServer) {
          // a server that predates the data-cap and ACL routes
          response.statusCode = 404;
          response.end("404 page not found");
          return;
        }
        response.statusCode = request.url === "/refused" ? 401 : 200;
        response.end(request.url === "/refused" ? "{}" : capAnswer(client));
      });
    });
    await new Promise<void>(resolve => standIn.listen(0, "127.0.0.1", resolve));
    try {
      const httpCall = apiCaller(`http://127.0.0.1:${(standIn.address() as AddressInfo).port}`, root);
      const body = capBody(client, {monthly: "9223372036854775807"});
      const answer = await httpCall("POST", "/network/client-data-cap", body);
      assert.ok(isObject(answer));
      assert.equal(answer.monthly_byte_limit, 9223372036854775807n);
      assert.deepEqual(seen[0], {authorization: `Bearer ${root}`, body});
      await assert.rejects(httpCall("GET", "/refused"), (error: unknown) => error instanceof ConfigError);

      // an older server's 404s print the contract's lines, over HTTP
      olderServer = true;
      const olderEnvironment = {URNETWORK_CLIENT_MAP: aclFile, URNETWORK_ROOT_JWT: root, URNETWORK_API_URL: `http://127.0.0.1:${(standIn.address() as AddressInfo).port}`};
      const olderCases: [string[], string][] = [
        [["cap", "user:ivan", "--monthly", "1"], `${capPath} answered 404: the server predates the data-cap routes`],
        [["usage", "user:ivan"], `${capPath} answered 404: the server predates the data-cap routes`],
        [["usage-all"], `${capsPath} answered 404: the server predates the data-cap routes`],
        [["acl", "user:ivan", "isolated"], aclUnsupportedMessage],
      ];
      for (const [args, line] of olderCases) {
        const lines: string[] = [];
        assert.equal(await runCommand(args, {environment: olderEnvironment, out: () => {}, err: message => lines.push(message)}), exitFailure, args.join(" "));
        assert.deepEqual(lines, [line]);
      }
    } finally {
      await new Promise<void>(resolve => standIn.close(() => resolve()));
    }
  } finally {
    fs.rmSync(directory, {recursive: true, force: true});
  }
}

// Whether this module is the program's entry point.
function isEntryPoint(): boolean {
  const meta = import.meta as ImportMeta & {main?: boolean};
  return typeof meta.main === "boolean"
    ? meta.main
    : process.argv[1] !== undefined && fs.realpathSync(process.argv[1]) === fileURLToPath(import.meta.url);
}

if (isEntryPoint()) {
  process.exitCode = await runCommand(process.argv.slice(2));
}
