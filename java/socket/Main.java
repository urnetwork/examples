import io.ur.sdk.Sdk;
import okhttp3.*;
import retrofit2.Retrofit;
import retrofit2.http.GET;
import retrofit2.http.Url;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

public final class Main {
    interface Endpoint { @GET retrofit2.Call<ResponseBody> get(@Url String url); }
    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("--version")) {System.out.println(Sdk.version()); return;}
        if (args.length > 0 && args[0].equals("--new-id")) {System.out.println(UUID.randomUUID()); return;}
        String mode = args.length > 0 ? args[0] : "okhttp";
        String target = args.length > 1 ? args[1] : "https://example.com/";
        try (UrSession session = new UrSession()) {
            if (mode.equals("udp") || mode.equals("dtls")) {
                try (Sdk.Conn conn = mode.equals("udp") ? session.device.dial("udp", target) : session.device.dialTls("udp", target, 30000, "{}")) {
                    conn.setDeadline(System.currentTimeMillis() + 10000);
                    conn.write("hello".getBytes(StandardCharsets.UTF_8));
                    Sdk.ReadResult reply = conn.read(65535);
                    System.out.println(new String(reply.data(), StandardCharsets.UTF_8) + "; EOF=" + reply.eof());
                }
                return;
            }
            if (mode.equals("apache")) {
                ApacheExample.get(session.device, target);
                return;
            }
            // Use unresolved names all the way to UR; OkHttp's native DNS must not run.
            OkHttpClient client = new OkHttpClient.Builder()
                .socketFactory(new UrSocketFactory(session.device))
                .dns(host -> Collections.singletonList(InetAddress.getByAddress(host, new byte[4])))
                .proxy(Proxy.NO_PROXY).callTimeout(30, TimeUnit.SECONDS).build();
            try {
                if (mode.equals("retrofit")) {
                    Endpoint endpoint = new Retrofit.Builder().baseUrl("https://example.com/").client(client).build().create(Endpoint.class);
                    retrofit2.Response<ResponseBody> response = endpoint.get(target).execute();
                    try (ResponseBody body = response.isSuccessful() ? response.body() : response.errorBody()) {
                        System.out.println(response.code()); if (body != null) System.out.println(body.string());
                    }
                } else {
                    try (Response response = client.newCall(new Request.Builder().url(target).build()).execute()) {
                        System.out.println(response.code()); if (response.body() != null) System.out.println(response.body().string());
                    }
                }
            } finally {client.connectionPool().evictAll(); client.dispatcher().executorService().shutdown();}
        }
    }
}
