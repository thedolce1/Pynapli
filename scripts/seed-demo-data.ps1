# Seeds a rich two-city demo world for partner/founder deep-dive builds:
#   - viewer accounts: seb@pynapli.local (Toronto/Weston) + partner@pynapli.local (Victoria Harbour)
#   - N populated fake profiles per city (photos, bios, tags, presence, linked couples)
#   - live Activity events, real message threads, Playing Tonight sessions, dev subscriptions
#
# Everything goes through the public API except the pieces the API deliberately refuses to
# fabricate (activity events, swipe matches, partner links, dev subscriptions), which reuse the
# proven docker-psql patterns from seed-dev-data.ps1. Re-runs are idempotent.
#
# This script is for LOCAL/DEMO servers only. Never point it at production.
param(
    [string]$BaseUrl = "http://localhost:8080",
    [string]$Password = "PynapliDevPass123!",
    [int]$PerCity = 50,
    # Presence lives in Redis with a short TTL, so online dots fade between demo sessions.
    # -RefreshPresence re-heartbeats the online subset (plus viewers) without reseeding.
    [switch]$RefreshPresence
)

$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.Drawing

# Deterministic world: same names/positions/attributes on every run.
$script:Rng = [System.Random]::new(20260713)
# Signups/logins are rate-limited per device subject (AUTH_SIGNUP: 10/hour), so every demo
# account gets its own device id — as a real user would — instead of sharing one.
$script:RunId = [guid]::NewGuid().ToString('N').Substring(0, 8)

# ---------------------------------------------------------------------------
# HTTP + auth helpers (same recipes as seed-dev-data.ps1)
# ---------------------------------------------------------------------------

function Invoke-PynapliJson {
    param(
        [ValidateSet("GET", "POST", "PATCH", "PUT")]
        [string]$Method,
        [string]$Path,
        [object]$Body = $null,
        [string]$AccessToken = $null
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
    }
    if ($null -ne $Body) {
        $parameters.ContentType = "application/json"
        $parameters.Body = $Body | ConvertTo-Json -Depth 10
    }

    try {
        return @{ StatusCode = 200; Body = Invoke-RestMethod @parameters }
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
            try { $parsedBody = $content | ConvertFrom-Json } catch { $parsedBody = $content }
        }
        return @{ StatusCode = $statusCode; Body = $parsedBody; RawError = $content }
    }
}

function Assert-Success {
    param([hashtable]$Response, [string]$Action)
    if ($Response.StatusCode -lt 200 -or $Response.StatusCode -ge 300) {
        $details = $Response.RawError
        if ([string]::IsNullOrWhiteSpace($details) -and $null -ne $Response.Body) {
            $details = $Response.Body | ConvertTo-Json -Depth 10
        }
        throw "$Action failed with HTTP $($Response.StatusCode): $details"
    }
}

function Get-DemoSession {
    param([string]$Email)

    $deviceId = "demo-$($Email.Split('@')[0])-$script:RunId"
    $signup = Invoke-PynapliJson -Method POST -Path "/v1/auth/email/signup" -Body @{
        email = $Email
        password = $Password
        birthdate = "1990-01-01"
        confirmedAdult = $true
        acceptedTerms = $true
        acceptedPrivacy = $true
        deviceId = $deviceId
        platform = "seed"
        appVersion = "0.1.0"
    }
    if ($signup.StatusCode -ge 200 -and $signup.StatusCode -lt 300) {
        return $signup.Body
    }
    # 409 = the account exists from an earlier run; anything else (incl. 429) is a real failure
    # now that each account uses its own device id.
    if ($signup.StatusCode -ne 409) {
        Assert-Success -Response $signup -Action "Signup $Email"
    }

    $login = Invoke-PynapliJson -Method POST -Path "/v1/auth/email/login" -Body @{
        email = $Email
        password = $Password
        deviceId = $deviceId
        platform = "seed"
        appVersion = "0.1.0"
    }
    Assert-Success -Response $login -Action "Login $Email"
    return $login.Body
}

# ---------------------------------------------------------------------------
# Placeholder portrait generation + upload (same visual recipe as seed-dev-data.ps1)
# ---------------------------------------------------------------------------

function Convert-HexColor { param([string]$Hex) return [System.Drawing.ColorTranslator]::FromHtml($Hex) }

function New-DemoProfileImageBytes {
    param([string]$DisplayName, [int]$Index)

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
            $rect, (Convert-HexColor $palette.Start), (Convert-HexColor $palette.End), 55.0)
        $graphics.FillRectangle($background, $rect)
        $background.Dispose()

        $glowColor = Convert-HexColor $palette.Glow
        $softGlow = [System.Drawing.SolidBrush]::new([System.Drawing.Color]::FromArgb(56, $glowColor.R, $glowColor.G, $glowColor.B))
        $graphics.FillEllipse($softGlow, -140, 120, 760, 760)
        $graphics.FillEllipse($softGlow, 520, -160, 520, 520)
        $graphics.FillEllipse($softGlow, 260, 740, 780, 620)
        $softGlow.Dispose()

        $portraitShadow = [System.Drawing.SolidBrush]::new([System.Drawing.Color]::FromArgb(120, 8, 8, 8))
        $graphics.FillEllipse($portraitShadow, 170, 248, 560, 560)
        $graphics.FillEllipse($portraitShadow, 70, 682, 760, 420)
        $portraitShadow.Dispose()

        $portraitFill = [System.Drawing.SolidBrush]::new([System.Drawing.Color]::FromArgb(92, $glowColor.R, $glowColor.G, $glowColor.B))
        $graphics.FillEllipse($portraitFill, 220, 298, 460, 460)
        $graphics.FillEllipse($portraitFill, 120, 720, 660, 340)
        $portraitFill.Dispose()

        $initial = if ([string]::IsNullOrWhiteSpace($DisplayName)) { "P" } else { $DisplayName.Trim().Substring(0, 1).ToUpperInvariant() }
        $font = [System.Drawing.Font]::new([System.Drawing.FontFamily]::GenericSansSerif, 176, [System.Drawing.FontStyle]::Bold, [System.Drawing.GraphicsUnit]::Pixel)
        $textBrush = [System.Drawing.SolidBrush]::new((Convert-HexColor $palette.Ink))
        $format = [System.Drawing.StringFormat]::new()
        $format.Alignment = [System.Drawing.StringAlignment]::Center
        $format.LineAlignment = [System.Drawing.StringAlignment]::Center
        $graphics.DrawString($initial, $font, $textBrush, [System.Drawing.RectangleF]::new(0, 410, $width, 220), $format)
        $format.Dispose(); $textBrush.Dispose(); $font.Dispose()

        $bitmap.Save($stream, [System.Drawing.Imaging.ImageFormat]::Png)
        return $stream.ToArray()
    } finally {
        $stream.Dispose(); $graphics.Dispose(); $bitmap.Dispose()
    }
}

function Test-ProfilePhotoReady {
    param([object]$Session)

    $album = Invoke-PynapliJson -Method GET -Path "/v1/media/albums/profile" -AccessToken $Session.accessToken
    if ($album.StatusCode -lt 200 -or $album.StatusCode -ge 300) { return $false }
    foreach ($item in @($album.Body.items)) {
        $asset = $item.mediaAsset
        if ($null -eq $asset) { continue }
        if ($asset.processingStatus -ne "ready" -or $asset.moderationStatus -ne "approved") { continue }
        try {
            Invoke-WebRequest -Method GET -Uri "$BaseUrl/v1/media/uploads/local/$($asset.mediaAssetId)" -ErrorAction Stop | Out-Null
            return $true
        } catch { continue }
    }
    return $false
}

function Set-ProfilePhoto {
    param([object]$Session, [string]$DisplayName, [string]$Email, [int]$Index)

    if (Test-ProfilePhotoReady -Session $Session) { return }

    $contentType = "image/png"
    $bytes = New-DemoProfileImageBytes -DisplayName $DisplayName -Index $Index
    $uploadSlot = Invoke-PynapliJson -Method POST -Path "/v1/media/uploads" -AccessToken $Session.accessToken -Body @{
        albumKind = "profile"
        mediaType = "image"
        contentType = $contentType
        byteSize = $bytes.LongLength
        pixelWidth = 900
        pixelHeight = 1200
    }
    Assert-Success -Response $uploadSlot -Action "Create photo upload $Email"

    $uploadUrl = $uploadSlot.Body.uploadUrl
    try {
        $uploadUri = [System.Uri]$uploadUrl
        $uploadUrl = "$BaseUrl$($uploadUri.PathAndQuery)"
    } catch {
        $uploadUrl = $uploadSlot.Body.uploadUrl
    }
    try {
        Invoke-WebRequest -Method PUT -Uri $uploadUrl -ContentType $contentType -Body $bytes -ErrorAction Stop | Out-Null
    } catch {
        throw "Upload photo $Email failed: $($_.Exception.Message)"
    }

    $assetId = $uploadSlot.Body.mediaAsset.mediaAssetId
    $complete = Invoke-PynapliJson -Method POST -Path "/v1/media/uploads/$assetId/complete" -AccessToken $Session.accessToken -Body @{
        albumId = $uploadSlot.Body.album.albumId
        sortOrder = 0
    }
    Assert-Success -Response $complete -Action "Complete photo upload $Email"
}

# ---------------------------------------------------------------------------
# Demo-world generation
# ---------------------------------------------------------------------------

# Random point within RadiusMeters of the center, uniformly distributed over the disc.
function Get-ScatterPoint {
    param([double]$Latitude, [double]$Longitude, [int]$RadiusMeters)

    $distance = [math]::Sqrt($script:Rng.NextDouble()) * $RadiusMeters
    $bearing = $script:Rng.NextDouble() * 2 * [math]::PI
    $dLat = ($distance * [math]::Cos($bearing)) / 111320.0
    $dLon = ($distance * [math]::Sin($bearing)) / (111320.0 * [math]::Cos($Latitude * [math]::PI / 180.0))
    return @{
        Latitude = [math]::Round($Latitude + $dLat, 6)
        Longitude = [math]::Round($Longitude + $dLon, 6)
    }
}

function Get-Pick { param([object[]]$Pool) return $Pool[$script:Rng.Next($Pool.Count)] }

function Get-PickMany {
    param([object[]]$Pool, [int]$Min, [int]$Max)
    $count = $script:Rng.Next($Min, $Max + 1)
    if ($count -le 0) { return @() }
    return @($Pool | Sort-Object { $script:Rng.Next() } | Select-Object -First $count)
}

$namesByGender = @{
    man        = @("Marcus", "Devon", "Liam", "Andre", "Cole", "Mateo", "Jaxon", "Owen", "Darius", "Felix", "Hugo", "Ezra", "Nolan", "Silas", "Tobias", "Reid", "Wes", "Callum", "Dante", "Emmett")
    woman      = @("Sienna", "Priya", "Chloe", "Amara", "Ivy", "Naomi", "Elena", "Zara", "Margot", "Freya", "Talia", "Bianca", "Daphne", "Selene", "Camille", "Lena", "Aisha", "Vera", "Josie", "Mireille")
    non_binary = @("River", "Sage", "Rowan", "Quinn", "Ash", "Indigo", "Marlow", "Sky")
    trans_man  = @("Elliot", "Finn", "Micah", "Jesse")
    trans_woman = @("Vivienne", "Sasha", "Lux", "Renee")
}
$genderCycle = @("man", "woman", "woman", "man", "non_binary", "woman", "man", "trans_woman", "woman", "man", "non_binary", "man", "woman", "trans_man", "woman", "man")
$orientations = @("straight", "straight", "bisexual", "bisexual", "heteroflexible", "pansexual", "bicurious", "queer", "gay", "lesbian")
$discoveryStyles = @("solo", "together", "both", "social", "social", "ask_in_chat", "both")
$lifestyleTagPool = @("soft_swap", "full_swap", "same_room", "separate_room", "new_to_lifestyle", "experienced", "kink_friendly", "vanilla_social", "enm", "swinging")
$logisticsTagPool = @("hosts", "travels", "hotel_friendly", "public_meet_first", "club_event_only")
$intentPool = @("dating", "chat_first", "events", "open_to_singles", "open_to_couples", "open_to_linked_partners", "open_to_groups")
$bioOpeners = @(
    "Social first, always.", "Weekend patio person.", "Big playlist energy.", "New-ish here, be kind.",
    "Here with an open mind.", "Low key, high curiosity.", "Ask me about my cooking.", "Sunset chaser.",
    "Board games and bad puns.", "Gym in the morning, wine at night.")
$bioMiddles = @(
    "Looking to meet genuine people and see where it goes.", "We believe chemistry starts with good conversation.",
    "Events and meetups are our comfort zone.", "Prefer a coffee or a drink before anything else.",
    "Happiest at a live show or a night market.", "Exploring the scene at our own pace.",
    "Love hosting a good dinner party.", "Travel often — tell me your favourite city.")
$bioClosers = @("Say hi 👋", "No pressure, ever.", "Respect first.", "Let's keep it fun.", "Chat first, then who knows.", "")

$cities = @(
    @{
        Code = "to"; City = "Toronto"; Region = "ON"; Country = "CA"
        Latitude = 43.7067; Longitude = -79.5187; RadiusMeters = 9000
        Neighborhoods = @("Weston", "The Junction", "Bloor West Village", "High Park", "Etobicoke", "Liberty Village", "Roncesvalles", "Parkdale", "Mount Dennis", "Swansea", "Corso Italia", "Little Portugal")
    },
    @{
        Code = "vh"; City = "Victoria Harbour"; Region = "ON"; Country = "CA"
        Latitude = 44.7515; Longitude = -79.7710; RadiusMeters = 12000
        Neighborhoods = @("Victoria Harbour", "Midland", "Penetanguishene", "Port McNicoll", "Wyebridge", "Waubaushene", "Tay", "Coldwater")
    }
)

$viewers = @(
    @{
        Email = "seb@pynapli.local"; DisplayName = "Seb"; Birthdate = "1988-06-14"
        Gender = "man"; Orientation = "pansexual"; RelationshipContext = "single"; DiscoveryStyle = "social"
        Bio = "Founder-mode by day, social by night. Show me your favourite spot in the west end."
        Neighborhood = "Weston"; CityCode = "to"
    },
    @{
        Email = "partner@pynapli.local"; DisplayName = "Marc"; Birthdate = "1987-03-22"
        Gender = "man"; Orientation = "heteroflexible"; RelationshipContext = "single"; DiscoveryStyle = "both"
        Bio = "Georgian Bay local. Boat in the summer, rink in the winter. Chat first."
        Neighborhood = "Victoria Harbour"; CityCode = "vh"
    }
)

function New-DemoAccounts {
    param([hashtable]$CityInfo, [int]$Count)

    $accounts = @()
    $usedNames = @{}
    for ($i = 0; $i -lt $Count; $i++) {
        $gender = $genderCycle[$i % $genderCycle.Count]
        $pool = $namesByGender[$gender]
        $name = Get-Pick -Pool $pool
        $attempts = 0
        while ($usedNames.ContainsKey($name) -and $attempts -lt 20) {
            $name = Get-Pick -Pool $pool
            $attempts++
        }
        if ($usedNames.ContainsKey($name)) { $name = "$name$($i)" }
        $usedNames[$name] = $true

        $age = 21 + $script:Rng.Next(0, 31)
        $birthYear = (Get-Date).Year - $age
        $birthMonth = 1 + $script:Rng.Next(0, 12)
        $point = Get-ScatterPoint -Latitude $CityInfo.Latitude -Longitude $CityInfo.Longitude -RadiusMeters $CityInfo.RadiusMeters

        # First 6 per city form 3 linked couples (indices 0+1, 2+3, 4+5).
        $isLinked = $i -lt 6

        $bio = ((Get-Pick -Pool $bioOpeners), (Get-Pick -Pool $bioMiddles), (Get-Pick -Pool $bioClosers) -join " ").Trim()

        $accounts += @{
            Email = ("demo-{0}-{1:d2}@pynapli.local" -f $CityInfo.Code, ($i + 1))
            DisplayName = $name
            Birthdate = ("{0:d4}-{1:d2}-15" -f $birthYear, $birthMonth)
            Gender = $gender
            Orientation = Get-Pick -Pool $orientations
            RelationshipContext = if ($isLinked) { "linked" } else { "single" }
            DiscoveryStyle = if ($isLinked) { Get-Pick -Pool @("together", "both", "social") } else { Get-Pick -Pool $discoveryStyles }
            Bio = $bio
            Neighborhood = Get-Pick -Pool $CityInfo.Neighborhoods
            Latitude = $point.Latitude
            Longitude = $point.Longitude
            City = $CityInfo.City
            Region = $CityInfo.Region
            Country = $CityInfo.Country
            LifestyleTags = @(Get-PickMany -Pool $lifestyleTagPool -Min 1 -Max 3)
            LogisticsTags = @(Get-PickMany -Pool $logisticsTagPool -Min 0 -Max 2)
            Intents = @(Get-PickMany -Pool $intentPool -Min 1 -Max 3)
        }
    }
    return $accounts
}

# IMPORTANT: displayName/birthdate/gender/orientation/relationshipContext/discoveryStyle have
# overwrite semantics on PATCH /v1/me/profile (null clears them — they predate the partial
# semantics of bio/tags), so every profile PATCH must send the full body, never a partial one.
function Get-ProfileBody {
    param([hashtable]$Account, [bool]$IncludeLifestyleTags)

    $body = @{
        displayName = $Account.DisplayName
        birthdate = $Account.Birthdate
        gender = $Account.Gender
        orientation = $Account.Orientation
        relationshipContext = $Account.RelationshipContext
        discoveryStyle = $Account.DiscoveryStyle
        bio = $Account.Bio
        neighborhood = $Account.Neighborhood
        logisticsTags = $Account.LogisticsTags
        intents = $Account.Intents
    }
    if ($IncludeLifestyleTags) {
        $body.lifestyleTags = $Account.LifestyleTags
    }
    return $body
}

function Publish-DemoAccount {
    param([hashtable]$Account, [bool]$Online, [int]$PhotoIndex)

    $session = Get-DemoSession -Email $Account.Email

    # lifestyleTags are age-gated (ProfileService AGE_VERIFICATION_REQUIRED), so they are
    # applied in a second full-body pass after the SQL age-verification step.
    $profile = Invoke-PynapliJson -Method PATCH -Path "/v1/me/profile" -AccessToken $session.accessToken -Body (Get-ProfileBody -Account $Account -IncludeLifestyleTags $false)
    Assert-Success -Response $profile -Action "Profile update $($Account.Email)"

    $location = Invoke-PynapliJson -Method POST -Path "/v1/location/current" -AccessToken $session.accessToken -Body @{
        latitude = $Account.Latitude
        longitude = $Account.Longitude
        accuracyMeters = 18
        source = "gps"
        city = $Account.City
        region = $Account.Region
        country = $Account.Country
    }
    Assert-Success -Response $location -Action "Location update $($Account.Email)"

    if ($Online) {
        $presence = Invoke-PynapliJson -Method POST -Path "/v1/presence/heartbeat" -AccessToken $session.accessToken
        Assert-Success -Response $presence -Action "Presence heartbeat $($Account.Email)"
    }

    Set-ProfilePhoto -Session $session -DisplayName $Account.DisplayName -Email $Account.Email -Index $PhotoIndex
    return $session
}

# ---------------------------------------------------------------------------
# Conversations via the real API (fake user opens the thread, both sides talk)
# ---------------------------------------------------------------------------

function New-DemoConversation {
    param(
        [object]$FromSession,
        [object]$ViewerSession,
        [string]$FromName,
        [string[]]$Script  # alternating messages: fake, viewer, fake, ...
    )

    $create = Invoke-PynapliJson -Method POST -Path "/v1/messages/threads" -AccessToken $FromSession.accessToken -Body @{
        targetUserId = $ViewerSession.user.userId
        origin = "grid"
    }
    if ($create.StatusCode -lt 200 -or $create.StatusCode -ge 300) {
        Write-Host "  [skip] Conversation from $($FromName): HTTP $($create.StatusCode)"
        return
    }
    # createDirectConversation returns ConversationDetailDto: { conversation, messages, nextCursor }
    $threadId = $create.Body.conversation.conversationId
    if ([string]::IsNullOrWhiteSpace($threadId)) {
        Write-Host "  [skip] Conversation from $($FromName): no conversationId in response"
        return
    }

    for ($i = 0; $i -lt $Script.Count; $i++) {
        $senderSession = if ($i % 2 -eq 0) { $FromSession } else { $ViewerSession }
        $send = Invoke-PynapliJson -Method POST -Path "/v1/messages/threads/$threadId/messages" -AccessToken $senderSession.accessToken -Body @{
            body = $Script[$i]
            clientIdempotencyKey = "demo-seed-$threadId-$i"
        }
        if ($send.StatusCode -lt 200 -or $send.StatusCode -ge 300) {
            Write-Host "  [skip] Message $i from $($FromName): HTTP $($send.StatusCode)"
            return
        }
    }
    Write-Host "  [ok] Conversation: $FromName <-> $($ViewerSession.user.userId) ($($Script.Count) messages)"
}

# ---------------------------------------------------------------------------
# Direct-SQL extras (same docker-psql pattern as seed-dev-data.ps1)
# ---------------------------------------------------------------------------

function Invoke-DemoSql {
    param([string]$Sql, [string]$Action)

    $docker = Get-Command docker -ErrorAction SilentlyContinue
    if ($null -eq $docker) {
        Write-Host "  [skip] Docker not found; $Action was not seeded."
        return $false
    }
    $output = & $docker.Source exec pynapli-postgres psql -U pynapli -d pynapli_dev -v ON_ERROR_STOP=1 -c $Sql 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "$Action failed: $output"
    }
    return $true
}

function Set-DemoAgeVerification {
    param([string[]]$UserIds)

    # Demo/dev bypass of the manual-review verification flow: mark the seeded accounts
    # age-verified directly so age-gated lifestyle tags and filters work in the demo.
    $idList = (@($UserIds | ForEach-Object { "'$_'::uuid" })) -join ", "
    $sql = @"
UPDATE profiles
SET age_verified_at = COALESCE(age_verified_at, now()), updated_at = now()
WHERE user_id IN ($idList);
"@
    if (Invoke-DemoSql -Sql $sql -Action "Age verification") {
        Write-Host "  [ok] $($UserIds.Count) demo accounts marked age-verified"
    }
}

function Set-DemoPartnerLinks {
    param([hashtable]$SessionsByEmail, [hashtable[]]$Accounts, [string]$CityCode)

    $values = @()
    for ($pair = 0; $pair -lt 3; $pair++) {
        $a = $Accounts[$pair * 2]
        $b = $Accounts[$pair * 2 + 1]
        $aId = $SessionsByEmail[$a.Email].user.userId
        $bId = $SessionsByEmail[$b.Email].user.userId
        $cityOffset = if ($CityCode -eq "to") { 400 } else { 500 }
        $idA = "00000000-0000-0000-0000-000000000{0:d3}" -f ($cityOffset + $pair * 2 + 1)
        $idB = "00000000-0000-0000-0000-000000000{0:d3}" -f ($cityOffset + $pair * 2 + 2)
        $values += "('$idA'::uuid, '$aId'::uuid, '$bId'::uuid, 'active', now(), NULL)"
        $values += "('$idB'::uuid, '$bId'::uuid, '$aId'::uuid, 'active', now(), NULL)"
    }

    $sql = @"
INSERT INTO user_profile_links (id, user_id, linked_user_id, status, accepted_at, unlinked_at)
VALUES
$($values -join ",`n")
ON CONFLICT (user_id, linked_user_id) DO UPDATE
SET status = EXCLUDED.status,
    accepted_at = COALESCE(user_profile_links.accepted_at, EXCLUDED.accepted_at),
    unlinked_at = NULL,
    updated_at = now();
"@
    if (Invoke-DemoSql -Sql $sql -Action "Partner links ($CityCode)") {
        Write-Host "  [ok] 3 linked couples in $CityCode"
    }
}

function Set-DemoActivity {
    param([object]$ViewerSession, [hashtable]$SessionsByEmail, [hashtable[]]$Actors, [string]$CityCode)

    $viewerId = $ViewerSession.user.userId
    $actorIds = @($Actors | ForEach-Object { $SessionsByEmail[$_.Email].user.userId })
    $cityOffset = if ($CityCode -eq "to") { 600 } else { 700 }

    $sql = @"
UPDATE discovery_settings
SET show_in_viewed_lists = true, incognito = false, updated_at = now()
WHERE user_id IN ('$viewerId'::uuid, $((@($actorIds | ForEach-Object { "'$_'::uuid" })) -join ", "));

DELETE FROM activity_events WHERE recipient_user_id = '$viewerId'::uuid;

INSERT INTO activity_events (
    id, recipient_user_id, actor_user_id, event_type, target_user_id, aggregate_count,
    first_occurred_at, last_counted_at, occurred_at, updated_at, seen_at, metadata
)
VALUES
    ('00000000-0000-0000-0000-000000000{0}'::uuid, '$viewerId'::uuid, '$($actorIds[0])'::uuid, 'profile_view', '$viewerId'::uuid, 3, now() - interval '55 minutes', now() - interval '10 minutes', now() - interval '10 minutes', now(), NULL, '{{}}'::jsonb),
    ('00000000-0000-0000-0000-000000000{1}'::uuid, '$viewerId'::uuid, '$($actorIds[1])'::uuid, 'profile_view', '$viewerId'::uuid, 1, now() - interval '38 minutes', now() - interval '38 minutes', now() - interval '38 minutes', now(), NULL, '{{}}'::jsonb),
    ('00000000-0000-0000-0000-000000000{2}'::uuid, '$viewerId'::uuid, '$($actorIds[2])'::uuid, 'profile_view', '$viewerId'::uuid, 2, now() - interval '3 hours', now() - interval '80 minutes', now() - interval '80 minutes', now(), NULL, '{{}}'::jsonb),
    ('00000000-0000-0000-0000-000000000{3}'::uuid, '$viewerId'::uuid, '$($actorIds[3])'::uuid, 'flirt', '$viewerId'::uuid, 1, now() - interval '26 minutes', now() - interval '26 minutes', now() - interval '26 minutes', now(), NULL, '{{}}'::jsonb),
    ('00000000-0000-0000-0000-000000000{4}'::uuid, '$viewerId'::uuid, '$($actorIds[4])'::uuid, 'flirt', '$viewerId'::uuid, 1, now() - interval '2 hours', now() - interval '2 hours', now() - interval '2 hours', now(), NULL, '{{}}'::jsonb),
    ('00000000-0000-0000-0000-000000000{5}'::uuid, '$viewerId'::uuid, '$($actorIds[5])'::uuid, 'swipe_like', '$viewerId'::uuid, 1, now() - interval '14 minutes', now() - interval '14 minutes', now() - interval '14 minutes', now(), NULL, '{{}}'::jsonb)
ON CONFLICT (id) DO UPDATE
SET occurred_at = EXCLUDED.occurred_at,
    last_counted_at = EXCLUDED.last_counted_at,
    aggregate_count = EXCLUDED.aggregate_count,
    seen_at = NULL,
    deleted_at = NULL,
    updated_at = now();
"@ -f ($cityOffset + 1), ($cityOffset + 2), ($cityOffset + 3), ($cityOffset + 4), ($cityOffset + 5), ($cityOffset + 6)

    if (Invoke-DemoSql -Sql $sql -Action "Activity feed ($CityCode)") {
        Write-Host "  [ok] Activity feed for $($ViewerSession.user.userId): 3 views, 2 flirts, 1 like"
    }
}

function Set-DemoSwipeMatch {
    param([object]$ViewerSession, [object]$MatchSession, [string]$CityCode)

    # A swipe-origin conversation with no messages: fuels the Messages "New Matches" rail.
    $viewerId = $ViewerSession.user.userId
    $matchId = $MatchSession.user.userId
    $convId = if ($CityCode -eq "to") { "00000000-0000-0000-0000-0000000008a1" } else { "00000000-0000-0000-0000-0000000008b1" }

    $sql = @"
INSERT INTO conversations (id, kind, origin, created_by_user_id, created_at, updated_at)
VALUES ('$convId'::uuid, 'direct', 'swipe', '$viewerId'::uuid, now() - interval '7 minutes', now() - interval '7 minutes')
ON CONFLICT (id) DO NOTHING;

INSERT INTO direct_conversations (conversation_id, user_a_id, user_b_id, created_at)
VALUES ('$convId'::uuid, LEAST('$viewerId'::uuid, '$matchId'::uuid), GREATEST('$viewerId'::uuid, '$matchId'::uuid), now() - interval '7 minutes')
ON CONFLICT (conversation_id) DO NOTHING;

INSERT INTO conversation_participants (
    conversation_id, user_id, role, joined_at, left_at, last_read_at,
    invitation_status, invited_by_user_id, invited_at, invitation_responded_at
)
VALUES
    ('$convId'::uuid, '$viewerId'::uuid, 'member', now() - interval '7 minutes', NULL, now() - interval '7 minutes', 'active', NULL, NULL, NULL),
    ('$convId'::uuid, '$matchId'::uuid, 'member', now() - interval '7 minutes', NULL, now() - interval '7 minutes', 'active', NULL, NULL, NULL)
ON CONFLICT (conversation_id, user_id) DO NOTHING;
"@
    if (Invoke-DemoSql -Sql $sql -Action "Swipe match ($CityCode)") {
        Write-Host "  [ok] No-message swipe match for the New Matches rail ($CityCode)"
    }
}

function Set-DemoMonetization {
    param([object[]]$ViewerSessions)

    $subValues = @()
    $invValues = @()
    for ($i = 0; $i -lt $ViewerSessions.Count; $i++) {
        $userId = $ViewerSessions[$i].user.userId
        $subValues += "('00000000-0000-0000-0000-000000000{0:d3}'::uuid, '$userId'::uuid, 'dev', 'demo-select-{1}', 'select', 'active', now() - interval '1 day', now() + interval '30 days')" -f (801 + $i), $i
        $invValues += "('00000000-0000-0000-0000-000000000{0:d3}'::uuid, '$userId'::uuid, 'intro_note', 5, 'dev', now() + interval '30 days')" -f (811 + $i)
    }

    $sql = @"
INSERT INTO subscriptions (id, user_id, platform, external_subscription_id, tier, status, current_period_start, current_period_end)
VALUES
$($subValues -join ",`n")
ON CONFLICT (platform, external_subscription_id) DO UPDATE
SET user_id = EXCLUDED.user_id,
    tier = EXCLUDED.tier,
    status = EXCLUDED.status,
    current_period_start = EXCLUDED.current_period_start,
    current_period_end = EXCLUDED.current_period_end,
    updated_at = now();

INSERT INTO premium_inventory (id, user_id, inventory_type, quantity, source, expires_at)
VALUES
$($invValues -join ",`n")
ON CONFLICT (id) DO UPDATE
SET user_id = EXCLUDED.user_id,
    quantity = EXCLUDED.quantity,
    source = EXCLUDED.source,
    expires_at = EXCLUDED.expires_at,
    updated_at = now();
"@
    if (Invoke-DemoSql -Sql $sql -Action "Demo subscriptions") {
        Write-Host "  [ok] Select subscriptions + intro notes for both viewers"
    }
}

function Set-DemoTonightSessions {
    param([hashtable]$SessionsByEmail, [hashtable[]]$Accounts)

    $intents = @("social", "social", "event", "solo", "social")
    $hostModes = @("hosting", "public_meet_first", "traveling", "public_meet_first", "hosting")
    $notes = @("Out at the local spot — say hi", "Up for a drink first", "Checking out the live music", $null, "In town for the night")

    for ($i = 0; $i -lt 5 -and $i -lt $Accounts.Count; $i++) {
        # Spread the sessions across the non-linked accounts (index 6+).
        $account = $Accounts[6 + ($i * 3)]
        if ($null -eq $account) { continue }
        $session = $SessionsByEmail[$account.Email]
        if ($null -eq $session) { continue }

        $body = @{
            mode = "solo"
            intent = $intents[$i]
            hostMode = $hostModes[$i]
            durationHours = 4
            mapVisible = $true
        }
        if ($null -ne $notes[$i]) { $body.note = $notes[$i] }

        $response = Invoke-PynapliJson -Method POST -Path "/v1/tonight/sessions" -AccessToken $session.accessToken -Body $body
        if ($response.StatusCode -ge 200 -and $response.StatusCode -lt 300) {
            Write-Host "  [ok] Playing Tonight: $($account.DisplayName) ($($intents[$i]))"
        } elseif ($response.StatusCode -eq 409) {
            Write-Host "  [skip] Playing Tonight: $($account.DisplayName) already live"
        } else {
            Write-Host "  [skip] Playing Tonight: $($account.DisplayName) HTTP $($response.StatusCode)"
        }
    }
}

# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

$health = Invoke-PynapliJson -Method GET -Path "/health"
Assert-Success -Response $health -Action "Health check"

$onlinePerCity = [math]::Ceiling($PerCity * 0.35)

if ($RefreshPresence) {
    Write-Host "Refreshing presence for the online subset at $BaseUrl..."
    foreach ($viewer in $viewers) {
        $session = Get-DemoSession -Email $viewer.Email
        Invoke-PynapliJson -Method POST -Path "/v1/presence/heartbeat" -AccessToken $session.accessToken | Out-Null
    }
    foreach ($cityInfo in $cities) {
        for ($i = 1; $i -le $onlinePerCity; $i++) {
            $email = "demo-{0}-{1:d2}@pynapli.local" -f $cityInfo.Code, $i
            try {
                $session = Get-DemoSession -Email $email
                Invoke-PynapliJson -Method POST -Path "/v1/presence/heartbeat" -AccessToken $session.accessToken | Out-Null
            } catch {
                Write-Host "  [skip] $email (not seeded yet?)"
            }
        }
        Write-Host "  [ok] $($cityInfo.City): $onlinePerCity profiles back online"
    }
    Write-Host "Presence refresh complete."
    return
}

Write-Host "Seeding Pynapli demo world at $BaseUrl ($PerCity profiles per city)..."

# 1. Viewer accounts.
$viewerSessions = @{}
$viewerAccounts = @{}
foreach ($viewer in $viewers) {
    $cityInfo = $cities | Where-Object { $_.Code -eq $viewer.CityCode }
    $account = @{
        Email = $viewer.Email; DisplayName = $viewer.DisplayName; Birthdate = $viewer.Birthdate
        Gender = $viewer.Gender; Orientation = $viewer.Orientation
        RelationshipContext = $viewer.RelationshipContext; DiscoveryStyle = $viewer.DiscoveryStyle
        Bio = $viewer.Bio; Neighborhood = $viewer.Neighborhood
        Latitude = $cityInfo.Latitude; Longitude = $cityInfo.Longitude
        City = $cityInfo.City; Region = $cityInfo.Region; Country = $cityInfo.Country
        LifestyleTags = @("vanilla_social", "new_to_lifestyle"); LogisticsTags = @("public_meet_first"); Intents = @("chat_first", "events")
    }
    $viewerAccounts[$viewer.Email] = $account
    $viewerSessions[$viewer.Email] = Publish-DemoAccount -Account $account -Online $true -PhotoIndex 0
    Write-Host "  [ok] Viewer $($viewer.DisplayName) <$($viewer.Email)> in $($cityInfo.City)"
}

# 2. Fake profiles per city.
$accountsByCity = @{}
$sessionsByEmail = @{}
foreach ($cityInfo in $cities) {
    $accounts = New-DemoAccounts -CityInfo $cityInfo -Count $PerCity
    $accountsByCity[$cityInfo.Code] = $accounts
    for ($i = 0; $i -lt $accounts.Count; $i++) {
        $account = $accounts[$i]
        $session = Publish-DemoAccount -Account $account -Online ($i -lt $onlinePerCity) -PhotoIndex ($i + 1)
        $sessionsByEmail[$account.Email] = $session
        if ((($i + 1) % 10) -eq 0) {
            Write-Host "  [$($cityInfo.City)] $($i + 1)/$($accounts.Count) profiles seeded"
        }
    }
    Write-Host "  [ok] $($cityInfo.City): $($accounts.Count) profiles"
}

# 3. Age-verify everyone via SQL, then re-PATCH the FULL profile with the age-gated lifestyle
# tags included (a tags-only PATCH would null the overwrite-semantics fields — see Get-ProfileBody).
$allUserIds = @($viewerSessions.Values | ForEach-Object { $_.user.userId }) + @($sessionsByEmail.Values | ForEach-Object { $_.user.userId })
Set-DemoAgeVerification -UserIds $allUserIds
foreach ($viewer in $viewers) {
    $tags = Invoke-PynapliJson -Method PATCH -Path "/v1/me/profile" -AccessToken $viewerSessions[$viewer.Email].accessToken `
        -Body (Get-ProfileBody -Account $viewerAccounts[$viewer.Email] -IncludeLifestyleTags $true)
    Assert-Success -Response $tags -Action "Lifestyle tags $($viewer.Email)"
}
foreach ($cityInfo in $cities) {
    foreach ($account in $accountsByCity[$cityInfo.Code]) {
        $tags = Invoke-PynapliJson -Method PATCH -Path "/v1/me/profile" -AccessToken $sessionsByEmail[$account.Email].accessToken `
            -Body (Get-ProfileBody -Account $account -IncludeLifestyleTags $true)
        if ($tags.StatusCode -lt 200 -or $tags.StatusCode -ge 300) {
            Write-Host "  [skip] Lifestyle tags $($account.Email): HTTP $($tags.StatusCode)"
        }
    }
    Write-Host "  [ok] Lifestyle tags applied in $($cityInfo.City)"
}

# 4. Linked couples (3 per city).
foreach ($cityInfo in $cities) {
    Set-DemoPartnerLinks -SessionsByEmail $sessionsByEmail -Accounts $accountsByCity[$cityInfo.Code] -CityCode $cityInfo.Code
}

# 5. Activity feeds + swipe matches + conversations per viewer.
foreach ($viewer in $viewers) {
    $viewerSession = $viewerSessions[$viewer.Email]
    $accounts = $accountsByCity[$viewer.CityCode]

    Set-DemoActivity -ViewerSession $viewerSession -SessionsByEmail $sessionsByEmail -Actors @($accounts[6..11]) -CityCode $viewer.CityCode
    Set-DemoSwipeMatch -ViewerSession $viewerSession -MatchSession $sessionsByEmail[$accounts[12].Email] -CityCode $viewer.CityCode

    $convoA = $accounts[7]
    New-DemoConversation `
        -FromSession $sessionsByEmail[$convoA.Email] `
        -ViewerSession $viewerSession `
        -FromName $convoA.DisplayName `
        -Script @(
            "Hey! Saw you're in $($viewer.Neighborhood) — love that area.",
            "Ha, yes! Born and raised. What about you?",
            "Over in $($convoA.Neighborhood). We should grab a drink at that new spot sometime.",
            "I'd be up for that. What's your week looking like?",
            "Thursday or Friday work best for me 🙂"
        )

    $convoB = $accounts[9]
    New-DemoConversation `
        -FromSession $sessionsByEmail[$convoB.Email] `
        -ViewerSession $viewerSession `
        -FromName $convoB.DisplayName `
        -Script @("Your profile made me smile. Chat first sounds perfect — hi 👋")
}

# 6. Playing Tonight sessions per city.
foreach ($cityInfo in $cities) {
    Set-DemoTonightSessions -SessionsByEmail $sessionsByEmail -Accounts $accountsByCity[$cityInfo.Code]
}

# 7. Subscriptions for both viewers.
Set-DemoMonetization -ViewerSessions @($viewerSessions[$viewers[0].Email], $viewerSessions[$viewers[1].Email])

# 8. Sanity: each viewer sees a populated grid.
Write-Host ""
foreach ($viewer in $viewers) {
    $grid = Invoke-PynapliJson -Method POST -Path "/v1/grid/query" -AccessToken $viewerSessions[$viewer.Email].accessToken -Body @{
        radiusMeters = 30000
        limit = 100
    }
    Assert-Success -Response $grid -Action "Grid query $($viewer.Email)"
    Write-Host "Grid cards for $($viewer.DisplayName): $($grid.Body.items.Count)"
}

Write-Host ""
Write-Host "Demo seed complete."
Write-Host "  Toronto viewer:          seb@pynapli.local / $Password"
Write-Host "  Victoria Harbour viewer: partner@pynapli.local / $Password"
Write-Host "  (Presence fades between sessions: re-run with -RefreshPresence before a demo.)"
