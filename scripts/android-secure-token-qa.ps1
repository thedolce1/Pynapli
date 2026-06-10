# Connected-Android QA for Keystore-backed secure token storage (ONBOARDING_AUTH_SPEC
# "Secure token storage"). Proves on a real emulator/device that (1) a signed-in session
# survives force-stop + relaunch through the encrypted store, and (2) the on-disk SQLite
# database (incl. WAL) carries the `enc1:` ciphertext and NO plaintext token JSON.
# Requires a debug build (uses `run-as` to read the app database).
param(
    [string]$BaseUrl = "http://127.0.0.1:8080",
    [string]$PackageName = "com.pynapli.app",
    [string]$LaunchActivity = ".MainActivity",
    [string]$DeviceId = "",
    [string]$OutputRoot = "artifacts\android-secure-token-qa",
    [switch]$SkipSeed,
    [switch]$SkipBuild,
    [switch]$SkipInstall,
    [int]$WaitSeconds = 35
)

$ErrorActionPreference = "Stop"

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
                return [xml](Get-Content -Raw -Path $local)
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

function Get-VisibleTexts {
    param([xml]$Xml)

    @($Xml.SelectNodes("//*[@text or @content-desc]")) |
        ForEach-Object {
            $text = $_.GetAttribute("text")
            if ([string]::IsNullOrWhiteSpace($text)) {
                $text = $_.GetAttribute("content-desc")
            }
            $text
        } |
        Where-Object { -not [string]::IsNullOrWhiteSpace($_) } |
        Select-Object -Unique
}

function Find-NodeByText {
    param(
        [xml]$Xml,
        [string]$Text
    )

    foreach ($node in @($Xml.SelectNodes("//*[@text or @content-desc]"))) {
        if ($node.GetAttribute("text").Contains($Text) -or $node.GetAttribute("content-desc").Contains($Text)) {
            return $node
        }
    }

    return $null
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

function Find-NodeByExactText {
    param(
        [xml]$Xml,
        [string]$Text
    )

    foreach ($node in @($Xml.SelectNodes("//*[@text or @content-desc]"))) {
        if ($node.GetAttribute("text") -eq $Text -or $node.GetAttribute("content-desc") -eq $Text) {
            return $node
        }
    }

    return $null
}

function Dismiss-NotificationPromptIfPresent {
    # The OS POST_NOTIFICATIONS prompt can appear once the session authenticates
    # (Android 13+). Exact match only: the dialog title also contains "Allow".
    for ($attempt = 1; $attempt -le 3; $attempt++) {
        Start-Sleep -Milliseconds 1500
        $xml = Dump-Ui "notification_prompt_check"
        $allowButton = Find-NodeByExactText $xml "Allow"
        if ($null -eq $allowButton) {
            return
        }
        $bounds = $allowButton.GetAttribute("bounds")
        if ($bounds -notmatch "\[(\d+),(\d+)\]\[(\d+),(\d+)\]") {
            return
        }
        $centerX = [int](([int]$Matches[1] + [int]$Matches[3]) / 2)
        $centerY = [int](([int]$Matches[2] + [int]$Matches[4]) / 2)
        Invoke-Adb @("shell", "input", "tap", "$centerX", "$centerY") | Out-Null
        Write-Host "[note] OS notification prompt allowed (tap at $centerX,$centerY)."
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

$summaryPath = Join-Path $script:ArtifactDir "qa-summary.json"
(@{
    passed = $true
    baseUrl = $BaseUrl
    deviceId = $script:DeviceId
    packageName = $PackageName
    encryptedMarkerFound = $true
    plaintextTokenJsonFound = $false
    screenshots = @{
        signedIn = $signedInShot
        sessionRestored = $restoredShot
    }
} | ConvertTo-Json -Depth 4) | Set-Content -Path $summaryPath -Encoding UTF8

Write-Host ""
Write-Host "Android secure-token QA passed."
Write-Host "Artifact directory: $script:ArtifactDir"
Write-Host "Summary: $summaryPath"
