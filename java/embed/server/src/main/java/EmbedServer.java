// SERVER ONLY. The Java embed backend tool (EMBED_CONTRACT.md, "Backend
// tools"): the integration allocator (../../integration/server) extended with
// what a backend needs to embed URnetwork. It provisions one client per user
// installation, sets and reads that client's data caps, reads every capped
// client of the network, removes a client, and sets a client's ACL group.
// Your service authenticates its user first and supplies the key internally:
// never take a key, a client ID or a cap from a raw request field.
//
//   provision <key> <client-jwt-file>
//   cap <key> [--monthly <bytes>|--monthly null] [--total <bytes>|--total null] [--reset-total]
//   usage <key>
//   usage-all
//   remove <key>
//   acl <key> default|isolated
//   --self-test
//
// <key> is user:<service-user-id> or user:<service-user-id>:<installation-id>.
// Settings: URNETWORK_ROOT_JWT (an API key or a network JWT, from the
// backend's secret store), URNETWORK_CLIENT_MAP (absolute path of this tool's
// private map, in an existing service-owned directory; never the token
// server's map), optional URNETWORK_API_URL and optional
// URNETWORK_DEFAULT_ACL_GROUP (the ACL group of each new client: "isolated",
// the default, or "default"). The map lock is a <map>.lock directory; a crash
// may leave it, so remove it only after confirming that no tool still runs.
//
// Exit codes: 0 success; 78 a configuration or credential problem (missing
// settings, an invalid key or map, the root credential refused, the client
// limit); 1 any other failure, with one stderr line that never holds a secret.
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

public final class EmbedServer {
  static final int EXIT_OK = 0;
  static final int EXIT_FAILURE = 1;
  // sysexits EX_CONFIG
  static final int EXIT_CONFIG = 78;
  static final int LIMIT = 1 << 20;

  static final ObjectMapper JSON = new ObjectMapper();
  static final String DESCRIPTION = "embed client";
  static final String DEVICE_SPEC = "urnetwork-examples/java-embed-server";
  static final String CLIENT_DOES_NOT_EXIST = "Client does not exist.";
  static final String CLIENT_LIMIT_MESSAGE =
      "client limit reached: your network is at its client limit; see https://ur.io/services";
  static final String AUTH_CLIENT_PATH = "/network/auth-client";
  static final String CAP_PATH = "/network/client-data-cap";
  static final String CAPS_PATH = "/network/client-data-caps";
  static final String REMOVE_PATH = "/network/remove-client";
  static final String ACL_PATH = "/network/client-acl-group";
  static final String UNMAPPED_MESSAGE = "no client is mapped for that key; run provision first";
  static final String ACL_UNSUPPORTED_MESSAGE = "/network/client-acl-group answered 404: the server predates ACL groups";
  static final String USAGE =
      "usage: provision <key> <client-jwt-file> | cap <key> [--monthly <bytes>|--monthly null] [--total <bytes>|--total null] [--reset-total] | usage <key> | usage-all | remove <key> | acl <key> default|isolated | --self-test";

  static final Pattern KEY = Pattern.compile("user:[A-Za-z0-9][A-Za-z0-9_.:@-]{0,122}");
  static final Pattern ID = Pattern.compile("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}");
  private static final Pattern BYTE_COUNT = Pattern.compile("[0-9]{1,19}");

  /** Static members only. */
  private EmbedServer() {}

  /** A failure with the exit code it maps to. */
  static final class ToolException extends Exception {
    private static final long serialVersionUID = 1;
    final int exitCode;

    /** A failure with its exit code and its one stderr line. */
    ToolException(int exitCode, String message) {
      super(message);
      this.exitCode = exitCode;
    }
  }

  /** One request to the URnetwork API, as the transport sees it. */
  record ApiRequest(String method, String pathAndQuery, String body) {}

  /** The answer to one request: the HTTP status and the body. */
  record ApiResponse(int status, String body) {}

  /** The API transport: HTTPS in production, a stand-in in the self-test. */
  @FunctionalInterface
  interface Transport {
    ApiResponse send(ApiRequest request) throws IOException, InterruptedException;
  }

  /** The transport for an origin and root credential. */
  @FunctionalInterface
  interface TransportFactory {
    Transport create(URI origin, String root);
  }

  /**
   * The options of the cap command. A limit that is given with a null value clears that cap; one
   * that is not given is not sent, so the cap keeps its value.
   */
  static final class CapOptions {
    boolean monthlyGiven;
    Long monthly;
    boolean totalGiven;
    Long total;
    boolean resetTotal;
  }

  /**
   * The settings of one run: the transport, for the commands that use it the map file, and the
   * ACL group of each new client ("isolated" or "default").
   */
  private record Settings(Transport transport, Path mapFile, String aclGroup) {}

  /** The mapped client no longer exists. */
  private static final class ClientMissingException extends Exception {
    private static final long serialVersionUID = 1;
  }

  /** Runs the command with the process environment and HTTPS. */
  public static void main(String[] args) {
    System.exit(run(args, System::getenv, System.out, System.err, EmbedServer::httpTransport));
  }

  /**
   * Runs one command and returns its exit code. The self-test passes its own environment, streams
   * and stand-in transport.
   */
  static int run(String[] args, Function<String, String> env, PrintStream out, PrintStream err,
                 TransportFactory transport) {
    try {
      String command = args.length == 0 ? "" : args[0];
      switch (command) {
        case "--self-test" -> {
          if (args.length != 1) {
            throw config(USAGE);
          }
          try {
            SelfTest.run();
          } catch (Exception e) {
            err.println("embed backend tool self-test failed: " + e.getMessage());
            return EXIT_FAILURE;
          }
          out.println("embed backend tool self-test passed");
        }
        case "provision" -> {
          if (args.length != 3) {
            throw config(USAGE);
          }
          provision(checkKey(args[1]), checkJwtFile(args[2]), settings(env, transport, true), out);
        }
        case "cap" -> {
          if (args.length < 2) {
            throw config(USAGE);
          }
          cap(checkKey(args[1]), parseCapOptions(Arrays.copyOfRange(args, 2, args.length)),
              settings(env, transport, true), out);
        }
        case "usage" -> {
          if (args.length != 2) {
            throw config(USAGE);
          }
          readUsage(checkKey(args[1]), settings(env, transport, true), out);
        }
        case "usage-all" -> {
          if (args.length != 1) {
            throw config(USAGE);
          }
          readUsageAll(settings(env, transport, false), out);
        }
        case "remove" -> {
          if (args.length != 2) {
            throw config(USAGE);
          }
          remove(checkKey(args[1]), settings(env, transport, true), out);
        }
        case "acl" -> {
          if (args.length != 3 || !(args[2].equals("default") || args[2].equals("isolated"))) {
            throw config(USAGE);
          }
          acl(checkKey(args[1]), args[2], settings(env, transport, true), out);
        }
        default -> throw config(USAGE);
      }
      return EXIT_OK;
    } catch (ToolException e) {
      err.println(e.getMessage());
      return e.exitCode;
    } catch (IOException e) {
      err.println("embed backend tool failed: " + Objects.toString(e.getMessage(), e.getClass().getSimpleName()));
      return EXIT_FAILURE;
    }
  }

  /** A configuration error (78). */
  static ToolException config(String message) {
    return new ToolException(EXIT_CONFIG, message);
  }

  /** The API refused a request (1), with its message made safe to print. */
  private static ToolException refused(String message) {
    return new ToolException(EXIT_FAILURE, "the URnetwork API refused the request: " + printable(message));
  }

  /**
   * Reissues the key's client, or provisions a new one, and writes the client JWT to jwtFile. A
   * reissue that answers "Client does not exist." (a client deactivated after 30 days without
   * connecting) drops the mapping and provisions a new client. Prints {"client_id": ...}, never the
   * token.
   */
  private static void provision(String key, Path jwtFile, Settings settings, PrintStream out)
      throws ToolException, IOException {
    Path mapFile = settings.mapFile();
    Path lock = takeLock(mapFile);
    try {
      ObjectNode map = loadMap(mapFile);
      ObjectNode clients = (ObjectNode)map.get("clients");
      String mapped = clients.path(key).textValue();
      String[] result = null;
      if (mapped != null) {
        try {
          result = authClientResult(call(settings, "POST", AUTH_CLIENT_PATH, authClientBody(mapped)), mapped);
        } catch (ClientMissingException e) {
          clients.remove(key);
          setAclPending(map, key, false);
          saveMap(mapFile, map);
        }
      }
      if (result == null) {
        try {
          result = authClientResult(call(settings, "POST", AUTH_CLIENT_PATH, authClientBody(null)), null);
        } catch (ClientMissingException e) {
          throw refused(CLIENT_DOES_NOT_EXIST);
        }
        for (JsonNode existing : clients) {
          if (result[0].equals(existing.textValue())) {
            throw new ToolException(EXIT_FAILURE, "the API answered a client that the map assigns to another key");
          }
        }
        clients.put(key, result[0]);
        // the mapping and the record that the client owes its group, in one save
        if (settings.aclGroup().equals("isolated")) {
          setAclPending(map, key, true);
        }
        saveMap(mapFile, map);
      }
      if (aclPending(map, key)) {
        // a new client is "default": only an isolated default needs the request. A failure throws
        // with the record kept, before any client JWT is written.
        if (settings.aclGroup().equals("isolated")) {
          postAclGroup(settings, result[0], "isolated");
        }
        setAclPending(map, key, false);
        saveMap(mapFile, map);
      }
      writePrivate(jwtFile, (result[1] + "\n").getBytes(StandardCharsets.UTF_8));
      out.println(JSON.writeValueAsString(JSON.createObjectNode().put("client_id", result[0])));
    } finally {
      Files.deleteIfExists(lock);
    }
  }

  /** Posts only the given cap fields for the key's client and prints the cap object. */
  private static void cap(String key, CapOptions options, Settings settings, PrintStream out)
      throws ToolException, IOException {
    Path lock = takeLock(settings.mapFile());
    try {
      String clientId = mappedClient(loadMap(settings.mapFile()), key);
      out.println(capObject(call(settings, "POST", CAP_PATH, capBody(clientId, options))));
    } finally {
      Files.deleteIfExists(lock);
    }
  }

  /** Prints the key's cap object, read with the root credential. */
  private static void readUsage(String key, Settings settings, PrintStream out)
      throws ToolException, IOException {
    Path lock = takeLock(settings.mapFile());
    try {
      String clientId = mappedClient(loadMap(settings.mapFile()), key);
      out.println(capObject(call(settings, "GET", CAP_PATH + "?client_id=" + encode(clientId), null)));
    } finally {
      Files.deleteIfExists(lock);
    }
  }

  /**
   * Pages through every capped client of the network, 1000 at a time, and prints one cap object per
   * line. It stops at a null cursor or a repeated one.
   */
  private static void readUsageAll(Settings settings, PrintStream out) throws ToolException, IOException {
    Set<String> seenCursors = new HashSet<>();
    String cursor = null;
    while (true) {
      String path = CAPS_PATH + "?limit=1000" + (cursor == null ? "" : "&cursor=" + encode(cursor));
      ObjectNode page = call(settings, "GET", path, null);
      checkRefusal(page);
      JsonNode clients = page.get("clients");
      if (clients == null || !clients.isArray()) {
        throw new ToolException(EXIT_FAILURE, "the URnetwork API answered a page without clients");
      }
      for (JsonNode client : clients) {
        if (!client.isObject()) {
          throw new ToolException(EXIT_FAILURE, "the URnetwork API answered a client that is not a cap object");
        }
        out.println(JSON.writeValueAsString(client));
      }
      String next = page.path("next_cursor").textValue();
      if (next == null || next.isEmpty() || !seenCursors.add(next)) {
        return;
      }
      cursor = next;
    }
  }

  /** Removes the key's client, then its mapping, also when the client is already gone. */
  private static void remove(String key, Settings settings, PrintStream out) throws ToolException, IOException {
    Path mapFile = settings.mapFile();
    Path lock = takeLock(mapFile);
    try {
      ObjectNode map = loadMap(mapFile);
      String clientId = mappedClient(map, key);
      ObjectNode answer = call(settings, "POST", REMOVE_PATH, JSON.createObjectNode().put("client_id", clientId));
      JsonNode error = answer.get("error");
      if (error != null && !error.isNull()) {
        String message = Objects.toString(error.path("message").textValue(), "");
        if (!message.equals(CLIENT_DOES_NOT_EXIST)) {
          throw refused(message);
        }
      }
      ((ObjectNode)map.get("clients")).remove(key);
      setAclPending(map, key, false);
      saveMap(mapFile, map);
      out.println(JSON.writeValueAsString(JSON.createObjectNode().put("removed", clientId)));
    } finally {
      Files.deleteIfExists(lock);
    }
  }

  /**
   * Sets the ACL group of the key's client and prints {"client_id": ..., "acl_group": ...}. An
   * explicit group settles a pending default group, so the record is dropped.
   */
  private static void acl(String key, String group, Settings settings, PrintStream out)
      throws ToolException, IOException {
    Path mapFile = settings.mapFile();
    Path lock = takeLock(mapFile);
    try {
      ObjectNode map = loadMap(mapFile);
      String clientId = mappedClient(map, key);
      postAclGroup(settings, clientId, group);
      if (aclPending(map, key)) {
        setAclPending(map, key, false);
        saveMap(mapFile, map);
      }
      out.println(JSON.writeValueAsString(JSON.createObjectNode().put("client_id", clientId).put("acl_group", group)));
    } finally {
      Files.deleteIfExists(lock);
    }
  }

  /** Posts the client's ACL group; the answer must name the client and the group. */
  private static void postAclGroup(Settings settings, String clientId, String group) throws ToolException, IOException {
    ObjectNode answer = call(settings, "POST", ACL_PATH,
                             JSON.createObjectNode().put("client_id", clientId).put("acl_group", group));
    checkRefusal(answer);
    if (!clientId.equals(answer.path("client_id").textValue()) || !group.equals(answer.path("acl_group").textValue())) {
      throw new ToolException(EXIT_FAILURE, "the URnetwork API answered another client or ACL group");
    }
  }

  /** Whether key owes its default ACL group: the map's pending_acl. */
  static boolean aclPending(ObjectNode map, String key) {
    for (JsonNode entry : map.path("pending_acl")) {
      if (key.equals(entry.textValue())) {
        return true;
      }
    }
    return false;
  }

  /** Records that key owes its default ACL group, or drops the record. */
  static void setAclPending(ObjectNode map, String key, boolean pending) {
    ArrayNode keys = JSON.createArrayNode();
    for (JsonNode entry : map.path("pending_acl")) {
      if (!key.equals(entry.textValue())) {
        keys.add(entry);
      }
    }
    if (pending) {
      keys.add(key);
    }
    map.set("pending_acl", keys);
  }

  /**
   * The auth-client request: a new client without client_id, or a reissue of the mapped one. Never
   * source_client_id: embed clients are top-level.
   */
  static ObjectNode authClientBody(String clientId) {
    ObjectNode body = JSON.createObjectNode().put("description", DESCRIPTION).put("device_spec", DEVICE_SPEC);
    if (clientId != null) {
      body.put("client_id", clientId);
    }
    return body;
  }

  /**
   * The client id and client JWT of an auth-client answer. A refusal with either client limit flag
   * is the client limit (78); "Client does not exist." throws ClientMissingException; other
   * refusals fail. The JWT's client_id claim must match the answer, and a reissue must answer the
   * mapped client. The claim check is not signature verification.
   */
  private static String[] authClientResult(ObjectNode answer, String expected)
      throws ToolException, ClientMissingException {
    JsonNode error = answer.get("error");
    if (error != null && !error.isNull()) {
      if (error.path("client_limit_exceeded").asBoolean(false) || error.path("upgrade_required").asBoolean(false)) {
        throw new ToolException(EXIT_CONFIG, CLIENT_LIMIT_MESSAGE);
      }
      String message = Objects.toString(error.path("message").textValue(), "");
      if (message.equals(CLIENT_DOES_NOT_EXIST)) {
        throw new ClientMissingException();
      }
      throw refused(message);
    }
    String id = Objects.toString(answer.path("client_id").textValue(), "");
    String jwt = Objects.toString(answer.path("by_client_jwt").textValue(), "");
    String[] parts = jwt.split("\\.", -1);
    if (!ID.matcher(id).matches() || parts.length != 3 || Arrays.stream(parts).anyMatch(String::isEmpty) ||
        !parts[1].matches("[A-Za-z0-9_-]+")) {
      throw new ToolException(EXIT_FAILURE, "the URnetwork API answered an invalid client");
    }
    String claim = null;
    try {
      JsonNode claims = JSON.readTree(Base64.getUrlDecoder().decode(parts[1]));
      claim = claims == null ? null : claims.path("client_id").textValue();
    } catch (IOException | IllegalArgumentException e) {
      // reported below
    }
    if (!id.equals(claim) || (expected != null && !expected.equals(id))) {
      throw new ToolException(EXIT_FAILURE, "the URnetwork API answered a client JWT for another client");
    }
    return new String[] {id, jwt};
  }

  /**
   * The cap request: client_id and only the given fields. A cleared cap is JSON null; byte counts
   * are JSON integers; reset_total only when given.
   */
  static ObjectNode capBody(String clientId, CapOptions options) {
    ObjectNode body = JSON.createObjectNode().put("client_id", clientId);
    if (options.monthlyGiven) {
      if (options.monthly == null) {
        body.putNull("monthly_byte_limit");
      } else {
        body.put("monthly_byte_limit", options.monthly.longValue());
      }
    }
    if (options.totalGiven) {
      if (options.total == null) {
        body.putNull("total_byte_limit");
      } else {
        body.put("total_byte_limit", options.total.longValue());
      }
    }
    if (options.resetTotal) {
      body.put("reset_total", true);
    }
    return body;
  }

  /** The cap options; at least one, each at most once. A usage error is a configuration error. */
  static CapOptions parseCapOptions(String[] args) throws ToolException {
    String usage = "cap needs at least one of --monthly <bytes>|null, --total <bytes>|null and --reset-total, each at most once";
    CapOptions options = new CapOptions();
    int index = 0;
    while (index < args.length) {
      String option = args[index];
      boolean hasValue = index + 1 < args.length;
      if (option.equals("--monthly") && !options.monthlyGiven && hasValue) {
        options.monthlyGiven = true;
        options.monthly = byteLimit(args[index + 1]);
        index += 2;
      } else if (option.equals("--total") && !options.totalGiven && hasValue) {
        options.totalGiven = true;
        options.total = byteLimit(args[index + 1]);
        index += 2;
      } else if (option.equals("--reset-total") && !options.resetTotal) {
        options.resetTotal = true;
        index += 1;
      } else {
        throw config(usage);
      }
    }
    if (!options.monthlyGiven && !options.totalGiven && !options.resetTotal) {
      throw config(usage);
    }
    return options;
  }

  /** A byte count from 0 to 9223372036854775807 without units, or null. */
  private static Long byteLimit(String text) throws ToolException {
    if (text.equals("null")) {
      return null;
    }
    if (BYTE_COUNT.matcher(text).matches()) {
      try {
        return Long.parseLong(text);
      } catch (NumberFormatException e) {
        // beyond a long, refused below
      }
    }
    throw config("a byte count is a decimal integer from 0 to 9223372036854775807 without units, or null");
  }

  /** The cap object of an answer, as one line of JSON; a refusal fails. */
  private static String capObject(ObjectNode answer) throws ToolException, JsonProcessingException {
    checkRefusal(answer);
    return JSON.writeValueAsString(answer);
  }

  /** Fails when an answer carries a refusal. */
  private static void checkRefusal(ObjectNode answer) throws ToolException {
    JsonNode error = answer.get("error");
    if (error != null && !error.isNull()) {
      throw refused(Objects.toString(error.path("message").textValue(), ""));
    }
  }

  /** The client mapped to key; an unmapped key is a configuration error. */
  private static String mappedClient(ObjectNode map, String key) throws ToolException {
    String clientId = map.get("clients").path(key).textValue();
    if (clientId == null) {
      throw config(UNMAPPED_MESSAGE);
    }
    return clientId;
  }

  /** A valid key: user:<service-user-id>, optionally with :<installation-id>. */
  private static String checkKey(String key) throws ToolException {
    if (!KEY.matcher(key).matches()) {
      throw config("expected a key user:<service-user-id>[:<installation-id>]");
    }
    return key;
  }

  /** The client JWT file: its directory must exist; a symlink or a directory is refused. */
  private static Path checkJwtFile(String text) throws ToolException {
    Path path;
    try {
      path = Path.of(text).toAbsolutePath();
    } catch (InvalidPathException e) {
      throw config("the client JWT file path is not valid");
    }
    if (path.getParent() == null || !Files.isDirectory(path.getParent()) || Files.isDirectory(path) ||
        Files.isSymbolicLink(path)) {
      throw config("the client JWT file needs an existing directory and must not be a symlink");
    }
    return path;
  }

  /**
   * The settings, checked: the root credential, the API origin and, when the command uses it, the
   * map file.
   */
  private static Settings settings(Function<String, String> env, TransportFactory transport, boolean map)
      throws ToolException {
    String root = Objects.toString(env.apply("URNETWORK_ROOT_JWT"), "");
    if (root.isEmpty() || root.chars().anyMatch(c -> c <= ' ' || c > '~')) {
      throw config("set URNETWORK_ROOT_JWT to the root credential (an API key or a network JWT) from the backend's secret store");
    }
    URI origin = endpoint(Objects.toString(env.apply("URNETWORK_API_URL"), "https://api.bringyour.com"));
    Path mapFile = null;
    if (map) {
      String mapText = env.apply("URNETWORK_CLIENT_MAP");
      try {
        mapFile = mapText == null ? null : Path.of(mapText);
      } catch (InvalidPathException e) {
        mapFile = null;
      }
      if (mapFile == null || !mapFile.isAbsolute() || mapFile.getParent() == null ||
          !Files.isDirectory(mapFile.getParent())) {
        throw config("set URNETWORK_CLIENT_MAP to an absolute file path in an existing private, service-owned directory");
      }
    }
    String aclGroup = Objects.toString(env.apply("URNETWORK_DEFAULT_ACL_GROUP"), "");
    if (!Set.of("", "isolated", "default").contains(aclGroup)) {
      throw config("URNETWORK_DEFAULT_ACL_GROUP must be default or isolated");
    }
    return new Settings(transport.create(origin, root), mapFile, aclGroup.isEmpty() ? "isolated" : aclGroup);
  }

  /** The API origin: HTTPS, or explicit loopback HTTP for local mocks. */
  static URI endpoint(String value) throws ToolException {
    URI u;
    try {
      u = URI.create(value);
    } catch (IllegalArgumentException e) {
      throw config("URNETWORK_API_URL must be an HTTPS origin");
    }
    boolean local = Set.of("localhost", "127.0.0.1", "[::1]", "::1").contains(Objects.toString(u.getHost(), ""));
    if (u.getHost() == null || u.getUserInfo() != null || !Set.of("", "/").contains(Objects.toString(u.getRawPath(), "")) ||
        u.getRawQuery() != null || u.getRawFragment() != null ||
        (!"https".equals(u.getScheme()) && !("http".equals(u.getScheme()) && local))) {
      throw config("URNETWORK_API_URL must be an HTTPS origin, or explicit loopback HTTP for a mock");
    }
    return URI.create(u.getScheme() + "://" + u.getRawAuthority() + "/");
  }

  /**
   * The JSON object of a 2xx answer. HTTP 401 or 403 is the root credential refused (78); another
   * status, an unreachable API or a body that is not a JSON object is a failure (1). A refusal
   * inside the object (an "error" member) is for the caller.
   */
  private static ObjectNode call(Settings settings, String method, String pathAndQuery, ObjectNode body)
      throws ToolException, IOException {
    ApiResponse response;
    try {
      response = settings.transport().send(new ApiRequest(method, pathAndQuery,
                                                          body == null ? null : JSON.writeValueAsString(body)));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ToolException(EXIT_FAILURE, "interrupted");
    } catch (IOException e) {
      throw new ToolException(EXIT_FAILURE, "could not reach the URnetwork API");
    }
    if (response.status() == 401 || response.status() == 403) {
      throw new ToolException(EXIT_CONFIG, "the URnetwork API refused the root credential");
    }
    if (response.status() == 404) {
      // a server that predates a route (EMBED_CONTRACT.md, "Backend tools")
      String route = pathAndQuery.split("\\?", 2)[0];
      throw new ToolException(EXIT_FAILURE, route.equals(ACL_PATH) ? ACL_UNSUPPORTED_MESSAGE
                                            : route.startsWith(CAP_PATH) ? route + " answered 404: the server predates the data-cap routes"
                                                                         : route + " answered 404");
    }
    if (response.status() < 200 || 299 < response.status()) {
      throw new ToolException(EXIT_FAILURE, "the URnetwork API answered HTTP " + response.status());
    }
    JsonNode node = null;
    try {
      node = JSON.readTree(Objects.toString(response.body(), ""));
    } catch (JsonProcessingException e) {
      // reported below
    }
    if (!(node instanceof ObjectNode object)) {
      throw new ToolException(EXIT_FAILURE, "the URnetwork API answered something other than a JSON object");
    }
    return object;
  }

  /** HTTPS to the API with the root credential: 15 seconds, no redirects, a bounded answer. */
  private static Transport httpTransport(URI origin, String root) {
    HttpClient client = HttpClient.newBuilder()
                            .connectTimeout(Duration.ofSeconds(15))
                            .followRedirects(HttpClient.Redirect.NEVER)
                            .build();
    return request -> {
      HttpRequest.Builder builder = HttpRequest.newBuilder(origin.resolve(request.pathAndQuery()))
                                        .timeout(Duration.ofSeconds(15))
                                        .header("Authorization", "Bearer " + root);
      if (request.body() != null) {
        builder.header("Content-Type", "application/json")
            .method(request.method(), HttpRequest.BodyPublishers.ofString(request.body()));
      } else {
        builder.method(request.method(), HttpRequest.BodyPublishers.noBody());
      }
      HttpResponse<InputStream> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
      try (InputStream input = response.body()) {
        byte[] data = input.readNBytes(LIMIT + 1);
        if (LIMIT < data.length) {
          throw new IOException("the answer is too large");
        }
        return new ApiResponse(response.statusCode(), new String(data, StandardCharsets.UTF_8));
      }
    };
  }

  /**
   * The map in file; an empty map when the file does not exist. Besides version and clients it may
   * hold pending_acl, the keys whose new clients still owe their default ACL group. A map with
   * fields this tool does not write, such as the token server's pending_caps, is refused, so that
   * two tools never rewrite each other's map.
   */
  static ObjectNode loadMap(Path file) throws ToolException, IOException {
    if (Files.isSymbolicLink(file)) {
      throw config("the client map must be a regular file");
    }
    if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
      ObjectNode map = JSON.createObjectNode().put("version", 1);
      map.set("clients", JSON.createObjectNode());
      return map;
    }
    if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > LIMIT) {
      throw config("the client map must be a regular file");
    }
    if (posix(file) && !Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
                            .containsAll(Files.getPosixFilePermissions(file, LinkOption.NOFOLLOW_LINKS))) {
      throw config("the client map must be private (0600)");
    }
    JsonNode parsed;
    try {
      parsed = JSON.readTree(Files.readString(file));
    } catch (JsonProcessingException e) {
      throw config("the client map is not a JSON object");
    }
    if (!(parsed instanceof ObjectNode map)) {
      throw config("the client map is not a JSON object");
    }
    for (Map.Entry<String, JsonNode> member : map.properties()) {
      String name = member.getKey();
      if (!name.equals("version") && !name.equals("clients") && !name.equals("pending_acl")) {
        throw config("the client map has fields this tool does not write, such as the token server's pending_caps; give each tool its own map");
      }
    }
    if (!map.path("version").isIntegralNumber() || map.get("version").intValue() != 1 || !map.path("clients").isObject()) {
      throw config("the client map is not version 1 with a clients object");
    }
    Set<String> seen = new HashSet<>();
    for (Map.Entry<String, JsonNode> entry : map.get("clients").properties()) {
      String id = entry.getValue().textValue();
      if (!KEY.matcher(entry.getKey()).matches() || id == null || !ID.matcher(id).matches() || !seen.add(id)) {
        throw config("the client map has an invalid entry");
      }
    }
    if (map.has("pending_acl")) {
      Set<String> pending = new HashSet<>();
      JsonNode keys = map.get("pending_acl");
      if (!keys.isArray()) {
        throw config("the client map's pending_acl is not a list of mapped keys");
      }
      for (JsonNode entry : keys) {
        String key = entry.textValue();
        if (key == null || !map.get("clients").has(key) || !pending.add(key)) {
          throw config("the client map's pending_acl is not a list of mapped keys");
        }
      }
    }
    return map;
  }

  /** Replaces the map atomically, private to its owner; pending_acl only while it is not empty. */
  static void saveMap(Path file, ObjectNode map) throws IOException {
    Map<String, Object> ordered = new LinkedHashMap<>();
    ordered.put("version", 1);
    ordered.put("clients", map.get("clients"));
    if (!map.path("pending_acl").isEmpty()) {
      ordered.put("pending_acl", map.get("pending_acl"));
    }
    writePrivate(file, JSON.writeValueAsBytes(ordered));
  }

  /**
   * Takes the map's exclusive lock: a <map>.lock directory created exclusively and held through the
   * remote calls and the map update. The C# tool creates a <map>.lock file at the same path, so the
   * tools exclude each other.
   */
  static Path takeLock(Path mapFile) throws ToolException, IOException {
    Path lock = Path.of(mapFile + ".lock");
    try {
      if (posix(mapFile.getParent())) {
        Files.createDirectory(lock, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
      } else {
        Files.createDirectory(lock);
      }
    } catch (FileAlreadyExistsException e) {
      throw new ToolException(EXIT_FAILURE,
                              "another run holds the client map's lock; retry, and remove a stale .lock only after confirming that no tool still runs");
    }
    return lock;
  }

  /**
   * Replaces file atomically: a private temporary file in the same directory, synced, then renamed
   * over it.
   */
  static void writePrivate(Path file, byte[] data) throws IOException {
    Path dir = file.toAbsolutePath().getParent();
    String prefix = "." + file.getFileName() + ".";
    Path temp = posix(dir)
                    ? Files.createTempFile(dir, prefix, ".tmp", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
                    : Files.createTempFile(dir, prefix, ".tmp");
    try {
      try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
        ByteBuffer buffer = ByteBuffer.wrap(data);
        while (buffer.hasRemaining()) {
          channel.write(buffer);
        }
        channel.force(true);
      }
      Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } finally {
      Files.deleteIfExists(temp);
    }
  }

  /** Whether the file system of path has POSIX permissions; Windows does not. */
  static boolean posix(Path path) {
    return path.getFileSystem().supportedFileAttributeViews().contains("posix");
  }

  /** A query parameter value, URL-encoded. */
  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  /** Text safe for one stderr line: no control characters, bounded. */
  private static String printable(String text) {
    StringBuilder out = new StringBuilder();
    text.codePoints().limit(200).forEach(c -> out.appendCodePoint(Character.isISOControl(c) ? ' ' : c));
    return out.toString().strip();
  }
}
