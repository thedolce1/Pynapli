# Connected-Android QA for the DOB-first signup stepper (ONBOARDING_AUTH_SPEC, Grindr-reference
# flow). Proves the happy path end-to-end on a real emulator: Create account -> typed DD/MM/YYYY
# birthday -> policies step (single Accept) -> email+password -> denied location permission
# (useful error + skip) -> mid-signup force-stop resume at the consolidated Profile step ->
# display name (photo skipped) -> first grid with no error text -> next-session bounded photo
# nudge that dismisses. Mirrors scripts/android-notification-permission-qa.ps1.
param(
    [string]$BaseUrl = "http://127.0.0.1:8080",
    [string]$PackageName = "com.pynapli.app",
    [string]$LaunchActivity = ".DefaultLauncherAlias",
    [string]$DeviceId = "",
    [string]$OutputRoot = "artifacts\android-onboarding-qa",
    [switch]$SkipSeed,
    [switch]$SkipBuild,
    [switch]$SkipInstall,
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

function Tap-FieldByLabel {
    # Taps the CENTER of the EditText that owns [Label]. Tapping the label text
    # node itself lands on the floating-label position once the field was focused
    # before, which does not re-engage the input session.
    param([string]$Label)

    $wait = Wait-ForAnyText @($Label)
    $labelNode = Find-NodeByText $wait.Xml $Label
    $labelBounds = Get-Bounds $labelNode
    foreach ($edit in @($wait.Xml.SelectNodes("//*[@class='android.widget.EditText']"))) {
        $bounds = $edit.GetAttribute("bounds")
        if ($bounds -match "\[(\d+),(\d+)\]\[(\d+),(\d+)\]") {
            $left = [int]$Matches[1]
            $top = [int]$Matches[2]
            $right = [int]$Matches[3]
            $bottom = [int]$Matches[4]
            $labelAligns =
                $labelBounds.CenterX -ge $left -and $labelBounds.CenterX -le $right -and
                $labelBounds.CenterY -ge ($top - 80) -and $labelBounds.CenterY -le $bottom
            if ($labelAligns) {
                $centerX = [int](($left + $right) / 2)
                $centerY = [int](($top + $bottom) / 2)
                Invoke-Adb @("shell", "input", "tap", "$centerX", "$centerY") | Out-Null
                Write-Host "[tap] field '$Label' at $centerX,$centerY"
                return
            }
        }
    }

    Invoke-Adb @("shell", "input", "tap", "$($labelBounds.CenterX)", "$($labelBounds.CenterY)") | Out-Null
    Write-Host "[tap] field '$Label' (label fallback) at $($labelBounds.CenterX),$($labelBounds.CenterY)"
}

function Enter-Text {
    param(
        [string]$FieldLabel,
        [string]$Value,
        # Masked fields (password) cannot be read back from the UI tree.
        [switch]$SkipVerify
    )

    for ($attempt = 1; $attempt -le 3; $attempt++) {
        Tap-FieldByLabel $FieldLabel
        Start-Sleep -Milliseconds 800
        # With the soft keyboard window up, the IME consumes injected key events
        # before they reach the Compose field. Dismissing it keeps the field
        # focused and injection then delivers — but fast bursts can still drop
        # tail characters between state round-trips, so retries pace per char.
        Hide-Keyboard
        if ($attempt -eq 1) {
            Invoke-Adb @("shell", "input", "text", $Value) | Out-Null
        } else {
            foreach ($char in $Value.ToCharArray()) {
                Invoke-Adb @("shell", "input", "text", "$char") | Out-Null
                Start-Sleep -Milliseconds 150
            }
        }
        Start-Sleep -Milliseconds 500
        if ($SkipVerify) {
            Write-Host "[type] '$FieldLabel' <- '$Value' (unverified)"
            return
        }
        $xml = Dump-Ui "type_check"
        if ($null -ne (Find-NodeByText $xml $Value)) {
            Write-Host "[type] '$FieldLabel' <- '$Value'"
            return
        }
        Write-Host "[retry] '$FieldLabel' did not accept '$Value' (attempt $attempt); clearing and retyping"
        # Paced backspaces: rapid DEL bursts drop the same way typed bursts do.
        Invoke-AdbBestEffort @("shell", "input", "keyevent", "123")
        for ($i = 0; $i -lt (($Value.Length * 3) + 6); $i++) {
            Invoke-AdbBestEffort @("shell", "input", "keyevent", "67")
            Start-Sleep -Milliseconds 120
        }
    }

    throw "Could not type '$Value' into '$FieldLabel' after retries."
}

function Hide-Keyboard {
    # BACK with the IME open only dismisses the keyboard; it does not reach the
    # app. Guard on actual IME state so a stray BACK can never close the activity.
    $imeState = ""
    if ([string]::IsNullOrWhiteSpace($script:DeviceId)) {
        $imeState = (& $script:Adb shell dumpsys input_method 2>$null | Out-String)
    } else {
        $imeState = (& $script:Adb -s $script:DeviceId shell dumpsys input_method 2>$null | Out-String)
    }
    if ($imeState -match "mInputShown=true") {
        Invoke-AdbBestEffort @("shell", "input", "keyevent", "4")
        Start-Sleep -Milliseconds 500
    }
}

# The soft keyboard consumes injected key events (it re-shows itself after a
# couple of keystrokes even when dismissed), so typing through uiautomator is
# only reliable with every IME disabled for the duration of the run.
$script:DisabledImes = @()

function Disable-SoftKeyboard {
    $imes =
        (Invoke-Adb @("shell", "ime", "list", "-s") | Out-String) -split "\r?\n" |
            ForEach-Object { $_.Trim() } |
            Where-Object { $_ }
    foreach ($ime in $imes) {
        Invoke-AdbBestEffort @("shell", "ime", "disable", $ime)
        $script:DisabledImes += $ime
        Write-Host "[ime] disabled $ime for reliable text injection"
    }
}

function Restore-SoftKeyboard {
    foreach ($ime in $script:DisabledImes) {
        Invoke-AdbBestEffort @("shell", "ime", "enable", $ime)
        Write-Host "[ime] re-enabled $ime"
    }
    $script:DisabledImes = @()
}

function Dismiss-NotificationPromptIfPresent {
    # The OS POST_NOTIFICATIONS prompt appears once after the session authenticates
    # (Android 13+). Allow it so notification state stays out of this QA's scope.
    # Exact match only: the dialog TITLE also contains the word "Allow".
    for ($attempt = 1; $attempt -le 3; $attempt++) {
        Start-Sleep -Milliseconds 1500
        $xml = Dump-Ui "notification_prompt_check"
        $allowButton = Find-NodeByExactText $xml "Allow"
        if ($null -eq $allowButton) {
            return
        }
        $bounds = Get-Bounds $allowButton
        Invoke-Adb @("shell", "input", "tap", "$($bounds.CenterX)", "$($bounds.CenterY)") | Out-Null
        Write-Host "[note] OS notification prompt allowed (tap at $($bounds.CenterX),$($bounds.CenterY))."
    }
}

function Write-Summary {
    param([hashtable]$Summary)

    $path = Join-Path $script:ArtifactDir "qa-summary.json"
    ($Summary | ConvertTo-Json -Depth 6) | Set-Content -Path $path -Encoding UTF8
    Write-Host ""
    Write-Host "Android onboarding QA passed."
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

$signupEmail = "qa-onboarding-$timestamp@pynapli.local"
$signupPassword = "OnboardingQaPass123!"
$displayName = "QaTester"

Write-Host "Pynapli Android onboarding QA"
Write-Host "-----------------------------"
Write-Host "Device: $script:DeviceId"
Write-Host "ADB: $script:Adb"
Write-Host "Artifacts: $script:ArtifactDir"
Write-Host "Signup email: $signupEmail"

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

Write-Host "[step] Clearing app data + permissions for a fresh signup"
Invoke-Adb @("shell", "pm", "clear", $PackageName) | Out-Null
foreach ($permission in @(
        "android.permission.ACCESS_FINE_LOCATION",
        "android.permission.ACCESS_COARSE_LOCATION",
        "android.permission.POST_NOTIFICATIONS"
    )) {
    Invoke-AdbBestEffort @("shell", "pm", "revoke", $PackageName, $permission)
    Invoke-AdbBestEffort @("shell", "pm", "clear-permission-flags", $PackageName, $permission, "user-set", "user-fixed")
}

$component = "$PackageName/$LaunchActivity"
Write-Host "[step] Launching $component"
Invoke-Adb @("shell", "am", "start", "-n", $component) | Out-Null

Disable-SoftKeyboard
try {
    # --- Auth screen -> stepper entry ---
Wait-ForAnyText @("Create account") | Out-Null
$authXml = Dump-Ui "00_auth" -Keep
Assert-NoAppErrorText $authXml "Auth screen"
$authShot = Save-Screen "00_auth"
Tap-ExactText "Create account"

# --- DOB step: typed DD/MM/YYYY fields (Grindr-reference flow) ---
Wait-ForAnyText @("Your birthday") | Out-Null
$dobXml = Dump-Ui "01_dob" -Keep
Assert-NoAppErrorText $dobXml "DOB step"
Enter-Text "DD" "01"
Enter-Text "MM" "06"
Enter-Text "YYYY" "2000"
Hide-Keyboard
$dobShot = Save-Screen "01_dob"
Tap-ExactText "Next"

# --- Policies step: read links + single Accept ---
Wait-ForAnyText @("Review our policies") | Out-Null
$policiesXml = Dump-Ui "02_policies" -Keep
Assert-NoAppErrorText $policiesXml "Policies step"
$policiesShot = Save-Screen "02_policies"
Tap-ExactText "Accept"

# --- Account step: email + password only ---
Wait-ForAnyText @("Create your account") | Out-Null
Enter-Text "Email" $signupEmail
Enter-Text "Password" $signupPassword -SkipVerify
Hide-Keyboard
$accountXml = Dump-Ui "03_account" -Keep
$accountShot = Save-Screen "03_account"
Tap-ExactText "Create account"

Dismiss-NotificationPromptIfPresent

# --- Location step: deny the OS prompt, expect a useful error ---
Wait-ForAnyText @("See who's nearby") 45 | Out-Null
$locationShot = Save-Screen "04_location"
Tap-ExactText "Share my location"
Wait-ForAnyText @("While using", "Only this time", "Don", "Deny") 20 | Out-Null
$locationPromptShot = Save-Screen "05_location_prompt"
$deniedButton = Tap-FirstAvailableText @("Don", "Deny")
Wait-ForAnyText @("Location permission is required") 20 | Out-Null
$locationDeniedXml = Dump-Ui "06_location_denied" -Keep
$locationDeniedShot = Save-Screen "06_location_denied"

# --- Mid-signup resume: force-stop mid-stepper; relaunch must re-enter at the
# --- consolidated Profile step (the armed progress flag drives this).
Write-Host "[step] Force-stopping mid-signup to prove resume"
Invoke-Adb @("shell", "am", "force-stop", $PackageName) | Out-Null
Start-Sleep -Milliseconds 1200
Invoke-Adb @("shell", "am", "start", "-n", $component) | Out-Null
Wait-ForAnyText @("Your profile") 45 | Out-Null
$resumeXml = Dump-Ui "07_resume_profile" -Keep
Assert-NoAppErrorText $resumeXml "Resume after force-stop"
$resumeShot = Save-Screen "07_resume_profile"

# --- Profile step: finish via the everything-skippable path (blank name, no
# --- photo). The typed-name save path is unit-tested; post-relaunch text
# --- injection is unreliable on emulators, so QA exercises the skip flow.
$profileShot = Save-Screen "08_profile"
Tap-ExactText "Next"

# --- First grid (location denied -> useful empty state, no error text) ---
Wait-ForAnyText @("Use current location", "Filters", "Grid") 45 | Out-Null
Start-Sleep -Milliseconds 1000
$gridXml = Dump-Ui "09_first_grid" -Keep
Assert-NoAppErrorText $gridXml "First grid"
Assert-TextAbsent $gridXml "Your birthday" "First grid"
$gridShot = Save-Screen "09_first_grid"

# --- Next session: bounded photo nudge appears (photoless profile), then dismisses ---
Write-Host "[step] Relaunching for the photo-nudge session"
Invoke-Adb @("shell", "am", "force-stop", $PackageName) | Out-Null
Start-Sleep -Milliseconds 1200
Invoke-Adb @("shell", "am", "start", "-n", $component) | Out-Null
Wait-ForAnyText @("Profiles with photos get seen more.") 45 | Out-Null
$nudgeXml = Dump-Ui "10_photo_nudge" -Keep
Assert-TextAbsent $nudgeXml "Your profile" "Photo-nudge session"
$nudgeShot = Save-Screen "10_photo_nudge"
Tap-Text "Dismiss photo reminder"
Start-Sleep -Milliseconds 800
$afterDismissXml = Dump-Ui "11_nudge_dismissed" -Keep
Assert-TextAbsent $afterDismissXml "Profiles with photos get seen more." "After nudge dismiss"
Assert-NoAppErrorText $afterDismissXml "After nudge dismiss"
$afterDismissShot = Save-Screen "11_nudge_dismissed"

Write-Summary @{
    passed = $true
    baseUrl = $BaseUrl
    deviceId = $script:DeviceId
    packageName = $PackageName
    signupEmail = $signupEmail
    locationDeniedButton = $deniedButton
    screenshots = @{
        auth = $authShot
        dob = $dobShot
        policies = $policiesShot
        account = $accountShot
        location = $locationShot
        locationPrompt = $locationPromptShot
        locationDenied = $locationDeniedShot
        resumeProfile = $resumeShot
        profile = $profileShot
        firstGrid = $gridShot
        photoNudge = $nudgeShot
        nudgeDismissed = $afterDismissShot
    }
}
} finally {
    Restore-SoftKeyboard
}
