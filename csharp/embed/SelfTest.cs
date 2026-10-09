// The credential-free self-test (EMBED_CONTRACT.md, "Self-test"). It checks the
// status texts and rules, the data fields, the cap object, the client JWT
// claim, the token fetch against a stand-in server, the cap read and the
// installation state without a network, credentials, a device or the native
// SDK runtime: nothing it runs calls into URnetwork.SDK, so it passes even
// where the SDK library is missing. The data is synthetic.
using System.Net;
using System.Text;
using System.Text.Json;

/// Runs every check; a failed check throws with its reason.
internal static class SelfTest {
  private const string ClientId = "11111111-1111-1111-1111-111111111111";
  private const string OtherClientId = "33333333-3333-3333-3333-333333333333";
  private const string InstanceId = "22222222-2222-2222-2222-222222222222";
  // a stand-in demo session token: at least 32 characters, as the token server
  // requires
  private const string DemoSession = "selftest-demo-session-0123456789abcdef";
  private static readonly Uri TokenServer = new("http://127.0.0.1:8790/");
  private static readonly Uri ApiOrigin = new("https://api.bringyour.com/");

  private const UnixFileMode PrivateFileMode = UnixFileMode.UserRead | UnixFileMode.UserWrite;
  private const UnixFileMode PrivateDirMode = PrivateFileMode | UnixFileMode.UserExecute;
  private const UnixFileMode SharedReadMode = UnixFileMode.GroupRead | UnixFileMode.OtherRead;

  /// Runs every check in order and stops at the first failure.
  public static void Run() {
    Action[] checks = [
      CheckFormatByteCount,
      CheckResetText,
      CheckClientLimitText,
      CheckStatusLines,
      CheckStatusRules,
      CheckDataFields,
      CheckCapParsing,
      CheckClientJwtClaims,
      CheckOrigins,
      CheckTokenFetch,
      CheckCapRead,
      CheckStateFiles,
      CheckSettings,
      CheckUsageExitCode,
      CheckStartLine,
    ];
    foreach (Action check in checks) {
      check();
    }
  }

  /// Fails with reason unless condition holds.
  private static void Expect(bool condition, string reason) {
    if (!condition) {
      throw new Exception(reason);
    }
  }

  /// Fails with reason unless action throws T, and returns the exception.
  private static T ExpectThrows<T>(Action action, string reason) where T : Exception {
    try {
      action();
    } catch (T e) {
      return e;
    }
    throw new Exception(reason);
  }

  /// Decimal units, one decimal, ties to even on the exact integer, the next
  /// unit at 1000.0.
  private static void CheckFormatByteCount() {
    (long ByteCount, string Text)[] cases = [
      (0, "0 B"), (999, "999 B"), (1000, "1.0 kB"), (999949, "999.9 kB"),
      // exact halves round to even on the integer: 1.05 is not a binary fraction
      (1050, "1.0 kB"), (1150, "1.2 kB"), (1250, "1.2 kB"), (1750, "1.8 kB"),
      // 999.95 kB ties to the even 1000.0 kB and moves to the next unit, as does 999.999 kB
      (999950, "1.0 MB"), (999999, "1.0 MB"),
      (1234567890, "1.2 GB"), (5000000000, "5.0 GB"), (10000000000, "10.0 GB"),
      (3000000000000, "3.0 TB"), (long.MaxValue, "9.2 EB"),
    ];
    foreach (var c in cases) {
      string text = StatusRules.FormatByteCount(c.ByteCount);
      Expect(text == c.Text, $"byte count {c.ByteCount} formats as \"{text}\", want \"{c.Text}\"");
    }
  }

  /// The monthly reset time in UTC, rounded up to the next whole minute.
  private static void CheckResetText() {
    (string? End, string? Text)[] cases = [
      ("2026-11-01T00:00:00Z", "resets 2026-11-01 00:00 UTC"),
      ("2026-10-31T23:59:00.001Z", "resets 2026-11-01 00:00 UTC"),
      ("2026-10-31T19:00:00-05:00", "resets 2026-11-01 00:00 UTC"),
      // Go writes up to nine fraction digits
      ("2026-10-31T23:59:59.999999999Z", "resets 2026-11-01 00:00 UTC"),
      ("2026-11-01T00:00:00.000Z", "resets 2026-11-01 00:00 UTC"),
      ("soon", null), ("", null), (null, null), ("2026-02-30T00:00:00Z", null),
      // rounds past the year 9999
      ("9999-12-31T23:59:30Z", null),
    ];
    foreach (var c in cases) {
      string? text = StatusRules.ResetText(c.End);
      Expect(text == c.Text, $"reset text for {c.End ?? "null"} is \"{text}\", want \"{c.Text}\"");
    }
  }

  /// The client limit text rounds the retry time up to the next whole minute.
  private static void CheckClientLimitText() {
    (long RetryTime, string Text)[] cases = [
      // 2026-10-06 19:05:00.000 UTC, exactly on a minute
      (1791313500000, "client limit, retry at 19:05 UTC"),
      // 19:04:00.001 rounds up, so the shown time is never before the retry
      (1791313440001, "client limit, retry at 19:05 UTC"),
      (0, "client limit"),
    ];
    foreach (var c in cases) {
      string text = StatusRules.ClientLimitText(c.RetryTime);
      Expect(text == c.Text, $"client limit text for {c.RetryTime} is \"{text}\", want \"{c.Text}\"");
    }
  }

  /// A console status line from a client limit status, cap readings and the
  /// providers in the window.
  private static string Line(string clientLimitStatus, long retryTime, CapReadings readings,
                             int providersAdded) {
    string status = StatusRules.Status(started: true, signedOut: false, clientLimitStatus,
                                       retryTime, readings.Latest, providersAdded);
    return StatusRules.StatusLine(status, StatusRules.DataField(readings, monthly: true),
                                  StatusRules.DataField(readings, monthly: false));
  }

  /// Cap readings after the given readings (null is a failed reading).
  private static CapReadings Readings(params DataCap?[] readings) {
    var capReadings = new CapReadings();
    foreach (DataCap? reading in readings) {
      capReadings.Record(reading);
    }
    return capReadings;
  }

  /// Cap readings after the given readings, then the Embed-not-enabled
  /// refusal.
  private static CapReadings NotEnabled(params DataCap?[] readings) {
    CapReadings capReadings = Readings(readings);
    capReadings.RecordEmbedNotEnabled();
    return capReadings;
  }

  /// The status lines match the contract's golden lines.
  private static void CheckStatusLines() {
    const string none = StatusRules.ClientLimitStatusNone;
    (string Line, string Want)[] cases = [
      (Line(none, 0, Readings(), 0),
       "status: connecting | data this month: checking | data total: checking"),
      (Line(none, 0, Readings(new DataCap { MonthlyByteLimit = 5000000000, MonthlyUsedByteCount = 1234567890 }), 1),
       "status: connected | data this month: 1.2 GB of 5.0 GB | data total: no cap"),
      (Line(none, 0, Readings((DataCap?)null), 1),
       "status: connected | data this month: unavailable | data total: unavailable"),
      (Line(none, 0, NotEnabled(), 1),
       "status: connected | data this month: unavailable | data total: unavailable"),
      (Line(none, 0, NotEnabled(new DataCap {
         MonthlyByteLimit = 5000000000, MonthlyUsedByteCount = 5000000000,
         MonthlyPeriodEnd = "2026-11-01T00:00:00Z", Capped = true, CappedReason = "monthly",
       }), 1),
       "status: connected | data this month: unavailable | data total: unavailable"),
      (Line(none, 0, Readings(new DataCap {
         MonthlyByteLimit = 5000000000, MonthlyUsedByteCount = 5000000000,
         MonthlyPeriodEnd = "2026-11-01T00:00:00Z", Capped = true, CappedReason = "monthly",
       }), 3),
       "status: data cap reached, resets 2026-11-01 00:00 UTC | data this month: 5.0 GB of 5.0 GB | data total: no cap"),
      (Line(none, 0, Readings(new DataCap {
         TotalByteLimit = 10000000000, TotalUsedByteCount = 10000000000, Capped = true, CappedReason = "total",
       }), 3),
       "status: data cap reached | data this month: no cap | data total: 10.0 GB of 10.0 GB"),
      (Line(none, 0, Readings(new DataCap {
         MonthlyByteLimit = 0, MonthlyUsedByteCount = 0, Capped = true, CappedReason = "monthly",
       }), 3),
       "status: paused | data this month: 0 B of 0 B | data total: no cap"),
      (Line(StatusRules.ClientLimitStatusExceeded, 1791313500000,
            Readings(new DataCap { MonthlyByteLimit = 5000000000, MonthlyUsedByteCount = 0 }), 0),
       "status: client limit, retry at 19:05 UTC | data this month: 0 B of 5.0 GB | data total: no cap"),
    ];
    foreach (var c in cases) {
      Expect(c.Line == c.Want, $"status line \"{c.Line}\", want \"{c.Want}\"");
    }
  }

  /// The status rules apply in the contract's order.
  private static void CheckStatusRules() {
    const string none = StatusRules.ClientLimitStatusNone;
    const string exceeded = StatusRules.ClientLimitStatusExceeded;
    var monthlyZero = new DataCap { MonthlyByteLimit = 0, Capped = true, CappedReason = "monthly" };
    (bool Started, bool SignedOut, string ClientLimit, long Retry, DataCap? Cap, int Added, string Want)[] cases = [
      (false, false, none, 0, null, 0, "stopped"),
      (false, true, none, 0, null, 0, "signed out"),
      (true, false, exceeded, 1791313500000, monthlyZero, 3, "client limit, retry at 19:05 UTC"),
      (true, false, none, 0, monthlyZero, 3, "paused"),
      (true, false, none, 0,
       new DataCap { TotalByteLimit = 0, MonthlyByteLimit = 5000000000, Capped = true, CappedReason = "total" },
       3, "paused"),
      (true, false, none, 0,
       new DataCap { MonthlyByteLimit = 5000000000, MonthlyPeriodEnd = "2026-11-01T00:00:00Z",
                     Capped = true, CappedReason = "monthly" },
       3, "data cap reached, resets 2026-11-01 00:00 UTC"),
      (true, false, none, 0,
       new DataCap { TotalByteLimit = 10000000000, Capped = true, CappedReason = "total" },
       0, "data cap reached"),
      (true, false, none, 0, new DataCap(), 1, "connected"),
      (true, false, none, 0, null, 0, "connecting"),
      // an unknown reason is capped without a reset time and names no cap
      (true, false, none, 0,
       new DataCap { MonthlyByteLimit = 0, TotalByteLimit = 0, Capped = true, CappedReason = "weekly" },
       1, "data cap reached"),
      // a monthly end that does not parse shows no reset time
      (true, false, none, 0,
       new DataCap { MonthlyByteLimit = 5, MonthlyPeriodEnd = "later", Capped = true, CappedReason = "monthly" },
       1, "data cap reached"),
      // the cap that capped_reason names decides paused: here the monthly cap
      // is 0 but the total cap was reached
      (true, false, none, 0,
       new DataCap { MonthlyByteLimit = 0, TotalByteLimit = 10, Capped = true, CappedReason = "total" },
       1, "data cap reached"),
    ];
    foreach (var c in cases) {
      string status = StatusRules.Status(c.Started, c.SignedOut, c.ClientLimit, c.Retry, c.Cap, c.Added);
      Expect(status == c.Want, $"status \"{status}\", want \"{c.Want}\"");
    }
  }

  /// The data fields before, after and between cap readings.
  private static void CheckDataFields() {
    Expect(StatusRules.DataField(Readings(), monthly: true) == "checking", "no reading yet is not checking");
    Expect(StatusRules.DataField(Readings((DataCap?)null), monthly: false) == "unavailable",
           "a failed first reading is not unavailable");
    var good = new DataCap { MonthlyByteLimit = 5000000000, MonthlyUsedByteCount = 1234567890 };
    Expect(StatusRules.DataField(Readings(good, null), monthly: true) == "1.2 GB of 5.0 GB",
           "a later failure does not keep the last value");
    Expect(StatusRules.DataField(Readings(null, good), monthly: true) == "1.2 GB of 5.0 GB",
           "a success after a failure does not show");
    // a server need not report usage for an uncapped client
    var uncapped = new DataCap { MonthlyUsedByteCount = 123, TotalUsedByteCount = 456 };
    Expect(StatusRules.DataField(Readings(uncapped), monthly: true) == "no cap" &&
           StatusRules.DataField(Readings(uncapped), monthly: false) == "no cap",
           "an uncapped field shows a used count");
    var total = new DataCap { TotalByteLimit = 10000000000, TotalUsedByteCount = 1000 };
    Expect(StatusRules.DataField(Readings(total), monthly: false) == "1.0 kB of 10.0 GB",
           "the total field is wrong");
    // the Embed-not-enabled refusal clears the last reading
    CapReadings cleared = NotEnabled(good, total);
    Expect(cleared.Latest == null && StatusRules.DataField(cleared, monthly: true) == "unavailable" &&
           StatusRules.DataField(cleared, monthly: false) == "unavailable",
           "the Embed-not-enabled refusal does not clear the last reading");
    // the outcome of a cap read: a reading replaces, another failure keeps it,
    // and the Embed-not-enabled refusal clears it
    var recorded = new CapReadings();
    recorded.RecordRead(new CapRead(good));
    recorded.RecordRead(new CapRead(null));
    Expect(recorded.Latest == good, "a failed cap read does not keep the reading");
    recorded.RecordRead(new CapRead(null, EmbedNotEnabled: true));
    Expect(recorded.Latest == null && recorded.Attempted, "the Embed-not-enabled cap read does not clear the reading");
  }

  /// The cap object parsing.
  private static void CheckCapParsing() {
    DataCap? cap = DataCap.Parse($$"""
        {"client_id":"{{ClientId}}","monthly_byte_limit":10000000000,"monthly_used_byte_count":42,
         "monthly_period_start":"2026-10-01T00:00:00Z","monthly_period_end":"2026-11-01T00:00:00Z",
         "total_byte_limit":null,"total_used_byte_count":7,"total_period_start":"2026-09-15T08:00:00Z",
         "capped":false,"capped_reason":""}
        """);
    Expect(cap is { MonthlyByteLimit: 10000000000, MonthlyUsedByteCount: 42, TotalByteLimit: null,
                    TotalUsedByteCount: 7, Capped: false, CappedReason: "",
                    MonthlyPeriodEnd: "2026-11-01T00:00:00Z", ClientId: ClientId },
           "a full cap object does not parse");
    DataCap? sparse = DataCap.Parse("{}");
    Expect(sparse is { MonthlyByteLimit: null, TotalByteLimit: null, Capped: false, CappedReason: "" },
           "absent limits are not null");
    DataCap? capped = DataCap.Parse("""{"total_byte_limit":0,"capped":true,"capped_reason":"total"}""");
    Expect(capped is { TotalByteLimit: 0, Capped: true, CappedReason: "total" }, "capped does not parse");
    DataCap? unknown = DataCap.Parse("""{"monthly_byte_limit":5,"capped":true,"capped_reason":"weekly"}""");
    Expect(unknown is { Capped: true, CappedReason: "weekly" } &&
           StatusRules.Status(true, false, "", 0, unknown, 1) == "data cap reached",
           "an unknown capped_reason is not read as capped without a reset time");
    string?[] invalid = [
      """{"error":{"message":"Client does not exist."}}""", "not json", "[]", "null", null,
      """{"monthly_byte_limit":"5GB"}""", """{"capped":"yes"}""", """{"monthly_byte_limit":1.5}""",
      """{"capped_reason":7}""",
    ];
    foreach (string? text in invalid) {
      Expect(DataCap.Parse(text) == null && !DataCap.IsEmbedNotEnabled(text),
             $"invalid cap object {text ?? "null"} parsed");
    }
    // the Embed-not-enabled refusal is no cap object, and is recognized
    const string notEnabled = """{"error":{"message":"Embed isn't enabled for this network."}}""";
    Expect(DataCap.Parse(notEnabled) == null && DataCap.IsEmbedNotEnabled(notEnabled),
           "the Embed-not-enabled refusal is misread");
  }

  /// A JWT with the given claims, unsigned: the app checks the shape and the
  /// client_id claim only.
  private static string Jwt(object claims) {
    string payload = Convert.ToBase64String(JsonSerializer.SerializeToUtf8Bytes(claims))
                         .TrimEnd('=').Replace('+', '-').Replace('/', '_');
    return "eyJhbGciOiJub25lIn0." + payload + ".c2lnbmF0dXJl";
  }

  /// The client_id claim: accepted for a client JWT, refused otherwise.
  private static void CheckClientJwtClaims() {
    Expect(StateFiles.ParseClientJwtClientId(Jwt(new { client_id = ClientId.ToUpperInvariant() })) == ClientId,
           "a client JWT is not accepted in canonical form");
    // a network JWT has no client_id claim
    ExpectThrows<EmbedConfigException>(
        () => StateFiles.ParseClientJwtClientId(Jwt(new { network_id = OtherClientId })),
        "a network JWT is accepted");
    foreach (string malformed in new[] { "abc", "a.b", "a..c", "a.!!!.c", "" }) {
      ExpectThrows<EmbedConfigException>(() => StateFiles.ParseClientJwtClientId(malformed),
                                         $"the malformed token \"{malformed}\" is accepted");
    }
    ExpectThrows<EmbedConfigException>(
        () => StateFiles.ParseClientJwtClientId(Jwt(new { client_id = "not-a-uuid" })),
        "an invalid client_id claim is accepted");
  }

  /// HTTPS origins and loopback HTTP only.
  private static void CheckOrigins() {
    (string Url, string Want)[] valid = [
      ("https://api.bringyour.com", "https://api.bringyour.com/"),
      ("https://example.com/", "https://example.com/"),
      ("http://127.0.0.1:8790", "http://127.0.0.1:8790/"),
      ("http://localhost:8790", "http://localhost:8790/"),
      ("http://[::1]:8790", "http://[::1]:8790/"),
    ];
    foreach (var c in valid) {
      string origin = Origins.Parse(c.Url, "URL").ToString();
      Expect(origin == c.Want, $"origin {c.Url} reads as {origin}, want {c.Want}");
    }
    foreach (string url in new[] {
               "http://example.com", "https://example.com/path", "https://example.com/?x=1",
               "https://example.com/#f", "https://user:pw@example.com", "ftp://example.com",
               "example.com", "",
             }) {
      ExpectThrows<EmbedConfigException>(() => Origins.Parse(url, "URL"), $"the origin {url} is accepted");
    }
  }

  /// A stand-in HTTP server: it records the request and answers with respond.
  private sealed class StandIn : HttpMessageHandler {
    private readonly Func<HttpStatusCode, string> respondBody;
    private readonly HttpStatusCode status;
    private readonly bool unreachable;
    public string Method = "";
    public string PathAndQuery = "";
    public string Authorization = "";
    public string Body = "";

    public StandIn(HttpStatusCode status, string body, bool unreachable = false) {
      this.status = status;
      respondBody = _ => body;
      this.unreachable = unreachable;
    }

    protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request,
                                                                 CancellationToken cancel) {
      Method = request.Method.Method;
      PathAndQuery = request.RequestUri?.PathAndQuery ?? "";
      Authorization = request.Headers.Authorization?.ToString() ?? "";
      Body = request.Content == null ? "" : await request.Content.ReadAsStringAsync(cancel);
      if (unreachable) {
        throw new HttpRequestException("connection refused");
      }
      return new HttpResponseMessage(status) { Content = new StringContent(respondBody(status)) };
    }
  }

  /// The token fetch against a stand-in token server.
  private static void CheckTokenFetch() {
    string stateDir = NewPrivateDir();
    try {
      string jwt = Jwt(new { client_id = ClientId });
      string clientJwtPath = Path.Combine(stateDir, StateFiles.ClientJwtFileName);
      var ok = new StandIn(HttpStatusCode.OK, $$$"""
          {"client_id":"{{{ClientId}}}","by_client_jwt":"{{{jwt}}}",
           "data_cap":{"client_id":"{{{ClientId}}}","monthly_byte_limit":10000000000,"monthly_used_byte_count":1,"capped":false,"capped_reason":""}}
          """);
      ClientCredential credential = Fetch(ok, stateDir);
      Expect(ok.Method == "POST" && ok.PathAndQuery == "/urnetwork/client-token",
             $"the token request is {ok.Method} {ok.PathAndQuery}");
      Expect(ok.Authorization == "Bearer " + DemoSession, "the token request does not carry the demo session");
      using (var body = JsonDocument.Parse(ok.Body)) {
        Expect(body.RootElement.GetProperty("installation_id").GetString() == InstanceId,
               "the token request does not carry the instance id");
      }
      Expect(credential.ClientId == ClientId && credential.ClientJwt == jwt &&
             credential.FirstCap is { MonthlyByteLimit: 10000000000 },
             "the token answer is not read");
      Expect(File.ReadAllText(clientJwtPath) == jwt + "\n", "client.jwt is not saved");
      ExpectPrivateFile(clientJwtPath);
      Expect(Directory.GetFiles(stateDir).Length == 1, "the token save leaves a temporary file");

      // a client_id that does not match the claim is refused, and the saved
      // client.jwt stays as it was
      var mismatched = new StandIn(HttpStatusCode.OK,
                                   $$"""{"client_id":"{{OtherClientId}}","by_client_jwt":"{{jwt}}","data_cap":null}""");
      var refused = ExpectThrows<TokenFetchException>(() => Fetch(mismatched, stateDir),
                                                      "a mismatched client_id is accepted");
      Expect(refused.ExitCode == 1 && refused.GuiStatus == "stopped", "a mismatched client_id is not a failure");
      Expect(File.ReadAllText(clientJwtPath) == jwt + "\n", "a refused answer replaced client.jwt");

      (HttpStatusCode Status, string Body, int Exit, string Gui, string Says)[] answers = [
        (HttpStatusCode.Unauthorized, """{"error":{"code":"unauthorized","message":"unknown session"}}""", 78, "signed out", "unknown session"),
        (HttpStatusCode.Conflict, """{"error":{"code":"installation_limit","message":"too many installations"}}""", 78, "signed out", "too many installations"),
        (HttpStatusCode.Conflict, """{"error":{"code":"client_limit","message":"your network is at its client limit; see https://ur.io/services"}}""", 78, "signed out", "https://ur.io/services"),
        (HttpStatusCode.Unauthorized, "", 78, "signed out", "refused"),
        (HttpStatusCode.InternalServerError, """{"error":{"code":"upstream","message":"upstream failed"}}""", 1, "stopped", "HTTP 500"),
        (HttpStatusCode.ServiceUnavailable, """{"error":{"code":"busy","message":"retry"}}""", 1, "stopped", "HTTP 503"),
        (HttpStatusCode.BadRequest, """{"error":{"code":"invalid_request","message":"bad"}}""", 1, "stopped", "HTTP 400"),
        (HttpStatusCode.NotFound, "404 page not found", 1, "stopped", "HTTP 404"),
        (HttpStatusCode.MethodNotAllowed, """{"error":{"code":"method_not_allowed","message":"use POST"}}""", 1, "stopped", "HTTP 405"),
        (HttpStatusCode.InternalServerError, """{"error":{"code":"internal","message":"internal error"}}""", 1, "stopped", "HTTP 500"),
        (HttpStatusCode.BadGateway, """{"error":{"code":"upstream","message":"upstream failed"}}""", 1, "stopped", "HTTP 502"),
        (HttpStatusCode.OK, "not json", 1, "stopped", "invalid"),
        (HttpStatusCode.OK, $$"""{"client_id":"{{ClientId}}","by_client_jwt":"{{Jwt(new { network_id = ClientId })}}"}""", 1, "stopped", "client_id"),
      ];
      foreach (var a in answers) {
        var e = ExpectThrows<TokenFetchException>(() => Fetch(new StandIn(a.Status, a.Body), stateDir),
                                                  $"the answer {(int)a.Status} {a.Body} is accepted");
        Expect(e.ExitCode == a.Exit && e.GuiStatus == a.Gui && e.Message.Contains(a.Says),
               $"the answer {(int)a.Status} maps to exit {e.ExitCode} \"{e.GuiStatus}\" ({e.Message})");
      }
      var unreachable = ExpectThrows<TokenFetchException>(
          () => Fetch(new StandIn(HttpStatusCode.OK, "", unreachable: true), stateDir),
          "an unreachable token server is accepted");
      Expect(unreachable.ExitCode == 1 && unreachable.GuiStatus == "stopped",
             "an unreachable token server is not a failure");
      Expect(!refused.Message.Contains(DemoSession) && !unreachable.Message.Contains(DemoSession),
             "an error message shows the demo session");
    } finally {
      Directory.Delete(stateDir, recursive: true);
    }
  }

  /// FetchClientJwt against a stand-in server.
  private static ClientCredential Fetch(StandIn standIn, string stateDir) {
    using var invoker = new HttpMessageInvoker(standIn);
    return TokenServerClient.FetchClientJwt(TokenServer, DemoSession, InstanceId, stateDir, invoker,
                                            CancellationToken.None);
  }

  /// The cap read presents the client JWT and treats any failure as no
  /// reading; the Embed-not-enabled refusal is marked.
  private static void CheckCapRead() {
    string jwt = Jwt(new { client_id = ClientId });
    var ok = new StandIn(HttpStatusCode.OK, """{"monthly_byte_limit":5,"capped":false,"capped_reason":""}""");
    CapRead read = Read(ok, jwt);
    Expect(read is { Cap.MonthlyByteLimit: 5, EmbedNotEnabled: false }, "a cap answer is not read");
    Expect(ok.Method == "GET" && ok.PathAndQuery == "/network/client-data-cap" &&
           ok.Authorization == "Bearer " + jwt,
           $"the cap read is {ok.Method} {ok.PathAndQuery}");
    Expect(Read(new StandIn(HttpStatusCode.NotFound, "404 page not found"), jwt) is { Cap: null, EmbedNotEnabled: false },
           "a server without the cap routes is a reading");
    Expect(Read(new StandIn(HttpStatusCode.OK, """{"error":{"message":"denied"}}"""), jwt) is
               { Cap: null, EmbedNotEnabled: false },
           "an error answer is a reading");
    Expect(Read(new StandIn(HttpStatusCode.OK, "", unreachable: true), jwt) is { Cap: null, EmbedNotEnabled: false },
           "an unreachable API is a reading");
    Expect(Read(new StandIn(HttpStatusCode.OK, """{"error":{"message":"Embed isn't enabled for this network."}}"""),
                jwt) is { Cap: null, EmbedNotEnabled: true },
           "the Embed-not-enabled refusal is not marked");
  }

  /// CapClient.ReadAsync against a stand-in API.
  private static CapRead Read(StandIn standIn, string jwt) {
    using var invoker = new HttpMessageInvoker(standIn);
    return CapClient.ReadAsync(ApiOrigin, jwt, invoker).GetAwaiter().GetResult();
  }

  /// The state directory: private files, atomic replacement, one instance id
  /// for the installation's life, no symlinks.
  private static void CheckStateFiles() {
    string stateDir = NewPrivateDir();
    try {
      string instanceId = StateFiles.LoadOrCreateInstanceId(stateDir);
      Expect(Uuid.Parse(instanceId) == instanceId, "instance-id is not a canonical UUID");
      Expect(StateFiles.LoadOrCreateInstanceId(stateDir) == instanceId, "instance-id is not reused");
      ExpectPrivateFile(Path.Combine(stateDir, StateFiles.InstanceIdFileName));

      string path = Path.Combine(stateDir, StateFiles.ClientJwtFileName);
      StateFiles.WritePrivateFile(path, Encoding.UTF8.GetBytes("one"));
      StateFiles.WritePrivateFile(path, Encoding.UTF8.GetBytes("two"));
      Expect(File.ReadAllText(path) == "two", "a state file is not replaced");
      Expect(Directory.GetFiles(stateDir).Length == 2, "a replacement leaves a temporary file");

      if (!OperatingSystem.IsWindows()) {
        File.SetUnixFileMode(path, PrivateFileMode | SharedReadMode);
        ExpectThrows<IOException>(() => StateFiles.ReadPrivateFile(path), "a shared file is read");
        File.SetUnixFileMode(path, PrivateFileMode);
        File.SetUnixFileMode(stateDir, PrivateDirMode | SharedReadMode);
        ExpectThrows<EmbedConfigException>(() => StateFiles.CheckStateDir(stateDir), "a shared directory is accepted");
        File.SetUnixFileMode(stateDir, PrivateDirMode);

        string target = Path.Combine(stateDir, "target");
        StateFiles.WritePrivateFile(target, Encoding.UTF8.GetBytes(Jwt(new { client_id = ClientId })));
        File.Delete(path);
        File.CreateSymbolicLink(path, target);
        ExpectThrows<IOException>(() => StateFiles.ReadPrivateFile(path), "a symlinked file is read");
        ExpectThrows<EmbedConfigException>(
            () => StateFiles.ReadStateFile(stateDir, StateFiles.ClientJwtFileName),
            "a symlinked client.jwt is not a configuration error");
      }
      Expect(StateFiles.ReadPrivateFile(Path.Combine(stateDir, "missing")) == null,
             "a missing file is not null");
    } finally {
      Directory.Delete(stateDir, recursive: true);
    }
  }

  /// The configuration errors, all exit 78.
  private static void CheckSettings() {
    string stateDir = NewPrivateDir();
    try {
      ExpectThrows<EmbedConfigException>(() => EmbedSettings.Load(null, null, null, null),
                                         "a missing state directory is accepted");
      ExpectThrows<EmbedConfigException>(() => EmbedSettings.Load("relative/state", null, null, null),
                                         "a relative state directory is accepted");
      ExpectThrows<EmbedConfigException>(
          () => EmbedSettings.Load(Path.Combine(stateDir, "missing"), null, null, null),
          "a state directory that does not exist is accepted");
      ExpectThrows<EmbedConfigException>(
          () => EmbedSettings.Load(stateDir, TokenServer.ToString(), null, null),
          "a token server without a demo session is accepted");
      ExpectThrows<EmbedConfigException>(
          () => EmbedSettings.Load(stateDir, TokenServer.ToString(), "has space", null),
          "a demo session with whitespace is accepted");
      ExpectThrows<EmbedConfigException>(() => EmbedSettings.Load(stateDir, null, DemoSession, null),
                                         "a demo session without a token server is accepted");
      ExpectThrows<EmbedConfigException>(
          () => EmbedSettings.Load(stateDir, null, null, "http://api.example.com"),
          "a plain HTTP API origin is accepted");

      EmbedSettings withTokenServer = EmbedSettings.Load(stateDir, TokenServer.ToString(), DemoSession, null);
      Expect(withTokenServer.TokenServer == TokenServer && withTokenServer.DemoSession == DemoSession &&
             withTokenServer.ApiOrigin.ToString() == "https://api.bringyour.com/",
             "the token server settings are not read");

      // no token server and no client.jwt
      EmbedSettings withoutTokenServer = EmbedSettings.Load(stateDir, null, null, null);
      ExpectThrows<EmbedConfigException>(
          () => ClientJwtSource.ObtainClientJwt(withoutTokenServer, null, CancellationToken.None),
          "no token server and no client.jwt is accepted");
      // a client.jwt from the backend tool
      StateFiles.SaveClientJwt(stateDir, Jwt(new { client_id = ClientId }));
      ClientCredential credential = ClientJwtSource.ObtainClientJwt(withoutTokenServer, null, CancellationToken.None);
      Expect(credential.ClientId == ClientId && credential.FirstCap == null, "client.jwt is not used");
      // a network JWT in client.jwt
      StateFiles.SaveClientJwt(stateDir, Jwt(new { network_id = ClientId }));
      ExpectThrows<EmbedConfigException>(
          () => ClientJwtSource.ObtainClientJwt(withoutTokenServer, null, CancellationToken.None),
          "a network JWT in client.jwt is accepted");
    } finally {
      Directory.Delete(stateDir, recursive: true);
    }
  }

  /// The start line and the kind of app whose licenses --licenses prints.
  private static void CheckStartLine() {
    Expect(StatusRules.StartLine(ClientId, InstanceId) == $"embed client {ClientId}, installation {InstanceId}",
           "the start line is not the contract's");
    Expect(StatusRules.LicenseApp(windows: true, macOs: false) == "windows" &&
           StatusRules.LicenseApp(windows: false, macOs: true) == "apple" &&
           StatusRules.LicenseApp(windows: false, macOs: false) == "linux",
           "--licenses asks for the wrong kind of app");
    Expect(Program.Usage.Contains("--licenses"), "the usage does not name --licenses");
  }

  /// An unknown command is a usage error, exit 78.
  private static void CheckUsageExitCode() {
    using var output = new StringWriter();
    using var error = new StringWriter();
    Expect(Program.Run(["--bogus"], output, error) == Program.ExitConfig, "a usage error does not exit 78");
    Expect(error.ToString().Contains(Program.Usage), "a usage error does not print the usage");
  }

  /// A new private temporary directory (0700 on POSIX).
  private static string NewPrivateDir() {
    string dir = Directory.CreateTempSubdirectory("ur-embed-selftest-").FullName;
    if (!OperatingSystem.IsWindows()) {
      File.SetUnixFileMode(dir, PrivateDirMode);
    }
    return dir;
  }

  /// A file is private to its owner on POSIX.
  private static void ExpectPrivateFile(string path) {
    if (!OperatingSystem.IsWindows()) {
      Expect((File.GetUnixFileMode(path) & StateFiles.GroupOrOtherAccess) == 0,
             $"{Path.GetFileName(path)} is not private");
    }
  }
}
