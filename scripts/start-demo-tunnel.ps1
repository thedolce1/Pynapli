# Starts a Cloudflare quick tunnel exposing the local Ktor dev server (localhost:8080)
# over public HTTPS, for partner/founder demo APK sessions (see scripts/seed-demo-data.ps1).
#
# The tunnel URL changes on every start (free quick tunnels are ephemeral): the staging demo
# APK bakes this URL in, so if you restart the tunnel you must rebuild the APK too.
# Stop the tunnel with:  Get-Process cloudflared | Stop-Process
param(
    [string]$LocalUrl = "http://localhost:8080",
    [string]$CloudflaredPath = "C:\Program Files (x86)\cloudflared\cloudflared.exe",
    [string]$LogPath = "C:\Users\Smaye\cloudflared-tunnel.log"
)

$ErrorActionPreference = "Stop"

if (-not (Test-Path $CloudflaredPath)) {
    throw "cloudflared not found at $CloudflaredPath (winget install Cloudflare.cloudflared)"
}

$existing = Get-Process cloudflared -ErrorAction SilentlyContinue
if ($existing) {
    Write-Host "cloudflared is already running (pid $($existing.Id -join ', ')). Checking the log for its URL..."
} else {
    Remove-Item $LogPath -ErrorAction SilentlyContinue
    Start-Process -FilePath $CloudflaredPath `
        -ArgumentList @('tunnel', '--url', $LocalUrl, '--no-autoupdate', '--logfile', $LogPath) `
        -WindowStyle Hidden
    Write-Host "cloudflared starting against $LocalUrl ..."
}

for ($i = 0; $i -lt 12; $i++) {
    Start-Sleep -Seconds 5
    if (Test-Path $LogPath) {
        $match = Select-String -Path $LogPath -Pattern 'https://[a-z0-9-]+\.trycloudflare\.com' -AllMatches |
            ForEach-Object { $_.Matches.Value } | Select-Object -First 1
        if ($match) {
            Write-Host ""
            Write-Host "TUNNEL URL: $match"
            Write-Host "(Keep this window's tunnel running; the demo APK is built against this exact URL.)"
            return
        }
    }
}

Write-Host "Tunnel URL not found after 60s. Log tail:"
if (Test-Path $LogPath) { Get-Content $LogPath -Tail 20 } else { Write-Host "(no log file was created)" }
