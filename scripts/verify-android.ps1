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

.\gradlew.bat :shared:testAndroidHostTest :composeApp:testAndroidHostTest :composeApp:desktopTest :androidApp:assembleDebug --no-configuration-cache --console=plain
if ($LASTEXITCODE -ne 0) {
    throw "Android verification failed."
}
