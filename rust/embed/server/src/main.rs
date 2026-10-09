//! SERVER ONLY. The Rust embed backend tool (EMBED_CONTRACT.md, "Backend tools"). It extends the
//! integration allocator (`rust/integration/server`, INTEGRATION_CONTRACT.md "Runnable backend
//! allocators") with the embed commands: provision an installation's client, set and read its data
//! caps, list every capped client, remove a client, and set a client's ACL group (`acl <key>
//! default|isolated`). The authenticated backend supplies each
//! `user:<service-user-id>[:<installation-id>]` key internally; never pass an untrusted request
//! field or a URnetwork client ID.
//!
//! Settings: `URNETWORK_ROOT_JWT` (an API key or a network JWT, from the backend secret store),
//! `URNETWORK_CLIENT_MAP` (absolute file path in an existing private, service-owned directory),
//! optional `URNETWORK_API_URL` and optional `URNETWORK_DEFAULT_ACL_GROUP` (the ACL group of each new
//! client: `isolated`, the default, or `default`). The map stores client IDs, never tokens; a
//! crash-left `<map>.lock` may be removed only after confirming that no tool still owns it.
//!
//! Exit codes: 0 success, 78 configuration or credential problem (missing settings, an invalid key
//! or map, the root credential refused, the client limit), 1 any other failure. Errors are one
//! stderr line that never contains a secret.

use base64::{
    Engine, alphabet,
    engine::{DecodePaddingMode, GeneralPurpose, GeneralPurposeConfig},
};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
#[cfg(unix)]
use std::os::unix::fs::{OpenOptionsExt, PermissionsExt};
use std::{
    collections::{BTreeMap, BTreeSet},
    env,
    fs::{self, OpenOptions},
    io::{self, Read, Write},
    path::{Path, PathBuf},
    time::{Duration, SystemTime, UNIX_EPOCH},
};

/// The largest map file and API answer the tool reads.
const LIMIT: u64 = 1 << 20;
/// The description of every client the tool provisions. It is visible to URnetwork, so it names no
/// user: the map links each client to its key.
const DESCRIPTION: &str = "embed client";
/// The device spec of every client the tool provisions.
const DEVICE_SPEC: &str = "urnetwork-examples/rust-embed-server";
/// The stderr line for a refusal at either client limit.
const CLIENT_LIMIT_MESSAGE: &str =
    "client limit reached: your network is at its client limit; see https://ur.io/services";
/// The API's message for a client that was removed or deactivated after 30 days without connecting.
const CLIENT_DOES_NOT_EXIST: &str = "Client does not exist.";
/// The largest byte count a cap takes.
const MAX_BYTE_COUNT: u64 = i64::MAX as u64;
/// The page size of `usage-all`.
const USAGE_ALL_PAGE_SIZE: &str = "1000";
/// The stderr line for `cap`, `usage`, `remove` or `acl` with a key that has no client.
const UNMAPPED_MESSAGE: &str = "no client is mapped for that key; run provision first";
/// The stderr line for a server without ACL groups.
const ACL_UNSUPPORTED_MESSAGE: &str =
    "/network/client-acl-group answered 404: the server predates ACL groups";
const AUTH_CLIENT_PATH: &str = "/network/auth-client";
const CAP_PATH: &str = "/network/client-data-cap";
const CAPS_PATH: &str = "/network/client-data-caps";
const REMOVE_PATH: &str = "/network/remove-client";
const ACL_PATH: &str = "/network/client-acl-group";
const ACL_GROUP_DEFAULT: &str = "default";
const ACL_GROUP_ISOLATED: &str = "isolated";

const EXIT_OK: i32 = 0;
const EXIT_FAILURE: i32 = 1;
const EXIT_CONFIG: i32 = 78;

const USAGE: &str = "usage: urnetwork-embed-server provision <key> <client-jwt-file> | cap <key> [--monthly <bytes>|null] [--total <bytes>|null] [--reset-total] | usage <key> | usage-all | remove <key> | acl <key> default|isolated | --self-test";

/// URL-safe base64 for JWT segments, accepting missing padding, as Go's decoder does.
const URL_SAFE_LENIENT: GeneralPurpose = GeneralPurpose::new(
    &alphabet::URL_SAFE,
    GeneralPurposeConfig::new()
        .with_decode_padding_mode(DecodePaddingMode::Indifferent)
        .with_decode_allow_trailing_bits(true),
);

/// The private client map: the allocators' format, plus `pending_acl`, the keys whose new clients
/// still owe their default ACL group, written only while it is not empty. A map with any other
/// field, such as the token server's `pending_caps`, is refused, so that two tools never rewrite
/// each other's map.
#[derive(Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct ClientMap {
    version: u32,
    clients: BTreeMap<String, String>,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pending_acl: Vec<String>,
}

impl ClientMap {
    /// Records that the key's new client owes its default ACL group, or drops the record.
    fn set_acl_pending(&mut self, key: &str, pending: bool) {
        self.pending_acl.retain(|entry| entry != key);
        if pending {
            self.pending_acl.push(key.to_string());
        }
    }

    /// Whether the key's client owes its default ACL group.
    fn acl_pending(&self, key: &str) -> bool {
        self.pending_acl.iter().any(|entry| entry == key)
    }
}

/// Why a command failed: its exit code and one stderr line without a secret.
#[derive(Debug, PartialEq, Eq)]
enum ToolError {
    /// Missing settings, an invalid key, map or argument, or the root credential refused: exit 78.
    Config(String),
    /// URnetwork refused a new client at the client limit: exit 78.
    ClientLimit,
    /// Any other failure: exit 1.
    Failure(String),
}

impl ToolError {
    /// The exit code.
    fn exit_code(&self) -> i32 {
        match self {
            ToolError::Config(_) | ToolError::ClientLimit => EXIT_CONFIG,
            ToolError::Failure(_) => EXIT_FAILURE,
        }
    }

    /// The stderr line.
    fn message(&self) -> String {
        match self {
            ToolError::Config(message) | ToolError::Failure(message) => message.clone(),
            ToolError::ClientLimit => CLIENT_LIMIT_MESSAGE.to_string(),
        }
    }
}

/// A failure while writing the tool's own output.
impl From<io::Error> for ToolError {
    fn from(error: io::Error) -> Self {
        ToolError::Failure(format!("i/o error: {error}"))
    }
}

/// A cap value from the command line: a byte count, or `null` to clear the cap.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum CapValue {
    Bytes(u64),
    Null,
}

impl CapValue {
    /// The JSON value: an integer, or `null`.
    fn to_json(self) -> Value {
        match self {
            CapValue::Bytes(byte_count) => json!(byte_count),
            CapValue::Null => Value::Null,
        }
    }
}

/// What the arguments ask for.
#[derive(Debug, PartialEq, Eq)]
enum Command {
    Provision {
        key: String,
        client_jwt_file: PathBuf,
    },
    Cap {
        key: String,
        monthly: Option<CapValue>,
        total: Option<CapValue>,
        reset_total: bool,
    },
    Usage {
        key: String,
    },
    UsageAll,
    Remove {
        key: String,
    },
    Acl {
        key: String,
        group: &'static str,
    },
    SelfTest,
}

/// An ACL group named on the command line or in `URNETWORK_DEFAULT_ACL_GROUP`.
fn acl_group(text: &str) -> Option<&'static str> {
    match text {
        ACL_GROUP_DEFAULT => Some(ACL_GROUP_DEFAULT),
        ACL_GROUP_ISOLATED => Some(ACL_GROUP_ISOLATED),
        _ => None,
    }
}

/// The ACL group of each new client from `URNETWORK_DEFAULT_ACL_GROUP`: `isolated` when it is unset
/// or empty.
fn default_acl_group(setting: Option<&str>) -> Result<&'static str, ToolError> {
    match setting {
        None | Some("") => Ok(ACL_GROUP_ISOLATED),
        Some(text) => acl_group(text).ok_or_else(|| {
            ToolError::Config("URNETWORK_DEFAULT_ACL_GROUP must be default or isolated".to_string())
        }),
    }
}

/// Whether a key matches the allocator pattern `user:[A-Za-z0-9][A-Za-z0-9_.:@-]{0,122}`, so both
/// `user:alice` and `user:alice:22222222-2222-2222-2222-222222222222` work.
fn key_valid(key: &str) -> bool {
    let Some(rest) = key.strip_prefix("user:") else {
        return false;
    };
    !rest.is_empty()
        && key.len() <= 128
        && rest.as_bytes()[0].is_ascii_alphanumeric()
        && rest
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b"_.:@-".contains(&b))
}

/// A valid key, or a configuration error.
fn checked_key(key: &str) -> Result<String, ToolError> {
    if key_valid(key) {
        Ok(key.to_string())
    } else {
        Err(ToolError::Config(
            "invalid key: expected user:<service-user-id>[:<installation-id>]".to_string(),
        ))
    }
}

/// Whether an id is a lowercase canonical UUID.
fn id_valid(id: &str) -> bool {
    id.len() == 36
        && id.bytes().enumerate().all(|(i, b)| {
            if [8, 13, 18, 23].contains(&i) {
                b == b'-'
            } else {
                b.is_ascii_digit() || (b'a'..=b'f').contains(&b)
            }
        })
}

/// A cap option value: a decimal integer from 0 to 9223372036854775807 with no sign or units, or
/// `null`.
fn parse_cap_value(text: &str) -> Result<CapValue, ToolError> {
    if text == "null" {
        return Ok(CapValue::Null);
    }
    let invalid = || {
        ToolError::Config(
            "a cap is a decimal byte count from 0 to 9223372036854775807 with no units, or null"
                .to_string(),
        )
    };
    if text.is_empty() || !text.bytes().all(|b| b.is_ascii_digit()) {
        return Err(invalid());
    }
    match text.parse::<u64>() {
        Ok(byte_count) if byte_count <= MAX_BYTE_COUNT => Ok(CapValue::Bytes(byte_count)),
        _ => Err(invalid()),
    }
}

/// The command for the arguments after the program name.
fn parse_command(args: &[String]) -> Result<Command, ToolError> {
    let usage = || ToolError::Config(USAGE.to_string());
    let args: Vec<&str> = args.iter().map(String::as_str).collect();
    match args.as_slice() {
        ["--self-test"] => Ok(Command::SelfTest),
        ["provision", key, client_jwt_file] => {
            if client_jwt_file.is_empty() {
                return Err(usage());
            }
            Ok(Command::Provision {
                key: checked_key(key)?,
                client_jwt_file: PathBuf::from(client_jwt_file),
            })
        }
        ["usage", key] => Ok(Command::Usage {
            key: checked_key(key)?,
        }),
        ["usage-all"] => Ok(Command::UsageAll),
        ["remove", key] => Ok(Command::Remove {
            key: checked_key(key)?,
        }),
        ["acl", key, group] => {
            let group = acl_group(group).ok_or_else(usage)?;
            Ok(Command::Acl {
                key: checked_key(key)?,
                group,
            })
        }
        ["cap", key, options @ ..] => {
            let key = checked_key(key)?;
            let (mut monthly, mut total, mut reset_total) = (None, None, false);
            let mut index = 0;
            while index < options.len() {
                match options[index] {
                    "--monthly" if monthly.is_none() => {
                        let value = options.get(index + 1).ok_or_else(usage)?;
                        monthly = Some(parse_cap_value(value)?);
                        index += 2;
                    }
                    "--total" if total.is_none() => {
                        let value = options.get(index + 1).ok_or_else(usage)?;
                        total = Some(parse_cap_value(value)?);
                        index += 2;
                    }
                    "--reset-total" if !reset_total => {
                        reset_total = true;
                        index += 1;
                    }
                    _ => return Err(usage()),
                }
            }
            if monthly.is_none() && total.is_none() && !reset_total {
                return Err(usage());
            }
            Ok(Command::Cap {
                key,
                monthly,
                total,
                reset_total,
            })
        }
        _ => Err(usage()),
    }
}

/// One answer from the URnetwork API.
#[derive(Clone, Debug)]
struct ApiAnswer {
    status: u16,
    body: String,
}

/// The URnetwork API called with the root credential: real HTTPS, or the self-test's mock.
trait Api {
    /// `POST <path>` with a JSON body.
    fn post(&mut self, path: &str, body: &Value) -> Result<ApiAnswer, ToolError>;
    /// `GET <path>?<query>`.
    fn get(&mut self, path: &str, query: &[(&str, &str)]) -> Result<ApiAnswer, ToolError>;
}

/// The API origin: HTTPS, or explicit loopback HTTP for a local mock; no credentials, path, query
/// or fragment.
fn api_origin(base: &str) -> Result<reqwest::Url, ToolError> {
    let invalid = || {
        ToolError::Config(
            "URNETWORK_API_URL must be an HTTPS origin, or explicit loopback HTTP for a mock"
                .to_string(),
        )
    };
    let url = reqwest::Url::parse(base).map_err(|_| invalid())?;
    let local = matches!(
        url.host_str(),
        Some("localhost" | "127.0.0.1" | "[::1]" | "::1")
    );
    if url.host().is_none()
        || !url.username().is_empty()
        || url.password().is_some()
        || url.path() != "/"
        || url.query().is_some()
        || url.fragment().is_some()
        || (url.scheme() != "https" && !(url.scheme() == "http" && local))
    {
        return Err(invalid());
    }
    Ok(url)
}

/// The real API over HTTPS: no redirects, a timeout, bounded answers.
struct HttpApi {
    origin: reqwest::Url,
    root: String,
    client: reqwest::blocking::Client,
}

impl HttpApi {
    /// An API client with the root credential, which must be one token.
    fn new(origin: reqwest::Url, root: String) -> Result<Self, ToolError> {
        if root.is_empty() || root.chars().any(char::is_whitespace) {
            return Err(ToolError::Config(
                "set URNETWORK_ROOT_JWT to the backend root credential".to_string(),
            ));
        }
        let client = reqwest::blocking::Client::builder()
            .timeout(Duration::from_secs(15))
            .redirect(reqwest::redirect::Policy::none())
            .build()
            .map_err(|_| ToolError::Failure("could not create the HTTP client".to_string()))?;
        Ok(Self {
            origin,
            root,
            client,
        })
    }

    /// The URL of a path on the origin.
    fn url(&self, path: &str, query: &[(&str, &str)]) -> reqwest::Url {
        let mut url = self.origin.clone();
        url.set_path(path);
        if !query.is_empty() {
            let mut pairs = url.query_pairs_mut();
            for (name, value) in query {
                pairs.append_pair(name, value);
            }
        }
        url
    }

    /// Reads a bounded answer.
    fn answer(response: reqwest::blocking::Response) -> Result<ApiAnswer, ToolError> {
        let status = response.status().as_u16();
        let mut bytes = Vec::new();
        response
            .take(LIMIT + 1)
            .read_to_end(&mut bytes)
            .map_err(|_| {
                ToolError::Failure("could not read the URnetwork API answer".to_string())
            })?;
        if bytes.len() as u64 > LIMIT {
            return Err(ToolError::Failure(
                "the URnetwork API answer is too large".to_string(),
            ));
        }
        let body = String::from_utf8(bytes)
            .map_err(|_| ToolError::Failure("the URnetwork API answer is not UTF-8".to_string()))?;
        Ok(ApiAnswer { status, body })
    }
}

impl Api for HttpApi {
    fn post(&mut self, path: &str, body: &Value) -> Result<ApiAnswer, ToolError> {
        let response = self
            .client
            .post(self.url(path, &[]))
            .bearer_auth(&self.root)
            .json(body)
            .send()
            .map_err(|_| ToolError::Failure("could not reach the URnetwork API".to_string()))?;
        Self::answer(response)
    }

    fn get(&mut self, path: &str, query: &[(&str, &str)]) -> Result<ApiAnswer, ToolError> {
        let response = self
            .client
            .get(self.url(path, query))
            .bearer_auth(&self.root)
            .send()
            .map_err(|_| ToolError::Failure("could not reach the URnetwork API".to_string()))?;
        Self::answer(response)
    }
}

/// One line of a server message, safe to print.
fn one_line(message: &str) -> String {
    let line: String = message
        .chars()
        .map(|c| if c.is_control() { ' ' } else { c })
        .take(300)
        .collect();
    line.trim().to_string()
}

/// The JSON object of a 2xx answer, or the error for a refused credential, a missing route or
/// another HTTP failure. `route` is the path that was called and `what` names the call in
/// messages.
fn answer_object(
    answer: &ApiAnswer,
    route: &str,
    what: &str,
) -> Result<serde_json::Map<String, Value>, ToolError> {
    match answer.status {
        401 | 403 => {
            return Err(ToolError::Config(
                "the URnetwork API refused the root credential".to_string(),
            ));
        }
        // a server that predates the route (EMBED_CONTRACT.md, "Backend tools")
        404 if route == ACL_PATH => {
            return Err(ToolError::Failure(ACL_UNSUPPORTED_MESSAGE.to_string()));
        }
        404 if route == CAP_PATH || route == CAPS_PATH => {
            return Err(ToolError::Failure(format!(
                "{route} answered 404: the server predates the data-cap routes"
            )));
        }
        404 => {
            return Err(ToolError::Failure(format!("{what}: {route} answered 404")));
        }
        200..=299 => {}
        status => {
            return Err(ToolError::Failure(format!(
                "{what}: the URnetwork API answered HTTP {status}"
            )));
        }
    }
    match serde_json::from_str::<Value>(&answer.body) {
        Ok(Value::Object(object)) => Ok(object),
        _ => Err(ToolError::Failure(format!(
            "{what}: the URnetwork API answered something other than a JSON object"
        ))),
    }
}

/// The `error.message` of an answer object, when it carries an error.
fn error_message(object: &serde_json::Map<String, Value>) -> Option<String> {
    let error = object.get("error").filter(|error| !error.is_null())?;
    Some(
        error
            .get("message")
            .and_then(Value::as_str)
            .map(one_line)
            .unwrap_or_default(),
    )
}

/// The `client_id` claim of a JWT, as a consistency check, not signature verification.
fn jwt_client_id(jwt: &str) -> Option<String> {
    let parts: Vec<&str> = jwt.split('.').collect();
    if parts.len() != 3 || parts.iter().any(|part| part.is_empty()) {
        return None;
    }
    let claims: Value = serde_json::from_slice(
        &URL_SAFE_LENIENT
            .decode(parts[1].trim_end_matches('='))
            .ok()?,
    )
    .ok()?;
    claims["client_id"].as_str().map(str::to_string)
}

/// The auth-client request: a new client sends neither identity field; a reissue sends only its
/// stored `client_id`. Never `source_client_id`.
fn auth_client_request(client_id: Option<&str>) -> Value {
    let mut body = json!({ "description": DESCRIPTION, "device_spec": DEVICE_SPEC });
    if let Some(client_id) = client_id {
        body["client_id"] = json!(client_id);
    }
    body
}

/// What an auth-client answer means.
enum AuthClientAnswer {
    /// The client and its scoped JWT, checked.
    Client {
        client_id: String,
        by_client_jwt: String,
    },
    /// The reissued client was removed or deactivated.
    ClientDoesNotExist,
}

/// Checks an auth-client answer: the HTTP result, the API error (the client limit flags, a missing
/// client), both success fields, the JWT's `client_id` claim and, for a reissue, the mapped id.
fn read_auth_client_answer(
    answer: &ApiAnswer,
    expected: Option<&str>,
) -> Result<AuthClientAnswer, ToolError> {
    let object = answer_object(answer, AUTH_CLIENT_PATH, "provision")?;
    if let Some(error) = object.get("error").filter(|error| !error.is_null()) {
        let flag = |name: &str| error.get(name).and_then(Value::as_bool).unwrap_or(false);
        if flag("client_limit_exceeded") || flag("upgrade_required") {
            return Err(ToolError::ClientLimit);
        }
        let message = error_message(&object).unwrap_or_default();
        if message == CLIENT_DOES_NOT_EXIST {
            return Ok(AuthClientAnswer::ClientDoesNotExist);
        }
        return Err(ToolError::Failure(format!(
            "provision: the URnetwork API refused: {message}"
        )));
    }
    let client_id = object
        .get("client_id")
        .and_then(Value::as_str)
        .filter(|client_id| id_valid(client_id))
        .ok_or_else(|| {
            ToolError::Failure("provision: the answer has no valid client_id".to_string())
        })?;
    let by_client_jwt = object
        .get("by_client_jwt")
        .and_then(Value::as_str)
        .filter(|jwt| !jwt.is_empty())
        .ok_or_else(|| ToolError::Failure("provision: the answer has no scoped JWT".to_string()))?;
    if jwt_client_id(by_client_jwt).as_deref() != Some(client_id)
        || expected.is_some_and(|expected| expected != client_id)
    {
        return Err(ToolError::Failure(
            "provision: the scoped identity does not match".to_string(),
        ));
    }
    Ok(AuthClientAnswer::Client {
        client_id: client_id.to_string(),
        by_client_jwt: by_client_jwt.to_string(),
    })
}

/// Loads the private map; a missing file is an empty map. Anything else that is not a private,
/// bounded file of the allocators' format, with valid keys and distinct valid ids, is a
/// configuration error.
fn load_map(file: &Path) -> Result<ClientMap, ToolError> {
    let invalid = |reason: &str| ToolError::Config(format!("invalid client map: {reason}"));
    let stat = match fs::symlink_metadata(file) {
        Ok(stat) => stat,
        Err(error) if error.kind() == io::ErrorKind::NotFound => {
            return Ok(ClientMap {
                version: 1,
                clients: BTreeMap::new(),
                pending_acl: Vec::new(),
            });
        }
        Err(_) => return Err(invalid("unreadable")),
    };
    if !stat.is_file() || stat.len() > LIMIT {
        return Err(invalid("not a regular file of bounded size"));
    }
    #[cfg(unix)]
    if stat.permissions().mode() & 0o077 != 0 {
        return Err(invalid("it must be private (0600)"));
    }
    let data = fs::read(file).map_err(|_| invalid("unreadable"))?;
    let map: ClientMap = serde_json::from_slice(&data).map_err(|_| {
        invalid("not the allocators' format (version, clients and pending_acl only)")
    })?;
    if map.version != 1 {
        return Err(invalid("unknown version"));
    }
    let mut seen = BTreeSet::new();
    for (key, client_id) in &map.clients {
        if !key_valid(key) || !id_valid(client_id) || !seen.insert(client_id) {
            return Err(invalid("an invalid or duplicate mapping"));
        }
    }
    let mut pending = BTreeSet::new();
    for key in &map.pending_acl {
        if !map.clients.contains_key(key) || !pending.insert(key) {
            return Err(invalid("pending_acl is not a list of mapped keys"));
        }
    }
    Ok(map)
}

/// A unique suffix for temporary files.
fn unique_suffix() -> u128 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|duration| duration.as_nanos())
        .unwrap_or(0)
}

/// Replaces a file atomically with owner-only permissions: a new temporary file in the same
/// directory, written, synced, then renamed over the old one.
fn write_private_atomically(file: &Path, data: &[u8]) -> io::Result<()> {
    let temporary = file.with_extension(format!("{}.tmp", unique_suffix()));
    let mut options = OpenOptions::new();
    options.write(true).create_new(true);
    #[cfg(unix)]
    options.mode(0o600);
    let mut output = options.open(&temporary)?;
    let result = (|| {
        output.write_all(data)?;
        output.sync_all()?;
        drop(output);
        fs::rename(&temporary, file)
    })();
    let _ = fs::remove_file(&temporary);
    result
}

/// Saves the map.
fn save_map(file: &Path, map: &ClientMap) -> Result<(), ToolError> {
    let data = serde_json::to_vec(map)
        .map_err(|_| ToolError::Failure("could not encode the client map".to_string()))?;
    write_private_atomically(file, &data)
        .map_err(|_| ToolError::Failure("could not save the client map".to_string()))
}

/// The map's exclusive lock directory, removed on drop.
struct MapLock(PathBuf);

impl Drop for MapLock {
    fn drop(&mut self) {
        let _ = fs::remove_dir(&self.0);
    }
}

/// Takes `<map>.lock`, held through the remote calls and the map update.
fn lock_map(file: &Path) -> Result<MapLock, ToolError> {
    let lock = PathBuf::from(format!("{}.lock", file.display()));
    match fs::create_dir(&lock) {
        Ok(()) => {}
        Err(error) if error.kind() == io::ErrorKind::AlreadyExists => {
            return Err(ToolError::Failure(
                "the client map is locked by another process; retry".to_string(),
            ));
        }
        Err(_) => {
            return Err(ToolError::Config(
                "could not lock the client map; its directory must exist".to_string(),
            ));
        }
    }
    let guard = MapLock(lock);
    #[cfg(unix)]
    let _ = fs::set_permissions(&guard.0, fs::Permissions::from_mode(0o700));
    Ok(guard)
}

/// The mapped client of a key.
fn mapped_client(map: &ClientMap, key: &str) -> Result<String, ToolError> {
    map.clients
        .get(key)
        .cloned()
        .ok_or_else(|| ToolError::Config(UNMAPPED_MESSAGE.to_string()))
}

/// Writes one JSON value as a line.
fn print_json(out: &mut dyn Write, value: &Value) -> Result<(), ToolError> {
    writeln!(out, "{value}")?;
    Ok(())
}

/// `provision <key> <client-jwt-file>`: reissues the key's client or provisions a new one; on
/// `Client does not exist.` it drops the mapping and provisions a new client. A new client goes into
/// `acl_group`, with a `pending_acl` record until the group is applied, before any client JWT is
/// written. Writes the client JWT to the file (owner-only, replaced atomically), never prints it,
/// and prints `{"client_id": ...}`.
fn provision(
    key: &str,
    client_jwt_file: &Path,
    map_file: &Path,
    acl_group: &str,
    api: &mut dyn Api,
    out: &mut dyn Write,
) -> Result<(), ToolError> {
    let _lock = lock_map(map_file)?;
    let mut map = load_map(map_file)?;
    let mut mapped = map.clients.get(key).cloned();
    let (client_id, by_client_jwt) = loop {
        let answer = api.post(AUTH_CLIENT_PATH, &auth_client_request(mapped.as_deref()))?;
        match read_auth_client_answer(&answer, mapped.as_deref())? {
            AuthClientAnswer::Client {
                client_id,
                by_client_jwt,
            } => break (client_id, by_client_jwt),
            AuthClientAnswer::ClientDoesNotExist if mapped.is_some() => {
                // deactivated after 30 days without connecting, or removed: provision a new client
                map.clients.remove(key);
                map.set_acl_pending(key, false);
                save_map(map_file, &map)?;
                mapped = None;
            }
            AuthClientAnswer::ClientDoesNotExist => {
                return Err(ToolError::Failure(
                    "provision: the URnetwork API answered Client does not exist. for a new client"
                        .to_string(),
                ));
            }
        }
    };
    if mapped.is_none() {
        if map
            .clients
            .values()
            .any(|mapped_id| mapped_id == &client_id)
        {
            return Err(ToolError::Failure(
                "provision: the new client is already mapped to another key".to_string(),
            ));
        }
        map.clients.insert(key.to_string(), client_id.clone());
        // the mapping and the record that the client owes its group, in one save
        map.set_acl_pending(key, acl_group == ACL_GROUP_ISOLATED);
        save_map(map_file, &map)?;
    }
    if map.acl_pending(key) {
        // a new client is `default`: only an isolated default needs the request. A failure returns
        // with the record kept, before any client JWT is written.
        if acl_group == ACL_GROUP_ISOLATED {
            post_acl_group(api, &client_id, ACL_GROUP_ISOLATED)?;
        }
        map.set_acl_pending(key, false);
        save_map(map_file, &map)?;
    }
    write_private_atomically(client_jwt_file, format!("{by_client_jwt}\n").as_bytes())
        .map_err(|_| ToolError::Failure("could not write the client JWT file".to_string()))?;
    print_json(out, &json!({ "client_id": client_id }))
}

/// Sets the client's ACL group; the answer must name the client and the group.
fn post_acl_group(api: &mut dyn Api, client_id: &str, group: &str) -> Result<(), ToolError> {
    let answer = api.post(
        ACL_PATH,
        &json!({ "client_id": client_id, "acl_group": group }),
    )?;
    let object = answer_object(&answer, ACL_PATH, "acl")?;
    if let Some(message) = error_message(&object) {
        return Err(ToolError::Failure(format!(
            "acl: the URnetwork API refused: {message}"
        )));
    }
    if object.get("client_id").and_then(Value::as_str) != Some(client_id)
        || object.get("acl_group").and_then(Value::as_str) != Some(group)
    {
        return Err(ToolError::Failure(
            "acl: the URnetwork API answered another client or ACL group".to_string(),
        ));
    }
    Ok(())
}

/// `acl <key> default|isolated`: sets the ACL group of the key's client and prints `{"client_id":
/// ..., "acl_group": ...}`. An explicit group settles a pending default group, so the record is
/// dropped.
fn acl(
    key: &str,
    group: &str,
    map_file: &Path,
    api: &mut dyn Api,
    out: &mut dyn Write,
) -> Result<(), ToolError> {
    let _lock = lock_map(map_file)?;
    let mut map = load_map(map_file)?;
    let client_id = mapped_client(&map, key)?;
    post_acl_group(api, &client_id, group)?;
    if map.acl_pending(key) {
        map.set_acl_pending(key, false);
        save_map(map_file, &map)?;
    }
    // the contract's key order, which a serialized map would sort
    writeln!(
        out,
        "{{\"client_id\":{},\"acl_group\":{}}}",
        json!(client_id),
        json!(group)
    )?;
    Ok(())
}

/// The cap object of an answer, refused when it carries an error.
fn cap_object(answer: &ApiAnswer, route: &str, what: &str) -> Result<Value, ToolError> {
    let object = answer_object(answer, route, what)?;
    if let Some(message) = error_message(&object) {
        return Err(ToolError::Failure(format!(
            "{what}: the URnetwork API refused: {message}"
        )));
    }
    Ok(Value::Object(object))
}

/// The merge body of `POST /network/client-data-cap`: only the given fields. An omitted option is
/// absent from the JSON, `null` is JSON `null`, byte counts are integers, and `reset_total` appears
/// only when given.
fn cap_request(
    client_id: &str,
    monthly: Option<CapValue>,
    total: Option<CapValue>,
    reset_total: bool,
) -> Value {
    let mut body = json!({ "client_id": client_id });
    if let Some(monthly) = monthly {
        body["monthly_byte_limit"] = monthly.to_json();
    }
    if let Some(total) = total {
        body["total_byte_limit"] = total.to_json();
    }
    if reset_total {
        body["reset_total"] = json!(true);
    }
    body
}

/// `cap <key> ...`: posts only the given fields and prints the cap object.
fn cap(
    key: &str,
    monthly: Option<CapValue>,
    total: Option<CapValue>,
    reset_total: bool,
    map_file: &Path,
    api: &mut dyn Api,
    out: &mut dyn Write,
) -> Result<(), ToolError> {
    let client_id = mapped_client(&load_map(map_file)?, key)?;
    let answer = api.post(
        CAP_PATH,
        &cap_request(&client_id, monthly, total, reset_total),
    )?;
    print_json(out, &cap_object(&answer, CAP_PATH, "cap")?)
}

/// `usage <key>`: prints the key's cap object, read with the root credential.
fn usage(
    key: &str,
    map_file: &Path,
    api: &mut dyn Api,
    out: &mut dyn Write,
) -> Result<(), ToolError> {
    let client_id = mapped_client(&load_map(map_file)?, key)?;
    let answer = api.get(CAP_PATH, &[("client_id", &client_id)])?;
    print_json(out, &cap_object(&answer, CAP_PATH, "usage")?)
}

/// `usage-all`: pages through `GET /network/client-data-caps` and prints one cap object per line,
/// stopping at a `null` cursor or a repeated one.
fn usage_all(api: &mut dyn Api, out: &mut dyn Write) -> Result<(), ToolError> {
    let mut cursor: Option<String> = None;
    let mut seen_cursors = BTreeSet::new();
    loop {
        let mut query = vec![("limit", USAGE_ALL_PAGE_SIZE)];
        if let Some(cursor) = &cursor {
            query.push(("cursor", cursor.as_str()));
        }
        let answer = api.get(CAPS_PATH, &query)?;
        let page = cap_object(&answer, CAPS_PATH, "usage-all")?;
        let clients = page
            .get("clients")
            .and_then(Value::as_array)
            .ok_or_else(|| {
                ToolError::Failure("usage-all: the page has no clients list".to_string())
            })?;
        for client in clients {
            print_json(out, client)?;
        }
        match page.get("next_cursor").and_then(Value::as_str) {
            Some(next) if !next.is_empty() && seen_cursors.insert(next.to_string()) => {
                cursor = Some(next.to_string());
            }
            // a null cursor ends the list; a repeated one would loop forever
            _ => return Ok(()),
        }
    }
}

/// `remove <key>`: removes the key's client, then the mapping (also when the answer is `Client does
/// not exist.`), and prints `{"removed": ...}`.
fn remove(
    key: &str,
    map_file: &Path,
    api: &mut dyn Api,
    out: &mut dyn Write,
) -> Result<(), ToolError> {
    let _lock = lock_map(map_file)?;
    let mut map = load_map(map_file)?;
    let client_id = mapped_client(&map, key)?;
    let answer = api.post(REMOVE_PATH, &json!({ "client_id": client_id }))?;
    let object = answer_object(&answer, REMOVE_PATH, "remove")?;
    // `Client does not exist.` means the client is already gone: drop the mapping too
    if let Some(message) = error_message(&object).filter(|message| message != CLIENT_DOES_NOT_EXIST)
    {
        return Err(ToolError::Failure(format!(
            "remove: the URnetwork API refused: {message}"
        )));
    }
    map.clients.remove(key);
    map.set_acl_pending(key, false);
    save_map(map_file, &map)?;
    print_json(out, &json!({ "removed": client_id }))
}

/// Runs a parsed command other than the self-test; `acl_group` is the ACL group of each new client.
fn execute(
    command: &Command,
    map_file: &Path,
    acl_group: &str,
    api: &mut dyn Api,
    out: &mut dyn Write,
) -> Result<(), ToolError> {
    if !map_file.is_absolute() {
        return Err(ToolError::Config(
            "set URNETWORK_CLIENT_MAP to an absolute file path".to_string(),
        ));
    }
    match command {
        Command::Provision {
            key,
            client_jwt_file,
        } => provision(key, client_jwt_file, map_file, acl_group, api, out),
        Command::Cap {
            key,
            monthly,
            total,
            reset_total,
        } => cap(key, *monthly, *total, *reset_total, map_file, api, out),
        Command::Usage { key } => usage(key, map_file, api, out),
        Command::UsageAll => usage_all(api, out),
        Command::Remove { key } => remove(key, map_file, api, out),
        Command::Acl { key, group } => acl(key, group, map_file, api, out),
        Command::SelfTest => Err(ToolError::Config(USAGE.to_string())),
    }
}

/// A required setting.
fn required_setting(name: &str) -> Result<String, ToolError> {
    env::var(name)
        .ok()
        .filter(|value| !value.trim().is_empty())
        .ok_or_else(|| ToolError::Config(format!("set {name}")))
}

/// Parses the arguments, reads the settings and runs the command. Returns the exit code; errors are
/// printed as one line on `err`.
fn run(args: &[String], out: &mut dyn Write, err: &mut dyn Write) -> i32 {
    let result = parse_command(args).and_then(|command| {
        if command == Command::SelfTest {
            return self_test::run(out);
        }
        let map_file = PathBuf::from(required_setting("URNETWORK_CLIENT_MAP")?);
        let origin = api_origin(
            &env::var("URNETWORK_API_URL").unwrap_or_else(|_| "https://api.bringyour.com".into()),
        )?;
        let acl_group = default_acl_group(env::var("URNETWORK_DEFAULT_ACL_GROUP").ok().as_deref())?;
        let mut api = HttpApi::new(origin, required_setting("URNETWORK_ROOT_JWT")?)?;
        execute(&command, &map_file, acl_group, &mut api, out)
    });
    match result {
        Ok(()) => EXIT_OK,
        Err(error) => {
            let _ = writeln!(err, "{}", error.message());
            error.exit_code()
        }
    }
}

fn main() {
    let args: Vec<String> = env::args().skip(1).collect();
    let code = run(&args, &mut io::stdout().lock(), &mut io::stderr().lock());
    std::process::exit(code);
}

/// The credential-free self-test (EMBED_CONTRACT.md, "Backend tools"): every command against a mock
/// API, in a private temporary directory, without credentials or a network.
mod self_test {
    use std::collections::VecDeque;

    use super::*;

    const CLIENT_A: &str = "11111111-1111-1111-1111-111111111111";
    const CLIENT_B: &str = "22222222-2222-2222-2222-222222222222";
    const KEY: &str = "user:alice:33333333-3333-3333-3333-333333333333";

    /// One recorded call.
    #[derive(Clone, Debug)]
    struct Call {
        method: &'static str,
        path: String,
        body: Option<Value>,
        query: Vec<(String, String)>,
    }

    /// Answers calls in order and records them.
    struct MockApi {
        answers: VecDeque<ApiAnswer>,
        calls: Vec<Call>,
    }

    impl MockApi {
        fn new(answers: &[(u16, Value)]) -> Self {
            Self {
                answers: answers
                    .iter()
                    .map(|(status, body)| ApiAnswer {
                        status: *status,
                        body: body.to_string(),
                    })
                    .collect(),
                calls: Vec::new(),
            }
        }

        fn next(&mut self) -> Result<ApiAnswer, ToolError> {
            self.answers
                .pop_front()
                .ok_or_else(|| ToolError::Failure("mock: no more answers".to_string()))
        }
    }

    impl Api for MockApi {
        fn post(&mut self, path: &str, body: &Value) -> Result<ApiAnswer, ToolError> {
            self.calls.push(Call {
                method: "POST",
                path: path.to_string(),
                body: Some(body.clone()),
                query: Vec::new(),
            });
            self.next()
        }

        fn get(&mut self, path: &str, query: &[(&str, &str)]) -> Result<ApiAnswer, ToolError> {
            self.calls.push(Call {
                method: "GET",
                path: path.to_string(),
                body: None,
                query: query
                    .iter()
                    .map(|(name, value)| (name.to_string(), value.to_string()))
                    .collect(),
            });
            self.next()
        }
    }

    /// A synthetic, unsigned JWT for a client.
    fn jwt(client_id: &str) -> String {
        use base64::engine::general_purpose::URL_SAFE_NO_PAD;
        format!(
            "e30.{}.test",
            URL_SAFE_NO_PAD.encode(json!({ "client_id": client_id }).to_string())
        )
    }

    /// An auth-client success answer.
    fn auth_ok(client_id: &str) -> (u16, Value) {
        (
            200,
            json!({ "client_id": client_id, "by_client_jwt": jwt(client_id) }),
        )
    }

    /// An API error answer.
    fn api_error(message: &str) -> (u16, Value) {
        (
            200,
            json!({ "error": { "client_limit_exceeded": false, "message": message } }),
        )
    }

    /// A cap object answer.
    fn cap_answer(client_id: &str) -> (u16, Value) {
        (
            200,
            json!({ "client_id": client_id, "monthly_byte_limit": 10000000000u64, "monthly_used_byte_count": 0, "total_byte_limit": null, "total_used_byte_count": 0, "capped": false, "capped_reason": "" }),
        )
    }

    /// Runs a command against the mock with new clients in `default`, which sends no ACL request;
    /// returns the exit code, stdout and stderr.
    fn invoke(args: &[&str], map_file: &Path, api: &mut MockApi) -> (i32, String, String) {
        invoke_with(args, map_file, ACL_GROUP_DEFAULT, api)
    }

    /// Runs a command against the mock with new clients in `acl_group`.
    fn invoke_with(
        args: &[&str],
        map_file: &Path,
        acl_group: &str,
        api: &mut MockApi,
    ) -> (i32, String, String) {
        let args: Vec<String> = args.iter().map(|arg| arg.to_string()).collect();
        let mut out = Vec::new();
        let result = parse_command(&args)
            .and_then(|command| execute(&command, map_file, acl_group, api, &mut out));
        let (code, err) = match result {
            Ok(()) => (EXIT_OK, String::new()),
            Err(error) => (error.exit_code(), format!("{}\n", error.message())),
        };
        (code, String::from_utf8_lossy(&out).into_owned(), err)
    }

    /// Fails with a message unless the condition holds.
    fn ensure(condition: bool, message: &str) -> Result<(), ToolError> {
        if condition {
            Ok(())
        } else {
            Err(ToolError::Failure(format!("self-test: {message}")))
        }
    }

    /// The POSIX mode of a file.
    #[cfg(unix)]
    fn mode(path: &Path) -> u32 {
        fs::metadata(path)
            .map(|metadata| metadata.permissions().mode() & 0o777)
            .unwrap_or(0)
    }

    /// Runs every check in a private temporary directory, then prints the passed line.
    pub(super) fn run(out: &mut dyn Write) -> Result<(), ToolError> {
        let dir = env::temp_dir().join(format!(
            "ur-embed-server-{}-{}",
            std::process::id(),
            unique_suffix()
        ));
        fs::create_dir(&dir)?;
        #[cfg(unix)]
        fs::set_permissions(&dir, fs::Permissions::from_mode(0o700))?;
        let result = checks(&dir)
            .and_then(|()| acl_checks(&dir))
            .and_then(|()| failure_text_checks(&dir));
        let _ = fs::remove_dir_all(&dir);
        result?;
        writeln!(out, "embed backend tool self-test passed")?;
        Ok(())
    }

    fn checks(dir: &Path) -> Result<(), ToolError> {
        let map_file = dir.join("clients.json");
        let jwt_file = dir.join("alice.jwt");
        let jwt_path = jwt_file.to_str().unwrap_or_default().to_string();

        // new versus reissue: a new client sends neither identity field, a reissue its stored id
        let mut api = MockApi::new(&[auth_ok(CLIENT_A), auth_ok(CLIENT_A)]);
        let (code, stdout, stderr) = invoke(&["provision", KEY, &jwt_path], &map_file, &mut api);
        ensure(code == EXIT_OK, &format!("provision failed: {stderr}"))?;
        ensure(
            stdout == format!("{{\"client_id\":\"{CLIENT_A}\"}}\n"),
            "provision stdout",
        )?;
        let first = api.calls[0].body.clone().unwrap_or_default();
        ensure(
            first == json!({ "description": DESCRIPTION, "device_spec": DEVICE_SPEC }),
            "a new client's request carries an identity field",
        )?;
        ensure(
            api.calls[0].method == "POST" && api.calls[0].path == "/network/auth-client",
            "provision posts to /network/auth-client",
        )?;
        // the token reaches the private file, never stdout or stderr
        let saved = fs::read_to_string(&jwt_file)?;
        ensure(saved.trim() == jwt(CLIENT_A), "the client JWT file content")?;
        ensure(
            !stdout.contains(&jwt(CLIENT_A)) && !stderr.contains("e30."),
            "the token was printed",
        )?;
        #[cfg(unix)]
        ensure(
            mode(&jwt_file) == 0o600,
            "the client JWT file is not private",
        )?;
        #[cfg(unix)]
        ensure(mode(&map_file) == 0o600, "the map is not private")?;
        let (code, _, _) = invoke(&["provision", KEY, &jwt_path], &map_file, &mut api);
        ensure(code == EXIT_OK, "reissue failed")?;
        let second = api.calls[1].body.clone().unwrap_or_default();
        ensure(
            second["client_id"] == CLIENT_A,
            "a reissue does not send its stored client_id",
        )?;
        ensure(
            api.calls.iter().all(|call| {
                call.body
                    .as_ref()
                    .is_none_or(|body| body.get("source_client_id").is_none())
            }),
            "a request carries source_client_id",
        )?;
        ensure(
            load_map(&map_file)?.clients.get(KEY).map(String::as_str) == Some(CLIENT_A),
            "the mapping",
        )?;
        ensure(
            !PathBuf::from(format!("{}.lock", map_file.display())).exists(),
            "the lock was left behind",
        )?;

        // Client does not exist.: drop the mapping and provision a new client
        let mut api = MockApi::new(&[api_error(CLIENT_DOES_NOT_EXIST), auth_ok(CLIENT_B)]);
        let (code, stdout, stderr) = invoke(&["provision", KEY, &jwt_path], &map_file, &mut api);
        ensure(code == EXIT_OK, &format!("re-provision failed: {stderr}"))?;
        ensure(
            api.calls[0]
                .body
                .as_ref()
                .is_some_and(|body| body["client_id"] == CLIENT_A),
            "the reissue",
        )?;
        ensure(
            api.calls[1]
                .body
                .as_ref()
                .is_some_and(|body| body.get("client_id").is_none()),
            "the new provision sends client_id",
        )?;
        ensure(stdout.contains(CLIENT_B), "the new client is not printed")?;
        ensure(
            load_map(&map_file)?.clients.get(KEY).map(String::as_str) == Some(CLIENT_B),
            "the new mapping",
        )?;

        // response and claim checks: nothing changes the map
        let mismatched = (
            200,
            json!({ "client_id": CLIENT_A, "by_client_jwt": jwt(CLIENT_B) }),
        );
        let missing_jwt = (200, json!({ "client_id": CLIENT_A }));
        let reissued_other = auth_ok(CLIENT_A);
        for (answer, what) in [
            (mismatched, "a JWT for another client"),
            (missing_jwt, "a missing JWT"),
            (reissued_other, "a reissue answering another client"),
            (api_error("Something else."), "another API error"),
            ((500, json!({})), "an HTTP 500"),
        ] {
            let mut api = MockApi::new(&[answer]);
            let (code, _, _) = invoke(&["provision", KEY, &jwt_path], &map_file, &mut api);
            ensure(
                code == EXIT_FAILURE,
                &format!("{what} did not fail with exit 1"),
            )?;
            ensure(
                load_map(&map_file)?.clients.get(KEY).map(String::as_str) == Some(CLIENT_B),
                &format!("{what} changed the map"),
            )?;
        }
        // a new client already mapped to another key
        let mut api = MockApi::new(&[auth_ok(CLIENT_B)]);
        let (code, _, _) = invoke(
            &[
                "provision",
                "user:bob",
                &dir.join("bob.jwt").to_string_lossy(),
            ],
            &map_file,
            &mut api,
        );
        ensure(
            code == EXIT_FAILURE,
            "a client mapped to another key was accepted",
        )?;

        // the client limit, both flags, and a refused root credential exit 78
        for error in [
            json!({ "error": { "client_limit_exceeded": true, "message": "Client limit exceeded." } }),
            json!({ "error": { "client_limit_exceeded": false, "upgrade_required": true, "message": "Upgrade required." } }),
        ] {
            let mut api = MockApi::new(&[(200, error)]);
            let (code, stdout, stderr) = invoke(
                &[
                    "provision",
                    "user:carol",
                    &dir.join("carol.jwt").to_string_lossy(),
                ],
                &map_file,
                &mut api,
            );
            ensure(
                code == EXIT_CONFIG && stdout.is_empty(),
                "the client limit did not exit 78",
            )?;
            ensure(
                stderr == format!("{CLIENT_LIMIT_MESSAGE}\n"),
                "the client limit message",
            )?;
        }
        let mut api = MockApi::new(&[(401, json!({}))]);
        let (code, _, _) = invoke(&["usage", KEY], &map_file, &mut api);
        ensure(
            code == EXIT_CONFIG,
            "a refused root credential did not exit 78",
        )?;

        // key and argument rejection
        for args in [
            vec!["provision", "alice", "x.jwt"],
            vec!["provision", "user:", "x.jwt"],
            vec!["provision", "user:../a", "x.jwt"],
            vec!["provision", "user:a b", "x.jwt"],
            vec!["provision", CLIENT_A, "x.jwt"],
            vec!["provision", KEY],
            vec!["provision", KEY, ""],
            vec!["cap", KEY],
            vec!["cap", KEY, "--monthly"],
            vec!["cap", KEY, "--monthly", "5GB"],
            vec!["cap", KEY, "--monthly", "-1"],
            vec!["cap", KEY, "--monthly", "+5"],
            vec!["cap", KEY, "--monthly", "1e9"],
            vec!["cap", KEY, "--monthly", "9223372036854775808"],
            vec!["cap", KEY, "--monthly", "1", "--monthly", "2"],
            vec!["cap", KEY, "--reset-total", "--reset-total"],
            vec!["cap", KEY, "--weekly", "1"],
            vec!["usage-all", "extra"],
            vec!["remove"],
            vec!["--unknown"],
            vec![],
        ] {
            let mut api = MockApi::new(&[]);
            let (code, _, _) = invoke(&args, &map_file, &mut api);
            ensure(
                code == EXIT_CONFIG && api.calls.is_empty(),
                &format!("arguments {args:?} were accepted"),
            )?;
        }
        let long_key = format!("user:{}", "a".repeat(124));
        ensure(
            !key_valid(&long_key) && key_valid(&long_key[..128]),
            "the key length limit",
        )?;
        ensure(
            parse_cap_value("9223372036854775807") == Ok(CapValue::Bytes(9223372036854775807)),
            "the largest cap",
        )?;
        ensure(parse_cap_value("0") == Ok(CapValue::Bytes(0)), "a zero cap")?;

        // merge request bodies: only the given fields
        for (args, want) in [
            (
                vec!["--monthly", "10000000000"],
                json!({ "client_id": CLIENT_B, "monthly_byte_limit": 10000000000u64 }),
            ),
            (
                vec!["--monthly", "null"],
                json!({ "client_id": CLIENT_B, "monthly_byte_limit": null }),
            ),
            (
                vec!["--total", "0", "--reset-total"],
                json!({ "client_id": CLIENT_B, "total_byte_limit": 0, "reset_total": true }),
            ),
            (
                vec!["--reset-total"],
                json!({ "client_id": CLIENT_B, "reset_total": true }),
            ),
        ] {
            let mut api = MockApi::new(&[cap_answer(CLIENT_B)]);
            let mut command = vec!["cap", KEY];
            command.extend(args.iter().copied());
            let (code, stdout, _) = invoke(&command, &map_file, &mut api);
            ensure(code == EXIT_OK, &format!("cap {args:?} failed"))?;
            let body = api.calls[0].body.clone().unwrap_or_default();
            ensure(body == want, &format!("cap {args:?} posted {body}"))?;
            ensure(
                api.calls[0].path == "/network/client-data-cap",
                "the cap path",
            )?;
            if let Some(limit) = body
                .get("monthly_byte_limit")
                .filter(|limit| !limit.is_null())
            {
                ensure(limit.is_u64(), "a byte count is not a JSON integer")?;
            }
            // the cap object is printed as one line
            ensure(
                stdout.lines().count() == 1
                    && stdout.contains("\"monthly_byte_limit\":10000000000"),
                "the cap object output",
            )?;
        }
        // a cap error or a server without the routes fails with exit 1
        for answer in [api_error("Client does not exist."), (404, json!({}))] {
            let mut api = MockApi::new(&[answer]);
            let (code, stdout, _) = invoke(&["cap", KEY, "--monthly", "1"], &map_file, &mut api);
            ensure(
                code == EXIT_FAILURE && stdout.is_empty(),
                "a failed cap did not exit 1",
            )?;
        }
        // usage reads the key's client with the root credential
        let mut api = MockApi::new(&[cap_answer(CLIENT_B)]);
        let (code, stdout, _) = invoke(&["usage", KEY], &map_file, &mut api);
        ensure(code == EXIT_OK && stdout.contains(CLIENT_B), "usage failed")?;
        ensure(
            api.calls[0].method == "GET"
                && api.calls[0].path == "/network/client-data-cap"
                && api.calls[0].query == [("client_id".to_string(), CLIENT_B.to_string())],
            "usage reads GET /network/client-data-cap?client_id=",
        )?;
        // an unmapped key is a configuration error
        let mut api = MockApi::new(&[]);
        let (code, _, _) = invoke(&["usage", "user:nobody"], &map_file, &mut api);
        ensure(
            code == EXIT_CONFIG && api.calls.is_empty(),
            "an unmapped key was read",
        )?;

        // usage-all pages, and stops at a repeated cursor and at a null cursor
        let mut api = MockApi::new(&[
            (
                200,
                json!({ "clients": [cap_answer(CLIENT_A).1, cap_answer(CLIENT_B).1], "next_cursor": "c1" }),
            ),
            (
                200,
                json!({ "clients": [cap_answer(CLIENT_A).1], "next_cursor": "c2" }),
            ),
            (200, json!({ "clients": [], "next_cursor": "c2" })),
        ]);
        let (code, stdout, _) = invoke(&["usage-all"], &map_file, &mut api);
        ensure(
            code == EXIT_OK && stdout.lines().count() == 3,
            "usage-all lines",
        )?;
        ensure(
            api.calls.len() == 3,
            "usage-all did not stop at a repeated cursor",
        )?;
        ensure(
            api.calls[0].query == [("limit".to_string(), "1000".to_string())],
            "the first page query",
        )?;
        ensure(
            api.calls[2]
                .query
                .contains(&("cursor".to_string(), "c2".to_string())),
            "the cursor query",
        )?;
        let mut api = MockApi::new(&[(
            200,
            json!({ "clients": [cap_answer(CLIENT_A).1], "next_cursor": null }),
        )]);
        let (code, stdout, _) = invoke(&["usage-all"], &map_file, &mut api);
        ensure(
            code == EXIT_OK && stdout.lines().count() == 1 && api.calls.len() == 1,
            "usage-all with a null cursor",
        )?;

        // remove drops the mapping for both answers, and keeps it on another error
        for answer in [(200, json!({})), api_error(CLIENT_DOES_NOT_EXIST)] {
            let mut map = load_map(&map_file)?;
            map.clients.insert(KEY.to_string(), CLIENT_B.to_string());
            save_map(&map_file, &map)?;
            let mut api = MockApi::new(&[answer]);
            let (code, stdout, _) = invoke(&["remove", KEY], &map_file, &mut api);
            ensure(code == EXIT_OK, "remove failed")?;
            ensure(
                stdout == format!("{{\"removed\":\"{CLIENT_B}\"}}\n"),
                "remove stdout",
            )?;
            ensure(
                api.calls[0].body == Some(json!({ "client_id": CLIENT_B })),
                "the remove request",
            )?;
            ensure(
                !load_map(&map_file)?.clients.contains_key(KEY),
                "remove kept the mapping",
            )?;
        }
        let mut map = load_map(&map_file)?;
        map.clients.insert(KEY.to_string(), CLIENT_B.to_string());
        save_map(&map_file, &map)?;
        let mut api = MockApi::new(&[api_error("Something else.")]);
        let (code, _, _) = invoke(&["remove", KEY], &map_file, &mut api);
        ensure(
            code == EXIT_FAILURE && load_map(&map_file)?.clients.contains_key(KEY),
            "a failed remove dropped the mapping",
        )?;

        // a map with unknown fields, such as the token server's, is refused
        let token_server_map = dir.join("token-server-clients.json");
        write_private_atomically(
            &token_server_map,
            json!({ "version": 1, "clients": {}, "pending_caps": [] })
                .to_string()
                .as_bytes(),
        )?;
        let mut api = MockApi::new(&[auth_ok(CLIENT_A)]);
        let (code, _, _) = invoke(&["provision", KEY, &jwt_path], &token_server_map, &mut api);
        ensure(
            code == EXIT_CONFIG && api.calls.is_empty(),
            "a map with pending_caps was accepted",
        )?;
        // a group-readable map is refused
        #[cfg(unix)]
        {
            fs::set_permissions(&map_file, fs::Permissions::from_mode(0o644))?;
            let mut api = MockApi::new(&[]);
            let (code, _, _) = invoke(&["usage", KEY], &map_file, &mut api);
            ensure(code == EXIT_CONFIG, "a group-readable map was accepted")?;
            fs::set_permissions(&map_file, fs::Permissions::from_mode(0o600))?;
        }
        // a relative map path is refused
        let mut api = MockApi::new(&[]);
        let (code, _, _) = invoke(&["usage-all"], Path::new("clients.json"), &mut api);
        ensure(code == EXIT_CONFIG, "a relative map path was accepted")?;
        // the API origin rules
        ensure(
            api_origin("https://api.bringyour.com").is_ok(),
            "the default origin",
        )?;
        ensure(
            api_origin("http://127.0.0.1:8080").is_ok(),
            "a loopback mock origin",
        )?;
        for origin in [
            "http://example.com",
            "https://example.com/path",
            "https://user@example.com",
        ] {
            ensure(
                api_origin(origin).is_err(),
                &format!("origin {origin} was accepted"),
            )?;
        }
        Ok(())
    }

    /// An ACL group answer.
    fn acl_answer(client_id: &str, group: &str) -> (u16, Value) {
        (200, json!({ "client_id": client_id, "acl_group": group }))
    }

    /// The keys a map file records in `pending_acl`.
    fn pending(map_file: &Path) -> Result<Vec<String>, ToolError> {
        Ok(load_map(map_file)?.pending_acl)
    }

    /// The default ACL group: applied to a new client only, with `pending_acl` set before and
    /// cleared after, kept and retried after a failure, kept on a server without ACL groups, and
    /// settled by `acl`; the `acl` command.
    fn acl_checks(dir: &Path) -> Result<(), ToolError> {
        let map_file = dir.join("acl.json");
        let jwt_file = dir.join("ivan.jwt");
        let jwt_path = jwt_file.to_string_lossy().to_string();

        // the setting: unset or empty means isolated, other values are a configuration error
        ensure(
            default_acl_group(None) == Ok(ACL_GROUP_ISOLATED)
                && default_acl_group(Some("")) == Ok(ACL_GROUP_ISOLATED)
                && default_acl_group(Some("default")) == Ok(ACL_GROUP_DEFAULT)
                && default_acl_group(Some("private")).is_err_and(|error| {
                    error.exit_code() == EXIT_CONFIG
                        && error.message()
                            == "URNETWORK_DEFAULT_ACL_GROUP must be default or isolated"
                }),
            "the default ACL group setting",
        )?;

        // isolated: the new client, then one ACL request, then no record
        let mut api = MockApi::new(&[auth_ok(CLIENT_A), acl_answer(CLIENT_A, ACL_GROUP_ISOLATED)]);
        let (code, _, stderr) = invoke_with(
            &["provision", "user:ivan", &jwt_path],
            &map_file,
            ACL_GROUP_ISOLATED,
            &mut api,
        );
        ensure(
            code == EXIT_OK,
            &format!("isolated provision failed: {stderr}"),
        )?;
        ensure(
            api.calls.len() == 2
                && api.calls[1].path == ACL_PATH
                && api.calls[1].body
                    == Some(json!({ "client_id": CLIENT_A, "acl_group": ACL_GROUP_ISOLATED })),
            "a new client is not put in isolated",
        )?;
        ensure(
            pending(&map_file)?.is_empty()
                && !fs::read_to_string(&map_file)?.contains("pending_acl")
                && fs::read_to_string(&jwt_file)?.trim() == jwt(CLIENT_A),
            "an applied group leaves its record or no client JWT",
        )?;
        let mut api = MockApi::new(&[auth_ok(CLIENT_A)]);
        let (code, _, _) = invoke_with(
            &["provision", "user:ivan", &jwt_path],
            &map_file,
            ACL_GROUP_ISOLATED,
            &mut api,
        );
        ensure(
            code == EXIT_OK && api.calls.len() == 1,
            "a reissue sends an ACL request",
        )?;

        // a failed request keeps the record and writes no client JWT; the next issue retries
        let judy_jwt = dir.join("judy.jwt");
        let judy_path = judy_jwt.to_string_lossy().to_string();
        let mut api = MockApi::new(&[auth_ok(CLIENT_B), (500, json!({}))]);
        let (code, _, _) = invoke_with(
            &["provision", "user:judy", &judy_path],
            &map_file,
            ACL_GROUP_ISOLATED,
            &mut api,
        );
        ensure(
            code == EXIT_FAILURE
                && !judy_jwt.exists()
                && pending(&map_file)? == ["user:judy"]
                && load_map(&map_file)?
                    .clients
                    .get("user:judy")
                    .map(String::as_str)
                    == Some(CLIENT_B),
            "a failed ACL request does not keep the record",
        )?;
        let mut api = MockApi::new(&[auth_ok(CLIENT_B), acl_answer(CLIENT_B, ACL_GROUP_ISOLATED)]);
        let (code, _, _) = invoke_with(
            &["provision", "user:judy", &judy_path],
            &map_file,
            ACL_GROUP_ISOLATED,
            &mut api,
        );
        ensure(
            code == EXIT_OK
                && api.calls.len() == 2
                && api.calls[1].path == ACL_PATH
                && pending(&map_file)?.is_empty()
                && judy_jwt.exists(),
            "a pending group is not applied on the next issue",
        )?;

        // a server without ACL groups: exit 1 with its line and the record kept; a default of
        // default then provisions without a request
        let older_map = dir.join("older.json");
        let kim = "55555555-5555-5555-5555-555555555555";
        let kim_jwt = dir.join("kim.jwt");
        let kim_path = kim_jwt.to_string_lossy().to_string();
        let mut api = MockApi::new(&[auth_ok(kim), (404, json!({}))]);
        let (code, _, stderr) = invoke_with(
            &["provision", "user:kim", &kim_path],
            &older_map,
            ACL_GROUP_ISOLATED,
            &mut api,
        );
        ensure(
            code == EXIT_FAILURE
                && stderr == format!("{ACL_UNSUPPORTED_MESSAGE}\n")
                && !kim_jwt.exists()
                && pending(&older_map)? == ["user:kim"],
            &format!("a server without ACL groups answers {code}: {stderr}"),
        )?;
        let mut api = MockApi::new(&[auth_ok(kim)]);
        let (code, _, _) = invoke(&["provision", "user:kim", &kim_path], &older_map, &mut api);
        ensure(
            code == EXIT_OK
                && api.calls.len() == 1
                && pending(&older_map)?.is_empty()
                && kim_jwt.exists(),
            "a default of default does not settle the record without a request",
        )?;

        // the acl command: the request, the printed answer, and its refusals
        let mut api = MockApi::new(&[acl_answer(CLIENT_A, ACL_GROUP_DEFAULT)]);
        let (code, stdout, _) = invoke(&["acl", "user:ivan", "default"], &map_file, &mut api);
        ensure(
            code == EXIT_OK
                && stdout
                    == format!("{{\"client_id\":\"{CLIENT_A}\",\"acl_group\":\"default\"}}\n")
                && api.calls[0].body
                    == Some(json!({ "client_id": CLIENT_A, "acl_group": "default" })),
            &format!("acl printed {stdout}"),
        )?;
        for args in [
            vec!["acl"],
            vec!["acl", "user:ivan"],
            vec!["acl", "user:ivan", "public"],
            vec!["acl", "user:ivan", "Default"],
            vec!["acl", "user:ivan", "default", "extra"],
            vec!["acl", "alice", "default"],
        ] {
            let mut api = MockApi::new(&[]);
            let (code, _, _) = invoke(&args, &map_file, &mut api);
            ensure(
                code == EXIT_CONFIG && api.calls.is_empty(),
                &format!("arguments {args:?} were accepted"),
            )?;
        }
        for (answer, what) in [
            (
                acl_answer(CLIENT_A, ACL_GROUP_DEFAULT),
                "an answer for another group",
            ),
            (api_error(CLIENT_DOES_NOT_EXIST), "a refusal"),
        ] {
            let mut api = MockApi::new(&[answer]);
            let (code, _, _) = invoke(&["acl", "user:ivan", "isolated"], &map_file, &mut api);
            ensure(code == EXIT_FAILURE, &format!("{what} was accepted"))?;
        }
        let mut api = MockApi::new(&[(404, json!({}))]);
        let (code, _, stderr) = invoke(&["acl", "user:ivan", "isolated"], &map_file, &mut api);
        ensure(
            code == EXIT_FAILURE && stderr == format!("{ACL_UNSUPPORTED_MESSAGE}\n"),
            &format!("acl on a server without ACL groups: {stderr}"),
        )?;

        // an explicit group settles a pending record, so a later issue keeps it
        let mia = "66666666-6666-6666-6666-666666666666";
        let mia_path = dir.join("mia.jwt").to_string_lossy().to_string();
        let mut api = MockApi::new(&[auth_ok(mia), (500, json!({}))]);
        invoke_with(
            &["provision", "user:mia", &mia_path],
            &map_file,
            ACL_GROUP_ISOLATED,
            &mut api,
        );
        ensure(
            pending(&map_file)? == ["user:mia"],
            "the failed group is not recorded",
        )?;
        let mut api = MockApi::new(&[acl_answer(mia, ACL_GROUP_DEFAULT)]);
        let (code, _, _) = invoke(&["acl", "user:mia", "default"], &map_file, &mut api);
        ensure(
            code == EXIT_OK && pending(&map_file)?.is_empty(),
            "acl does not settle a pending record",
        )?;
        let mut api = MockApi::new(&[auth_ok(mia)]);
        let (code, _, _) = invoke_with(
            &["provision", "user:mia", &mia_path],
            &map_file,
            ACL_GROUP_ISOLATED,
            &mut api,
        );
        ensure(
            code == EXIT_OK && api.calls.len() == 1,
            "a settled group is overridden on the next issue",
        )?;

        // remove drops a pending record with its mapping
        let noor = "77777777-7777-7777-7777-777777777777";
        let noor_path = dir.join("noor.jwt").to_string_lossy().to_string();
        let mut api = MockApi::new(&[auth_ok(noor), (500, json!({}))]);
        invoke_with(
            &["provision", "user:noor", &noor_path],
            &map_file,
            ACL_GROUP_ISOLATED,
            &mut api,
        );
        let mut api = MockApi::new(&[(200, json!({}))]);
        let (code, _, _) = invoke(&["remove", "user:noor"], &map_file, &mut api);
        ensure(
            code == EXIT_OK && pending(&map_file)?.is_empty(),
            "remove keeps a pending record",
        )?;

        // a pending_acl that is not a list of distinct mapped keys is refused untouched
        let pending_map = dir.join("pending.json");
        for map in [
            json!({ "version": 1, "clients": {}, "pending_acl": ["user:x"] }),
            json!({ "version": 1, "clients": { "user:x": CLIENT_A }, "pending_acl": ["user:x", "user:x"] }),
            json!({ "version": 1, "clients": { "user:x": CLIENT_A }, "pending_acl": "user:x" }),
            json!({ "version": 1, "clients": { "user:x": CLIENT_A }, "pending_acl": [1] }),
        ] {
            let text = map.to_string();
            write_private_atomically(&pending_map, text.as_bytes())?;
            let mut api = MockApi::new(&[]);
            let (code, _, _) = invoke(&["provision", "user:x", &jwt_path], &pending_map, &mut api);
            ensure(
                code == EXIT_CONFIG
                    && fs::read_to_string(&pending_map)? == text
                    && api.calls.is_empty(),
                &format!("the map {text} was accepted"),
            )?;
        }
        Ok(())
    }

    /// The unmapped-key and 404 lines.
    fn failure_text_checks(dir: &Path) -> Result<(), ToolError> {
        let map_file = dir.join("texts.json");
        for args in [
            vec!["cap", "user:nobody", "--monthly", "1"],
            vec!["usage", "user:nobody"],
            vec!["remove", "user:nobody"],
            vec!["acl", "user:nobody", "default"],
        ] {
            let mut api = MockApi::new(&[]);
            let (code, _, stderr) = invoke(&args, &map_file, &mut api);
            ensure(
                code == EXIT_CONFIG
                    && stderr == format!("{UNMAPPED_MESSAGE}\n")
                    && api.calls.is_empty(),
                &format!("{args:?} for an unmapped key: {stderr}"),
            )?;
        }
        let olga_path = dir.join("olga.jwt").to_string_lossy().to_string();
        let mut api = MockApi::new(&[auth_ok(CLIENT_A)]);
        invoke(&["provision", "user:olga", &olga_path], &map_file, &mut api);
        for (args, route) in [
            (vec!["cap", "user:olga", "--monthly", "1"], CAP_PATH),
            (vec!["usage", "user:olga"], CAP_PATH),
            (vec!["usage-all"], CAPS_PATH),
        ] {
            let mut api = MockApi::new(&[(404, json!({}))]);
            let (code, _, stderr) = invoke(&args, &map_file, &mut api);
            ensure(
                code == EXIT_FAILURE
                    && stderr
                        == format!(
                            "{route} answered 404: the server predates the data-cap routes\n"
                        ),
                &format!("{args:?} on a server without the cap routes: {stderr}"),
            )?;
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn self_test_passes() {
        let mut out = Vec::new();
        let mut err = Vec::new();
        let code = run(&["--self-test".to_string()], &mut out, &mut err);
        assert_eq!(code, EXIT_OK, "{}", String::from_utf8_lossy(&err));
        assert_eq!(
            String::from_utf8_lossy(&out),
            "embed backend tool self-test passed\n"
        );
    }

    #[test]
    fn usage_errors_exit_78() {
        let mut out = Vec::new();
        let mut err = Vec::new();
        assert_eq!(
            run(&["--unknown".to_string()], &mut out, &mut err),
            EXIT_CONFIG
        );
        assert!(out.is_empty());
    }
}
