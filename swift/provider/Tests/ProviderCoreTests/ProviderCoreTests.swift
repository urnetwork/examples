// Runs each self-test check as a test, so `swift test` and
// `provider --self-test` cover the same behavior. This target depends only on
// ProviderCore: it neither links nor loads the native SDK.

import ProviderCore
import XCTest

/// One test per self-test check.
final class ProviderCoreTests: XCTestCase {
  /// The disclaimer is the contract's exact text.
  func testConsentDisclaimer() throws {
    try checkConsentDisclaimer()
  }

  /// Byte counts use binary units with one decimal.
  func testFormatByteCount() throws {
    try checkFormatByteCount()
  }

  /// The client limit status text names the rounded-up retry time.
  func testStatusText() throws {
    try checkStatusText()
  }

  /// The status line matches the contract's golden lines.
  func testStatusLines() throws {
    try checkStatusLines()
  }

  /// A new retry time prints a line; the data counter alone does not.
  func testStatusKey() throws {
    try checkStatusKey()
  }

  /// The providing state rules apply in the contract's order.
  func testProviderState() throws {
    try checkProviderState()
  }

  /// The payout wallet label checks the consent scope first.
  func testPayoutWalletScope() throws {
    try checkPayoutWalletScope()
  }

  /// Contract peers resolve by direction and count once per client.
  func testClientsServed() throws {
    try checkClientsServed()
  }

  /// The C ABI's JSON values decode to the status inputs.
  func testSdkJson() throws {
    try checkSdkJson()
  }

  /// Only a JWT with a valid client_id claim is a client credential.
  func testClientJwtClaims() throws {
    try checkClientJwtClaims()
  }

  /// State files are private, atomic, created once and bound to their client.
  func testStateFiles() throws {
    try checkStateFiles()
  }

  /// A missing or incomplete installation state is refused.
  func testProviderConfig() throws {
    try checkProviderConfig()
  }

  /// Unknown arguments are a usage error with exit code 78.
  func testUsage() throws {
    try checkUsage()
  }

  /// --self-test runs every check above.
  func testSelfTestRunsEveryCheck() throws {
    XCTAssertEqual(selfTestChecks.count, 13)
    try runSelfTest()
  }
}
