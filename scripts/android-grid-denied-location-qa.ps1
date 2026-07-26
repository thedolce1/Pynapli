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

function Find-NodeByExactText {
    param(
        [xml]$Xml,
        [string]$Text
    )

    foreach ($node in Get-UiTextNodes $Xml) {
        if ($node.GetAttribute("text") -eq $Text) {
            return $node
        }
    }

    foreach ($node in Get-UiTextNodes $Xml) {
        if ($node.GetAttribute("content-desc") -eq $Text) {
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
        [string]$Stage
    )

    $allText = (Get-VisibleTexts $Xml) -join "`n"
    $allowed = @(
        "Location permission is required to load nearby profiles.",
        "Use current location to load nearby profiles."
    )
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
        if ($allText.Contains($pattern) -and $allowed -notcontains $pattern) {
            throw "$Stage displayed app error text matching '$pattern'. Visible text: $allText"
        }
    }
}

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
$script:DeviceId = $DeviceId
Select-AdbDevice

$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$script:ArtifactDir = Join-Path (Get-Location) (Join-Path $OutputRoot $timestamp)
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
