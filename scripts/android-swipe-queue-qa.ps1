param(
    [string]$BaseUrl = "http://127.0.0.1:8080",
    [string]$PackageName = "com.pynapli.app",
    [string]$LaunchActivity = ".DefaultLauncherAlias",
    [string]$DeviceId = "",
    [string]$OutputRoot = "artifacts\android-swipe-queue-qa",
    [switch]$SkipSeed,
    [switch]$SkipBuild,
    [switch]$SkipInstall,
    [switch]$KeepAppData,
    [switch]$SkipSwipeStateReset,
    [int]$WaitSeconds = 35
)

$ErrorActionPreference = "Stop"

# Shared UI-hierarchy helpers. These used to be copy-pasted into every QA script, which is
# how android-tonight-qa.ps1 ended up with a stale copy of the Grid card detection.
Import-Module (Join-Path $PSScriptRoot "lib\PynapliQaUi.psm1") -Force -DisableNameChecking
Import-Module (Join-Path $PSScriptRoot "lib\PynapliQaDevice.psm1") -Force -DisableNameChecking
Import-Module (Join-Path $PSScriptRoot "lib\PynapliQaGolden.psm1") -Force -DisableNameChecking

function Get-ScreenHeight {
    param([xml]$Xml)

    $root = $Xml.SelectSingleNode("/hierarchy/node")
    if ($null -ne $root) {
        return (Get-Bounds $root).Bottom
    }

    $bottom = 0
    foreach ($node in $Xml.SelectNodes("//*[@bounds]")) {
        $bounds = Get-Bounds $node
        if ($bounds.Bottom -gt $bottom) {
            $bottom = $bounds.Bottom
        }
    }

    if ($bottom -le 0) {
        throw "Unable to determine Android screen height from UI hierarchy."
    }
    return $bottom
}

function Find-NodeByExactContentDescription {
    param(
        [xml]$Xml,
        [string]$Description
    )

    foreach ($node in Get-UiTextNodes $Xml) {
        if ($node.GetAttribute("content-desc") -eq $Description) {
            return $node
        }
    }

    return $null
}

function Wait-ForNoText {
    param(
        [string]$Text,
        [int]$TimeoutSeconds = $script:WaitSeconds
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $xml = Dump-Ui "wait_absent"
        if ($null -eq (Find-NodeByText $xml $Text)) {
            return $xml
        }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)

    $visible = (Get-VisibleTexts $xml) -join " | "
    throw "Timed out waiting for '$Text' to disappear. Visible text: $visible"
}

function Tap-ContentDescription {
    param(
        [string]$Description,
        [int]$TimeoutSeconds = $script:WaitSeconds
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $xml = Dump-Ui "wait_content_desc"
        $node = Find-NodeByExactContentDescription $xml $Description
        if ($null -ne $node) {
            $bounds = Get-Bounds $node
            Invoke-Adb @("shell", "input", "tap", "$($bounds.CenterX)", "$($bounds.CenterY)") | Out-Null
            Write-Host "[tap-desc] '$Description' at $($bounds.CenterX),$($bounds.CenterY)"
            return
        }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)

    $visible = (Get-VisibleTexts $xml) -join " | "
    throw "Timed out waiting for content description '$Description'. Visible text: $visible"
}

function Set-DebugSwipeFailures {
    param([int]$Count)

    $receiver = "$PackageName/.debug.DebugQaReceiver"
    $output = Invoke-Adb @(
        "shell",
        "am",
        "broadcast",
        "-f",
        "0x20",
        "-n",
        $receiver,
        "-a",
        "com.pynapli.app.DEBUG_SET_SWIPE_FAULT",
        "--ei",
        "fail_next_swipe_actions",
        "$Count"
    )
    Write-Host "[debug] next swipe action failures set to $Count"
    return ($output | Out-String).Trim()
}

function Reset-DevSwipeState {
    $docker = Get-Command docker -ErrorAction SilentlyContinue
    if ($null -eq $docker) {
        Write-Host "[warn] Docker was not found; server-side dev Swipe state was not reset."
        return
    }

    $emailHash = Get-Sha256Base64Url "dev@pynapli.local"
    $sql = @'
DO $$
DECLARE
    viewer_id uuid;
BEGIN
    SELECT id INTO viewer_id FROM users WHERE email_hash = '__EMAIL_HASH__';
    IF viewer_id IS NULL THEN
        RETURN;
    END IF;

    DELETE FROM matches
    WHERE user_a = viewer_id OR user_b = viewer_id;

    DELETE FROM swipe_actions
    WHERE actor_user_id = viewer_id OR target_user_id = viewer_id;

    DELETE FROM swipe_deck_sessions
    WHERE viewer_user_id = viewer_id;
END $$;
'@.Replace("__EMAIL_HASH__", $emailHash)

    $output = & $docker.Source exec pynapli-postgres psql -U pynapli -d pynapli_dev -v ON_ERROR_STOP=1 -c $sql 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "Dev Swipe state reset failed: $output"
    }
    Write-Host "[ok] Reset dev Swipe decisions, matches, and deck sessions for dev@pynapli.local"
}

function Start-App {
    $component = "$PackageName/$LaunchActivity"
    Invoke-Adb @("shell", "am", "start", "-n", $component) | Out-Null
    Write-Host "[step] Launched $component"
}

function Complete-LoginIfNeeded {
    $landing = Wait-ForAnyText @("Dev login", "Grid Load", "Dense", "Filters") 45
    if ($landing.Text -eq "Dev login") {
        $loginXml = Dump-Ui "00_login" -Keep
        Assert-NoAppErrorText $loginXml "Login"
        $script:Screenshots.login = Save-Screen "00_login"
        Tap-Text "Dev login"

        Wait-ForAnyText @("Allow Pynapli", "Don", "Current location", "Filters", "Grid Load", "Dense") 45 | Out-Null
        Start-Sleep -Milliseconds 1200
        $afterLoginXml = Dump-Ui "after_login"
        if ($null -ne (Find-NodeByText $afterLoginXml "Don")) {
            $promptXml = Dump-Ui "01_notification_prompt" -Keep
            Assert-NoAppErrorText $promptXml "Notification prompt"
            $script:Screenshots.notificationPrompt = Save-Screen "01_notification_prompt"
            Tap-Text "Don"
        }
    }
}

function Open-SwipeTab {
    Tap-BottomNavText "Swipe"
    Wait-ForAnyText @("For You", "You've seen everyone nearby") 45 | Out-Null
    Start-Sleep -Milliseconds 900
    $xml = Dump-Ui "swipe_ready"
    Assert-NoAppErrorText $xml "Swipe ready"
    Assert-TextPresent $xml "For You" "Swipe ready"
    Assert-TextPresent $xml "Like" "Swipe ready"
    Assert-TextPresent $xml "Pass" "Swipe ready"
    Assert-TextAbsent $xml "You've seen everyone nearby" "Swipe ready"
    return $xml
}

function Open-LinkedSwipeCards {
    # Tap-DeckTab, not Tap-Text: "Linked" is also a badge on linked-partner cards, so a substring
    # tap can hit the card instead of the tab before the tab row settles (see the module comment).
    Tap-DeckTab "Linked"
    $linkedWait = Wait-ForAnyText @("Maya", "Alex", "Jules", "Rae") 45
    Start-Sleep -Milliseconds 900
    $xml = Dump-Ui "03b_swipe_linked_images" -Keep
    Assert-NoAppErrorText $xml "Linked Swipe image card"
    Assert-TextPresent $xml "Linked" "Linked Swipe image card"
    Assert-TextPresent $xml $linkedWait.Text "Linked Swipe image card"
    return [pscustomobject]@{
        Xml = $xml
        DisplayName = $linkedWait.Text
    }
}

function Write-Summary {
    param([hashtable]$Summary)

    $path = Join-Path $script:ArtifactDir "qa-summary.json"
    $Summary | ConvertTo-Json -Depth 6 | Set-Content -Path $path -Encoding UTF8
    Write-Host ""
    Write-Host "Android Swipe queue QA passed."
    Write-Host "Artifact directory: $script:ArtifactDir"
    Write-Host "Summary: $path"
}

if (-not (Test-Path ".\gradlew.bat")) {
    throw "Run this script from the Pynapli repo root."
}

$script:Adb = Find-Adb
$script:DeviceId = Resolve-QaDeviceId -Adb $script:Adb -RequestedDeviceId $DeviceId
# API level in the banner: this suite ran only on android-36 for months with nothing in its
# output saying so, which is how an edge-to-edge defect stayed invisible on API <=34.
Write-Host "Device: $script:DeviceId (API $(Get-QaDeviceApiLevel -Adb $script:Adb -DeviceId $script:DeviceId))"

$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$script:ArtifactDir = Join-Path (Get-Location) (Join-Path $OutputRoot $timestamp)
# Hand the shared adb helpers their per-run state. Must follow the artifact-dir assignment.
Set-PynapliQaContext -Adb $script:Adb -DeviceId $script:DeviceId -ArtifactDir $script:ArtifactDir -WaitSeconds $WaitSeconds -DumpMaxAttempts 12
New-Item -ItemType Directory -Force -Path $script:ArtifactDir | Out-Null
$script:Screenshots = @{}

Write-Host "Pynapli Android Swipe Queue QA"
Write-Host "-----------------------------"
Write-Host "Device: $script:DeviceId"
Write-Host "ADB: $script:Adb"
Write-Host "Artifacts: $script:ArtifactDir"

try {
    $health = Invoke-RestMethod -Uri "$BaseUrl/health/deep" -TimeoutSec 5
    if ($health.status -ne "ok") {
        throw "Unexpected health status: $($health | ConvertTo-Json -Compress)"
    }
    Write-Host "[ok] Ktor health: $BaseUrl/health/deep"
} catch {
    throw "Ktor is not healthy at $BaseUrl/health/deep. Start the local server before running Android Swipe queue QA. $($_.Exception.Message)"
}

if (-not $SkipSeed) {
    Write-Host "[step] Seeding dev data and emulator location"
    & pwsh -NoProfile -ExecutionPolicy Bypass -File ".\scripts\seed-dev-data.ps1" -BaseUrl $BaseUrl -SetAndroidEmulatorLocation -DeviceId $script:DeviceId
    if ($LASTEXITCODE -ne 0) {
        throw "Dev data seed failed."
    }
}

if (-not $SkipSwipeStateReset) {
    Write-Host "[step] Resetting server-side dev Swipe state"
    Reset-DevSwipeState
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

if (-not $KeepAppData) {
    Write-Host "[step] Clearing app data for fresh login"
    Invoke-Adb @("shell", "pm", "clear", $PackageName) | Out-Null
}

Set-DebugSwipeFailures 0 | Out-Null
Start-App
Complete-LoginIfNeeded

Wait-ForAnyText @("Grid Load", "Dense", "Filters") 45 | Out-Null
$gridXml = Dump-Ui "02_grid_after_login" -Keep
Assert-NoAppErrorText $gridXml "Grid after login"
$script:Screenshots.gridAfterLogin = Save-Screen "02_grid_after_login"

$swipeXml = Open-SwipeTab
$script:Screenshots.swipeReady = Save-Screen "03_swipe_ready"

$linkedProof = Open-LinkedSwipeCards
$script:Screenshots.linkedImages = Save-Screen "03b_swipe_linked_images"

Set-DebugSwipeFailures 1 | Out-Null
Tap-ContentDescription "Like"
Wait-ForAnyText @("1 pending", "Swipe saved") 20 | Out-Null
Start-Sleep -Milliseconds 500
$pendingXml = Dump-Ui "04_swipe_pending_before_force_stop" -Keep
Assert-TextPresent $pendingXml "1 pending" "Pending Swipe before force-stop"
Assert-TextPresent $pendingXml "Swipe saved" "Pending Swipe before force-stop"
$script:Screenshots.pendingBeforeForceStop = Save-Screen "04_swipe_pending_before_force_stop"

Write-Host "[step] Force-stopping app to simulate process death"
Invoke-Adb @("shell", "am", "force-stop", $PackageName) | Out-Null
Start-Sleep -Seconds 1

Set-DebugSwipeFailures 1 | Out-Null
Start-App
Complete-LoginIfNeeded
Open-SwipeTab | Out-Null
Wait-ForAnyText @("1 pending", "Swipe saved") 20 | Out-Null
$restoredXml = Dump-Ui "05_swipe_pending_after_relaunch" -Keep
Assert-TextPresent $restoredXml "1 pending" "Restored pending Swipe after relaunch"
Assert-TextPresent $restoredXml "Swipe saved" "Restored pending Swipe after relaunch"
$script:Screenshots.pendingAfterRelaunch = Save-Screen "05_swipe_pending_after_relaunch"

Set-DebugSwipeFailures 0 | Out-Null
Tap-Text "Retry"
$clearedXml = Wait-ForNoText "1 pending" 30
Assert-NoAppErrorText $clearedXml "Swipe queue cleared"
$clearedXml.Save((Join-Path $script:ArtifactDir "06_swipe_queue_cleared.xml"))
$script:Screenshots.queueCleared = Save-Screen "06_swipe_queue_cleared"

Write-Summary @{
    passed = $true
    baseUrl = $BaseUrl
    deviceId = $script:DeviceId
    packageName = $PackageName
    apk = $apk.FullName
    proof = @{
        debugRecoverableFailure = $true
        pendingVisibleBeforeForceStop = $true
        pendingRestoredAfterRelaunch = $true
        retryClearedQueue = $true
        linkedImageCard = $true
        linkedImageCardName = $linkedProof.DisplayName
    }
    screenshots = $script:Screenshots
}
