// The Java embed example: a console app for Windows, macOS and Linux that
// embeds the URnetwork SDK in your own app (EMBED_CONTRACT.md). It obtains
// this installation's scoped client JWT from your backend (Token.java), starts
// the SDK's local Device with it for the app's own traffic (EmbedSession.java),
// and shows the connection and the installation's data caps
// (EmbedStatus.java). In-app traffic only: no VPN APIs, and the device does
// not provide.
//
// Usage: java -jar urnetwork-embed.jar [run] | --self-test | --licenses |
// --version. --licenses prints the SDK's licenses and data attributions, as
// JSON, to publish with the app. Installation state is in the private
// directory named by
// URNETWORK_EMBED_STATE_DIR (InstallationState.java). With
// URNETWORK_TOKEN_SERVER_URL and URNETWORK_DEMO_SESSION set, every start
// fetches the client JWT from the token server; otherwise the app uses the
// client.jwt in the state directory. The caps are read from URNETWORK_API_URL,
// default https://api.bringyour.com.
//
// Exit codes: 0 stopped on request (Ctrl-C or SIGTERM), 78 configuration or
// credential problem (restarting does not help), 1 any other failure.
import java.io.IOException;
import java.io.PrintStream;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntConsumer;

/** Commands and exit codes. */
public final class Main {
  static final int EXIT_STOPPED = 0;
  static final int EXIT_FAILURE = 1;
  // sysexits EX_CONFIG
  static final int EXIT_CONFIG = 78;

  static final String USAGE = "usage: urnetwork-embed [run] | --self-test | --licenses | --version";

  // how long Ctrl-C or SIGTERM waits for the device to stop before the
  // process exits anyway
  private static final Duration STOP_TIMEOUT = Duration.ofSeconds(15);

  /** Static members only. */
  private Main() {}

  /** Exits with the code of the command. */
  public static void main(String[] args) {
    System.exit(run(args, System.getenv(), System.out, System.err));
  }

  /** Runs one command and returns the exit code. env is the process environment. */
  static int run(String[] args, Map<String, String> env, PrintStream out, PrintStream err) {
    if (args.length == 1 && args[0].equals("--self-test")) {
      try {
        SelfTest.run();
      } catch (Exception e) {
        err.println("embed self-test failed: " + describe(e));
        return EXIT_FAILURE;
      }
      out.println("embed self-test passed");
      return EXIT_STOPPED;
    }
    if (args.length == 1 && args[0].equals("--licenses")) {
      String licenses;
      try {
        licenses = EmbedSession.sdkLicenses(EmbedStatus.licenseApp(System.getProperty("os.name")));
      } catch (LinkageError | RuntimeException e) {
        err.println("could not load the URnetwork SDK runtime: " + describe(e));
        return EXIT_FAILURE;
      }
      if (licenses == null) {
        err.println("the sdk returned no licenses");
        return EXIT_FAILURE;
      }
      out.println(licenses);
      return EXIT_STOPPED;
    }
    if (args.length == 1 && args[0].equals("--version")) {
      try {
        out.println(EmbedSession.sdkVersion());
      } catch (LinkageError | RuntimeException e) {
        err.println("could not load the URnetwork SDK runtime: " + describe(e));
        return EXIT_FAILURE;
      }
      return EXIT_STOPPED;
    }
    if (!(args.length == 0 || (args.length == 1 && args[0].equals("run")))) {
      err.println(USAGE);
      return EXIT_CONFIG;
    }
    return runEmbed(env, out, err);
  }

  /** Embeds the device until Ctrl-C, SIGTERM or a credential that the server rejects. */
  private static int runEmbed(Map<String, String> env, PrintStream out, PrintStream err) {
    // from here on Ctrl-C and SIGTERM stop the device and exit with 0, also
    // while the client JWT is fetched and the device starts
    EmbedSession session = new EmbedSession(out, err);
    StopHook stopHook = new StopHook(session, Runtime.getRuntime()::halt);
    Runtime.getRuntime().addShutdownHook(new Thread(stopHook::stop, "embed-stop"));
    int exitCode = EXIT_FAILURE;
    try {
      exitCode = startAndRun(env, session, out, err);
    } finally {
      try {
        session.close();
      } catch (RuntimeException | LinkageError e) {
        err.println("could not close the embedded device: " + describe(e));
        exitCode = EXIT_FAILURE;
      }
      stopHook.closed(exitCode);
    }
    return exitCode;
  }

  /**
   * Loads the settings, obtains the client JWT, starts the device and shows the status until a
   * stop. A stop requested before the device starts ends here with 0.
   */
  private static int startAndRun(Map<String, String> env, EmbedSession session, PrintStream out,
                                 PrintStream err) {
    InstallationState.EmbedSettings settings;
    Token.ClientCredential credential;
    try {
      settings = InstallationState.loadSettings(env);
      credential = Token.obtainClientJwt(settings, Token.HttpTransport.jdk());
    } catch (InstallationState.ConfigException e) {
      err.println(e.getMessage());
      return EXIT_CONFIG;
    } catch (Token.TokenFetchException e) {
      if (session.stopRequested()) {
        return EXIT_STOPPED;
      }
      err.println(e.getMessage());
      return e.exitCode();
    }
    if (session.stopRequested()) {
      return EXIT_STOPPED;
    }
    try {
      EmbedSession.configureSdkLogs(settings.stateDir().resolve(InstallationState.LOG_DIR_NAME));
    } catch (IOException | LinkageError | RuntimeException e) {
      err.println("could not set the sdk log directory: " + describe(e));
      return EXIT_FAILURE;
    }
    if (session.stopRequested()) {
      return EXIT_STOPPED;
    }
    try {
      session.start(settings, credential);
    } catch (IOException | LinkageError | RuntimeException e) {
      err.println("could not start the embedded device: " + describe(e));
      return EXIT_FAILURE;
    }
    out.println(EmbedStatus.startLine(credential.clientId(), settings.instanceId()));
    try {
      return session.run();
    } catch (RuntimeException | LinkageError e) {
      err.println("the embedded device failed: " + describe(e));
      return EXIT_FAILURE;
    }
  }

  /** The message of a failure, without a stack trace. */
  static String describe(Throwable e) {
    // a runtime that fails to load surfaces from the sdk class's initializer
    if (e instanceof ExceptionInInitializerError && e.getCause() != null) {
      e = e.getCause();
    }
    if (e instanceof IOException ioException) {
      return InstallationState.describe(ioException);
    }
    String message = e.getMessage();
    return message != null ? message : e.getClass().getName();
  }

  /**
   * The shutdown hook that the JVM runs after Ctrl-C or SIGTERM, and after main's own exit. After
   * a signal it stops the device (or keeps it from starting), waits for main to close it and ends
   * the process with 0, where the JVM would exit with the signal's status (130 or 143): supervisors
   * read 0 as a requested stop. When main has closed the session first, it ends the process with
   * main's exit code, such as 78 after the server rejected the credential.
   */
  static final class StopHook {
    private final EmbedSession session;
    // Runtime.halt, which ends the process without waiting for other hooks
    private final IntConsumer halt;
    private final CountDownLatch closedLatch = new CountDownLatch(1);
    // main's exit code once main has closed the session; null before
    private final AtomicReference<Integer> mainExitCode = new AtomicReference<>();

    /** A hook for one session that ends the process with halt. */
    StopHook(EmbedSession session, IntConsumer halt) {
      this.session = session;
      this.halt = halt;
    }

    /** Runs in the JVM's shutdown and ends the process. */
    void stop() {
      Integer exitCode = mainExitCode.get();
      if (exitCode == null) {
        // a signal came before main closed the session
        session.requestStop();
        try {
          closedLatch.await(STOP_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
        exitCode = EXIT_STOPPED;
      }
      halt.accept(exitCode);
    }

    /** Main has closed the session and exits with exitCode, unless a signal came first. */
    void closed(int exitCode) {
      mainExitCode.compareAndSet(null, exitCode);
      closedLatch.countDown();
    }
  }
}
