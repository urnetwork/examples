import Foundation

struct MessageFrame: Equatable {
  let kind: UInt8
  let id: UInt64
  let text: String
}
struct CodecError: Error {}
func encode(_ frame: MessageFrame) throws -> Data {
  let payload = Array(frame.text.utf8)
  guard frame.id != 0, [1, 2].contains(frame.kind), payload.count <= 4096,
    frame.kind != 2 || payload.isEmpty
  else { throw CodecError() }
  var b: [UInt8] = [
    85, 82, 77, 83, 1, frame.kind, UInt8(payload.count >> 8), UInt8(payload.count & 255),
  ]
  for shift in stride(from: 56, through: 0, by: -8) { b.append(UInt8((frame.id >> shift) & 255)) }
  b.append(contentsOf: payload)
  return Data(b)
}
func decode(_ data: Data) throws -> MessageFrame {
  let b = Array(data)
  guard b.count >= 16, b.count <= 4112, Array(b[0..<4]) == [85, 82, 77, 83], b[4] == 1,
    [1, 2].contains(b[5]), Int(b[6]) * 256 + Int(b[7]) == b.count - 16, b[5] != 2 || b.count == 16,
    let text = String(bytes: b[16...], encoding: .utf8)
  else { throw CodecError() }
  let id = b[8..<16].reduce(UInt64(0)) { ($0 << 8) | UInt64($1) }
  guard id != 0 else { throw CodecError() }
  return MessageFrame(kind: b[5], id: id, text: text)
}
func codecSelfTest() throws {
  func check(_ value: Bool) throws { if !value { throw CodecError() } }
  func bytes(_ hex: String) -> Data {
    var result = Data()
    var index = hex.startIndex
    while index < hex.endIndex {
      let end = hex.index(index, offsetBy: 2)
      result.append(UInt8(hex[index..<end], radix: 16)!)
      index = end
    }
    return result
  }
  let golden = bytes("55524d530101000200000000000000016869")
  let ack = bytes("55524d53010200000000000000000001")
  for frame in [MessageFrame(kind: 1, id: 1, text: "hi"), MessageFrame(kind: 2, id: 1, text: "")] {
    let expected = frame.kind == 1 ? golden : ack
    try check(encode(frame) == expected)
    try check(decode(expected) == frame)
  }
  for text in [
    "", "é🙂\0", String(repeating: "x", count: 4096), String(repeating: "é", count: 2048),
  ] {
    let f = MessageFrame(kind: 1, id: UInt64.max, text: text)
    try check(decode(encode(f)) == f)
  }
  var bad = (0..<golden.count).map { Data(golden.prefix($0)) }
  bad.append(
    try encode(MessageFrame(kind: 1, id: 1, text: String(repeating: "x", count: 4096))) + Data([0]))
  bad.append(golden + Data([0]))
  bad.append(Data(golden.prefix(16)) + Data([0xc0, 0xaf]))
  for (offset, value) in [(0, 0), (4, 2), (5, 3), (5, 2), (6, 16), (7, 1), (15, 0)] {
    var b = golden
    b[offset] = UInt8(value)
    bad.append(b)
  }
  for b in bad {
    var rejected = false
    do { _ = try decode(b) } catch { rejected = true }
    try check(rejected)
  }
  for f in [
    MessageFrame(kind: 1, id: 0, text: ""), MessageFrame(kind: 2, id: 1, text: "x"),
    MessageFrame(kind: 3, id: 1, text: ""),
    MessageFrame(kind: 1, id: 1, text: String(repeating: "x", count: 4097)),
  ] {
    var rejected = false
    do { _ = try encode(f) } catch { rejected = true }
    try check(rejected)
  }
  print("URMS codec self-test passed")
}
