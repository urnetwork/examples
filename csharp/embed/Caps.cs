// The installation's data caps (EMBED_CONTRACT.md, "Backend: per-user data
// caps"). The backend sets them with the root credential; the app reads its
// own with its client JWT: GET /network/client-data-cap at the URnetwork API.
// Usage is accounted when transfer contracts settle, so the counts lag live
// traffic. The parsing here is pure managed code that never throws, so the
// self-test checks it without a network.
using System.Net.Http.Headers;
using System.Text;
using System.Text.Json;

/// One cap object, as GET /network/client-data-cap answers it. A limit is
/// null when that cap is not set.
internal sealed record DataCap {
  public string ClientId { get; init; } = "";
  public long? MonthlyByteLimit { get; init; }
  public long MonthlyUsedByteCount { get; init; }
  public string MonthlyPeriodStart { get; init; } = "";
  // RFC 3339; when monthly usage resets
  public string MonthlyPeriodEnd { get; init; } = "";
  public long? TotalByteLimit { get; init; }
  public long TotalUsedByteCount { get; init; }
  public string TotalPeriodStart { get; init; } = "";
  // true while a cap is reached: the client gets no new transfer contracts
  public bool Capped { get; init; }
  // "monthly", "total", or "" when the client is not capped
  public string CappedReason { get; init; } = "";

  /// The server refuses the cap read with this message while the team has not
  /// enabled Embed for the network (EMBED_CONTRACT.md, "Embed enablement").
  public const string EmbedNotEnabledMessage = "Embed isn't enabled for this network.";

  /// Whether a JSON text is the Embed-not-enabled refusal,
  /// {"error": {"message": "Embed isn't enabled for this network."}}.
  public static bool IsEmbedNotEnabled(string? json) {
    if (json == null) {
      return false;
    }
    try {
      using var document = JsonDocument.Parse(json);
      return document.RootElement.ValueKind == JsonValueKind.Object &&
             document.RootElement.TryGetProperty("error", out var error) &&
             error.ValueKind == JsonValueKind.Object &&
             error.TryGetProperty("message", out var message) &&
             message.ValueKind == JsonValueKind.String && message.GetString() == EmbedNotEnabledMessage;
    } catch (Exception e) when (e is JsonException or ArgumentException) {
      return false;
    }
  }

  /// The cap object of a JSON text; null when the text is not one: not JSON,
  /// not an object, an error answer, or a field of the wrong type.
  public static DataCap? Parse(string? json) {
    if (json == null) {
      return null;
    }
    try {
      using var document = JsonDocument.Parse(json);
      return FromElement(document.RootElement);
    } catch (Exception e) when (e is JsonException or ArgumentException) {
      return null;
    }
  }

  /// The cap object of a parsed JSON value; null as for Parse.
  public static DataCap? FromElement(JsonElement element) {
    if (element.ValueKind != JsonValueKind.Object) {
      return null;
    }
    if (element.TryGetProperty("error", out var error) &&
        error.ValueKind != JsonValueKind.Null) {
      return null;
    }
    if (!NullableLong(element, "monthly_byte_limit", out long? monthlyByteLimit) ||
        !NullableLong(element, "total_byte_limit", out long? totalByteLimit) ||
        !NullableLong(element, "monthly_used_byte_count", out long? monthlyUsed) ||
        !NullableLong(element, "total_used_byte_count", out long? totalUsed) ||
        !NullableString(element, "client_id", out string clientId) ||
        !NullableString(element, "monthly_period_start", out string monthlyPeriodStart) ||
        !NullableString(element, "monthly_period_end", out string monthlyPeriodEnd) ||
        !NullableString(element, "total_period_start", out string totalPeriodStart) ||
        !NullableString(element, "capped_reason", out string cappedReason)) {
      return null;
    }
    bool capped = false;
    if (element.TryGetProperty("capped", out var cappedValue)) {
      if (cappedValue.ValueKind is JsonValueKind.True or JsonValueKind.False) {
        capped = cappedValue.GetBoolean();
      } else if (cappedValue.ValueKind != JsonValueKind.Null) {
        return null;
      }
    }
    return new DataCap {
      ClientId = clientId,
      MonthlyByteLimit = monthlyByteLimit,
      MonthlyUsedByteCount = monthlyUsed ?? 0,
      MonthlyPeriodStart = monthlyPeriodStart,
      MonthlyPeriodEnd = monthlyPeriodEnd,
      TotalByteLimit = totalByteLimit,
      TotalUsedByteCount = totalUsed ?? 0,
      TotalPeriodStart = totalPeriodStart,
      Capped = capped,
      CappedReason = cappedReason,
    };
  }

  /// An integer member that fits a long, or null when it is absent or JSON
  /// null; false when it has another type.
  private static bool NullableLong(JsonElement element, string name, out long? value) {
    value = null;
    if (!element.TryGetProperty(name, out var member) ||
        member.ValueKind == JsonValueKind.Null) {
      return true;
    }
    if (member.ValueKind == JsonValueKind.Number && member.TryGetInt64(out long number)) {
      value = number;
      return true;
    }
    return false;
  }

  /// A string member, or "" when it is absent or JSON null; false when it has
  /// another type.
  private static bool NullableString(JsonElement element, string name, out string value) {
    value = "";
    if (!element.TryGetProperty(name, out var member) ||
        member.ValueKind == JsonValueKind.Null) {
      return true;
    }
    if (member.ValueKind == JsonValueKind.String) {
      value = member.GetString() ?? "";
      return true;
    }
    return false;
  }
}

/// One finished cap read: the cap object, or null for a failure.
/// EmbedNotEnabled marks the Embed-not-enabled refusal, which clears the last
/// reading.
internal sealed record CapRead(DataCap? Cap, bool EmbedNotEnabled = false);

/// The cap readings so far. A failed reading keeps the last successful one;
/// before any success, a failure shows "unavailable". The Embed-not-enabled
/// refusal clears the last reading. Owned by the status loop's thread.
internal sealed class CapReadings {
  // the latest successful reading; null until one succeeds
  public DataCap? Latest { get; private set; }
  // whether any reading finished, successful or not
  public bool Attempted { get; private set; }

  /// Records one finished reading: a cap object, or null for a failure.
  public void Record(DataCap? reading) {
    Attempted = true;
    if (reading != null) {
      Latest = reading;
    }
  }

  /// Records the Embed-not-enabled refusal: it clears the last reading, so
  /// both data fields read "unavailable" and the status rules see no cap
  /// reading.
  public void RecordEmbedNotEnabled() {
    Attempted = true;
    Latest = null;
  }

  /// Records one finished cap read: the Embed-not-enabled refusal clears the
  /// last reading; another failure keeps it.
  public void RecordRead(CapRead read) {
    if (read.EmbedNotEnabled) {
      RecordEmbedNotEnabled();
    } else {
      Record(read.Cap);
    }
  }
}

/// Reads the installation's own caps with its client JWT.
internal static class CapClient {
  public const string CapPath = "/network/client-data-cap";
  private const int ResponseByteLimit = 1 << 20;

  private static readonly HttpMessageInvoker SharedInvoker =
      new(new HttpClientHandler { AllowAutoRedirect = false });

  /// GET /network/client-data-cap with the client JWT; the client is the
  /// JWT's own, so no client_id is sent. No cap for any failure: unreachable,
  /// a status other than 2xx (a server without the cap routes answers 404), or
  /// an answer that is not a cap object; the Embed-not-enabled refusal is
  /// marked. Never throws.
  public static async Task<CapRead> ReadAsync(Uri apiOrigin, string clientJwt,
                                              HttpMessageInvoker? invoker = null) {
    try {
      using var request = new HttpRequestMessage(HttpMethod.Get, new Uri(apiOrigin, CapPath));
      request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", clientJwt);
      using var deadline = new CancellationTokenSource(TimeSpan.FromSeconds(15));
      using HttpResponseMessage response =
          await (invoker ?? SharedInvoker).SendAsync(request, deadline.Token);
      if (!response.IsSuccessStatusCode) {
        return new CapRead(null);
      }
      string? body = await ReadBoundedAsync(response.Content, ResponseByteLimit, deadline.Token);
      return DataCap.IsEmbedNotEnabled(body) ? new CapRead(null, EmbedNotEnabled: true)
                                             : new CapRead(DataCap.Parse(body));
    } catch (Exception e) when (e is HttpRequestException or OperationCanceledException or
                                    IOException or InvalidOperationException) {
      return new CapRead(null);
    }
  }

  /// The content as UTF-8 text, or null when it is longer than limit bytes.
  public static async Task<string?> ReadBoundedAsync(HttpContent content, int limit,
                                                     CancellationToken cancel) {
    using Stream input = await content.ReadAsStreamAsync(cancel);
    using var output = new MemoryStream();
    var buffer = new byte[8192];
    int count;
    while ((count = await input.ReadAsync(buffer, cancel)) != 0) {
      if (limit < output.Length + count) {
        return null;
      }
      output.Write(buffer, 0, count);
    }
    return Encoding.UTF8.GetString(output.ToArray());
  }
}
