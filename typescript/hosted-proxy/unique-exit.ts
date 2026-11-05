// SERVER ONLY: hosted proxies with unique exit IPs.
//
//   node unique-exit.ts --count 3 [--country de] [--replace auto|manual]
//                       [--rotate] [--echo-url URL] [--keep]
//   node unique-exit.ts --self-test
//
// Creates N hosted proxies with `proxy_config.unique_exit`, prints each proxy's
// exit location and `exit_id`, sends one request through each proxy's HTTPS and
// SOCKS5 URLs to an IP echo to show that the exits are distinct, optionally
// rotates one proxy's exit, and removes the proxies again unless --keep.
//
// Unique exits are a Pro feature. A Pro network holds up to 1,000 active hosted
// proxies and creates up to 20 a minute; this program paces its creates to that
// rate. Each unique-exit proxy exits through one provider whose egress IP no
// other active unique-exit proxy of the network uses. The API never returns the
// exit IP itself: it reports the exit's location and an opaque `exit_id`, and
// the IP is visible only through a request over the proxy, as below.
//
// Set URNETWORK_ROOT_JWT, the network credential (only a backend holds it), and
// optionally URNETWORK_API_URL (default https://api.bringyour.com).
import * as http from "node:http";
import type {AddressInfo} from "node:net";
import {pathToFileURL} from "node:url";

// The API's names (connect api/bringyour.yml).
export const AUTH_CLIENT_PATH = "/network/auth-client";
export const ROTATE_EXIT_PATH = "/network/proxy/rotate-exit";
export const REMOVE_CLIENT_PATH = "/network/remove-client";

// Pro plan limits on hosted proxies (server model/network_client_proxy_limit.go).
export const PRO_ACTIVE_PROXY_CAP = 1000;
export const PRO_CREATES_PER_MINUTE = 20;

// Any password works on the SOCKS5 leg; the token is the username. Some clients
// send no credentials at all without a password, so a placeholder is always set.
export const SOCKS_PLACEHOLDER_PASSWORD = "x";

export const DEFAULT_API_URL = "https://api.bringyour.com";
export const DEFAULT_ECHO_URL = "https://api.ipify.org?format=json";

const idPattern = /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/;
const countryPattern = /^[A-Za-z]{2}$/;
export const REPLACE_MODES = ["auto", "manual"] as const;
export type ReplaceMode = (typeof REPLACE_MODES)[number];

// `proxy_config_result.exit`: the proxy's current exit, never its IP.
export interface ProxyExit {
  exit_id?: string;
  country_code?: string;
  region?: string;
  city?: string;
  exit_since?: string;
  state?: string; // "assigned", "replacing" or "no_supply"
}

export interface CreatedProxy {
  client_id: string;
  auth_token: string;
  https_proxy_url?: string;
  socks_proxy_url?: string;
  exit: ProxyExit | null;
}

// The API refused the call. `limit` is a hard cap or a rate limit; `upgrade`
// asks for a plan upgrade.
export class Refused extends Error {
  limit: boolean;
  upgrade: boolean;
  constructor(message: string, limit = false, upgrade = false) {
    super(message);
    this.limit = limit;
    this.upgrade = upgrade;
  }
}

export function endpoint(base: string, path: string): string {
  const u = new URL(base);
  if (u.username || u.password || u.pathname !== "/" || u.search || u.hash ||
      (u.protocol !== "https:" && !(u.protocol === "http:" && ["localhost", "127.0.0.1", "[::1]"].includes(u.hostname))))
    throw Error("API URL must be an HTTPS origin; HTTP is allowed only for explicit loopback mocks");
  return u.origin + path;
}

// The /network/auth-client request for one unique-exit proxy. Without a country
// the exit is drawn from any location (best available).
export function createBody(index: number, country?: string, replace?: ReplaceMode) {
  let state: object;
  if (country !== undefined) {
    if (!countryPattern.test(country)) throw Error("country must be a two-letter country code");
    state = {country_code: country.toLowerCase()};
  } else {
    state = {location: {connect_location_id: {best_available: true}}};
  }
  const proxyConfig: Record<string, unknown> = {unique_exit: true, initial_device_state: state};
  if (replace !== undefined) {
    if (!REPLACE_MODES.includes(replace)) throw Error("replace must be auto or manual");
    proxyConfig.unique_exit_replace = replace;
  }
  return {description: `unique exit ${index}`, device_spec: "urnetwork-examples/typescript-unique-exit", proxy_config: proxyConfig};
}

export function refusal(obj: any): Refused | null {
  if (obj?.error == null) return null;
  const error = obj.error;
  if (typeof error !== "object") return new Refused("the API refused the call");
  return new Refused(String(error.message || "the API refused the call"), Boolean(error.client_limit_exceeded), Boolean(error.upgrade_required));
}

// The proxy's client id, proxy URLs and exit from an auth-client result.
export function parseCreated(obj: any): CreatedProxy {
  const refused = refusal(obj);
  if (refused) throw refused;
  const result = obj?.proxy_config_result;
  if (!result || typeof result !== "object" || typeof obj.client_id !== "string" || !idPattern.test(obj.client_id))
    throw Error("missing proxy result");
  const token = result.auth_token, host = result.proxy_host;
  if (typeof token !== "string" || !token || typeof host !== "string" || !host) throw Error("missing proxy credentials");
  const socksPort = result.socks_proxy_port;
  return {
    client_id: obj.client_id,
    auth_token: token,
    https_proxy_url: typeof result.https_proxy_url === "string" ? result.https_proxy_url : undefined,
    socks_proxy_url: Number.isInteger(socksPort) && socksPort > 0
      ? `socks5h://${token}:${SOCKS_PLACEHOLDER_PASSWORD}@${host}:${socksPort}` : undefined,
    exit: result.exit && typeof result.exit === "object" ? result.exit : null,
  };
}

export function describeExit(exit: ProxyExit | null | undefined): string {
  if (!exit) return "no unique exit reported (is the network on Pro, and the server current?)";
  const place = [exit.country_code, exit.region, exit.city].filter(Boolean).join(" / ");
  return `${place || "unknown location"} (exit_id ${exit.exit_id}, ${exit.state} since ${exit.exit_since})`;
}

// `observed` maps a proxy label to the exit IP seen through it. Returns the
// number of distinct IPs and each IP seen more than once with its proxies.
export function distinctReport(observed: Record<string, string>): {distinct: number; duplicates: Record<string, string[]>} {
  const byIp = new Map<string, string[]>();
  for (const [label, ip] of Object.entries(observed)) {
    if (ip) byIp.set(ip, [...(byIp.get(ip) ?? []), label]);
  }
  const duplicates: Record<string, string[]> = {};
  for (const [ip, labels] of byIp) if (labels.length > 1) duplicates[ip] = labels;
  return {distinct: byIp.size, duplicates};
}

export class Api {
  base: string;
  root: string;
  timeoutMs: number;
  constructor(base: string, root: string, timeoutMs = 30_000) {
    if (!root || /\s/.test(root)) throw Error("set a valid URNETWORK_ROOT_JWT on the backend");
    this.base = base;
    this.root = root;
    this.timeoutMs = timeoutMs;
  }

  async post(path: string, body: object): Promise<any> {
    const response = await fetch(endpoint(this.base, path), {
      method: "POST",
      headers: {Authorization: `Bearer ${this.root}`, "Content-Type": "application/json"},
      body: JSON.stringify(body),
      redirect: "error",
      signal: AbortSignal.timeout(this.timeoutMs),
    });
    if (!response.ok) throw new Refused(`HTTP ${response.status} from ${path}`);
    return response.json();
  }

  async create(body: object): Promise<CreatedProxy> {
    return parseCreated(await this.post(AUTH_CLIENT_PATH, body));
  }

  async rotate(clientId: string): Promise<ProxyExit | null> {
    const obj = await this.post(ROTATE_EXIT_PATH, {client_id: clientId});
    const refused = refusal(obj);
    if (refused) throw refused;
    return obj?.exit ?? null;
  }

  async remove(clientId: string): Promise<void> {
    const refused = refusal(await this.post(REMOVE_CLIENT_PATH, {client_id: clientId}));
    if (refused) throw refused;
  }
}

export type Echo = (proxyUrl: string, echoUrl: string) => Promise<string>;

function ipFrom(text: string): string {
  try {
    const value = JSON.parse(text);
    if (value && typeof value.ip === "string") return value.ip;
  } catch {
    // a plain-text echo
  }
  return text.trim();
}

// The exit IP seen through one proxy URL. The HTTPS leg uses undici's
// ProxyAgent, which sends the token in the proxy's TLS server name; the SOCKS5
// leg uses socks-proxy-agent with the token as the username.
export const echoThrough: Echo = async (proxyUrl, echoUrl) => {
  if (proxyUrl.startsWith("socks")) {
    const {SocksProxyAgent} = await import("socks-proxy-agent");
    const https = await import("node:https");
    const agent = new SocksProxyAgent(proxyUrl);
    const text = await new Promise<string>((resolve, reject) => {
      const request = https.get(echoUrl, {agent, timeout: 30_000}, response => {
        let body = "";
        response.setEncoding("utf8");
        response.on("data", chunk => (body += chunk));
        response.on("end", () => resolve(body));
      });
      request.on("timeout", () => request.destroy(Error("timeout")));
      request.on("error", reject);
    });
    return ipFrom(text);
  }
  const {ProxyAgent, fetch: proxiedFetch} = await import("undici");
  const response = await proxiedFetch(echoUrl, {dispatcher: new ProxyAgent({uri: proxyUrl}), signal: AbortSignal.timeout(30_000)});
  return ipFrom(await response.text());
};

export type Sleep = (ms: number) => Promise<void>;
const realSleep: Sleep = ms => new Promise(resolve => setTimeout(resolve, ms));

// Creates `count` unique-exit proxies at the Pro creation rate. Stops at the
// first refusal that waiting cannot fix, and returns what was created.
export async function createProxies(api: Api, count: number, country: string | undefined,
                                    replace: ReplaceMode | undefined, log: (line: string) => void,
                                    sleep: Sleep = realSleep): Promise<CreatedProxy[]> {
  const paceMs = count > PRO_CREATES_PER_MINUTE ? 60_000 / PRO_CREATES_PER_MINUTE : 0;
  const created: CreatedProxy[] = [];
  for (let index = 1; index <= count; index++) {
    if (index > 1 && paceMs) await sleep(paceMs);
    const body = createBody(index, country, replace);
    let proxy: CreatedProxy;
    try {
      proxy = await api.create(body);
    } catch (e) {
      if (e instanceof Refused && e.limit && /minute/i.test(e.message)) {
        log(`create ${index}: ${e.message}; waiting a minute`);
        await sleep(60_000);
        proxy = await api.create(body);
      } else if (e instanceof Refused) {
        log(`create ${index} refused: ${e.message}`);
        break;
      } else {
        throw e;
      }
    }
    created.push(proxy);
    log(`proxy ${index} ${proxy.client_id}: ${describeExit(proxy.exit)}`);
  }
  return created;
}

export interface RunOptions {
  count: number;
  country?: string;
  replace?: ReplaceMode;
  rotate: boolean;
  echoUrl: string;
  keep: boolean;
  log: (line: string) => void;
  echo?: Echo;
  sleep?: Sleep;
}

export async function run(api: Api, options: RunOptions): Promise<CreatedProxy[]> {
  const {log, echo = echoThrough} = options;
  const created = await createProxies(api, options.count, options.country, options.replace, log, options.sleep);
  try {
    const observed: Record<string, string> = {};
    const perProxy = new Map<string, Set<string>>();
    for (const [i, proxy] of created.entries()) {
      for (const [leg, url] of [["https", proxy.https_proxy_url], ["socks", proxy.socks_proxy_url]] as const) {
        if (!url) continue;
        try {
          const ip = await echo(url, options.echoUrl);
          observed[`proxy ${i + 1} ${leg}`] = ip;
          perProxy.set(`proxy ${i + 1}`, (perProxy.get(`proxy ${i + 1}`) ?? new Set()).add(ip));
        } catch (e) {
          // one failed request must not hide the others; never print the URL
          log(`proxy ${i + 1} ${leg} request failed: ${(e as Error).name}`);
        }
      }
    }
    // both legs of one proxy share its exit, so count by proxy
    const exits: Record<string, string> = {};
    for (const [label, ips] of perProxy) if (ips.size === 1) exits[label] = [...ips][0];
    for (const label of Object.keys(observed).sort()) log(`${label} exits from ${observed[label]}`);
    const {distinct, duplicates} = distinctReport(exits);
    log(`${Object.keys(exits).length} proxies, ${distinct} distinct exit IPs`);
    for (const [ip, labels] of Object.entries(duplicates)) log(`duplicate exit ${ip}: ${labels.join(", ")}`);
    if (options.rotate && created.length) {
      log(`rotated proxy 1: ${describeExit(await api.rotate(created[0].client_id))}`);
    }
    return created;
  } finally {
    if (!options.keep) {
      for (const proxy of created) await api.remove(proxy.client_id);
      log(`removed ${created.length} proxies`);
    }
  }
}

// A loopback stand-in for the API, for --self-test and the tests: it checks the
// request shapes and answers as the server does.
export interface MockCall {path: string; authorization: string | undefined; body: any}

export async function startMock(): Promise<{base: string; calls: MockCall[]; close: () => Promise<void>}> {
  const calls: MockCall[] = [];
  let serial = 0;
  const server = http.createServer((request, response) => {
    let raw = "";
    request.setEncoding("utf8");
    request.on("data", chunk => (raw += chunk));
    request.on("end", () => {
      const body = JSON.parse(raw || "{}");
      calls.push({path: request.url ?? "", authorization: request.headers.authorization, body});
      const n = ++serial;
      let answer: object;
      if (request.url === AUTH_CLIENT_PATH) {
        answer = body?.proxy_config?.unique_exit !== true
          ? {error: {client_limit_exceeded: false, message: "expected unique_exit"}}
          : {
              client_id: `019a5d6e-1d8e-7a3c-9c44-${n.toString(16).padStart(12, "0")}`,
              proxy_config_result: {
                keepalive_seconds: 300, proxy_host: "proxy.example", auth_token: `token${n}`,
                https_proxy_url: `https://token${n}.proxy.example:443`, socks_proxy_port: 1080,
                exit: {exit_id: `ex_${n}`, country_code: "de", region: "Berlin", city: "Berlin",
                       exit_since: "2026-11-05T19:00:00Z", state: "assigned"},
              },
            };
      } else if (request.url === ROTATE_EXIT_PATH) {
        answer = {exit: {exit_id: `ex_rotated_${n}`, country_code: "fr", state: "assigned"}};
      } else if (request.url === REMOVE_CLIENT_PATH) {
        answer = {};
      } else {
        response.writeHead(404).end();
        return;
      }
      response.writeHead(200, {"Content-Type": "application/json"}).end(JSON.stringify(answer));
    });
  });
  await new Promise<void>(resolve => server.listen(0, "127.0.0.1", resolve));
  const {port} = server.address() as AddressInfo;
  return {
    base: `http://127.0.0.1:${port}`,
    calls,
    close: () => new Promise<void>(resolve => {
      server.closeAllConnections();
      server.close(() => resolve());
    }),
  };
}

// The exit a mock proxy reports: one documentation address per proxy, the same
// on its HTTPS and SOCKS5 legs.
export const mockEcho: Echo = async proxyUrl => `192.0.2.${/token(\d+)/.exec(proxyUrl)?.[1]}`;

export async function selfTest(log: (line: string) => void = console.log): Promise<void> {
  const mock = await startMock();
  try {
    const api = new Api(mock.base, "self-test-root-jwt");
    const created = await run(api, {count: 3, replace: "auto", rotate: true, echoUrl: DEFAULT_ECHO_URL, keep: false,
                                     log, echo: mockEcho, sleep: async () => {}});
    const paths = mock.calls.map(call => call.path);
    const count = (path: string) => paths.filter(p => p === path).length;
    if (count(AUTH_CLIENT_PATH) !== 3 || count(ROTATE_EXIT_PATH) !== 1 || count(REMOVE_CLIENT_PATH) !== 3 || created.length !== 3)
      throw Error(`unexpected calls: ${paths.join(", ")}`);
    log("self-test ok");
  } finally {
    await mock.close();
  }
}

function usage(): never {
  console.error("usage: node unique-exit.ts --count N [--country CC] [--replace auto|manual] [--rotate] [--echo-url URL] [--keep] | --self-test");
  process.exit(64);
}

export async function main(argv: string[]): Promise<void> {
  if (argv.includes("--self-test")) return selfTest();
  const options: RunOptions = {count: 3, rotate: false, echoUrl: DEFAULT_ECHO_URL, keep: false, log: line => console.log(line)};
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    const value = () => argv[++i] ?? usage();
    if (arg === "--count") options.count = Number(value());
    else if (arg === "--country") options.country = value();
    else if (arg === "--replace") {
      const mode = value();
      if (!(REPLACE_MODES as readonly string[]).includes(mode)) usage();
      options.replace = mode as ReplaceMode;
    }
    else if (arg === "--rotate") options.rotate = true;
    else if (arg === "--echo-url") options.echoUrl = value();
    else if (arg === "--keep") options.keep = true;
    else usage();
  }
  if (!Number.isInteger(options.count) || options.count < 1 || options.count > PRO_ACTIVE_PROXY_CAP) usage();
  const api = new Api(process.env.URNETWORK_API_URL ?? DEFAULT_API_URL, process.env.URNETWORK_ROOT_JWT ?? "");
  await run(api, options);
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main(process.argv.slice(2)).catch(error => {
    console.error(error instanceof Error ? error.message : String(error));
    process.exit(1);
  });
}
