//! Installation state for one provider install, kept in one private directory
//! (PROVIDER_CONTRACT.md, "Installation state"). The console app reads its path from
//! `URNETWORK_PROVIDER_STATE_DIR`; the Tauri app uses a `provider` directory in its app data.
//!
//! - `client.jwt`: the scoped client credential that the developer's backend issued for this
//!   installation. The developer writes it; the app rewrites it whenever the SDK refreshes the
//!   token.
//! - `instance-id`: this installation's UUID, created on first run.
//! - `identity.json`: the provider identity (client key seed, provide TLS certificate and key,
//!   extender seed), created on first run so the provider keeps one identity across restarts.
//!
//! Every file is replaced atomically with owner-only permissions. On POSIX the directory and its
//! files must not be accessible to group or others; Windows relies on the access control of the
//! user's profile directory. The JWT and the identity are secrets: nothing here prints or logs
//! them.

use std::{
    fmt, fs,
    io::{self, Write},
    path::{Path, PathBuf},
};

use base64::{
    Engine, alphabet,
    engine::{DecodePaddingMode, GeneralPurpose, GeneralPurposeConfig, general_purpose::STANDARD},
};
use serde::{Deserialize, Deserializer, Serialize, Serializer};

use crate::{
    ConfigError,
    id::{new_id, parse_id},
};

/// The scoped client credential.
pub const CLIENT_JWT_FILE_NAME: &str = "client.jwt";
/// The installation's UUID.
pub const INSTANCE_ID_FILE_NAME: &str = "instance-id";
/// The provider identity.
pub const IDENTITY_FILE_NAME: &str = "identity.json";
/// The SDK's bounded log files.
pub const LOGS_DIR_NAME: &str = "logs";

/// The largest state file the app reads.
const STATE_FILE_BYTE_LIMIT: u64 = 64 * 1024;

/// The only `identity.json` version.
const PROVIDER_IDENTITY_VERSION: i64 = 1;

/// Standard base64 that accepts missing padding and non-zero trailing bits, as Go's decoder does.
const STANDARD_LENIENT: GeneralPurpose = GeneralPurpose::new(
    &alphabet::STANDARD,
    GeneralPurposeConfig::new()
        .with_decode_padding_mode(DecodePaddingMode::Indifferent)
        .with_decode_allow_trailing_bits(true),
);

/// URL-safe base64 for JWT segments, with the same tolerance.
const URL_SAFE_LENIENT: GeneralPurpose = GeneralPurpose::new(
    &alphabet::URL_SAFE,
    GeneralPurposeConfig::new()
        .with_decode_padding_mode(DecodePaddingMode::Indifferent)
        .with_decode_allow_trailing_bits(true),
);

/// `identity.json`. Byte fields are standard base64 in JSON, the same file the Go provider writes.
/// An identity belongs to one client: a newly provisioned client gets a new identity.
#[derive(Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ProviderIdentity {
    #[serde(default)]
    pub version: i64,
    #[serde(default)]
    pub client_id: String,
    #[serde(default, with = "base64_bytes")]
    pub client_key_seed: Vec<u8>,
    #[serde(default, with = "base64_bytes")]
    pub provide_tls_certificate_pem: Vec<u8>,
    #[serde(default, with = "base64_bytes")]
    pub provide_tls_private_key_pem: Vec<u8>,
    #[serde(default, with = "base64_bytes", skip_serializing_if = "Vec::is_empty")]
    pub extender_key_seed: Vec<u8>,
}

impl ProviderIdentity {
    /// The identity of a running device, for `identity.json`.
    pub fn new(
        client_id: String,
        client_key_seed: Vec<u8>,
        provide_tls_certificate_pem: Vec<u8>,
        provide_tls_private_key_pem: Vec<u8>,
        extender_key_seed: Vec<u8>,
    ) -> Self {
        Self {
            version: PROVIDER_IDENTITY_VERSION,
            client_id,
            client_key_seed,
            provide_tls_certificate_pem,
            provide_tls_private_key_pem,
            extender_key_seed,
        }
    }
}

impl fmt::Debug for ProviderIdentity {
    /// The version and client only: the rest are private keys.
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter
            .debug_struct("ProviderIdentity")
            .field("version", &self.version)
            .field("client_id", &self.client_id)
            .finish_non_exhaustive()
    }
}

/// The key material that recreates a device's identity, borrowed from an identity.
pub struct KeyMaterial<'a> {
    pub client_key_seed: &'a [u8],
    pub provide_tls_certificate_pem: &'a [u8],
    pub provide_tls_private_key_pem: &'a [u8],
    /// empty when the identity has none
    pub extender_key_seed: &'a [u8],
}

/// The key material to create the device with. None without an identity (the first run, or another
/// client's identity): the device then makes a new identity, which the app saves.
pub fn key_material(identity: Option<&ProviderIdentity>) -> Option<KeyMaterial<'_>> {
    identity.map(|identity| KeyMaterial {
        client_key_seed: &identity.client_key_seed,
        provide_tls_certificate_pem: &identity.provide_tls_certificate_pem,
        provide_tls_private_key_pem: &identity.provide_tls_private_key_pem,
        extender_key_seed: &identity.extender_key_seed,
    })
}

/// The installation state loaded at start.
pub struct ProviderConfig {
    pub state_dir: PathBuf,
    /// a bearer secret: never print or log it
    pub client_jwt: String,
    /// the `client_id` claim of `client_jwt`
    pub client_id: String,
    pub instance_id: String,
    /// none on first run, and when the stored identity belongs to another client
    pub identity: Option<ProviderIdentity>,
}

impl fmt::Debug for ProviderConfig {
    /// Everything but the credential and the identity's keys.
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter
            .debug_struct("ProviderConfig")
            .field("state_dir", &self.state_dir)
            .field("client_id", &self.client_id)
            .field("instance_id", &self.instance_id)
            .field("identity", &self.identity)
            .finish_non_exhaustive()
    }
}

/// Loads the installation state, creating `instance-id` on first run. Every error is a
/// configuration error: restarting does not fix it.
pub fn load_provider_config(state_dir: &Path) -> Result<ProviderConfig, ConfigError> {
    check_state_dir(state_dir)?;
    let client_jwt_bytes =
        read_private_file(&state_dir.join(CLIENT_JWT_FILE_NAME)).map_err(|error| {
            ConfigError::new(format!(
                "read {CLIENT_JWT_FILE_NAME} from the state directory: {error}"
            ))
        })?;
    let client_jwt = String::from_utf8(client_jwt_bytes)
        .map(|client_jwt| client_jwt.trim().to_string())
        .map_err(|_| not_a_jwt())?;
    let client_id = parse_client_jwt_client_id(&client_jwt)?;
    let instance_id = load_or_create_instance_id(state_dir)?;
    let identity = load_provider_identity(state_dir, &client_id)?;
    Ok(ProviderConfig {
        state_dir: state_dir.to_path_buf(),
        client_jwt,
        client_id,
        instance_id,
        identity,
    })
}

/// The state directory must be an existing absolute directory, private to its owner on POSIX.
pub fn check_state_dir(state_dir: &Path) -> Result<(), ConfigError> {
    if state_dir.as_os_str().is_empty() {
        return Err(ConfigError::new(
            "set URNETWORK_PROVIDER_STATE_DIR to this installation's private state directory",
        ));
    }
    if !state_dir.is_absolute() {
        return Err(ConfigError::new(
            "URNETWORK_PROVIDER_STATE_DIR must be an absolute path",
        ));
    }
    let metadata = fs::metadata(state_dir)
        .map_err(|error| ConfigError::new(format!("state directory: {error}")))?;
    if !metadata.is_dir() {
        return Err(ConfigError::new(
            "URNETWORK_PROVIDER_STATE_DIR is not a directory",
        ));
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

/// The error for a `client.jwt` that does not hold a JWT.
fn not_a_jwt() -> ConfigError {
    ConfigError::new(
        "client.jwt does not hold a JWT; write the scoped client JWT from your backend",
    )
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
                "client.jwt has no client_id claim; write a scoped client JWT, not a network JWT",
            )
        })?;
    parse_id(claim).ok_or_else(|| ConfigError::new("client.jwt has an invalid client_id claim"))
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

/// Reads `identity.json` for `client_id`. A missing file, or an identity of another client, returns
/// none: the device then creates a new identity, which the app saves.
pub fn load_provider_identity(
    state_dir: &Path,
    client_id: &str,
) -> Result<Option<ProviderIdentity>, ConfigError> {
    let data = match read_private_file(&state_dir.join(IDENTITY_FILE_NAME)) {
        Ok(data) => data,
        Err(error) if error.kind() == io::ErrorKind::NotFound => return Ok(None),
        Err(error) => {
            return Err(ConfigError::new(format!(
                "read {IDENTITY_FILE_NAME} from the state directory: {error}"
            )));
        }
    };
    // a known version and a 32-byte client key seed
    let valid = |identity: &ProviderIdentity| {
        identity.version == PROVIDER_IDENTITY_VERSION && identity.client_key_seed.len() == 32
    };
    let identity = serde_json::from_slice::<ProviderIdentity>(&data)
        .ok()
        .filter(valid)
        .ok_or_else(|| {
            ConfigError::new(format!(
                "{IDENTITY_FILE_NAME} is not a valid provider identity; remove it to create a new one"
            ))
        })?;
    let identity_client_id = parse_id(&identity.client_id).unwrap_or_default();
    if identity_client_id != client_id {
        return Ok(None);
    }
    Ok(Some(identity))
}

/// Writes `identity.json`.
pub fn save_provider_identity(state_dir: &Path, identity: &ProviderIdentity) -> io::Result<()> {
    let data = serde_json::to_vec(identity)?;
    write_private_file(&state_dir.join(IDENTITY_FILE_NAME), &data)
}

/// Byte fields as standard base64 strings, the way Go's encoding/json writes `[]byte`: an empty
/// value is `null`.
mod base64_bytes {
    use super::*;

    /// Writes the bytes as base64, or `null` when empty.
    pub fn serialize<S: Serializer>(bytes: &[u8], serializer: S) -> Result<S::Ok, S::Error> {
        if bytes.is_empty() {
            serializer.serialize_none()
        } else {
            serializer.serialize_str(&STANDARD.encode(bytes))
        }
    }

    /// Reads base64 or `null` (empty).
    pub fn deserialize<'de, D: Deserializer<'de>>(deserializer: D) -> Result<Vec<u8>, D::Error> {
        match Option::<String>::deserialize(deserializer)? {
            Some(text) => STANDARD_LENIENT
                .decode(text)
                .map_err(serde::de::Error::custom),
            None => Ok(Vec::new()),
        }
    }
}
