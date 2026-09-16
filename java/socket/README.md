# Java sockets, OkHttp, Retrofit and Apache HttpClient

[Integration](../integration/README.md) · [Sockets](README.md) · [Messages](../messages/README.md) · [Official networking research](../../NETWORK_EXAMPLES.md)

[Main.java](Main.java) runs the examples; [UrSession.java](../integration/UrSession.java) owns a local Device and [UrSocketFactory.java](UrSocketFactory.java) implements the Java Socket/SocketFactory operations these HTTP clients use.

## Install and run

Use JDK 17+ and Maven. The native desktop package is `io.ur:urnetwork-sdk`. Set `-Durnetwork.sdk.version=<socket-release-version>` to the published version. Before first publication, build `sdk/java` and install the generated JAR/POM into Maven Local:

```sh
mvn install:install-file -Dfile=/path/to/urnetwork-sdk-<version>.jar -DpomFile=/path/to/urnetwork-sdk-<version>.pom
mvn -Durnetwork.sdk.version=<version> compile
mvn -Durnetwork.sdk.version=<version> exec:java -Dexec.args=--version
mvn -Durnetwork.sdk.version=<version> exec:java -Dexec.args=--new-id
mvn -Durnetwork.sdk.version=<version> exec:java -Dexec.args='okhttp https://example.com/'
mvn -Durnetwork.sdk.version=<version> exec:java -Dexec.args='retrofit https://example.com/'
mvn -Durnetwork.sdk.version=<version> exec:java -Dexec.args='apache https://example.com/'
mvn -Durnetwork.sdk.version=<version> exec:java -Dexec.args='udp echo.example:9000'
mvn -Durnetwork.sdk.version=<version> exec:java -Dexec.args='dtls dtls.example:9001'
```

Set the Device environment variables below for live requests. This is a **desktop JVM/JNA** example. Android uses `io.ur:urnetwork-sdk-android` and the gomobile `openSocket` API; do not load desktop JNA libraries into an Android app.

## Replace the HTTP socket factory

| Library | Hook used by executable |
| --- | --- |
| OkHttp 5.3.2 | `OkHttpClient.Builder.socketFactory(new UrSocketFactory(device))`. |
| Retrofit 3.0.0 | Supply that OkHttp client with `Retrofit.Builder.client(client)`. |
| Apache HttpClient 5.5.1 classic | Register the plain and layered TLS connection factories in [ApacheExample.java](ApacheExample.java). |
| JDK HttpClient | No general raw SocketFactory hook; use the Go CONNECT proxy if this client must be retained. |

The custom DNS component supplies a hostname-bearing placeholder without consulting system DNS. Only the UR factory consumes it, passing the original name to `device.dial("tcp", ...)`. JSSE / the HTTP library then performs normal verified TLS over that socket. The placeholder is not an address to connect to.

Apache's registry constructor is deprecated in newer HttpClient versions; the runnable example pins 5.5.1 and uses its supported classic API. Upgrading to a newer connection-operator API requires porting and retesting the factory. The Socket facade implements streams, timeouts, close and half-close; it cannot expose a kernel descriptor, `SocketChannel`, OS buffer sizing, or kernel keepalive tuning.

Sources checked September 14, 2026: [OkHttp socketFactory](https://square.github.io/okhttp/5.x/okhttp/okhttp3/-ok-http-client/-builder/socket-factory.html), [Retrofit](https://github.com/square/retrofit), [Apache connection manager](https://hc.apache.org/httpcomponents-client-5.5.x/current/httpclient5/apidocs/org/apache/hc/client5/http/impl/io/PoolingHttpClientConnectionManager.html), [JDK HttpClient builder](https://docs.oracle.com/en/java/javase/21/docs/api/java.net.http/java/net/http/HttpClient.Builder.html).

## Device setup

The executable creates a local Device with the scoped client JWT issued by your service backend and chooses the best available location. Follow the [integration guide](../integration/README.md): the JWT must contain its assigned `client_id`; only the backend holds the root JWT. Generate an instance ID once with this program's `--new-id` mode, save it, and reuse it for this installation.

```sh
export URNETWORK_CLIENT_JWT='your-scoped-client-jwt'
export URNETWORK_INSTANCE_ID='your-persisted-instance-uuid'
```

Keep the network-space manager alive until the Device closes. The sample owns and releases these objects in that order. HTTP proxy modes use the Go proxy's Device instead.

## Socket behavior

The destination connection lives in the SDK's user-space network stack and sends packets through the Device's URnetwork connection. It is not a kernel socket: its handle cannot be passed to `select`, `poll`, an OS socket option, or a library that requires a native file descriptor. Creating these application sockets does not require installing a system VPN or opening a kernel TUN device.

Pass the hostname unchanged with `tcp` or `udp`. The SDK resolves A and AAAA records through its connection and applies Happy Eyeballs; use `tcp4`/`tcp6` or `udp4`/`udp6` to force a family. Plain UDP races the **initial application datagram** and selects the address that sends the first reply. **That initial datagram can be delivered to both addresses.** Subsequent writes use the winner; a silent server cannot select a winner, so set a read/write deadline. Use an echo server you control for UDP examples. An empty datagram is data, not stream EOF.

TLS verifies the certificate and destination hostname. TLS-over-TCP and DTLS-over-UDP use the same Device packet path. DTLS needs a DTLS server, not a plain UDP echo server. Deadlines are absolute Unix milliseconds in portable bindings; zero clears them. Close each connection and then its Device. Listener/server sockets are a future API.

These are application integration examples. The core SDK suite separately tests real TCP/UDP packets, IPv4/IPv6, hostname races, TLS/DTLS, deadlines, close, and local/RPC transport behavior. A version/self-test command checks installation without using account credentials; live examples require a working account and provider path.
