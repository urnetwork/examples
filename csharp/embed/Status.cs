// The status that every embed example shows, with the exact text rules of
// EMBED_CONTRACT.md ("Status"): the status field, the data used this month and
// the running total. The SDK hands the device values over as C ABI JSON with
// the Go field names; the readers and rules here are pure managed code that
// never throws on bad input, so the self-test checks them without the native
// SDK runtime, a network or credentials.
using System.Globalization;
using System.Text.Json;
using System.Text.RegularExpressions;

/// The status rules and the texts they produce.
internal static class StatusRules {
  // GUI and Android only: after an auth logout or a 401 or 409 from the token
  // server, until started again
  public const string StatusSignedOut = "signed out";
  // GUI and Android only: before start or after stop
  public const string StatusStopped = "stopped";
  // the platform disconnected this client for its network's client limit, and
  // the SDK holds off reconnecting until the retry time
  public const string StatusClientLimit = "client limit";
  // a cap of 0 is reached
  public const string StatusPaused = "paused";
  public const string StatusDataCapReached = "data cap reached";
  public const string StatusConnected = "connected";
  public const string StatusConnecting = "connecting";

  // a data field before the first cap reading finishes
  public const string DataChecking = "checking";
  // a data field when the first cap reading failed
  public const string DataUnavailable = "unavailable";
  // a data field whose cap is not set
  public const string DataNoCap = "no cap";

  public const string CappedReasonMonthly = "monthly";
  public const string CappedReasonTotal = "total";
  // URNET_CLIENT_LIMIT_STATUS_*
  public const string ClientLimitStatusNone = "";
  public const string ClientLimitStatusExceeded = "client_limit_exceeded";

  private static readonly Regex Rfc3339 = new(
      @"\A(\d{4})-(\d{2})-(\d{2})[Tt](\d{2}):(\d{2}):(\d{2})(?:\.(\d+))?([Zz]|[+-]\d{2}:\d{2})\z");

  /// Decimal units with one decimal: "0 B", "999 B", "1.0 kB", "5.0 GB". Data
  /// plans are sold in decimal units. A value that rounds to 1000.0 moves to
  /// the next unit, decided with midpoints away from zero as the Go
  /// reference's math.Round does. The text rounds to the nearest tenth with
  /// ties to even on the exact value, as Go's %.1f does, and F1 matches it
  /// (1250 bytes is "1.2 kB", 1750 bytes "1.8 kB").
  public static string FormatByteCount(long byteCount) {
    if (byteCount < 1000) {
      return byteCount.ToString(CultureInfo.InvariantCulture) + " B";
    }
    string[] units = ["kB", "MB", "GB", "TB", "PB", "EB"];
    double value = byteCount / 1000.0;
    int unitIndex = 0;
    while (unitIndex < units.Length - 1 &&
           1000 <= Math.Round(value * 10, MidpointRounding.AwayFromZero) / 10) {
      value /= 1000;
      unitIndex += 1;
    }
    return value.ToString("F1", CultureInfo.InvariantCulture) + " " + units[unitIndex];
  }

  /// The client limit text: "client limit, retry at 19:05 UTC". The retry
  /// time (unix milliseconds) is rounded up to the next whole minute in UTC,
  /// so the shown time is never before the real retry; a retry time of 0, or
  /// one after the year 9999, shows "client limit".
  public static string ClientLimitText(long clientLimitRetryTime) {
    if (clientLimitRetryTime <= 0) {
      return StatusClientLimit;
    }
    const long minuteMillis = 60 * 1000;
    long retryMinute = clientLimitRetryTime / minuteMillis +
                       (clientLimitRetryTime % minuteMillis == 0 ? 0 : 1);
    if (DateTimeOffset.MaxValue.ToUnixTimeSeconds() / 60 < retryMinute) {
      return StatusClientLimit;
    }
    var retryTime = DateTimeOffset.FromUnixTimeSeconds(retryMinute * 60);
    return $"{StatusClientLimit}, retry at {retryTime.ToString("HH:mm", CultureInfo.InvariantCulture)} UTC";
  }

  /// "resets 2026-11-01 00:00 UTC" for a monthly_period_end in RFC 3339, in UTC
  /// with the seconds rounded up to the next whole minute; null when it does
  /// not parse (or is after the year 9999).
  public static string? ResetText(string? monthlyPeriodEnd) {
    if (monthlyPeriodEnd == null) {
      return null;
    }
    Match match = Rfc3339.Match(monthlyPeriodEnd);
    if (!match.Success) {
      return null;
    }
    int Group(int index) => int.Parse(match.Groups[index].Value, CultureInfo.InvariantCulture);
    try {
      TimeSpan offset = TimeSpan.Zero;
      string zone = match.Groups[8].Value;
      if (zone is not ("Z" or "z")) {
        int sign = zone[0] == '-' ? -1 : 1;
        offset = new TimeSpan(sign * int.Parse(zone[1..3], CultureInfo.InvariantCulture),
                              sign * int.Parse(zone[4..6], CultureInfo.InvariantCulture), 0);
      }
      DateTimeOffset end = new DateTimeOffset(Group(1), Group(2), Group(3), Group(4), Group(5),
                                              Group(6), offset)
                               .ToUniversalTime();
      bool fraction = match.Groups[7].Success && match.Groups[7].Value.Any(c => c != '0');
      DateTimeOffset minute = end.AddSeconds(-end.Second);
      if (end.Second != 0 || fraction) {
        minute = minute.AddMinutes(1);
      }
      return $"resets {minute.ToString("yyyy-MM-dd HH:mm", CultureInfo.InvariantCulture)} UTC";
    } catch (ArgumentException) {
      // an invalid date or offset, or a time after the year 9999
      return null;
    }
  }

  /// The status field: the first rule that applies. started and signedOut
  /// matter to GUI apps only; a console app passes started and not signed out.
  /// latestCap is the latest successful cap reading, null before one.
  public static string Status(bool started, bool signedOut, string clientLimitStatus,
                              long clientLimitRetryTime, DataCap? latestCap,
                              int providersAdded) {
    if (signedOut) {
      return StatusSignedOut;
    }
    if (!started) {
      return StatusStopped;
    }
    if (clientLimitStatus == ClientLimitStatusExceeded) {
      return ClientLimitText(clientLimitRetryTime);
    }
    if (latestCap is { Capped: true } cap) {
      // the cap that capped_reason names; an unknown reason names none
      long? reachedLimit = cap.CappedReason switch {
        CappedReasonMonthly => cap.MonthlyByteLimit,
        CappedReasonTotal => cap.TotalByteLimit,
        _ => null,
      };
      if (reachedLimit == 0) {
        return StatusPaused;
      }
      if (cap.CappedReason == CappedReasonMonthly && ResetText(cap.MonthlyPeriodEnd) is { } reset) {
        return $"{StatusDataCapReached}, {reset}";
      }
      return StatusDataCapReached;
    }
    if (1 <= providersAdded) {
      return StatusConnected;
    }
    return StatusConnecting;
  }

  /// A data field: "checking", "unavailable", "no cap" or "<used> of <limit>".
  /// A field without a cap never shows a used count.
  public static string DataField(CapReadings readings, bool monthly) {
    if (readings.Latest is not { } cap) {
      return readings.Attempted ? DataUnavailable : DataChecking;
    }
    long? limit = monthly ? cap.MonthlyByteLimit : cap.TotalByteLimit;
    if (limit is not long byteLimit) {
      return DataNoCap;
    }
    long used = monthly ? cap.MonthlyUsedByteCount : cap.TotalUsedByteCount;
    return $"{FormatByteCount(used)} of {FormatByteCount(byteLimit)}";
  }

  /// The console status line.
  public static string StatusLine(string status, string monthly, string total) {
    return $"status: {status} | data this month: {monthly} | data total: {total}";
  }

  /// The client limit status of urnet_device_get_client_limit_status. NULL,
  /// which the call returns only when it cannot run, reads as no limit.
  public static (string Status, long RetryTime) ParseClientLimitStatus(string? json) {
    if (ParseObject(json) is not { } status) {
      return (Status: ClientLimitStatusNone, RetryTime: 0);
    }
    return (Status: StringProperty(status, "Status"), RetryTime: Int64Property(status, "RetryTime"));
  }

  /// ProviderStateAdded of urnet_device_get_window_status: the providers in
  /// the window. NULL before the window exists reads as 0.
  public static int ProvidersAdded(string? windowStatusJson) {
    if (ParseObject(windowStatusJson) is not { } windowStatus) {
      return 0;
    }
    long added = Int64Property(windowStatus, "ProviderStateAdded");
    return (int)Math.Clamp(added, 0, int.MaxValue);
  }

  /// The root object of a C ABI JSON value; null for NULL, JSON null, another
  /// kind of value, or text that does not parse.
  private static JsonElement? ParseObject(string? json) {
    if (json == null) {
      return null;
    }
    try {
      using var document = JsonDocument.Parse(json);
      if (document.RootElement.ValueKind != JsonValueKind.Object) {
        return null;
      }
      return document.RootElement.Clone();
    } catch (Exception e) when (e is JsonException or ArgumentException) {
      return null;
    }
  }

  /// A string property, or "" when it is missing or not a string.
  private static string StringProperty(JsonElement element, string name) {
    if (element.TryGetProperty(name, out var value) && value.ValueKind == JsonValueKind.String) {
      return value.GetString() ?? "";
    }
    return "";
  }

  /// An integer property, or 0 when it is missing or not an integer.
  private static long Int64Property(JsonElement element, string name) {
    if (element.TryGetProperty(name, out var value) &&
        value.ValueKind == JsonValueKind.Number && value.TryGetInt64(out long number)) {
      return number;
    }
    return 0;
  }
}
