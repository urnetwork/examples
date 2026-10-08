// A stand-in for the native companion in embed mode, for companion.test.mjs:
// it checks the environment the app gives the companion, prints the
// companion's lines, serves /embed-status in the companion's shape with the
// token check (the licenses unless ?licenses=0), and stops with exit 0 when its
// standard input closes, as javascript/integration/companion/embed.go does. It
// never creates a device and never touches the network beyond its loopback
// port. FAKE_COMPANION_EXIT=78 makes it exit as after an auth logout instead.

import http from "node:http";

// Prints a line on stderr and exits with code.
function fail(code, line) {
  process.stderr.write(`${line}\n`);
  process.exit(code);
}

const env = process.env;
if (env.URNETWORK_COMPANION_EMBED !== "1" || env.URNETWORK_COMPANION_PROVIDE) {
  fail(78, "URNETWORK_COMPANION_EMBED must be \"1\" or unset");
}
if (!env.URNETWORK_COMPANION_TOKEN || env.URNETWORK_COMPANION_TOKEN.length < 32) {
  fail(78, "set URNETWORK_COMPANION_TOKEN to a random token of at least 32 characters");
}
if (env.URNETWORK_COMPANION_STOP_ON_STDIN_CLOSE !== "1" || !env.URNETWORK_EMBED_STATE_DIR || env.URNETWORK_ROOT_JWT !== undefined) {
  fail(78, "the app must set the state directory and the stop on input close, and pass no other URNETWORK_ setting");
}
console.log("embed client 11111111-1111-1111-1111-111111111111, instance 22222222-2222-2222-2222-222222222222");
if (env.FAKE_COMPANION_EXIT === "78") {
  // an SDK log line after the companion's own message must not replace it
  process.stderr.write("the server rejected the client credential; obtain a new scoped client JWT from your backend\n");
  process.stderr.write("E1006 19:05:00.000001 1 device_local.go:1] synthetic sdk error while closing\n");
  process.exit(78);
}

const server = http.createServer((request, response) => {
  const url = new URL(request.url, "http://127.0.0.1");
  if (url.pathname !== "/embed-status" || url.searchParams.get("token") !== env.URNETWORK_COMPANION_TOKEN) {
    response.writeHead(401).end("unauthorized");
    return;
  }
  const body = {
    ClientId: "11111111-1111-1111-1111-111111111111",
    InstanceId: "22222222-2222-2222-2222-222222222222",
    WindowStatus: {TargetSize: 4, MinSatisfied: true, ProviderStateAdded: 2},
    ClientLimitStatus: {Status: "", RetryTime: 0},
    ContractStatus: null,
  };
  if (url.searchParams.get("licenses") !== "0") {
    body.Licenses = [{Name: "github.com/urnetwork/sdk", Version: "v2026", Kind: "software", Spdx: "MPL-2.0", Text: "Mozilla Public License"}];
  }
  response.writeHead(200, {"Content-Type": "application/json"});
  response.end(JSON.stringify(body));
});
const [host, port] = env.URNETWORK_COMPANION_ADDRESS.split(":");
server.listen(Number(port), host, () => {
  const address = `${host}:${server.address().port}`;
  console.log(`companion listening at http://${address}/embed-status and ws://${address}/device-rpc`);
});
// the app closed its pipe: close the device and exit 0
process.stdin.on("end", () => {
  server.close();
  process.exit(0);
});
process.stdin.resume();
