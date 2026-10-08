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
      if (request.method().equals("POST") && path.equals(EmbedServer.REMOVE_PATH)) {
        String id = body.path("client_id").asText();
        return new EmbedServer.ApiResponse(200, missing.contains(id) ? "{\"error\":{\"message\":\"Client does not exist.\"}}" : "{}");
      }
      return new EmbedServer.ApiResponse(404, "404 page not found");
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

  /** The settings of a run with a map in dir. */
  private static Function<String, String> env(Path dir, String map) {
    Map<String, String> settings = Map.of("URNETWORK_ROOT_JWT", ROOT,
                                          "URNETWORK_CLIENT_MAP", dir.resolve(map).toString(),
                                          "URNETWORK_API_URL", "http://127.0.0.1:1234");
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
               "URNETWORK_API_URL", "http://api.example.com"));
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
