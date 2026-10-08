// The credential-free self-test (EMBED_CONTRACT.md, "Self-test"). It checks the
// status texts and rules, the data fields, the cap object, the client JWT
// claim, the token fetch against a stand-in server, the cap read and the
// installation state without a network, credentials, a device or the native
// SDK runtime: nothing it runs touches io.ur.sdk.Sdk, so it passes even where
// the SDK library is missing. The data is synthetic.
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.ConnectException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** Runs every check; a failed check throws with its reason. */
final class SelfTest {
  private static final String CLIENT_ID = "11111111-1111-1111-1111-111111111111";
  private static final String OTHER_CLIENT_ID = "33333333-3333-3333-3333-333333333333";
  private static final String INSTANCE_ID = "22222222-2222-2222-2222-222222222222";
  // a stand-in demo session token: at least 32 characters, as the token server
  // requires
  private static final String DEMO_SESSION = "selftest-demo-session-0123456789abcdef";
  private static final URI TOKEN_SERVER = URI.create("http://127.0.0.1:8790/");
  private static final URI API_ORIGIN = URI.create("https://api.bringyour.com/");

  /** Static members only. */
  private SelfTest() {}

  /** A check that may throw. */
  @FunctionalInterface
  private interface Check {
    void run() throws Exception;
  }

  /** Runs every check in order and stops at the first failure. */
  static void run() throws Exception {
    List<Check> checks = List.of(
        SelfTest::checkFormatByteCount, SelfTest::checkResetText, SelfTest::checkClientLimitText,
        SelfTest::checkStatusLines, SelfTest::checkStatusRules, SelfTest::checkDataFields,
        SelfTest::checkCapParsing, SelfTest::checkClientJwtClaims, SelfTest::checkOrigins,
        SelfTest::checkTokenFetch, SelfTest::checkCapRead, SelfTest::checkStateFiles,
        SelfTest::checkSettings, SelfTest::checkUsageExitCode);
    for (Check check : checks) {
      check.run();
    }
  }

  /** Fails with reason unless condition holds. */
  private static void expect(boolean condition, String reason) {
    if (!condition) {
      throw new IllegalStateException(reason);
    }
  }

  /** Fails with reason unless check throws type, and returns the exception. */
  private static <T extends Exception> T expectThrows(Class<T> type, Check check, String reason) {
    try {
      check.run();
    } catch (Exception e) {
      if (type.isInstance(e)) {
        return type.cast(e);
      }
      throw new IllegalStateException(reason + " (threw " + e + ")");
    }
    throw new IllegalStateException(reason);
  }

  /** A cap object with the fields the checks vary. */
  private static Caps.DataCap cap(Long monthlyLimit, long monthlyUsed, Long totalLimit,
                                  long totalUsed, boolean capped, String reason,
                                  String monthlyEnd) {
    return new Caps.DataCap("", monthlyLimit, monthlyUsed, "", monthlyEnd, totalLimit, totalUsed,
                            "", capped, reason);
  }

  /** Cap readings after the given readings (null is a failed reading). */
  private static Caps.CapReadings readings(Caps.DataCap... readings) {
    Caps.CapReadings capReadings = new Caps.CapReadings();
    for (Caps.DataCap reading : readings) {
      capReadings.record(reading);
    }
    return capReadings;
  }

  /** Decimal units, one decimal, ties to even, the next unit at 1000.0. */
  private static void checkFormatByteCount() {
    Object[][] cases = {
        {0L, "0 B"}, {999L, "999 B"}, {1000L, "1.0 kB"}, {999949L, "999.9 kB"},
        // exact halves round to even, as Go's %.1f does
        {1250L, "1.2 kB"}, {1750L, "1.8 kB"},
        // 999.999 kB rounds to 1000.0 kB and moves to the next unit
        {999999L, "1.0 MB"},
        {1234567890L, "1.2 GB"}, {5000000000L, "5.0 GB"}, {10000000000L, "10.0 GB"},
        {3000000000000L, "3.0 TB"}, {Long.MAX_VALUE, "9.2 EB"},
    };
    for (Object[] c : cases) {
      String text = EmbedStatus.formatByteCount((Long)c[0]);
      expect(text.equals(c[1]), "byte count " + c[0] + " formats as \"" + text + "\", want \"" + c[1] + "\"");
    }
  }

  /** The monthly reset time in UTC, rounded up to the next whole minute. */
  private static void checkResetText() {
    String[][] cases = {
        {"2026-11-01T00:00:00Z", "resets 2026-11-01 00:00 UTC"},
        {"2026-10-31T23:59:00.001Z", "resets 2026-11-01 00:00 UTC"},
        {"2026-10-31T19:00:00-05:00", "resets 2026-11-01 00:00 UTC"},
        // Go writes up to nine fraction digits
        {"2026-10-31T23:59:59.999999999Z", "resets 2026-11-01 00:00 UTC"},
        {"2026-11-01T00:00:00.000Z", "resets 2026-11-01 00:00 UTC"},
        {"soon", null}, {"", null}, {null, null}, {"2026-02-30T00:00:00Z", null},
        // rounds past the year 9999
        {"9999-12-31T23:59:30Z", null},
    };
    for (String[] c : cases) {
      String text = EmbedStatus.resetText(c[0]);
      expect(java.util.Objects.equals(text, c[1]),
             "reset text for " + c[0] + " is \"" + text + "\", want \"" + c[1] + "\"");
    }
  }

  /** The client limit text rounds the retry time up to the next whole minute. */
  private static void checkClientLimitText() {
    Object[][] cases = {
        // 2026-10-06 19:05:00.000 UTC, exactly on a minute
        {1791313500000L, "client limit, retry at 19:05 UTC"},
        // 19:04:00.001 rounds up, so the shown time is never before the retry
        {1791313440001L, "client limit, retry at 19:05 UTC"},
        {0L, "client limit"},
    };
    for (Object[] c : cases) {
      String text = EmbedStatus.clientLimitText((Long)c[0]);
      expect(text.equals(c[1]), "client limit text for " + c[0] + " is \"" + text + "\"");
    }
  }

  /** A console status line. */
  private static String line(String clientLimitStatus, long retryTime, Caps.CapReadings readings,
                             int providersAdded) {
    String status = EmbedStatus.status(true, false, clientLimitStatus, retryTime,
                                       readings.latest(), providersAdded);
    return EmbedStatus.statusLine(status, EmbedStatus.dataField(readings, true),
                                  EmbedStatus.dataField(readings, false));
  }

  /** The status lines match the contract's golden lines. */
  private static void checkStatusLines() {
    String none = EmbedStatus.CLIENT_LIMIT_STATUS_NONE;
    String[][] cases = {
        {line(none, 0, readings(), 0),
         "status: connecting | data this month: checking | data total: checking"},
        {line(none, 0, readings(cap(5000000000L, 1234567890, null, 0, false, "", "")), 1),
         "status: connected | data this month: 1.2 GB of 5.0 GB | data total: no cap"},
        {line(none, 0, readings((Caps.DataCap)null), 1),
         "status: connected | data this month: unavailable | data total: unavailable"},
        {line(none, 0,
              readings(cap(5000000000L, 5000000000L, null, 0, true, "monthly", "2026-11-01T00:00:00Z")), 3),
         "status: data cap reached, resets 2026-11-01 00:00 UTC | data this month: 5.0 GB of 5.0 GB | data total: no cap"},
        {line(none, 0, readings(cap(null, 0, 10000000000L, 10000000000L, true, "total", "")), 3),
         "status: data cap reached | data this month: no cap | data total: 10.0 GB of 10.0 GB"},
        {line(none, 0, readings(cap(0L, 0, null, 0, true, "monthly", "")), 3),
         "status: paused | data this month: 0 B of 0 B | data total: no cap"},
        {line(EmbedStatus.CLIENT_LIMIT_STATUS_EXCEEDED, 1791313500000L,
              readings(cap(5000000000L, 0, null, 0, false, "", "")), 0),
         "status: client limit, retry at 19:05 UTC | data this month: 0 B of 5.0 GB | data total: no cap"},
    };
    for (String[] c : cases) {
      expect(c[0].equals(c[1]), "status line \"" + c[0] + "\", want \"" + c[1] + "\"");
    }
  }

  /** The status rules apply in the contract's order. */
  private static void checkStatusRules() {
    String none = EmbedStatus.CLIENT_LIMIT_STATUS_NONE;
    String exceeded = EmbedStatus.CLIENT_LIMIT_STATUS_EXCEEDED;
    Caps.DataCap monthlyZero = cap(0L, 0, null, 0, true, "monthly", "");
    Object[][] cases = {
        {false, false, none, 0L, null, 0, "stopped"},
        {false, true, none, 0L, null, 0, "signed out"},
        {true, false, exceeded, 1791313500000L, monthlyZero, 3, "client limit, retry at 19:05 UTC"},
        {true, false, none, 0L, monthlyZero, 3, "paused"},
        {true, false, none, 0L, cap(5000000000L, 0, 0L, 0, true, "total", ""), 3, "paused"},
        {true, false, none, 0L, cap(5000000000L, 0, null, 0, true, "monthly", "2026-11-01T00:00:00Z"), 3,
         "data cap reached, resets 2026-11-01 00:00 UTC"},
        {true, false, none, 0L, cap(null, 0, 10000000000L, 0, true, "total", ""), 0, "data cap reached"},
        {true, false, none, 0L, cap(null, 0, null, 0, false, "", ""), 1, "connected"},
        {true, false, none, 0L, null, 0, "connecting"},
        // an unknown reason is capped without a reset time and names no cap
        {true, false, none, 0L, cap(0L, 0, 0L, 0, true, "weekly", ""), 1, "data cap reached"},
        // a monthly end that does not parse shows no reset time
        {true, false, none, 0L, cap(5L, 0, null, 0, true, "monthly", "later"), 1, "data cap reached"},
        // the cap that capped_reason names decides paused
        {true, false, none, 0L, cap(0L, 0, 10L, 0, true, "total", ""), 1, "data cap reached"},
    };
    for (Object[] c : cases) {
      String status = EmbedStatus.status((Boolean)c[0], (Boolean)c[1], (String)c[2], (Long)c[3],
                                         (Caps.DataCap)c[4], (Integer)c[5]);
      expect(status.equals(c[6]), "status \"" + status + "\", want \"" + c[6] + "\"");
    }
  }

  /** The data fields before, after and between cap readings. */
  private static void checkDataFields() {
    expect(EmbedStatus.dataField(readings(), true).equals("checking"), "no reading yet is not checking");
    expect(EmbedStatus.dataField(readings((Caps.DataCap)null), false).equals("unavailable"),
           "a failed first reading is not unavailable");
    Caps.DataCap good = cap(5000000000L, 1234567890, null, 0, false, "", "");
    expect(EmbedStatus.dataField(readings(good, null), true).equals("1.2 GB of 5.0 GB"),
           "a later failure does not keep the last value");
    expect(EmbedStatus.dataField(readings(null, good), true).equals("1.2 GB of 5.0 GB"),
           "a success after a failure does not show");
    // a server need not report usage for an uncapped client
    Caps.DataCap uncapped = cap(null, 123, null, 456, false, "", "");
    expect(EmbedStatus.dataField(readings(uncapped), true).equals("no cap") &&
           EmbedStatus.dataField(readings(uncapped), false).equals("no cap"),
           "an uncapped field shows a used count");
    expect(EmbedStatus.dataField(readings(cap(null, 0, 10000000000L, 1000, false, "", "")), false)
               .equals("1.0 kB of 10.0 GB"),
           "the total field is wrong");
  }

  /** The cap object parsing. */
  private static void checkCapParsing() {
    Caps.DataCap full = Caps.DataCap.parse(
        "{\"client_id\":\"" + CLIENT_ID + "\",\"monthly_byte_limit\":10000000000,\"monthly_used_byte_count\":42," +
        "\"monthly_period_start\":\"2026-10-01T00:00:00Z\",\"monthly_period_end\":\"2026-11-01T00:00:00Z\"," +
        "\"total_byte_limit\":null,\"total_used_byte_count\":7,\"total_period_start\":\"2026-09-15T08:00:00Z\"," +
        "\"capped\":false,\"capped_reason\":\"\"}");
    expect(full != null && full.monthlyByteLimit() == 10000000000L && full.monthlyUsedByteCount() == 42 &&
           full.totalByteLimit() == null && full.totalUsedByteCount() == 7 && !full.capped() &&
           full.cappedReason().isEmpty() && full.monthlyPeriodEnd().equals("2026-11-01T00:00:00Z") &&
           full.clientId().equals(CLIENT_ID),
           "a full cap object does not parse");
    Caps.DataCap sparse = Caps.DataCap.parse("{}");
    expect(sparse != null && sparse.monthlyByteLimit() == null && sparse.totalByteLimit() == null &&
           !sparse.capped() && sparse.cappedReason().isEmpty(),
           "absent limits are not null");
    Caps.DataCap capped = Caps.DataCap.parse("{\"total_byte_limit\":0,\"capped\":true,\"capped_reason\":\"total\"}");
    expect(capped != null && capped.totalByteLimit() == 0 && capped.capped() &&
           capped.cappedReason().equals("total"),
           "capped does not parse");
    Caps.DataCap unknown = Caps.DataCap.parse("{\"monthly_byte_limit\":5,\"capped\":true,\"capped_reason\":\"weekly\"}");
    expect(unknown != null && unknown.capped() &&
           EmbedStatus.status(true, false, "", 0, unknown, 1).equals("data cap reached"),
           "an unknown capped_reason is not read as capped without a reset time");
    String[] invalid = {
        "{\"error\":{\"message\":\"Client does not exist.\"}}", "not json", "[]", "null", null,
        "{\"monthly_byte_limit\":\"5GB\"}", "{\"capped\":\"yes\"}", "{\"monthly_byte_limit\":1.5}",
        "{\"capped_reason\":7}",
    };
    for (String text : invalid) {
      expect(Caps.DataCap.parse(text) == null, "invalid cap object " + text + " parsed");
    }
  }

  /** An unsigned JWT with the given claims: the app checks the shape and the client_id claim. */
  private static String jwt(Map<String, Object> claims) {
    return "eyJhbGciOiJub25lIn0." +
        Base64.getUrlEncoder().withoutPadding().encodeToString(
            Json.write(claims).getBytes(StandardCharsets.UTF_8)) +
        ".c2lnbmF0dXJl";
  }

  /** The client_id claim: accepted for a client JWT, refused otherwise. */
  private static void checkClientJwtClaims() throws Exception {
    expect(InstallationState.parseClientJwtClientId(jwt(Map.of("client_id", CLIENT_ID.toUpperCase())))
               .equals(CLIENT_ID),
           "a client JWT is not accepted in canonical form");
    // a network JWT has no client_id claim
    expectThrows(InstallationState.ConfigException.class,
                 () -> InstallationState.parseClientJwtClientId(jwt(Map.of("network_id", OTHER_CLIENT_ID))),
                 "a network JWT is accepted");
    for (String malformed : new String[] {"abc", "a.b", "a..c", "a.!!!.c", ""}) {
      expectThrows(InstallationState.ConfigException.class,
                   () -> InstallationState.parseClientJwtClientId(malformed),
                   "the malformed token \"" + malformed + "\" is accepted");
    }
    expectThrows(InstallationState.ConfigException.class,
                 () -> InstallationState.parseClientJwtClientId(jwt(Map.of("client_id", "not-a-uuid"))),
                 "an invalid client_id claim is accepted");
  }

  /** HTTPS origins and loopback HTTP only. */
  private static void checkOrigins() throws Exception {
    String[][] valid = {
        {"https://api.bringyour.com", "https://api.bringyour.com/"},
        {"https://example.com/", "https://example.com/"},
        {"http://127.0.0.1:8790", "http://127.0.0.1:8790/"},
        {"http://localhost:8790", "http://localhost:8790/"},
        {"http://[::1]:8790", "http://[::1]:8790/"},
    };
    for (String[] c : valid) {
      String origin = InstallationState.parseOrigin(c[0], "URL").toString();
      expect(origin.equals(c[1]), "origin " + c[0] + " reads as " + origin + ", want " + c[1]);
    }
    for (String url : new String[] {
             "http://example.com", "https://example.com/path", "https://example.com/?x=1",
             "https://example.com/#f", "https://user:pw@example.com", "ftp://example.com",
             "example.com", ""}) {
      expectThrows(InstallationState.ConfigException.class,
                   () -> InstallationState.parseOrigin(url, "URL"), "the origin " + url + " is accepted");
    }
  }

  /** A stand-in HTTP server: it records the request and answers with a fixed status and body. */
  private static final class StandIn implements Token.HttpTransport {
    private final int status;
    private final String body;
    private final boolean unreachable;
    String method = "";
    String uri = "";
    String bearer = "";
    String jsonBody;

    StandIn(int status, String body) { this(status, body, false); }

    StandIn(int status, String body, boolean unreachable) {
      this.status = status;
      this.body = body;
      this.unreachable = unreachable;
    }

    @Override
    public HttpAnswer send(String method, URI uri, String bearer, String jsonBody)
        throws IOException {
      this.method = method;
      this.uri = uri.toString();
      this.bearer = bearer;
      this.jsonBody = jsonBody;
      if (unreachable) {
        throw new ConnectException("connection refused");
      }
      return new HttpAnswer(status, body);
    }
  }

  /** The token fetch against a stand-in token server. */
  private static void checkTokenFetch() throws Exception {
    Path stateDir = newPrivateDir();
    try {
      String token = jwt(Map.of("client_id", CLIENT_ID));
      Path clientJwtPath = stateDir.resolve(InstallationState.CLIENT_JWT_FILE_NAME);
      StandIn ok = new StandIn(200,
          "{\"client_id\":\"" + CLIENT_ID + "\",\"by_client_jwt\":\"" + token + "\"," +
          "\"data_cap\":{\"client_id\":\"" + CLIENT_ID + "\",\"monthly_byte_limit\":10000000000," +
          "\"monthly_used_byte_count\":1,\"capped\":false,\"capped_reason\":\"\"}}");
      Token.ClientCredential credential = fetch(ok, stateDir);
      expect(ok.method.equals("POST") && ok.uri.equals("http://127.0.0.1:8790/urnetwork/client-token"),
             "the token request is " + ok.method + " " + ok.uri);
      expect(ok.bearer.equals(DEMO_SESSION), "the token request does not carry the demo session");
      expect(INSTANCE_ID.equals(Json.string(Json.parseObject(ok.jsonBody), "installation_id")),
             "the token request does not carry the instance id");
      expect(credential.clientId().equals(CLIENT_ID) && credential.clientJwt().equals(token) &&
             credential.firstCap() != null && credential.firstCap().monthlyByteLimit() == 10000000000L,
             "the token answer is not read");
      expect(Files.readString(clientJwtPath).equals(token + "\n"), "client.jwt is not saved");
      expectPrivateFile(clientJwtPath);
      expect(listFiles(stateDir) == 1, "the token save leaves a temporary file");

      // a client_id that does not match the claim is refused, and the saved
      // client.jwt stays as it was
      StandIn mismatched = new StandIn(200, "{\"client_id\":\"" + OTHER_CLIENT_ID +
                                                "\",\"by_client_jwt\":\"" + token + "\",\"data_cap\":null}");
      Token.TokenFetchException refused = expectThrows(
          Token.TokenFetchException.class, () -> fetch(mismatched, stateDir), "a mismatched client_id is accepted");
      expect(refused.exitCode() == 1 && refused.guiStatus().equals("stopped"),
             "a mismatched client_id is not a failure");
      expect(Files.readString(clientJwtPath).equals(token + "\n"), "a refused answer replaced client.jwt");

      Object[][] answers = {
          {401, "{\"error\":{\"code\":\"unauthorized\",\"message\":\"unknown session\"}}", 78, "signed out", "unknown session"},
          {409, "{\"error\":{\"code\":\"installation_limit\",\"message\":\"too many installations\"}}", 78, "signed out", "too many installations"},
          {409, "{\"error\":{\"code\":\"client_limit\",\"message\":\"your network is at its client limit; see https://ur.io/services\"}}", 78, "signed out", "https://ur.io/services"},
          {401, "", 78, "signed out", "refused"},
          {500, "{\"error\":{\"code\":\"upstream\",\"message\":\"upstream failed\"}}", 1, "stopped", "HTTP 500"},
          {503, "{\"error\":{\"code\":\"busy\",\"message\":\"retry\"}}", 1, "stopped", "HTTP 503"},
          {400, "{\"error\":{\"code\":\"invalid_request\",\"message\":\"bad\"}}", 1, "stopped", "HTTP 400"},
          {200, "not json", 1, "stopped", "invalid"},
          {200, "{\"client_id\":\"" + CLIENT_ID + "\",\"by_client_jwt\":\"" + jwt(Map.of("network_id", CLIENT_ID)) + "\"}",
           1, "stopped", "client_id"},
      };
      for (Object[] a : answers) {
        Token.TokenFetchException e = expectThrows(
            Token.TokenFetchException.class, () -> fetch(new StandIn((Integer)a[0], (String)a[1]), stateDir),
            "the answer " + a[0] + " " + a[1] + " is accepted");
        expect(e.exitCode() == (Integer)a[2] && e.guiStatus().equals(a[3]) && e.getMessage().contains((String)a[4]),
               "the answer " + a[0] + " maps to exit " + e.exitCode() + " \"" + e.guiStatus() + "\" (" + e.getMessage() + ")");
      }
      Token.TokenFetchException unreachable = expectThrows(
          Token.TokenFetchException.class, () -> fetch(new StandIn(200, "", true), stateDir),
          "an unreachable token server is accepted");
      expect(unreachable.exitCode() == 1 && unreachable.guiStatus().equals("stopped"),
             "an unreachable token server is not a failure");
      expect(!refused.getMessage().contains(DEMO_SESSION) && !unreachable.getMessage().contains(DEMO_SESSION),
             "an error message shows the demo session");
    } finally {
      deleteTree(stateDir);
    }
  }

  /** fetchClientJwt against a stand-in server. */
  private static Token.ClientCredential fetch(StandIn standIn, Path stateDir) throws Exception {
    return Token.fetchClientJwt(TOKEN_SERVER, DEMO_SESSION, INSTANCE_ID, stateDir, standIn);
  }

  /** The cap read presents the client JWT and treats any failure as no reading. */
  private static void checkCapRead() {
    String token = jwt(Map.of("client_id", CLIENT_ID));
    StandIn ok = new StandIn(200, "{\"monthly_byte_limit\":5,\"capped\":false,\"capped_reason\":\"\"}");
    Caps.DataCap read = Caps.read(API_ORIGIN, token, ok);
    expect(read != null && read.monthlyByteLimit() == 5, "a cap answer is not read");
    expect(ok.method.equals("GET") && ok.uri.equals("https://api.bringyour.com/network/client-data-cap") &&
           ok.bearer.equals(token) && ok.jsonBody == null,
           "the cap read is " + ok.method + " " + ok.uri);
    expect(Caps.read(API_ORIGIN, token, new StandIn(404, "404 page not found")) == null,
           "a server without the cap routes is a reading");
    expect(Caps.read(API_ORIGIN, token, new StandIn(200, "{\"error\":{\"message\":\"denied\"}}")) == null,
           "an error answer is a reading");
    expect(Caps.read(API_ORIGIN, token, new StandIn(200, "", true)) == null,
           "an unreachable API is a reading");
  }

  /** The state directory: private files, atomic replacement, one instance id, no symlinks. */
  private static void checkStateFiles() throws Exception {
    Path stateDir = newPrivateDir();
    try {
      String instanceId = InstallationState.loadOrCreateInstanceId(stateDir);
      expect(instanceId.equals(InstallationState.parseId(instanceId)), "instance-id is not a canonical UUID");
      expect(InstallationState.loadOrCreateInstanceId(stateDir).equals(instanceId), "instance-id is not reused");
      expectPrivateFile(stateDir.resolve(InstallationState.INSTANCE_ID_FILE_NAME));

      Path path = stateDir.resolve(InstallationState.CLIENT_JWT_FILE_NAME);
      InstallationState.writePrivateFile(path, "one".getBytes(StandardCharsets.UTF_8));
      InstallationState.writePrivateFile(path, "two".getBytes(StandardCharsets.UTF_8));
      expect(Files.readString(path).equals("two"), "a state file is not replaced");
      expect(listFiles(stateDir) == 2, "a replacement leaves a temporary file");

      if (InstallationState.hasPosixPermissions(stateDir)) {
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-r--r--"));
        expectThrows(IOException.class, () -> InstallationState.readPrivateFile(path), "a shared file is read");
        Files.setPosixFilePermissions(path, InstallationState.OWNER_READ_WRITE);
        Files.setPosixFilePermissions(stateDir, PosixFilePermissions.fromString("rwxr-xr-x"));
        expectThrows(InstallationState.ConfigException.class,
                     () -> InstallationState.checkStateDir(stateDir.toString()), "a shared directory is accepted");
        Files.setPosixFilePermissions(stateDir, InstallationState.OWNER_ALL);

        Path target = stateDir.resolve("target");
        InstallationState.writePrivateFile(target, jwt(Map.of("client_id", CLIENT_ID)).getBytes(StandardCharsets.UTF_8));
        Files.delete(path);
        Files.createSymbolicLink(path, target);
        expectThrows(IOException.class, () -> InstallationState.readPrivateFile(path), "a symlinked file is read");
        expectThrows(InstallationState.ConfigException.class, () -> InstallationState.readClientJwt(stateDir),
                     "a symlinked client.jwt is not a configuration error");
      }
    } finally {
      deleteTree(stateDir);
    }
  }

  /** The configuration errors, all exit 78. */
  private static void checkSettings() throws Exception {
    Path stateDir = newPrivateDir();
    try {
      expectThrows(InstallationState.ConfigException.class, () -> InstallationState.loadSettings(Map.of()),
                   "a missing state directory is accepted");
      expectThrows(InstallationState.ConfigException.class,
                   () -> InstallationState.loadSettings(Map.of("URNETWORK_EMBED_STATE_DIR", "relative/state")),
                   "a relative state directory is accepted");
      expectThrows(InstallationState.ConfigException.class,
                   () -> InstallationState.loadSettings(
                       Map.of("URNETWORK_EMBED_STATE_DIR", stateDir.resolve("missing").toString())),
                   "a state directory that does not exist is accepted");
      Map<String, String> env = new HashMap<>();
      env.put("URNETWORK_EMBED_STATE_DIR", stateDir.toString());
      env.put("URNETWORK_TOKEN_SERVER_URL", TOKEN_SERVER.toString());
      expectThrows(InstallationState.ConfigException.class, () -> InstallationState.loadSettings(env),
                   "a token server without a demo session is accepted");
      env.put("URNETWORK_DEMO_SESSION", "has space");
      expectThrows(InstallationState.ConfigException.class, () -> InstallationState.loadSettings(env),
                   "a demo session with whitespace is accepted");
      env.put("URNETWORK_DEMO_SESSION", DEMO_SESSION);
      InstallationState.EmbedSettings withTokenServer = InstallationState.loadSettings(env);
      expect(withTokenServer.tokenServer().equals(TOKEN_SERVER) &&
             withTokenServer.demoSession().equals(DEMO_SESSION) &&
             withTokenServer.apiOrigin().toString().equals("https://api.bringyour.com/") &&
             !withTokenServer.toString().contains(DEMO_SESSION),
             "the token server settings are not read");
      env.remove("URNETWORK_TOKEN_SERVER_URL");
      expectThrows(InstallationState.ConfigException.class, () -> InstallationState.loadSettings(env),
                   "a demo session without a token server is accepted");
      env.remove("URNETWORK_DEMO_SESSION");
      env.put("URNETWORK_API_URL", "http://api.example.com");
      expectThrows(InstallationState.ConfigException.class, () -> InstallationState.loadSettings(env),
                   "a plain HTTP API origin is accepted");
      env.remove("URNETWORK_API_URL");

      // no token server and no client.jwt
      InstallationState.EmbedSettings withoutTokenServer = InstallationState.loadSettings(env);
      expectThrows(InstallationState.ConfigException.class,
                   () -> Token.obtainClientJwt(withoutTokenServer, null),
                   "no token server and no client.jwt is accepted");
      // a client.jwt from the backend tool
      InstallationState.saveClientJwt(stateDir, jwt(Map.of("client_id", CLIENT_ID)));
      Token.ClientCredential credential = Token.obtainClientJwt(withoutTokenServer, null);
      expect(credential.clientId().equals(CLIENT_ID) && credential.firstCap() == null &&
             !credential.toString().contains(credential.clientJwt()),
             "client.jwt is not used");
      // a network JWT in client.jwt
      InstallationState.saveClientJwt(stateDir, jwt(Map.of("network_id", CLIENT_ID)));
      expectThrows(InstallationState.ConfigException.class,
                   () -> Token.obtainClientJwt(withoutTokenServer, null),
                   "a network JWT in client.jwt is accepted");
    } finally {
      deleteTree(stateDir);
    }
  }

  /** An unknown command is a usage error, exit 78. */
  private static void checkUsageExitCode() {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    int exitCode = Main.run(new String[] {"--bogus"}, Map.of(),
                            new PrintStream(out, true, StandardCharsets.UTF_8),
                            new PrintStream(err, true, StandardCharsets.UTF_8));
    expect(exitCode == Main.EXIT_CONFIG, "a usage error does not exit 78");
    expect(err.toString(StandardCharsets.UTF_8).contains(Main.USAGE), "a usage error does not print the usage");
  }

  /** A new private temporary directory (0700 where the file system has POSIX permissions). */
  private static Path newPrivateDir() throws IOException {
    Path dir = Files.createTempDirectory("ur-embed-selftest-");
    if (InstallationState.hasPosixPermissions(dir)) {
      Files.setPosixFilePermissions(dir, InstallationState.OWNER_ALL);
    }
    return dir;
  }

  /** A file is private to its owner where the file system has POSIX permissions. */
  private static void expectPrivateFile(Path path) throws IOException {
    if (InstallationState.hasPosixPermissions(path)) {
      expect(java.util.Collections.disjoint(Files.getPosixFilePermissions(path),
                                            InstallationState.GROUP_AND_OTHERS),
             path.getFileName() + " is not private");
    }
  }

  /** The number of entries in a directory. */
  private static long listFiles(Path dir) throws IOException {
    try (Stream<Path> entries = Files.list(dir)) {
      return entries.count();
    }
  }

  /** Deletes a directory tree. */
  private static void deleteTree(Path dir) throws IOException {
    try (Stream<Path> paths = Files.walk(dir)) {
      for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(path);
      }
    }
  }
}
