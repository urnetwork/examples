import axios, {AxiosError} from "axios";
import wasmUrl from "@urnetwork/sdk/wasm/sdk.wasm?url";
import wasmExecUrl from "@urnetwork/sdk/wasm/wasm_exec.js?url";
import {openDevice, directSocketEcho} from "../device.mjs";
import {get} from "./http.mjs";

let session;
const output = document.querySelector("#output");
async function device() {
  if (!session) session = await openDevice(JSON.parse(document.querySelector("#config").value), {wasmUrl, wasmExecUrl});
  return session.device;
}
// Browser fetch/XHR have no raw socket factory. Axios has a request adapter.
const client = axios.create({adapter: async config => {
  if (config.method !== "get" || config.data) throw new Error("This example adapter implements GET only.");
  const response = {...await get(await device(), config.url, config.signal), config};
  if (config.validateStatus && !config.validateStatus(response.status)) throw new AxiosError("HTTP " + response.status, undefined, config, undefined, response);
  return response;
}});
document.querySelector("#get").onclick = async () => {
  try {const response = await client.get(document.querySelector("#url").value); output.textContent = response.status + "\n" + String(response.data);}
  catch (error) {output.textContent = error.message;}
};
document.querySelector("#direct-echo").onclick = async () => {
  try {output.textContent = await directSocketEcho(await device(), document.querySelector("#protocol").value, document.querySelector("#endpoint").value);}
  catch (error) {output.textContent = error.message;}
};
document.querySelector("#close").onclick = () => {session?.close(); session = undefined; output.textContent = "Closed";};
addEventListener("pagehide", () => session?.close());
