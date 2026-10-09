// The application-scoped holder of the embedded device (EMBED_CONTRACT.md, "Android"). The user's
// tap on Start starts it, and Stop or a sign-out closes it; in-app traffic in the foreground needs
// no foreground service, so it lives with the app's process.
//
// Start and stop decisions run on the main thread. The device work (loading the state, fetching the
// client JWT, opening and closing the session and the status read every second) runs on one worker
// thread, in order, so a stop always closes the session of the start before it. The cap reads (at
// start, every 5 minutes and soon after a contract status change) are HTTP calls on their own thread,
// so a slow read never delays the status; a run id drops a read that finishes after its run ended.
// The activity reads the latest snapshot from the main thread.
package com.example.urnetwork.embed

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.bringyour.sdk.Sdk
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

private const val logTag = "EmbedController"

// how often the device status is read
private const val statusPollIntervalMillis = 1000L

// how often the caps are read again
private const val capReadIntervalMillis = 5L * 60 * 1000

// A contract status change reads the caps after this delay, within the contract's 5 seconds, so a
// burst of changes reads once.
private const val capReadAfterChangeMillis = 2000L

// the message shown when the server no longer accepts the client credential
private const val credentialRejected =
    "the server rejected the client credential; sign in again so your backend issues a new client JWT"

// the message shown when there is neither a token server nor a client.jwt
private const val noCredential =
    "no token server is configured and the state has no $clientJwtFileName; sign in first (debug builds import $tokenServerFileName over adb)"

/** What the screen shows now. */
data class EmbedSnapshot(
    val fields: EmbedStatusFields,
    // "" until the client is known
    val clientId: String,
    // "" until the state is loaded
    val instanceId: String,
    // the last problem; "" when none
    val problem: String,
    // whether the user started the device and has not stopped it
    val running: Boolean,
)

/** The client JWT a run starts with, and the first cap reading when the token server had one. */
private class RunCredential(val clientJwt: String, val clientId: String, val firstCap: DataCap?)

/** Starts, reads and stops the embedded device for the app. One per process: see EmbedApplication. */
class EmbedController(context: Context) {
    private val stateDir = File(context.applicationContext.noBackupFilesDir, stateDirName)

    // Debuggable builds also accept the emulator's 10.0.2.2 for a loopback token server; release
    // builds allow only HTTPS in their network security configuration.
    private val allowEmulatorHost =
        (context.applicationContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private val capWorker: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private val capReadings = DataCapReadings()
    private val capReadAfterChangePending = AtomicBoolean(false)

    // main thread only
    private var runGeneration = 0

    // worker thread only
    private var session: EmbedSession? = null
    private var statusTask: ScheduledFuture<*>? = null
    private var capTask: ScheduledFuture<*>? = null
    private var lastStatusLine = ""

    // Written by the worker at each open and close; a cap read applies its result only while the
    // run id it started under is current.
    @Volatile
    private var runId = 0

    // the current client JWT: set at start, then by the sdk's refresh listener. Never log it.
    @Volatile
    private var clientJwt = ""

    @Volatile
    private var started = false

    @Volatile
    private var signedOut = false

    @Volatile
    private var problem = ""

    @Volatile
    private var clientId = ""

    @Volatile
    private var instanceId = ""

    @Volatile
    private var deviceReading = DeviceReading()

    /** The status fields, the identities and the last problem, for the screen. */
    fun snapshot(): EmbedSnapshot = EmbedSnapshot(
        fields = currentFields(),
        clientId = clientId,
        instanceId = instanceId,
        problem = problem,
        running = started,
    )

    /** Starts the device; main thread, from the user's tap. */
    fun start() {
        if (started) {
            return
        }
        started = true
        signedOut = false
        problem = ""
        runGeneration += 1
        val generation = runGeneration
        onWorker { open(generation) }
    }

    /** Stops the device; main thread, from the user's tap. */
    fun stop() {
        started = false
        onWorker { close() }
    }

    /** The status fields from the latest readings. */
    private fun currentFields(): EmbedStatusFields {
        val reading = deviceReading
        return embedStatusFields(
            EmbedStatusInputs(
                started = started,
                signedOut = signedOut,
                clientLimitStatus = reading.clientLimitStatus,
                clientLimitRetryTime = reading.clientLimitRetryTime,
                capReading = capReadings.current,
                providersAdded = reading.providersAdded,
            ),
        )
    }

    /**
     * Ends the run of generation with a problem, signed out or stopped, unless that run already
     * ended; any thread.
     */
    private fun endRun(generation: Int, signOut: Boolean, problemText: String) {
        mainHandler.post {
            if (started && generation == runGeneration) {
                started = false
                signedOut = signOut
                problem = problemText
                onWorker { close() }
            }
        }
    }

    /** Runs task on the worker thread. */
    private fun onWorker(task: () -> Unit) {
        try {
            worker.execute(task)
        } catch (e: RejectedExecutionException) {
            // the worker ended with the process
        }
    }

    /**
     * Loads the installation state and obtains the client JWT: from the token server when
     * token-server.json is configured, otherwise the client.jwt in the state. Throws
     * EmbedConfigException or TokenFetchException; worker thread.
     */
    private fun loadCredential(): RunCredential {
        ensureStateDir(stateDir)
        val instanceId = loadOrCreateInstanceId(stateDir)
        this.instanceId = instanceId
        val tokenServer = loadTokenServerConfig(stateDir, allowEmulatorHost)
        if (tokenServer != null) {
            val token = fetchClientJwt(stateDir, tokenServer, instanceId)
            return RunCredential(clientJwt = token.clientJwt, clientId = token.clientId, firstCap = token.dataCap)
        }
        val stored = loadClientJwt(stateDir) ?: throw EmbedConfigException(noCredential)
        return RunCredential(clientJwt = stored.clientJwt, clientId = stored.clientId, firstCap = null)
    }

    /** Opens the run of generation; worker thread. */
    private fun open(generation: Int) {
        capReadings.reset()
        deviceReading = DeviceReading()
        val credential = try {
            loadCredential()
        } catch (e: EmbedConfigException) {
            endRun(generation, signOut = false, problemText = e.message.orEmpty())
            return
        } catch (e: TokenFetchException) {
            endRun(generation, signOut = e.failure == TokenFetchFailure.SignedOut, problemText = e.message.orEmpty())
            return
        }
        clientJwt = credential.clientJwt
        clientId = credential.clientId
        try {
            // the sdk keeps its bounded log files there instead of a directory this app cannot write
            Sdk.setLogDir(ensureLogDir(stateDir).path)
        } catch (e: Exception) {
            endRun(generation, signOut = false, problemText = "could not set the sdk log directory: ${e.message}")
            return
        }
        val session = try {
            EmbedSession.start(
                clientJwt = credential.clientJwt,
                instanceId = instanceId,
                jwtRefreshed = { refreshedJwt -> jwtRefreshed(refreshedJwt) },
                authLogout = { endRun(generation, signOut = true, problemText = credentialRejected) },
                contractStatusChanged = { readCapsSoon() },
            )
        } catch (e: Exception) {
            endRun(generation, signOut = false, problemText = "could not start: ${e.message}")
            return
        }
        this.session = session
        runId += 1
        val id = runId
        // the token server's cap object serves as the first reading
        credential.firstCap?.let { capReadings.succeeded(it) }
        statusTask = worker.scheduleWithFixedDelay({ readStatus() }, 0, statusPollIntervalMillis, TimeUnit.MILLISECONDS)
        capTask = try {
            capWorker.scheduleWithFixedDelay(
                { readCaps(id) },
                if (credential.firstCap != null) capReadIntervalMillis else 0,
                capReadIntervalMillis,
                TimeUnit.MILLISECONDS,
            )
        } catch (e: RejectedExecutionException) {
            null
        }
    }

    /** Closes the run's session and stops its reads; worker thread. */
    private fun close() {
        runId += 1
        statusTask?.cancel(false)
        statusTask = null
        capTask?.cancel(false)
        capTask = null
        session?.close()
        session = null
        deviceReading = DeviceReading()
        lastStatusLine = ""
    }

    /** Reads the device status, and logs the status line when it changes; worker thread. */
    private fun readStatus() {
        val session = session ?: return
        try {
            deviceReading = session.read()
        } catch (e: Exception) {
            // a failed read must not end the scheduled reads
            Log.e(logTag, "could not read the device status: ${e.message}")
            return
        }
        val line = currentFields().line()
        if (line != lastStatusLine) {
            // the status line carries no identifiers or tokens
            Log.i(logTag, line)
            lastStatusLine = line
        }
    }

    /** Reads this installation's caps with its client JWT; cap thread. */
    private fun readCaps(id: Int) {
        if (id != runId) {
            return
        }
        val result = readDataCapResult(defaultApiUrl, clientJwt)
        if (id != runId) {
            // the run ended while the read was in flight
            return
        }
        // a later failure keeps the last reading; the Embed-not-enabled refusal clears it
        capReadings.record(result)
    }

    /** Reads the caps again soon after a contract status change; any thread (an sdk listener). */
    private fun readCapsSoon() {
        if (!capReadAfterChangePending.compareAndSet(false, true)) {
            return
        }
        val id = runId
        try {
            capWorker.schedule(
                {
                    capReadAfterChangePending.set(false)
                    readCaps(id)
                },
                capReadAfterChangeMillis,
                TimeUnit.MILLISECONDS,
            )
        } catch (e: RejectedExecutionException) {
            capReadAfterChangePending.set(false)
        }
    }

    /** Keeps the refreshed credential, so the next start uses a valid token; an sdk thread. */
    private fun jwtRefreshed(refreshedJwt: String) {
        clientJwt = refreshedJwt
        try {
            saveClientJwt(stateDir, refreshedJwt)
        } catch (e: IOException) {
            // never log the token itself
            Log.e(logTag, "could not save the refreshed client JWT: ${e.message}")
        }
    }
}
