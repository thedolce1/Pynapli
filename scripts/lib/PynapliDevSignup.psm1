<#
.SYNOPSIS
    Checks that the local server serves the dev sign-up shortcuts before a seed, load test or QA run.

.DESCRIPTION
    The public POST /v1/auth/email/signup only emails a code now (ONBOARDING_AUTH_SPEC, "Email
    sign-up"), so seeds, load tests and QA create accounts through the local-only
    POST /v1/dev/auth/email/signup and read codes from POST /v1/dev/auth/email/signup/latest-code.
    Those routes are OFF by default and exist only when the server was started with
    PYNAPLI_DEV_SIGNUP_SHORTCUTS_ENABLED=true, PYNAPLI_ENV=local (the default) and
    PYNAPLI_PASSWORD_RESET_MAILER=log (the local default), and they answer only direct loopback
    requests. Assert-DevSignupShortcuts fails fast with the exact instruction instead of letting a
    script fail halfway with a bare 404.

    Test-DevSignupShortcuts returns $true while they are served (scripts/start-demo-tunnel.ps1 uses it
    to refuse to publish a server that has them on).
#>

$script:DevSignupInstruction = @"
The local server is not serving the dev sign-up shortcuts (POST /v1/dev/auth/email/signup answered 404).
They are off by default. Restart the server with them on, then run this again:

    `$env:PYNAPLI_DEV_SIGNUP_SHORTCUTS_ENABLED = 'true'
    .\gradlew.bat :server:run

(PYNAPLI_ENV must be local and PYNAPLI_PASSWORD_RESET_MAILER=log; both are the local defaults.)
Turn them off again before exposing the server through a tunnel (scripts/start-demo-tunnel.ps1 refuses otherwise).
"@

function Test-DevSignupShortcuts {
    param([Parameter(Mandatory)][string]$BaseUrl)

    try {
        Invoke-RestMethod `
            -Method POST `
            -Uri "$BaseUrl/v1/dev/auth/email/signup/latest-code" `
            -ContentType "application/json" `
            -Body '{"email":"probe@pynapli.local"}' `
            -TimeoutSec 5 `
            -ErrorAction Stop | Out-Null
        return $true
    } catch {
        if ($_.Exception.Response -and [int]$_.Exception.Response.StatusCode -eq 404) {
            return $false
        }
        throw "Could not reach $BaseUrl to check the dev sign-up shortcuts: $($_.Exception.Message)"
    }
}

function Assert-DevSignupShortcuts {
    param([Parameter(Mandatory)][string]$BaseUrl)

    if (-not (Test-DevSignupShortcuts -BaseUrl $BaseUrl)) {
        throw $script:DevSignupInstruction
    }
}

Export-ModuleMember -Function Test-DevSignupShortcuts, Assert-DevSignupShortcuts
