// The installation's data caps (EMBED_CONTRACT.md, "Backend: per-user data
// caps"). The backend sets them with the root credential; the app reads its
// own with its client JWT: GET /network/client-data-cap at the URnetwork API.
// Usage is accounted when transfer contracts settle, so the counts lag live
// traffic. The parsing here never throws, so the self-test checks it without a
// network.
import java.io.IOException;
import java.net.URI;
import java.util.Map;

/** The cap object, the readings so far and the cap read. */
final class Caps {
  static final String CAP_PATH = "/network/client-data-cap";

  /** Static members only. */
  private Caps() {}

  /**
   * One cap object, as GET /network/client-data-cap answers it. A limit is null when that cap is
   * not set. cappedReason is "monthly", "total", or "" when the client is not capped.
   */
  record DataCap(String clientId, Long monthlyByteLimit, long monthlyUsedByteCount,
                 String monthlyPeriodStart, String monthlyPeriodEnd, Long totalByteLimit,
                 long totalUsedByteCount, String totalPeriodStart, boolean capped,
                 String cappedReason) {
    /**
     * The cap object of a JSON text; null when the text is not one: not JSON, not an object, an
     * error answer, or a field of the wrong type.
     */
    static DataCap parse(String json) {
      try {
        return fromMembers(Json.parseNullableObject(json));
      } catch (Json.ParseException e) {
        return null;
      }
    }

    /** The cap object of parsed members; null as for parse, and for null members. */
    static DataCap fromMembers(Map<String, Object> members) {
      if (members == null || members.get("error") != null) {
        return null;
      }
      try {
        Object cappedValue = members.get("capped");
        if (cappedValue != null && !(cappedValue instanceof Boolean)) {
          return null;
        }
        return new DataCap(
            text(members, "client_id"), nullableLong(members, "monthly_byte_limit"),
            Json.longValue(members, "monthly_used_byte_count", 0),
            text(members, "monthly_period_start"), text(members, "monthly_period_end"),
            nullableLong(members, "total_byte_limit"),
            Json.longValue(members, "total_used_byte_count", 0),
            text(members, "total_period_start"), Boolean.TRUE.equals(cappedValue),
            text(members, "capped_reason"));
      } catch (Json.ParseException e) {
        return null;
      }
    }

    /** An integer member, or null when it is absent or JSON null. */
    private static Long nullableLong(Map<String, Object> members, String name)
        throws Json.ParseException {
      return members.get(name) == null ? null : Json.longValue(members, name, 0);
    }

    /** A string member, or "" when it is absent or JSON null. */
    private static String text(Map<String, Object> members, String name)
        throws Json.ParseException {
      String value = Json.string(members, name);
      return value == null ? "" : value;
    }
  }

  /**
   * The cap readings so far. A failed reading keeps the last successful one; before any success,
   * a failure shows "unavailable". Owned by the status loop's thread.
   */
  static final class CapReadings {
    // the latest successful reading; null until one succeeds
    private DataCap latest;
    // whether any reading finished, successful or not
    private boolean attempted;

    /** Records one finished reading: a cap object, or null for a failure. */
    void record(DataCap reading) {
      attempted = true;
      if (reading != null) {
        latest = reading;
      }
    }

    /** The latest successful reading; null before one. */
    DataCap latest() { return latest; }

    /** Whether any reading finished. */
    boolean attempted() { return attempted; }
  }

  /**
   * GET /network/client-data-cap with the client JWT; the client is the JWT's own, so no
   * client_id is sent. Null for any failure: unreachable, a status other than 2xx (a server without
   * the cap routes answers 404), or an answer that is not a cap object. Never throws.
   */
  static DataCap read(URI apiOrigin, String clientJwt, Token.HttpTransport transport) {
    try {
      Token.HttpTransport.HttpAnswer answer =
          transport.send("GET", apiOrigin.resolve(CAP_PATH), clientJwt, null);
      if (answer.status() < 200 || 299 < answer.status()) {
        return null;
      }
      return DataCap.parse(answer.body());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return null;
    } catch (IOException | RuntimeException e) {
      return null;
    }
  }
}
