# Connected-Android QA for the denied POST_NOTIFICATIONS permission path. Proves the app stays fully
# usable when the user denies notifications: it logs in, denies the notification prompt, reaches the
# Grid, shows no error/crash text, and POST_NOTIFICATIONS is left ungranted (the spec's "app works
# with notifications denied" rule). Mirrors scripts/android-grid-denied-location-qa.ps1.
param(
    [string]$BaseUrl = "http://127.0.0.1:8080",
    [string]$PackageName = "com.pynapli.app",
    [string]$LaunchActivity = ".DefaultLauncherAlias",
    [string]$DeviceId = "",
    [string]$OutputRoot = "artifacts\android-notification-permission-qa",
    [switch]$SkipSeed,
    [switch]$SkipBuild,
    [switch]$SkipInstall,
    [switch]$KeepAppData,
    [int]$WaitSeconds = 35
)

$ErrorActionPreference = "Stop"

# Shared UI-hierarchy helpers. These used to be copy-pasted into every QA script, which is
# how android-tonight-qa.ps1 ended up with a stale copy of the Grid card detection.
Import-Module (Join-Path $PSScriptRoot "lib\PynapliQaUi.psm1") -Force

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

function Invoke-AdbBestEffort {
    param([string[]]$Arguments)

    if ([string]::IsNullOrWhiteSpace($script:DeviceId)) {
        & $script:Adb @Arguments 2>&1 | Out-Null
    } else {
        & $script:Adb -s $script:DeviceId @Arguments 2>&1 | Out-Null
    }
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
        throw "No connected Android emulator/device found. Start an emulator or connect a device and retry."
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

    $visible = (Get-VisibleTexts $xml) -join " | "
    throw "Timed out waiting for one of: $($Texts -join ', '). Visible text: $visible"
}

function Tap-Text {
    param([string]$Text)

    $wait = Wait-ForAnyText @($Text)
    $node = Find-NodeByText $wait.Xml $Text
    $bounds = Get-Bounds $node
    Invoke-Adb @("shell", "input", "tap", "$($bounds.CenterX)", "$($bounds.CenterY)") | Out-Null
    Write-Host "[tap] '$Text' at $($bounds.CenterX),$($bounds.CenterY)"
}

function Tap-ExactText {
    param([string]$Text)

    $wait = Wait-ForAnyText @($Text)
    $node = Find-NodeByExactText $wait.Xml $Text
    if ($null -eq $node) {
        throw "Text '$Text' was visible but no exact node match was found."
    }
    $bounds = Get-Bounds $node
    Invoke-Adb @("shell", "input", "tap", "$($bounds.CenterX)", "$($bounds.CenterY)") | Out-Null
    Write-Host "[tap] '$Text' at $($bounds.CenterX),$($bounds.CenterY)"
}

function Tap-FirstAvailableText {
    param([string[]]$Texts)

    $wait = Wait-ForAnyText $Texts
    $node = Find-NodeByText $wait.Xml $wait.Text
    $bounds = Get-Bounds $node
    Invoke-Adb @("shell", "input", "tap", "$($bounds.CenterX)", "$($bounds.CenterY)") | Out-Null
    Write-Host "[tap] '$($wait.Text)' at $($bounds.CenterX),$($bounds.CenterY)"
    return $wait.Text
}

function Write-Summary {
    param([hashtable]$Summary)

    $path = Join-Path $script:ArtifactDir "qa-summary.json"
    ($Summary | ConvertTo-Json -Depth 6) | Set-Content -Path $path -Encoding UTF8
    Write-Host ""
    Write-Host "Android denied-notification-permission QA passed."
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

Write-Host "Pynapli Android denied-notification-permission QA"
Write-Host "------------------------------------------------"
Write-Host "Device: $script:DeviceId"
Write-Host "ADB: $script:Adb"
Write-Host "Artifacts: $script:ArtifactDir"

$health = Invoke-RestMethod -Uri "$BaseUrl/health/deep" -TimeoutSec 5
if ($health.status -ne "ok") {
    throw "Unexpected health status: $($health | ConvertTo-Json -Compress)"
}
Write-Host "[ok] Ktor health: $BaseUrl/health/deep"

if (-not $SkipSeed) {
    Write-Host "[step] Seeding dev data"
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
    Write-Host "[step] Clearing app data for fresh permission state"
    Invoke-Adb @("shell", "pm", "clear", $PackageName) | Out-Null
}

# Ensure notifications start denied/unrequested so the in-app prompt appears and we can deny it.
Invoke-AdbBestEffort @("shell", "pm", "revoke", $PackageName, "android.permission.POST_NOTIFICATIONS")
Invoke-AdbBestEffort @("shell", "pm", "clear-permission-flags", $PackageName, "android.permission.POST_NOTIFICATIONS", "user-set", "user-fixed")

$component = "$PackageName/$LaunchActivity"
Write-Host "[step] Launching $component"
Invoke-Adb @("shell", "am", "start", "-n", $component) | Out-Null

Wait-ForAnyText @("Dev login") | Out-Null
$loginXml = Dump-Ui "00_login" -Keep
Assert-NoAppErrorText $loginXml "Login"
$loginShot = Save-Screen "00_login"
Tap-Text "Dev login"

# The OS POST_NOTIFICATIONS prompt appears once after login on Android 13+. Deny it.
Wait-ForAnyText @("Allow", "Don", "Deny", "Use current location") 45 | Out-Null
Start-Sleep -Milliseconds 1000
$promptXml = Dump-Ui "01_notification_permission_prompt" -Keep
$notificationPromptShot = $null
$deniedButton = $null
if ($null -ne (Find-NodeByText $promptXml "Don") -or $null -ne (Find-NodeByText $promptXml "Deny")) {
    $notificationPromptShot = Save-Screen "01_notification_permission_prompt"
    $deniedButton = Tap-FirstAvailableText @("Don", "Deny")
} else {
    Write-Host "[note] No OS notification prompt surfaced (pre-Tiramisu or already handled); continuing."
}

# The app must remain fully usable with notifications denied: it should reach the Grid (or the
# location-required state) with no error/crash text.
Wait-ForAnyText @("Use current location", "Dense", "Grid", "Filters") 45 | Out-Null
Start-Sleep -Milliseconds 1000
$afterDenyXml = Dump-Ui "02_after_notification_denied" -Keep
Assert-NoAppErrorText $afterDenyXml "After notification denied"
$afterDenyShot = Save-Screen "02_after_notification_denied"

# Confirm POST_NOTIFICATIONS was NOT granted during the run.
$packageDumpPath = Join-Path $script:ArtifactDir "package-permissions.txt"
(Invoke-Adb @("shell", "dumpsys", "package", $PackageName) | Out-String) | Set-Content -Path $packageDumpPath -Encoding UTF8
$permissionDump = Get-Content -Raw -Path $packageDumpPath
if ($permissionDump -match "android\.permission\.POST_NOTIFICATIONS:\s+granted=true") {
    throw "POST_NOTIFICATIONS was granted during denied-notification QA. See $packageDumpPath"
}

Write-Summary @{
    passed = $true
    baseUrl = $BaseUrl
    deviceId = $script:DeviceId
    packageName = $PackageName
    deniedButton = $deniedButton
    visibleAfterDenyTexts = @(Get-VisibleTexts $afterDenyXml)
    artifacts = @{
        packagePermissions = $packageDumpPath
    }
    screenshots = @{
        login = $loginShot
        notificationPermissionPrompt = $notificationPromptShot
        afterNotificationDenied = $afterDenyShot
    }
}
