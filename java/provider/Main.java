// The Java provider example: a console app for Windows, macOS and Linux that
// runs a URnetwork provider for the developer's network and shows its status
// (PROVIDER_CONTRACT.md). It provides publicly with the scoped client
// credential that the developer's backend issued for this installation; the
// payout wallet is mapped by the backend and is only displayed here.
//
// Usage: java -jar urnetwork-provider.jar [run] | --self-test | --version.
// All installation state is in the private directory named by
// URNETWORK_PROVIDER_STATE_DIR (InstallationState.java).
//
// Exit codes, for supervisors: 0 stopped on request, 78 configuration or
// credential problem (restarting does not help), 1 any other failure.
import java.io.IOException;
import java.io.PrintStream;
import java.time.Duration;
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

  static final String USAGE =
      "usage: urnetwork-provider [run] | --self-test | --version";

  // how long Ctrl-C or SIGTERM waits for the provider to stop before the
  // process exits anyway
  private static final Duration STOP_TIMEOUT = Duration.ofSeconds(15);

  /** Static members only. */
  private Main() {}

  /** Exits with the code of the command. */
  public static void main(String[] args) {
    System.exit(run(args, System.getenv("URNETWORK_PROVIDER_STATE_DIR"),
                    System.out, System.err));
  }

  /**
   * Runs one command and returns the exit code. stateDir is the value of
   * URNETWORK_PROVIDER_STATE_DIR.
   */
  static int run(String[] args, String stateDir, PrintStream out,
                 PrintStream err) {
    if (args.length == 1 && args[0].equals("--self-test")) {
      try {
        SelfTest.run();
      } catch (Exception e) {
        err.println("provider self-test failed: " + describe(e));
        return EXIT_FAILURE;
      }
      out.println("provider self-test passed");
      return EXIT_STOPPED;
    }
    if (args.length == 1 && args[0].equals("--version")) {
      try {
        out.println(ProviderSession.sdkVersion());
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
    return runProvider(stateDir, out, err);
  }

  /** Provides until Ctrl-C, SIGTERM or a credential that the server rejects. */
  private static int runProvider(String stateDir, PrintStream out,
                                 PrintStream err) {
    out.println(ProviderStatus.CONSENT_DISCLAIMER);
    InstallationState.ProviderConfig config;
    try {
      config = InstallationState.load(stateDir);
    } catch (InstallationState.ConfigException e) {
      err.println(e.getMessage());
      return EXIT_CONFIG;
    }
    // from here on Ctrl-C and SIGTERM stop the provider and exit with 0, also
    // while the native runtime loads and the device starts
    ProviderSession session = new ProviderSession(config, out, err);
    StopHook stopHook = new StopHook(session, Runtime.getRuntime()::halt);
    Runtime.getRuntime().addShutdownHook(
        new Thread(stopHook::stop, "provider-stop"));
    int exitCode = EXIT_FAILURE;
    try {
      exitCode = startAndRun(config, session, out, err);
    } finally {
      try {
        session.close();
      } catch (RuntimeException | LinkageError e) {
        err.println("could not close the provider: " + describe(e));
        exitCode = EXIT_FAILURE;
      }
      stopHook.closed(exitCode);
    }
    return exitCode;
  }

  /**
   * Loads the native runtime, starts providing and shows the status until a stop. A stop requested
   * before the device starts ends here with 0.
   */
  private static int startAndRun(InstallationState.ProviderConfig config,
                                 ProviderSession session, PrintStream out,
                                 PrintStream err) {
    if (session.stopRequested()) {
      return EXIT_STOPPED;
    }
    try {
      ProviderSession.configureSdkLogs(
          config.stateDir().resolve(InstallationState.LOG_DIR_NAME));
    } catch (IOException | LinkageError | RuntimeException e) {
      err.println("could not set the sdk log directory: " + describe(e));
      return EXIT_FAILURE;
    }
    if (session.stopRequested()) {
      return EXIT_STOPPED;
    }
    try {
      session.start();
    } catch (IOException | LinkageError | RuntimeException e) {
      err.println("could not start the provider: " + describe(e));
      return EXIT_FAILURE;
    }
    out.printf("provider client %s, instance %s%n", config.clientId(),
               config.instanceId());
    try {
      return session.run();
    } catch (RuntimeException | LinkageError e) {
      err.println("the provider failed: " + describe(e));
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
   * a signal it stops the provider (or keeps it from starting), waits for main to close it and
   * ends the process with 0, where the JVM would exit with the signal's status (130 or 143):
   * supervisors read 0 as a requested stop. When main has closed the session first, it ends the
   * process with main's exit code, such as 78 after the server rejected the credential. It always
   * ends the process itself, so the signal's status never escapes, even when a signal and main's
   * exit race.
   */
  static final class StopHook {
    private final ProviderSession session;
    // Runtime.halt, which ends the process without waiting for other hooks
    private final IntConsumer halt;
    private final CountDownLatch closedLatch = new CountDownLatch(1);
    // main's exit code once main has closed the session; null before
    private final AtomicReference<Integer> mainExitCode = new AtomicReference<>();

    /** A hook for one session that ends the process with halt. */
    StopHook(ProviderSession session, IntConsumer halt) {
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
