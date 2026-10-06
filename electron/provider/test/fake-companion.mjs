// A stand-in for the native companion in provider mode, for companion.test.mjs:
// it checks the environment the app gives the companion, prints the
// companion's lines, serves /provider-status with the token check, and stops
// with exit 0 when its standard input closes, as
// javascript/integration/companion/provider.go does. It never provides and
// never touches the network beyond its loopback port.
// FAKE_COMPANION_EXIT=78 makes it fail its configuration check instead.

import http from "node:http";

// Prints a line on stderr and exits with code.
function fail(code, line) {
  process.stderr.write(`${line}\n`);
  process.exit(code);
}

const env = process.env;
if (env.URNETWORK_COMPANION_PROVIDE !== "public") {
  fail(78, "URNETWORK_COMPANION_PROVIDE must be \"public\" or unset");
}
if (!env.URNETWORK_COMPANION_TOKEN || env.URNETWORK_COMPANION_TOKEN.length < 32) {
  fail(78, "set URNETWORK_COMPANION_TOKEN to a random token of at least 32 characters");
}
if (env.URNETWORK_COMPANION_STOP_ON_STDIN_CLOSE !== "1" || !env.URNETWORK_PROVIDER_STATE_DIR) {
  fail(78, "the app must set the state directory and the stop on input close");
}
console.log("Consent disclaimer: (the companion prints the contract's three lines here)");
if (env.FAKE_COMPANION_EXIT === "78") {
  // an sdk log line after the companion's own message must not replace it
  process.stderr.write("client.jwt is missing; write the scoped client JWT from your backend\n");
  process.stderr.write("E1006 19:05:00.000001 1 device_local.go:1] synthetic sdk error while closing\n");
  process.exit(78);
}
console.log("provider client 11111111-1111-1111-1111-111111111111, instance 22222222-2222-2222-2222-222222222222");

const server = http.createServer((request, response) => {
  const url = new URL(request.url, "http://127.0.0.1");
  if (url.pathname !== "/provider-status" || url.searchParams.get("token") !== env.URNETWORK_COMPANION_TOKEN) {
    response.writeHead(401).end("unauthorized");
    return;
  }
  response.writeHead(200, {"Content-Type": "application/json"});
  response.end(JSON.stringify({
    ProvideMode: 3,
    ProviderConnected: false,
    ClientLimitStatus: {Status: "client_limit_exceeded", RetryTime: 1791313500000},
    DeviceRpcStarted: false,
  }));
});
const [host, port] = env.URNETWORK_COMPANION_ADDRESS.split(":");
server.listen(Number(port), host, () => {
  const address = `${host}:${server.address().port}`;
  console.log(`companion listening at http://${address}/provider-status and ws://${address}/device-rpc`);
});
// the app closed its pipe: stop providing and exit 0
process.stdin.on("end", () => {
  server.close();
  console.log("status: stopped");
  process.exit(0);
});
process.stdin.resume();
