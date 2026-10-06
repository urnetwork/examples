// The Swift provider example: a console app for Windows, macOS and Linux that
// runs a URnetwork provider for the developer's network and shows its status
// (PROVIDER_CONTRACT.md). It provides publicly with the scoped client
// credential that the developer's backend issued for this installation; the
// payout wallet is mapped by the backend and is only displayed here.
//
// Usage: provider [run] | --self-test | --version. All installation state is
// in the private directory named by URNETWORK_PROVIDER_STATE_DIR (State.swift
// in ProviderCore).
//
// Exit codes, for supervisors: 0 stopped on request, 78 configuration or
// credential problem (restarting does not help), 1 any other failure.

import CURnetworkSdk
import Foundation
import ProviderCore

#if canImport(Darwin)
  import Darwin
#elseif canImport(Glibc)
  import Glibc
#elseif canImport(Musl)
  import Musl
#elseif canImport(CRT)
  import CRT
#endif

/// Writes one line to stdout at once, also when stdout is a file, so background
/// logs show each status line when it happens.
func writeOutput(_ line: String) {
  try? FileHandle.standardOutput.write(contentsOf: Data((line + "\n").utf8))
}

/// Writes one line to stderr.
func writeError(_ line: String) {
  try? FileHandle.standardError.write(contentsOf: Data((line + "\n").utf8))
}

/// The SDK values that ProviderCore keeps for its status rules equal the ones in
/// urnetwork_sdk.h. These are header constants: the check calls no SDK function.
func checkSdkHeaderValues() throws {
  let provideModesMatch =
    provideModeNone == Int64(URNET_PROVIDE_MODE_NONE)
    && provideModeNetwork == Int64(URNET_PROVIDE_MODE_NETWORK)
    && provideModePublic == Int64(URNET_PROVIDE_MODE_PUBLIC)
  let statusValuesMatch =
    clientLimitStatusNone == URNET_CLIENT_LIMIT_STATUS_NONE
    && clientLimitStatusExceeded == URNET_CLIENT_LIMIT_STATUS_EXCEEDED
    && snWalletConsentScopeNetwork == URNET_SN_WALLET_CONSENT_SCOPE_NETWORK
    && snWalletConsentScopeProvider == URNET_SN_WALLET_CONSENT_SCOPE_PROVIDER
  if !provideModesMatch || !statusValuesMatch {
    throw SelfTestError("sdk values: the status rules differ from urnetwork_sdk.h")
  }
}

/// Keeps the SDK's log files in logDir, which the SDK bounds (16 MiB files, the
/// newest four kept at each start), instead of the system temp directory. The
/// SDK also copies its log lines to stderr.
func configureSdkLogs(logDir: String) throws {
  #if os(Windows)
    try FileManager.default.createDirectory(atPath: logDir, withIntermediateDirectories: true)
  #else
    try FileManager.default.createDirectory(
      atPath: logDir, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
  #endif
  var error: UnsafeMutablePointer<CChar>?
  let configured = urnet_set_log_dir(logDir, &error)
  let errorText = takeSdkString(error)
  if !configured {
    throw ProviderStartError(errorText ?? "the sdk refused the directory")
  }
}

/// Runs one command and returns the exit code.
func runProvider(arguments: [String]) -> Int32 {
  switch ProviderCommand(arguments: arguments) {
  case .selfTest:
    do {
      try runSelfTest()
      try checkSdkHeaderValues()
    } catch {
      writeError("provider self-test failed: \(error)")
      return ProviderExitCode.failure
    }
    writeOutput("provider self-test passed")
    return ProviderExitCode.stopped
  case .version:
    writeOutput(takeSdkString(urnet_version()) ?? "")
    return ProviderExitCode.stopped
  case .usage:
    writeError(providerUsage)
    return ProviderExitCode.config
  case .run:
    break
  }

  writeOutput(consentDisclaimer)
  let config: ProviderConfig
  do {
    config = try loadProviderConfig(
      stateDir: ProcessInfo.processInfo.environment["URNETWORK_PROVIDER_STATE_DIR"])
  } catch {
    writeError("\(error)")
    return ProviderExitCode.config
  }
  do {
    try configureSdkLogs(logDir: joinPath(config.stateDir, "logs"))
  } catch {
    writeError("could not set the sdk log directory: \(error)")
    return ProviderExitCode.failure
  }
  let inbox = ProviderInbox(clientsServed: ClientsServed(limit: clientsServedLimit))
  let stopSignals = StopSignals(inbox: inbox)
  let session: ProviderSession
  do {
    session = try ProviderSession(config: config, inbox: inbox)
  } catch {
    writeError("could not start the provider: \(error)")
    return ProviderExitCode.failure
  }
  defer { session.close() }
  writeOutput("provider client \(config.clientId), instance \(config.instanceId)")
  return withExtendedLifetime(stopSignals) {
    session.run()
  }
}

exit(runProvider(arguments: Array(CommandLine.arguments.dropFirst())))
