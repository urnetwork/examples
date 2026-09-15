import {test} from "node:test";
import assert from "node:assert/strict";
import {attachSocketAPI} from "@urnetwork/sdk";
import {directSocketEcho} from "./direct-sockets.js";

for (const protocol of ["tcp", "udp"] as const) {
  test("typed " + protocol + " example exchanges bytes and releases its socket", async () => {
    let reply = new Uint8Array(), releases = 0, notify!: () => void;
    const closed = new Promise<void>(resolve => {notify = resolve;});
    const device = attachSocketAPI({socketOperation: async (operation: string, _id: number, argument: unknown) => {
      if (operation === "dial" || operation === "addresses") return {id: 1, localAddr: "127.0.0.1:4000", remoteAddr: "127.0.0.2:9000"};
      if (operation === "socketClosed") return closed;
      if (operation === "write") {reply = (argument as Uint8Array).slice(); return reply.length;}
      if (operation === "read") return {data: reply, eof: false};
      if (operation === "release") {releases++; notify();}
    }});
    assert.equal(await directSocketEcho(device, protocol, "echo.example:9000"), "hello from URnetwork");
    assert.equal(releases, 1);
  });
}
