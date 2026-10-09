//! The client's data caps (EMBED_CONTRACT.md, "The cap object"): an optional monthly cap that
//! resets at 00:00 UTC on the first of the month, and an optional running-total cap that the backend
//! raises, clears or resets. The backend sets them with its root credential; the app only reads its
//! own, with its client JWT (`GET /network/client-data-cap`), at start and every 5 minutes.

use std::fmt;

use serde_json::Value;

use crate::{
    http::{self, display_message},
    id::parse_id,
};

/// `capped_reason` for the monthly cap.
pub const CAPPED_REASON_MONTHLY: &str = "monthly";
/// `capped_reason` for the running-total cap.
pub const CAPPED_REASON_TOTAL: &str = "total";

/// The path of the cap read, after the API origin.
pub const CLIENT_DATA_CAP_PATH: &str = "/network/client-data-cap";

/// The server refuses the cap read with this message while the team has not enabled Embed for the
/// network (EMBED_CONTRACT.md, "Embed enablement").
pub const EMBED_NOT_ENABLED_MESSAGE: &str = "Embed isn't enabled for this network.";

/// Why a cap read gave no cap object.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum CapReadError {
    /// The Embed-not-enabled refusal, which clears the last reading.
    EmbedNotEnabled,
    /// Any other failure, which keeps the last reading. Never includes the token.
    Failed(String),
}

impl fmt::Display for CapReadError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            CapReadError::EmbedNotEnabled => formatter.write_str(EMBED_NOT_ENABLED_MESSAGE),
            CapReadError::Failed(message) => formatter.write_str(message),
        }
    }
}

/// Whether an answer is the Embed-not-enabled refusal,
/// `{"error": {"message": "Embed isn't enabled for this network."}}`.
pub fn is_embed_not_enabled(value: &Value) -> bool {
    value.pointer("/error/message").and_then(Value::as_str) == Some(EMBED_NOT_ENABLED_MESSAGE)
}

/// One client's caps and usage. A limit of `None` is no cap.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct DataCap {
    pub client_id: String,
    pub monthly_byte_limit: Option<u64>,
    pub monthly_used_byte_count: u64,
    pub monthly_period_start: Option<String>,
    /// when monthly usage resets, RFC 3339
    pub monthly_period_end: Option<String>,
    pub total_byte_limit: Option<u64>,
    pub total_used_byte_count: u64,
    pub total_period_start: Option<String>,
    /// a cap is reached: the client gets no new transfer contracts
    pub capped: bool,
    /// `monthly`, `total`, or `""`; another value reads as capped without a reset time
    pub capped_reason: String,
}

impl DataCap {
    /// The limit of the cap that `capped_reason` names; none for an unknown reason.
    pub fn named_cap_limit(&self) -> Option<Option<u64>> {
        match self.capped_reason.as_str() {
            CAPPED_REASON_MONTHLY => Some(self.monthly_byte_limit),
            CAPPED_REASON_TOTAL => Some(self.total_byte_limit),
            _ => None,
        }
    }
}

/// A non-negative byte count, or none for `null` or a missing field.
fn optional_byte_count(
    object: &serde_json::Map<String, Value>,
    name: &str,
) -> Result<Option<u64>, ()> {
    match object.get(name) {
        None | Some(Value::Null) => Ok(None),
        Some(value) => value.as_u64().map(Some).ok_or(()),
    }
}

/// An optional string field.
fn optional_string(object: &serde_json::Map<String, Value>, name: &str) -> Option<String> {
    object.get(name).and_then(Value::as_str).map(str::to_string)
}

/// The cap object of an answer; none when it is not an object, carries an `error`, or has a limit
/// or count that is not a non-negative integer.
pub fn parse_data_cap(value: &Value) -> Option<DataCap> {
    let object = value.as_object()?;
    if object.get("error").is_some_and(|error| !error.is_null()) {
        return None;
    }
    let client_id = optional_string(object, "client_id")
        .map(|client_id| parse_id(&client_id).unwrap_or(client_id))
        .unwrap_or_default();
    Some(DataCap {
        client_id,
        monthly_byte_limit: optional_byte_count(object, "monthly_byte_limit").ok()?,
        monthly_used_byte_count: optional_byte_count(object, "monthly_used_byte_count")
            .ok()?
            .unwrap_or(0),
        monthly_period_start: optional_string(object, "monthly_period_start"),
        monthly_period_end: optional_string(object, "monthly_period_end"),
        total_byte_limit: optional_byte_count(object, "total_byte_limit").ok()?,
        total_used_byte_count: optional_byte_count(object, "total_used_byte_count")
            .ok()?
            .unwrap_or(0),
        total_period_start: optional_string(object, "total_period_start"),
        capped: object
            .get("capped")
            .and_then(Value::as_bool)
            .unwrap_or(false),
        capped_reason: optional_string(object, "capped_reason").unwrap_or_default(),
    })
}

/// The cap object of a JSON text.
pub fn parse_data_cap_json(json: &[u8]) -> Option<DataCap> {
    serde_json::from_slice::<Value>(json)
        .ok()
        .and_then(|value| parse_data_cap(&value))
}

/// The latest cap reading, which the data fields and the status rules use.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub enum CapReading {
    /// Before the first reading.
    #[default]
    Checking,
    /// The first reading failed, or the Embed-not-enabled refusal cleared the reading; a later
    /// failure keeps the last value instead.
    Unavailable,
    /// The last successful reading.
    Read(DataCap),
}

impl CapReading {
    /// Applies a reading: a success replaces the value; the Embed-not-enabled refusal clears it to
    /// `unavailable`; another failure turns `checking` into `unavailable` and keeps any earlier
    /// value.
    pub fn apply(&mut self, reading: Result<DataCap, CapReadError>) {
        match reading {
            Ok(data_cap) => *self = CapReading::Read(data_cap),
            Err(CapReadError::EmbedNotEnabled) => *self = CapReading::Unavailable,
            Err(CapReadError::Failed(_)) => {
                if *self == CapReading::Checking {
                    *self = CapReading::Unavailable;
                }
            }
        }
    }

    /// The cap object, when a reading succeeded.
    pub fn data_cap(&self) -> Option<&DataCap> {
        match self {
            CapReading::Read(data_cap) => Some(data_cap),
            _ => None,
        }
    }
}

/// Reads this client's caps with its own client JWT, at `api_origin`. A server without the cap
/// routes answers 404, which is a failure like any other; the Embed-not-enabled refusal is its own
/// error. Never includes the token in the error.
pub fn read_own_data_cap(api_origin: &str, client_jwt: &str) -> Result<DataCap, CapReadError> {
    let answer = http::get(&format!("{api_origin}{CLIENT_DATA_CAP_PATH}"), client_jwt)
        .map_err(|error| CapReadError::Failed(format!("could not read the data caps: {error}")))?;
    if answer.status != 200 {
        let message = serde_json::from_slice::<Value>(&answer.body)
            .ok()
            .and_then(|value| {
                value
                    .pointer("/error/message")
                    .and_then(Value::as_str)
                    .map(display_message)
            })
            .map(|message| format!(": {message}"))
            .unwrap_or_default();
        return Err(CapReadError::Failed(format!(
            "the data cap read answered HTTP {}{message}",
            answer.status
        )));
    }
    let value = serde_json::from_slice::<Value>(&answer.body).ok();
    if value.as_ref().is_some_and(is_embed_not_enabled) {
        return Err(CapReadError::EmbedNotEnabled);
    }
    value.as_ref().and_then(parse_data_cap).ok_or_else(|| {
        CapReadError::Failed("the data cap read answered an invalid cap object".to_string())
    })
}
