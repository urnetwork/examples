//! The status that every embed example shows, with the exact text rules of EMBED_CONTRACT.md
//! ("Status"): the status, the data used this month and the running total. Everything here is pure,
//! so the self-test checks it without the native SDK, a network or credentials.

use std::{
    fmt,
    time::{Duration, Instant},
};

use crate::caps::{CAPPED_REASON_MONTHLY, CapReading};

/// No client limit hold (C ABI `URNET_CLIENT_LIMIT_STATUS_NONE`).
pub const CLIENT_LIMIT_STATUS_NONE: &str = "";
/// The platform disconnected this client for its network's concurrent client limit
/// (`URNET_CLIENT_LIMIT_STATUS_EXCEEDED`).
pub const CLIENT_LIMIT_STATUS_EXCEEDED: &str = "client_limit_exceeded";

/// How long the console waits before it repeats an unchanged status line.
pub const STATUS_REPEAT_INTERVAL: Duration = Duration::from_secs(60);

/// What the status rules read, in their order.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct StatusInputs {
    /// a GUI's device runs; the console is always started
    pub started: bool,
    /// a GUI after an auth logout, or a 401 or 409 from the token server, until started again
    pub signed_out: bool,
    /// `""` or `"client_limit_exceeded"`
    pub client_limit_status: String,
    /// the end of the client limit hold in unix milliseconds, 0 when there is none
    pub client_limit_retry_time: i64,
    pub cap_reading: CapReading,
    /// `ProviderStateAdded` of the window status
    pub providers_added: i64,
}

impl StatusInputs {
    /// A started console device that has read nothing yet.
    pub fn started() -> Self {
        Self {
            started: true,
            signed_out: false,
            client_limit_status: CLIENT_LIMIT_STATUS_NONE.to_string(),
            client_limit_retry_time: 0,
            cap_reading: CapReading::Checking,
            providers_added: 0,
        }
    }

    /// A GUI before its first start.
    pub fn stopped() -> Self {
        Self {
            started: false,
            ..Self::started()
        }
    }
}

/// One status snapshot: the three fields as text.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct EmbedStatus {
    pub status: String,
    pub data_this_month: String,
    pub data_total: String,
}

impl EmbedStatus {
    /// The fields for the inputs.
    pub fn new(inputs: &StatusInputs) -> Self {
        Self {
            status: status_text(inputs),
            data_this_month: data_field_text(&inputs.cap_reading, DataField::Monthly),
            data_total: data_field_text(&inputs.cap_reading, DataField::Total),
        }
    }
}

impl fmt::Display for EmbedStatus {
    /// The console status line:
    ///
    /// ```text
    /// status: connected | data this month: 1.2 GB of 5.0 GB | data total: no cap
    /// ```
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(
            formatter,
            "status: {} | data this month: {} | data total: {}",
            self.status, self.data_this_month, self.data_total
        )
    }
}

/// The status field: the first rule that applies.
///
/// 1. `signed out`, after an auth logout or a token server refusal, until started again (GUI);
/// 2. `stopped`, before start or after stop (GUI);
/// 3. `client limit`, while the platform holds this client off for its network's client limit,
///    with the retry time;
/// 4. `paused`, while the latest cap reading is capped and the cap that `capped_reason` names is 0;
/// 5. `data cap reached, resets YYYY-MM-DD HH:MM UTC`, while capped by the monthly cap;
/// 6. `data cap reached`, while capped by the running total, or with a period end that does not
///    parse, or an unknown reason;
/// 7. `connected`, while the window has at least one provider added;
/// 8. `connecting` otherwise.
pub fn status_text(inputs: &StatusInputs) -> String {
    if inputs.signed_out {
        return "signed out".to_string();
    }
    if !inputs.started {
        return "stopped".to_string();
    }
    if inputs.client_limit_status == CLIENT_LIMIT_STATUS_EXCEEDED {
        return client_limit_text(inputs.client_limit_retry_time);
    }
    if let Some(data_cap) = inputs.cap_reading.data_cap().filter(|cap| cap.capped) {
        if data_cap.named_cap_limit() == Some(Some(0)) {
            return "paused".to_string();
        }
        let reset_text = if data_cap.capped_reason == CAPPED_REASON_MONTHLY {
            data_cap
                .monthly_period_end
                .as_deref()
                .and_then(reset_time_text)
        } else {
            None
        };
        // the running total, a period end that does not parse, or an unknown reason: no reset time
        return match reset_text {
            Some(reset_text) => format!("data cap reached, {reset_text}"),
            None => "data cap reached".to_string(),
        };
    }
    if 1 <= inputs.providers_added {
        "connected".to_string()
    } else {
        "connecting".to_string()
    }
}

/// Which data field.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum DataField {
    /// data this month: `monthly_used_byte_count` of `monthly_byte_limit`
    Monthly,
    /// data total: `total_used_byte_count` of `total_byte_limit`
    Total,
}

/// A data field: `checking` until the first cap reading, `unavailable` if that reading failed,
/// `no cap` for a null limit (never a used count: a server need not report usage for an uncapped
/// client), otherwise `<used> of <limit>`.
pub fn data_field_text(cap_reading: &CapReading, field: DataField) -> String {
    match cap_reading {
        CapReading::Checking => "checking".to_string(),
        CapReading::Unavailable => "unavailable".to_string(),
        CapReading::Read(data_cap) => {
            let (used, limit) = match field {
                DataField::Monthly => (
                    data_cap.monthly_used_byte_count,
                    data_cap.monthly_byte_limit,
                ),
                DataField::Total => (data_cap.total_used_byte_count, data_cap.total_byte_limit),
            };
            match limit {
                None => "no cap".to_string(),
                Some(limit) => format!(
                    "{} of {}",
                    format_byte_count(used),
                    format_byte_count(limit)
                ),
            }
        }
    }
}

/// `client limit, retry at HH:MM UTC`: the retry time (unix milliseconds) rounded up to the next
/// whole minute in UTC, so the shown time is never before the real retry; plain `client limit` when
/// the retry time is 0.
pub fn client_limit_text(retry_time: i64) -> String {
    if retry_time <= 0 {
        return "client limit".to_string();
    }
    let retry_minute = (retry_time as u64).div_ceil(60 * 1000);
    let hour = (retry_minute / 60) % 24;
    let minute = retry_minute % 60;
    format!("client limit, retry at {hour:02}:{minute:02} UTC")
}

/// Decimal units with one decimal, because data plans and the Embed plan's monthly data budget are
/// sold in them: `0 B`, `999 B`, `1.0 kB`, `1.2 GB`. Values round to the nearest tenth with ties to
/// even, as Go's `%.1f` does: 1250 bytes (1.25 kB) is `1.2 kB` and 1750 bytes is `1.8 kB`. Rust's
/// `{:.1}` rounds the exact binary value that way. A value that rounds to 1000.0 moves to the next
/// unit.
pub fn format_byte_count(byte_count: u64) -> String {
    if byte_count < 1000 {
        return format!("{byte_count} B");
    }
    let units = ["kB", "MB", "GB", "TB", "PB", "EB"];
    let mut value = byte_count as f64 / 1000.0;
    let mut unit_index = 0;
    while unit_index < units.len() - 1 && 1000.0 <= (value * 10.0).round_ties_even() / 10.0 {
        value /= 1000.0;
        unit_index += 1;
    }
    format!("{value:.1} {}", units[unit_index])
}

/// `resets YYYY-MM-DD HH:MM UTC` for a `monthly_period_end` in RFC 3339, converted to UTC with the
/// seconds rounded up to the next whole minute. None when the time does not parse.
pub fn reset_time_text(monthly_period_end: &str) -> Option<String> {
    let (seconds, nanos) = parse_rfc3339(monthly_period_end)?;
    // round up: a period end of 23:59:00.001 resets at 00:00
    let minutes = if seconds.rem_euclid(60) == 0 && nanos == 0 {
        seconds.div_euclid(60)
    } else {
        seconds.div_euclid(60) + 1
    };
    let days = minutes.div_euclid(24 * 60);
    let minute_of_day = minutes.rem_euclid(24 * 60);
    let (year, month, day) = civil_from_days(days);
    Some(format!(
        "resets {year:04}-{month:02}-{day:02} {:02}:{:02} UTC",
        minute_of_day / 60,
        minute_of_day % 60
    ))
}

/// An RFC 3339 date-time (`2026-10-31T19:00:00.5-05:00`, `2026-11-01T00:00:00Z`) as UTC unix seconds
/// and nanoseconds.
pub fn parse_rfc3339(text: &str) -> Option<(i64, u32)> {
    let bytes = text.as_bytes();
    let digits = |start: usize, count: usize| -> Option<i64> {
        let part = bytes.get(start..start + count)?;
        if !part.iter().all(u8::is_ascii_digit) {
            return None;
        }
        std::str::from_utf8(part).ok()?.parse().ok()
    };
    let year = digits(0, 4)?;
    if bytes.get(4) != Some(&b'-') || bytes.get(7) != Some(&b'-') {
        return None;
    }
    let month = digits(5, 2)?;
    let day = digits(8, 2)?;
    if !matches!(bytes.get(10), Some(b'T' | b't' | b' ')) {
        return None;
    }
    let hour = digits(11, 2)?;
    if bytes.get(13) != Some(&b':') || bytes.get(16) != Some(&b':') {
        return None;
    }
    let minute = digits(14, 2)?;
    let second = digits(17, 2)?;
    let mut index = 19;
    let mut nanos: u32 = 0;
    if bytes.get(index) == Some(&b'.') {
        index += 1;
        let start = index;
        while bytes.get(index).is_some_and(u8::is_ascii_digit) {
            index += 1;
        }
        let fraction = &text[start..index];
        if fraction.is_empty() {
            return None;
        }
        // nanoseconds: the first nine digits; any further digit rounds the fraction up by one
        let mut padded: String = fraction.chars().take(9).collect();
        while padded.len() < 9 {
            padded.push('0');
        }
        nanos = padded.parse().ok()?;
        if fraction.len() > 9 && fraction[9..].bytes().any(|b| b != b'0') {
            nanos = nanos.saturating_add(1);
        }
    }
    let offset_seconds = match bytes.get(index) {
        Some(b'Z' | b'z') if index + 1 == bytes.len() => 0,
        Some(sign @ (b'+' | b'-')) if index + 6 == bytes.len() && bytes[index + 3] == b':' => {
            let offset_hour = digits(index + 1, 2)?;
            let offset_minute = digits(index + 4, 2)?;
            if 23 < offset_hour || 59 < offset_minute {
                return None;
            }
            let offset = offset_hour * 3600 + offset_minute * 60;
            if *sign == b'+' { offset } else { -offset }
        }
        _ => return None,
    };
    if !(1..=12).contains(&month)
        || day < 1
        || days_in_month(year, month) < day
        || 23 < hour
        || 59 < minute
        || 60 < second
    {
        return None;
    }
    let days = days_from_civil(year, month, day);
    let local_seconds = days * 86400 + hour * 3600 + minute * 60 + second;
    let mut seconds = local_seconds - offset_seconds;
    // a fraction of 1e9 after rounding carries into the seconds
    if 1_000_000_000 <= nanos {
        nanos -= 1_000_000_000;
        seconds += 1;
    }
    Some((seconds, nanos))
}

/// Days in a month of the proleptic Gregorian calendar.
fn days_in_month(year: i64, month: i64) -> i64 {
    match month {
        1 | 3 | 5 | 7 | 8 | 10 | 12 => 31,
        4 | 6 | 9 | 11 => 30,
        _ if (year % 4 == 0 && year % 100 != 0) || year % 400 == 0 => 29,
        _ => 28,
    }
}

/// Days since 1970-01-01 of a civil date (Howard Hinnant's algorithm).
pub fn days_from_civil(year: i64, month: i64, day: i64) -> i64 {
    let year = if month <= 2 { year - 1 } else { year };
    let era = year.div_euclid(400);
    let year_of_era = year - era * 400;
    let month_index = (month + 9) % 12;
    let day_of_year = (153 * month_index + 2) / 5 + day - 1;
    let day_of_era = year_of_era * 365 + year_of_era / 4 - year_of_era / 100 + day_of_year;
    era * 146097 + day_of_era - 719468
}

/// The civil date of days since 1970-01-01 (Howard Hinnant's algorithm).
pub fn civil_from_days(days: i64) -> (i64, i64, i64) {
    let days = days + 719468;
    let era = days.div_euclid(146097);
    let day_of_era = days - era * 146097;
    let year_of_era =
        (day_of_era - day_of_era / 1460 + day_of_era / 36524 - day_of_era / 146096) / 365;
    let day_of_year = day_of_era - (365 * year_of_era + year_of_era / 4 - year_of_era / 100);
    let month_index = (5 * day_of_year + 2) / 153;
    let day = day_of_year - (153 * month_index + 2) / 5 + 1;
    let month = if month_index < 10 {
        month_index + 3
    } else {
        month_index - 9
    };
    let year = year_of_era + era * 400 + if month <= 2 { 1 } else { 0 };
    (year, month, day)
}

/// Decides when the console prints a status line: at once when any field's text changes, otherwise
/// once every [`STATUS_REPEAT_INTERVAL`].
#[derive(Default)]
pub struct StatusLines {
    last_line: Option<String>,
    last_print_time: Option<Instant>,
}

impl StatusLines {
    /// The line to print for the status read at `now`, or none.
    pub fn next_line(&mut self, status: &EmbedStatus, now: Instant) -> Option<String> {
        let line = status.to_string();
        let changed = self.last_line.as_deref() != Some(line.as_str());
        let repeat_due = self.last_print_time.is_none_or(|last_print_time| {
            STATUS_REPEAT_INTERVAL <= now.saturating_duration_since(last_print_time)
        });
        if !changed && !repeat_due {
            return None;
        }
        self.last_line = Some(line.clone());
        self.last_print_time = Some(now);
        Some(line)
    }
}
