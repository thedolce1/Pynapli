param(
    [string]$BaseUrl = "http://127.0.0.1:8080",
    [string]$PackageName = "com.pynapli.app",
    [string]$LaunchActivity = ".DefaultLauncherAlias",
    [string]$DeviceId = "",
    [string]$OutputRoot = "artifacts\android-store-qa",
    [switch]$SkipSeed,
    [switch]$SkipBuild,
    [switch]$SkipInstall,
    [switch]$KeepAppData,
    [string]$GoldenSpec = "scripts\android-store-qa-goldens.json",
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
    throw "System.Drawing is required for Android Store screenshot signatures. Run this script on Windows with the local desktop tooling."
}

function Scroll-StoreDown {
    $xml = Dump-Ui "scroll_anchor"
    $screen = Get-ScreenSize $xml
    Invoke-Adb @(
        "shell",
        "input",
        "swipe",
        "$([int]($screen.Width * 0.50))",
        "$([int]($screen.Height * 0.78))",
        "$([int]($screen.Width * 0.50))",
        "$([int]($screen.Height * 0.32))",
        "700"
    ) | Out-Null
    Start-Sleep -Milliseconds 900
}

function Scroll-UntilAnyText {
    param(
        [string[]]$Texts,
        [int]$MaxScrolls = 5
    )

    for ($attempt = 0; $attempt -le $MaxScrolls; $attempt++) {
        $xml = Dump-Ui "scroll_until"
        foreach ($text in $Texts) {
            if ($null -ne (Find-NodeByText $xml $text)) {
                return [pscustomobject]@{ Text = $text; Xml = $xml }
            }
        }

        if ($attempt -lt $MaxScrolls) {
            Scroll-StoreDown
        }
    }

    $visible = (Get-VisibleTexts $xml) -join " | "
    throw "Timed out scrolling for one of: $($Texts -join ', '). Visible text: $visible"
}

function Assert-GoldenStage {
    param(
        [string]$StageName,
        [xml]$Xml,
        [string]$ScreenshotPath
    )

    if ($SkipGoldenAssertions -or $null -eq $script:GoldenSpecData) { return $null }

    $stageSpec = $script:GoldenSpecData.stages.$StageName
    if ($null -eq $stageSpec) {
        throw "Golden stage '$StageName' is missing from $GoldenSpec"
    }

    return Assert-GoldenStageSpec $StageName $Xml $ScreenshotPath $stageSpec
}

function Invoke-DevSql {
    param([string]$Sql)

    $docker = Get-Command docker -ErrorAction SilentlyContinue
    if ($null -eq $docker) {
        throw "Docker was not found; Android Store QA needs direct dev database reset."
    }

    $output = & $docker.Source exec pynapli-postgres psql -U pynapli -d pynapli_dev -t -A -v ON_ERROR_STOP=1 -c $Sql 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "Dev SQL failed: $output"
    }
    return ($output | Out-String).Trim()
}

function Reset-DevStoreState {
    $viewerHash = Get-Sha256Base64Url "dev@pynapli.local"
    $sql = @"
DO `$`$
DECLARE
    viewer_id uuid;
BEGIN
    SELECT id INTO viewer_id FROM users WHERE email_hash = '$viewerHash';

    IF viewer_id IS NULL THEN
        RAISE EXCEPTION 'Missing dev@pynapli.local for Store QA.';
    END IF;

    DELETE FROM subscriptions
    WHERE user_id = viewer_id
      AND platform = 'dev';

    DELETE FROM premium_inventory
    WHERE user_id = viewer_id
      AND source = 'dev'
      AND inventory_type IN ('boost', 'pineapple_like');
END
`$`$;
"@

    Invoke-DevSql $sql | Out-Null
    Write-Host "[ok] Reset Store QA purchase state for dev@pynapli.local"
}

function Start-App {
    $component = "$PackageName/$LaunchActivity"
    Grant-QaNotificationPermission -PackageName $PackageName
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

function Capture-StoreStage {
    param(
        [string]$StageName,
        [string]$ScreenshotName
    )

    Start-Sleep -Milliseconds 900
    $xml = Dump-Ui $ScreenshotName -Keep
    Assert-NoAppErrorText $xml $StageName
    $shot = Save-Screen $ScreenshotName
    $script:Screenshots[$StageName] = $shot
    $script:VisualSignatures[$StageName] = Assert-GoldenStage $StageName $xml $shot
    return $xml
}

function Write-Summary {
    param([hashtable]$Summary)

    $path = Join-Path $script:ArtifactDir "qa-summary.json"
    $Summary | ConvertTo-Json -Depth 8 | Set-Content -Path $path -Encoding UTF8
    Write-Host ""
    Write-Host "Android Store QA passed."
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

Write-Host "Pynapli Android Store QA"
Write-Host "------------------------"
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
    throw "Ktor is not healthy at $BaseUrl/health/deep. Start the local server before running Android Store QA. $($_.Exception.Message)"
}

if (-not $SkipSeed) {
    Write-Host "[step] Seeding dev data and emulator location"
    & pwsh -NoProfile -ExecutionPolicy Bypass -File ".\scripts\seed-dev-data.ps1" -BaseUrl $BaseUrl -SetAndroidEmulatorLocation -DeviceId $script:DeviceId
    if ($LASTEXITCODE -ne 0) { throw "Dev data seed failed." }
}

Write-Host "[step] Resetting Store QA state"
Reset-DevStoreState

if (-not $SkipBuild) {
    Write-Host "[step] Building androidApp debug APK"
    .\gradlew.bat :androidApp:assembleDebug --console=plain
    if ($LASTEXITCODE -ne 0) { throw "Android debug build failed." }
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

# No Store tab (2026-11-17): the Store is a sheet opened from the profile menu and from paywalls.
Tap-Text "Profile"
Wait-ForAnyText @("Edit profile", "Subscription") 45 | Out-Null
Scroll-UntilAnyText @("Plans and store") 8 | Out-Null
Tap-Text "Plans and store"
Wait-ForAnyText @("Store", "Plans") 45 | Out-Null
$initialXml = Capture-StoreStage "storeInitial" "03_store_initial"
# The linked-partner benefit leads the plans (2026-11-18), so the paid plans can sit below the fold.
Assert-TextPresent $initialXml "One subscription covers you both" "Initial Store"

Tap-Text "Restore"
Wait-ForAnyText @("Store refreshed.") 45 | Out-Null
$restoreXml = Capture-StoreStage "restoreRefreshed" "04_restore_refreshed"
Assert-TextPresent $restoreXml "Store refreshed." "Store restore"

# The paid plans are below the benefit; check they rendered before moving on to the extras.
$socialPlan = Scroll-UntilAnyText @("Pynapli Social") 4
Assert-TextPresent $socialPlan.Xml "Pynapli Social" "Store plans"

# Scroll to the last product so the whole Boosts & extras section is on-screen. Anchor on the
# Pineapple Likes pack's UNIQUE "5 for $3.99" price line, not the plain name: the Select plan lists
# "5 Pineapple Likes every week", so anchoring on the name could stop at the Select card.
Scroll-UntilAnyText @('5 for $3.99') 8 | Out-Null
$productsXml = Capture-StoreStage "productsVisible" "05_products_visible"
Assert-TextPresent $productsXml "Pineapple Likes" "Store products"

Tap-Text '$2.99'
Wait-ForAnyText @("Boost added.", "Balance: 1") 45 | Out-Null
$purchasedXml = Capture-StoreStage "boostPurchased" "06_boost_purchased"
Assert-TextPresent $purchasedXml "Boost added." "Boost purchase"
Assert-TextPresent $purchasedXml "Balance: 1" "Boost purchase"

# The Boost row grows once a Boost is owned (expiry line and "Use a Boost now"), so scroll to the
# Pineapple Likes feature line itself rather than to its balance.
Scroll-UntilAnyText @("Add a note up to 140 characters") | Out-Null
$otherProductsXml = Capture-StoreStage "otherProductsVisible" "07_other_products_visible"
Assert-TextPresent $otherProductsXml "Pineapple Likes" "Other Store products"
Assert-TextPresent $otherProductsXml "Add a note up to 140 characters" "Other Store products"

Write-Summary @{
    baseUrl = $BaseUrl
    deviceId = $script:DeviceId
    artifactDir = $script:ArtifactDir
    screenshots = $script:Screenshots
    visualSignatures = $script:VisualSignatures
    stages = @("gridAfterLogin", "storeInitial", "restoreRefreshed", "productsVisible", "boostPurchased", "otherProductsVisible")
}
