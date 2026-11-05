// Credential-free tests: the request shapes, the result parsing and the whole
// flow against a loopback mock API. No network.
import {test} from "node:test";
import assert from "node:assert/strict";
import {
  AUTH_CLIENT_PATH, DEFAULT_ECHO_URL, PRO_CREATES_PER_MINUTE, REMOVE_CLIENT_PATH, ROTATE_EXIT_PATH,
  Api, Refused, createBody, createProxies, describeExit, distinctReport, endpoint, mockEcho, parseCreated, run,
  selfTest, startMock,
} from "./unique-exit.ts";

test("asks for a unique exit from the best available location by default", () => {
  const body = createBody(1);
  assert.equal(body.proxy_config.unique_exit, true);
  assert.deepEqual(body.proxy_config.initial_device_state, {location: {connect_location_id: {best_available: true}}});
  assert.equal("unique_exit_replace" in body.proxy_config, false);
});

test("a country and a replace mode constrain the request", () => {
  const body = createBody(2, "DE", "manual");
  assert.deepEqual(body.proxy_config.initial_device_state, {country_code: "de"});
  assert.equal(body.proxy_config.unique_exit_replace, "manual");
  assert.throws(() => createBody(1, "Germany"));
});

test("the API URL must be an HTTPS origin", () => {
  assert.equal(endpoint("https://api.bringyour.com", AUTH_CLIENT_PATH), "https://api.bringyour.com/network/auth-client");
  assert.equal(endpoint("http://127.0.0.1:8080", AUTH_CLIENT_PATH), "http://127.0.0.1:8080/network/auth-client");
  for (const bad of ["http://api.bringyour.com", "https://user:pw@api.bringyour.com", "https://api.bringyour.com/x"])
    assert.throws(() => endpoint(bad, AUTH_CLIENT_PATH));
});

test("parses the exit and both proxy URLs", () => {
  const proxy = parseCreated({
    client_id: "019a5d6e-1d8e-7a3c-9c44-6b7f2a1e0c55",
    proxy_config_result: {
      proxy_host: "proxy.example", auth_token: "tok", socks_proxy_port: 1080,
      https_proxy_url: "https://tok.proxy.example:443",
      exit: {exit_id: "ex_1", country_code: "de", region: "Berlin", city: "Berlin", exit_since: "2026-11-05T19:00:00Z", state: "assigned"},
    },
  });
  assert.equal(proxy.exit?.exit_id, "ex_1");
  assert.equal(proxy.https_proxy_url, "https://tok.proxy.example:443");
  // the token is the SOCKS5 username, with a placeholder password
  assert.equal(proxy.socks_proxy_url, "socks5h://tok:x@proxy.example:1080");
  assert.match(describeExit(proxy.exit), /de \/ Berlin \/ Berlin/);
});

test("refusals carry the server's message", () => {
  const cases: [object, boolean, boolean, string][] = [
    [{error: {client_limit_exceeded: false, message: "no_unique_supply"}}, false, false, "no_unique_supply"],
    [{error: {client_limit_exceeded: true, message: "Too many new proxies this minute."}}, true, false, "Too many new proxies this minute."],
    [{error: {client_limit_exceeded: true, upgrade_required: true, message: "Pro only"}}, true, true, "Pro only"],
  ];
  for (const [obj, limit, upgrade, message] of cases) {
    assert.throws(() => parseCreated(obj), (e: unknown) => e instanceof Refused && e.message === message && e.limit === limit && e.upgrade === upgrade);
  }
});

test("flags exits that proxies share", () => {
  assert.deepEqual(distinctReport({a: "192.0.2.1", b: "192.0.2.2", c: "192.0.2.1"}), {distinct: 2, duplicates: {"192.0.2.1": ["a", "c"]}});
});

test("creates, rotates and removes against a mock API without printing a credential", async () => {
  const mock = await startMock();
  try {
    const lines: string[] = [];
    const api = new Api(mock.base, "root-jwt-not-printed");
    const created = await run(api, {count: 3, country: "fr", rotate: true, echoUrl: DEFAULT_ECHO_URL, keep: false,
                                     log: line => lines.push(line), echo: mockEcho, sleep: async () => {}});
    const count = (path: string) => mock.calls.filter(call => call.path === path).length;
    assert.equal(count(AUTH_CLIENT_PATH), 3);
    assert.equal(count(ROTATE_EXIT_PATH), 1);
    assert.equal(count(REMOVE_CLIENT_PATH), 3);
    for (const call of mock.calls) {
      assert.equal(call.authorization, "Bearer root-jwt-not-printed");
      if (call.path === AUTH_CLIENT_PATH) assert.deepEqual(call.body.proxy_config.initial_device_state, {country_code: "fr"});
    }
    const text = lines.join("\n");
    assert.match(text, /3 proxies, 3 distinct exit IPs/);
    assert.match(text, /rotated proxy 1/);
    assert.equal(text.includes("root-jwt-not-printed"), false);
    for (const proxy of created) {
      assert.equal(text.includes(`${proxy.auth_token}.`), false);
      assert.equal(text.includes(`${proxy.auth_token}:`), false);
    }
  } finally {
    await mock.close();
  }
});

test("--keep leaves the proxies", async () => {
  const mock = await startMock();
  try {
    await run(new Api(mock.base, "root"), {count: 2, rotate: false, echoUrl: DEFAULT_ECHO_URL, keep: true,
                                          log: () => {}, echo: mockEcho, sleep: async () => {}});
    assert.equal(mock.calls.filter(call => call.path === REMOVE_CLIENT_PATH).length, 0);
  } finally {
    await mock.close();
  }
});

test("paces creates above the Pro rate", async () => {
  const mock = await startMock();
  try {
    const slept: number[] = [];
    await createProxies(new Api(mock.base, "root"), PRO_CREATES_PER_MINUTE + 1, undefined, undefined, () => {},
                        async ms => { slept.push(ms); });
    // 21 creates, 20 waits of 60 s / 20 = 3 s between them
    assert.deepEqual(slept, new Array(PRO_CREATES_PER_MINUTE).fill(3000));
  } finally {
    await mock.close();
  }
});

test("self-test", async () => {
  const lines: string[] = [];
  await selfTest(line => lines.push(line));
  assert.ok(lines.includes("self-test ok"));
});
