param(
    [string]$BaseUrl = "http://127.0.0.1:8080",
    [string]$PackageName = "com.pynapli.app",
    [string]$LaunchActivity = ".MainActivity",
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

try {
    Add-Type -AssemblyName System.Drawing
} catch {
    throw "System.Drawing is required for Android Messages screenshot signatures. Run this script on Windows with the local desktop tooling."
}

function Find-Adb {
    $command = Get-Command "adb" -ErrorAction SilentlyContinue
    if ($null -ne $command) { return $command.Source }

    $sdkCandidates = @(
        $env:ANDROID_HOME,
        $env:ANDROID_SDK_ROOT,
        (Join-Path $env:LOCALAPPDATA "Android\Sdk")
    ) | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }

    foreach ($sdk in $sdkCandidates) {
        $candidate = Join-Path $sdk "platform-tools\adb.exe"
        if (Test-Path $candidate) { return $candidate }
    }

    throw "adb was not found. Start Android Studio once or install Android SDK Platform Tools."
}

function Invoke-Adb {
    param([string[]]$Arguments)

    $output = & $script:Adb -s $script:DeviceId @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "adb failed: $($Arguments -join ' '). Output: $(($output | Out-String).Trim())"
    }
    return $output
}

function Select-AdbDevice {
    $lines = & $script:Adb devices
    if ($LASTEXITCODE -ne 0) { throw "Unable to list adb devices." }

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
        throw "No connected Android emulator/device found. Start an emulator and retry."
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
    param(
        [string]$Name,
        [switch]$Keep
    )

    $remote = "/sdcard/window.xml"
    $fileName = if ($Keep) { "$Name.xml" } else { "_latest.xml" }
    $local = Join-Path $script:ArtifactDir $fileName
    $lastDumpOutput = ""

    for ($attempt = 1; $attempt -le 60; $attempt++) {
        $previousErrorActionPreference = $ErrorActionPreference
        $ErrorActionPreference = "Continue"
        try {
            $dumpOutput = & $script:Adb -s $script:DeviceId shell uiautomator dump $remote 2>&1
            $dumpExit = $LASTEXITCODE
        } finally {
            $ErrorActionPreference = $previousErrorActionPreference
        }
        $lastDumpOutput = ($dumpOutput | Out-String).Trim()

        if ($dumpExit -eq 0 -and $lastDumpOutput -notmatch "ERROR:\s+null root") {
            & $script:Adb -s $script:DeviceId pull $remote $local 2>$null | Out-Null
            if ($LASTEXITCODE -ne 0 -or -not (Test-Path $local)) {
                throw "Unable to pull Android UI hierarchy."
            }

            try {
                return [xml](Get-Content -Raw -Path $local)
            } catch {
                if ($attempt -eq 60) {
                    throw "Unable to parse Android UI hierarchy at $local"
                }
            }
        }

        Start-Sleep -Milliseconds 500
    }

    throw "Unable to dump Android UI hierarchy after retries. Last output: $lastDumpOutput"
}

function Get-UiTextNodes {
    param([xml]$Xml)

    @($Xml.SelectNodes("//*[@text or @content-desc]"))
}

function Get-VisibleTexts {
    param([xml]$Xml)

    Get-UiTextNodes $Xml |
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

    foreach ($node in Get-UiTextNodes $Xml) {
        $nodeText = $node.GetAttribute("text")
        if ($nodeText.Contains($Text)) { return $node }
    }
    foreach ($node in Get-UiTextNodes $Xml) {
        $nodeDescription = $node.GetAttribute("content-desc")
        if ($nodeDescription.Contains($Text)) { return $node }
    }
    return $null
}

function Get-Bounds {
    param($Node)

    $bounds = $Node.GetAttribute("bounds")
    if ($bounds -notmatch "\[(\d+),(\d+)\]\[(\d+),(\d+)\]") {
        throw "Invalid node bounds: $bounds"
    }

    return [pscustomobject]@{
        Left = [int]$Matches[1]
        Top = [int]$Matches[2]
        Right = [int]$Matches[3]
        Bottom = [int]$Matches[4]
        CenterX = [int](([int]$Matches[1] + [int]$Matches[3]) / 2)
        CenterY = [int](([int]$Matches[2] + [int]$Matches[4]) / 2)
    }
}

function Get-ScreenSize {
    param([xml]$Xml)

    $root = $Xml.SelectSingleNode("/hierarchy/node")
    if ($null -ne $root) {
        $bounds = Get-Bounds $root
        return [pscustomobject]@{ Width = $bounds.Right; Height = $bounds.Bottom }
    }

    $right = 0
    $bottom = 0
    foreach ($node in $Xml.SelectNodes("//*[@bounds]")) {
        $bounds = Get-Bounds $node
        if ($bounds.Right -gt $right) { $right = $bounds.Right }
        if ($bounds.Bottom -gt $bottom) { $bottom = $bounds.Bottom }
    }
    if ($right -le 0 -or $bottom -le 0) {
        throw "Unable to determine Android screen size from UI hierarchy."
    }
    return [pscustomobject]@{ Width = $right; Height = $bottom }
}

function Find-BottomNavNodeByText {
    param(
        [xml]$Xml,
        [string]$Text
    )

    $screen = Get-ScreenSize $Xml
    $navTop = [int]($screen.Height * 0.82)
    $matches = @()

    foreach ($node in Get-UiTextNodes $Xml) {
        $nodeText = $node.GetAttribute("text")
        if ([string]::IsNullOrWhiteSpace($nodeText)) {
            $nodeText = $node.GetAttribute("content-desc")
        }
        if ($nodeText -eq $Text) {
            $bounds = Get-Bounds $node
            if ($bounds.CenterY -ge $navTop) {
                $matches += [pscustomobject]@{ Node = $node; Bounds = $bounds }
            }
        }
    }

    if ($matches.Count -eq 0) { return $null }
    return ($matches | Sort-Object { $_.Bounds.CenterY } -Descending | Select-Object -First 1).Node
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
                return [pscustomobject]@{ Text = $text; Xml = $xml }
            }
        }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)

    throw "Timed out waiting for one of: $($Texts -join ', ')"
}

function Tap-Text {
    param(
        [string]$Text,
        [int]$TimeoutSeconds = $script:WaitSeconds
    )

    $wait = Wait-ForAnyText @($Text) $TimeoutSeconds
    $node = Find-NodeByText $wait.Xml $Text
    $bounds = Get-Bounds $node
    Invoke-Adb @("shell", "input", "tap", "$($bounds.CenterX)", "$($bounds.CenterY)") | Out-Null
    Write-Host "[tap] '$Text' at $($bounds.CenterX),$($bounds.CenterY)"
}

function Tap-BottomNavText {
    param(
        [string]$Text,
        [int]$TimeoutSeconds = $script:WaitSeconds
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $xml = Dump-Ui "wait_bottom_nav"
        $node = Find-BottomNavNodeByText $xml $Text
        if ($null -ne $node) {
            $bounds = Get-Bounds $node
            Invoke-Adb @("shell", "input", "tap", "$($bounds.CenterX)", "$($bounds.CenterY)") | Out-Null
            Write-Host "[tap-nav] '$Text' at $($bounds.CenterX),$($bounds.CenterY)"
            return
        }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)

    $visible = (Get-VisibleTexts $xml) -join " | "
    throw "Timed out waiting for bottom navigation item '$Text'. Visible text: $visible"
}

function Assert-TextPresent {
    param(
        [xml]$Xml,
        [string]$Text,
        [string]$Stage
    )

    if ($null -eq (Find-NodeByText $Xml $Text)) {
        $visible = (Get-VisibleTexts $Xml) -join " | "
        throw "$Stage did not show expected text '$Text'. Visible text: $visible"
    }
}

function Assert-NoAppErrorText {
    param(
        [xml]$Xml,
        [string]$Stage
    )

    $allText = (Get-VisibleTexts $Xml) -join "`n"
    $errorPatterns = @(
        "Something went wrong",
        "Illegal input",
        "NETWORK_ERROR",
        "Network request failed",
        "Request failed",
        "UNAUTHENTICATED",
        "HTTP_",
        "Exception"
    )
    foreach ($pattern in $errorPatterns) {
        if ($allText.Contains($pattern)) {
            throw "$Stage displayed app error text matching '$pattern'. Visible text: $allText"
        }
    }
}

function Get-ImageSignature {
    param([string]$Path)

    $bitmap = [System.Drawing.Bitmap]::new($Path)
    $buckets = @{}
    try {
        $stepX = [Math]::Max(1, [int][Math]::Floor($bitmap.Width / 96))
        $stepY = [Math]::Max(1, [int][Math]::Floor($bitmap.Height / 160))
        $sampleCount = 0
        $bright = 0
        $nonBlack = 0

        for ($y = 0; $y -lt $bitmap.Height; $y += $stepY) {
            for ($x = 0; $x -lt $bitmap.Width; $x += $stepX) {
                $color = $bitmap.GetPixel($x, $y)
                $sampleCount += 1
                $luma = (0.2126 * $color.R) + (0.7152 * $color.G) + (0.0722 * $color.B)
                if ($luma -gt 210) { $bright += 1 }
                if ($luma -gt 15) { $nonBlack += 1 }
                $bucket = "$([int]($color.R / 32)):$([int]($color.G / 32)):$([int]($color.B / 32))"
                $buckets[$bucket] = $true
            }
        }

        return [pscustomobject]@{
            width = $bitmap.Width
            height = $bitmap.Height
            sampleCount = $sampleCount
            brightRatio = [Math]::Round($bright / [double]$sampleCount, 4)
            nonBlackRatio = [Math]::Round($nonBlack / [double]$sampleCount, 4)
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

    if ($null -eq $StageSpec.image) { return $null }
    $signature = Get-ImageSignature $ScreenshotPath
    $checks = @{
        minWidth = "width"
        minHeight = "height"
        minNonBlackRatio = "nonBlackRatio"
        maxBrightRatio = "brightRatio"
        minColorBucketCount = "colorBucketCount"
    }

    foreach ($check in $checks.GetEnumerator()) {
        $expected = $StageSpec.image.($check.Key)
        if ($null -eq $expected) { continue }

        $actual = $signature.($check.Value)
        if ($check.Key.StartsWith("max")) {
            if ([double]$actual -gt [double]$expected) {
                throw "$StageName screenshot signature exceeded $($check.Key): actual=$actual expectedMax=$expected path=$ScreenshotPath"
            }
        } elseif ([double]$actual -lt [double]$expected) {
            throw "$StageName screenshot signature missed $($check.Key): actual=$actual expectedMin=$expected path=$ScreenshotPath"
        }
    }

    return $signature
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

    foreach ($text in @($stageSpec.requiredTexts)) {
        Assert-TextPresent $Xml $text "$StageName golden"
    }

    $visibleText = (Get-VisibleTexts $Xml) -join "`n"
    foreach ($text in @($stageSpec.forbiddenTexts)) {
        if (-not [string]::IsNullOrWhiteSpace($text) -and $visibleText.Contains($text)) {
            throw "$StageName golden displayed forbidden text '$text'. Visible text: $visibleText"
        }
    }

    return Assert-ImageSignature $StageName $ScreenshotPath $stageSpec
}

function Get-Sha256Base64Url {
    param([string]$Value)

    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        $bytes = [System.Text.Encoding]::UTF8.GetBytes($Value.Trim().ToLowerInvariant())
        $hash = $sha.ComputeHash($bytes)
        return [Convert]::ToBase64String($hash).TrimEnd("=").Replace("+", "-").Replace("/", "_")
    } finally {
        $sha.Dispose()
    }
}

function Invoke-DevSql {
    param([string]$Sql)

    $docker = Get-Command docker -ErrorAction SilentlyContinue
    if ($null -eq $docker) {
        throw "Docker was not found; Android Messages QA needs direct dev database seeding."
    }

    $output = & $docker.Source exec pynapli-postgres psql -U pynapli -d pynapli_dev -t -A -v ON_ERROR_STOP=1 -c $Sql 2>&1
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
$script:DeviceId = $DeviceId
Select-AdbDevice

$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$script:ArtifactDir = Join-Path (Get-Location) (Join-Path $OutputRoot $timestamp)
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
    & pwsh -NoProfile -ExecutionPolicy Bypass -File ".\scripts\seed-dev-data.ps1" -BaseUrl $BaseUrl -SetAndroidEmulatorLocation
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
Wait-ForAnyText @("Message") 30 | Out-Null
$normalThreadXml = Capture-MessagesStage "normalThread" "04_messages_normal_thread"
Assert-TextPresent $normalThreadXml "Kai" "Normal thread"

Tap-Text "Open profile"
Wait-ForAnyText @("Spark", "Open linked partner profile", "Private album") 30 | Out-Null
$threadProfilePeekXml = Capture-MessagesStage "threadProfilePeek" "04b_messages_thread_profile_peek"
Assert-TextPresent $threadProfilePeekXml "Kai" "Thread profile peek"
Tap-Text "Close profile"
Wait-ForAnyText @("FROM GRID", "Message") 30 | Out-Null

Tap-Text "Message"
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
Wait-ForAnyText @("Join us when you are ready.", "Message") 30 | Out-Null
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
