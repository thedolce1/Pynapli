param(
    [switch]$SkipDockerCompose
)

$ErrorActionPreference = "Stop"

.\scripts\check-local-tooling.ps1

$fallbackJavaHome = "C:\Program Files\Android\Android Studio\jbr"
if (!$env:JAVA_HOME -and (Test-Path (Join-Path $fallbackJavaHome "bin\java.exe"))) {
    $env:JAVA_HOME = $fallbackJavaHome
    $env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
}

if (!$SkipDockerCompose) {
    docker compose up -d
    if ($LASTEXITCODE -ne 0) {
        throw "Docker Compose failed. Start Docker Desktop and retry."
    }
}

.\gradlew.bat spotlessCheck :shared:jvmTest :server:test --no-configuration-cache --console=plain
if ($LASTEXITCODE -ne 0) {
    throw "Fast verification failed."
}
