#Requires -RunAsAdministrator
<#
.SYNOPSIS
  Installs Parental Control on this PC.

  * copies the program to C:\Program Files\ParentalControl (standard users can't modify it)
  * creates C:\ProgramData\ParentalControl, readable only by SYSTEM and Administrators
  * registers the service as a scheduled task running as SYSTEM at boot (auto-restart)
  * registers the agent to start at every user logon (HKLM Run key)
  * opens the dashboard port in the Windows firewall: to the ZeroTier network only if
    this PC has joined one, otherwise to Private (home) networks

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File scripts\install.ps1
  powershell -ExecutionPolicy Bypass -File scripts\install.ps1 -Port 9000
#>
param(
    [int]$Port = 8765,
    # Full path to an all-users python.exe, if the automatic search picks the wrong one.
    [string]$PythonPath = ""
)
$ErrorActionPreference = "Stop"

$InstallDir = Join-Path $env:ProgramFiles "ParentalControl"
$DataDir    = Join-Path $env:ProgramData  "ParentalControl"
$TaskName   = "ParentalControlService"
$RunKey     = "HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Run"
$SrcDir     = Join-Path (Split-Path -Parent $PSScriptRoot) "src"
. (Join-Path $PSScriptRoot "remote-access.ps1")

# ---------------------------------------------------------------- Python ---
# The agent runs as each child, so Python must be installed for all users
# (not in someone's profile, e.g. by the per-user Python Install Manager).
Write-Host "Looking for an all-users Python..."
$candidates = @()
if ($PythonPath) { $candidates += $PythonPath }
foreach ($root in "HKLM:\SOFTWARE\Python\PythonCore", "HKLM:\SOFTWARE\WOW6432Node\Python\PythonCore") {
    Get-ChildItem $root -ErrorAction SilentlyContinue |
        Sort-Object { try { [version]($_.PSChildName -replace '[^0-9.]', '') } catch { [version]"0.0" } } -Descending |
        ForEach-Object {
            $ip = Get-ItemProperty (Join-Path $_.PSPath "InstallPath") -ErrorAction SilentlyContinue
            if ($ip -and $ip.ExecutablePath) { $candidates += $ip.ExecutablePath }
            elseif ($ip -and $ip.'(default)') { $candidates += (Join-Path $ip.'(default)' "python.exe") }
        }
}
$candidates += @(Get-ChildItem "$env:ProgramFiles\Python3*\python.exe", "${env:ProgramFiles(x86)}\Python3*\python.exe" `
                   -ErrorAction SilentlyContinue | ForEach-Object { $_.FullName })

$python = $null
$rejected = @()
foreach ($c in $candidates) {
    if (-not $c -or -not (Test-Path $c)) { continue }
    if ($c -like "$env:SystemDrive\Users\*" -or $c -like "*WindowsApps*") { $rejected += $c; continue }
    if (-not (Test-Path (Join-Path (Split-Path $c) "pythonw.exe"))) { $rejected += $c; continue }
    & $c -c "import sys; sys.exit(0 if sys.version_info >= (3, 9) else 1)" 2>$null
    if ($LASTEXITCODE -ne 0) { $rejected += "$c (older than 3.9)"; continue }
    $python = $c
    break
}
if (-not $python) {
    $msg = "No Python 3.9+ installed for all users was found."
    if ($rejected) { $msg += " Not usable: " + ($rejected -join ", ") + "." }
    $msg += " Install one with the python.org 'Windows installer (64-bit)' (not the Python Install Manager): " +
            "choose 'Customize installation', then tick 'Install Python for all users' on the Advanced Options page. " +
            "It should end up in C:\Program Files\Python3xx. Or pass -PythonPath 'C:\path\to\python.exe'."
    throw $msg
}
$pythonw = Join-Path (Split-Path $python) "pythonw.exe"
Write-Host "  using $python"

Write-Host "Installing the psutil package for all users..."
& $python -m pip install --quiet --upgrade psutil
if ($LASTEXITCODE -ne 0) { throw "pip install psutil failed" }

# ----------------------------------------------------------------- files ---
Write-Host "Copying files to $InstallDir..."
Stop-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $InstallDir | Out-Null
Copy-Item -Path (Join-Path $SrcDir "*") -Destination $InstallDir -Recurse -Force

Write-Host "Creating $DataDir (SYSTEM + Administrators only)..."
New-Item -ItemType Directory -Force -Path $DataDir | Out-Null
# *S-1-5-18 = SYSTEM, *S-1-5-32-544 = Administrators (language independent)
& icacls $DataDir /inheritance:r /grant:r "*S-1-5-18:(OI)(CI)F" "*S-1-5-32-544:(OI)(CI)F" | Out-Null

$cfgFile = Join-Path $DataDir "config.json"
if (Test-Path $cfgFile) {
    $cfg = Get-Content $cfgFile -Raw | ConvertFrom-Json
    $cfg.port = $Port
} else {
    $cfg = [pscustomobject]@{ port = $Port }
}
$zt = @(Get-ZeroTierAddresses)
Set-RemoteAccessConfig $cfg ($zt.Count -gt 0) | Out-Null
$cfg | ConvertTo-Json -Depth 5 | Set-Content -Path $cfgFile -Encoding UTF8

# -------------------------------------------------------------- password ---
$hasPw = (Get-Content $cfgFile -Raw | ConvertFrom-Json).password
if (-not $hasPw) {
    Write-Host ""
    Write-Host "Choose the password for the remote dashboard:"
    & $python (Join-Path $InstallDir "service.py") set-password
    if ($LASTEXITCODE -ne 0) { throw "password setup failed" }
}

# --------------------------------------------------------------- service ---
Write-Host "Registering the service (scheduled task '$TaskName', runs as SYSTEM at boot)..."
$action    = New-ScheduledTaskAction -Execute $pythonw -Argument "`"$InstallDir\service.py`"" -WorkingDirectory $InstallDir
$trigger   = New-ScheduledTaskTrigger -AtStartup
$principal = New-ScheduledTaskPrincipal -UserId "S-1-5-18" -LogonType ServiceAccount -RunLevel Highest
$settings  = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries `
               -StartWhenAvailable -ExecutionTimeLimit ([TimeSpan]::Zero) `
               -RestartCount 999 -RestartInterval (New-TimeSpan -Minutes 1) -MultipleInstances IgnoreNew
Register-ScheduledTask -TaskName $TaskName -Action $action -Trigger $trigger -Principal $principal `
    -Settings $settings -Force | Out-Null

# ----------------------------------------------------------------- agent ---
Write-Host "Registering the agent to start at every user logon..."
Set-ItemProperty -Path $RunKey -Name "ParentalControlAgent" `
    -Value "`"$pythonw`" `"$InstallDir\agent.py`" --port $Port"

# -------------------------------------------------------------- firewall ---
Write-Host "Opening TCP port $Port in the firewall..."
$zt = @(Set-DashboardFirewallRule $Port)

# ----------------------------------------------------------------- start ---
Start-ScheduledTask -TaskName $TaskName
# Start the agent for the admin session doing the install (other users get it at their next logon).
Start-Process -FilePath $pythonw -ArgumentList "`"$InstallDir\agent.py`" --port $Port"

Write-Host ""
Write-Host "Done. Dashboard:" -ForegroundColor Green
Write-Host "  http://localhost:$Port"
if ($zt) {
    foreach ($a in $zt) { Write-Host "  http://$(if ($a.IP -like '*:*') { "[$($a.IP)]" } else { $a.IP }):$Port   (ZeroTier, from your phone)" }
} else {
    $ips = (Get-NetIPAddress -AddressFamily IPv4 | Where-Object { $_.IPAddress -notlike "127.*" -and $_.IPAddress -notlike "169.254.*" }).IPAddress
    foreach ($ip in $ips) { Write-Host "  http://${ip}:$Port" }
}
Write-Host ""
Write-Host "Make sure the children's accounts are STANDARD users (not Administrators)."
Write-Host "Users already logged in will be tracked after they sign out and back in."
