// The Kotlin provider example: a console app for Windows, macOS and Linux
// that runs a URnetwork provider for the developer's network and shows its
// status (PROVIDER_CONTRACT.md). It provides publicly with the scoped client
// credential that the developer's backend issued for this installation; the
// payout wallet is mapped by the backend and is only displayed here.
//
// Usage: urnetwork-provider [run] | --self-test | --version. All installation
// state is in the private directory named by URNETWORK_PROVIDER_STATE_DIR
// (InstallationState.kt).
//
// Exit codes, for supervisors: 0 stopped on request, 78 configuration or
// credential problem (restarting does not help), 1 any other failure.
import java.io.IOException
import java.io.PrintStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.seconds

const val exitStopped = 0
const val exitFailure = 1

// sysexits EX_CONFIG
const val exitConfig = 78

const val usage = "usage: urnetwork-provider [run] | --self-test | --version"

// how long Ctrl-C or SIGTERM waits for the provider to stop before the process
// exits anyway
private val stopTimeout = 15.seconds

// no exit code decided yet; exit codes are 0 to 255
private const val noExitCode = -1

/** Exits with the code of the command. */
fun main(args: Array<String>) {
    exitProcess(runCommand(args.toList(), System.getenv("URNETWORK_PROVIDER_STATE_DIR"), System.out, System.err))
}

/** Runs one command and returns the exit code. stateDir is the value of URNETWORK_PROVIDER_STATE_DIR. */
fun runCommand(args: List<String>, stateDir: String?, out: PrintStream, err: PrintStream): Int {
    when {
        args == listOf("--self-test") -> {
            try {
                runSelfTest()
            } catch (e: Exception) {
                err.println("provider self-test failed: ${describeFailure(e)}")
                return exitFailure
            }
            out.println("provider self-test passed")
            return exitStopped
        }
        args == listOf("--version") -> {
            try {
                out.println(ProviderSession.sdkVersion())
            } catch (e: LinkageError) {
                err.println("could not load the URnetwork SDK runtime: ${describeFailure(e)}")
                return exitFailure
            } catch (e: RuntimeException) {
                err.println("could not load the URnetwork SDK runtime: ${describeFailure(e)}")
                return exitFailure
            }
            return exitStopped
        }
        args.isEmpty() || args == listOf("run") -> return runProvider(stateDir, out, err)
        else -> {
            err.println(usage)
            return exitConfig
        }
    }
}

/** Provides until Ctrl-C, SIGTERM or a credential that the server rejects. */
private fun runProvider(stateDir: String?, out: PrintStream, err: PrintStream): Int {
    out.println(consentDisclaimer)
    val config = try {
        loadProviderConfig(stateDir)
    } catch (e: ConfigException) {
        err.println(e.message)
        return exitConfig
    }
    // from here on Ctrl-C and SIGTERM stop the provider and exit with 0, also
    // while the native runtime loads and the device starts
    val session = ProviderSession(config, out, err)
    val stopHook = StopHook(session, Runtime.getRuntime()::halt)
    Runtime.getRuntime().addShutdownHook(Thread(stopHook::stop, "provider-stop"))
    var exitCode = exitFailure
    try {
        exitCode = startAndRun(config, session, out, err)
    } finally {
        try {
            session.close()
        } catch (e: RuntimeException) {
            err.println("could not close the provider: ${describeFailure(e)}")
            exitCode = exitFailure
        } catch (e: LinkageError) {
            err.println("could not close the provider: ${describeFailure(e)}")
            exitCode = exitFailure
        }
        stopHook.closed(exitCode)
    }
    return exitCode
}

/**
 * Loads the native runtime, starts providing and shows the status until a stop. A stop requested
 * before the device starts ends here with 0.
 */
private fun startAndRun(config: ProviderConfig, session: ProviderSession, out: PrintStream, err: PrintStream): Int {
    if (session.stopRequested) {
        return exitStopped
    }
    try {
        ProviderSession.configureSdkLogs(config.stateDir.resolve(logDirName))
    } catch (e: Exception) {
        err.println("could not set the sdk log directory: ${describeFailure(e)}")
        return exitFailure
    } catch (e: LinkageError) {
        err.println("could not set the sdk log directory: ${describeFailure(e)}")
        return exitFailure
    }
    if (session.stopRequested) {
        return exitStopped
    }
    try {
        session.start()
    } catch (e: Exception) {
        err.println("could not start the provider: ${describeFailure(e)}")
        return exitFailure
    } catch (e: LinkageError) {
        err.println("could not start the provider: ${describeFailure(e)}")
        return exitFailure
    }
    out.println("provider client ${config.clientId}, instance ${config.instanceId}")
    return try {
        session.run()
    } catch (e: RuntimeException) {
        err.println("the provider failed: ${describeFailure(e)}")
        exitFailure
    } catch (e: LinkageError) {
        err.println("the provider failed: ${describeFailure(e)}")
        exitFailure
    }
}

/** The message of a failure, without a stack trace. */
fun describeFailure(e: Throwable): String {
    // a runtime that fails to load surfaces from the sdk class's initializer
    val cause = if (e is ExceptionInInitializerError) e.cause ?: e else e
    if (cause is IOException) {
        return describe(cause)
    }
    return cause.message ?: cause.javaClass.name
}

/**
 * The shutdown hook that the JVM runs after Ctrl-C or SIGTERM, and after main's own exit. After a
 * signal it stops the provider (or keeps it from starting), waits for main to close it and ends the
 * process with 0, where the JVM would exit with the signal's status (130 or 143): supervisors read 0
 * as a requested stop. When main has closed the session first, it ends the process with main's exit
 * code, such as 78 after the server rejected the credential. It always ends the process itself
 * (halt is Runtime.halt), so the signal's status never escapes, even when a signal and main's exit
 * race.
 */
class StopHook(private val session: ProviderSession, private val halt: (Int) -> Unit) {
    private val closedLatch = CountDownLatch(1)

    // main's exit code once main has closed the session; noExitCode before
    private val mainExitCode = AtomicInteger(noExitCode)

    /** Runs in the JVM's shutdown and ends the process. */
    fun stop() {
        var exitCode = mainExitCode.get()
        if (exitCode == noExitCode) {
            // a signal came before main closed the session
            session.requestStop()
            try {
                closedLatch.await(stopTimeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            exitCode = exitStopped
        }
        halt(exitCode)
    }

    /** Main has closed the session and exits with exitCode, unless a signal came first. */
    fun closed(exitCode: Int) {
        mainExitCode.compareAndSet(noExitCode, exitCode)
        closedLatch.countDown()
    }
}
