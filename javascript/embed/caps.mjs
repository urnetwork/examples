// The app's own data caps (EMBED_CONTRACT.md, "Read caps and usage"): GET
// /network/client-data-cap with the client JWT reads this client's cap object.
// The app reads it at start, every 5 minutes and within 5 seconds of a change
// in the device's contract status. A server without the cap routes answers
// 404, which counts as a failed reading.

import {parseCapObject} from "./status.mjs";
import {ConfigurationError} from "./state.mjs";

// The URnetwork API of the companion's network space (ur.network, main).
export const defaultApiUrl = "https://api.bringyour.com";

// How long one cap read may take.
const capReadTimeoutMillis = 30 * 1000;

// The API origin from URNETWORK_API_URL: an HTTPS origin, or explicit loopback
// HTTP for a local mock, without credentials, path, query or fragment.
export function apiOrigin(url = defaultApiUrl) {
  let parsed;
  try {
    parsed = new URL(url);
  } catch {
    throw new ConfigurationError("URNETWORK_API_URL is not a URL");
  }
  const loopback = ["localhost", "127.0.0.1", "[::1]"].includes(parsed.hostname);
  if (parsed.username || parsed.password || parsed.pathname !== "/" || parsed.search || parsed.hash ||
      (parsed.protocol !== "https:" && !(parsed.protocol === "http:" && loopback))) {
    throw new ConfigurationError("URNETWORK_API_URL must be an HTTPS origin, or loopback HTTP for a local mock");
  }
  return parsed.origin;
}

// Reads this client's cap object with its client JWT. Resolves with the parsed
// cap object (status.mjs parseCapObject); rejects for any failure, including
// a 404 from a server without the cap routes.
export async function readDataCap({apiUrl, clientJwt, fetchFunction = globalThis.fetch}) {
  const response = await fetchFunction(`${apiOrigin(apiUrl)}/network/client-data-cap`, {
    headers: {Authorization: `Bearer ${clientJwt}`},
    redirect: "error",
    cache: "no-store",
    signal: AbortSignal.timeout(capReadTimeoutMillis),
  });
  if (!response.ok) {
    throw new Error(`the cap read answered HTTP ${response.status}`);
  }
  return parseCapObject(await response.json());
}
