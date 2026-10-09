// A running embed app on the SDK's C ABI (EMBED_CONTRACT.md, "App lifecycle"):
// the network space manager, the local device with this installation's client
// JWT, and the listeners that feed its status. The device carries only the
// app's own traffic; it does not provide. Handles are SDK object references;
// each one is closed where it has a close function, then released. The
// session runs on the app's thread; the SDK's callbacks and the cap reads only
// reach it through its EmbedInbox.

import CURnetworkSdk
import EmbedCore
import Foundation

/// How often the status is read, and the longest gap between status lines, in
/// seconds.
let statusPollInterval = 1.0
let statusRepeatInterval = 60.0

/// How often the caps are read, in seconds. A contract status change reads them
/// at the next pass of the run loop, well within the contract's 5 seconds;
/// changes during a read add one more read.
let capReadInterval = 5.0 * 60.0

/// The device description and spec recorded for this installation's device.
let deviceDescription = "Swift embed example"
let deviceSpec = "urnetwork-examples/swift-embed"

/// The destination of the app's own traffic: the best available location.
let bestAvailableLocation = #"{"connect_location_id":{"best_available":true}}"#

/// A failure to start the device that is not a configuration problem.
struct EmbedStartError: Error, CustomStringConvertible {
  let description: String

  /// An error with the given text.
  init(_ description: String) {
    self.description = description
  }
}

/// Copies a string the SDK returned and frees the SDK's copy. nil for NULL.
func takeSdkString(_ value: UnsafeMutablePointer<CChar>?) -> String? {
  guard let value else {
    return nil
  }
  defer { urnet_free_string(value) }
  return String(cString: value)
}

/// Runs the body of an SDK callback. SDK threads have no autorelease pool, so on
/// Apple platforms the body runs in its own pool; otherwise an object that
/// Foundation autoreleases there would stay until the long-lived thread exits.
func withCallbackPool(_ body: () -> Void) {
  #if canImport(ObjectiveC)
    autoreleasepool(invoking: body)
  #else
    body()
  #endif
}

/// One run of the app. The initializer starts the device; run shows the status
/// until a stop or a credential error; close stops the device.
final class EmbedSession {
  private var config: EmbedConfig
  private let inbox: EmbedInbox
  /// the inbox as the callbacks' user data. It is retained for the life of the
  /// process: a callback that is already running when its subscription closes
  /// still reaches it.
  private let inboxContext: UnsafeMutableRawPointer

  private var manager: UInt64 = 0
  private var space: UInt64 = 0
  private var api: UInt64 = 0
  private var device: UInt64 = 0
  private var subs: [UInt64] = []
  private var closed = false

  /// the cap readings; only the run loop's thread uses them
  private var caps = Caps()

  /// Creates the local device with the installation's client JWT and instance
  /// id, adds the listeners and sets the destination of the app's traffic. The
  /// provide mode stays at its default: an embed app does not provide.
  init(config: EmbedConfig, inbox: EmbedInbox) throws {
    self.config = config
    self.inbox = inbox
    inboxContext = Unmanaged.passRetained(inbox).toOpaque()
    if let firstCap = config.firstCap {
      caps.apply(firstCap)
    }

    manager = urnet_new_network_space_manager_no_storage()
    space = urnet_network_space_manager_update_network_space_values(
      manager, #"{"host_name":"ur.network","env_name":"main"}"#,
      #"{"migration_host_name":"bringyour.com"}"#)
    api = urnet_network_space_get_api(space)
    if manager == 0 || space == 0 || api == 0 {
      releaseHandles()
      throw EmbedStartError("the sdk did not create the network space")
    }
    urnet_api_set_by_jwt(api, config.clientJwt)

    var error: UnsafeMutablePointer<CChar>?
    device = urnet_new_device_local_with_defaults(
      space, config.clientJwt, deviceDescription, deviceSpec, "1", config.instanceId, false,
      &error)
    let errorText = takeSdkString(error)
    if device == 0 || errorText != nil {
      releaseHandles()
      throw EmbedStartError(errorText ?? "the sdk did not create the device")
    }

    // each callback copies what it carries before returning
    subs = [
      urnet_device_add_jwt_refresh_listener(
        device,
        { userData, clientJwt in
          withCallbackPool {
            guard let inbox = EmbedInbox.of(userData), let clientJwt else {
              return
            }
            inbox.jwtRefreshed(String(cString: clientJwt))
          }
        }, inboxContext),
      urnet_device_add_auth_logout_listener(
        device,
        { userData in
          withCallbackPool {
            EmbedInbox.of(userData)?.authLogout()
          }
        }, inboxContext),
      urnet_device_add_contract_status_change_listener(
        device,
        { userData, _ in
          withCallbackPool {
            EmbedInbox.of(userData)?.contractStatusChanged()
          }
        }, inboxContext),
    ]
    urnet_device_set_connect_location(device, bestAvailableLocation)
  }

  /// Reads the status inputs from the device getters and the caps.
  func readStatusLine() -> String {
    // "client_limit_exceeded" with the hold's end in RetryTime while the
    // platform holds this client off for its network's concurrent client limit
    let clientLimitStatus = decodeClientLimitStatus(
      takeSdkString(urnet_device_get_client_limit_status(device)))
    // NULL before the window exists
    let providersAdded = decodeProvidersAdded(takeSdkString(urnet_device_get_window_status(device)))
    return statusLine(
      StatusInput(
        clientLimitStatus: clientLimitStatus.status,
        clientLimitRetryTime: clientLimitStatus.retryTime, caps: caps,
        providersAdded: providersAdded))
  }

  /// Starts a cap read on its own thread with the current client JWT, so a slow
  /// answer never holds up the status. The thread hands its reading to the run
  /// loop through the inbox.
  func startCapRead() {
    let inbox = inbox
    let apiUrl = config.apiUrl
    let clientJwt = config.clientJwt
    Thread.detachNewThread {
      do {
        let cap = try readCaps(http: urlSessionHttp, apiUrl: apiUrl, clientJwt: clientJwt)
        inbox.capReadFinished(CapRead(cap: cap, error: ""))
      } catch let error as EmbedNotEnabledError {
        inbox.capReadFinished(CapRead(cap: nil, error: "\(error)", notEnabled: true))
      } catch {
        inbox.capReadFinished(CapRead(cap: nil, error: "\(error)"))
      }
    }
  }

  /// Prints status lines until a stop request or until the server rejects the
  /// credential, and returns the process exit code. The caps are read at start
  /// (unless the token server's data_cap was the first reading), every 5
  /// minutes and after a contract status change.
  func run() -> Int32 {
    var lastLine: String?
    var lastPrintTime = 0.0
    var capReadRunning = false
    var capReadFailing = false
    var contractCapReadPending = false
    var nextCapReadTime =
      ProcessInfo.processInfo.systemUptime + (config.firstCap != nil ? capReadInterval : 0)
    while true {
      let items = inbox.take()
      saveRefreshedClientJwt(items.refreshedClientJwt)
      if items.authLoggedOut {
        writeError(
          "the server rejected the client credential; sign in again to obtain a new client JWT from your backend"
        )
        return EmbedExitCode.config
      }
      if items.stopRequested {
        return EmbedExitCode.stopped
      }
      let now = ProcessInfo.processInfo.systemUptime
      if let capRead = items.capRead {
        capReadRunning = false
        // the Embed-not-enabled refusal clears the last reading; another
        // failure keeps it
        caps.record(capRead.cap, notEnabled: capRead.notEnabled)
        if capRead.cap == nil && !capReadFailing {
          writeError("could not read the data caps: \(capRead.error)")
        }
        capReadFailing = capRead.cap == nil
      }
      if items.contractStatusChanged {
        contractCapReadPending = true
      }
      if !capReadRunning && (nextCapReadTime <= now || contractCapReadPending) {
        startCapRead()
        capReadRunning = true
        nextCapReadTime = now + capReadInterval
        contractCapReadPending = false
      }
      let line = readStatusLine()
      if line != lastLine || statusRepeatInterval <= now - lastPrintTime {
        writeOutput(line)
        lastLine = line
        lastPrintTime = now
      }
      inbox.wait(seconds: statusPollInterval)
    }
  }

  /// Closes the subscriptions, the device and the manager, and releases their
  /// handles. A token refreshed before the close is still saved. A console app
  /// prints no stopped line.
  func close() {
    if closed {
      return
    }
    closed = true
    releaseHandles()
    saveRefreshedClientJwt(inbox.take().refreshedClientJwt)
  }

  /// Closes and releases every handle the session holds, in the contract's
  /// order: subscriptions, device, then the space's handles and the manager.
  private func releaseHandles() {
    for sub in subs where sub != 0 {
      urnet_sub_close(sub)
      urnet_release(sub)
    }
    subs = []
    if device != 0 {
      urnet_device_close(device)
      urnet_release(device)
      device = 0
    }
    if api != 0 {
      urnet_release(api)
      api = 0
    }
    if space != 0 {
      urnet_release(space)
      space = 0
    }
    if manager != 0 {
      urnet_network_space_manager_close(manager)
      urnet_release(manager)
      manager = 0
    }
  }

  /// Keeps a refreshed credential, so later cap reads and the next start use a
  /// valid token. The token itself is never printed.
  private func saveRefreshedClientJwt(_ clientJwt: String?) {
    guard let clientJwt else {
      return
    }
    config.clientJwt = clientJwt
    do {
      try saveClientJwt(stateDir: config.stateDir, clientJwt: clientJwt)
    } catch {
      writeError("could not save the refreshed client credential: \(error)")
    }
  }
}
