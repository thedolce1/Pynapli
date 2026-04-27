param(
    [string]$BaseUrl = "http://localhost:8080",
    [string]$Password = "PynapliDevPass123!",
    [switch]$SetAndroidEmulatorLocation
)

$ErrorActionPreference = "Stop"

$viewerEmail = "dev@pynapli.local"
$viewerLatitude = 37.4220
$viewerLongitude = -122.0841

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

function Get-DevSession {
    param(
        [string]$Email
    )

    $signup = Invoke-PynapliJson `
        -Method POST `
        -Path "/v1/auth/email/signup" `
        -Body @{
            email = $Email
            password = $Password
            deviceId = "local-dev-seed"
            platform = "seed"
            appVersion = "0.1.0"
        }

    if ($signup.StatusCode -ge 200 -and $signup.StatusCode -lt 300) {
        return $signup.Body
    }

    if ($signup.StatusCode -ne 409) {
        Assert-Success -Response $signup -Action "Signup $Email"
    }

    $login = Invoke-PynapliJson `
        -Method POST `
        -Path "/v1/auth/email/login" `
        -Body @{
            email = $Email
            password = $Password
            deviceId = "local-dev-seed"
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

    $presence = Invoke-PynapliJson `
        -Method POST `
        -Path "/v1/presence/heartbeat" `
        -AccessToken $Session.accessToken
    Assert-Success -Response $presence -Action "Presence heartbeat $Email"
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

$accounts = @(
    @{
        email = $viewerEmail
        displayName = "Seb Dev"
        birthdate = "1988-06-14"
        gender = "man"
        orientation = "open"
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
        orientation = "open"
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
        gender = "nonbinary"
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
        orientation = "open"
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
        orientation = "open"
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
        gender = "nonbinary"
        orientation = "open"
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
for ($index = 0; $index -lt $accounts.Count; $index++) {
    $account = $accounts[$index]
    $session = Seed-Account -Account $account -Online ($index -lt 5)
    if ($account.email -eq $viewerEmail) {
        $viewerSession = $session
    }
    Write-Host "  [ok] $($account.displayName) <$($account.email)>"
}

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
        & $adb emu geo fix $viewerLongitude $viewerLatitude | Out-Null
        Write-Host "  [ok] Android emulator location set near seeded profiles."
    } else {
        Write-Host "  [skip] Android SDK adb was not found."
    }
}

Write-Host ""
Write-Host "Dev seed complete."
Write-Host "Email:    $viewerEmail"
Write-Host "Password: $Password"
Write-Host "Grid cards available for viewer: $($grid.Body.items.Count)"
