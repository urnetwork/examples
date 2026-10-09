# C# peer messages

[Installation](../README.md) · [Integration](../integration/README.md) · [Sockets](../socket/README.md) · [Messages](README.md) · [Provider](../provider/README.md) · [Embed](../embed/README.md)

This local Device example discovers real-time peers and exchanges text over subprotocol **4096** using the shared [URMS v1 TEXT/ACK format](../../MESSAGES_PROTOCOL.md). Its files are [Program.cs](Program.cs), [Codec.cs](Codec.cs) and [MessagesExample.csproj](MessagesExample.csproj); the Device bootstrap is described in [Integration](../integration/README.md).

## Build and check

Use .NET 8+. The project defaults `UrSdkVersion` to `0.0.1-dev.0`; install that matching local package or build with `dotnet build -p:UrSdkVersion=VERSION` to select a published release containing peer/subprotocol APIs. Run from `csharp/messages`:

```sh
dotnet build
dotnet run --no-build -- --self-test
dotnet run --no-build -- --version
```

`--self-test` checks the golden vectors and malformed-frame rejection without client credentials or live networking. `--version` reports the SDK version; bindings with native runtime assets also check that they load. Dependency/build steps may download packages.

For the local development package, restore from its artifact directory before building:

```sh
dotnet restore --source /absolute/path/to/sdk/csharp/dist/artifacts --source https://api.nuget.org/v3/index.json
```

## Live two-terminal demo

Provision two distinct top-level clients in the same network through your authenticated backend. Terminal A and terminal B need different scoped JWTs with different `client_id` claims and different persisted instance UUIDs. Only the backend holds the root JWT; [Integration](../integration/README.md) includes the executable allocator.

In terminal A, from this directory, export client A's values and start the receiver:

```sh
export URNETWORK_CLIENT_JWT='scoped-client-jwt-from-your-service'
export URNETWORK_INSTANCE_ID='persisted-installation-uuid'
dotnet run --no-build -- self
dotnet run --no-build -- peers
dotnet run --no-build -- watch
```

`self` prints this client's ID and exits. `peers` prints the current peer snapshot; `watch` remains running, refreshes on SDK peer notifications, and receives TEXT/returns ACK. Peer output includes SDK metadata, disconnected count and the SDK-derived client color. Start terminal B with its own `URNETWORK_CLIENT_JWT` and `URNETWORK_INSTANCE_ID`, then use A's discovered client ID:

```sh
export URNETWORK_PEER_CLIENT_ID='client-id-discovered-in-terminal-a'
dotnet run --no-build -- send $URNETWORK_PEER_CLIENT_ID hi
```

The sender queries the selected peer's supported subprotocols before sending and requires `4096`. A failed query, enqueue failure or ACK timeout is reported as a failure. A matching application ACK means the receiver parsed and accepted the TEXT; correlation uses **(source client ID, message ID)**. Use the discovered client ID as the destination, not an instance UUID or display name. For text containing spaces, keep the message in one quoted argument (or inside the quoted Maven/Gradle argument string).

Incoming bytes and retained source identity are copied before the native callback returns; queued work parses frames and sends ACKs outside that callback. Keep subscriptions and Device owners alive through shutdown. Some FFI bridges keep a small callback root until process exit because native close can race a late callback. The [protocol](../../MESSAGES_PROTOCOL.md) defines exact validation, UTF-8 limits, golden bytes and timeout semantics.

## Custom protobuf messages

Your own protocol runs on its own subprotocol ID beside URMS; [Subprotocols](../../SUBPROTOCOLS.md) covers choosing the ID, the peer query, receive lifetime, versioning and acknowledgements. The `Device` wrapper has no subprotocol methods, so C# uses the C ABI in `Raw`, as [Program.cs](Program.cs) does: `Raw.urnet_device_local_enable_subprotocol`, `Raw.urnet_device_local_query_subprotocols`, `Raw.urnet_device_local_send_subprotocol_bytes`, `Raw.urnet_sub_close` with `Raw.urnet_release`, and `Raw.urnet_device_local_disable_subprotocol`. `Raw.urnet_device_local_subprotocol_stats` returns the counters as JSON (read it with `Sdk.TakeString`), and `Raw.urnet_device_local_subprotocol_received_count` one ID's count.

This sketch carries [notes.proto](../../go/messages/subprotocol/notes.proto) on subprotocol 4097 with Google.Protobuf. Add the `Google.Protobuf` package at the version that matches your `protoc` (3.36.x for protoc 36.x) and generate `Notes.cs` with `protoc -I ../../go/messages/subprotocol --csharp_out=. notes.proto`:

```csharp
using System.Runtime.InteropServices;
using System.Text;
using Google.Protobuf;
using Notes.V1;
using URnetwork.SDK;

static class NotesProtocol {
  public const int Subprotocol = 4097;
  public const int MaxMessageBytes = 4608;

  public static void Send(ulong device, string destination, Envelope envelope) {
    byte[] bytes = envelope.ToByteArray();
    if (bytes.Length > MaxMessageBytes)
      throw new IOException("envelope too large");
    IntPtr p = Marshal.AllocHGlobal(bytes.Length);
    try {
      Marshal.Copy(bytes, 0, p, bytes.Length);
      if (Raw.urnet_device_local_send_subprotocol_bytes(device, Subprotocol, destination, p, bytes.Length) == 0)
        throw new IOException("SDK did not enqueue message");
    } finally {
      Marshal.FreeHGlobal(p);
    }
  }

  // On the worker, with source and bytes copied in the listener.
  public static void Receive(ulong device, string source, byte[] bytes) {
    Envelope envelope;
    try {
      envelope = Envelope.Parser.ParseFrom(bytes);
    } catch (InvalidProtocolBufferException) {
      return; // malformed: drop and count, never acknowledge
    }
    switch (envelope.KindCase) {
      case Envelope.KindOneofCase.Note
          when envelope.Note.MessageId != 0 && Encoding.UTF8.GetByteCount(envelope.Note.Text) <= 4096:
        Send(device, source, new Envelope { Ack = new Ack { MessageId = envelope.Note.MessageId } });
        break;
      case Envelope.KindOneofCase.Ack:
        // match an outstanding note on (source, envelope.Ack.MessageId)
        break;
      default: // None, an invalid note, or a kind from a later version
        break;
    }
  }
}
```

The listener copies before it returns and leaves parsing to the worker; keep the delegate in a callback root, as [Program.cs](Program.cs) does:

```csharp
Raw.urnet_subprotocol_cb onNote = (_, protocol, source, pointer, length) => {
  if (protocol != NotesProtocol.Subprotocol || source == null || length <= 0 || length > NotesProtocol.MaxMessageBytes)
    return;
  byte[] copy = new byte[length];
  Marshal.Copy(pointer, copy, 0, length);
  inbox.TryAdd((source, copy)); // a bounded BlockingCollection; never block
};
ulong sub = Raw.urnet_device_local_enable_subprotocol(h, NotesProtocol.Subprotocol, onNote, IntPtr.Zero, out var error);
string? message = Sdk.TakeString(error);
if (message != null || sub == 0)
  throw new IOException(message ?? "subprotocol registration failed");
NotesProtocol.Send(h, destination, new Envelope { Note = new Note { MessageId = messageId, Text = text } });
```
