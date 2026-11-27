param(
    [string]$BaseUrl = "http://localhost:8080",
    [string]$Password = "PynapliLoadPass123!",
    [int]$ViewerCount = 100,
    [int]$EventsPerViewer = 200,
    [int]$ActorCount = 5000,
    [int]$ConcurrentUsers = 100,
    [int]$SummaryBudgetP95Ms = 300,
    [int]$FeedBudgetP95Ms = 600,
    [int]$TabBudgetP95Ms = 600,
    [string]$OutputRoot = "artifacts\activity-api-load",
    [switch]$FailOnBudget
)

$ErrorActionPreference = "Stop"

# Accounts come from the local-only dev sign-up route, which is off by default: fail fast with the
# instruction if the server wasn't started with PYNAPLI_DEV_SIGNUP_SHORTCUTS_ENABLED=true.
Import-Module (Join-Path $PSScriptRoot "lib\PynapliDevSignup.psm1") -Force -DisableNameChecking
Assert-DevSignupShortcuts -BaseUrl $BaseUrl

if ($PSVersionTable.PSVersion.Major -lt 7) {
    throw "scripts/activity-api-load-test.ps1 requires PowerShell 7+ for parallel execution."
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$startedAt = Get-Date
$artifactStamp = $startedAt.ToString("yyyyMMdd-HHmmss")
$artifactDir = Join-Path $repoRoot (Join-Path $OutputRoot $artifactStamp)
New-Item -ItemType Directory -Force -Path $artifactDir | Out-Null

function Invoke-PynapliJson {
    param(
        [ValidateSet("GET", "POST", "PATCH")]
        [string]$Method,
        [string]$Path,
        [object]$Body = $null,
        [string]$AccessToken = $null,
        [int]$TimeoutSec = 30
    )

    $headers = @{ Accept = "application/json" }
    if (-not [string]::IsNullOrWhiteSpace($AccessToken)) {
        $headers.Authorization = "Bearer $AccessToken"
    }

    $parameters = @{
        Method      = $Method
        Uri         = "$BaseUrl$Path"
        Headers     = $headers
        ErrorAction = "Stop"
        TimeoutSec  = $TimeoutSec
    }
    if ($null -ne $Body) {
        $parameters.ContentType = "application/json"
        $parameters.Body = $Body | ConvertTo-Json -Depth 12 -Compress
    }

    try {
        $response = Invoke-WebRequest @parameters
        $parsed = if ([string]::IsNullOrWhiteSpace($response.Content)) {
            $null
        } else {
            $response.Content | ConvertFrom-Json
        }
        return [pscustomobject]@{
            StatusCode = [int]$response.StatusCode
            Body = $parsed
            RawError = $null
        }
    } catch {
        $statusCode = 0
        $content = $_.ErrorDetails.Message
        if ($_.Exception.Response) {
            $statusCode = [int]$_.Exception.Response.StatusCode
        }
        return [pscustomobject]@{
            StatusCode = $statusCode
            Body = $null
            RawError = $content
        }
    }
}

function Assert-Success {
    param(
        [pscustomobject]$Response,
        [string]$Action
    )

    if ($Response.StatusCode -lt 200 -or $Response.StatusCode -ge 300) {
        $details = if ($null -ne $Response.RawError) { $Response.RawError } else { "no response body" }
        throw "$Action failed with HTTP $($Response.StatusCode): $details"
    }
}

function Invoke-DockerPsql {
    param([string]$Sql)

    $output = $Sql | docker exec -i pynapli-postgres psql -U pynapli -d $(if ($env:PYNAPLI_DATABASE_NAME) { $env:PYNAPLI_DATABASE_NAME } elseif ($env:PYNAPLI_DATABASE_URL -match '/([^/?]+)(\?|$)') { $Matches[1] } else { 'pynapli_dev' }) -v ON_ERROR_STOP=1 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "psql failed: $output"
    }
    return $output
}

function ConvertTo-Sha256Base64Url {
    param([string]$Value)

    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        $bytes = [System.Text.Encoding]::UTF8.GetBytes($Value.Trim().ToLowerInvariant())
        return [Convert]::ToBase64String($sha.ComputeHash($bytes)).TrimEnd("=").Replace("+", "-").Replace("/", "_")
    } finally {
        $sha.Dispose()
    }
}

function New-LoadViewerSession {
    param([int]$Index)

    $email = "activity-load-$Index@pynapli.local"
    # Local-only dev route: the public sign-up now only emails a code (ONBOARDING_AUTH_SPEC,
    # "Membership privacy"). Verified account at once; 409 for an existing one; 404 off a local server.
    $signup = Invoke-PynapliJson `
        -Method POST `
        -Path "/v1/dev/auth/email/signup" `
        -Body @{
            email = $email
            password = $Password
            birthdate = "1990-01-01"
            confirmedAdult = $true
            acceptedTerms = $true
            acceptedPrivacy = $true
            deviceId = "activity-load-$Index"
            platform = "load-test"
            appVersion = "0.1.0"
        }

    $session = if ($signup.StatusCode -ge 200 -and $signup.StatusCode -lt 300) {
        $signup.Body
    } elseif ($signup.StatusCode -eq 409) {
        $login = Invoke-PynapliJson `
            -Method POST `
            -Path "/v1/auth/email/login" `
            -Body @{
                email = $email
                password = $Password
                deviceId = "activity-load-$Index"
                platform = "load-test"
                appVersion = "0.1.0"
            }
        Assert-Success -Response $login -Action "Login $email"
        $login.Body
    } else {
        Assert-Success -Response $signup -Action "Signup $email"
    }

    return [pscustomobject]@{
        Index = $Index
        Email = $email
        UserId = $session.user.userId
        AccessToken = $session.accessToken
    }
}

function New-ActorId {
    param([int]$Index)
    return "00000000-0000-0000-3000-$($Index.ToString("000000000000"))"
}

function Reset-ActivityRunState {
    param([array]$Viewers)

    $viewerIds = ($Viewers | ForEach-Object { "'$($_.UserId)'::uuid" }) -join ","
    $sql = @"
DELETE FROM activity_events
WHERE recipient_user_id IN ($viewerIds)
   OR actor_user_id::text LIKE '00000000-0000-0000-3000-%';
"@
    Invoke-DockerPsql -Sql $sql | Out-Null
}

function Seed-ActivityActors {
    $sql = @"
WITH actors AS (
    SELECT ('00000000-0000-0000-3000-' || lpad(i::text, 12, '0'))::uuid AS user_id,
           i AS actor_index
    FROM generate_series(1, $ActorCount) AS i
)
INSERT INTO users (id, status)
SELECT user_id, 'active'
FROM actors
ON CONFLICT (id) DO UPDATE SET status = 'active';

WITH actors AS (
    SELECT ('00000000-0000-0000-3000-' || lpad(i::text, 12, '0'))::uuid AS user_id
    FROM generate_series(1, $ActorCount) AS i
)
INSERT INTO discovery_settings (user_id, show_distance, show_in_viewed_lists)
SELECT user_id, true, true
FROM actors
ON CONFLICT (user_id) DO UPDATE
SET show_distance = true,
    show_in_viewed_lists = true,
    incognito = false;

WITH actors AS (
    SELECT ('00000000-0000-0000-3000-' || lpad(i::text, 12, '0'))::uuid AS user_id,
           i AS actor_index
    FROM generate_series(1, $ActorCount) AS i
)
INSERT INTO profiles (user_id, display_name, moderation_status, visibility_status, profile_completeness_score)
SELECT user_id, 'Activity Actor ' || actor_index, 'approved', 'visible', 80
FROM actors
ON CONFLICT (user_id) DO UPDATE
SET display_name = EXCLUDED.display_name,
    moderation_status = 'approved',
    visibility_status = 'visible',
    profile_completeness_score = 80,
    updated_at = now();

ANALYZE users;
ANALYZE profiles;
ANALYZE discovery_settings;
"@
    Invoke-DockerPsql -Sql $sql | Out-Null
}

function Seed-ActivityEvents {
    param([array]$Viewers)

    $types = @("profile_view", "flirt", "swipe_like", "match", "album_request", "partner_chat_invite")
    $rows = New-Object System.Collections.Generic.List[string]
    $inserted = 0

    foreach ($viewer in $Viewers) {
        for ($i = 1; $i -le $EventsPerViewer; $i++) {
            $eventId = [guid]::NewGuid().ToString()
            $actorIndex = (($viewer.Index + $i) % $ActorCount) + 1
            $actorId = New-ActorId -Index $actorIndex
            $type = $types[$i % $types.Count]
            $occurredAt = [DateTime]::UtcNow.AddMinutes(-1 * (($viewer.Index * $EventsPerViewer) + $i)).ToString("yyyy-MM-ddTHH:mm:ssZ")
            $metadata = "{}"
            if ($type -eq "match") {
                $metadata = "{""matchId"":""$([guid]::NewGuid().ToString())""}"
            } elseif ($type -eq "partner_chat_invite") {
                $metadata = "{""conversationId"":""$([guid]::NewGuid().ToString())""}"
            }
            $rows.Add("('$eventId'::uuid,'$($viewer.UserId)'::uuid,'$actorId'::uuid,'$type','$($viewer.UserId)'::uuid,1,'$occurredAt'::timestamptz,'$occurredAt'::timestamptz,'$occurredAt'::timestamptz,'$occurredAt'::timestamptz,'$metadata'::jsonb)")

            if ($rows.Count -ge 1000) {
                Insert-ActivityEventRows -Rows $rows
                $inserted += $rows.Count
                $rows.Clear()
            }
        }
    }

    if ($rows.Count -gt 0) {
        Insert-ActivityEventRows -Rows $rows
        $inserted += $rows.Count
    }

    Invoke-DockerPsql -Sql "ANALYZE activity_events;" | Out-Null
    return $inserted
}

function Insert-ActivityEventRows {
    param([System.Collections.Generic.List[string]]$Rows)

    $values = [string]::Join(",`n", $Rows)
    $sql = @"
INSERT INTO activity_events (
    id,
    recipient_user_id,
    actor_user_id,
    event_type,
    target_user_id,
    aggregate_count,
    first_occurred_at,
    last_counted_at,
    occurred_at,
    updated_at,
    metadata
)
VALUES
$values
ON CONFLICT DO NOTHING;
"@
    Invoke-DockerPsql -Sql $sql | Out-Null
}

function Get-Percentile {
    param(
        [double[]]$Values,
        [double]$Percentile
    )

    if ($Values.Count -eq 0) {
        return $null
    }

    $sorted = @($Values | Sort-Object)
    $index = [Math]::Ceiling(($Percentile / 100.0) * $sorted.Count) - 1
    $index = [Math]::Max(0, [Math]::Min($index, $sorted.Count - 1))
    return [Math]::Round([double]$sorted[$index], 1)
}

function Write-ScenarioSummary {
    param([array]$Results)

    $budgets = @{
        activity_summary = $SummaryBudgetP95Ms
        activity_all = $FeedBudgetP95Ms
        activity_likes = $TabBudgetP95Ms
        activity_flirts = $TabBudgetP95Ms
        activity_invites = $TabBudgetP95Ms
    }
    $failedBudget = $false
    $summaryRows = New-Object System.Collections.Generic.List[object]

    Write-Host ""
    Write-Host "Activity scenario latency summary"
    Write-Host "---------------------------------"
    foreach ($group in ($Results | Group-Object Scenario | Sort-Object Name)) {
        $successful = @($group.Group | Where-Object { $_.StatusCode -ge 200 -and $_.StatusCode -lt 300 })
        $errors = @($group.Group | Where-Object { $_.StatusCode -lt 200 -or $_.StatusCode -ge 300 })
        $latencies = @($successful | ForEach-Object { [double]$_.Millis })
        $p50 = Get-Percentile -Values $latencies -Percentile 50
        $p95 = Get-Percentile -Values $latencies -Percentile 95
        $budget = $budgets[$group.Name]
        $budgetStatus = if ($null -ne $budget -and $null -ne $p95) {
            if ($p95 -le $budget) { "ok" } else { $failedBudget = $true; "over" }
        } else {
            "n/a"
        }
        $summaryRows.Add([pscustomobject]@{
            Scenario = $group.Name
            Count = $group.Count
            Errors = $errors.Count
            P50Ms = $p50
            P95Ms = $p95
            MaxMs = if ($latencies.Count -gt 0) { [Math]::Round(($latencies | Measure-Object -Maximum).Maximum, 1) } else { $null }
            BudgetP95Ms = $budget
            Budget = $budgetStatus
        })
    }
    $summaryRows | Format-Table -AutoSize
    return [pscustomobject]@{
        Rows = @($summaryRows.ToArray())
        FailedBudget = $failedBudget
    }
}

Write-Host "Preparing $ViewerCount Activity load viewers..."
$viewers = for ($i = 1; $i -le $ViewerCount; $i++) {
    New-LoadViewerSession -Index $i
}

Reset-ActivityRunState -Viewers $viewers
Seed-ActivityActors
$insertedEvents = Seed-ActivityEvents -Viewers $viewers

$workItems = New-Object System.Collections.Generic.List[object]
foreach ($viewer in $viewers) {
    $workItems.Add([pscustomobject]@{ Scenario = "activity_summary"; Path = "/v1/activity/summary"; AccessToken = $viewer.AccessToken; UserIndex = $viewer.Index })
    $workItems.Add([pscustomobject]@{ Scenario = "activity_all"; Path = "/v1/activity?limit=30&tab=all"; AccessToken = $viewer.AccessToken; UserIndex = $viewer.Index })
    $workItems.Add([pscustomobject]@{ Scenario = "activity_likes"; Path = "/v1/activity?limit=30&tab=likes"; AccessToken = $viewer.AccessToken; UserIndex = $viewer.Index })
    $workItems.Add([pscustomobject]@{ Scenario = "activity_flirts"; Path = "/v1/activity?limit=30&tab=flirts"; AccessToken = $viewer.AccessToken; UserIndex = $viewer.Index })
    $workItems.Add([pscustomobject]@{ Scenario = "activity_invites"; Path = "/v1/activity?limit=30&tab=invites"; AccessToken = $viewer.AccessToken; UserIndex = $viewer.Index })
}

Write-Host "Running $($workItems.Count) Activity API requests with concurrency $ConcurrentUsers..."
$results = $workItems | ForEach-Object -Parallel {
    $headers = @{
        Accept = "application/json"
        Authorization = "Bearer $($_.AccessToken)"
    }
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    try {
        $response = Invoke-WebRequest -Method GET -Uri "$using:BaseUrl$($_.Path)" -Headers $headers -TimeoutSec 30 -ErrorAction Stop
        $sw.Stop()
        [pscustomobject]@{
            Scenario = $_.Scenario
            UserIndex = $_.UserIndex
            StatusCode = [int]$response.StatusCode
            Millis = [Math]::Round($sw.Elapsed.TotalMilliseconds, 1)
            Error = $null
        }
    } catch {
        $sw.Stop()
        $statusCode = 0
        if ($_.Exception.Response) {
            $statusCode = [int]$_.Exception.Response.StatusCode
        }
        [pscustomobject]@{
            Scenario = $_.Scenario
            UserIndex = $_.UserIndex
            StatusCode = $statusCode
            Millis = [Math]::Round($sw.Elapsed.TotalMilliseconds, 1)
            Error = $_.Exception.Message
        }
    }
} -ThrottleLimit $ConcurrentUsers

$summary = Write-ScenarioSummary -Results @($results)
$results | Export-Csv -NoTypeInformation -Path (Join-Path $artifactDir "activity-results.csv")
$summary.Rows | Export-Csv -NoTypeInformation -Path (Join-Path $artifactDir "activity-summary.csv")

$qaSummary = [pscustomobject]@{
    startedAt = $startedAt.ToUniversalTime().ToString("o")
    baseUrl = $BaseUrl
    viewerCount = $ViewerCount
    eventsPerViewer = $EventsPerViewer
    actorCount = $ActorCount
    concurrentUsers = $ConcurrentUsers
    insertedEvents = $insertedEvents
    scenarioSummary = @($summary.Rows)
    failedBudget = $summary.FailedBudget
    artifactDir = $artifactDir
}
$qaSummary | ConvertTo-Json -Depth 8 | Set-Content -Path (Join-Path $artifactDir "activity-load-summary.json")

Write-Host ""
Write-Host "Activity load artifacts: $artifactDir"

if ($FailOnBudget -and $summary.FailedBudget) {
    throw "One or more Activity scenarios exceeded the configured p95 budget. Artifacts were written to $artifactDir"
}
