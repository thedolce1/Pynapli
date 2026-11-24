# Connected-Android QA for Keystore-backed secure token storage (ONBOARDING_AUTH_SPEC
# "Secure token storage"). Proves on a real emulator/device that (1) a signed-in session
# survives force-stop + relaunch through the encrypted store, and (2) the on-disk SQLite
# database (incl. WAL) carries the `enc1:` ciphertext and NO plaintext token JSON.
# Requires a debug build (uses `run-as` to read the app database).
param(
    [string]$BaseUrl = "http://127.0.0.1:8080",
    [string]$PackageName = "com.pynapli.app",
    [string]$LaunchActivity = ".DefaultLauncherAlias",
    [string]$DeviceId = "",
    [string]$OutputRoot = "artifacts\android-secure-token-qa",
    [switch]$SkipSeed,
    [switch]$SkipBuild,
    [switch]$SkipInstall,
    [int]$WaitSeconds = 35
)

$ErrorActionPreference = "Stop"

# Shared UI-hierarchy helpers. These used to be copy-pasted into every QA script, which is
# how android-tonight-qa.ps1 ended up with a stale copy of the Grid card detection.
Import-Module (Join-Path $PSScriptRoot "lib\PynapliQaUi.psm1") -Force -DisableNameChecking
Import-Module (Join-Path $PSScriptRoot "lib\PynapliQaDevice.psm1") -Force -DisableNameChecking

# POST_NOTIFICATIONS is granted up front (Grant-QaNotificationPermission), so the old
# "dismiss the OS prompt after login" poll could no longer fire and is gone.

function Assert-StillSignedInAfterBackgroundCheck {
    # A stored session now goes straight to the shell and GET /v1/me checks it in the background
    # (ONBOARDING_AUTH_SPEC, Returning members), so the Grid showing proves nothing on its own: a
    # terminal answer would bounce to sign-in a moment later. Let that check (and any refresh behind
    # its 401) finish, then look again.
    param([string]$Stage)

    Start-Sleep -Seconds 8
    $xml = Dump-Ui "${Stage}_after_background_check"
    if ($null -ne (Find-NodeByText $xml "Dev login")) {
        throw "${Stage}: the session was signed out once the background /me check finished."
    }
}

function Read-AppDatabaseText {
    # Debug builds allow run-as; cat the database plus WAL (recent writes usually
    # live in the WAL until a checkpoint) and inspect the bytes as ASCII text.
    $combined = ""
    foreach ($file in @("databases/pynapli.db", "databases/pynapli.db-wal")) {
        $local = Join-Path $script:ArtifactDir ([System.IO.Path]::GetFileName($file))
        & $script:Adb -s $script:DeviceId exec-out run-as $script:PackageNameValue cat $file > $local 2>$null
        if ((Test-Path $local) -and ((Get-Item $local).Length -gt 0)) {
            $bytes = [System.IO.File]::ReadAllBytes($local)
            $combined += [System.Text.Encoding]::ASCII.GetString($bytes)
        }
    }

    if ([string]::IsNullOrEmpty($combined)) {
        throw "Could not read the app database via run-as. Is this a debug build?"
    }

    return $combined
}

if (-not (Test-Path ".\gradlew.bat")) {
    throw "Run this script from the Pynapli repo root."
}

$script:Adb = Find-Adb
$script:DeviceId = Resolve-QaDeviceId -Adb $script:Adb -RequestedDeviceId $DeviceId
# API level in the banner: this suite ran only on android-36 for months with nothing in its
# output saying so, which is how an edge-to-edge defect stayed invisible on API <=34.
Write-Host "Device: $script:DeviceId (API $(Get-QaDeviceApiLevel -Adb $script:Adb -DeviceId $script:DeviceId))"
$script:PackageNameValue = $PackageName

$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$script:ArtifactDir = Join-Path (Get-Location) (Join-Path $OutputRoot $timestamp)
# Hand the shared adb helpers their per-run state. Must follow the artifact-dir assignment.
Set-PynapliQaContext -Adb $script:Adb -DeviceId $script:DeviceId -ArtifactDir $script:ArtifactDir -WaitSeconds $WaitSeconds -DumpMaxAttempts 12
New-Item -ItemType Directory -Force -Path $script:ArtifactDir | Out-Null

Write-Host "Pynapli Android secure-token QA"
Write-Host "-------------------------------"
Write-Host "Device: $script:DeviceId"
Write-Host "ADB: $script:Adb"
Write-Host "Artifacts: $script:ArtifactDir"

$health = Invoke-RestMethod -Uri "$BaseUrl/health/deep" -TimeoutSec 5
if ($health.status -ne "ok") {
    throw "Unexpected health status: $($health | ConvertTo-Json -Compress)"
}
Write-Host "[ok] Ktor health: $BaseUrl/health/deep"

if (-not $SkipSeed) {
    Write-Host "[step] Seeding dev data"
    & pwsh -NoProfile -ExecutionPolicy Bypass -File ".\scripts\seed-dev-data.ps1" -BaseUrl $BaseUrl -SetAndroidEmulatorLocation -DeviceId $script:DeviceId
    if ($LASTEXITCODE -ne 0) {
        throw "Dev data seed failed."
    }
}

if (-not $SkipBuild) {
    Write-Host "[step] Building androidApp debug APK"
    .\gradlew.bat :androidApp:assembleDebug --console=plain
    if ($LASTEXITCODE -ne 0) {
        throw "Android debug build failed."
    }
}

$apk = Get-ChildItem -Path ".\androidApp\build\outputs\apk\debug" -Filter "*.apk" |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1
if ($null -eq $apk) {
    throw "No debug APK found. Run without -SkipBuild first."
}

if (-not $SkipInstall) {
    Write-Host "[step] Installing $($apk.FullName)"
    Invoke-Adb @("install", "-r", $apk.FullName) | Out-Null
}

Write-Host "[step] Clearing app data for a fresh session"
Invoke-Adb @("shell", "pm", "clear", $PackageName) | Out-Null
Grant-QaNotificationPermission -PackageName $PackageName

$component = "$PackageName/$LaunchActivity"
Write-Host "[step] Launching $component"
Invoke-Adb @("shell", "am", "start", "-n", $component) | Out-Null

# --- Sign in via dev login ---
Wait-ForAnyText @("Dev login") | Out-Null
Tap-Text "Dev login"
Wait-ForAnyText @("Use current location", "Filters", "Grid") 45 | Out-Null
Start-Sleep -Milliseconds 1500
$signedInShot = Save-Screen "00_signed_in"

# --- On-disk proof: ciphertext present, plaintext token JSON absent ---
$databaseText = Read-AppDatabaseText
if (-not $databaseText.Contains("enc1:")) {
    throw "Database does not contain the expected 'enc1:' ciphertext marker."
}
if ($databaseText.Contains('"accessToken"') -or $databaseText.Contains('"refreshToken"')) {
    throw "Database still contains plaintext session token JSON."
}
Write-Host "[ok] On-disk session value is encrypted (enc1: present, no plaintext token JSON)."

# --- Restart proof: the encrypted session restores after force-stop ---
Write-Host "[step] Force-stop + relaunch to prove encrypted session restore"
Invoke-Adb @("shell", "am", "force-stop", $PackageName) | Out-Null
Start-Sleep -Milliseconds 1200
Invoke-Adb @("shell", "am", "start", "-n", $component) | Out-Null
$afterRestart = Wait-ForAnyText @("Use current location", "Filters", "Grid", "Dev login") 45
if ($afterRestart.Text -eq "Dev login") {
    throw "Session did not survive restart: the auth screen is showing again."
}
Assert-StillSignedInAfterBackgroundCheck "01_restart"
$restoredShot = Save-Screen "01_session_restored"

# --- Expired-access-token proof: the app refreshes instead of signing the user out ---
#
# The restart proof above relaunches immediately, comfortably inside the 15-minute
# AUTH_ACCESS_TOKEN_TTL, so it never exercised expiry. That gap let a real bug ship: nothing in the
# client ever called /v1/auth/refresh, so a relaunch after the TTL read 401 as terminal and cleared
# the session — users were signed out without ever logging out. This phase reproduces that in
# seconds instead of waiting out the real TTL.
#
# Method: expire the access token in Postgres while leaving the refresh token valid, then let the
# server's 5s in-memory access-session cache lapse so the next call actually re-reads the row and
# returns 401. A correct client refreshes and carries on.
Write-Host "[step] Expiring the access token server-side to prove the refresh path"
$expireSql = "UPDATE user_sessions SET access_expires_at = now() - interval '1 minute' WHERE revoked_at IS NULL AND refresh_expires_at > now();"
$expired = docker exec pynapli-postgres psql -U pynapli -d pynapli_dev -t -A -c $expireSql
if ($LASTEXITCODE -ne 0) {
    throw "Could not expire user_sessions in Postgres. Is the pynapli-postgres container running?"
}
Write-Host "[note] Postgres: $expired"

# Outlive AuthService's accessSessionCacheTtl (5s) so the cached entry cannot answer the next call.
Start-Sleep -Seconds 8

Invoke-Adb @("shell", "am", "force-stop", $PackageName) | Out-Null
Start-Sleep -Milliseconds 1200
Invoke-Adb @("shell", "am", "start", "-n", $component) | Out-Null
$afterExpiry = Wait-ForAnyText @("Use current location", "Filters", "Grid", "Dev login") 45
if ($afterExpiry.Text -eq "Dev login") {
    throw "Expired access token signed the user out. The client is not refreshing (see StoredSessionRefresher / the refresh-on-401 interceptor)."
}
Assert-StillSignedInAfterBackgroundCheck "02_expired_token"
$refreshedShot = Save-Screen "02_expired_token_refreshed"

# Server-side confirmation: a refresh rotates the session, so the row must be live again. Without
# this the UI check alone could pass on a cached screen.
$liveSessions = docker exec pynapli-postgres psql -U pynapli -d pynapli_dev -t -A -c "SELECT count(*) FROM user_sessions WHERE revoked_at IS NULL AND access_expires_at > now();"
if ([int]$liveSessions -lt 1) {
    throw "App stayed on screen but no session was rotated: expected a live access_expires_at row after refresh, found $liveSessions."
}
Write-Host "[ok] Expired access token was refreshed, not treated as a sign-out ($liveSessions live session row(s))."

# --- Revoked-session proof: a session that dies mid-use returns to sign-in without a relaunch ---
#
# The mirror image of the phase above. An *expired* access token is recoverable — the client
# refreshes and stays in. A *revoked* session is terminal — the refresh is rejected, and the app
# must drop back to sign-in instead of stranding the user in an authenticated shell where every
# request 401s (the state a moderation ban or a remote logout would otherwise leave them in).
#
# Crucially this does NOT relaunch: the app is already open on the Grid from the phase above. The
# session is revoked server-side, the 5s access-session cache lapses, and the app's own periodic
# inbox refresh trips the 401 -> terminal-refresh-rejection -> sign-out chain on its own.
Write-Host "[step] Revoking the session server-side to prove mid-session sign-out (no relaunch)"
$revokeSql = "UPDATE user_sessions SET revoked_at = now() WHERE revoked_at IS NULL;"
$revoked = docker exec pynapli-postgres psql -U pynapli -d pynapli_dev -t -A -c $revokeSql
if ($LASTEXITCODE -ne 0) {
    throw "Could not revoke user_sessions in Postgres. Is the pynapli-postgres container running?"
}
Write-Host "[note] Postgres: $revoked"

# Outlive the 5s access-session cache, then let the app's periodic refresh (inbox loop, ~15s) reach
# the server and get rejected. Wait-ForAnyText polls until the sign-in screen appears; a regression
# that leaves the app on the Grid makes it time out here with a clear message.
Start-Sleep -Seconds 8
$afterRevoke = Wait-ForAnyText @("Dev login") 60
if ($afterRevoke.Text -ne "Dev login") {
    throw "Revoked session did not return to sign-in: the app is still in the authenticated shell (see SessionInvalidationBus / AuthStateHolder.observeSessionInvalidations)."
}
$revokedShot = Save-Screen "03_revoked_signed_out"

# Server-side confirmation: every session is revoked and the rejected refresh created no new one.
$liveAfterRevoke = docker exec pynapli-postgres psql -U pynapli -d pynapli_dev -t -A -c "SELECT count(*) FROM user_sessions WHERE revoked_at IS NULL AND access_expires_at > now();"
if ([int]$liveAfterRevoke -ne 0) {
    throw "Expected no live session rows after revoke, found $liveAfterRevoke (a failed refresh must not mint a new session)."
}
Write-Host "[ok] Revoked session returned the user to sign-in mid-session (no live session rows remain)."

$summaryPath = Join-Path $script:ArtifactDir "qa-summary.json"
(@{
    passed = $true
    baseUrl = $BaseUrl
    deviceId = $script:DeviceId
    packageName = $PackageName
    encryptedMarkerFound = $true
    plaintextTokenJsonFound = $false
    expiredAccessTokenRefreshed = $true
    liveSessionsAfterRefresh = [int]$liveSessions
    revokedSessionSignedOut = $true
    liveSessionsAfterRevoke = [int]$liveAfterRevoke
    screenshots = @{
        signedIn = $signedInShot
        sessionRestored = $restoredShot
        expiredTokenRefreshed = $refreshedShot
        revokedSignedOut = $revokedShot
    }
} | ConvertTo-Json -Depth 4) | Set-Content -Path $summaryPath -Encoding UTF8

Write-Host ""
Write-Host "Android secure-token QA passed."
Write-Host "Artifact directory: $script:ArtifactDir"
Write-Host "Summary: $summaryPath"
