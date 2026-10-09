# Rust peer messages

[Installation](../README.md) · [Integration](../integration/README.md) · [Sockets](../socket/README.md) · [Messages](README.md) · [Provider](../provider/README.md) · [Embed](../embed/README.md)

This local Device example discovers real-time peers and exchanges text over subprotocol **4096** using the shared [URMS v1 TEXT/ACK format](../../MESSAGES_PROTOCOL.md). Its files are [main.rs](src/main.rs), [codec.rs](src/codec.rs) and [Cargo.toml](Cargo.toml); the Device bootstrap is described in [Integration](../integration/README.md).

## Build and check

Use a Rust toolchain supporting edition 2024. The crate defaults to SDK `0.0.1-dev.0`; select an API-capable release in Cargo.toml or use the local development patch below. Run from `rust/messages`:

```sh
cargo build
cargo run -- --self-test
cargo run -- --version
```

`--self-test` checks the golden vectors and malformed-frame rejection without client credentials or live networking. `--version` reports the SDK version; bindings with native runtime assets also check that they load. Dependency/build steps may download packages.

To use the prepared local SDK crate before publication, apply the same Cargo configuration to build and run commands:

```sh
cargo run --config 'patch.crates-io.urnetwork-sdk.path="/absolute/path/to/sdk/rust/dist/project"' -- --self-test
```

## Live two-terminal demo

Provision two distinct top-level clients in the same network through your authenticated backend. Terminal A and terminal B need different scoped JWTs with different `client_id` claims and different persisted instance UUIDs. Only the backend holds the root JWT; [Integration](../integration/README.md) includes the executable allocator.

In terminal A, from this directory, export client A's values and start the receiver:

```sh
export URNETWORK_CLIENT_JWT='scoped-client-jwt-from-your-service'
export URNETWORK_INSTANCE_ID='persisted-installation-uuid'
cargo run -- self
cargo run -- peers
cargo run -- watch
```

`self` prints this client's ID and exits. `peers` prints the current peer snapshot; `watch` remains running, refreshes on SDK peer notifications, and receives TEXT/returns ACK. Peer output includes SDK metadata, disconnected count and the SDK-derived client color. Start terminal B with its own `URNETWORK_CLIENT_JWT` and `URNETWORK_INSTANCE_ID`, then use A's discovered client ID:

```sh
export URNETWORK_PEER_CLIENT_ID='client-id-discovered-in-terminal-a'
cargo run -- send $URNETWORK_PEER_CLIENT_ID hi
```

The sender queries the selected peer's supported subprotocols before sending and requires `4096`. A failed query, enqueue failure or ACK timeout is reported as a failure. A matching application ACK means the receiver parsed and accepted the TEXT; correlation uses **(source client ID, message ID)**. Use the discovered client ID as the destination, not an instance UUID or display name. For text containing spaces, keep the message in one quoted argument (or inside the quoted Maven/Gradle argument string).

Incoming bytes and retained source identity are copied before the native callback returns; queued work parses frames and sends ACKs outside that callback. Keep subscriptions and Device owners alive through shutdown. Some FFI bridges keep a small callback root until process exit because native close can race a late callback. The [protocol](../../MESSAGES_PROTOCOL.md) defines exact validation, UTF-8 limits, golden bytes and timeout semantics.

## Custom protobuf messages

Your own protocol runs on its own subprotocol ID beside URMS; [Subprotocols](../../SUBPROTOCOLS.md) covers choosing the ID, the peer query, receive lifetime, versioning and acknowledgements. The `Device` wrapper has no subprotocol methods, so Rust calls the C ABI through `urnetwork_sdk::native()`, as [main.rs](src/main.rs) does: `urnet_device_local_enable_subprotocol`, `urnet_device_local_query_subprotocols`, `urnet_device_local_send_subprotocol_bytes`, `urnet_sub_close` with `urnet_release`, and `urnet_device_local_disable_subprotocol`. `urnet_device_local_subprotocol_stats` returns the counters as JSON (read it with `take_string`), and `urnet_device_local_subprotocol_received_count` one ID's count.

This sketch carries [notes.proto](../../go/messages/subprotocol/notes.proto) on subprotocol 4097 with prost. Add `prost` to the dependencies and `prost-build` at the same version to the build dependencies, and compile the schema in `build.rs`. prost-build runs `protoc` and does not tell Cargo about the schema, so the script names it for rebuilds:

```rust
// build.rs
fn main() -> std::io::Result<()> {
    println!("cargo:rerun-if-changed=../../go/messages/subprotocol/notes.proto");
    prost_build::compile_protos(
        &["../../go/messages/subprotocol/notes.proto"],
        &["../../go/messages/subprotocol"],
    )
}
```

```rust
mod notes {
    include!(concat!(env!("OUT_DIR"), "/notes.v1.rs"));
}
use notes::{Ack, Envelope, Note, envelope::Kind};
use prost::Message;
use std::ffi::CString;
use urnetwork_sdk::native;

const NOTES: i64 = 4097;
const MAX_MESSAGE_BYTES: usize = 4608;

fn send_envelope(
    handle: u64,
    destination: &str,
    envelope: &Envelope,
) -> Result<(), Box<dyn std::error::Error>> {
    let bytes = envelope.encode_to_vec();
    if bytes.len() > MAX_MESSAGE_BYTES {
        return Err("envelope too large".into());
    }
    let destination = CString::new(destination)?;
    let sent = unsafe {
        (native()?.urnet_device_local_send_subprotocol_bytes)(
            handle,
            NOTES,
            destination.as_ptr(),
            bytes.as_ptr(),
            bytes.len() as i32,
        )
    };
    if !sent {
        return Err("SDK did not enqueue message".into());
    }
    Ok(())
}

// On the worker. The urnet_subprotocol_cb refused more than MAX_MESSAGE_BYTES
// and copied the bytes with slice::from_raw_parts(..).to_vec(), as main.rs does.
fn receive_envelope(
    handle: u64,
    source: &str,
    bytes: &[u8],
) -> Result<(), Box<dyn std::error::Error>> {
    let Ok(envelope) = Envelope::decode(bytes) else {
        return Ok(()); // malformed: drop and count, never acknowledge
    };
    match envelope.kind {
        Some(Kind::Note(note)) if note.message_id != 0 && note.text.len() <= 4096 => {
            let reply = Envelope {
                kind: Some(Kind::Ack(Ack {
                    message_id: note.message_id,
                })),
            };
            send_envelope(handle, source, &reply)?;
        }
        Some(Kind::Ack(_ack)) => {} // match an outstanding note on (source, _ack.message_id)
        _ => {}                     // None, an invalid note, or a kind from a later version
    }
    Ok(())
}

fn send_note(
    handle: u64,
    destination: &str,
    message_id: u64,
    text: String,
) -> Result<(), Box<dyn std::error::Error>> {
    send_envelope(
        handle,
        destination,
        &Envelope {
            kind: Some(Kind::Note(Note { message_id, text })),
        },
    )
}
```

Register the listener with `urnet_device_local_enable_subprotocol(h, NOTES, Some(on_note), context, &mut error)`, where `on_note` is an `unsafe extern "C" fn` like `message` in [main.rs](src/main.rs) and `context` outlives the Device. prost rejects a `string` field that is not UTF-8, so `decode` fails for it. A prost struct literal lists every field, so a field added to `notes.proto` means updating the literals.
