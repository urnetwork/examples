//! Installation state for one embedded installation, kept in one private directory
//! (EMBED_CONTRACT.md, "Installation state"). The console app reads its path from
//! `URNETWORK_EMBED_STATE_DIR`; the Tauri app uses a private `embed` directory in its local app
//! data.
//!
//! - `client.jwt`: the scoped client JWT that the developer's backend delivered for this
//!   installation, through the token server or a backend tool. The app rewrites it whenever the SDK
//!   refreshes the token.
//! - `instance-id`: this installation's UUID, created on first run. It is also the installation ID
//!   that the app sends to the token server.
//! - `token-server.json`: the token server origin and the demo session token (GUI apps).
//! - `logs/`: the SDK's bounded log files.
//!
//! Every file is replaced atomically with owner-only permissions. On POSIX the directory and its
//! files must not be accessible to group or others; Windows relies on the access control of the
//! user's profile directory. The JWT and the demo session are secrets: nothing here prints or logs
//! them.

use std::{
    fmt, fs,
    io::{self, Write},
    path::Path,
};

use base64::{
    Engine, alphabet,
    engine::{DecodePaddingMode, GeneralPurpose, GeneralPurposeConfig},
};
use serde::{Deserialize, Serialize};

use crate::{
    ConfigError,
    id::{new_id, parse_id},
};

/// The environment variable that names the console app's state directory.
pub const STATE_DIR_ENV: &str = "URNETWORK_EMBED_STATE_DIR";
/// The scoped client credential.
pub const CLIENT_JWT_FILE_NAME: &str = "client.jwt";
/// The installation's UUID.
pub const INSTANCE_ID_FILE_NAME: &str = "instance-id";
/// The token server origin and the demo session token, for GUI apps.
pub const TOKEN_SERVER_FILE_NAME: &str = "token-server.json";
/// The SDK's bounded log files.
pub const LOGS_DIR_NAME: &str = "logs";

/// The largest state file the app reads.
const STATE_FILE_BYTE_LIMIT: u64 = 64 * 1024;

/// URL-safe base64 for JWT segments, accepting missing padding and non-zero trailing bits, as Go's
/// decoder does.
const URL_SAFE_LENIENT: GeneralPurpose = GeneralPurpose::new(
    &alphabet::URL_SAFE,
    GeneralPurposeConfig::new()
        .with_decode_padding_mode(DecodePaddingMode::Indifferent)
        .with_decode_allow_trailing_bits(true),
);

/// `token-server.json`: where a GUI app obtains its client JWT. The session is a bearer secret.
#[derive(Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct TokenServerFile {
    #[serde(default)]
    pub url: String,
    #[serde(default)]
    pub session: String,
}

impl fmt::Debug for TokenServerFile {
    /// The URL only: the session is a credential.
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter
            .debug_struct("TokenServerFile")
            .field("url", &self.url)
            .finish_non_exhaustive()
    }
}

/// The state directory must be an existing absolute directory, private to its owner on POSIX.
pub fn check_state_dir(state_dir: &Path) -> Result<(), ConfigError> {
    if state_dir.as_os_str().is_empty() {
        return Err(ConfigError::new(format!(
            "set {STATE_DIR_ENV} to this installation's private state directory"
        )));
    }
    if !state_dir.is_absolute() {
        return Err(ConfigError::new(
            "the state directory must be an absolute path",
        ));
    }
    let metadata = fs::metadata(state_dir)
        .map_err(|error| ConfigError::new(format!("state directory: {error}")))?;
    if !metadata.is_dir() {
        return Err(ConfigError::new("the state directory is not a directory"));
    }
    if !is_private(&metadata) {
        return Err(ConfigError::new(
            "the state directory must be private to its owner (chmod 700)",
        ));
    }
    Ok(())
}

/// Reads a regular, private state file of bounded size. A symlink is refused so that the credential
/// cannot be redirected to another file.
pub fn read_private_file(path: &Path) -> io::Result<Vec<u8>> {
    let metadata = fs::symlink_metadata(path)?;
    if !metadata.file_type().is_file() {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "not a regular file",
        ));
    }
    if STATE_FILE_BYTE_LIMIT < metadata.len() {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "file is too large",
        ));
    }
    if !is_private(&metadata) {
        return Err(io::Error::new(
            io::ErrorKind::PermissionDenied,
            "file must be private to its owner (chmod 600)",
        ));
    }
    fs::read(path)
}

/// Replaces a state file atomically: a private temporary file in the same directory is written,
/// synced and renamed over the old file. The temporary file is removed on failure.
pub fn write_private_file(path: &Path, data: &[u8]) -> io::Result<()> {
    let dir = path.parent().ok_or_else(|| {
        io::Error::new(
            io::ErrorKind::InvalidInput,
            "a state file needs a directory",
        )
    })?;
    let file_name = path
        .file_name()
        .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "a state file needs a name"))?;
    let prefix = format!(".{}.", file_name.to_string_lossy());
    let mut file = tempfile::Builder::new().prefix(&prefix).tempfile_in(dir)?;
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        file.as_file()
            .set_permissions(fs::Permissions::from_mode(0o600))?;
    }
    file.write_all(data)?;
    file.as_file().sync_all()?;
    file.persist(path).map_err(|error| error.error)?;
    Ok(())
}

/// Creates a directory and its missing parents, private to the owner on POSIX (0700). An existing
/// directory keeps its permissions: [`check_state_dir`] refuses one that others can access.
pub fn create_private_dir(path: &Path) -> io::Result<()> {
    let mut builder = fs::DirBuilder::new();
    builder.recursive(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::DirBuilderExt;
        builder.mode(0o700);
    }
    builder.create(path)
}

/// Whether group and others have no access, on POSIX. Windows relies on the profile directory's
/// access control.
fn is_private(metadata: &fs::Metadata) -> bool {
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        metadata.permissions().mode() & 0o077 == 0
    }
    #[cfg(not(unix))]
    {
        let _ = metadata;
        true
    }
}

/// The error for a value that does not hold a JWT.
fn not_a_jwt() -> ConfigError {
    ConfigError::new("the client JWT is not a JWT; deliver the scoped client JWT from your backend")
}

/// The `client_id` claim of a scoped client JWT, in canonical form. This checks the token's shape
/// and claim only; the SDK and the server verify the token itself.
pub fn parse_client_jwt_client_id(client_jwt: &str) -> Result<String, ConfigError> {
    let parts: Vec<&str> = client_jwt.split('.').collect();
    if parts.len() != 3 || parts.iter().any(|part| part.is_empty()) {
        return Err(not_a_jwt());
    }
    let payload = URL_SAFE_LENIENT
        .decode(parts[1].trim_end_matches('='))
        .map_err(|_| not_a_jwt())?;
    let claims: serde_json::Value =
        serde_json::from_slice(&payload).unwrap_or(serde_json::Value::Null);
    let claim = claims
        .get("client_id")
        .and_then(serde_json::Value::as_str)
        .filter(|claim| !claim.is_empty())
        .ok_or_else(|| {
            ConfigError::new(
                "the client JWT has no client_id claim; use a scoped client JWT, not a network JWT",
            )
        })?;
    parse_id(claim).ok_or_else(|| ConfigError::new("the client JWT has an invalid client_id claim"))
}

/// Reads `instance-id`, creating it on first run. An installation keeps one instance id for its
/// lifetime.
pub fn load_or_create_instance_id(state_dir: &Path) -> Result<String, ConfigError> {
    let path = state_dir.join(INSTANCE_ID_FILE_NAME);
    match read_private_file(&path) {
        Ok(data) => String::from_utf8(data)
            .ok()
            .and_then(|text| parse_id(text.trim()))
            .ok_or_else(|| {
                ConfigError::new(format!("{INSTANCE_ID_FILE_NAME} does not hold a UUID"))
            }),
        Err(error) if error.kind() == io::ErrorKind::NotFound => {
            let instance_id = new_id().map_err(|error| {
                ConfigError::new(format!("create {INSTANCE_ID_FILE_NAME}: {error}"))
            })?;
            write_private_file(&path, format!("{instance_id}\n").as_bytes()).map_err(|error| {
                ConfigError::new(format!("write {INSTANCE_ID_FILE_NAME}: {error}"))
            })?;
            Ok(instance_id)
        }
        Err(error) => Err(ConfigError::new(format!(
            "read {INSTANCE_ID_FILE_NAME} from the state directory: {error}"
        ))),
    }
}

/// Reads `client.jwt` and its `client_id` claim; none when the file does not exist. Surrounding
/// whitespace is ignored. A network JWT, which has no `client_id` claim, is refused.
pub fn load_client_jwt(state_dir: &Path) -> Result<Option<(String, String)>, ConfigError> {
    let data = match read_private_file(&state_dir.join(CLIENT_JWT_FILE_NAME)) {
        Ok(data) => data,
        Err(error) if error.kind() == io::ErrorKind::NotFound => return Ok(None),
        Err(error) => {
            return Err(ConfigError::new(format!(
                "read {CLIENT_JWT_FILE_NAME} from the state directory: {error}"
            )));
        }
    };
    let client_jwt = String::from_utf8(data)
        .map(|client_jwt| client_jwt.trim().to_string())
        .map_err(|_| not_a_jwt())?;
    let client_id = parse_client_jwt_client_id(&client_jwt)?;
    Ok(Some((client_jwt, client_id)))
}

/// Writes `client.jwt`. Never prints the token.
pub fn save_client_jwt(state_dir: &Path, client_jwt: &str) -> io::Result<()> {
    write_private_file(
        &state_dir.join(CLIENT_JWT_FILE_NAME),
        format!("{client_jwt}\n").as_bytes(),
    )
}

/// Reads `token-server.json`; none when the file does not exist.
pub fn load_token_server_file(state_dir: &Path) -> Result<Option<TokenServerFile>, ConfigError> {
    let data = match read_private_file(&state_dir.join(TOKEN_SERVER_FILE_NAME)) {
        Ok(data) => data,
        Err(error) if error.kind() == io::ErrorKind::NotFound => return Ok(None),
        Err(error) => {
            return Err(ConfigError::new(format!(
                "read {TOKEN_SERVER_FILE_NAME} from the state directory: {error}"
            )));
        }
    };
    serde_json::from_slice::<TokenServerFile>(&data)
        .map(Some)
        .map_err(|_| ConfigError::new(format!("{TOKEN_SERVER_FILE_NAME} is not valid JSON")))
}

/// Writes `token-server.json`.
pub fn save_token_server_file(state_dir: &Path, file: &TokenServerFile) -> io::Result<()> {
    let data = serde_json::to_vec(file)?;
    write_private_file(&state_dir.join(TOKEN_SERVER_FILE_NAME), &data)
}
