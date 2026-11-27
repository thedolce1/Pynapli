param(
    # The web build talks to localhost:8080 when served from localhost (WebRuntimeConfig), so the API
    # must be the local server.
    [string]$BaseUrl = "http://localhost:8080",
    [int]$AppPort = 8081,
    [string]$OutputRoot = "artifacts\web-qa",
    [string]$Viewports = "desktop,tablet,phone",
    [string]$GoldenSpec = "scripts\web-qa-goldens.json",
    [switch]$SkipSeed,
    [switch]$SkipBuild,
    [switch]$SkipGoldenAssertions,
    # The sign-up + sign-in walk (run.mjs authWalk): on = with the surfaces, only = just the walk,
    # off = the surfaces alone. It signs up a new account per run and reads the emailed code from the
    # server's local-only dev lookup, so the server needs PYNAPLI_DEV_SIGNUP_SHORTCUTS_ENABLED=true.
    [ValidateSet("on", "off", "only")]
    [string]$AuthWalk = "on"
)

# Browser QA for the web companion (app.pynapli.com), the counterpart of the android-*-qa.ps1
# scripts. Drives the PRODUCTION Wasm bundle in headless Chrome at desktop, tablet and phone sizes:
# every main surface opens by URL, Playing Tonight and Psync open/close through browser history and
# load from a bookmark, Psync's live stream connects, the hand cursor and Esc work, and Chrome's
# "Very large" font setting scales the app. Screenshots are checked against loose luminance
# signatures in web-qa-goldens.json (not pixel goldens), like the Android scripts.
#
# Why it exists: the web shell once hid Playing Tonight while the Grid still showed its moon button,
# so that button did nothing on web, and every Android gate was green. Only a browser run sees that.
#
# It also walks sign-up -> first grid -> Log out -> sign in -> grid through the real UI (-AuthWalk) and
# prints time from Create account to the first grid card.
#
# Needs: the local server running (gradlew :server:run) with docker up, Chrome, Node 22+. With the
# walk on (the default) the server also needs PYNAPLI_DEV_SIGNUP_SHORTCUTS_ENABLED=true.
# Not part of the pre-push hook (it needs a running server); run it for any web-facing change.

$ErrorActionPreference = "Stop"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
Set-Location $repoRoot
Import-Module (Join-Path $PSScriptRoot "lib\PynapliQaGolden.psm1") -Force -DisableNameChecking

$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$artifactDir = Join-Path $repoRoot (Join-Path $OutputRoot $timestamp)
New-Item -ItemType Directory -Force -Path $artifactDir | Out-Null

Write-Host "Pynapli web companion QA"
Write-Host "------------------------"
Write-Host "Artifacts: $artifactDir"

$node = Get-Command node -ErrorAction SilentlyContinue
if ($null -eq $node) { throw "Node 22+ is required (it drives Chrome)." }
$nodeMajor = [int]((& node --version).TrimStart("v").Split(".")[0])
if ($nodeMajor -lt 22) { throw "Node 22+ is required for its built-in WebSocket; found $(& node --version)." }

try {
    $health = Invoke-RestMethod -Uri "$BaseUrl/health/deep" -TimeoutSec 5
    if ($health.status -ne "ok") { throw "Unexpected health status: $($health | ConvertTo-Json -Compress)" }
    Write-Host "[ok] Ktor health: $BaseUrl/health/deep"
} catch {
    throw "Ktor is not healthy at $BaseUrl/health/deep. Start the local server first. $($_.Exception.Message)"
}

if ($AuthWalk -ne "off") {
    # The walk's emailed code comes from the local-only dev lookup, which is off by default.
    Import-Module (Join-Path $PSScriptRoot "lib\PynapliDevSignup.psm1") -Force -DisableNameChecking
    Assert-DevSignupShortcuts -BaseUrl $BaseUrl
}

if (-not $SkipSeed) {
    Write-Host "[step] Seeding dev data"
    & pwsh -NoProfile -ExecutionPolicy Bypass -File ".\scripts\seed-dev-data.ps1" -BaseUrl $BaseUrl
    if ($LASTEXITCODE -ne 0) { throw "Dev data seed failed." }
}

# Earlier QA runs can leave the login rate limit tripped, which looks like a broken session restore.
docker exec pynapli-redis sh -c "redis-cli --scan --pattern 'pynapli:rate:*' | xargs -r redis-cli del" 2>$null | Out-Null

if (-not $SkipBuild) {
    Write-Host "[step] Building the production web bundle"
    .\gradlew.bat :webApp:wasmJsBrowserDistribution --console=plain -q
    if ($LASTEXITCODE -ne 0) { throw "Web build failed." }
}
$dist = Join-Path $repoRoot "webApp\build\dist\wasmJs\productionExecutable"
if (-not (Test-Path (Join-Path $dist "index.html"))) { throw "No web bundle at $dist. Run without -SkipBuild." }

$serveLog = Join-Path $artifactDir "serve.log"
$server = Start-Process -FilePath $node.Source -ArgumentList @("scripts/web-qa/serve.mjs", "`"$dist`"", $AppPort) `
    -RedirectStandardOutput $serveLog -RedirectStandardError (Join-Path $artifactDir "serve.err") -PassThru -WindowStyle Hidden
$failures = @()
try {
    $ready = $false
    for ($i = 0; $i -lt 40 -and -not $ready; $i++) {
        Start-Sleep -Milliseconds 250
        try { $ready = (Invoke-WebRequest -Uri "http://127.0.0.1:$AppPort/" -UseBasicParsing -TimeoutSec 2).StatusCode -eq 200 } catch {}
    }
    if (-not $ready) { throw "The static server did not start on port $AppPort (is something else using it?). See $serveLog" }

    Write-Host "[step] Driving Chrome ($Viewports)"
    & node scripts/web-qa/run.mjs --out $artifactDir --app "http://localhost:$AppPort" --api $BaseUrl --viewports $Viewports --auth-walk $AuthWalk
    $runExit = $LASTEXITCODE

    $results = Get-Content (Join-Path $artifactDir "results.json") -Raw | ConvertFrom-Json
    if ($null -ne $results.timings.createAccountTapToFirstCardMs) {
        Write-Host "[perf] Create account (landing) -> first grid card: $($results.timings.createAccountTapToFirstCardMs) ms"
    }
    if ($null -ne $results.timings.accountSubmitToFirstCardMs) {
        Write-Host "[perf] Create account (Account step) -> first grid card: $($results.timings.accountSubmitToFirstCardMs) ms"
    }
    $failures += @($results.checks | Where-Object { -not $_.pass } | ForEach-Object { "$($_.name): $($_.detail)" })

    if (-not $SkipGoldenAssertions) {
        Write-Host "[step] Screenshot signatures"
        $golden = Get-Content $GoldenSpec -Raw | ConvertFrom-Json
        foreach ($shot in $results.screenshots) {
            $spec = $golden.stages.($shot.stage)
            if ($null -eq $spec) {
                $failures += "No golden stage '$($shot.stage)' in $GoldenSpec"
                continue
            }
            try {
                $signature = Assert-ImageSignature -StageName "$($shot.viewport)-$($shot.stage)" -ScreenshotPath $shot.file -StageSpec $spec
                Write-Host ("  [ok] {0,-28} dark={1} nonBlack={2} buckets={3}" -f "$($shot.viewport)-$($shot.stage)", $signature.darkRatio, $signature.nonBlackRatio, $signature.colorBucketCount)
            } catch {
                $failures += $_.Exception.Message
            }
        }
    }
    if ($runExit -ne 0 -and $failures.Count -eq 0) { $failures += "run.mjs exited $runExit" }
} finally {
    if ($server -and -not $server.HasExited) { Stop-Process -Id $server.Id -Force -ErrorAction SilentlyContinue }
}

if ($failures.Count -gt 0) {
    Write-Host ""
    Write-Host "Web QA FAILED ($($failures.Count)):" -ForegroundColor Red
    $failures | ForEach-Object { Write-Host "  - $_" -ForegroundColor Yellow }
    Write-Host "Screenshots: $artifactDir"
    throw "Web QA failed."
}
Write-Host ""
Write-Host "Web QA passed. Screenshots: $artifactDir" -ForegroundColor Green
