import {selfTest} from "./codec.js";
const args = process.argv.slice(2);
if (args.length === 1 && args[0] === "--self-test") selfTest();
else if (args.length === 1 && args[0] === "--version") console.log("URMS v1; subprotocol 4096; hosted messaging unavailable");
else {
  console.error("Messaging unavailable: JavaScript/TypeScript currently expose hosted DeviceRemote, which has no EnableSubprotocol/SendSubprotocolBytes/QuerySubprotocols API. Its hosted DeviceLocal has AllowProvider=false and a non-visible proxy identity. A provider-capable visible DeviceLocal plus a supported raw subprotocol binding/RPC bridge is required. Use a native messages example with its own scoped client JWT. See ../../MESSAGES_PROTOCOL.md.");
  process.exitCode = 2;
}
