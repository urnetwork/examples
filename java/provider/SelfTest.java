// The credential-free self-test (PROVIDER_CONTRACT.md, "Self-test"). It
// checks the disclaimer, the status text, the providing state, the payout
// wallet labels, the clients-served count, the JSON values of the C ABI, the
// installation state files and the exit codes without a network, credentials,
// a device or the native SDK runtime: it never calls the SDK, so the native
// library never loads. `--self-test` runs every check and stops at the first
// failure; `mvn package` runs it too.
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** The self-test checks. */
final class SelfTest {
  // sha-256 of the consent disclaimer (utf-8, lf line breaks, no trailing
  // newline), published in PROVIDER_CONTRACT.md for every example to check
  private static final String CONSENT_DISCLAIMER_SHA256 =
      "83edee1e45cccd5deb6b86755cc5f6a91b6ade26e7eefc1e7670b5833a95502c";

  // the public Substrate development account, used only as test data
  private static final String WALLET_A =
      "5GrwvaEF5zXb26Fz9rcQpDWS57CtERHpNehXCPcNoHGKutQY";
  // synthetic ids: the provider, two clients, a stream and contracts
  private static final String PROVIDER_ID = "11111111-1111-1111-1111-111111111111";
  private static final String CLIENT_A_ID = "22222222-2222-2222-2222-222222222222";
  private static final String CLIENT_B_ID = "33333333-3333-3333-3333-333333333333";
  private static final String STREAM_ID = "44444444-4444-4444-4444-444444444444";
  private static final String ZERO_ID = "00000000-0000-0000-0000-000000000000";

  /** A failed check. */
  static final class Failure extends Exception {
    private static final long serialVersionUID = 1;

    /** A failure with the check's message. */
    Failure(String message) { super(message); }
  }

  /** One check; a failure throws. */
  @FunctionalInterface
  private interface Check {
    /** Runs the check. */
    void run() throws Exception;
  }

  /** A call that must fail with an exception. */
  @FunctionalInterface
  private interface Refused {
    /** Runs the call. */
    void run() throws Exception;
  }

  /** Static members only. */
  private SelfTest() {}

  /** Runs every check and throws the first failure. */
  static void run() throws Exception {
    List<Check> checks = List.of(
        SelfTest::checkConsentDisclaimer, SelfTest::checkFormatByteCount,
        SelfTest::checkStatusText, SelfTest::checkStatusLines,
        SelfTest::checkStatusKey, SelfTest::checkProviderState,
        SelfTest::checkPayoutWalletScope, SelfTest::checkPayoutWalletReads,
        SelfTest::checkClientsServed, SelfTest::checkSdkValues,
        SelfTest::checkJson, SelfTest::checkClientJwtClaims,
        SelfTest::checkStateFiles, SelfTest::checkProviderConfig,
        SelfTest::checkStopBeforeStart, SelfTest::checkStopHook,
        SelfTest::checkExitCodes);
    for (Check check : checks) {
      check.run();
    }
  }

  /** The disclaimer is the contract's exact text. */
  private static void checkConsentDisclaimer() throws Exception {
    byte[] digest = MessageDigest.getInstance("SHA-256").digest(
        ProviderStatus.CONSENT_DISCLAIMER.getBytes(StandardCharsets.UTF_8));
    expect(HexFormat.of().formatHex(digest).equals(CONSENT_DISCLAIMER_SHA256),
           "consent disclaimer differs from PROVIDER_CONTRACT.md");
  }

  /** Byte counts use binary units with one decimal, rounded as the Go reference rounds. */
  private static void checkFormatByteCount() throws Failure {
    Object[][] cases = {
        {0L, "0 B"},
        {1023L, "1023 B"},
        {1024L, "1.0 KiB"},
        {1536L, "1.5 KiB"},
        {1048575L, "1.0 MiB"},
        {13002342L, "12.4 MiB"},
        {5L * 1024 * 1024 * 1024, "5.0 GiB"},
        {3L * 1024 * 1024 * 1024 * 1024, "3.0 TiB"},
        // exact halves round to the even tenth, as the contract and Go's
        // %.1f do: 1.25 KiB and 1.75 KiB
        {1280L, "1.2 KiB"},
        {1792L, "1.8 KiB"},
        {Long.MAX_VALUE, "8.0 EiB"},
    };
    for (Object[] c : cases) {
      String text = ProviderStatus.formatByteCount((Long)c[0]);
      expect(text.equals(c[1]), "byte count %s formats as \"%s\", want \"%s\""
                                    .formatted(c[0], text, c[1]));
    }
  }

  /**
   * The client limit status names the sdk's retry time in UTC, rounded up to the next whole
   * minute; other states never show it.
   */
  private static void checkStatusText() throws Failure {
    Object[][] cases = {
        // 2026-10-06 19:05:00.000 UTC, exactly on a minute
        {ProviderStatus.STATE_CLIENT_LIMIT, 1791313500000L,
         "client limit, retry at 19:05 UTC"},
        // 19:04:00.001 rounds up, so the shown time is never before the retry
        {ProviderStatus.STATE_CLIENT_LIMIT, 1791313440001L,
         "client limit, retry at 19:05 UTC"},
        // no retry time
        {ProviderStatus.STATE_CLIENT_LIMIT, 0L, "client limit"},
        // 23:59:00.001 rolls over the hour and the day
        {ProviderStatus.STATE_CLIENT_LIMIT, 1791331140001L,
         "client limit, retry at 00:00 UTC"},
        {ProviderStatus.STATE_STARTING, 1791313500000L, "starting"},
    };
    for (Object[] c : cases) {
      String text = ProviderStatus.statusText((String)c[0], (Long)c[1]);
      expect(text.equals(c[2]),
             "status text \"%s\" for \"%s\" retrying at %s, want \"%s\""
                 .formatted(text, c[0], c[1], c[2]));
    }
  }

  /** The status line matches the contract's golden lines. */
  private static void checkStatusLines() throws Failure {
    Object[][] cases = {
        {new ProviderStatus(ProviderStatus.STATE_STARTING, 0, 0, false, 0,
                            ProviderStatus.PAYOUT_WALLET_CHECKING, ""),
         "status: starting | clients served: 0 | data provided: 0 B | payout wallet: checking"},
        {new ProviderStatus(ProviderStatus.STATE_PROVIDING, 0, 3, false,
                            13002342, WALLET_A,
                            ProviderStatus.PAYOUT_WALLET_SCOPE_NETWORK),
         "status: providing | clients served: 3 | data provided: 12.4 MiB | payout wallet: %s (network)"
             .formatted(WALLET_A)},
        {new ProviderStatus(ProviderStatus.STATE_PROVIDING, 0, 3, false,
                            13002342, WALLET_A,
                            ProviderStatus.PAYOUT_WALLET_SCOPE_HOTKEY),
         "status: providing | clients served: 3 | data provided: 12.4 MiB | payout wallet: %s (hotkey)"
             .formatted(WALLET_A)},
        {new ProviderStatus(ProviderStatus.STATE_PAUSED, 0,
                            ProviderStatus.CLIENTS_SERVED_LIMIT, true, 1536,
                            WALLET_A,
                            ProviderStatus.PAYOUT_WALLET_SCOPE_PROVIDER),
         "status: paused | clients served: 100000+ | data provided: 1.5 KiB | payout wallet: %s (this provider)"
             .formatted(WALLET_A)},
        {new ProviderStatus(ProviderStatus.STATE_STOPPED, 0, 0, false, 0,
                            ProviderStatus.PAYOUT_WALLET_NOT_SET, ""),
         "status: stopped | clients served: 0 | data provided: 0 B | payout wallet: not set"},
        {new ProviderStatus(ProviderStatus.STATE_CLIENT_LIMIT, 1791313500000L,
                            0, false, 0, WALLET_A,
                            ProviderStatus.PAYOUT_WALLET_SCOPE_NETWORK),
         "status: client limit, retry at 19:05 UTC | clients served: 0 | data provided: 0 B | payout wallet: %s (network)"
             .formatted(WALLET_A)},
    };
    for (Object[] c : cases) {
      String line = ((ProviderStatus)c[0]).line();
      expect(line.equals(c[1]),
             "status line \"%s\", want \"%s\"".formatted(line, c[1]));
    }
  }

  /**
   * A change of the status text prints a line at once, including a new client limit retry time;
   * the data counter alone does not.
   */
  private static void checkStatusKey() throws Failure {
    ProviderStatus status = new ProviderStatus(
        ProviderStatus.STATE_CLIENT_LIMIT, 1791313500000L, 0, false, 0,
        ProviderStatus.PAYOUT_WALLET_CHECKING, "");
    // 19:25 UTC
    ProviderStatus retried = new ProviderStatus(
        ProviderStatus.STATE_CLIENT_LIMIT, 1791314700000L, 0, false, 0,
        ProviderStatus.PAYOUT_WALLET_CHECKING, "");
    expect(!status.key().equals(retried.key()),
           "a new client limit retry time does not print a status line");
    ProviderStatus counted = new ProviderStatus(
        ProviderStatus.STATE_CLIENT_LIMIT, 1791313500000L, 0, false, 1536,
        ProviderStatus.PAYOUT_WALLET_CHECKING, "");
    expect(status.key().equals(counted.key()),
           "the data counter alone prints a status line");
  }

  /** The providing state follows the provide mode, client limit, pause, enable and connected rules, in that order. */
  private static void checkProviderState() throws Failure {
    String none = ProviderStatus.CLIENT_LIMIT_STATUS_NONE;
    String exceeded = ProviderStatus.CLIENT_LIMIT_STATUS_EXCEEDED;
    // provide mode, client limit status, paused, enabled, connected, state
    Object[][] cases = {
        {ProviderStatus.PROVIDE_MODE_NONE, none, false, false, false,
         ProviderStatus.STATE_STOPPED},
        {ProviderStatus.PROVIDE_MODE_NETWORK, none, false, true, true,
         ProviderStatus.STATE_STOPPED},
        {ProviderStatus.PROVIDE_MODE_PUBLIC, none, false, true, false,
         ProviderStatus.STATE_STARTING},
        {ProviderStatus.PROVIDE_MODE_PUBLIC, none, false, false, true,
         ProviderStatus.STATE_STARTING},
        {ProviderStatus.PROVIDE_MODE_PUBLIC, none, false, true, true,
         ProviderStatus.STATE_PROVIDING},
        {ProviderStatus.PROVIDE_MODE_PUBLIC, none, true, true, true,
         ProviderStatus.STATE_PAUSED},
        // the client limit comes after stopped and before every other state
        {ProviderStatus.PROVIDE_MODE_NETWORK, exceeded, false, true, true,
         ProviderStatus.STATE_STOPPED},
        {ProviderStatus.PROVIDE_MODE_PUBLIC, exceeded, true, true, true,
         ProviderStatus.STATE_CLIENT_LIMIT},
        {ProviderStatus.PROVIDE_MODE_PUBLIC, exceeded, false, false, false,
         ProviderStatus.STATE_CLIENT_LIMIT},
        {ProviderStatus.PROVIDE_MODE_PUBLIC, none, true, true, true,
         ProviderStatus.STATE_PAUSED},
    };
    for (Object[] c : cases) {
      String state = ProviderStatus.providerState(
          (Long)c[0], (String)c[1], (Boolean)c[2], (Boolean)c[3], (Boolean)c[4]);
      expect(state.equals(c[5]), "provider state \"" + state + "\" for " +
                                     Arrays.toString(c));
    }
  }

  /** The payout wallet is labeled by its consent scope first, then by the owner of its mapping. */
  private static void checkPayoutWalletScope() throws Failure {
    // wallet consent scope, wallet client id, label
    Object[][] cases = {
        // a hotkey delegation is network-level but not the network's wallet
        {ProviderStatus.SN_WALLET_CONSENT_SCOPE_HOTKEY, "",
         ProviderStatus.PAYOUT_WALLET_SCOPE_HOTKEY},
        {ProviderStatus.SN_WALLET_CONSENT_SCOPE_HOTKEY, PROVIDER_ID,
         ProviderStatus.PAYOUT_WALLET_SCOPE_HOTKEY},
        {ProviderStatus.SN_WALLET_CONSENT_SCOPE_NETWORK, "",
         ProviderStatus.PAYOUT_WALLET_SCOPE_NETWORK},
        {"", "", ProviderStatus.PAYOUT_WALLET_SCOPE_NETWORK},
        {ProviderStatus.SN_WALLET_CONSENT_SCOPE_PROVIDER, PROVIDER_ID,
         ProviderStatus.PAYOUT_WALLET_SCOPE_PROVIDER},
        {"", PROVIDER_ID, ProviderStatus.PAYOUT_WALLET_SCOPE_PROVIDER},
        {ProviderStatus.SN_WALLET_CONSENT_SCOPE_PROVIDER, CLIENT_A_ID,
         ProviderStatus.PAYOUT_WALLET_SCOPE_ANOTHER_PROVIDER},
    };
    for (Object[] c : cases) {
      String scope = ProviderStatus.payoutWalletScope((String)c[0],
                                                      (String)c[1], PROVIDER_ID);
      expect(scope.equals(c[2]),
             "payout wallet scope \"%s\" for consent scope \"%s\" and client \"%s\", want \"%s\""
                 .formatted(scope, c[0], c[1], c[2]));
    }
  }

  /**
   * The payout wallet shows checking until the first read, unavailable after a failed first read,
   * the last value after a later failure, and not set without a mapped wallet.
   */
  private static void checkPayoutWalletReads() throws Failure {
    ProviderStatus.PayoutWallet checking = ProviderStatus.PAYOUT_WALLET_BEFORE_READ;
    expect(checking.wallet().equals(ProviderStatus.PAYOUT_WALLET_CHECKING),
           "the payout wallet does not start as checking");
    ProviderStatus.PayoutWallet unavailable =
        ProviderStatus.payoutWalletAfterRead(checking, false, null, PROVIDER_ID);
    expect(unavailable.equals(new ProviderStatus.PayoutWallet(
               ProviderStatus.PAYOUT_WALLET_UNAVAILABLE, "")),
           "a failed first wallet read shows " + unavailable);
    ProviderStatus.PayoutWallet network = ProviderStatus.payoutWalletAfterRead(
        unavailable, true,
        new ProviderStatus.SnWallet(WALLET_A, "",
                                    ProviderStatus.SN_WALLET_CONSENT_SCOPE_NETWORK),
        PROVIDER_ID);
    expect(network.equals(new ProviderStatus.PayoutWallet(
               WALLET_A, ProviderStatus.PAYOUT_WALLET_SCOPE_NETWORK)),
           "a network wallet shows " + network);
    ProviderStatus.PayoutWallet kept =
        ProviderStatus.payoutWalletAfterRead(network, false, null, PROVIDER_ID);
    expect(kept.equals(network), "a later failed read shows " + kept);
    ProviderStatus.PayoutWallet notSet =
        ProviderStatus.payoutWalletAfterRead(network, true, null, PROVIDER_ID);
    expect(notSet.equals(new ProviderStatus.PayoutWallet(
               ProviderStatus.PAYOUT_WALLET_NOT_SET, "")),
           "a read without a wallet shows " + notSet);
  }

  /** Contract peers resolve by direction from the c abi's JSON and count once per client, up to the limit. */
  private static void checkClientsServed() throws Failure {
    // the peer is the source of a receive contract and the destination of a send contract
    Object[][] keyCases = {
        {contractJson("55555555-5555-5555-5555-555555555555", CLIENT_A_ID,
                      PROVIDER_ID, null),
         true, CLIENT_A_ID},
        {contractJson("66666666-6666-6666-6666-666666666666", PROVIDER_ID,
                      CLIENT_A_ID, null),
         false, CLIENT_A_ID},
        {contractJson("77777777-7777-7777-7777-777777777777", ZERO_ID,
                      PROVIDER_ID, STREAM_ID),
         true, "stream:" + STREAM_ID},
        {contractJson("88888888-8888-8888-8888-888888888888", null, null, null),
         true, "contract:88888888-8888-8888-8888-888888888888"},
        // nothing to key by
        {"null", true, ""},
        {"""
         {"ContractId":null,"ContractTransferPath":null,"Status":"open"}""",
         true, ""},
    };
    for (Object[] c : keyCases) {
      String peerKey = ProviderStatus.contractPeerKey((String)c[0], (Boolean)c[1]);
      expect(peerKey.equals(c[2]), "contract peer key \"%s\", want \"%s\" for %s"
                                       .formatted(peerKey, c[2], c[0]));
    }

    // both directions of one client count once
    ProviderStatus.ClientsServed served = new ProviderStatus.ClientsServed(2);
    served.add(ProviderStatus.contractPeerKey(
        contractJson("55555555-5555-5555-5555-555555555555", CLIENT_A_ID,
                     PROVIDER_ID, null),
        true));
    served.add(ProviderStatus.contractPeerKey(
        contractJson("66666666-6666-6666-6666-666666666666", PROVIDER_ID,
                     CLIENT_A_ID, null),
        false));
    served.add(ProviderStatus.contractPeerKey(
        contractJson("99999999-9999-9999-9999-999999999999", CLIENT_A_ID,
                     PROVIDER_ID, null),
        true));
    expect(served.count().equals(new ProviderStatus.ClientsServed.Count(1, false)),
           "one client counted as " + served.count());
    served.add(ProviderStatus.contractPeerKey(
        contractJson("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", CLIENT_B_ID,
                     PROVIDER_ID, null),
        true));
    expect(served.count().equals(new ProviderStatus.ClientsServed.Count(2, false)),
           "two clients counted as " + served.count());
    // a third distinct peer reaches the limit of 2
    served.add(ProviderStatus.contractPeerKey(
        contractJson("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb", ZERO_ID,
                     PROVIDER_ID, STREAM_ID),
        true));
    expect(served.count().equals(new ProviderStatus.ClientsServed.Count(2, true)),
           "limited count " + served.count());
  }

  /** The c abi's JSON values read as the contract's examples show them. */
  private static void checkSdkValues() throws Exception {
    ProviderStatus.ClientLimit clientLimit = ProviderStatus.parseClientLimit("""
        {"Status": "client_limit_exceeded", "RetryTime": 1791313500000}""");
    expect(clientLimit.equals(new ProviderStatus.ClientLimit(
               ProviderStatus.CLIENT_LIMIT_STATUS_EXCEEDED, 1791313500000L)),
           "the client limit status reads as " + clientLimit);
    expect(ProviderStatus.parseClientLimit("""
               {"Status": "", "RetryTime": 0}""")
               .equals(ProviderStatus.NO_CLIENT_LIMIT),
           "no client limit does not read as none");
    // NULL: the call could not run, read as no limit
    expect(ProviderStatus.parseClientLimit(null).equals(
               ProviderStatus.NO_CLIENT_LIMIT),
           "a NULL client limit status does not read as none");

    expect(ProviderStatus.dataProvidedByteCount("""
               {"RemoteEgressByteCount": 5, "RemoteIngressByteCount": 7, "LocalEgressByteCount": 11}""") == 12,
           "data provided is not the remote egress and ingress bytes");
    expect(ProviderStatus.dataProvidedByteCount(null) == 0 &&
               ProviderStatus.dataProvidedByteCount("null") == 0,
           "null packet stats are not 0 bytes");

    ProviderStatus.SnWallet wallet = ProviderStatus.parseSnWallet("""
        {"coldkey_ss58": "%s", "client_id": "%s", "set_at_millis": 1, "consent_scope": "provider"}"""
        .formatted(WALLET_A, PROVIDER_ID));
    expect(wallet != null &&
               wallet.equals(new ProviderStatus.SnWallet(
                   WALLET_A, PROVIDER_ID,
                   ProviderStatus.SN_WALLET_CONSENT_SCOPE_PROVIDER)),
           "the wallet does not read: " + wallet);
    ProviderStatus.SnWallet networkWallet = ProviderStatus.parseSnWallet("""
        {"coldkey_ss58": "%s", "set_at_millis": 1, "consent_scope": "network"}"""
        .formatted(WALLET_A));
    expect(networkWallet != null && networkWallet.clientId().isEmpty(),
           "a network wallet has a client id");
    expect(ProviderStatus.parseSnWallet(null) == null &&
               ProviderStatus.parseSnWallet("null") == null,
           "no wallet reads as a wallet");

    expect(ProviderStatus.walletReadSucceeded("""
               {"wallet": {"coldkey_ss58": "%s", "set_at_millis": 1}, "wallets": []}"""
                   .formatted(WALLET_A),
               null),
           "a wallet result reads as a failure");
    expect(ProviderStatus.walletReadSucceeded("{}", null),
           "an empty wallet result reads as a failure");
    expect(!ProviderStatus.walletReadSucceeded("""
               {"error": {"message": "synthetic failure"}}""", null),
           "a wallet result error reads as a success");
    expect(!ProviderStatus.walletReadSucceeded(null, "synthetic network error"),
           "a wallet callback error reads as a success");
    expect(!ProviderStatus.walletReadSucceeded("null", null),
           "a null wallet result reads as a success");
  }

  /** The JSON reader accepts JSON and only JSON, and the writer's output reads back. */
  private static void checkJson() throws Exception {
    Object value = Json.parse(
        " {\"a\": [1, -2.5e3, true, false, null, \"x\\u00e9\\n\\\"\\ud83d\\ude00\"], \"b\": {}} ");
    Map<String, Object> expected = new LinkedHashMap<>();
    expected.put("a", Arrays.asList(new BigDecimal("1"), new BigDecimal("-2.5e3"),
                                    true, false, null, "x\u00e9\n\"\ud83d\ude00"));
    expected.put("b", Map.of());
    expect(expected.equals(value), "json reads as " + value);
    expect(Json.parseObject("{\"a\":1,\"a\":2}").get("a").equals(new BigDecimal("2")),
           "a duplicate member does not keep its last value");
    expect(Json.parse("[".repeat(64) + "]".repeat(64)) != null,
           "64 nested arrays are refused");

    List<String> invalidTexts = List.of(
        "", " ", "{", "}", "{\"a\":}", "{\"a\":1,}", "[1,]", "[1 2]", "01",
        "1.", ".5", "-", "+1", "1e", "\"\\x\"", "\"unterminated",
        "\"tab\there\"", "{} x", "nul", "True", "{'a':1}", "{a:1}",
        "\"\\u12G4\"", "\"\\u\uff10\uff10\uff10\uff10\"", "\uff11",
        // an exponent beyond the range of BigDecimal's scale
        "1e2147483649", "[".repeat(65) + "]".repeat(65));
    for (String invalidText : invalidTexts) {
      expectRefused(() -> Json.parse(invalidText),
                    "invalid json accepted: " + invalidText);
    }

    Map<String, Object> members = new LinkedHashMap<>();
    members.put("text", "quote \" backslash \\ line\n tab\t control\u0001 \u00e9");
    members.put("count", 7);
    members.put("time", 1791313500000L);
    members.put("flag", true);
    members.put("missing", null);
    members.put("items", List.of(1, "x"));
    Map<String, Object> expectedMembers = new LinkedHashMap<>(members);
    expectedMembers.put("count", new BigDecimal(7));
    expectedMembers.put("time", new BigDecimal(1791313500000L));
    expectedMembers.put("items", List.of(new BigDecimal(1), "x"));
    expect(Json.parseObject(Json.write(members)).equals(expectedMembers),
           "written json reads back as " + Json.parseObject(Json.write(members)));
  }

  /** Only a JWT with a valid client_id claim is a client credential. */
  private static void checkClientJwtClaims() throws Exception {
    String clientId = InstallationState.parseClientJwtClientId(selfTestJwt("""
        {"client_id":"%s","network_id":"%s"}""".formatted(PROVIDER_ID, CLIENT_A_ID)));
    expect(clientId.equals(PROVIDER_ID), "client jwt claim " + clientId);
    // the claim is kept in canonical form
    String upperCaseClientId = InstallationState.parseClientJwtClientId(selfTestJwt("""
        {"client_id":"AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA"}"""));
    expect(upperCaseClientId.equals("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
           "client jwt claim " + upperCaseClientId);
    List<String> invalidJwts = List.of(
        "", "not-a-jwt", "e30.e30", "e30..test",
        // a network jwt has no client_id claim
        selfTestJwt("""
            {"network_id":"%s"}""".formatted(CLIENT_A_ID)),
        selfTestJwt("""
            {"client_id":"not-a-uuid"}"""),
        selfTestJwt("""
            {"client_id":11111111}"""),
        selfTestJwt("""
            ["%s"]""".formatted(PROVIDER_ID)),
        "e30.%%%.test");
    for (String invalidJwt : invalidJwts) {
      expectRefused(() -> InstallationState.parseClientJwtClientId(invalidJwt),
                    "invalid client jwt accepted: " + invalidJwt);
    }
  }

  /** State files are private, replaced atomically, created once and bound to their client. */
  private static void checkStateFiles() throws Exception {
    Path stateDir = newPrivateTempDir();
    try {
      boolean posix = InstallationState.hasPosixPermissions(stateDir);

      // an atomic private write leaves no temporary file behind
      Path path = stateDir.resolve(InstallationState.CLIENT_JWT_FILE_NAME);
      InstallationState.writePrivateFile(path, utf8("first\n"));
      InstallationState.writePrivateFile(path, utf8("second\n"));
      expect(Arrays.equals(InstallationState.readPrivateFile(path), utf8("second\n")),
             "private file round trip");
      expect(fileNames(stateDir).equals(List.of(InstallationState.CLIENT_JWT_FILE_NAME)),
             "the private write left " + fileNames(stateDir));
      if (posix) {
        expect(Files.getPosixFilePermissions(path).equals(
                   InstallationState.OWNER_READ_WRITE),
               "private file mode " + Files.getPosixFilePermissions(path));
        // a file or directory that others can read is refused
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-r--r--"));
        expectRefused(() -> InstallationState.readPrivateFile(path),
                      "a group-readable credential file was accepted");
        Files.setPosixFilePermissions(path, InstallationState.OWNER_READ_WRITE);
        // a symlinked file is refused, so the credential cannot be redirected
        Path linkPath = stateDir.resolve("linked.jwt");
        Files.createSymbolicLink(linkPath, path);
        expectRefused(() -> InstallationState.readPrivateFile(linkPath),
                      "a symlinked credential file was accepted");
        Files.delete(linkPath);
        Files.setPosixFilePermissions(stateDir,
                                      PosixFilePermissions.fromString("rwxr-xr-x"));
        expectRefused(() -> InstallationState.checkStateDir(stateDir.toString()),
                      "a group-readable state directory was accepted");
        Files.setPosixFilePermissions(stateDir, InstallationState.OWNER_ALL);
      }
      InstallationState.checkStateDir(stateDir.toString());

      // the instance id is created once and reused
      String instanceId = InstallationState.loadOrCreateInstanceId(stateDir);
      expect(InstallationState.parseId(instanceId) != null,
             "instance id " + instanceId + " is not a uuid");
      String again = InstallationState.loadOrCreateInstanceId(stateDir);
      expect(again.equals(instanceId),
             "instance id changed from " + instanceId + " to " + again);

      // the identity belongs to its client
      InstallationState.ProviderIdentity identity = new InstallationState.ProviderIdentity(
          PROVIDER_ID, filled(32, 1), utf8("synthetic certificate"),
          utf8("synthetic private key"), filled(32, 2));
      InstallationState.saveProviderIdentity(stateDir, identity);
      InstallationState.ProviderIdentity loaded =
          InstallationState.loadProviderIdentity(stateDir, PROVIDER_ID);
      expect(loaded != null && sameIdentity(loaded, identity),
             "identity round trip failed");
      // another client's identity is not used: the device gets no key
      // material and makes a new identity
      expect(InstallationState.loadProviderIdentity(stateDir, CLIENT_A_ID) == null,
             "another client's identity was used");
      // the extender seed is optional
      InstallationState.ProviderIdentity withoutExtender =
          new InstallationState.ProviderIdentity(
              PROVIDER_ID, filled(32, 1), utf8("synthetic certificate"),
              utf8("synthetic private key"), new byte[0]);
      InstallationState.saveProviderIdentity(stateDir, withoutExtender);
      loaded = InstallationState.loadProviderIdentity(stateDir, PROVIDER_ID);
      expect(loaded != null && sameIdentity(loaded, withoutExtender),
             "identity without an extender seed failed");
      String seed31 = Base64.getEncoder().encodeToString(filled(31, 1));
      String seed32 = Base64.getEncoder().encodeToString(filled(32, 1));
      String certificate = Base64.getEncoder().encodeToString(utf8("synthetic certificate"));
      // the go reference writes an empty byte field as null
      Path identityPath = stateDir.resolve(InstallationState.IDENTITY_FILE_NAME);
      InstallationState.writePrivateFile(identityPath, utf8("""
          {"version":1,"client_id":"%s","client_key_seed":"%s","provide_tls_certificate_pem":"%s","provide_tls_private_key_pem":null}"""
          .formatted(PROVIDER_ID, seed32, certificate)));
      loaded = InstallationState.loadProviderIdentity(stateDir, PROVIDER_ID);
      expect(loaded != null &&
                 sameIdentity(loaded, new InstallationState.ProviderIdentity(
                                          PROVIDER_ID, filled(32, 1),
                                          utf8("synthetic certificate"),
                                          new byte[0], new byte[0])),
             "an identity with a null field failed");

      List<String> invalidIdentities = List.of(
          """
          {"version":1}""",
          "not json",
          "[]",
          """
          {"version":2,"client_id":"%s","client_key_seed":"%s"}""".formatted(PROVIDER_ID, seed32),
          """
          {"version":1,"client_id":"%s","client_key_seed":"%s"}""".formatted(PROVIDER_ID, seed31),
          """
          {"version":1,"client_id":"%s","client_key_seed":"not base64"}""".formatted(PROVIDER_ID),
          """
          {"version":"1","client_id":"%s","client_key_seed":"%s"}""".formatted(PROVIDER_ID, seed32));
      for (String invalidIdentity : invalidIdentities) {
        InstallationState.writePrivateFile(identityPath, utf8(invalidIdentity));
        expectRefused(() -> InstallationState.loadProviderIdentity(stateDir, PROVIDER_ID),
                      "an invalid identity was accepted: " + invalidIdentity);
      }
    } finally {
      deleteRecursively(stateDir);
    }
  }

  /** A missing or incomplete installation state is refused; a first run loads. */
  private static void checkProviderConfig() throws Exception {
    expectRefused(() -> InstallationState.load(""),
                  "a missing state directory was accepted");
    expectRefused(() -> InstallationState.load(null),
                  "an unset state directory was accepted");
    expectRefused(() -> InstallationState.load("relative/state"),
                  "a relative state directory was accepted");
    Path stateDir = newPrivateTempDir();
    try {
      expectRefused(() -> InstallationState.load(stateDir.toString()),
                    "a state directory without client.jwt was accepted");
      // a network jwt has no client_id claim
      String networkJwt = selfTestJwt("""
          {"network_id":"%s"}""".formatted(CLIENT_A_ID));
      Path clientJwtPath = stateDir.resolve(InstallationState.CLIENT_JWT_FILE_NAME);
      InstallationState.writePrivateFile(clientJwtPath, utf8(networkJwt + "\n"));
      expectRefused(() -> InstallationState.load(stateDir.toString()),
                    "a network jwt was accepted");
      String clientJwt = selfTestJwt("""
          {"client_id":"%s"}""".formatted(PROVIDER_ID));
      InstallationState.writePrivateFile(clientJwtPath, utf8(clientJwt + "\n"));
      InstallationState.ProviderConfig config =
          InstallationState.load(stateDir.toString());
      expect(config.clientJwt().equals(clientJwt) &&
                 config.clientId().equals(PROVIDER_ID) &&
                 InstallationState.parseId(config.instanceId()) != null &&
                 config.identity() == null,
             "first-run configuration differs: " + config);
      expect(!config.toString().contains(clientJwt),
             "the configuration prints the credential");
    } finally {
      deleteRecursively(stateDir);
    }
  }

  /**
   * Ctrl-C or SIGTERM can come while the provider starts: the shutdown hook then requests a stop
   * from a session that has not started. The session keeps the request, so it never starts, and
   * closing a session that never started makes no sdk call and prints nothing. Creating a session
   * makes no sdk call either.
   */
  private static void checkStopBeforeStart() throws Failure {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    ProviderSession session = newUnstartedSession(output);
    expect(!session.stopRequested(), "a new session has a stop requested");
    session.requestStop();
    expect(session.stopRequested(), "a stop requested before start is not kept");
    session.close();
    expect(output.size() == 0, "closing a session that never started printed " +
                                   output.toString(StandardCharsets.UTF_8));
  }

  /**
   * The shutdown hook always ends the process with the decided exit code, never with the JVM's
   * signal status (130, 143): with main's code when main closed the session first, and with 0 when
   * a signal came first, also when main then fails while it starts. The halt is recorded here
   * instead of run.
   */
  private static void checkStopHook() throws Exception {
    // main closed the session first, here after the server rejected the credential
    ProviderSession mainFirst = newUnstartedSession(new ByteArrayOutputStream());
    List<Integer> mainFirstHalts = Collections.synchronizedList(new ArrayList<>());
    Main.StopHook mainFirstHook = new Main.StopHook(mainFirst, mainFirstHalts::add);
    mainFirstHook.closed(Main.EXIT_CONFIG);
    mainFirstHook.stop();
    expect(mainFirstHalts.equals(List.of(Main.EXIT_CONFIG)),
           "after main's exit with 78 the hook ends the process with " + mainFirstHalts);
    expect(!mainFirst.stopRequested(), "after main's exit the hook requested a stop");

    // a signal came first: the hook requests the stop and waits for main to close
    ProviderSession signalFirst = newUnstartedSession(new ByteArrayOutputStream());
    List<Integer> signalFirstHalts = Collections.synchronizedList(new ArrayList<>());
    Main.StopHook signalFirstHook = new Main.StopHook(signalFirst, signalFirstHalts::add);
    Thread hookThread = new Thread(signalFirstHook::stop, "self-test-stop-hook");
    hookThread.start();
    // the requested stop shows that the hook found no exit code from main
    long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
    while (!signalFirst.stopRequested()) {
      expect(System.nanoTime() - deadline < 0, "the hook did not request a stop");
      Thread.onSpinWait();
    }
    // main then fails while it starts, and closes
    signalFirstHook.closed(Main.EXIT_FAILURE);
    hookThread.join(Duration.ofSeconds(30).toMillis());
    expect(signalFirstHalts.equals(List.of(Main.EXIT_STOPPED)),
           "after a signal the hook ends the process with " + signalFirstHalts);
  }

  /**
   * A usage error and a configuration error exit with 78 before anything loads the native
   * runtime; the provider prints the disclaimer before it reads the state.
   */
  private static void checkExitCodes() throws Failure {
    PrintStream discard = new PrintStream(OutputStream.nullOutputStream());
    for (String[] args : List.of(new String[] {"--unknown"},
                                 new String[] {"run", "extra"},
                                 new String[] {"--self-test", "extra"})) {
      int exitCode = Main.run(args, null, discard, discard);
      expect(exitCode == Main.EXIT_CONFIG, "usage error " + Arrays.toString(args) +
                                               " exits with " + exitCode);
    }
    for (String stateDir : Arrays.asList(null, "", "relative/state")) {
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      int exitCode = Main.run(new String[] {"run"}, stateDir,
                              new PrintStream(output, true, StandardCharsets.UTF_8),
                              discard);
      expect(exitCode == Main.EXIT_CONFIG,
             "state directory \"" + stateDir + "\" exits with " + exitCode);
      expect(output.toString(StandardCharsets.UTF_8)
                 .startsWith(ProviderStatus.CONSENT_DISCLAIMER),
             "the provider does not print the disclaimer first");
    }
  }

  /** A session for a synthetic installation that has not started; it prints to output. */
  private static ProviderSession newUnstartedSession(ByteArrayOutputStream output) {
    InstallationState.ProviderConfig config = new InstallationState.ProviderConfig(
        Path.of("unused-state"), selfTestJwt("""
            {"client_id":"%s"}""".formatted(PROVIDER_ID)),
        PROVIDER_ID, "55555555-5555-5555-5555-555555555555", null);
    PrintStream printStream = new PrintStream(output, true, StandardCharsets.UTF_8);
    return new ProviderSession(config, printStream, printStream);
  }

  /** A synthetic, unsigned JWT with the given payload json. */
  private static String selfTestJwt(String payloadJson) {
    return "e30." +
        Base64.getUrlEncoder().withoutPadding().encodeToString(utf8(payloadJson)) +
        ".test";
  }

  /** The c abi's contract details JSON for one contract; a null id is JSON null. */
  private static String contractJson(String contractId, String sourceId,
                                     String destinationId, String streamId) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("ContractId", contractId);
    details.put("ContractUsedByteCount", 0);
    details.put("ContractByteCount", 0);
    details.put("ContractBitRate", 0);
    if (sourceId == null && destinationId == null && streamId == null) {
      details.put("ContractTransferPath", null);
    } else {
      Map<String, Object> path = new LinkedHashMap<>();
      path.put("SourceId", sourceId);
      path.put("DestinationId", destinationId);
      path.put("StreamId", streamId);
      details.put("ContractTransferPath", path);
    }
    details.put("Status", "open");
    return Json.write(details);
  }

  /** Whether two identities hold the same client and keys. */
  private static boolean sameIdentity(InstallationState.ProviderIdentity a,
                                      InstallationState.ProviderIdentity b) {
    return a.clientId().equals(b.clientId()) &&
        Arrays.equals(a.clientKeySeed(), b.clientKeySeed()) &&
        Arrays.equals(a.provideTlsCertificatePem(), b.provideTlsCertificatePem()) &&
        Arrays.equals(a.provideTlsPrivateKeyPem(), b.provideTlsPrivateKeyPem()) &&
        Arrays.equals(a.extenderKeySeed(), b.extenderKeySeed());
  }

  /** A new temporary directory, private to its owner where the file system has POSIX permissions. */
  private static Path newPrivateTempDir() throws IOException {
    Path dir = Files.createTempDirectory("ur-provider-self-test-");
    if (InstallationState.hasPosixPermissions(dir)) {
      Files.setPosixFilePermissions(dir, InstallationState.OWNER_ALL);
    }
    return dir.toRealPath();
  }

  /** The sorted names in a directory. */
  private static List<String> fileNames(Path dir) throws IOException {
    try (Stream<Path> paths = Files.list(dir)) {
      return paths.map(path -> path.getFileName().toString()).sorted().toList();
    }
  }

  /** Deletes a directory tree. */
  private static void deleteRecursively(Path dir) throws IOException {
    List<Path> paths;
    try (Stream<Path> walk = Files.walk(dir)) {
      paths = new ArrayList<>(walk.sorted(Comparator.reverseOrder()).toList());
    }
    for (Path path : paths) {
      Files.deleteIfExists(path);
    }
  }

  /** count bytes of one value. */
  private static byte[] filled(int count, int value) {
    byte[] bytes = new byte[count];
    Arrays.fill(bytes, (byte)value);
    return bytes;
  }

  /** The utf-8 bytes of text. */
  private static byte[] utf8(String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }

  /** Fails with message unless condition holds. */
  private static void expect(boolean condition, String message) throws Failure {
    if (!condition) {
      throw new Failure(message);
    }
  }

  /** Fails with message unless the call throws. */
  private static void expectRefused(Refused call, String message) throws Failure {
    try {
      call.run();
    } catch (Exception e) {
      return;
    }
    throw new Failure(message);
  }
}
