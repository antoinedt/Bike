<#
.SYNOPSIS
  Helpers shared by install.ps1 and update.ps1 (dot-source this file):
  ZeroTier detection, the dashboard firewall rule, and the remote-access config.

  With ZeroTier joined on this PC, the dashboard is reachable from the ZeroTier
  subnet(s) only, on the ZeroTier adapter only, whatever Windows' network profile
  for it (ZeroTier networks are often "Public" or "Unidentified").
  Without ZeroTier, it falls back to the original rule: any address on Private
  (home) networks.
#>

$FirewallRuleName = "Parental Control dashboard"

function Get-NetworkCidr([string]$Ip, [int]$Prefix) {
    $bytes = [Net.IPAddress]::Parse($Ip).GetAddressBytes()
    for ($i = 0; $i -lt $bytes.Length; $i++) {
        $bits = [Math]::Min(8, [Math]::Max(0, $Prefix - 8 * $i))
        $bytes[$i] = $bytes[$i] -band ((0xFF -shl (8 - $bits)) -band 0xFF)
    }
    "$([Net.IPAddress]::new($bytes))/$Prefix"
}

function Get-ZeroTierAddresses {
    <# One object per address on a ZeroTier adapter: Alias, IP, Subnet (CIDR). #>
    $adapters = Get-NetAdapter -ErrorAction SilentlyContinue |
        Where-Object { $_.InterfaceDescription -like "*ZeroTier*" -or $_.Name -like "ZeroTier*" }
    foreach ($a in $adapters) {
        Get-NetIPAddress -InterfaceIndex $a.ifIndex -ErrorAction SilentlyContinue |
            Where-Object { $_.IPAddress -notlike "fe80*" -and $_.IPAddress -notlike "169.254.*" } |
            ForEach-Object {
                [pscustomobject]@{
                    Alias  = $a.Name
                    IP     = ($_.IPAddress -split "%")[0]
                    Subnet = Get-NetworkCidr ($_.IPAddress -split "%")[0] $_.PrefixLength
                }
            }
    }
}

function Set-DashboardFirewallRule([int]$Port) {
    <# Creates or refreshes the inbound rule. Only touches it if something changed
       (port, ZeroTier subnets or adapter names): the wanted state is kept in the
       rule's Description and compared. Returns the ZeroTier addresses (may be empty). #>
    $zt = @(Get-ZeroTierAddresses)
    if ($zt) {
        $subnets = @($zt.Subnet | Sort-Object -Unique)
        $aliases = @($zt.Alias | Sort-Object -Unique)
        $wanted = "managed by Parental Control; port=$Port; zerotier=$($subnets -join ','); adapters=$($aliases -join ',')"
    } else {
        $wanted = "managed by Parental Control; port=$Port; private networks"
    }
    $existing = @(Get-NetFirewallRule -DisplayName $FirewallRuleName -ErrorAction SilentlyContinue)
    if ($existing.Count -eq 1 -and $existing[0].Description -eq $wanted) {
        Write-Host "  firewall rule unchanged ($wanted)"
        return $zt
    }
    $existing | Remove-NetFirewallRule -ErrorAction SilentlyContinue
    if ($zt) {
        New-NetFirewallRule -DisplayName $FirewallRuleName -Description $wanted -Direction Inbound -Action Allow `
            -Protocol TCP -LocalPort $Port -Profile Any -RemoteAddress $subnets -InterfaceAlias $aliases | Out-Null
        Write-Host "  firewall: port $Port open to ZeroTier only: $($subnets -join ', ') on $($aliases -join ', ')"
    } else {
        New-NetFirewallRule -DisplayName $FirewallRuleName -Description $wanted -Direction Inbound -Action Allow `
            -Protocol TCP -LocalPort $Port -Profile Private | Out-Null
        Write-Host "  firewall: no ZeroTier network found; port $Port open on Private networks"
    }
    return $zt
}

function Set-RemoteAccessConfig($Cfg, [bool]$ZeroTier) {
    <# With ZeroTier: listen on all interfaces (the agents need 127.0.0.1 and ZeroTier
       may come up after the service, so binding to the ZeroTier IP alone would break
       both) and only answer localhost + ZeroTier clients (allow_from = "zerotier").
       Without ZeroTier: leave the config alone. Returns $true if $Cfg changed. #>
    if (-not $ZeroTier) {
        if (@($Cfg.allow_from) -contains "zerotier") {
            Write-Warning "config allows only ZeroTier clients but no ZeroTier network was found: the dashboard is reachable from this PC only until ZeroTier is back."
        }
        return $false
    }
    $changed = $false
    if ($Cfg.PSObject.Properties.Name -notcontains "bind" -or $Cfg.bind -ne "0.0.0.0") {
        $Cfg | Add-Member -NotePropertyName bind -NotePropertyValue "0.0.0.0" -Force
        $changed = $true
    }
    if (@($Cfg.allow_from) -notcontains "zerotier") {
        $Cfg | Add-Member -NotePropertyName allow_from -NotePropertyValue @("zerotier") -Force
        $changed = $true
    }
    if ($changed) { Write-Host "  config: bind 0.0.0.0, allow_from = zerotier (+ localhost)" }
    return $changed
}
