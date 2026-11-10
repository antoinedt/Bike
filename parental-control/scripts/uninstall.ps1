#Requires -RunAsAdministrator
<#
.SYNOPSIS
  Removes Parental Control. Usage data is kept unless -RemoveData is given.
#>
param([switch]$RemoveData)
$ErrorActionPreference = "Continue"

$InstallDir = Join-Path $env:ProgramFiles "ParentalControl"
$DataDir    = Join-Path $env:ProgramData  "ParentalControl"

Stop-ScheduledTask -TaskName "ParentalControlService" -ErrorAction SilentlyContinue
Unregister-ScheduledTask -TaskName "ParentalControlService" -Confirm:$false -ErrorAction SilentlyContinue
Remove-ItemProperty -Path "HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Run" -Name "ParentalControlAgent" -ErrorAction SilentlyContinue
Get-NetFirewallRule -DisplayName "Parental Control dashboard" -ErrorAction SilentlyContinue | Remove-NetFirewallRule

# Stop the service and any running agents (python processes started from the install folder).
Get-CimInstance Win32_Process -Filter "Name='python.exe' OR Name='pythonw.exe'" |
    Where-Object { $_.CommandLine -like "*$InstallDir*" } |
    ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }

Remove-Item -Recurse -Force $InstallDir -ErrorAction SilentlyContinue
if ($RemoveData) { Remove-Item -Recurse -Force $DataDir -ErrorAction SilentlyContinue }

Write-Host "Parental Control removed." -ForegroundColor Green
if (-not $RemoveData) { Write-Host "Usage data kept in $DataDir (use -RemoveData to delete it)." }
