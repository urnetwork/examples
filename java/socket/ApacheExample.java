import io.ur.sdk.Sdk;
import java.net.*;
import java.io.*;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.socket.*;
import org.apache.hc.client5.http.ssl.SSLConnectionSocketFactory;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.config.RegistryBuilder;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.pool.*;
import org.apache.hc.core5.ssl.SSLContexts;
import org.apache.hc.core5.util.TimeValue;

/** Pinned Apache 5.5 example. The socket-factory registry API is deprecated. */
@SuppressWarnings("deprecation")
public final class ApacheExample {
    public static void get(Sdk.Device device, String url) throws Exception {
        UrSocketFactory factory = new UrSocketFactory(device);
        ConnectionSocketFactory plain = new ConnectionSocketFactory() {
            public Socket createSocket(HttpContext context) {return factory.createSocket();}
            public Socket connectSocket(TimeValue timeout, Socket socket, HttpHost host, InetSocketAddress remote, InetSocketAddress local, HttpContext context) throws IOException {
                if (local != null) throw new IOException("Local binding is unsupported");
                if (socket == null) socket = createSocket(context);
                socket.connect(InetSocketAddress.createUnresolved(host.getHostName(), remote.getPort()), timeout == null ? 30000 : (int)Math.min(Integer.MAX_VALUE, timeout.toMilliseconds()));
                return socket;
            }
        };
        SSLConnectionSocketFactory secure = new SSLConnectionSocketFactory(SSLContexts.createSystemDefault()) {
            @Override public Socket createSocket(HttpContext context) {return factory.createSocket();}
        };
        DnsResolver dns = new DnsResolver() {
            public InetAddress[] resolve(String host) throws UnknownHostException {return new InetAddress[]{InetAddress.getByAddress(host, new byte[4])};}
            public String resolveCanonicalHostname(String host) {return host;}
        };
        var registry = RegistryBuilder.<ConnectionSocketFactory>create().register("http", plain).register("https", secure).build();
        try (var manager = new PoolingHttpClientConnectionManager(registry, PoolConcurrencyPolicy.STRICT, PoolReusePolicy.LIFO, TimeValue.ofMinutes(1), null, dns, null);
             var client = HttpClients.custom().setConnectionManager(manager).build()) {
            client.execute(new HttpGet(url), response -> {System.out.println(response.getCode()); System.out.println(EntityUtils.toString(response.getEntity())); return null;});
        }
    }
}
