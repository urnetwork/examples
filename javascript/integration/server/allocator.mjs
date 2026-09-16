// SERVER ONLY. The authenticated backend supplies user:<service-user-id> internally.
// Never pass an untrusted request field or a UR client ID as that key.
// Set URNETWORK_ROOT_JWT, URNETWORK_CLIENT_MAP (absolute path, existing service-owned
// parent) and optional URNETWORK_API_URL. A crash may leave .lock; only remove a stale lock.
import * as fs from "node:fs";
import * as path from "node:path";
import * as os from "node:os";
import {randomUUID} from "node:crypto";
import assert from "node:assert/strict";

const limit = 1 << 20;
const userPattern = /^user:[A-Za-z0-9][A-Za-z0-9_.:@-]{0,122}(?![\s\S])/;
const idPattern = /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?![\s\S])/;
function serviceUser(args) {
  if (args.length !== 1 || !userPattern.test(args[0])) throw Error("expected one user:<service-user-id>");
  return args[0];
}
function endpoint(base) {
  const u = new URL(base);
  if (u.username || u.password || u.pathname !== "/" || u.search || u.hash ||
      (u.protocol !== "https:" && !(u.protocol === "http:" && ["localhost", "127.0.0.1", "[::1]"].includes(u.hostname))))
    throw Error("API must be an HTTPS origin (explicit loopback HTTP mocks are allowed)");
  return u.origin + "/network/auth-client";
}
function requestFor(user, client = undefined) {
  serviceUser([user]);
  const body = {description: "service " + user, device_spec: "urnetwork-examples/javascript-server"};
  if (client !== undefined) {
    if (!idPattern.test(client)) throw Error("invalid mapped client ID");
    Object.assign(body, {client_id: client});
  }
  return body;
}
function parseResponse(raw, expected = undefined) {
  const obj = JSON.parse(raw);
  if (!obj || Array.isArray(obj) || obj.error != null || typeof obj.client_id !== "string" ||
      !idPattern.test(obj.client_id) || typeof obj.by_client_jwt !== "string") throw Error("invalid API response");
  const parts = obj.by_client_jwt.split(".");
  if (parts.length !== 3 || parts.some(x => !x) || !/^[A-Za-z0-9_-]+(?![\s\S])/.test(parts[1])) throw Error("invalid client JWT");
  const claims = JSON.parse(Buffer.from(parts[1], "base64url").toString("utf8"));
  if (claims.client_id !== obj.client_id || (expected !== undefined && expected !== obj.client_id))
    throw Error("scoped client identity mismatch");
  // Claim checking establishes consistency, not local signature verification.
  return {client_id: obj.client_id, by_client_jwt: obj.by_client_jwt};
}
function loadMap(file) {
  if (!fs.existsSync(file)) return {version: 1, clients: {}};
  const stat = fs.lstatSync(file);
  if (!stat.isFile() || stat.isSymbolicLink() || stat.size > limit ||
      (process.platform !== "win32" && (stat.mode & 0o077))) throw Error("mapping must be private (0600)");
  const map = JSON.parse(fs.readFileSync(file, "utf8"));
  if (map.version !== 1 || !map.clients || Array.isArray(map.clients) || typeof map.clients !== "object") throw Error("invalid mapping");
  const seen = new Set();
  for (const [user, client] of Object.entries(map.clients)) {
    serviceUser([user]);
    if (typeof client !== "string" || !idPattern.test(client) || seen.has(client)) throw Error("invalid mapped client");
    seen.add(client);
  }
  return map;
}
function saveMap(file, map) {
  const temporary = file + "." + randomUUID();
  const fd = fs.openSync(temporary, "wx", 0o600);
  try {
    fs.writeFileSync(fd, JSON.stringify(map));
    fs.fsyncSync(fd);
  } finally { fs.closeSync(fd); }
  try { fs.renameSync(temporary, file); }
  finally { if (fs.existsSync(temporary)) fs.unlinkSync(temporary); }
}
async function allocate(user, file, call) {
  if (!path.isAbsolute(file) || !fs.statSync(path.dirname(file)).isDirectory()) throw Error("mapping needs an absolute path and existing parent");
  const lock = file + ".lock";
  fs.mkdirSync(lock, {mode: 0o700}); // Exclusive across load, remote allocation and atomic save.
  try {
    const map = loadMap(file);
    const old = map.clients[user];
    const result = parseResponse(await call(requestFor(user, old)), old);
    if (old === undefined) {
      if (Object.values(map.clients).includes(result.client_id)) throw Error("client assigned to another user");
      map.clients[user] = result.client_id;
      saveMap(file, map);
    }
    return result;
  } finally { fs.rmdirSync(lock); }
}
async function post(url, root, body) {
  if (!root || /\s/.test(root)) throw Error("set backend root JWT");
  const response = await fetch(url, {method: "POST", headers: {authorization: "Bearer " + root, "content-type": "application/json"},
    body: JSON.stringify(body), redirect: "error", signal: AbortSignal.timeout(15000)});
  if (!response.ok || !response.body) throw Error("provisioning HTTP failure");
  const reader = response.body.getReader();
  const chunks = []; let size = 0;
  try {
    while (true) {
      const next = await reader.read();
      if (next.done) break;
      size += next.value.length;
      if (size > limit) throw Error("response too large");
      chunks.push(next.value);
    }
  } finally { await reader.cancel(); }
  return Buffer.concat(chunks).toString("utf8");
}
async function selfTest() {
  const client = "11111111-1111-1111-1111-111111111111";
  const jwt = "e30." + Buffer.from(JSON.stringify({client_id: client})).toString("base64url") + ".test";
  const response = JSON.stringify({client_id: client, by_client_jwt: jwt});
  const calls = [];
  const mock = async body => { calls.push(body); return response; };
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), "ur-allocator-"));
  try {
    const file = path.join(directory, "clients.json");
    assert.equal((await allocate("user:alice", file, mock)).client_id, client);
    assert.equal((await allocate("user:alice", file, mock)).by_client_jwt, jwt);
    assert.equal(calls[0].client_id, undefined);
    assert.equal(calls[0].source_client_id, undefined);
    assert.equal(calls[1].client_id, client);
    assert.equal(calls[1].source_client_id, undefined);
    assert.deepEqual(loadMap(file).clients, {"user:alice": client});
    assert.equal(endpoint("http://127.0.0.1:1234"), "http://127.0.0.1:1234/network/auth-client");
    for (const reject of [() => serviceUser([client]), () => serviceUser(["user:a", "--client-id", client]),
      () => serviceUser(["user:../a"]), () => serviceUser(["user:alice\n"]), () => endpoint("http://example.com"), () => endpoint("https://example.com/path"),
      () => parseResponse('{"error":{}}'), () => parseResponse(response, "22222222-2222-2222-2222-222222222222")]) assert.throws(reject);
  } finally { fs.rmSync(directory, {recursive: true, force: true}); }
  console.log("allocator self-test passed");
}
async function main() {
  const args = process.argv.slice(2);
  if (args.length === 1 && args[0] === "--self-test") return selfTest();
  const user = serviceUser(args);
  const url = endpoint(process.env.URNETWORK_API_URL || "https://api.bringyour.com");
  const root = process.env.URNETWORK_ROOT_JWT;
  const file = process.env.URNETWORK_CLIENT_MAP;
  if (!root || !file) throw Error("set backend environment");
  console.log(JSON.stringify(await allocate(user, file, body => post(url, root, body))));
}
main().catch(() => { console.error("allocator failed: check service key, private mapping and backend API configuration"); process.exitCode = 1; });
