import {readFile} from "node:fs/promises";
import http from "node:http";
import https from "node:https";
import {URNetwork} from "@urnetwork/sdk";
import {Agent, fetch as undiciFetch} from "undici";
import axios from "axios";
import {openDevice, directSocketEcho} from "../device.mjs";
import {connector} from "../ur_node_socket.mjs";
import {clientConfig} from "../../integration/client.mjs";

if (process.argv.includes("--self-test")) {
  const sdk = await URNetwork.init();
  if (!sdk.isInitialized()) throw new Error("SDK did not initialize");
  console.log("Real SDK WASM loaded in Node; no account or network connection required.");
  sdk.close();
} else {
  const configFile = process.env.URNETWORK_DEVICE_CONFIG;
  if (!configFile) throw new Error("Set URNETWORK_DEVICE_CONFIG to a hosted Device JSON file; see ../README.md.");
  const config = clientConfig(process.env, JSON.parse(await readFile(configFile, "utf8")));
  const url = process.env.URNETWORK_HTTP_URL || "https://example.com/";
  const session = await openDevice(config);
  const httpAgent = new http.Agent({keepAlive: true});
  const httpsAgent = new https.Agent({keepAlive: true});
  httpAgent.createConnection = connector(session.device, false);
  httpsAgent.createConnection = connector(session.device, true);
  const dispatcher = new Agent({connect: connector(session.device)});
  try {
    // The hostname reaches Device.dialTls unchanged; SDK DNS and Happy Eyeballs apply.
    const response = await undiciFetch(url, {dispatcher, signal: AbortSignal.timeout(30000)});
    console.log("Undici:", response.status, (await response.text()).slice(0, 500));
    const other = await axios.get(url, {httpAgent, httpsAgent, proxy: false, timeout: 30000});
    console.log("Axios:", other.status, String(other.data).slice(0, 500));
    if (process.env.URNETWORK_TCP_ECHO) console.log("Direct Sockets TCP:", await directSocketEcho(session.device, "tcp", process.env.URNETWORK_TCP_ECHO));
    if (process.env.URNETWORK_UDP_ECHO) console.log("Direct Sockets UDP:", await directSocketEcho(session.device, "udp", process.env.URNETWORK_UDP_ECHO));
  } finally {
    await dispatcher.destroy();
    httpAgent.destroy(); httpsAgent.destroy(); session.close();
  }
}
