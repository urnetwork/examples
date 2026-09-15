import type { SocketDevice, TCPSocket, UDPSocket } from "@urnetwork/sdk";

/** Use the same constructor signatures as browser Direct Sockets. */
export async function directSocketEcho(
  device: Pick<SocketDevice, "directSockets">,
  protocol: "tcp" | "udp",
  endpoint: string,
  timeoutMillis = 30000,
): Promise<string> {
  const address = new URL(protocol + "://" + endpoint);
  if (!address.hostname || !address.port || address.username || address.password || address.search || address.hash || (address.pathname && address.pathname !== "/")) throw new TypeError("Expected host:port or [IPv6]:port");
  const host = address.hostname.replace(/^\[|\]$/g, ""), port = Number(address.port);
  const payload = new TextEncoder().encode("hello from URnetwork");
  const { TCPSocket, UDPSocket } = device.directSockets;
  if (protocol === "udp") {
    const socket: UDPSocket = new UDPSocket({ remoteAddress: host, remotePort: port });
    return exchange(socket, { data: payload }, message => message.data, true, timeoutMillis);
  }
  const socket: TCPSocket = new TCPSocket(host, port);
  return exchange(socket, payload, bytes => bytes, false, timeoutMillis);
}

async function exchange<R, W>(
  socket: {
    opened: Promise<{ readable: ReadableStream<R>; writable: WritableStream<W> }>;
    closed: Promise<void>;
    close(): Promise<void>;
  },
  message: W,
  data: (value: R) => Uint8Array,
  udp: boolean,
  timeoutMillis: number,
): Promise<string> {
  const { readable, writable } = await socket.opened;
  const reader = readable.getReader(), writer = writable.getWriter();
  let timer: ReturnType<typeof setTimeout> | undefined;
  try {
    const echo = async (): Promise<string> => {
      await writer.write(message);
      if (!udp) await writer.close();
      const parts: Uint8Array[] = []; let received = 0;
      const expected = new TextEncoder().encode("hello from URnetwork").length;
      while (received < expected) {
        const result = await reader.read();
        if (result.done) throw new Error("Echo closed before the whole reply");
        const chunk = data(result.value);
        parts.push(chunk); received += chunk.length;
        if (udp) break; // One UDPMessage is one datagram, even if empty.
      }
      const reply = new Uint8Array(received); let offset = 0;
      for (const part of parts) { reply.set(part, offset); offset += part.length; }
      return new TextDecoder().decode(reply);
    };
    return await Promise.race([echo(), new Promise<never>((_, reject) => {
      timer = setTimeout(() => reject(new Error("Direct Sockets echo timed out")), timeoutMillis);
    })]);
  } finally {
    clearTimeout(timer);
    await Promise.allSettled([reader.cancel(), writer.abort()]);
    reader.releaseLock(); writer.releaseLock();
    await socket.close(); await socket.closed;
  }
}
