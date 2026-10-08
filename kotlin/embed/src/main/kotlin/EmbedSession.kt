// The embedded device over the SDK's C ABI, through the desktop JVM binding
// io.ur.sdk (the JNA interface Raw and the Sdk handle wrappers, the same jar
// as Java): the network space manager, the local device with the
// installation's client JWT, and the listeners that feed its status
// (EMBED_CONTRACT.md, "App lifecycle"). The device routes only the traffic the
// app sends through it; it does not provide.
//
// SDK callbacks run on SDK threads; they only copy what they carry onto the
// event queue that the status loop drains on the app's own thread, which makes
// every other SDK call. Cap reads run on one background thread so the status
// keeps its pace.
import com.sun.jna.Callback
import com.sun.jna.ptr.PointerByReference
import io.ur.sdk.Raw
import io.ur.sdk.Sdk
import java.io.IOException
import java.io.PrintStream
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

// how often the status is read, and the longest gap between status lines
private val statusPollInterval = 1.seconds
private val statusRepeatInterval = 60.seconds

// how often the caps are read, and how soon after a contract status change
private val capReadInterval = 5.minutes
private val capReadAfterContractChange = 1.seconds

// the device description and spec recorded for this installation's device
const val deviceDescription = "Kotlin embed example"
const val deviceSpec = "urnetwork-examples/kotlin-embed"
private const val appVersion = "1"

// the ur.network main network space, as the integration helpers create it
private const val networkSpaceKeyJson = """{"host_name":"ur.network","env_name":"main"}"""
private const val networkSpaceValuesJson = """{"migration_host_name":"bringyour.com"}"""

// the destination of the app's own traffic: the best available location
private const val bestAvailableJson = """{"connect_location_id":{"best_available":true}}"""

// callback objects stay reachable for the life of the process: jna holds them
// weakly, and the sdk can call back after the device closes
private val callbackRoots: MutableList<Callback> = Collections.synchronizedList(ArrayList())

/** Work that the sdk callbacks hand to the status loop. */
private sealed interface SessionEvent {
    /** A refreshed client credential to save. */
    data class JwtRefreshed(val clientJwt: String?) : SessionEvent

    /** Ends the status loop with exit code 0. */
    data object Stop : SessionEvent

    /** Ends the status loop with exit code 78: the server rejected the credential. */
    data object AuthLogout : SessionEvent

    /** The contract status changed: read the caps soon. */
    data object ContractStatusChanged : SessionEvent
}

/**
 * One run of the embedded device. The constructor makes no sdk call; start creates the device; run
 * shows the status until a stop; close closes the device. requestStop is safe from any thread, at
 * any time; the other methods belong to the thread that created the session.
 */
class EmbedSession(private val out: PrintStream, private val err: PrintStream) {
    private val events = LinkedBlockingQueue<SessionEvent>()
    private val jwtRefreshCallback = Raw.urnet_jwt_refresh_cb { _, clientJwt ->
        events.add(SessionEvent.JwtRefreshed(clientJwt))
    }
    private val authLogoutCallback = Raw.urnet_auth_logout_cb { _ ->
        events.add(SessionEvent.AuthLogout)
    }
    private val contractStatusCallback = Raw.urnet_contract_status_change_cb { _, _ ->
        events.add(SessionEvent.ContractStatusChanged)
    }
    private val subs = ArrayList<Long>()

    // one daemon thread for the cap reads, so a slow read never delays the status
    private val capReader = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "embed-cap-read").apply { isDaemon = true }
    }
    private var started = false
    private lateinit var settings: EmbedSettings
    private lateinit var http: HttpTransport
    private var manager: Sdk.Handle? = null
    private var space: Sdk.Handle? = null
    private var api: Sdk.Handle? = null
    private var device: Sdk.Device? = null

    // owned by the status loop's thread: the client JWT that cap reads present,
    // the cap readings so far, and the cap read in flight
    private var clientJwt = ""
    private val capReadings = CapReadings()
    private var capRead: Future<DataCap?>? = null
    private var nextCapReadTime = 0L

    /** Whether requestStop was called; set from any thread. */
    @Volatile
    var stopRequested = false
        private set

    init {
        callbackRoots.addAll(listOf(jwtRefreshCallback, authLogoutCallback, contractStatusCallback))
    }

    companion object {
        /** The SDK version of the native runtime, which this call loads. */
        fun sdkVersion(): String = Sdk.version()

        /**
         * Keeps the SDK's log files in logDir, which the SDK bounds, instead of the system temp
         * directory. The SDK also copies its log lines to stderr.
         */
        fun configureSdkLogs(logDir: Path) {
            createPrivateDirectory(logDir)
            val error = PointerByReference()
            val set = Sdk.raw.urnet_set_log_dir(logDir.toString(), error) != 0.toByte()
            val message = Sdk.takeString(error.value)
            if (!set) {
                throw IOException(message ?: "the sdk did not accept the log directory")
            }
        }
    }

    /**
     * Creates the local device with the installation's client JWT, adds the listeners and sets the
     * connect location to best available. The token server's cap reading, when there is one, counts
     * as the first. A failure releases what was opened.
     */
    fun start(settings: EmbedSettings, credential: ClientCredential) {
        this.settings = settings
        http = jdkTransport()
        clientJwt = credential.clientJwt
        val now = System.nanoTime()
        val firstCap = credential.firstCap
        if (firstCap != null) {
            capReadings.record(firstCap)
            nextCapReadTime = now + capReadInterval.inWholeNanoseconds
        } else {
            nextCapReadTime = now
        }
        try {
            open()
        } catch (e: Throwable) {
            if (e !is Exception && e !is LinkageError) {
                throw e
            }
            // a LinkageError is a native runtime older than this jar, without a
            // function that it calls
            try {
                release()
            } catch (releaseError: RuntimeException) {
                e.addSuppressed(releaseError)
            } catch (releaseError: LinkageError) {
                e.addSuppressed(releaseError)
            }
            throw e
        }
        started = true
    }

    /** Opens the manager, the space and the device, adds the listeners and sets the destination. */
    private fun open() {
        val raw = Sdk.raw
        val manager = Sdk.Handle(requireHandle(raw.urnet_new_network_space_manager_no_storage(), "network space manager"))
        this.manager = manager
        val space = Sdk.Handle(
            requireHandle(
                raw.urnet_network_space_manager_update_network_space_values(manager.handle(), networkSpaceKeyJson, networkSpaceValuesJson),
                "network space",
            ),
        )
        this.space = space
        val api = Sdk.Handle(requireHandle(raw.urnet_network_space_get_api(space.handle()), "api"))
        this.api = api
        raw.urnet_api_set_by_jwt(api.handle(), clientJwt)
        val error = PointerByReference()
        // no device rpc; the provide mode stays at its default: an embed app
        // does not provide
        val deviceHandle = raw.urnet_new_device_local_with_defaults(
            space.handle(), clientJwt, deviceDescription, deviceSpec, appVersion, settings.instanceId, 0, error,
        )
        val message = Sdk.takeString(error.value)
        if (deviceHandle == 0L) {
            throw IOException(message ?: "the sdk did not create the device")
        }
        device = Sdk.Device(deviceHandle)
        subs.add(requireHandle(raw.urnet_device_add_jwt_refresh_listener(deviceHandle, jwtRefreshCallback, null), "jwt refresh listener"))
        subs.add(requireHandle(raw.urnet_device_add_auth_logout_listener(deviceHandle, authLogoutCallback, null), "auth logout listener"))
        subs.add(
            requireHandle(
                raw.urnet_device_add_contract_status_change_listener(deviceHandle, contractStatusCallback, null),
                "contract status listener",
            ),
        )
        raw.urnet_device_set_connect_location(deviceHandle, bestAvailableJson)
    }

    /** The status line from the device getters and the latest cap reading. */
    fun currentStatusLine(): String {
        val raw = Sdk.raw
        val deviceHandle = device!!.handle()
        val clientLimit = parseClientLimit(Sdk.takeString(raw.urnet_device_get_client_limit_status(deviceHandle)))
        val added = providersAdded(Sdk.takeString(raw.urnet_device_get_window_status(deviceHandle)))
        val status = embedStatus(true, false, clientLimit.status, clientLimit.retryTime, capReadings.latest, added)
        return statusLine(status, dataField(capReadings, monthly = true), dataField(capReadings, monthly = false))
    }

    /** Prints status lines until a stop is requested or the server rejects the credential, and returns the exit code. */
    fun run(): Int {
        var lastLine: String? = null
        var lastPrintTime = 0L
        while (true) {
            syncCaps()
            val line = currentStatusLine()
            val now = System.nanoTime()
            if (line != lastLine || statusRepeatInterval.inWholeNanoseconds <= now - lastPrintTime) {
                out.println(line)
                lastLine = line
                lastPrintTime = now
            }
            val exitCode = handleEvents(now + statusPollInterval.inWholeNanoseconds)
            if (exitCode != null) {
                return exitCode
            }
        }
    }

    /**
     * Applies a finished cap read, and starts the next one at start, every 5 minutes and soon after a
     * contract status change. One read at a time.
     */
    private fun syncCaps() {
        val now = System.nanoTime()
        val finished = capRead
        if (finished != null && finished.isDone) {
            val reading = try {
                finished.get()
            } catch (e: ExecutionException) {
                null
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                null
            }
            capReadings.record(reading)
            capRead = null
        }
        if (capRead == null && 0 <= now - nextCapReadTime) {
            val readJwt = clientJwt
            val readHttp = http
            val apiOrigin = settings.apiOrigin
            capRead = capReader.submit<DataCap?> { readCap(apiOrigin, readJwt, readHttp) }
            nextCapReadTime = now + capReadInterval.inWholeNanoseconds
        }
    }

    /** Ends run with exit code 0, or keeps a session that has not started from starting. Safe from any thread. */
    fun requestStop() {
        stopRequested = true
        events.add(SessionEvent.Stop)
    }

    /** Closes the subscriptions, the device and the manager, in that order. Does nothing for a session that did not start. */
    fun close() {
        capReader.shutdownNow()
        if (!started) {
            return
        }
        started = false
        release()
        // a credential refreshed after the status loop ended
        val remainingEvents = ArrayList<SessionEvent>()
        events.drainTo(remainingEvents)
        for (event in remainingEvents) {
            if (event is SessionEvent.JwtRefreshed) {
                saveRefreshedClientJwt(event.clientJwt)
            }
        }
    }

    /** Closes the subscriptions, then closes and releases the device, the space and the manager. */
    private fun release() {
        val raw = Sdk.raw
        for (sub in subs) {
            raw.urnet_sub_close(sub)
            raw.urnet_release(sub)
        }
        subs.clear()
        device?.close()
        device = null
        api?.close()
        api = null
        space?.close()
        space = null
        manager?.let {
            raw.urnet_network_space_manager_close(it.handle())
            it.close()
        }
        manager = null
    }

    /** Handles the callbacks' work until the deadline; an exit code ends the status loop. */
    private fun handleEvents(deadline: Long): Int? {
        while (true) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) {
                return null
            }
            val event = try {
                events.poll(remaining, TimeUnit.NANOSECONDS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return exitStopped
            } ?: return null
            when (event) {
                SessionEvent.Stop -> return exitStopped
                SessionEvent.AuthLogout -> {
                    err.println("the server rejected the client credential; sign in again to get a new client JWT from your backend")
                    return exitConfig
                }
                SessionEvent.ContractStatusChanged -> {
                    val soon = System.nanoTime() + capReadAfterContractChange.inWholeNanoseconds
                    if (0 < nextCapReadTime - soon) {
                        nextCapReadTime = soon
                    }
                }
                is SessionEvent.JwtRefreshed -> saveRefreshedClientJwt(event.clientJwt)
            }
        }
    }

    /** Keeps the refreshed credential, so the next start and the next cap read use a valid token. */
    private fun saveRefreshedClientJwt(refreshedJwt: String?) {
        if (refreshedJwt.isNullOrBlank()) {
            return
        }
        clientJwt = refreshedJwt.trim()
        try {
            saveClientJwt(settings.stateDir, clientJwt)
        } catch (e: ConfigException) {
            // never print the token itself
            err.println("could not save the refreshed client credential: ${e.message}")
        }
    }

    /** A handle the sdk returned; 0 means the sdk did not create the object. */
    private fun requireHandle(handle: Long, name: String): Long {
        if (handle == 0L) {
            throw IOException("the sdk did not create the $name")
        }
        return handle
    }
}
