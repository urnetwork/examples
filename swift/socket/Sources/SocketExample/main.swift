import Foundation
import Network
import Alamofire
import URnetworkSdk

struct ExampleError: Error, CustomStringConvertible {
    let description: String
    init(_ message: String) {description = message}
}
func required(_ name: String) throws -> String {
    guard let value = ProcessInfo.processInfo.environment[name], !value.isEmpty else {throw ExampleError("Set \(name); see README.md")}
    return value
}
func main() async throws {
    let args = CommandLine.arguments
    let mode = args.count > 1 ? args[1] : "tls"
    if mode == "--version" {print(Sdk.version()); return}
    if mode == "--new-id" {print(SdkNewId()!.string()); return}
    if mode == "urlsession" || mode == "alamofire" {
        let proxyString = try required("URNETWORK_HTTP_PROXY")
        guard let proxy = URLComponents(string: proxyString), proxy.scheme == "http",
              let host = proxy.host, ["127.0.0.1", "::1", "[::1]"].contains(host),
              let port = proxy.port, let nwPort = NWEndpoint.Port(rawValue: UInt16(exactly: port) ?? 0), port > 0
        else {throw ExampleError("Use the loopback address printed by the Go proxy example")}
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = 30
        configuration.timeoutIntervalForResource = 30
        configuration.proxyConfigurations = [
            ProxyConfiguration(httpCONNECTProxy: .hostPort(host: .init(host), port: nwPort), tlsOptions: nil)
        ]
        let urlString = args.count > 2 ? args[2] : "https://example.com/"
        guard let url = URL(string: urlString) else {throw ExampleError("Invalid HTTP URL")}
        if mode == "urlsession" {
            let session = URLSession(configuration: configuration)
            defer {session.invalidateAndCancel()}
            let (data, response) = try await session.data(from: url)
            print((response as? HTTPURLResponse)?.statusCode ?? 0, String(decoding: data, as: UTF8.self))
        } else {
            let session = Alamofire.Session(configuration: configuration)
            let response = try await session.request(url).validate().serializingString().value
            print(response)
            session.session.invalidateAndCancel()
        }
        return
    }
    guard ["tls", "udp", "dtls"].contains(mode) else {throw ExampleError("Modes: tls, udp, dtls, urlsession, alamofire")}
    if mode != "tls" && args.count < 3 {throw ExampleError("UDP/DTLS require an echo server host:port")}
    let jwt = try required("URNETWORK_JWT"), idString = try required("URNETWORK_INSTANCE_ID")
    var error: NSError?
    guard let id = SdkParseId(idString, &error) else {throw error ?? ExampleError("Invalid instance ID") as NSError}
    guard let manager = SdkNewNetworkSpaceManagerNoStorage() else {throw ExampleError("No network space manager")}
    defer {manager.close()}
    let values = SdkNetworkSpaceValues()
    values.migrationHostName = "bringyour.com"
    guard let space = manager.updateNetworkSpaceValues(SdkNewNetworkSpaceKey("ur.network", "main"), values: values) else {throw ExampleError("No network space")}
    space.getApi()?.setByJwt(jwt)
    guard let device = SdkNewDeviceLocalWithDefaults(space, jwt, "Swift socket example", "swift", "1", id, false, &error)
    else {throw error ?? ExampleError("Device setup failed") as NSError}
    defer {device.close()}
    let location = SdkConnectLocation(), locationId = SdkConnectLocationId()
    locationId.bestAvailable = true
    location.connectLocationId = locationId
    device.setConnectLocation(location)
    let address = args.count > 2 ? args[2] : "example.com:443"
    // This command-line program can block here. Apps should put portable socket
    // operations on a worker queue, never on the UI thread.
    let socket = try device.openSocket(mode == "tls" ? "tcp" : "udp", address: address,
        timeoutMillis: 30000, tlsOptions: mode == "udp" ? nil : SdkSocketTLSOptions())
    defer {try? socket.close()}
    try socket.setDeadlineMillis(Int64(Date().timeIntervalSince1970 * 1000) + 10000)
    let payload = Data((mode == "tls" ? "GET / HTTP/1.1\r\nHost: \(address)\r\nConnection: close\r\n\r\n" : "hello").utf8)
    var offset = 0
    while offset < payload.count {
        var count = 0
        try socket.write(payload.subdata(in: offset..<payload.count), ret0_: &count)
        if count == 0 {throw ExampleError("Write made no progress")}
        offset += count
    }
    var received = 0
    while received < 1_048_576 {
        let reply = try socket.read(65535)
        let data = reply.data ?? Data()
        FileHandle.standardOutput.write(data)
        received += data.count
        if reply.eof || mode != "tls" {break} // Empty UDP data does not mean EOF.
    }
}

do {try await main()}
catch {FileHandle.standardError.write(Data("\(error)\n".utf8)); exit(1)}
