$ErrorActionPreference = "Stop"

.\scripts\check-local-tooling.ps1

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

docker compose up -d
if ($LASTEXITCODE -ne 0) {
    throw "Docker Compose failed. Start Docker Desktop and retry."
}

.\gradlew.bat :shared:jvmTest :server:test :composeApp:testAndroidHostTest :androidApp:assembleDebug --no-configuration-cache
if ($LASTEXITCODE -ne 0) {
    throw "Gradle verification failed."
}
