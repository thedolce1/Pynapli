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
Import-Module (Join-Path $PSScriptRoot "lib\PynapliQaUi.psm1") -Force

function Find-Adb {
    $command = Get-Command "adb" -ErrorAction SilentlyContinue
    if ($null -ne $command) {
        return $command.Source
    }

    $sdkCandidates = @(
        $env:ANDROID_HOME,
        $env:ANDROID_SDK_ROOT,
        (Join-Path $env:LOCALAPPDATA "Android\Sdk")
    ) | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }

    foreach ($sdk in $sdkCandidates) {
        $candidate = Join-Path $sdk "platform-tools\adb.exe"
        if (Test-Path $candidate) {
            return $candidate
        }
    }

    throw "adb was not found. Start Android Studio once or install Android SDK Platform Tools."
}

function Invoke-Adb {
    param([string[]]$Arguments)

    $output = $null
    if ([string]::IsNullOrWhiteSpace($script:DeviceId)) {
        $output = & $script:Adb @Arguments 2>&1
    } else {
        $output = & $script:Adb -s $script:DeviceId @Arguments 2>&1
    }

    if ($LASTEXITCODE -ne 0) {
        throw "adb failed: $($Arguments -join ' '). Output: $(($output | Out-String).Trim())"
    }

    return $output
}

function Select-AdbDevice {
    $lines = & $script:Adb devices
    if ($LASTEXITCODE -ne 0) {
        throw "Unable to list adb devices."
    }

    $devices = @(
        $lines |
            Where-Object { $_ -match "^\S+\s+device$" } |
            ForEach-Object { ($_ -split "\s+")[0] }
    )

    if (-not [string]::IsNullOrWhiteSpace($script:DeviceId)) {
        if ($devices -notcontains $script:DeviceId) {
            throw "Requested adb device '$script:DeviceId' is not connected. Connected devices: $($devices -join ', ')"
        }
        return
    }

    if ($devices.Count -eq 0) {
        throw "No connected Android emulator/device found. Start an emulator or connect a device and retry."
    }

    if ($devices.Count -gt 1) {
        throw "Multiple Android devices are connected. Rerun with -DeviceId. Connected devices: $($devices -join ', ')"
    }

    $script:DeviceId = $devices[0]
}

function Save-Screen {
    param([string]$Name)

    $remote = "/sdcard/$Name.png"
    $local = Join-Path $script:ArtifactDir "$Name.png"
    Invoke-Adb @("shell", "screencap", "-p", $remote) | Out-Null
    Invoke-Adb @("pull", $remote, $local) | Out-Null
    Invoke-Adb @("shell", "rm", $remote) | Out-Null
    return (Resolve-Path $local).Path
}

function Dump-Ui {
    param([string]$Name)

    $remote = "/sdcard/window.xml"
    $local = Join-Path $script:ArtifactDir "$Name.xml"
    $lastDumpOutput = ""

    for ($attempt = 1; $attempt -le 5; $attempt++) {
        $dumpOutput = & $script:Adb -s $script:DeviceId shell uiautomator dump $remote 2>&1
        $dumpExit = $LASTEXITCODE
        $lastDumpOutput = ($dumpOutput | Out-String).Trim()

        if ($dumpExit -eq 0 -and $lastDumpOutput -notmatch "ERROR:\s+null root") {
            & $script:Adb -s $script:DeviceId pull $remote $local 2>$null | Out-Null
            if ($LASTEXITCODE -ne 0 -or -not (Test-Path $local)) {
                throw "Unable to pull Android UI hierarchy."
            }

            try {
                $parsed = [xml](Get-Content -Raw -Path $local)
                # Heavy host load can pop the system "isn't responding" ANR over the app mid-render.
                # Tap "Wait" and re-dump so callers never see the ANR frame instead of the real screen.
                $anrNode = $parsed.SelectNodes("//node[contains(@text,'responding') or contains(@content-desc,'responding')]") | Select-Object -First 1
                if ($null -ne $anrNode) {
                    $waitNode = $parsed.SelectNodes("//node[@text='Wait' or @content-desc='Wait']") | Select-Object -First 1
                    if ($null -ne $waitNode -and $waitNode.GetAttribute("bounds") -match "\[(\d+),(\d+)\]\[(\d+),(\d+)\]") {
                        $tapX = [int](([int]$Matches[1] + [int]$Matches[3]) / 2)
                        $tapY = [int](([int]$Matches[2] + [int]$Matches[4]) / 2)
                        & $script:Adb -s $script:DeviceId shell input tap $tapX $tapY 2>$null | Out-Null
                        Write-Host "[tap] 'Wait (ANR)' at $tapX,$tapY"
                        Start-Sleep -Milliseconds 1200
                        continue
                    }
                }
                return $parsed
            } catch {
                if ($attempt -eq 5) {
                    throw "Unable to parse Android UI hierarchy at $local"
                }
            }
        }

        Start-Sleep -Milliseconds 300
    }

    throw "Unable to dump Android UI hierarchy after retries. Last output: $lastDumpOutput"
}

function Wait-ForAnyText {
    param(
        [string[]]$Texts,
        [int]$TimeoutSeconds = $script:WaitSeconds
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $xml = Dump-Ui "wait"
        foreach ($text in $Texts) {
            if ($null -ne (Find-NodeByText $xml $text)) {
                return [pscustomobject]@{
                    Text = $text
                    Xml = $xml
                }
            }
        }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)

    $visible = (Get-VisibleTexts $xml) -join " | "
    throw "Timed out waiting for one of: $($Texts -join ', '). Visible text: $visible"
}

function Tap-Text {
    param([string]$Text)

    $wait = Wait-ForAnyText @($Text)
    $node = Find-NodeByText $wait.Xml $Text
    $bounds = $node.GetAttribute("bounds")
    if ($bounds -notmatch "\[(\d+),(\d+)\]\[(\d+),(\d+)\]") {
        throw "Invalid node bounds: $bounds"
    }
    $centerX = [int](([int]$Matches[1] + [int]$Matches[3]) / 2)
    $centerY = [int](([int]$Matches[2] + [int]$Matches[4]) / 2)
    Invoke-Adb @("shell", "input", "tap", "$centerX", "$centerY") | Out-Null
    Write-Host "[tap] '$Text' at $centerX,$centerY"
}

function Dismiss-NotificationPromptIfPresent {
    # The OS POST_NOTIFICATIONS prompt (Android 13+) appears only AFTER the session
    # authenticates, which lags the login tap by a network round-trip. Poll for it (don't
    # bail on the first miss) so we catch it whenever it appears; tap Allow and return, or
    # give up after the window if it never shows. Exact match: the dialog title also has "Allow".
    for ($attempt = 1; $attempt -le 10; $attempt++) {
        Start-Sleep -Milliseconds 1500
        $xml = Dump-Ui "notification_prompt_check"
        $allowButton = Find-NodeByExactText $xml "Allow"
        if ($null -eq $allowButton) {
            continue
        }
        $bounds = $allowButton.GetAttribute("bounds")
        if ($bounds -notmatch "\[(\d+),(\d+)\]\[(\d+),(\d+)\]") {
            return
        }
        $centerX = [int](([int]$Matches[1] + [int]$Matches[3]) / 2)
        $centerY = [int](([int]$Matches[2] + [int]$Matches[4]) / 2)
        Invoke-Adb @("shell", "input", "tap", "$centerX", "$centerY") | Out-Null
        Write-Host "[note] OS notification prompt allowed (tap at $centerX,$centerY)."
        return
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
$script:DeviceId = $DeviceId
$script:PackageNameValue = $PackageName
Select-AdbDevice

$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$script:ArtifactDir = Join-Path (Get-Location) (Join-Path $OutputRoot $timestamp)
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
    & pwsh -NoProfile -ExecutionPolicy Bypass -File ".\scripts\seed-dev-data.ps1" -BaseUrl $BaseUrl -SetAndroidEmulatorLocation
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

$component = "$PackageName/$LaunchActivity"
Write-Host "[step] Launching $component"
Invoke-Adb @("shell", "am", "start", "-n", $component) | Out-Null

# --- Sign in via dev login ---
Wait-ForAnyText @("Dev login") | Out-Null
Tap-Text "Dev login"
Dismiss-NotificationPromptIfPresent
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
Dismiss-NotificationPromptIfPresent
$afterRestart = Wait-ForAnyText @("Use current location", "Filters", "Grid", "Dev login") 45
if ($afterRestart.Text -eq "Dev login") {
    throw "Session did not survive restart: the auth screen is showing again."
}
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
Dismiss-NotificationPromptIfPresent
$afterExpiry = Wait-ForAnyText @("Use current location", "Filters", "Grid", "Dev login") 45
if ($afterExpiry.Text -eq "Dev login") {
    throw "Expired access token signed the user out. The client is not refreshing (see StoredSessionRefresher / the refresh-on-401 interceptor)."
}
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
