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
Import-Module (Join-Path $PSScriptRoot "lib\PynapliQaUi.psm1") -Force -DisableNameChecking
Import-Module (Join-Path $PSScriptRoot "lib\PynapliQaDevice.psm1") -Force -DisableNameChecking

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
    # The OS POST_NOTIFICATIONS prompt (Android 13+) appears only AFTER the session authenticates,
    # which lags the account-creation tap by a network round-trip. Poll for it and CONTINUE on a
    # miss — an earlier version returned on the first miss, so whenever the prompt was slower than
    # one 1.5s tick it was never dismissed and then blocked the rest of the run behind a modal
    # ("Timed out waiting for one of: See who's nearby"). Matches the proven shape in
    # android-secure-token-qa.ps1. Exact match: the dialog TITLE also contains the word "Allow".
    for ($attempt = 1; $attempt -le 10; $attempt++) {
        Start-Sleep -Milliseconds 1500
        $xml = Dump-Ui "notification_prompt_check"
        $allowButton = Find-NodeByExactText $xml "Allow"
        if ($null -eq $allowButton) {
            continue
        }
        $bounds = Get-Bounds $allowButton
        Invoke-Adb @("shell", "input", "tap", "$($bounds.CenterX)", "$($bounds.CenterY)") | Out-Null
        Write-Host "[note] OS notification prompt allowed (tap at $($bounds.CenterX),$($bounds.CenterY))."
        return
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
$script:DeviceId = Resolve-QaDeviceId -Adb $script:Adb -RequestedDeviceId $DeviceId

$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$script:ArtifactDir = Join-Path (Get-Location) (Join-Path $OutputRoot $timestamp)
# Hand the shared adb helpers their per-run state. Must follow the artifact-dir assignment.
Set-PynapliQaContext -Adb $script:Adb -DeviceId $script:DeviceId -ArtifactDir $script:ArtifactDir -WaitSeconds $WaitSeconds -DumpMaxAttempts 12
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
