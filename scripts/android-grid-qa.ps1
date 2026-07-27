param(
    [string]$BaseUrl = "http://127.0.0.1:8080",
    [string]$PackageName = "com.pynapli.app",
    [string]$LaunchActivity = ".DefaultLauncherAlias",
    [string]$DeviceId = "",
    [string]$OutputRoot = "artifacts\android-grid-qa",
    [switch]$SkipSeed,
    [switch]$SkipBuild,
    [switch]$SkipInstall,
    [switch]$KeepAppData,
    [string]$GoldenSpec = "scripts\android-grid-qa-goldens.json",
    [switch]$SkipGoldenAssertions,
    [int]$WaitSeconds = 35
)

$ErrorActionPreference = "Stop"

# Shared UI-hierarchy helpers. These used to be copy-pasted into every QA script, which is
# how android-tonight-qa.ps1 ended up with a stale copy of the Grid card detection.
Import-Module (Join-Path $PSScriptRoot "lib\PynapliQaUi.psm1") -Force -DisableNameChecking
Import-Module (Join-Path $PSScriptRoot "lib\PynapliQaDevice.psm1") -Force -DisableNameChecking

try {
    Add-Type -AssemblyName System.Drawing
} catch {
    throw "System.Drawing is required for Android Grid screenshot signatures. Run this script on Windows with the local desktop tooling."
}

function Get-ImageSignature {
    param([string]$Path)

    $bitmap = [System.Drawing.Bitmap]::new($Path)
    $buckets = @{}
    try {
        $stepX = [Math]::Max(1, [int][Math]::Floor($bitmap.Width / 96))
        $stepY = [Math]::Max(1, [int][Math]::Floor($bitmap.Height / 160))
        $sampleCount = 0
        $dark = 0
        $bright = 0
        $nonBlack = 0
        $accentYellow = 0

        for ($y = 0; $y -lt $bitmap.Height; $y += $stepY) {
            for ($x = 0; $x -lt $bitmap.Width; $x += $stepX) {
                $color = $bitmap.GetPixel($x, $y)
                $sampleCount += 1
                $luma = (0.2126 * $color.R) + (0.7152 * $color.G) + (0.0722 * $color.B)
                if ($luma -lt 45) { $dark += 1 }
                if ($luma -gt 210) { $bright += 1 }
                if ($luma -gt 15) { $nonBlack += 1 }
                if ($color.R -gt 200 -and $color.G -gt 150 -and $color.B -lt 90) { $accentYellow += 1 }

                $bucket = "$([int]($color.R / 32)):$([int]($color.G / 32)):$([int]($color.B / 32))"
                $buckets[$bucket] = $true
            }
        }

        return [pscustomobject]@{
            width = $bitmap.Width
            height = $bitmap.Height
            sampleCount = $sampleCount
            darkRatio = [Math]::Round($dark / [double]$sampleCount, 4)
            brightRatio = [Math]::Round($bright / [double]$sampleCount, 4)
            nonBlackRatio = [Math]::Round($nonBlack / [double]$sampleCount, 4)
            accentYellowRatio = [Math]::Round($accentYellow / [double]$sampleCount, 4)
            colorBucketCount = $buckets.Count
        }
    } finally {
        $bitmap.Dispose()
    }
}

function Assert-ImageSignature {
    param(
        [string]$StageName,
        [string]$ScreenshotPath,
        $StageSpec
    )

    if ($null -eq $StageSpec.image) {
        return $null
    }

    $signature = Get-ImageSignature $ScreenshotPath
    $checks = @{
        minWidth = "width"
        minHeight = "height"
        minDarkRatio = "darkRatio"
        maxBrightRatio = "brightRatio"
        minNonBlackRatio = "nonBlackRatio"
        minAccentYellowRatio = "accentYellowRatio"
        minColorBucketCount = "colorBucketCount"
    }

    foreach ($check in $checks.GetEnumerator()) {
        $expected = $StageSpec.image.($check.Key)
        if ($null -eq $expected) {
            continue
        }

        $actual = $signature.($check.Value)
        if ($check.Key.StartsWith("max")) {
            if ([double]$actual -gt [double]$expected) {
                throw "$StageName screenshot signature exceeded $($check.Key): actual=$actual expectedMax=$expected path=$ScreenshotPath"
            }
        } else {
            if ([double]$actual -lt [double]$expected) {
                throw "$StageName screenshot signature missed $($check.Key): actual=$actual expectedMin=$expected path=$ScreenshotPath"
            }
        }
    }

    return $signature
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

    if ($null -ne $stageSpec.minGridCards) {
        $cards = @(Get-GridCardSnapshot $Xml ([int]$stageSpec.minGridCards))
        if ($cards.Count -lt [int]$stageSpec.minGridCards) {
            throw "$StageName golden expected at least $($stageSpec.minGridCards) Grid cards but found $($cards.Count)."
        }
    }

    if (-not [string]::IsNullOrWhiteSpace($ScreenshotPath)) {
        return Assert-ImageSignature $StageName $ScreenshotPath $stageSpec
    }

    return $null
}

function Write-Summary {
    param([hashtable]$Summary)

    $path = Join-Path $script:ArtifactDir "qa-summary.json"
    $Summary | ConvertTo-Json -Depth 6 | Set-Content -Path $path -Encoding UTF8
    Write-Host ""
    Write-Host "Android Grid QA passed."
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

Write-Host "Pynapli Android Grid QA"
Write-Host "-----------------------"
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
    throw "Ktor is not healthy at $BaseUrl/health/deep. Start the local server before running Android Grid QA. $($_.Exception.Message)"
}

if (-not $SkipSeed) {
    Write-Host "[step] Seeding dev Grid data and emulator location"
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

if (-not $KeepAppData) {
    Write-Host "[step] Clearing app data for fresh login"
    Invoke-Adb @("shell", "pm", "clear", $PackageName) | Out-Null
}

$component = "$PackageName/$LaunchActivity"
Write-Host "[step] Launching $component"
Invoke-Adb @("shell", "am", "start", "-n", $component) | Out-Null

Wait-ForAnyText @("Dev login") | Out-Null
$loginXml = Dump-Ui "00_login" -Keep
Assert-NoAppErrorText $loginXml "Login"
$loginShot = Save-Screen "00_login"
Tap-Text "Dev login"

Wait-ForAnyText @("Allow Pynapli", "Don", "Current location", "Filters", "Grid Load", "Dense") 45 | Out-Null
Start-Sleep -Milliseconds 1200
$afterLoginXml = Dump-Ui "after_login"
if ($null -ne (Find-NodeByText $afterLoginXml "Don")) {
    $promptXml = Dump-Ui "01_notification_prompt" -Keep
    Assert-NoAppErrorText $promptXml "Notification prompt"
    $promptShot = Save-Screen "01_notification_prompt"
    Tap-Text "Don"
} else {
    $promptShot = $null
}

Wait-ForAnyText @("Grid Load", "Dense") 45 | Out-Null
$gridXml = Dump-Ui "02_grid_initial" -Keep
Assert-NoAppErrorText $gridXml "Initial Grid"
Assert-TextPresent $gridXml "Filters" "Initial Grid"
$firstCard = Get-FirstGridCardNode $gridXml
$firstCardText = $firstCard.GetAttribute("text")
$initialGridCards = @(Get-GridCardSnapshot $gridXml 12)
$gridShot = Save-Screen "02_grid_initial"
$gridSignature = Assert-GoldenStage "gridInitial" $gridXml $gridShot
Write-Host "[ok] First visible Grid card: $firstCardText"

Tap-Text "Filters"
Wait-ForAnyText @("Distance") | Out-Null
$filterSheetXml = Dump-Ui "03_filters_sheet" -Keep
Assert-NoAppErrorText $filterSheetXml "Filter sheet"
Assert-TextPresent $filterSheetXml "Presence" "Filter sheet"
$filterSheetShot = Save-Screen "03_filters_sheet"
$filterSheetSignature = Assert-GoldenStage "filtersSheet" $filterSheetXml $filterSheetShot

# Sections below Presence render past the fold at sheet open, and the fold keeps moving up as
# filters are added (Who/Gender/Orientation from the profile-filter coherence work, then Photos and
# the tonight/travel presence chips). So assert them by scrolling rather than off the initial dump —
# otherwise every new filter silently invalidates this stage. Order matters: Trust sits above Intent
# in the sheet, so reach it first, then continue down to the deepest Intent chip ("Together").
$filterSheetTrustXml = Scroll-SheetToText "Trust"
Assert-NoAppErrorText $filterSheetTrustXml "Filter sheet (trust)"
Assert-TextPresent $filterSheetTrustXml "Trust" "Filter sheet (trust)"
# The Trust chips moved off the filtersSheet golden with the section itself; assert them here so the
# coverage is relocated rather than lost.
foreach ($trustChip in @("Verified", "Face photo", "Age verified")) {
    Assert-TextPresent $filterSheetTrustXml $trustChip "Filter sheet (trust)"
}

$filterSheetLowerXml = Scroll-SheetToText "Together"
Assert-NoAppErrorText $filterSheetLowerXml "Filter sheet (lower)"
Assert-TextPresent $filterSheetLowerXml "Together" "Filter sheet (lower)"
Save-Screen "03b_filters_sheet_lower" | Out-Null

Invoke-Adb @("shell", "input", "keyevent", "KEYCODE_BACK") | Out-Null
Wait-ForAnyText @($firstCardText, "Filters") | Out-Null

Tap-Text "Online"
Start-Sleep -Seconds 2
$onlineXml = Dump-Ui "04_grid_online_filter" -Keep
Assert-NoAppErrorText $onlineXml "Online Grid filter"
Assert-TextPresent $onlineXml "Filters" "Online Grid filter"
$onlineShot = Save-Screen "04_grid_online_filter"
$onlineSignature = Assert-GoldenStage "onlineFilter" $onlineXml $onlineShot

Tap-Text "Online"
Wait-ForAnyText @($firstCardText) 45 | Out-Null
$clearedXml = Dump-Ui "05_grid_filter_cleared" -Keep
Assert-NoAppErrorText $clearedXml "Cleared Grid filter"
$firstAfterClear = Get-FirstGridCardNode $clearedXml
$firstAfterClearText = $firstAfterClear.GetAttribute("text")
$clearedGridCards = @(Get-GridCardSnapshot $clearedXml 12)
Assert-GridSnapshotStable $initialGridCards $clearedGridCards "Filter clear"
$clearedShot = Save-Screen "05_grid_filter_cleared"
$clearedSignature = Assert-GoldenStage "filterCleared" $clearedXml $clearedShot

# Hardening, not a fix for an observed failure: $clearedXml is several seconds stale by this point
# (Save-Screen + the golden's bitmap analysis run in between), so re-resolve the card rather than
# tapping coordinates captured before the grid had settled.
$preTapXml = Dump-Ui "05b_grid_pre_tap"
$freshCard = Find-NodeByText $preTapXml $firstAfterClearText
if ($null -eq $freshCard) {
    $freshCard = Get-FirstGridCardNode $preTapXml
}
Tap-Node $freshCard
# "Spark" only — Find-NodeByText is a substring match, so "Message" matches the "Messages" bottom-nav
# tab and "Link" matches the "Linked" Who chip, both of which are on the Grid behind the sheet. With
# those in the list this wait returned instantly and the stage dumped the Grid whenever the sheet
# animation was slow, which is why it failed only on a loaded emulator.
Wait-ForAnyText @("Spark") 45 | Out-Null
$profileXml = Dump-Ui "06_profile_detail" -Keep
Assert-NoAppErrorText $profileXml "Profile detail"
Assert-TextPresent $profileXml $firstCardText "Profile detail"
Assert-TextPresent $profileXml "Message" "Profile detail"
$profileShot = Save-Screen "06_profile_detail"
$profileSignature = Assert-GoldenStage "profileDetail" $profileXml $profileShot

Invoke-Adb @("shell", "input", "keyevent", "KEYCODE_BACK") | Out-Null
Wait-ForAnyText @($firstCardText, "Filters") 30 | Out-Null
$returnXml = Dump-Ui "07_grid_return_from_profile" -Keep
Assert-NoAppErrorText $returnXml "Returned Grid"
$firstAfterReturn = Get-FirstGridCardNode $returnXml
$firstAfterReturnText = $firstAfterReturn.GetAttribute("text")
$returnGridCards = @(Get-GridCardSnapshot $returnXml 12)
Assert-GridSnapshotStable $initialGridCards $returnGridCards "Profile close"
$returnShot = Save-Screen "07_grid_return_from_profile"
$returnSignature = Assert-GoldenStage "gridReturnFromProfile" $returnXml $returnShot

Write-Summary @{
    passed = $true
    baseUrl = $BaseUrl
    deviceId = $script:DeviceId
    packageName = $PackageName
    apk = $apk.FullName
    firstGridCard = $firstCardText
    visibleGridCards = @($initialGridCards | ForEach-Object { $_.Text })
    visualSignatures = @{
        gridInitial = $gridSignature
        filtersSheet = $filterSheetSignature
        onlineFilter = $onlineSignature
        filterCleared = $clearedSignature
        profileDetail = $profileSignature
        gridReturnFromProfile = $returnSignature
    }
    screenshots = @{
        login = $loginShot
        notificationPrompt = $promptShot
        gridInitial = $gridShot
        filtersSheet = $filterSheetShot
        onlineFilter = $onlineShot
        filterCleared = $clearedShot
        profileDetail = $profileShot
        gridReturnFromProfile = $returnShot
    }
}
