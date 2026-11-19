param(
    [string]$BaseUrl = "http://localhost:8080",
    [string]$Password = "PynapliLoadPass123!",
    [int]$CandidateCount = 5000,
    [int]$ViewerCount = 100,
    [int]$ConcurrentUsers = 100,
    [int]$OnlineCandidateCount = 1000,
    [int]$GridInitialBudgetP95Ms = 800,
    [int]$GridFilterBudgetP95Ms = 500,
    [int]$GridPageBudgetP95Ms = 800,
    [int]$ProfileOpenBudgetP95Ms = 800,
    [string]$OutputRoot = "artifacts\grid-api-load",
    [switch]$ColdCache,
    [switch]$ServerProfile,
    [switch]$FailOnBudget
)

$ErrorActionPreference = "Stop"

if ($PSVersionTable.PSVersion.Major -lt 7) {
    throw "scripts/grid-api-load-test.ps1 requires PowerShell 7+ for parallel execution."
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$viewerLatitude = 37.4220
$viewerLongitude = -122.0841
$startedAt = Get-Date
$artifactStamp = $startedAt.ToString("yyyyMMdd-HHmmss")
$artifactDir = Join-Path $repoRoot (Join-Path $OutputRoot $artifactStamp)
New-Item -ItemType Directory -Force -Path $artifactDir | Out-Null
$script:ScenarioSummaryRows = @()
$script:ScenarioBudgetFailed = $false
$script:ProfileSummaryRows = @()
$script:SlowProfileRows = @()
$script:PoolSummaryRows = @()

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
        $parsedError = $content
        if (-not [string]::IsNullOrWhiteSpace($content)) {
            try {
                $parsedError = $content | ConvertFrom-Json
            } catch {
                $parsedError = $content
            }
        }
        return [pscustomobject]@{
            StatusCode = $statusCode
            Body = $parsedError
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
        $details = if ($null -ne $Response.RawError) {
            $Response.RawError
        } elseif ($null -ne $Response.Body) {
            $Response.Body | ConvertTo-Json -Depth 12
        } else {
            "no response body"
        }
        throw "$Action failed with HTTP $($Response.StatusCode): $details"
    }
}

function Invoke-DockerPsql {
    param([string]$Sql)

    $output = $Sql | docker exec -i pynapli-postgres psql -U pynapli -d pynapli_dev -v ON_ERROR_STOP=1 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "psql failed: $output"
    }
    return $output
}

function Clear-GridRuntimeCaches {
    docker exec pynapli-redis redis-cli FLUSHDB | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to clear Redis before cold Grid load run."
    }
}

function New-LoadViewerSession {
    param(
        [int]$Index,
        [string]$Tier
    )

    $email = "grid-load-$Index@pynapli.local"
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
            deviceId = "grid-load-$Index"
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
                deviceId = "grid-load-$Index"
                platform = "load-test"
                appVersion = "0.1.0"
            }
        Assert-Success -Response $login -Action "Login $email"
        $login.Body
    } else {
        Assert-Success -Response $signup -Action "Signup $email"
    }

    $profile = Invoke-PynapliJson `
        -Method PATCH `
        -Path "/v1/me/profile" `
        -AccessToken $session.accessToken `
        -Body @{
            displayName = "Grid Load $Index"
            birthdate = "1990-01-01"
            # Structured gender/orientation keys (ProfileTaxonomies) — the profile API rejects free
            # text since the gender/orientation write path went structured (#118).
            gender = if ($Index % 3 -eq 0) { "man" } elseif ($Index % 3 -eq 1) { "woman" } else { "non_binary" }
            orientation = "bisexual"
            relationshipContext = if ($Index % 3 -eq 0) { "linked" } else { "single" }
            discoveryStyle = if ($Index % 4 -eq 0) { "together" } elseif ($Index % 4 -eq 1) { "solo" } elseif ($Index % 4 -eq 2) { "both" } else { "social" }
        }
    Assert-Success -Response $profile -Action "Profile update $email"

    $location = Invoke-PynapliJson `
        -Method POST `
        -Path "/v1/location/current" `
        -AccessToken $session.accessToken `
        -Body @{
            latitude = $viewerLatitude + (($Index % 10) - 5) * 0.00005
            longitude = $viewerLongitude + (($Index % 10) - 5) * 0.00005
            accuracyMeters = 18
            source = "gps"
            city = "Mountain View"
            region = "CA"
            country = "US"
        }
    Assert-Success -Response $location -Action "Location update $email"

    return [pscustomobject]@{
        Index = $Index
        Email = $email
        Tier = $Tier
        UserId = $session.user.userId
        AccessToken = $session.accessToken
    }
}

function Get-TierForIndex {
    param([int]$Index)

    $bucket = $Index % 5
    if ($bucket -in @(0, 1)) { return "free" }
    if ($bucket -in @(2, 3)) { return "social" }
    return "select"
}

function Set-LoadSubscriptions {
    param([array]$Sessions)

    $paidSessions = @($Sessions | Where-Object { $_.Tier -ne "free" })
    if ($paidSessions.Count -eq 0) {
        return
    }

    $values = ($paidSessions | ForEach-Object {
        $subId = "00000000-0000-0000-2100-{0:d12}" -f $_.Index
        $externalId = "grid-load-$($_.Tier)-$($_.Index)"
        "('$subId'::uuid, '$($_.UserId)'::uuid, 'dev', '$externalId', '$($_.Tier)', 'active', now() - interval '1 minute', now() + interval '30 days')"
    }) -join ",`n"

    $sql = @"
INSERT INTO subscriptions (
    id,
    user_id,
    platform,
    external_subscription_id,
    tier,
    status,
    current_period_start,
    current_period_end
)
VALUES
$values
ON CONFLICT (platform, external_subscription_id) DO UPDATE
SET user_id = EXCLUDED.user_id,
    tier = EXCLUDED.tier,
    status = EXCLUDED.status,
    current_period_start = EXCLUDED.current_period_start,
    current_period_end = EXCLUDED.current_period_end,
    updated_at = now();
"@

    Invoke-DockerPsql -Sql $sql | Out-Null
}

function Seed-DenseCandidates {
    param([int]$Count)

    $sql = @"
WITH candidates AS (
    SELECT
        i,
        ('00000000-0000-0000-1000-' || lpad(i::text, 12, '0'))::uuid AS user_id,
        'Dense ' || i AS display_name,
        30 + (i % 25) AS display_age,
        CASE WHEN i % 3 = 0 THEN 'linked' ELSE 'single' END AS relationship_context,
        CASE (i % 4)
            WHEN 0 THEN 'solo'
            WHEN 1 THEN 'together'
            WHEN 2 THEN 'both'
            ELSE 'social'
        END AS discovery_style,
        $viewerLongitude + (((i % 100) - 50) * 0.00008) AS longitude,
        $viewerLatitude + ((((i / 100)::integer % 100) - 25) * 0.00008) AS latitude
    FROM generate_series(1, $Count) AS i
),
upsert_users AS (
    INSERT INTO users (id, status)
    SELECT user_id, 'active'
    FROM candidates
    ON CONFLICT (id) DO UPDATE
    SET status = 'active',
        deleted_at = NULL,
        updated_at = now()
    RETURNING id
),
upsert_profiles AS (
    INSERT INTO profiles (
        user_id,
        display_name,
        display_age,
        relationship_context,
        discovery_style,
        moderation_status,
        visibility_status,
        profile_completeness_score,
        verified_at,
        face_photo_verified_at,
        age_verified_at,
        intents,
        logistics_tags,
        lifestyle_tags
    )
    SELECT
        user_id,
        display_name,
        display_age,
        relationship_context,
        discovery_style,
        'approved',
        'visible',
        70,
        CASE WHEN i % 5 = 0 THEN now() ELSE NULL END,
        CASE WHEN i % 7 = 0 THEN now() ELSE NULL END,
        CASE WHEN i % 11 = 0 THEN now() ELSE NULL END,
        ARRAY[
            CASE WHEN i % 2 = 0 THEN 'chat_first' ELSE 'dating' END,
            CASE WHEN i % 5 = 0 THEN 'events' ELSE 'open_to_couples' END
        ]::text[],
        ARRAY[
            CASE WHEN i % 3 = 0 THEN 'public_meet_first' ELSE 'hosts' END
        ]::text[],
        ARRAY[
            CASE WHEN i % 4 = 0 THEN 'soft_swap' ELSE 'vanilla_social' END
        ]::text[]
    FROM candidates
    ON CONFLICT (user_id) DO UPDATE
    SET display_name = EXCLUDED.display_name,
        display_age = EXCLUDED.display_age,
        relationship_context = EXCLUDED.relationship_context,
        discovery_style = EXCLUDED.discovery_style,
        moderation_status = EXCLUDED.moderation_status,
        visibility_status = EXCLUDED.visibility_status,
        profile_completeness_score = EXCLUDED.profile_completeness_score,
        verified_at = EXCLUDED.verified_at,
        face_photo_verified_at = EXCLUDED.face_photo_verified_at,
        age_verified_at = EXCLUDED.age_verified_at,
        intents = EXCLUDED.intents,
        logistics_tags = EXCLUDED.logistics_tags,
        lifestyle_tags = EXCLUDED.lifestyle_tags,
        updated_at = now()
    RETURNING user_id
),
upsert_discovery AS (
    INSERT INTO discovery_settings (
        user_id,
        grid_visible,
        swipe_visible,
        incognito,
        show_distance,
        allow_grid_messages,
        allow_flirts,
        allow_album_requests
    )
    SELECT user_id, true, true, false, true, true, true, true
    FROM candidates
    ON CONFLICT (user_id) DO UPDATE
    SET grid_visible = true,
        swipe_visible = true,
        incognito = false,
        show_distance = true,
        allow_grid_messages = true,
        allow_flirts = true,
        allow_album_requests = true,
        updated_at = now()
    RETURNING user_id
)
-- Same shape the server stores (V116): the cell centre only, no accuracy, the cell's label as geohash.
INSERT INTO user_locations (
    user_id,
    geom,
    geohash,
    location_source,
    accuracy_meters,
    city,
    region,
    country,
    updated_at
)
SELECT
    user_id,
    pynapli_location_cell(ST_SetSRID(ST_MakePoint(longitude, latitude), 4326)::geography),
    pynapli_location_cell_label(ST_SetSRID(ST_MakePoint(longitude, latitude), 4326)::geography),
    'gps',
    NULL::integer,
    'Mountain View',
    'CA',
    'US',
    now()
FROM candidates
ON CONFLICT (user_id) DO UPDATE
SET geom = EXCLUDED.geom,
    geohash = EXCLUDED.geohash,
    location_source = EXCLUDED.location_source,
    accuracy_meters = NULL,
    city = EXCLUDED.city,
    region = EXCLUDED.region,
    country = EXCLUDED.country,
    updated_at = now();
"@

    Invoke-DockerPsql -Sql $sql | Out-Null
}

function Seed-OnlineCandidates {
    param([int]$Count)

    $max = [Math]::Min($Count, $CandidateCount)
    for ($i = 1; $i -le $max; $i++) {
        $id = "00000000-0000-0000-1000-{0:d12}" -f $i
        docker exec pynapli-redis redis-cli SETEX "presence:user:$id" 300 "load-test" | Out-Null
        if ($LASTEXITCODE -ne 0) {
            throw "Failed to seed Redis presence for $id"
        }
    }
}

function Update-GridPlannerStats {
    $sql = @"
ANALYZE users;
ANALYZE profiles;
ANALYZE discovery_settings;
ANALYZE user_locations;
ANALYZE user_blocks;
ANALYZE user_profile_links;
ANALYZE boost_activations;
ANALYZE media_albums;
ANALYZE media_album_items;
ANALYZE media_assets;
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

function Convert-MetricHeader {
    param([string]$Header)

    $metrics = @{}
    if ([string]::IsNullOrWhiteSpace($Header)) {
        return $metrics
    }

    foreach ($part in ($Header -split ",")) {
        $pair = $part -split "=", 2
        if ($pair.Count -ne 2) {
            continue
        }
        $name = $pair[0].Trim()
        $rawValue = $pair[1].Trim()
        $value = 0.0
        if ([double]::TryParse(
                $rawValue,
                [System.Globalization.NumberStyles]::Float,
                [System.Globalization.CultureInfo]::InvariantCulture,
                [ref]$value
            )) {
            $metrics[$name] = $value
        }
    }

    return $metrics
}

function Write-ScenarioSummary {
    param([array]$Results)

    $budgets = @{
        grid_initial = $GridInitialBudgetP95Ms
        grid_filter = $GridFilterBudgetP95Ms
        grid_page2 = $GridPageBudgetP95Ms
        profile_open = $ProfileOpenBudgetP95Ms
    }
    $failedBudget = $false

    Write-Host ""
    Write-Host "Scenario latency summary"
    Write-Host "------------------------"
    $summaryRows = New-Object System.Collections.Generic.List[object]
    foreach ($group in ($Results | Group-Object Scenario | Sort-Object Name)) {
        $successful = @($group.Group | Where-Object { $_.StatusCode -ge 200 -and $_.StatusCode -lt 300 })
        $errors = @($group.Group | Where-Object { $_.StatusCode -lt 200 -or $_.StatusCode -ge 300 })
        $latencies = @($successful | ForEach-Object { [double]$_.Millis })
        $p50 = Get-Percentile -Values $latencies -Percentile 50
        $p95 = Get-Percentile -Values $latencies -Percentile 95
        $max = if ($latencies.Count -gt 0) { [Math]::Round(($latencies | Measure-Object -Maximum).Maximum, 1) } else { $null }
        $budget = $budgets[$group.Name]
        $budgetStatus = if ($null -ne $budget -and $null -ne $p95) {
            if ($p95 -le $budget) {
                "ok"
            } else {
                $failedBudget = $true
                "over"
            }
        } else {
            "n/a"
        }

        $summaryRows.Add([pscustomobject]@{
            Scenario = $group.Name
            Count = $group.Count
            Errors = $errors.Count
            P50Ms = $p50
            P95Ms = $p95
            MaxMs = $max
            BudgetP95Ms = $budget
            Budget = $budgetStatus
        })
    }
    $summaryRows | Format-Table -AutoSize
    $script:ScenarioSummaryRows = @($summaryRows.ToArray())
    $script:ScenarioBudgetFailed = $failedBudget

    if ($Results.StatusCode -contains 0 -or @($Results | Where-Object { $_.StatusCode -ge 400 }).Count -gt 0) {
        Write-Host ""
        Write-Host "Failures"
        Write-Host "--------"
        $Results |
            Where-Object { $_.StatusCode -lt 200 -or $_.StatusCode -ge 300 } |
            Select-Object -First 20 Scenario, Tier, UserIndex, StatusCode, Error |
            Format-Table -AutoSize
    }

    if ($FailOnBudget -and $failedBudget) {
        Write-Host ""
        Write-Host "Budget gate will fail after writing artifacts."
    }
}

function Write-ProfileSummary {
    param([array]$Results)

    $rows = New-Object System.Collections.Generic.List[object]
    foreach ($result in $Results) {
        if ([string]::IsNullOrWhiteSpace($result.Profile)) {
            continue
        }
        $metrics = Convert-MetricHeader -Header $result.Profile
        foreach ($key in $metrics.Keys) {
            $rows.Add([pscustomobject]@{
                Scenario = $result.Scenario
                Phase = $key
                Millis = [double]$metrics[$key]
            })
        }
    }

    if ($rows.Count -eq 0) {
        return
    }

    Write-Host ""
    Write-Host "Server profile timing summary"
    Write-Host "-----------------------------"
    $summaryRows = New-Object System.Collections.Generic.List[object]
    foreach ($scenarioGroup in ($rows | Group-Object Scenario | Sort-Object Name)) {
        foreach ($phaseGroup in ($scenarioGroup.Group | Group-Object Phase | Sort-Object Name)) {
            $latencies = @($phaseGroup.Group | ForEach-Object { [double]$_.Millis })
            $summaryRows.Add([pscustomobject]@{
                Scenario = $scenarioGroup.Name
                Phase = $phaseGroup.Name
                Count = $phaseGroup.Count
                P50Ms = Get-Percentile -Values $latencies -Percentile 50
                P95Ms = Get-Percentile -Values $latencies -Percentile 95
                MaxMs = if ($latencies.Count -gt 0) { [Math]::Round(($latencies | Measure-Object -Maximum).Maximum, 1) } else { $null }
            })
        }
    }
    $summaryRows | Format-Table -AutoSize
    $script:ProfileSummaryRows = @($summaryRows.ToArray())
}

function Write-SlowProfileSummary {
    param([array]$Results)

    $rows = New-Object System.Collections.Generic.List[object]
    foreach ($result in $Results) {
        if ([string]::IsNullOrWhiteSpace($result.SlowProfile)) {
            continue
        }
        $metrics = Convert-MetricHeader -Header $result.SlowProfile
        foreach ($key in $metrics.Keys) {
            $rows.Add([pscustomobject]@{
                Scenario = $result.Scenario
                Tier = $result.Tier
                UserIndex = $result.UserIndex
                Phase = $key
                Millis = [double]$metrics[$key]
            })
        }
    }

    if ($rows.Count -eq 0) {
        return
    }

    Write-Host ""
    Write-Host "Slow server profile spans"
    Write-Host "-------------------------"
    $rows |
        Sort-Object Millis -Descending |
        Select-Object -First 30 Scenario, Tier, UserIndex, Phase, Millis |
        Format-Table -AutoSize
    $script:SlowProfileRows = @($rows | Sort-Object Millis -Descending | Select-Object -First 30 Scenario, Tier, UserIndex, Phase, Millis)
}

function Write-PoolSummary {
    param([array]$Results)

    $rows = New-Object System.Collections.Generic.List[object]
    foreach ($result in $Results) {
        if ([string]::IsNullOrWhiteSpace($result.Pool)) {
            continue
        }
        $metrics = Convert-MetricHeader -Header $result.Pool
        foreach ($key in @("active", "idle", "total", "waiting")) {
            if ($metrics.ContainsKey($key)) {
                $rows.Add([pscustomobject]@{
                    Scenario = $result.Scenario
                    Metric = $key
                    Value = [double]$metrics[$key]
                })
            }
        }
    }

    if ($rows.Count -eq 0) {
        return
    }

    Write-Host ""
    Write-Host "Hikari pool snapshot summary"
    Write-Host "---------------------------"
    $summaryRows = New-Object System.Collections.Generic.List[object]
    foreach ($scenarioGroup in ($rows | Group-Object Scenario | Sort-Object Name)) {
        foreach ($metricGroup in ($scenarioGroup.Group | Group-Object Metric | Sort-Object Name)) {
            $values = @($metricGroup.Group | ForEach-Object { [double]$_.Value })
            $summaryRows.Add([pscustomobject]@{
                Scenario = $scenarioGroup.Name
                Metric = $metricGroup.Name
                P50 = Get-Percentile -Values $values -Percentile 50
                P95 = Get-Percentile -Values $values -Percentile 95
                Max = if ($values.Count -gt 0) { [Math]::Round(($values | Measure-Object -Maximum).Maximum, 1) } else { $null }
            })
        }
    }
    $summaryRows | Format-Table -AutoSize
    $script:PoolSummaryRows = @($summaryRows.ToArray())
}

$health = Invoke-PynapliJson -Method GET -Path "/health/deep" -TimeoutSec 5
Assert-Success -Response $health -Action "Ktor deep health"

Write-Host "Grid API load artifacts: $artifactDir"
if ($ColdCache) {
    Write-Host "Clearing Redis runtime caches for cold Grid load run..."
    Clear-GridRuntimeCaches
}

Write-Host "Seeding $CandidateCount dense Grid candidates..."
Seed-DenseCandidates -Count $CandidateCount

Write-Host "Seeding $OnlineCandidateCount online candidate presence keys..."
Seed-OnlineCandidates -Count $OnlineCandidateCount

Write-Host "Preparing $ViewerCount mixed-tier viewer sessions..."
$sessions = for ($i = 1; $i -le $ViewerCount; $i++) {
    New-LoadViewerSession -Index $i -Tier (Get-TierForIndex -Index $i)
}
Set-LoadSubscriptions -Sessions $sessions

Write-Host "Refreshing Grid planner statistics..."
Update-GridPlannerStats

Write-Host "Running API load: $ViewerCount viewers, throttle $ConcurrentUsers..."
$results = $sessions | ForEach-Object -ThrottleLimit $ConcurrentUsers -Parallel {
    $session = $_
    $baseUrl = $using:BaseUrl

    function Invoke-LoadRequest {
        param(
            [string]$Scenario,
            [ValidateSet("GET", "POST")]
            [string]$Method,
            [string]$Path,
            [object]$Body = $null
        )

        $headers = @{
            Accept = "application/json"
            Authorization = "Bearer $($session.AccessToken)"
        }
        if ($using:ServerProfile) {
            $headers["X-Pynapli-Profile"] = "true"
        }

        function Get-HeaderValue {
            param(
                [object]$Headers,
                [string]$Name
            )

            $value = $Headers[$Name]
            if ($null -eq $value) {
                return $null
            }
            if ($value -is [array]) {
                return ($value -join ",")
            }
            return [string]$value
        }

        $parameters = @{
            Method = $Method
            Uri = "$baseUrl$Path"
            Headers = $headers
            TimeoutSec = 30
            ErrorAction = "Stop"
        }
        if ($null -ne $Body) {
            $parameters.ContentType = "application/json"
            $parameters.Body = $Body | ConvertTo-Json -Depth 12 -Compress
        }

        $stopwatch = [System.Diagnostics.Stopwatch]::StartNew()
        $statusCode = 0
        $bodyObject = $null
        $errorText = $null
        $profileHeader = $null
        $slowProfileHeader = $null
        $poolHeader = $null
        try {
            $response = Invoke-WebRequest @parameters
            $statusCode = [int]$response.StatusCode
            $profileHeader = Get-HeaderValue -Headers $response.Headers -Name "X-Pynapli-Profile"
            $slowProfileHeader = Get-HeaderValue -Headers $response.Headers -Name "X-Pynapli-Profile-Slow"
            $poolHeader = Get-HeaderValue -Headers $response.Headers -Name "X-Pynapli-Pool"
            if (-not [string]::IsNullOrWhiteSpace($response.Content)) {
                $bodyObject = $response.Content | ConvertFrom-Json
            }
        } catch {
            if ($_.Exception.Response) {
                $statusCode = [int]$_.Exception.Response.StatusCode
            }
            $errorText = $_.ErrorDetails.Message
            if ([string]::IsNullOrWhiteSpace($errorText)) {
                $errorText = $_.Exception.Message
            }
        } finally {
            $stopwatch.Stop()
        }

        $itemCount = if ($null -ne $bodyObject -and $null -ne $bodyObject.items) {
            @($bodyObject.items).Count
        } else {
            $null
        }

        return [pscustomobject]@{
            Record = [pscustomobject]@{
                Scenario = $Scenario
                Tier = $session.Tier
                UserIndex = $session.Index
                StatusCode = $statusCode
                Millis = [Math]::Round($stopwatch.Elapsed.TotalMilliseconds, 1)
                ItemCount = $itemCount
                NextCursor = if ($null -ne $bodyObject) { $bodyObject.nextCursor } else { $null }
                ResultLimitReached = if ($null -ne $bodyObject) { $bodyObject.resultLimitReached } else { $null }
                Profile = $profileHeader
                SlowProfile = $slowProfileHeader
                Pool = $poolHeader
                Error = $errorText
            }
            Body = $bodyObject
        }
    }

    $records = New-Object System.Collections.Generic.List[object]

    $initial = Invoke-LoadRequest `
        -Scenario "grid_initial" `
        -Method POST `
        -Path "/v1/grid/query" `
        -Body @{
            radiusMeters = 100000
            limit = 120
        }
    $records.Add($initial.Record)

    $filterBody = switch ($session.Index % 4) {
        0 { @{ radiusMeters = 100000; limit = 120; relationshipContexts = @("single") } }
        1 { @{ radiusMeters = 100000; limit = 120; relationshipContexts = @("linked") } }
        2 { @{ radiusMeters = 100000; limit = 120; minimumAge = 35; maximumAge = 45 } }
        default { @{ radiusMeters = 100000; limit = 120; discoveryStyles = @("social", "both"); intents = @("chat_first", "events") } }
    }
    $filtered = Invoke-LoadRequest -Scenario "grid_filter" -Method POST -Path "/v1/grid/query" -Body $filterBody
    $records.Add($filtered.Record)

    if ($null -ne $initial.Body -and -not [string]::IsNullOrWhiteSpace($initial.Body.nextCursor)) {
        $page2 = Invoke-LoadRequest `
            -Scenario "grid_page2" `
            -Method POST `
            -Path "/v1/grid/query" `
            -Body @{
                radiusMeters = 100000
                limit = 120
                cursor = $initial.Body.nextCursor
            }
        $records.Add($page2.Record)
    }

    $targetUserId = $null
    if ($null -ne $initial.Body -and $null -ne $initial.Body.items -and @($initial.Body.items).Count -gt 0) {
        $targetUserId = @($initial.Body.items)[0].userId
    } elseif ($null -ne $filtered.Body -and $null -ne $filtered.Body.items -and @($filtered.Body.items).Count -gt 0) {
        $targetUserId = @($filtered.Body.items)[0].userId
    }

    if (-not [string]::IsNullOrWhiteSpace($targetUserId)) {
        $profile = Invoke-LoadRequest -Scenario "profile_open" -Method GET -Path "/v1/profiles/$targetUserId"
        $records.Add($profile.Record)
    } else {
        $records.Add([pscustomobject]@{
            Scenario = "profile_open"
            Tier = $session.Tier
            UserIndex = $session.Index
            StatusCode = 0
            Millis = 0
            ItemCount = $null
            NextCursor = $null
            ResultLimitReached = $null
            Profile = $null
            SlowProfile = $null
            Pool = $null
            Error = "No grid target returned for profile open."
        })
    }

    return $records
}

Write-ScenarioSummary -Results @($results)
Write-ProfileSummary -Results @($results)
Write-SlowProfileSummary -Results @($results)
Write-PoolSummary -Results @($results)

Write-Host ""
Write-Host "Tier mix"
Write-Host "--------"
$sessions | Group-Object Tier | Sort-Object Name | Select-Object Name, Count | Format-Table -AutoSize

$finishedAt = Get-Date
$tierMix = @($sessions | Group-Object Tier | Sort-Object Name | ForEach-Object {
    [pscustomobject]@{
        Tier = $_.Name
        Count = $_.Count
    }
})

$parameters = [pscustomobject]@{
    baseUrl = $BaseUrl
    candidateCount = $CandidateCount
    viewerCount = $ViewerCount
    concurrentUsers = $ConcurrentUsers
    onlineCandidateCount = $OnlineCandidateCount
    coldCache = [bool]$ColdCache
    serverProfile = [bool]$ServerProfile
    failOnBudget = [bool]$FailOnBudget
    budgets = [pscustomobject]@{
        gridInitialP95Ms = $GridInitialBudgetP95Ms
        gridFilterP95Ms = $GridFilterBudgetP95Ms
        gridPageP95Ms = $GridPageBudgetP95Ms
        profileOpenP95Ms = $ProfileOpenBudgetP95Ms
    }
}

$summary = [pscustomobject]@{
    startedAt = $startedAt.ToUniversalTime().ToString("o")
    finishedAt = $finishedAt.ToUniversalTime().ToString("o")
    durationSeconds = [Math]::Round(($finishedAt - $startedAt).TotalSeconds, 1)
    parameters = $parameters
    scenarioSummary = @($script:ScenarioSummaryRows)
    profileSummary = @($script:ProfileSummaryRows)
    slowProfile = @($script:SlowProfileRows)
    poolSummary = @($script:PoolSummaryRows)
    tierMix = $tierMix
    errorCount = @($results | Where-Object { $_.StatusCode -lt 200 -or $_.StatusCode -ge 300 }).Count
    budgetFailed = [bool]$script:ScenarioBudgetFailed
}

$summaryPath = Join-Path $artifactDir "summary.json"
$resultsPath = Join-Path $artifactDir "results.csv"
$summary | ConvertTo-Json -Depth 12 | Set-Content -Path $summaryPath -Encoding UTF8
$results | Export-Csv -NoTypeInformation -Path $resultsPath

Write-Host ""
Write-Host "Grid API load artifacts written:"
Write-Host "  Summary: $summaryPath"
Write-Host "  Results: $resultsPath"
Write-Host "Grid API load test complete."

if ($FailOnBudget -and $script:ScenarioBudgetFailed) {
    throw "One or more scenarios exceeded the configured p95 budget. Artifacts were written to $artifactDir"
}
