// The provider status that every provider example shows, with the exact text
// rules of PROVIDER_CONTRACT.md ("Status"): providing state, clients served,
// data provided and the payout wallet, read only. It also reads the status
// values that the SDK's C ABI returns as JSON, with the JSON elements of
// kotlinx.serialization. Nothing here calls the SDK, so the self-test checks
// it without a network, credentials or the native SDK runtime.
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Shown once at start, and in every example's README. The app that integrates a provider owns the
 * consent screen; this example starts without asking.
 */
val consentDisclaimer = """
    Consent disclaimer: an app that integrates a URnetwork provider must collect the user's consent before it provides.
    Providing shares the user's internet connection: other URnetwork users' traffic exits through the user's device and IP address.
    This example starts providing without asking, because the consent screen belongs to your app.
""".trimIndent()

const val providerStateStopped = "stopped"

// the platform disconnected this client for its network's client limit, and
// the sdk holds off reconnecting until the retry time
const val providerStateClientLimit = "client limit"
const val providerStateStarting = "starting"
const val providerStatePaused = "paused"
const val providerStateProviding = "providing"

// the payout wallet before the first wallet read finishes
const val payoutWalletChecking = "checking"

// the payout wallet when the first wallet read failed
const val payoutWalletUnavailable = "unavailable"

// the payout wallet when no wallet is mapped
const val payoutWalletNotSet = "not set"

const val payoutWalletScopeHotkey = "hotkey"
const val payoutWalletScopeProvider = "this provider"
const val payoutWalletScopeNetwork = "network"
const val payoutWalletScopeAnotherProvider = "another provider"

// the provide modes of the c abi (URNET_PROVIDE_MODE_*)
const val provideModeNone = 0L
const val provideModeNetwork = 1L
const val provideModePublic = 3L

// the client limit status values of the c abi (URNET_CLIENT_LIMIT_STATUS_*)
const val clientLimitStatusNone = ""
const val clientLimitStatusExceeded = "client_limit_exceeded"

// the consent_scope values of GET /sn/wallet. Hotkey delegations come with a
// later server and sdk change; the app only labels the entry.
const val snWalletConsentScopeHotkey = "hotkey"
const val snWalletConsentScopeNetwork = "network"
const val snWalletConsentScopeProvider = "provider"

// distinct clients are counted up to this many; beyond it the count is a
// lower bound, shown with a trailing "+"
const val clientsServedLimit = 100 * 1000

const val zeroIdString = "00000000-0000-0000-0000-000000000000"

private val byteUnits = listOf("KiB", "MiB", "GiB", "TiB", "PiB", "EiB")

/** One status snapshot. */
data class ProviderStatus(
    val state: String,
    // the end of the client limit hold in unix milliseconds, shown with the
    // client limit state; 0 when there is none
    val clientLimitRetryTime: Long = 0,
    val clientsServed: Int = 0,
    val clientsServedAtLimit: Boolean = false,
    val dataProvidedByteCount: Long = 0,
    // a coldkey ss58 address, or payoutWalletChecking, payoutWalletUnavailable,
    // payoutWalletNotSet
    val payoutWallet: String,
    // "" unless payoutWallet is an address
    val payoutWalletScope: String = "",
) {
    /**
     * The status line, for example "status: providing | clients served: 3 | data provided: 12.4
     * MiB | payout wallet: 5Grw... (network)".
     */
    fun line(): String {
        val clientsServedText = if (clientsServedAtLimit) "$clientsServed+" else "$clientsServed"
        val payoutWalletText =
            if (payoutWalletScope.isEmpty()) payoutWallet else "$payoutWallet ($payoutWalletScope)"
        return listOf(
            "status: ${providerStatusText(state, clientLimitRetryTime)}",
            "clients served: $clientsServedText",
            "data provided: ${formatByteCount(dataProvidedByteCount)}",
            "payout wallet: $payoutWalletText",
        ).joinToString(" | ")
    }

    /**
     * The fields that change rarely. A change prints a status line at once; the data counter alone
     * only prints on the periodic line. The status text carries the client limit retry time, so a
     * new retry time prints too.
     */
    fun key(): String =
        listOf(
            providerStatusText(state, clientLimitRetryTime),
            clientsServed,
            clientsServedAtLimit,
            payoutWallet,
            payoutWalletScope,
        ).joinToString("|")
}

/**
 * The status field text: the state, and for the client limit state the time the sdk retries, for
 * example "client limit, retry at 19:05 UTC". The retry time (unix milliseconds) is rounded up to
 * the next whole minute in UTC, so the shown time is never before the real retry; a retry time of
 * 0 shows "client limit".
 */
fun providerStatusText(state: String, clientLimitRetryTime: Long): String {
    if (state != providerStateClientLimit || clientLimitRetryTime <= 0) {
        return state
    }
    val minuteMillis = 60L * 1000
    // whole minutes since the epoch, rounded up without overflow
    val retryMinute = (clientLimitRetryTime - 1) / minuteMillis + 1
    val minuteOfDay = retryMinute % (24 * 60)
    return "%s, retry at %02d:%02d UTC".format(
        Locale.ROOT,
        providerStateClientLimit,
        minuteOfDay / 60,
        minuteOfDay % 60,
    )
}

/**
 * Binary units with one decimal: "0 B", "1023 B", "1.0 KiB", "12.4 MiB". A value that rounds to
 * 1024.0 moves to the next unit. The tenth is the nearest one with ties to even, from the exact
 * binary value, as Go's %.1f rounds it: 1280 bytes (1.25 KiB) shows "1.2 KiB" and 1792 bytes "1.8
 * KiB". String.format rounds ties up, so BigDecimal does the rounding. The unit check follows the
 * Go reference: double division, rounded half up (Math.round; kotlin.math.round rounds half to
 * even).
 */
fun formatByteCount(byteCount: Long): String {
    if (byteCount < 1024) {
        return "$byteCount B"
    }
    var value = byteCount.toDouble() / 1024
    var unitIndex = 0
    while (unitIndex < byteUnits.size - 1 && 1024 <= Math.round(value * 10) / 10.0) {
        value /= 1024
        unitIndex += 1
    }
    val decimal = BigDecimal(value).setScale(1, RoundingMode.HALF_EVEN).toPlainString()
    return "$decimal ${byteUnits[unitIndex]}"
}

/**
 * The providing state from the device getters, in this order: stopped unless the provide mode is
 * public; client limit while the sdk holds this client off for its network's client limit; paused
 * while paused; providing once the provider is enabled and its platform carrier is connected;
 * starting otherwise.
 */
fun providerState(
    provideMode: Long,
    clientLimitStatus: String,
    providePaused: Boolean,
    provideEnabled: Boolean,
    providerConnected: Boolean,
): String = when {
    provideMode != provideModePublic -> providerStateStopped
    clientLimitStatus == clientLimitStatusExceeded -> providerStateClientLimit
    providePaused -> providerStatePaused
    provideEnabled && providerConnected -> providerStateProviding
    else -> providerStateStarting
}

/**
 * Which owner the effective payout wallet belongs to. The consent scope comes first: a hotkey
 * delegation is network-level, with no client id, but is not the network's wallet. Otherwise by the
 * wallet's client id: this provider's own mapping, the network's wallet, or another provider of the
 * network.
 */
fun payoutWalletScope(walletConsentScope: String, walletClientId: String, clientId: String): String =
    when {
        walletConsentScope == snWalletConsentScopeHotkey -> payoutWalletScopeHotkey
        walletClientId.isEmpty() -> payoutWalletScopeNetwork
        walletClientId == clientId -> payoutWalletScopeProvider
        else -> payoutWalletScopeAnotherProvider
    }

/**
 * The peer of one provider contract, from the c abi's contract details JSON, by direction as the
 * SDK's contract screens resolve it: the source of a receive (ingress) contract, the destination of
 * a send (egress) contract. A path without that client id is keyed by its stream id, then by the
 * contract id. "" when the details name none of them or do not parse.
 */
fun contractPeerKey(contractDetailsJson: String?, receive: Boolean): String {
    // the canonical form of an id; null for a missing, malformed or all-zero id
    val presentId = { id: String? ->
        id?.let { parseId(it) }?.takeIf { it != zeroIdString }
    }
    try {
        val details = parseNullableJsonObject(contractDetailsJson) ?: return ""
        val path = details.objectMember("ContractTransferPath")
        if (path != null) {
            val peerId = presentId(path.stringMember(if (receive) "SourceId" else "DestinationId"))
            if (peerId != null) {
                return peerId
            }
            val streamId = presentId(path.stringMember("StreamId"))
            if (streamId != null) {
                return "stream:$streamId"
            }
        }
        val contractId = presentId(details.stringMember("ContractId"))
        if (contractId != null) {
            return "contract:$contractId"
        }
        return ""
    } catch (e: IllegalArgumentException) {
        // the sdk writes these details; a value that does not parse counts no peer
        return ""
    }
}

/**
 * One client limit readout: status is clientLimitStatusNone or clientLimitStatusExceeded, retryTime
 * the end of the hold in unix milliseconds, 0 when there is none.
 */
data class ClientLimit(val status: String, val retryTime: Long)

/** The client limit status when there is none, or when it cannot be read. */
val noClientLimit = ClientLimit(status = clientLimitStatusNone, retryTime = 0)

/**
 * The c abi's client limit status JSON, for example {"Status": "client_limit_exceeded",
 * "RetryTime": 1791313500000}. NULL (the call could not run) and a value that does not parse read
 * as no limit.
 */
fun parseClientLimit(clientLimitStatusJson: String?): ClientLimit =
    try {
        val members = parseNullableJsonObject(clientLimitStatusJson)
        if (members == null) {
            noClientLimit
        } else {
            ClientLimit(
                status = members.stringMember("Status") ?: clientLimitStatusNone,
                retryTime = members.longMember("RetryTime"),
            )
        }
    } catch (e: IllegalArgumentException) {
        noClientLimit
    }

/**
 * RemoteEgressByteCount + RemoteIngressByteCount of the c abi's provider packet stats JSON: bytes
 * relayed for clients in both directions since the device started; 0 when the stats are null.
 */
fun dataProvidedByteCount(packetStatsJson: String?): Long =
    try {
        val members = parseNullableJsonObject(packetStatsJson)
        if (members == null) {
            0
        } else {
            members.longMember("RemoteEgressByteCount") + members.longMember("RemoteIngressByteCount")
        }
    } catch (e: IllegalArgumentException) {
        0
    }

/** The fields of an SnWallet that the app shows; "" for an absent field. */
data class SnWallet(val coldkeySs58: String, val clientId: String, val consentScope: String)

/**
 * The c abi's SnWallet JSON (urnet_device_local_get_sn_wallet); null for NULL or null. Throws
 * IllegalArgumentException for a value that does not parse.
 */
fun parseSnWallet(snWalletJson: String?): SnWallet? {
    val members = parseNullableJsonObject(snWalletJson) ?: return null
    return SnWallet(
        coldkeySs58 = members.stringMember("coldkey_ss58") ?: "",
        clientId = members.stringMember("client_id") ?: "",
        consentScope = members.stringMember("consent_scope") ?: "",
    )
}

/**
 * Whether a wallet read succeeded, from the two values of the c abi's wallet callback: no error,
 * and a result (an SnGetWalletResult JSON) without an error of its own.
 */
fun walletReadSucceeded(resultJson: String?, error: String?): Boolean {
    if (error != null) {
        return false
    }
    return try {
        val result = parseNullableJsonObject(resultJson)
        result != null && result.objectMember("error") == null
    } catch (e: IllegalArgumentException) {
        false
    }
}

/** The payout wallet field: an address with its scope, or one of the payout wallet texts with scope "". */
data class PayoutWallet(val wallet: String, val scope: String)

/** The payout wallet before the first wallet read finishes. */
val payoutWalletBeforeRead = PayoutWallet(wallet = payoutWalletChecking, scope = "")

/**
 * The payout wallet after one wallet read: a failed first read shows unavailable and a later
 * failure keeps the last value; a read without a mapped wallet shows not set; otherwise the
 * effective wallet's coldkey with its scope for this client.
 */
fun payoutWalletAfterRead(
    current: PayoutWallet,
    readSucceeded: Boolean,
    effectiveWallet: SnWallet?,
    clientId: String,
): PayoutWallet = when {
    !readSucceeded && current.wallet == payoutWalletChecking ->
        PayoutWallet(wallet = payoutWalletUnavailable, scope = "")
    !readSucceeded -> current
    effectiveWallet == null || effectiveWallet.coldkeySs58.isEmpty() ->
        PayoutWallet(wallet = payoutWalletNotSet, scope = "")
    else -> PayoutWallet(
        wallet = effectiveWallet.coldkeySs58,
        scope = payoutWalletScope(effectiveWallet.consentScope, effectiveWallet.clientId, clientId),
    )
}

/**
 * The distinct clients that held a contract with this provider since the app started. Safe for
 * concurrent use: the sdk delivers contract details on its own threads.
 */
class ClientsServed(private val limit: Int) {
    /** The distinct count, and whether the count stopped at the limit. */
    data class Count(val count: Int, val atLimit: Boolean)

    // guards peerKeys and atLimit
    private val stateLock = Any()

    // contractPeerKey values
    private val peerKeys = HashSet<String>()
    private var atLimit = false

    /** Counts the peer of one provider contract; "" counts nothing. */
    fun add(peerKey: String) {
        if (peerKey.isEmpty()) {
            return
        }
        synchronized(stateLock) {
            if (peerKey in peerKeys) {
                return
            }
            if (limit <= peerKeys.size) {
                atLimit = true
                return
            }
            peerKeys.add(peerKey)
        }
    }

    /** The current count. */
    fun count(): Count = synchronized(stateLock) { Count(count = peerKeys.size, atLimit = atLimit) }
}

/**
 * The JSON object of a c abi value: null for NULL and for JSON null. Throws
 * IllegalArgumentException (kotlinx.serialization's SerializationException is one) for text that is
 * not JSON or another JSON value.
 */
fun parseNullableJsonObject(text: String?): JsonObject? {
    if (text == null) {
        return null
    }
    return when (val element = Json.parseToJsonElement(text)) {
        is JsonNull -> null
        is JsonObject -> element
        else -> throw IllegalArgumentException("not a JSON object")
    }
}

/** A string member; null when it is absent or null. */
fun JsonObject.stringMember(name: String): String? =
    when (val value = this[name]) {
        null, is JsonNull -> null
        is JsonPrimitive -> if (value.isString) value.content else throw IllegalArgumentException("$name is not a string")
        else -> throw IllegalArgumentException("$name is not a string")
    }

/** An integer member that fits a Long; 0 when it is absent or null. */
fun JsonObject.longMember(name: String): Long =
    when (val value = this[name]) {
        null, is JsonNull -> 0
        is JsonPrimitive ->
            if (value.isString) throw IllegalArgumentException("$name is not an integer")
            else value.longOrNull ?: throw IllegalArgumentException("$name is not an integer")
        else -> throw IllegalArgumentException("$name is not an integer")
    }

/** An object member; null when it is absent or null. */
fun JsonObject.objectMember(name: String): JsonObject? =
    when (val value = this[name]) {
        null, is JsonNull -> null
        is JsonObject -> value
        else -> throw IllegalArgumentException("$name is not an object")
    }
