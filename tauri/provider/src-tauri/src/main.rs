//! The Tauri provider example: a desktop app for Windows, macOS and Linux that runs a URnetwork
//! provider in its Rust core, in process, with the provider core of the Rust console example
//! (`rust/provider`) over the SDK's C ABI (PROVIDER_CONTRACT.md, "Tauri"). The window shows the
//! consent disclaimer next to the start control and the four status fields. Closing the window
//! keeps providing in the tray; Quit in the tray stops providing and exits. Start at login uses the
//! official autostart plugin: the app then starts in the tray and provides.
//!
//! The installation state (`client.jwt`, `instance-id`, `identity.json`, the SDK's `logs/`) is in a
//! private `provider` directory in the app's local data directory, which the window names.

#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod provider;

use tauri::{
    AppHandle, Manager, RunEvent, State, WindowEvent,
    menu::{Menu, MenuItem},
    tray::TrayIconBuilder,
};
use tauri_plugin_autostart::ManagerExt;

use provider::{ProviderController, ProviderView};

/// The argument that start at login passes: the app starts in the tray and provides.
const BACKGROUND_ARG: &str = "--background";

/// The main window's label (tauri.conf.json).
const MAIN_WINDOW_LABEL: &str = "main";

/// The private directory in the app's local data directory that holds the installation state.
/// Local, not roaming: the identity belongs to this machine.
const PROVIDER_DIR_NAME: &str = "provider";

/// The tray menu item that shows the window.
const SHOW_MENU_ID: &str = "show";

/// The tray menu item that stops providing and exits.
const QUIT_MENU_ID: &str = "quit";

/// Whether start at login launched the app.
fn launched_in_background(args: impl IntoIterator<Item = String>) -> bool {
    args.into_iter().any(|arg| arg == BACKGROUND_ARG)
}

/// Shows and focuses the main window.
fn show_main_window(app: &AppHandle) {
    if let Some(window) = app.get_webview_window(MAIN_WINDOW_LABEL) {
        let _ = window.unminimize();
        let _ = window.show();
        let _ = window.set_focus();
    }
}

/// The provider view, for the window's first paint.
#[tauri::command]
fn provider_view(controller: State<'_, ProviderController>) -> ProviderView {
    controller.view()
}

/// Starts providing, from the start control next to the consent disclaimer.
#[tauri::command]
fn start_providing(app: AppHandle, controller: State<'_, ProviderController>) -> ProviderView {
    controller.start(&app)
}

/// Stops providing; the window gets the stopped view once the device has closed.
#[tauri::command]
fn stop_providing(controller: State<'_, ProviderController>) -> ProviderView {
    controller.stop()
}

/// Whether the app starts at login.
#[tauri::command]
fn start_at_login(app: AppHandle) -> Result<bool, String> {
    app.autolaunch()
        .is_enabled()
        .map_err(|error| error.to_string())
}

/// Turns start at login on or off and returns the new setting.
#[tauri::command]
fn set_start_at_login(app: AppHandle, enabled: bool) -> Result<bool, String> {
    let autolaunch = app.autolaunch();
    let changed = if enabled {
        autolaunch.enable()
    } else {
        autolaunch.disable()
    };
    changed.map_err(|error| error.to_string())?;
    autolaunch.is_enabled().map_err(|error| error.to_string())
}

/// The tray icon and its menu: show the window, or quit, which stops providing first.
fn build_tray(app: &AppHandle) -> tauri::Result<()> {
    let show = MenuItem::with_id(app, SHOW_MENU_ID, "Show window", true, None::<&str>)?;
    let quit = MenuItem::with_id(app, QUIT_MENU_ID, "Quit", true, None::<&str>)?;
    let menu = Menu::with_items(app, &[&show, &quit])?;
    let mut tray = TrayIconBuilder::with_id("provider")
        .tooltip("URnetwork provider")
        .menu(&menu)
        .on_menu_event(|app, event| match event.id().as_ref() {
            SHOW_MENU_ID => show_main_window(app),
            QUIT_MENU_ID => {
                // closing the device takes a moment; keep the event loop free meanwhile
                let app = app.clone();
                std::thread::spawn(move || {
                    app.state::<ProviderController>().stop_and_wait();
                    app.exit(0);
                });
            }
            _ => {}
        });
    if let Some(icon) = app.default_window_icon() {
        tray = tray.icon(icon.clone());
    }
    tray.build(app)?;
    Ok(())
}

/// Runs the app until Quit.
fn main() {
    let app = tauri::Builder::default()
        // a second launch shows this window instead of starting a second provider with the same
        // identity
        .plugin(tauri_plugin_single_instance::init(|app, _args, _cwd| {
            show_main_window(app)
        }))
        .plugin(
            tauri_plugin_autostart::Builder::new()
                .arg(BACKGROUND_ARG)
                .build(),
        )
        .setup(|app| {
            let state_dir = app.path().app_local_data_dir()?.join(PROVIDER_DIR_NAME);
            app.manage(ProviderController::new(state_dir));
            build_tray(app.handle())?;
            if launched_in_background(std::env::args()) {
                app.state::<ProviderController>().start(app.handle());
            } else {
                show_main_window(app.handle());
            }
            Ok(())
        })
        .on_window_event(|window, event| {
            // closing the window keeps providing in the tray
            if let WindowEvent::CloseRequested { api, .. } = event {
                api.prevent_close();
                let _ = window.hide();
            }
        })
        .invoke_handler(tauri::generate_handler![
            provider_view,
            start_providing,
            stop_providing,
            start_at_login,
            set_start_at_login,
        ])
        .build(tauri::generate_context!())
        .expect("the Tauri app could not start");
    app.run(|app, event| match event {
        // stop providing before the process exits: provide mode none, then the device closes
        RunEvent::Exit => app.state::<ProviderController>().stop_and_wait(),
        #[cfg(target_os = "macos")]
        RunEvent::Reopen { .. } => show_main_window(app),
        _ => {}
    });
}

#[cfg(test)]
mod tests {
    //! The launch mode, the window's consent disclaimer and the shared core's self-test, without
    //! starting the app.

    use urnetwork_provider::{self_test, status::CONSENT_DISCLAIMER};

    use super::*;

    #[test]
    fn background_launch() {
        let args = |args: &[&str]| args.iter().map(|arg| arg.to_string()).collect::<Vec<_>>();
        assert!(launched_in_background(args(&[
            "urnetwork-tauri-provider",
            "--background"
        ])));
        assert!(!launched_in_background(args(&["urnetwork-tauri-provider"])));
    }

    #[test]
    fn window_shows_the_consent_disclaimer_next_to_start() {
        let page = include_str!("../../ui/index.html");
        for line in CONSENT_DISCLAIMER.lines() {
            assert!(
                page.contains(line),
                "the window lacks the disclaimer line {line:?}"
            );
        }
        let disclaimer_index = page.find("id=\"consent-disclaimer\"").unwrap();
        let start_index = page.find("id=\"start\"").unwrap();
        assert!(disclaimer_index < start_index);
        for id in [
            "status",
            "clients-served",
            "data-provided",
            "payout-wallet",
            "stop",
            "start-at-login",
        ] {
            assert!(
                page.contains(&format!("id=\"{id}\"")),
                "the window lacks #{id}"
            );
        }
    }

    #[test]
    fn window_calls_the_registered_commands() {
        let script = include_str!("../../ui/main.js");
        for command in [
            "provider_view",
            "start_providing",
            "stop_providing",
            "start_at_login",
            "set_start_at_login",
        ] {
            assert!(
                script.contains(&format!("'{command}'")),
                "main.js does not call {command}"
            );
        }
    }

    #[test]
    fn core_self_test() {
        if let Err(message) = self_test::run_self_test() {
            panic!("{message}");
        }
    }
}
