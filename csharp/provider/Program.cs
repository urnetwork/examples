// The C# provider example: a console app for Windows, macOS and Linux that
// runs a URnetwork provider for the developer's network and shows its status
// (PROVIDER_CONTRACT.md). It provides publicly with the scoped client
// credential that the developer's backend issued for this installation; the
// payout wallet is mapped by the backend and is only displayed here.
//
// Usage: ProviderExample [run] | --self-test | --version. All installation
// state is in the private directory named by URNETWORK_PROVIDER_STATE_DIR
// (State.cs).
//
// Exit codes, for supervisors: 0 stopped on request (Ctrl-C or SIGTERM), 78
// configuration or credential problem (restarting does not help), 1 any other
// failure.
using System.Runtime.InteropServices;
using URnetwork.SDK;

/// Commands and exit codes.
internal static class Program {
  public const int ExitStopped = 0;
  public const int ExitFailure = 1;
  // sysexits EX_CONFIG
  public const int ExitConfig = 78;

  /// Runs the command; an unexpected error exits with 1.
  public static int Main(string[] args) {
    try {
      return Run(args, Console.Out, Console.Error);
    } catch (Exception e) {
      // the innermost reason, for example a native library that does not load
      Console.Error.WriteLine(e.GetBaseException().Message);
      return ExitFailure;
    }
  }

  /// Runs one command and returns the exit code. The self-test passes its own
  /// writers.
  public static int Run(string[] args, TextWriter output, TextWriter error) {
    switch (args) {
    case ["--self-test"]:
      try {
        SelfTest.Run();
      } catch (Exception e) {
        error.WriteLine($"provider self-test failed: {e.Message}");
        return ExitFailure;
      }
      output.WriteLine("provider self-test passed");
      return ExitStopped;
    case ["--version"]:
      output.WriteLine(Sdk.Version);
      return ExitStopped;
    case [] or ["run"]:
      return Provide(output, error);
    default:
      error.WriteLine("usage: ProviderExample [run] | --self-test | --version");
      return ExitConfig;
    }
  }

  /// Provides until Ctrl-C or SIGTERM, or until the server rejects the client
  /// credential.
  private static int Provide(TextWriter output, TextWriter error) {
    output.WriteLine(StatusRules.ConsentDisclaimer);
    ProviderConfig config;
    try {
      config = ProviderConfig.Load(
          Environment.GetEnvironmentVariable("URNETWORK_PROVIDER_STATE_DIR"));
    } catch (Exception e) {
      error.WriteLine(e.Message);
      return ExitConfig;
    }
    try {
      ConfigureSdkLogs(Path.Combine(config.StateDir, "logs"));
    } catch (Exception e) {
      error.WriteLine($"could not set the sdk log directory: {e.GetBaseException().Message}");
      return ExitFailure;
    }
    using var stop = new CancellationTokenSource();
    ConsoleCancelEventHandler interrupt = (_, e) => {
      // stop providing and exit 0, instead of the default termination
      e.Cancel = true;
      stop.Cancel();
    };
    Console.CancelKeyPress += interrupt;
    try {
      using PosixSignalRegistration? terminate = RegisterTerminate(stop);
      ProviderSession session;
      try {
        session = new ProviderSession(config, output, error);
      } catch (Exception e) {
        error.WriteLine($"could not start the provider: {e.GetBaseException().Message}");
        return ExitFailure;
      }
      using (session) {
        output.WriteLine($"provider client {config.ClientId}, instance {config.InstanceId}");
        return session.Run(stop.Token);
      }
    } finally {
      Console.CancelKeyPress -= interrupt;
    }
  }

  /// Stops on SIGTERM as on Ctrl-C (on Windows, a system shutdown). Null where
  /// the platform has no such signal.
  private static PosixSignalRegistration? RegisterTerminate(
      CancellationTokenSource stop) {
    try {
      return PosixSignalRegistration.Create(PosixSignal.SIGTERM, context => {
        context.Cancel = true;
        stop.Cancel();
      });
    } catch (PlatformNotSupportedException) {
      return null;
    }
  }

  /// Keeps the SDK's log files in logDir, which the SDK bounds (16 MiB files,
  /// the newest four kept at each start), instead of the system temp
  /// directory. The SDK also copies its log lines to stderr.
  private static void ConfigureSdkLogs(string logDir) {
    if (OperatingSystem.IsWindows()) {
      Directory.CreateDirectory(logDir);
    } else {
      Directory.CreateDirectory(
          logDir, UnixFileMode.UserRead | UnixFileMode.UserWrite | UnixFileMode.UserExecute);
    }
    byte set = Raw.urnet_set_log_dir(logDir, out IntPtr setError);
    string? message = Sdk.TakeString(setError);
    if (set == 0) {
      throw new IOException(message ?? "the sdk refused the log directory");
    }
  }
}
