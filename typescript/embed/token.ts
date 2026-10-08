// Obtaining the client JWT (EMBED_CONTRACT.md, "Obtaining the client JWT").
// fetchClientJwt is the one place this app gets its credential, so it is easy
// to find and to replace with your own sign-in: it posts the installation's
// instance-id to the token server (go/embed/server) with the demo session as
// the bearer token, checks the answer and saves client.jwt. Without a token
// server the app uses the client.jwt already in its state.

import {type CapObject, normalizeId, parseCapObject} from "./status.ts";
import {ConfigurationError, parseClientJwtClientId, saveClientJwt} from "./state.ts";

export const exitFailure = 1;
// sysexits EX_CONFIG
export const exitConfig = 78;

// How long one token request may take.
const tokenRequestTimeoutMillis = 30 * 1000;

// the largest token server answer the app reads
const tokenAnswerByteLimit = 64 * 1024;

// The fetch function, replaceable in tests.
export type FetchFunction = typeof globalThis.fetch;

// The token server settings: its origin and the demo session token.
export interface TokenServerSettings {
  url: string;
  session: string;
}

// What fetchClientJwt obtains: the saved client JWT, its client, and the token
// server's first cap reading, or null.
export interface FetchedClientJwt {
  clientJwt: string;
  clientId: string;
  dataCap: CapObject | null;
}

// The arguments of fetchClientJwt.
export interface FetchClientJwtOptions {
  tokenServerUrl: string;
  demoSession: string;
  instanceId: string;
  stateDir: string;
  fetchFunction?: FetchFunction;
}

// A token server answer that stops the run. exitCode is the console exit code:
// 78 for 401 unauthorized and 409 installation_limit or client_limit, which a
// restart does not fix (a GUI app shows "signed out"); 1 for an unreachable
// server, a 5xx or an invalid answer (a GUI app shows "stopped").
export class TokenError extends Error {
  readonly exitCode: number;

  // A token error with its message and exit code.
  constructor(message: string, exitCode: number) {
    super(message);
    this.name = "TokenError";
    this.exitCode = exitCode;
  }

  // Whether a GUI app shows "signed out" for it.
  get signedOut(): boolean {
    return this.exitCode === exitConfig;
  }
}

// The message of an error.
function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

// Whether a hostname names the loopback interface explicitly.
function loopbackHostname(hostname: string): boolean {
  return hostname === "localhost" || hostname === "127.0.0.1" || hostname === "[::1]";
}

// The token server's origin: an HTTPS origin, or explicit loopback HTTP
// (localhost, 127.0.0.1, [::1]) for local testing, without credentials, path,
// query or fragment.
export function tokenServerOrigin(url: string): string {
  let parsed: URL;
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
export function tokenServerSettings(environment: Record<string, string | undefined>): TokenServerSettings | null {
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

// The error message of an error answer, {"error": {"code", "message"}}, or the
// fallback.
function answerErrorMessage(text: string, fallback: string): string {
  try {
    const message = (JSON.parse(text) as {error?: {message?: unknown}} | null)?.error?.message;
    return typeof message === "string" && message !== "" ? message : fallback;
  } catch {
    return fallback;
  }
}

// Obtains this installation's client JWT from the token server and saves it as
// client.jwt: POST /urnetwork/client-token with the demo session as the bearer
// token and {"installation_id": instanceId}. The answer's by_client_jwt must
// carry a client_id claim equal to its client_id. Resolves with the saved
// credential and the token server's first cap reading, or null. Rejects with a
// ConfigurationError for invalid settings and a TokenError for an answer that
// stops the run. The demo session and the client JWT are never printed.
export async function fetchClientJwt({tokenServerUrl, demoSession, instanceId, stateDir, fetchFunction = globalThis.fetch}: FetchClientJwtOptions): Promise<FetchedClientJwt> {
  const origin = tokenServerOrigin(tokenServerUrl);
  if (demoSession === "" || /\s/.test(demoSession)) {
    throw new ConfigurationError("the demo session must be one token without spaces");
  }
  if (normalizeId(instanceId) === "") {
    throw new ConfigurationError("the installation ID is not a UUID");
  }
  let response: Response;
  let text: string;
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
    text = await response.text();
    if (tokenAnswerByteLimit < Buffer.byteLength(text)) {
      throw new Error("the answer is too large");
    }
  } catch (error) {
    throw new TokenError(`the token server is unreachable: ${errorMessage(error)}`, exitFailure);
  }
  if (response.status === 401 || response.status === 409) {
    throw new TokenError(answerErrorMessage(text, `the token server answered HTTP ${response.status}`), exitConfig);
  }
  if (response.status !== 200) {
    throw new TokenError(`the token server answered HTTP ${response.status}: ${answerErrorMessage(text, "no message")}`, exitFailure);
  }
  let answer: Record<string, unknown> | null;
  try {
    answer = JSON.parse(text) as Record<string, unknown> | null;
  } catch {
    throw new TokenError("the token server answered something that is not JSON", exitFailure);
  }
  const clientId = normalizeId(answer?.client_id);
  if (answer === null || clientId === "" || typeof answer.by_client_jwt !== "string") {
    throw new TokenError("the token server answered without a client_id and by_client_jwt", exitFailure);
  }
  const clientJwt = answer.by_client_jwt.trim();
  let claimedClientId: string;
  try {
    claimedClientId = parseClientJwtClientId(clientJwt);
  } catch {
    throw new TokenError("the token server answered a client JWT without a client_id claim", exitFailure);
  }
  if (claimedClientId !== clientId) {
    throw new TokenError("the token server answered a client JWT for another client", exitFailure);
  }
  let dataCap: CapObject | null = null;
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
    throw new ConfigurationError(`could not save client.jwt: ${errorMessage(error)}`);
  }
  return {clientJwt, clientId, dataCap};
}
