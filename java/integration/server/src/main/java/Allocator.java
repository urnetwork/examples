// SERVER ONLY. The authenticated backend supplies user:<service-user-id> internally;
// never accept a UR client ID or a raw request field as this key. Set URNETWORK_ROOT_JWT,
// URNETWORK_CLIENT_MAP (absolute file path under an existing service-owned directory),
// and optional URNETWORK_API_URL. A crash may leave .lock; confirm it is stale before removal.
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.time.Duration;
import java.util.*;
import java.util.regex.Pattern;
import static java.nio.file.StandardOpenOption.*;

public final class Allocator {
    static final ObjectMapper JSON = new ObjectMapper();
    static final int LIMIT = 1 << 20;
    static final Pattern USER = Pattern.compile("user:[A-Za-z0-9][A-Za-z0-9_.:@-]{0,122}");
    static final Pattern ID = Pattern.compile("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}");
    interface Call { String post(ObjectNode body) throws Exception; }
    interface Action { void run() throws Exception; }
    static String serviceUser(String[] args) {
        if (args.length != 1 || !USER.matcher(args[0]).matches()) throw new IllegalArgumentException("expected one user:<service-user-id>");
        return args[0];
    }
    static URI endpoint(String value) {
        URI u = URI.create(value);
        boolean local = Set.of("localhost", "127.0.0.1", "[::1]", "::1").contains(Objects.toString(u.getHost(), ""));
        if (u.getHost() == null || u.getUserInfo() != null || !Set.of("", "/").contains(u.getRawPath()) ||
            u.getRawQuery() != null || u.getRawFragment() != null ||
            (!"https".equals(u.getScheme()) && !("http".equals(u.getScheme()) && local))) throw new IllegalArgumentException("invalid API origin");
        return u.resolve("/network/auth-client");
    }
    static ObjectNode requestFor(String user, String client) {
        serviceUser(new String[]{user});
        ObjectNode body = JSON.createObjectNode().put("description", "service " + user).put("device_spec", "urnetwork-examples/java-server");
        if (client != null) {
            if (!ID.matcher(client).matches()) throw new IllegalArgumentException("invalid mapped client");
            body.put("client_id", client);
        }
        return body;
    }
    static ObjectNode parseResponse(String raw, String expected) throws Exception {
        JsonNode obj = JSON.readTree(raw);
        if (obj == null || !obj.isObject() || (obj.has("error") && !obj.get("error").isNull()) || !obj.path("client_id").isTextual() || !obj.path("by_client_jwt").isTextual()) throw new IllegalArgumentException("API failure");
        String id = obj.get("client_id").textValue(), jwt = obj.get("by_client_jwt").textValue();
        String[] parts = jwt.split("\\.", -1);
        if (!ID.matcher(id).matches() || parts.length != 3 || Arrays.stream(parts).anyMatch(String::isEmpty) || !parts[1].matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException("invalid scoped result");
        JsonNode claims = JSON.readTree(Base64.getUrlDecoder().decode(parts[1]));
        if (claims == null || !id.equals(claims.path("client_id").textValue()) || (expected != null && !expected.equals(id))) throw new IllegalArgumentException("scoped identity mismatch");
        // Consistency check only; this does not verify JWT signatures locally.
        return JSON.createObjectNode().put("client_id", id).put("by_client_jwt", jwt);
    }
    static boolean posix(Path file) throws Exception {return Files.getFileStore(file).supportsFileAttributeView("posix");}
    static ObjectNode loadMap(Path file) throws Exception {
        if (Files.isSymbolicLink(file)) throw new IllegalArgumentException("mapping cannot be a symlink");
        if (!Files.exists(file)) {
            ObjectNode map = JSON.createObjectNode().put("version", 1); map.set("clients", JSON.createObjectNode()); return map;
        }
        if (!Files.isRegularFile(file) || Files.size(file) > LIMIT) throw new IllegalArgumentException("invalid map file");
        if (posix(file)) {
            var permissions = Files.getPosixFilePermissions(file);
            if (!Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE).containsAll(permissions)) throw new IllegalArgumentException("mapping must be private (0600)");
        }
        JsonNode parsed = JSON.readTree(Files.readString(file));
        if (!(parsed instanceof ObjectNode map) || !map.path("version").isIntegralNumber() || map.get("version").intValue() != 1 || !map.path("clients").isObject()) throw new IllegalArgumentException("invalid map");
        Set<String> seen = new HashSet<>();
        var fields = map.get("clients").properties().iterator();
        while (fields.hasNext()) {
            var entry = fields.next(); String id = entry.getValue().textValue();
            if (!USER.matcher(entry.getKey()).matches() || id == null || !ID.matcher(id).matches() || !seen.add(id)) throw new IllegalArgumentException("invalid mapped client");
        }
        return map;
    }
    static void saveMap(Path file, ObjectNode map) throws Exception {
        Path temporary = posix(file.getParent()) ? Files.createTempFile(file.getParent(), "clients-", ".json", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))) : Files.createTempFile(file.getParent(), "clients-", ".json");
        try {
            Files.writeString(temporary, JSON.writeValueAsString(map), TRUNCATE_EXISTING);
            try (FileChannel output = FileChannel.open(temporary, WRITE)) { output.force(true); }
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }
    static ObjectNode allocate(String user, Path file, Call call) throws Exception {
        if (!file.isAbsolute() || !Files.isDirectory(file.getParent())) throw new IllegalArgumentException("mapping needs an absolute path and existing parent");
        Path lock = Path.of(file + ".lock");
        if (posix(file.getParent())) Files.createDirectory(lock, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        else Files.createDirectory(lock);
        try {
            ObjectNode map = loadMap(file), clients = (ObjectNode) map.get("clients");
            String old = clients.path(user).textValue();
            ObjectNode result = parseResponse(call.post(requestFor(user, old)), old);
            if (old == null) {
                String id = result.get("client_id").textValue();
                for (JsonNode existing : clients) if (id.equals(existing.textValue())) throw new IllegalArgumentException("client assigned to another user");
                clients.put(user, id); saveMap(file, map);
            }
            return result;
        } finally { Files.delete(lock); }
    }
    static String post(URI url, String root, ObjectNode body) throws Exception {
        if (root.isEmpty() || root.chars().anyMatch(Character::isWhitespace)) throw new IllegalArgumentException("set backend root JWT");
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NEVER).build();
        HttpRequest request = HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(15)).header("Authorization", "Bearer " + root).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300 || response.body().length() > LIMIT) throw new IllegalArgumentException("provisioning HTTP failure");
        return response.body();
    }
    static void check(boolean ok) { if (!ok) throw new IllegalStateException("self-test assertion failed"); }
    static void reject(Action action) throws Exception {try {action.run();} catch (Exception expected) {return;} throw new IllegalStateException("invalid input accepted");}
    static void selfTest() throws Exception {
        String id = "11111111-1111-1111-1111-111111111111";
        String jwt = "e30." + Base64.getUrlEncoder().withoutPadding().encodeToString(JSON.writeValueAsBytes(Map.of("client_id", id))) + ".test";
        String raw = JSON.writeValueAsString(Map.of("client_id", id, "by_client_jwt", jwt));
        Path dir = Files.createTempDirectory("ur-allocator-"), file = dir.resolve("clients.json");
        try {
            List<ObjectNode> calls = new ArrayList<>(); Call mock = body -> {calls.add(body); return raw;};
            check(allocate("user:alice", file, mock).get("client_id").textValue().equals(id));
            check(allocate("user:alice", file, mock).get("by_client_jwt").textValue().equals(jwt));
            check(!calls.get(0).has("client_id") && calls.get(1).get("client_id").textValue().equals(id));
            check(calls.stream().noneMatch(c -> c.has("source_client_id")) && loadMap(file).get("clients").get("user:alice").textValue().equals(id));
            check(endpoint("http://127.0.0.1:1234").getPath().equals("/network/auth-client"));
            reject(() -> serviceUser(new String[]{id})); reject(() -> serviceUser(new String[]{"user:a", "--client-id", id}));
            reject(() -> serviceUser(new String[]{"user:../a"})); reject(() -> endpoint("http://example.com"));
            reject(() -> endpoint("https://example.com/path")); reject(() -> parseResponse("{\"error\":{}}", null));
            reject(() -> parseResponse(raw, "22222222-2222-2222-2222-222222222222"));
        } finally {Files.deleteIfExists(file); Files.deleteIfExists(dir.resolve("clients.json.lock")); Files.delete(dir);}
        System.out.println("allocator self-test passed");
    }
    public static void main(String[] args) {
        try {
            if (Arrays.equals(args, new String[]{"--self-test"})) {selfTest(); return;}
            String user = serviceUser(args);
            URI url = endpoint(System.getenv().getOrDefault("URNETWORK_API_URL", "https://api.bringyour.com"));
            String root = Objects.requireNonNull(System.getenv("URNETWORK_ROOT_JWT"));
            Path file = Path.of(Objects.requireNonNull(System.getenv("URNETWORK_CLIENT_MAP")));
            System.out.println(JSON.writeValueAsString(allocate(user, file, body -> post(url, root, body))));
        } catch (Exception ignored) {System.err.println("allocator failed: check service key, private mapping and backend API configuration"); System.exit(1);}
    }
}
