param(
    [string]$BaseUrl = "http://127.0.0.1:8080",
    [string]$PackageName = "com.pynapli.app",
    [string]$LaunchActivity = ".MainActivity",
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

function Find-Adb {
    $command = Get-Command "adb" -ErrorAction SilentlyContinue
    if ($null -ne $command) {
        return $command.Source
    }

    $sdkCandidates = @(
        $env:ANDROID_HOME,
        $env:ANDROID_SDK_ROOT,
        (Join-Path $env:LOCALAPPDATA "Android\Sdk")
    ) | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }

    foreach ($sdk in $sdkCandidates) {
        $candidate = Join-Path $sdk "platform-tools\adb.exe"
        if (Test-Path $candidate) {
            return $candidate
        }
    }

    throw "adb was not found. Start Android Studio once or install Android SDK Platform Tools."
}

function Invoke-Adb {
    param([string[]]$Arguments)

    $output = $null
    if ([string]::IsNullOrWhiteSpace($script:DeviceId)) {
        $output = & $script:Adb @Arguments 2>&1
    } else {
        $output = & $script:Adb -s $script:DeviceId @Arguments 2>&1
    }

    if ($LASTEXITCODE -ne 0) {
        throw "adb failed: $($Arguments -join ' '). Output: $(($output | Out-String).Trim())"
    }

    return $output
}

function Select-AdbDevice {
    $lines = & $script:Adb devices
    if ($LASTEXITCODE -ne 0) {
        throw "Unable to list adb devices."
    }

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

    for ($attempt = 1; $attempt -le 5; $attempt++) {
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
                if ($attempt -eq 5) {
                    throw "Unable to parse Android UI hierarchy at $local"
                }
            }
        }

        Start-Sleep -Milliseconds 300
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
        if ($nodeText.Contains($Text)) {
            return $node
        }
    }

    foreach ($node in Get-UiTextNodes $Xml) {
        $nodeDescription = $node.GetAttribute("content-desc")
        if ($nodeDescription.Contains($Text)) {
            return $node
        }
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

function Find-BottomNavNodeByText {
    param(
        [xml]$Xml,
        [string]$Text
    )

    $screenHeight = Get-ScreenHeight $Xml
    $navTop = [int]($screenHeight * 0.82)
    $matches = @()

    foreach ($node in Get-UiTextNodes $Xml) {
        $nodeText = $node.GetAttribute("text")
        if ([string]::IsNullOrWhiteSpace($nodeText)) {
            $nodeText = $node.GetAttribute("content-desc")
        }

        if ($nodeText -eq $Text) {
            $bounds = Get-Bounds $node
            if ($bounds.CenterY -ge $navTop) {
                $matches += [pscustomobject]@{
                    Node = $node
                    Bounds = $bounds
                }
            }
        }
    }

    if ($matches.Count -eq 0) {
        return $null
    }

    return ($matches | Sort-Object { $_.Bounds.CenterY } -Descending | Select-Object -First 1).Node
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
                return [pscustomobject]@{
                    Text = $text
                    Xml = $xml
                }
            }
        }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)

    throw "Timed out waiting for one of: $($Texts -join ', ')"
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

function Assert-TextAbsent {
    param(
        [xml]$Xml,
        [string]$Text,
        [string]$Stage
    )

    if ($null -ne (Find-NodeByText $Xml $Text)) {
        $visible = (Get-VisibleTexts $Xml) -join " | "
        throw "$Stage showed forbidden text '$Text'. Visible text: $visible"
    }
}

function Assert-NoAppErrorText {
    param(
        [xml]$Xml,
        [string]$Stage,
        [string[]]$AllowedPatterns = @()
    )

    $allText = (Get-VisibleTexts $Xml) -join "`n"
    $errorPatterns = @(
        "Illegal input",
        "NETWORK_ERROR",
        "Network request failed",
        "Request failed",
        "UNAUTHENTICATED",
        "HTTP_",
        "Exception"
    )

    foreach ($pattern in $errorPatterns) {
        if ($AllowedPatterns -contains $pattern) {
            continue
        }
        if ($allText.Contains($pattern)) {
            throw "$Stage displayed app error text matching '$pattern'. Visible text: $allText"
        }
    }
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
    Wait-ForAnyText @("For You", "No more profiles nearby") 45 | Out-Null
    Start-Sleep -Milliseconds 900
    $xml = Dump-Ui "swipe_ready"
    Assert-NoAppErrorText $xml "Swipe ready"
    Assert-TextPresent $xml "For You" "Swipe ready"
    Assert-TextPresent $xml "Like" "Swipe ready"
    Assert-TextPresent $xml "Pass" "Swipe ready"
    Assert-TextAbsent $xml "No more profiles nearby" "Swipe ready"
    return $xml
}

function Open-LinkedSwipeCards {
    Tap-Text "Linked"
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
$script:DeviceId = $DeviceId
Select-AdbDevice

$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$script:ArtifactDir = Join-Path (Get-Location) (Join-Path $OutputRoot $timestamp)
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
    & pwsh -NoProfile -ExecutionPolicy Bypass -File ".\scripts\seed-dev-data.ps1" -BaseUrl $BaseUrl -SetAndroidEmulatorLocation
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
