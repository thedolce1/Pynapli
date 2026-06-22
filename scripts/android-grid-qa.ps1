param(
    [string]$BaseUrl = "http://127.0.0.1:8080",
    [string]$PackageName = "com.pynapli.app",
    [string]$LaunchActivity = ".MainActivity",
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

try {
    Add-Type -AssemblyName System.Drawing
} catch {
    throw "System.Drawing is required for Android Grid screenshot signatures. Run this script on Windows with the local desktop tooling."
}

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
                return [xml](Get-Content -Raw -Path $local)
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

    $nodes = Get-UiTextNodes $Xml

    foreach ($node in $nodes) {
        $nodeText = $node.GetAttribute("text")
        if ($nodeText.Contains($Text)) {
            return $node
        }
    }

    foreach ($node in $nodes) {
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

function Tap-Text {
    param([string]$Text)

    $wait = Wait-ForAnyText @($Text)
    $node = Find-NodeByText $wait.Xml $Text
    $bounds = Get-Bounds $node
    Invoke-Adb @("shell", "input", "tap", "$($bounds.CenterX)", "$($bounds.CenterY)") | Out-Null
    Write-Host "[tap] '$Text' at $($bounds.CenterX),$($bounds.CenterY)"
}

function Scroll-SheetToText {
    param(
        [string]$Text,
        [int]$MaxSwipes = 6
    )

    for ($attempt = 0; $attempt -le $MaxSwipes; $attempt++) {
        $xml = Dump-Ui "sheet_scroll"
        if ($null -ne (Find-NodeByText $xml $Text)) {
            return $xml
        }
        # Swipe up within the sheet content (clear of the top drag handle) to reveal lower sections.
        Invoke-Adb @("shell", "input", "swipe", "540", "1700", "540", "760", "350") | Out-Null
        Start-Sleep -Milliseconds 600
    }

    throw "Could not scroll the filter sheet to reveal '$Text'."
}

function Get-FirstGridCardNode {
    param([xml]$Xml)

    $first = @(Get-GridCardSnapshot $Xml 1 | Select-Object -First 1)
    if ($first.Count -eq 0) {
        throw "No visible Grid card text was found."
    }

    return $first[0].Node
}

function Get-GridCardSnapshot {
    param(
        [xml]$Xml,
        [int]$Limit = 12
    )

    $chromeTexts = @(
        "Current location",
        "Filters",
        "Online",
        "Singles",
        "Linked",
        "Grid",
        "Swipe",
        "Activity",
        "Messages",
        "Store",
        "NEARBY"
    )

    $candidates = @(
        foreach ($node in Get-UiTextNodes $Xml) {
        $text = $node.GetAttribute("text")
        if ([string]::IsNullOrWhiteSpace($text)) {
            continue
        }
        if ($chromeTexts -contains $text) {
            continue
        }
        if ($text -match "^\s*(<=|≤)?\s*\d+(\.\d+)?\s*(m|km)\s*$") {
            continue
        }
        # Skip the density/context-strip count (a bare integer like "60"); card names are never
        # purely numeric.
        if ($text -match "^\s*\d+\s*$") {
            continue
        }

        $bounds = Get-Bounds $node
        if ($bounds.Top -lt 340 -or $bounds.Top -gt 2100) {
            continue
        }

        [pscustomobject]@{
            Node = $node
            Text = $text
            Top = $bounds.Top
            Left = $bounds.Left
            Right = $bounds.Right
            Bottom = $bounds.Bottom
        }
        }
    )

    @($candidates | Sort-Object Top, Left | Select-Object -First $Limit)
}

function Assert-GridSnapshotStable {
    param(
        [object[]]$Before,
        [object[]]$After,
        [string]$Stage,
        [int]$MinimumCards = 9,
        [int]$MaxPositionDeltaPx = 8
    )

    $count = [Math]::Min($Before.Count, $After.Count)
    if ($count -lt $MinimumCards) {
        throw "$Stage did not expose enough comparable Grid cards. Before=$($Before.Count) After=$($After.Count)"
    }

    for ($index = 0; $index -lt $MinimumCards; $index++) {
        $beforeCard = $Before[$index]
        $afterCard = $After[$index]
        if ($beforeCard.Text -ne $afterCard.Text) {
            throw "$Stage changed Grid card order at position $index. Before='$($beforeCard.Text)' After='$($afterCard.Text)'"
        }

        $leftDelta = [Math]::Abs([int]$beforeCard.Left - [int]$afterCard.Left)
        $topDelta = [Math]::Abs([int]$beforeCard.Top - [int]$afterCard.Top)
        if ($leftDelta -gt $MaxPositionDeltaPx -or $topDelta -gt $MaxPositionDeltaPx) {
            throw "$Stage moved Grid card '$($beforeCard.Text)' too far. LeftDelta=$leftDelta TopDelta=$topDelta"
        }
    }
}

function Tap-Node {
    param($Node)

    $bounds = Get-Bounds $Node
    Invoke-Adb @("shell", "input", "tap", "$($bounds.CenterX)", "$($bounds.CenterY)") | Out-Null
}

function Assert-NoAppErrorText {
    param(
        [xml]$Xml,
        [string]$Stage
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
        if ($allText.Contains($pattern)) {
            throw "$Stage displayed app error text matching '$pattern'. Visible text: $allText"
        }
    }
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
$script:DeviceId = $DeviceId
Select-AdbDevice

$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$script:ArtifactDir = Join-Path (Get-Location) (Join-Path $OutputRoot $timestamp)
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
Assert-TextPresent $filterSheetXml "Trust" "Filter sheet"
$filterSheetShot = Save-Screen "03_filters_sheet"
$filterSheetSignature = Assert-GoldenStage "filtersSheet" $filterSheetXml $filterSheetShot

# The profile-filter coherence work inserted Who/Gender/Orientation sections, so the Intent section
# and its chips now render below the fold at sheet open. Scroll to the deepest one ("Together", an
# Intent chip) and assert it — reaching it proves the Intent section rendered.
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

Tap-Node $firstAfterClear
Wait-ForAnyText @("Message", "Spark", "Request", "Link") 45 | Out-Null
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
