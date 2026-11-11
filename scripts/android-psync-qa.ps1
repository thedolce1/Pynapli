param(
    [string]$BaseUrl = "http://127.0.0.1:8080",
    [string]$PackageName = "com.pynapli.app",
    [string]$LaunchActivity = ".DefaultLauncherAlias",
    [string]$DeviceId = "",
    [string]$OutputRoot = "artifacts\android-psync-qa",
    # The seeded linked pair (scripts\seed-dev-data.ps1). One partner uses the app on the device,
    # the other is driven through the API, so a single emulator covers the two-person flow.
    [string]$DeviceUser = "maya@pynapli.local",
    [string]$ApiUser = "alex@pynapli.local",
    [string]$Password = "PynapliDevPass123!",
    # inviter: the device partner starts the session. invitee: the API partner starts it and the
    # device receives the invite. Both = run inviter then invitee.
    [ValidateSet("inviter", "invitee", "both")]
    [string]$Role = "both",
    [switch]$SkipSeed,
    [switch]$SkipBuild,
    [switch]$SkipInstall,
    [int]$WaitSeconds = 35
)

<#
.SYNOPSIS
    Connected two-partner Psync QA: invite -> accept -> both start -> live deck -> partner progress
    -> recap, with one partner on the emulator and the other over HTTP.

.DESCRIPTION
    Checks what only a live run shows: the invite and acceptance reach the other partner without a
    manual refresh, the deck appears once both start, partner progress is shown without revealing
    their choices, and the recap on screen agrees with the server. Requires `:server:run` on
    $BaseUrl and one connected emulator (Pynapli_Phone).
#>

$ErrorActionPreference = "Stop"

Import-Module (Join-Path $PSScriptRoot "lib\PynapliQaUi.psm1") -Force -DisableNameChecking
Import-Module (Join-Path $PSScriptRoot "lib\PynapliQaDevice.psm1") -Force -DisableNameChecking

if (-not (Test-Path ".\gradlew.bat")) {
    throw "Run this script from the Pynapli repo root."
}

$script:Adb = Find-Adb
$script:DeviceId = Resolve-QaDeviceId -Adb $script:Adb -RequestedDeviceId $DeviceId
Write-Host "Device: $script:DeviceId (API $(Get-QaDeviceApiLevel -Adb $script:Adb -DeviceId $script:DeviceId))"

$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$script:ArtifactDir = Join-Path (Get-Location) (Join-Path $OutputRoot $timestamp)
Set-PynapliQaContext -Adb $script:Adb -DeviceId $script:DeviceId -ArtifactDir $script:ArtifactDir -WaitSeconds $WaitSeconds -DumpMaxAttempts 12
New-Item -ItemType Directory -Force -Path $script:ArtifactDir | Out-Null

$script:Results = [System.Collections.Generic.List[object]]::new()
$script:ShotNumber = 0

function Add-Result {
    param([string]$Step, [bool]$Ok, [string]$Detail = "")

    $script:Results.Add([pscustomobject]@{ step = $Step; ok = $Ok; detail = $Detail })
    $mark = if ($Ok) { "PASS" } else { "FAIL" }
    $suffix = if ([string]::IsNullOrWhiteSpace($Detail)) { "" } else { "  ($Detail)" }
    Write-Host "$mark $Step$suffix"
}

function Save-Step {
    param([string]$Name)

    $script:ShotNumber++
    Save-Screen ("{0:D2}_{1}" -f $script:ShotNumber, $Name) | Out-Null
}

# Waits for text without throwing, so one missing screen is reported as a failure and the run
# keeps going far enough to show what the user actually saw.
function Test-TextAppears {
    param([string]$Text, [int]$TimeoutSeconds = 30)

    $started = Get-Date
    try {
        $wait = Wait-ForAnyText @($Text) $TimeoutSeconds
        return [pscustomobject]@{ Found = $true; Seconds = [int]((Get-Date) - $started).TotalSeconds; Xml = $wait.Xml }
    } catch {
        return [pscustomobject]@{ Found = $false; Seconds = $TimeoutSeconds; Xml = $null }
    }
}

function Invoke-Api {
    param([string]$Method, [string]$Path, [string]$Token = "", $Body = $null)

    $headers = @{}
    if ($Token) { $headers["Authorization"] = "Bearer $Token" }
    $params = @{ Method = $Method; Uri = "$BaseUrl$Path"; Headers = $headers; ContentType = "application/json"; TimeoutSec = 20 }
    if ($null -ne $Body) { $params["Body"] = ($Body | ConvertTo-Json -Depth 6 -Compress) }
    return Invoke-RestMethod @params
}

function Get-ApiToken {
    param([string]$Email)

    $login = Invoke-Api POST "/v1/auth/email/login" -Body @{
        email = $Email
        password = $Password
        deviceId = "psync-qa-" + [guid]::NewGuid().ToString("N").Substring(0, 8)
        platform = "qa"
        appVersion = "0.1.0"
    }
    if ($null -ne $login.session) { return $login.session.accessToken }
    return $login.accessToken
}

function Get-OpenSession {
    param([string]$Token)

    $current = Invoke-Api GET "/v1/me/psync/session" $Token
    return $current.session
}

# Leaves whatever session either partner is in, so every run starts from setup.
function Clear-PsyncSessions {
    foreach ($token in @($script:ApiToken, $script:DeviceToken)) {
        $open = Get-OpenSession $token
        if ($null -ne $open) {
            try { Invoke-Api POST "/v1/psync/sessions/$($open.id)/leave" $token | Out-Null } catch { }
        }
    }
}

try {
    $health = Invoke-RestMethod -Uri "$BaseUrl/health/deep" -TimeoutSec 5
    if ($health.status -ne "ok") { throw "Unexpected health status: $($health | ConvertTo-Json -Compress)" }
} catch {
    throw "Ktor is not healthy at $BaseUrl/health/deep. Start the local server first. $($_.Exception.Message)"
}

if (-not $SkipSeed) {
    Write-Host "[step] Seeding dev data (incl. the linked pairs) and emulator location"
    & pwsh -NoProfile -ExecutionPolicy Bypass -File ".\scripts\seed-dev-data.ps1" -BaseUrl $BaseUrl -SetAndroidEmulatorLocation -DeviceId $script:DeviceId
    if ($LASTEXITCODE -ne 0) { throw "Dev data seed failed." }
}

if (-not $SkipBuild) {
    Write-Host "[step] Building androidApp debug APK"
    .\gradlew.bat :androidApp:assembleDebug --console=plain
    if ($LASTEXITCODE -ne 0) { throw "Android debug build failed." }
}

if (-not $SkipInstall) {
    $apk = Get-ChildItem -Path ".\androidApp\build\outputs\apk\debug" -Filter "*.apk" | Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if ($null -eq $apk) { throw "No debug APK found. Run without -SkipBuild first." }
    Write-Host "[step] Installing $($apk.FullName)"
    Invoke-Adb @("install", "-r", $apk.FullName) | Out-Null
}

# Repeated logins trip the auth rate limit; a QA run should not fail on that.
try { docker exec pynapli-redis sh -c "redis-cli --scan --pattern 'pynapli:rate:*' | xargs -r redis-cli del" | Out-Null } catch { }

$script:ApiToken = Get-ApiToken $ApiUser
$script:DeviceToken = Get-ApiToken $DeviceUser

function Start-DeviceSession {
    Invoke-Adb @("shell", "pm", "clear", $PackageName) | Out-Null
    foreach ($permission in @("POST_NOTIFICATIONS", "ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION")) {
        Invoke-AdbBestEffort @("shell", "pm", "grant", $PackageName, "android.permission.$permission")
    }
    Invoke-Adb @("shell", "am", "start", "-n", "$PackageName/$LaunchActivity") | Out-Null

    Tap-ExactText "Email" 60
    Invoke-Adb @("shell", "input", "keyevent", "111") | Out-Null
    Invoke-Adb @("shell", "input", "text", $DeviceUser) | Out-Null
    Tap-ExactText "Password"
    Invoke-Adb @("shell", "input", "keyevent", "111") | Out-Null
    Invoke-Adb @("shell", "input", "text", $Password) | Out-Null
    # The keyboard covers the button; the screen title also reads "Sign in", the button is lower.
    Invoke-Adb @("shell", "input", "keyevent", "4") | Out-Null
    Start-Sleep -Seconds 1
    $xml = Dump-Ui "sign_in"
    $signIn = @(Get-UiTextNodes $xml | Where-Object { $_.GetAttribute("text") -eq "Sign in" } | Sort-Object { (Get-Bounds $_).CenterY })
    Tap-Node $signIn[-1] "Sign in"

    $signedIn = Test-TextAppears "Swipe" 45
    Add-Result "$DeviceUser signs in on the device" $signedIn.Found "$($signedIn.Seconds)s"
    Save-Step "signed_in"
    Tap-BottomNavText "Swipe"
    Start-Sleep -Seconds 2
    Tap-ExactText "Psync"
    Start-Sleep -Seconds 2
}

function Invoke-PsyncRun {
    param([string]$RunRole)

    Write-Host ""
    Write-Host "== Psync run: device partner is the $RunRole =="
    Clear-PsyncSessions
    Start-DeviceSession

    if ($RunRole -eq "inviter") {
        $setup = Test-TextAppears "Invite partner" 20
        Add-Result "setup shows deck size and Invite partner" ($setup.Found -and $null -ne (Find-NodeByText $setup.Xml "Deck size"))
        Tap-ExactText "10"
        Save-Step "setup_deck_10"
        Tap-ExactText "Invite partner"
        $waiting = Test-TextAppears "Waiting for" 20
        Add-Result "after inviting, the device says it is waiting for the partner" $waiting.Found
        Save-Step "waiting_for_partner"

        $session = Get-OpenSession $script:ApiToken
        Add-Result "the partner receives the invite with the chosen deck size" ($null -ne $session -and $session.deckSize -eq 10) "$($session.deckSize)"
        $sessionId = $session.id
        Invoke-Api POST "/v1/psync/sessions/$sessionId/accept" $script:ApiToken | Out-Null
        $bothIn = Test-TextAppears "You're both in" 30
        Add-Result "the device learns the partner accepted without a manual refresh" $bothIn.Found "$($bothIn.Seconds)s"
    } else {
        $created = Invoke-Api POST "/v1/psync/sessions" $script:ApiToken @{ deckSize = 10; filters = @{ radiusMeters = 100000 } }
        $sessionId = $created.id
        $invite = Test-TextAppears "invited you to Psync" 30
        Add-Result "the device shows the partner's invite" $invite.Found "$($invite.Seconds)s"
        $xml = Dump-Ui "invite"
        Add-Result "the invite shows the setup before accepting" ($null -ne (Find-NodeByText $xml "10 profiles"))
        Add-Result "the invite offers Accept, Suggest changes and Decline" (
            $null -ne (Find-NodeByExactText $xml "Accept") -and
                $null -ne (Find-NodeByExactText $xml "Suggest changes") -and
                $null -ne (Find-NodeByExactText $xml "Decline")
        )
        Save-Step "invite_received"
        Tap-ExactText "Accept"
        $bothIn = Test-TextAppears "You're both in" 20
        Add-Result "accepting moves both partners to ready" $bothIn.Found
    }
    Save-Step "both_in"

    Tap-ExactText "Start"
    $startedWaiting = Test-TextAppears "Waiting for" 15
    Add-Result "after Start the device waits for the partner to start" $startedWaiting.Found
    Invoke-Api POST "/v1/psync/sessions/$sessionId/start" $script:ApiToken | Out-Null
    $deck = Test-TextAppears "reviewed" 30
    Add-Result "the deck appears once the partner starts" $deck.Found "$($deck.Seconds)s"
    Save-Step "deck"
    $xml = Dump-Ui "deck"
    Add-Result "the deck shows Pass, Pineapple Like and Like" (
        $null -ne (Find-NodeByExactText $xml "Pass") -and
            $null -ne (Find-NodeByExactText $xml "Pineapple Like") -and
            $null -ne (Find-NodeByExactText $xml "Like")
    )
    Add-Result "the deck offers Pause and End session" ($null -ne (Find-NodeByExactText $xml "Pause") -and $null -ne (Find-NodeByText $xml "End session"))

    # The partner likes the first card and passes the rest; the device must only learn they finished.
    $apiDeck = Invoke-Api GET "/v1/psync/sessions/$sessionId/deck" $script:ApiToken
    $cards = @($apiDeck.cards)
    for ($i = 0; $i -lt $cards.Count; $i++) {
        $action = if ($i -eq 0) { "like" } else { "pass" }
        Invoke-Api POST "/v1/psync/sessions/$sessionId/actions" $script:ApiToken @{ cardToken = $cards[$i].cardToken; action = $action } | Out-Null
    }
    $finished = Test-TextAppears "has finished" 30
    Add-Result "the device shows the partner finished without revealing their choices" $finished.Found "$($finished.Seconds)s"
    Save-Step "partner_finished"

    for ($i = 0; $i -lt $cards.Count; $i++) {
        $label = if ($i -eq 0) { "Like" } else { "Pass" }
        Tap-ExactText $label 15
        Start-Sleep -Milliseconds 600
    }
    $recap = Test-TextAppears "Psync recap" 30
    Add-Result "finishing the deck shows the recap" $recap.Found "$($recap.Seconds)s"
    Save-Step "recap"
    $xml = Dump-Ui "recap"
    Add-Result "the recap offers Start another session and Done" ($null -ne (Find-NodeByText $xml "Start another session") -and $null -ne (Find-NodeByExactText $xml "Done"))
    $serverRecap = Invoke-Api GET "/v1/psync/sessions/$sessionId/recap" $script:DeviceToken
    Add-Result "the server recap has the one card both liked" ($serverRecap.bothLikedCount -eq 1) "bothLiked=$($serverRecap.bothLikedCount), reviewed=$($serverRecap.profilesReviewed)"
}

$roles = if ($Role -eq "both") { @("inviter", "invitee") } else { @($Role) }
foreach ($runRole in $roles) {
    try {
        Invoke-PsyncRun $runRole
    } catch {
        Add-Result "$runRole run completed" $false $_.Exception.Message
        try { Save-Step "$runRole`_error" } catch { }
    }
}
Clear-PsyncSessions

$summaryPath = Join-Path $script:ArtifactDir "qa-summary.json"
$failed = @($script:Results | Where-Object { -not $_.ok })
@{ passed = ($failed.Count -eq 0); deviceId = $script:DeviceId; deviceUser = $DeviceUser; apiUser = $ApiUser; results = $script:Results } |
    ConvertTo-Json -Depth 6 | Set-Content -Path $summaryPath -Encoding UTF8

Write-Host ""
Write-Host "Artifacts: $script:ArtifactDir"
if ($failed.Count -gt 0) {
    throw "Android Psync QA failed: $($failed.Count) step(s): $(($failed | ForEach-Object { $_.step }) -join '; ')"
}
Write-Host "Android Psync QA passed."
