// A running provider on the SDK's C ABI (PROVIDER_CONTRACT.md, "App lifecycle"):
// the network space manager, the provider device with the installation's
// identity, and the listeners that feed its status. Handles are SDK object
// references; each one is closed where it has a close function, then released.
// The session runs on the app's thread; the SDK's callbacks only reach it
// through its ProviderInbox.

import CURnetworkSdk
import Foundation
import ProviderCore

/// How often the status is read, and the longest gap between status lines, in
/// seconds.
let statusPollInterval = 1.0
let statusRepeatInterval = 60.0

/// How often the payout wallet is read again, in seconds. The wallet is fixed; a
/// reread shows a mapping that the backend completes while the provider runs.
let walletSyncInterval = 10.0 * 60.0

/// The device description and spec recorded for this installation's device.
let deviceDescription = "Swift provider example"
let deviceSpec = "urnetwork-examples/swift-provider"

/// The provider extender role's two device settings (PROVIDER_CONTRACT.md,
/// "Extender role"), passed explicitly and both on. provideExtenderEnabled is
/// the embedder's hard switch: false means the role never runs.
/// defaultProvideExtender is the setting the device uses until the user sets
/// one: false turns the default off.
let provideExtenderEnabled = true
let defaultProvideExtender = true

/// A failure to start the provider that is not a configuration problem.
struct ProviderStartError: Error, CustomStringConvertible {
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

/// Reads one buffer-out value: the first call asks for the size, the second
/// copies. A value that grew in between is asked for again.
func readSdkBytes(_ read: (UnsafeMutablePointer<UInt8>?, UnsafeMutablePointer<Int32>?) -> Bool)
  -> Data
{
  var byteCount: Int32 = 0
  _ = read(nil, &byteCount)
  for _ in 0..<3 {
    if byteCount <= 0 {
      return Data()
    }
    var bytes = [UInt8](repeating: 0, count: Int(byteCount))
    var capacity = byteCount
    let copied = bytes.withUnsafeMutableBufferPointer { read($0.baseAddress, &capacity) }
    if copied {
      return Data(bytes.prefix(Int(capacity)))
    }
    byteCount = capacity
  }
  return Data()
}

/// One run of the provider. The initializer starts providing; run shows the
/// status until a stop or a credential error; close stops providing.
final class ProviderSession {
  private let config: ProviderConfig
  private let inbox: ProviderInbox
  /// the inbox as the callbacks' user data. It is retained for the life of the
  /// process: a callback that is already running when its subscription closes,
  /// or a wallet read that finishes after the device closed, still reaches it.
  private let inboxContext: UnsafeMutableRawPointer

  private var manager: UInt64 = 0
  private var space: UInt64 = 0
  private var api: UInt64 = 0
  private var device: UInt64 = 0
  private var subs: [UInt64] = []
  private var closed = false

  /// payoutWalletChecking, payoutWalletUnavailable, payoutWalletNotSet or the
  /// mapped coldkey; only the status loop's thread uses these
  private var payoutWallet = payoutWalletChecking
  private var payoutWalletScope = ""

  /// Creates the provider device with the installation's identity and the
  /// extender role's settings on, saves a new identity on first run, and starts
  /// providing publicly. The SDK declares provide intent on the device's
  /// platform connections by itself while the provide mode is public.
  init(config: ProviderConfig, inbox: ProviderInbox) throws {
    self.config = config
    self.inbox = inbox
    inboxContext = Unmanaged.passRetained(inbox).toOpaque()

    manager = urnet_new_network_space_manager_no_storage()
    space = urnet_network_space_manager_update_network_space_values(
      manager, #"{"host_name": "ur.network", "env_name": "main"}"#,
      #"{"migration_host_name": "bringyour.com"}"#)
    api = urnet_network_space_get_api(space)
    if manager == 0 || space == 0 || api == 0 {
      releaseHandles()
      throw ProviderStartError("the sdk did not create the network space")
    }
    urnet_api_set_by_jwt(api, config.clientJwt)

    // one call for both runs: on first run there is no identity, the key
    // material handle is 0 and the device makes a new identity, saved below
    var keyMaterial: UInt64 = 0
    if let material = providerKeyMaterial(config.identity) {
      keyMaterial = urnet_new_device_local_key_material(
        material.clientKeySeed, Int32(material.clientKeySeed.count),
        material.provideTlsCertificatePem, Int32(material.provideTlsCertificatePem.count),
        material.provideTlsPrivateKeyPem, Int32(material.provideTlsPrivateKeyPem.count))
      if keyMaterial == 0 {
        releaseHandles()
        throw ProviderStartError("the sdk did not take the provider identity")
      }
      if !material.extenderKeySeed.isEmpty {
        urnet_device_local_key_material_set_extender_key_seed(
          keyMaterial, material.extenderKeySeed, Int32(material.extenderKeySeed.count))
      }
    }
    var error: UnsafeMutablePointer<CChar>?
    device = urnet_new_device_local_with_provide_extender(
      space, config.clientJwt, deviceDescription, deviceSpec, "1", config.instanceId, false,
      keyMaterial, provideExtenderEnabled, defaultProvideExtender, &error)
    if keyMaterial != 0 {
      urnet_release(keyMaterial)
    }
    let errorText = takeSdkString(error)
    if device == 0 {
      releaseHandles()
      throw ProviderStartError(errorText ?? "the sdk did not create the device")
    }
    if config.identity == nil {
      // keep the new identity, so later starts present the same provider
      let device = device
      let identity = ProviderIdentity(
        version: providerIdentityVersion, clientId: config.clientId,
        clientKeySeed: readSdkBytes { urnet_device_local_get_client_key_seed(device, $0, $1) },
        provideTlsCertificatePem: readSdkBytes {
          urnet_device_local_get_provide_tls_certificate_pem(device, $0, $1)
        },
        provideTlsPrivateKeyPem: readSdkBytes {
          urnet_device_local_get_provide_tls_private_key_pem(device, $0, $1)
        },
        extenderKeySeed: readSdkBytes { urnet_device_local_get_extender_key_seed(device, $0, $1) })
      if identity.clientKeySeed.count != 32 {
        releaseHandles()
        throw ProviderStartError("the device has no provider identity to save")
      }
      do {
        try saveProviderIdentity(stateDir: config.stateDir, identity: identity)
      } catch {
        releaseHandles()
        throw ProviderStartError("save \(identityFileName): \(error)")
      }
    }

    // each callback copies what it carries before returning
    subs = [
      urnet_device_add_jwt_refresh_listener(
        device,
        { userData, clientJwt in
          withCallbackPool {
            guard let inbox = ProviderInbox.of(userData), let clientJwt else {
              return
            }
            inbox.jwtRefreshed(String(cString: clientJwt))
          }
        }, inboxContext),
      urnet_device_add_auth_logout_listener(
        device,
        { userData in
          withCallbackPool {
            ProviderInbox.of(userData)?.authLogout()
          }
        }, inboxContext),
      urnet_device_add_provider_ingress_contract_details_change_listener(
        device,
        { userData, detailsJson in
          withCallbackPool {
            guard let inbox = ProviderInbox.of(userData), let detailsJson,
              let details = decodeContractDetails(String(cString: detailsJson))
            else {
              return
            }
            inbox.clientsServed.add(details, receive: true)
          }
        }, inboxContext),
      urnet_device_add_provider_egress_contract_details_change_listener(
        device,
        { userData, detailsJson in
          withCallbackPool {
            guard let inbox = ProviderInbox.of(userData), let detailsJson,
              let details = decodeContractDetails(String(cString: detailsJson))
            else {
              return
            }
            inbox.clientsServed.add(details, receive: false)
          }
        }, inboxContext),
    ]
    urnet_device_set_provide_mode(device, Int64(URNET_PROVIDE_MODE_PUBLIC))
    syncWallet()
  }

  /// Reads the status from the device getters and the counted clients.
  func status() -> ProviderStatus {
    // "client_limit_exceeded" with the hold's end in RetryTime while the
    // platform holds this client off for its network's client limit
    let clientLimitStatus = decodeClientLimitStatus(
      takeSdkString(urnet_device_get_client_limit_status(device)))
    // urnet_device_local_get_provider_ready also waits for processed client key
    // registration, which default device settings do not enable, so the
    // connected carrier is the readiness signal here
    let state = providerState(
      provideMode: urnet_device_get_provide_mode(device),
      clientLimitStatus: clientLimitStatus.status,
      providePaused: urnet_device_get_provide_paused(device),
      provideEnabled: urnet_device_get_provide_enabled(device),
      providerConnected: urnet_device_local_get_provider_connected(device))
    let clientsServed = inbox.clientsServed.count()
    return ProviderStatus(
      state: state, clientLimitRetryTime: clientLimitStatus.retryTime,
      clientsServed: clientsServed.count, clientsServedAtLimit: clientsServed.atLimit,
      dataProvidedByteCount: decodeDataProvidedByteCount(
        takeSdkString(urnet_device_get_provider_packet_stats(device))),
      payoutWallet: payoutWallet, payoutWalletScope: payoutWalletScope)
  }

  /// Starts a payout wallet read (GET /sn/wallet with the client credential).
  /// The app only displays the wallet: the backend maps it, never the app.
  func syncWallet() {
    urnet_device_local_sync_sn_wallet(
      device,
      { userData, resultJson, errorText in
        withCallbackPool {
          let succeeded = snGetWalletSucceeded(
            resultJson: resultJson.map { String(cString: $0) },
            errorText: errorText.map { String(cString: $0) })
          ProviderInbox.of(userData)?.walletSynced(succeeded: succeeded)
        }
      }, inboxContext)
  }

  /// Prints status lines until a stop request or until the server rejects the
  /// credential, and returns the process exit code.
  func run() -> Int32 {
    var lastKey = ""
    var lastPrintTime = -Double.infinity
    var nextWalletSyncTime = ProcessInfo.processInfo.systemUptime + walletSyncInterval
    while true {
      let items = inbox.take()
      applyWalletSync(succeeded: items.walletSyncSucceeded, failed: items.walletSyncFailed)
      saveRefreshedClientJwt(items.refreshedClientJwt)
      let status = self.status()
      let now = ProcessInfo.processInfo.systemUptime
      if status.key != lastKey || statusRepeatInterval <= now - lastPrintTime {
        writeOutput(status.line)
        lastKey = status.key
        lastPrintTime = now
      }
      if nextWalletSyncTime <= now {
        syncWallet()
        nextWalletSyncTime = now + walletSyncInterval
      }
      if items.stopRequested {
        return ProviderExitCode.stopped
      }
      if items.authLoggedOut {
        writeError(
          "the server rejected the client credential; issue a new scoped client JWT from your backend"
        )
        return ProviderExitCode.config
      }
      inbox.wait(seconds: statusPollInterval)
    }
  }

  /// Stops providing, closes the subscriptions, the device and the manager, and
  /// releases their handles.
  func close() {
    if closed {
      return
    }
    closed = true
    urnet_device_set_provide_mode(device, Int64(URNET_PROVIDE_MODE_NONE))
    releaseHandles()
    // a token refreshed while stopping is still saved for the next start
    saveRefreshedClientJwt(inbox.take().refreshedClientJwt)
    writeOutput("status: stopped")
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

  /// Records a finished wallet read. A failure keeps the last known wallet.
  private func applyWalletSync(succeeded: Bool, failed: Bool) {
    if succeeded {
      // the sdk caches the effective wallet before the callback: this client's
      // own consent, else the network consent, else (with hotkey delegations)
      // the network's hotkey entry, else a non-consent wallet
      guard let wallet = decodeSnWallet(takeSdkString(urnet_device_local_get_sn_wallet(device)))
      else {
        payoutWallet = payoutWalletNotSet
        payoutWalletScope = ""
        return
      }
      payoutWallet = wallet.coldkeySs58
      payoutWalletScope = payoutWalletScopeOf(
        walletConsentScope: wallet.consentScope, walletClientId: wallet.clientId,
        clientId: config.clientId)
    } else if failed && payoutWallet == payoutWalletChecking {
      payoutWallet = payoutWalletUnavailable
    }
  }

  /// Keeps a refreshed credential, so the next start uses a valid token.
  private func saveRefreshedClientJwt(_ clientJwt: String?) {
    guard let clientJwt else {
      return
    }
    do {
      try writePrivateFile(
        joinPath(config.stateDir, clientJwtFileName), Data((clientJwt + "\n").utf8))
    } catch {
      // never print the token itself
      writeError("could not save the refreshed client credential: \(error)")
    }
  }
}
