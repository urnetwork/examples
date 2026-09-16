import Foundation
import URExampleIntegration
import URnetworkSdk

enum Event {
  case message(String, Data)
  case peers(SdkNetworkPeers?)
  case query(Bool)
  case stop
}
final class Callbacks: NSObject, SdkSubprotocolListenerProtocol,
  SdkNetworkPeersChangeListenerProtocol, SdkSubprotocolsQueryCallbackProtocol
{
  let condition = NSCondition()
  var events: [Event] = []
  func put(_ event: Event) {
    condition.lock()
    defer { condition.unlock() }
    if events.count < 256 {
      events.append(event)
      condition.signal()
    }
  }
  func next() -> Event? {
    condition.lock()
    defer { condition.unlock() }
    if events.isEmpty { _ = condition.wait(until: Date().addingTimeInterval(0.1)) }
    return events.isEmpty ? nil : events.removeFirst()
  }
  func subprotocolMessage(_ subprotocolId: Int32, sourceClientId: SdkId?, messageBytes: Data?) {
    guard subprotocolId == 4096, let source = sourceClientId?.string(), let bytes = messageBytes,
      bytes.count >= 16, bytes.count <= 4112
    else { return }
    let copy = bytes.withUnsafeBytes { Data(bytes: $0.baseAddress!, count: $0.count) }
    put(.message(source, copy))  // Copy callback storage before returning to native code.
  }
  func networkPeersChanged(_ peers: SdkNetworkPeers?) { put(.peers(peers)) }
  func result(_ ids: SdkIntList?, ok: Bool) {
    var supported = false
    if ok, let ids { for i in 0..<ids.len() { if ids.get(i) == 4096 { supported = true } } }
    put(.query(supported))
  }
}
func showPeers(_ peers: SdkNetworkPeers?) {
  guard let peers else {
    print("peers unavailable (no snapshot)")
    return
  }
  print("disconnected:", peers.disconnectedCount)
  guard let connected = peers.connected else {
    print("connected peers unavailable")
    return
  }
  for i in 0..<connected.len() {
    guard let peer = connected.get(i) else { continue }
    var roles: [String] = []
    if let list = peer.roles { for i in 0..<list.len() { roles.append(list.get(i)) } }
    let row: [String: Any] = [
      "ClientId": peer.clientId?.string() ?? "unavailable", "ProvideEnabled": peer.provideEnabled,
      "Principal": peer.principal, "Roles": roles, "DeviceSpec": peer.deviceSpec,
      "DeviceName": peer.deviceName, "Color": peer.colorHex(),
    ]
    if let bytes = try? JSONSerialization.data(withJSONObject: row, options: [.sortedKeys]),
      let text = String(data: bytes, encoding: .utf8)
    {
      print(text)
    }
  }
}
func run() throws {
  let args = Array(CommandLine.arguments.dropFirst())
  if args == ["--self-test"] {
    try codecSelfTest()
    return
  }
  if args == ["--version"] {
    print(Sdk.version())
    return
  }
  guard let mode = args.first, ["self", "peers", "watch", "send"].contains(mode),
    mode != "send" || args.count >= 3
  else {
    throw ClientSetupError(
      "usage: --self-test | --version | self | peers | watch | send CLIENT_ID TEXT")
  }
  var error: NSError?
  let destination = mode == "send" ? SdkParseId(args[1], &error) : nil
  if mode == "send" && destination == nil {
    throw error ?? ClientSetupError("Invalid client ID") as NSError
  }
  let callbacks = Callbacks()
  let session = try UrSession(connect: false)
  defer {
    session.close()
    withExtendedLifetime(callbacks) {}
  }
  let device = session.device
  let sub = try device.enableSubprotocol(4096, listener: callbacks)
  defer { sub.close() }
  let peersSub = device.add(callbacks as SdkNetworkPeersChangeListenerProtocol)
  defer { peersSub?.close() }
  device.setProvideMode(SdkProvideModeNetwork)
  print("self:", device.getClientId()?.string() ?? "unavailable")
  if mode == "self" { return }
  let snapshot = device.getNetworkPeers()
  showPeers(snapshot)
  if mode == "peers" && snapshot != nil { return }
  var queried = false
  var deadline = ProcessInfo.processInfo.systemUptime + 30
  let pending = UInt64.random(in: 1...UInt64.max)
  signal(SIGINT, SIG_IGN)
  let interrupt = DispatchSource.makeSignalSource(signal: SIGINT, queue: .global())
  interrupt.setEventHandler { callbacks.put(.stop) }
  interrupt.resume()
  defer { interrupt.cancel() }
  func send(_ target: SdkId?, _ frame: MessageFrame) throws {
    guard
      device.sendSubprotocolBytes(
        4096, destinationClientId: target, messageBytes: try encode(frame))
    else { throw ClientSetupError("SDK did not enqueue message") }
  }
  defer { withExtendedLifetime(callbacks) { device.disableSubprotocol(4096) } }
  while true {
    if let destination, !queried, device.getProviderConnected() {
      queried = true
      device.querySubprotocols(destination, timeoutMillis: 10000, callback: callbacks)
    }
    if let event = callbacks.next() {
      switch event {
      case .stop: return
      case .peers(let peers):
        showPeers(peers)
        if mode == "peers" && peers != nil { return }
      case .query(let supported):
        guard supported else {
          throw ClientSetupError("peer query failed or peer does not advertise 4096")
        }
        try send(
          destination,
          MessageFrame(kind: 1, id: pending, text: args.dropFirst(2).joined(separator: " ")))
        print("sent:", pending, "waiting for ACK")
        deadline = ProcessInfo.processInfo.systemUptime + 10
      case .message(let source, let bytes):
        let frame: MessageFrame
        do { frame = try decode(bytes) } catch {
          fputs("rejected malformed frame\n", stderr)
          continue
        }
        print(
          "kind=\(frame.kind) source=\(source) id=\(frame.id) text=\(String(reflecting: frame.text))"
        )
        if frame.kind == 1 {
          try send(SdkParseId(source, &error), MessageFrame(kind: 2, id: frame.id, text: ""))
        } else if source == destination?.string() && frame.id == pending {
          return
        }
      }
    }
    if mode != "watch" && ProcessInfo.processInfo.systemUptime > deadline {
      throw ClientSetupError("connection, peer snapshot, query, or ACK timed out")
    }
  }
}
do { try run() } catch {
  fputs("\(error)\n", stderr)
  exit(1)
}
