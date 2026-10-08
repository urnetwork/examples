//! The small HTTPS client that the token fetch and the cap reads use, and the origin rules for the
//! token server and the API (EMBED_CONTRACT.md): an HTTPS origin, or explicit loopback HTTP
//! (`localhost`, `127.0.0.1`, `[::1]`) for local testing. Requests follow no redirects, time out,
//! and read a bounded answer.

use std::{io, time::Duration};

use crate::ConfigError;

/// The API origin when `URNETWORK_API_URL` is not set.
pub const DEFAULT_API_ORIGIN: &str = "https://api.bringyour.com";

/// How long one request may take, connect to last byte.
pub const REQUEST_TIMEOUT: Duration = Duration::from_secs(15);

/// The largest answer body the app reads.
pub const RESPONSE_BYTE_LIMIT: u64 = 64 * 1024;

/// One answer: the HTTP status and the body.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct HttpAnswer {
    pub status: u16,
    pub body: Vec<u8>,
}

/// The origin `scheme://host[:port]` of an HTTPS origin, or of explicit loopback HTTP. A trailing
/// `/` is allowed; credentials, a path, a query or a fragment are not. `what` names the setting in
/// the error.
pub fn parse_origin(text: &str, what: &str) -> Result<String, ConfigError> {
    let invalid = || {
        ConfigError::new(format!(
            "{what} must be an HTTPS origin such as https://example.com, or loopback HTTP such as http://127.0.0.1:8790"
        ))
    };
    let (scheme, rest) = text.split_once("://").ok_or_else(invalid)?;
    let authority = rest.strip_suffix('/').unwrap_or(rest);
    if authority.is_empty()
        || authority.chars().any(|c| {
            matches!(c, '/' | '?' | '#' | '@' | '\\') || c.is_whitespace() || c.is_control()
        })
    {
        return Err(invalid());
    }
    let (host, port) = split_host_port(authority).ok_or_else(invalid)?;
    if let Some(port) = port {
        if port.is_empty() || !port.bytes().all(|b| b.is_ascii_digit()) {
            return Err(invalid());
        }
        match port.parse::<u32>() {
            Ok(1..=65535) => {}
            _ => return Err(invalid()),
        }
    }
    let loopback = matches!(host, "localhost" | "127.0.0.1" | "[::1]");
    match scheme {
        "https" => {}
        "http" if loopback => {}
        _ => return Err(invalid()),
    }
    Ok(format!("{scheme}://{authority}"))
}

/// The host and optional port of an authority; a bracketed IPv6 host keeps its brackets.
fn split_host_port(authority: &str) -> Option<(&str, Option<&str>)> {
    if authority.starts_with('[') {
        let end = authority.find(']')?;
        let host = &authority[..=end];
        let rest = &authority[end + 1..];
        if rest.is_empty() {
            return Some((host, None));
        }
        return rest.strip_prefix(':').map(|port| (host, Some(port)));
    }
    match authority.split_once(':') {
        Some((host, port)) if !host.is_empty() && !port.contains(':') => Some((host, Some(port))),
        Some(_) => None,
        None => Some((authority, None)),
    }
}

/// The agent every request uses: answers of every status are returned rather than errors, no
/// redirects, a global timeout.
fn agent() -> ureq::Agent {
    ureq::Agent::config_builder()
        .http_status_as_error(false)
        .max_redirects(0)
        .timeout_global(Some(REQUEST_TIMEOUT))
        .build()
        .into()
}

/// Reads the body of an answer, at most [`RESPONSE_BYTE_LIMIT`] bytes.
fn read_answer(mut response: ureq::http::Response<ureq::Body>) -> io::Result<HttpAnswer> {
    let status = response.status().as_u16();
    let body = response
        .body_mut()
        .with_config()
        .limit(RESPONSE_BYTE_LIMIT)
        .read_to_vec()
        .map_err(|error| io::Error::other(error.to_string()))?;
    Ok(HttpAnswer { status, body })
}

/// `POST url` with a bearer token and a JSON body. An error means the request did not complete: the
/// host was unreachable, it timed out, or the answer was too large.
pub fn post_json(url: &str, bearer: &str, body: &[u8]) -> io::Result<HttpAnswer> {
    let response = agent()
        .post(url)
        .header("Authorization", format!("Bearer {bearer}"))
        .header("Content-Type", "application/json")
        .header("Accept", "application/json")
        .send(body)
        .map_err(|error| io::Error::other(error.to_string()))?;
    read_answer(response)
}

/// `GET url` with a bearer token. An error means the request did not complete.
pub fn get(url: &str, bearer: &str) -> io::Result<HttpAnswer> {
    let response = agent()
        .get(url)
        .header("Authorization", format!("Bearer {bearer}"))
        .header("Accept", "application/json")
        .call()
        .map_err(|error| io::Error::other(error.to_string()))?;
    read_answer(response)
}

/// A server-provided message made safe to show: one line, printable, at most 300 characters.
pub fn display_message(message: &str) -> String {
    let line: String = message
        .chars()
        .map(|c| if c.is_control() { ' ' } else { c })
        .collect();
    let line = line.trim();
    match line.char_indices().nth(300) {
        Some((index, _)) => format!("{}…", &line[..index]),
        None => line.to_string(),
    }
}
