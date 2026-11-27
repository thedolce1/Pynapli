param(
    [string]$BaseUrl = "http://127.0.0.1:8080",
    [string]$PackageName = "com.pynapli.app",
    [string]$LaunchActivity = ".DefaultLauncherAlias",
    [string]$DeviceId = "",
    [string]$OutputRoot = "artifacts\android-activity-qa",
    [switch]$SkipSeed,
    [switch]$SkipBuild,
    [switch]$SkipInstall,
    [switch]$KeepAppData,
    [string]$GoldenSpec = "scripts\android-activity-qa-goldens.json",
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
    throw "System.Drawing is required for Android Activity screenshot signatures. Run this script on Windows with the local desktop tooling."
}

function Tap-ActivityTabText {
    param(
        [string]$Text,
        [int]$TimeoutSeconds = $script:WaitSeconds
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $xml = Dump-Ui "wait_activity_tab"
        $node = Find-NodeByText $xml $Text
        if ($null -ne $node) {
            $bounds = Get-Bounds $node
            Invoke-Adb @("shell", "input", "tap", "$($bounds.CenterX)", "$($bounds.CenterY)") | Out-Null
            Write-Host "[tap-tab] '$Text' at $($bounds.CenterX),$($bounds.CenterY)"
            return
        }

        $screen = Get-ScreenSize $xml
        $anchor = Find-NodeByText $xml "Flirts"
        $y = if ($null -ne $anchor) {
            (Get-Bounds $anchor).CenterY
        } else {
            [int]($screen.Height * 0.17)
        }
        Invoke-Adb @(
            "shell",
            "input",
            "swipe",
            "$([int]($screen.Width * 0.86))",
            "$y",
            "$([int]($screen.Width * 0.18))",
            "$y",
            "240"
        ) | Out-Null
        Start-Sleep -Milliseconds 350
    } while ((Get-Date) -lt $deadline)

    $visible = (Get-VisibleTexts $xml) -join " | "
    throw "Timed out waiting for Activity tab '$Text'. Visible text: $visible"
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
        throw "Docker was not found; Android Activity QA needs direct dev database seeding."
    }

    $output = & $docker.Source exec pynapli-postgres psql -U pynapli -d $(if ($env:PYNAPLI_DATABASE_NAME) { $env:PYNAPLI_DATABASE_NAME } elseif ($env:PYNAPLI_DATABASE_URL -match '/([^/?]+)(\?|$)') { $Matches[1] } else { 'pynapli_dev' }) -t -A -v ON_ERROR_STOP=1 -c $Sql 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "Dev SQL failed: $output"
    }
    return ($output | Out-String).Trim()
}

function Reset-DevActivityState {
    $hashes = @{
        viewer = Get-Sha256Base64Url "dev@pynapli.local"
        maya = Get-Sha256Base64Url "maya@pynapli.local"
        alex = Get-Sha256Base64Url "alex@pynapli.local"
        nico = Get-Sha256Base64Url "nico@pynapli.local"
        jules = Get-Sha256Base64Url "jules@pynapli.local"
        rae = Get-Sha256Base64Url "rae@pynapli.local"
        noa = Get-Sha256Base64Url "noa@pynapli.local"
    }

    $sql = @"
DO `$`$
DECLARE
    viewer_id uuid;
    maya_id uuid;
    alex_id uuid;
    nico_id uuid;
    jules_id uuid;
    rae_id uuid;
    noa_id uuid;
    private_album_id uuid;
BEGIN
    SELECT id INTO viewer_id FROM users WHERE email_hash = '$($hashes.viewer)';
    SELECT id INTO maya_id FROM users WHERE email_hash = '$($hashes.maya)';
    SELECT id INTO alex_id FROM users WHERE email_hash = '$($hashes.alex)';
    SELECT id INTO nico_id FROM users WHERE email_hash = '$($hashes.nico)';
    SELECT id INTO jules_id FROM users WHERE email_hash = '$($hashes.jules)';
    SELECT id INTO rae_id FROM users WHERE email_hash = '$($hashes.rae)';
    SELECT id INTO noa_id FROM users WHERE email_hash = '$($hashes.noa)';

    IF viewer_id IS NULL OR maya_id IS NULL OR alex_id IS NULL OR nico_id IS NULL OR jules_id IS NULL OR rae_id IS NULL OR noa_id IS NULL THEN
        RAISE EXCEPTION 'Missing one or more dev users for Activity QA.';
    END IF;

    -- The Views tab gates profile_view rows on mutual viewed-list opt-in, so make the viewer and
    -- the view actor discoverable in viewed lists (non-incognito) or the seeded view stays hidden.
    UPDATE discovery_settings
    SET show_in_viewed_lists = true, incognito = false, updated_at = now()
    WHERE user_id IN (viewer_id, maya_id);

    DELETE FROM activity_events WHERE recipient_user_id = viewer_id;
    SELECT id INTO private_album_id
    FROM media_albums
    WHERE owner_user_id = viewer_id
      AND kind = 'private'
      AND deleted_at IS NULL
    ORDER BY created_at DESC, id DESC
    LIMIT 1;

    IF private_album_id IS NULL THEN
        private_album_id := '00000000-0000-0000-0000-000000009701'::uuid;
    END IF;

    DELETE FROM media_album_access_requests WHERE id = '00000000-0000-0000-0000-000000009702'::uuid;

    INSERT INTO media_albums (id, owner_user_id, kind, title, visibility_status)
    VALUES (private_album_id, viewer_id, 'private', 'Private album', 'visible')
    ON CONFLICT (id) DO UPDATE
    SET owner_user_id = EXCLUDED.owner_user_id,
        kind = EXCLUDED.kind,
        title = EXCLUDED.title,
        visibility_status = EXCLUDED.visibility_status,
        deleted_at = NULL,
        updated_at = now();

    INSERT INTO media_album_access_requests (
        id,
        album_id,
        owner_user_id,
        requester_user_id,
        status,
        requested_at,
        decided_at,
        decided_by_user_id
    )
    VALUES (
        '00000000-0000-0000-0000-000000009702'::uuid,
        private_album_id,
        viewer_id,
        rae_id,
        'pending',
        now() - interval '7 minutes',
        NULL,
        NULL
    )
    ON CONFLICT (id) DO UPDATE
    SET album_id = EXCLUDED.album_id,
        owner_user_id = EXCLUDED.owner_user_id,
        requester_user_id = EXCLUDED.requester_user_id,
        status = 'pending',
        requested_at = EXCLUDED.requested_at,
        decided_at = NULL,
        decided_by_user_id = NULL;

    INSERT INTO activity_events (
        id,
        recipient_user_id,
        actor_user_id,
        event_type,
        target_user_id,
        aggregate_count,
        first_occurred_at,
        last_counted_at,
        occurred_at,
        updated_at,
        seen_at,
        metadata
    )
    VALUES
        ('00000000-0000-0000-0000-000000009711'::uuid, viewer_id, maya_id, 'profile_view', viewer_id, 2, now() - interval '40 minutes', now() - interval '40 minutes', now() - interval '40 minutes', now(), NULL, '{}'::jsonb),
        ('00000000-0000-0000-0000-000000009712'::uuid, viewer_id, alex_id, 'flirt', viewer_id, 1, now() - interval '30 minutes', now() - interval '30 minutes', now() - interval '30 minutes', now(), NULL, '{}'::jsonb),
        ('00000000-0000-0000-0000-000000009713'::uuid, viewer_id, nico_id, 'swipe_like', viewer_id, 1, now() - interval '20 minutes', now() - interval '20 minutes', now() - interval '20 minutes', now(), NULL, '{}'::jsonb),
        ('00000000-0000-0000-0000-000000009714'::uuid, viewer_id, jules_id, 'match', viewer_id, 1, now() - interval '12 minutes', now() - interval '12 minutes', now() - interval '12 minutes', now(), NULL, '{"matchId":"00000000-0000-0000-0000-000000009714"}'::jsonb),
        ('00000000-0000-0000-0000-000000009715'::uuid, viewer_id, rae_id, 'album_request', viewer_id, 1, now() - interval '7 minutes', now() - interval '7 minutes', now() - interval '7 minutes', now(), NULL, '{}'::jsonb),
        ('00000000-0000-0000-0000-000000009716'::uuid, viewer_id, noa_id, 'partner_chat_invite', viewer_id, 1, now() - interval '3 minutes', now() - interval '3 minutes', now() - interval '3 minutes', now(), NULL, '{"conversationId":"00000000-0000-0000-0000-000000009716"}'::jsonb)
    ON CONFLICT (id) DO UPDATE
    SET recipient_user_id = EXCLUDED.recipient_user_id,
        actor_user_id = EXCLUDED.actor_user_id,
        event_type = EXCLUDED.event_type,
        target_user_id = EXCLUDED.target_user_id,
        aggregate_count = EXCLUDED.aggregate_count,
        first_occurred_at = EXCLUDED.first_occurred_at,
        last_counted_at = EXCLUDED.last_counted_at,
        occurred_at = EXCLUDED.occurred_at,
        updated_at = now(),
        seen_at = NULL,
        deleted_at = NULL,
        visibility_status = 'visible',
        metadata = EXCLUDED.metadata;
END
`$`$;
"@

    Invoke-DevSql $sql | Out-Null
    Write-Host "[ok] Seeded Activity QA rows for dev@pynapli.local"
}

function Get-ActivitySeenCount {
    $viewerHash = Get-Sha256Base64Url "dev@pynapli.local"
    $sql = "SELECT COUNT(*) FROM activity_events ae JOIN users u ON u.id = ae.recipient_user_id WHERE u.email_hash = '$viewerHash' AND ae.seen_at IS NOT NULL AND ae.deleted_at IS NULL;"
    $value = Invoke-DevSql $sql
    return [int]$value
}

function Start-App {
    $component = "$PackageName/$LaunchActivity"
    Grant-QaNotificationPermission -PackageName $PackageName
    Invoke-Adb @("shell", "am", "start", "-n", $component) | Out-Null
    Write-Host "[step] Launched $component"
}

function Complete-LoginIfNeeded {
    # Cold start on the x86_64 emulator can take well over a minute right after a Gradle build,
    # so give the splash -> sign-in transition a generous window before giving up.
    $landing = Wait-ForAnyText @("Dev login", "Grid Load", "Dense", "Filters") 120
    if ($landing.Text -eq "Dev login") {
        $loginXml = Dump-Ui "00_login" -Keep
        Assert-NoAppErrorText $loginXml "Login"
        $script:Screenshots.login = Save-Screen "00_login"
        Tap-Text "Dev login"

        Wait-ForAnyText @("Allow Pynapli", "Don", "Current location", "Filters", "Grid Load", "Dense") 90 | Out-Null
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

function Capture-ActivityStage {
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
    Write-Host "Android Activity QA passed."
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

Write-Host "Pynapli Android Activity QA"
Write-Host "--------------------------"
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
    throw "Ktor is not healthy at $BaseUrl/health/deep. Start the local server before running Android Activity QA. $($_.Exception.Message)"
}

if (-not $SkipSeed) {
    Write-Host "[step] Seeding dev data and emulator location"
    & pwsh -NoProfile -ExecutionPolicy Bypass -File ".\scripts\seed-dev-data.ps1" -BaseUrl $BaseUrl -SetAndroidEmulatorLocation -DeviceId $script:DeviceId
    if ($LASTEXITCODE -ne 0) { throw "Dev data seed failed." }
}

Write-Host "[step] Resetting and seeding Activity QA rows"
Reset-DevActivityState

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

Tap-BottomNavText "Activity"
Wait-ForAnyText @("Activity", "Flirts", "Views") 45 | Out-Null
# Default tab is Flirts (unified inbound interest: taps + swipe-likes + pineapples (with their notes) —
# the premium reveal money surface). Matches live in the Messages new-matches rail; album/invite
# requests are folded into Requests. Flirts/Views render as a 2-up FOMO grid.
$initialXml = Capture-ActivityStage "flirtsTab" "03_activity_flirts"
Assert-TextPresent $initialXml "Alex" "Initial Activity (Flirts)"

Tap-ActivityTabText "Views"
Wait-ForAnyText @("Maya", "viewed you") 20 | Out-Null
Capture-ActivityStage "viewsTab" "04_activity_views" | Out-Null

Tap-ActivityTabText "Requests"
Wait-ForAnyText @("Rae", "Review", "Noa", "Open invite") 20 | Out-Null
Capture-ActivityStage "requestsTab" "06_activity_requests" | Out-Null

# After visiting every tab the app should have marked the loaded rows seen in the backend.
# Flirts(flirt+swipe_like=2) + Views(1) + Requests(album+invite=2) = 5.
$seenDeadline = (Get-Date).AddSeconds(20)
do {
    Start-Sleep -Milliseconds 800
    $seenCount = Get-ActivitySeenCount
    if ($seenCount -ge 4) { break }
} while ((Get-Date) -lt $seenDeadline)
if ($seenCount -lt 4) {
    throw "Activity screen did not mark loaded rows seen. Seen rows=$seenCount"
}
Write-Host "[ok] Activity loaded rows marked seen in backend: $seenCount"

Write-Summary @{
    passed = $true
    baseUrl = $BaseUrl
    deviceId = $script:DeviceId
    packageName = $PackageName
    apk = $apk.FullName
    seenCount = $seenCount
    visualSignatures = $script:VisualSignatures
    screenshots = $script:Screenshots
}
