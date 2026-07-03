param(
    [switch]$SkipSpotless
)

$ErrorActionPreference = "Stop"

.\scripts\check-local-tooling.ps1

$fallbackJavaHome = "C:\Program Files\Android\Android Studio\jbr"
if (!$env:JAVA_HOME -and (Test-Path (Join-Path $fallbackJavaHome "bin\java.exe"))) {
    $env:JAVA_HOME = $fallbackJavaHome
    $env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
}

# Web gate: the wasm compile across all three client modules plus the shared JVM tests
# (state holders/repositories the web reuses are JVM-tested; wasm browser-test infra is
# deliberately not part of the gate yet). Spotless keeps the new module ktlint-clean.
$tasks = @(
    ":shared:compileKotlinWasmJs",
    ":composeApp:compileKotlinWasmJs",
    ":webApp:compileKotlinWasmJs",
    ":shared:jvmTest"
)
if (!$SkipSpotless) {
    $tasks = @("spotlessCheck") + $tasks
}

.\gradlew.bat @tasks --console=plain
if ($LASTEXITCODE -ne 0) {
    throw "Web verification failed."
}
