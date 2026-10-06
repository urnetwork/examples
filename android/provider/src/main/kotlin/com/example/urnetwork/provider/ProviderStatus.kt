// The provider status that every provider example shows, with the exact text rules of
// PROVIDER_CONTRACT.md ("Status"): providing state, clients served, data provided and the payout
// wallet, read only. These functions take plain values, so the unit tests check them on the JVM
// without the native sdk runtime. The sdk values they compare with are compile-time constants of
// the gomobile Sdk class, which Kotlin copies into this code, so reading them loads nothing.
package com.example.urnetwork.provider

import com.bringyour.sdk.Sdk
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Shown next to the Start control, and in every example's README. The app that integrates a
 * provider owns the consent screen; this example starts without asking.
 */
const val consentDisclaimer = """Consent disclaimer: an app that integrates a URnetwork provider must collect the user's consent before it provides.
Providing shares the user's internet connection: other URnetwork users' traffic exits through the user's device and IP address.
This example starts providing without asking, because the consent screen belongs to your app."""

const val providerStateStopped = "stopped"

// the platform disconnected this client for its network's client limit, and the sdk holds off
// reconnecting until the retry time
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

// The consent_scope of a network's hotkey delegation entry in GET /sn/wallet. Hotkey delegations
// come with a later server and sdk change, which adds the sdk's own constant; the app only labels
// the entry.
const val snWalletConsentScopeHotkey = "hotkey"

// Distinct clients are counted up to this many; beyond it the count is a lower bound, shown with a
// trailing "+".
const val clientsServedLimit = 100 * 1000

private const val zeroIdString = "00000000-0000-0000-0000-000000000000"

// the retry time of the client limit status, as 24-hour UTC time
private val retryTimeFormat = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)

/** One status snapshot. */
data class ProviderStatus(
    val state: String,
    // the end of the client limit hold in unix milliseconds, shown with the client limit state;
    // 0 when unknown
    val clientLimitRetryTime: Long = 0,
    val clientsServed: Int = 0,
    val clientsServedAtLimit: Boolean = false,
    val dataProvidedByteCount: Long = 0,
    // a coldkey ss58 address, or payoutWalletChecking, payoutWalletUnavailable, payoutWalletNotSet
    val payoutWallet: String = payoutWalletChecking,
    // "" unless payoutWallet is an address
    val payoutWalletScope: String = "",
) {
    /** The status field, for example "providing" or "client limit, retry at 19:05 UTC". */
    fun statusText(): String = providerStatusText(state, clientLimitRetryTime)

    /** The clients served field: the count, with a trailing "+" once it stopped at the limit. */
    fun clientsServedText(): String = if (clientsServedAtLimit) "$clientsServed+" else "$clientsServed"

    /** The data provided field, in binary units. */
    fun dataProvidedText(): String = formatByteCount(dataProvidedByteCount)

    /** The payout wallet field: the address with its scope, or the state of the wallet read. */
    fun payoutWalletText(): String =
        if (payoutWalletScope != "") "$payoutWallet ($payoutWalletScope)" else payoutWallet

    /**
     * The console examples' status line, for example
     * "status: providing | clients served: 3 | data provided: 12.4 MiB | payout wallet: 5Grw... (network)".
     * The notification shows it.
     */
    fun line(): String =
        "status: ${statusText()} | clients served: ${clientsServedText()} | data provided: ${dataProvidedText()} | payout wallet: ${payoutWalletText()}"

    /**
     * The fields that change rarely. A change updates the notification at once; the data counter
     * alone only updates it once a minute. The status text carries the client limit retry time, so
     * a new retry time updates it too.
     */
    fun key(): String =
        "${statusText()}|$clientsServed|$clientsServedAtLimit|$payoutWallet|$payoutWalletScope"
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
    val retryMinute = (clientLimitRetryTime + minuteMillis - 1) / minuteMillis
    val retryTime = Instant.ofEpochSecond(retryMinute * 60).atOffset(ZoneOffset.UTC)
    return "$providerStateClientLimit, retry at ${retryTimeFormat.format(retryTime)} UTC"
}

/**
 * Binary units with one decimal: "0 B", "1023 B", "1.0 KiB", "12.4 MiB". A value that rounds to
 * 1024.0 moves to the next unit. The decimal is the exact value rounded half to even, as the Go
 * reference formats it.
 */
fun formatByteCount(byteCount: Long): String {
    if (byteCount < 1024) {
        return "$byteCount B"
    }
    val units = listOf("KiB", "MiB", "GiB", "TiB", "PiB", "EiB")
    var value = byteCount.toDouble() / 1024
    var unitIndex = 0
    while (unitIndex < units.size - 1 && 1024 <= Math.round(value * 10) / 10.0) {
        value /= 1024
        unitIndex += 1
    }
    val text = BigDecimal(value).setScale(1, RoundingMode.HALF_EVEN).toPlainString()
    return "$text ${units[unitIndex]}"
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
    provideMode != Sdk.ProvideModePublic -> providerStateStopped
    clientLimitStatus == Sdk.ClientLimitStatusExceeded -> providerStateClientLimit
    providePaused -> providerStatePaused
    provideEnabled && providerConnected -> providerStateProviding
    else -> providerStateStarting
}

/**
 * Which owner the effective payout wallet belongs to. The consent scope comes first: a hotkey
 * delegation is network-level, with no client id, but is not the network's wallet. Otherwise by the
 * wallet's client id: this provider's own mapping, the network's wallet, or another provider of
 * the network.
 */
fun payoutWalletScope(walletConsentScope: String, walletClientId: String, clientId: String): String = when {
    walletConsentScope == snWalletConsentScopeHotkey -> payoutWalletScopeHotkey
    walletClientId == "" -> payoutWalletScopeNetwork
    walletClientId == clientId -> payoutWalletScopeProvider
    else -> payoutWalletScopeAnotherProvider
}

/**
 * The peer of one provider contract, by direction as the sdk's contract screens resolve it: the
 * source of a receive (ingress) contract, the destination of a send (egress) contract. A path
 * without that client id is keyed by its stream id, then by the contract id. Ids are the sdk's id
 * strings, null when the contract has no path or no such id; "" means no peer.
 */
fun contractPeerKey(
    contractId: String?,
    sourceId: String?,
    destinationId: String?,
    streamId: String?,
    receive: Boolean,
): String {
    val present = { id: String? -> id != null && id != "" && id != zeroIdString }
    val peerId = if (receive) sourceId else destinationId
    return when {
        present(peerId) -> peerId.orEmpty()
        present(streamId) -> "stream:$streamId"
        present(contractId) -> "contract:$contractId"
        else -> ""
    }
}

/** A clients-served readout: the distinct count, and whether the count stopped at the limit. */
data class ClientsServedCount(val count: Int, val atLimit: Boolean)

/**
 * The distinct clients that held a contract with this provider since providing started. Safe for
 * concurrent use: the sdk delivers contract details on its own threads.
 */
class ClientsServed(private val limit: Int) {
    private val stateLock = Any()

    // set of contractPeerKey values
    private val peerKeys = HashSet<String>()
    private var atLimit = false

    /** Counts the peer of one provider contract; "" (no peer) is not counted. */
    fun add(peerKey: String) {
        if (peerKey == "") {
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

    /** The distinct count, and whether the count stopped at the limit. */
    fun count(): ClientsServedCount = synchronized(stateLock) {
        ClientsServedCount(count = peerKeys.size, atLimit = atLimit)
    }
}
