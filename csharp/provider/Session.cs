// A running provider over the SDK's C ABI: the network space manager, the
// provider device with the installation's identity, and the listeners that
// feed its status (PROVIDER_CONTRACT.md, "App lifecycle"). C ABI callbacks run
// on SDK threads and the marshaller copies their strings before they run. They
// only record what they carry under a lock: the contract listeners count the
// peer at once, the others leave the work (saving the token, reading the
// wallet, stopping) to the status loop on the app's thread, within a second.
// They never throw, because an exception must not unwind into native code.
using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Text;
using URnetwork.SDK;

/// One run of the provider. The constructor starts providing; Run shows the
/// status until a stop; Dispose stops providing and releases the device, then
/// the manager. Only the callbacks run concurrently with the app's thread.
internal sealed class ProviderSession : IDisposable {
  // How often the status is read, and the longest gap between status lines.
  private static readonly TimeSpan StatusPollInterval = TimeSpan.FromSeconds(1);
  private static readonly TimeSpan StatusRepeatInterval = TimeSpan.FromSeconds(60);

  // How often the payout wallet is read again. The wallet is fixed; a reread
  // shows a mapping that the backend completes while the provider runs.
  private static readonly TimeSpan WalletSyncInterval = TimeSpan.FromMinutes(10);

  // The device description and spec recorded for this installation's device.
  private const string DeviceDescription = "C# provider example";
  private const string DeviceSpec = "urnetwork-examples/csharp-provider";

  // The provider extender role's two device settings (PROVIDER_CONTRACT.md,
  // "Extender role"), passed explicitly and both on. ProvideExtenderEnabled is
  // the embedder's hard switch: false means the role never runs.
  // DefaultProvideExtender is the setting the device uses until the user sets
  // one: false turns the default off.
  private const bool ProvideExtenderEnabled = true;
  private const bool DefaultProvideExtender = true;

  // The ur.network main network space, as the integration helper creates it.
  private const string NetworkSpaceKeyJson = """{"host_name":"ur.network","env_name":"main"}""";
  private const string NetworkSpaceValuesJson = """{"migration_host_name":"bringyour.com"}""";

  // The SDK can call back after Dispose, for example with a wallet read that
  // the device close cancels. Every callback delegate stays here for the life
  // of the process, as in the messages example, so no native call reaches a
  // collected delegate.
  private static readonly List<Delegate> CallbackRoots = [];

  private readonly ProviderConfig config;
  private readonly TextWriter output;
  private readonly TextWriter error;
  private readonly ServedClients servedClients = new(StatusRules.ClientsServedLimit);
  private readonly Raw.urnet_jwt_refresh_cb jwtRefreshed;
  private readonly Raw.urnet_auth_logout_cb authLogout;
  private readonly Raw.urnet_contract_details_change_cb ingressContractDetailsChanged;
  private readonly Raw.urnet_contract_details_change_cb egressContractDetailsChanged;
  private readonly Raw.urnet_sn_get_wallet_cb walletRead;
  // listener subscription handles, closed and released by Dispose
  private readonly List<ulong> subs = [];
  private Handle? manager;
  private Handle? space;
  private Handle? api;
  private Device? device;
  private bool started;
  private bool disposed;

  // the payout wallet that the status shows, kept by the app's thread: a
  // coldkey address, or StatusRules.PayoutWalletChecking,
  // PayoutWalletUnavailable or PayoutWalletNotSet
  private string payoutWallet = StatusRules.PayoutWalletChecking;
  private string payoutWalletScope = "";

  private readonly object stateLock = new();
  // the newest refreshed client JWT that is not saved yet
  private string? unsavedClientJwt;
  // set when the server rejected the client credential
  private bool loggedOut;
  // whether wallet reads finished since the status loop last looked, and
  // whether one of them succeeded
  private bool walletReadPending;
  private bool walletReadSucceeded;

  /// Creates the provider device with the installation's identity and the
  /// extender role's settings on, saves a new identity on first run, and
  /// starts providing publicly. The SDK declares provide intent on the
  /// device's platform connections by itself while the provide mode is public.
  public ProviderSession(ProviderConfig config, TextWriter output,
                         TextWriter error) {
    this.config = config;
    this.output = output;
    this.error = error;
    jwtRefreshed = (_, clientJwt) => {
      lock (stateLock) {
        unsavedClientJwt = clientJwt;
      }
    };
    authLogout = _ => {
      lock (stateLock) {
        loggedOut = true;
      }
    };
    ingressContractDetailsChanged = (_, contractDetailsJson) =>
        servedClients.Add(contractDetailsJson, receive: true);
    egressContractDetailsChanged = (_, contractDetailsJson) =>
        servedClients.Add(contractDetailsJson, receive: false);
    walletRead = (_, resultJson, errorText) => {
      bool succeeded = StatusRules.WalletReadSucceeded(resultJson, errorText);
      lock (stateLock) {
        walletReadPending = true;
        walletReadSucceeded |= succeeded;
      }
    };
    lock (CallbackRoots) {
      CallbackRoots.AddRange([jwtRefreshed, authLogout, ingressContractDetailsChanged,
                              egressContractDetailsChanged, walletRead]);
    }
    try {
      manager = new Handle(Raw.urnet_new_network_space_manager_no_storage());
      space = new Handle(Raw.urnet_network_space_manager_update_network_space_values(
          manager.Value, NetworkSpaceKeyJson, NetworkSpaceValuesJson));
      api = new Handle(Raw.urnet_network_space_get_api(space.Value));
      Raw.urnet_api_set_by_jwt(api.Value, config.ClientJwt);
      device = NewDevice(space.Value, config);
      ulong handle = device.Value;
      if (config.Identity == null) {
        SaveNewIdentity(handle);
      }
      AddSub(Raw.urnet_device_add_jwt_refresh_listener(handle, jwtRefreshed, IntPtr.Zero));
      AddSub(Raw.urnet_device_add_auth_logout_listener(handle, authLogout, IntPtr.Zero));
      AddSub(Raw.urnet_device_add_provider_ingress_contract_details_change_listener(
          handle, ingressContractDetailsChanged, IntPtr.Zero));
      AddSub(Raw.urnet_device_add_provider_egress_contract_details_change_listener(
          handle, egressContractDetailsChanged, IntPtr.Zero));
      Raw.urnet_device_set_provide_mode(handle, StatusRules.ProvideModePublic);
      SyncWallet();
      started = true;
    } catch {
      Dispose();
      throw;
    }
  }

  /// The key material handle that recreates the device's identity, which the
  /// caller releases. 0 without an identity (the first run, or another
  /// client's identity): the device then makes a new identity. That case
  /// touches no native code, so the self-test checks it.
  public static ulong NewKeyMaterial(ProviderIdentity? identity) {
    if (identity == null) {
      return 0;
    }
    using var clientKeySeed = new PinnedBytes(identity.ClientKeySeed ?? []);
    using var certificatePem = new PinnedBytes(identity.ProvideTlsCertificatePem ?? []);
    using var privateKeyPem = new PinnedBytes(identity.ProvideTlsPrivateKeyPem ?? []);
    ulong keyMaterial = Raw.urnet_new_device_local_key_material(
        clientKeySeed.Pointer, clientKeySeed.Length, certificatePem.Pointer,
        certificatePem.Length, privateKeyPem.Pointer, privateKeyPem.Length);
    if (keyMaterial == 0) {
      throw new IOException("the sdk did not accept the provider identity");
    }
    if (identity.ExtenderKeySeed is { Length: > 0 } extenderKeySeedBytes) {
      using var extenderKeySeed = new PinnedBytes(extenderKeySeedBytes);
      Raw.urnet_device_local_key_material_set_extender_key_seed(
          keyMaterial, extenderKeySeed.Pointer, extenderKeySeed.Length);
    }
    return keyMaterial;
  }

  /// Creates the local device. One call serves both runs: on first run there
  /// is no identity, the key material handle is 0 and the device makes a new
  /// identity, which the constructor saves.
  private static Device NewDevice(ulong spaceHandle, ProviderConfig config) {
    ulong keyMaterial = NewKeyMaterial(config.Identity);
    try {
      // no device rpc; the extender settings as above
      ulong handle = Raw.urnet_new_device_local_with_provide_extender(
          spaceHandle, config.ClientJwt, DeviceDescription, DeviceSpec, "1",
          config.InstanceId, CBool(false), keyMaterial,
          CBool(ProvideExtenderEnabled), CBool(DefaultProvideExtender),
          out IntPtr createError);
      string? message = Sdk.TakeString(createError);
      if (handle == 0) {
        throw new IOException(message ?? "the sdk did not create the device");
      }
      return new Device(handle);
    } finally {
      // the device keeps its own copy of the identity
      if (keyMaterial != 0) {
        Raw.urnet_release(keyMaterial);
      }
    }
  }

  /// Keeps the new identity of a first run, so later starts present the same
  /// provider.
  private void SaveNewIdentity(ulong deviceHandle) {
    byte[] clientKeySeed =
        ReadDeviceBytes(deviceHandle, Raw.urnet_device_local_get_client_key_seed);
    if (clientKeySeed.Length != 32) {
      throw new IOException("the device has no provider identity to save");
    }
    byte[] extenderKeySeed =
        ReadDeviceBytes(deviceHandle, Raw.urnet_device_local_get_extender_key_seed);
    var identity = new ProviderIdentity {
      Version = ProviderIdentity.CurrentVersion,
      ClientId = config.ClientId,
      ClientKeySeed = clientKeySeed,
      ProvideTlsCertificatePem = ReadDeviceBytes(
          deviceHandle, Raw.urnet_device_local_get_provide_tls_certificate_pem),
      ProvideTlsPrivateKeyPem = ReadDeviceBytes(
          deviceHandle, Raw.urnet_device_local_get_provide_tls_private_key_pem),
      ExtenderKeySeed = extenderKeySeed.Length == 0 ? null : extenderKeySeed,
    };
    try {
      StateFiles.SaveProviderIdentity(config.StateDir, identity);
    } catch (Exception e) when (e is IOException or UnauthorizedAccessException) {
      throw new IOException($"save {StateFiles.IdentityFileName}: {e.Message}", e);
    }
  }

  // A C ABI buffer-out getter: it always sets length to the size it needs, and
  // copies (returning true) only when output has that capacity.
  private delegate byte BufferGetter(ulong handle, IntPtr output, ref int length);

  /// One identity value of the device: the first call reads its size, the
  /// second copies it into a pinned array.
  private static byte[] ReadDeviceBytes(ulong deviceHandle, BufferGetter getter) {
    int length = 0;
    getter(deviceHandle, IntPtr.Zero, ref length);
    if (length <= 0) {
      return [];
    }
    byte[] bytes = new byte[length];
    using var pinned = new PinnedBytes(bytes);
    int capacity = length;
    if (getter(deviceHandle, pinned.Pointer, ref capacity) == 0 ||
        capacity != length) {
      throw new IOException("the device identity changed while it was read");
    }
    return bytes;
  }

  /// Keeps a listener subscription for Dispose. 0 means the SDK did not add the
  /// listener.
  private void AddSub(ulong sub) {
    if (sub == 0) {
      throw new IOException("the sdk did not add a device listener");
    }
    subs.Add(sub);
  }

  /// Starts a payout wallet read (GET /sn/wallet with the client credential).
  /// The app only displays the wallet: the backend maps it, never the app.
  private void SyncWallet() {
    Raw.urnet_device_local_sync_sn_wallet(device!.Value, walletRead, IntPtr.Zero);
  }

  /// Reads the status from the device getters and the listener state.
  public ProviderStatus Status() {
    ulong handle = device!.Value;
    // "client_limit_exceeded", with the hold's end in RetryTime, while the
    // platform holds this client off for its network's client limit
    var (clientLimitStatus, clientLimitRetryTime) = StatusRules.ParseClientLimitStatus(
        Sdk.TakeString(Raw.urnet_device_get_client_limit_status(handle)));
    // GetProviderReady also waits for processed client key registration, which
    // default device settings do not enable, so the connected carrier is the
    // readiness signal here
    string state = StatusRules.ProviderState(
        Raw.urnet_device_get_provide_mode(handle), clientLimitStatus,
        Raw.urnet_device_get_provide_paused(handle) != 0,
        Raw.urnet_device_get_provide_enabled(handle) != 0,
        Raw.urnet_device_local_get_provider_connected(handle) != 0);
    long dataProvidedByteCount = StatusRules.DataProvidedByteCount(
        Sdk.TakeString(Raw.urnet_device_get_provider_packet_stats(handle)));
    var (clientsServed, clientsServedAtLimit) = servedClients.Count();
    return new ProviderStatus {
      State = state,
      ClientLimitRetryTime = clientLimitRetryTime,
      ClientsServed = clientsServed,
      ClientsServedAtLimit = clientsServedAtLimit,
      DataProvidedByteCount = dataProvidedByteCount,
      PayoutWallet = payoutWallet,
      PayoutWalletScope = payoutWalletScope,
    };
  }

  /// Prints status lines until stop is cancelled (exit 0) or the server
  /// rejects the credential (exit 78), and returns the process exit code. It
  /// also does the work that the callbacks handed over.
  public int Run(CancellationToken stop) {
    StatusKey? lastKey = null;
    var clock = Stopwatch.StartNew();
    TimeSpan lastPrintTime = TimeSpan.Zero;
    TimeSpan nextWalletSyncTime = WalletSyncInterval;
    while (true) {
      SaveRefreshedClientJwt();
      ApplyWalletRead();
      ProviderStatus status = Status();
      TimeSpan now = clock.Elapsed;
      StatusKey key = status.Key();
      if (key != lastKey || StatusRepeatInterval <= now - lastPrintTime) {
        output.WriteLine(status);
        lastKey = key;
        lastPrintTime = now;
      }
      if (nextWalletSyncTime <= now) {
        SyncWallet();
        nextWalletSyncTime = now + WalletSyncInterval;
      }
      if (stop.WaitHandle.WaitOne(StatusPollInterval)) {
        return Program.ExitStopped;
      }
      bool credentialRejected;
      lock (stateLock) {
        credentialRejected = loggedOut;
      }
      if (credentialRejected) {
        error.WriteLine("the server rejected the client credential; issue a new scoped client JWT from your backend");
        return Program.ExitConfig;
      }
    }
  }

  /// Keeps the refreshed credential, so the next start uses a valid token.
  private void SaveRefreshedClientJwt() {
    string? clientJwt;
    lock (stateLock) {
      clientJwt = unsavedClientJwt;
      unsavedClientJwt = null;
    }
    if (clientJwt == null) {
      return;
    }
    try {
      StateFiles.WritePrivateFile(
          Path.Combine(config.StateDir, StateFiles.ClientJwtFileName),
          Encoding.UTF8.GetBytes(clientJwt + "\n"));
    } catch (Exception e) when (e is IOException or UnauthorizedAccessException) {
      // never print the token itself
      error.WriteLine($"could not save the refreshed client credential: {e.Message}");
    }
  }

  /// Applies the wallet reads that finished. A failure keeps the last known
  /// wallet; before the first successful read it shows "unavailable".
  private void ApplyWalletRead() {
    bool pending;
    bool succeeded;
    lock (stateLock) {
      pending = walletReadPending;
      succeeded = walletReadSucceeded;
      walletReadPending = false;
      walletReadSucceeded = false;
    }
    if (!pending) {
      return;
    }
    if (!succeeded) {
      if (payoutWallet == StatusRules.PayoutWalletChecking) {
        payoutWallet = StatusRules.PayoutWalletUnavailable;
      }
      return;
    }
    // the SDK caches the effective wallet before the callback: this client's
    // own consent, else the network consent, else (with hotkey delegations)
    // the network's hotkey entry, else a non-consent wallet
    (payoutWallet, payoutWalletScope) = StatusRules.ParsePayoutWallet(
        Sdk.TakeString(Raw.urnet_device_local_get_sn_wallet(device!.Value)),
        config.ClientId);
  }

  /// Stops providing, closes the subscriptions, closes the device and then
  /// the manager, and releases every handle. Also saves a refreshed
  /// credential that arrived after the status loop's last pass.
  public void Dispose() {
    if (disposed) {
      return;
    }
    disposed = true;
    if (device != null) {
      Raw.urnet_device_set_provide_mode(device.Value, StatusRules.ProvideModeNone);
    }
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
    if (started) {
      output.WriteLine("status: stopped");
    }
  }

  /// A C bool argument.
  private static byte CBool(bool value) {
    return value ? (byte)1 : (byte)0;
  }
}

/// Bytes pinned for C ABI calls, so the SDK reads or fills them in place
/// instead of through a copy in unmanaged memory (they hold identity secrets).
internal readonly struct PinnedBytes : IDisposable {
  private readonly GCHandle handle;

  /// Pins bytes until Dispose.
  public PinnedBytes(byte[] bytes) {
    handle = GCHandle.Alloc(bytes, GCHandleType.Pinned);
    Length = bytes.Length;
  }

  /// The address of the first byte, valid until Dispose.
  public IntPtr Pointer => handle.AddrOfPinnedObject();

  /// The number of bytes.
  public int Length { get; }

  /// Unpins the bytes.
  public void Dispose() {
    handle.Free();
  }
}
