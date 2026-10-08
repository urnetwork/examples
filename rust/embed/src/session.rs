//! A running embedded Device over the SDK's C ABI (the `urnetwork-sdk` crate's raw function table):
//! the network space manager, the local Device with the installation's scoped client JWT, and the
//! listeners that feed its status (EMBED_CONTRACT.md, "App lifecycle"). The Device routes only the
//! app's own traffic; it does not provide.
//!
//! C ABI callbacks run on SDK threads. They copy what they carry before returning and leave the
//! rest to the thread that runs the session: the refreshed JWT and a contract change are recorded
//! for the run loop, and an auth logout wakes it. A callback's user data is a token that names its
//! session, not a pointer: a callback that arrives after the session closed finds no session and
//! returns.
//!
//! The caps are read over HTTP with the client JWT on a short-lived thread at start, every 5
//! minutes, and within 5 seconds of a contract change, so a slow API never stalls the status.

use std::{
    collections::BTreeMap,
    ffi::{CStr, CString, c_char, c_void},
    io,
    path::{Path, PathBuf},
    ptr,
    sync::{
        Arc, Mutex, MutexGuard, PoisonError, Weak,
        atomic::{AtomicUsize, Ordering},
        mpsc::{self, Receiver, RecvTimeoutError, Sender},
    },
    thread,
    time::{Duration, Instant},
};

use urnetwork_sdk::{Device, Handle, native, raw::Raw, take_string};

use crate::{
    caps::{CapReading, DataCap, read_own_data_cap},
    config::EmbedConfig,
    sdk_json::{licenses_app_kind, parse_client_limit_status, parse_providers_added},
    state::{create_private_dir, save_client_jwt},
    status::{EmbedStatus, StatusInputs},
};

/// How often the run loop reads the status.
pub const STATUS_POLL_INTERVAL: Duration = Duration::from_secs(1);

/// How often the caps are read without a contract change.
pub const CAP_READ_INTERVAL: Duration = Duration::from_secs(5 * 60);

/// The shortest time between two cap reads. A contract change is read again within 5 seconds: at
/// most this long after the last read, plus one status poll.
pub const CAP_READ_MIN_INTERVAL: Duration = Duration::from_secs(3);

/// The app version recorded with the device.
const APP_VERSION: &CStr = c"1";

/// The `ur.network`/`main` network space.
const NETWORK_SPACE_KEY_JSON: &CStr = cr#"{"host_name":"ur.network","env_name":"main"}"#;

/// The network space's migration host.
const NETWORK_SPACE_VALUES_JSON: &CStr = cr#"{"migration_host_name":"bringyour.com"}"#;

/// The destination of the app's own traffic: the best available location.
const CONNECT_LOCATION_BEST_AVAILABLE_JSON: &CStr =
    cr#"{"connect_location_id":{"best_available":true}}"#;

/// The description and spec recorded for this installation's device, naming the example.
#[derive(Clone, Copy, Debug)]
pub struct DeviceInfo {
    pub description: &'static str,
    pub spec: &'static str,
}

/// A request to the run loop, from a signal handler, the app or an SDK callback.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum RunEvent {
    /// Stop, as the user asked.
    Stop,
    /// The server rejected the client credential.
    Logout,
}

/// Why a run ended.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum RunEnd {
    /// A requested stop (exit 0).
    Stopped,
    /// The server rejected the client credential: a restart does not help (exit 78; a GUI shows
    /// `signed out`).
    LoggedOut,
}

/// Receives what the run loop reads, on the thread that runs the session.
pub trait EmbedObserver {
    /// The status, read every [`STATUS_POLL_INTERVAL`].
    fn status(&mut self, status: &EmbedStatus);

    /// A problem the device keeps running through, such as a refreshed credential it could not
    /// save.
    fn warning(&mut self, message: &str);
}

/// The session state that SDK callbacks reach through [`CALLBACK_TARGETS`].
struct SessionShared {
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
    /// a contract status changed since the run loop last looked: read the caps again
    contract_changed: bool,
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

/// Records a contract status change: the caps may have changed.
unsafe extern "C" fn contract_status_changed(user_data: *mut c_void, _json: *const c_char) {
    if let Some(shared) = session_for_callback(user_data) {
        shared.pending().contract_changed = true;
    }
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

/// Keeps the SDK's log files in `log_dir`, which the SDK bounds, instead of the system temp
/// directory. The SDK still copies its log lines to stderr. Loads the native runtime.
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

/// The SDK's licenses and data attributions for this OS's app kind, as JSON
/// (EMBED_CONTRACT.md, "License"). Publish them with the app. Loads the native runtime; needs no
/// network.
pub fn licenses_json() -> io::Result<String> {
    let raw = native()?;
    let app_kind = CString::new(licenses_app_kind())?;
    unsafe { take_string((raw.urnet_get_licenses)(app_kind.as_ptr())) }
        .ok_or_else(|| io::Error::other("the SDK returned no licenses"))
}

/// When the caps are read: at start, every [`CAP_READ_INTERVAL`], and soon after a contract change,
/// never two reads within [`CAP_READ_MIN_INTERVAL`] and never two at once. Pure, so the self-test
/// checks it with synthetic times.
#[derive(Debug)]
pub struct CapReadSchedule {
    next_read_time: Instant,
    last_read_time: Option<Instant>,
    in_flight: bool,
    reread_pending: bool,
}

impl CapReadSchedule {
    /// The first read is due at `now`.
    pub fn new(now: Instant) -> Self {
        Self {
            next_read_time: now,
            last_read_time: None,
            in_flight: false,
            reread_pending: false,
        }
    }

    /// The earliest time a read may start: now, or the minimum interval after the last read.
    fn soonest(&self, now: Instant) -> Instant {
        match self.last_read_time {
            Some(last_read_time) => now.max(last_read_time + CAP_READ_MIN_INTERVAL),
            None => now,
        }
    }

    /// A contract status changed: read again soon, after the read in flight if there is one.
    pub fn contract_changed(&mut self, now: Instant) {
        if self.in_flight {
            self.reread_pending = true;
        } else {
            self.next_read_time = self.next_read_time.min(self.soonest(now));
        }
    }

    /// Whether a read starts now; if so, it is marked in flight.
    pub fn start_due(&mut self, now: Instant) -> bool {
        if self.in_flight || now < self.next_read_time {
            return false;
        }
        self.in_flight = true;
        self.last_read_time = Some(now);
        self.next_read_time = now + CAP_READ_INTERVAL;
        true
    }

    /// The read in flight finished.
    pub fn finished(&mut self, now: Instant) {
        self.in_flight = false;
        if self.reread_pending {
            self.reread_pending = false;
            self.next_read_time = self.next_read_time.min(self.soonest(now));
        }
    }

    /// When the next read is due.
    pub fn next_read_time(&self) -> Instant {
        self.next_read_time
    }
}

/// One run of the embedded Device. [`EmbedSession::start`] creates it and connects to the best
/// available location; [`EmbedSession::run`] shows the status; [`EmbedSession::close`] (or drop)
/// stops it. Used from one thread; callbacks reach only its shared state.
pub struct EmbedSession {
    raw: &'static Raw,
    state_dir: PathBuf,
    api_origin: String,
    client_id: String,
    instance_id: String,
    /// a bearer secret, the newest one: never print or log it
    client_jwt: String,
    device_handle: u64,
    cap_reading: CapReading,
    shared: Arc<SessionShared>,
    closed: bool,
    // dropped in this order after close: subscriptions, device, callback target, api, space,
    // manager
    subs: Vec<Sub>,
    device: Device,
    _callback_target: CallbackTarget,
    _api: Handle,
    _space: Handle,
    _manager: NetworkSpaceManager,
}

impl EmbedSession {
    /// Creates the network space manager and the local Device with the installation's scoped client
    /// JWT, adds the listeners and connects to the best available location. The Device keeps the
    /// default provide mode: an embed app does not provide. `events` is the sender of the receiver
    /// that [`EmbedSession::run`] takes.
    pub fn start(
        config: EmbedConfig,
        device_info: DeviceInfo,
        api_origin: String,
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

        let mut error = ptr::null_mut();
        let device_handle = unsafe {
            (raw.urnet_new_device_local_with_defaults)(
                space.value()?,
                client_jwt.as_ptr(),
                description.as_ptr(),
                spec.as_ptr(),
                APP_VERSION.as_ptr(),
                instance_id.as_ptr(),
                false,
                &mut error,
            )
        };
        if let Some(message) = unsafe { take_string(error) } {
            return Err(io::Error::other(message));
        }
        let device = unsafe { Device::from_owned(device_handle)? };

        let shared = Arc::new(SessionShared {
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
                    (raw.urnet_device_add_contract_status_change_listener)(
                        device_handle,
                        Some(contract_status_changed),
                        user_data,
                    ),
                    "contract status",
                )?,
            ]
        };
        unsafe {
            (raw.urnet_device_set_connect_location)(
                device_handle,
                CONNECT_LOCATION_BEST_AVAILABLE_JSON.as_ptr(),
            )
        };

        let cap_reading = match config.initial_data_cap {
            Some(data_cap) => CapReading::Read(data_cap),
            None => CapReading::Checking,
        };
        Ok(Self {
            raw,
            state_dir: config.state_dir,
            api_origin,
            client_id: config.client_id,
            instance_id: config.instance_id,
            client_jwt: config.client_jwt,
            device_handle,
            cap_reading,
            shared,
            closed: false,
            subs,
            device,
            _callback_target: callback_target,
            _api: api,
            _space: space,
            _manager: manager,
        })
    }

    /// This installation's client.
    pub fn client_id(&self) -> &str {
        &self.client_id
    }

    /// This installation's instance id.
    pub fn instance_id(&self) -> &str {
        &self.instance_id
    }

    /// Reads the status from the device getters and the latest cap reading.
    pub fn status(&self) -> EmbedStatus {
        let raw = self.raw;
        let device = self.device_handle;
        // "client_limit_exceeded" with the hold's end in RetryTime while the platform holds this
        // client off for its network's concurrent client limit
        let client_limit_status_json =
            unsafe { take_string((raw.urnet_device_get_client_limit_status)(device)) };
        let (client_limit_status, client_limit_retry_time) =
            parse_client_limit_status(client_limit_status_json.as_deref());
        let window_status_json =
            unsafe { take_string((raw.urnet_device_get_window_status)(device)) };
        EmbedStatus::new(&StatusInputs {
            started: true,
            signed_out: false,
            client_limit_status,
            client_limit_retry_time,
            cap_reading: self.cap_reading.clone(),
            providers_added: parse_providers_added(window_status_json.as_deref()),
        })
    }

    /// Saves a refreshed client JWT, so the next start uses a valid token, and uses it for the cap
    /// reads. Never prints the token.
    pub fn save_refreshed_client_jwt(&mut self) -> io::Result<()> {
        let client_jwt = self.shared.pending().client_jwt.take();
        match client_jwt {
            Some(client_jwt) => {
                save_client_jwt(&self.state_dir, &client_jwt)?;
                self.client_jwt = client_jwt;
                Ok(())
            }
            None => Ok(()),
        }
    }

    /// Starts a cap read on its own thread; the result arrives on `results`.
    fn spawn_cap_read(&self, results: &Sender<Result<DataCap, String>>) {
        let api_origin = self.api_origin.clone();
        let client_jwt = self.client_jwt.clone();
        let results = results.clone();
        thread::spawn(move || {
            // the receiver may be gone when the session closed meanwhile
            let _ = results.send(read_own_data_cap(&api_origin, &client_jwt));
        });
    }

    /// Hands the observer the status every [`STATUS_POLL_INTERVAL`] and reads the caps when they
    /// are due, until `events` asks to stop or the server rejects the credential.
    pub fn run(
        &mut self,
        events: &Receiver<RunEvent>,
        observer: &mut impl EmbedObserver,
    ) -> RunEnd {
        let (cap_results, cap_results_receiver) = mpsc::channel();
        let mut schedule = CapReadSchedule::new(Instant::now());
        loop {
            if let Err(error) = self.save_refreshed_client_jwt() {
                observer.warning(&format!(
                    "could not save the refreshed client credential: {error}"
                ));
            }
            let now = Instant::now();
            while let Ok(reading) = cap_results_receiver.try_recv() {
                self.cap_reading.apply(reading);
                schedule.finished(now);
            }
            if std::mem::take(&mut self.shared.pending().contract_changed) {
                schedule.contract_changed(now);
            }
            if schedule.start_due(now) {
                self.spawn_cap_read(&cap_results);
            }
            observer.status(&self.status());
            match events.recv_timeout(STATUS_POLL_INTERVAL) {
                Ok(RunEvent::Stop) | Err(RecvTimeoutError::Disconnected) => return RunEnd::Stopped,
                Ok(RunEvent::Logout) => return RunEnd::LoggedOut,
                Err(RecvTimeoutError::Timeout) => {}
            }
        }
    }

    /// Closes the subscriptions and the device, then the manager. Returns the result of saving a
    /// credential that was refreshed just before the stop.
    pub fn close(mut self) -> io::Result<()> {
        self.close_device()
    }

    /// The first part of closing, once: subscriptions, device, then the last refreshed credential.
    /// The manager and the remaining handles close when the fields drop.
    fn close_device(&mut self) -> io::Result<()> {
        if self.closed {
            return Ok(());
        }
        self.closed = true;
        self.subs.clear();
        self.device.close();
        // the SDK writes its log files periodically; keep the lines of a short run and of the close
        unsafe { (self.raw.urnet_flush_glog)() };
        self.save_refreshed_client_jwt()
    }
}

impl Drop for EmbedSession {
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

    /// A registered session state, as start makes one.
    fn new_shared() -> (Arc<SessionShared>, Receiver<RunEvent>) {
        let (events, run_events) = mpsc::channel();
        let shared = Arc::new(SessionShared {
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
        unsafe {
            jwt_refreshed(user_data, c" e30.e30.refreshed \n".as_ptr());
            contract_status_changed(user_data, cr#"{"InsufficientBalance":false}"#.as_ptr());
            auth_logged_out(user_data);
        }
        assert_eq!(
            shared.pending().client_jwt.as_deref(),
            Some("e30.e30.refreshed")
        );
        assert!(shared.pending().contract_changed);
        assert_eq!(run_events.try_recv(), Ok(RunEvent::Logout));
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
            contract_status_changed(user_data, ptr::null());
            auth_logged_out(user_data);
        }
        assert!(shared.pending().client_jwt.is_none());
        assert!(!shared.pending().contract_changed);
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
