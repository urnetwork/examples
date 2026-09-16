export const SUBPROTOCOL = 4096;
const utf8 = new TextDecoder("utf-8", {fatal: true, ignoreBOM: true});
export function encode(kind, id, text = "") {
  // TextEncoder replaces isolated UTF-16 surrogates; reject them instead.
  if (typeof text !== "string" || !text.isWellFormed()) throw new Error("invalid Unicode text");
  const payload = new TextEncoder().encode(text);
  if (![1, 2].includes(kind) || typeof id !== "bigint" || id <= 0n || id > 0xffffffffffffffffn ||
      payload.length > 4096 || (kind === 2 && payload.length)) throw new Error("invalid message");
  const frame = new Uint8Array(16 + payload.length), view = new DataView(frame.buffer);
  frame.set([85, 82, 77, 83, 1, kind]); view.setUint16(6, payload.length); view.setBigUint64(8, id);
  frame.set(payload, 16); return frame;
}
export function decode(input) {
  const frame = new Uint8Array(input); // Own the bytes before leaving a native callback.
  if (frame.length < 16 || frame.length > 4112) throw new Error("invalid frame size");
  const view = new DataView(frame.buffer, frame.byteOffset, frame.byteLength);
  const kind = frame[5], id = view.getBigUint64(8), size = view.getUint16(6);
  if (frame[0] !== 85 || frame[1] !== 82 || frame[2] !== 77 || frame[3] !== 83 ||
      frame[4] !== 1 || ![1, 2].includes(kind) || id === 0n || size !== frame.length - 16 ||
      (kind === 2 && size !== 0)) throw new Error("invalid frame header");
  return {kind, id, text: utf8.decode(frame.subarray(16))};
}
export function selfTest() {
  const check = (value) => {if (!value) throw new Error("codec test failed");};
  const hex = (data) => Array.from(data, b => b.toString(16).padStart(2, "0")).join("");
  const golden = encode(1, 1n, "hi"), ack = encode(2, 1n);
  check(hex(golden) === "55524d530101000200000000000000016869");
  check(hex(ack) === "55524d53010200000000000000000001");
  for (const text of ["", "é🙂\0", "\ufeffhi", "x".repeat(4096), "é".repeat(2048)]) {
    const result = decode(encode(1, 0xffffffffffffffffn, text));
    check(result.text === text && result.id === 0xffffffffffffffffn && result.kind === 1);
  }
  check(decode(ack).kind === 2);
  const bad = Array.from({length: golden.length}, (_, n) => golden.slice(0, n));
  bad.push(new Uint8Array([...encode(1, 1n, "x".repeat(4096)), 0]));
  bad.push(new Uint8Array([...golden, 0]), new Uint8Array([...golden.slice(0, 16), 0xc0, 0xaf]));
  for (const [offset, value] of [[0, 0], [4, 2], [5, 3], [5, 2], [6, 16], [7, 1], [15, 0]]) {
    const changed = golden.slice(); changed[offset] = value; bad.push(changed);
  }
  const reject = fn => {let failed = false; try {fn();} catch {failed = true;} check(failed);};
  for (const data of bad) reject(() => decode(data));
  for (const fn of [() => encode(1, 0n), () => encode(2, 1n, "x"), () => encode(1, 1n, "x".repeat(4097)),
    () => encode(1, 1n, "\ud800"), () => encode(3, 1n)]) reject(fn);
  console.log("URMS codec self-test passed");
}
