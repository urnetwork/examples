using System.Collections.Concurrent;
using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Text.Json;
using URnetwork.SDK;

public static class Program {
  // This CLI creates one bounded set; keep native trampolines alive even when
  // a cancelled query worker delivers a callback after Device.Dispose().
  private static readonly List<Delegate> CallbackRoots = [];
  private sealed record Event(string Kind, string? Source = null,
                              byte[]? Bytes = null, string? Json = null,
                              bool Ok = false);
  private static void Peers(string? json) {
    if (json == null || json == "null") {
      Console.WriteLine("peers unavailable (no snapshot)");
      return;
    }
    using var doc = JsonDocument.Parse(json);
    Console.WriteLine(
        json); // Includes all peer metadata and DisconnectedCount.
    if (doc.RootElement.GetProperty("Connected").ValueKind !=
        JsonValueKind.Array)
      return;
    foreach (var peer in doc.RootElement.GetProperty("Connected")
                 .EnumerateArray()) {
      string? id = peer.GetProperty("ClientId").GetString();
      if (id == null)
        continue;
      Console.WriteLine(
          $"color {id} {Sdk.TakeString(Raw.urnet_get_color_hex(id))}");
    }
  }
  private static void Send(ulong handle, string destination, byte[] bytes) {
    IntPtr p = Marshal.AllocHGlobal(bytes.Length);
    try {
      Marshal.Copy(bytes, 0, p, bytes.Length);
      if (Raw.urnet_device_local_send_subprotocol_bytes(
              handle, 4096, destination, p, bytes.Length) == 0)
        throw new IOException("SDK did not enqueue message");
    } finally {
      Marshal.FreeHGlobal(p);
    }
  }
  private static void Run(string[] args) {
    if (args is ["--self-test"]) {
      Codec.SelfTest();
      return;
    }
    if (args is ["--version"]) {
      Console.WriteLine(Sdk.Version);
      return;
    }
    if (args.Length == 0 ||
        !new[] { "self", "peers", "watch", "send" }.Contains(args[0]) ||
        (args[0] == "send" && args.Length < 3))
      throw new ArgumentException("usage: --self-test | --version | self | " +
                                  "peers | watch | send CLIENT_ID TEXT");
    string? destination =
        args[0] == "send" ? Guid.Parse(args[1]).ToString() : null;
    var events = new BlockingCollection<Event>(256);
    Raw.urnet_subprotocol_cb onMessage =
        (_, protocol, source, pointer, length) => {
          if (protocol != 4096 || length < 16 || length > 4112)
            return;
          byte[] copy = new byte[length];
          Marshal.Copy(pointer, copy, 0,
                       length); // Ephemeral pointer never escapes.
          events.TryAdd(new Event("message", source, copy));
        };
    Raw.urnet_network_peers_change_cb onPeers = (_, json) =>
        events.TryAdd(new Event("peers", Json: json));
    Raw.urnet_subprotocols_query_cb onQuery = (_, json, ok) =>
        events.TryAdd(new Event("query", Json: json, Ok: ok != 0));
    CallbackRoots.AddRange([onMessage, onPeers, onQuery]);
    using var cancel = new CancellationTokenSource();
    ConsoleCancelEventHandler interrupt = (_, e) => {
      e.Cancel = true;
      cancel.Cancel();
    };
    Console.CancelKeyPress += interrupt;
    try {
      using var session = new UrSession(connect: false);
      ulong h = session.Device.Value, sub = 0, peerSub = 0;
      try {
        sub = Raw.urnet_device_local_enable_subprotocol(
            h, 4096, onMessage, IntPtr.Zero, out var error);
        string? message = Sdk.TakeString(error);
        if (message != null || sub == 0)
          throw new IOException(message ?? "subprotocol registration failed");
        peerSub = Raw.urnet_device_add_network_peers_change_listener(
            h, onPeers, IntPtr.Zero);
        Raw.urnet_device_set_provide_mode(h, 1); // URNET_PROVIDE_MODE_NETWORK
        Console.WriteLine("self: " +
                          Sdk.TakeString(Raw.urnet_device_get_client_id(h)));
        if (args[0] == "self")
          return;
        string? snapshot = Sdk.TakeString(Raw.urnet_device_get_network_peers(
            h)); // Listener is registered first.
        Peers(snapshot);
        if (args[0] == "peers" && snapshot != null && snapshot != "null")
          return;
        ulong pending =
            BitConverter.ToUInt64(RandomNumberGenerator.GetBytes(8));
        if (pending == 0)
          pending = 1;
        var clock = Stopwatch.StartNew();
        TimeSpan deadline = TimeSpan.FromSeconds(30);
        bool queried = false;
        while (!cancel.IsCancellationRequested) {
          if (destination != null && !queried &&
              Raw.urnet_device_local_get_provider_connected(h) != 0) {
            queried = true;
            Raw.urnet_device_local_query_subprotocols(h, destination, 10000,
                                                      onQuery, IntPtr.Zero);
          }
          if (events.TryTake(out var e, 100))
            switch (e.Kind) {
            case "peers":
              Peers(e.Json);
              if (args[0] == "peers" && e.Json != null && e.Json != "null")
                return;
              break;
            case "query":
              if (!e.Ok || e.Json == null ||
                  !(JsonSerializer.Deserialize<int[]>(e.Json)?.Contains(4096) ??
                    false))
                throw new IOException(
                    "peer query failed or peer does not advertise 4096");
              Send(h, destination!,
                   Codec.Encode(
                       new Frame(1, pending, string.Join(" ", args[2..]))));
              Console.WriteLine($"sent: {pending} waiting for ACK");
              deadline = clock.Elapsed + TimeSpan.FromSeconds(10);
              break;
            case "message":
              Frame f;
              try {
                f = Codec.Decode(e.Bytes!);
              } catch (ArgumentException invalid) {
                Console.Error.WriteLine("rejected frame: " + invalid.Message);
                continue;
              }
              Console.WriteLine(
                  $"kind={f.Kind} source={e.Source} id={f.Id} text={JsonSerializer.Serialize(f.Text)}");
              if (f.Kind == 1)
                Send(h, e.Source!, Codec.Encode(new Frame(2, f.Id, "")));
              else if (e.Source == destination && f.Id == pending)
                return;
              break;
            }
          if (args[0] != "watch" && clock.Elapsed > deadline)
            throw new TimeoutException(
                "connection, peer snapshot, query, or ACK timed out");
        }
      } finally {
        if (peerSub != 0) {
          Raw.urnet_sub_close(peerSub);
          Raw.urnet_release(peerSub);
        }
        if (sub != 0) {
          Raw.urnet_sub_close(sub);
          Raw.urnet_release(sub);
        }
        Raw.urnet_device_local_disable_subprotocol(h, 4096);
      }
    } finally {
      Console.CancelKeyPress -= interrupt;
      // Keep delegates rooted through subscription removal and
      // Device.Dispose().
      GC.KeepAlive(onMessage);
      GC.KeepAlive(onPeers);
      GC.KeepAlive(onQuery);
    }
  }
  public static int Main(string[] args) {
    try {
      Run(args);
      return 0;
    } catch (Exception e) {
      Console.Error.WriteLine(e.Message);
      return 1;
    }
  }
}
