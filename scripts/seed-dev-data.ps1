param(
    [string]$BaseUrl = "http://localhost:8080",
    [string]$Password = "PynapliDevPass123!",
    [switch]$SetAndroidEmulatorLocation,
    # Which emulator -SetAndroidEmulatorLocation targets. Required once more than one is running.
    [string]$DeviceId = ""
)

$ErrorActionPreference = "Stop"

# Accounts come from the local-only dev sign-up route, which is off by default: fail fast with the
# instruction if the server wasn't started with PYNAPLI_DEV_SIGNUP_SHORTCUTS_ENABLED=true.
Import-Module (Join-Path $PSScriptRoot "lib\PynapliDevSignup.psm1") -Force -DisableNameChecking
Assert-DevSignupShortcuts -BaseUrl $BaseUrl

$viewerEmail = "dev@pynapli.local"
$viewerLatitude = 37.4220
$viewerLongitude = -122.0841

Add-Type -AssemblyName System.Drawing

function Invoke-PynapliJson {
    param(
        [ValidateSet("GET", "POST", "PATCH")]
        [string]$Method,
        [string]$Path,
        [object]$Body = $null,
        [string]$AccessToken = $null
    )

    $headers = @{
        Accept = "application/json"
    }
    if (-not [string]::IsNullOrWhiteSpace($AccessToken)) {
        $headers.Authorization = "Bearer $AccessToken"
    }

    $parameters = @{
        Method      = $Method
        Uri         = "$BaseUrl$Path"
        Headers     = $headers
        ErrorAction = "Stop"
    }

    if ($null -ne $Body) {
        $parameters.ContentType = "application/json"
        $parameters.Body = $Body | ConvertTo-Json -Depth 10
    }

    try {
        return @{
            StatusCode = 200
            Body = Invoke-RestMethod @parameters
        }
    } catch {
        $statusCode = 0
        $content = $_.ErrorDetails.Message

        if ($_.Exception.Response) {
            $statusCode = [int]$_.Exception.Response.StatusCode
            if ([string]::IsNullOrWhiteSpace($content)) {
                try {
                    $stream = $_.Exception.Response.GetResponseStream()
                    if ($stream) {
                        $reader = [System.IO.StreamReader]::new($stream)
                        $content = $reader.ReadToEnd()
                    }
                } catch {
                    $content = $_.Exception.Message
                }
            }
        }

        $parsedBody = $null
        if (-not [string]::IsNullOrWhiteSpace($content)) {
            try {
                $parsedBody = $content | ConvertFrom-Json
            } catch {
                $parsedBody = $content
            }
        }

        return @{
            StatusCode = $statusCode
            Body = $parsedBody
            RawError = $content
        }
    }
}

function Assert-Success {
    param(
        [hashtable]$Response,
        [string]$Action
    )

    if ($Response.StatusCode -lt 200 -or $Response.StatusCode -ge 300) {
        $details = $Response.RawError
        if ([string]::IsNullOrWhiteSpace($details) -and $null -ne $Response.Body) {
            $details = $Response.Body | ConvertTo-Json -Depth 10
        }
        throw "$Action failed with HTTP $($Response.StatusCode): $details"
    }
}

function Convert-HexColor {
    param(
        [string]$Hex
    )

    return [System.Drawing.ColorTranslator]::FromHtml($Hex)
}

function New-DevProfileImageBytes {
    param(
        [string]$DisplayName,
        [int]$Index
    )

    $width = 900
    $height = 1200
    $palettes = @(
        @{ Start = "#4E3A31"; End = "#111111"; Glow = "#D1A64F"; Ink = "#F8EFE2" },
        @{ Start = "#263A40"; End = "#111314"; Glow = "#78A2B0"; Ink = "#F0F7F8" },
        @{ Start = "#543126"; End = "#130F0D"; Glow = "#D96D52"; Ink = "#FFF0E8" },
        @{ Start = "#2F3D2E"; End = "#101310"; Glow = "#7FA56A"; Ink = "#EEF6E9" },
        @{ Start = "#4A3522"; End = "#15100B"; Glow = "#B78349"; Ink = "#FFF1DD" },
        @{ Start = "#31363D"; End = "#101113"; Glow = "#9BA6B7"; Ink = "#F4F6FA" },
        @{ Start = "#243C3B"; End = "#0C1212"; Glow = "#6FB8AD"; Ink = "#EAF9F7" },
        @{ Start = "#4E2F3A"; End = "#120D10"; Glow = "#C07890"; Ink = "#FFF0F5" },
        @{ Start = "#4B4037"; End = "#12100E"; Glow = "#BFA084"; Ink = "#FFF3E8" }
    )

    $palette = $palettes[$Index % $palettes.Count]
    $bitmap = [System.Drawing.Bitmap]::new($width, $height)
    $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
    $stream = [System.IO.MemoryStream]::new()

    try {
        $graphics.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
        $graphics.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
        $graphics.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality

        $rect = [System.Drawing.Rectangle]::new(0, 0, $width, $height)
        $background = [System.Drawing.Drawing2D.LinearGradientBrush]::new(
            $rect,
            (Convert-HexColor $palette.Start),
            (Convert-HexColor $palette.End),
            55.0
        )
        $graphics.FillRectangle($background, $rect)
        $background.Dispose()

        $glowColor = Convert-HexColor $palette.Glow
        $softGlow = [System.Drawing.SolidBrush]::new(
            [System.Drawing.Color]::FromArgb(56, $glowColor.R, $glowColor.G, $glowColor.B)
        )
        $graphics.FillEllipse($softGlow, -140, 120, 760, 760)
        $graphics.FillEllipse($softGlow, 520, -160, 520, 520)
        $graphics.FillEllipse($softGlow, 260, 740, 780, 620)
        $softGlow.Dispose()

        $portraitShadow = [System.Drawing.SolidBrush]::new([System.Drawing.Color]::FromArgb(120, 8, 8, 8))
        $graphics.FillEllipse($portraitShadow, 170, 248, 560, 560)
        $graphics.FillEllipse($portraitShadow, 70, 682, 760, 420)
        $portraitShadow.Dispose()

        $portraitFill = [System.Drawing.SolidBrush]::new(
            [System.Drawing.Color]::FromArgb(92, $glowColor.R, $glowColor.G, $glowColor.B)
        )
        $graphics.FillEllipse($portraitFill, 220, 298, 460, 460)
        $graphics.FillEllipse($portraitFill, 120, 720, 660, 340)
        $portraitFill.Dispose()

        $initial = if ([string]::IsNullOrWhiteSpace($DisplayName)) { "P" } else { $DisplayName.Trim().Substring(0, 1).ToUpperInvariant() }
        $font = [System.Drawing.Font]::new(
            [System.Drawing.FontFamily]::GenericSansSerif,
            176,
            [System.Drawing.FontStyle]::Bold,
            [System.Drawing.GraphicsUnit]::Pixel
        )
        $textBrush = [System.Drawing.SolidBrush]::new((Convert-HexColor $palette.Ink))
        $format = [System.Drawing.StringFormat]::new()
        $format.Alignment = [System.Drawing.StringAlignment]::Center
        $format.LineAlignment = [System.Drawing.StringAlignment]::Center
        $graphics.DrawString($initial, $font, $textBrush, [System.Drawing.RectangleF]::new(0, 410, $width, 220), $format)

        $format.Dispose()
        $textBrush.Dispose()
        $font.Dispose()

        $bitmap.Save($stream, [System.Drawing.Imaging.ImageFormat]::Png)
        return $stream.ToArray()
    } finally {
        $stream.Dispose()
        $graphics.Dispose()
        $bitmap.Dispose()
    }
}

function Invoke-PynapliUploadBytes {
    param(
        [string]$Uri,
        [byte[]]$Bytes,
        [string]$ContentType
    )

    try {
        Invoke-WebRequest `
            -Method PUT `
            -Uri $Uri `
            -ContentType $ContentType `
            -Body $Bytes `
            -ErrorAction Stop | Out-Null
        return @{
            StatusCode = 200
        }
    } catch {
        $statusCode = 0
        $content = $_.ErrorDetails.Message
        if ($_.Exception.Response) {
            $statusCode = [int]$_.Exception.Response.StatusCode
        }
        return @{
            StatusCode = $statusCode
            RawError = $content
        }
    }
}

# Unique per run: the server rate-limits signups/logins per device subject, so a
# fixed device id would make repeated seed/QA runs trip 429s on shared counters.
$script:SeedDeviceId = "local-dev-seed-$([guid]::NewGuid().ToString('N').Substring(0, 8))"

# Accounts come from the local-only dev sign-up route: the public /v1/auth/email/signup now only
# emails a code (ONBOARDING_AUTH_SPEC, "Membership privacy"). The dev route creates a verified
# account at once and answers 409 for an existing one; it exists only on a PYNAPLI_ENV=local server
# with the "log" mailer and PYNAPLI_DEV_SIGNUP_SHORTCUTS_ENABLED=true, for direct loopback requests.
function Get-DevSession {
    param(
        [string]$Email
    )

    $signup = Invoke-PynapliJson `
        -Method POST `
        -Path "/v1/dev/auth/email/signup" `
        -Body @{
            email = $Email
            password = $Password
            birthdate = "1990-01-01"
            confirmedAdult = $true
            acceptedTerms = $true
            acceptedPrivacy = $true
            deviceId = $script:SeedDeviceId
            platform = "seed"
            appVersion = "0.1.0"
        }

    if ($signup.StatusCode -ge 200 -and $signup.StatusCode -lt 300) {
        return $signup.Body
    }

    # 409 = account already seeded. (429 is kept for an older server whose public sign-up was
    # rate-limited; the dev route has no sign-up limit.) Both fall through to login.
    if ($signup.StatusCode -ne 409 -and $signup.StatusCode -ne 429) {
        Assert-Success -Response $signup -Action "Signup $Email"
    }

    $login = Invoke-PynapliJson `
        -Method POST `
        -Path "/v1/auth/email/login" `
        -Body @{
            email = $Email
            password = $Password
            deviceId = $script:SeedDeviceId
            platform = "seed"
            appVersion = "0.1.0"
        }
    Assert-Success -Response $login -Action "Login $Email"
    return $login.Body
}

function Update-DevProfile {
    param(
        [object]$Session,
        [hashtable]$Account
    )

    $profile = Invoke-PynapliJson `
        -Method PATCH `
        -Path "/v1/me/profile" `
        -AccessToken $Session.accessToken `
        -Body @{
            displayName = $Account.displayName
            birthdate = $Account.birthdate
            gender = $Account.gender
            orientation = $Account.orientation
            relationshipContext = $Account.relationshipContext
            discoveryStyle = $Account.discoveryStyle
        }
    Assert-Success -Response $profile -Action "Profile update $($Account.email)"
}

function Update-DevLocation {
    param(
        [object]$Session,
        [hashtable]$Account
    )

    $location = Invoke-PynapliJson `
        -Method POST `
        -Path "/v1/location/current" `
        -AccessToken $Session.accessToken `
        -Body @{
            latitude = $Account.latitude
            longitude = $Account.longitude
            accuracyMeters = 18
            source = "gps"
            city = $Account.city
            region = $Account.region
            country = $Account.country
        }
    Assert-Success -Response $location -Action "Location update $($Account.email)"
}

function Refresh-DevPresence {
    param(
        [object]$Session,
        [string]$Email
    )

    # A Grid query marks the caller online (the server refreshes the viewer's presence on every
    # query); there is no separate heartbeat endpoint.
    $presence = Invoke-PynapliJson `
        -Method POST `
        -Path "/v1/grid/query" `
        -AccessToken $Session.accessToken `
        -Body @{ radiusMeters = 100000; limit = 1 }
    Assert-Success -Response $presence -Action "Presence refresh $Email"
}

function Seed-Account {
    param(
        [hashtable]$Account,
        [bool]$Online = $false
    )

    $session = Get-DevSession -Email $Account.email
    Update-DevProfile -Session $session -Account $Account
    Update-DevLocation -Session $session -Account $Account
    if ($Online) {
        Refresh-DevPresence -Session $session -Email $Account.email
    }
    return $session
}

function Test-DevProfilePhotoReady {
    param(
        [object]$Session
    )

    $album = Invoke-PynapliJson `
        -Method GET `
        -Path "/v1/media/albums/profile" `
        -AccessToken $Session.accessToken

    if ($album.StatusCode -lt 200 -or $album.StatusCode -ge 300) {
        return $false
    }

    foreach ($item in @($album.Body.items)) {
        $asset = $item.mediaAsset
        if ($null -eq $asset) {
            continue
        }
        if ($asset.processingStatus -ne "ready" -or $asset.moderationStatus -ne "approved") {
            continue
        }

        try {
            Invoke-WebRequest `
                -Method GET `
                -Uri "$BaseUrl/v1/media/uploads/local/$($asset.mediaAssetId)" `
                -ErrorAction Stop | Out-Null
            return $true
        } catch {
            continue
        }
    }

    return $false
}

function Set-DevProfilePhoto {
    param(
        [object]$Session,
        [hashtable]$Account,
        [int]$Index
    )

    if (Test-DevProfilePhotoReady -Session $Session) {
        Write-Host "  [media] Existing profile photo for $($Account.displayName)"
        return
    }

    $contentType = "image/png"
    $bytes = New-DevProfileImageBytes -DisplayName $Account.displayName -Index $Index
    $uploadSlot = Invoke-PynapliJson `
        -Method POST `
        -Path "/v1/media/uploads" `
        -AccessToken $Session.accessToken `
        -Body @{
            albumKind = "profile"
            mediaType = "image"
            contentType = $contentType
            byteSize = $bytes.LongLength
            pixelWidth = 900
            pixelHeight = 1200
        }
    Assert-Success -Response $uploadSlot -Action "Create profile photo upload $($Account.email)"

    $uploadUrl = $uploadSlot.Body.uploadUrl
    try {
        $uploadUri = [System.Uri]$uploadUrl
        $uploadUrl = "$BaseUrl$($uploadUri.PathAndQuery)"
    } catch {
        $uploadUrl = $uploadSlot.Body.uploadUrl
    }

    $upload = Invoke-PynapliUploadBytes `
        -Uri $uploadUrl `
        -Bytes $bytes `
        -ContentType $contentType
    Assert-Success -Response $upload -Action "Upload profile photo $($Account.email)"

    $assetId = $uploadSlot.Body.mediaAsset.mediaAssetId
    $complete = Invoke-PynapliJson `
        -Method POST `
        -Path "/v1/media/uploads/$assetId/complete" `
        -AccessToken $Session.accessToken `
        -Body @{
            albumId = $uploadSlot.Body.album.albumId
            sortOrder = 0
        }
    Assert-Success -Response $complete -Action "Complete profile photo upload $($Account.email)"
    Write-Host "  [media] Profile photo for $($Account.displayName)"
}

function Set-LinkedDevAccounts {
    param(
        [hashtable]$SessionsByEmail
    )

    $docker = Get-Command docker -ErrorAction SilentlyContinue
    if ($null -eq $docker) {
        Write-Host "  [skip] Docker not found; linked dev accounts were not seeded."
        return
    }

    $links = @(
        @{
            id = "00000000-0000-0000-0000-000000000201"
            userId = $SessionsByEmail["maya@pynapli.local"].user.userId
            linkedUserId = $SessionsByEmail["alex@pynapli.local"].user.userId
        },
        @{
            id = "00000000-0000-0000-0000-000000000202"
            userId = $SessionsByEmail["alex@pynapli.local"].user.userId
            linkedUserId = $SessionsByEmail["maya@pynapli.local"].user.userId
        },
        @{
            id = "00000000-0000-0000-0000-000000000203"
            userId = $SessionsByEmail["jules@pynapli.local"].user.userId
            linkedUserId = $SessionsByEmail["rae@pynapli.local"].user.userId
        },
        @{
            id = "00000000-0000-0000-0000-000000000204"
            userId = $SessionsByEmail["rae@pynapli.local"].user.userId
            linkedUserId = $SessionsByEmail["jules@pynapli.local"].user.userId
        }
    )

    $values = ($links | ForEach-Object {
        "('$($_.id)'::uuid, '$($_.userId)'::uuid, '$($_.linkedUserId)'::uuid, 'active', now(), NULL)"
    }) -join ",`n"

    $sql = @"
INSERT INTO user_profile_links (id, user_id, linked_user_id, status, accepted_at, unlinked_at)
VALUES
$values
ON CONFLICT (user_id, linked_user_id) DO UPDATE
SET status = EXCLUDED.status,
    accepted_at = COALESCE(user_profile_links.accepted_at, EXCLUDED.accepted_at),
    unlinked_at = NULL,
    updated_at = now();
"@

    $output = & $docker.Source exec pynapli-postgres psql -U pynapli -d $(if ($env:PYNAPLI_DATABASE_NAME) { $env:PYNAPLI_DATABASE_NAME } else { 'pynapli_dev' }) -v ON_ERROR_STOP=1 -c $sql 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "Linked account seed failed: $output"
    }

    Write-Host "  [ok] Linked dev couples: Maya + Alex, Jules + Rae"
}

function Set-DevMonetization {
    param(
        [hashtable]$SessionsByEmail
    )

    $docker = Get-Command docker -ErrorAction SilentlyContinue
    if ($null -eq $docker) {
        Write-Host "  [skip] Docker not found; dev subscription inventory was not seeded."
        return
    }

    $viewerUserId = $SessionsByEmail[$viewerEmail].user.userId
    $mayaUserId = $SessionsByEmail["maya@pynapli.local"].user.userId
    $alexUserId = $SessionsByEmail["alex@pynapli.local"].user.userId

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
    ('00000000-0000-0000-0000-000000000301'::uuid, '$viewerUserId'::uuid, 'dev', 'dev-select-viewer', 'select', 'active', now() - interval '1 day', now() + interval '30 days'),
    ('00000000-0000-0000-0000-000000000302'::uuid, '$mayaUserId'::uuid, 'dev', 'dev-social-maya', 'social', 'active', now() - interval '1 day', now() + interval '30 days')
ON CONFLICT (platform, external_subscription_id) DO UPDATE
SET user_id = EXCLUDED.user_id,
    tier = EXCLUDED.tier,
    status = EXCLUDED.status,
    current_period_start = EXCLUDED.current_period_start,
    current_period_end = EXCLUDED.current_period_end,
    updated_at = now();

INSERT INTO premium_inventory (
    id,
    user_id,
    inventory_type,
    quantity,
    source,
    expires_at
)
VALUES
    ('00000000-0000-0000-0000-000000000311'::uuid, '$viewerUserId'::uuid, 'pineapple_like', 5, 'dev', now() + interval '30 days'),
    ('00000000-0000-0000-0000-000000000312'::uuid, '$mayaUserId'::uuid, 'pineapple_like', 2, 'dev', now() + interval '30 days'),
    ('00000000-0000-0000-0000-000000000313'::uuid, '$alexUserId'::uuid, 'pineapple_like', 2, 'dev', now() + interval '30 days')
ON CONFLICT (id) DO UPDATE
SET user_id = EXCLUDED.user_id,
    quantity = EXCLUDED.quantity,
    source = EXCLUDED.source,
    expires_at = EXCLUDED.expires_at,
    updated_at = now();
"@

    $output = & $docker.Source exec pynapli-postgres psql -U pynapli -d $(if ($env:PYNAPLI_DATABASE_NAME) { $env:PYNAPLI_DATABASE_NAME } else { 'pynapli_dev' }) -v ON_ERROR_STOP=1 -c $sql 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "Dev monetization seed failed: $output"
    }

    Write-Host "  [ok] Dev subscriptions: Seb Dev Select, Maya Social, Alex linked benefit via Maya"
}

function Set-DevTonightSessions {
    param(
        [hashtable]$SessionsByEmail
    )

    # Seed a few live Playing Tonight sessions so the viewer's feed + zone map show
    # populated rows/dots. SOLO sessions go active immediately; a "together" session
    # would stay 'proposed' until the partner approves (so it would be invisible),
    # which is why these are all solo. Re-runs are idempotent: an existing live session
    # returns TONIGHT_ALREADY_ACTIVE (409), which we treat as a skip.
    $tonight = @(
        @{ email = "maya@pynapli.local"; intent = "social"; hostMode = "hosting";           note = "At the lounge — say hi" },
        @{ email = "kai@pynapli.local";  intent = "social"; hostMode = "public_meet_first"; note = "Up for a drink first" },
        @{ email = "theo@pynapli.local"; intent = "event";  hostMode = "traveling";         note = "Heading to the night market" },
        @{ email = "nico@pynapli.local"; intent = "solo";   hostMode = "public_meet_first"; note = $null }
    )

    foreach ($entry in $tonight) {
        $session = $SessionsByEmail[$entry.email]
        if ($null -eq $session) {
            continue
        }

        $body = @{
            mode = "solo"
            intent = $entry.intent
            hostMode = $entry.hostMode
            durationHours = 4
            mapVisible = $true
        }
        if ($null -ne $entry.note) {
            $body.note = $entry.note
        }

        $response = Invoke-PynapliJson `
            -Method POST `
            -Path "/v1/tonight/sessions" `
            -AccessToken $session.accessToken `
            -Body $body

        if ($response.StatusCode -ge 200 -and $response.StatusCode -lt 300) {
            Write-Host "  [ok] Playing Tonight session for $($entry.email) ($($entry.intent))"
        } elseif ($response.StatusCode -eq 409) {
            Write-Host "  [skip] Playing Tonight session for $($entry.email): already live"
        } else {
            Write-Host "  [skip] Playing Tonight session for $($entry.email): HTTP $($response.StatusCode)"
        }
    }
}

function Set-DevActivityFeed {
    param(
        [hashtable]$SessionsByEmail
    )

    # Populate the viewer's Activity feed + a no-message swipe match so the restructured Activity
    # surface is demoable on first login: Views/Flirts render as 2-up FOMO tiles, Likes shows a
    # like, and Messages shows the Tinder-style New Matches rail (a swipe conversation with no
    # messages yet). Re-runs are idempotent and reset seen state so the "New" pills reappear.
    $docker = Get-Command docker -ErrorAction SilentlyContinue
    if ($null -eq $docker) {
        Write-Host "  [skip] Docker not found; dev Activity feed was not seeded."
        return
    }

    $viewerId = $SessionsByEmail[$viewerEmail].user.userId
    $mayaId = $SessionsByEmail["maya@pynapli.local"].user.userId
    $kaiId = $SessionsByEmail["kai@pynapli.local"].user.userId
    $theoId = $SessionsByEmail["theo@pynapli.local"].user.userId
    $nicoId = $SessionsByEmail["nico@pynapli.local"].user.userId
    $julesId = $SessionsByEmail["jules@pynapli.local"].user.userId
    $noaId = $SessionsByEmail["noa@pynapli.local"].user.userId

    $sql = @"
-- The Views tab gates profile_view rows on mutual viewed-list opt-in, so make the viewer and the
-- view actors discoverable in viewed lists (non-incognito) — otherwise the seeded views are hidden.
UPDATE discovery_settings
SET show_in_viewed_lists = true, incognito = false, updated_at = now()
WHERE user_id IN ('$viewerId'::uuid, '$mayaId'::uuid, '$kaiId'::uuid);

-- Clear any prior dev/QA-seeded Activity for the viewer so the aggregate unique indexes
-- (one profile_view/flirt row per actor) don't collide on re-run.
DELETE FROM activity_events WHERE recipient_user_id = '$viewerId'::uuid;

INSERT INTO activity_events (
    id, recipient_user_id, actor_user_id, event_type, target_user_id, aggregate_count,
    first_occurred_at, last_counted_at, occurred_at, updated_at, seen_at, metadata
)
VALUES
    ('00000000-0000-0000-0000-000000009541'::uuid, '$viewerId'::uuid, '$mayaId'::uuid, 'profile_view', '$viewerId'::uuid, 3, now() - interval '42 minutes', now() - interval '12 minutes', now() - interval '12 minutes', now(), NULL, '{}'::jsonb),
    ('00000000-0000-0000-0000-000000009542'::uuid, '$viewerId'::uuid, '$kaiId'::uuid, 'profile_view', '$viewerId'::uuid, 1, now() - interval '35 minutes', now() - interval '35 minutes', now() - interval '35 minutes', now(), NULL, '{}'::jsonb),
    ('00000000-0000-0000-0000-000000009543'::uuid, '$viewerId'::uuid, '$theoId'::uuid, 'flirt', '$viewerId'::uuid, 1, now() - interval '28 minutes', now() - interval '28 minutes', now() - interval '28 minutes', now(), NULL, '{}'::jsonb),
    ('00000000-0000-0000-0000-000000009544'::uuid, '$viewerId'::uuid, '$nicoId'::uuid, 'flirt', '$viewerId'::uuid, 1, now() - interval '22 minutes', now() - interval '22 minutes', now() - interval '22 minutes', now(), NULL, '{}'::jsonb),
    ('00000000-0000-0000-0000-000000009545'::uuid, '$viewerId'::uuid, '$julesId'::uuid, 'swipe_like', '$viewerId'::uuid, 1, now() - interval '16 minutes', now() - interval '16 minutes', now() - interval '16 minutes', now(), NULL, '{}'::jsonb)
ON CONFLICT (id) DO UPDATE
SET occurred_at = EXCLUDED.occurred_at,
    last_counted_at = EXCLUDED.last_counted_at,
    aggregate_count = EXCLUDED.aggregate_count,
    seen_at = NULL,
    deleted_at = NULL,
    updated_at = now();

INSERT INTO conversations (id, kind, origin, created_by_user_id, created_at, updated_at)
VALUES ('00000000-0000-0000-0000-0000000095a1'::uuid, 'direct', 'swipe', '$viewerId'::uuid, now() - interval '9 minutes', now() - interval '9 minutes')
ON CONFLICT (id) DO NOTHING;

INSERT INTO direct_conversations (conversation_id, user_a_id, user_b_id, created_at)
VALUES ('00000000-0000-0000-0000-0000000095a1'::uuid, LEAST('$viewerId'::uuid, '$noaId'::uuid), GREATEST('$viewerId'::uuid, '$noaId'::uuid), now() - interval '9 minutes')
ON CONFLICT (conversation_id) DO NOTHING;

INSERT INTO conversation_participants (
    conversation_id, user_id, role, joined_at, left_at, last_read_at,
    invitation_status, invited_by_user_id, invited_at, invitation_responded_at
)
VALUES
    ('00000000-0000-0000-0000-0000000095a1'::uuid, '$viewerId'::uuid, 'member', now() - interval '9 minutes', NULL, now() - interval '9 minutes', 'active', NULL, NULL, NULL),
    ('00000000-0000-0000-0000-0000000095a1'::uuid, '$noaId'::uuid, 'member', now() - interval '9 minutes', NULL, now() - interval '9 minutes', 'active', NULL, NULL, NULL)
ON CONFLICT (conversation_id, user_id) DO NOTHING;

-- The server decides what is a new match from the matches row (MESSAGING_SPEC "New-Matches Rail"),
-- so the rail needs a real match, and a re-run clears any "seen" state left by earlier QA.
INSERT INTO matches (id, user_a, user_b, created_at)
VALUES ('00000000-0000-0000-0000-0000000095a2'::uuid, LEAST('$viewerId'::uuid, '$noaId'::uuid), GREATEST('$viewerId'::uuid, '$noaId'::uuid), now() - interval '9 minutes')
ON CONFLICT (user_a, user_b) DO NOTHING;

DELETE FROM match_inbox_acknowledgements
WHERE match_id IN (
    SELECT id FROM matches
    WHERE user_a = LEAST('$viewerId'::uuid, '$noaId'::uuid) AND user_b = GREATEST('$viewerId'::uuid, '$noaId'::uuid)
);
"@

    $output = & $docker.Source exec pynapli-postgres psql -U pynapli -d $(if ($env:PYNAPLI_DATABASE_NAME) { $env:PYNAPLI_DATABASE_NAME } else { 'pynapli_dev' }) -v ON_ERROR_STOP=1 -c $sql 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "Dev Activity feed seed failed: $output"
    }

    Write-Host "  [ok] Dev Activity feed: 2 views, 2 flirts, 1 like, + a no-message swipe match (Noa) for the Messages rail"
}

$accounts = @(
    @{
        email = $viewerEmail
        displayName = "Seb Dev"
        birthdate = "1988-06-14"
        gender = "man"
        orientation = "pansexual"
        relationshipContext = "single"
        discoveryStyle = "social"
        latitude = $viewerLatitude
        longitude = $viewerLongitude
        city = "Mountain View"
        region = "CA"
        country = "US"
    },
    @{
        email = "maya@pynapli.local"
        displayName = "Maya"
        birthdate = "1991-02-11"
        gender = "woman"
        orientation = "bisexual"
        relationshipContext = "linked"
        discoveryStyle = "both"
        latitude = 37.4226
        longitude = -122.0836
        city = "Mountain View"
        region = "CA"
        country = "US"
    },
    @{
        email = "alex@pynapli.local"
        displayName = "Alex"
        birthdate = "1986-09-23"
        gender = "man"
        orientation = "pansexual"
        relationshipContext = "linked"
        discoveryStyle = "social"
        latitude = 37.4235
        longitude = -122.0851
        city = "Mountain View"
        region = "CA"
        country = "US"
    },
    @{
        email = "nico@pynapli.local"
        displayName = "Nico"
        birthdate = "1993-05-19"
        gender = "non_binary"
        orientation = "queer"
        relationshipContext = "single"
        discoveryStyle = "solo"
        latitude = 37.4244
        longitude = -122.0828
        city = "Mountain View"
        region = "CA"
        country = "US"
    },
    @{
        email = "jules@pynapli.local"
        displayName = "Jules"
        birthdate = "1989-12-04"
        gender = "woman"
        orientation = "pansexual"
        relationshipContext = "linked"
        discoveryStyle = "together"
        latitude = 37.4208
        longitude = -122.0865
        city = "Mountain View"
        region = "CA"
        country = "US"
    },
    @{
        email = "kai@pynapli.local"
        displayName = "Kai"
        birthdate = "1990-07-30"
        gender = "man"
        orientation = "bisexual"
        relationshipContext = "single"
        discoveryStyle = "social"
        latitude = 37.4196
        longitude = -122.0819
        city = "Mountain View"
        region = "CA"
        country = "US"
    },
    @{
        email = "rae@pynapli.local"
        displayName = "Rae"
        birthdate = "1992-03-08"
        gender = "woman"
        orientation = "queer"
        relationshipContext = "linked"
        discoveryStyle = "both"
        latitude = 37.4252
        longitude = -122.0872
        city = "Mountain View"
        region = "CA"
        country = "US"
    },
    @{
        email = "theo@pynapli.local"
        displayName = "Theo"
        birthdate = "1985-11-17"
        gender = "man"
        orientation = "pansexual"
        relationshipContext = "single"
        discoveryStyle = "ask_in_chat"
        latitude = 37.4265
        longitude = -122.0804
        city = "Mountain View"
        region = "CA"
        country = "US"
    },
    @{
        email = "noa@pynapli.local"
        displayName = "Noa"
        birthdate = "1994-01-26"
        gender = "non_binary"
        orientation = "pansexual"
        relationshipContext = "linked"
        discoveryStyle = "social"
        latitude = 37.4183
        longitude = -122.0880
        city = "Mountain View"
        region = "CA"
        country = "US"
    }
)

$health = Invoke-PynapliJson -Method GET -Path "/health"
Assert-Success -Response $health -Action "Health check"

Write-Host "Seeding Pynapli dev accounts at $BaseUrl..."
$viewerSession = $null
$sessionsByEmail = @{}
for ($index = 0; $index -lt $accounts.Count; $index++) {
    $account = $accounts[$index]
    $session = Seed-Account -Account $account -Online ($index -lt 5)
    Set-DevProfilePhoto -Session $session -Account $account -Index $index
    $sessionsByEmail[$account.email] = $session
    if ($account.email -eq $viewerEmail) {
        $viewerSession = $session
    }
    Write-Host "  [ok] $($account.displayName) <$($account.email)>"
}

Set-LinkedDevAccounts -SessionsByEmail $sessionsByEmail
Set-DevMonetization -SessionsByEmail $sessionsByEmail
Set-DevTonightSessions -SessionsByEmail $sessionsByEmail
Set-DevActivityFeed -SessionsByEmail $sessionsByEmail

$grid = Invoke-PynapliJson `
    -Method POST `
    -Path "/v1/grid/query" `
    -AccessToken $viewerSession.accessToken `
    -Body @{
        radiusMeters = 100000
        limit = 60
    }
Assert-Success -Response $grid -Action "Viewer grid query"

if ($SetAndroidEmulatorLocation) {
    $adb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
    if (Test-Path $adb) {
        # -s matters: with two AVDs running (android-36 + android-34, see the QA matrix) a bare
        # `adb emu geo fix` dies with "more than one emulator detected" and the viewer keeps whatever
        # location it had. Every distance-dependent assertion downstream — grid distances, the Linked
        # deck — then fails for a reason that looks nothing like the real cause.
        $geoArgs = @()
        if (-not [string]::IsNullOrWhiteSpace($DeviceId)) { $geoArgs += @("-s", $DeviceId) }
        $geoArgs += @("emu", "geo", "fix", $viewerLongitude, $viewerLatitude)
        $geoOutput = (& $adb @geoArgs 2>&1 | Out-String).Trim()

        # This used to print [ok] unconditionally, so a failed geo fix reported success and the
        # broken run was blamed on the app. Trust the exit code, and adb's habit of printing
        # "error:" while still exiting 0.
        if ($LASTEXITCODE -ne 0 -or $geoOutput -match "error:") {
            throw "Failed to set the Android emulator location$(if ($DeviceId) { " on $DeviceId" }). " +
                  "adb said: $geoOutput`n" +
                  "If several emulators are running, pass -DeviceId (scripts\android-qa-matrix.ps1 does this for you)."
        }
        Write-Host "  [ok] Android emulator location set near seeded profiles$(if ($DeviceId) { " ($DeviceId)" })."
    } else {
        Write-Host "  [skip] Android SDK adb was not found."
    }
}

Write-Host ""
Write-Host "Dev seed complete."
Write-Host "Email:    $viewerEmail"
Write-Host "Password: $Password"
Write-Host "Grid cards available for viewer: $($grid.Body.items.Count)"
