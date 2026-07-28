# Cross-API-level Android QA driver.
#
# WHY THIS EXISTS: every android-*-qa.ps1 runs against whichever emulator is connected, and in
# practice that has always been Pynapli_Phone (android-36). minSdk is 23, and platform behaviour
# genuinely splits at API 35 — edge-to-edge is automatic at 35+ and must be asked for below it.
# On 2026-07-27 that gap hid a real defect: on API <=34 the app drew the system's opaque #000000
# behind the status/navigation bars instead of its own #0B0B0E, and not one of the 14 QA scripts
# could see it, because all 14 ran on the single API level where it cannot appear.
#
# A suite that only ever exercises one API level cannot catch a whole class of regression. This
# driver runs a chosen set of QA scripts against EVERY configured AVD so that class fails loudly.
#
# Usage:
#   .\scripts\android-qa-matrix.ps1                                     # e2e on both AVDs
#   .\scripts\android-qa-matrix.ps1 -Scripts android-grid-qa.ps1,android-swipe-qa.ps1
#   .\scripts\android-qa-matrix.ps1 -Avd Pynapli_API34 -SkipBuild
#
# Prereqs: the Ktor server reachable at -BaseUrl, Docker running (for the Redis flush between
# scripts), and the AVDs themselves — see docs/ANDROID_PRODUCTION_GAMEPLAN.md "API-level coverage".
param(
    [string[]]$Avd = @("Pynapli_Phone", "Pynapli_API34"),
    [string[]]$Scripts = @("android-e2e-qa.ps1"),
    [string]$BaseUrl = "http://127.0.0.1:8080",
    [string]$PackageName = "com.pynapli.app",
    [switch]$SkipBuild,
    [switch]$SkipSeed,
    # Opt-in speed-up. OFF by default on purpose: several scripts consume seeded state (swipe decks,
    # activity events), and the sweep pattern that is known to get all 14 surfaces green re-seeds per
    # script. Seeding once and skipping the rest would trade ~15s/script for failures that look like
    # platform bugs but are exhausted fixtures.
    [switch]$SeedOnce,
    [switch]$ColdBoot,
    [switch]$KeepEmulators,
    [int]$BootTimeoutSeconds = 420,
    # Hard ceiling per QA script. A hung `adb install` does not fail — it blocks forever while the
    # package is already on the device, and the sweep just stops with no error (observed 2026-07-27:
    # 32 minutes of silence that looked like normal progress). Nothing here legitimately takes more
    # than a few minutes, so cap it and record a FAIL instead of stalling the run.
    [int]$ScriptTimeoutSeconds = 420,
    # Reboot the emulator every N scripts to keep its memory footprint flat.
    #
    # qemu's working set only grows — it climbed 1.28 -> 1.92 GB across nine scripts on 2026-07-27,
    # dragging host available RAM from 4.66 GB back down to 2.03 GB. Below ~2 GB the emulator starts
    # throwing SystemUI ANRs and scripts fail at their first assertion with "Timed out waiting for
    # one of: Dev login" while the app sits on its boot splash. That looks exactly like an app or
    # platform bug and is neither, so a long sweep degrades into noise the further it gets.
    #
    # 0 disables. Only applies to emulators this script started (it will not bounce a device the
    # founder is using), so pair it with letting the driver boot the AVD.
    [int]$RestartEveryNScripts = 5
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot

# Normalize comma-joined input. `pwsh -File script.ps1 -Scripts a.ps1,b.ps1` binds the whole thing
# as ONE string rather than a 3-element array, so a detached Start-Process launch fails instantly
# with "QA script not found: <every name concatenated>". Splitting here makes both call styles work
# instead of leaving a 25-minute run to die on the first line.
$Scripts = @($Scripts | ForEach-Object { $_ -split "," } | ForEach-Object { $_.Trim() } | Where-Object { $_ })
$Avd = @($Avd | ForEach-Object { $_ -split "," } | ForEach-Object { $_.Trim() } | Where-Object { $_ })

function Find-SdkTool {
    param([Parameter(Mandatory)][string]$RelativePath)

    $sdkCandidates = @(
        $env:ANDROID_HOME,
        $env:ANDROID_SDK_ROOT,
        (Join-Path $env:LOCALAPPDATA "Android\Sdk")
    ) | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }

    foreach ($sdk in $sdkCandidates) {
        $candidate = Join-Path $sdk $RelativePath
        if (Test-Path $candidate) { return $candidate }
    }
    throw "Could not find '$RelativePath' in the Android SDK. Looked under: $($sdkCandidates -join ', ')"
}

$adb = Find-SdkTool "platform-tools\adb.exe"
$emulator = Find-SdkTool "emulator\emulator.exe"

# Serial -> AVD name for everything currently connected. `adb emu avd name` prints the name and
# then "OK", so only the first line is the answer.
function Get-RunningAvdMap {
    $map = @{}
    $lines = & $adb devices
    foreach ($line in $lines) {
        if ($line -notmatch "^(\S+)\s+device$") { continue }
        $serial = $Matches[1]
        $name = (& $adb -s $serial emu avd name 2>$null | Select-Object -First 1)
        if ($name) { $map[$name.Trim()] = $serial }
    }
    return $map
}

function Get-ApiLevel {
    param([Parameter(Mandatory)][string]$Serial)
    return (& $adb -s $Serial shell getprop ro.build.version.sdk 2>$null | Select-Object -First 1).Trim()
}

# sys.boot_completed=1 is NOT "ready". It fires while PackageManager is still settling, and issuing
# `adb install` into that window hangs the adb CLIENT indefinitely — the package installs fine, the
# client just never exits. Observed 2026-07-27: a 32-minute hang that looked like a frozen sweep.
# So gate on three things, then let it settle.
function Wait-ForBoot {
    param([Parameter(Mandatory)][string]$Serial, [int]$TimeoutSeconds)
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        $booted = (& $adb -s $Serial shell getprop sys.boot_completed 2>$null | Select-Object -First 1)
        $bootanim = (& $adb -s $Serial shell getprop init.svc.bootanim 2>$null | Select-Object -First 1)
        # `pm path android` only answers once PackageManager can actually service install requests.
        $pmReady = (& $adb -s $Serial shell pm path android 2>$null | Select-Object -First 1)

        if ($booted -and $booted.Trim() -eq "1" -and
            $bootanim -and $bootanim.Trim() -eq "stopped" -and
            $pmReady -and $pmReady -match "package:") {
            Start-Sleep -Seconds 5   # let early-boot dexopt/broadcast churn drain before installing
            return
        }
        Start-Sleep -Seconds 5
    }
    throw "Emulator '$Serial' did not become install-ready within $TimeoutSeconds seconds."
}

# Returns @{ Serial; Started } — Started tells the caller whether WE booted it, because an emulator
# the founder already had open must never be shut down by this script.
function Start-AvdIfNeeded {
    param([Parameter(Mandatory)][string]$Name)

    $running = Get-RunningAvdMap
    if ($running.ContainsKey($Name)) {
        Write-Host "  $Name already running as $($running[$Name])"
        return @{ Serial = $running[$Name]; Started = $false }
    }

    $known = & $emulator -list-avds
    if ($known -notcontains $Name) {
        throw "AVD '$Name' does not exist. Known AVDs: $($known -join ', '). See docs/ANDROID_PRODUCTION_GAMEPLAN.md."
    }

    Write-Host "  booting $Name ..."
    $emulatorArgs = @("-avd", $Name, "-no-boot-anim")
    if ($ColdBoot) { $emulatorArgs += "-no-snapshot-load" }
    # Detached on purpose: a child process in this shell's tree dies with the shell.
    Start-Process -FilePath $emulator -ArgumentList $emulatorArgs -WindowStyle Minimized | Out-Null

    $deadline = (Get-Date).AddSeconds($BootTimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Seconds 5
        $map = Get-RunningAvdMap
        if ($map.ContainsKey($Name)) {
            Wait-ForBoot -Serial $map[$Name] -TimeoutSeconds $BootTimeoutSeconds
            return @{ Serial = $map[$Name]; Started = $true }
        }
    }
    throw "AVD '$Name' never appeared in 'adb devices' within $BootTimeoutSeconds seconds."
}

function Reset-BetweenScripts {
    param([Parameter(Mandatory)][string]$Serial)
    # Cumulative dev logins trip the server's auth rate limit; seeded data lives in Postgres, so
    # flushing Redis is safe and is what makes a multi-script sweep possible at all.
    & docker exec pynapli-redis redis-cli FLUSHDB *> $null
    & $adb -s $Serial shell am force-stop $PackageName *> $null
}

if (-not $SkipBuild) {
    Write-Host "Building debug APK once for the whole matrix ..."
    & (Join-Path $repoRoot "gradlew.bat") ":androidApp:assembleDebug"
    if ($LASTEXITCODE -ne 0) { throw "Debug APK build failed; matrix not started." }
}

$results = @()
$startedByUs = @()
# $seeded means "a later script should be told to skip seeding". Only ever becomes true when the
# caller asked for it; otherwise every script seeds itself, as the known-good sweep does.
$seeded = $SkipSeed.IsPresent

foreach ($avdName in $Avd) {
    Write-Host ""
    Write-Host "=== AVD $avdName ===" -ForegroundColor Cyan
    $device = Start-AvdIfNeeded -Name $avdName
    if ($device.Started) { $startedByUs += @{ Name = $avdName; Serial = $device.Serial } }

    $apiLevel = Get-ApiLevel -Serial $device.Serial
    Write-Host "  $avdName -> $($device.Serial) (API $apiLevel)"

    $sinceRestart = 0
    foreach ($qaScript in $Scripts) {
        # Bounce the emulator before it drifts far enough to start manufacturing failures. Only if
        # we booted it, so a device the founder is driving is never yanked out from under them.
        if ($RestartEveryNScripts -gt 0 -and $sinceRestart -ge $RestartEveryNScripts -and $device.Started) {
            $freeGb = [math]::Round((Get-Counter '\Memory\Available MBytes').CounterSamples[0].CookedValue / 1024, 2)
            Write-Host "  restarting $avdName after $sinceRestart scripts (host available RAM ${freeGb} GB)"
            & $adb -s $device.Serial emu kill *> $null
            Start-Sleep -Seconds 10
            $device = Start-AvdIfNeeded -Name $avdName
            $sinceRestart = 0
        }
        $sinceRestart++

        $scriptPath = Join-Path $PSScriptRoot $qaScript
        if (-not (Test-Path $scriptPath)) { throw "QA script not found: $scriptPath" }

        Reset-BetweenScripts -Serial $device.Serial

        # Only some scripts take -SkipBuild (android-e2e-qa.ps1 never builds), so ask each one
        # rather than assuming a uniform parameter surface.
        $supported = (Get-Command $scriptPath).Parameters
        $arguments = @{ DeviceId = $device.Serial; BaseUrl = $BaseUrl }
        if ($supported.ContainsKey("SkipBuild")) { $arguments["SkipBuild"] = $true }
        if ($seeded -and $supported.ContainsKey("SkipSeed")) { $arguments["SkipSeed"] = $true }

        Write-Host "  -> $qaScript on API $apiLevel" -ForegroundColor Yellow
        $started = Get-Date
        try {
            # Run in a job purely so it can be bounded. In-process `& $scriptPath` cannot be timed
            # out, and an adb hang inside it stalls the whole sweep silently.
            $job = Start-Job -ScriptBlock {
                param($path, $argMap)
                Set-Location $using:repoRoot
                & $path @argMap 2>&1
                if ($LASTEXITCODE -ne 0 -and $null -ne $LASTEXITCODE) { throw "exit code $LASTEXITCODE" }
            } -ArgumentList $scriptPath, $arguments

            if (Wait-Job $job -Timeout $ScriptTimeoutSeconds) {
                Receive-Job $job -ErrorAction Stop | ForEach-Object { Write-Host $_ }
                $status = "PASS"
                $detail = ""
            } else {
                Stop-Job $job -ErrorAction SilentlyContinue
                # A hung script usually means a wedged adb install; the device needs a reboot before
                # the next script, or every one after it fails the same way.
                Get-CimInstance Win32_Process -Filter "Name='adb.exe'" -ErrorAction SilentlyContinue |
                    Where-Object { $_.CommandLine -match 'install' } |
                    ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
                if ($device.Started) {
                    Write-Host "     hung - rebooting $avdName to clear a wedged PackageManager" -ForegroundColor Yellow
                    & $adb -s $device.Serial emu kill *> $null
                    Start-Sleep -Seconds 10
                    $device = Start-AvdIfNeeded -Name $avdName
                    $sinceRestart = 0
                }
                throw "timed out after ${ScriptTimeoutSeconds}s (likely a hung adb install)"
            }
            Remove-Job $job -Force -ErrorAction SilentlyContinue
        } catch {
            Remove-Job $job -Force -ErrorAction SilentlyContinue
            $status = "FAIL"
            $detail = $_.Exception.Message
            Write-Host "     FAIL: $detail" -ForegroundColor Red
        }
        if ($SeedOnce) { $seeded = $true }

        $results += [pscustomobject]@{
            Avd      = $avdName
            Api      = $apiLevel
            Script   = $qaScript
            Status   = $status
            Seconds  = [int]((Get-Date) - $started).TotalSeconds
            Detail   = $detail
        }
    }

    # Shut this AVD down before starting the next, rather than at the very end. Two emulators up at
    # once is not just RAM: any tooling that calls adb without -s dies with "more than one emulator
    # detected", and seed-dev-data.ps1's geo fix did exactly that — silently leaving the viewer's
    # location unset, which fails distance-dependent surfaces for a reason that looks like an app
    # bug. That is fixed, but one emulator at a time keeps the next device-blind tool from biting.
    if ($device.Started -and -not $KeepEmulators) {
        Write-Host "  shutting down $avdName before the next AVD"
        & $adb -s $device.Serial emu kill *> $null
        Start-Sleep -Seconds 5
    }
}

if (-not $KeepEmulators) {
    foreach ($started in $startedByUs) {
        Write-Host "Shutting down $($started.Name) ($($started.Serial)) — started by this run."
        & $adb -s $started.Serial emu kill *> $null
    }
}

Write-Host ""
Write-Host "=== QA matrix ===" -ForegroundColor Cyan
$results | Format-Table -AutoSize Avd, Api, Script, Status, Seconds

$failed = @($results | Where-Object { $_.Status -eq "FAIL" })
if ($failed.Count -gt 0) {
    Write-Host "$($failed.Count) of $($results.Count) runs FAILED." -ForegroundColor Red
    foreach ($failure in $failed) {
        Write-Host "  API $($failure.Api) / $($failure.Script): $($failure.Detail)" -ForegroundColor Red
    }
    exit 1
}

Write-Host "All $($results.Count) runs passed across $($Avd.Count) API level(s)." -ForegroundColor Green
