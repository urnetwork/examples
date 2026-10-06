//! A running provider over the SDK's C ABI (the `urnetwork-sdk` crate's raw function table): the
//! network space manager, the provider device with the installation's identity, and the listeners
//! that feed its status (PROVIDER_CONTRACT.md, "App lifecycle").
//!
//! C ABI callbacks run on SDK threads. They copy what they carry before returning and leave the
//! rest to the thread that runs the session: the contract details listeners count peers in
//! [`ClientsServed`] (a short locked update), and the credential and wallet callbacks record what
//! the run loop then saves or reads. A callback's user data is a token that names its session, not
//! a pointer: a callback that arrives after the session closed (a wallet read can finish late)
//! finds no session and returns.

use std::{
    collections::BTreeMap,
    ffi::{CStr, CString, c_char, c_void},
    io,
    path::{Path, PathBuf},
    ptr,
    sync::{
        Arc, Mutex, MutexGuard, PoisonError, Weak,
        atomic::{AtomicUsize, Ordering},
        mpsc::{Receiver, RecvTimeoutError, Sender},
    },
    time::{Duration, Instant},
};

use urnetwork_sdk::{Device, Handle, native, raw::Raw, take_string};

use crate::{
    sdk_json::{
        parse_client_limit_status, parse_contract_details, parse_data_provided_byte_count,
        parse_payout_wallet, wallet_read_succeeded,
    },
    state::{
        CLIENT_JWT_FILE_NAME, IDENTITY_FILE_NAME, KeyMaterial, ProviderConfig, ProviderIdentity,
        create_private_dir, key_material, save_provider_identity, write_private_file,
    },
    status::{
        ClientsServed, PROVIDE_MODE_NONE, PROVIDE_MODE_PUBLIC, PayoutWallet, ProviderStatus,
        provider_state,
    },
};

/// How often the run loop reads the status.
pub const STATUS_POLL_INTERVAL: Duration = Duration::from_secs(1);

/// How often the payout wallet is read again. The wallet is fixed; a reread shows a mapping that
/// the backend completes while the provider runs.
pub const WALLET_SYNC_INTERVAL: Duration = Duration::from_secs(10 * 60);

/// The provider extender role's hard switch (PROVIDER_CONTRACT.md, "Extender role"), passed
/// explicitly: false means the role never runs.
const PROVIDE_EXTENDER_ENABLED: bool = true;

/// The extender setting the device uses until the user sets one, passed explicitly: false turns the
/// role's default off.
const DEFAULT_PROVIDE_EXTENDER: bool = true;

/// The app version recorded with the device.
const APP_VERSION: &CStr = c"1";

/// The `ur.network`/`main` network space.
const NETWORK_SPACE_KEY_JSON: &CStr = cr#"{"host_name":"ur.network","env_name":"main"}"#;

/// The network space's migration host.
const NETWORK_SPACE_VALUES_JSON: &CStr = cr#"{"migration_host_name":"bringyour.com"}"#;

/// The description and spec recorded for this installation's device, naming the example.
#[derive(Clone, Copy, Debug)]
pub struct DeviceInfo {
    pub description: &'static str,
    pub spec: &'static str,
}

/// A request to the run loop, from a signal handler, the app or an SDK callback.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum RunEvent {
    /// Stop providing, as the user asked.
    Stop,
    /// The server rejected the client credential.
    Logout,
}

/// Why a run ended.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum RunEnd {
    /// A requested stop (exit 0).
    Stopped,
    /// The server rejected the client credential: a restart does not help (exit 78).
    LoggedOut,
}

/// Receives what the run loop reads, on the thread that runs the session.
pub trait ProviderObserver {
    /// The status, read every [`STATUS_POLL_INTERVAL`].
    fn status(&mut self, status: &ProviderStatus);

    /// A problem the provider keeps running through, such as a refreshed credential it could not
    /// save.
    fn warning(&mut self, message: &str);
}

/// The session state that SDK callbacks reach through [`CALLBACK_TARGETS`].
struct SessionShared {
    /// owned by the app, so a GUI that stops and starts again keeps counting since the app started
    clients_served: Arc<ClientsServed>,
    /// wakes the run loop when the server rejects the credential
    events: Sender<RunEvent>,
    pending: Mutex<PendingWork>,
}

impl SessionShared {
    /// The pending work, locked for a short update.
    fn pending(&self) -> MutexGuard<'_, PendingWork> {
        self.pending.lock().unwrap_or_else(PoisonError::into_inner)
    }
}

/// What callbacks hand to the run loop.
#[derive(Default)]
struct PendingWork {
    /// the newest refreshed client JWT, not yet saved
    client_jwt: Option<String>,
    /// finished wallet reads, not yet applied: true when any of them succeeded
    wallet_read_succeeded: Option<bool>,
}

/// Live sessions that SDK callbacks can reach, by callback token.
static CALLBACK_TARGETS: Mutex<BTreeMap<usize, Weak<SessionShared>>> = Mutex::new(BTreeMap::new());

/// The next callback token; 0 is never used.
static NEXT_CALLBACK_TOKEN: AtomicUsize = AtomicUsize::new(1);

/// A session's entry in [`CALLBACK_TARGETS`], removed on drop.
struct CallbackTarget {
    token: usize,
}

impl CallbackTarget {
    /// Makes `shared` reachable from callbacks until this is dropped.
    fn register(shared: &Arc<SessionShared>) -> Self {
        let token = NEXT_CALLBACK_TOKEN.fetch_add(1, Ordering::Relaxed);
        CALLBACK_TARGETS
            .lock()
            .unwrap_or_else(PoisonError::into_inner)
            .insert(token, Arc::downgrade(shared));
        Self { token }
    }

    /// The user data that callbacks get: the token, never dereferenced.
    fn user_data(&self) -> *mut c_void {
        ptr::without_provenance_mut(self.token)
    }
}

impl Drop for CallbackTarget {
    /// Later callbacks with this token find nothing.
    fn drop(&mut self) {
        CALLBACK_TARGETS
            .lock()
            .unwrap_or_else(PoisonError::into_inner)
            .remove(&self.token);
    }
}

/// The open session that a callback's user data names.
fn session_for_callback(user_data: *mut c_void) -> Option<Arc<SessionShared>> {
    let callback_targets = CALLBACK_TARGETS
        .lock()
        .unwrap_or_else(PoisonError::into_inner);
    callback_targets
        .get(&user_data.addr())
        .and_then(Weak::upgrade)
}

/// Copies a string that a callback borrows for the call.
///
/// # Safety
/// `text` is null or a valid C string for the duration of the call.
unsafe fn copy_c_string(text: *const c_char) -> Option<String> {
    if text.is_null() {
        None
    } else {
        Some(
            unsafe { CStr::from_ptr(text) }
                .to_string_lossy()
                .into_owned(),
        )
    }
}

/// Keeps the refreshed client JWT for the run loop to save.
unsafe extern "C" fn jwt_refreshed(user_data: *mut c_void, client_jwt: *const c_char) {
    let Some(shared) = session_for_callback(user_data) else {
        return;
    };
    let client_jwt =
        unsafe { copy_c_string(client_jwt) }.map(|client_jwt| client_jwt.trim().to_string());
    if let Some(client_jwt) = client_jwt.filter(|client_jwt| !client_jwt.is_empty()) {
        shared.pending().client_jwt = Some(client_jwt);
    }
}

/// Ends the run: the server no longer accepts this client's credential.
unsafe extern "C" fn auth_logged_out(user_data: *mut c_void) {
    if let Some(shared) = session_for_callback(user_data) {
        // the run loop owns the receiver until the session closes
        let _ = shared.events.send(RunEvent::Logout);
    }
}

/// Counts the peer of a receive (ingress) provider contract.
unsafe extern "C" fn provider_ingress_contract_details_changed(
    user_data: *mut c_void,
    json: *const c_char,
) {
    unsafe { contract_details_changed(user_data, json, true) }
}

/// Counts the peer of a send (egress) provider contract.
unsafe extern "C" fn provider_egress_contract_details_changed(
    user_data: *mut c_void,
    json: *const c_char,
) {
    unsafe { contract_details_changed(user_data, json, false) }
}

/// Counts the peer of one provider contract in its direction.
///
/// # Safety
/// `json` is null or a valid C string for the duration of the call.
unsafe fn contract_details_changed(user_data: *mut c_void, json: *const c_char, receive: bool) {
    let Some(shared) = session_for_callback(user_data) else {
        return;
    };
    if let Some(details) =
        unsafe { copy_c_string(json) }.and_then(|json| parse_contract_details(&json))
    {
        shared.clients_served.add(&details, receive);
    }
}

/// Records a finished payout wallet read for the run loop.
unsafe extern "C" fn sn_wallet_synced(
    user_data: *mut c_void,
    result_json: *const c_char,
    error: *const c_char,
) {
    let Some(shared) = session_for_callback(user_data) else {
        return;
    };
    let succeeded = wallet_read_succeeded(
        unsafe { copy_c_string(result_json) }.as_deref(),
        unsafe { copy_c_string(error) }.as_deref(),
    );
    let mut pending = shared.pending();
    pending.wallet_read_succeeded =
        Some(pending.wallet_read_succeeded.unwrap_or(false) || succeeded);
}

/// One listener subscription, closed and released on drop. Its callback may still run on an SDK
/// thread while it closes, which the callback token makes safe.
struct Sub(u64);

impl Sub {
    /// Wraps the subscription handle that adding `listener` returned; 0 means the SDK refused the
    /// listener.
    fn new(sub: u64, listener: &str) -> io::Result<Self> {
        if sub == 0 {
            return Err(io::Error::other(format!(
                "could not add the {listener} listener"
            )));
        }
        Ok(Self(sub))
    }
}

impl Drop for Sub {
    /// Closes the subscription, then releases its handle.
    fn drop(&mut self) {
        if let Ok(raw) = native() {
            unsafe {
                (raw.urnet_sub_close)(self.0);
                (raw.urnet_release)(self.0);
            }
        }
    }
}

/// The network space manager, closed and released on drop.
struct NetworkSpaceManager(Handle);

impl NetworkSpaceManager {
    /// The handle, for calls on the manager.
    fn value(&self) -> io::Result<u64> {
        self.0.value()
    }
}

impl Drop for NetworkSpaceManager {
    /// Closes the manager, then releases its handle.
    fn drop(&mut self) {
        if let (Ok(value), Ok(raw)) = (self.0.value(), native()) {
            unsafe { (raw.urnet_network_space_manager_close)(value) };
        }
        self.0.close();
    }
}

/// The key material handle for the device constructor, released on drop; handle 0 (none) on first
/// run.
struct NativeKeyMaterial(Option<Handle>);

impl NativeKeyMaterial {
    /// A handle with the identity's key material, or none.
    fn new(raw: &Raw, key_material: Option<KeyMaterial<'_>>) -> io::Result<Self> {
        let Some(key_material) = key_material else {
            return Ok(Self(None));
        };
        let byte_count = |bytes: &[u8]| {
            i32::try_from(bytes.len()).map_err(|_| {
                io::Error::new(io::ErrorKind::InvalidInput, "key material is too large")
            })
        };
        let handle = unsafe {
            Handle::from_owned((raw.urnet_new_device_local_key_material)(
                key_material.client_key_seed.as_ptr(),
                byte_count(key_material.client_key_seed)?,
                key_material.provide_tls_certificate_pem.as_ptr(),
                byte_count(key_material.provide_tls_certificate_pem)?,
                key_material.provide_tls_private_key_pem.as_ptr(),
                byte_count(key_material.provide_tls_private_key_pem)?,
            ))?
        };
        if !key_material.extender_key_seed.is_empty() {
            unsafe {
                (raw.urnet_device_local_key_material_set_extender_key_seed)(
                    handle.value()?,
                    key_material.extender_key_seed.as_ptr(),
                    byte_count(key_material.extender_key_seed)?,
                )
            };
        }
        Ok(Self(Some(handle)))
    }

    /// The handle to pass, 0 for none.
    fn value(&self) -> u64 {
        self.0
            .as_ref()
            .and_then(|handle| handle.value().ok())
            .unwrap_or(0)
    }
}

/// One byte value of the device from a buffer-out getter (a size query, then the copy). Empty when
/// there is none.
fn device_bytes(
    get: unsafe extern "C" fn(u64, *mut u8, *mut i32) -> bool,
    device_handle: u64,
) -> Vec<u8> {
    // the value can only grow between the two calls if the device changes it; ask again then
    for _ in 0..3 {
        let mut byte_count: i32 = 0;
        unsafe { get(device_handle, ptr::null_mut(), &mut byte_count) };
        if byte_count <= 0 {
            return Vec::new();
        }
        let mut bytes = vec![0u8; byte_count as usize];
        let mut capacity = byte_count;
        if unsafe { get(device_handle, bytes.as_mut_ptr(), &mut capacity) } {
            bytes.truncate(capacity.max(0) as usize);
            return bytes;
        }
    }
    Vec::new()
}

/// Keeps the SDK's log files in `log_dir`, which the SDK bounds (16 MiB files, the newest four kept
/// at each start), instead of the system temp directory. The SDK still copies its log lines to
/// stderr. Loads the native runtime.
pub fn set_log_dir(log_dir: &Path) -> io::Result<()> {
    create_private_dir(log_dir)?;
    let log_dir = log_dir.to_str().ok_or_else(|| {
        io::Error::new(
            io::ErrorKind::InvalidInput,
            "the log directory path is not UTF-8",
        )
    })?;
    let log_dir = CString::new(log_dir)?;
    let raw = native()?;
    let mut error = ptr::null_mut();
    let accepted = unsafe { (raw.urnet_set_log_dir)(log_dir.as_ptr(), &mut error) };
    let message = unsafe { take_string(error) };
    if !accepted {
        return Err(io::Error::other(message.unwrap_or_else(|| {
            "the SDK refused the log directory".to_string()
        })));
    }
    Ok(())
}

/// One run of the provider. [`ProviderSession::start`] starts providing; [`ProviderSession::run`]
/// shows the status; [`ProviderSession::close`] (or drop) stops providing. Used from one thread;
/// callbacks reach only its shared state.
pub struct ProviderSession {
    raw: &'static Raw,
    state_dir: PathBuf,
    client_id: String,
    instance_id: String,
    device_handle: u64,
    payout_wallet: PayoutWallet,
    shared: Arc<SessionShared>,
    closed: bool,
    // dropped in this order after close: subscriptions, device, callback target, api, space,
    // manager
    subs: Vec<Sub>,
    device: Device,
    callback_target: CallbackTarget,
    _api: Handle,
    _space: Handle,
    _manager: NetworkSpaceManager,
}

impl ProviderSession {
    /// Creates the provider device with the installation's identity and both extender settings on,
    /// saves a new identity on first run, and starts providing publicly. The SDK declares
    /// provide intent on the device's platform connections by itself while the provide mode is
    /// public. The provider contract listeners count into `clients_served`. `events` is the
    /// sender of the receiver that [`ProviderSession::run`] takes.
    pub fn start(
        config: ProviderConfig,
        device_info: DeviceInfo,
        clients_served: Arc<ClientsServed>,
        events: Sender<RunEvent>,
    ) -> io::Result<Self> {
        let raw = native()?;
        let client_jwt = CString::new(config.client_jwt.as_str())?;
        let instance_id = CString::new(config.instance_id.as_str())?;
        let description = CString::new(device_info.description)?;
        let spec = CString::new(device_info.spec)?;

        // each handle comes from this runtime and has exactly one owner, which closes or releases
        // it on drop
        let manager = NetworkSpaceManager(unsafe {
            Handle::from_owned((raw.urnet_new_network_space_manager_no_storage)())?
        });
        let space = unsafe {
            Handle::from_owned((raw
                .urnet_network_space_manager_update_network_space_values)(
                manager.value()?,
                NETWORK_SPACE_KEY_JSON.as_ptr(),
                NETWORK_SPACE_VALUES_JSON.as_ptr(),
            ))?
        };
        let api = unsafe { Handle::from_owned((raw.urnet_network_space_get_api)(space.value()?))? };
        unsafe { (raw.urnet_api_set_by_jwt)(api.value()?, client_jwt.as_ptr()) };

        // one call for both runs: on first run there is no identity, the key material is 0 and the
        // device makes a new identity, saved below
        let native_key_material =
            NativeKeyMaterial::new(raw, key_material(config.identity.as_ref()))?;
        let mut error = ptr::null_mut();
        let device_handle = unsafe {
            (raw.urnet_new_device_local_with_provide_extender)(
                space.value()?,
                client_jwt.as_ptr(),
                description.as_ptr(),
                spec.as_ptr(),
                APP_VERSION.as_ptr(),
                instance_id.as_ptr(),
                false,
                native_key_material.value(),
                PROVIDE_EXTENDER_ENABLED,
                DEFAULT_PROVIDE_EXTENDER,
                &mut error,
            )
        };
        // the device keeps what it needs
        drop(native_key_material);
        if let Some(message) = unsafe { take_string(error) } {
            return Err(io::Error::other(message));
        }
        let device = unsafe { Device::from_owned(device_handle)? };

        if config.identity.is_none() {
            // keep the new identity, so later starts present the same provider
            let identity = ProviderIdentity::new(
                config.client_id.clone(),
                device_bytes(raw.urnet_device_local_get_client_key_seed, device_handle),
                device_bytes(
                    raw.urnet_device_local_get_provide_tls_certificate_pem,
                    device_handle,
                ),
                device_bytes(
                    raw.urnet_device_local_get_provide_tls_private_key_pem,
                    device_handle,
                ),
                device_bytes(raw.urnet_device_local_get_extender_key_seed, device_handle),
            );
            if identity.client_key_seed.len() != 32 {
                return Err(io::Error::other(
                    "the device has no provider identity to save",
                ));
            }
            save_provider_identity(&config.state_dir, &identity)
                .map_err(|error| io::Error::other(format!("save {IDENTITY_FILE_NAME}: {error}")))?;
        }

        let shared = Arc::new(SessionShared {
            clients_served,
            events,
            pending: Mutex::new(PendingWork::default()),
        });
        let callback_target = CallbackTarget::register(&shared);
        let user_data = callback_target.user_data();
        let subs = unsafe {
            vec![
                Sub::new(
                    (raw.urnet_device_add_jwt_refresh_listener)(
                        device_handle,
                        Some(jwt_refreshed),
                        user_data,
                    ),
                    "JWT refresh",
                )?,
                Sub::new(
                    (raw.urnet_device_add_auth_logout_listener)(
                        device_handle,
                        Some(auth_logged_out),
                        user_data,
                    ),
                    "auth logout",
                )?,
                Sub::new(
                    (raw.urnet_device_add_provider_ingress_contract_details_change_listener)(
                        device_handle,
                        Some(provider_ingress_contract_details_changed),
                        user_data,
                    ),
                    "provider ingress contract details",
                )?,
                Sub::new(
                    (raw.urnet_device_add_provider_egress_contract_details_change_listener)(
                        device_handle,
                        Some(provider_egress_contract_details_changed),
                        user_data,
                    ),
                    "provider egress contract details",
                )?,
            ]
        };
        unsafe { (raw.urnet_device_set_provide_mode)(device_handle, PROVIDE_MODE_PUBLIC) };

        let session = Self {
            raw,
            state_dir: config.state_dir,
            client_id: config.client_id,
            instance_id: config.instance_id,
            device_handle,
            payout_wallet: PayoutWallet::Checking,
            shared,
            closed: false,
            subs,
            device,
            callback_target,
            _api: api,
            _space: space,
            _manager: manager,
        };
        session.sync_wallet();
        Ok(session)
    }

    /// This installation's provider client.
    pub fn client_id(&self) -> &str {
        &self.client_id
    }

    /// This installation's instance id.
    pub fn instance_id(&self) -> &str {
        &self.instance_id
    }

    /// Starts a payout wallet read (`GET /sn/wallet` with the client credential); the run loop
    /// applies the result. The app only displays the wallet: the backend maps it, never the
    /// app.
    pub fn sync_wallet(&self) {
        unsafe {
            (self.raw.urnet_device_local_sync_sn_wallet)(
                self.device_handle,
                Some(sn_wallet_synced),
                self.callback_target.user_data(),
            )
        };
    }

    /// Reads the status from the device getters and the listener state.
    pub fn status(&self) -> ProviderStatus {
        let raw = self.raw;
        let device = self.device_handle;
        // "client_limit_exceeded" with the hold's end in RetryTime while the platform holds this
        // client off for its network's client limit
        let client_limit_status_json =
            unsafe { take_string((raw.urnet_device_get_client_limit_status)(device)) };
        let (client_limit_status, client_limit_retry_time) =
            parse_client_limit_status(client_limit_status_json.as_deref());
        // GetProviderReady also waits for processed client key registration, which default device
        // settings do not enable, so the connected carrier is the readiness signal here
        let state = unsafe {
            provider_state(
                (raw.urnet_device_get_provide_mode)(device),
                &client_limit_status,
                (raw.urnet_device_get_provide_paused)(device),
                (raw.urnet_device_get_provide_enabled)(device),
                (raw.urnet_device_local_get_provider_connected)(device),
            )
        };
        let packet_stats_json =
            unsafe { take_string((raw.urnet_device_get_provider_packet_stats)(device)) };
        let (clients_served, clients_served_at_limit) = self.shared.clients_served.count();
        ProviderStatus {
            state,
            client_limit_retry_time,
            clients_served,
            clients_served_at_limit,
            data_provided_byte_count: parse_data_provided_byte_count(packet_stats_json.as_deref()),
            payout_wallet: self.payout_wallet.clone(),
        }
    }

    /// Saves a refreshed client JWT, so the next start uses a valid token. Never prints the token.
    pub fn save_refreshed_client_jwt(&self) -> io::Result<()> {
        let client_jwt = self.shared.pending().client_jwt.take();
        match client_jwt {
            Some(client_jwt) => write_private_file(
                &self.state_dir.join(CLIENT_JWT_FILE_NAME),
                format!("{client_jwt}\n").as_bytes(),
            ),
            None => Ok(()),
        }
    }

    /// Applies finished payout wallet reads. The first failure shows unavailable; a later failure
    /// keeps the last known wallet.
    pub fn apply_wallet_reads(&mut self) {
        let wallet_read_succeeded = self.shared.pending().wallet_read_succeeded.take();
        match wallet_read_succeeded {
            None => {}
            Some(false) => {
                if self.payout_wallet == PayoutWallet::Checking {
                    self.payout_wallet = PayoutWallet::Unavailable;
                }
            }
            Some(true) => {
                // the SDK caches the effective wallet before the callback: this client's own
                // consent, else the network consent, else (with hotkey delegations)
                // the network's hotkey entry, else a non-consent wallet
                let wallet_json = unsafe {
                    take_string((self.raw.urnet_device_local_get_sn_wallet)(
                        self.device_handle,
                    ))
                };
                self.payout_wallet = parse_payout_wallet(wallet_json.as_deref(), &self.client_id);
            }
        }
    }

    /// Hands the observer the status every [`STATUS_POLL_INTERVAL`] and rereads the payout wallet
    /// every [`WALLET_SYNC_INTERVAL`], until `events` asks to stop or the server rejects the
    /// credential.
    pub fn run(
        &mut self,
        events: &Receiver<RunEvent>,
        observer: &mut impl ProviderObserver,
    ) -> RunEnd {
        let mut next_wallet_sync_time = Instant::now() + WALLET_SYNC_INTERVAL;
        loop {
            if let Err(error) = self.save_refreshed_client_jwt() {
                observer.warning(&format!(
                    "could not save the refreshed client credential: {error}"
                ));
            }
            self.apply_wallet_reads();
            observer.status(&self.status());
            let now = Instant::now();
            if next_wallet_sync_time <= now {
                self.sync_wallet();
                next_wallet_sync_time = now + WALLET_SYNC_INTERVAL;
            }
            match events.recv_timeout(STATUS_POLL_INTERVAL) {
                Ok(RunEvent::Stop) | Err(RecvTimeoutError::Disconnected) => return RunEnd::Stopped,
                Ok(RunEvent::Logout) => return RunEnd::LoggedOut,
                Err(RecvTimeoutError::Timeout) => {}
            }
        }
    }

    /// Stops providing, closes the subscriptions and the device, then the manager. Returns the
    /// result of saving a credential that was refreshed just before the stop.
    pub fn close(mut self) -> io::Result<()> {
        self.close_device()
    }

    /// The first part of closing, once: provide mode none, subscriptions, device, then the last
    /// refreshed credential. The manager and the remaining handles close when the fields drop.
    fn close_device(&mut self) -> io::Result<()> {
        if self.closed {
            return Ok(());
        }
        self.closed = true;
        unsafe { (self.raw.urnet_device_set_provide_mode)(self.device_handle, PROVIDE_MODE_NONE) };
        self.subs.clear();
        self.device.close();
        // the SDK writes its log files every 30 seconds; keep the lines of a short run and of the
        // close in logs/ too
        unsafe { (self.raw.urnet_flush_glog)() };
        self.save_refreshed_client_jwt()
    }
}

impl Drop for ProviderSession {
    /// Closes a session that was not closed explicitly.
    fn drop(&mut self) {
        let _ = self.close_device();
    }
}

#[cfg(test)]
mod tests {
    //! The callback plumbing at the C ABI boundary, driven directly with synthetic values. No
    //! native runtime: these callbacks never call into the SDK.

    use std::sync::mpsc;

    use super::*;
    use crate::status::CLIENTS_SERVED_LIMIT;

    /// A registered session state, as start makes one.
    fn new_shared() -> (Arc<SessionShared>, Receiver<RunEvent>) {
        let (events, run_events) = mpsc::channel();
        let shared = Arc::new(SessionShared {
            clients_served: Arc::new(ClientsServed::new(CLIENTS_SERVED_LIMIT)),
            events,
            pending: Mutex::new(PendingWork::default()),
        });
        (shared, run_events)
    }

    #[test]
    fn callbacks_reach_their_session() {
        let (shared, run_events) = new_shared();
        let callback_target = CallbackTarget::register(&shared);
        let user_data = callback_target.user_data();
        let receive_json = cr#"{"ContractId":"33333333-3333-3333-3333-333333333333","ContractTransferPath":{"SourceId":"22222222-2222-2222-2222-222222222222","DestinationId":"11111111-1111-1111-1111-111111111111","StreamId":null},"Status":"open"}"#;
        let send_json = cr#"{"ContractId":"55555555-5555-5555-5555-555555555555","ContractTransferPath":{"SourceId":"11111111-1111-1111-1111-111111111111","DestinationId":"22222222-2222-2222-2222-222222222222","StreamId":null},"Status":"open"}"#;
        unsafe {
            provider_ingress_contract_details_changed(user_data, receive_json.as_ptr());
            provider_egress_contract_details_changed(user_data, send_json.as_ptr());
            jwt_refreshed(user_data, c" e30.e30.refreshed \n".as_ptr());
            sn_wallet_synced(user_data, cr#"{"wallet":null}"#.as_ptr(), ptr::null());
            auth_logged_out(user_data);
        }
        // both directions of one client count once
        assert_eq!(shared.clients_served.count(), (1, false));
        assert_eq!(
            shared.pending().client_jwt.as_deref(),
            Some("e30.e30.refreshed")
        );
        assert_eq!(shared.pending().wallet_read_succeeded, Some(true));
        assert_eq!(run_events.try_recv(), Ok(RunEvent::Logout));
    }

    #[test]
    fn wallet_reads_merge_until_applied() {
        let (shared, _run_events) = new_shared();
        let callback_target = CallbackTarget::register(&shared);
        let user_data = callback_target.user_data();
        unsafe {
            sn_wallet_synced(user_data, ptr::null(), c"timeout".as_ptr());
        }
        assert_eq!(shared.pending().wallet_read_succeeded, Some(false));
        // a later success is not lost behind the failure
        unsafe {
            sn_wallet_synced(user_data, c"{}".as_ptr(), ptr::null());
            sn_wallet_synced(
                user_data,
                cr#"{"error":{"message":"unavailable"}}"#.as_ptr(),
                ptr::null(),
            );
        }
        assert_eq!(shared.pending().wallet_read_succeeded, Some(true));
    }

    #[test]
    fn late_callbacks_after_close_do_nothing() {
        let (shared, run_events) = new_shared();
        let callback_target = CallbackTarget::register(&shared);
        let user_data = callback_target.user_data();
        // the session closed: its callback target is gone, but an SDK thread still holds the user
        // data
        drop(callback_target);
        unsafe {
            jwt_refreshed(user_data, c"e30.e30.late".as_ptr());
            sn_wallet_synced(user_data, c"{}".as_ptr(), ptr::null());
            provider_ingress_contract_details_changed(
                user_data,
                cr#"{"ContractTransferPath":{"SourceId":"22222222-2222-2222-2222-222222222222"}}"#
                    .as_ptr(),
            );
            auth_logged_out(user_data);
        }
        assert!(shared.pending().client_jwt.is_none());
        assert!(shared.pending().wallet_read_succeeded.is_none());
        assert_eq!(shared.clients_served.count(), (0, false));
        assert!(run_events.try_recv().is_err());
    }

    #[test]
    fn callbacks_for_a_dropped_session_state_do_nothing() {
        let (shared, run_events) = new_shared();
        let callback_target = CallbackTarget::register(&shared);
        let user_data = callback_target.user_data();
        // the session dropped its state before its callback target, as a session's fields drop
        drop(shared);
        assert!(session_for_callback(user_data).is_none());
        unsafe {
            jwt_refreshed(user_data, c"e30.e30.late".as_ptr());
            auth_logged_out(user_data);
        }
        assert!(run_events.try_recv().is_err());
    }

    #[test]
    fn empty_refreshed_jwt_keeps_the_saved_one() {
        let (shared, _run_events) = new_shared();
        let callback_target = CallbackTarget::register(&shared);
        unsafe {
            jwt_refreshed(callback_target.user_data(), c"  ".as_ptr());
            jwt_refreshed(callback_target.user_data(), ptr::null());
        }
        assert!(shared.pending().client_jwt.is_none());
    }
}
