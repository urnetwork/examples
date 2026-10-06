// The command line and the exit codes (PROVIDER_CONTRACT.md, "Exit codes"):
// provider [run] | --self-test | --version. Supervisors restart on failure but
// not after 0 or 78.

/// Exit codes, for supervisors.
public enum ProviderExitCode {
  /// stopped on request (Ctrl-C, SIGTERM), and the commands that only print
  public static let stopped: Int32 = 0
  /// any other failure; supervisors restart after it
  public static let failure: Int32 = 1
  /// a configuration or credential problem that restarting does not fix
  /// (sysexits EX_CONFIG)
  public static let config: Int32 = 78
}

/// The usage line, printed on stderr with exit code ProviderExitCode.config.
public let providerUsage = "usage: provider [run] | --self-test | --version"

/// One command of the command line.
public enum ProviderCommand: Equatable {
  case run
  case selfTest
  case version
  /// any other arguments: print the usage and exit with ProviderExitCode.config
  case usage

  /// The command for the arguments after the program name.
  public init(arguments: [String]) {
    switch arguments {
    case [], ["run"]:
      self = .run
    case ["--self-test"]:
      self = .selfTest
    case ["--version"]:
      self = .version
    default:
      self = .usage
    }
  }
}
