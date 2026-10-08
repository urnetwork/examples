// SERVER ONLY. The embed backend tool (EMBED_CONTRACT.md, "Backend tools"). It
// extends the JavaScript integration allocator (../../integration/server) with
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
//   --self-test                         credential-free, no network
//
// Settings: URNETWORK_ROOT_JWT (an API key or a network JWT; never in an app),
// URNETWORK_CLIENT_MAP (absolute path in an existing private, service-owned
// directory) and optional URNETWORK_API_URL. A crash may leave <map>.lock;
// remove it only after confirming that no tool still owns it.
//
// Exit codes: 0 success, 78 configuration or credential problem (missing
// settings, an invalid key or map, the root credential refused, the client
// limit), 1 any other failure, with one stderr line that never holds a secret.

import assert from "node:assert/strict";
import {randomUUID} from "node:crypto";
import * as fs from "node:fs";
import {createServer} from "node:http";
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
const deviceSpec = "urnetwork-examples/javascript-embed-server";
const clientLimitMessage = "client limit reached: your network is at its client limit; see https://ur.io/services";
const clientDoesNotExist = "Client does not exist.";

// A configuration or credential problem: exit code 78.
class ConfigError extends Error {}

// The key, checked against the allocator's pattern.
function checkKey(key) {
  if (typeof key !== "string" || !keyPattern.test(key)) {
    throw new ConfigError("expected a key user:<service-user-id>[:<installation-id>]");
  }
  return key;
}

// The API origin: HTTPS, or explicit loopback HTTP for local mocks.
function apiOrigin(base) {
  let u;
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

// JSON with exact int64 byte counts: integers beyond 2^53 parse as BigInt and
// print back unchanged.
function parseJson(text) {
  return JSON.parse(text, (key, value, context) =>
    typeof value === "number" && Number.isInteger(value) && !Number.isSafeInteger(value) ? BigInt(context.source) : value);
}
function printJson(value) {
  return JSON.stringify(value, (key, member) => typeof member === "bigint" ? JSON.rawJSON(String(member)) : member);
}

// Loads the map: only version and clients, so that two tools (or the token
// server, whose map adds pending_caps) never rewrite each other's map.
function loadMap(file) {
  if (!fs.existsSync(file)) return {version: 1, clients: {}};
  const stat = fs.lstatSync(file);
  if (!stat.isFile() || stat.isSymbolicLink() || stat.size > limit ||
      (process.platform !== "win32" && (stat.mode & 0o077))) throw new ConfigError("the client map must be a private regular file (0600)");
  let map;
  try {
    map = JSON.parse(fs.readFileSync(file, "utf8"));
  } catch {
    throw new ConfigError("the client map is not JSON");
  }
  if (!map || typeof map !== "object" || Array.isArray(map) || map.version !== 1 || !map.clients ||
      Array.isArray(map.clients) || typeof map.clients !== "object") throw new ConfigError("invalid client map");
  const unknown = Object.keys(map).filter(name => name !== "version" && name !== "clients");
  if (unknown.length) throw new ConfigError(`the client map has fields this tool does not own (${unknown.join(", ")}); use a map of its own`);
  const seen = new Set();
  for (const [key, client] of Object.entries(map.clients)) {
    if (!keyPattern.test(key) || typeof client !== "string" || !idPattern.test(client) || seen.has(client)) throw new ConfigError("invalid client map entry");
    seen.add(client);
  }
  return map;
}

// Writes a file atomically with owner-only permissions.
function writePrivate(file, data) {
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
async function withLock(file, work) {
  if (!path.isAbsolute(file) || !fs.statSync(path.dirname(file), {throwIfNoEntry: false})?.isDirectory())
    throw new ConfigError("URNETWORK_CLIENT_MAP needs an absolute path in an existing directory");
  try {
    fs.mkdirSync(file + ".lock", {mode: 0o700});
  } catch (error) {
    if (error.code === "EEXIST") throw new Error("the client map is locked by another tool; retry");
    throw error;
  }
  try { return await work(); }
  finally { fs.rmdirSync(file + ".lock"); }
}

// The JWT's client_id claim; this checks consistency, not the signature.
function claimedClientId(jwt) {
  const parts = typeof jwt === "string" ? jwt.split(".") : [];
  if (parts.length !== 3 || parts.some(part => !part) || !/^[A-Za-z0-9_-]+(?![\s\S])/.test(parts[1])) throw new Error("invalid client JWT");
  let claims;
  try {
    claims = JSON.parse(Buffer.from(parts[1], "base64url").toString("utf8"));
  } catch {
    throw new Error("invalid client JWT");
  }
  return typeof claims?.client_id === "string" ? claims.client_id.toLowerCase() : "";
}

// The auth-client request for a key: a reissue sends the mapped client_id,
// a new client sends neither client_id nor source_client_id.
function authClientBody(client) {
  const body = {description, device_spec: deviceSpec};
  if (client !== undefined) {
    if (!idPattern.test(client)) throw new ConfigError("invalid mapped client ID");
    body.client_id = client;
  }
  return JSON.stringify(body);
}

// Checks an auth-client answer: a refusal throws (the client limit flags as a
// configuration error), a success must carry a client JWT whose claim matches.
function checkAuthClient(answer, expected) {
  if (!answer || typeof answer !== "object" || Array.isArray(answer)) throw new Error("invalid API response");
  if (answer.error != null) {
    if (answer.error.client_limit_exceeded || answer.error.upgrade_required) throw new ConfigError(clientLimitMessage);
    const refusal = new Error(typeof answer.error.message === "string" ? `provisioning refused: ${answer.error.message}` : "provisioning refused");
    refusal.apiMessage = answer.error.message;
    throw refusal;
  }
  if (typeof answer.client_id !== "string" || !idPattern.test(answer.client_id) || typeof answer.by_client_jwt !== "string") throw new Error("invalid API response");
  if (claimedClientId(answer.by_client_jwt) !== answer.client_id || (expected !== undefined && expected !== answer.client_id))
    throw new Error("scoped client identity mismatch");
  return {client_id: answer.client_id, by_client_jwt: answer.by_client_jwt};
}

// The cap request body: only the given fields, byte counts exact.
function capBody(client, {monthly, total, resetTotal}) {
  let body = `{"client_id":${JSON.stringify(client)}`;
  if (monthly !== undefined) body += `,"monthly_byte_limit":${monthly}`;
  if (total !== undefined) body += `,"total_byte_limit":${total}`;
  if (resetTotal) body += `,"reset_total":true`;
  return body + "}";
}

// The cap command's options: at least one, each once.
function capOptions(args) {
  const options = {};
  const byteCount = value => {
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
      options[name] = byteCount(args[i += 1]);
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
function checkCapObject(answer, client) {
  if (!answer || typeof answer !== "object" || Array.isArray(answer)) throw new Error("invalid cap answer");
  if (answer.error != null) throw new Error(typeof answer.error.message === "string" ? `the cap request was refused: ${answer.error.message}` : "the cap request was refused");
  if (client !== undefined && answer.client_id !== client) throw new Error("the cap answer is for another client");
  for (const field of ["monthly_byte_limit", "total_byte_limit"]) {
    const value = answer[field];
    if (!(value === undefined || value === null || typeof value === "bigint" || (Number.isInteger(value) && 0 <= value))) throw new Error("invalid cap answer");
  }
  return answer;
}

// The mapped client of a key, or a configuration error.
function mappedClient(map, key) {
  const client = map.clients[key];
  if (client === undefined) throw new ConfigError(`no client is mapped to ${key}; run provision first`);
  return client;
}

// An API call with the root credential: call(method, path, body) resolves
// with the parsed answer. A 401 is the root credential refused (78).
function apiCaller(origin, root) {
  if (!root || /\s/.test(root)) throw new ConfigError("set URNETWORK_ROOT_JWT to the backend's root credential");
  return async (method, apiPath, body) => {
    const response = await fetch(origin + apiPath, {
      method,
      headers: {authorization: "Bearer " + root, ...(body === undefined ? {} : {"content-type": "application/json"})},
      body, redirect: "error", signal: AbortSignal.timeout(15000),
    });
    if (response.status === 401) throw new ConfigError("the URnetwork API refused the root credential");
    const text = await response.text();
    if (text.length > limit) throw new Error("API response too large");
    if (!response.ok) throw new Error(`the URnetwork API answered HTTP ${response.status}`);
    return parseJson(text);
  };
}

// provision: reissue the key's client, or provision a new one; on "Client does
// not exist." drop the mapping and provision a new client. Writes the client
// JWT to the file, never to the output.
async function provision(key, clientJwtFile, {file, call, out}) {
  checkKey(key);
  if (typeof clientJwtFile !== "string" || clientJwtFile === "") throw new ConfigError("expected provision <key> <client-jwt-file>");
  const target = path.resolve(clientJwtFile);
  await withLock(file, async () => {
    const map = loadMap(file);
    let old = map.clients[key];
    let result;
    try {
      result = checkAuthClient(await call("POST", "/network/auth-client", authClientBody(old)), old);
    } catch (error) {
      if (old === undefined || error.apiMessage !== clientDoesNotExist) throw error;
      // deactivated after 30 days without connecting: provision a new client
      delete map.clients[key];
      writePrivate(file, JSON.stringify(map));
      old = undefined;
      result = checkAuthClient(await call("POST", "/network/auth-client", authClientBody(undefined)), undefined);
    }
    if (old === undefined) {
      if (Object.values(map.clients).includes(result.client_id)) throw new Error("client assigned to another key");
      map.clients[key] = result.client_id;
    }
    writePrivate(target, result.by_client_jwt + "\n");
    writePrivate(file, JSON.stringify(map));
    out(printJson({client_id: result.client_id}));
  });
}

// cap: post only the given fields; prints the cap object.
async function cap(key, args, {file, call, out}) {
  checkKey(key);
  const options = capOptions(args);
  const client = mappedClient(loadMap(file), key);
  out(printJson(checkCapObject(await call("POST", "/network/client-data-cap", capBody(client, options)), client)));
}

// usage: the key's cap object, read with the root credential.
async function usage(key, {file, call, out}) {
  checkKey(key);
  const client = mappedClient(loadMap(file), key);
  out(printJson(checkCapObject(await call("GET", `/network/client-data-cap?client_id=${client}`), client)));
}

// usage-all: every capped client's cap object, one per line, paging with
// limit=1000 until a null cursor or a repeated one.
async function usageAll({call, out}) {
  const seen = new Set();
  let cursor = null;
  while (true) {
    const page = await call("GET", `/network/client-data-caps?limit=1000${cursor === null ? "" : `&cursor=${encodeURIComponent(cursor)}`}`);
    if (!page || typeof page !== "object" || page.error != null || !Array.isArray(page.clients) ||
        !(page.next_cursor === null || page.next_cursor === undefined || typeof page.next_cursor === "string")) throw new Error("invalid cap list answer");
    for (const item of page.clients) out(printJson(checkCapObject(item)));
    cursor = page.next_cursor ?? null;
    if (cursor === null || seen.has(cursor)) return;
    seen.add(cursor);
  }
}

// remove: remove the key's client, then its mapping (also when the client is
// already gone).
async function remove(key, {file, call, out}) {
  checkKey(key);
  await withLock(file, async () => {
    const map = loadMap(file);
    const client = mappedClient(map, key);
    const answer = await call("POST", "/network/remove-client", JSON.stringify({client_id: client}));
    if (!answer || typeof answer !== "object") throw new Error("invalid remove answer");
    if (answer.error != null && answer.error.message !== clientDoesNotExist)
      throw new Error(typeof answer.error.message === "string" ? `removal refused: ${answer.error.message}` : "removal refused");
    delete map.clients[key];
    writePrivate(file, JSON.stringify(map));
    out(printJson({removed: client}));
  });
}

// Runs one command; resolves with the exit code. The options replace the
// environment, the API caller and the output, for the self-test.
export async function runCommand(args, {environment = process.env, call = undefined, out = line => console.log(line), err = line => console.error(line)} = {}) {
  try {
    const [command, ...rest] = args;
    if (command === "--self-test" && rest.length === 0) {
      await selfTest();
      out("embed server self-test passed");
      return exitSuccess;
    }
    const handlers = {
      provision: rest.length === 2 ? context => provision(rest[0], rest[1], context) : null,
      cap: rest.length >= 1 ? context => cap(rest[0], rest.slice(1), context) : null,
      usage: rest.length === 1 ? context => usage(rest[0], context) : null,
      "usage-all": rest.length === 0 ? context => usageAll(context) : null,
      remove: rest.length === 1 ? context => remove(rest[0], context) : null,
    };
    const handler = Object.hasOwn(handlers, command) ? handlers[command] : null;
    if (!handler) throw new ConfigError("usage: embed-server provision <key> <client-jwt-file> | cap <key> [--monthly <bytes>|null] [--total <bytes>|null] [--reset-total] | usage <key> | usage-all | remove <key> | --self-test");
    const file = environment.URNETWORK_CLIENT_MAP;
    if (!file) throw new ConfigError("set URNETWORK_CLIENT_MAP to the absolute path of this tool's private client map");
    call ??= apiCaller(apiOrigin(environment.URNETWORK_API_URL || "https://api.bringyour.com"), environment.URNETWORK_ROOT_JWT);
    await handler({file, call, out});
    return exitSuccess;
  } catch (error) {
    // one line, never a token: messages here never include the root credential or a JWT
    // the client limit line is the contract's exact text
    err(error?.message === clientLimitMessage ? clientLimitMessage : error?.message ? `embed server: ${error.message}` : "embed server failed");
    return error instanceof ConfigError ? exitConfig : exitFailure;
  }
}

// The credential-free self-test: a mock API, no network.
async function selfTest() {
  const client = "11111111-1111-1111-1111-111111111111";
  const other = "22222222-2222-2222-2222-222222222222";
  const jwt = id => "e30." + Buffer.from(JSON.stringify({client_id: id})).toString("base64url") + ".test";
  const root = "urn_" + "r".repeat(40);
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), "ur-embed-server-"));
  fs.chmodSync(directory, 0o700);
  const file = path.join(directory, "clients.json");
  const jwtFile = path.join(directory, "client.jwt");
  const key = "user:alice:33333333-3333-3333-3333-333333333333";
  const capAnswer = id => `{"client_id":"${id}","monthly_byte_limit":9223372036854775807,"monthly_used_byte_count":12,"total_byte_limit":null,"total_used_byte_count":12,"capped":false,"capped_reason":""}`;
  try {
    // a scripted API: answers[i] answers the i-th call; calls records them
    const calls = [];
    let answers = [];
    const call = async (method, apiPath, body) => {
      calls.push({method, apiPath, body: body === undefined ? undefined : body});
      const next = answers.shift();
      if (next instanceof Error) throw next;
      return parseJson(next);
    };
    const outputs = [];
    const command = async args => {
      outputs.length = 0;
      return runCommand(args, {environment: {URNETWORK_CLIENT_MAP: file, URNETWORK_ROOT_JWT: root}, call, out: line => outputs.push(line), err: line => outputs.push(line)});
    };

    // new, then reissue: client_id only on the reissue, never source_client_id
    answers = [JSON.stringify({client_id: client, by_client_jwt: jwt(client)})];
    assert.equal(await command(["provision", key, jwtFile]), exitSuccess);
    assert.deepEqual(outputs, [`{"client_id":"${client}"}`]);
    assert.deepEqual(JSON.parse(calls[0].body), {description, device_spec: deviceSpec});
    assert.equal(fs.readFileSync(jwtFile, "utf8").trim(), jwt(client));
    if (process.platform !== "win32") {
      assert.equal(fs.statSync(jwtFile).mode & 0o077, 0);
      assert.equal(fs.statSync(file).mode & 0o077, 0);
    }
    answers = [JSON.stringify({client_id: client, by_client_jwt: jwt(client)})];
    assert.equal(await command(["provision", key, jwtFile]), exitSuccess);
    assert.deepEqual(JSON.parse(calls[1].body), {description, device_spec: deviceSpec, client_id: client});
    assert.equal(JSON.parse(calls[1].body).source_client_id, undefined);
    assert.deepEqual(loadMap(file).clients, {[key]: client});
    // the token never reaches the output
    assert.ok(outputs.every(line => !line.includes(jwt(client))));

    // a deactivated client: the reissue answers "Client does not exist.", a new client is provisioned
    answers = [JSON.stringify({error: {message: clientDoesNotExist}}), JSON.stringify({client_id: other, by_client_jwt: jwt(other)})];
    assert.equal(await command(["provision", key, jwtFile]), exitSuccess);
    assert.equal(calls[3].body, authClientBody(undefined));
    assert.deepEqual(loadMap(file).clients, {[key]: other});

    // response and claim checks, the client limit (78), a refusal (1), the root credential refused (78)
    for (const [answer, code] of [
      [JSON.stringify({client_id: other, by_client_jwt: jwt(client)}), exitFailure],
      [JSON.stringify({error: {client_limit_exceeded: true, message: "Client limit exceeded."}}), exitConfig],
      [JSON.stringify({error: {upgrade_required: true, message: "Upgrade required."}}), exitConfig],
      [JSON.stringify({error: {message: "Invalid location"}}), exitFailure],
      [new ConfigError("the URnetwork API refused the root credential"), exitConfig],
    ]) {
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
    for (const [args, body] of [
      [["--monthly", "10000000000"], `{"client_id":"${other}","monthly_byte_limit":10000000000}`],
      [["--total", "null"], `{"client_id":"${other}","total_byte_limit":null}`],
      [["--monthly", "0", "--reset-total"], `{"client_id":"${other}","monthly_byte_limit":0,"reset_total":true}`],
      [["--reset-total"], `{"client_id":"${other}","reset_total":true}`],
      [["--monthly", "9223372036854775807", "--total", "5"], `{"client_id":"${other}","monthly_byte_limit":9223372036854775807,"total_byte_limit":5}`],
    ]) {
      answers = [capAnswer(other)];
      assert.equal(await command(["cap", key, ...args]), exitSuccess);
      assert.equal(calls.at(-1).body, body);
      // the cap object prints with the exact byte counts
      assert.equal(outputs[0], capAnswer(other));
    }
    answers = [capAnswer(client)];
    assert.equal(await command(["cap", key, "--monthly", "1"]), exitFailure);
    answers = [capAnswer(other)];
    assert.equal(await command(["usage", key]), exitSuccess);
    assert.equal(calls.at(-1).apiPath, `/network/client-data-cap?client_id=${other}`);

    // usage-all pages until a null cursor, and stops on a repeated cursor
    answers = [JSON.stringify({clients: [JSON.parse(capAnswer(client))], next_cursor: "a"}), JSON.stringify({clients: [JSON.parse(capAnswer(other))], next_cursor: null})];
    assert.equal(await command(["usage-all"]), exitSuccess);
    assert.equal(outputs.length, 2);
    assert.equal(calls.at(-2).apiPath, "/network/client-data-caps?limit=1000");
    assert.equal(calls.at(-1).apiPath, "/network/client-data-caps?limit=1000&cursor=a");
    answers = [JSON.stringify({clients: [], next_cursor: "b"}), JSON.stringify({clients: [], next_cursor: "b"}), JSON.stringify({clients: [], next_cursor: "c"})];
    assert.equal(await command(["usage-all"]), exitSuccess);
    assert.equal(answers.length, 1, "usage-all did not stop on the repeated cursor");

    // remove drops the mapping for both answers
    answers = [JSON.stringify({})];
    assert.equal(await command(["remove", key]), exitSuccess);
    assert.deepEqual(outputs, [`{"removed":"${other}"}`]);
    assert.deepEqual(JSON.parse(calls.at(-1).body), {client_id: other});
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
    const seen = [];
    const standIn = createServer((request, response) => {
      const chunks = [];
      request.on("data", chunk => chunks.push(chunk));
      request.on("end", () => {
        seen.push({authorization: request.headers.authorization, body: Buffer.concat(chunks).toString("utf8")});
        response.statusCode = request.url === "/refused" ? 401 : 200;
        response.end(request.url === "/refused" ? "{}" : capAnswer(client));
      });
    });
    await new Promise(resolve => standIn.listen(0, "127.0.0.1", resolve));
    try {
      const httpCall = apiCaller(`http://127.0.0.1:${standIn.address().port}`, root);
      const body = capBody(client, {monthly: "9223372036854775807"});
      const answer = await httpCall("POST", "/network/client-data-cap", body);
      assert.equal(answer.monthly_byte_limit, 9223372036854775807n);
      assert.deepEqual(seen[0], {authorization: `Bearer ${root}`, body});
      await assert.rejects(httpCall("GET", "/refused"), error => error instanceof ConfigError);
    } finally {
      await new Promise(resolve => standIn.close(resolve));
    }
  } finally {
    fs.rmSync(directory, {recursive: true, force: true});
  }
}

// Whether this module is the program's entry point.
function isEntryPoint() {
  return typeof import.meta.main === "boolean"
    ? import.meta.main
    : process.argv[1] !== undefined && fs.realpathSync(process.argv[1]) === fileURLToPath(import.meta.url);
}

if (isEntryPoint()) {
  process.exitCode = await runCommand(process.argv.slice(2));
}
