import Foundation
import URnetworkSdk

public struct ClientSetupError: Error, CustomStringConvertible {
  public let description: String
  public init(_ value: String) { description = value }
}
public final class UrSession {
  public let device: SdkDeviceLocal
  private let manager: SdkNetworkSpaceManager
  private var closed = false
  public init(connect: Bool = true) throws {
    let env = ProcessInfo.processInfo.environment
    guard let jwt = env["URNETWORK_CLIENT_JWT"], !jwt.isEmpty,
      let instance = env["URNETWORK_INSTANCE_ID"], !instance.isEmpty
    else {
      throw ClientSetupError("Set URNETWORK_CLIENT_JWT and a persistent URNETWORK_INSTANCE_ID")
    }
    var error: NSError?
    guard let id = SdkParseId(instance, &error) else {
      throw error ?? ClientSetupError("Invalid instance UUID") as NSError
    }
    guard let manager = SdkNewNetworkSpaceManagerNoStorage() else {
      throw ClientSetupError("No network space manager")
    }
    self.manager = manager
    let values = SdkNetworkSpaceValues()
    values.migrationHostName = "bringyour.com"
    guard
      let space = manager.updateNetworkSpaceValues(
        SdkNewNetworkSpaceKey("ur.network", "main"), values: values)
    else {
      manager.close()
      throw ClientSetupError("No network space")
    }
    space.getApi()?.setByJwt(jwt)
    guard
      let device = SdkNewDeviceLocalWithDefaults(
        space, jwt, "Swift example", "swift", "1", id, false, &error)
    else {
      manager.close()
      throw error ?? ClientSetupError("Device setup failed") as NSError
    }
    self.device = device
    if connect {
      let location = SdkConnectLocation()
      let id = SdkConnectLocationId()
      id.bestAvailable = true
      location.connectLocationId = id
      device.setConnectLocation(location)
    }
  }
  public func close() {
    if !closed {
      closed = true
      device.close()
      manager.close()
    }
  }
  deinit { close() }
}
