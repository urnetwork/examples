// The embedded device over the SDK's C ABI: the network space manager, the
// local device with the installation's client JWT, and the listeners that feed
// its status (EMBED_CONTRACT.md, "App lifecycle"). The device routes only the
// traffic the app sends through it; it does not provide.
//
// C ABI callbacks run on SDK threads and the marshaller copies their strings
// before they run. They only record what they carry under a lock and leave the
// work (saving the token, reading the caps, stopping) to the status loop on
// the app's thread, within a second. They never throw, because an exception
// must not unwind into native code.
using System.Diagnostics;
using URnetwork.SDK;

/// One run of the embedded device. The constructor starts the device; Run
/// shows the status until a stop; Dispose closes the subscriptions, the device
/// and the manager. Only the callbacks run concurrently with the app's thread.
internal sealed class EmbedSession : IDisposable {
  // How often the status is read, and the longest gap between status lines.
  private static readonly TimeSpan StatusPollInterval = TimeSpan.FromSeconds(1);
  private static readonly TimeSpan StatusRepeatInterval = TimeSpan.FromSeconds(60);
  // How often the caps are read, and how soon after a contract status change.
  private static readonly TimeSpan CapReadInterval = TimeSpan.FromMinutes(5);
  private static readonly TimeSpan CapReadAfterContractChange = TimeSpan.FromSeconds(1);

  // The device description and spec recorded for this installation's device.
  private const string DeviceDescription = "C# embed example";
  private const string DeviceSpec = "urnetwork-examples/csharp-embed";
  private const string AppVersion = "1";

  // The ur.network main network space, as the integration helper creates it.
  private const string NetworkSpaceKeyJson = """{"host_name":"ur.network","env_name":"main"}""";
  private const string NetworkSpaceValuesJson = """{"migration_host_name":"bringyour.com"}""";
  // The destination of the app's own traffic: the best available location.
  private const string BestAvailableJson = """{"connect_location_id":{"best_available":true}}""";

  // The SDK can call back after Dispose. Every callback delegate stays here
  // for the life of the process, so no native call reaches a collected
  // delegate.
  private static readonly List<Delegate> CallbackRoots = [];

  private readonly EmbedSettings settings;
  private readonly TextWriter output;
  private readonly TextWriter error;
  private readonly Raw.urnet_jwt_refresh_cb jwtRefreshed;
  private readonly Raw.urnet_auth_logout_cb authLogout;
  private readonly Raw.urnet_contract_status_change_cb contractStatusChanged;
  // listener subscription handles, closed and released by Dispose
  private readonly List<ulong> subs = [];
  private Handle? manager;
  private Handle? space;
  private Handle? api;
  private Device? device;
  private bool disposed;

  // owned by the app's thread: the client JWT that cap reads present, the
  // cap readings so far, and the cap read in flight
  private string clientJwt;
  private readonly CapReadings capReadings = new();
  private Task<CapRead>? capRead;
  private TimeSpan nextCapReadTime;

  private readonly object stateLock = new();
  // the newest refreshed client JWT that is not saved yet
  private string? unsavedClientJwt;
  // set when the server rejected the client credential
  private bool loggedOut;
  // set when the contract status changed since the status loop last looked
  private bool contractStatusChangedPending;

  private readonly Stopwatch clock = Stopwatch.StartNew();

  /// Creates the local device with the installation's client JWT, adds the
  /// listeners and sets the connect location to best available. The token
  /// server's cap reading, when there is one, counts as the first.
  public EmbedSession(EmbedSettings settings, ClientCredential credential, TextWriter output,
                      TextWriter error) {
    this.settings = settings;
    this.output = output;
    this.error = error;
    clientJwt = credential.ClientJwt;
    jwtRefreshed = (_, refreshedJwt) => {
      lock (stateLock) {
        unsavedClientJwt = refreshedJwt;
      }
    };
    authLogout = _ => {
      lock (stateLock) {
        loggedOut = true;
      }
    };
    contractStatusChanged = (_, _) => {
      lock (stateLock) {
        contractStatusChangedPending = true;
      }
    };
    lock (CallbackRoots) {
      CallbackRoots.AddRange([jwtRefreshed, authLogout, contractStatusChanged]);
    }
    if (credential.FirstCap != null) {
      capReadings.Record(credential.FirstCap);
      nextCapReadTime = CapReadInterval;
    }
    try {
      manager = new Handle(Raw.urnet_new_network_space_manager_no_storage());
      space = new Handle(Raw.urnet_network_space_manager_update_network_space_values(
          manager.Value, NetworkSpaceKeyJson, NetworkSpaceValuesJson));
      api = new Handle(Raw.urnet_network_space_get_api(space.Value));
      Raw.urnet_api_set_by_jwt(api.Value, clientJwt);
      // no device rpc; the provide mode stays at its default: an embed app
      // does not provide
      ulong handle = Raw.urnet_new_device_local_with_defaults(
          space.Value, clientJwt, DeviceDescription, DeviceSpec, AppVersion,
          settings.InstanceId, 0, out IntPtr createError);
      string? message = Sdk.TakeString(createError);
      if (handle == 0) {
        throw new IOException(message ?? "the sdk did not create the device");
      }
      device = new Device(handle);
      AddSub(Raw.urnet_device_add_jwt_refresh_listener(handle, jwtRefreshed, IntPtr.Zero));
      AddSub(Raw.urnet_device_add_auth_logout_listener(handle, authLogout, IntPtr.Zero));
      AddSub(Raw.urnet_device_add_contract_status_change_listener(
          handle, contractStatusChanged, IntPtr.Zero));
      Raw.urnet_device_set_connect_location(handle, BestAvailableJson);
    } catch {
      Dispose();
      throw;
    }
  }

  /// Keeps a listener subscription for Dispose. 0 means the SDK did not add the
  /// listener.
  private void AddSub(ulong sub) {
    if (sub == 0) {
      throw new IOException("the sdk did not add a device listener");
    }
    subs.Add(sub);
  }

  /// The status line from the device getters and the latest cap reading.
  public string StatusLine() {
    ulong handle = device!.Value;
    var (clientLimitStatus, clientLimitRetryTime) = StatusRules.ParseClientLimitStatus(
        Sdk.TakeString(Raw.urnet_device_get_client_limit_status(handle)));
    int providersAdded = StatusRules.ProvidersAdded(
        Sdk.TakeString(Raw.urnet_device_get_window_status(handle)));
    string status = StatusRules.Status(started: true, signedOut: false, clientLimitStatus,
                                       clientLimitRetryTime, capReadings.Latest, providersAdded);
    return StatusRules.StatusLine(status, StatusRules.DataField(capReadings, monthly: true),
                                  StatusRules.DataField(capReadings, monthly: false));
  }

  /// Prints status lines until stop is cancelled (exit 0) or the server
  /// rejects the credential (exit 78), and returns the process exit code. It
  /// also does the work that the callbacks handed over.
  public int Run(CancellationToken stop) {
    string? lastLine = null;
    TimeSpan lastPrintTime = TimeSpan.Zero;
    while (true) {
      SaveRefreshedClientJwt();
      SyncCaps();
      string line = StatusLine();
      TimeSpan now = clock.Elapsed;
      if (line != lastLine || StatusRepeatInterval <= now - lastPrintTime) {
        output.WriteLine(line);
        lastLine = line;
        lastPrintTime = now;
      }
      if (stop.WaitHandle.WaitOne(StatusPollInterval)) {
        return Program.ExitStopped;
      }
      bool credentialRejected;
      lock (stateLock) {
        credentialRejected = loggedOut;
      }
      if (credentialRejected) {
        error.WriteLine("the server rejected the client credential; sign in again to get a new client JWT from your backend");
        return Program.ExitConfig;
      }
    }
  }

  /// Applies a finished cap read, and starts the next one at start, every 5
  /// minutes and soon after a contract status change. One read at a time; a
  /// read runs on the thread pool so the status keeps its pace.
  private void SyncCaps() {
    TimeSpan now = clock.Elapsed;
    bool contractChanged;
    lock (stateLock) {
      contractChanged = contractStatusChangedPending;
      contractStatusChangedPending = false;
    }
    if (contractChanged && now + CapReadAfterContractChange < nextCapReadTime) {
      nextCapReadTime = now + CapReadAfterContractChange;
    }
    if (capRead is { IsCompleted: true } finished) {
      // the Embed-not-enabled refusal clears the last reading; another failure
      // keeps it
      capReadings.RecordRead(finished.IsCompletedSuccessfully ? finished.Result : new CapRead(null));
      capRead = null;
    }
    if (capRead == null && nextCapReadTime <= now) {
      capRead = CapClient.ReadAsync(settings.ApiOrigin, clientJwt);
      nextCapReadTime = now + CapReadInterval;
    }
  }

  /// Keeps the refreshed credential, so the next start and the next cap read
  /// use a valid token.
  private void SaveRefreshedClientJwt() {
    string? refreshedJwt;
    lock (stateLock) {
      refreshedJwt = unsavedClientJwt;
      unsavedClientJwt = null;
    }
    if (string.IsNullOrWhiteSpace(refreshedJwt)) {
      return;
    }
    clientJwt = refreshedJwt.Trim();
    try {
      StateFiles.SaveClientJwt(settings.StateDir, clientJwt);
    } catch (EmbedConfigException e) {
      // never print the token itself
      error.WriteLine($"could not save the refreshed client credential: {e.Message}");
    }
  }

  /// Closes the subscriptions, closes the device and then the manager, and
  /// releases every handle. Also saves a refreshed credential that arrived
  /// after the status loop's last pass.
  public void Dispose() {
    if (disposed) {
      return;
    }
    disposed = true;
    foreach (ulong sub in subs) {
      Raw.urnet_sub_close(sub);
      Raw.urnet_release(sub);
    }
    subs.Clear();
    // closes the device, then releases its handle
    device?.Dispose();
    api?.Dispose();
    space?.Dispose();
    if (manager != null) {
      Raw.urnet_network_space_manager_close(manager.Value);
      manager.Dispose();
    }
    SaveRefreshedClientJwt();
  }
}
