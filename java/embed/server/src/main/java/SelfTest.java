// The backend tool's credential-free self-test (EMBED_CONTRACT.md, "Backend
// tools"). It runs the commands against a stand-in URnetwork API in a
// temporary private directory: no credentials, no network. The data is
// synthetic.
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;

final class SelfTest {
  private static final String ROOT = "selftest-root-credential-not-a-secret";
  private static final StringBuilder ALL_OUTPUT = new StringBuilder();
  private static final Set<PosixFilePermission> GROUP_AND_OTHERS = EnumSet.of(
      PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_WRITE, PosixFilePermission.GROUP_EXECUTE,
      PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_WRITE, PosixFilePermission.OTHERS_EXECUTE);

  /** Static members only. */
  private SelfTest() {}

  /** A stand-in URnetwork API that keeps the clients it issued. */
  static final class StandInApi implements EmbedServer.Transport {
    // every client JWT any stand-in issued, to check that none is printed
    static final List<String> ALL_ISSUED_JWTS = new ArrayList<>();
    final List<EmbedServer.ApiRequest> requests = new ArrayList<>();
    final List<String> issuedJwts = new ArrayList<>();
    // clients whose reissue and removal answer "Client does not exist."
    final Set<String> missing = new HashSet<>();
    // the client-data-caps pages by cursor ("" for the first page)
    final Map<String, String> pages = new HashMap<>();
    // when set, a new client is refused with this client limit flag
    String limitFlag;
    // when set, every request answers with this HTTP status
    Integer status;
    // when set, a new client's JWT claims this client id instead of its own
    String claimOverride;
    // when set, the cap routes answer this body
    String capAnswer;
    // the ACL group each client was set to
    final Map<String, String> aclGroups = new HashMap<>();
    // how many ACL requests still fail with HTTP 500
    int aclFailures;
    // when set, the ACL route answers 404, as a server without ACL groups does
    boolean aclUnsupported;
    // when set, the ACL route answers the other group
    boolean aclMismatch;
    // when set, Embed isn't enabled for the network: the data-cap and ACL-group routes answer the
    // refusal and GET /network/embed answers enabled false
    boolean embedDisabled;
    // when set, GET /network/embed answers this body
    String embedAnswer;
    private int nextClient = 1;
    private int generation;

    /** A client id the stand-in issues. */
    static String id(int n) {
      return String.format("%08x-0000-4000-8000-%012x", n, n);
    }

    /** An unsigned JWT with the client_id claim; the tool checks the claim only. */
    static String jwt(String clientId, int generation) {
      ObjectNode claims = EmbedServer.JSON.createObjectNode().put("client_id", clientId).put("generation", generation);
      return "eyJhbGciOiJub25lIn0." +
          Base64.getUrlEncoder().withoutPadding().encodeToString(claims.toString().getBytes(StandardCharsets.UTF_8)) +
          ".c2lnbmF0dXJl";
    }

    /** A cap object for a client. */
    static String cap(String clientId) {
      return "{\"client_id\":\"" + clientId + "\",\"monthly_byte_limit\":10000000000,\"monthly_used_byte_count\":0," +
          "\"total_byte_limit\":null,\"total_used_byte_count\":0,\"capped\":false,\"capped_reason\":\"\"}";
    }

    @Override
    public EmbedServer.ApiResponse send(EmbedServer.ApiRequest request) throws IOException {
      requests.add(request);
      if (status != null) {
        return new EmbedServer.ApiResponse(status, "{}");
      }
      JsonNode body = request.body() == null ? EmbedServer.JSON.createObjectNode() : EmbedServer.JSON.readTree(request.body());
      String path = request.pathAndQuery();
      if (request.method().equals("POST") && path.equals(EmbedServer.AUTH_CLIENT_PATH)) {
        String clientId = body.path("client_id").textValue();
        if (clientId != null) {
          if (missing.contains(clientId)) {
            return new EmbedServer.ApiResponse(200, "{\"error\":{\"message\":\"Client does not exist.\"}}");
          }
          return issue(clientId, clientId);
        }
        if (limitFlag != null) {
          return new EmbedServer.ApiResponse(200, "{\"error\":{\"" + limitFlag + "\":true,\"message\":\"Client limit exceeded.\"}}");
        }
        String id = id(nextClient++);
        return issue(id, claimOverride != null ? claimOverride : id);
      }
      if (embedDisabled && (path.startsWith(EmbedServer.CAP_PATH) || path.equals(EmbedServer.ACL_PATH))) {
        return new EmbedServer.ApiResponse(200, "{\"error\":{\"message\":\"Embed isn't enabled for this network.\"}}");
      }
      if (request.method().equals("GET") && path.equals(EmbedServer.EMBED_PATH)) {
        return new EmbedServer.ApiResponse(200, embedAnswer != null ? embedAnswer
                                                                    : EmbedServer.JSON.createObjectNode()
                                                                          .put("enabled", !embedDisabled).put("client_limit", 100)
                                                                          .put("active_client_count", nextClient - 1).toString());
      }
      if (request.method().equals("POST") && path.equals(EmbedServer.CAP_PATH)) {
        return new EmbedServer.ApiResponse(200, capAnswer != null ? capAnswer : cap(body.path("client_id").asText()));
      }
      String capPrefix = EmbedServer.CAP_PATH + "?client_id=";
      if (request.method().equals("GET") && path.startsWith(capPrefix)) {
        return new EmbedServer.ApiResponse(200, capAnswer != null ? capAnswer
                                                                  : cap(URLDecoder.decode(path.substring(capPrefix.length()), StandardCharsets.UTF_8)));
      }
      String capsPrefix = EmbedServer.CAPS_PATH + "?limit=1000";
      if (request.method().equals("GET") && path.startsWith(capsPrefix)) {
        String rest = path.substring(capsPrefix.length());
        String cursor = rest.startsWith("&cursor=") ? URLDecoder.decode(rest.substring(8), StandardCharsets.UTF_8) : "";
        return new EmbedServer.ApiResponse(200, pages.getOrDefault(cursor, "{\"clients\":[],\"next_cursor\":null}"));
      }
      if (request.method().equals("POST") && path.equals(EmbedServer.ACL_PATH) && !aclUnsupported) {
        if (0 < aclFailures) {
          aclFailures--;
          return new EmbedServer.ApiResponse(500, "{}");
        }
        String id = body.path("client_id").asText();
        String group = body.path("acl_group").asText();
        if (missing.contains(id)) {
          return new EmbedServer.ApiResponse(200, "{\"error\":{\"message\":\"Client does not exist.\"}}");
        }
        if (!group.equals("default") && !group.equals("isolated")) {
          return new EmbedServer.ApiResponse(200, "{\"error\":{\"message\":\"Invalid ACL group.\"}}");
        }
        aclGroups.put(id, group);
        String answered = aclMismatch ? (group.equals("default") ? "isolated" : "default") : group;
        return new EmbedServer.ApiResponse(200, EmbedServer.JSON.createObjectNode()
                                                    .put("client_id", id).put("acl_group", answered).toString());
      }
      if (request.method().equals("POST") && path.equals(EmbedServer.REMOVE_PATH)) {
        String id = body.path("client_id").asText();
        return new EmbedServer.ApiResponse(200, missing.contains(id) ? "{\"error\":{\"message\":\"Client does not exist.\"}}" : "{}");
      }
      return new EmbedServer.ApiResponse(404, "404 page not found");
    }

    /** The ACL requests this stand-in answered. */
    List<EmbedServer.ApiRequest> aclRequests() {
      return requests.stream().filter(r -> r.pathAndQuery().equals(EmbedServer.ACL_PATH)).toList();
    }

    private EmbedServer.ApiResponse issue(String clientId, String claim) {
      String token = jwt(claim, ++generation);
      issuedJwts.add(token);
      ALL_ISSUED_JWTS.add(token);
      return new EmbedServer.ApiResponse(200, EmbedServer.JSON.createObjectNode()
                                                  .put("client_id", clientId).put("by_client_jwt", token).toString());
    }
  }

  /** The exit code, stdout and stderr of one run. */
  private record Run(int exit, String out, String err) {}

  /** Runs every check in order and stops at the first failure. */
  static void run() throws Exception {
    Path dir = Files.createTempDirectory("ur-embed-server-");
    try {
      if (EmbedServer.posix(dir)) {
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
      }
      checkProvision(dir);
      checkArguments(dir);
      checkCap(dir);
      checkUsageAll(dir);
      checkRemove(dir);
      checkMap(dir);
      checkAclGroups(dir);
      checkEmbedNotEnabled(dir);
      checkEmbedStatus(dir);
      checkFailureTexts(dir);
      checkNothingSecretPrinted();
    } finally {
      try (Stream<Path> paths = Files.walk(dir)) {
        for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
          Files.deleteIfExists(path);
        }
      }
    }
  }

  private static void expect(boolean condition, String reason) {
    if (!condition) {
      throw new IllegalStateException(reason);
    }
  }

  /**
   * The settings of a run with a map in dir. The checks that do not test ACL groups keep new
   * clients in "default", which sends no ACL request.
   */
  private static Function<String, String> env(Path dir, String map) {
    return env(dir, map, "default");
  }

  /** The settings of a run with a map in dir and a default ACL group; null leaves it unset. */
  private static Function<String, String> env(Path dir, String map, String aclGroup) {
    Map<String, String> settings = new HashMap<>(Map.of("URNETWORK_ROOT_JWT", ROOT,
                                                        "URNETWORK_CLIENT_MAP", dir.resolve(map).toString(),
                                                        "URNETWORK_API_URL", "http://127.0.0.1:1234"));
    if (aclGroup != null) {
      settings.put("URNETWORK_DEFAULT_ACL_GROUP", aclGroup);
    }
    return settings::get;
  }

  /** Runs one command against api. */
  private static Run exec(StandInApi api, Function<String, String> env, String... args) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    int exit = EmbedServer.run(args, env, new PrintStream(out, true, StandardCharsets.UTF_8),
                               new PrintStream(err, true, StandardCharsets.UTF_8), (origin, root) -> api);
    Run run = new Run(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    ALL_OUTPUT.append(run.out()).append(run.err());
    return run;
  }

  /** The JSON body of a recorded request. */
  private static JsonNode body(EmbedServer.ApiRequest request) throws IOException {
    return EmbedServer.JSON.readTree(request.body());
  }

  /** Whether a file is private to its owner (always true without POSIX permissions). */
  private static boolean isPrivate(Path path) throws IOException {
    return !EmbedServer.posix(path) || Collections.disjoint(Files.getPosixFilePermissions(path), GROUP_AND_OTHERS);
  }

  /** JSON text in a canonical form, to compare bodies. */
  private static String canonical(String json) throws IOException {
    return EmbedServer.JSON.writeValueAsString(EmbedServer.JSON.readTree(json));
  }

  /** New versus reissue, the re-provision of a missing client, the client limit and the checks. */
  private static void checkProvision(Path dir) throws IOException, EmbedServer.ToolException {
    StandInApi api = new StandInApi();
    Function<String, String> env = env(dir, "clients.json");
    Path jwtFile = dir.resolve("alice.jwt");
    String key = "user:alice:22222222-2222-2222-2222-222222222222";
    String id = StandInApi.id(1);

    Run run = exec(api, env, "provision", key, jwtFile.toString());
    expect(run.exit() == 0 && run.out().strip().equals("{\"client_id\":\"" + id + "\"}"), "provision printed " + run.out() + run.err());
    JsonNode created = body(api.requests.get(0));
    expect(created.path("description").asText().equals("embed client") &&
           created.path("device_spec").asText().equals("urnetwork-examples/java-embed-server") &&
           !created.has("client_id") && !created.has("source_client_id"),
           "a new client request has the wrong fields");
    expect(Files.readString(jwtFile).equals(api.issuedJwts.get(0) + "\n") && isPrivate(jwtFile),
           "the client JWT file is not written privately");
    expect(EmbedServer.loadMap(dir.resolve("clients.json")).get("clients").path(key).asText().equals(id) &&
           isPrivate(dir.resolve("clients.json")),
           "the map is not saved privately");

    run = exec(api, env, "provision", key, jwtFile.toString());
    JsonNode reissued = body(api.requests.get(1));
    expect(run.exit() == 0 && reissued.path("client_id").asText().equals(id) && !reissued.has("source_client_id"),
           "a mapped key is not reissued");
    expect(Files.readString(jwtFile).equals(api.issuedJwts.get(1) + "\n"), "a reissue does not replace the client JWT file");

    // a client deactivated after 30 days without connecting
    api.missing.add(id);
    run = exec(api, env, "provision", key, jwtFile.toString());
    expect(run.exit() == 0 && body(api.requests.get(2)).path("client_id").asText().equals(id) &&
           !body(api.requests.get(3)).has("client_id"),
           "a missing client is not re-provisioned");
    expect(EmbedServer.loadMap(dir.resolve("clients.json")).get("clients").path(key).asText().equals(StandInApi.id(2)),
           "the re-provisioned client is not mapped");

    for (String flag : new String[] {"client_limit_exceeded", "upgrade_required"}) {
      StandInApi limited = new StandInApi();
      limited.limitFlag = flag;
      run = exec(limited, env, "provision", "user:bob", dir.resolve("bob.jwt").toString());
      expect(run.exit() == 78 && run.err().strip().equals(EmbedServer.CLIENT_LIMIT_MESSAGE),
             "the client limit (" + flag + ") exits " + run.exit() + ": " + run.err());
    }
    StandInApi mismatched = new StandInApi();
    mismatched.claimOverride = StandInApi.id(9);
    expect(exec(mismatched, env, "provision", "user:carol", dir.resolve("carol.jwt").toString()).exit() == 1,
           "a client JWT for another client is accepted");
    StandInApi refusedRoot = new StandInApi();
    refusedRoot.status = 401;
    expect(exec(refusedRoot, env, "provision", "user:carol", dir.resolve("carol.jwt").toString()).exit() == 78,
           "a refused root credential does not exit 78");
    StandInApi failing = new StandInApi();
    failing.status = 500;
    expect(exec(failing, env, "provision", "user:carol", dir.resolve("carol.jwt").toString()).exit() == 1,
           "an API failure does not exit 1");
  }

  /** Key, argument and setting rejection, all exit 78. */
  private static void checkArguments(Path dir) {
    StandInApi api = new StandInApi();
    Function<String, String> env = env(dir, "clients.json");
    String jwtFile = dir.resolve("x.jwt").toString();
    String[][] usage = {
        {}, {"provision"}, {"provision", "user:a"}, {"provision", "user:a", jwtFile, "extra"},
        {"provision", "alice", jwtFile}, {"provision", "user:../a", jwtFile},
        {"provision", StandInApi.id(1), jwtFile}, {"provision", "user:a", dir.resolve("missing").resolve("x.jwt").toString()},
        {"usage"}, {"remove", "user:a", "extra"}, {"usage-all", "extra"}, {"unknown"}, {"--self-test", "extra"},
        {"cap", "user:a"}, {"cap", "user:a", "--monthly", "1GB"}, {"cap", "user:a", "--monthly", "-1"},
        {"cap", "user:a", "--monthly", "9223372036854775808"}, {"cap", "user:a", "--monthly", "1", "--monthly", "2"},
        {"cap", "user:a", "--foo"}, {"cap", "user:a", "--monthly"}, {"cap", "user:a", "--reset-total", "--reset-total"},
        {"acl"}, {"acl", "user:a"}, {"acl", "user:a", "other"}, {"acl", "user:a", "Default"},
        {"acl", "user:a", "default", "extra"}, {"acl", "alice", "default"}, {"status", "extra"},
    };
    for (String[] args : usage) {
      expect(exec(api, env, args).exit() == 78, "the arguments " + String.join(" ", args) + " do not exit 78");
    }
    expect(api.requests.isEmpty(), "a rejected command reached the API");
    List<Map<String, String>> invalid = List.of(
        Map.of("URNETWORK_CLIENT_MAP", dir.resolve("clients.json").toString()),
        Map.of("URNETWORK_ROOT_JWT", ROOT),
        Map.of("URNETWORK_ROOT_JWT", ROOT, "URNETWORK_CLIENT_MAP", "clients.json"),
        Map.of("URNETWORK_ROOT_JWT", ROOT, "URNETWORK_CLIENT_MAP", dir.resolve("clients.json").toString(),
               "URNETWORK_API_URL", "http://api.example.com"),
        Map.of("URNETWORK_ROOT_JWT", ROOT, "URNETWORK_CLIENT_MAP", dir.resolve("clients.json").toString(),
               "URNETWORK_DEFAULT_ACL_GROUP", "private"));
    for (Map<String, String> settings : invalid) {
      expect(exec(api, settings::get, "provision", "user:a", jwtFile).exit() == 78, "missing or invalid settings do not exit 78");
    }
    expect(exec(api, env, "usage", "user:never-provisioned").exit() == 78, "an unmapped key does not exit 78");
  }

  /** The merge request bodies and the cap object. */
  private static void checkCap(Path dir) throws IOException {
    StandInApi api = new StandInApi();
    Function<String, String> env = env(dir, "caps.json");
    exec(api, env, "provision", "user:dana", dir.resolve("dana.jwt").toString());
    String id = StandInApi.id(1);
    String[][] cases = {
        {"--monthly 10000000000", "{\"client_id\":\"" + id + "\",\"monthly_byte_limit\":10000000000}"},
        {"--total null", "{\"client_id\":\"" + id + "\",\"total_byte_limit\":null}"},
        {"--monthly 0", "{\"client_id\":\"" + id + "\",\"monthly_byte_limit\":0}"},
        {"--reset-total", "{\"client_id\":\"" + id + "\",\"reset_total\":true}"},
        {"--reset-total --total 5 --monthly null",
         "{\"client_id\":\"" + id + "\",\"monthly_byte_limit\":null,\"total_byte_limit\":5,\"reset_total\":true}"},
    };
    for (String[] c : cases) {
      List<String> args = new ArrayList<>(List.of("cap", "user:dana"));
      args.addAll(List.of(c[0].split(" ")));
      Run run = exec(api, env, args.toArray(String[]::new));
      EmbedServer.ApiRequest last = api.requests.get(api.requests.size() - 1);
      expect(run.exit() == 0 && last.method().equals("POST") && last.pathAndQuery().equals(EmbedServer.CAP_PATH),
             "cap " + c[0] + " exits " + run.exit() + ": " + run.err());
      expect(canonical(last.body()).equals(canonical(c[1])), "cap " + c[0] + " sends " + last.body() + ", want " + c[1]);
      expect(canonical(run.out()).equals(canonical(StandInApi.cap(id))), "cap does not print the cap object");
    }
    Run usage = exec(api, env, "usage", "user:dana");
    EmbedServer.ApiRequest last = api.requests.get(api.requests.size() - 1);
    expect(usage.exit() == 0 && last.method().equals("GET") &&
           last.pathAndQuery().equals(EmbedServer.CAP_PATH + "?client_id=" + id) &&
           canonical(usage.out()).equals(canonical(StandInApi.cap(id))),
           "usage does not read the cap object with the root credential");
    api.capAnswer = "{\"error\":{\"message\":\"Client not in network.\"}}";
    expect(exec(api, env, "cap", "user:dana", "--monthly", "1").exit() == 1, "a refused cap does not exit 1");
    StandInApi old = new StandInApi();
    old.status = 404;
    expect(exec(old, env, "usage-all").exit() == 1, "a server without the cap routes does not exit 1");
  }

  /** A page of cap objects. */
  private static String page(int first, int count, String next) {
    ObjectNode page = EmbedServer.JSON.createObjectNode();
    ArrayNode clients = page.putArray("clients");
    for (int i = first; i < first + count; i++) {
      try {
        clients.add(EmbedServer.JSON.readTree(StandInApi.cap(StandInApi.id(i))));
      } catch (IOException e) {
        throw new IllegalStateException(e);
      }
    }
    if (next == null) {
      page.putNull("next_cursor");
    } else {
      page.put("next_cursor", next);
    }
    return page.toString();
  }

  /** usage-all pages 1000 at a time and stops at a null or repeated cursor. */
  private static void checkUsageAll(Path dir) {
    // no map is needed: usage-all lists the network's capped clients
    Map<String, String> noMap = Map.of("URNETWORK_ROOT_JWT", ROOT);
    StandInApi api = new StandInApi();
    api.pages.put("", page(1, 2, "c1"));
    api.pages.put("c1", page(3, 1, "c/2"));
    api.pages.put("c/2", page(4, 1, null));
    Run run = exec(api, noMap::get, "usage-all");
    expect(run.exit() == 0 && run.out().strip().split("\n").length == 4, "usage-all printed " + run.out() + run.err());
    List<String> paths = api.requests.stream().map(EmbedServer.ApiRequest::pathAndQuery).toList();
    expect(paths.equals(List.of(EmbedServer.CAPS_PATH + "?limit=1000", EmbedServer.CAPS_PATH + "?limit=1000&cursor=c1",
                                EmbedServer.CAPS_PATH + "?limit=1000&cursor=c%2F2")),
           "usage-all does not page with the cursor: " + paths);

    StandInApi repeating = new StandInApi();
    repeating.pages.put("", page(1, 1, "c1"));
    repeating.pages.put("c1", page(2, 1, "c1"));
    run = exec(repeating, noMap::get, "usage-all");
    expect(run.exit() == 0 && repeating.requests.size() == 2, "usage-all does not stop on a repeated cursor");
  }

  /** remove drops the mapping for both answers. */
  private static void checkRemove(Path dir) throws IOException, EmbedServer.ToolException {
    StandInApi api = new StandInApi();
    Function<String, String> env = env(dir, "remove.json");
    exec(api, env, "provision", "user:erin", dir.resolve("erin.jwt").toString());
    exec(api, env, "provision", "user:frank", dir.resolve("frank.jwt").toString());
    Run run = exec(api, env, "remove", "user:erin");
    expect(run.exit() == 0 && run.out().strip().equals("{\"removed\":\"" + StandInApi.id(1) + "\"}") &&
           body(api.requests.get(api.requests.size() - 1)).path("client_id").asText().equals(StandInApi.id(1)),
           "remove printed " + run.out() + run.err());
    api.missing.add(StandInApi.id(2));
    expect(exec(api, env, "remove", "user:frank").exit() == 0, "removing a missing client fails");
    expect(EmbedServer.loadMap(dir.resolve("remove.json")).get("clients").isEmpty(), "remove keeps a mapping");
    expect(exec(api, env, "remove", "user:erin").exit() == 78, "removing an unmapped key does not exit 78");
  }

  /** A map with unknown fields is refused untouched, and a held lock fails. */
  private static void checkMap(Path dir) throws IOException {
    Path map = dir.resolve("token-server.json");
    String text = "{\"version\":1,\"clients\":{},\"pending_caps\":[]}";
    EmbedServer.writePrivate(map, text.getBytes(StandardCharsets.UTF_8));
    StandInApi api = new StandInApi();
    expect(exec(api, env(dir, "token-server.json"), "provision", "user:gina", dir.resolve("gina.jwt").toString()).exit() == 78,
           "a map with unknown fields is accepted");
    expect(Files.readString(map).equals(text) && api.requests.isEmpty(), "a refused map is rewritten");

    Path locked = dir.resolve("locked.json");
    Files.createDirectory(Path.of(locked + ".lock"));
    expect(exec(api, env(dir, "locked.json"), "provision", "user:hana", dir.resolve("hana.jwt").toString()).exit() == 1,
           "a held lock does not exit 1");
    // the C# tool's lock is a file at the same path
    Path fileLocked = dir.resolve("file-locked.json");
    Files.createFile(Path.of(fileLocked + ".lock"));
    Run run = exec(api, env(dir, "file-locked.json"), "provision", "user:hana", dir.resolve("hana.jwt").toString());
    expect(run.exit() == 1 && run.err().contains("lock") && Files.isRegularFile(Path.of(fileLocked + ".lock")),
           "a held file lock does not exit 1 untouched");
  }

  /** The keys a map file records in pending_acl. */
  private static List<String> pendingAcl(Path dir, String map) throws IOException, EmbedServer.ToolException {
    List<String> keys = new ArrayList<>();
    for (JsonNode entry : EmbedServer.loadMap(dir.resolve(map)).path("pending_acl")) {
      keys.add(entry.asText());
    }
    return keys;
  }

  /**
   * The default ACL group: applied to a new client only, with pending_acl set before and cleared
   * after, kept and retried after a failure, kept on a server without ACL groups, and settled by
   * acl; the acl command.
   */
  private static void checkAclGroups(Path dir) throws IOException, EmbedServer.ToolException {
    StandInApi api = new StandInApi();
    Function<String, String> isolated = env(dir, "acl.json", null);
    Path jwtFile = dir.resolve("ivan.jwt");
    String id = StandInApi.id(1);

    // unset means isolated: one ACL request after the new client, then no record
    Run run = exec(api, isolated, "provision", "user:ivan", jwtFile.toString());
    expect(run.exit() == 0 && api.requests.size() == 2 && api.requests.get(1).pathAndQuery().equals(EmbedServer.ACL_PATH) &&
           canonical(api.requests.get(1).body()).equals(canonical("{\"client_id\":\"" + id + "\",\"acl_group\":\"isolated\"}")),
           "a new client is not put in isolated: " + run.err());
    expect("isolated".equals(api.aclGroups.get(id)) && pendingAcl(dir, "acl.json").isEmpty() &&
           !Files.readString(dir.resolve("acl.json")).contains("pending_acl") &&
           Files.readString(jwtFile).equals(api.issuedJwts.get(0) + "\n"),
           "an applied group leaves its record or no client JWT");
    run = exec(api, isolated, "provision", "user:ivan", jwtFile.toString());
    expect(run.exit() == 0 && api.aclRequests().size() == 1, "a reissue sends an ACL request");

    // a failed request keeps the record and writes no client JWT; the next issue retries
    api.aclFailures = 1;
    Path judyJwt = dir.resolve("judy.jwt");
    run = exec(api, isolated, "provision", "user:judy", judyJwt.toString());
    expect(run.exit() == 1 && !Files.exists(judyJwt) && pendingAcl(dir, "acl.json").equals(List.of("user:judy")) &&
           EmbedServer.loadMap(dir.resolve("acl.json")).get("clients").path("user:judy").asText().equals(StandInApi.id(2)),
           "a failed ACL request does not keep the record: " + run.exit() + " " + run.err());
    run = exec(api, isolated, "provision", "user:judy", judyJwt.toString());
    expect(run.exit() == 0 && "isolated".equals(api.aclGroups.get(StandInApi.id(2))) &&
           pendingAcl(dir, "acl.json").isEmpty() && Files.exists(judyJwt),
           "a pending group is not applied on the next issue");

    // a server without ACL groups: exit 1 with the record kept; a default of default then provisions
    StandInApi older = new StandInApi();
    older.aclUnsupported = true;
    Path kimJwt = dir.resolve("kim.jwt");
    run = exec(older, env(dir, "older.json", "isolated"), "provision", "user:kim", kimJwt.toString());
    expect(run.exit() == 1 && run.err().strip().equals(EmbedServer.ACL_UNSUPPORTED_MESSAGE) && !Files.exists(kimJwt) &&
           pendingAcl(dir, "older.json").equals(List.of("user:kim")),
           "a server without ACL groups answers " + run.exit() + ": " + run.err());
    run = exec(older, env(dir, "older.json"), "provision", "user:kim", kimJwt.toString());
    expect(run.exit() == 0 && Files.exists(kimJwt) && pendingAcl(dir, "older.json").isEmpty() &&
           older.aclRequests().size() == 1,
           "a default of default does not settle the record without a request");
    run = exec(older, env(dir, "older.json"), "provision", "user:leo", dir.resolve("leo.jwt").toString());
    expect(run.exit() == 0 && older.aclRequests().size() == 1 && pendingAcl(dir, "older.json").isEmpty(),
           "a default of default sends an ACL request");

    // the acl command: request body, printed answer, and refusals
    run = exec(api, isolated, "acl", "user:ivan", "default");
    String answer = "{\"client_id\":\"" + id + "\",\"acl_group\":\"default\"}";
    expect(run.exit() == 0 && run.out().strip().equals(answer) &&
           canonical(api.requests.get(api.requests.size() - 1).body()).equals(canonical(answer)) &&
           "default".equals(api.aclGroups.get(id)),
           "acl printed " + run.out() + run.err());
    int sent = api.requests.size();
    expect(exec(api, isolated, "acl", "user:ivan", "public").exit() == 78 && api.requests.size() == sent,
           "an invalid group is sent");
    api.aclMismatch = true;
    expect(exec(api, isolated, "acl", "user:ivan", "isolated").exit() == 1, "an answer for another group is accepted");
    api.aclMismatch = false;
    api.missing.add(id);
    expect(exec(api, isolated, "acl", "user:ivan", "isolated").exit() == 1, "a refused ACL request does not exit 1");
    api.missing.remove(id);
    StandInApi olderAcl = new StandInApi();
    olderAcl.aclUnsupported = true;
    run = exec(olderAcl, isolated, "acl", "user:ivan", "isolated");
    expect(run.exit() == 1 && run.err().strip().equals(EmbedServer.ACL_UNSUPPORTED_MESSAGE), "acl on an older server: " + run.err());

    // an explicit group settles a pending record, so a later issue keeps it
    api.aclFailures = 1;
    exec(api, isolated, "provision", "user:mia", dir.resolve("mia.jwt").toString());
    expect(pendingAcl(dir, "acl.json").equals(List.of("user:mia")), "the failed group is not recorded");
    run = exec(api, isolated, "acl", "user:mia", "default");
    expect(run.exit() == 0 && pendingAcl(dir, "acl.json").isEmpty(), "acl does not settle a pending record");
    sent = api.aclRequests().size();
    expect(exec(api, isolated, "provision", "user:mia", dir.resolve("mia.jwt").toString()).exit() == 0 &&
           api.aclRequests().size() == sent && "default".equals(api.aclGroups.get(StandInApi.id(3))),
           "a settled group is overridden on the next issue");

    // remove drops a pending record with its mapping
    api.aclFailures = 1;
    exec(api, isolated, "provision", "user:noor", dir.resolve("noor.jwt").toString());
    expect(exec(api, isolated, "remove", "user:noor").exit() == 0 && pendingAcl(dir, "acl.json").isEmpty(),
           "remove keeps a pending record");

    // a pending_acl that is not a list of distinct mapped keys is refused untouched
    String[] invalid = {
        "{\"version\":1,\"clients\":{},\"pending_acl\":[\"user:x\"]}",
        "{\"version\":1,\"clients\":{\"user:x\":\"" + id + "\"},\"pending_acl\":[\"user:x\",\"user:x\"]}",
        "{\"version\":1,\"clients\":{\"user:x\":\"" + id + "\"},\"pending_acl\":\"user:x\"}",
        "{\"version\":1,\"clients\":{\"user:x\":\"" + id + "\"},\"pending_acl\":[1]}",
    };
    for (String text : invalid) {
      Path map = dir.resolve("pending.json");
      EmbedServer.writePrivate(map, text.getBytes(StandardCharsets.UTF_8));
      StandInApi refused = new StandInApi();
      expect(exec(refused, env(dir, "pending.json"), "provision", "user:x", dir.resolve("x.jwt").toString()).exit() == 78 &&
             Files.readString(map).equals(text) && refused.requests.isEmpty(),
             "the map " + text + " is accepted");
    }
  }

  /**
   * The Embed-not-enabled refusal: cap, usage, usage-all and acl exit 78 with the fixed line;
   * provision still provisions and writes the client JWT, keeps the default group pending with the
   * fixed stderr line, and a provision after the team enables Embed applies it.
   */
  private static void checkEmbedNotEnabled(Path dir) throws IOException, EmbedServer.ToolException {
    StandInApi api = new StandInApi();
    Function<String, String> isolated = env(dir, "embed.json", null);
    expect(exec(api, isolated, "provision", "user:pia", dir.resolve("pia.jwt").toString()).exit() == 0,
           "provision before the refusal failed");
    api.embedDisabled = true;
    String[][] refusedCommands = {
        {"cap", "user:pia", "--monthly", "1"}, {"usage", "user:pia"}, {"usage-all"}, {"acl", "user:pia", "isolated"},
    };
    for (String[] args : refusedCommands) {
      Run refused = exec(api, isolated, args);
      expect(refused.exit() == 78 && refused.out().isEmpty() &&
                 refused.err().equals(EmbedServer.EMBED_NOT_ENABLED_LINE + System.lineSeparator()),
             args[0] + " while Embed isn't enabled answers " + refused.exit() + ": " + refused.err());
    }
    Path quinnJwt = dir.resolve("quinn.jwt");
    Run run = exec(api, isolated, "provision", "user:quinn", quinnJwt.toString());
    String quinn = EmbedServer.loadMap(dir.resolve("embed.json")).path("clients").path("user:quinn").asText();
    expect(run.exit() == 0 && run.out().trim().equals("{\"client_id\":\"" + quinn + "\"}") &&
               run.err().equals(EmbedServer.EMBED_PENDING_LINE + System.lineSeparator()),
           "provision while Embed isn't enabled answers " + run.exit() + ": " + run.out() + run.err());
    expect(Files.readString(quinnJwt).equals(api.issuedJwts.get(api.issuedJwts.size() - 1) + "\n") &&
               pendingAcl(dir, "embed.json").equals(List.of("user:quinn")),
           "provision while Embed isn't enabled wrote no client JWT or dropped the record");
    api.embedDisabled = false;
    run = exec(api, isolated, "provision", "user:quinn", quinnJwt.toString());
    expect(run.exit() == 0 && run.err().isEmpty() && pendingAcl(dir, "embed.json").isEmpty() &&
               "isolated".equals(api.aclGroups.get(quinn)),
           "provision after Embed was enabled does not apply the group: " + run.err());
  }

  /**
   * The status command: GET /network/embed with the root credential, printed as the fixed line for
   * an enabled and a not enabled network; a refusal exits 78; a server without the route and an
   * invalid answer exit 1.
   */
  private static void checkEmbedStatus(Path dir) {
    Function<String, String> env = env(dir, "status.json");
    StandInApi api = new StandInApi();
    api.embedAnswer = "{\"enabled\":true,\"client_limit\":5000,\"active_client_count\":1234}";
    Run run = exec(api, env, "status");
    expect(run.exit() == 0 &&
               run.out().equals("embed enabled: yes | client limit: 5000 | active clients: 1234" + System.lineSeparator()) &&
               run.err().isEmpty(),
           "status printed " + run.out() + run.err());
    expect(api.requests.size() == 1 && api.requests.get(0).method().equals("GET") &&
               api.requests.get(0).pathAndQuery().equals(EmbedServer.EMBED_PATH) && api.requests.get(0).body() == null,
           "the status request is not GET /network/embed");
    StandInApi disabled = new StandInApi();
    disabled.embedDisabled = true;
    run = exec(disabled, env, "status");
    expect(run.exit() == 0 &&
               run.out().equals("embed enabled: no | client limit: 100 | active clients: 0" + System.lineSeparator()),
           "status while Embed isn't enabled printed " + run.out() + run.err());
    StandInApi refusing = new StandInApi();
    refusing.embedAnswer = "{\"error\":{\"message\":\"Invalid credential.\"}}";
    run = exec(refusing, env, "status");
    expect(run.exit() == 78 && run.out().isEmpty() && run.err().contains("Invalid credential.") &&
               run.err().chars().filter(c -> c == '\n').count() == 1,
           "status on a refusal answers " + run.exit() + ": " + run.err());
    StandInApi unauthorized = new StandInApi();
    unauthorized.status = 401;
    expect(exec(unauthorized, env, "status").exit() == 78, "status with a refused root credential");
    StandInApi older = new StandInApi();
    older.status = 404;
    run = exec(older, env, "status");
    expect(run.exit() == 1 && run.err().trim().equals(EmbedServer.EMBED_UNSUPPORTED_MESSAGE),
           "status on a server without Embed enablement answers " + run.exit() + ": " + run.err());
    for (String invalid : List.of(
             "{\"enabled\":true}", "{\"enabled\":\"yes\",\"client_limit\":1,\"active_client_count\":1}",
             "{\"enabled\":true,\"client_limit\":-1,\"active_client_count\":0}",
             "{\"enabled\":true,\"client_limit\":1.5,\"active_client_count\":0}",
             "{\"enabled\":1,\"client_limit\":1,\"active_client_count\":1}",
             "{\"enabled\":true,\"client_limit\":\"1\",\"active_client_count\":1}")) {
      StandInApi answering = new StandInApi();
      answering.embedAnswer = invalid;
      run = exec(answering, env, "status");
      expect(run.exit() == 1 && run.out().isEmpty(), "status accepted " + invalid);
    }
  }

  /** The unmapped-key and 404 texts. */
  private static void checkFailureTexts(Path dir) {
    StandInApi api = new StandInApi();
    Function<String, String> env = env(dir, "texts.json");
    String[][] unmapped = {
        {"cap", "user:nobody", "--monthly", "1"}, {"usage", "user:nobody"}, {"remove", "user:nobody"},
        {"acl", "user:nobody", "default"},
    };
    for (String[] args : unmapped) {
      Run run = exec(api, env, args);
      expect(run.exit() == 78 && run.err().strip().equals(EmbedServer.UNMAPPED_MESSAGE),
             args[0] + " for an unmapped key answers " + run.exit() + ": " + run.err());
    }
    expect(api.requests.isEmpty(), "an unmapped key reached the API");

    exec(api, env, "provision", "user:olga", dir.resolve("olga.jwt").toString());
    StandInApi older = new StandInApi();
    older.status = 404;
    String[][] cases = {
        {EmbedServer.CAP_PATH, "cap", "user:olga", "--monthly", "1"},
        {EmbedServer.CAP_PATH, "usage", "user:olga"},
        {EmbedServer.CAPS_PATH, "usage-all"},
    };
    for (String[] c : cases) {
      Run run = exec(older, env, Arrays.copyOfRange(c, 1, c.length));
      expect(run.exit() == 1 && run.err().strip().equals(c[0] + " answered 404: the server predates the data-cap routes"),
             c[1] + " on a server without the cap routes answers " + run.exit() + ": " + run.err());
    }
  }

  /** No client JWT and no root credential reached stdout or stderr in any run. */
  private static void checkNothingSecretPrinted() {
    String output = ALL_OUTPUT.toString();
    expect(!StandInApi.ALL_ISSUED_JWTS.isEmpty(), "no client JWT was issued");
    for (String token : StandInApi.ALL_ISSUED_JWTS) {
      expect(!output.contains(token), "a client JWT reached stdout or stderr");
    }
    expect(!output.contains(ROOT), "the root credential reached stdout or stderr");
  }
}
