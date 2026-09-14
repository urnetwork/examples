import { Duplex } from "node:stream";

// Adapt an initialized SDK Conn to the Node HTTP/1.1 socket contract.
export class UrNodeSocket extends Duplex {
  constructor(conn, encrypted = false) {
    super({allowHalfOpen: false});
    this.conn = conn;
    this.encrypted = encrypted;
    this.authorized = encrypted;
    this.alpnProtocol = encrypted ? "http/1.1" : undefined;
    this.connecting = false;
    this.readPending = false;
    this.timeoutMillis = 0;
  }
  touch() {
    clearTimeout(this.timer);
    if (this.timeoutMillis) {
      this.timer = setTimeout(() => this.emit("timeout"), this.timeoutMillis);
      if (this.unreferenced) this.timer.unref();
    }
  }
  _read() {
    if (this.readPending) return;
    this.readPending = true;
    this.conn.read().then(data => {
      this.readPending = false;
      this.touch();
      if (this.destroyed) return;
      if (data === null) { this.push(null); return; }
      if (this.push(Buffer.from(data))) this._read();
    }, error => { this.readPending = false; this.destroy(error); });
  }
  _write(data, encoding, callback) {
    this.conn.write(new Uint8Array(data)).then(() => { this.touch(); callback(); }, callback);
  }
  _final(callback) { this.conn.closeWrite().then(() => callback(), callback); }
  _destroy(error, callback) {
    clearTimeout(this.timer);
    this.conn.close().then(() => callback(error), closeError => callback(error || closeError));
  }
  setTimeout(millis, callback) { this.timeoutMillis = millis; if (callback) this.once("timeout", callback); this.touch(); return this; }
  setNoDelay() { return this; }
  setKeepAlive() { return this; }
  ref() { this.unreferenced = false; this.timer?.ref(); return this; }
  unref() { this.unreferenced = true; this.timer?.unref(); return this; }
  get remoteAddress() { return this.conn.remoteAddr; }
  get localAddress() { return this.conn.localAddr; }
}

export function connector(device, secure) {
  return (options, callback) => {
    if (options.httpSocket || options.socketPath || options.localAddress || options.ca || options.cert || options.key || options.rejectUnauthorized === false) {
      callback(new Error("This adapter does not support socket upgrades, local binding, custom Node TLS keys/CAs, or disabling certificate verification"));
      return;
    }
    const tls = secure ?? options.protocol === "https:";
    const host = String(options.hostname || options.host).replace(/^\[|\]$/g, "");
    const address = (host.includes(":") ? "[" + host + "]" : host) + ":" + (options.port || (tls ? 443 : 80));
    const opened = tls
      ? device.dialTls("tcp", address, {serverName: options.servername || host, nextProtos: ["http/1.1"]}, {timeoutMillis: 30000})
      : device.dial("tcp", address, {timeoutMillis: 30000});
    opened.then(conn => callback(null, new UrNodeSocket(conn, tls)), error => callback(error));
  };
}
