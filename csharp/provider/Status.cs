// The provider status that every provider example shows, with the exact text
// rules of PROVIDER_CONTRACT.md ("Status"): providing state, clients served,
// data provided and the payout wallet, read only. The SDK hands the status
// values over as C ABI JSON with the Go field names; the readers and rules
// here are pure managed code that never throws on bad input, so SDK callbacks
// can use them and the self-test checks them without the native SDK runtime,
// a network or credentials.
using System.Globalization;
using System.Text.Json;

/// The status rules and the texts they produce.
internal static class StatusRules {
  /// Shown once at start, and in every example's README. The app that
  /// integrates a provider owns the consent screen; this example starts
  /// without asking. The line breaks are LF on every OS, as the contract's
  /// hash requires, also when git checks this file out with CRLF.
  public static readonly string ConsentDisclaimer = """
      Consent disclaimer: an app that integrates a URnetwork provider must collect the user's consent before it provides.
      Providing shares the user's internet connection: other URnetwork users' traffic exits through the user's device and IP address.
      This example starts providing without asking, because the consent screen belongs to your app.
      """.ReplaceLineEndings("\n");

  public const string StateStopped = "stopped";
  // the platform disconnected this client for its network's client limit, and
  // the SDK holds off reconnecting until the retry time
  public const string StateClientLimit = "client limit";
  public const string StateStarting = "starting";
  public const string StatePaused = "paused";
  public const string StateProviding = "providing";

  // the payout wallet before the first wallet read finishes
  public const string PayoutWalletChecking = "checking";
  // the payout wallet when the first wallet read failed
  public const string PayoutWalletUnavailable = "unavailable";
  // the payout wallet when no wallet is mapped
  public const string PayoutWalletNotSet = "not set";

  public const string PayoutWalletScopeHotkey = "hotkey";
  public const string PayoutWalletScopeProvider = "this provider";
  public const string PayoutWalletScopeNetwork = "network";
  public const string PayoutWalletScopeAnotherProvider = "another provider";

  // C ABI values (urnetwork_sdk.h): URNET_PROVIDE_MODE_*
  public const long ProvideModeNone = 0;
  public const long ProvideModeNetwork = 1;
  public const long ProvideModePublic = 3;
  // URNET_CLIENT_LIMIT_STATUS_*
  public const string ClientLimitStatusNone = "";
  public const string ClientLimitStatusExceeded = "client_limit_exceeded";
  // the consent_scope of a GET /sn/wallet entry
  public const string SnWalletConsentScopeProvider = "provider";
  public const string SnWalletConsentScopeNetwork = "network";
  // a network's hotkey delegation entry; it comes with a later server and SDK
  // change, and the app only labels it
  public const string SnWalletConsentScopeHotkey = "hotkey";

  // Distinct clients are counted up to this many; beyond it the count is a
  // lower bound, shown with a trailing "+".
  public const int ClientsServedLimit = 100 * 1000;

  /// Binary units with one decimal: "0 B", "1023 B", "1.0 KiB", "12.4 MiB". A
  /// value that rounds to 1024.0 moves to the next unit, decided with
  /// midpoints away from zero as the Go reference's math.Round does. The text
  /// rounds ties to even, as Go's %.1f does (1280 bytes is "1.2 KiB").
  public static string FormatByteCount(long byteCount) {
    if (byteCount < 1024) {
      return byteCount.ToString(CultureInfo.InvariantCulture) + " B";
    }
    string[] units = ["KiB", "MiB", "GiB", "TiB", "PiB", "EiB"];
    double value = byteCount / 1024.0;
    int unitIndex = 0;
    while (unitIndex < units.Length - 1 &&
           1024 <= Math.Round(value * 10, MidpointRounding.AwayFromZero) / 10) {
      value /= 1024;
      unitIndex += 1;
    }
    return value.ToString("F1", CultureInfo.InvariantCulture) + " " +
           units[unitIndex];
  }

  /// The status field text: the state, and for the client limit state the time
  /// the SDK retries, for example "client limit, retry at 19:05 UTC". The retry
  /// time (unix milliseconds) is rounded up to the next whole minute in UTC, so
  /// the shown time is never before the real retry; a retry time of 0, or one
  /// after the year 9999, shows "client limit".
  public static string StatusText(string state, long clientLimitRetryTime) {
    if (state != StateClientLimit || clientLimitRetryTime <= 0) {
      return state;
    }
    const long minuteMillis = 60 * 1000;
    long retryMinute = clientLimitRetryTime / minuteMillis +
                       (clientLimitRetryTime % minuteMillis == 0 ? 0 : 1);
    if (DateTimeOffset.MaxValue.ToUnixTimeSeconds() / 60 < retryMinute) {
      return state;
    }
    var retryTime = DateTimeOffset.FromUnixTimeSeconds(retryMinute * 60);
    return $"{StateClientLimit}, retry at {retryTime.ToString("HH:mm", CultureInfo.InvariantCulture)} UTC";
  }

  /// The providing state from the device getters, in this order: stopped
  /// unless the provide mode is public; client limit while the SDK holds this
  /// client off for its network's client limit; paused while paused; providing
  /// once the provider is enabled and its platform carrier is connected;
  /// starting otherwise.
  public static string ProviderState(long provideMode, string clientLimitStatus,
                                     bool providePaused, bool provideEnabled,
                                     bool providerConnected) {
    if (provideMode != ProvideModePublic) {
      return StateStopped;
    }
    if (clientLimitStatus == ClientLimitStatusExceeded) {
      return StateClientLimit;
    }
    if (providePaused) {
      return StatePaused;
    }
    if (provideEnabled && providerConnected) {
      return StateProviding;
    }
    return StateStarting;
  }

  /// Which owner the effective payout wallet belongs to. The consent scope
  /// comes first: a hotkey delegation is network-level, with no client id, but
  /// is not the network's wallet. Otherwise by the wallet's client id: this
  /// provider's own mapping, the network's wallet, or another provider of the
  /// network.
  public static string PayoutWalletScope(string walletConsentScope,
                                         string walletClientId,
                                         string clientId) {
    if (walletConsentScope == SnWalletConsentScopeHotkey) {
      return PayoutWalletScopeHotkey;
    }
    if (walletClientId == "") {
      return PayoutWalletScopeNetwork;
    }
    if (walletClientId == clientId) {
      return PayoutWalletScopeProvider;
    }
    return PayoutWalletScopeAnotherProvider;
  }

  /// The peer of one provider contract, from the contract details JSON of the
  /// provider contract listeners, by direction as the SDK's contract screens
  /// resolve it: the source of a receive (ingress) contract, the destination of
  /// a send (egress) contract. A path without that client id is keyed by its
  /// stream id, then by the contract id; "" when there is none.
  public static string ContractPeerKey(string? contractDetailsJson,
                                       bool receive) {
    if (ParseObject(contractDetailsJson) is not { } details) {
      return "";
    }
    if (details.TryGetProperty("ContractTransferPath", out var path) &&
        path.ValueKind == JsonValueKind.Object) {
      if (PresentId(path, receive ? "SourceId" : "DestinationId") is { } peerId) {
        return peerId;
      }
      if (PresentId(path, "StreamId") is { } streamId) {
        return "stream:" + streamId;
      }
    }
    if (PresentId(details, "ContractId") is { } contractId) {
      return "contract:" + contractId;
    }
    return "";
  }

  /// The client limit status of urnet_device_get_client_limit_status. NULL,
  /// which the call returns only when it cannot run, reads as no limit.
  public static (string Status, long RetryTime) ParseClientLimitStatus(
      string? clientLimitStatusJson) {
    if (ParseObject(clientLimitStatusJson) is not { } status) {
      return (Status: ClientLimitStatusNone, RetryTime: 0);
    }
    return (Status: StringProperty(status, "Status"),
            RetryTime: Int64Property(status, "RetryTime"));
  }

  /// Bytes relayed for clients, in both directions, since the device started,
  /// from urnet_device_get_provider_packet_stats; 0 when the stats are NULL.
  public static long DataProvidedByteCount(string? providerPacketStatsJson) {
    if (ParseObject(providerPacketStatsJson) is not { } packetStats) {
      return 0;
    }
    return Int64Property(packetStats, "RemoteEgressByteCount") +
           Int64Property(packetStats, "RemoteIngressByteCount");
  }

  /// Whether a wallet read succeeded, from the arguments of its
  /// urnet_sn_get_wallet_cb: no error text, and a result without an error.
  public static bool WalletReadSucceeded(string? resultJson, string? errorText) {
    if (errorText != null || ParseObject(resultJson) is not { } result) {
      return false;
    }
    return !result.TryGetProperty("error", out var resultError) ||
           resultError.ValueKind == JsonValueKind.Null;
  }

  /// The payout wallet text and scope of the effective wallet that the SDK
  /// caches (urnet_device_local_get_sn_wallet): the coldkey address with its
  /// scope, or "not set" without a mapped coldkey.
  public static (string PayoutWallet, string PayoutWalletScope)
      ParsePayoutWallet(string? walletJson, string clientId) {
    if (ParseObject(walletJson) is not { } wallet ||
        StringProperty(wallet, "coldkey_ss58") is not { Length: > 0 } coldkeySs58) {
      return (PayoutWallet: PayoutWalletNotSet, PayoutWalletScope: "");
    }
    string scope = PayoutWalletScope(StringProperty(wallet, "consent_scope"),
                                     StringProperty(wallet, "client_id"), clientId);
    return (PayoutWallet: coldkeySs58, PayoutWalletScope: scope);
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
    if (element.TryGetProperty(name, out var value) &&
        value.ValueKind == JsonValueKind.String) {
      return value.GetString() ?? "";
    }
    return "";
  }

  /// An integer property, or 0 when it is missing or not an integer.
  private static long Int64Property(JsonElement element, string name) {
    if (element.TryGetProperty(name, out var value) &&
        value.ValueKind == JsonValueKind.Number &&
        value.TryGetInt64(out long number)) {
      return number;
    }
    return 0;
  }

  /// An id property in canonical form, or null when it is missing, not a
  /// UUID or all zeros.
  private static string? PresentId(JsonElement element, string name) {
    string? id = Uuid.Parse(StringProperty(element, name));
    return id == Uuid.Zero ? null : id;
  }
}

/// One status snapshot.
internal sealed record ProviderStatus {
  public required string State { get; init; }
  // the end of the client limit hold in unix milliseconds, shown with the
  // client limit state; 0 when unknown
  public long ClientLimitRetryTime { get; init; }
  public int ClientsServed { get; init; }
  public bool ClientsServedAtLimit { get; init; }
  public long DataProvidedByteCount { get; init; }
  // a coldkey ss58 address, or StatusRules.PayoutWalletChecking,
  // PayoutWalletUnavailable or PayoutWalletNotSet
  public required string PayoutWallet { get; init; }
  // "" unless PayoutWallet is an address
  public string PayoutWalletScope { get; init; } = "";

  /// The status line, for example
  /// "status: providing | clients served: 3 | data provided: 12.4 MiB | payout wallet: 5Grw... (network)".
  public override string ToString() {
    string clientsServed = ClientsServed.ToString(CultureInfo.InvariantCulture) +
                           (ClientsServedAtLimit ? "+" : "");
    string payoutWallet = PayoutWalletScope == ""
                              ? PayoutWallet
                              : $"{PayoutWallet} ({PayoutWalletScope})";
    return $"status: {StatusRules.StatusText(State, ClientLimitRetryTime)} | clients served: {clientsServed} | data provided: {StatusRules.FormatByteCount(DataProvidedByteCount)} | payout wallet: {payoutWallet}";
  }

  /// The fields that change rarely. A change prints a status line at once; the
  /// data counter alone only prints on the periodic line. The status text
  /// carries the client limit retry time, so a new retry time prints too.
  public StatusKey Key() {
    return new StatusKey(
        StatusText: StatusRules.StatusText(State, ClientLimitRetryTime),
        ClientsServed: ClientsServed,
        ClientsServedAtLimit: ClientsServedAtLimit,
        PayoutWallet: PayoutWallet,
        PayoutWalletScope: PayoutWalletScope);
  }
}

/// The parts of a status snapshot whose change prints a status line.
internal readonly record struct StatusKey(string StatusText, int ClientsServed,
                                          bool ClientsServedAtLimit,
                                          string PayoutWallet,
                                          string PayoutWalletScope);

/// The distinct clients that held a contract with this provider since the app
/// started. Safe for concurrent use: the SDK delivers contract details on its
/// own threads.
internal sealed class ServedClients {
  private readonly int limit;
  private readonly object stateLock = new();
  // the ContractPeerKey values counted so far
  private readonly HashSet<string> peerKeys = [];
  private bool atLimit;

  /// An empty count that keeps at most limit distinct peers.
  public ServedClients(int limit) {
    this.limit = limit;
  }

  /// Counts the peer of one provider contract, from its contract details JSON.
  public void Add(string? contractDetailsJson, bool receive) {
    string peerKey = StatusRules.ContractPeerKey(contractDetailsJson, receive);
    if (peerKey == "") {
      return;
    }
    lock (stateLock) {
      if (peerKeys.Contains(peerKey)) {
        return;
      }
      if (limit <= peerKeys.Count) {
        atLimit = true;
        return;
      }
      peerKeys.Add(peerKey);
    }
  }

  /// The distinct count, and whether the count stopped at the limit.
  public (int Count, bool AtLimit) Count() {
    lock (stateLock) {
      return (Count: peerKeys.Count, AtLimit: atLimit);
    }
  }
}
