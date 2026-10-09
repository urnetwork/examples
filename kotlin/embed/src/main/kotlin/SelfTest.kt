// The credential-free self-test (EMBED_CONTRACT.md, "Self-test"). It checks the
// status texts and rules, the data fields, the cap object, the client JWT
// claim, the token fetch against a stand-in server, the cap read and the
// installation state without a network, credentials, a device or the native
// SDK runtime: nothing it runs touches io.ur.sdk.Sdk, so it passes even where
// the SDK library is missing. The data is synthetic.
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.PrintStream
import java.net.ConnectException
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.Base64
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private const val testClientId = "11111111-1111-1111-1111-111111111111"
private const val otherClientId = "33333333-3333-3333-3333-333333333333"
private const val testInstanceId = "22222222-2222-2222-2222-222222222222"

// a stand-in demo session token: at least 32 characters, as the token server
// requires
private const val testDemoSession = "selftest-demo-session-0123456789abcdef"
private val testTokenServer = URI.create("http://127.0.0.1:8790/")
private val testApiOrigin = URI.create("https://api.bringyour.com/")

/** Runs every check in order and stops at the first failure. */
fun runSelfTest() {
    checkFormatByteCount()
    checkResetText()
    checkClientLimitText()
    checkStatusLines()
    checkStatusRules()
    checkDataFields()
    checkCapParsing()
    checkClientJwtClaims()
    checkOrigins()
    checkTokenFetch()
    checkCapRead()
    checkStateFiles()
    checkSettings()
    checkUsageExitCode()
    checkStartLine()
}

/** Fails with reason unless condition holds. */
private fun expect(condition: Boolean, reason: () -> String) {
    if (!condition) {
        throw IllegalStateException(reason())
    }
}

/** Fails with reason unless block throws T, and returns the exception. */
private inline fun <reified T : Exception> expectThrows(reason: String, block: () -> Unit): T {
    try {
        block()
    } catch (e: Exception) {
        if (e is T) {
            return e
        }
        throw IllegalStateException("$reason (threw $e)")
    }
    throw IllegalStateException(reason)
}

/** Cap readings after the given readings (null is a failed reading). */
private fun readings(vararg readings: DataCap?): CapReadings =
    CapReadings().also { capReadings -> readings.forEach(capReadings::record) }

/** Cap readings after the given readings, then the Embed-not-enabled refusal. */
private fun notEnabled(vararg readings: DataCap?): CapReadings = readings(*readings).also { it.recordEmbedNotEnabled() }

/** Decimal units, one decimal, ties to even on the exact integer, the next unit at 1000.0. */
private fun checkFormatByteCount() {
    val cases = listOf(
        0L to "0 B", 999L to "999 B", 1000L to "1.0 kB", 999949L to "999.9 kB",
        // exact halves round to even on the integer: 1.05 is not a binary fraction
        1050L to "1.0 kB", 1150L to "1.2 kB", 1250L to "1.2 kB", 1750L to "1.8 kB",
        // 999.95 kB ties to the even 1000.0 kB and moves to the next unit, as does 999.999 kB
        999950L to "1.0 MB", 999999L to "1.0 MB",
        1234567890L to "1.2 GB", 5000000000L to "5.0 GB", 10000000000L to "10.0 GB",
        3000000000000L to "3.0 TB", Long.MAX_VALUE to "9.2 EB",
    )
    for ((byteCount, want) in cases) {
        val text = formatByteCount(byteCount)
        expect(text == want) { "byte count $byteCount formats as \"$text\", want \"$want\"" }
    }
}

/** The monthly reset time in UTC, rounded up to the next whole minute. */
private fun checkResetText() {
    val cases = listOf(
        "2026-11-01T00:00:00Z" to "resets 2026-11-01 00:00 UTC",
        "2026-10-31T23:59:00.001Z" to "resets 2026-11-01 00:00 UTC",
        "2026-10-31T19:00:00-05:00" to "resets 2026-11-01 00:00 UTC",
        // Go writes up to nine fraction digits
        "2026-10-31T23:59:59.999999999Z" to "resets 2026-11-01 00:00 UTC",
        "2026-11-01T00:00:00.000Z" to "resets 2026-11-01 00:00 UTC",
        "soon" to null, "" to null, null to null, "2026-02-30T00:00:00Z" to null,
        // rounds past the year 9999
        "9999-12-31T23:59:30Z" to null,
    )
    for ((end, want) in cases) {
        val text = resetText(end)
        expect(text == want) { "reset text for $end is \"$text\", want \"$want\"" }
    }
}

/** The client limit text rounds the retry time up to the next whole minute. */
private fun checkClientLimitText() {
    val cases = listOf(
        // 2026-10-06 19:05:00.000 UTC, exactly on a minute
        1791313500000L to "client limit, retry at 19:05 UTC",
        // 19:04:00.001 rounds up, so the shown time is never before the retry
        1791313440001L to "client limit, retry at 19:05 UTC",
        0L to "client limit",
    )
    for ((retryTime, want) in cases) {
        val text = clientLimitText(retryTime)
        expect(text == want) { "client limit text for $retryTime is \"$text\"" }
    }
}

/** A console status line. */
private fun line(clientLimitStatus: String, retryTime: Long, readings: CapReadings, providersAdded: Int): String =
    statusLine(
        embedStatus(true, false, clientLimitStatus, retryTime, readings.latest, providersAdded),
        dataField(readings, monthly = true),
        dataField(readings, monthly = false),
    )

/** The status lines match the contract's golden lines. */
private fun checkStatusLines() {
    val cases = listOf(
        line(clientLimitStatusNone, 0, readings(), 0) to
            "status: connecting | data this month: checking | data total: checking",
        line(clientLimitStatusNone, 0, readings(DataCap(monthlyByteLimit = 5000000000, monthlyUsedByteCount = 1234567890)), 1) to
            "status: connected | data this month: 1.2 GB of 5.0 GB | data total: no cap",
        line(clientLimitStatusNone, 0, readings(null), 1) to
            "status: connected | data this month: unavailable | data total: unavailable",
        line(clientLimitStatusNone, 0, notEnabled(), 1) to
            "status: connected | data this month: unavailable | data total: unavailable",
        line(
            clientLimitStatusNone, 0,
            notEnabled(
                DataCap(
                    monthlyByteLimit = 5000000000, monthlyUsedByteCount = 5000000000,
                    monthlyPeriodEnd = "2026-11-01T00:00:00Z", capped = true, cappedReason = "monthly",
                ),
            ),
            1,
        ) to "status: connected | data this month: unavailable | data total: unavailable",
        line(
            clientLimitStatusNone, 0,
            readings(
                DataCap(
                    monthlyByteLimit = 5000000000, monthlyUsedByteCount = 5000000000,
                    monthlyPeriodEnd = "2026-11-01T00:00:00Z", capped = true, cappedReason = "monthly",
                ),
            ),
            3,
        ) to "status: data cap reached, resets 2026-11-01 00:00 UTC | data this month: 5.0 GB of 5.0 GB | data total: no cap",
        line(
            clientLimitStatusNone, 0,
            readings(DataCap(totalByteLimit = 10000000000, totalUsedByteCount = 10000000000, capped = true, cappedReason = "total")),
            3,
        ) to "status: data cap reached | data this month: no cap | data total: 10.0 GB of 10.0 GB",
        line(clientLimitStatusNone, 0, readings(DataCap(monthlyByteLimit = 0, capped = true, cappedReason = "monthly")), 3) to
            "status: paused | data this month: 0 B of 0 B | data total: no cap",
        line(clientLimitStatusExceeded, 1791313500000, readings(DataCap(monthlyByteLimit = 5000000000)), 0) to
            "status: client limit, retry at 19:05 UTC | data this month: 0 B of 5.0 GB | data total: no cap",
    )
    for ((text, want) in cases) {
        expect(text == want) { "status line \"$text\", want \"$want\"" }
    }
}

/** The status rules apply in the contract's order. */
private fun checkStatusRules() {
    data class Case(
        val started: Boolean,
        val signedOut: Boolean,
        val clientLimit: String,
        val retry: Long,
        val cap: DataCap?,
        val added: Int,
        val want: String,
    )
    val monthlyZero = DataCap(monthlyByteLimit = 0, capped = true, cappedReason = "monthly")
    val none = clientLimitStatusNone
    val cases = listOf(
        Case(false, false, none, 0, null, 0, "stopped"),
        Case(false, true, none, 0, null, 0, "signed out"),
        Case(true, false, clientLimitStatusExceeded, 1791313500000, monthlyZero, 3, "client limit, retry at 19:05 UTC"),
        Case(true, false, none, 0, monthlyZero, 3, "paused"),
        Case(true, false, none, 0, DataCap(totalByteLimit = 0, monthlyByteLimit = 5000000000, capped = true, cappedReason = "total"), 3, "paused"),
        Case(
            true, false, none, 0,
            DataCap(monthlyByteLimit = 5000000000, monthlyPeriodEnd = "2026-11-01T00:00:00Z", capped = true, cappedReason = "monthly"),
            3, "data cap reached, resets 2026-11-01 00:00 UTC",
        ),
        Case(true, false, none, 0, DataCap(totalByteLimit = 10000000000, capped = true, cappedReason = "total"), 0, "data cap reached"),
        Case(true, false, none, 0, DataCap(), 1, "connected"),
        Case(true, false, none, 0, null, 0, "connecting"),
        // an unknown reason is capped without a reset time and names no cap
        Case(true, false, none, 0, DataCap(monthlyByteLimit = 0, totalByteLimit = 0, capped = true, cappedReason = "weekly"), 1, "data cap reached"),
        // a monthly end that does not parse shows no reset time
        Case(true, false, none, 0, DataCap(monthlyByteLimit = 5, monthlyPeriodEnd = "later", capped = true, cappedReason = "monthly"), 1, "data cap reached"),
        // the cap that capped_reason names decides paused
        Case(true, false, none, 0, DataCap(monthlyByteLimit = 0, totalByteLimit = 10, capped = true, cappedReason = "total"), 1, "data cap reached"),
    )
    for (c in cases) {
        val status = embedStatus(c.started, c.signedOut, c.clientLimit, c.retry, c.cap, c.added)
        expect(status == c.want) { "status \"$status\", want \"${c.want}\"" }
    }
}

/** The data fields before, after and between cap readings. */
private fun checkDataFields() {
    expect(dataField(readings(), monthly = true) == "checking") { "no reading yet is not checking" }
    expect(dataField(readings(null), monthly = false) == "unavailable") { "a failed first reading is not unavailable" }
    val good = DataCap(monthlyByteLimit = 5000000000, monthlyUsedByteCount = 1234567890)
    expect(dataField(readings(good, null), monthly = true) == "1.2 GB of 5.0 GB") { "a later failure does not keep the last value" }
    expect(dataField(readings(null, good), monthly = true) == "1.2 GB of 5.0 GB") { "a success after a failure does not show" }
    // a server need not report usage for an uncapped client
    val uncapped = DataCap(monthlyUsedByteCount = 123, totalUsedByteCount = 456)
    expect(dataField(readings(uncapped), monthly = true) == "no cap" && dataField(readings(uncapped), monthly = false) == "no cap") {
        "an uncapped field shows a used count"
    }
    expect(dataField(readings(DataCap(totalByteLimit = 10000000000, totalUsedByteCount = 1000)), monthly = false) == "1.0 kB of 10.0 GB") {
        "the total field is wrong"
    }
    // the Embed-not-enabled refusal clears the last reading
    val cleared = notEnabled(good)
    expect(cleared.latest == null && dataField(cleared, monthly = true) == "unavailable" && dataField(cleared, monthly = false) == "unavailable") {
        "the Embed-not-enabled refusal does not clear the last reading"
    }
}

/** The cap object parsing. */
private fun checkCapParsing() {
    val full = DataCap.parse(
        """{"client_id":"$testClientId","monthly_byte_limit":10000000000,"monthly_used_byte_count":42,""" +
            """"monthly_period_start":"2026-10-01T00:00:00Z","monthly_period_end":"2026-11-01T00:00:00Z",""" +
            """"total_byte_limit":null,"total_used_byte_count":7,"total_period_start":"2026-09-15T08:00:00Z",""" +
            """"capped":false,"capped_reason":""}""",
    )
    expect(
        full == DataCap(
            clientId = testClientId, monthlyByteLimit = 10000000000, monthlyUsedByteCount = 42,
            monthlyPeriodStart = "2026-10-01T00:00:00Z", monthlyPeriodEnd = "2026-11-01T00:00:00Z",
            totalByteLimit = null, totalUsedByteCount = 7, totalPeriodStart = "2026-09-15T08:00:00Z",
            capped = false, cappedReason = "",
        ),
    ) { "a full cap object does not parse: $full" }
    expect(DataCap.parse("{}") == DataCap()) { "absent limits are not null" }
    val capped = DataCap.parse("""{"total_byte_limit":0,"capped":true,"capped_reason":"total"}""")
    expect(capped == DataCap(totalByteLimit = 0, capped = true, cappedReason = "total")) { "capped does not parse" }
    val unknown = DataCap.parse("""{"monthly_byte_limit":5,"capped":true,"capped_reason":"weekly"}""")
    expect(unknown != null && unknown.capped && embedStatus(true, false, "", 0, unknown, 1) == "data cap reached") {
        "an unknown capped_reason is not read as capped without a reset time"
    }
    val invalid = listOf(
        """{"error":{"message":"Client does not exist."}}""", "not json", "[]", "null", null,
        """{"monthly_byte_limit":"5GB"}""", """{"capped":"yes"}""", """{"monthly_byte_limit":1.5}""",
        """{"capped_reason":7}""",
    )
    for (text in invalid) {
        expect(DataCap.parse(text) == null && !isEmbedNotEnabled(text)) { "invalid cap object $text parsed" }
    }
    // the Embed-not-enabled refusal is no cap object, and is recognized
    val refusal = """{"error":{"message":"Embed isn't enabled for this network."}}"""
    expect(DataCap.parse(refusal) == null && isEmbedNotEnabled(refusal)) { "the Embed-not-enabled refusal is misread" }
}

/** An unsigned JWT with the given claims: the app checks the shape and the client_id claim. */
private fun jwt(vararg claims: Pair<String, String>): String =
    "eyJhbGciOiJub25lIn0." +
        Base64.getUrlEncoder().withoutPadding().encodeToString(
            JsonObject(claims.associate { (name, value) -> name to JsonPrimitive(value) }).toString().toByteArray(Charsets.UTF_8),
        ) +
        ".c2lnbmF0dXJl"

/** The client_id claim: accepted for a client JWT, refused otherwise. */
private fun checkClientJwtClaims() {
    expect(parseClientJwtClientId(jwt("client_id" to testClientId.uppercase())) == testClientId) {
        "a client JWT is not accepted in canonical form"
    }
    // a network JWT has no client_id claim
    expectThrows<ConfigException>("a network JWT is accepted") { parseClientJwtClientId(jwt("network_id" to otherClientId)) }
    for (malformed in listOf("abc", "a.b", "a..c", "a.!!!.c", "")) {
        expectThrows<ConfigException>("the malformed token \"$malformed\" is accepted") { parseClientJwtClientId(malformed) }
    }
    expectThrows<ConfigException>("an invalid client_id claim is accepted") { parseClientJwtClientId(jwt("client_id" to "not-a-uuid")) }
}

/** HTTPS origins and loopback HTTP only. */
private fun checkOrigins() {
    val valid = listOf(
        "https://api.bringyour.com" to "https://api.bringyour.com/",
        "https://example.com/" to "https://example.com/",
        "http://127.0.0.1:8790" to "http://127.0.0.1:8790/",
        "http://localhost:8790" to "http://localhost:8790/",
        "http://[::1]:8790" to "http://[::1]:8790/",
    )
    for ((url, want) in valid) {
        val origin = parseOrigin(url, "URL").toString()
        expect(origin == want) { "origin $url reads as $origin, want $want" }
    }
    for (url in listOf(
        "http://example.com", "https://example.com/path", "https://example.com/?x=1", "https://example.com/#f",
        "https://user:pw@example.com", "ftp://example.com", "example.com", "",
    )) {
        expectThrows<ConfigException>("the origin $url is accepted") { parseOrigin(url, "URL") }
    }
}

/** A stand-in HTTP server: it records the request and answers with a fixed status and body. */
private class StandIn(private val status: Int, private val body: String, private val unreachable: Boolean = false) : HttpTransport {
    var method = ""
    var uri = ""
    var bearer = ""
    var jsonBody: String? = null

    override fun send(method: String, uri: URI, bearer: String, jsonBody: String?): HttpAnswer {
        this.method = method
        this.uri = uri.toString()
        this.bearer = bearer
        this.jsonBody = jsonBody
        if (unreachable) {
            throw ConnectException("connection refused")
        }
        return HttpAnswer(status, body)
    }
}

/** fetchClientJwt against a stand-in server. */
private fun fetch(standIn: StandIn, stateDir: Path): ClientCredential =
    fetchClientJwt(testTokenServer, testDemoSession, testInstanceId, stateDir, standIn)

/** The token fetch against a stand-in token server. */
private fun checkTokenFetch() {
    val stateDir = newPrivateDir()
    try {
        val token = jwt("client_id" to testClientId)
        val clientJwtPath = stateDir.resolve(clientJwtFileName)
        val ok = StandIn(
            200,
            """{"client_id":"$testClientId","by_client_jwt":"$token",""" +
                """"data_cap":{"client_id":"$testClientId","monthly_byte_limit":10000000000,"monthly_used_byte_count":1,"capped":false,"capped_reason":""}}""",
        )
        val credential = fetch(ok, stateDir)
        expect(ok.method == "POST" && ok.uri == "http://127.0.0.1:8790/urnetwork/client-token") { "the token request is ${ok.method} ${ok.uri}" }
        expect(ok.bearer == testDemoSession) { "the token request does not carry the demo session" }
        expect(parseNullableJsonObject(ok.jsonBody)?.stringMember("installation_id") == testInstanceId) {
            "the token request does not carry the instance id"
        }
        expect(credential.clientId == testClientId && credential.clientJwt == token && credential.firstCap?.monthlyByteLimit == 10000000000L) {
            "the token answer is not read"
        }
        expect(Files.readString(clientJwtPath) == "$token\n") { "client.jwt is not saved" }
        expectPrivateFile(clientJwtPath)
        expect(entryCount(stateDir) == 1L) { "the token save leaves a temporary file" }

        // a client_id that does not match the claim is refused, and the saved
        // client.jwt stays as it was
        val mismatched = StandIn(200, """{"client_id":"$otherClientId","by_client_jwt":"$token","data_cap":null}""")
        val refused = expectThrows<TokenFetchException>("a mismatched client_id is accepted") { fetch(mismatched, stateDir) }
        expect(refused.exitCode == 1 && refused.guiStatus == "stopped") { "a mismatched client_id is not a failure" }
        expect(Files.readString(clientJwtPath) == "$token\n") { "a refused answer replaced client.jwt" }

        data class Answer(val status: Int, val body: String, val exit: Int, val gui: String, val says: String)
        val answers = listOf(
            Answer(401, """{"error":{"code":"unauthorized","message":"unknown session"}}""", 78, "signed out", "unknown session"),
            Answer(409, """{"error":{"code":"installation_limit","message":"too many installations"}}""", 78, "signed out", "too many installations"),
            Answer(409, """{"error":{"code":"client_limit","message":"your network is at its client limit; see https://ur.io/services"}}""", 78, "signed out", "https://ur.io/services"),
            Answer(401, "", 78, "signed out", "refused"),
            Answer(500, """{"error":{"code":"upstream","message":"upstream failed"}}""", 1, "stopped", "HTTP 500"),
            Answer(503, """{"error":{"code":"busy","message":"retry"}}""", 1, "stopped", "HTTP 503"),
            Answer(400, """{"error":{"code":"invalid_request","message":"bad"}}""", 1, "stopped", "HTTP 400"),
            Answer(404, "404 page not found", 1, "stopped", "HTTP 404"),
            Answer(405, """{"error":{"code":"method_not_allowed","message":"use POST"}}""", 1, "stopped", "HTTP 405"),
            Answer(500, """{"error":{"code":"internal","message":"internal error"}}""", 1, "stopped", "HTTP 500"),
            Answer(502, """{"error":{"code":"upstream","message":"upstream failed"}}""", 1, "stopped", "HTTP 502"),
            Answer(200, "not json", 1, "stopped", "invalid"),
            Answer(200, """{"client_id":"$testClientId","by_client_jwt":"${jwt("network_id" to testClientId)}"}""", 1, "stopped", "client_id"),
        )
        for (a in answers) {
            val e = expectThrows<TokenFetchException>("the answer ${a.status} ${a.body} is accepted") { fetch(StandIn(a.status, a.body), stateDir) }
            expect(e.exitCode == a.exit && e.guiStatus == a.gui && e.message.orEmpty().contains(a.says)) {
                "the answer ${a.status} maps to exit ${e.exitCode} \"${e.guiStatus}\" (${e.message})"
            }
        }
        val unreachable = expectThrows<TokenFetchException>("an unreachable token server is accepted") { fetch(StandIn(200, "", true), stateDir) }
        expect(unreachable.exitCode == 1 && unreachable.guiStatus == "stopped") { "an unreachable token server is not a failure" }
        expect(!refused.message.orEmpty().contains(testDemoSession) && !unreachable.message.orEmpty().contains(testDemoSession)) {
            "an error message shows the demo session"
        }
    } finally {
        deleteTree(stateDir)
    }
}

/** The cap read presents the client JWT and treats any failure as no reading; the Embed-not-enabled refusal is marked. */
private fun checkCapRead() {
    val token = jwt("client_id" to testClientId)
    val ok = StandIn(200, """{"monthly_byte_limit":5,"capped":false,"capped_reason":""}""")
    val read = readCap(testApiOrigin, token, ok)
    expect(read.cap?.monthlyByteLimit == 5L && !read.embedNotEnabled) { "a cap answer is not read" }
    expect(ok.method == "GET" && ok.uri == "https://api.bringyour.com/network/client-data-cap" && ok.bearer == token && ok.jsonBody == null) {
        "the cap read is ${ok.method} ${ok.uri}"
    }
    expect(readCap(testApiOrigin, token, StandIn(404, "404 page not found")) == CapRead(null)) { "a server without the cap routes is a reading" }
    expect(readCap(testApiOrigin, token, StandIn(200, """{"error":{"message":"denied"}}""")) == CapRead(null)) { "an error answer is a reading" }
    expect(readCap(testApiOrigin, token, StandIn(200, "", true)) == CapRead(null)) { "an unreachable API is a reading" }
    expect(
        readCap(testApiOrigin, token, StandIn(200, """{"error":{"message":"Embed isn't enabled for this network."}}""")) ==
            CapRead(null, embedNotEnabled = true),
    ) { "the Embed-not-enabled refusal is not marked" }
}

/** The state directory: private files, atomic replacement, one instance id, no symlinks. */
private fun checkStateFiles() {
    val stateDir = newPrivateDir()
    try {
        val instanceId = loadOrCreateInstanceId(stateDir)
        expect(instanceId == parseId(instanceId)) { "instance-id is not a canonical UUID" }
        expect(loadOrCreateInstanceId(stateDir) == instanceId) { "instance-id is not reused" }
        expectPrivateFile(stateDir.resolve(instanceIdFileName))

        val path = stateDir.resolve(clientJwtFileName)
        writePrivateFile(path, "one".toByteArray(Charsets.UTF_8))
        writePrivateFile(path, "two".toByteArray(Charsets.UTF_8))
        expect(Files.readString(path) == "two") { "a state file is not replaced" }
        expect(entryCount(stateDir) == 2L) { "a replacement leaves a temporary file" }

        if (hasPosixPermissions(stateDir)) {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-r--r--"))
            expectThrows<IOException>("a shared file is read") { readPrivateFile(path) }
            Files.setPosixFilePermissions(path, ownerReadWrite)
            Files.setPosixFilePermissions(stateDir, PosixFilePermissions.fromString("rwxr-xr-x"))
            expectThrows<ConfigException>("a shared directory is accepted") { checkStateDir(stateDir.toString()) }
            Files.setPosixFilePermissions(stateDir, ownerAll)

            val target = stateDir.resolve("target")
            writePrivateFile(target, jwt("client_id" to testClientId).toByteArray(Charsets.UTF_8))
            Files.delete(path)
            Files.createSymbolicLink(path, target)
            expectThrows<IOException>("a symlinked file is read") { readPrivateFile(path) }
            expectThrows<ConfigException>("a symlinked client.jwt is not a configuration error") { readClientJwt(stateDir) }
        }
    } finally {
        deleteTree(stateDir)
    }
}

/** The configuration errors, all exit 78. */
private fun checkSettings() {
    val stateDir = newPrivateDir()
    try {
        expectThrows<ConfigException>("a missing state directory is accepted") { loadSettings(emptyMap()) }
        expectThrows<ConfigException>("a relative state directory is accepted") {
            loadSettings(mapOf("URNETWORK_EMBED_STATE_DIR" to "relative/state"))
        }
        expectThrows<ConfigException>("a state directory that does not exist is accepted") {
            loadSettings(mapOf("URNETWORK_EMBED_STATE_DIR" to stateDir.resolve("missing").toString()))
        }
        val env = mutableMapOf("URNETWORK_EMBED_STATE_DIR" to stateDir.toString(), "URNETWORK_TOKEN_SERVER_URL" to testTokenServer.toString())
        expectThrows<ConfigException>("a token server without a demo session is accepted") { loadSettings(env) }
        env["URNETWORK_DEMO_SESSION"] = "has space"
        expectThrows<ConfigException>("a demo session with whitespace is accepted") { loadSettings(env) }
        env["URNETWORK_DEMO_SESSION"] = testDemoSession
        val withTokenServer = loadSettings(env)
        expect(
            withTokenServer.tokenServer == testTokenServer && withTokenServer.demoSession == testDemoSession &&
                withTokenServer.apiOrigin.toString() == "https://api.bringyour.com/" && !withTokenServer.toString().contains(testDemoSession),
        ) { "the token server settings are not read" }
        env.remove("URNETWORK_TOKEN_SERVER_URL")
        expectThrows<ConfigException>("a demo session without a token server is accepted") { loadSettings(env) }
        env.remove("URNETWORK_DEMO_SESSION")
        env["URNETWORK_API_URL"] = "http://api.example.com"
        expectThrows<ConfigException>("a plain HTTP API origin is accepted") { loadSettings(env) }
        env.remove("URNETWORK_API_URL")

        // no token server and no client.jwt
        val withoutTokenServer = loadSettings(env)
        expectThrows<ConfigException>("no token server and no client.jwt is accepted") { obtainClientJwt(withoutTokenServer, null) }
        // a client.jwt from the backend tool
        saveClientJwt(stateDir, jwt("client_id" to testClientId))
        val credential = obtainClientJwt(withoutTokenServer, null)
        expect(credential.clientId == testClientId && credential.firstCap == null && !credential.toString().contains(credential.clientJwt)) {
            "client.jwt is not used"
        }
        // a network JWT in client.jwt
        saveClientJwt(stateDir, jwt("network_id" to testClientId))
        expectThrows<ConfigException>("a network JWT in client.jwt is accepted") { obtainClientJwt(withoutTokenServer, null) }
    } finally {
        deleteTree(stateDir)
    }
}

/** The start line and the kind of app whose licenses --licenses prints. */
private fun checkStartLine() {
    expect(startLine(testClientId, testInstanceId) == "embed client $testClientId, installation $testInstanceId") {
        "the start line is not the contract's"
    }
    expect(
        licenseApp("Windows 11") == "windows" && licenseApp("Mac OS X") == "apple" && licenseApp("Linux") == "linux" &&
            licenseApp("FreeBSD") == "linux" && licenseApp(null) == "linux",
    ) { "--licenses asks for the wrong kind of app" }
    expect(usage.contains("--licenses")) { "the usage does not name --licenses" }
}

/** An unknown command is a usage error, exit 78. */
private fun checkUsageExitCode() {
    val out = ByteArrayOutputStream()
    val err = ByteArrayOutputStream()
    val exitCode = runCommand(listOf("--bogus"), emptyMap(), PrintStream(out, true, Charsets.UTF_8), PrintStream(err, true, Charsets.UTF_8))
    expect(exitCode == exitConfig) { "a usage error does not exit 78" }
    expect(err.toString(Charsets.UTF_8).contains(usage)) { "a usage error does not print the usage" }
}

/** A new private temporary directory (0700 where the file system has POSIX permissions). */
private fun newPrivateDir(): Path {
    val dir = Files.createTempDirectory("ur-embed-selftest-")
    if (hasPosixPermissions(dir)) {
        Files.setPosixFilePermissions(dir, ownerAll)
    }
    return dir
}

/** A file is private to its owner where the file system has POSIX permissions. */
private fun expectPrivateFile(path: Path) {
    if (hasPosixPermissions(path)) {
        expect(Files.getPosixFilePermissions(path).none { it in groupAndOthers }) { "${path.fileName} is not private" }
    }
}

/** The number of entries in a directory. */
private fun entryCount(dir: Path): Long = Files.list(dir).use { it.count() }

/** Deletes a directory tree. */
private fun deleteTree(dir: Path) {
    Files.walk(dir).use { paths -> paths.sorted(Comparator.reverseOrder()).toList() }.forEach { Files.deleteIfExists(it) }
}
