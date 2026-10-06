//! The provider in the app's Rust core. While providing, one background thread owns a
//! [`ProviderSession`] of the provider core shared with the Rust console example (`rust/provider`)
//! and publishes a [`ProviderView`] to the window every second as the [`PROVIDER_VIEW_EVENT`]
//! event. Tauri commands and the tray start and stop it through the [`ProviderController`]. The
//! core formats every value; the window only shows them.

use std::{
    path::{Path, PathBuf},
    sync::{
        Arc, Mutex, MutexGuard, PoisonError,
        mpsc::{self, Receiver, Sender},
    },
    thread::{self, JoinHandle},
};

use serde::Serialize;
use tauri::{AppHandle, Emitter, Manager};
use urnetwork_provider::{
    session::{self, DeviceInfo, ProviderObserver, ProviderSession, RunEnd, RunEvent},
    state::{LOGS_DIR_NAME, create_private_dir, load_provider_config},
    status::{CLIENTS_SERVED_LIMIT, ClientsServed, PayoutWallet, ProviderState, ProviderStatus},
};

/// The event that carries a [`ProviderView`] to the window.
pub const PROVIDER_VIEW_EVENT: &str = "provider-view";

/// The description and spec recorded for this installation's device.
const DEVICE_INFO: DeviceInfo = DeviceInfo {
    description: "Tauri provider example",
    spec: "urnetwork-examples/tauri-provider",
};

/// What the window shows: the four status fields as the provider core formats them, and the
/// controls' state.
#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ProviderView {
    /// a provider thread runs: start is disabled and stop enabled
    pub running: bool,
    pub status: String,
    pub clients_served: String,
    pub data_provided: String,
    pub payout_wallet: String,
    /// a configuration problem, a credential rejection or a warning
    pub message: Option<String>,
    /// the private installation state directory, where `client.jwt` goes
    pub state_dir: String,
}

impl ProviderView {
    /// The labeled values of one status.
    pub fn new(
        status: &ProviderStatus,
        running: bool,
        message: Option<String>,
        state_dir: &Path,
    ) -> Self {
        Self {
            running,
            status: status.status_text(),
            clients_served: status.clients_served_text(),
            data_provided: status.data_provided_text(),
            payout_wallet: status.payout_wallet_text(),
            message,
            state_dir: state_dir.display().to_string(),
        }
    }
}

/// Starts and stops the provider thread and keeps the latest view. Tauri state, safe for concurrent
/// use: commands and the tray call it on the main thread while the provider thread publishes the
/// status.
pub struct ProviderController {
    state_dir: PathBuf,
    /// distinct clients since the app started, across stops and starts
    clients_served: Arc<ClientsServed>,
    state: Mutex<ControllerState>,
}

/// The controller's mutable state.
struct ControllerState {
    /// the last status; a stopped provider keeps its counts and wallet
    status: ProviderStatus,
    running: bool,
    message: Option<String>,
    run: Option<ProviderRun>,
}

/// The running provider thread.
struct ProviderRun {
    /// asks the run loop to stop
    events: Sender<RunEvent>,
    thread: JoinHandle<()>,
}

impl ProviderController {
    /// A stopped provider that keeps its installation state in `state_dir`.
    pub fn new(state_dir: PathBuf) -> Self {
        Self {
            state_dir,
            clients_served: Arc::new(ClientsServed::new(CLIENTS_SERVED_LIMIT)),
            state: Mutex::new(ControllerState {
                status: ProviderStatus::stopped(),
                running: false,
                message: None,
                run: None,
            }),
        }
    }

    /// The state, locked for a short update.
    fn state(&self) -> MutexGuard<'_, ControllerState> {
        self.state.lock().unwrap_or_else(PoisonError::into_inner)
    }

    /// The view of the locked state.
    fn view_with_lock(&self, state: &ControllerState) -> ProviderView {
        ProviderView::new(
            &state.status,
            state.running,
            state.message.clone(),
            &self.state_dir,
        )
    }

    /// The current view.
    pub fn view(&self) -> ProviderView {
        let state = self.state();
        self.view_with_lock(&state)
    }

    /// Starts providing on a new thread, unless a provider thread still runs (it may still be
    /// stopping).
    pub fn start(&self, app: &AppHandle) -> ProviderView {
        let mut state = self.state();
        if state
            .run
            .as_ref()
            .is_some_and(|run| !run.thread.is_finished())
        {
            return self.view_with_lock(&state);
        }
        let (events, run_events) = mpsc::channel();
        let thread_app = app.clone();
        let thread_events = events.clone();
        let thread = thread::spawn(move || run_provider(&thread_app, thread_events, run_events));
        state.run = Some(ProviderRun { events, thread });
        self.started_with_lock(&mut state)
    }

    /// Marks the state as starting a new session: the data counter and the wallet start over, the
    /// clients-served count continues.
    fn started_with_lock(&self, state: &mut ControllerState) -> ProviderView {
        state.running = true;
        state.message = None;
        state.status = ProviderStatus {
            state: ProviderState::Starting,
            client_limit_retry_time: 0,
            data_provided_byte_count: 0,
            payout_wallet: PayoutWallet::Checking,
            ..state.status.clone()
        };
        self.view_with_lock(state)
    }

    /// Asks the provider thread to stop. The thread publishes the stopped view once the device has
    /// closed.
    pub fn stop(&self) -> ProviderView {
        let state = self.state();
        if let Some(run) = &state.run {
            let _ = run.events.send(RunEvent::Stop);
        }
        self.view_with_lock(&state)
    }

    /// Stops providing and waits until the device has closed, before the app exits.
    pub fn stop_and_wait(&self) {
        let run = self.state().run.take();
        if let Some(run) = run {
            let _ = run.events.send(RunEvent::Stop);
            let _ = run.thread.join();
        }
    }

    /// Applies an update and returns the new view.
    fn update(&self, update: impl FnOnce(&mut ControllerState)) -> ProviderView {
        let mut state = self.state();
        update(&mut state);
        self.view_with_lock(&state)
    }

    /// The view after a session ended: stopped, with its last counts and wallet, and the message to
    /// show.
    fn finished(&self, message: Option<String>) -> ProviderView {
        self.update(|state| {
            state.running = false;
            state.run = None;
            state.message = message;
            state.status.state = ProviderState::Stopped;
            state.status.client_limit_retry_time = 0;
        })
    }
}

/// Sends a view to the window. Called without the controller's lock.
fn emit_view(app: &AppHandle, view: ProviderView) {
    let _ = app.emit(PROVIDER_VIEW_EVENT, view);
}

/// The provider thread: one session from start to close, then the stopped view.
fn run_provider(app: &AppHandle, events: Sender<RunEvent>, run_events: Receiver<RunEvent>) {
    let controller = app.state::<ProviderController>();
    let message = run_session(app, &controller, events, &run_events).unwrap_or_else(Some);
    emit_view(app, controller.finished(message));
}

/// Loads the installation state, starts the session and publishes its status until a stop or a
/// credential rejection. Returns the message to show after the session closed, or the error that
/// kept it from starting.
fn run_session(
    app: &AppHandle,
    controller: &ProviderController,
    events: Sender<RunEvent>,
    run_events: &Receiver<RunEvent>,
) -> Result<Option<String>, String> {
    let state_dir = &controller.state_dir;
    create_private_dir(state_dir)
        .map_err(|error| format!("could not create the state directory: {error}"))?;
    let config = load_provider_config(state_dir).map_err(|error| error.to_string())?;
    session::set_log_dir(&state_dir.join(LOGS_DIR_NAME))
        .map_err(|error| format!("could not set the sdk log directory: {error}"))?;
    let mut session = ProviderSession::start(
        config,
        DEVICE_INFO,
        controller.clients_served.clone(),
        events,
    )
    .map_err(|error| format!("could not start the provider: {error}"))?;
    let end = session.run(run_events, &mut ViewPublisher { app, controller });
    let close_result = session.close();
    Ok(match end {
        RunEnd::LoggedOut => Some(
            "the server rejected the client credential; issue a new scoped client JWT from your backend".to_string(),
        ),
        RunEnd::Stopped => close_result
            .err()
            .map(|error| format!("could not save the refreshed client credential: {error}")),
    })
}

/// Publishes the run loop's status and warnings to the window.
struct ViewPublisher<'a> {
    app: &'a AppHandle,
    controller: &'a ProviderController,
}

impl ProviderObserver for ViewPublisher<'_> {
    /// Keeps the status and sends the view, every second.
    fn status(&mut self, status: &ProviderStatus) {
        let view = self
            .controller
            .update(|state| state.status = status.clone());
        emit_view(self.app, view);
    }

    /// Shows the warning under the fields.
    fn warning(&mut self, message: &str) {
        let view = self
            .controller
            .update(|state| state.message = Some(message.to_string()));
        emit_view(self.app, view);
    }
}

#[cfg(test)]
mod tests {
    //! The views and the controller's state changes, without a window or the native runtime.

    use urnetwork_provider::status::PayoutWalletScope;

    use super::*;

    /// The public Substrate development account, used only as test data.
    const TEST_COLDKEY_SS58: &str = "5GrwvaEF5zXb26Fz9rcQpDWS57CtERHpNehXCPcNoHGKutQY";

    /// A status with the given fields and the rest zero.
    fn test_status(state: ProviderState, payout_wallet: PayoutWallet) -> ProviderStatus {
        ProviderStatus {
            state,
            payout_wallet,
            ..ProviderStatus::stopped()
        }
    }

    /// The test coldkey with a scope.
    fn test_wallet(scope: PayoutWalletScope) -> PayoutWallet {
        PayoutWallet::Mapped {
            coldkey_ss58: TEST_COLDKEY_SS58.to_string(),
            scope,
        }
    }

    #[test]
    fn views_show_the_contract_texts() {
        let state_dir = Path::new("/state/provider");
        let cases = [
            (
                ProviderStatus {
                    clients_served: 3,
                    data_provided_byte_count: 13002342,
                    ..test_status(
                        ProviderState::Providing,
                        test_wallet(PayoutWalletScope::Network),
                    )
                },
                [
                    "providing",
                    "3",
                    "12.4 MiB",
                    "5GrwvaEF5zXb26Fz9rcQpDWS57CtERHpNehXCPcNoHGKutQY (network)",
                ],
            ),
            (
                ProviderStatus {
                    clients_served: CLIENTS_SERVED_LIMIT,
                    clients_served_at_limit: true,
                    data_provided_byte_count: 1536,
                    ..test_status(
                        ProviderState::Paused,
                        test_wallet(PayoutWalletScope::ThisProvider),
                    )
                },
                [
                    "paused",
                    "100000+",
                    "1.5 KiB",
                    "5GrwvaEF5zXb26Fz9rcQpDWS57CtERHpNehXCPcNoHGKutQY (this provider)",
                ],
            ),
            (
                ProviderStatus {
                    client_limit_retry_time: 1791313440001,
                    ..test_status(
                        ProviderState::ClientLimit,
                        test_wallet(PayoutWalletScope::Hotkey),
                    )
                },
                [
                    "client limit, retry at 19:05 UTC",
                    "0",
                    "0 B",
                    "5GrwvaEF5zXb26Fz9rcQpDWS57CtERHpNehXCPcNoHGKutQY (hotkey)",
                ],
            ),
            (
                test_status(ProviderState::Starting, PayoutWallet::Checking),
                ["starting", "0", "0 B", "checking"],
            ),
            (
                test_status(ProviderState::Stopped, PayoutWallet::NotSet),
                ["stopped", "0", "0 B", "not set"],
            ),
            // exact halves round to the even tenth, as in the console
            (
                ProviderStatus {
                    data_provided_byte_count: 1280,
                    ..test_status(ProviderState::Providing, PayoutWallet::NotSet)
                },
                ["providing", "0", "1.2 KiB", "not set"],
            ),
            (
                ProviderStatus {
                    data_provided_byte_count: 1792,
                    ..test_status(ProviderState::Providing, PayoutWallet::NotSet)
                },
                ["providing", "0", "1.8 KiB", "not set"],
            ),
        ];
        for (status, [status_text, clients_served, data_provided, payout_wallet]) in cases {
            let view = ProviderView::new(&status, true, None, state_dir);
            assert_eq!(
                [
                    view.status.as_str(),
                    &view.clients_served,
                    &view.data_provided,
                    &view.payout_wallet
                ],
                [status_text, clients_served, data_provided, payout_wallet]
            );
        }
    }

    #[test]
    fn view_names_match_the_window_script() {
        let view = ProviderView::new(
            &ProviderStatus::stopped(),
            false,
            None,
            Path::new("/state/provider"),
        );
        let json = serde_json::to_value(&view).unwrap();
        let mut names: Vec<&str> = json
            .as_object()
            .unwrap()
            .keys()
            .map(String::as_str)
            .collect();
        names.sort();
        assert_eq!(
            names,
            [
                "clientsServed",
                "dataProvided",
                "message",
                "payoutWallet",
                "running",
                "stateDir",
                "status"
            ]
        );
        let script = include_str!("../../ui/main.js");
        for name in names {
            assert!(
                script.contains(&format!("view.{name}")),
                "main.js does not show view.{name}"
            );
        }
        assert!(script.contains(&format!("'{PROVIDER_VIEW_EVENT}'")));
    }

    #[test]
    fn a_new_controller_is_stopped() {
        let controller = ProviderController::new(PathBuf::from("/state/provider"));
        let view = controller.view();
        assert!(!view.running);
        assert_eq!(
            [
                view.status.as_str(),
                &view.clients_served,
                &view.data_provided,
                &view.payout_wallet
            ],
            ["stopped", "0", "0 B", "checking"]
        );
        // stopping without a provider thread changes nothing
        assert_eq!(controller.stop(), view);
        controller.stop_and_wait();
    }

    #[test]
    fn a_new_session_keeps_the_clients_served_count() {
        let controller = ProviderController::new(PathBuf::from("/state/provider"));
        controller.update(|state| {
            state.status = ProviderStatus {
                clients_served: 2,
                data_provided_byte_count: 4096,
                ..test_status(
                    ProviderState::Providing,
                    test_wallet(PayoutWalletScope::Network),
                )
            };
        });
        let stopped = controller.finished(Some("synthetic warning".to_string()));
        assert!(!stopped.running);
        assert_eq!(
            [
                stopped.status.as_str(),
                &stopped.clients_served,
                &stopped.data_provided
            ],
            ["stopped", "2", "4.0 KiB"]
        );
        assert_eq!(stopped.message.as_deref(), Some("synthetic warning"));
        let started = controller.update(|state| {
            controller.started_with_lock(state);
        });
        assert!(started.running);
        assert_eq!(started.message, None);
        assert_eq!(
            [
                started.status.as_str(),
                &started.clients_served,
                &started.data_provided,
                &started.payout_wallet
            ],
            ["starting", "2", "0 B", "checking"]
        );
    }

    #[test]
    fn a_client_limit_hold_ends_with_the_session() {
        let controller = ProviderController::new(PathBuf::from("/state/provider"));
        controller.update(|state| {
            state.running = true;
            state.status = ProviderStatus {
                client_limit_retry_time: 1791313500000,
                ..test_status(ProviderState::ClientLimit, PayoutWallet::Checking)
            };
        });
        assert_eq!(controller.view().status, "client limit, retry at 19:05 UTC");
        assert_eq!(controller.finished(None).status, "stopped");
    }
}
