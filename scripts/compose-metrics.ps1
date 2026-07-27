# Compose compiler metrics/reports for the Compose modules.
#
# The compiler can tell you exactly which composables are skippable and which classes it considers
# unstable — you cannot reason about recomposition cost without it. Metrics are OFF by default
# (they make the compiler write reports on every Compose compile), so this script turns them on for
# one run and summarises the result.
#
#   .\scripts\compose-metrics.ps1              # :composeApp desktop (fastest, covers commonMain)
#   .\scripts\compose-metrics.ps1 -Android     # :androidApp debug, the variant that ships
#
# Raw reports land in build/compose-metrics/<module>/ (gitignored with the rest of build/):
#   *-module.json      totals
#   *-classes.txt      per-class stability, with the reason
#   *-composables.txt  per-composable skippability
param(
    [switch]$Android,
    [switch]$SkipBuild
)

$ErrorActionPreference = "Stop"

if (-not (Test-Path ".\gradlew.bat")) {
    throw "Run this script from the Pynapli repo root."
}

$task = if ($Android) { ":androidApp:compileDebugKotlin" } else { ":composeApp:compileKotlinDesktop" }

if (-not $SkipBuild) {
    Write-Host "[step] $task with metrics enabled (--rerun-tasks: reports are only written when the compiler actually runs)"
    # NOTE the quotes around the -P flag. Unquoted, PowerShell splits it at the dot and Gradle sees
    # ".composeMetrics=true" as a task name.
    .\gradlew.bat $task "-Ppynapli.composeMetrics=true" --rerun-tasks --console=plain
    if ($LASTEXITCODE -ne 0) {
        throw "Compose metrics build failed."
    }
}

$root = Join-Path (Get-Location) "build\compose-metrics"
if (-not (Test-Path $root)) {
    throw "No reports at $root. Run without -SkipBuild first."
}

foreach ($moduleJson in Get-ChildItem -Recurse $root -Filter "*-module.json") {
    $m = Get-Content $moduleJson.FullName -Raw | ConvertFrom-Json
    $nonSkippable = $m.totalComposables - $m.skippableComposables
    $skippablePct = if ($m.totalComposables -gt 0) { [math]::Round(100 * $m.skippableComposables / $m.totalComposables) } else { 0 }

    Write-Host ""
    Write-Host "########## $($moduleJson.BaseName)"
    "{0,-30} {1}" -f "total composables", $m.totalComposables
    "{0,-30} {1} ({2}%)" -f "skippable", $m.skippableComposables, $skippablePct
    "{0,-30} {1}" -f "NOT skippable", $nonSkippable
    "{0,-30} {1} / {2}" -f "unstable args", $m.knownUnstableArguments, $m.totalArguments
    "{0,-30} {1} / {2}" -f "memoized lambdas", $m.memoizedLambdas, $m.totalLambdas
    "{0,-30} {1}" -f "strong skipping", $m.featureFlags.StrongSkipping
}

# The actionable slice. An unstable *state holder* or *repository* barely matters — it is passed once
# and the screen recomposes on its state anyway. An unstable UI MODEL is what silently makes the
# composables receiving it non-skippable.
$classFiles = @(Get-ChildItem -Recurse $root -Filter "*-classes.txt")
if ($classFiles.Count -gt 0) {
    Write-Host ""
    Write-Host "########## unstable UI-model candidates - VERIFY before annotating"
    Write-Host "  (@Immutable is a promise: a wrong one makes Compose skip recompositions it should run."
    Write-Host "   This is a name heuristic, so mutable interaction holders can show up here too --"
    Write-Host "   AlbumReorderState did, and correctly stayed un-annotated: it is mutable by design.)"
    $found = $false
    foreach ($cf in $classFiles) {
        $lines = Get-Content $cf.FullName
        for ($i = 0; $i -lt $lines.Count; $i++) {
            if ($lines[$i] -notmatch '^unstable class .*(Ui|UiState|Card|Item|State) \{') { continue }
            $found = $true
            Write-Host ""
            Write-Host ("  " + ($lines[$i] -replace ' \{$', ''))
            for ($j = $i + 1; $j -lt $lines.Count -and $lines[$j] -notmatch '^\}'; $j++) {
                if ($lines[$j] -match '^\s*unstable ') { Write-Host "      $($lines[$j].Trim())" }
            }
        }
    }
    if (-not $found) { Write-Host "  none" }
}

Write-Host ""
Write-Host "Raw reports: $root"
