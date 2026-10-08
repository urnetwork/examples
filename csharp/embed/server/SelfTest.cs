// The backend tool's credential-free self-test (EMBED_CONTRACT.md, "Backend
// tools"). It runs the commands against a stand-in URnetwork API in a
// temporary private directory: no credentials, no network. The data is
// synthetic.
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;

/// A stand-in URnetwork API that keeps the clients it issued.
internal sealed class StandInApi {
  public readonly List<ApiRequest> Requests = [];
  // every client JWT this stand-in issued
  public readonly List<string> IssuedJwts = [];
  // every client JWT any stand-in issued, to check that none is printed
  public static readonly List<string> AllIssuedJwts = [];
  // clients whose reissue and removal answer "Client does not exist."
  public readonly HashSet<string> Missing = [];
  // when set, a new client is refused with this client limit flag
  public string? LimitFlag;
  // when set, every request answers with this HTTP status
  public int? Status;
  // when set, a new client's JWT claims this client id instead of its own
  public string? ClaimOverride;
  // the client-data-caps pages by cursor ("" for the first page)
  public Dictionary<string, string> Pages = [];
  // when set, the cap routes answer this body
  public string? CapAnswer;
  private int nextClient = 1;
  private int generation;

  /// A client id the stand-in issues.
  public static string Id(int n) => $"{n:x8}-0000-4000-8000-{n:x12}";

  /// An unsigned JWT with the client_id claim; the tool checks the claim only.
  public static string Jwt(string clientId, int generation) {
    string payload = Convert.ToBase64String(JsonSerializer.SerializeToUtf8Bytes(
                                                new { client_id = clientId, generation }))
                         .TrimEnd('=').Replace('+', '-').Replace('/', '_');
    return "eyJhbGciOiJub25lIn0." + payload + ".c2lnbmF0dXJl";
  }

  /// Answers one request.
  public Task<ApiResponse> Handle(ApiRequest request) {
    Requests.Add(request);
    if (Status is int status) {
      return Answer(status, "{}");
    }
    JsonObject body = request.Body == null ? [] : JsonNode.Parse(request.Body) as JsonObject ?? [];
    string path = request.PathAndQuery;
    if (request.Method == "POST" && path == Program.AuthClientPath) {
      if (Program.Text(body["client_id"]) is { } clientId) {
        if (Missing.Contains(clientId)) {
          return Answer(200, """{"error":{"message":"Client does not exist."}}""");
        }
        return Issue(clientId, clientId);
      }
      if (LimitFlag != null) {
        var refusal = new JsonObject {
          ["error"] = new JsonObject { [LimitFlag] = true, ["message"] = "Client limit exceeded." },
        };
        return Answer(200, refusal.ToJsonString());
      }
      string id = Id(nextClient++);
      return Issue(id, ClaimOverride ?? id);
    }
    if (request.Method == "POST" && path == Program.CapPath) {
      return Answer(200, CapAnswer ?? Cap(Program.Text(body["client_id"]) ?? ""));
    }
    if (request.Method == "GET" && path.StartsWith(Program.CapPath + "?client_id=", StringComparison.Ordinal)) {
      return Answer(200, CapAnswer ?? Cap(Uri.UnescapeDataString(path[(Program.CapPath.Length + 11)..])));
    }
    if (request.Method == "GET" && path.StartsWith(Program.CapsPath + "?limit=1000", StringComparison.Ordinal)) {
      string rest = path[(Program.CapsPath.Length + 11)..];
      string cursor = rest.StartsWith("&cursor=", StringComparison.Ordinal) ? Uri.UnescapeDataString(rest[8..]) : "";
      return Answer(200, Pages.GetValueOrDefault(cursor, """{"clients":[],"next_cursor":null}"""));
    }
    if (request.Method == "POST" && path == Program.RemovePath) {
      string id = Program.Text(body["client_id"]) ?? "";
      return Answer(200, Missing.Contains(id) ? """{"error":{"message":"Client does not exist."}}""" : "{}");
    }
    return Answer(404, "404 page not found");
  }

  /// A cap object for a client.
  public static string Cap(string clientId) =>
      $$"""{"client_id":"{{clientId}}","monthly_byte_limit":10000000000,"monthly_used_byte_count":0,"total_byte_limit":null,"total_used_byte_count":0,"capped":false,"capped_reason":""}""";

  private Task<ApiResponse> Issue(string clientId, string claim) {
    string jwt = Jwt(claim, ++generation);
    IssuedJwts.Add(jwt);
    AllIssuedJwts.Add(jwt);
    return Answer(200, new JsonObject { ["client_id"] = clientId, ["by_client_jwt"] = jwt }.ToJsonString());
  }

  private static Task<ApiResponse> Answer(int status, string body) => Task.FromResult(new ApiResponse(status, body));
}

/// Runs every check; a failed check throws with its reason.
internal static class SelfTest {
  private const string Root = "selftest-root-credential-not-a-secret";

  private static readonly StringBuilder AllOutput = new();

  /// Runs every check in order and stops at the first failure.
  public static void Run() {
    string dir = Directory.CreateTempSubdirectory("ur-embed-server-").FullName;
    try {
      if (!OperatingSystem.IsWindows()) {
        File.SetUnixFileMode(dir, UnixFileMode.UserRead | UnixFileMode.UserWrite | UnixFileMode.UserExecute);
      }
      CheckProvision(dir);
      CheckArguments(dir);
      CheckCap(dir);
      CheckUsageAll(dir);
      CheckRemove(dir);
      CheckMap(dir);
      CheckNothingSecretPrinted();
    } finally {
      Directory.Delete(dir, recursive: true);
    }
  }

  private static void Expect(bool condition, string reason) {
    if (!condition) {
      throw new Exception(reason);
    }
  }

  /// The settings of a run with a map in dir.
  private static Func<string, string?> Env(string dir, string map = "clients.json") {
    var settings = new Dictionary<string, string?> {
      ["URNETWORK_ROOT_JWT"] = Root,
      ["URNETWORK_CLIENT_MAP"] = Path.Combine(dir, map),
      ["URNETWORK_API_URL"] = "http://127.0.0.1:1234",
    };
    return name => settings.GetValueOrDefault(name);
  }

  /// Runs one command against api; returns the exit code, stdout and stderr.
  private static (int Exit, string Out, string Err) Exec(StandInApi api, Func<string, string?> env,
                                                         params string[] args) {
    using var stdout = new StringWriter();
    using var stderr = new StringWriter();
    int exit = Program.Run(args, env, stdout, stderr, (_, _) => api.Handle);
    AllOutput.Append(stdout).Append(stderr);
    return (exit, stdout.ToString(), stderr.ToString());
  }

  /// The JSON body of a recorded request.
  private static JsonObject Body(ApiRequest request) => (JsonObject)JsonNode.Parse(request.Body!)!;

  /// Whether a file is private to its owner (always true on Windows).
  private static bool Private(string path) =>
      OperatingSystem.IsWindows() || ((int)File.GetUnixFileMode(path) & 0x3f) == 0;

  /// New versus reissue, the re-provision of a missing client, the client
  /// limit, the response checks and the private files.
  private static void CheckProvision(string dir) {
    var api = new StandInApi();
    var env = Env(dir);
    string jwtFile = Path.Combine(dir, "alice.jwt");
    const string key = "user:alice:22222222-2222-2222-2222-222222222222";
    string id = StandInApi.Id(1);

    var run = Exec(api, env, "provision", key, jwtFile);
    Expect(run.Exit == 0 && run.Out.Trim() == $"{{\"client_id\":\"{id}\"}}", $"provision printed {run.Out}{run.Err}");
    JsonObject created = Body(api.Requests[0]);
    Expect(Program.Text(created["description"]) == "embed client" &&
           Program.Text(created["device_spec"]) == "urnetwork-examples/csharp-embed-server" &&
           !created.ContainsKey("client_id") && !created.ContainsKey("source_client_id"),
           "a new client request has the wrong fields");
    Expect(File.ReadAllText(jwtFile) == api.IssuedJwts[0] + "\n" && Private(jwtFile),
           "the client JWT file is not written privately");
    Expect(ClientMap.Load(Path.Combine(dir, "clients.json")).Clients[key] == id &&
           Private(Path.Combine(dir, "clients.json")),
           "the map is not saved privately");

    run = Exec(api, env, "provision", key, jwtFile);
    JsonObject reissued = Body(api.Requests[1]);
    Expect(run.Exit == 0 && Program.Text(reissued["client_id"]) == id && !reissued.ContainsKey("source_client_id"),
           "a mapped key is not reissued");
    Expect(File.ReadAllText(jwtFile) == api.IssuedJwts[1] + "\n", "a reissue does not replace the client JWT file");

    // a client deactivated after 30 days without connecting
    api.Missing.Add(id);
    run = Exec(api, env, "provision", key, jwtFile);
    Expect(run.Exit == 0 && Program.Text(Body(api.Requests[2])["client_id"]) == id &&
           !Body(api.Requests[3]).ContainsKey("client_id"),
           "a missing client is not re-provisioned");
    Expect(ClientMap.Load(Path.Combine(dir, "clients.json")).Clients[key] == StandInApi.Id(2),
           "the re-provisioned client is not mapped");

    foreach (string flag in new[] { "client_limit_exceeded", "upgrade_required" }) {
      var limited = new StandInApi { LimitFlag = flag };
      run = Exec(limited, env, "provision", "user:bob", Path.Combine(dir, "bob.jwt"));
      Expect(run.Exit == 78 && run.Err.Trim() == Program.ClientLimitMessage,
             $"the client limit ({flag}) exits {run.Exit}: {run.Err}");
    }
    var mismatched = new StandInApi { ClaimOverride = StandInApi.Id(9) };
    Expect(Exec(mismatched, env, "provision", "user:carol", Path.Combine(dir, "carol.jwt")).Exit == 1,
           "a client JWT for another client is accepted");
    Expect(Exec(new StandInApi { Status = 401 }, env, "provision", "user:carol", Path.Combine(dir, "carol.jwt")).Exit == 78,
           "a refused root credential does not exit 78");
    Expect(Exec(new StandInApi { Status = 500 }, env, "provision", "user:carol", Path.Combine(dir, "carol.jwt")).Exit == 1,
           "an API failure does not exit 1");
  }

  /// No client JWT and no root credential reached stdout or stderr in any run.
  private static void CheckNothingSecretPrinted() {
    string output = AllOutput.ToString();
    Expect(StandInApi.AllIssuedJwts.Count > 0, "no client JWT was issued");
    foreach (string jwt in StandInApi.AllIssuedJwts) {
      Expect(!output.Contains(jwt), "a client JWT reached stdout or stderr");
    }
    Expect(!output.Contains(Root), "the root credential reached stdout or stderr");
  }

  /// Key, argument and setting rejection, all exit 78.
  private static void CheckArguments(string dir) {
    var api = new StandInApi();
    var env = Env(dir);
    string jwtFile = Path.Combine(dir, "x.jwt");
    string[][] usage = [
      [], ["provision"], ["provision", "user:a"], ["provision", "user:a", jwtFile, "extra"],
      ["provision", "alice", jwtFile], ["provision", "user:../a", jwtFile],
      ["provision", StandInApi.Id(1), jwtFile], ["provision", "user:a", Path.Combine(dir, "missing", "x.jwt")],
      ["usage"], ["remove", "user:a", "extra"], ["usage-all", "extra"], ["unknown"],
      ["cap", "user:a"], ["cap", "user:a", "--monthly", "1GB"], ["cap", "user:a", "--monthly", "-1"],
      ["cap", "user:a", "--monthly", "9223372036854775808"], ["cap", "user:a", "--monthly", "1", "--monthly", "2"],
      ["cap", "user:a", "--foo"], ["cap", "user:a", "--monthly"], ["cap", "user:a", "--reset-total", "--reset-total"],
    ];
    foreach (string[] args in usage) {
      Expect(Exec(api, env, args).Exit == 78, $"the arguments [{string.Join(' ', args)}] do not exit 78");
    }
    Expect(api.Requests.Count == 0, "a rejected command reached the API");
    var noRoot = new Dictionary<string, string?> { ["URNETWORK_CLIENT_MAP"] = Path.Combine(dir, "clients.json") };
    var noMap = new Dictionary<string, string?> { ["URNETWORK_ROOT_JWT"] = Root };
    var relativeMap = new Dictionary<string, string?> { ["URNETWORK_ROOT_JWT"] = Root, ["URNETWORK_CLIENT_MAP"] = "clients.json" };
    var plainHttp = new Dictionary<string, string?> {
      ["URNETWORK_ROOT_JWT"] = Root, ["URNETWORK_CLIENT_MAP"] = Path.Combine(dir, "clients.json"),
      ["URNETWORK_API_URL"] = "http://api.example.com",
    };
    foreach (var settings in new[] { noRoot, noMap, relativeMap, plainHttp }) {
      Expect(Exec(api, name => settings.GetValueOrDefault(name), "provision", "user:a", jwtFile).Exit == 78,
             "missing or invalid settings do not exit 78");
    }
    Expect(Exec(api, Env(dir), "usage", "user:never-provisioned").Exit == 78, "an unmapped key does not exit 78");
  }

  /// The merge request bodies and the cap object.
  private static void CheckCap(string dir) {
    var api = new StandInApi();
    var env = Env(dir, "caps.json");
    Exec(api, env, "provision", "user:dana", Path.Combine(dir, "dana.jwt"));
    string id = StandInApi.Id(1);

    (string[] Args, string Body)[] cases = [
      (["--monthly", "10000000000"], $"{{\"client_id\":\"{id}\",\"monthly_byte_limit\":10000000000}}"),
      (["--total", "null"], $"{{\"client_id\":\"{id}\",\"total_byte_limit\":null}}"),
      (["--monthly", "0"], $"{{\"client_id\":\"{id}\",\"monthly_byte_limit\":0}}"),
      (["--reset-total"], $"{{\"client_id\":\"{id}\",\"reset_total\":true}}"),
      (["--reset-total", "--total", "5", "--monthly", "null"],
       $"{{\"client_id\":\"{id}\",\"monthly_byte_limit\":null,\"total_byte_limit\":5,\"reset_total\":true}}"),
    ];
    foreach (var c in cases) {
      var run = Exec(api, env, ["cap", "user:dana", .. c.Args]);
      Expect(run.Exit == 0 && api.Requests[^1].Method == "POST" && api.Requests[^1].PathAndQuery == Program.CapPath,
             $"cap {string.Join(' ', c.Args)} exits {run.Exit}: {run.Err}");
      Expect(Canonical(api.Requests[^1].Body!) == Canonical(c.Body),
             $"cap {string.Join(' ', c.Args)} sends {api.Requests[^1].Body}, want {c.Body}");
      Expect(Canonical(run.Out) == Canonical(StandInApi.Cap(id)), "cap does not print the cap object");
    }
    var usage = Exec(api, env, "usage", "user:dana");
    Expect(usage.Exit == 0 && api.Requests[^1].Method == "GET" &&
           api.Requests[^1].PathAndQuery == $"{Program.CapPath}?client_id={id}" &&
           Canonical(usage.Out) == Canonical(StandInApi.Cap(id)),
           "usage does not read the cap object with the root credential");
    api.CapAnswer = """{"error":{"message":"Client not in network."}}""";
    Expect(Exec(api, env, "cap", "user:dana", "--monthly", "1").Exit == 1, "a refused cap does not exit 1");
    api.CapAnswer = null;
    Expect(Exec(new StandInApi { Status = 404 }, Env(dir), "usage-all").Exit == 1,
           "a server without the cap routes does not exit 1");
  }

  /// usage-all pages 1000 at a time and stops at a null or repeated cursor.
  private static void CheckUsageAll(string dir) {
    string Page(int first, int count, string? next) {
      var clients = new JsonArray();
      for (int i = first; i < first + count; i++) {
        clients.Add(JsonNode.Parse(StandInApi.Cap(StandInApi.Id(i))));
      }
      return new JsonObject { ["clients"] = clients, ["next_cursor"] = next }.ToJsonString();
    }
    // no map is needed: usage-all lists the network's capped clients
    var noMap = new Dictionary<string, string?> { ["URNETWORK_ROOT_JWT"] = Root };
    var api = new StandInApi();
    api.Pages[""] = Page(1, 2, "c1");
    api.Pages["c1"] = Page(3, 1, "c/2");
    api.Pages["c/2"] = Page(4, 1, null);
    var run = Exec(api, name => noMap.GetValueOrDefault(name), "usage-all");
    Expect(run.Exit == 0 && run.Out.Split('\n', StringSplitOptions.RemoveEmptyEntries).Length == 4,
           $"usage-all printed {run.Out}{run.Err}");
    Expect(api.Requests.Select(r => r.PathAndQuery).SequenceEqual(new[] {
             $"{Program.CapsPath}?limit=1000", $"{Program.CapsPath}?limit=1000&cursor=c1",
             $"{Program.CapsPath}?limit=1000&cursor=c%2F2",
           }),
           "usage-all does not page with the cursor");

    var repeating = new StandInApi();
    repeating.Pages[""] = Page(1, 1, "c1");
    repeating.Pages["c1"] = Page(2, 1, "c1");
    run = Exec(repeating, name => noMap.GetValueOrDefault(name), "usage-all");
    Expect(run.Exit == 0 && repeating.Requests.Count == 2, "usage-all does not stop on a repeated cursor");
  }

  /// remove drops the mapping for both answers.
  private static void CheckRemove(string dir) {
    var api = new StandInApi();
    var env = Env(dir, "remove.json");
    Exec(api, env, "provision", "user:erin", Path.Combine(dir, "erin.jwt"));
    Exec(api, env, "provision", "user:frank", Path.Combine(dir, "frank.jwt"));
    var run = Exec(api, env, "remove", "user:erin");
    Expect(run.Exit == 0 && run.Out.Trim() == $"{{\"removed\":\"{StandInApi.Id(1)}\"}}" &&
           Program.Text(Body(api.Requests[^1])["client_id"]) == StandInApi.Id(1),
           $"remove printed {run.Out}{run.Err}");
    api.Missing.Add(StandInApi.Id(2));
    Expect(Exec(api, env, "remove", "user:frank").Exit == 0, "removing a missing client fails");
    Expect(ClientMap.Load(Path.Combine(dir, "remove.json")).Clients.Count == 0, "remove keeps a mapping");
    Expect(Exec(api, env, "remove", "user:erin").Exit == 78, "removing an unmapped key does not exit 78");
  }

  /// A map with unknown fields is refused untouched, and a held lock fails.
  private static void CheckMap(string dir) {
    string map = Path.Combine(dir, "token-server.json");
    string text = """{"version":1,"clients":{},"pending_caps":[]}""";
    Program.WritePrivate(map, Encoding.UTF8.GetBytes(text));
    var api = new StandInApi();
    Expect(Exec(api, Env(dir, "token-server.json"), "provision", "user:gina", Path.Combine(dir, "gina.jwt")).Exit == 78,
           "a map with unknown fields is accepted");
    Expect(File.ReadAllText(map) == text && api.Requests.Count == 0, "a refused map is rewritten");

    string locked = Path.Combine(dir, "locked.json");
    using (Program.CreatePrivate(locked + ".lock")) {
      Expect(Exec(api, Env(dir, "locked.json"), "provision", "user:hana", Path.Combine(dir, "hana.jwt")).Exit == 1,
             "a held lock does not exit 1");
    }
  }

  /// JSON text in a canonical form, to compare bodies.
  private static string Canonical(string json) => JsonNode.Parse(json)!.ToJsonString();
}
