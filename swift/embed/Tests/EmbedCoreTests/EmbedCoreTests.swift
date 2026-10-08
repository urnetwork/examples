// Runs each self-test check as a test, so `swift test` and `embed --self-test`
// cover the same behavior. This target depends only on EmbedCore: it neither
// links nor loads the native SDK.

import EmbedCore
import XCTest

/// One test per self-test check.
final class EmbedCoreTests: XCTestCase {
  /// Data amounts use decimal units with one decimal, ties to even.
  func testFormatByteCount() throws {
    try checkFormatByteCount()
  }

  /// The monthly reset time is in UTC, rounded up to the next minute.
  func testResetText() throws {
    try checkResetText()
  }

  /// The client limit text names the rounded-up retry time.
  func testClientLimitText() throws {
    try checkClientLimitText()
  }

  /// The status line matches the contract's golden lines.
  func testStatusLines() throws {
    try checkStatusLines()
  }

  /// The status rules apply in the contract's order.
  func testStatusRules() throws {
    try checkStatusRules()
  }

  /// The data fields: checking, unavailable, no cap, and used of limit.
  func testDataFields() throws {
    try checkDataFields()
  }

  /// The cap object's fields, and the answers that are not a cap object.
  func testCapObject() throws {
    try checkCapObject()
  }

  /// The C ABI's JSON values decode to the status inputs.
  func testSdkJson() throws {
    try checkSdkJson()
  }

  /// Only a JWT with a valid client_id claim is a client credential.
  func testClientJwtClaims() throws {
    try checkClientJwtClaims()
  }

  /// Origins follow the allocators' rules.
  func testOriginUrl() throws {
    try checkOriginUrl()
  }

  /// The token fetch against a stand-in server.
  func testTokenFetch() throws {
    try checkTokenFetch()
  }

  /// The cap read with the client JWT.
  func testCapRead() throws {
    try checkCapRead()
  }

  /// State files are private, atomic and created once.
  func testStateFiles() throws {
    try checkStateFiles()
  }

  /// Configuration problems exit with code 78.
  func testConfiguration() throws {
    try checkConfiguration()
  }

  /// Unknown arguments are a usage error with exit code 78.
  func testUsage() throws {
    try checkUsage()
  }

  /// --self-test runs every check above.
  func testSelfTestRunsEveryCheck() throws {
    XCTAssertEqual(selfTestChecks.count, 15)
    try runSelfTest()
  }
}
