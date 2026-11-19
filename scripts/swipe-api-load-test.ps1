param(
    [string]$BaseUrl = "http://localhost:8080",
    [string]$Password = "PynapliLoadPass123!",
    [int]$CandidateCount = 5000,
    [int]$ViewerCount = 100,
    [int]$ConcurrentUsers = 100,
    [int]$OnlineCandidateCount = 1000,
    [int]$DeckInitialBudgetP95Ms = 800,
    [int]$DeckFilterBudgetP95Ms = 500,
    [int]$DeckFilterRelaxedBudgetP95Ms = 1500,
    [int]$DeckPageBudgetP95Ms = 800,
    [int]$BoostDeckBudgetP95Ms = 800,
    [int]$TravelSetBudgetP95Ms = 500,
    [int]$TravelDeckBudgetP95Ms = 1000,
    [int]$ActionBudgetP95Ms = 400,
    [int]$RewindBudgetP95Ms = 600,
    [int]$BoostedCandidateCount = 100,
    # 0 (default) keeps the historical dense seed: every candidate within ~0.5 km of the viewer
    # point, so runs stay comparable with the 2026-05-18 and 2026-11-18 baselines. A value above 0
    # spreads candidates across a metro-shaped disc of that radius in km (see Seed-DenseCandidates).
    # Every deck request asks for 50 km, so keep this at or below ~48 km to keep all candidates
    # inside the requested radius after the server's cell-to-cell distance rounding.
    [int]$CandidateSpreadKm = 0,
    [string]$OutputRoot = "artifacts\swipe-api-load",
    [switch]$ColdCache,
    [switch]$ServerProfile,
    [switch]$SkipSwipeStateReset,
    [switch]$SkipExtendedScenarios,
    [switch]$SkipSafetyProbes,
    [switch]$FailOnBudget
)

$ErrorActionPreference = "Stop"

if ($PSVersionTable.PSVersion.Major -lt 7) {
    throw "scripts/swipe-api-load-test.ps1 requires PowerShell 7+ for parallel execution."
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
$script:ScenarioUnexpectedFailureCount = 0
$script:ProfileSummaryRows = @()
$script:SlowProfileRows = @()
$script:PoolSummaryRows = @()
$script:SafetyProbeRows = @()

function Invoke-PynapliJson {
    param(
        [ValidateSet("GET", "POST", "PATCH", "DELETE")]
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

function Clear-SwipeRuntimeCaches {
    docker exec pynapli-redis redis-cli FLUSHDB | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to clear Redis before cold Swipe load run."
    }
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

function Reset-SwipeRunState {
    # Wipe per-run swipe state so consecutive runs do not poison query plans or
    # exhaust dealt cards. Load viewers are found by their deterministic email
    # identity hashes; dense seeded candidates use the 1000 UUID range.
    $emailHashes = for ($i = 1; $i -le $ViewerCount; $i++) {
        ConvertTo-Sha256Base64Url "swipe-load-$i@pynapli.local"
    }
    $quotedEmailHashes = ($emailHashes | ForEach-Object { "'$_'" }) -join ","
    $sql = @"
DROP TABLE IF EXISTS load_run_users;

CREATE TEMP TABLE load_run_users AS
SELECT user_id
FROM auth_identities
WHERE provider = 'email'
  AND provider_subject IN ($quotedEmailHashes);

DELETE FROM matches
WHERE user_a IN (SELECT user_id FROM load_run_users)
   OR user_b IN (SELECT user_id FROM load_run_users);

DELETE FROM swipe_actions
WHERE actor_user_id IN (SELECT user_id FROM load_run_users)
   OR target_user_id IN (SELECT user_id FROM load_run_users);

DELETE FROM swipe_deck_cards
WHERE viewer_user_id IN (SELECT user_id FROM load_run_users);

DELETE FROM swipe_deck_sessions
WHERE viewer_user_id IN (SELECT user_id FROM load_run_users);

DELETE FROM travel_locations
WHERE user_id IN (SELECT user_id FROM load_run_users);

DELETE FROM user_sessions
WHERE user_id IN (SELECT user_id FROM load_run_users);

DELETE FROM boost_activations
WHERE user_id IN (SELECT id FROM users WHERE id::text LIKE '00000000-0000-0000-1000-%');

DROP TABLE IF EXISTS load_run_users;
"@

    Invoke-DockerPsql -Sql $sql | Out-Null
}

function New-LoadViewerSession {
    param(
        [int]$Index,
        [string]$Tier
    )

    $email = "swipe-load-$Index@pynapli.local"
    $signup = Invoke-PynapliJson `
        -Method POST `
        -Path "/v1/auth/email/signup" `
        -Body @{
            email = $email
            password = $Password
            birthdate = "1990-01-01"
            confirmedAdult = $true
            acceptedTerms = $true
            acceptedPrivacy = $true
            deviceId = "swipe-load-$Index"
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
                deviceId = "swipe-load-$Index"
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
            displayName = "Swipe Load $Index"
            birthdate = "1990-01-01"
            gender = "non_binary"
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
        $subId = "00000000-0000-0000-2200-{0:d12}" -f $_.Index
        $externalId = "swipe-load-$($_.Tier)-$($_.Index)"
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

    # Reuses the same 1000-prefix candidate range as the Grid harness. Profiles and
    # locations are idempotent so running both harnesses against the same DB is fine.
    # Adds profiles.bio so the new SwipeService.toShortBio() path has something to trim.
    $longitudeSql = "$viewerLongitude + (((i % 100) - 50) * 0.00008)"
    $latitudeSql = "$viewerLatitude + ((((i / 100)::integer % 100) - 25) * 0.00008)"
    if ($CandidateSpreadKm -gt 0) {
        # Metro spread. Each candidate i gets two deterministic, low-discrepancy fractions from the
        # R2 (plastic-number) sequence: u = frac(0.5 + i*0.7548...), v = frac(0.5 + i*0.5698...).
        # They are evenly spread over [0,1) whatever the candidate count and uncorrelated with the
        # i % n attribute pattern above, so boosted/online candidates (low i) are not all central.
        # Distance from the viewer is r = spread * u (linear in u, not sqrt(u) as a uniform disc
        # would be), giving a surface density that falls off as 1/r: a dense core and a thin
        # outskirts, with an equal share of candidates in every 1 km ring (5000 over 40 km is
        # ~125 per ring). Bearing is 2*pi*v. Offsets use a flat-earth km-per-degree conversion,
        # accurate to well under 1% at metro scale. numeric mod() keeps it exact and run-stable.
        $uSql = "mod(0.5 + i * 0.7548776662466927, 1.0)::double precision"
        $vSql = "mod(0.5 + i * 0.5698402909980532, 1.0)::double precision"
        $longitudeSql = "$viewerLongitude + ($CandidateSpreadKm * $uSql) * cos(2 * pi() * $vSql) / (111.320 * cos(radians($viewerLatitude)))"
        $latitudeSql = "$viewerLatitude + ($CandidateSpreadKm * $uSql) * sin(2 * pi() * $vSql) / 110.574"
    }
    $sql = @"
WITH candidates AS (
    SELECT
        i,
        ('00000000-0000-0000-1000-' || lpad(i::text, 12, '0'))::uuid AS user_id,
        'Dense ' || i AS display_name,
        'Sample bio for candidate ' || i AS bio,
        30 + (i % 25) AS display_age,
        CASE WHEN i % 3 = 0 THEN 'linked' ELSE 'single' END AS relationship_context,
        CASE (i % 4)
            WHEN 0 THEN 'solo'
            WHEN 1 THEN 'together'
            WHEN 2 THEN 'both'
            ELSE 'social'
        END AS discovery_style,
        $longitudeSql AS longitude,
        $latitudeSql AS latitude
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
        bio,
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
        bio,
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
        bio = EXCLUDED.bio,
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
INSERT INTO user_locations (
    user_id,
    geom,
    location_source,
    accuracy_meters,
    city,
    region,
    country,
    updated_at
)
SELECT
    user_id,
    ST_SetSRID(ST_MakePoint(longitude, latitude), 4326)::geography,
    'gps',
    12,
    'Mountain View',
    'CA',
    'US',
    now()
FROM candidates
ON CONFLICT (user_id) DO UPDATE
SET geom = EXCLUDED.geom,
    location_source = EXCLUDED.location_source,
    accuracy_meters = EXCLUDED.accuracy_meters,
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
    if ($max -le 0) {
        return
    }

    $seedScript = "for i in `$(seq 1 $max); do id=`$(printf '00000000-0000-0000-1000-%012d' `$i); redis-cli SETEX `"presence:user:`$id`" 300 load-test >/dev/null || exit 1; done"
    docker exec pynapli-redis sh -c $seedScript
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to seed Redis presence for $max candidates"
    }
}

function Seed-BoostedCandidates {
    param([int]$Count)

    $max = [Math]::Min($Count, $CandidateCount)
    if ($max -le 0) {
        return
    }

    $sql = @"
WITH boosted AS (
    SELECT
        i,
        ('00000000-0000-0000-1000-' || lpad(i::text, 12, '0'))::uuid AS user_id,
        ('00000000-0000-0000-2400-' || lpad(i::text, 12, '0'))::uuid AS activation_id
    FROM generate_series(1, $max) AS i
)
INSERT INTO boost_activations (
    id,
    user_id,
    boost_type,
    source,
    starts_at,
    ends_at,
    status
)
SELECT
    activation_id,
    user_id,
    'boost',
    'dev',
    now() - interval '1 minute',
    now() + interval '29 minutes',
    'active'
FROM boosted
ON CONFLICT (id) DO UPDATE
SET starts_at = EXCLUDED.starts_at,
    ends_at = EXCLUDED.ends_at,
    status = EXCLUDED.status;
"@

    Invoke-DockerPsql -Sql $sql | Out-Null
}

function Update-SwipePlannerStats {
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
ANALYZE swipe_actions;
ANALYZE swipe_deck_sessions;
ANALYZE swipe_deck_cards;
ANALYZE matches;
ANALYZE travel_locations;
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

function Get-LoadRecordAccepted {
    param([pscustomobject]$Record)

    if ($Record.StatusCode -ge 200 -and $Record.StatusCode -lt 300) {
        return $true
    }
    if (
        -not [string]::IsNullOrWhiteSpace($Record.ExpectedErrorCode) -and
        $Record.StatusCode -ge 400 -and
        $Record.StatusCode -lt 500 -and
        $Record.ErrorCode -eq $Record.ExpectedErrorCode
    ) {
        return $true
    }
    return $false
}

function Write-ScenarioSummary {
    param([array]$Results)

    $budgets = @{
        swipe_deck_initial = $DeckInitialBudgetP95Ms
        swipe_deck_filtered = $DeckFilterBudgetP95Ms
        swipe_deck_filtered_relaxed = $DeckFilterRelaxedBudgetP95Ms
        swipe_deck_page2 = $DeckPageBudgetP95Ms
        swipe_deck_boosted = $BoostDeckBudgetP95Ms
        travel_set = $TravelSetBudgetP95Ms
        swipe_deck_travel = $TravelDeckBudgetP95Ms
        swipe_action_like = $ActionBudgetP95Ms
        swipe_action_pass = $ActionBudgetP95Ms
        swipe_rewind = $RewindBudgetP95Ms
    }
    $failedBudget = $false

    Write-Host ""
    Write-Host "Scenario latency summary"
    Write-Host "------------------------"
    $summaryRows = New-Object System.Collections.Generic.List[object]
    foreach ($group in ($Results | Group-Object Scenario | Sort-Object Name)) {
        $accepted = @($group.Group | Where-Object { Get-LoadRecordAccepted -Record $_ })
        $errors = @($group.Group | Where-Object { -not (Get-LoadRecordAccepted -Record $_) })
        $expectedFailures = @($group.Group | Where-Object {
                (-not [string]::IsNullOrWhiteSpace($_.ExpectedErrorCode)) -and
                (Get-LoadRecordAccepted -Record $_)
            })
        $latencies = @($accepted | ForEach-Object { [double]$_.Millis })
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
            ExpectedFailures = $expectedFailures.Count
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

    $unexpectedFailures = @($Results | Where-Object { -not (Get-LoadRecordAccepted -Record $_) })
    $script:ScenarioUnexpectedFailureCount = $unexpectedFailures.Count
    if ($unexpectedFailures.Count -gt 0) {
        Write-Host ""
        Write-Host "Unexpected failures"
        Write-Host "-------------------"
        $unexpectedFailures |
            Select-Object -First 20 Scenario, Tier, UserIndex, StatusCode, ErrorCode, ExpectedErrorCode, Error |
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

function Get-PynapliErrorCode {
    param([pscustomobject]$Response)

    if ($null -eq $Response -or $null -eq $Response.Body) {
        return $null
    }
    $propertyNames = @($Response.Body.PSObject.Properties.Name)
    if ($propertyNames -contains "code") {
        return [string]$Response.Body.code
    }
    if ($propertyNames -contains "error") {
        return [string]$Response.Body.error
    }
    return $null
}

function Add-SafetyProbeResult {
    param(
        [System.Collections.Generic.List[object]]$Rows,
        [string]$Probe,
        [pscustomobject]$Response,
        [string]$ExpectedCode,
        [bool]$ExpectedSuccess
    )

    $actualCode = Get-PynapliErrorCode -Response $Response
    $passed = if ($ExpectedSuccess) {
        $Response.StatusCode -ge 200 -and $Response.StatusCode -lt 300
    } else {
        $Response.StatusCode -ge 400 -and $actualCode -eq $ExpectedCode
    }
    $Rows.Add([pscustomobject]@{
        Probe = $Probe
        StatusCode = $Response.StatusCode
        ExpectedCode = $ExpectedCode
        ActualCode = $actualCode
        Passed = $passed
    })
    if (-not $passed) {
        $body = if ($null -ne $Response.Body) { $Response.Body | ConvertTo-Json -Depth 12 -Compress } else { $Response.RawError }
        throw "Swipe safety probe '$Probe' failed. HTTP $($Response.StatusCode), expected=$ExpectedCode, actual=$actualCode, body=$body"
    }
}

function Invoke-SwipeSafetyProbes {
    param([pscustomobject]$Session)

    Write-Host ""
    Write-Host "Swipe safety probes"
    Write-Host "-------------------"

    $rows = New-Object System.Collections.Generic.List[object]
    $deck = Invoke-PynapliJson `
        -Method POST `
        -Path "/v1/swipe/deck/query" `
        -AccessToken $Session.AccessToken `
        -Body @{
            radiusMeters = 50000
            limit = 5
        }
    Assert-Success -Response $deck -Action "Safety probe deck query"

    $cards = if ($null -ne $deck.Body -and $null -ne $deck.Body.items) { @($deck.Body.items) } else { @() }
    if ($cards.Count -lt 3 -or [string]::IsNullOrWhiteSpace($deck.Body.deckSessionId)) {
        throw "Safety probe deck query returned fewer than 3 cards; cannot prove token/idempotency invariants."
    }

    $deckSessionId = $deck.Body.deckSessionId
    $first = $cards[0]
    $second = $cards[1]
    $third = $cards[2]

    $mismatchedTarget = Invoke-PynapliJson `
        -Method POST `
        -Path "/v1/swipe/actions" `
        -AccessToken $Session.AccessToken `
        -Body @{
            actionId = [guid]::NewGuid().ToString()
            deckSessionId = $deckSessionId
            cardToken = $first.rankToken
            targetUserId = $second.userId
            action = "like"
        }
    Add-SafetyProbeResult `
        -Rows $rows `
        -Probe "mismatched_card_target_rejected" `
        -Response $mismatchedTarget `
        -ExpectedCode "INVALID_SWIPE_CARD_TOKEN" `
        -ExpectedSuccess $false

    $actionId = [guid]::NewGuid().ToString()
    $validLike = Invoke-PynapliJson `
        -Method POST `
        -Path "/v1/swipe/actions" `
        -AccessToken $Session.AccessToken `
        -Body @{
            actionId = $actionId
            deckSessionId = $deckSessionId
            cardToken = $first.rankToken
            targetUserId = $first.userId
            action = "like"
        }
    Add-SafetyProbeResult `
        -Rows $rows `
        -Probe "valid_like_records" `
        -Response $validLike `
        -ExpectedCode "2xx" `
        -ExpectedSuccess $true

    $idempotentRetry = Invoke-PynapliJson `
        -Method POST `
        -Path "/v1/swipe/actions" `
        -AccessToken $Session.AccessToken `
        -Body @{
            actionId = $actionId
            deckSessionId = $deckSessionId
            cardToken = $first.rankToken
            targetUserId = $first.userId
            action = "like"
        }
    Add-SafetyProbeResult `
        -Rows $rows `
        -Probe "same_action_id_same_payload_idempotent" `
        -Response $idempotentRetry `
        -ExpectedCode "2xx" `
        -ExpectedSuccess $true

    $actionIdConflict = Invoke-PynapliJson `
        -Method POST `
        -Path "/v1/swipe/actions" `
        -AccessToken $Session.AccessToken `
        -Body @{
            actionId = $actionId
            deckSessionId = $deckSessionId
            cardToken = $third.rankToken
            targetUserId = $third.userId
            action = "pass"
        }
    Add-SafetyProbeResult `
        -Rows $rows `
        -Probe "same_action_id_different_payload_rejected" `
        -Response $actionIdConflict `
        -ExpectedCode "INVALID_SWIPE_ACTION_ID" `
        -ExpectedSuccess $false

    $alreadyActionedCard = Invoke-PynapliJson `
        -Method POST `
        -Path "/v1/swipe/actions" `
        -AccessToken $Session.AccessToken `
        -Body @{
            actionId = [guid]::NewGuid().ToString()
            deckSessionId = $deckSessionId
            cardToken = $first.rankToken
            targetUserId = $first.userId
            action = "like"
        }
    Add-SafetyProbeResult `
        -Rows $rows `
        -Probe "already_actioned_card_token_rejected" `
        -Response $alreadyActionedCard `
        -ExpectedCode "INVALID_SWIPE_CARD_TOKEN" `
        -ExpectedSuccess $false

    $script:SafetyProbeRows = @($rows.ToArray())
    $script:SafetyProbeRows | Format-Table -AutoSize
}

$health = Invoke-PynapliJson -Method GET -Path "/health/deep" -TimeoutSec 5
Assert-Success -Response $health -Action "Ktor deep health"

Write-Host "Swipe API load artifacts: $artifactDir"
if ($ColdCache) {
    Write-Host "Clearing Redis runtime caches for cold Swipe load run..."
    Clear-SwipeRuntimeCaches
}

if (-not $SkipSwipeStateReset) {
    Write-Host "Resetting load-tester swipe state (matches, actions, deck sessions/cards)..."
    Reset-SwipeRunState
}

if ($CandidateSpreadKm -gt 0) {
    Write-Host "Seeding $CandidateCount Swipe candidates across a $CandidateSpreadKm km metro spread..."
} else {
    Write-Host "Seeding $CandidateCount dense Swipe candidates..."
}
Seed-DenseCandidates -Count $CandidateCount

if (-not $SkipExtendedScenarios) {
    Write-Host "Seeding $BoostedCandidateCount active boosted candidates..."
    Seed-BoostedCandidates -Count $BoostedCandidateCount
}

Write-Host "Seeding $OnlineCandidateCount online candidate presence keys..."
Seed-OnlineCandidates -Count $OnlineCandidateCount

Write-Host "Preparing $ViewerCount mixed-tier viewer sessions..."
$sessions = for ($i = 1; $i -le $ViewerCount; $i++) {
    New-LoadViewerSession -Index $i -Tier (Get-TierForIndex -Index $i)
}
Set-LoadSubscriptions -Sessions $sessions

Write-Host "Refreshing Swipe planner statistics..."
Update-SwipePlannerStats

Write-Host "Running API load: $ViewerCount viewers, throttle $ConcurrentUsers..."
$results = $sessions | ForEach-Object -ThrottleLimit $ConcurrentUsers -Parallel {
    $session = $_
    $baseUrl = $using:BaseUrl

    function New-ActionId {
        [guid]::NewGuid().ToString()
    }

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
            if (-not [string]::IsNullOrWhiteSpace($errorText)) {
                try {
                    $bodyObject = $errorText | ConvertFrom-Json
                } catch {
                    $bodyObject = $null
                }
            }
        } finally {
            $stopwatch.Stop()
        }

        $itemCount = if ($null -ne $bodyObject -and $null -ne $bodyObject.items) {
            @($bodyObject.items).Count
        } else {
            $null
        }
        $errorCode = if (
            $null -ne $bodyObject -and
            @($bodyObject.PSObject.Properties.Name) -contains "code"
        ) {
            [string]$bodyObject.code
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
                DeckSessionId = if ($null -ne $bodyObject) { $bodyObject.deckSessionId } else { $null }
                Profile = $profileHeader
                SlowProfile = $slowProfileHeader
                Pool = $poolHeader
                ErrorCode = $errorCode
                ExpectedErrorCode = $null
                Error = $errorText
            }
            Body = $bodyObject
        }
    }

    $records = New-Object System.Collections.Generic.List[object]

    # Initial deck — no filters beyond defaults.
    $initial = Invoke-LoadRequest `
        -Scenario "swipe_deck_initial" `
        -Method POST `
        -Path "/v1/swipe/deck/query" `
        -Body @{
            radiusMeters = 50000
            limit = 30
        }
    $records.Add($initial.Record)

    # Per-viewer filter mix exercises the supported filter surface today.
    # Index modulo decides which filter shape so the harness covers the matrix.
    # Branches 4 and 5 exercise the B1 expanded filter surface (verifiedPreferred, intents).
    $filterBody = switch ($session.Index % 6) {
        0 { @{ radiusMeters = 50000; limit = 30; relationshipContexts = @("single") } }
        1 { @{ radiusMeters = 50000; limit = 30; linkedOnly = $true; dealbreakers = @("linkedOnly") } }
        2 { @{ radiusMeters = 50000; limit = 30; minimumAge = 30; maximumAge = 45 } }
        3 { @{ radiusMeters = 50000; limit = 30; relationshipContexts = @("linked"); minimumAge = 30; maximumAge = 50 } }
        4 { @{ radiusMeters = 50000; limit = 30; verifiedPreferred = $true; intents = @("dating") } }
        default { @{ radiusMeters = 50000; limit = 30; discoveryStyles = @("solo", "both") } }
    }
    $filtered = Invoke-LoadRequest `
        -Scenario "swipe_deck_filtered" `
        -Method POST `
        -Path "/v1/swipe/deck/query" `
        -Body $filterBody
    $records.Add($filtered.Record)

    # B3 relaxation trigger: intents=[open_to_singles] is never seeded on dense
    # candidates (Seed-DenseCandidates writes either ["chat_first","open_to_couples"]
    # or ["dating","events"]), so the first pass returns zero rows. With no
    # dealbreakers set, intents is flexible -> SwipeService relaxes and re-queries.
    # This scenario measures the relaxed-pass cost end-to-end at concurrent load.
    $relaxed = Invoke-LoadRequest `
        -Scenario "swipe_deck_filtered_relaxed" `
        -Method POST `
        -Path "/v1/swipe/deck/query" `
        -Body @{
            radiusMeters = 50000
            limit = 30
            intents = @("open_to_singles")
        }
    $records.Add($relaxed.Record)

    if ($null -ne $initial.Body -and -not [string]::IsNullOrWhiteSpace($initial.Body.nextCursor)) {
        $page2 = Invoke-LoadRequest `
            -Scenario "swipe_deck_page2" `
            -Method POST `
            -Path "/v1/swipe/deck/query" `
            -Body @{
                radiusMeters = 50000
                limit = 30
                cursor = $initial.Body.nextCursor
            }
        $records.Add($page2.Record)
    }

    if (-not $using:SkipExtendedScenarios) {
        # Boosted candidates are seeded globally for the load run. This scenario
        # keeps a separate budget row for the boosted-ranking deck shape.
        $boosted = Invoke-LoadRequest `
            -Scenario "swipe_deck_boosted" `
            -Method POST `
            -Path "/v1/swipe/deck/query" `
            -Body @{
                radiusMeters = 50000
                limit = 30
                verifiedPreferred = $true
            }
        $records.Add($boosted.Record)
    }

    # Submit one Like + one Pass against the first two cards from the initial deck.
    # Each viewer targets distinct candidates (offset by index) to avoid mutual matches.
    $deckCards = if ($null -ne $initial.Body -and $null -ne $initial.Body.items) { @($initial.Body.items) } else { @() }
    $deckSessionId = if ($null -ne $initial.Body) { $initial.Body.deckSessionId } else { $null }

    if ($deckCards.Count -ge 1 -and -not [string]::IsNullOrWhiteSpace($deckSessionId)) {
        $likeCard = $deckCards[0]
        $likeAction = Invoke-LoadRequest `
            -Scenario "swipe_action_like" `
            -Method POST `
            -Path "/v1/swipe/actions" `
            -Body @{
                actionId = (New-ActionId)
                deckSessionId = $deckSessionId
                cardToken = $likeCard.rankToken
                targetUserId = $likeCard.userId
                action = "like"
            }
        $records.Add($likeAction.Record)
    } else {
        $records.Add([pscustomobject]@{
            Scenario = "swipe_action_like"
            Tier = $session.Tier
            UserIndex = $session.Index
            StatusCode = 0
            Millis = 0
            ItemCount = $null
            NextCursor = $null
            DeckSessionId = $null
            Profile = $null
            SlowProfile = $null
            Pool = $null
            ErrorCode = $null
            ExpectedErrorCode = $null
            Error = "No card available for like."
        })
    }

    if ($deckCards.Count -ge 2 -and -not [string]::IsNullOrWhiteSpace($deckSessionId)) {
        $passCard = $deckCards[1]
        $passAction = Invoke-LoadRequest `
            -Scenario "swipe_action_pass" `
            -Method POST `
            -Path "/v1/swipe/actions" `
            -Body @{
                actionId = (New-ActionId)
                deckSessionId = $deckSessionId
                cardToken = $passCard.rankToken
                targetUserId = $passCard.userId
                action = "pass"
            }
        $records.Add($passAction.Record)
    } else {
        $records.Add([pscustomobject]@{
            Scenario = "swipe_action_pass"
            Tier = $session.Tier
            UserIndex = $session.Index
            StatusCode = 0
            Millis = 0
            ItemCount = $null
            NextCursor = $null
            DeckSessionId = $null
            Profile = $null
            SlowProfile = $null
            Pool = $null
            ErrorCode = $null
            ExpectedErrorCode = $null
            Error = "No card available for pass."
        })
    }

    # Rewind is paid-only; for free-tier viewers we expect ENTITLEMENT_REQUIRED and
    # we still record the latency so the budget tracks the gating cost.
    $rewind = Invoke-LoadRequest `
        -Scenario "swipe_rewind" `
        -Method POST `
        -Path "/v1/swipe/rewind"
    if ($session.Tier -eq "free") {
        $rewind.Record.ExpectedErrorCode = "ENTITLEMENT_REQUIRED"
    }
    $records.Add($rewind.Record)

    if (-not $using:SkipExtendedScenarios) {
        # Exercise travel-mode activation and the active-travel deck path after the
        # local deck/action/rewind scenarios so the baseline deck timings stay local.
        $travelSet = Invoke-LoadRequest `
            -Scenario "travel_set" `
            -Method POST `
            -Path "/v1/location/travel" `
            -Body @{
                latitude = 37.4220 + (($session.Index % 10) - 5) * 0.00005
                longitude = -122.0841 + (($session.Index % 10) - 5) * 0.00005
                city = "Mountain View"
                region = "CA"
                country = "US"
            }
        if ($session.Tier -eq "free") {
            $travelSet.Record.ExpectedErrorCode = "ENTITLEMENT_REQUIRED"
        }
        $records.Add($travelSet.Record)

        $travelDeck = Invoke-LoadRequest `
            -Scenario "swipe_deck_travel" `
            -Method POST `
            -Path "/v1/swipe/deck/query" `
            -Body @{
                radiusMeters = 50000
                limit = 30
            }
        $records.Add($travelDeck.Record)
    }

    return $records
}

Write-ScenarioSummary -Results @($results)
Write-ProfileSummary -Results @($results)
Write-SlowProfileSummary -Results @($results)
Write-PoolSummary -Results @($results)

if (-not $SkipSafetyProbes) {
    Invoke-SwipeSafetyProbes -Session $sessions[0]
}

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
    candidateSpreadKm = $CandidateSpreadKm
    viewerCount = $ViewerCount
    concurrentUsers = $ConcurrentUsers
    onlineCandidateCount = $OnlineCandidateCount
    boostedCandidateCount = $BoostedCandidateCount
    coldCache = [bool]$ColdCache
    serverProfile = [bool]$ServerProfile
    skipSwipeStateReset = [bool]$SkipSwipeStateReset
    skipExtendedScenarios = [bool]$SkipExtendedScenarios
    skipSafetyProbes = [bool]$SkipSafetyProbes
    failOnBudget = [bool]$FailOnBudget
    budgets = [pscustomobject]@{
        deckInitialP95Ms = $DeckInitialBudgetP95Ms
        deckFilterP95Ms = $DeckFilterBudgetP95Ms
        deckFilterRelaxedP95Ms = $DeckFilterRelaxedBudgetP95Ms
        deckPageP95Ms = $DeckPageBudgetP95Ms
        boostDeckP95Ms = $BoostDeckBudgetP95Ms
        travelSetP95Ms = $TravelSetBudgetP95Ms
        travelDeckP95Ms = $TravelDeckBudgetP95Ms
        actionP95Ms = $ActionBudgetP95Ms
        rewindP95Ms = $RewindBudgetP95Ms
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
    safetyProbes = @($script:SafetyProbeRows)
    tierMix = $tierMix
    errorCount = @($results | Where-Object { -not (Get-LoadRecordAccepted -Record $_) }).Count
    budgetFailed = [bool]$script:ScenarioBudgetFailed
}

$summaryPath = Join-Path $artifactDir "summary.json"
$resultsPath = Join-Path $artifactDir "results.csv"
$safetyProbePath = Join-Path $artifactDir "safety-probes.json"
$summary | ConvertTo-Json -Depth 12 | Set-Content -Path $summaryPath -Encoding UTF8
$results | Export-Csv -NoTypeInformation -Path $resultsPath
if ($script:SafetyProbeRows.Count -gt 0) {
    $script:SafetyProbeRows | ConvertTo-Json -Depth 12 | Set-Content -Path $safetyProbePath -Encoding UTF8
}

Write-Host ""
Write-Host "Swipe API load artifacts written:"
Write-Host "  Summary: $summaryPath"
Write-Host "  Results: $resultsPath"
if ($script:SafetyProbeRows.Count -gt 0) {
    Write-Host "  Safety probes: $safetyProbePath"
}
Write-Host "Swipe API load test complete."

if ($script:ScenarioUnexpectedFailureCount -gt 0) {
    throw "$($script:ScenarioUnexpectedFailureCount) unexpected scenario failure(s). Artifacts were written to $artifactDir"
}

if ($FailOnBudget -and $script:ScenarioBudgetFailed) {
    throw "One or more scenarios exceeded the configured p95 budget. Artifacts were written to $artifactDir"
}
