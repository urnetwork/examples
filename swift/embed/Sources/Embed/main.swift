// The Swift embed example: a console app for Windows, macOS and Linux that
// embeds the URnetwork SDK in the developer's own product (EMBED_CONTRACT.md).
// It obtains this installation's scoped client JWT from the developer's
// backend, starts a local device with it on the SDK's C ABI, and shows the
// status and the data caps that the backend set for this installation. The
// device carries only the app's own traffic; what the app sends through it
// continues in the Sockets and Messages examples.
//
// Usage: embed [run] | --self-test | --licenses | --version. The settings come
// from the environment: URNETWORK_EMBED_STATE_DIR (required),
// URNETWORK_TOKEN_SERVER_URL with URNETWORK_DEMO_SESSION, and
// URNETWORK_API_URL.
//
// Exit codes, for supervisors: 0 stopped on request, 78 configuration or
// credential problem (restarting does not help), 1 any other failure.

import CURnetworkSdk
import EmbedCore
import Foundation

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

/// The SDK values that EmbedCore keeps for its status rules equal the ones in
/// urnetwork_sdk.h. These are header constants: the check calls no SDK function.
func checkSdkHeaderValues() throws {
  if clientLimitStatusNone != URNET_CLIENT_LIMIT_STATUS_NONE
    || clientLimitStatusExceeded != URNET_CLIENT_LIMIT_STATUS_EXCEEDED
  {
    throw SelfTestError("sdk values: the status rules differ from urnetwork_sdk.h")
  }
}

/// Keeps the SDK's log files in logDir, which the SDK bounds, instead of the
/// system temp directory. The SDK also copies its log lines to stderr.
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
    throw EmbedStartError(errorText ?? "the sdk refused the directory")
  }
}

/// Runs the app until a stop request or until the server rejects the
/// credential, and returns the exit code.
func runEmbed() -> Int32 {
  let config: EmbedConfig
  switch loadEmbedConfig(settings: .fromEnvironment(), http: urlSessionHttp) {
  case .loaded(let loaded):
    config = loaded
  case .failed(let exitCode, let error):
    writeError(error)
    return exitCode
  }
  do {
    try configureSdkLogs(logDir: joinPath(config.stateDir, "logs"))
  } catch {
    writeError("could not set the sdk log directory: \(error)")
    return EmbedExitCode.failure
  }
  let inbox = EmbedInbox()
  let stopSignals = StopSignals(inbox: inbox)
  let session: EmbedSession
  do {
    session = try EmbedSession(config: config, inbox: inbox)
  } catch {
    writeError("could not start the device: \(error)")
    return EmbedExitCode.failure
  }
  defer { session.close() }
  writeOutput(startLine(clientId: config.clientId, instanceId: config.instanceId))
  return withExtendedLifetime(stopSignals) {
    session.run()
  }
}

/// Prints the SDK's licenses and data attributions for this kind of app, as
/// JSON. Publish them with the app.
func printLicenses() -> Int32 {
  guard let licenses = takeSdkString(urnet_get_licenses(licenseApp)) else {
    writeError("the sdk returned no licenses")
    return EmbedExitCode.failure
  }
  writeOutput(licenses)
  return EmbedExitCode.stopped
}

/// Runs one command and returns the exit code.
func runCommand(arguments: [String]) -> Int32 {
  switch EmbedCommand(arguments: arguments) {
  case .selfTest:
    do {
      try runSelfTest()
      try checkSdkHeaderValues()
    } catch {
      writeError("embed self-test failed: \(error)")
      return EmbedExitCode.failure
    }
    writeOutput("embed self-test passed")
    return EmbedExitCode.stopped
  case .licenses:
    return printLicenses()
  case .version:
    writeOutput(takeSdkString(urnet_version()) ?? "")
    return EmbedExitCode.stopped
  case .usage:
    writeError(embedUsage)
    return EmbedExitCode.config
  case .run:
    return runEmbed()
  }
}

exit(runCommand(arguments: Array(CommandLine.arguments.dropFirst())))
