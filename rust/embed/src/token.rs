//! Obtaining the client JWT from the token server (EMBED_CONTRACT.md, "Obtaining the client JWT").
//! The token server is the shape of a real service's sign-in endpoint: the app posts its
//! `instance-id` to `POST /urnetwork/client-token` with its demo session as the bearer token, and
//! the server provisions or reissues the installation's client and answers its scoped client JWT.
//! [`fetch_client_jwt`] is the one function to replace with your own sign-in.

use std::fmt;

use serde_json::{Value, json};

use crate::{
    ConfigError, EXIT_CONFIG, EXIT_FAILURE,
    caps::{DataCap, parse_data_cap},
    http::{self, display_message, parse_origin},
    id::parse_id,
    state::parse_client_jwt_client_id,
};

/// The token route, after the token server origin.
pub const CLIENT_TOKEN_PATH: &str = "/urnetwork/client-token";

/// Where the app obtains its client JWT: the token server's origin and the demo session token,
/// which stands in for the developer's real sign-in. The session is a bearer secret.
#[derive(Clone, PartialEq, Eq)]
pub struct TokenServer {
    origin: String,
    session: String,
}

impl TokenServer {
    /// A token server at an HTTPS origin or explicit loopback HTTP, with a non-empty session.
    pub fn new(url: &str, session: &str) -> Result<Self, ConfigError> {
        let origin = parse_origin(url.trim(), "the token server URL")?;
        let session = session.trim();
        if session.is_empty() || session.chars().any(|c| c.is_whitespace() || c.is_control()) {
            return Err(ConfigError::new(
                "the demo session must be one token without spaces",
            ));
        }
        Ok(Self {
            origin,
            session: session.to_string(),
        })
    }

    /// The token server's origin.
    pub fn origin(&self) -> &str {
        &self.origin
    }
}

impl fmt::Debug for TokenServer {
    /// The origin only: the session is a credential.
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter
            .debug_struct("TokenServer")
            .field("origin", &self.origin)
            .finish_non_exhaustive()
    }
}

/// A successful token answer.
#[derive(Clone, PartialEq, Eq)]
pub struct TokenAnswer {
    pub client_id: String,
    /// the installation's scoped client JWT: never print or log it
    pub by_client_jwt: String,
    /// the client's cap object, read by the token server; none when that read failed
    pub data_cap: Option<DataCap>,
}

impl fmt::Debug for TokenAnswer {
    /// Everything but the credential.
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter
            .debug_struct("TokenAnswer")
            .field("client_id", &self.client_id)
            .field("data_cap", &self.data_cap)
            .finish_non_exhaustive()
    }
}

/// Why the token fetch did not obtain a client JWT.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum TokenError {
    /// 401 `unauthorized`, 409 `installation_limit` or `client_limit`: a restart does not fix it.
    /// The console exits with 78; a GUI shows `signed out`.
    Refused { status: u16, message: String },
    /// Unreachable, a 5xx, or an invalid answer. The console exits with 1; a GUI shows `stopped`.
    Failed(String),
}

impl TokenError {
    /// The console exit code for this answer.
    pub fn exit_code(&self) -> i32 {
        match self {
            TokenError::Refused { .. } => EXIT_CONFIG,
            TokenError::Failed(_) => EXIT_FAILURE,
        }
    }

    /// Whether a GUI shows `signed out` (otherwise `stopped`).
    pub fn signs_out(&self) -> bool {
        matches!(self, TokenError::Refused { .. })
    }
}

impl fmt::Display for TokenError {
    /// A message for the user; it never contains a credential.
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            TokenError::Refused { status, message } => {
                write!(
                    formatter,
                    "the token server refused (HTTP {status}): {message}"
                )
            }
            TokenError::Failed(message) => write!(formatter, "{message}"),
        }
    }
}

/// The `error.message` of an answer body, made safe to show.
fn answer_error_message(body: &[u8]) -> Option<String> {
    let value: Value = serde_json::from_slice(body).ok()?;
    let error = value.get("error")?;
    let message = error.get("message").and_then(Value::as_str)?;
    let code = error.get("code").and_then(Value::as_str);
    Some(match code {
        Some(code) if !code.is_empty() => {
            format!("{} ({})", display_message(message), display_message(code))
        }
        _ => display_message(message),
    })
}

/// Posts this installation's `instance-id` to the token server and checks the answer: a 200 must
/// carry a `client_id` and a `by_client_jwt` whose `client_id` claim equals it. Fetches on every
/// start, so a start reissues the client.
pub fn fetch_client_jwt(
    token_server: &TokenServer,
    instance_id: &str,
) -> Result<TokenAnswer, TokenError> {
    let body = json!({ "installation_id": instance_id }).to_string();
    let answer = http::post_json(
        &format!("{}{CLIENT_TOKEN_PATH}", token_server.origin),
        &token_server.session,
        body.as_bytes(),
    )
    .map_err(|error| {
        TokenError::Failed(format!(
            "could not reach the token server at {}: {error}",
            token_server.origin
        ))
    })?;
    match answer.status {
        200 => parse_token_answer(&answer.body),
        401 | 409 => Err(TokenError::Refused {
            status: answer.status,
            message: answer_error_message(&answer.body).unwrap_or_else(|| "no message".to_string()),
        }),
        status => Err(TokenError::Failed(
            match answer_error_message(&answer.body) {
                Some(message) => format!("the token server answered HTTP {status}: {message}"),
                None => format!("the token server answered HTTP {status}"),
            },
        )),
    }
}

/// A 200 answer's client, scoped client JWT and cap object.
pub fn parse_token_answer(body: &[u8]) -> Result<TokenAnswer, TokenError> {
    let invalid = |reason: &str| TokenError::Failed(format!("the token server's answer {reason}"));
    let value: Value = serde_json::from_slice(body).map_err(|_| invalid("is not a JSON object"))?;
    let object = value
        .as_object()
        .ok_or_else(|| invalid("is not a JSON object"))?;
    let client_id = object
        .get("client_id")
        .and_then(Value::as_str)
        .and_then(parse_id)
        .ok_or_else(|| invalid("has no valid client_id"))?;
    let by_client_jwt = object
        .get("by_client_jwt")
        .and_then(Value::as_str)
        .map(str::trim)
        .filter(|client_jwt| !client_jwt.is_empty())
        .ok_or_else(|| invalid("has no by_client_jwt"))?
        .to_string();
    let claim = parse_client_jwt_client_id(&by_client_jwt)
        .map_err(|error| invalid(&format!("carries an unusable client JWT: {error}")))?;
    if claim != client_id {
        return Err(invalid(
            "carries a client JWT for another client than its client_id",
        ));
    }
    let data_cap = object.get("data_cap").and_then(parse_data_cap);
    Ok(TokenAnswer {
        client_id,
        by_client_jwt,
        data_cap,
    })
}
