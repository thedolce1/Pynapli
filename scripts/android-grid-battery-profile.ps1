param(
    [string]$BaseUrl = "http://127.0.0.1:8080",
    [string]$PackageName = "com.pynapli.app",
    [string]$LaunchActivity = ".DefaultLauncherAlias",
    [string]$DeviceId = "",
    [string]$OutputRoot = "artifacts\android-grid-battery-profile",
    [int]$ForegroundMinutes = 10,
    [int]$BackgroundSeconds = 120,
    [switch]$AllowEmulator,
    [switch]$SkipSeed,
    [switch]$SkipBuild,
    [switch]$SkipInstall,
    [switch]$KeepAppData,
    [switch]$SimulateUnplug
)

$ErrorActionPreference = "Stop"

# Shared UI-hierarchy helpers. These used to be copy-pasted into every QA script, which is
# how android-tonight-qa.ps1 ended up with a stale copy of the Grid card detection.
Import-Module (Join-Path $PSScriptRoot "lib\PynapliQaUi.psm1") -Force -DisableNameChecking
Import-Module (Join-Path $PSScriptRoot "lib\PynapliQaDevice.psm1") -Force -DisableNameChecking

function Save-CommandOutput {
    param(
        [string]$Name,
        [string[]]$Arguments
    )

    $path = Join-Path $script:ArtifactDir $Name
    try {
        (Invoke-Adb $Arguments | Out-String) | Set-Content -Path $path -Encoding UTF8
    } catch {
        "FAILED: $($_.Exception.Message)" | Set-Content -Path $path -Encoding UTF8
    }
    return $path
}

function Get-DeviceInfo {
    $serial = $script:DeviceId
    $model = (Invoke-Adb @("shell", "getprop", "ro.product.model") | Out-String).Trim()
    $manufacturer = (Invoke-Adb @("shell", "getprop", "ro.product.manufacturer") | Out-String).Trim()
    $androidVersion = (Invoke-Adb @("shell", "getprop", "ro.build.version.release") | Out-String).Trim()
    $sdk = (Invoke-Adb @("shell", "getprop", "ro.build.version.sdk") | Out-String).Trim()
    $qemu = (Invoke-Adb @("shell", "getprop", "ro.kernel.qemu") | Out-String).Trim()
    $isEmulator = $serial.StartsWith("emulator-") -or $qemu -eq "1" -or $model -match "sdk|emulator"

    [pscustomobject]@{
        serial = $serial
        model = $model
        manufacturer = $manufacturer
        androidVersion = $androidVersion
        sdk = $sdk
        isEmulator = $isEmulator
    }
}

function Parse-BatteryDump {
    param([string]$Path)

    $text = Get-Content -Raw -Path $Path
    $level = $null
    $usbPowered = $null
    $acPowered = $null
    if ($text -match "level:\s+(\d+)") {
        $level = [int]$Matches[1]
    }
    if ($text -match "USB powered:\s+(true|false)") {
        $usbPowered = [bool]::Parse($Matches[1])
    }
    if ($text -match "AC powered:\s+(true|false)") {
        $acPowered = [bool]::Parse($Matches[1])
    }

    [pscustomobject]@{
        level = $level
        usbPowered = $usbPowered
        acPowered = $acPowered
    }
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

$deviceInfo = Get-DeviceInfo
if ($deviceInfo.isEmulator -and -not $AllowEmulator) {
    throw "Connected device '$($deviceInfo.serial)' is an emulator. Battery/location profiling needs a physical Android device. Rerun with -AllowEmulator only to smoke-test the harness."
}

Write-Host "Pynapli Android Grid battery/location profile"
Write-Host "--------------------------------------------"
Write-Host "Device: $($deviceInfo.serial) $($deviceInfo.manufacturer) $($deviceInfo.model) Android $($deviceInfo.androidVersion)"
Write-Host "Artifacts: $script:ArtifactDir"
if ($deviceInfo.isEmulator) {
    Write-Host "[warn] Emulator smoke mode; results are not valid real-device battery evidence."
}

$health = Invoke-RestMethod -Uri "$BaseUrl/health/deep" -TimeoutSec 5
if ($health.status -ne "ok") {
    throw "Unexpected health status: $($health | ConvertTo-Json -Compress)"
}
Write-Host "[ok] Ktor health: $BaseUrl/health/deep"

if (-not $SkipSeed) {
    Write-Host "[step] Seeding dev Grid data and emulator location when applicable"
    $seedArgs = @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", ".\scripts\seed-dev-data.ps1", "-BaseUrl", $BaseUrl)
    if ($deviceInfo.isEmulator) {
        $seedArgs += "-SetAndroidEmulatorLocation"
    }
    & pwsh @seedArgs
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
    Write-Host "[step] Clearing app data"
    Invoke-Adb @("shell", "pm", "clear", $PackageName) | Out-Null
}

Invoke-AdbBestEffort @("shell", "pm", "grant", $PackageName, "android.permission.ACCESS_FINE_LOCATION")
Invoke-AdbBestEffort @("shell", "pm", "grant", $PackageName, "android.permission.ACCESS_COARSE_LOCATION")
Invoke-AdbBestEffort @("shell", "pm", "revoke", $PackageName, "android.permission.POST_NOTIFICATIONS")

Write-Host "[step] Resetting battery stats"
Invoke-AdbBestEffort @("shell", "dumpsys", "batterystats", "--reset")
if ($SimulateUnplug) {
    Invoke-AdbBestEffort @("shell", "cmd", "battery", "unplug")
}

$batteryBeforePath = Save-CommandOutput "battery-before.txt" @("shell", "dumpsys", "battery")
$packagePath = Save-CommandOutput "package-permissions.txt" @("shell", "dumpsys", "package", $PackageName)
$locationBeforePath = Save-CommandOutput "location-before.txt" @("shell", "dumpsys", "location")

$component = "$PackageName/$LaunchActivity"
Write-Host "[step] Launching $component"
Invoke-Adb @("shell", "am", "start", "-n", $component) | Out-Null

Wait-ForAnyText @("Dev login") | Out-Null
Tap-Text "Dev login"
Wait-ForAnyText @("Don", "Grid Load", "Dense") 45 | Out-Null
Start-Sleep -Milliseconds 1000
$afterLoginXml = Dump-Ui "after-login"
if ($null -ne (Find-NodeByText $afterLoginXml "Don")) {
    Tap-Text "Don"
}

Wait-ForAnyText @("Grid Load", "Dense") 45 | Out-Null
$gridShot = Save-Screen "grid-foreground-start"
$gridUiPath = Join-Path $script:ArtifactDir "grid-foreground-start.xml"
$gridXml = Dump-Ui "grid-foreground-start"
$gridXml.Save($gridUiPath)

$foregroundSeconds = [Math]::Max(15, $ForegroundMinutes * 60)
Write-Host "[step] Foreground observation for $foregroundSeconds seconds"
Start-Sleep -Seconds $foregroundSeconds

$batteryForegroundPath = Save-CommandOutput "battery-after-foreground.txt" @("shell", "dumpsys", "battery")
$batteryStatsForegroundPath = Save-CommandOutput "batterystats-after-foreground.txt" @("shell", "dumpsys", "batterystats", "--charged", $PackageName)
$locationForegroundPath = Save-CommandOutput "location-after-foreground.txt" @("shell", "dumpsys", "location")
$servicesForegroundPath = Save-CommandOutput "activity-services-foreground.txt" @("shell", "dumpsys", "activity", "services", $PackageName)

Write-Host "[step] Background observation for $BackgroundSeconds seconds"
Invoke-Adb @("shell", "input", "keyevent", "KEYCODE_HOME") | Out-Null
Start-Sleep -Seconds $BackgroundSeconds

$batteryBackgroundPath = Save-CommandOutput "battery-after-background.txt" @("shell", "dumpsys", "battery")
$batteryStatsBackgroundPath = Save-CommandOutput "batterystats-after-background.txt" @("shell", "dumpsys", "batterystats", "--charged", $PackageName)
$locationBackgroundPath = Save-CommandOutput "location-after-background.txt" @("shell", "dumpsys", "location")
$servicesBackgroundPath = Save-CommandOutput "activity-services-background.txt" @("shell", "dumpsys", "activity", "services", $PackageName)

Invoke-Adb @("shell", "am", "start", "-n", $component) | Out-Null
Wait-ForAnyText @("Grid Load", "Dense", "Filters") 30 | Out-Null
$returnShot = Save-Screen "grid-return-after-background"

if ($SimulateUnplug) {
    Invoke-AdbBestEffort @("shell", "cmd", "battery", "reset")
}

$batteryBefore = Parse-BatteryDump $batteryBeforePath
$batteryForeground = Parse-BatteryDump $batteryForegroundPath
$batteryBackground = Parse-BatteryDump $batteryBackgroundPath

$packageDump = Get-Content -Raw -Path $packagePath
if ($packageDump -match "android\.permission\.ACCESS_BACKGROUND_LOCATION:\s+granted=true") {
    throw "Background location is granted. See $packagePath"
}

$backgroundLocationDump = Get-Content -Raw -Path $locationBackgroundPath
$backgroundMentionsPackage = $backgroundLocationDump.Contains($PackageName)

$summaryPath = Join-Path $script:ArtifactDir "battery-profile-summary.json"
$summary = @{
    passed = $true
    realDeviceEvidence = -not $deviceInfo.isEmulator
    emulatorSmokeOnly = $deviceInfo.isEmulator
    manualReviewRequired = $true
    baseUrl = $BaseUrl
    packageName = $PackageName
    device = $deviceInfo
    foregroundSeconds = $foregroundSeconds
    backgroundSeconds = $BackgroundSeconds
    battery = @{
        before = $batteryBefore
        afterForeground = $batteryForeground
        afterBackground = $batteryBackground
    }
    backgroundLocationDumpMentionsPackage = $backgroundMentionsPackage
    screenshots = @{
        gridForegroundStart = $gridShot
        gridReturnAfterBackground = $returnShot
    }
    artifacts = @{
        batteryBefore = $batteryBeforePath
        packagePermissions = $packagePath
        locationBefore = $locationBeforePath
        batteryAfterForeground = $batteryForegroundPath
        batteryStatsAfterForeground = $batteryStatsForegroundPath
        locationAfterForeground = $locationForegroundPath
        servicesForeground = $servicesForegroundPath
        batteryAfterBackground = $batteryBackgroundPath
        batteryStatsAfterBackground = $batteryStatsBackgroundPath
        locationAfterBackground = $locationBackgroundPath
        servicesBackground = $servicesBackgroundPath
    }
}
$summary | ConvertTo-Json -Depth 8 | Set-Content -Path $summaryPath -Encoding UTF8

Write-Host ""
Write-Host "Android Grid battery/location profile completed."
Write-Host "Artifact directory: $script:ArtifactDir"
Write-Host "Summary: $summaryPath"
if ($deviceInfo.isEmulator) {
    Write-Host "[warn] This was emulator smoke evidence only. Run on a physical Android device before beta."
}
