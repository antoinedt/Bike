#Requires -RunAsAdministrator
<#
.SYNOPSIS
  Updates an existing Parental Control install to the code in ..\src and restarts it
  everywhere, without touching settings, password, data, firewall or the agent Run key.

  * stops the service (scheduled task) and the agent in every user session
  * copies the new files to C:\Program Files\ParentalControl
  * starts the service again and waits for the dashboard to answer
  * starts the agent again in every logged-in user session, as that user
    (a one-off scheduled task per user, deleted right after)

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File scripts\update.ps1
#>
param(
    # Optional: also write everything this script prints to this file.
    [string]$LogFile = ""
)
$ErrorActionPreference = "Stop"
if ($LogFile) { Start-Transcript -Path $LogFile -Force | Out-Null }

$InstallDir = Join-Path $env:ProgramFiles "ParentalControl"
$DataDir    = Join-Path $env:ProgramData  "ParentalControl"
$TaskName   = "ParentalControlService"
$RunKey     = "HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Run"
$SrcDir     = Join-Path (Split-Path -Parent $PSScriptRoot) "src"

function Get-PcProcesses([string]$script) {
    Get-CimInstance Win32_Process -Filter "Name='python.exe' OR Name='pythonw.exe'" |
        Where-Object { $_.CommandLine -like "*$InstallDir\$script*" }
}

try {
    $started = Get-Date

    # ------------------------------------------------------------ checks ---
    if (-not (Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue)) {
        throw "Parental Control is not installed (no '$TaskName' task). Run scripts\install.ps1 instead."
    }
    $agentCmd = (Get-ItemProperty $RunKey -ErrorAction SilentlyContinue).ParentalControlAgent
    if (-not $agentCmd -or $agentCmd -notmatch '^"([^"]+)"\s+(.*)$') {
        throw "Agent Run key entry not found or not understood. Run scripts\install.ps1 instead."
    }
    $agentExe, $agentArgs = $Matches[1], $Matches[2]
    $cfg = Get-Content (Join-Path $DataDir "config.json") -Raw | ConvertFrom-Json
    $port = if ($cfg.port) { [int]$cfg.port } else { 8765 }
    $scheme = if ($cfg.tls_cert) { "https" } else { "http" }

    # Who is logged in right now: owners of explorer.exe, plus owners of running agents.
    $users = @{}
    $procs = @(Get-CimInstance Win32_Process -Filter "Name='explorer.exe'") + @(Get-PcProcesses "agent.py")
    foreach ($p in $procs) {
        $o = Invoke-CimMethod -InputObject $p -MethodName GetOwner
        if ($o.ReturnValue -eq 0 -and $o.User) { $users["$($o.Domain)\$($o.User)"] = $p.SessionId }
    }
    Write-Host "Logged-in users: $(if ($users.Count) { ($users.Keys | Sort-Object) -join ', ' } else { 'none' })"

    # -------------------------------------------------------------- stop ---
    Write-Host "Stopping the service..."
    Stop-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
    Write-Host "Stopping the agents..."
    foreach ($p in @(Get-PcProcesses "service.py") + @(Get-PcProcesses "agent.py")) {
        Stop-Process -Id $p.ProcessId -Force -ErrorAction SilentlyContinue
    }
    for ($i = 0; $i -lt 20 -and (@(Get-PcProcesses "service.py") + @(Get-PcProcesses "agent.py")).Count; $i++) {
        Start-Sleep -Milliseconds 500
    }

    # ------------------------------------------------------------- files ---
    Write-Host "Copying $SrcDir to $InstallDir..."
    Remove-Item (Join-Path $InstallDir "__pycache__") -Recurse -Force -ErrorAction SilentlyContinue
    Copy-Item -Path (Join-Path $SrcDir "*") -Destination $InstallDir -Recurse -Force

    # ----------------------------------------------------------- service ---
    Write-Host "Starting the service..."
    Start-ScheduledTask -TaskName $TaskName
    $up = $false
    for ($i = 0; $i -lt 30 -and -not $up; $i++) {
        Start-Sleep -Seconds 1
        try {
            Invoke-WebRequest -UseBasicParsing -TimeoutSec 3 "${scheme}://127.0.0.1:$port/api/session" | Out-Null
            $up = $true
        } catch {}
    }
    if (-not $up) { throw "The service did not answer on port $port within 30 s. See $DataDir\service.log" }

    # ------------------------------------------------------------ agents ---
    foreach ($user in $users.Keys | Sort-Object) {
        Write-Host "Starting the agent for $user..."
        $tmpTask = "ParentalControlAgentRestart-" + ($user -replace '[^A-Za-z0-9]', '_')
        $action    = New-ScheduledTaskAction -Execute $agentExe -Argument $agentArgs -WorkingDirectory $InstallDir
        $principal = New-ScheduledTaskPrincipal -UserId $user -LogonType Interactive -RunLevel Limited
        $settings  = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries `
                       -ExecutionTimeLimit ([TimeSpan]::Zero) -MultipleInstances IgnoreNew
        Register-ScheduledTask -TaskName $tmpTask -Action $action -Principal $principal -Settings $settings -Force | Out-Null
        try {
            Start-ScheduledTask -TaskName $tmpTask
            for ($i = 0; $i -lt 10; $i++) {
                Start-Sleep -Milliseconds 500
                if ((Get-ScheduledTask -TaskName $tmpTask).State -eq "Running") { break }
            }
        } finally {
            Unregister-ScheduledTask -TaskName $tmpTask -Confirm:$false -ErrorAction SilentlyContinue
        }
    }

    # ------------------------------------------------------------ verify ---
    Start-Sleep -Seconds 2
    $ok = $true
    Write-Host ""
    Write-Host "Verification:"
    foreach ($f in Get-ChildItem $SrcDir -File) {
        $dst = Join-Path $InstallDir $f.Name
        if ((Get-FileHash $f.FullName).Hash -ne (Get-FileHash $dst).Hash) { Write-Host "  MISMATCH $($f.Name)"; $ok = $false }
    }
    Write-Host "  installed files match src: $ok"
    $svc = @(Get-PcProcesses "service.py")
    foreach ($p in $svc) { Write-Host "  service pid $($p.ProcessId) session $($p.SessionId) started $($p.CreationDate)" }
    if (-not $svc -or ($svc | Where-Object { $_.CreationDate -lt $started })) { Write-Host "  service NOT restarted"; $ok = $false }
    foreach ($user in $users.Keys | Sort-Object) {
        $mine = @(Get-PcProcesses "agent.py" | Where-Object {
            $o = Invoke-CimMethod -InputObject $_ -MethodName GetOwner; "$($o.Domain)\$($o.User)" -eq $user })
        $new = @($mine | Where-Object { $_.CreationDate -ge $started })
        Write-Host "  agent for ${user}: $(if ($new) { "running, pid $($new.ProcessId -join ',') session $($new[0].SessionId)" } else { 'NOT running' })"
        if (-not $new) { $ok = $false }
    }
    if ($ok) { Write-Host "Update complete." -ForegroundColor Green }
    else { Write-Host "Update finished with problems (see above)." -ForegroundColor Yellow; exit 1 }
} catch {
    Write-Host "ERROR: $_" -ForegroundColor Red
    exit 1
} finally {
    if ($LogFile) { Stop-Transcript | Out-Null }
}
