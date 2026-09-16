// SERVER ONLY. The authenticated backend supplies user:<service-user-id> internally.
// Never pass an untrusted request field or UR client ID. Set URNETWORK_ROOT_JWT,
// URNETWORK_CLIENT_MAP (absolute file path, existing service-owned parent), and
// optional URNETWORK_API_URL. Remove crash-left .lock only after confirming it is stale.
use base64::{Engine, engine::general_purpose::URL_SAFE_NO_PAD};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
#[cfg(unix)]
use std::os::unix::fs::{OpenOptionsExt, PermissionsExt};
use std::{
    collections::{BTreeMap, BTreeSet},
    env,
    error::Error,
    fs::{self, OpenOptions},
    io::{Read, Write},
    path::{Path, PathBuf},
    time::{Duration, SystemTime, UNIX_EPOCH},
};
type Result<T> = std::result::Result<T, Box<dyn Error>>;
const LIMIT: u64 = 1 << 20;
#[derive(Serialize, Deserialize)]
struct ClientMap {
    version: u32,
    clients: BTreeMap<String, String>,
}
#[derive(Serialize, Debug)]
struct ClientResult {
    client_id: String,
    by_client_jwt: String,
}
fn user_valid(user: &str) -> bool {
    let Some(s) = user.strip_prefix("user:") else {
        return false;
    };
    !s.is_empty()
        && user.len() <= 128
        && s.as_bytes()[0].is_ascii_alphanumeric()
        && s.bytes()
            .all(|b| b.is_ascii_alphanumeric() || b"_.:@-".contains(&b))
}
fn service_user(args: &[String]) -> Result<String> {
    if args.len() != 1 || !user_valid(&args[0]) {
        return Err("expected one user:<service-user-id>".into());
    }
    Ok(args[0].clone())
}
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
fn endpoint(base: &str) -> Result<reqwest::Url> {
    let mut u = reqwest::Url::parse(base)?;
    let local = matches!(
        u.host_str(),
        Some("localhost" | "127.0.0.1" | "[::1]" | "::1")
    );
    if u.host().is_none()
        || !u.username().is_empty()
        || u.password().is_some()
        || u.path() != "/"
        || u.query().is_some()
        || u.fragment().is_some()
        || (u.scheme() != "https" && !(u.scheme() == "http" && local))
    {
        return Err("API must be an HTTPS origin, or explicit loopback HTTP mock".into());
    }
    u.set_path("/network/auth-client");
    Ok(u)
}
fn request_for(user: &str, client: Option<&str>) -> Result<Value> {
    if !user_valid(user) {
        return Err("invalid service key".into());
    }
    let mut body = json!({"description":format!("service {user}"),"device_spec":"urnetwork-examples/rust-server"});
    if let Some(id) = client {
        if !id_valid(id) {
            return Err("invalid mapped client".into());
        };
        body["client_id"] = json!(id)
    }
    Ok(body)
}
fn parse_response(raw: &str, expected: Option<&str>) -> Result<ClientResult> {
    let obj: Value = serde_json::from_str(raw)?;
    if !obj.is_object() || !obj["error"].is_null() {
        return Err("API failure".into());
    }
    let id = obj["client_id"].as_str().ok_or("missing client ID")?;
    let jwt = obj["by_client_jwt"].as_str().ok_or("missing scoped JWT")?;
    let parts: Vec<_> = jwt.split('.').collect();
    if !id_valid(id) || parts.len() != 3 || parts.iter().any(|p| p.is_empty()) {
        return Err("invalid scoped result".into());
    }
    let claims: Value = serde_json::from_slice(&URL_SAFE_NO_PAD.decode(parts[1])?)?;
    if claims["client_id"].as_str() != Some(id) || expected.is_some_and(|old| old != id) {
        return Err("scoped identity mismatch".into());
    }
    // Claim comparison is consistency checking, not local signature verification.
    Ok(ClientResult {
        client_id: id.into(),
        by_client_jwt: jwt.into(),
    })
}
fn load_map(file: &Path) -> Result<ClientMap> {
    let stat = match fs::symlink_metadata(file) {
        Ok(s) => s,
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => {
            return Ok(ClientMap {
                version: 1,
                clients: BTreeMap::new(),
            });
        }
        Err(e) => return Err(e.into()),
    };
    if !stat.is_file() || stat.len() > LIMIT {
        return Err("invalid mapping file".into());
    }
    #[cfg(unix)]
    if stat.permissions().mode() & 0o077 != 0 {
        return Err("mapping must be private (0600)".into());
    }
    let map: ClientMap = serde_json::from_slice(&fs::read(file)?)?;
    if map.version != 1 {
        return Err("invalid map version".into());
    }
    let mut seen = BTreeSet::new();
    for (u, id) in &map.clients {
        if !user_valid(u) || !id_valid(id) || !seen.insert(id) {
            return Err("invalid mapped client".into());
        }
    }
    Ok(map)
}
fn unique_suffix() -> u128 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap()
        .as_nanos()
}
fn save_map(file: &Path, map: &ClientMap) -> Result<()> {
    let temporary = file.with_extension(format!("{}.tmp", unique_suffix()));
    let mut options = OpenOptions::new();
    options.write(true).create_new(true);
    #[cfg(unix)]
    options.mode(0o600);
    let mut output = options.open(&temporary)?;
    let result = (|| {
        output.write_all(&serde_json::to_vec(map)?)?;
        output.sync_all()?;
        drop(output);
        fs::rename(&temporary, file)?;
        Ok(())
    })();
    let _ = fs::remove_file(&temporary);
    result
}
struct Lock(PathBuf);
impl Drop for Lock {
    fn drop(&mut self) {
        let _ = fs::remove_dir(&self.0);
    }
}
fn allocate(
    user: &str,
    file: &Path,
    mut call: impl FnMut(&Value) -> Result<String>,
) -> Result<ClientResult> {
    if !file.is_absolute() {
        return Err("mapping needs absolute path".into());
    }
    let lock = PathBuf::from(format!("{}.lock", file.display()));
    fs::create_dir(&lock)?;
    let _guard = Lock(lock.clone());
    #[cfg(unix)]
    fs::set_permissions(&lock, fs::Permissions::from_mode(0o700))?;
    let mut map = load_map(file)?;
    let old = map.clients.get(user).map(String::as_str);
    let response = parse_response(&call(&request_for(user, old)?)?, old)?;
    if old.is_none() {
        if map.clients.values().any(|id| id == &response.client_id) {
            return Err("client assigned to another user".into());
        };
        map.clients.insert(user.into(), response.client_id.clone());
        save_map(file, &map)?
    }
    Ok(response)
}
fn post(url: &reqwest::Url, root: &str, body: &Value) -> Result<String> {
    if root.is_empty() || root.chars().any(char::is_whitespace) {
        return Err("set backend root JWT".into());
    }
    let client = reqwest::blocking::Client::builder()
        .timeout(Duration::from_secs(15))
        .redirect(reqwest::redirect::Policy::none())
        .build()?;
    let response = client
        .post(url.clone())
        .bearer_auth(root)
        .json(body)
        .send()?;
    if !response.status().is_success() {
        return Err("provisioning HTTP failure".into());
    }
    let mut bytes = Vec::new();
    response.take(LIMIT + 1).read_to_end(&mut bytes)?;
    if bytes.len() as u64 > LIMIT {
        return Err("response too large".into());
    };
    Ok(String::from_utf8(bytes)?)
}
fn self_test() -> Result<()> {
    let id = "11111111-1111-1111-1111-111111111111";
    let jwt = format!(
        "e30.{}.test",
        URL_SAFE_NO_PAD.encode(serde_json::to_vec(&json!({"client_id":id}))?)
    );
    let raw = json!({"client_id":id,"by_client_jwt":jwt}).to_string();
    let dir = env::temp_dir().join(format!(
        "ur-allocator-{}-{}",
        std::process::id(),
        unique_suffix()
    ));
    fs::create_dir(&dir)?;
    let file = dir.join("clients.json");
    let mut calls = vec![];
    let test: Result<()> = (|| {
        let mut mock = |body: &Value| {
            calls.push(body.clone());
            Ok(raw.clone())
        };
        assert_eq!(allocate("user:alice", &file, &mut mock)?.client_id, id);
        assert_eq!(allocate("user:alice", &file, &mut mock)?.by_client_jwt, jwt);
        assert!(calls[0].get("client_id").is_none());
        assert!(calls.iter().all(|c| c.get("source_client_id").is_none()));
        assert_eq!(calls[1]["client_id"], id);
        assert_eq!(load_map(&file)?.clients["user:alice"], id);
        assert_eq!(
            endpoint("http://127.0.0.1:1234")?.path(),
            "/network/auth-client"
        );
        for args in [
            vec![id.into()],
            vec!["user:a".into(), "--client-id".into(), id.into()],
            vec!["user:../a".into()],
        ] {
            assert!(service_user(&args).is_err())
        }
        assert!(endpoint("http://example.com").is_err());
        assert!(endpoint("https://example.com/path").is_err());
        assert!(parse_response("{\"error\":{}}", None).is_err());
        assert!(parse_response(&raw, Some("22222222-2222-2222-2222-222222222222")).is_err());
        Ok(())
    })();
    fs::remove_dir_all(dir)?;
    test?;
    println!("allocator self-test passed");
    Ok(())
}
fn run() -> Result<()> {
    let args: Vec<_> = env::args().skip(1).collect();
    if args == ["--self-test"] {
        return self_test();
    }
    let user = service_user(&args)?;
    let url = endpoint(
        &env::var("URNETWORK_API_URL").unwrap_or_else(|_| "https://api.bringyour.com".into()),
    )?;
    let root = env::var("URNETWORK_ROOT_JWT")?;
    let file = PathBuf::from(env::var("URNETWORK_CLIENT_MAP")?);
    let result = allocate(&user, &file, |body| post(&url, &root, body))?;
    println!("{}", serde_json::to_string(&result)?);
    Ok(())
}
fn main() {
    if run().is_err() {
        eprintln!(
            "allocator failed: check service key, private mapping and backend API configuration"
        );
        std::process::exit(1)
    }
}
