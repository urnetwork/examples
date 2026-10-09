//! The Rust embed example: a console app for Windows, macOS and Linux that embeds a URnetwork Device
//! in your application and shows its status (EMBED_CONTRACT.md). It obtains this installation's
//! scoped client JWT from your backend's token server (or uses the `client.jwt` a backend tool
//! wrote), starts the Device with it and connects to the best available location. The Device carries
//! only the app's own traffic; the Sockets and Messages examples continue from there.
//!
//! Usage: `embed [run] | --self-test | --version | --licenses`. Settings:
//! - `URNETWORK_EMBED_STATE_DIR` (required): the installation's private state directory.
//! - `URNETWORK_TOKEN_SERVER_URL` and `URNETWORK_DEMO_SESSION` (together, optional): the token
//!   server and the demo session that stands in for your sign-in.
//! - `URNETWORK_API_URL` (optional): the API origin for the cap reads.
//!
//! Exit codes, for supervisors: 0 stopped on request, 78 configuration or credential problem
//! (restarting does not help), 1 any other failure.

use std::{env, path::PathBuf, sync::mpsc, time::Instant};

use urnetwork_embed::{
    EXIT_CONFIG, EXIT_FAILURE, EXIT_STOPPED,
    config::{api_origin_from_setting, load_embed_config, token_server_from_settings},
    self_test,
    session::{self, DeviceInfo, EmbedObserver, EmbedSession, RunEnd, RunEvent},
    state::{LOGS_DIR_NAME, STATE_DIR_ENV},
    status::{EmbedStatus, StatusLines, start_line},
    version,
};

/// The device description and spec recorded for this installation's device.
const DEVICE_INFO: DeviceInfo = DeviceInfo {
    description: "Rust embed example",
    spec: "urnetwork-examples/rust-embed",
};

/// Printed with [`USAGE_EXIT_CODE`] for arguments the app does not know.
const USAGE: &str = "usage: embed [run] | --self-test | --version | --licenses";

/// A usage error is a configuration problem: exit code 78.
const USAGE_EXIT_CODE: i32 = EXIT_CONFIG;

/// What the arguments ask for.
#[derive(Debug, PartialEq, Eq)]
enum Command {
    Run,
    SelfTest,
    Version,
    Licenses,
}

/// The command for the arguments after the program name, or none for a usage error.
fn command(args: &[String]) -> Option<Command> {
    match args {
        [] => Some(Command::Run),
        [arg] if arg == "run" => Some(Command::Run),
        [arg] if arg == "--self-test" => Some(Command::SelfTest),
        [arg] if arg == "--version" => Some(Command::Version),
        [arg] if arg == "--licenses" => Some(Command::Licenses),
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
                println!("embed self-test passed");
                EXIT_STOPPED
            }
            Err(message) => {
                eprintln!("embed self-test failed: {message}");
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
        // the SDK's licenses and data attributions for this OS, to publish with the app
        Some(Command::Licenses) => match session::licenses_json() {
            Ok(licenses) => {
                println!("{licenses}");
                EXIT_STOPPED
            }
            Err(error) => {
                eprintln!("could not read the SDK licenses: {error}");
                EXIT_FAILURE
            }
        },
        Some(Command::Run) => run_embed(),
        None => {
            eprintln!("{USAGE}");
            USAGE_EXIT_CODE
        }
    }
}

/// Unknown arguments are a usage error, which exits with code 78.
fn check_usage() -> self_test::CheckResult {
    let usage_errors: [&[&str]; 4] = [
        &["--unknown"],
        &["run", "--unknown"],
        &["--self-test", "--version"],
        &["--licenses", "run"],
    ];
    for args in usage_errors {
        let args: Vec<String> = args.iter().map(|arg| arg.to_string()).collect();
        if command(&args).is_some() {
            return Err(format!("usage: {args:?} was accepted"));
        }
    }
    if USAGE_EXIT_CODE != 78 {
        return Err("a usage error does not exit with 78".to_string());
    }
    Ok(())
}

/// An environment variable as text; an unset or non-UTF-8 value reads as unset.
fn setting(name: &str) -> Option<String> {
    env::var(name).ok()
}

/// Loads the installation, obtains the client JWT, starts the Device and prints status lines until
/// a stop or a credential rejection.
fn run_embed() -> i32 {
    let state_dir = PathBuf::from(env::var_os(STATE_DIR_ENV).unwrap_or_default());
    let token_server = match token_server_from_settings(
        setting("URNETWORK_TOKEN_SERVER_URL").as_deref(),
        setting("URNETWORK_DEMO_SESSION").as_deref(),
    ) {
        Ok(token_server) => token_server,
        Err(error) => {
            eprintln!("{error}");
            return EXIT_CONFIG;
        }
    };
    let api_origin = match api_origin_from_setting(setting("URNETWORK_API_URL").as_deref()) {
        Ok(api_origin) => api_origin,
        Err(error) => {
            eprintln!("{error}");
            return EXIT_CONFIG;
        }
    };
    // with a token server, this fetches the client JWT on every start (token::fetch_client_jwt)
    let config = match load_embed_config(&state_dir, token_server.as_ref()) {
        Ok(config) => config,
        Err(error) => {
            eprintln!("{error}");
            return error.exit_code();
        }
    };
    if let Err(error) = session::set_log_dir(&config.state_dir.join(LOGS_DIR_NAME)) {
        eprintln!("could not set the sdk log directory: {error}");
        return EXIT_FAILURE;
    }
    // Ctrl-C, SIGTERM and SIGHUP (or the Windows console close) stop the device; installed before
    // the device starts
    let (events, run_events) = mpsc::channel();
    let stop_events = events.clone();
    if let Err(error) = ctrlc::set_handler(move || {
        let _ = stop_events.send(RunEvent::Stop);
    }) {
        eprintln!("could not handle stop signals: {error}");
        return EXIT_FAILURE;
    }
    let mut session = match EmbedSession::start(config, DEVICE_INFO, api_origin, events) {
        Ok(session) => session,
        Err(error) => {
            eprintln!("could not start the embedded device: {error}");
            return EXIT_FAILURE;
        }
    };
    println!("{}", start_line(session.client_id(), session.instance_id()));
    println!(
        "the device carries this app's own traffic: route it with the Sockets and Messages examples"
    );
    let exit_code = match session.run(&run_events, &mut ConsoleObserver::default()) {
        RunEnd::Stopped => EXIT_STOPPED,
        RunEnd::LoggedOut => {
            eprintln!(
                "the server rejected the client credential; sign in again so your backend reissues it"
            );
            EXIT_CONFIG
        }
    };
    if let Err(error) = session.close() {
        // never print the token itself
        eprintln!("could not save the refreshed client credential: {error}");
    }
    exit_code
}

/// Prints a status line on stdout when any field's text changes, and at least once a minute;
/// warnings go to stderr.
#[derive(Default)]
struct ConsoleObserver {
    status_lines: StatusLines,
}

impl EmbedObserver for ConsoleObserver {
    /// Prints the line that is due.
    fn status(&mut self, status: &EmbedStatus) {
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
        assert_eq!(
            command(&["--licenses".to_string()]),
            Some(Command::Licenses)
        );
        assert!(check_usage().is_ok());
    }
}
