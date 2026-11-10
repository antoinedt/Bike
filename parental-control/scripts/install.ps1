#Requires -RunAsAdministrator
<#
.SYNOPSIS
  Installs Parental Control on this PC.

  * copies the program to C:\Program Files\ParentalControl (standard users can't modify it)
  * creates C:\ProgramData\ParentalControl, readable only by SYSTEM and Administrators
  * registers the service as a scheduled task running as SYSTEM at boot (auto-restart)
  * registers the agent to start at every user logon (HKLM Run key)
  * opens the dashboard port in the Windows firewall (Private networks only)

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File scripts\install.ps1
  powershell -ExecutionPolicy Bypass -File scripts\install.ps1 -Port 9000
#>
param(
    [int]$Port = 8765
)
$ErrorActionPreference = "Stop"

$InstallDir = Join-Path $env:ProgramFiles "ParentalControl"
$DataDir    = Join-Path $env:ProgramData  "ParentalControl"
$TaskName   = "ParentalControlService"
$RunKey     = "HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Run"
$SrcDir     = Join-Path (Split-Path -Parent $PSScriptRoot) "src"

# ---------------------------------------------------------------- Python ---
Write-Host "Looking for Python..."
$python = $null
$py = Get-Command py.exe -ErrorAction SilentlyContinue
if ($py) { $python = (& $py.Source -3 -c "import sys; print(sys.executable)" 2>$null) }
if (-not $python) {
    $p = Get-Command python.exe -ErrorAction SilentlyContinue
    # Skip the Microsoft Store "python.exe" stub in WindowsApps.
    if ($p -and $p.Source -notlike "*WindowsApps*") { $python = (& $p.Source -c "import sys; print(sys.executable)" 2>$null) }
}
if ($python) { $python = "$python".Trim() }
if (-not $python) {
    throw "Python 3.9+ not found. Install it from python.org with 'Install for all users' ticked, then re-run."
}
if ($python -like "$env:SystemDrive\Users\*") {
    throw "Python is installed in a user profile ($python). Child accounts cannot run it from there. " +
          "Reinstall Python with 'Install for all users' (it goes to C:\Program Files\Python3xx) and re-run."
}
$pythonw = Join-Path (Split-Path $python) "pythonw.exe"
if (-not (Test-Path $pythonw)) { throw "pythonw.exe not found next to $python" }
& $python -c "import sys; sys.exit(0 if sys.version_info >= (3, 9) else 1)"
if ($LASTEXITCODE -ne 0) { throw "Python 3.9 or newer is required ($python is too old)." }
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
Write-Host "Opening TCP port $Port on Private networks..."
Get-NetFirewallRule -DisplayName "Parental Control dashboard" -ErrorAction SilentlyContinue | Remove-NetFirewallRule
New-NetFirewallRule -DisplayName "Parental Control dashboard" -Direction Inbound -Protocol TCP `
    -LocalPort $Port -Action Allow -Profile Private | Out-Null

# ----------------------------------------------------------------- start ---
Start-ScheduledTask -TaskName $TaskName
# Start the agent for the admin session doing the install (other users get it at their next logon).
Start-Process -FilePath $pythonw -ArgumentList "`"$InstallDir\agent.py`" --port $Port"

$ips = (Get-NetIPAddress -AddressFamily IPv4 | Where-Object { $_.IPAddress -notlike "127.*" -and $_.IPAddress -notlike "169.254.*" }).IPAddress
Write-Host ""
Write-Host "Done. Dashboard:" -ForegroundColor Green
Write-Host "  http://localhost:$Port"
foreach ($ip in $ips) { Write-Host "  http://${ip}:$Port" }
Write-Host ""
Write-Host "Make sure the children's accounts are STANDARD users (not Administrators)."
Write-Host "Users already logged in will be tracked after they sign out and back in."
