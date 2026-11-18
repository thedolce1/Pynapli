
# Token drift guard: the marketing site mirrors the app design language by hand
# (web/src/styles/global.css vars vs composeApp PynapliColors.kt). This check fails
# when a load-bearing hex diverges, so a palette change can't silently fork the brand.
# Canonical source: docs/DESIGN_SPEC.md → PynapliColors.kt (dark palette).

$ErrorActionPreference = "Stop"

$kotlinPath = "composeApp/src/commonMain/kotlin/com/pynapli/app/design/PynapliColors.kt"
$cssPath = "web/src/styles/global.css"

# css var -> Kotlin token (dark palette; the FIRST occurrence of each name wins,
# which is the dark object at the top of PynapliColors.kt).
$mapping = [ordered]@{
    "--bg"              = "Black"
    "--surface"         = "Surface"
    "--surface-raised"  = "SurfaceRaised"
    "--surface-pressed" = "SurfacePressed"
    "--border"          = "Border"
    "--border-soft"     = "BorderSoft"
    "--text"            = "TextPrimary"
    "--text-2"          = "TextSecondary"
    "--text-3"          = "TextTertiary"
    "--gold"            = "Accent"
    "--online"          = "Success"
    "--danger"          = "Danger"
    "--info"            = "Info"
}

$kotlin = Get-Content $kotlinPath -Raw
$css = Get-Content $cssPath -Raw

$failures = @()

foreach ($cssVar in $mapping.Keys) {
    $kotlinName = $mapping[$cssVar]

    $kotlinMatch = [regex]::Match($kotlin, "val $kotlinName = Color\(0x[0-9A-Fa-f]{2}([0-9A-Fa-f]{6})\)")
    if (-not $kotlinMatch.Success) {
        $failures += "Kotlin token '$kotlinName' not found in PynapliColors.kt (renamed? update scripts/check-web-tokens.ps1)"
        continue
    }
    $kotlinHex = $kotlinMatch.Groups[1].Value.ToLowerInvariant()

    $cssMatch = [regex]::Match($css, [regex]::Escape($cssVar) + ":\s*#([0-9A-Fa-f]{6})\b")
    if (-not $cssMatch.Success) {
        $failures += "CSS var '$cssVar' not found in global.css (renamed? update scripts/check-web-tokens.ps1)"
        continue
    }
    $cssHex = $cssMatch.Groups[1].Value.ToLowerInvariant()

    if ($kotlinHex -ne $cssHex) {
        $failures += "$cssVar (#$cssHex) != PynapliColors.$kotlinName (#$kotlinHex)"
    }
}

if ($failures.Count -gt 0) {
    Write-Host "Web token drift detected — the marketing site palette no longer matches the app:" -ForegroundColor Red
    $failures | ForEach-Object { Write-Host "  - $_" -ForegroundColor Red }
    Write-Host "Fix web/src/styles/global.css (or the mapping in this script if a token was deliberately renamed)."
    exit 1
}

Write-Host "Web tokens in sync: $($mapping.Count) site vars match PynapliColors."
