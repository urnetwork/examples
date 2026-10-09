# Ruby peer messages

[Installation](../README.md) · [Integration](../integration/README.md) · [Sockets](../socket/README.md) · [Messages](README.md) · [Provider](../provider/README.md) · [Embed](../embed/README.md)

This local Device example discovers real-time peers and exchanges text over subprotocol **4096** using the shared [URMS v1 TEXT/ACK format](../../MESSAGES_PROTOCOL.md). Its files are [main.rb](main.rb), [codec.rb](codec.rb) and [Gemfile](Gemfile); the Device bootstrap is described in [Integration](../integration/README.md).

## Build and check

Use Ruby and Bundler with a peer/subprotocol-capable SDK gem. Install the matching locally built platform gem before `bundle install` if it is unpublished. The codec self-test runs before Bundler/SDK loading and needs no SDK installation. There is no compilation step. Run from `ruby/messages`:

```sh
ruby main.rb --self-test
bundle install
bundle exec ruby main.rb --version
```

`--self-test` checks the golden vectors and malformed-frame rejection without client credentials or live networking. `--version` reports the SDK version; bindings with native runtime assets also check that they load. Dependency/build steps may download packages.

## Live two-terminal demo

Provision two distinct top-level clients in the same network through your authenticated backend. Terminal A and terminal B need different scoped JWTs with different `client_id` claims and different persisted instance UUIDs. Only the backend holds the root JWT; [Integration](../integration/README.md) includes the executable allocator.

In terminal A, from this directory, export client A's values and start the receiver:

```sh
export URNETWORK_CLIENT_JWT='scoped-client-jwt-from-your-service'
export URNETWORK_INSTANCE_ID='persisted-installation-uuid'
bundle exec ruby main.rb self
bundle exec ruby main.rb peers
bundle exec ruby main.rb watch
```

`self` prints this client's ID and exits. `peers` prints the current peer snapshot; `watch` remains running, refreshes on SDK peer notifications, and receives TEXT/returns ACK. Peer output includes SDK metadata, disconnected count and the SDK-derived client color. Start terminal B with its own `URNETWORK_CLIENT_JWT` and `URNETWORK_INSTANCE_ID`, then use A's discovered client ID:

```sh
export URNETWORK_PEER_CLIENT_ID='client-id-discovered-in-terminal-a'
bundle exec ruby main.rb send $URNETWORK_PEER_CLIENT_ID hi
```

The sender queries the selected peer's supported subprotocols before sending and requires `4096`. A failed query, enqueue failure or ACK timeout is reported as a failure. A matching application ACK means the receiver parsed and accepted the TEXT; correlation uses **(source client ID, message ID)**. Use the discovered client ID as the destination, not an instance UUID or display name. For text containing spaces, keep the message in one quoted argument (or inside the quoted Maven/Gradle argument string).

Incoming bytes and retained source identity are copied before the native callback returns; queued work parses frames and sends ACKs outside that callback. Keep subscriptions and Device owners alive through shutdown. Some FFI bridges keep a small callback root until process exit because native close can race a late callback. The [protocol](../../MESSAGES_PROTOCOL.md) defines exact validation, UTF-8 limits, golden bytes and timeout semantics.

## Custom protobuf messages

Your own protocol runs on its own subprotocol ID beside URMS; [Subprotocols](../../SUBPROTOCOLS.md) covers choosing the ID, the peer query, receive lifetime, versioning and acknowledgements. The `URnetwork::Device` wrapper has no subprotocol methods, so Ruby uses the C ABI in `URnetwork::Raw`, as [main.rb](main.rb) does: `urnet_device_local_enable_subprotocol`, `urnet_device_local_query_subprotocols`, `urnet_device_local_send_subprotocol_bytes`, `urnet_sub_close` with `urnet_release`, and `urnet_device_local_disable_subprotocol`. `urnet_device_local_subprotocol_stats` returns the counters as JSON (read it with `URnetwork.take_string`), and `urnet_device_local_subprotocol_received_count` one ID's count.

This sketch carries [notes.proto](../../go/messages/subprotocol/notes.proto) on subprotocol 4097 with the `google-protobuf` gem. Add it to the Gemfile and generate `notes_pb.rb` with `protoc -I ../../go/messages/subprotocol --ruby_out=. notes.proto`:

```ruby
require "google/protobuf"
require_relative "notes_pb"

NOTES = 4097
MAX_MESSAGE_BYTES = 4608

def send_envelope(raw, handle, destination, envelope)
  bytes = Notes::V1::Envelope.encode(envelope)
  raise "envelope too large" if bytes.bytesize > MAX_MESSAGE_BYTES
  memory = FFI::MemoryPointer.new(:uint8, bytes.bytesize)
  memory.put_bytes(0, bytes)
  raise "SDK did not enqueue message" unless raw.urnet_device_local_send_subprotocol_bytes(handle, NOTES, destination, memory, bytes.bytesize)
end

# On the worker, with source and bytes copied in the listener.
def receive_envelope(raw, handle, source, bytes)
  envelope = begin
    Notes::V1::Envelope.decode(bytes)
  rescue Google::Protobuf::ParseError
    return # malformed: drop and count, never acknowledge
  end
  case envelope.kind
  when :note
    note = envelope.note
    if note.message_id != 0 && note.text.bytesize <= 4096
      send_envelope(raw, handle, source, Notes::V1::Envelope.new(ack: Notes::V1::Ack.new(message_id: note.message_id)))
    end
  when :ack
    # match an outstanding note on (source, envelope.ack.message_id)
  end # nil: no kind this version knows
end

# Copies before it returns; keep it in CALLBACK_ROOTS like main.rb.
on_note = proc do |_, protocol, source, pointer, length|
  put.call([:note, source.dup, pointer.get_bytes(0, length)]) if protocol == NOTES && source && length.between?(1, MAX_MESSAGE_BYTES)
end

send_envelope(raw, handle, destination, Notes::V1::Envelope.new(note: Notes::V1::Note.new(message_id: message_id, text: text)))
```

Register `on_note` with `raw.urnet_device_local_enable_subprotocol(handle, NOTES, on_note, nil, error)` and check the error as `main.rb` does; `put` is the bounded queue from `main.rb`, which drops when full.
