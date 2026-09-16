// SERVER ONLY: dotnet run -- user:<authenticated-service-user-id> | --self-test
// The backend supplies the key after authentication, never from a raw request field
// or UR client ID. Set URNETWORK_ROOT_JWT, URNETWORK_CLIENT_MAP (absolute file path,
// existing service-owned parent), and optional URNETWORK_API_URL. A crash may leave
// .lock; remove it only after confirming it is stale.
using System.Net.Http.Headers;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;
using System.Text.RegularExpressions;

record RequestBody([property: JsonPropertyName("description")] string Description,
    [property: JsonPropertyName("device_spec")] string DeviceSpec,
    [property: JsonPropertyName("client_id"), JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)] string? ClientId);
record ClientResult([property: JsonPropertyName("client_id")] string ClientId,
    [property: JsonPropertyName("by_client_jwt")] string Jwt);
record ClientMap([property: JsonPropertyName("version")] int Version,
    [property: JsonPropertyName("clients")] Dictionary<string, string> Clients);

static class Program
{
    const int Limit = 1 << 20;
    static readonly Regex Users = new(@"\Auser:[A-Za-z0-9][A-Za-z0-9_.:@-]{0,122}\z");
    static readonly Regex Ids = new(@"\A[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\z");
    static string ServiceUser(string[] args)
    {
        if (args.Length != 1 || !Users.IsMatch(args[0])) throw new Exception("expected one user:<service-user-id>");
        return args[0];
    }
    static Uri Endpoint(string value)
    {
        var u = new Uri(value, UriKind.Absolute);
        bool local = u.Host is "localhost" or "127.0.0.1" or "[::1]" or "::1";
        if (u.Host.Length == 0 || u.UserInfo.Length != 0 || u.AbsolutePath != "/" || u.Query.Length != 0 || u.Fragment.Length != 0 ||
            (u.Scheme != "https" && !(u.Scheme == "http" && local))) throw new Exception("API must be HTTPS origin, or explicit loopback HTTP mock");
        return new Uri(u, "/network/auth-client");
    }
    static RequestBody RequestFor(string user, string? client)
    {
        ServiceUser([user]);
        if (client != null && !Ids.IsMatch(client)) throw new Exception("invalid mapped client");
        return new("service " + user, "urnetwork-examples/csharp-server", client);
    }
    static byte[] Decode64(string part) => Convert.FromBase64String(part.Replace('-', '+').Replace('_', '/') + new string('=', (4 - part.Length % 4) % 4));
    static ClientResult ParseResponse(string raw, string? expected)
    {
        using var doc = JsonDocument.Parse(raw);
        var obj = doc.RootElement;
        if (obj.TryGetProperty("error", out var error) && error.ValueKind != JsonValueKind.Null) throw new Exception("API failure");
        string id = obj.GetProperty("client_id").GetString() ?? "", jwt = obj.GetProperty("by_client_jwt").GetString() ?? "";
        string[] parts = jwt.Split('.');
        if (!Ids.IsMatch(id) || parts.Length != 3 || parts.Any(string.IsNullOrEmpty) || !Regex.IsMatch(parts[1], @"\A[A-Za-z0-9_-]+\z")) throw new Exception("invalid scoped result");
        using var claims = JsonDocument.Parse(Decode64(parts[1]));
        if (claims.RootElement.GetProperty("client_id").GetString() != id || (expected != null && expected != id)) throw new Exception("scoped identity mismatch");
        // Claim consistency checking is not local signature verification.
        return new(id, jwt);
    }
    static ClientMap LoadMap(string file)
    {
        if (!File.Exists(file)) return new(1, new());
        var info = new FileInfo(file);
        if (info.LinkTarget != null || info.Length > Limit) throw new Exception("invalid mapping file");
        if (!OperatingSystem.IsWindows() && ((int)File.GetUnixFileMode(file) & 0x3f) != 0) throw new Exception("mapping must be private (0600)");
        var map = JsonSerializer.Deserialize<ClientMap>(File.ReadAllText(file)) ?? throw new Exception("invalid mapping");
        if (map.Version != 1 || map.Clients == null) throw new Exception("invalid mapping");
        var seen = new HashSet<string>();
        foreach (var (user, id) in map.Clients)
            if (!Users.IsMatch(user) || id == null || !Ids.IsMatch(id) || !seen.Add(id)) throw new Exception("invalid mapped client");
        return map;
    }
    static FileStream CreatePrivate(string file)
    {
        var options = new FileStreamOptions {Mode = FileMode.CreateNew, Access = FileAccess.Write, Share = FileShare.None};
        if (!OperatingSystem.IsWindows()) options.UnixCreateMode = UnixFileMode.UserRead | UnixFileMode.UserWrite;
        return new FileStream(file, options);
    }
    static void SaveMap(string file, ClientMap map)
    {
        string temp = file + "." + Guid.NewGuid();
        try
        {
            using (var output = CreatePrivate(temp))
            {
                JsonSerializer.Serialize(output, map);
                output.Flush(true);
            }
            File.Move(temp, file, true);
        }
        finally { if (File.Exists(temp)) File.Delete(temp); }
    }
    static async Task<ClientResult> Allocate(string user, string file, Func<RequestBody, Task<string>> call)
    {
        if (!Path.IsPathFullyQualified(file) || !Directory.Exists(Path.GetDirectoryName(file))) throw new Exception("mapping needs absolute path and existing parent");
        string lockFile = file + ".lock";
        var guard = CreatePrivate(lockFile); // Exclusive through API call and save, including other languages' directory locks.
        try
        {
            var map = LoadMap(file);
            map.Clients.TryGetValue(user, out var old);
            var result = ParseResponse(await call(RequestFor(user, old)), old);
            if (old == null)
            {
                if (map.Clients.Values.Contains(result.ClientId)) throw new Exception("client assigned to another user");
                map.Clients[user] = result.ClientId;
                SaveMap(file, map);
            }
            return result;
        }
        finally { guard.Dispose(); File.Delete(lockFile); }
    }
    static async Task<string> Post(Uri url, string root, RequestBody body)
    {
        if (root.Length == 0 || root.Any(char.IsWhiteSpace)) throw new Exception("set backend root JWT");
        using var handler = new HttpClientHandler {AllowAutoRedirect = false};
        using var client = new HttpClient(handler) {Timeout = TimeSpan.FromSeconds(15)};
        using var request = new HttpRequestMessage(HttpMethod.Post, url);
        request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", root);
        request.Content = new StringContent(JsonSerializer.Serialize(body), Encoding.UTF8, "application/json");
        using var deadline = new CancellationTokenSource(TimeSpan.FromSeconds(15));
        using var response = await client.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, deadline.Token);
        if (!response.IsSuccessStatusCode) throw new Exception("provisioning HTTP failure");
        using var input = await response.Content.ReadAsStreamAsync(deadline.Token);
        using var output = new MemoryStream();
        var buffer = new byte[8192]; int count;
        while ((count = await input.ReadAsync(buffer, deadline.Token)) != 0)
        {
            if (output.Length + count > Limit) throw new Exception("response too large");
            output.Write(buffer, 0, count);
        }
        return Encoding.UTF8.GetString(output.ToArray());
    }
    static void Check(bool ok) { if (!ok) throw new Exception("self-test assertion failed"); }
    static void Reject(Action action) {try {action();} catch {return;} throw new Exception("invalid input accepted");}
    static async Task SelfTest()
    {
        const string id = "11111111-1111-1111-1111-111111111111";
        var payload = Convert.ToBase64String(Encoding.UTF8.GetBytes(JsonSerializer.Serialize(new {client_id = id}))).TrimEnd('=').Replace('+', '-').Replace('/', '_');
        string jwt = "e30." + payload + ".test", raw = JsonSerializer.Serialize(new ClientResult(id, jwt));
        string directory = Path.Combine(Path.GetTempPath(), "ur-allocator-" + Guid.NewGuid());
        Directory.CreateDirectory(directory);
        try
        {
            var calls = new List<RequestBody>();
            Task<string> Mock(RequestBody body) { calls.Add(body); return Task.FromResult(raw); }
            string file = Path.Combine(directory, "clients.json");
            Check((await Allocate("user:alice", file, Mock)).ClientId == id);
            Check((await Allocate("user:alice", file, Mock)).Jwt == jwt);
            Check(!JsonSerializer.Serialize(calls[0]).Contains("client_id") && calls[1].ClientId == id);
            Check(!JsonSerializer.Serialize(calls).Contains("source_client_id") && LoadMap(file).Clients["user:alice"] == id);
            Check(Endpoint("http://127.0.0.1:1234").AbsolutePath == "/network/auth-client");
            Reject(() => ServiceUser([id])); Reject(() => ServiceUser(["user:a", "--client-id", id]));
            Reject(() => ServiceUser(["user:../a"])); Reject(() => Endpoint("http://example.com"));
            Reject(() => Endpoint("https://example.com/path")); Reject(() => ParseResponse("{\"error\":{}}", null));
            Reject(() => ParseResponse(raw, "22222222-2222-2222-2222-222222222222"));
        }
        finally { Directory.Delete(directory, true); }
        Console.WriteLine("allocator self-test passed");
    }
    static async Task<int> Main(string[] args)
    {
        try
        {
            if (args.SequenceEqual(new[] {"--self-test"})) { await SelfTest(); return 0; }
            string user = ServiceUser(args);
            var url = Endpoint(Environment.GetEnvironmentVariable("URNETWORK_API_URL") ?? "https://api.bringyour.com");
            string root = Environment.GetEnvironmentVariable("URNETWORK_ROOT_JWT") ?? throw new Exception(),
                   file = Environment.GetEnvironmentVariable("URNETWORK_CLIENT_MAP") ?? throw new Exception();
            Console.WriteLine(JsonSerializer.Serialize(await Allocate(user, file, body => Post(url, root, body))));
            return 0;
        }
        catch { Console.Error.WriteLine("allocator failed: check service key, private mapping and backend API configuration"); return 1; }
    }
}
