import {test} from "node:test";
import assert from "node:assert/strict";
import http from "node:http";
import https from "node:https";
import net from "node:net";
import tls from "node:tls";
import {once} from "node:events";
import {execFileSync} from "node:child_process";
import {mkdtempSync, readFileSync, rmSync} from "node:fs";
import {tmpdir} from "node:os";
import {join} from "node:path";
import axios from "axios";
import {Agent, fetch} from "undici";
import {connector} from "../ur_node_socket.mjs";

test("Undici and Axios use the original-host socket factory for HTTP and verified HTTPS", {timeout: 30000}, async () => {
  const directory = mkdtempSync(join(tmpdir(), "urnetwork-node-http-"));
  const key = join(directory, "key.pem"), cert = join(directory, "cert.pem");
  try {
    execFileSync("openssl", ["req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "1", "-subj", "/CN=socket.test", "-addext", "subjectAltName=DNS:socket.test", "-keyout", key, "-out", cert], {stdio: "ignore"});
    for (const secure of [false, true]) {
      const handle = (request, response) => {
        assert.equal(request.headers.host, "socket.test:8443");
        const chunks = [];
        request.on("data", data => chunks.push(data));
        request.on("end", () => {response.setHeader("content-type", "text/plain"); response.end("reply:" + Buffer.concat(chunks).toString());});
      };
      const server = secure ? https.createServer({key: readFileSync(key), cert: readFileSync(cert)}, handle) : http.createServer(handle);
      server.listen(0, "127.0.0.1"); await once(server, "listening");
      const calls = [];
      const device = {
        async open(network, address, options) {
          assert.equal(network, "tcp"); calls.push(address);
          const transport = options
            ? tls.connect({host: "127.0.0.1", port: server.address().port, servername: options.serverName, ca: readFileSync(cert)})
            : net.connect({host: "127.0.0.1", port: server.address().port});
          await once(transport, options ? "secureConnect" : "connect");
          const iterator = transport[Symbol.asyncIterator]();
          return {
            async read() {const result = await iterator.next(); return result.done ? null : new Uint8Array(result.value);},
            async write(data) {await new Promise((resolve, reject) => transport.write(data, e => e ? reject(e) : resolve())); return data.length;},
            async closeWrite() {await new Promise(resolve => transport.end(resolve));},
            async close() {transport.destroy();},
          };
        },
        dial(network, address) {return this.open(network, address);},
        dialTls(network, address, options) {return this.open(network, address, options);},
      };
      const dispatcher = new Agent({connect: connector(device)});
      const agent = secure ? new https.Agent() : new http.Agent();
      agent.createConnection = connector(device, secure);
      try {
        const url = (secure ? "https" : "http") + "://socket.test:8443/";
        const first = await fetch(url, {method: "POST", body: "undici", dispatcher, signal: AbortSignal.timeout(5000)});
        assert.equal(await first.text(), "reply:undici");
        const second = await axios.post(url, "axios", {httpAgent: agent, httpsAgent: agent, proxy: false, timeout: 5000});
        assert.equal(second.data, "reply:axios");
        assert.deepEqual(calls, ["socket.test:8443", "socket.test:8443"]);
      } finally {await dispatcher.destroy(); agent.destroy(); server.closeAllConnections(); await new Promise(resolve => server.close(resolve));}
    }
  } finally {rmSync(directory, {recursive: true, force: true});}
});
