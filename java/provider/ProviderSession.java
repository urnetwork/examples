// A running provider over the SDK's C ABI, through the desktop JVM binding
// io.ur.sdk (the JNA interface Raw and the Sdk handle wrappers): the network
// space manager, the provider device with the installation's identity, and the
// listeners that feed its status. SDK callbacks run on SDK threads; they only
// copy what they carry, into the clients-served count or onto the event queue
// that the status loop drains on the app's own thread, which makes every other
// SDK call.
import com.sun.jna.Callback;
import com.sun.jna.Memory;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import io.ur.sdk.Raw;
import io.ur.sdk.Sdk;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * One run of the provider. The constructor makes no sdk call; start creates the device and starts
 * providing; run shows the status until a stop; close stops providing. requestStop is safe from any
 * thread, at any time; the other methods belong to the thread that created the session.
 */
final class ProviderSession {
  // how often the status is read, and the longest gap between status lines
  private static final Duration STATUS_POLL_INTERVAL = Duration.ofSeconds(1);
  private static final Duration STATUS_REPEAT_INTERVAL = Duration.ofSeconds(60);
  // how often the payout wallet is read again. The wallet is fixed; a reread
  // shows a mapping that the backend completes while the provider runs.
  private static final Duration WALLET_SYNC_INTERVAL = Duration.ofMinutes(10);

  // the device description and spec recorded for this installation's device
  static final String DEVICE_DESCRIPTION = "Java provider example";
  static final String DEVICE_SPEC = "urnetwork-examples/java-provider";
  private static final String APP_VERSION = "1";

  // the provider extender role's two device settings (PROVIDER_CONTRACT.md,
  // "Extender role"), passed explicitly and both on. PROVIDE_EXTENDER_ENABLED
  // is the embedder's hard switch: false means the role never runs.
  // DEFAULT_PROVIDE_EXTENDER is the setting the device uses until the user sets
  // one: false turns the default off.
  private static final boolean PROVIDE_EXTENDER_ENABLED = true;
  private static final boolean DEFAULT_PROVIDE_EXTENDER = true;

  // the ur.network main network space, as the integration helpers create it
  private static final String NETWORK_SPACE_KEY_JSON =
      "{\"host_name\":\"ur.network\",\"env_name\":\"main\"}";
  private static final String NETWORK_SPACE_VALUES_JSON =
      "{\"migration_host_name\":\"bringyour.com\"}";

  // callback objects stay reachable for the life of the process: jna holds
  // them weakly, and the sdk can deliver a wallet result after the device
  // closes
  private static final List<Callback> CALLBACK_ROOTS =
      Collections.synchronizedList(new ArrayList<>());

  /** Work that the sdk callbacks hand to the status loop. */
  private sealed interface Event {}

  /** A refreshed client credential to save. */
  private record JwtRefreshed(String clientJwt) implements Event {}

  /** The two values of one wallet read callback. */
  private record WalletRead(String resultJson, String error) implements Event {}

  /** Ends the status loop. */
  private enum Signal implements Event { STOP, AUTH_LOGOUT }

  /** One of the c abi's buffer-out getters. */
  @FunctionalInterface
  private interface BufferGetter {
    /** Sets inoutLength to the needed size; copies and returns nonzero when out has room. */
    byte get(Pointer out, IntByReference inoutLength);
  }

  private final InstallationState.ProviderConfig config;
  private final PrintStream out;
  private final PrintStream err;
  private final ProviderStatus.ClientsServed clientsServed =
      new ProviderStatus.ClientsServed(ProviderStatus.CLIENTS_SERVED_LIMIT);
  private final LinkedBlockingQueue<Event> events = new LinkedBlockingQueue<>();
  private final Raw.urnet_jwt_refresh_cb jwtRefreshCallback;
  private final Raw.urnet_auth_logout_cb authLogoutCallback;
  private final Raw.urnet_contract_details_change_cb ingressContractCallback;
  private final Raw.urnet_contract_details_change_cb egressContractCallback;
  private final Raw.urnet_sn_get_wallet_cb walletReadCallback;
  private final List<Long> subs = new ArrayList<>();
  // set by requestStop, from any thread
  private volatile boolean stopRequested;
  private boolean started;
  private Sdk.Handle manager;
  private Sdk.Handle space;
  private Sdk.Handle api;
  private Sdk.Device device;
  // owned by the status loop's thread
  private ProviderStatus.PayoutWallet payoutWallet =
      ProviderStatus.PAYOUT_WALLET_BEFORE_READ;

  /** A session that has not opened anything yet. */
  ProviderSession(InstallationState.ProviderConfig config, PrintStream out,
                  PrintStream err) {
    this.config = config;
    this.out = out;
    this.err = err;
    jwtRefreshCallback =
        (userData, clientJwt) -> events.add(new JwtRefreshed(clientJwt));
    authLogoutCallback = userData -> events.add(Signal.AUTH_LOGOUT);
    ingressContractCallback = (userData, contractDetailsJson) ->
        clientsServed.add(ProviderStatus.contractPeerKey(contractDetailsJson, true));
    egressContractCallback = (userData, contractDetailsJson) ->
        clientsServed.add(ProviderStatus.contractPeerKey(contractDetailsJson, false));
    walletReadCallback = (userData, resultJson, error) ->
        events.add(new WalletRead(resultJson, error));
    CALLBACK_ROOTS.addAll(List.of(jwtRefreshCallback, authLogoutCallback,
                                  ingressContractCallback,
                                  egressContractCallback, walletReadCallback));
  }

  /** The SDK version of the native runtime, which this call loads. */
  static String sdkVersion() { return Sdk.version(); }

  /**
   * Keeps the SDK's log files in logDir, which the SDK bounds (16 MiB files, the newest four kept
   * at each start), instead of the system temp directory. The SDK also copies its log lines to
   * stderr; this binding keeps that copy.
   */
  static void configureSdkLogs(Path logDir) throws IOException {
    InstallationState.createPrivateDirectory(logDir);
    PointerByReference error = new PointerByReference();
    boolean set = Sdk.raw.urnet_set_log_dir(logDir.toString(), error) != 0;
    String message = Sdk.takeString(error.getValue());
    if (!set) {
      throw new IOException(message != null
                                ? message
                                : "the sdk did not accept the log directory");
    }
  }

  /**
   * Creates the provider device with the installation's identity and the extender role's settings
   * on, saves a new identity on first run, and starts providing publicly. The sdk declares provide
   * intent on the device's platform connections by itself while the provide mode is public. A
   * failure releases what was opened.
   */
  void start() throws IOException {
    try {
      open();
    } catch (IOException | RuntimeException | LinkageError e) {
      // a LinkageError is a native runtime older than this jar, without a
      // function that it calls
      try {
        release();
      } catch (RuntimeException | LinkageError releaseError) {
        e.addSuppressed(releaseError);
      }
      throw e;
    }
    started = true;
  }

  /** Opens the manager, the space and the device, adds the listeners and starts providing. */
  private void open() throws IOException {
    Raw raw = Sdk.raw;
    manager = new Sdk.Handle(requireHandle(
        raw.urnet_new_network_space_manager_no_storage(),
        "network space manager"));
    space = new Sdk.Handle(requireHandle(
        raw.urnet_network_space_manager_update_network_space_values(
            manager.handle(), NETWORK_SPACE_KEY_JSON,
            NETWORK_SPACE_VALUES_JSON),
        "network space"));
    api = new Sdk.Handle(
        requireHandle(raw.urnet_network_space_get_api(space.handle()), "api"));
    raw.urnet_api_set_by_jwt(api.handle(), config.clientJwt());
    device = newDevice();
    if (config.identity() == null) {
      saveNewIdentity();
    }
    long deviceHandle = device.handle();
    subs.add(requireHandle(raw.urnet_device_add_jwt_refresh_listener(
                               deviceHandle, jwtRefreshCallback, null),
                           "jwt refresh listener"));
    subs.add(requireHandle(raw.urnet_device_add_auth_logout_listener(
                               deviceHandle, authLogoutCallback, null),
                           "auth logout listener"));
    subs.add(requireHandle(
        raw.urnet_device_add_provider_ingress_contract_details_change_listener(
            deviceHandle, ingressContractCallback, null),
        "provider ingress contract listener"));
    subs.add(requireHandle(
        raw.urnet_device_add_provider_egress_contract_details_change_listener(
            deviceHandle, egressContractCallback, null),
        "provider egress contract listener"));
    raw.urnet_device_set_provide_mode(deviceHandle,
                                      ProviderStatus.PROVIDE_MODE_PUBLIC);
    syncWallet();
  }

  /**
   * The device, created with the identity's key material or, on first run, with none: one call
   * for both runs. Without key material the device makes a new identity, which the app saves.
   */
  private Sdk.Device newDevice() throws IOException {
    Raw raw = Sdk.raw;
    long keyMaterial =
        config.identity() == null ? 0 : newKeyMaterial(config.identity());
    PointerByReference error = new PointerByReference();
    long deviceHandle;
    try {
      deviceHandle = raw.urnet_new_device_local_with_provide_extender(
          space.handle(), config.clientJwt(), DEVICE_DESCRIPTION, DEVICE_SPEC,
          APP_VERSION, config.instanceId(), cBool(false), keyMaterial,
          cBool(PROVIDE_EXTENDER_ENABLED), cBool(DEFAULT_PROVIDE_EXTENDER),
          error);
    } finally {
      // the device keeps its own copy of the key material
      if (keyMaterial != 0) {
        raw.urnet_release(keyMaterial);
      }
    }
    String message = Sdk.takeString(error.getValue());
    if (deviceHandle == 0) {
      throw new IOException(message != null ? message
                                            : "the sdk did not create the device");
    }
    return new Sdk.Device(deviceHandle);
  }

  /** A key material handle with the identity's keys; the caller releases it. */
  private static long newKeyMaterial(InstallationState.ProviderIdentity identity) {
    Raw raw = Sdk.raw;
    byte[] clientKeySeed = identity.clientKeySeed();
    byte[] certificatePem = identity.provideTlsCertificatePem();
    byte[] privateKeyPem = identity.provideTlsPrivateKeyPem();
    try (Memory clientKeySeedMemory = memory(clientKeySeed);
         Memory certificatePemMemory = memory(certificatePem);
         Memory privateKeyPemMemory = memory(privateKeyPem)) {
      long keyMaterial = raw.urnet_new_device_local_key_material(
          clientKeySeedMemory, clientKeySeed.length, certificatePemMemory,
          certificatePem.length, privateKeyPemMemory, privateKeyPem.length);
      if (keyMaterial == 0) {
        throw new IllegalStateException("the sdk did not create the key material");
      }
      byte[] extenderKeySeed = identity.extenderKeySeed();
      if (0 < extenderKeySeed.length) {
        try (Memory extenderKeySeedMemory = memory(extenderKeySeed)) {
          raw.urnet_device_local_key_material_set_extender_key_seed(
              keyMaterial, extenderKeySeedMemory, extenderKeySeed.length);
        }
      }
      return keyMaterial;
    }
  }

  /** Saves the identity that the device made on first run, so later starts present the same provider. */
  private void saveNewIdentity() throws IOException {
    Raw raw = Sdk.raw;
    long deviceHandle = device.handle();
    InstallationState.ProviderIdentity identity =
        new InstallationState.ProviderIdentity(
            config.clientId(),
            readBuffer((buffer, length) ->
                           raw.urnet_device_local_get_client_key_seed(
                               deviceHandle, buffer, length)),
            readBuffer((buffer, length) ->
                           raw.urnet_device_local_get_provide_tls_certificate_pem(
                               deviceHandle, buffer, length)),
            readBuffer((buffer, length) ->
                           raw.urnet_device_local_get_provide_tls_private_key_pem(
                               deviceHandle, buffer, length)),
            readBuffer((buffer, length) ->
                           raw.urnet_device_local_get_extender_key_seed(
                               deviceHandle, buffer, length)));
    if (identity.clientKeySeed().length !=
        InstallationState.CLIENT_KEY_SEED_BYTE_COUNT) {
      throw new IOException("the device has no provider identity to save");
    }
    try {
      InstallationState.saveProviderIdentity(config.stateDir(), identity);
    } catch (IOException e) {
      throw new IOException("save " + InstallationState.IDENTITY_FILE_NAME +
                                ": " + InstallationState.describe(e),
                            e);
    }
  }

  /** Reads the status from the device getters and the listener state. */
  ProviderStatus status() {
    Raw raw = Sdk.raw;
    long deviceHandle = device.handle();
    // "client_limit_exceeded" with the hold's end in RetryTime while the
    // platform holds this client off for its network's client limit
    ProviderStatus.ClientLimit clientLimit = ProviderStatus.parseClientLimit(
        Sdk.takeString(raw.urnet_device_get_client_limit_status(deviceHandle)));
    // urnet_device_local_get_provider_ready also waits for processed client
    // key registration, which default device settings do not enable, so the
    // connected carrier is the readiness signal here
    String state = ProviderStatus.providerState(
        raw.urnet_device_get_provide_mode(deviceHandle), clientLimit.status(),
        raw.urnet_device_get_provide_paused(deviceHandle) != 0,
        raw.urnet_device_get_provide_enabled(deviceHandle) != 0,
        raw.urnet_device_local_get_provider_connected(deviceHandle) != 0);
    // bytes relayed for clients, in both directions, since the device started
    long dataProvidedByteCount = ProviderStatus.dataProvidedByteCount(
        Sdk.takeString(raw.urnet_device_get_provider_packet_stats(deviceHandle)));
    ProviderStatus.ClientsServed.Count clientsServedCount = clientsServed.count();
    return new ProviderStatus(state, clientLimit.retryTime(),
                              clientsServedCount.count(),
                              clientsServedCount.atLimit(),
                              dataProvidedByteCount, payoutWallet.wallet(),
                              payoutWallet.scope());
  }

  /**
   * Prints status lines until a stop is requested or the server rejects the credential, and
   * returns the process exit code.
   */
  int run() {
    String lastKey = "";
    long lastPrintTime = 0;
    long nextWalletSyncTime = System.nanoTime() + WALLET_SYNC_INTERVAL.toNanos();
    while (true) {
      ProviderStatus status = status();
      long now = System.nanoTime();
      String key = status.key();
      if (!key.equals(lastKey) ||
          STATUS_REPEAT_INTERVAL.toNanos() <= now - lastPrintTime) {
        out.println(status.line());
        lastKey = key;
        lastPrintTime = now;
      }
      if (0 <= now - nextWalletSyncTime) {
        syncWallet();
        nextWalletSyncTime = now + WALLET_SYNC_INTERVAL.toNanos();
      }
      OptionalInt exitCode =
          handleEvents(now + STATUS_POLL_INTERVAL.toNanos());
      if (exitCode.isPresent()) {
        return exitCode.getAsInt();
      }
    }
  }

  /**
   * Ends run with exit code 0, or keeps a session that has not started from starting. Safe from any
   * thread, such as a shutdown hook.
   */
  void requestStop() {
    stopRequested = true;
    events.add(Signal.STOP);
  }

  /** Whether requestStop was called. */
  boolean stopRequested() { return stopRequested; }

  /**
   * Stops providing and releases the subscriptions, the device and the manager, in that order. Does
   * nothing for a session that did not start.
   */
  void close() {
    if (!started) {
      return;
    }
    started = false;
    Sdk.raw.urnet_device_set_provide_mode(device.handle(),
                                          ProviderStatus.PROVIDE_MODE_NONE);
    release();
    // a credential refreshed after the status loop ended
    List<Event> remainingEvents = new ArrayList<>();
    events.drainTo(remainingEvents);
    for (Event event : remainingEvents) {
      if (event instanceof JwtRefreshed refreshed) {
        saveClientJwt(refreshed.clientJwt());
      }
    }
    out.println("status: stopped");
  }

  /** Closes the subscriptions, then closes and releases the device, the space and the manager. */
  private void release() {
    Raw raw = Sdk.raw;
    for (long sub : subs) {
      raw.urnet_sub_close(sub);
      raw.urnet_release(sub);
    }
    subs.clear();
    if (device != null) {
      device.close();
      device = null;
    }
    if (api != null) {
      api.close();
      api = null;
    }
    if (space != null) {
      space.close();
      space = null;
    }
    if (manager != null) {
      raw.urnet_network_space_manager_close(manager.handle());
      manager.close();
      manager = null;
    }
  }

  /** Handles the callbacks' work until the deadline; an exit code ends the status loop. */
  private OptionalInt handleEvents(long deadline) {
    while (true) {
      long remaining = deadline - System.nanoTime();
      if (remaining <= 0) {
        return OptionalInt.empty();
      }
      Event event;
      try {
        event = events.poll(remaining, TimeUnit.NANOSECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return OptionalInt.of(Main.EXIT_STOPPED);
      }
      if (event == null) {
        return OptionalInt.empty();
      }
      if (event == Signal.STOP) {
        return OptionalInt.of(Main.EXIT_STOPPED);
      }
      if (event == Signal.AUTH_LOGOUT) {
        err.println(
            "the server rejected the client credential; issue a new scoped client JWT from your backend");
        return OptionalInt.of(Main.EXIT_CONFIG);
      }
      if (event instanceof JwtRefreshed refreshed) {
        saveClientJwt(refreshed.clientJwt());
      } else if (event instanceof WalletRead read) {
        walletRead(read.resultJson(), read.error());
      }
    }
  }

  /**
   * Reads the payout wallet (GET /sn/wallet with the client credential); the callback queues the
   * result. The app only displays the wallet: the backend maps it, never the app.
   */
  private void syncWallet() {
    Sdk.raw.urnet_device_local_sync_sn_wallet(device.handle(),
                                              walletReadCallback, null);
  }

  /** Records one wallet read. A failure keeps the last known wallet. */
  private void walletRead(String resultJson, String error) {
    boolean readSucceeded = ProviderStatus.walletReadSucceeded(resultJson, error);
    ProviderStatus.SnWallet effectiveWallet = null;
    if (readSucceeded) {
      try {
        // the sdk caches the effective wallet before the callback: this
        // client's own consent, else the network consent, else (with hotkey
        // delegations) the network's hotkey entry, else a non-consent wallet
        effectiveWallet = ProviderStatus.parseSnWallet(Sdk.takeString(
            Sdk.raw.urnet_device_local_get_sn_wallet(device.handle())));
      } catch (Json.ParseException e) {
        readSucceeded = false;
      }
    }
    payoutWallet = ProviderStatus.payoutWalletAfterRead(
        payoutWallet, readSucceeded, effectiveWallet, config.clientId());
  }

  /** Keeps the refreshed credential, so the next start uses a valid token. */
  private void saveClientJwt(String clientJwt) {
    if (clientJwt == null || clientJwt.isBlank()) {
      return;
    }
    try {
      InstallationState.writePrivateFile(
          config.stateDir().resolve(InstallationState.CLIENT_JWT_FILE_NAME),
          (clientJwt + "\n").getBytes(StandardCharsets.UTF_8));
    } catch (IOException e) {
      // never print the token itself
      err.println("could not save the refreshed client credential: " +
                  InstallationState.describe(e));
    }
  }

  /** The bytes of a buffer-out getter: the first call reads the size, the second copies. */
  private static byte[] readBuffer(BufferGetter getter) {
    IntByReference length = new IntByReference(0);
    getter.get(null, length);
    int byteCount = length.getValue();
    if (byteCount <= 0) {
      return new byte[0];
    }
    try (Memory buffer = new Memory(byteCount)) {
      length.setValue(byteCount);
      if (getter.get(buffer, length) == 0) {
        throw new IllegalStateException("the sdk did not copy the identity");
      }
      return buffer.getByteArray(0, byteCount);
    }
  }

  /** Native memory with a copy of bytes; null (a NULL pointer) for none. */
  private static Memory memory(byte[] bytes) {
    if (bytes.length == 0) {
      return null;
    }
    Memory memory = new Memory(bytes.length);
    memory.write(0, bytes, 0, bytes.length);
    return memory;
  }

  /** A handle the sdk returned; 0 means the sdk did not create the object. */
  private static long requireHandle(long handle, String name) throws IOException {
    if (handle == 0) {
      throw new IOException("the sdk did not create the " + name);
    }
    return handle;
  }

  /** A c bool for jna. */
  private static byte cBool(boolean value) { return (byte)(value ? 1 : 0); }
}
