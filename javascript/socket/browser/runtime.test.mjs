import {test} from "node:test";
import assert from "node:assert/strict";
import {createServer} from "vite";
import {chromium} from "playwright";

test("Browser loads real SDK WASM and closes it without account credentials", {timeout: 60000}, async () => {
  const server = await createServer({root: new URL("..", import.meta.url).pathname, server: {host: "127.0.0.1", port: 0}});
  await server.listen();
  let browser;
  try {
    browser = await chromium.launch({headless: true, executablePath: process.env.BROWSER_EXECUTABLE || undefined});
    const page = await browser.newPage();
    const errors = [];
    page.on("pageerror", error => errors.push(error.message));
    await page.goto("http://127.0.0.1:" + server.httpServer.address().port);
    assert.equal(await page.locator("#get").count(), 1);
    assert.equal(await page.locator("#direct-echo").count(), 1);
    const result = await page.evaluate(async () => {
      const exported = await import("/node_modules/@urnetwork/sdk/dist/index.js");
      const {URNetwork, attachSocketAPI} = exported;
      const sdk = await URNetwork.init({
        wasmUrl: "/node_modules/@urnetwork/sdk/wasm/sdk.wasm",
        wasmExecUrl: "/node_modules/@urnetwork/sdk/wasm/wasm_exec.js",
      });
      const initialized = sdk.isInitialized();
      const factory = typeof globalThis.URnetworkNewPlatformDeviceRemote;
      const directResults = [];
      for (const udp of [false, true]) {
        let notify;
        const lifetime = new Promise(resolve => {notify = resolve;});
        const device = attachSocketAPI({socketOperation: async op => {
          if (op === "dial" || op === "addresses") return {id: 1, localAddr: "127.0.0.1:1000", remoteAddr: "127.0.0.2:7"};
          if (op === "socketClosed") return lifetime;
          if (op === "read") return {data: udp ? new Uint8Array() : Uint8Array.of(8), eof: !udp};
          if (op === "release") notify();
        }});
        const {TCPSocket, UDPSocket} = device.directSockets;
        const socket = udp ? new UDPSocket({remoteAddress: "host", remotePort: 7}) : new TCPSocket("host", 7);
        const {readable} = await socket.opened;
        const reader = udp ? readable.getReader() : readable.getReader({mode: "byob"});
        const result = udp ? await reader.read() : await reader.read(new Uint8Array(10));
        directResults.push(udp ? result.value.data.length : result.value[0]);
        await reader.cancel(); reader.releaseLock(); await socket.close(); await socket.closed;
      }
      sdk.close();
      await new Promise(resolve => setTimeout(resolve, 30));
      return {initialized, factory, directResults, removedSessionAPI: !("WebTransport" in exported)};
    });
    assert.deepEqual(result, {initialized: true, factory: "function", directResults: [8, 0], removedSessionAPI: true});
    assert.deepEqual(errors, []);
  } finally {await browser?.close(); await server.close();}
});
