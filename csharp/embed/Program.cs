// The C# embed example: a console app for Windows, macOS and Linux that embeds
// the URnetwork SDK in your own app (EMBED_CONTRACT.md). It obtains this
// installation's scoped client JWT from your backend (Token.cs), starts the
// SDK's local Device with it for the app's own traffic (Session.cs), and shows
// the connection and the installation's data caps (Status.cs). In-app traffic
// only: no VPN APIs, and the device does not provide.
//
// Usage: EmbedExample [run] | --self-test | --licenses | --version.
// --licenses prints the SDK's licenses and data attributions, as JSON, to
// publish with the app. Installation state is
// in the private directory named by URNETWORK_EMBED_STATE_DIR (State.cs). With
// URNETWORK_TOKEN_SERVER_URL and URNETWORK_DEMO_SESSION set, every start
// fetches the client JWT from the token server; otherwise the app uses the
// client.jwt in the state directory. The caps are read from
// URNETWORK_API_URL, default https://api.bringyour.com.
//
// Exit codes: 0 stopped on request (Ctrl-C or SIGTERM), 78 configuration or
// credential problem (restarting does not help), 1 any other failure.
using System.Runtime.InteropServices;
using URnetwork.SDK;

/// Commands and exit codes.
internal static class Program {
  public const int ExitStopped = 0;
  public const int ExitFailure = 1;
  // sysexits EX_CONFIG
  public const int ExitConfig = 78;

  public const string Usage = "usage: EmbedExample [run] | --self-test | --licenses | --version";

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
        error.WriteLine($"embed self-test failed: {e.Message}");
        return ExitFailure;
      }
      output.WriteLine("embed self-test passed");
      return ExitStopped;
    case ["--licenses"]:
      string? licenses = Sdk.TakeString(Raw.urnet_get_licenses(
          StatusRules.LicenseApp(OperatingSystem.IsWindows(), OperatingSystem.IsMacOS())));
      if (licenses == null) {
        error.WriteLine("the sdk returned no licenses");
        return ExitFailure;
      }
      output.WriteLine(licenses);
      return ExitStopped;
    case ["--version"]:
      output.WriteLine(Sdk.Version);
      return ExitStopped;
    case [] or ["run"]:
      return Embed(output, error);
    default:
      error.WriteLine(Usage);
      return ExitConfig;
    }
  }

  /// Obtains the client JWT, starts the embedded device and shows the status
  /// until Ctrl-C or SIGTERM, or until the server rejects the credential.
  private static int Embed(TextWriter output, TextWriter error) {
    using var stop = new CancellationTokenSource();
    ConsoleCancelEventHandler interrupt = (_, e) => {
      // stop and exit 0, instead of the default termination
      e.Cancel = true;
      stop.Cancel();
    };
    Console.CancelKeyPress += interrupt;
    try {
      using PosixSignalRegistration? terminate = RegisterTerminate(stop);
      EmbedSettings settings;
      ClientCredential credential;
      try {
        settings = EmbedSettings.Load(
            Environment.GetEnvironmentVariable("URNETWORK_EMBED_STATE_DIR"),
            Environment.GetEnvironmentVariable("URNETWORK_TOKEN_SERVER_URL"),
            Environment.GetEnvironmentVariable("URNETWORK_DEMO_SESSION"),
            Environment.GetEnvironmentVariable("URNETWORK_API_URL"));
        credential = ClientJwtSource.ObtainClientJwt(settings, invoker: null, stop.Token);
      } catch (EmbedConfigException e) {
        error.WriteLine(e.Message);
        return ExitConfig;
      } catch (TokenFetchException e) {
        if (stop.IsCancellationRequested) {
          return ExitStopped;
        }
        error.WriteLine(e.Message);
        return e.ExitCode;
      }
      if (stop.IsCancellationRequested) {
        return ExitStopped;
      }
      try {
        ConfigureSdkLogs(Path.Combine(settings.StateDir, StateFiles.LogDirName));
      } catch (Exception e) {
        error.WriteLine($"could not set the sdk log directory: {e.GetBaseException().Message}");
        return ExitFailure;
      }
      EmbedSession session;
      try {
        session = new EmbedSession(settings, credential, output, error);
      } catch (Exception e) {
        error.WriteLine($"could not start the embedded device: {e.GetBaseException().Message}");
        return ExitFailure;
      }
      using (session) {
        output.WriteLine(StatusRules.StartLine(credential.ClientId, settings.InstanceId));
        return session.Run(stop.Token);
      }
    } finally {
      Console.CancelKeyPress -= interrupt;
    }
  }

  /// Stops on SIGTERM as on Ctrl-C (on Windows, a system shutdown). Null where
  /// the platform has no such signal.
  private static PosixSignalRegistration? RegisterTerminate(CancellationTokenSource stop) {
    try {
      return PosixSignalRegistration.Create(PosixSignal.SIGTERM, context => {
        context.Cancel = true;
        stop.Cancel();
      });
    } catch (PlatformNotSupportedException) {
      return null;
    }
  }

  /// Keeps the SDK's log files in logDir, which the SDK bounds, instead of the
  /// system temp directory. The SDK also copies its log lines to stderr.
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
