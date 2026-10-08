//! The embed core that the Rust console app (`src/main.rs`) and the Tauri app (`tauri/embed`)
//! share: it embeds a URnetwork Device in the app with the scoped client JWT that the developer's
//! backend delivered for this installation, and computes the status that every embed example shows
//! (EMBED_CONTRACT.md). The Device carries only the app's own traffic; the Sockets and Messages
//! examples continue from there.
//!
//! - [`status`]: the three status fields and their exact text rules.
//! - [`caps`]: the client's data caps, read with its own client JWT.
//! - [`token`]: obtaining the client JWT from the token server, the developer's sign-in stand-in.
//! - [`state`]: the private installation state directory (`client.jwt`, `instance-id`,
//!   `token-server.json`).
//! - [`config`]: loading the installation state and the client JWT at start.
//! - [`session`]: the embedded Device over the SDK's C ABI, its listeners and the status loop.
//! - [`sdk_json`]: the C ABI's JSON values that the status reads.
//! - [`http`]: the small HTTPS client that the token fetch and the cap reads use.
//! - [`self_test`]: the credential-free self-test, which never loads the native SDK runtime.
//! - [`stand_in`]: a loopback HTTP stand-in for the token server and the API, used by the tests.
//! - [`id`]: canonical ids, parsed and made without the native runtime.
//!
//! Only [`session`] and [`version`] load the native runtime, from `URNETWORK_SDK_LIBRARY` or the
//! runtime that the `urnetwork-sdk` crate embeds.

pub mod caps;
pub mod config;
pub mod http;
pub mod id;
pub mod sdk_json;
pub mod self_test;
pub mod session;
pub mod stand_in;
pub mod state;
pub mod status;
pub mod token;

use std::{error::Error, fmt};

/// Exit code after a requested stop.
pub const EXIT_STOPPED: i32 = 0;
/// Exit code for any failure that a restart may fix.
pub const EXIT_FAILURE: i32 = 1;
/// Exit code for a configuration or credential problem that a restart does not fix (sysexits
/// `EX_CONFIG`).
pub const EXIT_CONFIG: i32 = 78;

/// A configuration or credential problem: restarting does not fix it, so the console exits with
/// [`EXIT_CONFIG`].
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ConfigError(String);

impl ConfigError {
    /// An error with a message that tells the user what to fix.
    pub fn new(message: impl Into<String>) -> Self {
        Self(message.into())
    }
}

impl fmt::Display for ConfigError {
    /// The message only; it never contains a credential.
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter.write_str(&self.0)
    }
}

impl Error for ConfigError {}

/// The SDK version of the native runtime. Loads the runtime, so it also checks that the library
/// loads.
pub fn version() -> std::io::Result<String> {
    urnetwork_sdk::version()
}
