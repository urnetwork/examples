// The status checks of the provider self-test (PROVIDER_CONTRACT.md, "Self-test"): the disclaimer,
// the byte, status text, status line, providing state and payout wallet vectors, and the
// clients-served count. They run on the JVM without credentials, a network or the native sdk
// runtime; the sdk constants they read are compile-time constants.
package com.example.urnetwork.provider

import com.bringyour.sdk.Sdk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.security.MessageDigest

// sha-256 of the consent disclaimer (utf-8, lf line breaks, no trailing newline), published in
// PROVIDER_CONTRACT.md for every example to check
private const val consentDisclaimerSha256 = "83edee1e45cccd5deb6b86755cc5f6a91b6ade26e7eefc1e7670b5833a95502c"

// the public Substrate development account, used only as test data
private const val walletA = "5GrwvaEF5zXb26Fz9rcQpDWS57CtERHpNehXCPcNoHGKutQY"

/** Status text and counting rules. */
class ProviderStatusTest {
    /** The disclaimer is the contract's exact text. */
    @Test
    fun consentDisclaimer() {
        val digest = MessageDigest.getInstance("SHA-256").digest(consentDisclaimer.toByteArray(Charsets.UTF_8))
        assertEquals(consentDisclaimerSha256, digest.joinToString("") { "%02x".format(it) })
    }

    /** Byte counts use binary units with one decimal. */
    @Test
    fun formatByteCount() {
        val cases = listOf(
            0L to "0 B",
            1023L to "1023 B",
            1024L to "1.0 KiB",
            1536L to "1.5 KiB",
            1048575L to "1.0 MiB",
            13002342L to "12.4 MiB",
            5L * 1024 * 1024 * 1024 to "5.0 GiB",
            3L * 1024 * 1024 * 1024 * 1024 to "3.0 TiB",
            // 1.25 KiB exactly: a tie rounds half to even, as the Go reference formats it
            1280L to "1.2 KiB",
        )
        for ((byteCount, text) in cases) {
            assertEquals("byte count $byteCount", text, formatByteCount(byteCount))
        }
    }

    /**
     * The client limit status names the sdk's retry time in UTC, rounded up to the next whole
     * minute; other states never show it.
     */
    @Test
    fun statusText() {
        data class Case(val state: String, val clientLimitRetryTime: Long, val text: String)
        val cases = listOf(
            // 2026-10-06 19:05:00.000 UTC, exactly on a minute
            Case(state = providerStateClientLimit, clientLimitRetryTime = 1791313500000, text = "client limit, retry at 19:05 UTC"),
            // 19:04:00.001 rounds up, so the shown time is never before the retry
            Case(state = providerStateClientLimit, clientLimitRetryTime = 1791313440001, text = "client limit, retry at 19:05 UTC"),
            // no retry time
            Case(state = providerStateClientLimit, clientLimitRetryTime = 0, text = "client limit"),
            // 23:59:00.001 rolls over the hour and the day
            Case(state = providerStateClientLimit, clientLimitRetryTime = 1791331140001, text = "client limit, retry at 00:00 UTC"),
            Case(state = providerStateStarting, clientLimitRetryTime = 1791313500000, text = "starting"),
        )
        for (c in cases) {
            assertEquals("$c", c.text, providerStatusText(c.state, c.clientLimitRetryTime))
        }
    }

    /** The status line matches the contract's golden lines. */
    @Test
    fun statusLines() {
        val cases = listOf(
            ProviderStatus(state = providerStateStarting, payoutWallet = payoutWalletChecking) to
                "status: starting | clients served: 0 | data provided: 0 B | payout wallet: checking",
            ProviderStatus(state = providerStateProviding, clientsServed = 3, dataProvidedByteCount = 13002342, payoutWallet = walletA, payoutWalletScope = payoutWalletScopeNetwork) to
                "status: providing | clients served: 3 | data provided: 12.4 MiB | payout wallet: $walletA (network)",
            ProviderStatus(state = providerStateProviding, clientsServed = 3, dataProvidedByteCount = 13002342, payoutWallet = walletA, payoutWalletScope = payoutWalletScopeHotkey) to
                "status: providing | clients served: 3 | data provided: 12.4 MiB | payout wallet: $walletA (hotkey)",
            ProviderStatus(state = providerStatePaused, clientsServed = clientsServedLimit, clientsServedAtLimit = true, dataProvidedByteCount = 1536, payoutWallet = walletA, payoutWalletScope = payoutWalletScopeProvider) to
                "status: paused | clients served: 100000+ | data provided: 1.5 KiB | payout wallet: $walletA (this provider)",
            ProviderStatus(state = providerStateStopped, payoutWallet = payoutWalletNotSet) to
                "status: stopped | clients served: 0 | data provided: 0 B | payout wallet: not set",
            ProviderStatus(state = providerStateClientLimit, clientLimitRetryTime = 1791313500000, payoutWallet = walletA, payoutWalletScope = payoutWalletScopeNetwork) to
                "status: client limit, retry at 19:05 UTC | clients served: 0 | data provided: 0 B | payout wallet: $walletA (network)",
        )
        for ((status, line) in cases) {
            assertEquals(line, status.line())
        }
    }

    /**
     * A change of the status text updates the notification at once, including a new client limit
     * retry time; the data counter alone does not.
     */
    @Test
    fun statusKey() {
        val status = ProviderStatus(state = providerStateClientLimit, clientLimitRetryTime = 1791313500000, payoutWallet = payoutWalletChecking)
        // 19:25 UTC
        val retried = status.copy(clientLimitRetryTime = 1791314700000)
        assertNotEquals("a new client limit retry time keeps the key", status.key(), retried.key())
        val counted = status.copy(dataProvidedByteCount = 1536)
        assertEquals("the data counter alone changes the key", status.key(), counted.key())
    }

    /**
     * The providing state follows the provide mode, client limit, pause, enable and connected
     * rules, in that order.
     */
    @Test
    fun providerState() {
        data class Case(
            val provideMode: Long,
            val clientLimitStatus: String,
            val providePaused: Boolean,
            val provideEnabled: Boolean,
            val providerConnected: Boolean,
            val state: String,
        )
        val cases = listOf(
            Case(provideMode = Sdk.ProvideModeNone, clientLimitStatus = Sdk.ClientLimitStatusNone, providePaused = false, provideEnabled = false, providerConnected = false, state = providerStateStopped),
            Case(provideMode = Sdk.ProvideModeNetwork, clientLimitStatus = Sdk.ClientLimitStatusNone, providePaused = false, provideEnabled = true, providerConnected = true, state = providerStateStopped),
            Case(provideMode = Sdk.ProvideModePublic, clientLimitStatus = Sdk.ClientLimitStatusNone, providePaused = false, provideEnabled = true, providerConnected = false, state = providerStateStarting),
            Case(provideMode = Sdk.ProvideModePublic, clientLimitStatus = Sdk.ClientLimitStatusNone, providePaused = false, provideEnabled = false, providerConnected = true, state = providerStateStarting),
            Case(provideMode = Sdk.ProvideModePublic, clientLimitStatus = Sdk.ClientLimitStatusNone, providePaused = false, provideEnabled = true, providerConnected = true, state = providerStateProviding),
            Case(provideMode = Sdk.ProvideModePublic, clientLimitStatus = Sdk.ClientLimitStatusNone, providePaused = true, provideEnabled = true, providerConnected = true, state = providerStatePaused),
            // the client limit comes after stopped and before every other state
            Case(provideMode = Sdk.ProvideModeNetwork, clientLimitStatus = Sdk.ClientLimitStatusExceeded, providePaused = false, provideEnabled = true, providerConnected = true, state = providerStateStopped),
            Case(provideMode = Sdk.ProvideModePublic, clientLimitStatus = Sdk.ClientLimitStatusExceeded, providePaused = true, provideEnabled = true, providerConnected = true, state = providerStateClientLimit),
            Case(provideMode = Sdk.ProvideModePublic, clientLimitStatus = Sdk.ClientLimitStatusExceeded, providePaused = false, provideEnabled = false, providerConnected = false, state = providerStateClientLimit),
            Case(provideMode = Sdk.ProvideModePublic, clientLimitStatus = Sdk.ClientLimitStatusNone, providePaused = true, provideEnabled = true, providerConnected = true, state = providerStatePaused),
        )
        for (c in cases) {
            assertEquals("$c", c.state, providerState(c.provideMode, c.clientLimitStatus, c.providePaused, c.provideEnabled, c.providerConnected))
        }
    }

    /** The payout wallet is labeled by its consent scope first, then by the owner of its mapping. */
    @Test
    fun payoutWalletScope() {
        val clientId = "11111111-1111-1111-1111-111111111111"
        data class Case(val walletConsentScope: String, val walletClientId: String, val scope: String)
        val cases = listOf(
            // a hotkey delegation is network-level but not the network's wallet
            Case(walletConsentScope = snWalletConsentScopeHotkey, walletClientId = "", scope = payoutWalletScopeHotkey),
            Case(walletConsentScope = snWalletConsentScopeHotkey, walletClientId = clientId, scope = payoutWalletScopeHotkey),
            Case(walletConsentScope = Sdk.SnWalletConsentScopeNetwork, walletClientId = "", scope = payoutWalletScopeNetwork),
            Case(walletConsentScope = "", walletClientId = "", scope = payoutWalletScopeNetwork),
            Case(walletConsentScope = Sdk.SnWalletConsentScopeProvider, walletClientId = clientId, scope = payoutWalletScopeProvider),
            Case(walletConsentScope = "", walletClientId = clientId, scope = payoutWalletScopeProvider),
            Case(walletConsentScope = Sdk.SnWalletConsentScopeProvider, walletClientId = "22222222-2222-2222-2222-222222222222", scope = payoutWalletScopeAnotherProvider),
        )
        for (c in cases) {
            assertEquals("$c", c.scope, payoutWalletScope(c.walletConsentScope, c.walletClientId, clientId))
        }
    }

    /** Contract peers resolve by direction and count once per client, up to the limit. */
    @Test
    fun clientsServed() {
        val provider = "11111111-1111-1111-1111-111111111111"
        val clientA = "22222222-2222-2222-2222-222222222222"
        val clientB = "33333333-3333-3333-3333-333333333333"
        val stream = "44444444-4444-4444-4444-444444444444"
        val zero = "00000000-0000-0000-0000-000000000000"

        // the peer is the source of a receive contract and the destination of a send contract
        data class Case(
            val contractId: String,
            val sourceId: String?,
            val destinationId: String?,
            val streamId: String?,
            val receive: Boolean,
            val peerKey: String,
        )
        val keyCases = listOf(
            Case(contractId = "55555555-5555-5555-5555-555555555555", sourceId = clientA, destinationId = provider, streamId = null, receive = true, peerKey = clientA),
            Case(contractId = "66666666-6666-6666-6666-666666666666", sourceId = provider, destinationId = clientA, streamId = null, receive = false, peerKey = clientA),
            Case(contractId = "77777777-7777-7777-7777-777777777777", sourceId = zero, destinationId = provider, streamId = stream, receive = true, peerKey = "stream:$stream"),
            // no path
            Case(contractId = "88888888-8888-8888-8888-888888888888", sourceId = null, destinationId = null, streamId = null, receive = true, peerKey = "contract:88888888-8888-8888-8888-888888888888"),
        )
        for (c in keyCases) {
            assertEquals("$c", c.peerKey, contractPeerKey(c.contractId, c.sourceId, c.destinationId, c.streamId, c.receive))
        }

        // both directions of one client count once
        val served = ClientsServed(limit = 2)
        served.add(contractPeerKey("55555555-5555-5555-5555-555555555555", clientA, provider, null, receive = true))
        served.add(contractPeerKey("66666666-6666-6666-6666-666666666666", provider, clientA, null, receive = false))
        served.add(contractPeerKey("99999999-9999-9999-9999-999999999999", clientA, provider, null, receive = true))
        assertEquals(ClientsServedCount(count = 1, atLimit = false), served.count())
        served.add(contractPeerKey("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", clientB, provider, null, receive = true))
        assertEquals(ClientsServedCount(count = 2, atLimit = false), served.count())
        // a third distinct peer reaches the limit of 2
        served.add(contractPeerKey("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb", zero, provider, stream, receive = true))
        assertEquals(ClientsServedCount(count = 2, atLimit = true), served.count())
        // a contract with no peer is not counted
        served.add("")
        assertEquals(ClientsServedCount(count = 2, atLimit = true), served.count())
    }
}
