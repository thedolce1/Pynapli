param(
    [string]$BaseUrl = "http://127.0.0.1:8080",
    [string]$PackageName = "com.pynapli.app",
    [string]$LaunchActivity = ".MainActivity",
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

try {
    Add-Type -AssemblyName System.Drawing
} catch {
    throw "System.Drawing is required for Android Activity screenshot signatures. Run this script on Windows with the local desktop tooling."
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

    for ($attempt = 1; $attempt -le 12; $attempt++) {
        $dumpOutput = & $script:Adb -s $script:DeviceId shell uiautomator dump $remote 2>&1
        $dumpExit = $LASTEXITCODE
        $lastDumpOutput = ($dumpOutput | Out-String).Trim()

        if ($dumpExit -eq 0 -and $lastDumpOutput -notmatch "ERROR:\s+null root") {
            & $script:Adb -s $script:DeviceId pull $remote $local 2>$null | Out-Null
            if ($LASTEXITCODE -ne 0 -or -not (Test-Path $local)) {
                throw "Unable to pull Android UI hierarchy."
            }

            try {
                $parsed = [xml](Get-Content -Raw -Path $local)
                # Heavy host load can pop the system "isn't responding" ANR over the app mid-render.
                # Tap "Wait" and re-dump so callers never see the ANR frame instead of the real screen.
                $anrNode = $parsed.SelectNodes("//node[contains(@text,'responding') or contains(@content-desc,'responding')]") | Select-Object -First 1
                if ($null -ne $anrNode) {
                    $waitNode = $parsed.SelectNodes("//node[@text='Wait' or @content-desc='Wait']") | Select-Object -First 1
                    if ($null -ne $waitNode -and $waitNode.GetAttribute("bounds") -match "\[(\d+),(\d+)\]\[(\d+),(\d+)\]") {
                        $tapX = [int](([int]$Matches[1] + [int]$Matches[3]) / 2)
                        $tapY = [int](([int]$Matches[2] + [int]$Matches[4]) / 2)
                        & $script:Adb -s $script:DeviceId shell input tap $tapX $tapY 2>$null | Out-Null
                        Write-Host "[tap] 'Wait (ANR)' at $tapX,$tapY"
                        Start-Sleep -Milliseconds 1200
                        continue
                    }
                }
                return $parsed
            } catch {
                if ($attempt -eq 12) {
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
    $errorPatterns = @("Illegal input", "NETWORK_ERROR", "Network request failed", "Request failed", "UNAUTHENTICATED", "HTTP_", "Exception")
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
        $dark = 0
        $bright = 0
        $nonBlack = 0

        for ($y = 0; $y -lt $bitmap.Height; $y += $stepY) {
            for ($x = 0; $x -lt $bitmap.Width; $x += $stepX) {
                $color = $bitmap.GetPixel($x, $y)
                $sampleCount += 1
                $luma = (0.2126 * $color.R) + (0.7152 * $color.G) + (0.0722 * $color.B)
                if ($luma -lt 45) { $dark += 1 }
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
            darkRatio = [Math]::Round($dark / [double]$sampleCount, 4)
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
        throw "Docker was not found; Android Activity QA needs direct dev database seeding."
    }

    $output = & $docker.Source exec pynapli-postgres psql -U pynapli -d pynapli_dev -t -A -v ON_ERROR_STOP=1 -c $Sql 2>&1
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
$script:DeviceId = $DeviceId
Select-AdbDevice

$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$script:ArtifactDir = Join-Path (Get-Location) (Join-Path $OutputRoot $timestamp)
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
    & pwsh -NoProfile -ExecutionPolicy Bypass -File ".\scripts\seed-dev-data.ps1" -BaseUrl $BaseUrl -SetAndroidEmulatorLocation
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
# Default tab is Flirts (unified inbound interest: taps + swipe-likes + pineapples + intro notes —
# the premium reveal money surface). Matches has its own tab (all swipe matches); album/invite
# requests are folded into Requests. Flirts/Views/Matches render as a 2-up FOMO grid.
$initialXml = Capture-ActivityStage "flirtsTab" "03_activity_flirts"
Assert-TextPresent $initialXml "Alex" "Initial Activity (Flirts)"

Tap-ActivityTabText "Views"
Wait-ForAnyText @("Maya", "viewed you") 20 | Out-Null
Capture-ActivityStage "viewsTab" "04_activity_views" | Out-Null

Tap-ActivityTabText "Matches"
Wait-ForAnyText @("Jules") 20 | Out-Null
Capture-ActivityStage "matchesTab" "05_activity_matches" | Out-Null

Tap-ActivityTabText "Requests"
Wait-ForAnyText @("Rae", "Review", "Noa", "Open invite") 20 | Out-Null
Capture-ActivityStage "requestsTab" "06_activity_requests" | Out-Null

# After visiting every tab the app should have marked the loaded rows seen in the backend.
# Flirts(flirt+swipe_like=2) + Views(1) + Matches(1) + Requests(album+invite=2) = 6.
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
