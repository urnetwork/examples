// The credential-free self-test (PROVIDER_CONTRACT.md, "Self-test"). It checks
// the disclaimer, the status text, the providing state, the clients-served
// count, the SDK's JSON values and the installation state files without a
// network, credentials, a device or the native SDK runtime: nothing it runs
// calls into URnetwork.SDK, so it passes even where the SDK library is
// missing. The data is synthetic.
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

/// Runs every check; a failed check throws with its reason.
internal static class SelfTest {
  // sha-256 of the consent disclaimer (utf-8, lf line breaks, no trailing
  // newline), published in PROVIDER_CONTRACT.md for every example to check
  private const string ConsentDisclaimerSha256 =
      "83edee1e45cccd5deb6b86755cc5f6a91b6ade26e7eefc1e7670b5833a95502c";

  // the public Substrate development account, used only as test data
  private const string WalletA = "5GrwvaEF5zXb26Fz9rcQpDWS57CtERHpNehXCPcNoHGKutQY";

  private const string ProviderId = "11111111-1111-1111-1111-111111111111";
  private const string ClientAId = "22222222-2222-2222-2222-222222222222";
  private const string ClientBId = "33333333-3333-3333-3333-333333333333";
  private const string StreamId = "44444444-4444-4444-4444-444444444444";

  private const UnixFileMode PrivateFileMode = UnixFileMode.UserRead | UnixFileMode.UserWrite;
  private const UnixFileMode PrivateDirMode = PrivateFileMode | UnixFileMode.UserExecute;
  private const UnixFileMode SharedReadMode = UnixFileMode.GroupRead | UnixFileMode.OtherRead;

  /// Runs every check in order and stops at the first failure.
  public static void Run() {
    Action[] checks = [
      CheckConsentDisclaimer,
      CheckFormatByteCount,
      CheckStatusText,
      CheckStatusLines,
      CheckStatusKey,
      CheckProviderState,
      CheckPayoutWalletScope,
      CheckSdkJson,
      CheckClientsServed,
      CheckClientJwtClaims,
      CheckStateFiles,
      CheckProviderConfig,
      CheckUsageExitCode,
    ];
    foreach (Action check in checks) {
      check();
    }
  }

  /// Fails with reason unless condition holds.
  private static void Expect(bool condition, string reason) {
    if (!condition) {
      throw new Exception(reason);
    }
  }

  /// Fails with reason unless action throws T. Another exception fails with
  /// its own message.
  private static void ExpectThrows<T>(Action action, string reason)
      where T : Exception {
    try {
      action();
    } catch (T) {
      return;
    }
    throw new Exception(reason);
  }

  /// The disclaimer is the contract's exact text.
  private static void CheckConsentDisclaimer() {
    string digest = Convert.ToHexString(
        SHA256.HashData(Encoding.UTF8.GetBytes(StatusRules.ConsentDisclaimer)));
    Expect(digest.ToLowerInvariant() == ConsentDisclaimerSha256,
           "consent disclaimer differs from PROVIDER_CONTRACT.md");
  }

  /// Byte counts use binary units with one decimal.
  private static void CheckFormatByteCount() {
    (long ByteCount, string Text)[] cases = [
      (ByteCount: 0, Text: "0 B"),
      (ByteCount: 1023, Text: "1023 B"),
      (ByteCount: 1024, Text: "1.0 KiB"),
      (ByteCount: 1536, Text: "1.5 KiB"),
      (ByteCount: 1048575, Text: "1.0 MiB"),
      (ByteCount: 13002342, Text: "12.4 MiB"),
      (ByteCount: 5L * 1024 * 1024 * 1024, Text: "5.0 GiB"),
      (ByteCount: 3L * 1024 * 1024 * 1024 * 1024, Text: "3.0 TiB"),
      // 1.25 KiB is a tie: it rounds to even, as the Go reference prints it
      (ByteCount: 1280, Text: "1.2 KiB"),
    ];
    foreach (var c in cases) {
      string text = StatusRules.FormatByteCount(c.ByteCount);
      Expect(text == c.Text, $"byte count {c.ByteCount} formats as \"{text}\", want \"{c.Text}\"");
    }
  }

  /// The client limit status names the SDK's retry time in UTC, rounded up to
  /// the next whole minute; other states never show it.
  private static void CheckStatusText() {
    (string State, long ClientLimitRetryTime, string Text)[] cases = [
      // 2026-10-06 19:05:00.000 UTC, exactly on a minute
      (State: StatusRules.StateClientLimit, ClientLimitRetryTime: 1791313500000,
       Text: "client limit, retry at 19:05 UTC"),
      // 19:04:00.001 rounds up, so the shown time is never before the retry
      (State: StatusRules.StateClientLimit, ClientLimitRetryTime: 1791313440001,
       Text: "client limit, retry at 19:05 UTC"),
      // no retry time
      (State: StatusRules.StateClientLimit, ClientLimitRetryTime: 0, Text: "client limit"),
      // 23:59:00.001 rolls over the hour and the day
      (State: StatusRules.StateClientLimit, ClientLimitRetryTime: 1791331140001,
       Text: "client limit, retry at 00:00 UTC"),
      (State: StatusRules.StateStarting, ClientLimitRetryTime: 1791313500000, Text: "starting"),
    ];
    foreach (var c in cases) {
      string text = StatusRules.StatusText(c.State, c.ClientLimitRetryTime);
      Expect(text == c.Text,
             $"status text \"{text}\" for \"{c.State}\" retrying at {c.ClientLimitRetryTime}, want \"{c.Text}\"");
    }
  }

  /// The status line matches the contract's golden lines.
  private static void CheckStatusLines() {
    (ProviderStatus Status, string Line)[] cases = [
      (Status: new ProviderStatus {
         State = StatusRules.StateStarting,
         PayoutWallet = StatusRules.PayoutWalletChecking,
       },
       Line: "status: starting | clients served: 0 | data provided: 0 B | payout wallet: checking"),
      (Status: new ProviderStatus {
         State = StatusRules.StateProviding,
         ClientsServed = 3,
         DataProvidedByteCount = 13002342,
         PayoutWallet = WalletA,
         PayoutWalletScope = StatusRules.PayoutWalletScopeNetwork,
       },
       Line: $"status: providing | clients served: 3 | data provided: 12.4 MiB | payout wallet: {WalletA} (network)"),
      (Status: new ProviderStatus {
         State = StatusRules.StateProviding,
         ClientsServed = 3,
         DataProvidedByteCount = 13002342,
         PayoutWallet = WalletA,
         PayoutWalletScope = StatusRules.PayoutWalletScopeHotkey,
       },
       Line: $"status: providing | clients served: 3 | data provided: 12.4 MiB | payout wallet: {WalletA} (hotkey)"),
      (Status: new ProviderStatus {
         State = StatusRules.StatePaused,
         ClientsServed = StatusRules.ClientsServedLimit,
         ClientsServedAtLimit = true,
         DataProvidedByteCount = 1536,
         PayoutWallet = WalletA,
         PayoutWalletScope = StatusRules.PayoutWalletScopeProvider,
       },
       Line: $"status: paused | clients served: 100000+ | data provided: 1.5 KiB | payout wallet: {WalletA} (this provider)"),
      (Status: new ProviderStatus {
         State = StatusRules.StateStopped,
         PayoutWallet = StatusRules.PayoutWalletNotSet,
       },
       Line: "status: stopped | clients served: 0 | data provided: 0 B | payout wallet: not set"),
      (Status: new ProviderStatus {
         State = StatusRules.StateClientLimit,
         ClientLimitRetryTime = 1791313500000,
         PayoutWallet = WalletA,
         PayoutWalletScope = StatusRules.PayoutWalletScopeNetwork,
       },
       Line: $"status: client limit, retry at 19:05 UTC | clients served: 0 | data provided: 0 B | payout wallet: {WalletA} (network)"),
    ];
    foreach (var c in cases) {
      string line = c.Status.ToString();
      Expect(line == c.Line, $"status line \"{line}\", want \"{c.Line}\"");
    }
  }

  /// A change of the status text prints a line at once, including a new client
  /// limit retry time; the data counter alone does not.
  private static void CheckStatusKey() {
    var status = new ProviderStatus {
      State = StatusRules.StateClientLimit,
      ClientLimitRetryTime = 1791313500000,
      PayoutWallet = StatusRules.PayoutWalletChecking,
    };
    // 19:25 UTC
    ProviderStatus retried = status with { ClientLimitRetryTime = 1791314700000 };
    Expect(status.Key() != retried.Key(),
           "a new client limit retry time does not print a status line");
    ProviderStatus counted = status with { DataProvidedByteCount = 1536 };
    Expect(status.Key() == counted.Key(), "the data counter alone prints a status line");
  }

  /// The providing state follows the provide mode, client limit, pause, enable
  /// and connected rules, in that order.
  private static void CheckProviderState() {
    (long ProvideMode, string ClientLimitStatus, bool ProvidePaused, bool ProvideEnabled,
     bool ProviderConnected, string State)[] cases = [
      (ProvideMode: StatusRules.ProvideModeNone, ClientLimitStatus: "", ProvidePaused: false,
       ProvideEnabled: false, ProviderConnected: false, State: StatusRules.StateStopped),
      (ProvideMode: StatusRules.ProvideModeNetwork, ClientLimitStatus: "", ProvidePaused: false,
       ProvideEnabled: true, ProviderConnected: true, State: StatusRules.StateStopped),
      (ProvideMode: StatusRules.ProvideModePublic, ClientLimitStatus: "", ProvidePaused: false,
       ProvideEnabled: true, ProviderConnected: false, State: StatusRules.StateStarting),
      (ProvideMode: StatusRules.ProvideModePublic, ClientLimitStatus: "", ProvidePaused: false,
       ProvideEnabled: false, ProviderConnected: true, State: StatusRules.StateStarting),
      (ProvideMode: StatusRules.ProvideModePublic, ClientLimitStatus: "", ProvidePaused: false,
       ProvideEnabled: true, ProviderConnected: true, State: StatusRules.StateProviding),
      (ProvideMode: StatusRules.ProvideModePublic, ClientLimitStatus: "", ProvidePaused: true,
       ProvideEnabled: true, ProviderConnected: true, State: StatusRules.StatePaused),
      // the client limit comes after stopped and before every other state
      (ProvideMode: StatusRules.ProvideModeNetwork,
       ClientLimitStatus: StatusRules.ClientLimitStatusExceeded, ProvidePaused: false,
       ProvideEnabled: true, ProviderConnected: true, State: StatusRules.StateStopped),
      (ProvideMode: StatusRules.ProvideModePublic,
       ClientLimitStatus: StatusRules.ClientLimitStatusExceeded, ProvidePaused: true,
       ProvideEnabled: true, ProviderConnected: true, State: StatusRules.StateClientLimit),
      (ProvideMode: StatusRules.ProvideModePublic,
       ClientLimitStatus: StatusRules.ClientLimitStatusExceeded, ProvidePaused: false,
       ProvideEnabled: false, ProviderConnected: false, State: StatusRules.StateClientLimit),
      (ProvideMode: StatusRules.ProvideModePublic,
       ClientLimitStatus: StatusRules.ClientLimitStatusNone, ProvidePaused: true,
       ProvideEnabled: true, ProviderConnected: true, State: StatusRules.StatePaused),
    ];
    foreach (var c in cases) {
      string state = StatusRules.ProviderState(c.ProvideMode, c.ClientLimitStatus,
                                               c.ProvidePaused, c.ProvideEnabled,
                                               c.ProviderConnected);
      Expect(state == c.State, $"provider state \"{state}\" for {c}");
    }
  }

  /// The payout wallet is labeled by its consent scope first, then by the
  /// owner of its mapping.
  private static void CheckPayoutWalletScope() {
    (string WalletConsentScope, string WalletClientId, string Scope)[] cases = [
      // a hotkey delegation is network-level but not the network's wallet
      (WalletConsentScope: StatusRules.SnWalletConsentScopeHotkey, WalletClientId: "",
       Scope: StatusRules.PayoutWalletScopeHotkey),
      (WalletConsentScope: StatusRules.SnWalletConsentScopeHotkey, WalletClientId: ProviderId,
       Scope: StatusRules.PayoutWalletScopeHotkey),
      (WalletConsentScope: StatusRules.SnWalletConsentScopeNetwork, WalletClientId: "",
       Scope: StatusRules.PayoutWalletScopeNetwork),
      (WalletConsentScope: "", WalletClientId: "", Scope: StatusRules.PayoutWalletScopeNetwork),
      (WalletConsentScope: StatusRules.SnWalletConsentScopeProvider, WalletClientId: ProviderId,
       Scope: StatusRules.PayoutWalletScopeProvider),
      (WalletConsentScope: "", WalletClientId: ProviderId,
       Scope: StatusRules.PayoutWalletScopeProvider),
      (WalletConsentScope: StatusRules.SnWalletConsentScopeProvider, WalletClientId: ClientAId,
       Scope: StatusRules.PayoutWalletScopeAnotherProvider),
    ];
    foreach (var c in cases) {
      string scope = StatusRules.PayoutWalletScope(c.WalletConsentScope, c.WalletClientId,
                                                   ProviderId);
      Expect(scope == c.Scope,
             $"payout wallet scope \"{scope}\" for consent scope \"{c.WalletConsentScope}\" and client \"{c.WalletClientId}\", want \"{c.Scope}\"");
    }
  }

  /// The status reads the SDK's C ABI JSON: NULL and JSON that does not parse
  /// read as nothing, never as an error.
  private static void CheckSdkJson() {
    var clientLimit = StatusRules.ParseClientLimitStatus(
        """{"Status":"client_limit_exceeded","RetryTime":1791313500000}""");
    Expect(clientLimit == (StatusRules.ClientLimitStatusExceeded, 1791313500000),
           $"client limit status {clientLimit}");
    Expect(StatusRules.ParseClientLimitStatus("""{"Status":"","RetryTime":0}""") ==
               (StatusRules.ClientLimitStatusNone, 0) &&
               StatusRules.ParseClientLimitStatus(null) == (StatusRules.ClientLimitStatusNone, 0) &&
               StatusRules.ParseClientLimitStatus("{") == (StatusRules.ClientLimitStatusNone, 0),
           "a missing client limit status is not read as no limit");

    long dataProvidedByteCount = StatusRules.DataProvidedByteCount(
        """{"RemoteEgressPacketCount":1,"RemoteEgressByteCount":5,"RemoteIngressPacketCount":1,"RemoteIngressByteCount":7,"LocalEgressByteCount":100,"TransportStats":null}""");
    Expect(dataProvidedByteCount == 12, $"data provided {dataProvidedByteCount}, want 12");
    Expect(StatusRules.DataProvidedByteCount(null) == 0 &&
               StatusRules.DataProvidedByteCount("null") == 0,
           "null provider packet stats are not 0 bytes");

    (string? WalletJson, string PayoutWallet, string PayoutWalletScope)[] walletCases = [
      (WalletJson: $$"""{"coldkey_ss58":"{{WalletA}}","set_at_millis":1,"consent_scope":"network"}""",
       PayoutWallet: WalletA, PayoutWalletScope: StatusRules.PayoutWalletScopeNetwork),
      (WalletJson: $$"""{"coldkey_ss58":"{{WalletA}}","client_id":"{{ProviderId}}","set_at_millis":1,"consent_scope":"provider"}""",
       PayoutWallet: WalletA, PayoutWalletScope: StatusRules.PayoutWalletScopeProvider),
      (WalletJson: $$"""{"coldkey_ss58":"{{WalletA}}","set_at_millis":1,"consent_scope":"hotkey"}""",
       PayoutWallet: WalletA, PayoutWalletScope: StatusRules.PayoutWalletScopeHotkey),
      (WalletJson: """{"coldkey_ss58":"","set_at_millis":0}""",
       PayoutWallet: StatusRules.PayoutWalletNotSet, PayoutWalletScope: ""),
      (WalletJson: null, PayoutWallet: StatusRules.PayoutWalletNotSet, PayoutWalletScope: ""),
    ];
    foreach (var c in walletCases) {
      var wallet = StatusRules.ParsePayoutWallet(c.WalletJson, ProviderId);
      Expect(wallet == (c.PayoutWallet, c.PayoutWalletScope),
             $"payout wallet {wallet} for {c.WalletJson ?? "NULL"}");
    }

    (string? ResultJson, string? ErrorText, bool Succeeded)[] readCases = [
      (ResultJson: $$$"""{"wallet":{"coldkey_ss58":"{{{WalletA}}}","set_at_millis":1}}""",
       ErrorText: null, Succeeded: true),
      (ResultJson: """{"error":{"message":"synthetic refusal"}}""", ErrorText: null,
       Succeeded: false),
      (ResultJson: "null", ErrorText: null, Succeeded: false),
      (ResultJson: null, ErrorText: null, Succeeded: false),
      (ResultJson: "{}", ErrorText: "synthetic timeout", Succeeded: false),
    ];
    foreach (var c in readCases) {
      Expect(StatusRules.WalletReadSucceeded(c.ResultJson, c.ErrorText) == c.Succeeded,
             $"wallet read {c.ResultJson ?? "NULL"} with error {c.ErrorText ?? "NULL"} is not {(c.Succeeded ? "a success" : "a failure")}");
    }
  }

  /// Provider contract details as the C ABI listeners deliver them: the Go
  /// field names, ids as UUID text or null.
  private static string ContractDetailsJson(string contractId, string? sourceId,
                                            string? destinationId,
                                            string? streamId) {
    return JsonSerializer.Serialize(new {
      ContractId = contractId,
      ContractUsedByteCount = 0,
      ContractByteCount = 0,
      ContractBitRate = 0,
      ContractTransferPath = new {
        SourceId = sourceId,
        DestinationId = destinationId,
        StreamId = streamId,
      },
      Status = "open",
    });
  }

  /// Contract peers resolve by direction and count once per client, up to the
  /// limit.
  private static void CheckClientsServed() {
    // the peer is the source of a receive contract and the destination of a
    // send contract
    (string ContractDetailsJson, bool Receive, string PeerKey)[] keyCases = [
      (ContractDetailsJson: ContractDetailsJson(
           "55555555-5555-5555-5555-555555555555", ClientAId, ProviderId, null),
       Receive: true, PeerKey: ClientAId),
      (ContractDetailsJson: ContractDetailsJson(
           "66666666-6666-6666-6666-666666666666", ProviderId, ClientAId, null),
       Receive: false, PeerKey: ClientAId),
      (ContractDetailsJson: ContractDetailsJson(
           "77777777-7777-7777-7777-777777777777", Uuid.Zero, ProviderId, StreamId),
       Receive: true, PeerKey: "stream:" + StreamId),
      (ContractDetailsJson: ContractDetailsJson(
           "88888888-8888-8888-8888-888888888888", null, null, null),
       Receive: true, PeerKey: "contract:88888888-8888-8888-8888-888888888888"),
      // no path at all
      (ContractDetailsJson:
           """{"ContractId":"88888888-8888-8888-8888-888888888888","ContractTransferPath":null,"Status":"open"}""",
       Receive: true, PeerKey: "contract:88888888-8888-8888-8888-888888888888"),
    ];
    foreach (var c in keyCases) {
      string peerKey = StatusRules.ContractPeerKey(c.ContractDetailsJson, c.Receive);
      Expect(peerKey == c.PeerKey, $"contract peer key \"{peerKey}\", want \"{c.PeerKey}\"");
    }

    // both directions of one client count once
    var served = new ServedClients(limit: 2);
    served.Add(ContractDetailsJson("55555555-5555-5555-5555-555555555555", ClientAId,
                                   ProviderId, null), receive: true);
    served.Add(ContractDetailsJson("66666666-6666-6666-6666-666666666666", ProviderId,
                                   ClientAId, null), receive: false);
    served.Add(ContractDetailsJson("99999999-9999-9999-9999-999999999999", ClientAId,
                                   ProviderId, null), receive: true);
    var count = served.Count();
    Expect(count == (1, false), $"one client counted as {count}");
    served.Add(ContractDetailsJson("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", ClientBId,
                                   ProviderId, null), receive: true);
    count = served.Count();
    Expect(count == (2, false), $"two clients counted as {count}");
    // a third distinct peer reaches the limit of 2
    served.Add(ContractDetailsJson("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb", Uuid.Zero,
                                   ProviderId, StreamId), receive: true);
    count = served.Count();
    Expect(count == (2, true), $"limited count {count}");
    // details without any id, or that do not parse, count nothing
    served.Add("""{"ContractTransferPath":null}""", receive: true);
    served.Add("{", receive: true);
    served.Add(null, receive: false);
    Expect(served.Count() == (2, true), "contract details without a peer were counted");
  }

  /// A synthetic, unsigned JWT with the given payload JSON.
  private static string SelfTestJwt(string payloadJson) {
    string payload = Convert.ToBase64String(Encoding.UTF8.GetBytes(payloadJson))
                         .TrimEnd('=')
                         .Replace('+', '-')
                         .Replace('/', '_');
    return "e30." + payload + ".test";
  }

  /// Only a JWT with a valid client_id claim is a client credential.
  private static void CheckClientJwtClaims() {
    string clientId = StateFiles.ParseClientJwtClientId(SelfTestJwt(
        $$"""{"client_id":"{{ProviderId}}","network_id":"{{ClientAId}}"}"""));
    Expect(clientId == ProviderId, $"client jwt claim \"{clientId}\"");
    // the claim is kept in the SDK's canonical form
    clientId = StateFiles.ParseClientJwtClientId(
        SelfTestJwt("""{"client_id":"AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA"}"""));
    Expect(clientId == "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", $"client jwt claim \"{clientId}\"");
    string[] invalidJwts = [
      "",
      "not-a-jwt",
      SelfTestJwt($$"""{"network_id":"{{ClientAId}}"}"""),
      SelfTestJwt("""{"client_id":"not-a-uuid"}"""),
      SelfTestJwt("""{"client_id":7}"""),
      "e30.%%%.test",
    ];
    foreach (string invalidJwt in invalidJwts) {
      ExpectThrows<ProviderConfigException>(() => StateFiles.ParseClientJwtClientId(invalidJwt),
                                            $"invalid client jwt \"{invalidJwt}\" accepted");
    }
  }

  /// State files are private, replaced atomically, created once and bound to
  /// their client.
  private static void CheckStateFiles() {
    string stateDir = Directory.CreateTempSubdirectory("ur-provider-self-test-").FullName;
    try {
      // an atomic private write
      string path = Path.Combine(stateDir, StateFiles.ClientJwtFileName);
      StateFiles.WritePrivateFile(path, "first\n"u8.ToArray());
      StateFiles.WritePrivateFile(path, "second\n"u8.ToArray());
      byte[]? data = StateFiles.ReadPrivateFile(path);
      Expect(data != null && data.AsSpan().SequenceEqual("second\n"u8),
             "private file round trip failed");
      Expect(Directory.GetFiles(stateDir).Length == 1,
             "a replaced state file left a temporary file behind");
      Expect(StateFiles.ReadPrivateFile(Path.Combine(stateDir, "missing")) == null,
             "a missing state file is not read as missing");
      if (!OperatingSystem.IsWindows()) {
        UnixFileMode mode = File.GetUnixFileMode(path);
        Expect(mode == PrivateFileMode, $"private file mode {mode}");
        // a file or directory that others can read is refused
        File.SetUnixFileMode(path, PrivateFileMode | SharedReadMode);
        ExpectThrows<IOException>(() => StateFiles.ReadPrivateFile(path),
                                  "a group-readable credential file was accepted");
        File.SetUnixFileMode(path, PrivateFileMode);
        // a symlinked file is refused, so the credential cannot be redirected
        string linkPath = Path.Combine(stateDir, "linked.jwt");
        File.CreateSymbolicLink(linkPath, path);
        ExpectThrows<IOException>(() => StateFiles.ReadPrivateFile(linkPath),
                                  "a symlinked state file was accepted");
        File.Delete(linkPath);
        File.SetUnixFileMode(stateDir, PrivateDirMode | SharedReadMode);
        ExpectThrows<ProviderConfigException>(() => StateFiles.CheckStateDir(stateDir),
                                              "a group-readable state directory was accepted");
        File.SetUnixFileMode(stateDir, PrivateDirMode);
      }
      StateFiles.CheckStateDir(stateDir);

      // the instance id is created once and reused
      string instanceId = StateFiles.LoadOrCreateInstanceId(stateDir);
      Expect(Uuid.Parse(instanceId) == instanceId, $"instance id \"{instanceId}\" is not a uuid");
      string again = StateFiles.LoadOrCreateInstanceId(stateDir);
      Expect(again == instanceId, $"instance id changed from \"{instanceId}\" to \"{again}\"");

      // the identity belongs to its client
      byte[] clientKeySeed = Enumerable.Repeat((byte)1, 32).ToArray();
      byte[] certificatePem = "synthetic certificate"u8.ToArray();
      byte[] privateKeyPem = "synthetic private key"u8.ToArray();
      StateFiles.SaveProviderIdentity(stateDir, new ProviderIdentity {
        Version = ProviderIdentity.CurrentVersion,
        ClientId = ProviderId,
        ClientKeySeed = clientKeySeed,
        ProvideTlsCertificatePem = certificatePem,
        ProvideTlsPrivateKeyPem = privateKeyPem,
      });
      ProviderIdentity? loaded = StateFiles.LoadProviderIdentity(stateDir, ProviderId);
      Expect(loaded != null && (loaded.ClientKeySeed ?? []).SequenceEqual(clientKeySeed) &&
                 (loaded.ProvideTlsCertificatePem ?? []).SequenceEqual(certificatePem) &&
                 (loaded.ProvideTlsPrivateKeyPem ?? []).SequenceEqual(privateKeyPem) &&
                 loaded.ExtenderKeySeed == null,
             "identity round trip failed");
      ProviderIdentity? other = StateFiles.LoadProviderIdentity(stateDir, ClientAId);
      Expect(other == null, "another client's identity was used");
      // without an identity the device gets no key material and makes a new identity
      Expect(ProviderSession.NewKeyMaterial(other) == 0, "a first run passes key material");

      string identityPath = Path.Combine(stateDir, StateFiles.IdentityFileName);
      string[] invalidIdentities = [
        """{"version":1}""",
        $$"""{"version":2,"client_id":"{{ProviderId}}","client_key_seed":"AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE="}""",
        $$"""{"version":1,"client_id":"{{ProviderId}}","client_key_seed":"AQEB"}""",
        $$"""{"version":1,"client_id":"{{ProviderId}}","client_key_seed":"not base64"}""",
        "not json",
      ];
      foreach (string invalidIdentity in invalidIdentities) {
        StateFiles.WritePrivateFile(identityPath, Encoding.UTF8.GetBytes(invalidIdentity));
        ExpectThrows<ProviderConfigException>(
            () => StateFiles.LoadProviderIdentity(stateDir, ProviderId),
            $"an invalid identity was accepted: {invalidIdentity}");
      }
    } finally {
      Directory.Delete(stateDir, recursive: true);
    }
  }

  /// A missing or incomplete installation state is refused; a first run loads.
  private static void CheckProviderConfig() {
    ExpectThrows<ProviderConfigException>(() => ProviderConfig.Load(null),
                                          "a missing state directory was accepted");
    ExpectThrows<ProviderConfigException>(() => ProviderConfig.Load(""),
                                          "an empty state directory was accepted");
    ExpectThrows<ProviderConfigException>(() => ProviderConfig.Load("relative/state"),
                                          "a relative state directory was accepted");
    string stateDir = Directory.CreateTempSubdirectory("ur-provider-self-test-").FullName;
    try {
      ExpectThrows<ProviderConfigException>(() => ProviderConfig.Load(stateDir),
                                            "a state directory without client.jwt was accepted");
      // a network jwt has no client_id claim
      string clientJwtPath = Path.Combine(stateDir, StateFiles.ClientJwtFileName);
      string networkJwt = SelfTestJwt($$"""{"network_id":"{{ClientAId}}"}""");
      StateFiles.WritePrivateFile(clientJwtPath, Encoding.UTF8.GetBytes(networkJwt + "\n"));
      ExpectThrows<ProviderConfigException>(() => ProviderConfig.Load(stateDir),
                                            "a network jwt was accepted");
      string clientJwt = SelfTestJwt($$"""{"client_id":"{{ProviderId}}"}""");
      StateFiles.WritePrivateFile(clientJwtPath, Encoding.UTF8.GetBytes(clientJwt + "\r\n"));
      ProviderConfig config = ProviderConfig.Load(stateDir);
      Expect(config.StateDir == stateDir && config.ClientJwt == clientJwt &&
                 config.ClientId == ProviderId && Uuid.Parse(config.InstanceId) != null &&
                 config.Identity == null,
             "first-run configuration differs");
    } finally {
      Directory.Delete(stateDir, recursive: true);
    }
  }

  /// A usage error is a configuration error that a restart does not fix.
  private static void CheckUsageExitCode() {
    int code = Program.Run(["--unknown"], TextWriter.Null, TextWriter.Null);
    Expect(code == Program.ExitConfig, $"usage error exit code {code}, want {Program.ExitConfig}");
  }
}
