# URnetwork language examples

Start with **Integration** to provision a scoped client identity and create a Device. **Sockets** routes TCP, UDP and HTTP clients through that Device. **Messages** discovers live peers and exchanges the same text/ACK protocol across languages.

| Language and installation | Integration | Sockets | Messages |
| --- | --- | --- | --- |
| [C](c/README.md) | [Integration](c/integration/README.md) | [Sockets](c/socket/README.md) | [Messages](c/messages/README.md) |
| [C++](cpp/README.md) | [Integration](cpp/integration/README.md) | [Sockets](cpp/socket/README.md) | [Messages](cpp/messages/README.md) |
| [C#](csharp/README.md) | [Integration](csharp/integration/README.md) | [Sockets](csharp/socket/README.md) | [Messages](csharp/messages/README.md) |
| [Go](go/README.md) | [Integration](go/integration/README.md) | [Sockets](go/socket/README.md) | [Messages](go/messages/README.md) |
| [Java](java/README.md) | [Integration](java/integration/README.md) | [Sockets](java/socket/README.md) | [Messages](java/messages/README.md) |
| [JavaScript](javascript/README.md) | [Integration](javascript/integration/README.md) | [Sockets](javascript/socket/README.md) | [Messages: native companion](javascript/messages/README.md) |
| [Kotlin](kotlin/README.md) | [Integration](kotlin/integration/README.md) | [Sockets](kotlin/socket/README.md) | [Messages](kotlin/messages/README.md) |
| [Python](python/README.md) | [Integration](python/integration/README.md) | [Sockets](python/socket/README.md) | [Messages](python/messages/README.md) |
| [Ruby](ruby/README.md) | [Integration](ruby/integration/README.md) | [Sockets](ruby/socket/README.md) | [Messages](ruby/messages/README.md) |
| [Rust](rust/README.md) | [Integration](rust/integration/README.md) | [Sockets](rust/socket/README.md) | [Messages](rust/messages/README.md) |
| [Swift](swift/README.md) | [Integration](swift/integration/README.md) | [Sockets](swift/socket/README.md) | [Messages](swift/messages/README.md) |
| [TypeScript](typescript/README.md) | [Integration](typescript/integration/README.md) | [Sockets](typescript/socket/README.md) | [Messages: native companion](typescript/messages/README.md) |

The [integration contract](INTEGRATION_CONTRACT.md) defines the service backend and client credential boundary. Apps receive `URNETWORK_CLIENT_JWT`; only the backend holds `URNETWORK_ROOT_JWT`. A client JWT identifies a client, while `URNETWORK_INSTANCE_ID` identifies an installation. Two messaging terminals need two distinct client identities and two persisted instance IDs.

Every Integration guide includes a runnable allocator in `integration/server`, with exact build, credential-free self-test and backend run commands. The authenticated backend supplies a `user:<service-user-id>` key; the allocator owns the private user-to-client mapping and returns the scoped credential. The client helpers and Messages guides include their matching build and live commands.

The [networking research matrix](NETWORK_EXAMPLES.md) maps official HTTP and raw networking examples to each socket adapter and explains where a loopback proxy is required. The [URMS v1 protocol](MESSAGES_PROTOCOL.md) defines exact wire bytes, discovery, callback ownership and ACK semantics.

JavaScript and TypeScript messaging runs on Node 24 through a [native companion](javascript/integration/companion/README.md). The companion owns a provider-capable `DeviceLocal`; the JS/TS application owns the codec, live peer display, targeted sends and ACK handling through typed extension RPC. Build the current SDK from sibling checkouts for this new capability. Their socket examples continue to use hosted `DeviceRemote`; hosted proxy devices remain excluded from visible peers and reject subprotocol messaging.

Some package coordinates are awaiting their first publication. Each installation guide explains the package target and local build path. Use a matching SDK release with the required socket, peer and subprotocol APIs; installation of an older release does not add them. Live checks require provisioned clients and a working URnetwork connection; codec self-tests need no credentials.
