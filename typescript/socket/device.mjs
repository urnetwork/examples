import {URNetwork} from "@urnetwork/sdk";
import {openDevice as openIntegratedDevice} from "../integration/client.mjs";
export function openDevice(config, wasmOptions = {}) {return openIntegratedDevice(URNetwork, config, wasmOptions);}
