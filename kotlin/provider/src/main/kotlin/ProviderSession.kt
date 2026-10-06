// A running provider over the SDK's C ABI, through the desktop JVM binding
// io.ur.sdk (the JNA interface Raw and the Sdk handle wrappers, the same jar
// as Java): the network space manager, the provider device with the
// installation's identity, and the listeners that feed its status. SDK
// callbacks run on SDK threads; they only copy what they carry, into the
// clients-served count or onto the event queue that the status loop drains on
// the app's own thread, which makes every other SDK call.
import com.sun.jna.Callback
import com.sun.jna.Memory
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import io.ur.sdk.Raw
import io.ur.sdk.Sdk
import java.io.IOException
import java.io.PrintStream
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

// how often the status is read, and the longest gap between status lines
private val statusPollInterval = 1.seconds
private val statusRepeatInterval = 60.seconds

// how often the payout wallet is read again. The wallet is fixed; a reread
// shows a mapping that the backend completes while the provider runs.
private val walletSyncInterval = 10.minutes

// the device description and spec recorded for this installation's device
const val deviceDescription = "Kotlin provider example"
const val deviceSpec = "urnetwork-examples/kotlin-provider"
private const val appVersion = "1"

// the provider extender role's two device settings (PROVIDER_CONTRACT.md,
// "Extender role"), passed explicitly and both on. provideExtenderEnabled is
// the embedder's hard switch: false means the role never runs.
// defaultProvideExtender is the setting the device uses until the user sets
// one: false turns the default off.
private const val provideExtenderEnabled = true
private const val defaultProvideExtender = true

// the ur.network main network space, as the integration helpers create it
private const val networkSpaceKeyJson = """{"host_name":"ur.network","env_name":"main"}"""
private const val networkSpaceValuesJson = """{"migration_host_name":"bringyour.com"}"""

// callback objects stay reachable for the life of the process: jna holds them
// weakly, and the sdk can deliver a wallet result after the device closes
private val callbackRoots: MutableList<Callback> = Collections.synchronizedList(ArrayList())

/** Work that the sdk callbacks hand to the status loop. */
private sealed interface SessionEvent {
    /** A refreshed client credential to save. */
    data class JwtRefreshed(val clientJwt: String?) : SessionEvent

    /** The two values of one wallet read callback. */
    data class WalletRead(val resultJson: String?, val error: String?) : SessionEvent

    /** Ends the status loop with exit code 0. */
    data object Stop : SessionEvent

    /** Ends the status loop with exit code 78: the server rejected the credential. */
    data object AuthLogout : SessionEvent
}

/**
 * One run of the provider. The constructor makes no sdk call; start creates the device and starts
 * providing; run shows the status until a stop; close stops providing. requestStop is safe from any
 * thread, at any time; the other methods belong to the thread that created the session.
 */
class ProviderSession(
    private val config: ProviderConfig,
    private val out: PrintStream,
    private val err: PrintStream,
) {
    private val clientsServed = ClientsServed(clientsServedLimit)
    private val events = LinkedBlockingQueue<SessionEvent>()
    private val jwtRefreshCallback = Raw.urnet_jwt_refresh_cb { _, clientJwt ->
        events.add(SessionEvent.JwtRefreshed(clientJwt))
    }
    private val authLogoutCallback = Raw.urnet_auth_logout_cb { _ ->
        events.add(SessionEvent.AuthLogout)
    }
    private val ingressContractCallback = Raw.urnet_contract_details_change_cb { _, contractDetailsJson ->
        clientsServed.add(contractPeerKey(contractDetailsJson, receive = true))
    }
    private val egressContractCallback = Raw.urnet_contract_details_change_cb { _, contractDetailsJson ->
        clientsServed.add(contractPeerKey(contractDetailsJson, receive = false))
    }
    private val walletReadCallback = Raw.urnet_sn_get_wallet_cb { _, resultJson, error ->
        events.add(SessionEvent.WalletRead(resultJson, error))
    }
    private val subs = ArrayList<Long>()
    private var started = false
    private var manager: Sdk.Handle? = null
    private var space: Sdk.Handle? = null
    private var api: Sdk.Handle? = null
    private var device: Sdk.Device? = null

    // owned by the status loop's thread
    private var payoutWallet = payoutWalletBeforeRead

    /** Whether requestStop was called; set from any thread. */
    @Volatile
    var stopRequested = false
        private set

    init {
        callbackRoots.addAll(
            listOf(
                jwtRefreshCallback,
                authLogoutCallback,
                ingressContractCallback,
                egressContractCallback,
                walletReadCallback,
            ),
        )
    }

    /** The calls that need no session: the SDK version and the log directory. */
    companion object {
        /** The SDK version of the native runtime, which this call loads. */
        fun sdkVersion(): String = Sdk.version()

        /**
         * Keeps the SDK's log files in logDir, which the SDK bounds (16 MiB files, the newest four
         * kept at each start), instead of the system temp directory. The SDK also copies its log
         * lines to stderr; this binding keeps that copy.
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
     * Creates the provider device with the installation's identity and the extender role's settings
     * on, saves a new identity on first run, and starts providing publicly. The sdk declares provide
     * intent on the device's platform connections by itself while the provide mode is public. A
     * failure releases what was opened.
     */
    fun start() {
        try {
            open()
        } catch (e: Throwable) {
            // a LinkageError is a native runtime older than this jar, without a
            // function that it calls
            try {
                release()
            } catch (releaseError: Throwable) {
                e.addSuppressed(releaseError)
            }
            throw e
        }
        started = true
    }

    /** Opens the manager, the space and the device, adds the listeners and starts providing. */
    private fun open() {
        val raw = Sdk.raw
        // each handle is kept as soon as it exists, so a failure releases it
        val manager = Sdk.Handle(
            requireHandle(raw.urnet_new_network_space_manager_no_storage(), "network space manager"),
        ).also { this.manager = it }
        val space = Sdk.Handle(
            requireHandle(
                raw.urnet_network_space_manager_update_network_space_values(
                    manager.handle(),
                    networkSpaceKeyJson,
                    networkSpaceValuesJson,
                ),
                "network space",
            ),
        ).also { this.space = it }
        val api = Sdk.Handle(requireHandle(raw.urnet_network_space_get_api(space.handle()), "api"))
            .also { this.api = it }
        raw.urnet_api_set_by_jwt(api.handle(), config.clientJwt)
        val device = newDevice(space).also { this.device = it }
        if (config.identity == null) {
            saveNewIdentity(device)
        }
        val deviceHandle = device.handle()
        subs.add(
            requireHandle(
                raw.urnet_device_add_jwt_refresh_listener(deviceHandle, jwtRefreshCallback, null),
                "jwt refresh listener",
            ),
        )
        subs.add(
            requireHandle(
                raw.urnet_device_add_auth_logout_listener(deviceHandle, authLogoutCallback, null),
                "auth logout listener",
            ),
        )
        subs.add(
            requireHandle(
                raw.urnet_device_add_provider_ingress_contract_details_change_listener(
                    deviceHandle,
                    ingressContractCallback,
                    null,
                ),
                "provider ingress contract listener",
            ),
        )
        subs.add(
            requireHandle(
                raw.urnet_device_add_provider_egress_contract_details_change_listener(
                    deviceHandle,
                    egressContractCallback,
                    null,
                ),
                "provider egress contract listener",
            ),
        )
        raw.urnet_device_set_provide_mode(deviceHandle, provideModePublic)
        syncWallet()
    }

    /**
     * The device, created with the identity's key material or, on first run, with none: one call
     * for both runs. Without key material the device makes a new identity, which the app saves.
     */
    private fun newDevice(space: Sdk.Handle): Sdk.Device {
        val raw = Sdk.raw
        val keyMaterial = config.identity?.let { newKeyMaterial(it) } ?: 0L
        val error = PointerByReference()
        val deviceHandle = try {
            raw.urnet_new_device_local_with_provide_extender(
                space.handle(),
                config.clientJwt,
                deviceDescription,
                deviceSpec,
                appVersion,
                config.instanceId,
                cBool(false),
                keyMaterial,
                cBool(provideExtenderEnabled),
                cBool(defaultProvideExtender),
                error,
            )
        } finally {
            // the device keeps its own copy of the key material
            if (keyMaterial != 0L) {
                raw.urnet_release(keyMaterial)
            }
        }
        val message = Sdk.takeString(error.value)
        if (deviceHandle == 0L) {
            throw IOException(message ?: "the sdk did not create the device")
        }
        return Sdk.Device(deviceHandle)
    }

    /** Saves the identity that the device made on first run, so later starts present the same provider. */
    private fun saveNewIdentity(device: Sdk.Device) {
        val raw = Sdk.raw
        val deviceHandle = device.handle()
        val identity = ProviderIdentity(
            clientId = config.clientId,
            clientKeySeed = readBuffer { buffer, length ->
                raw.urnet_device_local_get_client_key_seed(deviceHandle, buffer, length)
            },
            provideTlsCertificatePem = readBuffer { buffer, length ->
                raw.urnet_device_local_get_provide_tls_certificate_pem(deviceHandle, buffer, length)
            },
            provideTlsPrivateKeyPem = readBuffer { buffer, length ->
                raw.urnet_device_local_get_provide_tls_private_key_pem(deviceHandle, buffer, length)
            },
            extenderKeySeed = readBuffer { buffer, length ->
                raw.urnet_device_local_get_extender_key_seed(deviceHandle, buffer, length)
            },
        )
        if (identity.clientKeySeed.size != clientKeySeedByteCount) {
            throw IOException("the device has no provider identity to save")
        }
        try {
            saveProviderIdentity(config.stateDir, identity)
        } catch (e: IOException) {
            throw IOException("save $identityFileName: ${describe(e)}", e)
        }
    }

    /** Reads the status from the device getters and the listener state. */
    fun status(): ProviderStatus {
        val raw = Sdk.raw
        val deviceHandle = checkNotNull(device).handle()
        // "client_limit_exceeded" with the hold's end in RetryTime while the
        // platform holds this client off for its network's client limit
        val clientLimit = parseClientLimit(Sdk.takeString(raw.urnet_device_get_client_limit_status(deviceHandle)))
        // urnet_device_local_get_provider_ready also waits for processed client
        // key registration, which default device settings do not enable, so the
        // connected carrier is the readiness signal here
        val state = providerState(
            provideMode = raw.urnet_device_get_provide_mode(deviceHandle),
            clientLimitStatus = clientLimit.status,
            providePaused = raw.urnet_device_get_provide_paused(deviceHandle) != 0.toByte(),
            provideEnabled = raw.urnet_device_get_provide_enabled(deviceHandle) != 0.toByte(),
            providerConnected = raw.urnet_device_local_get_provider_connected(deviceHandle) != 0.toByte(),
        )
        val clientsServedCount = clientsServed.count()
        return ProviderStatus(
            state = state,
            clientLimitRetryTime = clientLimit.retryTime,
            clientsServed = clientsServedCount.count,
            clientsServedAtLimit = clientsServedCount.atLimit,
            // bytes relayed for clients, in both directions, since the device started
            dataProvidedByteCount = dataProvidedByteCount(
                Sdk.takeString(raw.urnet_device_get_provider_packet_stats(deviceHandle)),
            ),
            payoutWallet = payoutWallet.wallet,
            payoutWalletScope = payoutWallet.scope,
        )
    }

    /**
     * Prints status lines until a stop is requested or the server rejects the credential, and
     * returns the process exit code.
     */
    fun run(): Int {
        var lastKey = ""
        var lastPrintTime = 0L
        var nextWalletSyncTime = System.nanoTime() + walletSyncInterval.inWholeNanoseconds
        while (true) {
            val status = status()
            val now = System.nanoTime()
            val key = status.key()
            if (key != lastKey || statusRepeatInterval.inWholeNanoseconds <= now - lastPrintTime) {
                out.println(status.line())
                lastKey = key
                lastPrintTime = now
            }
            if (0 <= now - nextWalletSyncTime) {
                syncWallet()
                nextWalletSyncTime = now + walletSyncInterval.inWholeNanoseconds
            }
            val exitCode = handleEvents(now + statusPollInterval.inWholeNanoseconds)
            if (exitCode != null) {
                return exitCode
            }
        }
    }

    /**
     * Ends run with exit code 0, or keeps a session that has not started from starting. Safe from any
     * thread, such as a shutdown hook.
     */
    fun requestStop() {
        stopRequested = true
        events.add(SessionEvent.Stop)
    }

    /**
     * Stops providing and releases the subscriptions, the device and the manager, in that order. Does
     * nothing for a session that did not start.
     */
    fun close() {
        if (!started) {
            return
        }
        started = false
        device?.let { Sdk.raw.urnet_device_set_provide_mode(it.handle(), provideModeNone) }
        release()
        // a credential refreshed after the status loop ended
        val remainingEvents = ArrayList<SessionEvent>()
        events.drainTo(remainingEvents)
        for (event in remainingEvents) {
            if (event is SessionEvent.JwtRefreshed) {
                saveClientJwt(event.clientJwt)
            }
        }
        out.println("status: stopped")
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
                is SessionEvent.Stop -> return exitStopped
                is SessionEvent.AuthLogout -> {
                    err.println("the server rejected the client credential; issue a new scoped client JWT from your backend")
                    return exitConfig
                }
                is SessionEvent.JwtRefreshed -> saveClientJwt(event.clientJwt)
                is SessionEvent.WalletRead -> walletRead(event.resultJson, event.error)
            }
        }
    }

    /**
     * Reads the payout wallet (GET /sn/wallet with the client credential); the callback queues the
     * result. The app only displays the wallet: the backend maps it, never the app.
     */
    private fun syncWallet() {
        Sdk.raw.urnet_device_local_sync_sn_wallet(checkNotNull(device).handle(), walletReadCallback, null)
    }

    /** Records one wallet read. A failure keeps the last known wallet. */
    private fun walletRead(resultJson: String?, error: String?) {
        var readSucceeded = walletReadSucceeded(resultJson, error)
        var effectiveWallet: SnWallet? = null
        if (readSucceeded) {
            try {
                // the sdk caches the effective wallet before the callback: this
                // client's own consent, else the network consent, else (with
                // hotkey delegations) the network's hotkey entry, else a
                // non-consent wallet
                effectiveWallet = parseSnWallet(
                    Sdk.takeString(Sdk.raw.urnet_device_local_get_sn_wallet(checkNotNull(device).handle())),
                )
            } catch (e: IllegalArgumentException) {
                readSucceeded = false
            }
        }
        payoutWallet = payoutWalletAfterRead(payoutWallet, readSucceeded, effectiveWallet, config.clientId)
    }

    /** Keeps the refreshed credential, so the next start uses a valid token. */
    private fun saveClientJwt(clientJwt: String?) {
        if (clientJwt.isNullOrBlank()) {
            return
        }
        try {
            writePrivateFile(config.stateDir.resolve(clientJwtFileName), "$clientJwt\n".toByteArray(Charsets.UTF_8))
        } catch (e: IOException) {
            // never print the token itself
            err.println("could not save the refreshed client credential: ${describe(e)}")
        }
    }
}

/** A key material handle with the identity's keys; the caller releases it. */
private fun newKeyMaterial(identity: ProviderIdentity): Long {
    val raw = Sdk.raw
    val clientKeySeed = identity.clientKeySeed
    val certificatePem = identity.provideTlsCertificatePem
    val privateKeyPem = identity.provideTlsPrivateKeyPem
    return withMemory(clientKeySeed) { clientKeySeedMemory ->
        withMemory(certificatePem) { certificatePemMemory ->
            withMemory(privateKeyPem) { privateKeyPemMemory ->
                val keyMaterial = raw.urnet_new_device_local_key_material(
                    clientKeySeedMemory,
                    clientKeySeed.size,
                    certificatePemMemory,
                    certificatePem.size,
                    privateKeyPemMemory,
                    privateKeyPem.size,
                )
                check(keyMaterial != 0L) { "the sdk did not create the key material" }
                val extenderKeySeed = identity.extenderKeySeed
                if (extenderKeySeed.isNotEmpty()) {
                    withMemory(extenderKeySeed) { extenderKeySeedMemory ->
                        raw.urnet_device_local_key_material_set_extender_key_seed(
                            keyMaterial,
                            extenderKeySeedMemory,
                            extenderKeySeed.size,
                        )
                    }
                }
                keyMaterial
            }
        }
    }
}

/** Calls block with native memory holding a copy of bytes, or null (a NULL pointer) for none, and frees it. */
private fun <T> withMemory(bytes: ByteArray, block: (Memory?) -> T): T {
    if (bytes.isEmpty()) {
        return block(null)
    }
    return Memory(bytes.size.toLong()).use { memory ->
        memory.write(0, bytes, 0, bytes.size)
        block(memory)
    }
}

/**
 * The bytes of one of the c abi's buffer-out getters: the first call reads the size into
 * inoutLength, the second copies into a buffer of that size.
 */
private fun readBuffer(get: (Pointer?, IntByReference) -> Byte): ByteArray {
    val length = IntByReference(0)
    get(null, length)
    val byteCount = length.value
    if (byteCount <= 0) {
        return ByteArray(0)
    }
    return Memory(byteCount.toLong()).use { buffer ->
        length.value = byteCount
        check(get(buffer, length) != 0.toByte()) { "the sdk did not copy the identity" }
        buffer.getByteArray(0, byteCount)
    }
}

/** A handle the sdk returned; 0 means the sdk did not create the object. */
private fun requireHandle(handle: Long, name: String): Long {
    if (handle == 0L) {
        throw IOException("the sdk did not create the $name")
    }
    return handle
}

/** A c bool for jna. */
private fun cBool(value: Boolean): Byte = if (value) 1 else 0
