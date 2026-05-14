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

function Get-FirstGridCardNode {
    param([xml]$Xml)

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
        "Store"
    )

    $candidates = foreach ($node in Get-UiTextNodes $Xml) {
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

        $bounds = Get-Bounds $node
        if ($bounds.Top -lt 340 -or $bounds.Top -gt 2100) {
            continue
        }

        [pscustomobject]@{
            Node = $node
            Text = $text
            Top = $bounds.Top
            Left = $bounds.Left
        }
    }

    $first = @($candidates | Sort-Object Top, Left | Select-Object -First 1)
    if ($first.Count -eq 0) {
        throw "No visible Grid card text was found."
    }

    return $first[0].Node
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
$gridShot = Save-Screen "02_grid_initial"
Write-Host "[ok] First visible Grid card: $firstCardText"

Tap-Text "Filters"
Wait-ForAnyText @("Distance") | Out-Null
$filterSheetXml = Dump-Ui "03_filters_sheet" -Keep
Assert-NoAppErrorText $filterSheetXml "Filter sheet"
Assert-TextPresent $filterSheetXml "Presence" "Filter sheet"
Assert-TextPresent $filterSheetXml "Trust" "Filter sheet"
Assert-TextPresent $filterSheetXml "Intent" "Filter sheet"
$filterSheetShot = Save-Screen "03_filters_sheet"

Invoke-Adb @("shell", "input", "keyevent", "KEYCODE_BACK") | Out-Null
Wait-ForAnyText @($firstCardText, "Filters") | Out-Null

Tap-Text "Online"
Start-Sleep -Seconds 2
$onlineXml = Dump-Ui "04_grid_online_filter" -Keep
Assert-NoAppErrorText $onlineXml "Online Grid filter"
Assert-TextPresent $onlineXml "Filters" "Online Grid filter"
$onlineShot = Save-Screen "04_grid_online_filter"

Tap-Text "Online"
Wait-ForAnyText @($firstCardText) 45 | Out-Null
$clearedXml = Dump-Ui "05_grid_filter_cleared" -Keep
Assert-NoAppErrorText $clearedXml "Cleared Grid filter"
$firstAfterClear = Get-FirstGridCardNode $clearedXml
$firstAfterClearText = $firstAfterClear.GetAttribute("text")
if ($firstAfterClearText -ne $firstCardText) {
    throw "Filter clear did not restore the same first Grid card. Before='$firstCardText' After='$firstAfterClearText'"
}
$clearedShot = Save-Screen "05_grid_filter_cleared"

Tap-Node $firstAfterClear
Wait-ForAnyText @("Message", "Spark", "Request", "Link") 45 | Out-Null
$profileXml = Dump-Ui "06_profile_detail" -Keep
Assert-NoAppErrorText $profileXml "Profile detail"
Assert-TextPresent $profileXml $firstCardText "Profile detail"
Assert-TextPresent $profileXml "Message" "Profile detail"
$profileShot = Save-Screen "06_profile_detail"

Invoke-Adb @("shell", "input", "keyevent", "KEYCODE_BACK") | Out-Null
Wait-ForAnyText @($firstCardText, "Filters") 30 | Out-Null
$returnXml = Dump-Ui "07_grid_return_from_profile" -Keep
Assert-NoAppErrorText $returnXml "Returned Grid"
$firstAfterReturn = Get-FirstGridCardNode $returnXml
$firstAfterReturnText = $firstAfterReturn.GetAttribute("text")
if ($firstAfterReturnText -ne $firstCardText) {
    throw "Profile close did not return to the same Grid position. Before='$firstCardText' After='$firstAfterReturnText'"
}
$returnShot = Save-Screen "07_grid_return_from_profile"

Write-Summary @{
    passed = $true
    baseUrl = $BaseUrl
    deviceId = $script:DeviceId
    packageName = $PackageName
    apk = $apk.FullName
    firstGridCard = $firstCardText
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
