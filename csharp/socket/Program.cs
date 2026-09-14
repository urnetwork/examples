using System.Net;
using System.Text;
using URnetwork.SDK;
using RestSharp;

if (args.FirstOrDefault() == "--version") {Console.WriteLine(Sdk.Version); return;}
if (args.FirstOrDefault() == "--new-id") {Console.WriteLine(Guid.NewGuid()); return;}
using var session = new UrSession();
if (args.FirstOrDefault() is "udp" or "dtls") {
    if (args.Length < 2) throw new ArgumentException("Pass UDP/DTLS echo host:port.");
    using var conn = args[0] == "udp" ? session.Device.Dial("udp", args[1]) : session.Device.DialTls("udp", args[1]);
    conn.SetDeadline(DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() + 10000);
    conn.Write(Encoding.UTF8.GetBytes("hello"));
    var reply = conn.Read();
    Console.WriteLine(Encoding.UTF8.GetString(reply.Data) + "; EOF=" + reply.Eof);
    return;
}
using var handler = new SocketsHttpHandler {
    UseProxy = false,
    ConnectCallback = async (context, cancellation) => {
        var endpoint = context.DnsEndPoint;
        string host = endpoint.Host.Contains(':') ? "[" + endpoint.Host + "]" : endpoint.Host;
        // Keep DNS inside UR. HttpClient applies TLS to this plain TCP stream.
        var pending = Task.Run(() => session.Device.Dial("tcp", host + ":" + endpoint.Port, 30000));
        try {
            var conn = await pending.WaitAsync(cancellation).ConfigureAwait(false);
            return new UrStream(conn);
        } catch (OperationCanceledException) {
            _ = pending.ContinueWith(task => {
                if (task.IsCompletedSuccessfully) task.Result.Dispose();
                else _ = task.Exception;
            }, TaskScheduler.Default);
            throw;
        }
    }
};
using var client = new HttpClient(handler) {
    Timeout = TimeSpan.FromSeconds(30),
    DefaultRequestVersion = HttpVersion.Version20,
    DefaultVersionPolicy = HttpVersionPolicy.RequestVersionOrLower
};
string url = args.LastOrDefault() is string value && value.StartsWith("http") ? value : "https://example.com/";
if (args.FirstOrDefault() == "restsharp") {
    using var rest = new RestClient(client, disposeHttpClient: false);
    var response = await rest.ExecuteAsync(new RestRequest(url));
    if (!response.IsSuccessful) throw new HttpRequestException(response.ErrorMessage ?? response.StatusCode.ToString());
    Console.WriteLine(response.Content);
} else {
    using var response = await client.GetAsync(url);
    response.EnsureSuccessStatusCode();
    Console.WriteLine(await response.Content.ReadAsStringAsync());
}
