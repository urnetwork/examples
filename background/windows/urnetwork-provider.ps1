# Launcher for a URnetwork provider example (PROVIDER_CONTRACT.md), started by
# the scheduled task that register-task.ps1 creates. Replace the @...@ values.
#
# Exit 0 is a requested stop and exit 78 is a configuration or credential
# problem that a restart does not fix; after any other exit the provider
# restarts 30 seconds later. Each start keeps the previous run's logs as *.1.

$ErrorActionPreference = 'Stop'

# the private installation state directory; it holds client.jwt
$env:URNETWORK_PROVIDER_STATE_DIR = '@STATE_DIR@'
# the example's executable and its arguments, with absolute paths
$ProviderCommand = '@COMMAND@'
$ProviderArguments = @('@ARGUMENT@')
# a directory for the console output, for example the state directory's logs
$LogDir = '@LOG_DIR@'

New-Item -ItemType Directory -Force -Path $LogDir | Out-Null
$OutLog = Join-Path $LogDir 'urnetwork-provider.out.log'
$ErrLog = Join-Path $LogDir 'urnetwork-provider.err.log'

while ($true) {
    foreach ($Log in @($OutLog, $ErrLog)) {
        if (Test-Path $Log) {
            Move-Item -Force $Log "$Log.1"
        }
    }
    $Process = Start-Process -FilePath $ProviderCommand -ArgumentList $ProviderArguments `
        -NoNewWindow -Wait -PassThru `
        -RedirectStandardOutput $OutLog -RedirectStandardError $ErrLog
    $ExitCode = $Process.ExitCode
    if ($ExitCode -eq 0 -or $ExitCode -eq 78) {
        exit $ExitCode
    }
    Start-Sleep -Seconds 30
}
