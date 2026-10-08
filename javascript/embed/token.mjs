// Obtaining the client JWT (EMBED_CONTRACT.md, "Obtaining the client JWT").
// fetchClientJwt is the one place this app gets its credential, so it is easy
// to find and to replace with your own sign-in: it posts the installation's
// instance-id to the token server (go/embed/server) with the demo session as
// the bearer token, checks the answer and saves client.jwt. Without a token
// server the app uses the client.jwt already in its state.

import {parseCapObject, normalizeId} from "./status.mjs";
import {ConfigurationError, parseClientJwtClientId, saveClientJwt} from "./state.mjs";

export const exitFailure = 1;
// sysexits EX_CONFIG
export const exitConfig = 78;

// How long one token request may take.
const tokenRequestTimeoutMillis = 30 * 1000;

// the largest token server answer the app reads
const tokenAnswerByteLimit = 64 * 1024;

// A token server answer that stops the run. exitCode is the console exit code:
// 78 for 401 unauthorized and 409 installation_limit or client_limit, which a
// restart does not fix (a GUI app shows "signed out"); 1 for an unreachable
// server, a 5xx or an invalid answer (a GUI app shows "stopped").
export class TokenError extends Error {
  // A token error with its message and exit code.
  constructor(message, exitCode) {
    super(message);
    this.name = "TokenError";
    this.exitCode = exitCode;
  }

  // Whether a GUI app shows "signed out" for it.
  get signedOut() {
    return this.exitCode === exitConfig;
  }
}

// Whether a hostname names the loopback interface explicitly.
function loopbackHostname(hostname) {
  return hostname === "localhost" || hostname === "127.0.0.1" || hostname === "[::1]";
}

// The token server's origin: an HTTPS origin, or explicit loopback HTTP
// (localhost, 127.0.0.1, [::1]) for local testing, without credentials, path,
// query or fragment.
export function tokenServerOrigin(url) {
  let parsed;
  try {
    parsed = new URL(url);
  } catch {
    throw new ConfigurationError("URNETWORK_TOKEN_SERVER_URL is not a URL");
  }
  if (parsed.username || parsed.password || parsed.pathname !== "/" || parsed.search || parsed.hash ||
      (parsed.protocol !== "https:" && !(parsed.protocol === "http:" && loopbackHostname(parsed.hostname)))) {
    throw new ConfigurationError("URNETWORK_TOKEN_SERVER_URL must be an HTTPS origin, or loopback HTTP for local testing");
  }
  return parsed.origin;
}

// The token server settings of a console app: both or neither of
// URNETWORK_TOKEN_SERVER_URL and URNETWORK_DEMO_SESSION. Returns null without a
// token server.
export function tokenServerSettings(environment) {
  const url = environment.URNETWORK_TOKEN_SERVER_URL ?? "";
  const session = environment.URNETWORK_DEMO_SESSION ?? "";
  if (url === "" && session === "") {
    return null;
  }
  if (url === "" || session === "") {
    throw new ConfigurationError("set both URNETWORK_TOKEN_SERVER_URL and URNETWORK_DEMO_SESSION, or neither");
  }
  return {url, session};
}

// Reads a response body as text, up to the limit.
async function boundedText(response) {
  const text = await response.text();
  if (tokenAnswerByteLimit < Buffer.byteLength(text)) {
    throw new Error("the answer is too large");
  }
  return text;
}

// The error message of an error answer, {"error": {"code", "message"}}, or the
// fallback.
function errorMessage(text, fallback) {
  try {
    const message = JSON.parse(text)?.error?.message;
    return typeof message === "string" && message !== "" ? message : fallback;
  } catch {
    return fallback;
  }
}

// Obtains this installation's client JWT from the token server and saves it as
// client.jwt: POST /urnetwork/client-token with the demo session as the bearer
// token and {"installation_id": instanceId}. The answer's by_client_jwt must
// carry a client_id claim equal to its client_id. Resolves with {clientJwt,
// clientId, dataCap}, where dataCap is the parsed cap object or null (the
// token server answers null when it could not read the caps). Rejects with a
// ConfigurationError for invalid settings and a TokenError for an answer that
// stops the run. The demo session and the client JWT are never printed.
export async function fetchClientJwt({tokenServerUrl, demoSession, instanceId, stateDir, fetchFunction = globalThis.fetch}) {
  const origin = tokenServerOrigin(tokenServerUrl);
  if (typeof demoSession !== "string" || demoSession === "" || /\s/.test(demoSession)) {
    throw new ConfigurationError("the demo session must be one token without spaces");
  }
  if (normalizeId(instanceId) === "") {
    throw new ConfigurationError("the installation ID is not a UUID");
  }
  let response;
  let text;
  try {
    response = await fetchFunction(`${origin}/urnetwork/client-token`, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${demoSession}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({installation_id: normalizeId(instanceId)}),
      redirect: "error",
      cache: "no-store",
      signal: AbortSignal.timeout(tokenRequestTimeoutMillis),
    });
    text = await boundedText(response);
  } catch (error) {
    throw new TokenError(`the token server is unreachable: ${error.message}`, exitFailure);
  }
  if (response.status === 401 || response.status === 409) {
    throw new TokenError(errorMessage(text, `the token server answered HTTP ${response.status}`), exitConfig);
  }
  if (response.status !== 200) {
    throw new TokenError(`the token server answered HTTP ${response.status}: ${errorMessage(text, "no message")}`, exitFailure);
  }
  let answer;
  try {
    answer = JSON.parse(text);
  } catch {
    throw new TokenError("the token server answered something that is not JSON", exitFailure);
  }
  const clientId = normalizeId(answer?.client_id);
  if (clientId === "" || typeof answer.by_client_jwt !== "string") {
    throw new TokenError("the token server answered without a client_id and by_client_jwt", exitFailure);
  }
  const clientJwt = answer.by_client_jwt.trim();
  let claimedClientId;
  try {
    claimedClientId = parseClientJwtClientId(clientJwt);
  } catch {
    throw new TokenError("the token server answered a client JWT without a client_id claim", exitFailure);
  }
  if (claimedClientId !== clientId) {
    throw new TokenError("the token server answered a client JWT for another client", exitFailure);
  }
  let dataCap = null;
  if (answer.data_cap !== undefined && answer.data_cap !== null) {
    try {
      dataCap = parseCapObject(answer.data_cap);
    } catch {
      // a cap object that does not parse is no first reading; the app reads
      // the caps itself
    }
  }
  try {
    await saveClientJwt(stateDir, clientJwt);
  } catch (error) {
    throw new ConfigurationError(`could not save client.jwt: ${error.message}`);
  }
  return {clientJwt, clientId, dataCap};
}
