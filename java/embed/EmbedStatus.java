// The status that every embed example shows, with the exact text rules of
// EMBED_CONTRACT.md ("Status"): the status field, the data used this month and
// the running total. The SDK hands the device values over as C ABI JSON with
// the Go field names; the readers and rules here never throw on bad input, so
// the self-test checks them without the native SDK runtime, a network or
// credentials.
import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The status rules and the texts they produce. */
final class EmbedStatus {
  // GUI and Android only: after an auth logout or a 401 or 409 from the token
  // server, until started again
  static final String STATUS_SIGNED_OUT = "signed out";
  // GUI and Android only: before start or after stop
  static final String STATUS_STOPPED = "stopped";
  // the platform disconnected this client for its network's client limit, and
  // the sdk holds off reconnecting until the retry time
  static final String STATUS_CLIENT_LIMIT = "client limit";
  // a cap of 0 is reached
  static final String STATUS_PAUSED = "paused";
  static final String STATUS_DATA_CAP_REACHED = "data cap reached";
  static final String STATUS_CONNECTED = "connected";
  static final String STATUS_CONNECTING = "connecting";

  // a data field before the first cap reading finishes
  static final String DATA_CHECKING = "checking";
  // a data field when the first cap reading failed
  static final String DATA_UNAVAILABLE = "unavailable";
  // a data field whose cap is not set
  static final String DATA_NO_CAP = "no cap";

  static final String CAPPED_REASON_MONTHLY = "monthly";
  static final String CAPPED_REASON_TOTAL = "total";
  // URNET_CLIENT_LIMIT_STATUS_*
  static final String CLIENT_LIMIT_STATUS_NONE = "";
  static final String CLIENT_LIMIT_STATUS_EXCEEDED = "client_limit_exceeded";

  private static final String[] BYTE_UNITS = {"kB", "MB", "GB", "TB", "PB", "EB"};
  private static final long MINUTE_MILLIS = 60 * 1000;
  private static final long DAY_MINUTES = 24 * 60;
  private static final Pattern RFC3339 = Pattern.compile(
      "(\\d{4})-(\\d{2})-(\\d{2})[Tt](\\d{2}):(\\d{2}):(\\d{2})(?:\\.(\\d+))?([Zz]|[+-]\\d{2}:\\d{2})");

  /** Static members only. */
  private EmbedStatus() {}

  /** The client limit status of the c abi: Status "" or "client_limit_exceeded". */
  record ClientLimit(String status, long retryTime) {}

  private static final ClientLimit NO_CLIENT_LIMIT = new ClientLimit(CLIENT_LIMIT_STATUS_NONE, 0);

  /**
   * Decimal units with one decimal: "0 B", "999 B", "1.0 kB", "5.0 GB". Data plans are sold in
   * decimal units. The tenths round half to even on the exact integer, never on a binary fraction
   * (1050 bytes is "1.0 kB", 1150 bytes "1.2 kB"), and a value that rounds to 1000.0 moves to the
   * next unit (999999 bytes is "1.0 MB").
   */
  static String formatByteCount(long byteCount) {
    if (byteCount < 1000) {
      return byteCount + " B";
    }
    // the bytes in a tenth of the unit
    long tenth = 100;
    for (int unitIndex = 0;; unitIndex += 1) {
      long tenths = byteCount / tenth;
      long remainder = byteCount % tenth;
      if (tenth < 2 * remainder || (2 * remainder == tenth && tenths % 2 == 1)) {
        tenths += 1;
      }
      if (tenths < 10000 || unitIndex == BYTE_UNITS.length - 1) {
        return tenths / 10 + "." + tenths % 10 + " " + BYTE_UNITS[unitIndex];
      }
      tenth *= 1000;
    }
  }

  /** The line the console app prints once the device runs (EMBED_CONTRACT.md, "Status"). */
  static String startLine(String clientId, String instanceId) {
    return "embed client " + clientId + ", installation " + instanceId;
  }

  /**
   * The kind of app whose licenses --licenses prints, from the os.name property: "windows" on
   * Windows, "apple" on macOS, "linux" elsewhere.
   */
  static String licenseApp(String osName) {
    String name = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
    return name.startsWith("windows") ? "windows" : name.startsWith("mac") ? "apple" : "linux";
  }

  /**
   * The client limit text: "client limit, retry at 19:05 UTC". The retry time (unix milliseconds)
   * is rounded up to the next whole minute in UTC, so the shown time is never before the real
   * retry; a retry time of 0 shows "client limit".
   */
  static String clientLimitText(long clientLimitRetryTime) {
    if (clientLimitRetryTime <= 0) {
      return STATUS_CLIENT_LIMIT;
    }
    // whole minutes since the epoch, rounded up without overflow
    long retryMinute = (clientLimitRetryTime - 1) / MINUTE_MILLIS + 1;
    long minuteOfDay = retryMinute % DAY_MINUTES;
    return String.format(Locale.ROOT, "%s, retry at %02d:%02d UTC", STATUS_CLIENT_LIMIT,
                         minuteOfDay / 60, minuteOfDay % 60);
  }

  /**
   * "resets 2026-11-01 00:00 UTC" for a monthly_period_end in RFC 3339, in UTC with the seconds
   * rounded up to the next whole minute; null when it does not parse (or is after the year 9999).
   */
  static String resetText(String monthlyPeriodEnd) {
    if (monthlyPeriodEnd == null) {
      return null;
    }
    Matcher match = RFC3339.matcher(monthlyPeriodEnd);
    if (!match.matches()) {
      return null;
    }
    try {
      ZoneOffset offset = ZoneOffset.UTC;
      String zone = match.group(8);
      if (!zone.equalsIgnoreCase("Z")) {
        int sign = zone.charAt(0) == '-' ? -1 : 1;
        offset = ZoneOffset.ofHoursMinutes(sign * Integer.parseInt(zone.substring(1, 3)),
                                           sign * Integer.parseInt(zone.substring(4, 6)));
      }
      OffsetDateTime end = OffsetDateTime.of(
          group(match, 1), group(match, 2), group(match, 3), group(match, 4), group(match, 5),
          group(match, 6), 0, offset);
      String fraction = match.group(7);
      boolean partialSecond = fraction != null && fraction.chars().anyMatch(c -> c != '0');
      long epochSecond = end.toEpochSecond();
      long minute = Math.floorDiv(epochSecond, 60);
      if (Math.floorMod(epochSecond, 60) != 0 || partialSecond) {
        minute += 1;
      }
      LocalDateTime utc = LocalDateTime.ofEpochSecond(minute * 60, 0, ZoneOffset.UTC);
      if (9999 < utc.getYear()) {
        return null;
      }
      return String.format(Locale.ROOT, "resets %04d-%02d-%02d %02d:%02d UTC", utc.getYear(),
                           utc.getMonthValue(), utc.getDayOfMonth(), utc.getHour(),
                           utc.getMinute());
    } catch (DateTimeException e) {
      // an invalid date or offset
      return null;
    }
  }

  /** One numeric group of a match. */
  private static int group(Matcher match, int index) {
    return Integer.parseInt(match.group(index));
  }

  /**
   * The status field: the first rule that applies. started and signedOut matter to GUI apps only;
   * a console app passes started and not signed out. latestCap is the latest successful cap
   * reading, null before one.
   */
  static String status(boolean started, boolean signedOut, String clientLimitStatus,
                       long clientLimitRetryTime, Caps.DataCap latestCap, int providersAdded) {
    if (signedOut) {
      return STATUS_SIGNED_OUT;
    }
    if (!started) {
      return STATUS_STOPPED;
    }
    if (CLIENT_LIMIT_STATUS_EXCEEDED.equals(clientLimitStatus)) {
      return clientLimitText(clientLimitRetryTime);
    }
    if (latestCap != null && latestCap.capped()) {
      String reason = latestCap.cappedReason() == null ? "" : latestCap.cappedReason();
      // the cap that capped_reason names; an unknown reason names none
      Long reachedLimit = switch (reason) {
        case CAPPED_REASON_MONTHLY -> latestCap.monthlyByteLimit();
        case CAPPED_REASON_TOTAL -> latestCap.totalByteLimit();
        default -> null;
      };
      if (reachedLimit != null && reachedLimit == 0) {
        return STATUS_PAUSED;
      }
      if (CAPPED_REASON_MONTHLY.equals(reason)) {
        String reset = resetText(latestCap.monthlyPeriodEnd());
        if (reset != null) {
          return STATUS_DATA_CAP_REACHED + ", " + reset;
        }
      }
      return STATUS_DATA_CAP_REACHED;
    }
    if (1 <= providersAdded) {
      return STATUS_CONNECTED;
    }
    return STATUS_CONNECTING;
  }

  /**
   * A data field: "checking", "unavailable", "no cap" or "<used> of <limit>". A field without a
   * cap never shows a used count.
   */
  static String dataField(Caps.CapReadings readings, boolean monthly) {
    Caps.DataCap cap = readings.latest();
    if (cap == null) {
      return readings.attempted() ? DATA_UNAVAILABLE : DATA_CHECKING;
    }
    Long limit = monthly ? cap.monthlyByteLimit() : cap.totalByteLimit();
    if (limit == null) {
      return DATA_NO_CAP;
    }
    long used = monthly ? cap.monthlyUsedByteCount() : cap.totalUsedByteCount();
    return formatByteCount(used) + " of " + formatByteCount(limit);
  }

  /** The console status line. */
  static String statusLine(String status, String monthly, String total) {
    return "status: " + status + " | data this month: " + monthly + " | data total: " + total;
  }

  /**
   * The c abi's client limit status JSON, for example {"Status": "client_limit_exceeded",
   * "RetryTime": 1791313500000}. NULL (the call could not run) and a value that does not parse read
   * as no limit.
   */
  static ClientLimit parseClientLimit(String clientLimitStatusJson) {
    try {
      Map<String, Object> members = Json.parseNullableObject(clientLimitStatusJson);
      if (members == null) {
        return NO_CLIENT_LIMIT;
      }
      String status = Json.string(members, "Status");
      return new ClientLimit(status == null ? CLIENT_LIMIT_STATUS_NONE : status,
                             Json.longValue(members, "RetryTime", 0));
    } catch (Json.ParseException e) {
      return NO_CLIENT_LIMIT;
    }
  }

  /**
   * ProviderStateAdded of the c abi's window status JSON: the providers in the window. NULL before
   * the window exists, and a value that does not parse, read as 0.
   */
  static int providersAdded(String windowStatusJson) {
    try {
      Map<String, Object> members = Json.parseNullableObject(windowStatusJson);
      if (members == null) {
        return 0;
      }
      long added = Json.longValue(members, "ProviderStateAdded", 0);
      return (int)Math.max(0, Math.min(Integer.MAX_VALUE, added));
    } catch (Json.ParseException e) {
      return 0;
    }
  }
}
