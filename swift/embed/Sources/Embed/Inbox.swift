// What the SDK's callbacks, the cap reads and the stop signals hand to the run
// loop. C ABI callbacks run on SDK threads, cap reads on their own threads and
// the signal handlers on a dispatch queue: each one copies what it carries
// into the inbox under stateLock and wakes the loop, which does the work
// (files, SDK calls, output) on the app's own thread. A callback never blocks
// and never calls the SDK.

import EmbedCore
import Foundation

/// A finished cap read: the reading, or nil and the reason it failed.
struct CapRead {
  var cap: Cap?
  var error: String
  /// the failure is the Embed-not-enabled refusal, which clears the last
  /// reading
  var notEnabled = false
}

/// The pending work of one wake of the run loop.
struct EmbedInboxItems {
  var stopRequested = false
  var authLoggedOut = false
  /// the newest token the SDK refreshed, not saved yet
  var refreshedClientJwt: String?
  /// a contract status changed: read the caps again
  var contractStatusChanged = false
  /// a finished cap read that the run loop has not applied yet
  var capRead: CapRead?
}

/// The hand-off to the run loop. Safe for concurrent use.
final class EmbedInbox {
  /// guards items and woken, and wakes the run loop
  private let stateLock = NSCondition()
  private var items = EmbedInboxItems()
  private var woken = false

  /// The inbox that a callback's user data points to; EmbedSession passes a
  /// retained inbox as the user data of every callback.
  static func of(_ userData: UnsafeMutableRawPointer?) -> EmbedInbox? {
    guard let userData else {
      return nil
    }
    return Unmanaged<EmbedInbox>.fromOpaque(userData).takeUnretainedValue()
  }

  /// Ctrl-C or SIGTERM: stop the device and exit with code 0.
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

  /// Keeps the refreshed token for the run loop to save. A newer token replaces
  /// one that is not saved yet.
  func jwtRefreshed(_ clientJwt: String) {
    stateLock.lock()
    defer { stateLock.unlock() }
    items.refreshedClientJwt = clientJwt
    wakeWithLock()
  }

  /// A contract opened, closed or was refused: the caps may have changed.
  func contractStatusChanged() {
    stateLock.lock()
    defer { stateLock.unlock() }
    items.contractStatusChanged = true
    wakeWithLock()
  }

  /// Hands a finished cap read to the run loop.
  func capReadFinished(_ capRead: CapRead) {
    stateLock.lock()
    defer { stateLock.unlock() }
    items.capRead = capRead
    wakeWithLock()
  }

  /// Returns the pending work and clears it. A stop or logout stays set, so a
  /// later take still ends the loop.
  func take() -> EmbedInboxItems {
    stateLock.lock()
    defer { stateLock.unlock() }
    let taken = items
    items = EmbedInboxItems(
      stopRequested: items.stopRequested, authLoggedOut: items.authLoggedOut)
    return taken
  }

  /// Waits until a callback, a cap read or a signal adds work, or until seconds
  /// pass.
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

  /// Wakes the run loop. The caller holds stateLock.
  private func wakeWithLock() {
    woken = true
    stateLock.signal()
  }
}
