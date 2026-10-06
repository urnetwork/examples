# Registers the scheduled task that runs urnetwork-provider.ps1 in the
# background for the current user (PROVIDER_CONTRACT.md). Run it from a
# PowerShell prompt as that user:
#
#   powershell -ExecutionPolicy Bypass -File register-task.ps1 -Launcher C:\path\to\urnetwork-provider.ps1
#
# The default task starts at logon, runs as the signed-in user without
# administrator rights and has no time limit. Add -AtStartup to start at boot
# whether or not the user is signed in; that form runs without a window, does
# not store a password (S4U) and must be registered from an elevated prompt.
#
# Remove the task with:
#   Unregister-ScheduledTask -TaskName 'URnetwork Provider' -Confirm:$false

param(
    [Parameter(Mandatory = $true)] [string] $Launcher,
    [string] $TaskName = 'URnetwork Provider',
    [switch] $AtStartup
)

$ErrorActionPreference = 'Stop'

$Launcher = (Resolve-Path $Launcher).Path
$Action = New-ScheduledTaskAction -Execute 'powershell.exe' `
    -Argument "-NoProfile -NonInteractive -WindowStyle Hidden -ExecutionPolicy Bypass -File `"$Launcher`""
$Settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries `
    -ExecutionTimeLimit ([TimeSpan]::Zero) -MultipleInstances IgnoreNew `
    -RestartCount 3 -RestartInterval (New-TimeSpan -Minutes 1)
$UserId = [System.Security.Principal.WindowsIdentity]::GetCurrent().Name
if ($AtStartup) {
    $Trigger = New-ScheduledTaskTrigger -AtStartup
    $Principal = New-ScheduledTaskPrincipal -UserId $UserId -LogonType S4U -RunLevel Limited
} else {
    $Trigger = New-ScheduledTaskTrigger -AtLogOn -User $UserId
    $Principal = New-ScheduledTaskPrincipal -UserId $UserId -LogonType Interactive -RunLevel Limited
}
Register-ScheduledTask -TaskName $TaskName -Action $Action -Trigger $Trigger `
    -Principal $Principal -Settings $Settings -Force | Out-Null
Start-ScheduledTask -TaskName $TaskName
Write-Output "registered and started the scheduled task '$TaskName'"
