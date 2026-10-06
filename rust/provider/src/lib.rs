//! The provider core that the Rust console app (`src/main.rs`) and the Tauri app (`tauri/provider`)
//! share: it runs a URnetwork provider as a provider client of the developer's network and computes
//! the status that every provider example shows (PROVIDER_CONTRACT.md).
//!
//! - [`status`]: the four status fields and their exact text rules.
//! - [`state`]: the private installation state directory (`client.jwt`, `instance-id`,
//!   `identity.json`).
//! - [`session`]: the provider device over the SDK's C ABI, its listeners and the status loop.
//! - [`sdk_json`]: the C ABI's JSON values that the status reads.
//! - [`self_test`]: the credential-free self-test, which never loads the native SDK runtime.
//! - [`id`]: canonical ids, parsed and made without the native runtime.
//!
//! Only [`session`] and [`version`] load the native runtime, from `URNETWORK_SDK_LIBRARY` or the
//! runtime that the `urnetwork-sdk` crate embeds.

pub mod id;
pub mod sdk_json;
pub mod self_test;
pub mod session;
pub mod state;
pub mod status;

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
