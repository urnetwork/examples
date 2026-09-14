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
    assert.equal(await page.locator("#wt").count(), 1);
    const result = await page.evaluate(async () => {
      const {URNetwork} = await import("/node_modules/@urnetwork/sdk/dist/index.js");
      const sdk = await URNetwork.init({
        wasmUrl: "/node_modules/@urnetwork/sdk/wasm/sdk.wasm",
        wasmExecUrl: "/node_modules/@urnetwork/sdk/wasm/wasm_exec.js",
      });
      const initialized = sdk.isInitialized();
      const factory = typeof globalThis.URnetworkNewPlatformDeviceRemote;
      sdk.close();
      await new Promise(resolve => setTimeout(resolve, 30));
      return {initialized, factory};
    });
    assert.deepEqual(result, {initialized: true, factory: "function"});
    assert.deepEqual(errors, []);
  } finally {await browser?.close(); await server.close();}
});
