param(
    [string]$ApiBaseUrl = "https://api-staging.pynapli.com",
    [string]$WebsocketBaseUrl = "",
    [switch]$SkipGoogleServicesCheck
)

$ErrorActionPreference = "Stop"

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
Push-Location $repoRoot

try {
    if ([string]::IsNullOrWhiteSpace($WebsocketBaseUrl)) {
        $WebsocketBaseUrl = $ApiBaseUrl.TrimEnd("/") -replace "^https://", "wss://" -replace "^http://", "ws://"
    }

    $googleServicesPath = Join-Path (Join-Path $repoRoot "androidApp") "google-services.json"
    if (-not $SkipGoogleServicesCheck -and -not (Test-Path $googleServicesPath)) {
        throw "androidApp\google-services.json is required for a Firebase-enabled staging APK."
    }

    Write-Host "Building staging APK:"
    Write-Host "  API: $ApiBaseUrl"
    Write-Host "  WS:  $WebsocketBaseUrl"

    $gradle = if ([System.IO.Path]::DirectorySeparatorChar -eq "\") { ".\gradlew.bat" } else { "./gradlew" }
    & $gradle :androidApp:assembleStaging `
        "-Ppynapli.android.stagingApiBaseUrl=$ApiBaseUrl" `
        "-Ppynapli.android.stagingWebsocketBaseUrl=$WebsocketBaseUrl" `
        --no-configuration-cache `
        --console=plain
    if ($LASTEXITCODE -ne 0) {
        throw "assembleStaging failed with exit code $LASTEXITCODE."
    }

    $apk =
        Get-ChildItem -Path (Join-Path (Join-Path (Join-Path (Join-Path (Join-Path $repoRoot "androidApp") "build") "outputs") "apk") "staging") -Filter "androidApp-staging.apk" |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
    if ($null -eq $apk) {
        throw "Staging APK build completed, but no APK was found under androidApp\build\outputs\apk\staging."
    }

    # Friendly-named copy so the file is findable among similarly named downloads
    # (founder request 2026-07-13) — this is the one to share with testers.
    $testerApk = Join-Path $apk.DirectoryName "pynapli-dev-tester.apk"
    Copy-Item $apk.FullName $testerApk -Force

    Write-Host "Staging APK: $($apk.FullName)"
    Write-Host "Tester copy: $testerApk"
} finally {
    Pop-Location
}
