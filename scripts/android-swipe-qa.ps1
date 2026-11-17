param(
    [string]$BaseUrl = "http://127.0.0.1:8080",
    [string]$PackageName = "com.pynapli.app",
    [string]$LaunchActivity = ".DefaultLauncherAlias",
    [string]$DeviceId = "",
    [string]$OutputRoot = "artifacts\android-swipe-qa",
    [switch]$SkipSeed,
    [switch]$SkipBuild,
    [switch]$SkipInstall,
    [switch]$KeepAppData,
    [switch]$SkipSwipeStateReset,
    [string]$GoldenSpec = "scripts\android-swipe-qa-goldens.json",
    [switch]$SkipGoldenAssertions,
    [int]$WaitSeconds = 35
)

$ErrorActionPreference = "Stop"

# Shared UI-hierarchy helpers. These used to be copy-pasted into every QA script, which is
# how android-tonight-qa.ps1 ended up with a stale copy of the Grid card detection.
Import-Module (Join-Path $PSScriptRoot "lib\PynapliQaUi.psm1") -Force -DisableNameChecking
Import-Module (Join-Path $PSScriptRoot "lib\PynapliQaDevice.psm1") -Force -DisableNameChecking
Import-Module (Join-Path $PSScriptRoot "lib\PynapliQaGolden.psm1") -Force -DisableNameChecking

try {
    Add-Type -AssemblyName System.Drawing
} catch {
    throw "System.Drawing is required for Android Swipe screenshot signatures. Run this script on Windows with the local desktop tooling."
}

function Find-ContentDescriptionNodes {
    param(
        [xml]$Xml,
        [string]$Description,
        [int]$MinTop = 0,
        [int]$MaxTop = 0,
        [double]$MinLeftRatio = 0
    )

    $screen = Get-ScreenSize $Xml
    @(
        foreach ($node in Get-UiTextNodes $Xml) {
            if ($node.GetAttribute("content-desc") -ne $Description) {
                continue
            }

            $bounds = Get-Bounds $node
            if ($MinTop -gt 0 -and $bounds.Top -lt $MinTop) {
                continue
            }
            if ($MaxTop -gt 0 -and $bounds.Top -gt $MaxTop) {
                continue
            }
            if ($MinLeftRatio -gt 0 -and $bounds.Left -lt [int]($screen.Width * $MinLeftRatio)) {
                continue
            }

            [pscustomobject]@{
                Node = $node
                Bounds = $bounds
            }
        }
    )
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
        [int]$TimeoutSeconds = $script:WaitSeconds,
        [int]$MinTop = 0,
        [int]$MaxTop = 0,
        [double]$MinLeftRatio = 0
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $xml = Dump-Ui "wait_content_desc"
        $matches = @(Find-ContentDescriptionNodes $xml $Description $MinTop $MaxTop $MinLeftRatio)
        if ($matches.Count -gt 0) {
            $selected = $matches | Sort-Object { $_.Bounds.Top }, { $_.Bounds.Left } | Select-Object -First 1
            $bounds = $selected.Bounds
            Invoke-Adb @("shell", "input", "tap", "$($bounds.CenterX)", "$($bounds.CenterY)") | Out-Null
            Write-Host "[tap-desc] '$Description' at $($bounds.CenterX),$($bounds.CenterY)"
            return
        }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)

    $visible = (Get-VisibleTexts $xml) -join " | "
    throw "Timed out waiting for content description '$Description'. Visible text: $visible"
}

function Assert-GoldenStage {
    param(
        [string]$StageName,
        [xml]$Xml,
        [string]$ScreenshotPath = $null
    )

    if ($SkipGoldenAssertions -or $null -eq $script:GoldenSpecData) {
        return $null
    }

    $stageSpec = $script:GoldenSpecData.stages.$StageName
    if ($null -eq $stageSpec) {
        throw "Golden stage '$StageName' is missing from $GoldenSpec"
    }

    foreach ($text in @($stageSpec.requiredTexts)) {
        Assert-TextPresent $Xml $text "$StageName golden"
    }

    $visibleText = (Get-VisibleTexts $Xml) -join "`n"
    foreach ($text in @($stageSpec.forbiddenTexts)) {
        if (-not [string]::IsNullOrWhiteSpace($text) -and $visibleText.Contains($text)) {
            throw "$StageName golden displayed forbidden text '$text'. Visible text: $visibleText"
        }
    }

    if (-not [string]::IsNullOrWhiteSpace($ScreenshotPath)) {
        return Assert-ImageSignature $StageName $ScreenshotPath $stageSpec
    }

    return $null
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

    DELETE FROM top_pick_sessions
    WHERE user_id = viewer_id;

    DELETE FROM matches
    WHERE user_a = viewer_id OR user_b = viewer_id;

    DELETE FROM swipe_actions
    WHERE actor_user_id = viewer_id OR target_user_id = viewer_id;

    DELETE FROM swipe_deck_sessions
    WHERE viewer_user_id = viewer_id;

    UPDATE travel_locations
    SET status = 'cleared'
    WHERE user_id = viewer_id AND status = 'active';
END $$;
'@.Replace("__EMAIL_HASH__", $emailHash)

    $output = & $docker.Source exec pynapli-postgres psql -U pynapli -d pynapli_dev -v ON_ERROR_STOP=1 -c $sql 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "Dev Swipe state reset failed: $output"
    }
    Write-Host "[ok] Reset dev Swipe decisions, matches, deck sessions, Top Picks, and travel state for dev@pynapli.local"
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
    Wait-ForAnyText @("For You", "No more profiles nearby") 45 | Out-Null
    Start-Sleep -Milliseconds 1000
    $xml = Dump-Ui "swipe_ready"
    Assert-NoAppErrorText $xml "Swipe ready"
    Assert-TextPresent $xml "For You" "Swipe ready"
    Assert-TextPresent $xml "Like" "Swipe ready"
    Assert-TextPresent $xml "Pass" "Swipe ready"
    Assert-TextAbsent $xml "No more profiles nearby" "Swipe ready"
    Assert-TextAbsent $xml "No more recommendations match your filters" "Swipe ready"
    return $xml
}

# Tap a deck tab (For You / Linked / Age) by anchoring to the tab row instead of trusting the
# first substring match anywhere on screen.
#
# "Linked" also renders as a badge on linked-partner cards further down the deck, so a plain
# Tap-Text could tap the CARD whenever the tab row had not rendered yet. That was a real
# intermittent failure: the tap landed at y=852 (a card) instead of y=383 (the tab), which opened an
# unrelated surface and the run died on "Timed out waiting for one of: Maya, Alex, Jules, Rae".
#
# "For You" appears only in the tab row, so it defines where that row is; the requested tab is then
# matched on the same line. This also makes the function a real wait — it polls until the tab row
# exists, which the previous `Wait-ForAnyText @("For You", "Like")` never did, since both of those
# strings are permanently on screen.
function Get-MainSwipeSnapshot {
    param([xml]$Xml)

    $chromeTexts = @(
        "Swipe",
        "Psync",
        "Travel",
        "Filters",
        "For You",
        "Linked",
        "Age",
        "Rewind",
        "Pass",
        "Pineapple Like",
        "Like",
        "Grid",
        "Activity",
        "Messages",
        "Store",
        "Linked partner",
        "Verified"
    )

    $screen = Get-ScreenSize $Xml
    $minTop = [int]($screen.Height * 0.58)
    $maxTop = [int]($screen.Height * 0.82)

    $candidates = @(
        foreach ($node in Get-UiTextNodes $Xml) {
            $text = $node.GetAttribute("text")
            if ([string]::IsNullOrWhiteSpace($text)) {
                continue
            }

            if ($chromeTexts -contains $text) {
                continue
            }
            if ($text -match "^\s*(<=|<|>|>=)?\s*\d+(\.\d+)?\s*(m|km)\s*$") {
                continue
            }
            if ($text -match "^\d{2}$") {
                continue
            }
            if ($text -match "^\d+\s+pending$") {
                continue
            }
            if ($text -match "Swipe saved|Retry|No more|Illegal input|Request failed") {
                continue
            }

            $bounds = Get-Bounds $node
            if ($bounds.Top -lt $minTop -or $bounds.Top -gt $maxTop) {
                continue
            }

            [pscustomobject]@{
                Text = $text
                Top = $bounds.Top
                Left = $bounds.Left
                Right = $bounds.Right
                Bottom = $bounds.Bottom
            }
        }
    )

    @($candidates | Sort-Object Top, Left | Select-Object -First 8)
}

function Get-SnapshotTexts {
    param([object[]]$Snapshot)

    @($Snapshot | ForEach-Object { $_.Text })
}

function Assert-MainSwipeSnapshotPresent {
    param(
        [object[]]$Snapshot,
        [string]$Stage
    )

    if ($Snapshot.Count -eq 0) {
        throw "$Stage did not expose a detectable main Swipe card name. The card may be hidden, blank, or outside the expected deck area."
    }
}

function Wait-ForMainSwipeSnapshotChanged {
    param(
        [object[]]$Before,
        [string]$Stage,
        [int]$TimeoutSeconds = 10
    )

    $beforeText = (Get-SnapshotTexts $Before) -join "|"
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        Start-Sleep -Milliseconds 600
        $xml = Dump-Ui "wait_card_changed"
        Dismiss-MatchIfPresent $xml | Out-Null
        $xml = Dump-Ui "wait_card_changed_after_match"
        Assert-NoAppErrorText $xml $Stage
        $after = @(Get-MainSwipeSnapshot $xml)
        if ($after.Count -gt 0) {
            $afterText = (Get-SnapshotTexts $after) -join "|"
            if ($afterText -ne $beforeText) {
                return [pscustomobject]@{
                    Xml = $xml
                    Snapshot = $after
                }
            }
        }
    } while ((Get-Date) -lt $deadline)

    $visible = (Get-VisibleTexts $xml) -join " | "
    throw "$Stage did not advance the visible Swipe card after gesture. Before='$beforeText'. Visible text: $visible"
}

function Invoke-SwipeGesture {
    param(
        [ValidateSet("left", "right")]
        [string]$Direction,
        [xml]$Xml
    )

    $screen = Get-ScreenSize $Xml
    $startX = if ($Direction -eq "left") { [int]($screen.Width * 0.82) } else { [int]($screen.Width * 0.18) }
    $endX = if ($Direction -eq "left") { [int]($screen.Width * 0.16) } else { [int]($screen.Width * 0.84) }
    $y = [int]($screen.Height * 0.60)
    Invoke-Adb @("shell", "input", "swipe", "$startX", "$y", "$endX", "$y", "320") | Out-Null
    Write-Host "[gesture] $Direction swipe from $startX,$y to $endX,$y"
}

function Dismiss-MatchIfPresent {
    param([xml]$Xml)

    if ($null -eq (Find-NodeByText $Xml "Keep swiping")) {
        return $false
    }

    Tap-Text "Keep swiping" 5
    Start-Sleep -Milliseconds 800
    return $true
}

function Write-Summary {
    param([hashtable]$Summary)

    $path = Join-Path $script:ArtifactDir "qa-summary.json"
    $Summary | ConvertTo-Json -Depth 8 | Set-Content -Path $path -Encoding UTF8
    Write-Host ""
    Write-Host "Android Swipe visual QA passed."
    Write-Host "Artifact directory: $script:ArtifactDir"
    Write-Host "Summary: $path"
}

if (-not (Test-Path ".\gradlew.bat")) {
    throw "Run this script from the Pynapli repo root."
}

$script:GoldenSpecData = $null
if (-not $SkipGoldenAssertions) {
    if (-not (Test-Path $GoldenSpec)) {
        throw "Golden spec not found at $GoldenSpec. Pass -SkipGoldenAssertions to run without screenshot golden checks."
    }
    $script:GoldenSpecData = Get-Content -Raw -Path $GoldenSpec | ConvertFrom-Json
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
$script:VisualSignatures = @{}

Write-Host "Pynapli Android Swipe Visual QA"
Write-Host "------------------------------"
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
    throw "Ktor is not healthy at $BaseUrl/health/deep. Start the local server before running Android Swipe QA. $($_.Exception.Message)"
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

Start-App
Complete-LoginIfNeeded

Wait-ForAnyText @("Grid Load", "Dense", "Filters") 45 | Out-Null
$gridXml = Dump-Ui "02_grid_after_login" -Keep
Assert-NoAppErrorText $gridXml "Grid after login"
$script:Screenshots.gridAfterLogin = Save-Screen "02_grid_after_login"

$swipeXml = Open-SwipeTab
$initialXml = Dump-Ui "03_swipe_initial" -Keep
Assert-NoAppErrorText $initialXml "Initial Swipe"
$initialSnapshot = @(Get-MainSwipeSnapshot $initialXml)
Assert-MainSwipeSnapshotPresent $initialSnapshot "Initial Swipe"
$script:Screenshots.swipeInitial = Save-Screen "03_swipe_initial"
$script:VisualSignatures.swipeInitial = Assert-GoldenStage "swipeInitial" $initialXml $script:Screenshots.swipeInitial
Write-Host "[ok] Initial main Swipe card: $((Get-SnapshotTexts $initialSnapshot) -join ', ')"

Tap-Text "Filters"
Wait-ForAnyText @("These shape who shows up in your deck.", "Distance") | Out-Null
Start-Sleep -Milliseconds 600
$filtersXml = Dump-Ui "04_swipe_filters_sheet" -Keep
Assert-NoAppErrorText $filtersXml "Swipe filters sheet"
$script:Screenshots.filtersSheet = Save-Screen "04_swipe_filters_sheet"
$script:VisualSignatures.filtersSheet = Assert-GoldenStage "filtersSheet" $filtersXml $script:Screenshots.filtersSheet
# The profile<->filter coherence Gender/Orientation sections made the sheet taller, pushing the
# Intent and Style sections below the fold at open (same shape as the Grid QA fix in 44eb9fb).
# The golden's requiredTexts only cover the top-of-sheet sections; scroll down and assert the lower
# ones here so that half stays covered. Intent/Style were still listed in the golden despite this
# comment already claiming otherwise, which is what broke the stage — the script half of that earlier
# fix landed but the JSON half did not.
$filterSheetLowerXml = Scroll-SheetToText "Looking for"
Assert-NoAppErrorText $filterSheetLowerXml "Swipe filters sheet (lower)"
Assert-TextPresent $filterSheetLowerXml "Looking for" "Swipe filters sheet (lower)"
Assert-TextPresent $filterSheetLowerXml "Intent" "Swipe filters sheet (lower)"
$filterSheetStyleXml = Scroll-SheetToText "Style"
Assert-TextPresent $filterSheetStyleXml "Style" "Swipe filters sheet (lower)"
Invoke-Adb @("shell", "input", "keyevent", "KEYCODE_BACK") | Out-Null
Wait-ForAnyText @("For You", "Like") 20 | Out-Null

Tap-Text "Travel"
Wait-ForAnyText @("Travel mode", "Type a city") | Out-Null
Start-Sleep -Milliseconds 600
$travelXml = Dump-Ui "05_swipe_travel_sheet" -Keep
Assert-NoAppErrorText $travelXml "Travel sheet"
$script:Screenshots.travelSheet = Save-Screen "05_swipe_travel_sheet"
$script:VisualSignatures.travelSheet = Assert-GoldenStage "travelSheet" $travelXml $script:Screenshots.travelSheet
Invoke-Adb @("shell", "input", "keyevent", "KEYCODE_BACK") | Out-Null
Wait-ForAnyText @("For You", "Like") 20 | Out-Null

# No Wait-ForAnyText @("For You", "Like") here: both strings are permanently on screen, so that
# "wait" returned instantly and let the next tap fire mid-switch. Tap-DeckTab polls for the tab row
# itself, and the assertion below waits on partner names, which are real Linked-deck content.
Tap-DeckTab "Linked"
Wait-ForAnyText @("Maya", "Alex", "Jules", "Rae") 45 | Out-Null
Start-Sleep -Milliseconds 1000
$linkedXml = Dump-Ui "06_swipe_linked_deck" -Keep
Assert-NoAppErrorText $linkedXml "Linked Swipe deck"
$linkedSnapshot = @(Get-MainSwipeSnapshot $linkedXml)
Assert-MainSwipeSnapshotPresent $linkedSnapshot "Linked Swipe deck"
$script:Screenshots.linkedDeck = Save-Screen "06_swipe_linked_deck"
$script:VisualSignatures.linkedDeck = Assert-GoldenStage "linkedDeck" $linkedXml $script:Screenshots.linkedDeck
Write-Host "[ok] Linked main Swipe card: $((Get-SnapshotTexts $linkedSnapshot) -join ', ')"

# Slim header (#93) pulled the card and its top-right Linked-partner badge up.
Tap-ContentDescription "Linked partner" -MinTop 420 -MaxTop 1300 -MinLeftRatio 0.55
Wait-ForAnyText @("Linked with") 20 | Out-Null
Start-Sleep -Milliseconds 600
$partnerXml = Dump-Ui "07_swipe_linked_partner_preview" -Keep
Assert-NoAppErrorText $partnerXml "Linked partner preview"
$script:Screenshots.linkedPartnerPreview = Save-Screen "07_swipe_linked_partner_preview"
$script:VisualSignatures.linkedPartnerPreview = Assert-GoldenStage "linkedPartnerPreview" $partnerXml $script:Screenshots.linkedPartnerPreview
Tap-Text "Close"
Wait-ForAnyText @("Linked", "Like") 20 | Out-Null

# The Pineapple Like button opens its sheet (optional note; Intro Notes merged into it 2026-11-17).
Tap-ContentDescription "Pineapple Like"
Wait-ForAnyText @("Send Pineapple Like", "Add a note (optional)") 20 | Out-Null
Start-Sleep -Milliseconds 600
$pineappleXml = Dump-Ui "08_swipe_pineapple_like_sheet" -Keep
Assert-NoAppErrorText $pineappleXml "Pineapple Like sheet"
$script:Screenshots.pineappleLikeSheet = Save-Screen "08_swipe_pineapple_like_sheet"
$script:VisualSignatures.pineappleLikeSheet = Assert-GoldenStage "pineappleLikeSheet" $pineappleXml $script:Screenshots.pineappleLikeSheet
Tap-Text "Cancel"
Wait-ForAnyText @("Linked", "Like") 20 | Out-Null

$beforePassXml = Dump-Ui "before_pass_gesture"
$beforePass = @(Get-MainSwipeSnapshot $beforePassXml)
Assert-MainSwipeSnapshotPresent $beforePass "Pass gesture pre-state"
Invoke-SwipeGesture "left" $beforePassXml
$passResult = Wait-ForMainSwipeSnapshotChanged $beforePass "Pass gesture"
$script:Screenshots.afterPassGesture = Save-Screen "09_swipe_after_pass_gesture"
$script:VisualSignatures.afterPassGesture = Assert-GoldenStage "afterPassGesture" $passResult.Xml $script:Screenshots.afterPassGesture
Write-Host "[ok] Pass gesture advanced card to: $((Get-SnapshotTexts $passResult.Snapshot) -join ', ')"

$beforeLikeXml = Dump-Ui "before_like_gesture"
$beforeLike = @(Get-MainSwipeSnapshot $beforeLikeXml)
Assert-MainSwipeSnapshotPresent $beforeLike "Like gesture pre-state"
Invoke-SwipeGesture "right" $beforeLikeXml
$likeResult = Wait-ForMainSwipeSnapshotChanged $beforeLike "Like gesture"
$script:Screenshots.afterLikeGesture = Save-Screen "10_swipe_after_like_gesture"
$script:VisualSignatures.afterLikeGesture = Assert-GoldenStage "afterLikeGesture" $likeResult.Xml $script:Screenshots.afterLikeGesture
Write-Host "[ok] Like gesture advanced card to: $((Get-SnapshotTexts $likeResult.Snapshot) -join ', ')"

Write-Summary @{
    passed = $true
    baseUrl = $BaseUrl
    deviceId = $script:DeviceId
    packageName = $PackageName
    apk = $apk.FullName
    proof = @{
        initialMainCard = @(Get-SnapshotTexts $initialSnapshot)
        linkedMainCard = @(Get-SnapshotTexts $linkedSnapshot)
        passGestureAdvancedTo = @(Get-SnapshotTexts $passResult.Snapshot)
        likeGestureAdvancedTo = @(Get-SnapshotTexts $likeResult.Snapshot)
        filtersSheetCaptured = $true
        travelSheetCaptured = $true
        linkedPartnerPreviewCaptured = $true
        pineappleLikeSheetCaptured = $true
    }
    visualSignatures = $script:VisualSignatures
    screenshots = $script:Screenshots
}
