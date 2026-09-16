import com.sun.jna.Callback;
import com.sun.jna.Memory;
import com.sun.jna.ptr.PointerByReference;
import io.ur.sdk.Raw;
import io.ur.sdk.Sdk;
import java.lang.ref.Reference;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** JVM callback bridge shared by the Java and Kotlin ports. */
public final class UrMessages {
  // One bounded set per CLI process: Close cancels workers asynchronously, so
  // late query/receive callbacks must still have live JNA trampolines.
  private static final List<Callback> CALLBACK_ROOTS = new ArrayList<>();
  private record
      Event(String kind, String source, byte[] bytes, String json, boolean ok) {
  }
  private static final Pattern CLIENT =
      Pattern.compile("\"ClientId\"\\s*:\\s*\"([0-9a-fA-F-]{36})\"");
  private static void peers(String json) {
    if (json == null || json.equals("null")) {
      System.out.println("peers unavailable (no snapshot)");
      return;
    }
    System.out.println(
        json); // Preserve every SDK metadata field and DisconnectedCount.
    var matcher = CLIENT.matcher(json);
    while (matcher.find()) {
      String id = matcher.group(1);
      System.out.printf("color %s %s%n", id,
                        Sdk.takeString(Sdk.raw.urnet_get_color_hex(id)));
    }
  }
  private static void send(long h, String destination, byte[] bytes)
      throws Exception {
    try (Memory memory = new Memory(bytes.length)) {
      memory.write(0, bytes, 0, bytes.length);
      if (Sdk.raw.urnet_device_local_send_subprotocol_bytes(
              h, 4096, destination, memory, bytes.length) == 0)
        throw new Exception("SDK did not enqueue message");
    }
  }
  public static void run(String[] args, MessageCodec.Codec codec)
      throws Exception {
    if (args.length == 1 && args[0].equals("--self-test")) {
      MessageCodec.selfTest(codec);
      return;
    }
    if (args.length == 1 && args[0].equals("--version")) {
      System.out.println(Sdk.version());
      return;
    }
    if (args.length == 0 ||
        !List.of("self", "peers", "watch", "send").contains(args[0]) ||
        (args[0].equals("send") && args.length < 3))
      throw new IllegalArgumentException(
          "usage: --self-test | --version | self | peers | watch | send "
          + "CLIENT_ID TEXT");
    String destination =
        args[0].equals("send") ? UUID.fromString(args[1]).toString() : null;
    var events = new ArrayBlockingQueue<Event>(256);
    List<Callback> retained = new ArrayList<>();
    Raw.urnet_subprotocol_cb onMessage =
        (u, protocol, source, pointer, length) -> {
      if (protocol == 4096 && source != null && length >= 16 && length <= 4112)
        events.offer(new Event("message", source,
                               pointer.getByteArray(0, length), null,
                               false)); // Copy immediately.
    };
    Raw.urnet_network_peers_change_cb onPeers =
        (u, json) -> events.offer(new Event("peers", null, null, json, false));
    Raw.urnet_subprotocols_query_cb onQuery =
        (u, json,
         ok) -> events.offer(new Event("query", null, null, json, ok != 0));
    retained.add(onMessage);
    retained.add(onPeers);
    retained.add(onQuery);
    CALLBACK_ROOTS.addAll(retained);
    // Callbacks only copy/enqueue. Decode, stdout and ACK sends run on this
    // thread.
    try (UrSession session = new UrSession(false)) {
      long h = session.device.handle(), sub = 0, peerSub = 0;
      try {
        var error = new PointerByReference();
        sub = Sdk.raw.urnet_device_local_enable_subprotocol(h, 4096, onMessage,
                                                            null, error);
        String message = Sdk.takeString(error.getValue());
        if (message != null || sub == 0)
          throw new Exception(
              message != null ? message : "subprotocol registration failed");
        peerSub = Sdk.raw.urnet_device_add_network_peers_change_listener(
            h, onPeers, null); // Register before snapshot.
        Sdk.raw.urnet_device_set_provide_mode(h,
                                              1); // URNET_PROVIDE_MODE_NETWORK
        System.out.println(
            "self: " + Sdk.takeString(Sdk.raw.urnet_device_get_client_id(h)));
        if (args[0].equals("self"))
          return;
        String snapshot =
            Sdk.takeString(Sdk.raw.urnet_device_get_network_peers(h));
        peers(snapshot);
        if (args[0].equals("peers") && snapshot != null &&
            !snapshot.equals("null"))
          return;
        long pending = new SecureRandom().nextLong();
        if (pending == 0)
          pending = 1;
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        boolean queried = false;
        while (!Thread.currentThread().isInterrupted()) {
          if (destination != null && !queried &&
              Sdk.raw.urnet_device_local_get_provider_connected(h) != 0) {
            queried = true;
            Sdk.raw.urnet_device_local_query_subprotocols(h, destination, 10000,
                                                          onQuery, null);
          }
          var e = events.poll(100, TimeUnit.MILLISECONDS);
          if (e != null)
            switch (e.kind()) {
            case "peers" -> {
              peers(e.json());
              if (args[0].equals("peers") && e.json() != null &&
                  !e.json().equals("null"))
                return;
            }
            case "query" -> {
              boolean supported =
                  e.ok() && e.json() != null &&
                  Arrays
                      .stream(
                          e.json().replace("[", "").replace("]", "").split(","))
                      .anyMatch(s -> s.trim().equals("4096"));
              if (!supported)
                throw new Exception(
                    "peer query failed or peer does not advertise 4096");
              send(h, destination,
                   codec.encode(new MessageCodec.Frame(
                       1, pending,
                       String.join(" ",
                                   Arrays.copyOfRange(args, 2, args.length)))));
              System.out.println("sent: " + Long.toUnsignedString(pending) +
                                 " waiting for ACK");
              deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            }
            case "message" -> {
              MessageCodec.Frame frame;
              try {
                frame = codec.decode(e.bytes());
              } catch (Exception invalid) {
                System.err.println("rejected malformed frame: " +
                                   invalid.getMessage());
                continue;
              }
              System.out.printf("kind=%d source=%s id=%s text=%s%n",
                                frame.kind(), e.source(),
                                Long.toUnsignedString(frame.id()),
                                frame.text().replaceAll("[\\p{Cntrl}]", "?"));
              if (frame.kind() == 1)
                send(h, e.source(),
                     codec.encode(new MessageCodec.Frame(2, frame.id(), "")));
              else if (e.source().equals(destination) && frame.id() == pending)
                return; // Never ACK an ACK.
            }
            }
          if (!args[0].equals("watch") && System.nanoTime() > deadline)
            throw new Exception(
                "connection, peer snapshot, query, or ACK timed out");
        }
      } finally {
        if (peerSub != 0) {
          Sdk.raw.urnet_sub_close(peerSub);
          Sdk.raw.urnet_release(peerSub);
        }
        if (sub != 0) {
          Sdk.raw.urnet_sub_close(sub);
          Sdk.raw.urnet_release(sub);
        }
        Sdk.raw.urnet_device_local_disable_subprotocol(h, 4096);
      }
    } finally {
      Reference.reachabilityFence(retained);
    } // Includes Device.close and query cancellation.
  }
}
