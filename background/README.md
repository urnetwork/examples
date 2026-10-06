# Run a provider in the background

These templates keep a console [provider example](../PROVIDER_CONTRACT.md) running after you close the terminal: a **systemd user service** on Linux, a **launchd agent** on macOS and a **scheduled task** on Windows. Every provider example uses them the same way; only the run command differs. The GUI samples ([Electron](../PROVIDER_CONTRACT.md#electron), [Tauri](../PROVIDER_CONTRACT.md#tauri)) and [Android](../PROVIDER_CONTRACT.md#android) have their own background patterns.

**Consent disclaimer:** an app that integrates a URnetwork provider must collect the user's consent before it provides. Providing shares the user's internet connection: other URnetwork users' traffic exits through the user's device and IP address. The examples start providing without asking, because the consent screen belongs to your app.

## Before you start

Prepare the installation's private state directory as the provider example's README describes: create it with owner-only permissions and write the scoped `client.jwt` from your backend into it. Run the example once in a terminal to check the configuration; it creates `instance-id` and `identity.json` on first run. Every template sets `URNETWORK_PROVIDER_STATE_DIR` to that directory, so the service definition names a path, never a credential.

Each template has `@...@` placeholders:

| Placeholder | Value |
| --- | --- |
| `@STATE_DIR@` | Absolute path of the state directory, for example `/home/alice/.local/state/urnetwork-provider`. |
| `@COMMAND@` | Absolute path of the example's executable or interpreter, for example the built Go `provider` binary or `python3`. |
| `@ARGUMENT@` | The arguments that start providing, one per entry, for example `run`, or the script path followed by `run`. |
| `@LOG_DIR@` | A directory for the app's console output (macOS and Windows), for example the `logs` directory inside the state directory, where the SDK also keeps its own bounded log files. |

All provider examples use the same exit codes, so one supervisor policy fits them all: **0** means a requested stop, **78** means a configuration or credential problem that restarting does not fix (for example, the server rejected the client credential), and any other code is a failure that is retried after a delay.

## Linux: systemd user service

[urnetwork-provider.service](linux/urnetwork-provider.service) runs the provider as your user, restarts it 30 seconds after a failure, and does not restart it after exit 78.

```sh
mkdir -p ~/.config/systemd/user
cp linux/urnetwork-provider.service ~/.config/systemd/user/
# edit @STATE_DIR@ and @COMMAND@ (ExecStart= takes the absolute command and its arguments)
systemctl --user daemon-reload
systemctl --user enable --now urnetwork-provider.service
loginctl enable-linger "$USER"
journalctl --user -u urnetwork-provider.service -f
```

`enable-linger` starts your user services at boot and keeps them running after logout. Stop with `systemctl --user stop urnetwork-provider.service`; remove with `systemctl --user disable --now urnetwork-provider.service`.

## macOS: launchd agent

[com.example.urnetwork-provider.plist](macos/com.example.urnetwork-provider.plist) starts at login and restarts after a non-zero exit, at most once a minute. Replace the `com.example.urnetwork-provider` label with your own reverse-DNS name and use it as the file name.

```sh
cp macos/com.example.urnetwork-provider.plist ~/Library/LaunchAgents/
# edit the label and the @...@ values; add one <string> per extra argument
plutil -lint ~/Library/LaunchAgents/com.example.urnetwork-provider.plist
launchctl bootstrap gui/$(id -u) ~/Library/LaunchAgents/com.example.urnetwork-provider.plist
launchctl print gui/$(id -u)/com.example.urnetwork-provider
```

Stop and remove with `launchctl bootout gui/$(id -u)/com.example.urnetwork-provider`. launchd has no per-code restart rule: after exit 78 it keeps retrying once a minute, so fix the state directory, then bootout and bootstrap again.

## Windows: scheduled task

[urnetwork-provider.ps1](windows/urnetwork-provider.ps1) is the launcher: it sets the state directory, starts the provider, keeps the logs, and restarts the provider 30 seconds after a failure (not after exit 0 or 78). [register-task.ps1](windows/register-task.ps1) registers it with Task Scheduler for the current user. Copy both, edit the `@...@` values in the launcher, then in PowerShell:

```powershell
powershell -ExecutionPolicy Bypass -File .\register-task.ps1 -Launcher C:\path\to\urnetwork-provider.ps1
Get-ScheduledTask -TaskName 'URnetwork Provider'
Get-Content -Wait C:\path\to\logs\urnetwork-provider.out.log
```

The default task starts at logon as the signed-in user and needs no administrator rights; a console window may flash briefly at logon. From an elevated prompt, `-AtStartup` instead starts the provider at boot, whether or not the user is signed in, without a window and without storing a password. Stop with `Stop-ScheduledTask -TaskName 'URnetwork Provider'`; remove with `Unregister-ScheduledTask -TaskName 'URnetwork Provider' -Confirm:$false`.

A Windows service is the other option for machine-wide providers, but it needs code that answers the service control manager. These console examples use the scheduled task so that every language works without service-specific code.
