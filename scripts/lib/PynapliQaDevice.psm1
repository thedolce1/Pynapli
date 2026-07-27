<#
.SYNOPSIS
    Shared adb / device-interaction helpers for the android-*-qa.ps1 connected QA scripts.

.DESCRIPTION
    Second tranche of the QA-harness deduplication (the first, PynapliQaUi.psm1, took the pure
    UI-hierarchy layer). These functions all touch per-run state — the adb path, the target device,
    the artifact directory, the default wait — which is exactly why they were left behind the first
    time: a PowerShell module has its OWN `$script:` scope, so simply moving a function that reads
    `$script:Adb` would silently resolve it to $null.

    That is solved here by giving the module explicit state, set once via Set-PynapliQaContext.
    Scripts still keep their own $script:Adb / $script:DeviceId / $script:ArtifactDir because each
    script uses them ~20 times inline outside these helpers; Set-PynapliQaContext mirrors the same
    values into the module. Call it AFTER all three are known — the scripts' uniform bootstrap order
    is adb -> device -> artifact dir, so it goes directly after the artifact-dir assignment.

    Canonical bodies are supersets of what the 15 scripts had, so migrating is additive:

    - Dump-Ui: 9 versions existed, differing in retry cap (5/12/60), ANR dismissal, and the -Keep
      switch. The canonical one always has ANR dismissal (android-grid-qa.ps1 and the two battery
      profilers previously lacked it and would simply fail when the system "isn't responding" dialog
      appeared under host load) and takes its retry cap from context, so android-messages-qa.ps1 can
      keep its hardened 60.
    - Tap-Node: was ($Node) in grid/tonight and ($Node, $Label) with logging in media/profile.
      $Label is now optional and only logs when supplied, matching both.
    - Tap-ExactText: two implementations existed. The polling one is canonical because the other
      waited on a SUBSTRING match and then demanded an exact node, so it threw immediately when a
      substring matched but the exact text had not rendered yet.

    android-e2e-qa.ps1 is not a consumer — see the PynapliQaUi.psm1 header.
#>

Set-StrictMode -Off

# These helpers query the UI hierarchy (Find-NodeByText, Get-Bounds, ...), which lives in the sibling
# module. A module cannot see another module's functions implicitly, so import it here rather than
# relying on the consuming script having imported it first.
#
# Deliberately NO -Force. The consuming script imports PynapliQaUi into its own session first; -Force
# here would REMOVE that copy and re-import it into this module's scope alone, so every UI function
# would vanish from the script ("The term 'Assert-NoAppErrorText' is not recognized"). Without
# -Force the already-loaded module is simply bound into this scope too, and both keep working.
Import-Module (Join-Path $PSScriptRoot "PynapliQaUi.psm1") -DisableNameChecking

$script:QaAdb = $null
$script:QaDeviceId = $null
$script:QaArtifactDir = $null
$script:QaWaitSeconds = 35
$script:QaDumpMaxAttempts = 12

function Set-PynapliQaContext {
    param(
        [Parameter(Mandatory)][string]$Adb,
        [Parameter(Mandatory)][string]$DeviceId,
        [Parameter(Mandatory)][string]$ArtifactDir,
        [int]$WaitSeconds = 35,
        [int]$DumpMaxAttempts = 12
    )

    $script:QaAdb = $Adb
    $script:QaDeviceId = $DeviceId
    $script:QaArtifactDir = $ArtifactDir
    $script:QaWaitSeconds = $WaitSeconds
    $script:QaDumpMaxAttempts = $DumpMaxAttempts
}

function Find-Adb {
    $command = Get-Command "adb" -ErrorAction SilentlyContinue
    if ($null -ne $command) { return $command.Source }

    $sdkCandidates = @(
        $env:ANDROID_HOME,
        $env:ANDROID_SDK_ROOT,
        (Join-Path $env:LOCALAPPDATA "Android\Sdk")
    ) | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }

    foreach ($sdk in $sdkCandidates) {
        $candidate = Join-Path $sdk "platform-tools\adb.exe"
        if (Test-Path $candidate) { return $candidate }
    }

    throw "adb was not found. Start Android Studio once or install Android SDK Platform Tools."
}

# Returns the device id rather than assigning it. The old Select-AdbDevice set $script:DeviceId
# directly, which cannot work from a module: it would set the MODULE's scope while every inline
# `$script:DeviceId` in the calling script stayed empty.
function Resolve-QaDeviceId {
    param(
        [Parameter(Mandatory)][string]$Adb,
        [string]$RequestedDeviceId = ""
    )

    $lines = & $Adb devices
    if ($LASTEXITCODE -ne 0) { throw "Unable to list adb devices." }

    $devices = @(
        $lines |
            Where-Object { $_ -match "^\S+\s+device$" } |
            ForEach-Object { ($_ -split "\s+")[0] }
    )

    if (-not [string]::IsNullOrWhiteSpace($RequestedDeviceId)) {
        if ($devices -notcontains $RequestedDeviceId) {
            throw "Requested adb device '$RequestedDeviceId' is not connected. Connected devices: $($devices -join ', ')"
        }
        return $RequestedDeviceId
    }

    if ($devices.Count -eq 0) {
        throw "No connected Android emulator/device found. Start an emulator and retry."
    }
    if ($devices.Count -gt 1) {
        throw "Multiple Android devices are connected. Rerun with -DeviceId, or use scripts\android-qa-matrix.ps1 to run across every AVD. Connected devices: $($devices -join ', ')"
    }
    return $devices[0]
}

# The API level a run actually covered. Every QA script reports this now: minSdk is 23, platform
# behaviour splits at API 35, and for months every script silently ran on android-36 with nothing
# in its output to say so — which is how an edge-to-edge defect stayed invisible on API <=34.
function Get-QaDeviceApiLevel {
    param(
        [Parameter(Mandatory)][string]$Adb,
        [Parameter(Mandatory)][string]$DeviceId
    )

    $value = & $Adb -s $DeviceId shell getprop ro.build.version.sdk 2>$null | Select-Object -First 1
    if ([string]::IsNullOrWhiteSpace($value)) { return "unknown" }
    return $value.Trim()
}

function Invoke-Adb {
    param([string[]]$Arguments)

    $output = & $script:QaAdb -s $script:QaDeviceId @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "adb failed: $($Arguments -join ' '). Output: $(($output | Out-String).Trim())"
    }
    return $output
}

function Invoke-AdbBestEffort {
    param([string[]]$Arguments)

    if ([string]::IsNullOrWhiteSpace($script:QaDeviceId)) {
        & $script:QaAdb @Arguments 2>&1 | Out-Null
    } else {
        & $script:QaAdb -s $script:QaDeviceId @Arguments 2>&1 | Out-Null
    }
}

function Save-Screen {
    param([string]$Name)

    $remote = "/sdcard/$Name.png"
    $local = Join-Path $script:QaArtifactDir "$Name.png"
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
    $local = Join-Path $script:QaArtifactDir $fileName
    $lastDumpOutput = ""

    for ($attempt = 1; $attempt -le $script:QaDumpMaxAttempts; $attempt++) {
        $previousErrorActionPreference = $ErrorActionPreference
        $ErrorActionPreference = "Continue"
        try {
            $dumpOutput = & $script:QaAdb -s $script:QaDeviceId shell uiautomator dump $remote 2>&1
            $dumpExit = $LASTEXITCODE
        } finally {
            $ErrorActionPreference = $previousErrorActionPreference
        }
        $lastDumpOutput = ($dumpOutput | Out-String).Trim()

        if ($dumpExit -eq 0 -and $lastDumpOutput -notmatch "ERROR:\s+null root") {
            & $script:QaAdb -s $script:QaDeviceId pull $remote $local 2>$null | Out-Null
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
                        & $script:QaAdb -s $script:QaDeviceId shell input tap $tapX $tapY 2>$null | Out-Null
                        Write-Host "[tap] 'Wait (ANR)' at $tapX,$tapY"
                        Start-Sleep -Milliseconds 1200
                        continue
                    }
                }
                return $parsed
            } catch {
                if ($attempt -eq $script:QaDumpMaxAttempts) {
                    throw "Unable to parse Android UI hierarchy at $local"
                }
            }
        }

        Start-Sleep -Milliseconds 500
    }

    throw "Unable to dump Android UI hierarchy after retries. Last output: $lastDumpOutput"
}

function Wait-ForAnyText {
    param(
        [string[]]$Texts,
        [int]$TimeoutSeconds = 0
    )

    if ($TimeoutSeconds -le 0) { $TimeoutSeconds = $script:QaWaitSeconds }

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $xml = $null
    do {
        $xml = Dump-Ui "wait"
        foreach ($text in $Texts) {
            if ($null -ne (Find-NodeByText $xml $text)) {
                return [pscustomobject]@{ Text = $text; Xml = $xml }
            }
        }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)

    $visible = if ($null -ne $xml) { (Get-VisibleTexts $xml) -join " | " } else { "<no dump>" }
    throw "Timed out waiting for one of: $($Texts -join ', '). Visible text: $visible"
}

function Tap-Text {
    param(
        [string]$Text,
        [int]$TimeoutSeconds = 0
    )

    $wait = Wait-ForAnyText @($Text) $TimeoutSeconds
    $node = Find-NodeByText $wait.Xml $Text
    $bounds = Get-Bounds $node
    Invoke-Adb @("shell", "input", "tap", "$($bounds.CenterX)", "$($bounds.CenterY)") | Out-Null
    Write-Host "[tap] '$Text' at $($bounds.CenterX),$($bounds.CenterY)"
}

# $Label is optional: grid/tonight called this with just a node and expected no logging.
function Tap-Node {
    param(
        $Node,
        [string]$Label = ""
    )

    $bounds = Get-Bounds $Node
    Invoke-Adb @("shell", "input", "tap", "$($bounds.CenterX)", "$($bounds.CenterY)") | Out-Null
    if (-not [string]::IsNullOrWhiteSpace($Label)) {
        Write-Host "[tap] '$Label' at $($bounds.CenterX),$($bounds.CenterY)"
    }
}

function Tap-ExactText {
    param(
        [string]$Text,
        [int]$TimeoutSeconds = 0
    )

    if ($TimeoutSeconds -le 0) { $TimeoutSeconds = $script:QaWaitSeconds }

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $xml = $null
    do {
        $xml = Dump-Ui "wait_exact"
        $node = Find-NodeByExactText $xml $Text
        if ($null -ne $node) {
            Tap-Node $node $Text
            return
        }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)

    $visible = if ($null -ne $xml) { (Get-VisibleTexts $xml) -join " | " } else { "<no dump>" }
    throw "Timed out waiting for exact text '$Text'. Visible text: $visible"
}

function Tap-BottomNavText {
    param(
        [string]$Text,
        [int]$TimeoutSeconds = 0
    )

    if ($TimeoutSeconds -le 0) { $TimeoutSeconds = $script:QaWaitSeconds }

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $xml = $null
    do {
        $xml = Dump-Ui "wait_bottom_nav"
        $node = Find-BottomNavNodeByText $xml $Text
        if ($null -ne $node) {
            $bounds = Get-Bounds $node
            Invoke-Adb @("shell", "input", "tap", "$($bounds.CenterX)", "$($bounds.CenterY)") | Out-Null
            Write-Host "[tap-nav] '$Text' at $($bounds.CenterX),$($bounds.CenterY)"
            return
        }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)

    $visible = if ($null -ne $xml) { (Get-VisibleTexts $xml) -join " | " } else { "<no dump>" }
    throw "Timed out waiting for bottom navigation item '$Text'. Visible text: $visible"
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

<#
    Tap a Swipe deck tab (For You / Top Picks / Linked) by anchoring to the tab row rather than
    trusting the first substring match anywhere on screen.

    "Linked" also renders as a badge on linked-partner cards further down the deck, so a plain
    Tap-Text could tap the CARD when the tab row had not rendered yet. That was a real intermittent
    failure in android-swipe-qa.ps1: the tap landed at y=852 (a card) instead of y=383 (the tab),
    opening an unrelated surface, and the run died on "Timed out waiting for one of: Maya, Alex,
    Jules, Rae".

    "Top Picks" appears only in the tab row, so it defines where that row is; the requested tab is
    then matched on the same line. This is also a real wait — it polls until the tab row exists,
    which the old `Wait-ForAnyText @("For You", "Like")` never did, both strings being permanently
    on screen.
#>
function Tap-DeckTab {
    param(
        [string]$Text,
        [int]$TimeoutSeconds = 45,
        [int]$RowTolerancePx = 40
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $xml = $null
    do {
        $xml = Dump-Ui "deck_tab_wait"
        $anchor = Find-NodeByExactText $xml "Top Picks"
        if ($null -ne $anchor) {
            $anchorY = (Get-Bounds $anchor).CenterY
            foreach ($node in Get-UiTextNodes $xml) {
                if ($node.GetAttribute("text") -ne $Text) { continue }
                $bounds = Get-Bounds $node
                if ([Math]::Abs($bounds.CenterY - $anchorY) -le $RowTolerancePx) {
                    Invoke-Adb @("shell", "input", "tap", "$($bounds.CenterX)", "$($bounds.CenterY)") | Out-Null
                    Write-Host "[tap-deck] '$Text' at $($bounds.CenterX),$($bounds.CenterY)"
                    return
                }
            }
        }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)

    $visible = if ($null -ne $xml) { (Get-VisibleTexts $xml) -join " | " } else { "<no dump>" }
    throw "Timed out waiting for the '$Text' deck tab on the tab row. Visible text: $visible"
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

Export-ModuleMember -Function @(
    "Set-PynapliQaContext",
    "Find-Adb",
    "Resolve-QaDeviceId",
    "Get-QaDeviceApiLevel",
    "Invoke-Adb",
    "Invoke-AdbBestEffort",
    "Save-Screen",
    "Dump-Ui",
    "Wait-ForAnyText",
    "Tap-Text",
    "Tap-Node",
    "Tap-ExactText",
    "Tap-BottomNavText",
    "Tap-FirstAvailableText",
    "Tap-DeckTab",
    "Scroll-SheetToText"
)
