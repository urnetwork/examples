import {readFile} from "node:fs/promises";
import * as codec from "./codec.ts";
import {runMessages, validateCommand} from "../../javascript/messages/application.mjs";
import {clientConfig} from "../integration/client.mjs";
import {openMessageDevice} from "../integration/companion.mjs";

async function main(): Promise<void> {
  const args = process.argv.slice(2);
  if (args.length === 1 && args[0] === "--self-test") {codec.selfTest(); return;}
  if (args.length === 1 && args[0] === "--version") {console.log("URMS v1; subprotocol 4096; native companion RPC"); return;}
  validateCommand(args);
  const file = process.env.URNETWORK_DEVICE_CONFIG;
  if (!file) throw new Error("Set URNETWORK_DEVICE_CONFIG to JSON containing apiUrl, platformUrl, and companionUrl; start javascript/integration/companion first");
  const config = clientConfig(process.env, JSON.parse(await readFile(file, "utf8")));
  const {URNetwork} = await import("@urnetwork/sdk");
  const session = await openMessageDevice(URNetwork, config, process.env.URNETWORK_COMPANION_TOKEN);
  const controller = new AbortController();
  const stop = () => controller.abort();
  process.once("SIGINT", stop); process.once("SIGTERM", stop);
  try {await runMessages(session.device, codec, args, {signal: controller.signal});}
  finally {process.removeListener("SIGINT", stop); process.removeListener("SIGTERM", stop); session.close();}
}
try {await main();} catch (error) {console.error(error instanceof Error ? error.message : error); process.exitCode = 1;}
