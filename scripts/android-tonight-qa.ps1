param(
    [string]$BaseUrl = "http://127.0.0.1:8080",
    [string]$PackageName = "com.pynapli.app",
    [string]$LaunchActivity = ".DefaultLauncherAlias",
    [string]$DeviceId = "",
    [string]$OutputRoot = "artifacts\android-tonight-qa",
    [switch]$SkipSeed,
    [switch]$SkipBuild,
    [switch]$SkipInstall,
    [switch]$KeepAppData,
    [string]$GoldenSpec = "scripts\android-tonight-qa-goldens.json",
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
    throw "System.Drawing is required for Android Grid screenshot signatures. Run this script on Windows with the local desktop tooling."
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
    Write-Host "Android Playing Tonight QA passed."
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

Write-Host "Pynapli Android Playing Tonight QA"
Write-Host "----------------------------------"
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
    Write-Host "[step] Seeding dev data (incl. live Playing Tonight sessions) and emulator location"
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

if (-not $KeepAppData) {
    Write-Host "[step] Clearing app data for fresh login"
    Invoke-Adb @("shell", "pm", "clear", $PackageName) | Out-Null
}

Grant-QaNotificationPermission -PackageName $PackageName

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
Write-Host "[ok] Grid loaded; first card: $firstCardText"

# Open Playing Tonight from the Grid header moon button (content-desc "Playing Tonight";
# the per-tile indicator uses lowercase "Playing tonight", so this matches only the button).
Tap-Text "Playing Tonight"
Wait-ForAnyText @("All", "Solo", "Events", "Nobody here yet") 30 | Out-Null
Start-Sleep -Milliseconds 1500
$tonightXml = Dump-Ui "08_tonight_map" -Keep
Assert-NoAppErrorText $tonightXml "Playing Tonight map"
Assert-TextPresent $tonightXml "All" "Playing Tonight map"
Assert-TextPresent $tonightXml "Together" "Playing Tonight map"
$tonightShot = Save-Screen "08_tonight_map"
$tonightSignature = Assert-GoldenStage "tonightMap" $tonightXml $tonightShot
Write-Host "[ok] Playing Tonight surface opened"

# Filter the sheet to Social — seeded social sessions (e.g. Maya, Kai) remain.
Tap-Text "Social"
Start-Sleep -Seconds 1
$tonightFilterXml = Dump-Ui "09_tonight_social_filter" -Keep
Assert-NoAppErrorText $tonightFilterXml "Playing Tonight social filter"
Assert-TextPresent $tonightFilterXml "Playing Tonight" "Playing Tonight social filter"
$tonightFilterShot = Save-Screen "09_tonight_social_filter"
$tonightFilterSignature = Assert-GoldenStage "tonightSocialFilter" $tonightFilterXml $tonightFilterShot

# Return to the Grid via the surface's back button.
Tap-Text "Back"
Wait-ForAnyText @($firstCardText, "Filters") 30 | Out-Null
$returnXml = Dump-Ui "10_grid_return_from_tonight" -Keep
Assert-NoAppErrorText $returnXml "Returned Grid"
$returnShot = Save-Screen "10_grid_return_from_tonight"

Write-Summary @{
    passed = $true
    baseUrl = $BaseUrl
    deviceId = $script:DeviceId
    packageName = $PackageName
    apk = $apk.FullName
    firstGridCard = $firstCardText
    visualSignatures = @{
        tonightMap = $tonightSignature
        tonightSocialFilter = $tonightFilterSignature
    }
    screenshots = @{
        login = $loginShot
        notificationPrompt = $promptShot
        gridInitial = $gridShot
        tonightMap = $tonightShot
        tonightSocialFilter = $tonightFilterShot
        gridReturnFromTonight = $returnShot
    }
}
