// SERVER ONLY. The C# embed backend tool (EMBED_CONTRACT.md, "Backend tools"):
// the integration allocator (../../integration/server) extended with what a
// backend needs to embed URnetwork. It provisions one client per user
// installation, sets and reads that client's data caps, reads every capped
// client of the network, removes a client, and sets a client's ACL group.
// Your service authenticates its user first and supplies the key internally:
// never take a key, a client ID or a cap from a raw request field.
//
//   dotnet run -- provision <key> <client-jwt-file>
//   dotnet run -- cap <key> [--monthly <bytes>|--monthly null] [--total <bytes>|--total null] [--reset-total]
//   dotnet run -- usage <key>
//   dotnet run -- usage-all
//   dotnet run -- remove <key>
//   dotnet run -- acl <key> default|isolated
//   dotnet run -- --self-test
//
// <key> is user:<service-user-id> or user:<service-user-id>:<installation-id>.
// Settings: URNETWORK_ROOT_JWT (an API key or a network JWT, from the
// backend's secret store), URNETWORK_CLIENT_MAP (absolute path of this tool's
// private map, in an existing service-owned directory; never the token
// server's map), optional URNETWORK_API_URL and optional
// URNETWORK_DEFAULT_ACL_GROUP (the ACL group of each new client: "isolated",
// the default, or "default"). The map lock is an exclusively created
// <map>.lock file; a crash may leave it, so remove it only after confirming
// that no tool still runs.
//
// Exit codes: 0 success; 78 a configuration or credential problem (missing
// settings, an invalid key or map, the root credential refused, the client
// limit); 1 any other failure, with one stderr line that never holds a secret.
using System.Globalization;
using System.Net.Http.Headers;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;

/// A failure with the exit code it maps to.
internal sealed class ToolException : Exception {
  public int ExitCode { get; }

  /// A failure with its exit code and its one stderr line.
  public ToolException(int exitCode, string message) : base(message) {
    ExitCode = exitCode;
  }
}

/// One request to the URnetwork API, as the transport sees it.
internal sealed record ApiRequest(string Method, string PathAndQuery, string? Body);

/// The answer to one request: the HTTP status and the body.
internal sealed record ApiResponse(int Status, string Body);

/// The options of the cap command. A limit that is given with a null value
/// clears that cap; one that is not given is not sent, so the cap keeps its
/// value.
internal sealed class CapOptions {
  public bool MonthlyGiven { get; set; }
  public long? Monthly { get; set; }
  public bool TotalGiven { get; set; }
  public long? Total { get; set; }
  public bool ResetTotal { get; set; }
}

/// The URnetwork API with the root credential.
internal sealed class Api {
  private readonly Func<ApiRequest, Task<ApiResponse>> transport;

  /// The API over a transport: HTTPS in production, a stand-in in the
  /// self-test.
  public Api(Func<ApiRequest, Task<ApiResponse>> transport) {
    this.transport = transport;
  }

  /// The JSON object of a 2xx answer. HTTP 401 or 403 is the root credential
  /// refused (78); another status, an unreachable API or a body that is not a
  /// JSON object is a failure (1). A refusal inside the object (an "error"
  /// member) is for the caller.
  public JsonObject Call(string method, string pathAndQuery, JsonObject? body) {
    ApiResponse response;
    try {
      response = transport(new ApiRequest(method, pathAndQuery, body?.ToJsonString()))
                     .GetAwaiter().GetResult();
    } catch (Exception e) when (e is HttpRequestException or OperationCanceledException or IOException) {
      throw new ToolException(Program.ExitFailure, "could not reach the URnetwork API");
    }
    if (response.Status is 401 or 403) {
      throw new ToolException(Program.ExitConfig, "the URnetwork API refused the root credential");
    }
    if (response.Status == 404) {
      // a server that predates a route (EMBED_CONTRACT.md, "Backend tools")
      string route = pathAndQuery.Split('?')[0];
      throw new ToolException(Program.ExitFailure,
                              route == Program.AclPath ? Program.AclUnsupportedMessage
                              : route.StartsWith(Program.CapPath, StringComparison.Ordinal)
                                  ? $"{route} answered 404: the server predates the data-cap routes"
                                  : $"{route} answered 404");
    }
    if (response.Status is < 200 or > 299) {
      throw new ToolException(Program.ExitFailure, $"the URnetwork API answered HTTP {response.Status}");
    }
    JsonNode? node = null;
    try {
      node = JsonNode.Parse(response.Body);
    } catch (JsonException) {
      // reported below
    }
    return node as JsonObject ??
           throw new ToolException(Program.ExitFailure, "the URnetwork API answered something other than a JSON object");
  }
}

/// The tool's private map from keys to client ids, in the allocator's format:
/// {"version": 1, "clients": {key: client_id}}, plus "pending_acl", the keys
/// whose new clients still owe their default ACL group, written only while it
/// is not empty. It stores client ids, never tokens.
internal sealed class ClientMap {
  public Dictionary<string, string> Clients { get; } = new();
  public List<string> PendingAcl { get; } = new();

  /// The map in file; an empty map when the file does not exist. A map with
  /// fields this tool does not write, such as the token server's pending_caps,
  /// is refused, so that two tools never rewrite each other's map.
  public static ClientMap Load(string file) {
    var map = new ClientMap();
    var info = new FileInfo(file);
    if (info.LinkTarget == null && !info.Exists) {
      return map;
    }
    if (info.LinkTarget != null || info.Length > Program.ResponseLimit) {
      throw Program.Config("the client map must be a regular file");
    }
    if (!OperatingSystem.IsWindows() && ((int)File.GetUnixFileMode(file) & 0x3f) != 0) {
      throw Program.Config("the client map must be private (0600)");
    }
    JsonNode? node = null;
    try {
      node = JsonNode.Parse(File.ReadAllText(file));
    } catch (JsonException) {
      // reported below
    }
    if (node is not JsonObject root) {
      throw Program.Config("the client map is not a JSON object");
    }
    foreach (var (name, _) in root) {
      if (name is not ("version" or "clients" or "pending_acl")) {
        throw Program.Config(
            "the client map has fields this tool does not write, such as the token server's pending_caps; give each tool its own map");
      }
    }
    if (root["version"] is not JsonValue version || !version.TryGetValue(out int versionNumber) ||
        versionNumber != 1 || root["clients"] is not JsonObject clients) {
      throw Program.Config("the client map is not version 1 with a clients object");
    }
    var seen = new HashSet<string>();
    foreach (var (key, value) in clients) {
      string? id = Program.Text(value);
      if (!Program.Keys.IsMatch(key) || id == null || !Program.Ids.IsMatch(id) || !seen.Add(id)) {
        throw Program.Config("the client map has an invalid entry");
      }
      map.Clients[key] = id;
    }
    if (root.ContainsKey("pending_acl")) {
      if (root["pending_acl"] is not JsonArray pending) {
        throw Program.Config("the client map's pending_acl is not a list of mapped keys");
      }
      foreach (JsonNode? entry in pending) {
        string? key = Program.Text(entry);
        if (key == null || !map.Clients.ContainsKey(key) || map.PendingAcl.Contains(key)) {
          throw Program.Config("the client map's pending_acl is not a list of mapped keys");
        }
        map.PendingAcl.Add(key);
      }
    }
    return map;
  }

  /// Replaces file atomically with this map, private to its owner.
  public void Save(string file) {
    var clients = new JsonObject();
    foreach (var (key, id) in Clients) {
      clients[key] = id;
    }
    var root = new JsonObject { ["version"] = 1, ["clients"] = clients };
    if (PendingAcl.Count > 0) {
      root["pending_acl"] = new JsonArray(PendingAcl.Select(key => (JsonNode?)key).ToArray());
    }
    Program.WritePrivate(file, Encoding.UTF8.GetBytes(root.ToJsonString()));
  }
}

/// The map's exclusive lock: a <map>.lock file created exclusively and held
/// through the remote calls and the map update. The other languages' tools
/// create a <map>.lock directory at the same path, so they exclude each other.
internal sealed class MapLock : IDisposable {
  private readonly FileStream stream;
  private readonly string path;

  private MapLock(FileStream stream, string path) {
    this.stream = stream;
    this.path = path;
  }

  /// Takes the lock of mapFile, or fails when another run holds it.
  public static MapLock Take(string mapFile) {
    string path = mapFile + ".lock";
    try {
      return new MapLock(Program.CreatePrivate(path), path);
    } catch (Exception e) when ((e is IOException or UnauthorizedAccessException) &&
                               (File.Exists(path) || Directory.Exists(path))) {
      throw new ToolException(
          Program.ExitFailure,
          "another run holds the client map's lock; retry, and remove a stale .lock only after confirming that no tool still runs");
    }
  }

  /// Releases the lock.
  public void Dispose() {
    stream.Dispose();
    File.Delete(path);
  }
}

/// Commands, settings and the API requests of the tool.
internal static class Program {
  public const int ExitOk = 0;
  public const int ExitFailure = 1;
  // sysexits EX_CONFIG
  public const int ExitConfig = 78;
  public const int ResponseLimit = 1 << 20;

  public const string Description = "embed client";
  public const string DeviceSpec = "urnetwork-examples/csharp-embed-server";
  public const string ClientDoesNotExist = "Client does not exist.";
  public const string ClientLimitMessage =
      "client limit reached: your network is at its client limit; see https://ur.io/services";
  public const string AuthClientPath = "/network/auth-client";
  public const string CapPath = "/network/client-data-cap";
  public const string CapsPath = "/network/client-data-caps";
  public const string RemovePath = "/network/remove-client";
  public const string AclPath = "/network/client-acl-group";
  public const string UnmappedMessage = "no client is mapped for that key; run provision first";
  public const string AclUnsupportedMessage = "/network/client-acl-group answered 404: the server predates ACL groups";
  public const string Usage =
      "usage: provision <key> <client-jwt-file> | cap <key> [--monthly <bytes>|--monthly null] [--total <bytes>|--total null] [--reset-total] | usage <key> | usage-all | remove <key> | acl <key> default|isolated | --self-test";

  public static readonly Regex Keys = new(@"\Auser:[A-Za-z0-9][A-Za-z0-9_.:@-]{0,122}\z");
  public static readonly Regex Ids = new(@"\A[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\z");
  private static readonly Regex ByteCounts = new(@"\A[0-9]{1,19}\z");

  /// The API for an origin and root credential.
  public delegate Func<ApiRequest, Task<ApiResponse>> TransportFactory(Uri origin, string root);

  /// The settings of one run: the API and, for the commands that use it, the
  /// map file, and the ACL group of each new client ("isolated" or "default").
  private sealed record ToolSettings(Api Api, string? MapFile, string AclGroup);

  /// "client_id" is mapped to a client that no longer exists.
  private sealed class ClientMissingException : Exception {}

  /// Runs the command with the process environment and HTTPS.
  public static int Main(string[] args) {
    return Run(args, Environment.GetEnvironmentVariable, Console.Out, Console.Error, HttpTransport);
  }

  /// Runs one command and returns its exit code. The self-test passes its own
  /// environment, writers and stand-in transport.
  public static int Run(string[] args, Func<string, string?> env, TextWriter stdout, TextWriter stderr,
                        TransportFactory transport) {
    try {
      switch (args) {
      case ["--self-test"]:
        try {
          SelfTest.Run();
        } catch (Exception e) {
          // a tool failure inside a check fails the self-test too
          stderr.WriteLine($"embed backend tool self-test failed: {e.Message}");
          return ExitFailure;
        }
        stdout.WriteLine("embed backend tool self-test passed");
        return ExitOk;
      case ["provision", var key, var jwtFile]:
        Provision(CheckKey(key), CheckJwtFile(jwtFile), Settings(env, transport, map: true), stdout);
        return ExitOk;
      case ["cap", var key, .. var options]:
        Cap(CheckKey(key), ParseCapOptions(options), Settings(env, transport, map: true), stdout);
        return ExitOk;
      case ["usage", var key]:
        ReadUsage(CheckKey(key), Settings(env, transport, map: true), stdout);
        return ExitOk;
      case ["usage-all"]:
        ReadUsageAll(Settings(env, transport, map: false), stdout);
        return ExitOk;
      case ["remove", var key]:
        Remove(CheckKey(key), Settings(env, transport, map: true), stdout);
        return ExitOk;
      case ["acl", var key, var group]:
        Acl(CheckKey(key), group is "default" or "isolated" ? group : throw Config(Usage),
            Settings(env, transport, map: true), stdout);
        return ExitOk;
      default:
        throw Config(Usage);
      }
    } catch (ToolException e) {
      stderr.WriteLine(e.Message);
      return e.ExitCode;
    } catch (Exception e) when (e is IOException or UnauthorizedAccessException) {
      stderr.WriteLine($"embed backend tool failed: {e.Message}");
      return ExitFailure;
    }
  }

  /// A configuration error (78).
  public static ToolException Config(string message) => new(ExitConfig, message);

  /// The API refused a request (1), with its message made safe to print.
  private static ToolException Refused(string message) =>
      new(ExitFailure, $"the URnetwork API refused the request: {Printable(message)}");

  /// Reissues the key's client, or provisions a new one, and writes the
  /// client JWT to jwtFile. A reissue that answers "Client does not exist."
  /// (a client deactivated after 30 days without connecting) drops the
  /// mapping and provisions a new client. Prints {"client_id": ...}, never the
  /// token.
  private static void Provision(string key, string jwtFile, ToolSettings settings, TextWriter stdout) {
    string mapFile = settings.MapFile!;
    using MapLock _ = MapLock.Take(mapFile);
    ClientMap map = ClientMap.Load(mapFile);
    (string ClientId, string Jwt)? result = null;
    if (map.Clients.TryGetValue(key, out string? mapped)) {
      try {
        result = AuthClientResult(settings.Api.Call("POST", AuthClientPath, AuthClientBody(mapped)), mapped);
      } catch (ClientMissingException) {
        map.Clients.Remove(key);
        map.PendingAcl.Remove(key);
        map.Save(mapFile);
      }
    }
    if (result == null) {
      try {
        result = AuthClientResult(settings.Api.Call("POST", AuthClientPath, AuthClientBody(null)), null);
      } catch (ClientMissingException) {
        throw Refused(ClientDoesNotExist);
      }
      if (map.Clients.ContainsValue(result.Value.ClientId)) {
        throw new ToolException(ExitFailure, "the API answered a client that the map assigns to another key");
      }
      map.Clients[key] = result.Value.ClientId;
      // the mapping and the record that the client owes its group, in one save
      if (settings.AclGroup == "isolated") {
        map.PendingAcl.Add(key);
      }
      map.Save(mapFile);
    }
    if (map.PendingAcl.Contains(key)) {
      // a new client is "default": only an isolated default needs the request.
      // A failure throws with the record kept, before any client JWT is written.
      if (settings.AclGroup == "isolated") {
        PostAclGroup(settings.Api, result.Value.ClientId, "isolated");
      }
      map.PendingAcl.Remove(key);
      map.Save(mapFile);
    }
    WritePrivate(jwtFile, Encoding.UTF8.GetBytes(result.Value.Jwt + "\n"));
    stdout.WriteLine(new JsonObject { ["client_id"] = result.Value.ClientId }.ToJsonString());
  }

  /// Posts only the given cap fields for the key's client and prints the cap
  /// object.
  private static void Cap(string key, CapOptions options, ToolSettings settings, TextWriter stdout) {
    using MapLock _ = MapLock.Take(settings.MapFile!);
    string clientId = MappedClient(ClientMap.Load(settings.MapFile!), key);
    stdout.WriteLine(CapObject(settings.Api.Call("POST", CapPath, CapBody(clientId, options))));
  }

  /// Prints the key's cap object, read with the root credential.
  private static void ReadUsage(string key, ToolSettings settings, TextWriter stdout) {
    using MapLock _ = MapLock.Take(settings.MapFile!);
    string clientId = MappedClient(ClientMap.Load(settings.MapFile!), key);
    stdout.WriteLine(CapObject(settings.Api.Call(
        "GET", CapPath + "?client_id=" + Uri.EscapeDataString(clientId), null)));
  }

  /// Pages through every capped client of the network, 1000 at a time, and
  /// prints one cap object per line. It stops at a null cursor or a repeated
  /// one.
  private static void ReadUsageAll(ToolSettings settings, TextWriter stdout) {
    var seenCursors = new HashSet<string>();
    string? cursor = null;
    while (true) {
      string path = CapsPath + "?limit=1000" + (cursor == null ? "" : "&cursor=" + Uri.EscapeDataString(cursor));
      JsonObject page = settings.Api.Call("GET", path, null);
      CheckRefusal(page);
      if (page["clients"] is not JsonArray clients) {
        throw new ToolException(ExitFailure, "the URnetwork API answered a page without clients");
      }
      foreach (JsonNode? client in clients) {
        if (client is not JsonObject capObject) {
          throw new ToolException(ExitFailure, "the URnetwork API answered a client that is not a cap object");
        }
        stdout.WriteLine(capObject.ToJsonString());
      }
      string? next = Text(page["next_cursor"]);
      if (string.IsNullOrEmpty(next) || !seenCursors.Add(next)) {
        return;
      }
      cursor = next;
    }
  }

  /// Removes the key's client, then its mapping, also when the client is
  /// already gone. Prints {"removed": ...}.
  private static void Remove(string key, ToolSettings settings, TextWriter stdout) {
    string mapFile = settings.MapFile!;
    using MapLock _ = MapLock.Take(mapFile);
    ClientMap map = ClientMap.Load(mapFile);
    string clientId = MappedClient(map, key);
    JsonObject answer = settings.Api.Call("POST", RemovePath, new JsonObject { ["client_id"] = clientId });
    if (answer["error"] is { } error) {
      string message = error is JsonObject errorObject ? Text(errorObject["message"]) ?? "" : "";
      if (message != ClientDoesNotExist) {
        throw Refused(message);
      }
    }
    map.Clients.Remove(key);
    map.PendingAcl.Remove(key);
    map.Save(mapFile);
    stdout.WriteLine(new JsonObject { ["removed"] = clientId }.ToJsonString());
  }

  /// Sets the ACL group of the key's client and prints {"client_id": ...,
  /// "acl_group": ...}. An explicit group settles a pending default group, so
  /// the record is dropped.
  private static void Acl(string key, string group, ToolSettings settings, TextWriter stdout) {
    string mapFile = settings.MapFile!;
    using MapLock _ = MapLock.Take(mapFile);
    ClientMap map = ClientMap.Load(mapFile);
    string clientId = MappedClient(map, key);
    PostAclGroup(settings.Api, clientId, group);
    if (map.PendingAcl.Remove(key)) {
      map.Save(mapFile);
    }
    stdout.WriteLine($"{{\"client_id\":\"{clientId}\",\"acl_group\":\"{group}\"}}");
  }

  /// Posts the client's ACL group; the answer must name the client and the
  /// group. A refusal or another answer fails (1).
  private static void PostAclGroup(Api api, string clientId, string group) {
    JsonObject answer = api.Call("POST", AclPath, new JsonObject { ["client_id"] = clientId, ["acl_group"] = group });
    CheckRefusal(answer);
    if (Text(answer["client_id"]) != clientId || Text(answer["acl_group"]) != group) {
      throw new ToolException(ExitFailure, "the URnetwork API answered another client or ACL group");
    }
  }

  /// The auth-client request: a new client without client_id, or a reissue of
  /// the mapped one. Never source_client_id: embed clients are top-level.
  public static JsonObject AuthClientBody(string? clientId) {
    var body = new JsonObject { ["description"] = Description, ["device_spec"] = DeviceSpec };
    if (clientId != null) {
      body["client_id"] = clientId;
    }
    return body;
  }

  /// The client id and client JWT of an auth-client answer. A refusal with
  /// either client limit flag is the client limit (78); "Client does not
  /// exist." throws ClientMissingException; other refusals fail. The JWT's
  /// client_id claim must match the answer, and a reissue must answer the
  /// mapped client. The claim check is not signature verification.
  private static (string ClientId, string Jwt) AuthClientResult(JsonObject answer, string? expected) {
    if (answer["error"] is { } errorNode) {
      if (errorNode is JsonObject error) {
        if (Flag(error, "client_limit_exceeded") || Flag(error, "upgrade_required")) {
          throw new ToolException(ExitConfig, ClientLimitMessage);
        }
        if (Text(error["message"]) == ClientDoesNotExist) {
          throw new ClientMissingException();
        }
        throw Refused(Text(error["message"]) ?? "");
      }
      throw Refused("");
    }
    string id = Text(answer["client_id"]) ?? "";
    string jwt = Text(answer["by_client_jwt"]) ?? "";
    string[] parts = jwt.Split('.');
    if (!Ids.IsMatch(id) || parts.Length != 3 || parts.Any(string.IsNullOrEmpty) ||
        !Regex.IsMatch(parts[1], @"\A[A-Za-z0-9_-]+\z")) {
      throw new ToolException(ExitFailure, "the URnetwork API answered an invalid client");
    }
    string? claim = null;
    try {
      using var claims = JsonDocument.Parse(DecodeBase64Url(parts[1]));
      if (claims.RootElement.ValueKind == JsonValueKind.Object &&
          claims.RootElement.TryGetProperty("client_id", out var claimValue) &&
          claimValue.ValueKind == JsonValueKind.String) {
        claim = claimValue.GetString();
      }
    } catch (Exception e) when (e is JsonException or FormatException) {
      // reported below
    }
    if (claim != id || (expected != null && expected != id)) {
      throw new ToolException(ExitFailure, "the URnetwork API answered a client JWT for another client");
    }
    return (id, jwt);
  }

  /// The cap request: client_id and only the given fields. A cleared cap is
  /// JSON null; byte counts are JSON integers; reset_total only when given.
  public static JsonObject CapBody(string clientId, CapOptions options) {
    var body = new JsonObject { ["client_id"] = clientId };
    if (options.MonthlyGiven) {
      body["monthly_byte_limit"] = options.Monthly is long monthly ? JsonValue.Create(monthly) : null;
    }
    if (options.TotalGiven) {
      body["total_byte_limit"] = options.Total is long total ? JsonValue.Create(total) : null;
    }
    if (options.ResetTotal) {
      body["reset_total"] = true;
    }
    return body;
  }

  /// The cap options; at least one, each at most once. A usage error is a
  /// configuration error (78).
  public static CapOptions ParseCapOptions(string[] args) {
    const string usage =
        "cap needs at least one of --monthly <bytes>|null, --total <bytes>|null and --reset-total, each at most once";
    var options = new CapOptions();
    int index = 0;
    while (index < args.Length) {
      string option = args[index];
      bool hasValue = index + 1 < args.Length;
      if (option == "--monthly" && !options.MonthlyGiven && hasValue) {
        options.MonthlyGiven = true;
        options.Monthly = ByteLimit(args[index + 1]);
        index += 2;
      } else if (option == "--total" && !options.TotalGiven && hasValue) {
        options.TotalGiven = true;
        options.Total = ByteLimit(args[index + 1]);
        index += 2;
      } else if (option == "--reset-total" && !options.ResetTotal) {
        options.ResetTotal = true;
        index += 1;
      } else {
        throw Config(usage);
      }
    }
    if (!options.MonthlyGiven && !options.TotalGiven && !options.ResetTotal) {
      throw Config(usage);
    }
    return options;
  }

  /// A byte count from 0 to 9223372036854775807 without units, or null.
  private static long? ByteLimit(string text) {
    if (text == "null") {
      return null;
    }
    if (!ByteCounts.IsMatch(text) ||
        !long.TryParse(text, NumberStyles.None, CultureInfo.InvariantCulture, out long value)) {
      throw Config("a byte count is a decimal integer from 0 to 9223372036854775807 without units, or null");
    }
    return value;
  }

  /// The cap object of an answer, as one line of JSON; a refusal fails.
  private static string CapObject(JsonObject answer) {
    CheckRefusal(answer);
    return answer.ToJsonString();
  }

  /// Fails when an answer carries a refusal.
  private static void CheckRefusal(JsonObject answer) {
    if (answer["error"] is { } error) {
      throw Refused(error is JsonObject errorObject ? Text(errorObject["message"]) ?? "" : "");
    }
  }

  /// The client mapped to key; an unmapped key is a configuration error.
  private static string MappedClient(ClientMap map, string key) {
    return map.Clients.TryGetValue(key, out string? clientId)
               ? clientId
               : throw Config(UnmappedMessage);
  }

  /// A valid key: user:<service-user-id>, optionally with :<installation-id>.
  private static string CheckKey(string key) {
    return Keys.IsMatch(key) ? key : throw Config("expected a key user:<service-user-id>[:<installation-id>]");
  }

  /// The client JWT file: its directory must exist; a symlink is refused.
  private static string CheckJwtFile(string path) {
    string full;
    try {
      full = Path.GetFullPath(path);
    } catch (Exception e) when (e is ArgumentException or NotSupportedException or PathTooLongException) {
      throw Config("the client JWT file path is not valid");
    }
    if (!Directory.Exists(Path.GetDirectoryName(full)) || Directory.Exists(full) ||
        new FileInfo(full).LinkTarget != null) {
      throw Config("the client JWT file needs an existing directory and must not be a symlink");
    }
    return full;
  }

  /// The settings, checked: the root credential, the API origin and, when the
  /// command uses it, the map file.
  private static ToolSettings Settings(Func<string, string?> env, TransportFactory transport, bool map) {
    string root = env("URNETWORK_ROOT_JWT") ?? "";
    if (root.Length == 0 || root.Any(c => c <= ' ' || c > '~')) {
      throw Config("set URNETWORK_ROOT_JWT to the root credential (an API key or a network JWT) from the backend's secret store");
    }
    Uri origin = Endpoint(env("URNETWORK_API_URL") ?? "https://api.bringyour.com");
    string? mapFile = null;
    if (map) {
      mapFile = env("URNETWORK_CLIENT_MAP");
      if (string.IsNullOrEmpty(mapFile) || !Path.IsPathFullyQualified(mapFile) ||
          !Directory.Exists(Path.GetDirectoryName(mapFile))) {
        throw Config("set URNETWORK_CLIENT_MAP to an absolute file path in an existing private, service-owned directory");
      }
    }
    string aclGroup = env("URNETWORK_DEFAULT_ACL_GROUP") ?? "";
    if (aclGroup is not ("" or "isolated" or "default")) {
      throw Config("URNETWORK_DEFAULT_ACL_GROUP must be default or isolated");
    }
    return new ToolSettings(new Api(transport(origin, root)), mapFile, aclGroup == "" ? "isolated" : aclGroup);
  }

  /// The API origin: HTTPS, or explicit loopback HTTP for local mocks; no
  /// credentials, path, query or fragment.
  public static Uri Endpoint(string value) {
    if (!Uri.TryCreate(value, UriKind.Absolute, out Uri? u)) {
      throw Config("URNETWORK_API_URL must be an HTTPS origin");
    }
    bool local = u.Host is "localhost" or "127.0.0.1" or "[::1]" or "::1";
    if (u.Host.Length == 0 || u.UserInfo.Length != 0 || u.AbsolutePath != "/" || u.Query.Length != 0 ||
        u.Fragment.Length != 0 || (u.Scheme != "https" && !(u.Scheme == "http" && local))) {
      throw Config("URNETWORK_API_URL must be an HTTPS origin, or explicit loopback HTTP for a mock");
    }
    return new Uri(u.GetLeftPart(UriPartial.Authority) + "/");
  }

  /// HTTPS to the API with the root credential: 15 seconds, no redirects, a
  /// bounded answer.
  private static Func<ApiRequest, Task<ApiResponse>> HttpTransport(Uri origin, string root) {
    var client = new HttpClient(new HttpClientHandler { AllowAutoRedirect = false }) {
      Timeout = TimeSpan.FromSeconds(15),
    };
    return async request => {
      using var message = new HttpRequestMessage(new HttpMethod(request.Method), new Uri(origin, request.PathAndQuery));
      message.Headers.Authorization = new AuthenticationHeaderValue("Bearer", root);
      if (request.Body != null) {
        message.Content = new StringContent(request.Body, Encoding.UTF8, "application/json");
      }
      using HttpResponseMessage response = await client.SendAsync(message, HttpCompletionOption.ResponseHeadersRead);
      using Stream input = await response.Content.ReadAsStreamAsync();
      using var output = new MemoryStream();
      var buffer = new byte[8192];
      int count;
      while ((count = await input.ReadAsync(buffer)) != 0) {
        if (output.Length + count > ResponseLimit) {
          throw new IOException("the answer is too large");
        }
        output.Write(buffer, 0, count);
      }
      return new ApiResponse((int)response.StatusCode, Encoding.UTF8.GetString(output.ToArray()));
    };
  }

  /// A string JSON value, or null.
  public static string? Text(JsonNode? node) {
    return node is JsonValue value && value.TryGetValue(out string? text) ? text : null;
  }

  /// Whether a member is JSON true.
  private static bool Flag(JsonObject obj, string name) {
    return obj[name] is JsonValue value && value.TryGetValue(out bool flag) && flag;
  }

  /// Text safe for one stderr line: no control characters, bounded.
  private static string Printable(string text) {
    return new string(text.Select(c => char.IsControl(c) ? ' ' : c).Take(200).ToArray()).Trim();
  }

  /// Unpadded base64url.
  private static byte[] DecodeBase64Url(string part) {
    return Convert.FromBase64String(part.Replace('-', '+').Replace('_', '/') +
                                    new string('=', (4 - part.Length % 4) % 4));
  }

  /// Creates a new file exclusively, private to its owner on POSIX.
  public static FileStream CreatePrivate(string file) {
    var options = new FileStreamOptions { Mode = FileMode.CreateNew, Access = FileAccess.Write, Share = FileShare.None };
    if (!OperatingSystem.IsWindows()) {
      options.UnixCreateMode = UnixFileMode.UserRead | UnixFileMode.UserWrite;
    }
    return new FileStream(file, options);
  }

  /// Replaces file atomically: a private temporary file in the same
  /// directory, synced, then renamed over it.
  public static void WritePrivate(string file, byte[] data) {
    string temp = Path.Combine(Path.GetDirectoryName(file) ?? ".",
                               "." + Path.GetFileName(file) + "." + Guid.NewGuid().ToString("N"));
    try {
      using (FileStream output = CreatePrivate(temp)) {
        output.Write(data);
        output.Flush(true);
      }
      File.Move(temp, file, true);
    } finally {
      if (File.Exists(temp)) {
        File.Delete(temp);
      }
    }
  }
}
