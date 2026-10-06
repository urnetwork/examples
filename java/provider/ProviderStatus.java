// The provider status that every provider example shows, with the exact text
// rules of PROVIDER_CONTRACT.md ("Status"): providing state, clients served,
// data provided and the payout wallet, read only. It also reads the status
// values that the SDK's C ABI returns as JSON. Nothing here calls the SDK, so
// the self-test checks it without a network, credentials or the native SDK
// runtime.
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * One status snapshot. payoutWallet is a coldkey ss58 address or one of the PAYOUT_WALLET texts;
 * payoutWalletScope is "" unless payoutWallet is an address. clientLimitRetryTime is the end of the
 * client limit hold in unix milliseconds, 0 when there is none.
 */
record ProviderStatus(String state, long clientLimitRetryTime,
                      int clientsServed, boolean clientsServedAtLimit,
                      long dataProvidedByteCount, String payoutWallet,
                      String payoutWalletScope) {
  // shown once at start and in every example's README. The app that
  // integrates a provider owns the consent screen; this example starts
  // without asking.
  static final String CONSENT_DISCLAIMER = """
      Consent disclaimer: an app that integrates a URnetwork provider must collect the user's consent before it provides.
      Providing shares the user's internet connection: other URnetwork users' traffic exits through the user's device and IP address.
      This example starts providing without asking, because the consent screen belongs to your app.""";

  static final String STATE_STOPPED = "stopped";
  // the platform disconnected this client for its network's client limit,
  // and the sdk holds off reconnecting until the retry time
  static final String STATE_CLIENT_LIMIT = "client limit";
  static final String STATE_STARTING = "starting";
  static final String STATE_PAUSED = "paused";
  static final String STATE_PROVIDING = "providing";

  // the payout wallet before the first wallet read finishes
  static final String PAYOUT_WALLET_CHECKING = "checking";
  // the payout wallet when the first wallet read failed
  static final String PAYOUT_WALLET_UNAVAILABLE = "unavailable";
  // the payout wallet when no wallet is mapped
  static final String PAYOUT_WALLET_NOT_SET = "not set";

  static final String PAYOUT_WALLET_SCOPE_HOTKEY = "hotkey";
  static final String PAYOUT_WALLET_SCOPE_PROVIDER = "this provider";
  static final String PAYOUT_WALLET_SCOPE_NETWORK = "network";
  static final String PAYOUT_WALLET_SCOPE_ANOTHER_PROVIDER = "another provider";

  // the provide modes of the c abi (URNET_PROVIDE_MODE_*)
  static final long PROVIDE_MODE_NONE = 0;
  static final long PROVIDE_MODE_NETWORK = 1;
  static final long PROVIDE_MODE_PUBLIC = 3;

  // the client limit status values of the c abi (URNET_CLIENT_LIMIT_STATUS_*)
  static final String CLIENT_LIMIT_STATUS_NONE = "";
  static final String CLIENT_LIMIT_STATUS_EXCEEDED = "client_limit_exceeded";

  // the consent_scope values of GET /sn/wallet. Hotkey delegations come with
  // a later server and sdk change; the app only labels the entry.
  static final String SN_WALLET_CONSENT_SCOPE_HOTKEY = "hotkey";
  static final String SN_WALLET_CONSENT_SCOPE_NETWORK = "network";
  static final String SN_WALLET_CONSENT_SCOPE_PROVIDER = "provider";

  // distinct clients are counted up to this many; beyond it the count is a
  // lower bound, shown with a trailing "+"
  static final int CLIENTS_SERVED_LIMIT = 100 * 1000;

  // the client limit status when there is none, or when it cannot be read
  static final ClientLimit NO_CLIENT_LIMIT =
      new ClientLimit(CLIENT_LIMIT_STATUS_NONE, 0);

  private static final String ZERO_ID = "00000000-0000-0000-0000-000000000000";
  private static final String[] BYTE_UNITS = {"KiB", "MiB", "GiB",
                                               "TiB", "PiB", "EiB"};
  private static final long MINUTE_MILLIS = 60 * 1000;
  private static final long DAY_MINUTES = 24 * 60;

  /**
   * The status line, for example "status: providing | clients served: 3 | data provided: 12.4 MiB
   * | payout wallet: 5Grw... (network)".
   */
  String line() {
    String payoutWalletText = payoutWallet;
    if (!payoutWalletScope.isEmpty()) {
      payoutWalletText = payoutWallet + " (" + payoutWalletScope + ")";
    }
    return String.format(
        Locale.ROOT,
        "status: %s | clients served: %d%s | data provided: %s | payout wallet: %s",
        statusText(state, clientLimitRetryTime), clientsServed,
        clientsServedAtLimit ? "+" : "",
        formatByteCount(dataProvidedByteCount), payoutWalletText);
  }

  /**
   * The fields that change rarely. A change prints a status line at once; the data counter alone
   * only prints on the periodic line. The status text carries the client limit retry time, so a
   * new retry time prints too.
   */
  String key() {
    return String.format(Locale.ROOT, "%s|%d|%b|%s|%s",
                         statusText(state, clientLimitRetryTime),
                         clientsServed, clientsServedAtLimit, payoutWallet,
                         payoutWalletScope);
  }

  /**
   * The status field text: the state, and for the client limit state the time the sdk retries, for
   * example "client limit, retry at 19:05 UTC". The retry time (unix milliseconds) is rounded up to
   * the next whole minute in UTC, so the shown time is never before the real retry; a retry time of
   * 0 shows "client limit".
   */
  static String statusText(String state, long clientLimitRetryTime) {
    if (!STATE_CLIENT_LIMIT.equals(state) || clientLimitRetryTime <= 0) {
      return state;
    }
    // whole minutes since the epoch, rounded up without overflow
    long retryMinute = (clientLimitRetryTime - 1) / MINUTE_MILLIS + 1;
    long minuteOfDay = retryMinute % DAY_MINUTES;
    return String.format(Locale.ROOT, "%s, retry at %02d:%02d UTC",
                         STATE_CLIENT_LIMIT, minuteOfDay / 60,
                         minuteOfDay % 60);
  }

  /**
   * Binary units with one decimal: "0 B", "1023 B", "1.0 KiB", "12.4 MiB". A value that rounds to
   * 1024.0 moves to the next unit. The tenth is the nearest one with ties to even, from the exact
   * binary value, as Go's %.1f rounds it: 1280 bytes (1.25 KiB) shows "1.2 KiB" and 1792 bytes
   * "1.8 KiB". String.format rounds ties up, so BigDecimal does the rounding. The unit check
   * follows the Go reference: double division, rounded half up.
   */
  static String formatByteCount(long byteCount) {
    if (byteCount < 1024) {
      return byteCount + " B";
    }
    double value = (double)byteCount / 1024;
    int unitIndex = 0;
    while (unitIndex < BYTE_UNITS.length - 1 &&
           1024 <= Math.round(value * 10) / 10.0) {
      value /= 1024;
      unitIndex += 1;
    }
    return new BigDecimal(value)
        .setScale(1, RoundingMode.HALF_EVEN)
        .toPlainString() +
        " " + BYTE_UNITS[unitIndex];
  }

  /**
   * The providing state from the device getters, in this order: stopped unless the provide mode is
   * public; client limit while the sdk holds this client off for its network's client limit;
   * paused while paused; providing once the provider is enabled and its platform carrier is
   * connected; starting otherwise.
   */
  static String providerState(long provideMode, String clientLimitStatus,
                              boolean providePaused, boolean provideEnabled,
                              boolean providerConnected) {
    if (provideMode != PROVIDE_MODE_PUBLIC) {
      return STATE_STOPPED;
    }
    if (CLIENT_LIMIT_STATUS_EXCEEDED.equals(clientLimitStatus)) {
      return STATE_CLIENT_LIMIT;
    }
    if (providePaused) {
      return STATE_PAUSED;
    }
    if (provideEnabled && providerConnected) {
      return STATE_PROVIDING;
    }
    return STATE_STARTING;
  }

  /**
   * Which owner the effective payout wallet belongs to. The consent scope comes first: a hotkey
   * delegation is network-level, with no client id, but is not the network's wallet. Otherwise by
   * the wallet's client id: this provider's own mapping, the network's wallet, or another provider
   * of the network.
   */
  static String payoutWalletScope(String walletConsentScope,
                                  String walletClientId, String clientId) {
    if (SN_WALLET_CONSENT_SCOPE_HOTKEY.equals(walletConsentScope)) {
      return PAYOUT_WALLET_SCOPE_HOTKEY;
    }
    if (walletClientId == null || walletClientId.isEmpty()) {
      return PAYOUT_WALLET_SCOPE_NETWORK;
    }
    if (walletClientId.equals(clientId)) {
      return PAYOUT_WALLET_SCOPE_PROVIDER;
    }
    return PAYOUT_WALLET_SCOPE_ANOTHER_PROVIDER;
  }

  /**
   * The peer of one provider contract, from the c abi's contract details JSON, by direction as the
   * SDK's contract screens resolve it: the source of a receive (ingress) contract, the destination
   * of a send (egress) contract. A path without that client id is keyed by its stream id, then by
   * the contract id. "" when the details name none of them or do not parse.
   */
  static String contractPeerKey(String contractDetailsJson, boolean receive) {
    try {
      Map<String, Object> details = Json.parseNullableObject(contractDetailsJson);
      if (details == null) {
        return "";
      }
      Map<String, Object> path = Json.object(details, "ContractTransferPath");
      if (path != null) {
        String peerId =
            presentId(Json.string(path, receive ? "SourceId" : "DestinationId"));
        if (peerId != null) {
          return peerId;
        }
        String streamId = presentId(Json.string(path, "StreamId"));
        if (streamId != null) {
          return "stream:" + streamId;
        }
      }
      String contractId = presentId(Json.string(details, "ContractId"));
      if (contractId != null) {
        return "contract:" + contractId;
      }
      return "";
    } catch (Json.ParseException e) {
      // the sdk writes these details; a value that does not parse counts no peer
      return "";
    }
  }

  /** The canonical form of an id; null for a missing, malformed or all-zero id. */
  private static String presentId(String id) {
    if (id == null) {
      return null;
    }
    String canonicalId = InstallationState.parseId(id);
    if (canonicalId == null || ZERO_ID.equals(canonicalId)) {
      return null;
    }
    return canonicalId;
  }

  /**
   * One client limit readout: status is CLIENT_LIMIT_STATUS_NONE or CLIENT_LIMIT_STATUS_EXCEEDED,
   * retryTime the end of the hold in unix milliseconds, 0 when there is none.
   */
  record ClientLimit(String status, long retryTime) {}

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
   * RemoteEgressByteCount + RemoteIngressByteCount of the c abi's provider packet stats JSON: bytes
   * relayed for clients in both directions since the device started; 0 when the stats are null.
   */
  static long dataProvidedByteCount(String packetStatsJson) {
    try {
      Map<String, Object> members = Json.parseNullableObject(packetStatsJson);
      if (members == null) {
        return 0;
      }
      return Json.longValue(members, "RemoteEgressByteCount", 0) +
          Json.longValue(members, "RemoteIngressByteCount", 0);
    } catch (Json.ParseException e) {
      return 0;
    }
  }

  /** The fields of an SnWallet that the app shows; "" for an absent field. */
  record SnWallet(String coldkeySs58, String clientId, String consentScope) {}

  /** The c abi's SnWallet JSON (urnet_device_local_get_sn_wallet); null for NULL or null. */
  static SnWallet parseSnWallet(String snWalletJson) throws Json.ParseException {
    Map<String, Object> members = Json.parseNullableObject(snWalletJson);
    if (members == null) {
      return null;
    }
    return new SnWallet(orEmpty(Json.string(members, "coldkey_ss58")),
                        orEmpty(Json.string(members, "client_id")),
                        orEmpty(Json.string(members, "consent_scope")));
  }

  /**
   * Whether a wallet read succeeded, from the two values of the c abi's wallet callback: no error,
   * and a result (an SnGetWalletResult JSON) without an error of its own.
   */
  static boolean walletReadSucceeded(String resultJson, String error) {
    if (error != null) {
      return false;
    }
    try {
      Map<String, Object> result = Json.parseNullableObject(resultJson);
      return result != null && Json.object(result, "error") == null;
    } catch (Json.ParseException e) {
      return false;
    }
  }

  /** The payout wallet field: an address with its scope, or one of the PAYOUT_WALLET texts with scope "". */
  record PayoutWallet(String wallet, String scope) {}

  /** The payout wallet before the first wallet read finishes. */
  static final PayoutWallet PAYOUT_WALLET_BEFORE_READ =
      new PayoutWallet(PAYOUT_WALLET_CHECKING, "");

  /**
   * The payout wallet after one wallet read: a failed first read shows unavailable and a later
   * failure keeps the last value; a read without a mapped wallet shows not set; otherwise the
   * effective wallet's coldkey with its scope for this client.
   */
  static PayoutWallet payoutWalletAfterRead(PayoutWallet current,
                                            boolean readSucceeded,
                                            SnWallet effectiveWallet,
                                            String clientId) {
    if (!readSucceeded) {
      if (PAYOUT_WALLET_CHECKING.equals(current.wallet())) {
        return new PayoutWallet(PAYOUT_WALLET_UNAVAILABLE, "");
      }
      return current;
    }
    if (effectiveWallet == null || effectiveWallet.coldkeySs58().isEmpty()) {
      return new PayoutWallet(PAYOUT_WALLET_NOT_SET, "");
    }
    return new PayoutWallet(
        effectiveWallet.coldkeySs58(),
        payoutWalletScope(effectiveWallet.consentScope(),
                          effectiveWallet.clientId(), clientId));
  }

  /** "" for null. */
  private static String orEmpty(String value) { return value == null ? "" : value; }

  /**
   * The distinct clients that held a contract with this provider since the app started. Safe for
   * concurrent use: the sdk delivers contract details on its own threads.
   */
  static final class ClientsServed {
    /** The distinct count, and whether the count stopped at the limit. */
    record Count(int count, boolean atLimit) {}

    private final int limit;
    // guards peerKeys and atLimit
    private final Object stateLock = new Object();
    // contractPeerKey values
    private final Set<String> peerKeys = new HashSet<>();
    private boolean atLimit;

    /** An empty count that keeps at most limit distinct peers. */
    ClientsServed(int limit) { this.limit = limit; }

    /** Counts the peer of one provider contract; "" counts nothing. */
    void add(String peerKey) {
      if (peerKey == null || peerKey.isEmpty()) {
        return;
      }
      synchronized (stateLock) {
        if (peerKeys.contains(peerKey)) {
          return;
        }
        if (limit <= peerKeys.size()) {
          atLimit = true;
          return;
        }
        peerKeys.add(peerKey);
      }
    }

    /** The current count. */
    Count count() {
      synchronized (stateLock) {
        return new Count(peerKeys.size(), atLimit);
      }
    }
  }
}
