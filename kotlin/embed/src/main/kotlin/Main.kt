// The Kotlin embed example: a console app for Windows, macOS and Linux that
// embeds the URnetwork SDK in your own app (EMBED_CONTRACT.md). It obtains
// this installation's scoped client JWT from your backend (Token.kt), starts
// the SDK's local Device with it for the app's own traffic (EmbedSession.kt),
// and shows the connection and the installation's data caps (EmbedStatus.kt).
// In-app traffic only: no VPN APIs, and the device does not provide.
//
// Usage: urnetwork-embed [run] | --self-test | --licenses | --version.
// --licenses prints the SDK's licenses and data attributions, as JSON, to
// publish with the app. Installation state is in the private directory named
// by URNETWORK_EMBED_STATE_DIR
// (InstallationState.kt). With URNETWORK_TOKEN_SERVER_URL and
// URNETWORK_DEMO_SESSION set, every start fetches the client JWT from the
// token server; otherwise the app uses the client.jwt in the state directory.
// The caps are read from URNETWORK_API_URL, default https://api.bringyour.com.
//
// Exit codes: 0 stopped on request (Ctrl-C or SIGTERM), 78 configuration or
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

const val usage = "usage: urnetwork-embed [run] | --self-test | --licenses | --version"

// how long Ctrl-C or SIGTERM waits for the device to stop before the process
// exits anyway
private val stopTimeout = 15.seconds

// no exit code decided yet; exit codes are 0 to 255
private const val noExitCode = -1

/** Exits with the code of the command. */
fun main(args: Array<String>) {
    exitProcess(runCommand(args.toList(), System.getenv(), System.out, System.err))
}

/** Runs one command and returns the exit code. env is the process environment. */
fun runCommand(args: List<String>, env: Map<String, String>, out: PrintStream, err: PrintStream): Int {
    when {
        args == listOf("--self-test") -> {
            try {
                runSelfTest()
            } catch (e: Exception) {
                err.println("embed self-test failed: ${describeFailure(e)}")
                return exitFailure
            }
            out.println("embed self-test passed")
            return exitStopped
        }
        args == listOf("--licenses") -> {
            val licenses = try {
                EmbedSession.sdkLicenses(licenseApp(System.getProperty("os.name")))
            } catch (e: LinkageError) {
                err.println("could not load the URnetwork SDK runtime: ${describeFailure(e)}")
                return exitFailure
            } catch (e: RuntimeException) {
                err.println("could not load the URnetwork SDK runtime: ${describeFailure(e)}")
                return exitFailure
            }
            if (licenses == null) {
                err.println("the sdk returned no licenses")
                return exitFailure
            }
            out.println(licenses)
            return exitStopped
        }
        args == listOf("--version") -> {
            try {
                out.println(EmbedSession.sdkVersion())
            } catch (e: LinkageError) {
                err.println("could not load the URnetwork SDK runtime: ${describeFailure(e)}")
                return exitFailure
            } catch (e: RuntimeException) {
                err.println("could not load the URnetwork SDK runtime: ${describeFailure(e)}")
                return exitFailure
            }
            return exitStopped
        }
        args.isEmpty() || args == listOf("run") -> return runEmbed(env, out, err)
        else -> {
            err.println(usage)
            return exitConfig
        }
    }
}

/** Embeds the device until Ctrl-C, SIGTERM or a credential that the server rejects. */
private fun runEmbed(env: Map<String, String>, out: PrintStream, err: PrintStream): Int {
    // from here on Ctrl-C and SIGTERM stop the device and exit with 0, also
    // while the client JWT is fetched and the device starts
    val session = EmbedSession(out, err)
    val stopHook = StopHook(session, Runtime.getRuntime()::halt)
    Runtime.getRuntime().addShutdownHook(Thread(stopHook::stop, "embed-stop"))
    var exitCode = exitFailure
    try {
        exitCode = startAndRun(env, session, out, err)
    } finally {
        try {
            session.close()
        } catch (e: RuntimeException) {
            err.println("could not close the embedded device: ${describeFailure(e)}")
            exitCode = exitFailure
        } catch (e: LinkageError) {
            err.println("could not close the embedded device: ${describeFailure(e)}")
            exitCode = exitFailure
        }
        stopHook.closed(exitCode)
    }
    return exitCode
}

/**
 * Loads the settings, obtains the client JWT, starts the device and shows the status until a stop.
 * A stop requested before the device starts ends here with 0.
 */
private fun startAndRun(env: Map<String, String>, session: EmbedSession, out: PrintStream, err: PrintStream): Int {
    val settings: EmbedSettings
    val credential: ClientCredential
    try {
        settings = loadSettings(env)
        credential = obtainClientJwt(settings, jdkTransport())
    } catch (e: ConfigException) {
        err.println(e.message)
        return exitConfig
    } catch (e: TokenFetchException) {
        if (session.stopRequested) {
            return exitStopped
        }
        err.println(e.message)
        return e.exitCode
    }
    if (session.stopRequested) {
        return exitStopped
    }
    try {
        EmbedSession.configureSdkLogs(settings.stateDir.resolve(logDirName))
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
        session.start(settings, credential)
    } catch (e: Exception) {
        err.println("could not start the embedded device: ${describeFailure(e)}")
        return exitFailure
    } catch (e: LinkageError) {
        err.println("could not start the embedded device: ${describeFailure(e)}")
        return exitFailure
    }
    out.println(startLine(credential.clientId, settings.instanceId))
    return try {
        session.run()
    } catch (e: RuntimeException) {
        err.println("the embedded device failed: ${describeFailure(e)}")
        exitFailure
    } catch (e: LinkageError) {
        err.println("the embedded device failed: ${describeFailure(e)}")
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
 * signal it stops the device (or keeps it from starting), waits for main to close it and ends the
 * process with 0, where the JVM would exit with the signal's status (130 or 143): supervisors read 0
 * as a requested stop. When main has closed the session first, it ends the process with main's exit
 * code, such as 78 after the server rejected the credential.
 */
class StopHook(private val session: EmbedSession, private val halt: (Int) -> Unit) {
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
