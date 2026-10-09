//! The embedded Device in the app's Rust core. While it runs, one background thread owns an
//! [`EmbedSession`] of the embed core shared with the Rust console example (`rust/embed`) and
//! publishes an [`EmbedView`] to the window every second as the [`EMBED_VIEW_EVENT`] event. Tauri
//! commands start and stop it through the [`EmbedController`]. The core formats every value; the
//! window only shows them.

use std::{
    path::PathBuf,
    sync::{
        Mutex, MutexGuard, PoisonError,
        mpsc::{self, Receiver, Sender},
    },
    thread::{self, JoinHandle},
};

use tauri::{AppHandle, Emitter, Manager};
use urnetwork_embed::{
    http::DEFAULT_API_ORIGIN,
    session::{self, DeviceInfo, EmbedObserver, EmbedSession, RunEnd, RunEvent},
    state::{LOGS_DIR_NAME, TokenServerFile, load_token_server_file},
    status::EmbedStatus,
};

use crate::embed_state::{
    EMBED_VIEW_EVENT, EmbedView, Finish, ViewState, prepare_start, save_token_server_settings,
};

/// The description and spec recorded for this installation's device.
const DEVICE_INFO: DeviceInfo = DeviceInfo {
    description: "Tauri embed example",
    spec: "urnetwork-examples/tauri-embed",
};

/// Starts and stops the device thread and keeps the latest view. Tauri state, safe for concurrent
/// use: commands call it on the main thread while the device thread publishes the status.
pub struct EmbedController {
    state_dir: PathBuf,
    state: Mutex<ControllerState>,
}

/// The controller's mutable state.
struct ControllerState {
    view: ViewState,
    run: Option<EmbedRun>,
    /// `token-server.json` as last read, for the view's URL and whether a session is saved
    token_server_file: Option<TokenServerFile>,
}

/// The running device thread.
struct EmbedRun {
    /// asks the run loop to stop
    events: Sender<RunEvent>,
    thread: JoinHandle<()>,
}

impl EmbedController {
    /// A stopped device that keeps its installation state in `state_dir`.
    pub fn new(state_dir: PathBuf) -> Self {
        let token_server_file = load_token_server_file(&state_dir).ok().flatten();
        Self {
            state_dir,
            state: Mutex::new(ControllerState {
                view: ViewState::default(),
                run: None,
                token_server_file,
            }),
        }
    }

    /// The state, locked for a short update.
    fn state(&self) -> MutexGuard<'_, ControllerState> {
        self.state.lock().unwrap_or_else(PoisonError::into_inner)
    }

    /// The view of the locked state, with the saved token server settings (never the session).
    fn view_with_lock(&self, state: &ControllerState) -> EmbedView {
        state
            .view
            .view(&self.state_dir, state.token_server_file.as_ref())
    }

    /// The current view.
    pub fn view(&self) -> EmbedView {
        let state = self.state();
        self.view_with_lock(&state)
    }

    /// Saves the window's token server settings, then starts the device on a new thread, unless a
    /// device thread still runs (it may still be stopping). A setting the core refuses is shown
    /// and nothing starts.
    pub fn start(&self, app: &AppHandle, token_server_url: &str, demo_session: &str) -> EmbedView {
        let mut state = self.state();
        if state
            .run
            .as_ref()
            .is_some_and(|run| !run.thread.is_finished())
        {
            return self.view_with_lock(&state);
        }
        if let Err(message) =
            save_token_server_settings(&self.state_dir, token_server_url, demo_session)
        {
            state.view.message = Some(message);
            return self.view_with_lock(&state);
        }
        state.token_server_file = load_token_server_file(&self.state_dir).ok().flatten();
        let (events, run_events) = mpsc::channel();
        let thread_app = app.clone();
        let thread_events = events.clone();
        let thread = thread::spawn(move || run_embed(&thread_app, thread_events, run_events));
        state.run = Some(EmbedRun { events, thread });
        state.view.started();
        self.view_with_lock(&state)
    }

    /// Asks the device thread to stop. The thread publishes the stopped view once the device has
    /// closed.
    pub fn stop(&self) -> EmbedView {
        let state = self.state();
        if let Some(run) = &state.run {
            let _ = run.events.send(RunEvent::Stop);
        }
        self.view_with_lock(&state)
    }

    /// Stops the device and waits until it has closed, before the app exits.
    pub fn stop_and_wait(&self) {
        let run = self.state().run.take();
        if let Some(run) = run {
            let _ = run.events.send(RunEvent::Stop);
            let _ = run.thread.join();
        }
    }

    /// Applies an update and returns the new view.
    fn update(&self, update: impl FnOnce(&mut ViewState)) -> EmbedView {
        let mut state = self.state();
        update(&mut state.view);
        self.view_with_lock(&state)
    }

    /// The view after a run ended: stopped or signed out, with its message.
    fn finished(&self, finish: Finish) -> EmbedView {
        let mut state = self.state();
        state.run = None;
        state.view.finished(finish);
        self.view_with_lock(&state)
    }
}

/// Sends a view to the window. Called without the controller's lock.
fn emit_view(app: &AppHandle, view: EmbedView) {
    let _ = app.emit(EMBED_VIEW_EVENT, view);
}

/// The device thread: one run from the token fetch to close, then the stopped view.
fn run_embed(app: &AppHandle, events: Sender<RunEvent>, run_events: Receiver<RunEvent>) {
    let controller = app.state::<EmbedController>();
    let finish = run_session(app, &controller, events, &run_events);
    emit_view(app, controller.finished(finish));
}

/// Obtains the client JWT (the token server, or the `client.jwt` a backend tool wrote), starts the
/// session and publishes its status until a stop or a credential rejection.
fn run_session(
    app: &AppHandle,
    controller: &EmbedController,
    events: Sender<RunEvent>,
    run_events: &Receiver<RunEvent>,
) -> Finish {
    let state_dir = &controller.state_dir;
    let config = match prepare_start(state_dir) {
        Ok(config) => config,
        Err(finish) => return finish,
    };
    emit_view(
        app,
        controller.update(|view| view.identified(&config.client_id, &config.instance_id)),
    );
    if let Err(error) = session::set_log_dir(&state_dir.join(LOGS_DIR_NAME)) {
        return Finish::Stopped(Some(format!(
            "could not set the sdk log directory: {error}"
        )));
    }
    let mut session =
        match EmbedSession::start(config, DEVICE_INFO, DEFAULT_API_ORIGIN.to_string(), events) {
            Ok(session) => session,
            Err(error) => {
                return Finish::Stopped(Some(format!(
                    "could not start the embedded device: {error}"
                )));
            }
        };
    let end = session.run(run_events, &mut ViewPublisher { app, controller });
    let close_result = session.close();
    match end {
        RunEnd::LoggedOut => Finish::SignedOut(
            "the server rejected the client credential; sign in again so your backend reissues it"
                .to_string(),
        ),
        RunEnd::Stopped => Finish::Stopped(
            close_result
                .err()
                .map(|error| format!("could not save the refreshed client credential: {error}")),
        ),
    }
}

/// Publishes the run loop's status and warnings to the window.
struct ViewPublisher<'a> {
    app: &'a AppHandle,
    controller: &'a EmbedController,
}

impl EmbedObserver for ViewPublisher<'_> {
    /// Keeps the status and sends the view, every second.
    fn status(&mut self, status: &EmbedStatus) {
        let view = self.controller.update(|view| view.session_status(status));
        emit_view(self.app, view);
    }

    /// Shows the warning under the fields.
    fn warning(&mut self, message: &str) {
        let view = self
            .controller
            .update(|view| view.message = Some(message.to_string()));
        emit_view(self.app, view);
    }
}

#[cfg(test)]
mod tests {
    //! The controller's state without a window or the native runtime.

    use super::*;

    #[test]
    fn a_new_controller_is_stopped() {
        let controller = EmbedController::new(PathBuf::from("/state/embed"));
        let view = controller.view();
        assert!(!view.running);
        assert_eq!(
            [
                view.status.as_str(),
                &view.data_this_month,
                &view.data_total
            ],
            ["stopped", "checking", "checking"]
        );
        // stopping without a device thread changes nothing
        assert_eq!(controller.stop(), view);
        controller.stop_and_wait();
    }

    #[test]
    fn a_finished_run_shows_its_message() {
        let controller = EmbedController::new(PathBuf::from("/state/embed"));
        controller.update(ViewState::started);
        let view = controller.finished(Finish::SignedOut("synthetic sign-out".to_string()));
        assert_eq!(view.status, "signed out");
        assert_eq!(view.message.as_deref(), Some("synthetic sign-out"));
    }
}
