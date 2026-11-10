param(
    [switch]$SkipDockerCompose
)

$ErrorActionPreference = "Stop"

.\scripts\check-local-tooling.ps1
.\scripts\android-location-policy-check.ps1
.\scripts\check-compose-tokens.ps1

$fallbackJavaHome = "C:\Program Files\Android\Android Studio\jbr"
if (!$env:JAVA_HOME -and (Test-Path (Join-Path $fallbackJavaHome "bin\java.exe"))) {
    $env:JAVA_HOME = $fallbackJavaHome
    $env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
}

if (!$env:ANDROID_HOME) {
    $fallbackAndroidHome = Join-Path $env:LOCALAPPDATA "Android\Sdk"
    if (Test-Path $fallbackAndroidHome) {
        $env:ANDROID_HOME = $fallbackAndroidHome
        $env:ANDROID_SDK_ROOT = $fallbackAndroidHome
    }
}

if (!$SkipDockerCompose) {
    docker compose up -d
    if ($LASTEXITCODE -ne 0) {
        throw "Docker Compose failed. Start Docker Desktop and retry."
    }
}

# lintRelease + assembleRelease guard the variant that actually ships (lint catches NewApi/security
# errors, R8 catches shrinker breakage). The full gate stopped at assembleDebug until 2026-07-26,
# which is why a release-breaking lint error could sit in main unnoticed.
.\gradlew.bat spotlessCheck :shared:jvmTest :server:test :composeApp:desktopTest :androidApp:assembleDebug :androidApp:lintRelease :androidApp:assembleRelease --console=plain
if ($LASTEXITCODE -ne 0) {
    throw "Full verification failed."
}
