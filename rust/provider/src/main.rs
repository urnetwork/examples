//! The Rust provider example: a console app for Windows, macOS and Linux that runs a URnetwork
//! provider for the developer's network and shows its status (PROVIDER_CONTRACT.md). It provides
//! publicly with the scoped client credential that the developer's backend issued for this
//! installation; the payout wallet is mapped by the backend and is only displayed here.
//!
//! Usage: `provider [run] | --self-test | --version`. All installation state is in the private
//! directory named by `URNETWORK_PROVIDER_STATE_DIR` (src/state.rs).
//!
//! Exit codes, for supervisors: 0 stopped on request, 78 configuration or credential problem
//! (restarting does not help), 1 any other failure.

use std::{
    env,
    path::PathBuf,
    sync::{Arc, mpsc},
    time::Instant,
};

use urnetwork_provider::{
    EXIT_CONFIG, EXIT_FAILURE, EXIT_STOPPED, self_test,
    session::{self, DeviceInfo, ProviderObserver, ProviderSession, RunEnd, RunEvent},
    state::{LOGS_DIR_NAME, load_provider_config},
    status::{
        CLIENTS_SERVED_LIMIT, CONSENT_DISCLAIMER, ClientsServed, ProviderStatus, StatusLines,
    },
    version,
};

/// The device description and spec recorded for this installation's device.
const DEVICE_INFO: DeviceInfo = DeviceInfo {
    description: "Rust provider example",
    spec: "urnetwork-examples/rust-provider",
};

/// Printed with exit code 78 for arguments the app does not know.
const USAGE: &str = "usage: provider [run] | --self-test | --version";

/// What the arguments ask for.
#[derive(Debug, PartialEq, Eq)]
enum Command {
    Run,
    SelfTest,
    Version,
}

/// The command for the arguments after the program name, or none for a usage error.
fn command(args: &[String]) -> Option<Command> {
    match args {
        [] => Some(Command::Run),
        [arg] if arg == "run" => Some(Command::Run),
        [arg] if arg == "--self-test" => Some(Command::SelfTest),
        [arg] if arg == "--version" => Some(Command::Version),
        _ => None,
    }
}

/// Exits with the code of the command.
fn main() {
    let args: Vec<String> = env::args().skip(1).collect();
    std::process::exit(run(&args));
}

/// Runs one command and returns the exit code.
fn run(args: &[String]) -> i32 {
    match command(args) {
        Some(Command::SelfTest) => match self_test::run_self_test().and_then(|()| check_usage()) {
            Ok(()) => {
                println!("provider self-test passed");
                EXIT_STOPPED
            }
            Err(message) => {
                eprintln!("provider self-test failed: {message}");
                EXIT_FAILURE
            }
        },
        Some(Command::Version) => match version() {
            Ok(version) => {
                println!("{version}");
                EXIT_STOPPED
            }
            Err(error) => {
                eprintln!("could not load the URnetwork SDK: {error}");
                EXIT_FAILURE
            }
        },
        Some(Command::Run) => run_provider(),
        None => {
            eprintln!("{USAGE}");
            EXIT_CONFIG
        }
    }
}

/// Unknown arguments are a usage error, which exits with code 78.
fn check_usage() -> self_test::CheckResult {
    let usage_errors: [&[&str]; 3] = [
        &["--unknown"],
        &["run", "--unknown"],
        &["--self-test", "--version"],
    ];
    for args in usage_errors {
        let args: Vec<String> = args.iter().map(|arg| arg.to_string()).collect();
        if command(&args).is_some() {
            return Err(format!("usage: {args:?} was accepted"));
        }
    }
    Ok(())
}

/// Shows the disclaimer, starts providing and prints status lines until a stop or a credential
/// rejection.
fn run_provider() -> i32 {
    println!("{CONSENT_DISCLAIMER}");
    let state_dir = PathBuf::from(env::var_os("URNETWORK_PROVIDER_STATE_DIR").unwrap_or_default());
    let config = match load_provider_config(&state_dir) {
        Ok(config) => config,
        Err(error) => {
            eprintln!("{error}");
            return EXIT_CONFIG;
        }
    };
    if let Err(error) = session::set_log_dir(&config.state_dir.join(LOGS_DIR_NAME)) {
        eprintln!("could not set the sdk log directory: {error}");
        return EXIT_FAILURE;
    }
    // Ctrl-C, SIGTERM and SIGHUP (or the Windows console close) stop providing; installed before
    // the device starts
    let (events, run_events) = mpsc::channel();
    let stop_events = events.clone();
    if let Err(error) = ctrlc::set_handler(move || {
        let _ = stop_events.send(RunEvent::Stop);
    }) {
        eprintln!("could not handle stop signals: {error}");
        return EXIT_FAILURE;
    }
    let clients_served = Arc::new(ClientsServed::new(CLIENTS_SERVED_LIMIT));
    let mut session = match ProviderSession::start(config, DEVICE_INFO, clients_served, events) {
        Ok(session) => session,
        Err(error) => {
            eprintln!("could not start the provider: {error}");
            return EXIT_FAILURE;
        }
    };
    println!(
        "provider client {}, instance {}",
        session.client_id(),
        session.instance_id()
    );
    let exit_code = match session.run(&run_events, &mut ConsoleObserver::default()) {
        RunEnd::Stopped => EXIT_STOPPED,
        RunEnd::LoggedOut => {
            eprintln!(
                "the server rejected the client credential; issue a new scoped client JWT from your backend"
            );
            EXIT_CONFIG
        }
    };
    if let Err(error) = session.close() {
        // never print the token itself
        eprintln!("could not save the refreshed client credential: {error}");
    }
    println!("status: stopped");
    exit_code
}

/// Prints a status line on stdout when the status text, the clients-served count or the payout
/// wallet changes, and at least once a minute; warnings go to stderr.
#[derive(Default)]
struct ConsoleObserver {
    status_lines: StatusLines,
}

impl ProviderObserver for ConsoleObserver {
    /// Prints the line that is due.
    fn status(&mut self, status: &ProviderStatus) {
        if let Some(line) = self.status_lines.next_line(status, Instant::now()) {
            println!("{line}");
        }
    }

    /// Prints the warning on stderr.
    fn warning(&mut self, message: &str) {
        eprintln!("{message}");
    }
}

#[cfg(test)]
mod tests {
    //! The command line, without the native runtime.

    use super::*;

    #[test]
    fn usage_exit_code() {
        assert_eq!(run(&["--unknown".to_string()]), EXIT_CONFIG);
        assert_eq!(EXIT_CONFIG, 78);
    }

    #[test]
    fn commands() {
        assert_eq!(command(&[]), Some(Command::Run));
        assert_eq!(command(&["run".to_string()]), Some(Command::Run));
        assert_eq!(
            command(&["--self-test".to_string()]),
            Some(Command::SelfTest)
        );
        assert_eq!(command(&["--version".to_string()]), Some(Command::Version));
        assert!(check_usage().is_ok());
    }
}
