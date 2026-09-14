// A deliberately small GET-only HTTP/1.1 transport for the browser example.
// It supports Content-Length, chunked transfer encoding, and connection-close bodies.
// General HTTP clients also need redirects, cookies, compression and pooling policy.
export function parseResponse(data) {
  const text = new TextDecoder("latin1").decode(data);
  const split = text.indexOf("\r\n\r\n");
  if (split < 0) throw new Error("Incomplete HTTP response");
  const lines = text.slice(0, split).split("\r\n");
  const status = /^HTTP\/1\.[01] ([0-9]{3})(?: (.*))?$/.exec(lines.shift());
  if (!status) throw new Error("Invalid HTTP status line");
  const headers = {};
  for (const line of lines) {
    const colon = line.indexOf(":");
    if (colon <= 0) throw new Error("Invalid HTTP header");
    const name = line.slice(0, colon).toLowerCase();
    headers[name] = line.slice(colon + 1).trim();
  }
  let body = data.slice(split + 4);
  if (headers["transfer-encoding"]?.toLowerCase() === "chunked") {
    const chunks = []; let offset = 0;
    for (;;) {
      let end = offset;
      while (end + 1 < body.length && !(body[end] === 13 && body[end + 1] === 10)) end++;
      if (end + 1 >= body.length) throw new Error("Incomplete chunk header");
      const sizeText = new TextDecoder().decode(body.slice(offset, end)).split(";")[0];
      if (!/^[0-9a-f]+$/i.test(sizeText)) throw new Error("Invalid chunk size");
      const size = parseInt(sizeText, 16); offset = end + 2;
      if (!Number.isSafeInteger(size) || size > body.length - offset) throw new Error("Incomplete chunk");
      if (size === 0) break;
      chunks.push(body.slice(offset, offset + size)); offset += size;
      if (body[offset] !== 13 || body[offset + 1] !== 10) throw new Error("Invalid chunk terminator");
      offset += 2;
    }
    body = concatenate(chunks);
  } else if (headers["content-length"] !== undefined) {
    const length = Number(headers["content-length"]);
    if (!Number.isSafeInteger(length) || length < 0 || length > body.length) throw new Error("Incomplete HTTP body");
    body = body.slice(0, length);
  }
  return {status: Number(status[1]), statusText: status[2] || "", headers, data: new TextDecoder().decode(body)};
}

function concatenate(chunks) {
  const out = new Uint8Array(chunks.reduce((n, c) => n + c.length, 0)); let offset = 0;
  for (const chunk of chunks) {out.set(chunk, offset); offset += chunk.length;}
  return out;
}

export async function get(device, address, signal) {
  const url = new URL(address);
  if (url.protocol !== "https:" || url.username || url.password) throw new Error("This example accepts HTTPS URLs without embedded credentials.");
  const hostname = url.hostname.replace(/^\[|\]$/g, "");
  const target = (hostname.includes(":") ? "[" + hostname + "]" : hostname) + ":" + (url.port || 443);
  const conn = await device.dialTls("tcp", target, {serverName: hostname, nextProtos: ["http/1.1"]}, {timeoutMillis: 30000, signal});
  const abort = () => {void conn.close();};
  signal?.addEventListener("abort", abort, {once: true});
  try {
    signal?.throwIfAborted();
    await conn.setDeadline(Date.now() + 30000);
    await conn.write(new TextEncoder().encode("GET " + url.pathname + url.search + " HTTP/1.1\r\nHost: " + url.host + "\r\nConnection: close\r\nAccept-Encoding: identity\r\n\r\n"));
    const chunks = []; let total = 0;
    for (;;) {
      const data = await conn.read(); if (data === null) break;
      total += data.length; if (total > 1024 * 1024) throw new Error("Example response exceeds 1 MiB");
      chunks.push(data);
    }
    return parseResponse(concatenate(chunks));
  } finally {signal?.removeEventListener("abort", abort); await conn.close();}
}
