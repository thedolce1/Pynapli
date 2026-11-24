# Connected-Android QA for the DOB-first signup stepper (ONBOARDING_AUTH_SPEC, Grindr-reference
# flow). Proves the happy path end-to-end on a real emulator: Create account -> typed DD/MM/YYYY
# birthday -> policies step (single Accept) -> email+password -> emailed 6-digit code (read from the
# local-only dev code lookup, as a developer would read .pynapli-local-mail) -> denied location permission
# (useful error + skip) -> mid-signup force-stop resume at the consolidated Profile step ->
# display name (photo skipped) -> first grid with no error text -> next-session bounded photo
# nudge that dismisses. Mirrors scripts/android-notification-permission-qa.ps1.
#
# Two separate paths, each a fresh install state and its own sign-up (-LocationPath picks; both by default):
# - denied: the flow above.
# - allowed: location permission granted up front and the emulator's GPS set inside the seeded area,
#   then sign-up -> "Share my location" -> Profile (skipped) -> the first grid must show REAL faces:
#   seeded display names as grid tiles. The "Use current location" empty state is a FAILURE here,
#   never a pass. It also reports time to first card from the app's PynapliPerf logcat markers
#   (create_account_tap / account_submit -> first_grid_card), the baseline later work must not regress.
param(
    [string]$BaseUrl = "http://127.0.0.1:8080",
    [string]$PackageName = "com.pynapli.app",
    [string]$LaunchActivity = ".DefaultLauncherAlias",
    [string]$DeviceId = "",
    [string]$OutputRoot = "artifacts\android-onboarding-qa",
    [switch]$SkipSeed,
    [switch]$SkipBuild,
    [switch]$SkipInstall,
    [ValidateSet("both", "denied", "allowed")]
    [string]$LocationPath = "both",
    # The allowed path's GPS fix: the dev viewer's spot in scripts\seed-dev-data.ps1, which every
    # seeded profile sits within a few hundred metres of.
    [double]$SeedLatitude = 37.4220,
    [double]$SeedLongitude = -122.0841,
    # Seeded grid tiles that must be on screen. Earlier allowed-path accounts also live in this cell
    # and can sort ahead of the seed, so the check scrolls a little before giving up.
    [int]$MinSeededTiles = 1,
    # Card text nodes (name + distance per tile) that also prove real faces when earlier QA or load-test
    # accounts in the same cell outrank the seeded names.
    [int]$MinGridCardTexts = 6,
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

function Get-SignupCode {
    # The code is written off the request path, so poll the local-only dev lookup
    # (POST /v1/dev/auth/email/signup/latest-code). It exists only on a PYNAPLI_ENV=local
    # server with PYNAPLI_PASSWORD_RESET_MAILER=log; anywhere else this fails loudly.
    param([string]$Email)

    for ($attempt = 1; $attempt -le 30; $attempt++) {
        try {
            $result = Invoke-RestMethod `
                -Method POST `
                -Uri "$BaseUrl/v1/dev/auth/email/signup/latest-code" `
                -ContentType "application/json" `
                -Body (@{ email = $Email } | ConvertTo-Json) `
                -TimeoutSec 5
        } catch {
            throw "Dev sign-up code lookup failed ($($_.Exception.Message)). It needs a local server started with PYNAPLI_DEV_SIGNUP_SHORTCUTS_ENABLED=true (PYNAPLI_ENV=local, PYNAPLI_PASSWORD_RESET_MAILER=log)."
        }
        if (-not [string]::IsNullOrWhiteSpace([string]$result.code)) {
            return [string]$result.code
        }
        Start-Sleep -Milliseconds 500
    }
    throw "No sign-up code was sent to $Email within 15 seconds."
}

function Write-Summary {
    param([hashtable]$Summary)

    $path = Join-Path $script:ArtifactDir "qa-summary.json"
    ($Summary | ConvertTo-Json -Depth 6) | Set-Content -Path $path -Encoding UTF8
    Write-Host ""
    Write-Host "Android onboarding QA passed ($LocationPath)."
    Write-Host "Artifact directory: $script:ArtifactDir"
    Write-Host "Summary: $path"
}

function Reset-AppForFreshSignup {
    # pm clear also resets runtime permissions; revoke + clear the flags so the OS prompts again,
    # then (allowed path) grant location up front so "Share my location" needs no prompt.
    param([switch]$GrantLocation)

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
    if ($GrantLocation) {
        foreach ($permission in @("android.permission.ACCESS_FINE_LOCATION", "android.permission.ACCESS_COARSE_LOCATION")) {
            Invoke-Adb @("shell", "pm", "grant", $PackageName, $permission) | Out-Null
            Write-Host "[ok] granted $permission"
        }
    }
}

function Set-EmulatorGeoFix {
    # `emu geo fix` takes LONGITUDE first. Invoke-Adb passes -s, which matters with two AVDs up.
    # adb can print "error:" and still exit 0, so check the text as well (seed-dev-data.ps1 does too).
    $culture = [System.Globalization.CultureInfo]::InvariantCulture
    $output = (Invoke-Adb @("emu", "geo", "fix", $SeedLongitude.ToString($culture), $SeedLatitude.ToString($culture)) | Out-String).Trim()
    if ($output -match "error:|KO") {
        throw "Could not set the emulator GPS fix (this path needs an emulator, not a phone). adb said: $output"
    }
    Write-Host "[ok] emulator GPS set to $SeedLatitude,$SeedLongitude (seeded area)"
}

function Start-App {
    Write-Host "[step] Launching $script:Component"
    Invoke-Adb @("shell", "am", "start", "-n", $script:Component) | Out-Null
}

function Complete-SignupToLocationStep {
    # Landing -> birthday -> policies -> email + password -> emailed code -> the Location step.
    # [Prefix] keeps each path's artifacts apart ("" keeps the denied path's historical names).
    param(
        [string]$Email,
        [string]$Prefix
    )

    $shots = @{}

    # --- Auth screen -> stepper entry ---
    Wait-ForAnyText @("Create account") | Out-Null
    $authXml = Dump-Ui "${Prefix}00_auth" -Keep
    Assert-NoAppErrorText $authXml "Auth screen"
    $shots.auth = Save-Screen "${Prefix}00_auth"
    Tap-ExactText "Create account"

    # --- DOB step: phones show the day/month/year wheel; its "type instead" link opens the typed
    # --- DD/MM/YYYY fields, which injection can drive reliably (the wheel itself is unit-tested).
    Wait-ForAnyText @("Your birthday") | Out-Null
    $dobXml = Dump-Ui "${Prefix}01_dob" -Keep
    Assert-NoAppErrorText $dobXml "DOB step"
    $shots.dobWheel = Save-Screen "${Prefix}01_dob_wheel"
    Tap-ExactText "Type your birthday instead"
    Wait-ForAnyText @("DD") | Out-Null
    Enter-Text "DD" "01"
    Enter-Text "MM" "06"
    Enter-Text "YYYY" "2000"
    Hide-Keyboard
    $shots.dob = Save-Screen "${Prefix}01_dob"
    Tap-ExactText "Next"

    # --- Policies step: read links + single Accept ---
    Wait-ForAnyText @("Review our policies") | Out-Null
    $policiesXml = Dump-Ui "${Prefix}02_policies" -Keep
    Assert-NoAppErrorText $policiesXml "Policies step"
    $shots.policies = Save-Screen "${Prefix}02_policies"
    Tap-ExactText "Accept"

    # --- Account step: email + password only ---
    Wait-ForAnyText @("Create your account") | Out-Null
    Enter-Text "Email" $Email
    Enter-Text "Password" $signupPassword -SkipVerify
    Hide-Keyboard
    Dump-Ui "${Prefix}03_account" -Keep | Out-Null
    $shots.account = Save-Screen "${Prefix}03_account"
    Tap-ExactText "Create account"

    # --- Code step: no session exists yet; the emailed code creates the account ---
    Wait-ForAnyText @("Check your email") 30 | Out-Null
    $codeXml = Dump-Ui "${Prefix}03b_check_email" -Keep
    Assert-NoAppErrorText $codeXml "Check-your-email step"
    $signupCode = Get-SignupCode $Email
    Write-Host "[ok] Sign-up code received from the local dev mailer"
    # The sixth digit submits on its own and the step moves on, so the typed code can't be read
    # back (and there is no Verify to tap afterwards).
    Enter-Text "6-digit code" $signupCode -SkipVerify
    $shots.checkEmail = Save-Screen "${Prefix}03b_check_email"
    Hide-Keyboard

    # The session (and with it the OS notification prompt) only exists after the code is verified.
    Dismiss-NotificationPromptIfPresent

    Wait-ForAnyText @("See who's nearby") 45 | Out-Null
    $shots.location = Save-Screen "${Prefix}04_location"
    return $shots
}

function Invoke-DeniedLocationPath {
    Reset-AppForFreshSignup
    Start-App
    $shots = Complete-SignupToLocationStep -Email $script:DeniedEmail -Prefix ""

    # --- Location step: deny the OS prompt, expect a useful error ---
    Tap-ExactText "Share my location"
    Wait-ForAnyText @("While using", "Only this time", "Don", "Deny") 20 | Out-Null
    $shots.locationPrompt = Save-Screen "05_location_prompt"
    $deniedButton = Tap-FirstAvailableText @("Don", "Deny")
    Wait-ForAnyText @("Location permission is required") 20 | Out-Null
    Dump-Ui "06_location_denied" -Keep | Out-Null
    $shots.locationDenied = Save-Screen "06_location_denied"

    # --- Mid-signup resume: force-stop mid-stepper; relaunch must re-enter at the
    # --- consolidated Profile step (the armed progress flag drives this).
    Write-Host "[step] Force-stopping mid-signup to prove resume"
    Invoke-Adb @("shell", "am", "force-stop", $PackageName) | Out-Null
    Start-Sleep -Milliseconds 1200
    Start-App
    Wait-ForAnyText @("Your profile") 45 | Out-Null
    $resumeXml = Dump-Ui "07_resume_profile" -Keep
    Assert-NoAppErrorText $resumeXml "Resume after force-stop"
    $shots.resumeProfile = Save-Screen "07_resume_profile"

    # --- Profile step: finish via the everything-skippable path (blank name, no
    # --- photo). The typed-name save path is unit-tested; post-relaunch text
    # --- injection is unreliable on emulators, so QA exercises the skip flow.
    $shots.profile = Save-Screen "08_profile"
    Tap-ExactText "Done"

    # --- First grid (location denied -> useful empty state, no error text) ---
    Wait-ForAnyText @("Use current location", "Filters", "Grid") 45 | Out-Null
    Start-Sleep -Milliseconds 1000
    $gridXml = Dump-Ui "09_first_grid" -Keep
    Assert-NoAppErrorText $gridXml "First grid"
    Assert-TextAbsent $gridXml "Your birthday" "First grid"
    $shots.firstGrid = Save-Screen "09_first_grid"

    # --- Next session: bounded photo nudge appears (photoless profile), then dismisses ---
    Write-Host "[step] Relaunching for the photo-nudge session"
    Invoke-Adb @("shell", "am", "force-stop", $PackageName) | Out-Null
    Start-Sleep -Milliseconds 1200
    Start-App
    Wait-ForAnyText @("Profiles with photos get seen more.") 45 | Out-Null
    $nudgeXml = Dump-Ui "10_photo_nudge" -Keep
    Assert-TextAbsent $nudgeXml "Your profile" "Photo-nudge session"
    $shots.photoNudge = Save-Screen "10_photo_nudge"
    Tap-Text "Dismiss photo reminder"
    Start-Sleep -Milliseconds 800
    $afterDismissXml = Dump-Ui "11_nudge_dismissed" -Keep
    Assert-TextAbsent $afterDismissXml "Profiles with photos get seen more." "After nudge dismiss"
    Assert-NoAppErrorText $afterDismissXml "After nudge dismiss"
    $shots.nudgeDismissed = Save-Screen "11_nudge_dismissed"

    return @{
        signupEmail = $script:DeniedEmail
        locationDeniedButton = $deniedButton
        screenshots = $shots
    }
}

# Every seeded display name (scripts\seed-dev-data.ps1); "Seb Dev" is the dev viewer, a member too.
$script:SeededNames = @("Maya", "Alex", "Nico", "Jules", "Kai", "Rae", "Theo", "Noa", "Seb Dev")

function Get-SeededTileNames {
    # Exact text matches only: these names are short enough to be substrings of other copy.
    param([xml]$Xml)

    @(
        foreach ($name in $script:SeededNames) {
            foreach ($node in Get-UiTextNodes $Xml) {
                if ($node.GetAttribute("text") -eq $name) {
                    $name
                    break
                }
            }
        }
    )
}

function Wait-ForSeededGrid {
    # Polls the first grid until seeded faces show. "Use current location" (the no-location empty
    # state) or a location error fails at once: on this path location was granted, so either means
    # the member's own location never reached the server.
    param([int]$TimeoutSeconds = 45)

    $failureTexts = @(
        "Use current location",
        "Location permission is required",
        "Current location is not available yet."
    )
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $xml = $null
    do {
        $xml = Dump-Ui "allowed_grid_wait"
        foreach ($text in $failureTexts) {
            if ($null -ne (Find-NodeByText $xml $text)) {
                Save-Screen "a09_FAILED_no_location" | Out-Null
                $visible = (Get-VisibleTexts $xml) -join " | "
                throw "Location-allowed path reached '$text' instead of real grid tiles. Visible text: $visible"
            }
        }
        $names = @(Get-SeededTileNames $xml)
        $cardTexts = @(Get-GridCardSnapshot $xml 60).Count
        if ($names.Count -gt 0 -or $cardTexts -ge $MinGridCardTexts) {
            return [pscustomobject]@{ Xml = $xml; Names = $names }
        }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)

    $visible = if ($null -ne $xml) { (Get-VisibleTexts $xml) -join " | " } else { "<no dump>" }
    throw "No seeded grid tile appeared within $TimeoutSeconds s on the location-allowed path. Visible text: $visible"
}

function Read-PerfMarkers {
    # The app logs `PynapliPerf: <name> t=<elapsedRealtime ms>` once per process (PynapliPerfMarks).
    # The last line per marker wins: it belongs to the newest process (this path's), even when
    # `logcat -c` could not clear the denied path's lines.
    $lines = @(Invoke-Adb @("logcat", "-d", "-s", "PynapliPerf:I"))
    $marks = @{}
    foreach ($line in $lines) {
        if ("$line" -match "PynapliPerf\s*:\s*([a-z_]+) t=(\d+)") {
            $marks[$Matches[1]] = [long]$Matches[2]
        }
    }
    return $marks
}

function Invoke-AllowedLocationPath {
    Reset-AppForFreshSignup -GrantLocation
    Set-EmulatorGeoFix
    # Only this process's markers: no force-stop between the Create account tap and the first card.
    Invoke-AdbBestEffort @("logcat", "-c")
    Start-App
    $shots = Complete-SignupToLocationStep -Email $script:AllowedEmail -Prefix "a"

    # --- Location step: permission is already granted, so no OS prompt; the shared fix advances
    # --- the stepper on its own.
    Tap-ExactText "Share my location"
    $afterShare = Wait-ForAnyText @("Your profile", "Location permission is required", "Current location is not available yet.") 30
    if ($afterShare.Text -ne "Your profile") {
        $shots.locationFailed = Save-Screen "a05_location_failed"
        throw "Sharing a granted location did not reach the Profile step: '$($afterShare.Text)'."
    }
    $profileXml = Dump-Ui "a07_profile" -Keep
    Assert-NoAppErrorText $profileXml "Profile step (location allowed)"
    $shots.profile = Save-Screen "a07_profile"
    Tap-ExactText "Done"

    # --- First grid: real faces, not the location empty state ---
    $grid = Wait-ForSeededGrid 45
    Start-Sleep -Milliseconds 1000
    $gridXml = Dump-Ui "a09_first_grid" -Keep
    Assert-NoAppErrorText $gridXml "First grid (location allowed)"
    Assert-TextAbsent $gridXml "Your birthday" "First grid (location allowed)"
    Assert-TextAbsent $gridXml "Use current location" "First grid (location allowed)"
    $shots.firstGrid = Save-Screen "a09_first_grid"

    # Tiles: seeded names on the first screen, scrolling a little when earlier QA accounts in the same
    # cell crowd them below the fold. The snapshot count is any card text in the grid band, so it is a
    # floor-style sanity number; the seeded names are the proof.
    $seenNames = [System.Collections.Generic.HashSet[string]]::new()
    foreach ($name in @(Get-SeededTileNames $gridXml)) { [void]$seenNames.Add($name) }
    $tileTexts = @(Get-GridCardSnapshot $gridXml 60).Count
    $screen = Get-ScreenSize $gridXml
    for ($scroll = 1; $scroll -le 3 -and $seenNames.Count -lt $MinSeededTiles; $scroll++) {
        $x = [int]($screen.Width / 2)
        Invoke-Adb @("shell", "input", "swipe", "$x", "$([int]($screen.Height * 0.75))", "$x", "$([int]($screen.Height * 0.35))", "400") | Out-Null
        Start-Sleep -Milliseconds 1200
        $scrolledXml = Dump-Ui "a09_first_grid_scroll$scroll" -Keep
        Assert-TextAbsent $scrolledXml "Use current location" "First grid scroll $scroll (location allowed)"
        foreach ($name in @(Get-SeededTileNames $scrolledXml)) { [void]$seenNames.Add($name) }
    }
    if ($seenNames.Count -lt $MinSeededTiles -and $tileTexts -lt $MinGridCardTexts) {
        throw "Only $($seenNames.Count) seeded grid tile(s) found ($(@($seenNames) -join ', ')) and $tileTexts card text node(s); expected at least $MinSeededTiles seeded tile(s) or $MinGridCardTexts card text nodes."
    }
    Write-Host "[ok] First grid shows real cards: seeded [$(@($seenNames) -join ', ')], $tileTexts card text nodes on the first screen"

    # --- Time to first card (PynapliPerf logcat markers) ---
    $marks = Read-PerfMarkers
    foreach ($required in @("create_account_tap", "first_grid_card")) {
        if (-not $marks.ContainsKey($required)) {
            throw "PynapliPerf marker '$required' was not logged. Is the installed APK older than the markers? Found: $(@($marks.Keys) -join ', ')"
        }
    }
    $fromTapMs = $marks["first_grid_card"] - $marks["create_account_tap"]
    $fromSubmitMs = if ($marks.ContainsKey("account_submit")) { $marks["first_grid_card"] - $marks["account_submit"] } else { $null }
    Write-Host "[perf] Create account (landing) -> first grid card: $fromTapMs ms (includes scripted typing and the code lookup)"
    if ($null -ne $fromSubmitMs) {
        Write-Host "[perf] Create account (Account step) -> first grid card: $fromSubmitMs ms"
    }

    return @{
        signupEmail = $script:AllowedEmail
        seededTiles = @($seenNames)
        firstScreenCardTextNodes = $tileTexts
        timeToFirstCardMs = @{
            fromCreateAccountTap = $fromTapMs
            fromAccountSubmit = $fromSubmitMs
        }
        screenshots = $shots
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

# One unique address per path and per run: each path signs up a brand-new account.
$script:DeniedEmail = "qa-onboarding-$timestamp@pynapli.local"
$script:AllowedEmail = "qa-onboarding-allowed-$timestamp@pynapli.local"
$signupPassword = "OnboardingQaPass123!"

Write-Host "Pynapli Android onboarding QA"
Write-Host "-----------------------------"
Write-Host "Device: $script:DeviceId"
Write-Host "ADB: $script:Adb"
Write-Host "Artifacts: $script:ArtifactDir"
Write-Host "Location paths: $LocationPath"

$health = Invoke-RestMethod -Uri "$BaseUrl/health/deep" -TimeoutSec 5
if ($health.status -ne "ok") {
    throw "Unexpected health status: $($health | ConvertTo-Json -Compress)"
}
Write-Host "[ok] Ktor health: $BaseUrl/health/deep"

# The emailed code is read from the local-only dev lookup, which is off by default.
Import-Module (Join-Path $PSScriptRoot "lib\PynapliDevSignup.psm1") -Force -DisableNameChecking
Assert-DevSignupShortcuts -BaseUrl $BaseUrl

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

$script:Component = "$PackageName/$LaunchActivity"

Disable-SoftKeyboard
try {
    $summary = @{
        passed = $true
        baseUrl = $BaseUrl
        deviceId = $script:DeviceId
        packageName = $PackageName
        locationPath = $LocationPath
    }
    if ($LocationPath -in @("both", "denied")) {
        Write-Host ""
        Write-Host "== Path: location DENIED ($script:DeniedEmail) =="
        $summary.denied = Invoke-DeniedLocationPath
    }
    if ($LocationPath -in @("both", "allowed")) {
        Write-Host ""
        Write-Host "== Path: location ALLOWED ($script:AllowedEmail) =="
        $summary.allowed = Invoke-AllowedLocationPath
    }
    Write-Summary $summary
} finally {
    Restore-SoftKeyboard
}
