param(
    [string]$BaseUrl = "http://127.0.0.1:8080",
    [string]$PackageName = "com.pynapli.app",
    [string]$LaunchActivity = ".DefaultLauncherAlias",
    [string]$DeviceId = "",
    [string]$OutputRoot = "artifacts\android-grid-denied-location-qa",
    [switch]$SkipSeed,
    [switch]$SkipBuild,
    [switch]$SkipInstall,
    [switch]$KeepAppData,
    [switch]$SkipRestoreDevLocation,
    [int]$WaitSeconds = 35
)

$ErrorActionPreference = "Stop"

# Shared UI-hierarchy helpers. These used to be copy-pasted into every QA script, which is
# how android-tonight-qa.ps1 ended up with a stale copy of the Grid card detection.
Import-Module (Join-Path $PSScriptRoot "lib\PynapliQaUi.psm1") -Force -DisableNameChecking
Import-Module (Join-Path $PSScriptRoot "lib\PynapliQaDevice.psm1") -Force -DisableNameChecking

function Invoke-PostgresSql {
    param([string]$Sql)

    $docker = Get-Command docker -ErrorAction SilentlyContinue
    if ($null -eq $docker) {
        throw "Docker was not found; cannot prepare denied-location server state."
    }

    $output = & $docker.Source exec pynapli-postgres psql -U pynapli -d pynapli_dev -v ON_ERROR_STOP=1 -c $Sql 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "Postgres SQL failed: $output"
    }
    return $output
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

function Remove-DevViewerLocation {
    $emailHash = Get-Sha256Base64Url "dev@pynapli.local"
    $sql = @"
DELETE FROM user_locations
WHERE user_id = (
    SELECT id FROM users WHERE email_hash = '$emailHash'
);
"@
    $output = Invoke-PostgresSql $sql
    if (($output | Out-String) -notmatch "DELETE\s+1") {
        throw "Expected to remove exactly one dev viewer location row, but Postgres returned: $(($output | Out-String).Trim())"
    }
}

function Restore-DevLocation {
    if ($SkipRestoreDevLocation) {
        return
    }

    Write-Host "[cleanup] Restoring dev viewer location"
    & pwsh -NoProfile -ExecutionPolicy Bypass -File ".\scripts\seed-dev-data.ps1" -BaseUrl $BaseUrl -SetAndroidEmulatorLocation
    if ($LASTEXITCODE -ne 0) {
        throw "Dev location restore failed."
    }
}

function Write-Summary {
    param([hashtable]$Summary)

    $path = Join-Path $script:ArtifactDir "qa-summary.json"
    $Summary | ConvertTo-Json -Depth 6 | Set-Content -Path $path -Encoding UTF8
    Write-Host ""
    Write-Host "Android denied-location Grid QA passed."
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

Write-Host "Pynapli Android denied-location Grid QA"
Write-Host "--------------------------------------"
Write-Host "Device: $script:DeviceId"
Write-Host "ADB: $script:Adb"
Write-Host "Artifacts: $script:ArtifactDir"

$passed = $false
try {
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

    Write-Host "[step] Removing dev viewer server-side location"
    Remove-DevViewerLocation

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

    Invoke-AdbBestEffort @("shell", "pm", "revoke", $PackageName, "android.permission.ACCESS_FINE_LOCATION")
    Invoke-AdbBestEffort @("shell", "pm", "revoke", $PackageName, "android.permission.ACCESS_COARSE_LOCATION")
    Invoke-AdbBestEffort @("shell", "pm", "revoke", $PackageName, "android.permission.POST_NOTIFICATIONS")
    Invoke-AdbBestEffort @("shell", "pm", "clear-permission-flags", $PackageName, "android.permission.ACCESS_FINE_LOCATION", "user-set", "user-fixed")
    Invoke-AdbBestEffort @("shell", "pm", "clear-permission-flags", $PackageName, "android.permission.ACCESS_COARSE_LOCATION", "user-set", "user-fixed")
    Invoke-AdbBestEffort @("shell", "appops", "set", $PackageName, "android:fine_location", "default")
    Invoke-AdbBestEffort @("shell", "appops", "set", $PackageName, "android:coarse_location", "default")

    $component = "$PackageName/$LaunchActivity"
    Write-Host "[step] Launching $component"
    Invoke-Adb @("shell", "am", "start", "-n", $component) | Out-Null

    Wait-ForAnyText @("Dev login") | Out-Null
    $loginXml = Dump-Ui "00_login" -Keep
    Assert-NoAppErrorText $loginXml "Login"
    $loginShot = Save-Screen "00_login"
    Tap-Text "Dev login"

    Wait-ForAnyText @("Allow Pynapli", "Don", "Use current location") 45 | Out-Null
    Start-Sleep -Milliseconds 1000
    $afterLoginXml = Dump-Ui "after_login"
    $notificationShot = $null
    if ($null -ne (Find-NodeByText $afterLoginXml "Don")) {
        $notificationXml = Dump-Ui "01_notification_prompt" -Keep
        $notificationShot = Save-Screen "01_notification_prompt"
        Tap-Text "Don"
    }

    Wait-ForAnyText @("Use current location") 45 | Out-Null
    $locationRequiredXml = Dump-Ui "02_location_required" -Keep
    Assert-NoAppErrorText $locationRequiredXml "Location required state"
    Assert-TextPresent $locationRequiredXml "Use current location to load nearby profiles." "Location required state"
    Assert-TextPresent $locationRequiredXml "Use current location" "Location required state"
    Assert-TextAbsent $locationRequiredXml "Dense" "Location required state"
    Assert-TextAbsent $locationRequiredXml "Grid Load" "Location required state"
    $locationRequiredShot = Save-Screen "02_location_required"

    Tap-ExactText "Use current location"
    Wait-ForAnyText @("While using", "Only this time", "Don", "Deny") 30 | Out-Null
    $permissionPromptXml = Dump-Ui "03_location_permission_prompt" -Keep
    $permissionPromptShot = Save-Screen "03_location_permission_prompt"
    $deniedButton = Tap-FirstAvailableText @("Don", "Deny")

    Wait-ForAnyText @("Location permission is required to load nearby profiles.") 30 | Out-Null
    $deniedXml = Dump-Ui "04_location_denied" -Keep
    Assert-NoAppErrorText $deniedXml "Location denied state"
    Assert-TextPresent $deniedXml "Location permission is required to load nearby profiles." "Location denied state"
    Assert-TextPresent $deniedXml "Use current location" "Location denied state"
    Assert-TextAbsent $deniedXml "Dense" "Location denied state"
    Assert-TextAbsent $deniedXml "Grid Load" "Location denied state"
    $deniedShot = Save-Screen "04_location_denied"

    $packageDumpPath = Join-Path $script:ArtifactDir "package-permissions.txt"
    $appOpsPath = Join-Path $script:ArtifactDir "appops.txt"
    (Invoke-Adb @("shell", "dumpsys", "package", $PackageName) | Out-String) | Set-Content -Path $packageDumpPath -Encoding UTF8
    (Invoke-Adb @("shell", "appops", "get", $PackageName) | Out-String) | Set-Content -Path $appOpsPath -Encoding UTF8
    $permissionDump = Get-Content -Raw -Path $packageDumpPath
    if ($permissionDump -match "android\.permission\.ACCESS_FINE_LOCATION:\s+granted=true" -or
        $permissionDump -match "android\.permission\.ACCESS_COARSE_LOCATION:\s+granted=true") {
        throw "Location permission was granted during denied-location QA. See $packageDumpPath"
    }

    $passed = $true
    Write-Summary @{
        passed = $true
        baseUrl = $BaseUrl
        deviceId = $script:DeviceId
        packageName = $PackageName
        deniedButton = $deniedButton
        visibleDeniedTexts = @(Get-VisibleTexts $deniedXml)
        artifacts = @{
            packagePermissions = $packageDumpPath
            appOps = $appOpsPath
        }
        screenshots = @{
            login = $loginShot
            notificationPrompt = $notificationShot
            locationRequired = $locationRequiredShot
            locationPermissionPrompt = $permissionPromptShot
            locationDenied = $deniedShot
        }
    }
} finally {
    if (-not $SkipRestoreDevLocation) {
        Restore-DevLocation
    }
}
