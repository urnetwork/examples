//! The Tauri embed example: a desktop app for Windows, macOS and Linux that embeds a URnetwork Device
//! in its Rust core, in process, with the embed core of the Rust console example (`rust/embed`) over
//! the SDK's C ABI (EMBED_CONTRACT.md, "Tauri"). The window takes the token server URL and the demo
//! session that stand in for your sign-in, starts and stops the Device, and shows the status, the
//! data used this month and the running total, and the client and installation IDs. The Device
//! carries only the app's own traffic; it lives with the app, so closing the window stops it.
//!
//! The installation state (`client.jwt`, `instance-id`, `token-server.json`, the SDK's `logs/`) is
//! in a private `embed` directory in the app's local data directory, which the window names.

#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod embed;
mod embed_state;

use tauri::{AppHandle, Manager, RunEvent, State};
use urnetwork_embed::session::licenses_json;

use embed::EmbedController;
use embed_state::EmbedView;

/// The main window's label (tauri.conf.json).
const MAIN_WINDOW_LABEL: &str = "main";

/// The private directory in the app's local data directory that holds the installation state.
const EMBED_DIR_NAME: &str = "embed";

/// Shows and focuses the main window.
fn show_main_window(app: &AppHandle) {
    if let Some(window) = app.get_webview_window(MAIN_WINDOW_LABEL) {
        let _ = window.unminimize();
        let _ = window.show();
        let _ = window.set_focus();
    }
}

/// The embed view, for the window's first paint.
#[tauri::command]
fn embed_view(controller: State<'_, EmbedController>) -> EmbedView {
    controller.view()
}

/// Saves the token server settings and starts the device. An empty session keeps the saved one;
/// both fields empty use the `client.jwt` a backend tool wrote.
#[tauri::command]
fn start_embed(
    app: AppHandle,
    controller: State<'_, EmbedController>,
    token_server_url: String,
    demo_session: String,
) -> EmbedView {
    controller.start(&app, &token_server_url, &demo_session)
}

/// Stops the device; the window gets the stopped view once the device has closed.
#[tauri::command]
fn stop_embed(controller: State<'_, EmbedController>) -> EmbedView {
    controller.stop()
}

/// The SDK's licenses and data attributions for this OS, as JSON, to publish with the app.
#[tauri::command]
fn licenses() -> Result<String, String> {
    let json = licenses_json().map_err(|error| error.to_string())?;
    // pretty-printed for reading; the SDK's own text when it is not JSON
    Ok(serde_json::from_str::<serde_json::Value>(&json)
        .ok()
        .and_then(|value| serde_json::to_string_pretty(&value).ok())
        .unwrap_or(json))
}

/// Runs the app until its window closes.
fn main() {
    let app = tauri::Builder::default()
        // a second launch shows this window instead of starting a second device with the same
        // installation id, which would displace the first
        .plugin(tauri_plugin_single_instance::init(|app, _args, _cwd| {
            show_main_window(app)
        }))
        .setup(|app| {
            let state_dir = app.path().app_local_data_dir()?.join(EMBED_DIR_NAME);
            app.manage(EmbedController::new(state_dir));
            show_main_window(app.handle());
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            embed_view,
            start_embed,
            stop_embed,
            licenses
        ])
        .build(tauri::generate_context!())
        .expect("the Tauri app could not start");
    app.run(|app, event| {
        // the device lives with the app: close it before the process exits
        if let RunEvent::Exit = event {
            app.state::<EmbedController>().stop_and_wait();
        }
    });
}

#[cfg(test)]
mod tests {
    //! The window's fields and commands, and the shared core's self-test, without starting the app.

    use urnetwork_embed::self_test;

    #[test]
    fn window_has_the_fields_and_controls() {
        let page = include_str!("../../ui/index.html");
        for id in [
            "token-server-url",
            "demo-session",
            "start",
            "stop",
            "status",
            "data-this-month",
            "data-total",
            "client-id",
            "installation-id",
            "message",
            "state-dir",
            "licenses",
        ] {
            assert!(
                page.contains(&format!("id=\"{id}\"")),
                "the window lacks #{id}"
            );
        }
        // the closing pointer to the Sockets and Messages examples
        assert!(page.contains("Sockets") && page.contains("Messages"));
    }

    #[test]
    fn window_calls_the_registered_commands() {
        let script = include_str!("../../ui/main.js");
        for command in ["embed_view", "start_embed", "stop_embed", "licenses"] {
            assert!(
                script.contains(&format!("'{command}'")),
                "main.js does not call {command}"
            );
        }
        assert!(script.contains(&format!("'{}'", crate::embed::EMBED_VIEW_EVENT)));
        // Tauri maps the commands' snake_case arguments to camelCase
        assert!(script.contains("tokenServerUrl") && script.contains("demoSession"));
    }

    #[test]
    fn core_self_test() {
        if let Err(message) = self_test::run_self_test() {
            panic!("{message}");
        }
    }
}
