// What the SDK's callbacks and the stop signals hand to the status loop. C ABI
// callbacks run on SDK threads and the signal handlers on a dispatch queue:
// each one copies what it carries into the inbox under stateLock and wakes the
// loop, which does the work (files, SDK calls, output) on the app's own thread.
// A callback never blocks and never calls the SDK.

import Foundation
import ProviderCore

/// The pending work of one wake of the status loop.
struct ProviderInboxItems {
  var stopRequested = false
  var authLoggedOut = false
  /// the newest token the SDK refreshed, not saved yet
  var refreshedClientJwt: String?
  var walletSyncSucceeded = false
  var walletSyncFailed = false
}

/// The callbacks' and signals' hand-off to the status loop. Safe for concurrent
/// use. The clients-served count is the one value a callback updates in place:
/// it is its own lock-guarded count, and the status loop only reads it.
final class ProviderInbox {
  let clientsServed: ClientsServed

  /// guards items and woken, and wakes the status loop
  private let stateLock = NSCondition()
  private var items = ProviderInboxItems()
  private var woken = false

  /// An empty inbox that counts clients into clientsServed.
  init(clientsServed: ClientsServed) {
    self.clientsServed = clientsServed
  }

  /// The inbox that a callback's user data points to; ProviderSession passes a
  /// retained inbox as the user data of every callback.
  static func of(_ userData: UnsafeMutableRawPointer?) -> ProviderInbox? {
    guard let userData else {
      return nil
    }
    return Unmanaged<ProviderInbox>.fromOpaque(userData).takeUnretainedValue()
  }

  /// Ctrl-C or SIGTERM: stop providing and exit with code 0.
  func requestStop() {
    stateLock.lock()
    defer { stateLock.unlock() }
    items.stopRequested = true
    wakeWithLock()
  }

  /// The server rejected the client credential: exit with code 78.
  func authLogout() {
    stateLock.lock()
    defer { stateLock.unlock() }
    items.authLoggedOut = true
    wakeWithLock()
  }

  /// Keeps the refreshed token for the status loop to save.
  func jwtRefreshed(_ clientJwt: String) {
    stateLock.lock()
    defer { stateLock.unlock() }
    items.refreshedClientJwt = clientJwt
    wakeWithLock()
  }

  /// Records a finished wallet read; the status loop reads the wallet itself.
  func walletSynced(succeeded: Bool) {
    stateLock.lock()
    defer { stateLock.unlock() }
    if succeeded {
      items.walletSyncSucceeded = true
    } else {
      items.walletSyncFailed = true
    }
    wakeWithLock()
  }

  /// Returns the pending work and clears it. A stop or logout stays set, so a
  /// later take still ends the loop.
  func take() -> ProviderInboxItems {
    stateLock.lock()
    defer { stateLock.unlock() }
    let taken = items
    items = ProviderInboxItems(
      stopRequested: items.stopRequested, authLoggedOut: items.authLoggedOut)
    return taken
  }

  /// Waits until a callback or signal adds work, or until seconds pass.
  func wait(seconds: Double) {
    stateLock.lock()
    defer { stateLock.unlock() }
    let deadline = Date(timeIntervalSinceNow: seconds)
    while !woken {
      if !stateLock.wait(until: deadline) {
        break
      }
    }
    woken = false
  }

  /// Wakes the status loop. The caller holds stateLock.
  private func wakeWithLock() {
    woken = true
    stateLock.signal()
  }
}
