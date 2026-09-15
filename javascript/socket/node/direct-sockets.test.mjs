import {test} from "node:test";
import assert from "node:assert/strict";
import {attachSocketAPI} from "@urnetwork/sdk";
import {directSocketEcho} from "../device.mjs";

function echoDevice({silent = false, empty = false} = {}) {
  const reads = []; let pending, closed, releases = 0;
  const lifetime = new Promise(resolve => {closed = resolve;});
  const device = attachSocketAPI({socketOperation: async (operation, _id, arg) => {
    switch (operation) {
      case "dial": return {id: 1, localAddr: "127.0.0.1:4000", remoteAddr: "127.0.0.2:9000"};
      case "socketClosed": return lifetime;
      case "write":
        if (!silent) reads.push({data: empty ? new Uint8Array() : arg.slice(), eof: false});
        return arg.length;
      case "read": return reads.shift() || new Promise((_, reject) => {pending = reject;});
      case "addresses": return {localAddr: "127.0.0.1:4000", remoteAddr: "127.0.0.2:9000"};
      case "closeRead": case "readDeadline": pending?.(new Error("read canceled")); return;
      case "release": releases++; closed(); return;
    }
  }});
  return {device, releases: () => releases};
}

for (const protocol of ["tcp", "udp"]) {
  test(protocol + " example exchanges data through the packaged Direct Sockets interface", async () => {
    const fixture = echoDevice();
    assert.equal(await directSocketEcho(fixture.device, protocol, "echo.example:9000"), "hello from URnetwork");
    assert.equal(fixture.releases(), 1);
  });
  test(protocol + " example timeout cancels I/O and releases the connection", async () => {
    const fixture = echoDevice({silent: true});
    await assert.rejects(directSocketEcho(fixture.device, protocol, "echo.example:9000", 10), /timed out/);
    assert.equal(fixture.releases(), 1);
  });
}
test("UDP example accepts an empty reply as a datagram", async () => {
  const fixture = echoDevice({empty: true});
  assert.equal(await directSocketEcho(fixture.device, "udp", "[2001:db8::1]:9000"), "");
  assert.equal(fixture.releases(), 1);
});
