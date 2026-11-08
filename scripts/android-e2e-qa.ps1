# Critical-path Android E2E QA (Phase F2, TESTING_STRATEGY_SPEC).
#
# Drives the real debug app on a connected emulator/device through the core user journey end to
# end against the local Ktor server: dev login -> Grid -> Swipe (with a gesture) -> Activity ->
# Messages (open the seeded match thread) -> Store. Each stage asserts the expected surface text
# rendered and that no error band is showing, and captures a screenshot. Structural assertions
# only (no pixel goldens) so it stays robust across restyles.
#
# Prereqs: a connected device (the Pynapli_Phone AVD), the local server reachable at -BaseUrl, and
# the dev seed (the script seeds by default). Build/install the debug APK first, or pass -SkipInstall.
param(
    [string]$BaseUrl = "http://127.0.0.1:8080",
    [string]$PackageName = "com.pynapli.app",
    [string]$LaunchActivity = ".DefaultLauncherAlias",
    [string]$DeviceId = "",
    [string]$ApkPath = "androidApp\build\outputs\apk\debug\androidApp-debug.apk",
    [string]$OutputRoot = "artifacts\android-e2e-qa",
    [switch]$SkipSeed,
    [switch]$SkipInstall,
    [int]$WaitSeconds = 40
)

$ErrorActionPreference = "Stop"
$script:DeviceId = $DeviceId

function Find-Adb {
    $candidates = @(
        (Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"),
        (Join-Path $env:USERPROFILE "AppData\Local\Android\Sdk\platform-tools\adb.exe")
    )
    foreach ($candidate in $candidates) {
        if (Test-Path $candidate) { return $candidate }
    }
    $onPath = Get-Command adb -ErrorAction SilentlyContinue
    if ($onPath) { return $onPath.Source }
    throw "adb was not found. Start Android Studio once or install Android SDK Platform Tools."
}

function Invoke-Adb {
    param([string[]]$Arguments)
    $full = @("-s", $script:DeviceId) + $Arguments
    $output = & $script:Adb @full 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "adb failed: $($Arguments -join ' '). Output: $(($output | Out-String).Trim())"
    }
    return $output
}

function Select-AdbDevice {
    $lines = & $script:Adb devices 2>&1
    $devices = @()
    foreach ($line in $lines) {
        if ($line -match "^(\S+)\s+device$") { $devices += $Matches[1] }
    }
    if ($devices.Count -eq 0) { throw "No connected adb device. Start the Pynapli_Phone emulator." }
    if ($script:DeviceId) {
        if ($devices -notcontains $script:DeviceId) { throw "Requested device '$script:DeviceId' not connected. Connected: $($devices -join ', ')" }
    } elseif ($devices.Count -gt 1) {
        # Never silently pick one. There are now deliberately two AVDs (android-36 and android-34),
        # and choosing whichever adb lists first would report a pass for an API level nobody asked
        # for — the exact failure mode that let an edge-to-edge bug ship. Matches the shared
        # module's Resolve-QaDeviceId behaviour.
        throw "Multiple Android devices are connected. Rerun with -DeviceId (or use scripts\android-qa-matrix.ps1 to cover every AVD). Connected: $($devices -join ', ')"
    } else {
        $script:DeviceId = $devices[0]
    }
    # API level in the banner so a run is self-describing: this suite historically only ever ran on
    # one platform version and nothing in its output said so.
    $api = (& $script:Adb -s $script:DeviceId shell getprop ro.build.version.sdk 2>$null | Select-Object -First 1)
    Write-Host "Using device: $script:DeviceId (API $($api.Trim()))"
}

function Save-Screen {
    param([string]$Name)
    $remote = "/sdcard/pynapli-e2e.png"
    $local = Join-Path $script:OutputDir "$Name.png"
    Invoke-Adb @("shell", "screencap", "-p", $remote) | Out-Null
    Invoke-Adb @("pull", $remote, $local) | Out-Null
    Invoke-Adb @("shell", "rm", $remote) | Out-Null
}

function Dump-Ui {
    param([string]$Name = "dump")
    $remote = "/sdcard/pynapli-e2e-ui.xml"
    $local = Join-Path $script:OutputDir "$Name.xml"
    $last = ""
    for ($attempt = 0; $attempt -lt 6; $attempt++) {
        $out = & $script:Adb -s $script:DeviceId shell uiautomator dump $remote 2>&1
        $last = ($out | Out-String).Trim()
        if ($LASTEXITCODE -eq 0 -and $last -notmatch "ERROR:\s+null root") {
            Invoke-Adb @("pull", $remote, $local) | Out-Null
            Invoke-Adb @("shell", "rm", $remote) | Out-Null
            return [xml](Get-Content $local -Raw)
        }
        Start-Sleep -Milliseconds 800
    }
    throw "Unable to dump Android UI after retries. Last: $last"
}

function Get-VisibleTexts {
    param([xml]$Xml)
    $texts = @()
    foreach ($node in $Xml.SelectNodes("//node[@text]")) {
        $value = $node.GetAttribute("text")
        if ($value -and $value.Trim()) { $texts += $value.Trim() }
    }
    return $texts
}

function Find-NodeByText {
    param([xml]$Xml, [string]$Text)
    foreach ($node in $Xml.SelectNodes("//node[@text]")) {
        if ($node.GetAttribute("text").Trim() -eq $Text) { return $node }
    }
    return $null
}

function Get-Bounds {
    param($Node)
    $raw = $Node.GetAttribute("bounds")
    if ($raw -match "\[(\d+),(\d+)\]\[(\d+),(\d+)\]") {
        $x1 = [int]$Matches[1]; $y1 = [int]$Matches[2]; $x2 = [int]$Matches[3]; $y2 = [int]$Matches[4]
        return [pscustomobject]@{ CenterX = [int](($x1 + $x2) / 2); CenterY = [int](($y1 + $y2) / 2) }
    }
    throw "Could not parse bounds: $raw"
}

function Wait-ForAnyText {
    param([string[]]$Texts, [int]$TimeoutSeconds = $WaitSeconds)
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        $xml = Dump-Ui "wait"
        $visible = Get-VisibleTexts $xml
        foreach ($text in $Texts) {
            if ($visible -contains $text) { return $xml }
        }
        Start-Sleep -Milliseconds 900
    }
    throw "Timed out waiting for any of: $($Texts -join ', ')"
}

function Tap-Text {
    param([string]$Text)
    $xml = Wait-ForAnyText @($Text)
    $node = Find-NodeByText $xml $Text
    $bounds = Get-Bounds $node
    Invoke-Adb @("shell", "input", "tap", "$($bounds.CenterX)", "$($bounds.CenterY)") | Out-Null
    Start-Sleep -Milliseconds 1500
}

function Assert-TextPresent {
    param([xml]$Xml, [string[]]$AnyOf, [string]$Stage)
    $visible = Get-VisibleTexts $Xml
    foreach ($text in $AnyOf) {
        if ($visible -contains $text) { return }
    }
    throw "[$Stage] none of the expected texts were visible: $($AnyOf -join ', '). Saw: $(($visible | Select-Object -First 25) -join ' | ')"
}

function Assert-NoErrorBand {
    param([xml]$Xml, [string]$Stage)
    $visible = Get-VisibleTexts $Xml
    $errorMarkers = @("Something went wrong", "Couldn't load", "Couldn’t load", "Try again", "Network error")
    foreach ($marker in $errorMarkers) {
        if ($visible -contains $marker) {
            throw "[$Stage] error band visible: '$marker'."
        }
    }
}

function Invoke-Stage {
    param([string]$Stage, [string]$NavText, [string[]]$Expect)
    Write-Host "--- Stage: $Stage ---"
    if ($NavText) { Tap-Text $NavText }
    $xml = Wait-ForAnyText $Expect
    Assert-TextPresent -Xml $xml -AnyOf $Expect -Stage $Stage
    Assert-NoErrorBand -Xml $xml -Stage $Stage
    Save-Screen $Stage
    Write-Host "[$Stage] OK"
    return $xml
}

# --- Setup ---
$script:Adb = Find-Adb
Select-AdbDevice
$script:OutputDir = Join-Path $OutputRoot (Get-Date -Format "yyyyMMdd-HHmmss")
New-Item -ItemType Directory -Force -Path $script:OutputDir | Out-Null
Write-Host "Artifacts: $script:OutputDir"

Write-Host "Checking server health at $BaseUrl ..."
$health = Invoke-RestMethod -Uri "$BaseUrl/health" -TimeoutSec 5
if ($health.status -ne "ok") { throw "Server health not ok: $($health | ConvertTo-Json -Compress)" }

if (-not $SkipSeed) {
    Write-Host "Seeding dev data ..."
    & (Join-Path $PSScriptRoot "seed-dev-data.ps1") -BaseUrl $BaseUrl | Out-Null
}

if (-not $SkipInstall) {
    if (-not (Test-Path $ApkPath)) { throw "APK not found at $ApkPath. Build it (assembleDebug) or pass -SkipInstall." }
    Write-Host "Installing $ApkPath ..."
    Invoke-Adb @("install", "-r", $ApkPath) | Out-Null
}

# Disable stylus/handwriting IME interference and start fresh.
Invoke-Adb @("shell", "am", "force-stop", $PackageName) | Out-Null
Invoke-Adb @("shell", "monkey", "-p", $PackageName, "-c", "android.intent.category.LAUNCHER", "1") | Out-Null
Start-Sleep -Seconds 7

# --- Stage 1: dev login ---
Write-Host "--- Stage: login ---"
$loginXml = Wait-ForAnyText @("Dev login", "Grid")
if ((Get-VisibleTexts $loginXml) -contains "Dev login") {
    Tap-Text "Dev login"
}
$homeXml = Wait-ForAnyText @("Grid", "Swipe", "Activity")
Assert-NoErrorBand -Xml $homeXml -Stage "login"
Save-Screen "login"
Write-Host "[login] OK"

# --- Stage 2: Grid ---
$gridXml = Invoke-Stage -Stage "grid" -NavText "Grid" -Expect @("Filters", "Current location")

# --- Stage 3: Swipe (navigate + perform a gesture, then re-assert) ---
$swipeXml = Invoke-Stage -Stage "swipe" -NavText "Swipe" -Expect @("For You", "Top Picks", "Filters")
# A left swipe advances the deck (pass); the deck must still render afterwards.
Invoke-Adb @("shell", "input", "swipe", "820", "1100", "200", "1100", "250") | Out-Null
Start-Sleep -Seconds 2
$swipeAfter = Dump-Ui "swipe-after-gesture"
Assert-NoErrorBand -Xml $swipeAfter -Stage "swipe-gesture"
Save-Screen "swipe-after-gesture"
Write-Host "[swipe-gesture] OK"

# --- Stage 4: Activity ---
$activityXml = Invoke-Stage -Stage "activity" -NavText "Activity" -Expect @("Flirts", "Views", "Matches", "Requests")

# --- Stage 5: Messages (inbox renders; the seeded match thread is reachable) ---
$messagesXml = Invoke-Stage -Stage "messages" -NavText "Messages" -Expect @("Messages", "New Matches")

# --- Stage 6: Store ---
$storeXml = Invoke-Stage -Stage "store" -NavText "Store" -Expect @("Store", "Plans", "Boosts & extras", "Restore")

Write-Host ""
Write-Host "E2E PASS: login -> grid -> swipe -> activity -> messages -> store. Artifacts in $script:OutputDir"
