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
Import-Module (Join-Path $PSScriptRoot "lib\PynapliQaUi.psm1") -Force -DisableNameChecking
Import-Module (Join-Path $PSScriptRoot "lib\PynapliQaDevice.psm1") -Force -DisableNameChecking

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
$script:DeviceId = Resolve-QaDeviceId -Adb $script:Adb -RequestedDeviceId $DeviceId
# API level in the banner: this suite ran only on android-36 for months with nothing in its
# output saying so, which is how an edge-to-edge defect stayed invisible on API <=34.
Write-Host "Device: $script:DeviceId (API $(Get-QaDeviceApiLevel -Adb $script:Adb -DeviceId $script:DeviceId))"

$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$script:ArtifactDir = Join-Path (Get-Location) (Join-Path $OutputRoot $timestamp)
# Hand the shared adb helpers their per-run state. Must follow the artifact-dir assignment.
Set-PynapliQaContext -Adb $script:Adb -DeviceId $script:DeviceId -ArtifactDir $script:ArtifactDir -WaitSeconds $WaitSeconds -DumpMaxAttempts 12
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
    & pwsh -NoProfile -ExecutionPolicy Bypass -File ".\scripts\seed-dev-data.ps1" -BaseUrl $BaseUrl -SetAndroidEmulatorLocation -DeviceId $script:DeviceId
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
