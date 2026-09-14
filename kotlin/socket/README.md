# Kotlin sockets and Ktor

[src/main/kotlin/Main.kt](src/main/kotlin/Main.kt) is a runnable desktop Kotlin/JVM program using the Java SDK. Its local Device and SocketFactory helpers are included under `src/main/java`.

## Install and run

Use JDK 21+, Gradle 8.13+, and the published `io.ur:urnetwork-sdk` version:

```sh
gradle -PsdkVersion=<socket-release-version> run --args=--version
gradle -PsdkVersion=<socket-release-version> run --args=--new-id
gradle -PsdkVersion=<socket-release-version> run --args='https://example.com/'
gradle -PsdkVersion=<socket-release-version> run --args='udp echo.example:9000'
```

Before first publication, install the JAR/POM built by `sdk/java` into Maven Local as described in the [Java guide](../../java/socket/README.md). This Gradle project checks Maven Local before Central. Set the Device variables below for live requests. The default local package version is `0.0.1-dev.0`.

## Replace HTTP connection creation

| Library / engine | Integration |
| --- | --- |
| Ktor 3.3.0 with OkHttp engine | `engine { config { socketFactory(...); dns(...); proxy(Proxy.NO_PROXY) } }`. |
| OkHttp directly | Use the included `UrSocketFactory` as in the Java example. |
| Retrofit | Supply an OkHttp client configured with that same factory. |
| Ktor CIO / Darwin | These are separate engines; an OkHttp configuration does not change them. Use the supported engine or a documented HTTP proxy. |

The DNS placeholder preserves the original hostname for the SDK. TLS is JSSE/OkHttp TLS above a UR TCP stream, with normal certificate verification. The example intentionally chooses the engine whose socket creation can be replaced. Native Kotlin/Multiplatform targets do not use this JVM/JNA artifact. Android Kotlin uses the gomobile AAR and portable Socket methods; platform-specific UI/lifecycle setup is separate.

Sources checked September 14, 2026: [Ktor engines](https://ktor.io/docs/http-client-engines.html), [Ktor OkHttpConfig](https://api.ktor.io/ktor-client-okhttp/io.ktor.client.engine.okhttp/-ok-http-config/index.html), [OkHttp socket factory](https://square.github.io/okhttp/5.x/okhttp/okhttp3/-ok-http-client/-builder/socket-factory.html), [Retrofit](https://github.com/square/retrofit).

## Device setup

The executable creates a local Device using the SDK's existing network-space APIs, applies your JWT, and chooses the best available location. Set an account JWT as described in the [SDK setup guide](https://ur.io/docs/getting-started-sdk). Generate an instance ID once with this program's `--new-id` mode, save it, and reuse it for this installation.

```sh
export URNETWORK_JWT='your-account-jwt'
export URNETWORK_INSTANCE_ID='your-persisted-instance-uuid'
```

Keep the network-space manager alive until the Device closes. The sample owns and releases these objects in that order. HTTP proxy modes use the Go proxy's Device instead.

## Socket behavior

The destination connection lives in the SDK's user-space network stack and sends packets through the Device's URnetwork connection. It is not a kernel socket: its handle cannot be passed to `select`, `poll`, an OS socket option, or a library that requires a native file descriptor. Creating these application sockets does not require installing a system VPN or opening a kernel TUN device.

Pass the hostname unchanged with `tcp` or `udp`. The SDK resolves A and AAAA records through its connection and applies Happy Eyeballs; use `tcp4`/`tcp6` or `udp4`/`udp6` to force a family. Plain UDP races the **initial application datagram** and selects the address that sends the first reply. **That initial datagram can be delivered to both addresses.** Subsequent writes use the winner; a silent server cannot select a winner, so set a read/write deadline. Use an echo server you control for UDP examples. An empty datagram is data, not stream EOF.

TLS verifies the certificate and destination hostname. TLS-over-TCP and DTLS-over-UDP use the same Device packet path. DTLS needs a DTLS server, not a plain UDP echo server. Deadlines are absolute Unix milliseconds in portable bindings; zero clears them. Close each connection and then its Device. Listener/server sockets are a future API.

These are application integration examples. The core SDK suite separately tests real TCP/UDP packets, IPv4/IPv6, hostname races, TLS/DTLS, deadlines, close, and local/RPC transport behavior. A version/self-test command checks installation without using account credentials; live examples require a working account and provider path.
