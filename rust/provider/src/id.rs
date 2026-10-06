//! URnetwork ids in their canonical text form, a lowercase UUID such as
//! `11111111-1111-1111-1111-111111111111`. Parsing and creation are pure Rust, so the state files
//! and the self-test work without the native SDK runtime.

use std::time::{SystemTime, UNIX_EPOCH};

/// The all-zero id, which the SDK uses for a missing id in a transfer path.
pub const ZERO_ID: &str = "00000000-0000-0000-0000-000000000000";

/// The canonical form of a UUID with dashes (36 characters) or without (32 hex digits), in either
/// case.
pub fn parse_id(text: &str) -> Option<String> {
    let hex: String = match text.len() {
        36 => {
            let dashes_in_place = [8, 13, 18, 23]
                .iter()
                .all(|&index| text.as_bytes()[index] == b'-');
            if !dashes_in_place {
                return None;
            }
            text.chars().filter(|&c| c != '-').collect()
        }
        32 => text.to_string(),
        _ => return None,
    };
    if hex.len() != 32 || !hex.chars().all(|c| c.is_ascii_hexdigit()) {
        return None;
    }
    let mut bytes = [0u8; 16];
    for (index, byte) in bytes.iter_mut().enumerate() {
        *byte = u8::from_str_radix(&hex[2 * index..2 * index + 2], 16).ok()?;
    }
    Some(format_id(&bytes))
}

/// A new id in the shape the SDK makes: a 48-bit millisecond timestamp followed by 80 random bits.
pub fn new_id() -> std::io::Result<String> {
    let millis = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|duration| duration.as_millis() as u64)
        .unwrap_or(0);
    let mut bytes = [0u8; 16];
    bytes[0..6].copy_from_slice(&millis.to_be_bytes()[2..8]);
    getrandom::fill(&mut bytes[6..16]).map_err(|error| std::io::Error::other(error.to_string()))?;
    Ok(format_id(&bytes))
}

/// The 8-4-4-4-12 lowercase text of 16 id bytes.
fn format_id(bytes: &[u8; 16]) -> String {
    let hex: String = bytes.iter().map(|byte| format!("{byte:02x}")).collect();
    format!(
        "{}-{}-{}-{}-{}",
        &hex[0..8],
        &hex[8..12],
        &hex[12..16],
        &hex[16..20],
        &hex[20..32]
    )
}
