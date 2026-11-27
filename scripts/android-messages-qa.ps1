param(
    [string]$BaseUrl = "http://127.0.0.1:8080",
    [string]$PackageName = "com.pynapli.app",
    [string]$LaunchActivity = ".DefaultLauncherAlias",
    [string]$DeviceId = "",
    [string]$OutputRoot = "artifacts\android-messages-qa",
    [switch]$SkipSeed,
    [switch]$SkipBuild,
    [switch]$SkipInstall,
    [switch]$KeepAppData,
    [string]$GoldenSpec = "scripts\android-messages-qa-goldens.json",
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
    throw "System.Drawing is required for Android Messages screenshot signatures. Run this script on Windows with the local desktop tooling."
}

# Exact text match.
#
# Find-NodeByText is a substring match, so "Message" also matches the "Messages" nav tab. The nav is
# NOT hidden inside an open conversation (verified on device: composer "Message" and nav "Messages"
# are on screen together), which meant a wait on "Message" returned instantly no matter what was
# rendered, and a tap on it could land on the nav bar and navigate away mid-test.
#
# Exact matching is the whole fix — "Messages" is simply not equal to "Message". Deliberately NO
# bottom-nav band here: Find-BottomNavNodeByText's 0.82*height cut works for *finding* the nav (it
# takes the lowest match) but the composer sits at ~0.823*height, just inside it, so reusing that
# threshold to *exclude* the nav also excluded the very node we want.
function Wait-ForContentText {
    param(
        [string]$Text,
        [int]$TimeoutSeconds = $script:WaitSeconds
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $xml = Dump-Ui "wait_content"
        # -TextOnly: the composer's "Message" must never be satisfied by some other element's
        # content-desc. This script's local copy had no content-desc pass, so keep that behaviour.
        $node = Find-NodeByExactText $xml $Text -TextOnly
        if ($null -ne $node) { return [pscustomobject]@{ Xml = $xml; Node = $node } }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)

    throw "Timed out waiting for exact text '$Text'."
}

function Tap-ContentText {
    param(
        [string]$Text,
        [int]$TimeoutSeconds = $script:WaitSeconds
    )

    $wait = Wait-ForContentText $Text $TimeoutSeconds
    $bounds = Get-Bounds $wait.Node
    Invoke-Adb @("shell", "input", "tap", "$($bounds.CenterX)", "$($bounds.CenterY)") | Out-Null
    Write-Host "[tap] '$Text' (exact) at $($bounds.CenterX),$($bounds.CenterY)"
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
        throw "Docker was not found; Android Messages QA needs direct dev database seeding."
    }

    $output = & $docker.Source exec pynapli-postgres psql -U pynapli -d $(if ($env:PYNAPLI_DATABASE_NAME) { $env:PYNAPLI_DATABASE_NAME } else { 'pynapli_dev' }) -t -A -v ON_ERROR_STOP=1 -c $Sql 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "Dev SQL failed: $output"
    }
    return ($output | Out-String).Trim()
}

function Reset-DevMessagesState {
    $hashes = @{
        viewer = Get-Sha256Base64Url "dev@pynapli.local"
        kai = Get-Sha256Base64Url "kai@pynapli.local"
        alex = Get-Sha256Base64Url "alex@pynapli.local"
        maya = Get-Sha256Base64Url "maya@pynapli.local"
        noa = Get-Sha256Base64Url "noa@pynapli.local"
        rae = Get-Sha256Base64Url "rae@pynapli.local"
    }

    $sql = @"
DO `$`$
DECLARE
    viewer_id uuid;
    kai_id uuid;
    alex_id uuid;
    maya_id uuid;
    noa_id uuid;
    rae_id uuid;
BEGIN
    SELECT id INTO viewer_id FROM users WHERE email_hash = '$($hashes.viewer)';
    SELECT id INTO kai_id FROM users WHERE email_hash = '$($hashes.kai)';
    SELECT id INTO alex_id FROM users WHERE email_hash = '$($hashes.alex)';
    SELECT id INTO maya_id FROM users WHERE email_hash = '$($hashes.maya)';
    SELECT id INTO noa_id FROM users WHERE email_hash = '$($hashes.noa)';
    SELECT id INTO rae_id FROM users WHERE email_hash = '$($hashes.rae)';

    IF viewer_id IS NULL OR kai_id IS NULL OR alex_id IS NULL OR maya_id IS NULL OR noa_id IS NULL OR rae_id IS NULL THEN
        RAISE EXCEPTION 'Missing one or more dev users for Messages QA.';
    END IF;

    DELETE FROM conversations c
    WHERE c.id IN (
        '00000000-0000-0000-0000-000000009901'::uuid,
        '00000000-0000-0000-0000-000000009902'::uuid,
        '00000000-0000-0000-0000-000000009903'::uuid,
        '00000000-0000-0000-0000-000000009904'::uuid
    )
    OR (
        EXISTS (
            SELECT 1
            FROM conversation_participants cp
            WHERE cp.conversation_id = c.id
              AND cp.user_id = viewer_id
        )
        AND EXISTS (
            SELECT 1
            FROM conversation_participants cp
            WHERE cp.conversation_id = c.id
              AND cp.user_id IN (kai_id, alex_id, maya_id, noa_id, rae_id)
        )
    );

    INSERT INTO conversations (id, kind, origin, created_by_user_id, created_at, updated_at)
    VALUES
        ('00000000-0000-0000-0000-000000009901'::uuid, 'direct', 'grid', kai_id, now() - interval '14 minutes', now() - interval '5 minutes'),
        ('00000000-0000-0000-0000-000000009902'::uuid, 'direct', 'swipe', viewer_id, now() - interval '13 minutes', now() - interval '8 minutes'),
        ('00000000-0000-0000-0000-000000009903'::uuid, 'group', 'grid', noa_id, now() - interval '12 minutes', now() - interval '1 minutes'),
        ('00000000-0000-0000-0000-000000009904'::uuid, 'group', 'grid', maya_id, now() - interval '11 minutes', now() - interval '2 minutes');

    INSERT INTO direct_conversations (conversation_id, user_a_id, user_b_id, created_at)
    VALUES
        ('00000000-0000-0000-0000-000000009901'::uuid, LEAST(viewer_id, kai_id), GREATEST(viewer_id, kai_id), now() - interval '14 minutes'),
        ('00000000-0000-0000-0000-000000009902'::uuid, LEAST(viewer_id, alex_id), GREATEST(viewer_id, alex_id), now() - interval '13 minutes');

    INSERT INTO conversation_participants (
        conversation_id,
        user_id,
        role,
        joined_at,
        left_at,
        last_read_at,
        invitation_status,
        invited_by_user_id,
        invited_at,
        invitation_responded_at
    )
    VALUES
        ('00000000-0000-0000-0000-000000009901'::uuid, viewer_id, 'member', now() - interval '14 minutes', NULL, now() - interval '30 minutes', 'active', NULL, NULL, NULL),
        ('00000000-0000-0000-0000-000000009901'::uuid, kai_id, 'member', now() - interval '14 minutes', NULL, now(), 'active', NULL, NULL, NULL),
        ('00000000-0000-0000-0000-000000009902'::uuid, viewer_id, 'member', now() - interval '13 minutes', NULL, now(), 'active', NULL, NULL, NULL),
        ('00000000-0000-0000-0000-000000009902'::uuid, alex_id, 'member', now() - interval '13 minutes', NULL, now(), 'active', NULL, NULL, NULL),
        ('00000000-0000-0000-0000-000000009903'::uuid, viewer_id, 'member', now() - interval '12 minutes', NULL, now(), 'active', NULL, NULL, NULL),
        ('00000000-0000-0000-0000-000000009903'::uuid, noa_id, 'member', now() - interval '12 minutes', NULL, now(), 'active', NULL, NULL, NULL),
        ('00000000-0000-0000-0000-000000009903'::uuid, rae_id, 'member', now() - interval '1 minutes', NULL, now(), 'invited', noa_id, now() - interval '1 minutes', now() - interval '1 minutes'),
        ('00000000-0000-0000-0000-000000009904'::uuid, maya_id, 'member', now() - interval '11 minutes', NULL, now(), 'active', NULL, NULL, NULL),
        ('00000000-0000-0000-0000-000000009904'::uuid, alex_id, 'member', now() - interval '10 minutes', NULL, now(), 'active', NULL, NULL, NULL),
        ('00000000-0000-0000-0000-000000009904'::uuid, viewer_id, 'member', now() - interval '2 minutes', NULL, now(), 'invited', maya_id, now() - interval '2 minutes', NULL);

    INSERT INTO messages (id, conversation_id, sender_user_id, body, moderation_status, created_at)
    VALUES
        ('00000000-0000-0000-0000-000000009911'::uuid, '00000000-0000-0000-0000-000000009901'::uuid, kai_id, 'See you tonight', 'visible', now() - interval '5 minutes'),
        ('00000000-0000-0000-0000-000000009912'::uuid, '00000000-0000-0000-0000-000000009902'::uuid, alex_id, 'Matched from swipe.', 'visible', now() - interval '8 minutes'),
        ('00000000-0000-0000-0000-000000009913'::uuid, '00000000-0000-0000-0000-000000009903'::uuid, noa_id, 'Rae wants to join us.', 'visible', now() - interval '1 minutes'),
        ('00000000-0000-0000-0000-000000009914'::uuid, '00000000-0000-0000-0000-000000009904'::uuid, maya_id, 'Join us when you are ready.', 'visible', now() - interval '2 minutes');

    INSERT INTO partner_chat_invites (
        id,
        thread_id,
        inviter_user_id,
        invited_partner_user_id,
        third_party_status,
        partner_status,
        created_at,
        resolved_at
    )
    VALUES
        ('00000000-0000-0000-0000-000000009921'::uuid, '00000000-0000-0000-0000-000000009903'::uuid, noa_id, rae_id, 'pending', 'accepted', now() - interval '1 minutes', NULL),
        ('00000000-0000-0000-0000-000000009922'::uuid, '00000000-0000-0000-0000-000000009904'::uuid, maya_id, viewer_id, 'accepted', 'pending', now() - interval '2 minutes', NULL);
END
`$`$;
"@

    Invoke-DevSql $sql | Out-Null
    Write-Host "[ok] Seeded Messages QA conversations for dev@pynapli.local"
}

function Find-DebugApk {
    $candidateRoots = @(
        ".\androidApp\build\outputs\apk\debug",
        ".\androidApp\build\intermediates\apk\debug"
    )

    foreach ($root in $candidateRoots) {
        if (-not (Test-Path $root)) { continue }
        $apk = Get-ChildItem -Path $root -Filter "*.apk" |
            Sort-Object LastWriteTime -Descending |
            Select-Object -First 1
        if ($null -ne $apk) { return $apk }
    }

    return $null
}

function Start-App {
    $component = "$PackageName/$LaunchActivity"
    Grant-QaNotificationPermission -PackageName $PackageName
    for ($attempt = 1; $attempt -le 3; $attempt++) {
        Invoke-Adb @("shell", "am", "force-stop", $PackageName) | Out-Null
        Start-Sleep -Milliseconds 800
        $startOutput = Invoke-Adb @("shell", "am", "start", "-W", "-S", "-n", $component)
        $startText = ($startOutput | Out-String).Trim()
        if ($startText -match "Status:\s+(\S+)" -and $Matches[1] -notin @("ok", "timeout")) {
            throw "Unable to launch $component; Android returned $($Matches[1]). Output: $startText"
        }
        if ($startText -match "Status:\s+timeout") {
            Write-Host "[warn] Android launch wait timed out; continuing until the focused app window is visible."
        }
        Write-Host "[step] Launched $component (attempt $attempt)"

        $deadline = (Get-Date).AddSeconds(60)
        do {
            $processIdText = (& $script:Adb -s $script:DeviceId shell pidof $PackageName 2>$null | Out-String).Trim()
            $windowText = (& $script:Adb -s $script:DeviceId shell dumpsys window 2>$null | Select-String -Pattern "mCurrentFocus|mFocusedApp" | Out-String).Trim()
            if (
                -not [string]::IsNullOrWhiteSpace($processIdText) -and
                $windowText.Contains($PackageName)
            ) {
                return
            }
            Start-Sleep -Milliseconds 500
        } while ((Get-Date) -lt $deadline)
    }

    throw "Unable to launch $component; app process did not become the focused window."
}

function Complete-LoginIfNeeded {
    $landing = Wait-ForAnyText @("Dev login", "Grid Load", "Dense", "Filters") 120
    if ($landing.Text -eq "Dev login") {
        $loginXml = Dump-Ui "00_login" -Keep
        Assert-NoAppErrorText $loginXml "Login"
        $script:Screenshots.login = Save-Screen "00_login"
        Tap-Text "Dev login"

        Wait-ForAnyText @("Allow Pynapli", "Don", "Current location", "Filters", "Grid Load", "Dense") 45 | Out-Null
        Start-Sleep -Milliseconds 1200
        $afterLoginXml = Dump-Ui "after_login"
        $denyNode = Find-NodeByText $afterLoginXml "Don"
        if ($null -ne $denyNode) {
            $promptXml = Dump-Ui "01_notification_prompt" -Keep
            Assert-NoAppErrorText $promptXml "Notification prompt"
            $script:Screenshots.notificationPrompt = Save-Screen "01_notification_prompt"
            $bounds = Get-Bounds $denyNode
            Invoke-Adb @("shell", "input", "tap", "$($bounds.CenterX)", "$($bounds.CenterY)") | Out-Null
            Write-Host "[tap] notification prompt decline at $($bounds.CenterX),$($bounds.CenterY)"
        }
    }
}

function Capture-MessagesStage {
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
    Write-Host "Android Messages QA passed."
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
Set-PynapliQaContext -Adb $script:Adb -DeviceId $script:DeviceId -ArtifactDir $script:ArtifactDir -WaitSeconds $WaitSeconds -DumpMaxAttempts 60
New-Item -ItemType Directory -Force -Path $script:ArtifactDir | Out-Null
$script:Screenshots = @{}
$script:VisualSignatures = @{}

Write-Host "Pynapli Android Messages QA"
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
    throw "Ktor is not healthy at $BaseUrl/health/deep. Start the local server before running Android Messages QA. $($_.Exception.Message)"
}

if (-not $SkipSeed) {
    Write-Host "[step] Seeding dev data and emulator location"
    & pwsh -NoProfile -ExecutionPolicy Bypass -File ".\scripts\seed-dev-data.ps1" -BaseUrl $BaseUrl -SetAndroidEmulatorLocation -DeviceId $script:DeviceId
    if ($LASTEXITCODE -ne 0) { throw "Dev data seed failed." }
}

Write-Host "[step] Resetting and seeding Messages QA rows"
Reset-DevMessagesState

if (-not $SkipBuild) {
    Write-Host "[step] Building androidApp debug APK"
    .\gradlew.bat :androidApp:assembleDebug --console=plain
    if ($LASTEXITCODE -ne 0) { throw "Android debug build failed." }
}

$apk = Find-DebugApk
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

Tap-BottomNavText "Messages"
Wait-ForAnyText @("Messages", "Noa, Rae", "Maya, Alex", "Kai") 45 | Out-Null
$initialXml = Capture-MessagesStage "messagesInitial" "03_messages_initial"
Assert-TextPresent $initialXml "Partner invite needs your approval" "Initial Messages"
Assert-TextPresent $initialXml "You were invited to join this chat" "Initial Messages"

Tap-Text "Kai"
Wait-ForAnyText @("See you tonight") 30 | Out-Null
# Composer, not the nav tab — see Find-NodeByExactText.
Wait-ForContentText "Message" 30 | Out-Null
$normalThreadXml = Capture-MessagesStage "normalThread" "04_messages_normal_thread"
Assert-TextPresent $normalThreadXml "Kai" "Normal thread"

Tap-Text "Open profile"
Wait-ForAnyText @("Spark", "Open linked partner profile", "Private album") 30 | Out-Null
$threadProfilePeekXml = Capture-MessagesStage "threadProfilePeek" "04b_messages_thread_profile_peek"
Assert-TextPresent $threadProfilePeekXml "Kai" "Thread profile peek"
Tap-Text "Close profile"
# Wait for the composer itself to come back: that is what proves the profile peek actually closed.
# The old @("FROM GRID", "Message") short-circuited on the nav tab and waited for nothing, so the
# tap below could fire while the peek was still up.
Wait-ForContentText "Message" 30 | Out-Null

Tap-ContentText "Message"
Invoke-Adb @("shell", "input", "keyevent", "KEYCODE_Q") | Out-Null
Invoke-Adb @("shell", "input", "keyevent", "KEYCODE_A") | Out-Null
Wait-ForAnyText @("qa") 10 | Out-Null
Invoke-Adb @("shell", "input", "keyevent", "KEYCODE_BACK") | Out-Null
Start-Sleep -Milliseconds 700
Tap-Text "Send message"
Wait-ForAnyText @("Sent") 30 | Out-Null
$messageSentXml = Capture-MessagesStage "messageSent" "05_messages_sent"
Assert-TextPresent $messageSentXml "qa" "Message send"
Assert-TextPresent $messageSentXml "Sent" "Message send"

Tap-Text "Delete message"
# Deleting asks first ("Delete this message? It will be removed for everyone in this chat.").
Wait-ForAnyText @("Delete this message?") 15 | Out-Null
# Exact match: plain Tap-Text "Delete" would hit the dialog title "Delete this message?".
Tap-ContentText "Delete"
Wait-ForAnyText @("You deleted a message") 30 | Out-Null
$messageDeletedXml = Capture-MessagesStage "messageDeleted" "06_messages_deleted"
Assert-TextPresent $messageDeletedXml "You deleted a message" "Message delete"

Tap-Text "Back"
Wait-ForAnyText @("Messages", "Noa, Rae") 30 | Out-Null

Tap-Text "Accept"
Wait-ForAnyText @("Rae wants to join us.") 30 | Out-Null
$thirdPartyApprovedXml = Capture-MessagesStage "thirdPartyApproved" "07_messages_third_party_approved"
Assert-TextPresent $thirdPartyApprovedXml "Maya, Alex" "Third-party approved Messages"

Tap-Text "Maya, Alex"
Wait-ForAnyText @("You were invited to join this chat.", "Join us when you are ready.") 30 | Out-Null
$invitedPromptXml = Capture-MessagesStage "invitedPartnerPrompt" "08_messages_invited_partner_prompt"
Assert-TextPresent $invitedPromptXml "Decline" "Invited partner prompt"

Tap-Text "Accept"
# "Message" dropped: it matched the nav tab, so this returned before the accept had applied. The
# assertion below is the real check, and it now gets a settled screen to run against.
Wait-ForAnyText @("Join us when you are ready.") 30 | Out-Null
$invitedAcceptedXml = Capture-MessagesStage "invitedPartnerAccepted" "09_messages_invited_partner_accepted"
Assert-TextPresent $invitedAcceptedXml "Join us when you are ready." "Invited partner accepted"

Write-Summary @{
    passed = $true
    baseUrl = $BaseUrl
    deviceId = $script:DeviceId
    packageName = $PackageName
    apk = $apk.FullName
    proof = @{
        initialInbox = $true
        normalThreadOpened = $true
        threadProfilePeek = $true
        composerSentMessage = $true
        deletedMessagePlaceholder = $true
        thirdPartyApprovalCompleted = $true
        invitedPartnerAccepted = $true
    }
    visualSignatures = $script:VisualSignatures
    screenshots = $script:Screenshots
}
