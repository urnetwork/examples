//! Loading the installation at start (EMBED_CONTRACT.md, "App lifecycle" steps 1 and 2): the state
//! directory, the `instance-id`, and the client JWT, from the token server when one is configured
//! and otherwise from the `client.jwt` already in the state.

use std::{fmt, path::Path, path::PathBuf};

use crate::{
    ConfigError, EXIT_CONFIG, EXIT_FAILURE,
    caps::DataCap,
    http::{DEFAULT_API_ORIGIN, parse_origin},
    state::{check_state_dir, load_client_jwt, load_or_create_instance_id, save_client_jwt},
    token::{TokenError, TokenServer, fetch_client_jwt},
};

/// The installation loaded at start.
pub struct EmbedConfig {
    pub state_dir: PathBuf,
    /// a bearer secret: never print or log it
    pub client_jwt: String,
    /// the `client_id` claim of `client_jwt`
    pub client_id: String,
    pub instance_id: String,
    /// the token server's cap object, the first cap reading; none without a token server
    pub initial_data_cap: Option<DataCap>,
}

impl fmt::Debug for EmbedConfig {
    /// Everything but the credential.
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter
            .debug_struct("EmbedConfig")
            .field("state_dir", &self.state_dir)
            .field("client_id", &self.client_id)
            .field("instance_id", &self.instance_id)
            .field("initial_data_cap", &self.initial_data_cap)
            .finish_non_exhaustive()
    }
}

/// Why the installation could not be loaded.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum StartError {
    /// The state or configuration is not usable (console exit 78; a GUI stays `stopped` with the
    /// message).
    Config(ConfigError),
    /// The token server refused or failed (console exit 78 or 1; a GUI shows `signed out` or
    /// `stopped`).
    Token(TokenError),
    /// Any other failure, such as a state file that could not be written (console exit 1).
    Failed(String),
}

impl StartError {
    /// The console exit code.
    pub fn exit_code(&self) -> i32 {
        match self {
            StartError::Config(_) => EXIT_CONFIG,
            StartError::Token(error) => error.exit_code(),
            StartError::Failed(_) => EXIT_FAILURE,
        }
    }

    /// Whether a GUI shows `signed out` (otherwise `stopped`).
    pub fn signs_out(&self) -> bool {
        matches!(self, StartError::Token(error) if error.signs_out())
    }
}

impl fmt::Display for StartError {
    /// A message for the user; it never contains a credential.
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            StartError::Config(error) => write!(formatter, "{error}"),
            StartError::Token(error) => write!(formatter, "{error}"),
            StartError::Failed(message) => formatter.write_str(message),
        }
    }
}

impl From<ConfigError> for StartError {
    fn from(error: ConfigError) -> Self {
        StartError::Config(error)
    }
}

/// Loads the installation: checks the state directory, creates `instance-id` on first run, then
/// obtains the client JWT. With a token server it fetches on every start and saves the answer as
/// `client.jwt`; otherwise it uses the `client.jwt` already in the state, as a backend tool's
/// `provision` or the developer wrote it.
pub fn load_embed_config(
    state_dir: &Path,
    token_server: Option<&TokenServer>,
) -> Result<EmbedConfig, StartError> {
    check_state_dir(state_dir)?;
    let instance_id = load_or_create_instance_id(state_dir)?;
    match token_server {
        Some(token_server) => {
            let answer = fetch_client_jwt(token_server, &instance_id).map_err(StartError::Token)?;
            save_client_jwt(state_dir, &answer.by_client_jwt).map_err(|error| {
                StartError::Failed(format!("could not save client.jwt: {error}"))
            })?;
            Ok(EmbedConfig {
                state_dir: state_dir.to_path_buf(),
                client_jwt: answer.by_client_jwt,
                client_id: answer.client_id,
                instance_id,
                initial_data_cap: answer.data_cap,
            })
        }
        None => match load_client_jwt(state_dir)? {
            Some((client_jwt, client_id)) => Ok(EmbedConfig {
                state_dir: state_dir.to_path_buf(),
                client_jwt,
                client_id,
                instance_id,
                initial_data_cap: None,
            }),
            None => Err(StartError::Config(ConfigError::new(
                "no token server is configured and the state directory has no client.jwt; configure a token server or write a client JWT from your backend's provision command",
            ))),
        },
    }
}

/// The console's token server from `URNETWORK_TOKEN_SERVER_URL` and `URNETWORK_DEMO_SESSION`: none
/// when neither is set; both are needed together.
pub fn token_server_from_settings(
    url: Option<&str>,
    session: Option<&str>,
) -> Result<Option<TokenServer>, ConfigError> {
    let url = url.map(str::trim).filter(|url| !url.is_empty());
    let session = session.map(str::trim).filter(|session| !session.is_empty());
    match (url, session) {
        (None, None) => Ok(None),
        (Some(url), Some(session)) => TokenServer::new(url, session).map(Some),
        (Some(_), None) => Err(ConfigError::new(
            "URNETWORK_TOKEN_SERVER_URL is set: also set URNETWORK_DEMO_SESSION",
        )),
        (None, Some(_)) => Err(ConfigError::new(
            "URNETWORK_DEMO_SESSION is set: also set URNETWORK_TOKEN_SERVER_URL",
        )),
    }
}

/// The API origin for the cap reads, from `URNETWORK_API_URL`, by default the URnetwork API.
pub fn api_origin_from_setting(url: Option<&str>) -> Result<String, ConfigError> {
    match url.map(str::trim).filter(|url| !url.is_empty()) {
        None => Ok(DEFAULT_API_ORIGIN.to_string()),
        Some(url) => parse_origin(url, "URNETWORK_API_URL"),
    }
}
