param(
    [switch]$SkipSpotless
)

$ErrorActionPreference = "Stop"

.\scripts\check-local-tooling.ps1
.\scripts\check-web-tokens.ps1
.\scripts\check-compose-tokens.ps1

$fallbackJavaHome = "C:\Program Files\Android\Android Studio\jbr"
if (!$env:JAVA_HOME -and (Test-Path (Join-Path $fallbackJavaHome "bin\java.exe"))) {
    $env:JAVA_HOME = $fallbackJavaHome
    $env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
}

# Web gate: the wasm compile across all three client modules, the shared JVM tests, and the
# common tests of shared + composeApp run on wasm in headless Chrome (Karma). The common tests
# use runTest (not runBlocking, which doesn't exist on wasm), so they compile and run on every
# target; running them here keeps it that way. Spotless keeps the new module ktlint-clean.
$tasks = @(
    ":shared:compileKotlinWasmJs",
    ":composeApp:compileKotlinWasmJs",
    ":webApp:compileKotlinWasmJs",
    ":shared:jvmTest",
    ":shared:wasmJsBrowserTest",
    ":composeApp:wasmJsBrowserTest"
)
if (!$SkipSpotless) {
    $tasks = @("spotlessCheck") + $tasks
}

.\gradlew.bat @tasks --console=plain
if ($LASTEXITCODE -ne 0) {
    throw "Web verification failed."
}
