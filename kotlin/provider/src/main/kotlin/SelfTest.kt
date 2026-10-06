// The credential-free self-test (PROVIDER_CONTRACT.md, "Self-test"). It
// checks the disclaimer, the status text, the providing state, the payout
// wallet labels, the clients-served count, the JSON values of the C ABI, the
// installation state files and the exit codes without a network, credentials,
// a device or the native SDK runtime: it never calls the SDK, so the native
// library never loads. `--self-test` runs every check and stops at the first
// failure; `gradle check` runs it too.
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.Base64
import java.util.Collections
import java.util.HexFormat
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// sha-256 of the consent disclaimer (utf-8, lf line breaks, no trailing
// newline), published in PROVIDER_CONTRACT.md for every example to check
private const val consentDisclaimerSha256 = "83edee1e45cccd5deb6b86755cc5f6a91b6ade26e7eefc1e7670b5833a95502c"

// the public Substrate development account, used only as test data
private const val walletA = "5GrwvaEF5zXb26Fz9rcQpDWS57CtERHpNehXCPcNoHGKutQY"

// synthetic ids: the provider, two clients and a stream
private const val providerId = "11111111-1111-1111-1111-111111111111"
private const val clientAId = "22222222-2222-2222-2222-222222222222"
private const val clientBId = "33333333-3333-3333-3333-333333333333"
private const val streamId = "44444444-4444-4444-4444-444444444444"

/** A failed check. */
class SelfTestFailure(message: String) : Exception(message)

/** Runs every check and throws the first failure. */
fun runSelfTest() {
    val checks = listOf(
        ::checkConsentDisclaimer,
        ::checkFormatByteCount,
        ::checkStatusText,
        ::checkStatusLines,
        ::checkStatusKey,
        ::checkProviderState,
        ::checkPayoutWalletScope,
        ::checkPayoutWalletReads,
        ::checkClientsServed,
        ::checkSdkValues,
        ::checkClientJwtClaims,
        ::checkStateFiles,
        ::checkProviderConfig,
        ::checkStopBeforeStart,
        ::checkStopHook,
        ::checkExitCodes,
    )
    for (check in checks) {
        check()
    }
}

/** The disclaimer is the contract's exact text. */
private fun checkConsentDisclaimer() {
    val digest = MessageDigest.getInstance("SHA-256").digest(consentDisclaimer.toByteArray(Charsets.UTF_8))
    expect(HexFormat.of().formatHex(digest) == consentDisclaimerSha256) {
        "consent disclaimer differs from PROVIDER_CONTRACT.md"
    }
}

/** Byte counts use binary units with one decimal, rounded as the Go reference rounds. */
private fun checkFormatByteCount() {
    val cases = listOf(
        0L to "0 B",
        1023L to "1023 B",
        1024L to "1.0 KiB",
        1536L to "1.5 KiB",
        1048575L to "1.0 MiB",
        13002342L to "12.4 MiB",
        5L * 1024 * 1024 * 1024 to "5.0 GiB",
        3L * 1024 * 1024 * 1024 * 1024 to "3.0 TiB",
        // exact halves round to the even tenth, as the contract and Go's %.1f
        // do: 1.25 KiB and 1.75 KiB
        1280L to "1.2 KiB",
        1792L to "1.8 KiB",
        Long.MAX_VALUE to "8.0 EiB",
    )
    for ((byteCount, text) in cases) {
        val formatted = formatByteCount(byteCount)
        expect(formatted == text) { "byte count $byteCount formats as \"$formatted\", want \"$text\"" }
    }
}

/**
 * The client limit status names the sdk's retry time in UTC, rounded up to the next whole minute;
 * other states never show it.
 */
private fun checkStatusText() {
    /** A state with a retry time, and its status text. */
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
        val text = providerStatusText(c.state, c.clientLimitRetryTime)
        expect(text == c.text) { "status text \"$text\" for $c" }
    }
}

/** The status line matches the contract's golden lines. */
private fun checkStatusLines() {
    val cases = listOf(
        ProviderStatus(state = providerStateStarting, payoutWallet = payoutWalletChecking) to
            "status: starting | clients served: 0 | data provided: 0 B | payout wallet: checking",
        ProviderStatus(
            state = providerStateProviding,
            clientsServed = 3,
            dataProvidedByteCount = 13002342,
            payoutWallet = walletA,
            payoutWalletScope = payoutWalletScopeNetwork,
        ) to "status: providing | clients served: 3 | data provided: 12.4 MiB | payout wallet: $walletA (network)",
        ProviderStatus(
            state = providerStateProviding,
            clientsServed = 3,
            dataProvidedByteCount = 13002342,
            payoutWallet = walletA,
            payoutWalletScope = payoutWalletScopeHotkey,
        ) to "status: providing | clients served: 3 | data provided: 12.4 MiB | payout wallet: $walletA (hotkey)",
        ProviderStatus(
            state = providerStatePaused,
            clientsServed = clientsServedLimit,
            clientsServedAtLimit = true,
            dataProvidedByteCount = 1536,
            payoutWallet = walletA,
            payoutWalletScope = payoutWalletScopeProvider,
        ) to "status: paused | clients served: 100000+ | data provided: 1.5 KiB | payout wallet: $walletA (this provider)",
        ProviderStatus(state = providerStateStopped, payoutWallet = payoutWalletNotSet) to
            "status: stopped | clients served: 0 | data provided: 0 B | payout wallet: not set",
        ProviderStatus(
            state = providerStateClientLimit,
            clientLimitRetryTime = 1791313500000,
            payoutWallet = walletA,
            payoutWalletScope = payoutWalletScopeNetwork,
        ) to "status: client limit, retry at 19:05 UTC | clients served: 0 | data provided: 0 B | payout wallet: $walletA (network)",
    )
    for ((status, line) in cases) {
        expect(status.line() == line) { "status line \"${status.line()}\", want \"$line\"" }
    }
}

/**
 * A change of the status text prints a line at once, including a new client limit retry time; the
 * data counter alone does not.
 */
private fun checkStatusKey() {
    val status = ProviderStatus(
        state = providerStateClientLimit,
        clientLimitRetryTime = 1791313500000,
        payoutWallet = payoutWalletChecking,
    )
    // 19:25 UTC
    val retried = status.copy(clientLimitRetryTime = 1791314700000)
    expect(status.key() != retried.key()) { "a new client limit retry time does not print a status line" }
    val counted = status.copy(dataProvidedByteCount = 1536)
    expect(status.key() == counted.key()) { "the data counter alone prints a status line" }
}

/** The providing state follows the provide mode, client limit, pause, enable and connected rules, in that order. */
private fun checkProviderState() {
    /** The device getters, and the providing state they give. */
    data class Case(
        val provideMode: Long,
        val clientLimitStatus: String = clientLimitStatusNone,
        val providePaused: Boolean = false,
        val provideEnabled: Boolean,
        val providerConnected: Boolean,
        val state: String,
    )
    val cases = listOf(
        Case(provideMode = provideModeNone, provideEnabled = false, providerConnected = false, state = providerStateStopped),
        Case(provideMode = provideModeNetwork, provideEnabled = true, providerConnected = true, state = providerStateStopped),
        Case(provideMode = provideModePublic, provideEnabled = true, providerConnected = false, state = providerStateStarting),
        Case(provideMode = provideModePublic, provideEnabled = false, providerConnected = true, state = providerStateStarting),
        Case(provideMode = provideModePublic, provideEnabled = true, providerConnected = true, state = providerStateProviding),
        Case(provideMode = provideModePublic, providePaused = true, provideEnabled = true, providerConnected = true, state = providerStatePaused),
        // the client limit comes after stopped and before every other state
        Case(
            provideMode = provideModeNetwork,
            clientLimitStatus = clientLimitStatusExceeded,
            provideEnabled = true,
            providerConnected = true,
            state = providerStateStopped,
        ),
        Case(
            provideMode = provideModePublic,
            clientLimitStatus = clientLimitStatusExceeded,
            providePaused = true,
            provideEnabled = true,
            providerConnected = true,
            state = providerStateClientLimit,
        ),
        Case(
            provideMode = provideModePublic,
            clientLimitStatus = clientLimitStatusExceeded,
            provideEnabled = false,
            providerConnected = false,
            state = providerStateClientLimit,
        ),
        Case(
            provideMode = provideModePublic,
            clientLimitStatus = clientLimitStatusNone,
            providePaused = true,
            provideEnabled = true,
            providerConnected = true,
            state = providerStatePaused,
        ),
    )
    for (c in cases) {
        val state = providerState(c.provideMode, c.clientLimitStatus, c.providePaused, c.provideEnabled, c.providerConnected)
        expect(state == c.state) { "provider state \"$state\" for $c" }
    }
}

/** The payout wallet is labeled by its consent scope first, then by the owner of its mapping. */
private fun checkPayoutWalletScope() {
    /** A wallet entry's consent scope and client id, and its label for this provider. */
    data class Case(val walletConsentScope: String, val walletClientId: String, val scope: String)
    val cases = listOf(
        // a hotkey delegation is network-level but not the network's wallet
        Case(walletConsentScope = snWalletConsentScopeHotkey, walletClientId = "", scope = payoutWalletScopeHotkey),
        Case(walletConsentScope = snWalletConsentScopeHotkey, walletClientId = providerId, scope = payoutWalletScopeHotkey),
        Case(walletConsentScope = snWalletConsentScopeNetwork, walletClientId = "", scope = payoutWalletScopeNetwork),
        Case(walletConsentScope = "", walletClientId = "", scope = payoutWalletScopeNetwork),
        Case(walletConsentScope = snWalletConsentScopeProvider, walletClientId = providerId, scope = payoutWalletScopeProvider),
        Case(walletConsentScope = "", walletClientId = providerId, scope = payoutWalletScopeProvider),
        Case(
            walletConsentScope = snWalletConsentScopeProvider,
            walletClientId = clientAId,
            scope = payoutWalletScopeAnotherProvider,
        ),
    )
    for (c in cases) {
        val scope = payoutWalletScope(c.walletConsentScope, c.walletClientId, providerId)
        expect(scope == c.scope) { "payout wallet scope \"$scope\" for $c" }
    }
}

/**
 * The payout wallet shows checking until the first read, unavailable after a failed first read, the
 * last value after a later failure, and not set without a mapped wallet.
 */
private fun checkPayoutWalletReads() {
    expect(payoutWalletBeforeRead.wallet == payoutWalletChecking) { "the payout wallet does not start as checking" }
    val unavailable = payoutWalletAfterRead(payoutWalletBeforeRead, readSucceeded = false, effectiveWallet = null, clientId = providerId)
    expect(unavailable == PayoutWallet(wallet = payoutWalletUnavailable, scope = "")) {
        "a failed first wallet read shows $unavailable"
    }
    val network = payoutWalletAfterRead(
        unavailable,
        readSucceeded = true,
        effectiveWallet = SnWallet(coldkeySs58 = walletA, clientId = "", consentScope = snWalletConsentScopeNetwork),
        clientId = providerId,
    )
    expect(network == PayoutWallet(wallet = walletA, scope = payoutWalletScopeNetwork)) { "a network wallet shows $network" }
    val kept = payoutWalletAfterRead(network, readSucceeded = false, effectiveWallet = null, clientId = providerId)
    expect(kept == network) { "a later failed read shows $kept" }
    val notSet = payoutWalletAfterRead(network, readSucceeded = true, effectiveWallet = null, clientId = providerId)
    expect(notSet == PayoutWallet(wallet = payoutWalletNotSet, scope = "")) { "a read without a wallet shows $notSet" }
}

/** Contract peers resolve by direction from the c abi's JSON and count once per client, up to the limit. */
private fun checkClientsServed() {
    /**
     * Contract details in one direction, and the peer they count: the source of a receive contract
     * and the destination of a send contract.
     */
    data class Case(val contractDetailsJson: String, val receive: Boolean, val peerKey: String)
    val keyCases = listOf(
        Case(contractJson("55555555-5555-5555-5555-555555555555", clientAId, providerId, null), receive = true, peerKey = clientAId),
        Case(contractJson("66666666-6666-6666-6666-666666666666", providerId, clientAId, null), receive = false, peerKey = clientAId),
        Case(
            contractJson("77777777-7777-7777-7777-777777777777", zeroIdString, providerId, streamId),
            receive = true,
            peerKey = "stream:$streamId",
        ),
        Case(
            contractJson("88888888-8888-8888-8888-888888888888", null, null, null),
            receive = true,
            peerKey = "contract:88888888-8888-8888-8888-888888888888",
        ),
        // nothing to key by
        Case("null", receive = true, peerKey = ""),
        Case("""{"ContractId":null,"ContractTransferPath":null,"Status":"open"}""", receive = true, peerKey = ""),
    )
    for (c in keyCases) {
        val peerKey = contractPeerKey(c.contractDetailsJson, c.receive)
        expect(peerKey == c.peerKey) { "contract peer key \"$peerKey\" for $c" }
    }

    // both directions of one client count once
    val served = ClientsServed(2)
    served.add(contractPeerKey(contractJson("55555555-5555-5555-5555-555555555555", clientAId, providerId, null), receive = true))
    served.add(contractPeerKey(contractJson("66666666-6666-6666-6666-666666666666", providerId, clientAId, null), receive = false))
    served.add(contractPeerKey(contractJson("99999999-9999-9999-9999-999999999999", clientAId, providerId, null), receive = true))
    expect(served.count() == ClientsServed.Count(count = 1, atLimit = false)) { "one client counted as ${served.count()}" }
    served.add(contractPeerKey(contractJson("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", clientBId, providerId, null), receive = true))
    expect(served.count() == ClientsServed.Count(count = 2, atLimit = false)) { "two clients counted as ${served.count()}" }
    // a third distinct peer reaches the limit of 2
    served.add(contractPeerKey(contractJson("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb", zeroIdString, providerId, streamId), receive = true))
    expect(served.count() == ClientsServed.Count(count = 2, atLimit = true)) { "limited count ${served.count()}" }
}

/** The c abi's JSON values read as the contract's examples show them. */
private fun checkSdkValues() {
    expect(
        parseClientLimit("""{"Status": "client_limit_exceeded", "RetryTime": 1791313500000}""") ==
            ClientLimit(status = clientLimitStatusExceeded, retryTime = 1791313500000),
    ) { "the client limit status does not read" }
    expect(parseClientLimit("""{"Status": "", "RetryTime": 0}""") == noClientLimit) { "no client limit does not read as none" }
    // NULL: the call could not run, read as no limit
    expect(parseClientLimit(null) == noClientLimit) { "a NULL client limit status does not read as none" }

    expect(
        dataProvidedByteCount("""{"RemoteEgressByteCount": 5, "RemoteIngressByteCount": 7, "LocalEgressByteCount": 11}""") == 12L,
    ) { "data provided is not the remote egress and ingress bytes" }
    expect(dataProvidedByteCount(null) == 0L && dataProvidedByteCount("null") == 0L) { "null packet stats are not 0 bytes" }

    val wallet = parseSnWallet(
        """{"coldkey_ss58": "$walletA", "client_id": "$providerId", "set_at_millis": 1, "consent_scope": "provider"}""",
    )
    expect(wallet == SnWallet(coldkeySs58 = walletA, clientId = providerId, consentScope = snWalletConsentScopeProvider)) {
        "the wallet does not read: $wallet"
    }
    val networkWallet = parseSnWallet("""{"coldkey_ss58": "$walletA", "set_at_millis": 1, "consent_scope": "network"}""")
    expect(networkWallet != null && networkWallet.clientId.isEmpty()) { "a network wallet has a client id" }
    expect(parseSnWallet(null) == null && parseSnWallet("null") == null) { "no wallet reads as a wallet" }

    expect(walletReadSucceeded("""{"wallet": {"coldkey_ss58": "$walletA", "set_at_millis": 1}, "wallets": []}""", null)) {
        "a wallet result reads as a failure"
    }
    expect(walletReadSucceeded("{}", null)) { "an empty wallet result reads as a failure" }
    expect(!walletReadSucceeded("""{"error": {"message": "synthetic failure"}}""", null)) {
        "a wallet result error reads as a success"
    }
    expect(!walletReadSucceeded(null, "synthetic network error")) { "a wallet callback error reads as a success" }
    expect(!walletReadSucceeded("null", null)) { "a null wallet result reads as a success" }
    // text that is not one JSON object reads as no value, not as a crash
    for (invalidText in listOf("", "{", "{\"Status\": 1}", "[]", "{} x")) {
        expect(parseClientLimit(invalidText) == noClientLimit) { "invalid client limit status read: $invalidText" }
    }
}

/** Only a JWT with a valid client_id claim is a client credential. */
private fun checkClientJwtClaims() {
    val clientId = parseClientJwtClientId(selfTestJwt("""{"client_id":"$providerId","network_id":"$clientAId"}"""))
    expect(clientId == providerId) { "client jwt claim $clientId" }
    // the claim is kept in canonical form
    val upperCaseClientId = parseClientJwtClientId(selfTestJwt("""{"client_id":"AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA"}"""))
    expect(upperCaseClientId == "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa") { "client jwt claim $upperCaseClientId" }
    val invalidJwts = listOf(
        "",
        "not-a-jwt",
        "e30.e30",
        "e30..test",
        // a network jwt has no client_id claim
        selfTestJwt("""{"network_id":"$clientAId"}"""),
        selfTestJwt("""{"client_id":"not-a-uuid"}"""),
        selfTestJwt("""{"client_id":11111111}"""),
        selfTestJwt("""["$providerId"]"""),
        "e30.%%%.test",
    )
    for (invalidJwt in invalidJwts) {
        expectRefused("invalid client jwt accepted: $invalidJwt") { parseClientJwtClientId(invalidJwt) }
    }
}

/** State files are private, replaced atomically, created once and bound to their client. */
private fun checkStateFiles() {
    val stateDir = newPrivateTempDir()
    try {
        val posix = hasPosixPermissions(stateDir)

        // an atomic private write leaves no temporary file behind
        val path = stateDir.resolve(clientJwtFileName)
        writePrivateFile(path, "first\n".toByteArray())
        writePrivateFile(path, "second\n".toByteArray())
        expect(readPrivateFile(path).contentEquals("second\n".toByteArray())) { "private file round trip" }
        expect(fileNames(stateDir) == listOf(clientJwtFileName)) { "the private write left ${fileNames(stateDir)}" }
        if (posix) {
            expect(Files.getPosixFilePermissions(path) == ownerReadWrite) {
                "private file mode ${Files.getPosixFilePermissions(path)}"
            }
            // a file or directory that others can read is refused
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-r--r--"))
            expectRefused("a group-readable credential file was accepted") { readPrivateFile(path) }
            Files.setPosixFilePermissions(path, ownerReadWrite)
            // a symlinked file is refused, so the credential cannot be redirected
            val linkPath = stateDir.resolve("linked.jwt")
            Files.createSymbolicLink(linkPath, path)
            expectRefused("a symlinked credential file was accepted") { readPrivateFile(linkPath) }
            Files.delete(linkPath)
            Files.setPosixFilePermissions(stateDir, PosixFilePermissions.fromString("rwxr-xr-x"))
            expectRefused("a group-readable state directory was accepted") { checkStateDir(stateDir.toString()) }
            Files.setPosixFilePermissions(stateDir, ownerAll)
        }
        checkStateDir(stateDir.toString())

        // the instance id is created once and reused
        val instanceId = loadOrCreateInstanceId(stateDir)
        expect(parseId(instanceId) != null) { "instance id $instanceId is not a uuid" }
        val again = loadOrCreateInstanceId(stateDir)
        expect(again == instanceId) { "instance id changed from $instanceId to $again" }

        // the identity belongs to its client
        val identity = ProviderIdentity(
            clientId = providerId,
            clientKeySeed = ByteArray(32) { 1 },
            provideTlsCertificatePem = "synthetic certificate".toByteArray(),
            provideTlsPrivateKeyPem = "synthetic private key".toByteArray(),
            extenderKeySeed = ByteArray(32) { 2 },
        )
        saveProviderIdentity(stateDir, identity)
        val loaded = loadProviderIdentity(stateDir, providerId)
        expect(loaded != null && loaded.sameAs(identity)) { "identity round trip failed" }
        // another client's identity is not used: the device gets no key
        // material and makes a new identity
        expect(loadProviderIdentity(stateDir, clientAId) == null) { "another client's identity was used" }
        // the extender seed is optional
        val withoutExtender = ProviderIdentity(
            clientId = providerId,
            clientKeySeed = ByteArray(32) { 1 },
            provideTlsCertificatePem = "synthetic certificate".toByteArray(),
            provideTlsPrivateKeyPem = "synthetic private key".toByteArray(),
            extenderKeySeed = ByteArray(0),
        )
        saveProviderIdentity(stateDir, withoutExtender)
        val loadedWithoutExtender = loadProviderIdentity(stateDir, providerId)
        expect(loadedWithoutExtender != null && loadedWithoutExtender.sameAs(withoutExtender)) {
            "identity without an extender seed failed"
        }
        // the go reference writes an empty byte field as null
        val identityPath = stateDir.resolve(identityFileName)
        val seed = Base64.getEncoder().encodeToString(ByteArray(32) { 1 })
        val certificate = Base64.getEncoder().encodeToString("synthetic certificate".toByteArray())
        writePrivateFile(
            identityPath,
            """{"version":1,"client_id":"$providerId","client_key_seed":"$seed","provide_tls_certificate_pem":"$certificate","provide_tls_private_key_pem":null}"""
                .toByteArray(),
        )
        val loadedWithNull = loadProviderIdentity(stateDir, providerId)
        val withNull = ProviderIdentity(
            clientId = providerId,
            clientKeySeed = ByteArray(32) { 1 },
            provideTlsCertificatePem = "synthetic certificate".toByteArray(),
            provideTlsPrivateKeyPem = ByteArray(0),
            extenderKeySeed = ByteArray(0),
        )
        expect(loadedWithNull != null && loadedWithNull.sameAs(withNull)) { "an identity with a null field failed" }

        val seed31 = Base64.getEncoder().encodeToString(ByteArray(31) { 1 })
        val seed32 = Base64.getEncoder().encodeToString(ByteArray(32) { 1 })
        val invalidIdentities = listOf(
            """{"version":1}""",
            "not json",
            "[]",
            """{"version":2,"client_id":"$providerId","client_key_seed":"$seed32"}""",
            """{"version":1,"client_id":"$providerId","client_key_seed":"$seed31"}""",
            """{"version":1,"client_id":"$providerId","client_key_seed":"%%%"}""",
            """{"version":"1","client_id":"$providerId","client_key_seed":"$seed32"}""",
        )
        for (invalidIdentity in invalidIdentities) {
            writePrivateFile(identityPath, invalidIdentity.toByteArray())
            expectRefused("an invalid identity was accepted: $invalidIdentity") { loadProviderIdentity(stateDir, providerId) }
        }
    } finally {
        stateDir.toFile().deleteRecursively()
    }
}

/** A missing or incomplete installation state is refused; a first run loads. */
private fun checkProviderConfig() {
    expectRefused("a missing state directory was accepted") { loadProviderConfig("") }
    expectRefused("an unset state directory was accepted") { loadProviderConfig(null) }
    expectRefused("a relative state directory was accepted") { loadProviderConfig("relative/state") }
    val stateDir = newPrivateTempDir()
    try {
        expectRefused("a state directory without client.jwt was accepted") { loadProviderConfig(stateDir.toString()) }
        // a network jwt has no client_id claim
        val networkJwt = selfTestJwt("""{"network_id":"$clientAId"}""")
        val clientJwtPath = stateDir.resolve(clientJwtFileName)
        writePrivateFile(clientJwtPath, "$networkJwt\n".toByteArray())
        expectRefused("a network jwt was accepted") { loadProviderConfig(stateDir.toString()) }
        val clientJwt = selfTestJwt("""{"client_id":"$providerId"}""")
        writePrivateFile(clientJwtPath, "$clientJwt\n".toByteArray())
        val config = loadProviderConfig(stateDir.toString())
        expect(
            config.clientJwt == clientJwt &&
                config.clientId == providerId &&
                parseId(config.instanceId) != null &&
                config.identity == null,
        ) { "first-run configuration differs: $config" }
        expect(clientJwt !in config.toString()) { "the configuration prints the credential" }
    } finally {
        stateDir.toFile().deleteRecursively()
    }
}

/**
 * Ctrl-C or SIGTERM can come while the provider starts: the shutdown hook then requests a stop from
 * a session that has not started. The session keeps the request, so it never starts, and closing a
 * session that never started makes no sdk call and prints nothing. Creating a session makes no sdk
 * call either.
 */
private fun checkStopBeforeStart() {
    val output = ByteArrayOutputStream()
    val session = newUnstartedSession(output)
    expect(!session.stopRequested) { "a new session has a stop requested" }
    session.requestStop()
    expect(session.stopRequested) { "a stop requested before start is not kept" }
    session.close()
    expect(output.size() == 0) { "closing a session that never started printed ${output.toString(Charsets.UTF_8)}" }
}

/**
 * The shutdown hook always ends the process with the decided exit code, never with the JVM's signal
 * status (130, 143): with main's code when main closed the session first, and with 0 when a signal
 * came first, also when main then fails while it starts. The halt is recorded here instead of run.
 */
private fun checkStopHook() {
    // main closed the session first, here after the server rejected the credential
    val mainFirst = newUnstartedSession(ByteArrayOutputStream())
    val mainFirstHalts = Collections.synchronizedList(ArrayList<Int>())
    val mainFirstHook = StopHook(mainFirst) { exitCode -> mainFirstHalts.add(exitCode) }
    mainFirstHook.closed(exitConfig)
    mainFirstHook.stop()
    expect(mainFirstHalts == listOf(exitConfig)) { "after main's exit with 78 the hook ends the process with $mainFirstHalts" }
    expect(!mainFirst.stopRequested) { "after main's exit the hook requested a stop" }

    // a signal came first: the hook requests the stop and waits for main to close
    val signalFirst = newUnstartedSession(ByteArrayOutputStream())
    val signalFirstHalts = Collections.synchronizedList(ArrayList<Int>())
    val signalFirstHook = StopHook(signalFirst) { exitCode -> signalFirstHalts.add(exitCode) }
    val hookThread = Thread(signalFirstHook::stop, "self-test-stop-hook")
    hookThread.start()
    // the requested stop shows that the hook found no exit code from main
    val deadline = System.nanoTime() + 30.seconds.inWholeNanoseconds
    while (!signalFirst.stopRequested) {
        expect(System.nanoTime() - deadline < 0) { "the hook did not request a stop" }
        Thread.onSpinWait()
    }
    // main then fails while it starts, and closes
    signalFirstHook.closed(exitFailure)
    hookThread.join(30.seconds.inWholeMilliseconds)
    expect(signalFirstHalts == listOf(exitStopped)) { "after a signal the hook ends the process with $signalFirstHalts" }
}

/**
 * A usage error and a configuration error exit with 78 before anything loads the native runtime;
 * the provider prints the disclaimer before it reads the state.
 */
private fun checkExitCodes() {
    val discard = PrintStream(OutputStream.nullOutputStream())
    for (args in listOf(listOf("--unknown"), listOf("run", "extra"), listOf("--self-test", "extra"))) {
        val exitCode = runCommand(args, null, discard, discard)
        expect(exitCode == exitConfig) { "usage error $args exits with $exitCode" }
    }
    for (stateDir in listOf(null, "", "relative/state")) {
        val output = ByteArrayOutputStream()
        val exitCode = runCommand(listOf("run"), stateDir, PrintStream(output, true, Charsets.UTF_8), discard)
        expect(exitCode == exitConfig) { "state directory \"$stateDir\" exits with $exitCode" }
        expect(output.toString(Charsets.UTF_8).startsWith(consentDisclaimer)) {
            "the provider does not print the disclaimer first"
        }
    }
}

/** A session for a synthetic installation that has not started; it prints to output. */
private fun newUnstartedSession(output: ByteArrayOutputStream): ProviderSession {
    val config = ProviderConfig(
        stateDir = Path.of("unused-state"),
        clientJwt = selfTestJwt("""{"client_id":"$providerId"}"""),
        clientId = providerId,
        instanceId = "55555555-5555-5555-5555-555555555555",
        identity = null,
    )
    val printStream = PrintStream(output, true, Charsets.UTF_8)
    return ProviderSession(config, printStream, printStream)
}

/** A synthetic, unsigned JWT with the given payload json. */
private fun selfTestJwt(payloadJson: String): String =
    "e30." + Base64.getUrlEncoder().withoutPadding().encodeToString(payloadJson.toByteArray()) + ".test"

/** The c abi's contract details JSON for one contract; a null id is JSON null. */
private fun contractJson(contractId: String, sourceId: String?, destinationId: String?, streamId: String?): String =
    buildJsonObject {
        put("ContractId", contractId)
        put("ContractUsedByteCount", 0)
        put("ContractByteCount", 0)
        put("ContractBitRate", 0)
        if (sourceId == null && destinationId == null && streamId == null) {
            put("ContractTransferPath", JsonNull)
        } else {
            put(
                "ContractTransferPath",
                buildJsonObject {
                    put("SourceId", sourceId)
                    put("DestinationId", destinationId)
                    put("StreamId", streamId)
                },
            )
        }
        put("Status", "open")
    }.toString()

/** A new temporary directory, private to its owner where the file system has POSIX permissions. */
private fun newPrivateTempDir(): Path {
    val dir = Files.createTempDirectory("ur-provider-self-test-")
    if (hasPosixPermissions(dir)) {
        Files.setPosixFilePermissions(dir, ownerAll)
    }
    return dir.toRealPath()
}

/** The sorted names in a directory. */
private fun fileNames(dir: Path): List<String> =
    Files.list(dir).use { paths -> paths.map { it.fileName.toString() }.sorted().toList() }

/** Fails with the message unless condition holds. */
private inline fun expect(condition: Boolean, message: () -> String) {
    if (!condition) {
        throw SelfTestFailure(message())
    }
}

/** Fails with message unless the call throws. */
private inline fun expectRefused(message: String, call: () -> Unit) {
    try {
        call()
    } catch (e: Exception) {
        return
    }
    throw SelfTestFailure(message)
}
