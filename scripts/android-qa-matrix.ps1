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
    [int]$BootTimeoutSeconds = 420
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot

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

function Wait-ForBoot {
    param([Parameter(Mandatory)][string]$Serial, [int]$TimeoutSeconds)
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        $booted = (& $adb -s $Serial shell getprop sys.boot_completed 2>$null | Select-Object -First 1)
        if ($booted -and $booted.Trim() -eq "1") { return }
        Start-Sleep -Seconds 5
    }
    throw "Emulator '$Serial' did not finish booting within $TimeoutSeconds seconds."
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

    foreach ($qaScript in $Scripts) {
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
            & $scriptPath @arguments
            if ($LASTEXITCODE -ne 0 -and $null -ne $LASTEXITCODE) { throw "exit code $LASTEXITCODE" }
            $status = "PASS"
            $detail = ""
        } catch {
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
