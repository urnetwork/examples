import {readFile} from "node:fs/promises";
import http from "node:http";
import https from "node:https";
import {URNetwork, type Conn, type DeviceRemote} from "@urnetwork/sdk";
import {Agent, fetch} from "undici";
import axios from "axios";
import {openDevice, streamEcho} from "./device.mjs";
import {connector} from "./ur_node_socket.mjs";

async function datagramEcho(device: DeviceRemote, address: string): Promise<Uint8Array | null> {
  const conn: Conn = await device.dial("udp", address, {timeoutMillis: 30000});
  try {
    await conn.setDeadline(Date.now() + 10000);
    await conn.write(new TextEncoder().encode("hello"));
    return await conn.read(); // null is EOF; Uint8Array(0) is an empty datagram.
  } finally {await conn.close();}
}

if (process.argv.includes("--self-test")) {
  const sdk = await URNetwork.init();
  console.log("Real SDK WASM loaded from a typed Node program:", sdk.isInitialized());
  sdk.close();
} else {
  const file = process.env.URNETWORK_DEVICE_CONFIG;
  if (!file) throw new Error("Set URNETWORK_DEVICE_CONFIG to the hosted Device JSON file described in README.md.");
  const session = await openDevice(JSON.parse(await readFile(file, "utf8")));
  const httpAgent = new http.Agent({keepAlive: true});
  const httpsAgent = new https.Agent({keepAlive: true});
  // The adapter implements Node's Duplex callback contract over async Conn I/O.
  httpAgent.createConnection = connector(session.device, false) as typeof httpAgent.createConnection;
  httpsAgent.createConnection = connector(session.device, true) as typeof httpsAgent.createConnection;
  const dispatcher = new Agent({connect: connector(session.device)});
  try {
    const url = process.env.URNETWORK_HTTP_URL || "https://example.com/";
    const response = await fetch(url, {dispatcher, signal: AbortSignal.timeout(30000)});
    console.log("Undici:", response.status, (await response.text()).slice(0, 500));
    const other = await axios.get(url, {httpAgent, httpsAgent, proxy: false, timeout: 30000});
    console.log("Axios:", other.status, other.data);
    if (process.env.URNETWORK_UDP_ECHO) console.log(await datagramEcho(session.device, process.env.URNETWORK_UDP_ECHO));
    if (process.env.URNETWORK_WEBTRANSPORT_URL) console.log(await streamEcho(session.device, process.env.URNETWORK_WEBTRANSPORT_URL));
  } finally {await dispatcher.destroy(); httpAgent.destroy(); httpsAgent.destroy(); session.close();}
}
