// The embedded device over the SDK's C ABI, through the desktop JVM binding
// io.ur.sdk (the JNA interface Raw and the Sdk handle wrappers): the network
// space manager, the local device with the installation's client JWT, and the
// listeners that feed its status (EMBED_CONTRACT.md, "App lifecycle"). The
// device routes only the traffic the app sends through it; it does not
// provide.
//
// SDK callbacks run on SDK threads; they only copy what they carry onto the
// event queue that the status loop drains on the app's own thread, which makes
// every other SDK call. Cap reads run on one background thread so the status
// keeps its pace.
import com.sun.jna.Callback;
import com.sun.jna.ptr.PointerByReference;
import io.ur.sdk.Raw;
import io.ur.sdk.Sdk;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * One run of the embedded device. The constructor makes no sdk call; start creates the device;
 * run shows the status until a stop; close closes the device. requestStop is safe from any thread,
 * at any time; the other methods belong to the thread that created the session.
 */
final class EmbedSession {
  // how often the status is read, and the longest gap between status lines
  private static final Duration STATUS_POLL_INTERVAL = Duration.ofSeconds(1);
  private static final Duration STATUS_REPEAT_INTERVAL = Duration.ofSeconds(60);
  // how often the caps are read, and how soon after a contract status change
  private static final Duration CAP_READ_INTERVAL = Duration.ofMinutes(5);
  private static final Duration CAP_READ_AFTER_CONTRACT_CHANGE = Duration.ofSeconds(1);

  // the device description and spec recorded for this installation's device
  static final String DEVICE_DESCRIPTION = "Java embed example";
  static final String DEVICE_SPEC = "urnetwork-examples/java-embed";
  private static final String APP_VERSION = "1";

  // the ur.network main network space, as the integration helpers create it
  private static final String NETWORK_SPACE_KEY_JSON =
      "{\"host_name\":\"ur.network\",\"env_name\":\"main\"}";
  private static final String NETWORK_SPACE_VALUES_JSON =
      "{\"migration_host_name\":\"bringyour.com\"}";
  // the destination of the app's own traffic: the best available location
  private static final String BEST_AVAILABLE_JSON =
      "{\"connect_location_id\":{\"best_available\":true}}";

  // callback objects stay reachable for the life of the process: jna holds
  // them weakly, and the sdk can call back after the device closes
  private static final List<Callback> CALLBACK_ROOTS =
      Collections.synchronizedList(new ArrayList<>());

  /** Work that the sdk callbacks hand to the status loop. */
  private sealed interface Event {}

  /** A refreshed client credential to save. */
  private record JwtRefreshed(String clientJwt) implements Event {}

  /** Signals to the status loop. */
  private enum Signal implements Event { STOP, AUTH_LOGOUT, CONTRACT_STATUS_CHANGED }

  private final PrintStream out;
  private final PrintStream err;
  private final LinkedBlockingQueue<Event> events = new LinkedBlockingQueue<>();
  private final Raw.urnet_jwt_refresh_cb jwtRefreshCallback;
  private final Raw.urnet_auth_logout_cb authLogoutCallback;
  private final Raw.urnet_contract_status_change_cb contractStatusCallback;
  private final List<Long> subs = new ArrayList<>();
  // one daemon thread for the cap reads, so a slow read never delays the status
  private final ExecutorService capReader = Executors.newSingleThreadExecutor(runnable -> {
    Thread thread = new Thread(runnable, "embed-cap-read");
    thread.setDaemon(true);
    return thread;
  });
  // set by requestStop, from any thread
  private volatile boolean stopRequested;
  private boolean started;
  private InstallationState.EmbedSettings settings;
  // the HTTPS client of the cap reads
  private Token.HttpTransport http;
  private Sdk.Handle manager;
  private Sdk.Handle space;
  private Sdk.Handle api;
  private Sdk.Device device;

  // owned by the status loop's thread: the client JWT that cap reads present,
  // the cap readings so far, and the cap read in flight
  private String clientJwt;
  private final Caps.CapReadings capReadings = new Caps.CapReadings();
  private Future<Caps.DataCap> capRead;
  private long nextCapReadTime;

  /** A session that has not opened anything yet. */
  EmbedSession(PrintStream out, PrintStream err) {
    this.out = out;
    this.err = err;
    jwtRefreshCallback = (userData, refreshedJwt) -> events.add(new JwtRefreshed(refreshedJwt));
    authLogoutCallback = userData -> events.add(Signal.AUTH_LOGOUT);
    contractStatusCallback =
        (userData, contractStatusJson) -> events.add(Signal.CONTRACT_STATUS_CHANGED);
    CALLBACK_ROOTS.addAll(List.of(jwtRefreshCallback, authLogoutCallback, contractStatusCallback));
  }

  /** The SDK version of the native runtime, which this call loads. */
  static String sdkVersion() { return Sdk.version(); }

  /** The SDK's licenses and data attributions for this kind of app, as JSON; null if none. */
  static String sdkLicenses(String app) { return Sdk.takeString(Sdk.raw.urnet_get_licenses(app)); }

  /**
   * Keeps the SDK's log files in logDir, which the SDK bounds, instead of the system temp
   * directory. The SDK also copies its log lines to stderr.
   */
  static void configureSdkLogs(Path logDir) throws IOException {
    InstallationState.createPrivateDirectory(logDir);
    PointerByReference error = new PointerByReference();
    boolean set = Sdk.raw.urnet_set_log_dir(logDir.toString(), error) != 0;
    String message = Sdk.takeString(error.getValue());
    if (!set) {
      throw new IOException(message != null ? message : "the sdk did not accept the log directory");
    }
  }

  /**
   * Creates the local device with the installation's client JWT, adds the listeners and sets the
   * connect location to best available. The token server's cap reading, when there is one, counts
   * as the first. A failure releases what was opened.
   */
  void start(InstallationState.EmbedSettings settings, Token.ClientCredential credential)
      throws IOException {
    this.settings = settings;
    http = Token.HttpTransport.jdk();
    clientJwt = credential.clientJwt();
    long now = System.nanoTime();
    if (credential.firstCap() != null) {
      capReadings.record(credential.firstCap());
      nextCapReadTime = now + CAP_READ_INTERVAL.toNanos();
    } else {
      nextCapReadTime = now;
    }
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

  /** Opens the manager, the space and the device, adds the listeners and sets the destination. */
  private void open() throws IOException {
    Raw raw = Sdk.raw;
    manager = new Sdk.Handle(requireHandle(raw.urnet_new_network_space_manager_no_storage(),
                                           "network space manager"));
    space = new Sdk.Handle(requireHandle(
        raw.urnet_network_space_manager_update_network_space_values(
            manager.handle(), NETWORK_SPACE_KEY_JSON, NETWORK_SPACE_VALUES_JSON),
        "network space"));
    api = new Sdk.Handle(requireHandle(raw.urnet_network_space_get_api(space.handle()), "api"));
    raw.urnet_api_set_by_jwt(api.handle(), clientJwt);
    PointerByReference error = new PointerByReference();
    // no device rpc; the provide mode stays at its default: an embed app does
    // not provide
    long deviceHandle = raw.urnet_new_device_local_with_defaults(
        space.handle(), clientJwt, DEVICE_DESCRIPTION, DEVICE_SPEC, APP_VERSION,
        settings.instanceId(), (byte)0, error);
    String message = Sdk.takeString(error.getValue());
    if (deviceHandle == 0) {
      throw new IOException(message != null ? message : "the sdk did not create the device");
    }
    device = new Sdk.Device(deviceHandle);
    subs.add(requireHandle(
        raw.urnet_device_add_jwt_refresh_listener(deviceHandle, jwtRefreshCallback, null),
        "jwt refresh listener"));
    subs.add(requireHandle(
        raw.urnet_device_add_auth_logout_listener(deviceHandle, authLogoutCallback, null),
        "auth logout listener"));
    subs.add(requireHandle(raw.urnet_device_add_contract_status_change_listener(
                               deviceHandle, contractStatusCallback, null),
                           "contract status listener"));
    raw.urnet_device_set_connect_location(deviceHandle, BEST_AVAILABLE_JSON);
  }

  /** The status line from the device getters and the latest cap reading. */
  String statusLine() {
    Raw raw = Sdk.raw;
    long deviceHandle = device.handle();
    EmbedStatus.ClientLimit clientLimit = EmbedStatus.parseClientLimit(
        Sdk.takeString(raw.urnet_device_get_client_limit_status(deviceHandle)));
    int providersAdded = EmbedStatus.providersAdded(
        Sdk.takeString(raw.urnet_device_get_window_status(deviceHandle)));
    String status = EmbedStatus.status(true, false, clientLimit.status(), clientLimit.retryTime(),
                                       capReadings.latest(), providersAdded);
    return EmbedStatus.statusLine(status, EmbedStatus.dataField(capReadings, true),
                                  EmbedStatus.dataField(capReadings, false));
  }

  /**
   * Prints status lines until a stop is requested or the server rejects the credential, and
   * returns the process exit code.
   */
  int run() {
    String lastLine = null;
    long lastPrintTime = 0;
    while (true) {
      syncCaps();
      String line = statusLine();
      long now = System.nanoTime();
      if (!line.equals(lastLine) || STATUS_REPEAT_INTERVAL.toNanos() <= now - lastPrintTime) {
        out.println(line);
        lastLine = line;
        lastPrintTime = now;
      }
      OptionalInt exitCode = handleEvents(now + STATUS_POLL_INTERVAL.toNanos());
      if (exitCode.isPresent()) {
        return exitCode.getAsInt();
      }
    }
  }

  /**
   * Applies a finished cap read, and starts the next one at start, every 5 minutes and soon after a
   * contract status change. One read at a time.
   */
  private void syncCaps() {
    long now = System.nanoTime();
    if (capRead != null && capRead.isDone()) {
      Caps.DataCap reading;
      try {
        reading = capRead.get();
      } catch (ExecutionException e) {
        reading = null;
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        reading = null;
      }
      capReadings.record(reading);
      capRead = null;
    }
    if (capRead == null && 0 <= now - nextCapReadTime) {
      String readJwt = clientJwt;
      Token.HttpTransport readHttp = http;
      capRead = capReader.submit(() -> Caps.read(settings.apiOrigin(), readJwt, readHttp));
      nextCapReadTime = now + CAP_READ_INTERVAL.toNanos();
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
   * Closes the subscriptions, the device and the manager, in that order. Does nothing for a session
   * that did not start.
   */
  void close() {
    capReader.shutdownNow();
    if (!started) {
      return;
    }
    started = false;
    release();
    // a credential refreshed after the status loop ended
    List<Event> remainingEvents = new ArrayList<>();
    events.drainTo(remainingEvents);
    for (Event event : remainingEvents) {
      if (event instanceof JwtRefreshed refreshed) {
        saveClientJwt(refreshed.clientJwt());
      }
    }
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
        err.println("the server rejected the client credential; sign in again to get a new client JWT from your backend");
        return OptionalInt.of(Main.EXIT_CONFIG);
      }
      if (event == Signal.CONTRACT_STATUS_CHANGED) {
        long soon = System.nanoTime() + CAP_READ_AFTER_CONTRACT_CHANGE.toNanos();
        if (0 < nextCapReadTime - soon) {
          nextCapReadTime = soon;
        }
      } else if (event instanceof JwtRefreshed refreshed) {
        saveClientJwt(refreshed.clientJwt());
      }
    }
  }

  /** Keeps the refreshed credential, so the next start and the next cap read use a valid token. */
  private void saveClientJwt(String refreshedJwt) {
    if (refreshedJwt == null || refreshedJwt.isBlank()) {
      return;
    }
    clientJwt = refreshedJwt.strip();
    try {
      InstallationState.saveClientJwt(settings.stateDir(), clientJwt);
    } catch (InstallationState.ConfigException e) {
      // never print the token itself
      err.println("could not save the refreshed client credential: " + e.getMessage());
    }
  }

  /** A handle the sdk returned; 0 means the sdk did not create the object. */
  private static long requireHandle(long handle, String name) throws IOException {
    if (handle == 0) {
      throw new IOException("the sdk did not create the " + name);
    }
    return handle;
  }
}
