param(
    [string]$BaseUrl = "http://127.0.0.1:8080",
    [string]$ViewerEmail = "dev@pynapli.local",
    [string]$Password = "PynapliDevPass123!",
    [string]$PackageName = "com.pynapli.app",
    [string]$LaunchActivity = ".DefaultLauncherAlias",
    [string]$DeviceId = "",
    [string]$OutputRoot = "artifacts\android-profile-qa",
    [switch]$SkipSeed,
    [switch]$SkipBuild,
    [switch]$SkipInstall,
    [switch]$KeepAppData,
    [string]$GoldenSpec = "scripts\android-profile-qa-goldens.json",
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
    throw "System.Drawing is required for Android profile screenshot signatures. Run this script on Windows with the local desktop tooling."
}

function Scroll-Down {
    $xml = Dump-Ui "scroll_anchor"
    $screen = Get-ScreenSize $xml
    Invoke-Adb @(
        "shell", "input", "swipe",
        "$([int]($screen.Width * 0.5))",
        "$([int]($screen.Height * 0.72))",
        "$([int]($screen.Width * 0.5))",
        "$([int]($screen.Height * 0.30))",
        "600"
    ) | Out-Null
    Start-Sleep -Milliseconds 700
}

# Scrolls the edit form until an exact-text chip is on screen, then taps it. The
# edit screen is a LazyColumn, so lower sections only compose once scrolled into view.
function Scroll-And-Tap-Exact {
    param(
        [string]$Text,
        [int]$MaxScrolls = 8
    )

    for ($attempt = 0; $attempt -le $MaxScrolls; $attempt++) {
        $xml = Dump-Ui "scroll_tap"
        $node = Find-NodeByExactText $xml $Text
        if ($null -ne $node) {
            Tap-Node $node $Text
            Start-Sleep -Milliseconds 400
            return
        }
        if ($attempt -lt $MaxScrolls) { Scroll-Down }
    }

    $visible = (Get-VisibleTexts (Dump-Ui "scroll_tap_fail")) -join " | "
    throw "Timed out scrolling to chip '$Text'. Visible text: $visible"
}

function Scroll-Until-Text {
    param(
        [string[]]$Texts,
        [int]$MaxScrolls = 8
    )

    for ($attempt = 0; $attempt -le $MaxScrolls; $attempt++) {
        $xml = Dump-Ui "scroll_until"
        foreach ($text in $Texts) {
            if ($null -ne (Find-NodeByText $xml $text)) {
                return [pscustomobject]@{ Text = $text; Xml = $xml }
            }
        }
        if ($attempt -lt $MaxScrolls) { Scroll-Down }
    }

    $visible = (Get-VisibleTexts (Dump-Ui "scroll_until_fail")) -join " | "
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

function Capture-Stage {
    param(
        [string]$StageName,
        [string]$ScreenshotName,
        [xml]$Xml = $null
    )

    if ($null -eq $Xml) {
        Start-Sleep -Milliseconds 700
        $Xml = Dump-Ui $ScreenshotName -Keep
    }
    Assert-NoAppErrorText $Xml $StageName
    $shot = Save-Screen $ScreenshotName
    $script:Screenshots[$StageName] = $shot
    $script:VisualSignatures[$StageName] = Assert-GoldenStage $StageName $Xml $shot
    return $Xml
}

# ---------------------------------------------------------------------------
# Dev API + DB helpers (deterministic setup + server-side write-path verification).
# ---------------------------------------------------------------------------

function Invoke-PynapliJson {
    param(
        [ValidateSet("GET", "POST", "PATCH", "PUT", "DELETE")]
        [string]$Method,
        [string]$Path,
        [object]$Body = $null,
        [string]$AccessToken = $null
    )

    $headers = @{ Accept = "application/json" }
    if (-not [string]::IsNullOrWhiteSpace($AccessToken)) {
        $headers.Authorization = "Bearer $AccessToken"
    }

    $parameters = @{
        Method      = $Method
        Uri         = "$BaseUrl$Path"
        Headers     = $headers
        ErrorAction = "Stop"
    }

    if ($null -ne $Body) {
        $parameters.ContentType = "application/json"
        $parameters.Body = $Body | ConvertTo-Json -Depth 10
    }

    try {
        return @{ StatusCode = 200; Body = Invoke-RestMethod @parameters }
    } catch {
        $statusCode = 0
        if ($_.Exception.Response) { $statusCode = [int]$_.Exception.Response.StatusCode }
        return @{ StatusCode = $statusCode; Body = $null; RawError = $_.ErrorDetails.Message }
    }
}

function Assert-ApiSuccess {
    param(
        [hashtable]$Response,
        [string]$Action
    )

    if ($Response.StatusCode -lt 200 -or $Response.StatusCode -ge 300) {
        throw "$Action failed with HTTP $($Response.StatusCode): $($Response.RawError)"
    }
}

function Invoke-DevSql {
    param([string]$Sql)

    $docker = Get-Command docker -ErrorAction SilentlyContinue
    if ($null -eq $docker) {
        throw "Docker was not found; Android Profile QA needs direct dev database setup."
    }
    $output = & $docker.Source exec pynapli-postgres psql -U pynapli -d pynapli_dev -t -A -v ON_ERROR_STOP=1 -c $Sql 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "Dev SQL failed: $output"
    }
    return ($output | Out-String).Trim()
}

# Partner stage fixture: the shared seed only pairs maya/alex and jules/rae, so the dev viewer has no
# linked partner. Link the viewer to Nico (an unpaired seeded account) for the duration of this run
# and unlink it at the end, leaving the shared dev environment with the viewer single by default.
$script:QaPartnerEmail = "nico@pynapli.local"

function Set-DevViewerPartnerLink {
    # Must run before the app launches so the Profile hub loads the link on first render.
    $viewerHash = Get-Sha256Base64Url $ViewerEmail
    $partnerHash = Get-Sha256Base64Url $script:QaPartnerEmail
    $sql = @"
UPDATE user_profile_links SET status = 'unlinked', unlinked_at = now(), updated_at = now()
WHERE status = 'active' AND unlinked_at IS NULL
  AND (user_id IN (SELECT id FROM users WHERE email_hash IN ('$viewerHash', '$partnerHash'))
       OR linked_user_id IN (SELECT id FROM users WHERE email_hash IN ('$viewerHash', '$partnerHash')));

INSERT INTO user_profile_links (id, user_id, linked_user_id, status, accepted_at, unlinked_at)
SELECT gen_random_uuid(), v.id, p.id, 'active', now(), NULL::timestamptz
FROM (SELECT id FROM users WHERE email_hash = '$viewerHash') v,
     (SELECT id FROM users WHERE email_hash = '$partnerHash') p
UNION ALL
SELECT gen_random_uuid(), p.id, v.id, 'active', now(), NULL::timestamptz
FROM (SELECT id FROM users WHERE email_hash = '$viewerHash') v,
     (SELECT id FROM users WHERE email_hash = '$partnerHash') p
ON CONFLICT (user_id, linked_user_id) DO UPDATE
SET status = 'active',
    accepted_at = COALESCE(user_profile_links.accepted_at, now()),
    unlinked_at = NULL,
    updated_at = now();
"@
    Invoke-DevSql $sql | Out-Null
    Write-Host "[ok] Linked the dev viewer to $($script:QaPartnerEmail) for the partner stage"
}

function Clear-DevViewerPartnerLink {
    $viewerHash = Get-Sha256Base64Url $ViewerEmail
    $partnerHash = Get-Sha256Base64Url $script:QaPartnerEmail
    $sql = @"
UPDATE user_profile_links SET status = 'unlinked', unlinked_at = now(), updated_at = now()
WHERE status = 'active' AND unlinked_at IS NULL
  AND ((user_id IN (SELECT id FROM users WHERE email_hash = '$viewerHash')
        AND linked_user_id IN (SELECT id FROM users WHERE email_hash = '$partnerHash'))
    OR (user_id IN (SELECT id FROM users WHERE email_hash = '$partnerHash')
        AND linked_user_id IN (SELECT id FROM users WHERE email_hash = '$viewerHash')));
"@
    Invoke-DevSql $sql | Out-Null
    Write-Host "[ok] Unlinked the dev viewer partner (shared seed left viewer single)"
}

function Reset-DevProfileForEdit {
    # Clean, age-verified slate so chip-selection assertions are deterministic and the
    # age-gated Lifestyle chips render (age verification is exercised separately by
    # ProfileServiceTest's gate matrix).
    $viewerHash = Get-Sha256Base64Url $ViewerEmail
    # A valid display name is required for any profile save; the dev viewer's seeded
    # profile leaves it null, so set a known value alongside the clean-slate reset.
    $sql = @"
UPDATE profiles
SET display_name = 'Seb Dev',
    gender = NULL, gender_detail = NULL,
    orientation = NULL, orientation_detail = NULL,
    bio = NULL,
    lifestyle_tags = NULL, logistics_tags = NULL, intents = NULL,
    age_verified_at = now(),
    updated_at = now()
WHERE user_id = (SELECT id FROM users WHERE email_hash = '$viewerHash');
"@
    Invoke-DevSql $sql | Out-Null
    # Reset partner visibility to the default so the selector starts at a known state.
    Invoke-DevSql "UPDATE discovery_settings SET partner_visibility = 'public' WHERE user_id = (SELECT id FROM users WHERE email_hash = '$viewerHash');" | Out-Null
    Set-DevViewerPartnerLink
    Write-Host "[ok] Reset + age-verified dev profile for a clean edit slate"
}

function Get-ViewerSession {
    $login = Invoke-PynapliJson -Method POST -Path "/v1/auth/email/login" -Body @{
        email = $ViewerEmail
        password = $Password
        deviceId = "profile-qa-$([guid]::NewGuid().ToString('N').Substring(0, 8))"
        platform = "qa"
        appVersion = "0.1.0"
    }
    Assert-ApiSuccess $login "Dev viewer login"
    return $login.Body
}

function Start-App {
    $component = "$PackageName/$LaunchActivity"
    Invoke-Adb @("shell", "am", "start", "-n", $component) | Out-Null
    Write-Host "[step] Launched $component"
}

function Complete-LoginIfNeeded {
    $landing = Wait-ForAnyText @("Dev login", "Grid Load", "Dense", "Filters") 45
    if ($landing.Text -eq "Dev login") {
        Tap-Text "Dev login"
        Wait-ForAnyText @("Allow Pynapli", "Don", "Current location", "Filters", "Grid Load", "Dense") 45 | Out-Null
        Start-Sleep -Milliseconds 1200
        $afterLoginXml = Dump-Ui "after_login"
        if ($null -ne (Find-NodeByText $afterLoginXml "Don")) {
            Tap-Text "Don"
        }
    }
}

function Open-EditProfile {
    Wait-ForAnyText @("Grid Load", "Dense", "Filters") 45 | Out-Null
    Tap-Text "Profile"
    Tap-Text "Edit profile"
    Wait-ForAnyText @("Edit profile", "Display name") 45 | Out-Null
}

function Write-Summary {
    param([hashtable]$Summary)

    $path = Join-Path $script:ArtifactDir "qa-summary.json"
    $Summary | ConvertTo-Json -Depth 8 | Set-Content -Path $path -Encoding UTF8
    Write-Host ""
    Write-Host "Android Profile QA passed."
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

Write-Host "Pynapli Android Profile QA"
Write-Host "--------------------------"
Write-Host "Device: $script:DeviceId"
Write-Host "Artifacts: $script:ArtifactDir"

try {
    $health = Invoke-RestMethod -Uri "$BaseUrl/health/deep" -TimeoutSec 5
    if ($health.status -ne "ok") {
        throw "Unexpected health status: $($health | ConvertTo-Json -Compress)"
    }
    Write-Host "[ok] Ktor health: $BaseUrl/health/deep"
} catch {
    throw "Ktor is not healthy at $BaseUrl/health/deep. Start the local server before running Android Profile QA. $($_.Exception.Message)"
}

if (-not $SkipSeed) {
    Write-Host "[step] Seeding dev data and emulator location"
    & pwsh -NoProfile -ExecutionPolicy Bypass -File ".\scripts\seed-dev-data.ps1" -BaseUrl $BaseUrl -SetAndroidEmulatorLocation -DeviceId $script:DeviceId
    if ($LASTEXITCODE -ne 0) { throw "Dev data seed failed." }
}

Reset-DevProfileForEdit

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
Open-EditProfile

# Stage 1: the edit form renders the new structured sections (top of the form).
$initialXml = Capture-Stage "editInitial" "01_edit_initial"
Assert-TextPresent $initialXml "Display name" "Edit initial"
Assert-TextPresent $initialXml "Bio" "Edit initial"

# Stage 2: structured gender/orientation chips select.
Scroll-Until-Text @("Gender") | Out-Null
Scroll-And-Tap-Exact "Woman"
Scroll-And-Tap-Exact "Bisexual"
$taxonomyXml = Capture-Stage "editTaxonomy" "02_edit_taxonomy"
Assert-TextPresent $taxonomyXml "Orientation" "Edit taxonomy"

# Stage 3: multi-select intents/logistics + the age-gated lifestyle chip.
Scroll-And-Tap-Exact "Events"
Scroll-And-Tap-Exact "Hosts"
Scroll-And-Tap-Exact "ENM"
$tagsXml = Capture-Stage "editTags" "03_edit_tags"
Assert-TextPresent $tagsXml "Boundaries" "Edit tags"
Assert-TextPresent $tagsXml "Logistics" "Edit tags"

# Save and confirm the editor closes back to the hub with success feedback. The
# unique "Profile updated" band — not the edit screen's own "Edit profile" title —
# signals a real save; a validation failure leaves the form open and times out here.
Tap-Text "Save profile"
$savedWait = Wait-ForAnyText @("Profile updated") 45
$savedXml = Capture-Stage "saved" "04_saved" $savedWait.Xml

# Server-side verification: the on-device save persisted the structured write path.
$session = Get-ViewerSession
$me = Invoke-PynapliJson -Method GET -Path "/v1/me" -AccessToken $session.accessToken
Assert-ApiSuccess $me "GET /v1/me after save"
$profile = $me.Body.profile
$failures = @()
if ($profile.gender -ne "woman") { $failures += "gender=$($profile.gender) (expected woman)" }
if ($profile.orientation -ne "bisexual") { $failures += "orientation=$($profile.orientation) (expected bisexual)" }
if (@($profile.intents) -notcontains "events") { $failures += "intents=$($profile.intents -join ',') (expected events)" }
if (@($profile.logisticsTags) -notcontains "hosts") { $failures += "logisticsTags=$($profile.logisticsTags -join ',') (expected hosts)" }
if (@($profile.lifestyleTags) -notcontains "enm") { $failures += "lifestyleTags=$($profile.lifestyleTags -join ',') (expected enm)" }
if ($failures.Count -gt 0) {
    throw "On-device save did not persist through the write path: $($failures -join '; ')"
}
Write-Host "[ok] Server confirmed the saved profile: gender=woman, orientation=bisexual, intents=events, logistics=hosts, lifestyle=enm"

# Reopen the editor and confirm the saved chips render selected.
Tap-Text "Edit profile"
Wait-ForAnyText @("Display name") 45 | Out-Null
Scroll-Until-Text @("Lifestyle") | Out-Null
$reopenXml = Capture-Stage "editReopened" "05_edit_reopened"
Assert-TextPresent $reopenXml "Lifestyle" "Edit reopened"

# Partner stage: the dedicated Partner surface renders the visibility selector, and a
# selection saves through the discovery-settings write path (server-verified).
Tap-Text "Back"
Tap-Text "Linked partner"
$partnerXml = Wait-ForAnyText @("Partner visibility") 45
$partnerXml = Capture-Stage "partner" "06_partner" $partnerXml.Xml
Assert-TextPresent $partnerXml "Show partner publicly" "Partner"
Assert-TextPresent $partnerXml "Matches only" "Partner"
Assert-TextPresent $partnerXml "Hide partner" "Partner"

Tap-Text "Matches only"
Tap-Text "Save partner settings"
Wait-ForAnyText @("Partner settings updated", "Linked partner") 45 | Out-Null

$me2 = Invoke-PynapliJson -Method GET -Path "/v1/me" -AccessToken $session.accessToken
Assert-ApiSuccess $me2 "GET /v1/me after partner save"
$partnerVisibility = $me2.Body.discoverySettings.partnerVisibility
if ($partnerVisibility -ne "matches_only") {
    throw "Partner visibility did not persist: got '$partnerVisibility', expected matches_only."
}
Write-Host "[ok] Server confirmed partner visibility = matches_only"

# Restore the shared dev environment: the viewer is single by default outside this QA.
Clear-DevViewerPartnerLink

Write-Summary @{
    baseUrl = $BaseUrl
    deviceId = $script:DeviceId
    artifactDir = $script:ArtifactDir
    screenshots = $script:Screenshots
    visualSignatures = $script:VisualSignatures
    persistedProfile = @{
        gender = $profile.gender
        orientation = $profile.orientation
        intents = @($profile.intents)
        logisticsTags = @($profile.logisticsTags)
        lifestyleTags = @($profile.lifestyleTags)
        partnerVisibility = $partnerVisibility
    }
    stages = @("editInitial", "editTaxonomy", "editTags", "saved", "editReopened", "partner")
}
