// The backend tool's credential-free self-test (EMBED_CONTRACT.md, "Backend
// tools"). It runs the commands against a stand-in URnetwork API in a
// temporary private directory: no credentials, no network. The data is
// synthetic.
import com.fasterxml.jackson.databind.JsonNode
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.URLDecoder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.util.Base64

private const val testRoot = "selftest-root-credential-not-a-secret"
private val allOutput = StringBuilder()

// every client JWT any stand-in issued, to check that none is printed
private val allIssuedJwts = ArrayList<String>()
private val groupAndOthers = setOf(
    PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_WRITE, PosixFilePermission.GROUP_EXECUTE,
    PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_WRITE, PosixFilePermission.OTHERS_EXECUTE,
)

/** A stand-in URnetwork API that keeps the clients it issued. */
private class StandInApi : Transport {
    val requests = ArrayList<ApiRequest>()
    val issuedJwts = ArrayList<String>()

    // clients whose reissue and removal answer "Client does not exist."
    val missing = HashSet<String>()

    // the client-data-caps pages by cursor ("" for the first page)
    val pages = HashMap<String, String>()

    // when set, a new client is refused with this client limit flag
    var limitFlag: String? = null

    // when set, every request answers with this HTTP status
    var status: Int? = null

    // when set, a new client's JWT claims this client id instead of its own
    var claimOverride: String? = null

    // when set, the cap routes answer this body
    var capAnswer: String? = null

    // the ACL group each client was set to
    val aclGroups = HashMap<String, String>()

    // how many ACL requests still fail with HTTP 500
    var aclFailures = 0

    // when set, the ACL route answers 404, as a server without ACL groups does
    var aclUnsupported = false

    // when set, the ACL route answers the other group
    var aclMismatch = false
    private var nextClient = 1
    private var generation = 0

    companion object {
        /** A client id the stand-in issues. */
        fun id(n: Int) = "%08x-0000-4000-8000-%012x".format(n, n)

        /** An unsigned JWT with the client_id claim; the tool checks the claim only. */
        fun jwt(clientId: String, generation: Int): String {
            val claims = json.createObjectNode().put("client_id", clientId).put("generation", generation)
            val payload = Base64.getUrlEncoder().withoutPadding().encodeToString(claims.toString().toByteArray(Charsets.UTF_8))
            return "eyJhbGciOiJub25lIn0.$payload.c2lnbmF0dXJl"
        }

        /** A cap object for a client. */
        fun cap(clientId: String): String =
            json.createObjectNode()
                .put("client_id", clientId)
                .put("monthly_byte_limit", 10_000_000_000L)
                .put("monthly_used_byte_count", 0)
                .putNull("total_byte_limit")
                .put("total_used_byte_count", 0)
                .put("capped", false)
                .put("capped_reason", "")
                .toString()
    }

    override fun send(request: ApiRequest): ApiResponse {
        requests.add(request)
        status?.let { return ApiResponse(it, "{}") }
        val body = request.body?.let { json.readTree(it) } ?: json.createObjectNode()
        val path = request.pathAndQuery
        val capPrefix = "$capPath?client_id="
        val capsPrefix = "$capsPath?limit=1000"
        return when {
            request.method == "POST" && path == authClientPath -> {
                val clientId = body.path("client_id").textValue()
                when {
                    clientId != null && clientId in missing -> ApiResponse(200, """{"error":{"message":"Client does not exist."}}""")
                    clientId != null -> issue(clientId, clientId)
                    limitFlag != null -> ApiResponse(200, """{"error":{"$limitFlag":true,"message":"Client limit exceeded."}}""")
                    else -> {
                        val id = id(nextClient++)
                        issue(id, claimOverride ?: id)
                    }
                }
            }
            request.method == "POST" && path == capPath -> ApiResponse(200, capAnswer ?: cap(body.path("client_id").asText()))
            request.method == "GET" && path.startsWith(capPrefix) ->
                ApiResponse(200, capAnswer ?: cap(URLDecoder.decode(path.removePrefix(capPrefix), Charsets.UTF_8)))
            request.method == "GET" && path.startsWith(capsPrefix) -> {
                val rest = path.removePrefix(capsPrefix)
                val cursor = if (rest.startsWith("&cursor=")) URLDecoder.decode(rest.removePrefix("&cursor="), Charsets.UTF_8) else ""
                ApiResponse(200, pages[cursor] ?: """{"clients":[],"next_cursor":null}""")
            }
            request.method == "POST" && path == aclPath && !aclUnsupported -> {
                val id = body.path("client_id").asText()
                val group = body.path("acl_group").asText()
                when {
                    0 < aclFailures -> {
                        aclFailures--
                        ApiResponse(500, "{}")
                    }
                    id in missing -> ApiResponse(200, """{"error":{"message":"Client does not exist."}}""")
                    group !in setOf("default", "isolated") -> ApiResponse(200, """{"error":{"message":"Invalid ACL group."}}""")
                    else -> {
                        aclGroups[id] = group
                        val answered = if (aclMismatch) (if (group == "default") "isolated" else "default") else group
                        ApiResponse(200, json.createObjectNode().put("client_id", id).put("acl_group", answered).toString())
                    }
                }
            }
            request.method == "POST" && path == removePath -> {
                val id = body.path("client_id").asText()
                ApiResponse(200, if (id in missing) """{"error":{"message":"Client does not exist."}}""" else "{}")
            }
            else -> ApiResponse(404, "404 page not found")
        }
    }

    /** The ACL requests this stand-in answered. */
    fun aclRequests() = requests.filter { it.pathAndQuery == aclPath }

    private fun issue(clientId: String, claim: String): ApiResponse {
        val token = jwt(claim, ++generation)
        issuedJwts.add(token)
        allIssuedJwts.add(token)
        return ApiResponse(200, json.createObjectNode().put("client_id", clientId).put("by_client_jwt", token).toString())
    }
}

/** The exit code, stdout and stderr of one run. */
private data class Run(val exit: Int, val out: String, val err: String)

/** Runs every check in order and stops at the first failure. */
fun selfTest() {
    val dir = Files.createTempDirectory("ur-embed-server-")
    try {
        if (posix(dir)) {
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"))
        }
        checkProvision(dir)
        checkArguments(dir)
        checkCap(dir)
        checkUsageAll()
        checkRemove(dir)
        checkMap(dir)
        checkAclGroups(dir)
        checkFailureTexts(dir)
        checkNothingSecretPrinted()
    } finally {
        Files.walk(dir).use { paths -> paths.sorted(Comparator.reverseOrder()).toList() }.forEach { Files.deleteIfExists(it) }
    }
}

private fun expect(condition: Boolean, reason: () -> String) {
    if (!condition) {
        throw IllegalStateException(reason())
    }
}

/**
 * The settings of a run with a map in dir and a default ACL group; null leaves it unset. The checks
 * that do not test ACL groups keep new clients in "default", which sends no ACL request.
 */
private fun env(dir: Path, map: String, aclGroup: String? = "default"): (String) -> String? {
    val settings = mapOf(
        "URNETWORK_ROOT_JWT" to testRoot,
        "URNETWORK_CLIENT_MAP" to dir.resolve(map).toString(),
        "URNETWORK_API_URL" to "http://127.0.0.1:1234",
        "URNETWORK_DEFAULT_ACL_GROUP" to aclGroup,
    )
    return { settings[it] }
}

/** Runs one command against api. */
private fun exec(api: StandInApi, env: (String) -> String?, vararg args: String): Run {
    val out = ByteArrayOutputStream()
    val err = ByteArrayOutputStream()
    val exit = runCommand(args.toList(), env, PrintStream(out, true, Charsets.UTF_8), PrintStream(err, true, Charsets.UTF_8)) { _, _ -> api }
    val run = Run(exit, out.toString(Charsets.UTF_8), err.toString(Charsets.UTF_8))
    allOutput.append(run.out).append(run.err)
    return run
}

/** The JSON body of a recorded request. */
private fun body(request: ApiRequest): JsonNode = json.readTree(request.body)

/** Whether a file is private to its owner (always true without POSIX permissions). */
private fun isPrivate(path: Path) = !posix(path) || Files.getPosixFilePermissions(path).none { it in groupAndOthers }

/** Whether two JSON texts hold the same value; member order does not matter. */
private fun sameJson(a: String, b: String) = json.readTree(a) == json.readTree(b)

/** New versus reissue, the re-provision of a missing client, the client limit and the checks. */
private fun checkProvision(dir: Path) {
    val api = StandInApi()
    val env = env(dir, "clients.json")
    val jwtFile = dir.resolve("alice.jwt")
    val key = "user:alice:22222222-2222-2222-2222-222222222222"
    val id = StandInApi.id(1)

    var run = exec(api, env, "provision", key, jwtFile.toString())
    expect(run.exit == 0 && run.out.trim() == """{"client_id":"$id"}""") { "provision printed ${run.out}${run.err}" }
    val created = body(api.requests[0])
    expect(
        created.path("description").asText() == "embed client" &&
            created.path("device_spec").asText() == "urnetwork-examples/kotlin-embed-server" &&
            !created.has("client_id") && !created.has("source_client_id"),
    ) { "a new client request has the wrong fields" }
    expect(Files.readString(jwtFile) == "${api.issuedJwts[0]}\n" && isPrivate(jwtFile)) { "the client JWT file is not written privately" }
    expect(loadMap(dir.resolve("clients.json"))["clients"].path(key).asText() == id && isPrivate(dir.resolve("clients.json"))) {
        "the map is not saved privately"
    }

    run = exec(api, env, "provision", key, jwtFile.toString())
    val reissued = body(api.requests[1])
    expect(run.exit == 0 && reissued.path("client_id").asText() == id && !reissued.has("source_client_id")) { "a mapped key is not reissued" }
    expect(Files.readString(jwtFile) == "${api.issuedJwts[1]}\n") { "a reissue does not replace the client JWT file" }

    // a client deactivated after 30 days without connecting
    api.missing.add(id)
    run = exec(api, env, "provision", key, jwtFile.toString())
    expect(run.exit == 0 && body(api.requests[2]).path("client_id").asText() == id && !body(api.requests[3]).has("client_id")) {
        "a missing client is not re-provisioned"
    }
    expect(loadMap(dir.resolve("clients.json"))["clients"].path(key).asText() == StandInApi.id(2)) { "the re-provisioned client is not mapped" }

    for (flag in listOf("client_limit_exceeded", "upgrade_required")) {
        val limited = StandInApi().apply { limitFlag = flag }
        run = exec(limited, env, "provision", "user:bob", dir.resolve("bob.jwt").toString())
        expect(run.exit == 78 && run.err.trim() == clientLimitMessage) { "the client limit ($flag) exits ${run.exit}: ${run.err}" }
    }
    expect(exec(StandInApi().apply { claimOverride = StandInApi.id(9) }, env, "provision", "user:carol", dir.resolve("carol.jwt").toString()).exit == 1) {
        "a client JWT for another client is accepted"
    }
    expect(exec(StandInApi().apply { status = 401 }, env, "provision", "user:carol", dir.resolve("carol.jwt").toString()).exit == 78) {
        "a refused root credential does not exit 78"
    }
    expect(exec(StandInApi().apply { status = 500 }, env, "provision", "user:carol", dir.resolve("carol.jwt").toString()).exit == 1) {
        "an API failure does not exit 1"
    }
}

/** Key, argument and setting rejection, all exit 78. */
private fun checkArguments(dir: Path) {
    val api = StandInApi()
    val env = env(dir, "clients.json")
    val jwtFile = dir.resolve("x.jwt").toString()
    val usages = listOf(
        listOf(), listOf("provision"), listOf("provision", "user:a"), listOf("provision", "user:a", jwtFile, "extra"),
        listOf("provision", "alice", jwtFile), listOf("provision", "user:../a", jwtFile),
        listOf("provision", StandInApi.id(1), jwtFile), listOf("provision", "user:a", dir.resolve("missing").resolve("x.jwt").toString()),
        listOf("usage"), listOf("remove", "user:a", "extra"), listOf("usage-all", "extra"), listOf("unknown"), listOf("--self-test", "extra"),
        listOf("cap", "user:a"), listOf("cap", "user:a", "--monthly", "1GB"), listOf("cap", "user:a", "--monthly", "-1"),
        listOf("cap", "user:a", "--monthly", "9223372036854775808"), listOf("cap", "user:a", "--monthly", "1", "--monthly", "2"),
        listOf("cap", "user:a", "--foo"), listOf("cap", "user:a", "--monthly"), listOf("cap", "user:a", "--reset-total", "--reset-total"),
        listOf("acl"), listOf("acl", "user:a"), listOf("acl", "user:a", "other"), listOf("acl", "user:a", "Default"),
        listOf("acl", "user:a", "default", "extra"), listOf("acl", "alice", "default"),
    )
    for (args in usages) {
        expect(exec(api, env, *args.toTypedArray()).exit == 78) { "the arguments ${args.joinToString(" ")} do not exit 78" }
    }
    expect(api.requests.isEmpty()) { "a rejected command reached the API" }
    val invalid = listOf(
        mapOf("URNETWORK_CLIENT_MAP" to dir.resolve("clients.json").toString()),
        mapOf("URNETWORK_ROOT_JWT" to testRoot),
        mapOf("URNETWORK_ROOT_JWT" to testRoot, "URNETWORK_CLIENT_MAP" to "clients.json"),
        mapOf(
            "URNETWORK_ROOT_JWT" to testRoot, "URNETWORK_CLIENT_MAP" to dir.resolve("clients.json").toString(),
            "URNETWORK_API_URL" to "http://api.example.com",
        ),
        mapOf(
            "URNETWORK_ROOT_JWT" to testRoot, "URNETWORK_CLIENT_MAP" to dir.resolve("clients.json").toString(),
            "URNETWORK_DEFAULT_ACL_GROUP" to "private",
        ),
    )
    for (settings in invalid) {
        expect(exec(api, { settings[it] }, "provision", "user:a", jwtFile).exit == 78) { "missing or invalid settings do not exit 78" }
    }
    expect(exec(api, env, "usage", "user:never-provisioned").exit == 78) { "an unmapped key does not exit 78" }
}

/** The merge request bodies and the cap object. */
private fun checkCap(dir: Path) {
    val api = StandInApi()
    val env = env(dir, "caps.json")
    exec(api, env, "provision", "user:dana", dir.resolve("dana.jwt").toString())
    val id = StandInApi.id(1)
    val cases = listOf(
        listOf("--monthly", "10000000000") to """{"client_id":"$id","monthly_byte_limit":10000000000}""",
        listOf("--total", "null") to """{"client_id":"$id","total_byte_limit":null}""",
        listOf("--monthly", "0") to """{"client_id":"$id","monthly_byte_limit":0}""",
        listOf("--reset-total") to """{"client_id":"$id","reset_total":true}""",
        listOf("--reset-total", "--total", "5", "--monthly", "null") to
            """{"client_id":"$id","monthly_byte_limit":null,"total_byte_limit":5,"reset_total":true}""",
    )
    for ((options, want) in cases) {
        val run = exec(api, env, "cap", "user:dana", *options.toTypedArray())
        val last = api.requests.last()
        expect(run.exit == 0 && last.method == "POST" && last.pathAndQuery == capPath) { "cap $options exits ${run.exit}: ${run.err}" }
        expect(sameJson(last.body!!, want)) { "cap $options sends ${last.body}, want $want" }
        expect(sameJson(run.out, StandInApi.cap(id))) { "cap does not print the cap object" }
    }
    val usageRun = exec(api, env, "usage", "user:dana")
    val last = api.requests.last()
    expect(
        usageRun.exit == 0 && last.method == "GET" && last.pathAndQuery == "$capPath?client_id=$id" &&
            sameJson(usageRun.out, StandInApi.cap(id)),
    ) { "usage does not read the cap object with the root credential" }
    api.capAnswer = """{"error":{"message":"Client not in network."}}"""
    expect(exec(api, env, "cap", "user:dana", "--monthly", "1").exit == 1) { "a refused cap does not exit 1" }
    expect(exec(StandInApi().apply { status = 404 }, env, "usage-all").exit == 1) { "a server without the cap routes does not exit 1" }
}

/** A page of cap objects. */
private fun page(first: Int, count: Int, next: String?): String {
    val page = json.createObjectNode()
    val clients = page.putArray("clients")
    for (i in first until first + count) {
        clients.add(json.readTree(StandInApi.cap(StandInApi.id(i))))
    }
    if (next == null) page.putNull("next_cursor") else page.put("next_cursor", next)
    return page.toString()
}

/** usage-all pages 1000 at a time and stops at a null or repeated cursor. */
private fun checkUsageAll() {
    // no map is needed: usage-all lists the network's capped clients
    val noMap = mapOf("URNETWORK_ROOT_JWT" to testRoot)
    val api = StandInApi()
    api.pages[""] = page(1, 2, "c1")
    api.pages["c1"] = page(3, 1, "c/2")
    api.pages["c/2"] = page(4, 1, null)
    var run = exec(api, { noMap[it] }, "usage-all")
    expect(run.exit == 0 && run.out.trim().lines().size == 4) { "usage-all printed ${run.out}${run.err}" }
    val paths = api.requests.map { it.pathAndQuery }
    expect(paths == listOf("$capsPath?limit=1000", "$capsPath?limit=1000&cursor=c1", "$capsPath?limit=1000&cursor=c%2F2")) {
        "usage-all does not page with the cursor: $paths"
    }
    val repeating = StandInApi()
    repeating.pages[""] = page(1, 1, "c1")
    repeating.pages["c1"] = page(2, 1, "c1")
    run = exec(repeating, { noMap[it] }, "usage-all")
    expect(run.exit == 0 && repeating.requests.size == 2) { "usage-all does not stop on a repeated cursor" }
}

/** remove drops the mapping for both answers. */
private fun checkRemove(dir: Path) {
    val api = StandInApi()
    val env = env(dir, "remove.json")
    exec(api, env, "provision", "user:erin", dir.resolve("erin.jwt").toString())
    exec(api, env, "provision", "user:frank", dir.resolve("frank.jwt").toString())
    val run = exec(api, env, "remove", "user:erin")
    expect(
        run.exit == 0 && run.out.trim() == """{"removed":"${StandInApi.id(1)}"}""" &&
            body(api.requests.last()).path("client_id").asText() == StandInApi.id(1),
    ) { "remove printed ${run.out}${run.err}" }
    api.missing.add(StandInApi.id(2))
    expect(exec(api, env, "remove", "user:frank").exit == 0) { "removing a missing client fails" }
    expect(loadMap(dir.resolve("remove.json"))["clients"].isEmpty) { "remove keeps a mapping" }
    expect(exec(api, env, "remove", "user:erin").exit == 78) { "removing an unmapped key does not exit 78" }
}

/** A map with unknown fields is refused untouched, and a held lock fails. */
private fun checkMap(dir: Path) {
    val map = dir.resolve("token-server.json")
    val text = """{"version":1,"clients":{},"pending_caps":[]}"""
    writePrivate(map, text.toByteArray(Charsets.UTF_8))
    val api = StandInApi()
    expect(exec(api, env(dir, "token-server.json"), "provision", "user:gina", dir.resolve("gina.jwt").toString()).exit == 78) {
        "a map with unknown fields is accepted"
    }
    expect(Files.readString(map) == text && api.requests.isEmpty()) { "a refused map is rewritten" }

    val locked = dir.resolve("locked.json")
    Files.createDirectory(Path.of("$locked.lock"))
    expect(exec(api, env(dir, "locked.json"), "provision", "user:hana", dir.resolve("hana.jwt").toString()).exit == 1) {
        "a held lock does not exit 1"
    }
    // the C# tool's lock is a file at the same path
    val fileLocked = dir.resolve("file-locked.json")
    Files.createFile(Path.of("$fileLocked.lock"))
    val run = exec(api, env(dir, "file-locked.json"), "provision", "user:hana", dir.resolve("hana.jwt").toString())
    expect(run.exit == 1 && run.err.contains("lock") && Files.isRegularFile(Path.of("$fileLocked.lock"))) {
        "a held file lock does not exit 1 untouched"
    }
}

/** The keys a map file records in pending_acl. */
private fun pendingAcl(dir: Path, map: String): List<String> = loadMap(dir.resolve(map)).path("pending_acl").map { it.asText() }

/**
 * The default ACL group: applied to a new client only, with pending_acl set before and cleared
 * after, kept and retried after a failure, kept on a server without ACL groups, and settled by acl;
 * the acl command.
 */
private fun checkAclGroups(dir: Path) {
    val api = StandInApi()
    val isolated = env(dir, "acl.json", aclGroup = null)
    val jwtFile = dir.resolve("ivan.jwt")
    val id = StandInApi.id(1)

    // unset means isolated: one ACL request after the new client, then no record
    var run = exec(api, isolated, "provision", "user:ivan", jwtFile.toString())
    expect(
        run.exit == 0 && api.requests.size == 2 && api.requests[1].pathAndQuery == aclPath &&
            sameJson(api.requests[1].body!!, """{"client_id":"$id","acl_group":"isolated"}"""),
    ) { "a new client is not put in isolated: ${run.err}" }
    expect(
        api.aclGroups[id] == "isolated" && pendingAcl(dir, "acl.json").isEmpty() &&
            !Files.readString(dir.resolve("acl.json")).contains("pending_acl") &&
            Files.readString(jwtFile) == "${api.issuedJwts[0]}\n",
    ) { "an applied group leaves its record or no client JWT" }
    run = exec(api, isolated, "provision", "user:ivan", jwtFile.toString())
    expect(run.exit == 0 && api.aclRequests().size == 1) { "a reissue sends an ACL request" }

    // a failed request keeps the record and writes no client JWT; the next issue retries
    api.aclFailures = 1
    val judyJwt = dir.resolve("judy.jwt")
    run = exec(api, isolated, "provision", "user:judy", judyJwt.toString())
    expect(
        run.exit == 1 && !Files.exists(judyJwt) && pendingAcl(dir, "acl.json") == listOf("user:judy") &&
            loadMap(dir.resolve("acl.json"))["clients"].path("user:judy").asText() == StandInApi.id(2),
    ) { "a failed ACL request does not keep the record: ${run.exit} ${run.err}" }
    run = exec(api, isolated, "provision", "user:judy", judyJwt.toString())
    expect(
        run.exit == 0 && api.aclGroups[StandInApi.id(2)] == "isolated" && pendingAcl(dir, "acl.json").isEmpty() && Files.exists(judyJwt),
    ) { "a pending group is not applied on the next issue" }

    // a server without ACL groups: exit 1 with the record kept; a default of default then provisions
    val older = StandInApi().apply { aclUnsupported = true }
    val kimJwt = dir.resolve("kim.jwt")
    run = exec(older, env(dir, "older.json", aclGroup = "isolated"), "provision", "user:kim", kimJwt.toString())
    expect(
        run.exit == 1 && run.err.trim() == aclUnsupportedMessage && !Files.exists(kimJwt) && pendingAcl(dir, "older.json") == listOf("user:kim"),
    ) { "a server without ACL groups answers ${run.exit}: ${run.err}" }
    run = exec(older, env(dir, "older.json"), "provision", "user:kim", kimJwt.toString())
    expect(run.exit == 0 && Files.exists(kimJwt) && pendingAcl(dir, "older.json").isEmpty() && older.aclRequests().size == 1) {
        "a default of default does not settle the record without a request"
    }
    run = exec(older, env(dir, "older.json"), "provision", "user:leo", dir.resolve("leo.jwt").toString())
    expect(run.exit == 0 && older.aclRequests().size == 1 && pendingAcl(dir, "older.json").isEmpty()) {
        "a default of default sends an ACL request"
    }

    // the acl command: request body, printed answer, and refusals
    run = exec(api, isolated, "acl", "user:ivan", "default")
    val answer = """{"client_id":"$id","acl_group":"default"}"""
    expect(run.exit == 0 && run.out.trim() == answer && sameJson(api.requests.last().body!!, answer) && api.aclGroups[id] == "default") {
        "acl printed ${run.out}${run.err}"
    }
    var sent = api.requests.size
    expect(exec(api, isolated, "acl", "user:ivan", "public").exit == 78 && api.requests.size == sent) { "an invalid group is sent" }
    api.aclMismatch = true
    expect(exec(api, isolated, "acl", "user:ivan", "isolated").exit == 1) { "an answer for another group is accepted" }
    api.aclMismatch = false
    api.missing.add(id)
    expect(exec(api, isolated, "acl", "user:ivan", "isolated").exit == 1) { "a refused ACL request does not exit 1" }
    api.missing.remove(id)
    run = exec(StandInApi().apply { aclUnsupported = true }, isolated, "acl", "user:ivan", "isolated")
    expect(run.exit == 1 && run.err.trim() == aclUnsupportedMessage) { "acl on an older server: ${run.err}" }

    // an explicit group settles a pending record, so a later issue keeps it
    api.aclFailures = 1
    exec(api, isolated, "provision", "user:mia", dir.resolve("mia.jwt").toString())
    expect(pendingAcl(dir, "acl.json") == listOf("user:mia")) { "the failed group is not recorded" }
    run = exec(api, isolated, "acl", "user:mia", "default")
    expect(run.exit == 0 && pendingAcl(dir, "acl.json").isEmpty()) { "acl does not settle a pending record" }
    sent = api.aclRequests().size
    expect(
        exec(api, isolated, "provision", "user:mia", dir.resolve("mia.jwt").toString()).exit == 0 &&
            api.aclRequests().size == sent && api.aclGroups[StandInApi.id(3)] == "default",
    ) { "a settled group is overridden on the next issue" }

    // remove drops a pending record with its mapping
    api.aclFailures = 1
    exec(api, isolated, "provision", "user:noor", dir.resolve("noor.jwt").toString())
    expect(exec(api, isolated, "remove", "user:noor").exit == 0 && pendingAcl(dir, "acl.json").isEmpty()) { "remove keeps a pending record" }

    // a pending_acl that is not a list of distinct mapped keys is refused untouched
    val invalid = listOf(
        """{"version":1,"clients":{},"pending_acl":["user:x"]}""",
        """{"version":1,"clients":{"user:x":"$id"},"pending_acl":["user:x","user:x"]}""",
        """{"version":1,"clients":{"user:x":"$id"},"pending_acl":"user:x"}""",
        """{"version":1,"clients":{"user:x":"$id"},"pending_acl":[1]}""",
    )
    for (text in invalid) {
        val map = dir.resolve("pending.json")
        writePrivate(map, text.toByteArray(Charsets.UTF_8))
        val refused = StandInApi()
        expect(
            exec(refused, env(dir, "pending.json"), "provision", "user:x", dir.resolve("x.jwt").toString()).exit == 78 &&
                Files.readString(map) == text && refused.requests.isEmpty(),
        ) { "the map $text is accepted" }
    }
}

/** The unmapped-key and 404 texts. */
private fun checkFailureTexts(dir: Path) {
    val api = StandInApi()
    val env = env(dir, "texts.json")
    val unmapped = listOf(
        listOf("cap", "user:nobody", "--monthly", "1"), listOf("usage", "user:nobody"), listOf("remove", "user:nobody"),
        listOf("acl", "user:nobody", "default"),
    )
    for (args in unmapped) {
        val run = exec(api, env, *args.toTypedArray())
        expect(run.exit == 78 && run.err.trim() == unmappedMessage) { "${args[0]} for an unmapped key answers ${run.exit}: ${run.err}" }
    }
    expect(api.requests.isEmpty()) { "an unmapped key reached the API" }

    exec(api, env, "provision", "user:olga", dir.resolve("olga.jwt").toString())
    val older = StandInApi().apply { status = 404 }
    val cases = listOf(
        capPath to listOf("cap", "user:olga", "--monthly", "1"),
        capPath to listOf("usage", "user:olga"),
        capsPath to listOf("usage-all"),
    )
    for ((route, args) in cases) {
        val run = exec(older, env, *args.toTypedArray())
        expect(run.exit == 1 && run.err.trim() == "$route answered 404: the server predates the data-cap routes") {
            "${args[0]} on a server without the cap routes answers ${run.exit}: ${run.err}"
        }
    }
}

/** No client JWT and no root credential reached stdout or stderr in any run. */
private fun checkNothingSecretPrinted() {
    val output = allOutput.toString()
    expect(allIssuedJwts.isNotEmpty()) { "no client JWT was issued" }
    for (token in allIssuedJwts) {
        expect(!output.contains(token)) { "a client JWT reached stdout or stderr" }
    }
    expect(!output.contains(testRoot)) { "the root credential reached stdout or stderr" }
}
