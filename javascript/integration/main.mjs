import {readFile} from "node:fs/promises";
import {URNetwork} from "@urnetwork/sdk";
import {clientConfig, openDevice} from "./client.mjs";

if (process.argv.includes("--self-test")) {
  const sdk = await URNetwork.init();
  try {
    if (!sdk.isInitialized()) throw new Error("SDK did not initialize");
    console.log("SDK WASM initialized; no credentials required.");
  } finally {sdk.close();}
} else {
  const file = process.env.URNETWORK_DEVICE_CONFIG;
  if (!file) throw new Error("Set URNETWORK_DEVICE_CONFIG to the backend-issued hosted proxy configuration JSON");
  const hosted = JSON.parse(await readFile(file, "utf8"));
  const config = clientConfig(process.env, hosted);
  const session = await openDevice(URNetwork, config);
  try {console.log("Hosted client connected:", session.device.getRemoteConnected());}
  finally {session.close();}
}
