//! The window's state without Tauri: the view the window shows, how starts, stops, sign-outs and
//! failures change it, and the start's preparation (the state directory, `token-server.json` and the
//! client JWT). Pure apart from the state files and the token fetch, so its tests run without a
//! window or the native runtime, with a loopback stand-in for the token server.

use std::path::Path;

use serde::Serialize;
use urnetwork_embed::{
    config::{EmbedConfig, StartError, load_embed_config},
    state::{
        TOKEN_SERVER_FILE_NAME, TokenServerFile, create_private_dir, load_token_server_file,
        save_token_server_file,
    },
    status::{EmbedStatus, StatusInputs, status_text},
    token::TokenServer,
};

/// What the window shows: the status fields as the core formats them, the installation's identity,
/// the token server settings without the session, and the controls' state.
#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct EmbedView {
    /// a device thread runs: start is disabled and stop enabled
    pub running: bool,
    pub status: String,
    pub data_this_month: String,
    pub data_total: String,
    /// empty until the client JWT is known
    pub client_id: String,
    /// empty until the installation state is loaded
    pub installation_id: String,
    /// a configuration problem, a token server refusal, a credential rejection or a warning
    pub message: Option<String>,
    /// the private installation state directory
    pub state_dir: String,
    /// the saved token server origin, to fill the field
    pub token_server_url: String,
    /// whether a demo session is saved; the session itself never goes back to the window
    pub has_demo_session: bool,
}

/// How a run ended, for the stopped view.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Finish {
    /// A requested stop, or a failure that a restart may fix: `stopped`, with an optional message.
    Stopped(Option<String>),
    /// An auth logout, or a token server refusal (401, 409): `signed out` until started again.
    SignedOut(String),
}

impl From<StartError> for Finish {
    /// A token server refusal signs out; every other start failure stops with its message.
    fn from(error: StartError) -> Self {
        if error.signs_out() {
            Finish::SignedOut(error.to_string())
        } else {
            Finish::Stopped(Some(error.to_string()))
        }
    }
}

/// The view's state, kept by the controller.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ViewState {
    pub running: bool,
    pub signed_out: bool,
    /// the running session's status text
    pub session_status: String,
    pub data_this_month: String,
    pub data_total: String,
    pub client_id: String,
    pub installation_id: String,
    pub message: Option<String>,
}

impl Default for ViewState {
    /// Before the first start: `stopped`, the data fields `checking`.
    fn default() -> Self {
        let status = EmbedStatus::new(&StatusInputs::stopped());
        Self {
            running: false,
            signed_out: false,
            session_status: status.status,
            data_this_month: status.data_this_month,
            data_total: status.data_total,
            client_id: String::new(),
            installation_id: String::new(),
            message: None,
        }
    }
}

impl ViewState {
    /// The status field: the running session's, or `signed out` or `stopped` by the status rules.
    pub fn status_text(&self) -> String {
        if self.running {
            return self.session_status.clone();
        }
        status_text(&StatusInputs {
            signed_out: self.signed_out,
            ..StatusInputs::stopped()
        })
    }

    /// A new start: signed out ends, the data fields check again.
    pub fn started(&mut self) {
        let status = EmbedStatus::new(&StatusInputs::started());
        self.running = true;
        self.signed_out = false;
        self.message = None;
        self.session_status = status.status;
        self.data_this_month = status.data_this_month;
        self.data_total = status.data_total;
    }

    /// The installation's identity, once the client JWT is known.
    pub fn identified(&mut self, client_id: &str, installation_id: &str) {
        self.client_id = client_id.to_string();
        self.installation_id = installation_id.to_string();
    }

    /// The running session's status, every second.
    pub fn session_status(&mut self, status: &EmbedStatus) {
        self.session_status = status.status.clone();
        self.data_this_month = status.data_this_month.clone();
        self.data_total = status.data_total.clone();
    }

    /// The run ended: stopped or signed out, with its message. The data fields keep their last
    /// values.
    pub fn finished(&mut self, finish: Finish) {
        self.running = false;
        match finish {
            Finish::Stopped(message) => {
                self.signed_out = false;
                self.message = message;
            }
            Finish::SignedOut(message) => {
                self.signed_out = true;
                self.message = Some(message);
            }
        }
    }

    /// The view of this state.
    pub fn view(&self, state_dir: &Path, token_server_file: Option<&TokenServerFile>) -> EmbedView {
        EmbedView {
            running: self.running,
            status: self.status_text(),
            data_this_month: self.data_this_month.clone(),
            data_total: self.data_total.clone(),
            client_id: self.client_id.clone(),
            installation_id: self.installation_id.clone(),
            message: self.message.clone(),
            state_dir: state_dir.display().to_string(),
            token_server_url: token_server_file
                .map(|file| file.url.clone())
                .unwrap_or_default(),
            has_demo_session: token_server_file.is_some_and(|file| !file.session.is_empty()),
        }
    }
}

/// Saves the window's token server settings in `token-server.json`. Both fields empty removes the
/// file, so the app uses the `client.jwt` a backend tool wrote. An empty session keeps the saved
/// one.
pub fn save_token_server_settings(
    state_dir: &Path,
    url: &str,
    session: &str,
) -> Result<(), String> {
    let url = url.trim();
    let session = session.trim();
    create_private_dir(state_dir)
        .map_err(|error| format!("could not create the state directory: {error}"))?;
    let saved = load_token_server_file(state_dir).map_err(|error| error.to_string())?;
    if url.is_empty() && session.is_empty() {
        return match std::fs::remove_file(state_dir.join(TOKEN_SERVER_FILE_NAME)) {
            Ok(()) => Ok(()),
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => Ok(()),
            Err(error) => Err(format!(
                "could not remove {TOKEN_SERVER_FILE_NAME}: {error}"
            )),
        };
    }
    let session = if session.is_empty() {
        saved
            .map(|file| file.session)
            .filter(|session| !session.is_empty())
            .ok_or_else(|| "enter the demo session from your token server".to_string())?
    } else {
        session.to_string()
    };
    // the same rules as the start: an HTTPS origin or loopback HTTP, one session token
    TokenServer::new(url, &session).map_err(|error| error.to_string())?;
    save_token_server_file(
        state_dir,
        &TokenServerFile {
            url: url.to_string(),
            session,
        },
    )
    .map_err(|error| format!("could not save {TOKEN_SERVER_FILE_NAME}: {error}"))
}

/// The start's preparation: creates the private state directory, reads `token-server.json`, and
/// obtains the client JWT from the token server (or uses the `client.jwt` in the state). A
/// refusal signs out; any other problem stops with its message.
pub fn prepare_start(state_dir: &Path) -> Result<EmbedConfig, Finish> {
    create_private_dir(state_dir).map_err(|error| {
        Finish::Stopped(Some(format!(
            "could not create the state directory: {error}"
        )))
    })?;
    let token_server = match load_token_server_file(state_dir) {
        Ok(Some(file)) => Some(
            TokenServer::new(&file.url, &file.session)
                .map_err(|error| Finish::Stopped(Some(error.to_string())))?,
        ),
        Ok(None) => None,
        Err(error) => return Err(Finish::Stopped(Some(error.to_string()))),
    };
    load_embed_config(state_dir, token_server.as_ref()).map_err(Finish::from)
}

#[cfg(test)]
mod tests {
    //! The view and its state changes, and the start against a loopback stand-in for the token
    //! server: no window, no native runtime, no network.

    use std::path::PathBuf;

    use urnetwork_embed::{
        caps::CapReading,
        self_test::{self_test_jwt, test_cap},
        stand_in::{StandInAnswer, StandInServer},
        state::{CLIENT_JWT_FILE_NAME, load_client_jwt},
    };

    use super::*;

    const TEST_CLIENT_ID: &str = "11111111-1111-1111-1111-111111111111";
    const TEST_SESSION: &str = "synthetic-demo-session-token-0123456789";

    /// A private temporary state directory (the app's `embed` directory).
    fn temp_state_dir() -> tempfile::TempDir {
        tempfile::Builder::new()
            .prefix("ur-tauri-embed-test-")
            .tempdir()
            .unwrap()
    }

    /// The state directory inside the temporary directory, created by the app on start.
    fn embed_dir(temp_dir: &tempfile::TempDir) -> PathBuf {
        temp_dir.path().join("embed")
    }

    #[test]
    fn a_new_view_is_stopped_and_checking() {
        let state = ViewState::default();
        let view = state.view(Path::new("/state/embed"), None);
        assert!(!view.running);
        assert_eq!(
            [
                view.status.as_str(),
                &view.data_this_month,
                &view.data_total
            ],
            ["stopped", "checking", "checking"]
        );
        assert_eq!(view.token_server_url, "");
        assert!(!view.has_demo_session);
    }

    #[test]
    fn views_show_the_contract_texts() {
        let mut state = ViewState::default();
        state.started();
        assert_eq!(state.status_text(), "connecting");
        state.identified(TEST_CLIENT_ID, "22222222-2222-2222-2222-222222222222");
        let cases = [
            (
                StatusInputs {
                    cap_reading: CapReading::Read(test_cap(
                        Some(5000000000),
                        1234567890,
                        None,
                        0,
                        "",
                        "2026-11-01T00:00:00Z",
                    )),
                    providers_added: 2,
                    ..StatusInputs::started()
                },
                ["connected", "1.2 GB of 5.0 GB", "no cap"],
            ),
            (
                StatusInputs {
                    cap_reading: CapReading::Read(test_cap(
                        Some(5000000000),
                        5000000000,
                        None,
                        0,
                        "monthly",
                        "2026-11-01T00:00:00Z",
                    )),
                    providers_added: 3,
                    ..StatusInputs::started()
                },
                [
                    "data cap reached, resets 2026-11-01 00:00 UTC",
                    "5.0 GB of 5.0 GB",
                    "no cap",
                ],
            ),
            (
                StatusInputs {
                    client_limit_status: "client_limit_exceeded".to_string(),
                    client_limit_retry_time: 1791313440001,
                    cap_reading: CapReading::Unavailable,
                    ..StatusInputs::started()
                },
                [
                    "client limit, retry at 19:05 UTC",
                    "unavailable",
                    "unavailable",
                ],
            ),
        ];
        for (inputs, [status, monthly, total]) in cases {
            state.session_status(&EmbedStatus::new(&inputs));
            let view = state.view(Path::new("/state/embed"), None);
            assert_eq!(
                [
                    view.status.as_str(),
                    &view.data_this_month,
                    &view.data_total
                ],
                [status, monthly, total]
            );
            assert_eq!(view.client_id, TEST_CLIENT_ID);
        }
    }

    #[test]
    fn a_sign_out_lasts_until_started_again() {
        let mut state = ViewState::default();
        state.started();
        state.session_status(&EmbedStatus::new(&StatusInputs {
            providers_added: 1,
            ..StatusInputs::started()
        }));
        state.finished(Finish::SignedOut(
            "the server rejected the client credential".to_string(),
        ));
        let view = state.view(Path::new("/state/embed"), None);
        assert_eq!(view.status, "signed out");
        assert!(!view.running);
        assert!(view.message.is_some());
        // stopping again keeps nothing of the sign-out but the stopped status
        state.started();
        assert_eq!(state.status_text(), "connecting");
        assert_eq!(state.message, None);
        state.finished(Finish::Stopped(None));
        assert_eq!(state.status_text(), "stopped");
    }

    #[test]
    fn view_names_match_the_window_script() {
        let view = ViewState::default().view(Path::new("/state/embed"), None);
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
                "clientId",
                "dataThisMonth",
                "dataTotal",
                "hasDemoSession",
                "installationId",
                "message",
                "running",
                "stateDir",
                "status",
                "tokenServerUrl"
            ]
        );
        let script = include_str!("../../ui/main.js");
        for name in names {
            assert!(
                script.contains(&format!("view.{name}")),
                "main.js does not show view.{name}"
            );
        }
    }

    #[test]
    fn token_server_settings_round_trip_without_showing_the_session() {
        let temp_dir = temp_state_dir();
        let state_dir = embed_dir(&temp_dir);
        save_token_server_settings(&state_dir, "http://127.0.0.1:8790", TEST_SESSION).unwrap();
        let file = load_token_server_file(&state_dir).unwrap().unwrap();
        let view = ViewState::default().view(&state_dir, Some(&file));
        assert_eq!(view.token_server_url, "http://127.0.0.1:8790");
        assert!(view.has_demo_session);
        assert!(!serde_json::to_string(&view).unwrap().contains(TEST_SESSION));
        // an empty session keeps the saved one
        save_token_server_settings(&state_dir, "http://localhost:8790", "").unwrap();
        let file = load_token_server_file(&state_dir).unwrap().unwrap();
        assert_eq!(
            (file.url.as_str(), file.session.as_str()),
            ("http://localhost:8790", TEST_SESSION)
        );
        // invalid settings are refused and the saved ones kept
        assert!(
            save_token_server_settings(&state_dir, "http://tokens.example.com", TEST_SESSION)
                .is_err()
        );
        assert!(
            save_token_server_settings(&state_dir, "http://127.0.0.1:8790", "two words").is_err()
        );
        assert_eq!(
            load_token_server_file(&state_dir).unwrap().unwrap().url,
            "http://localhost:8790"
        );
        // both fields empty: no token server, the client.jwt from a backend tool is used
        save_token_server_settings(&state_dir, "", "").unwrap();
        assert!(load_token_server_file(&state_dir).unwrap().is_none());
    }

    #[test]
    fn a_start_gets_the_client_jwt_from_the_token_server() {
        let temp_dir = temp_state_dir();
        let state_dir = embed_dir(&temp_dir);
        let client_jwt = self_test_jwt(&format!(r#"{{"client_id":"{TEST_CLIENT_ID}"}}"#));
        let stand_in = StandInServer::start(vec![StandInAnswer::json(
            200,
            serde_json::json!({"client_id": TEST_CLIENT_ID, "by_client_jwt": client_jwt, "data_cap": null}).to_string(),
        )])
        .unwrap();
        save_token_server_settings(&state_dir, stand_in.origin(), TEST_SESSION).unwrap();
        let config = prepare_start(&state_dir).unwrap();
        assert_eq!(config.client_id, TEST_CLIENT_ID);
        assert_eq!(config.client_jwt, client_jwt);
        // the state directory is private, and the token is saved there
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            let mode = std::fs::metadata(&state_dir).unwrap().permissions().mode() & 0o777;
            assert_eq!(mode, 0o700);
        }
        let (saved, _) = load_client_jwt(&state_dir).unwrap().unwrap();
        assert_eq!(saved, client_jwt);
        let request = &stand_in.requests()[0];
        assert_eq!(
            request.json().unwrap()["installation_id"],
            config.instance_id
        );
    }

    #[test]
    fn token_server_answers_map_to_signed_out_or_stopped() {
        let cases = [
            (
                401,
                r#"{"error":{"code":"unauthorized","message":"Unknown session."}}"#,
                true,
            ),
            (
                409,
                r#"{"error":{"code":"client_limit","message":"Your network is at its client limit; see https://ur.io/services"}}"#,
                true,
            ),
            (
                409,
                r#"{"error":{"code":"installation_limit","message":"Too many installations."}}"#,
                true,
            ),
            (
                502,
                r#"{"error":{"code":"upstream","message":"The URnetwork API failed."}}"#,
                false,
            ),
            (
                503,
                r#"{"error":{"code":"busy","message":"Retry."}}"#,
                false,
            ),
        ];
        for (status, body, signs_out) in cases {
            let temp_dir = temp_state_dir();
            let state_dir = embed_dir(&temp_dir);
            let stand_in = StandInServer::start(vec![StandInAnswer::json(status, body)]).unwrap();
            save_token_server_settings(&state_dir, stand_in.origin(), TEST_SESSION).unwrap();
            let finish = prepare_start(&state_dir).unwrap_err();
            let mut state = ViewState::default();
            state.started();
            state.finished(finish.clone());
            let want = if signs_out { "signed out" } else { "stopped" };
            assert_eq!(state.status_text(), want, "HTTP {status}");
            assert!(state.message.is_some(), "HTTP {status} has no message");
            assert!(!format!("{finish:?}").contains(TEST_SESSION));
        }
    }

    #[test]
    fn a_start_without_a_token_server_uses_client_jwt() {
        let temp_dir = temp_state_dir();
        let state_dir = embed_dir(&temp_dir);
        // no token server and no client.jwt: stopped with a message
        match prepare_start(&state_dir) {
            Err(Finish::Stopped(Some(message))) => assert!(message.contains("client.jwt")),
            other => panic!("a start without a token server or client.jwt gave {other:?}"),
        }
        let client_jwt = self_test_jwt(&format!(r#"{{"client_id":"{TEST_CLIENT_ID}"}}"#));
        urnetwork_embed::state::write_private_file(
            &state_dir.join(CLIENT_JWT_FILE_NAME),
            format!("{client_jwt}\n").as_bytes(),
        )
        .unwrap();
        let config = prepare_start(&state_dir).unwrap();
        assert_eq!(config.client_id, TEST_CLIENT_ID);
    }
}
