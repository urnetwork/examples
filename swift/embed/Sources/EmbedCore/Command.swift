// The command line and the exit codes (EMBED_CONTRACT.md, "Exit codes"):
// embed [run] | --self-test | --licenses | --version.

/// Exit codes.
public enum EmbedExitCode {
  /// stopped on request (Ctrl-C, SIGTERM), and the commands that only print
  public static let stopped: Int32 = 0
  /// any other failure
  public static let failure: Int32 = 1
  /// a configuration or credential problem that restarting does not fix
  /// (sysexits EX_CONFIG)
  public static let config: Int32 = 78
}

/// The usage line, printed on stderr with exit code EmbedExitCode.config.
public let embedUsage = "usage: embed [run] | --self-test | --licenses | --version"

/// One command of the command line.
public enum EmbedCommand: Equatable {
  case run
  case selfTest
  case licenses
  case version
  /// any other arguments: print the usage and exit with EmbedExitCode.config
  case usage

  /// The command for the arguments after the program name.
  public init(arguments: [String]) {
    switch arguments {
    case [], ["run"]:
      self = .run
    case ["--self-test"]:
      self = .selfTest
    case ["--licenses"]:
      self = .licenses
    case ["--version"]:
      self = .version
    default:
      self = .usage
    }
  }
}
