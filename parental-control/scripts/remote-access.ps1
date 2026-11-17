<#
.SYNOPSIS
  Helpers shared by install.ps1 and update.ps1 (dot-source this file):
  ZeroTier detection, the dashboard firewall rule, and the remote-access config.

  With ZeroTier joined on this PC, the dashboard is reachable from the ZeroTier
  subnet(s) only, on the ZeroTier adapter only, whatever Windows' network profile
  for it (ZeroTier networks are often "Public" or "Unidentified").
  Without ZeroTier (not installed, not joined, adapter without an address), the
  existing rule is kept as it is (the original rule: any address on Private (home)
  networks). Nothing here throws: a ZeroTier or firewall problem is reported as a
  warning and never stops an install or update.
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
    <# One object per address on a ZeroTier adapter: Alias, IP, Subnet (CIDR).
       Never throws: no ZeroTier, an adapter without an IP stack ("The specified
       interface was not found on the system"), or any other lookup error = no address. #>
    $adapters = @()
    try {
        $adapters = @(Get-NetAdapter -ErrorAction Stop |
            Where-Object { $_.InterfaceDescription -like "*ZeroTier*" -or $_.Name -like "ZeroTier*" })
    } catch {
        Write-Host "  could not list network adapters ($($_.Exception.Message)): treating ZeroTier as absent"
        return
    }
    foreach ($a in $adapters) {
        try {
            $ips = @(Get-NetIPAddress -InterfaceIndex $a.ifIndex -ErrorAction Stop)
        } catch {
            Write-Host "  ZeroTier adapter '$($a.Name)' has no usable address ($($_.Exception.Message)): skipped"
            continue
        }
        foreach ($ip in $ips) {
            $addr = ($ip.IPAddress -split "%")[0]
            if ($addr -like "fe80*" -or $addr -like "169.254.*") { continue }
            try {
                [pscustomobject]@{ Alias = $a.Name; IP = $addr; Subnet = Get-NetworkCidr $addr $ip.PrefixLength }
            } catch {
                Write-Host "  skipped ZeroTier address $addr ($($_.Exception.Message))"
            }
        }
    }
}

function Get-DashboardFirewallRules {
    try { @(Get-NetFirewallRule -DisplayName $FirewallRuleName -ErrorAction Stop) } catch { @() }
}

function New-PrivateFirewallRule([int]$Port) {
    New-NetFirewallRule -DisplayName $FirewallRuleName -Direction Inbound -Action Allow -Protocol TCP `
        -LocalPort $Port -Profile Private `
        -Description "managed by Parental Control; port=$Port; private networks" -ErrorAction Stop | Out-Null
}

function Set-DashboardFirewallRule([int]$Port) {
    <# With ZeroTier: makes the inbound rule ZeroTier-only, rewriting it only if the
       port, subnets or adapter names changed (the wanted state is kept in the rule's
       Description and compared).
       Without ZeroTier: keeps whatever rule exists; only creates the original
       Private-network rule if there is none at all.
       Never throws: on any firewall error the existing rule is left in place.
       Returns the ZeroTier addresses (empty if none). #>
    $zt = @(Get-ZeroTierAddresses)
    $existing = @(Get-DashboardFirewallRules)

    if (-not $zt) {
        if ($existing) {
            Write-Host "  no ZeroTier network found: keeping the existing firewall rule"
        } else {
            try {
                New-PrivateFirewallRule $Port
                Write-Host "  no ZeroTier network found and no firewall rule: created one for port $Port on Private networks"
            } catch {
                Write-Warning "could not create the firewall rule ($($_.Exception.Message)); the dashboard may only work from this PC"
            }
        }
        return @()
    }

    $subnets = @($zt.Subnet | Sort-Object -Unique)
    $aliases = @($zt.Alias | Sort-Object -Unique)
    $wanted = "managed by Parental Control; port=$Port; zerotier=$($subnets -join ','); adapters=$($aliases -join ',')"
    if ($existing.Count -eq 1 -and $existing[0].Description -eq $wanted) {
        Write-Host "  firewall rule unchanged ($wanted)"
        return $zt
    }
    try {
        # Create the new rule first (under a temporary name) so a failure never leaves no rule.
        $tmpName = "$FirewallRuleName (new)"
        New-NetFirewallRule -DisplayName $tmpName -Description $wanted -Direction Inbound -Action Allow `
            -Protocol TCP -LocalPort $Port -Profile Any -RemoteAddress $subnets -InterfaceAlias $aliases `
            -ErrorAction Stop | Out-Null
        $existing | Remove-NetFirewallRule -ErrorAction SilentlyContinue
        Get-NetFirewallRule -DisplayName $tmpName -ErrorAction Stop | Set-NetFirewallRule -NewDisplayName $FirewallRuleName -ErrorAction Stop
        Write-Host "  firewall: port $Port open to ZeroTier only: $($subnets -join ', ') on $($aliases -join ', ')"
        return $zt
    } catch {
        try { Get-NetFirewallRule -DisplayName "$FirewallRuleName (new)" -ErrorAction Stop | Remove-NetFirewallRule } catch {}
        Write-Warning "could not set the ZeroTier firewall rule ($($_.Exception.Message)); keeping the existing rule and settings"
        return @()  # don't switch the config to ZeroTier-only if the firewall isn't ready for it
    }
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
