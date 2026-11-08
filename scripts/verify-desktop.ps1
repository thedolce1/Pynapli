param(
    [switch]$SkipSpotless
)

$ErrorActionPreference = "Stop"

.\scripts\check-local-tooling.ps1
.\scripts\check-compose-tokens.ps1

$fallbackJavaHome = "C:\Program Files\Android\Android Studio\jbr"
if (!$env:JAVA_HOME -and (Test-Path (Join-Path $fallbackJavaHome "bin\java.exe"))) {
    $env:JAVA_HOME = $fallbackJavaHome
    $env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
}

# Desktop gate for :desktopApp, the dev-only JVM shell.
#
# WHY THIS EXISTS: adding the module to settings.gradle.kts put it in no gate at all. The pre-push
# hook routed a desktopApp-only change to verify-fast.ps1, which runs
# `spotlessCheck :shared:jvmTest :server:test` — so formatting was checked (spotless is allprojects)
# but nothing COMPILED the shell or ran its tests. That included `DesktopRuntimeConfigTest`, which
# covers the guard stopping a plaintext token store from targeting a real environment.
#
# `:desktopApp:desktopTest` is the whole gate because it pulls the rest in transitively:
# :shared (jvm) -> :composeApp (desktop) -> :desktopApp, then runs the tests. No separate compile
# task needed.
#
# Deliberately NOT folded into verify-fast.ps1: that would drag a Compose-desktop compile into every
# backend-only push, and keeping the fast gate fast is the reason it exists. Same rationale as
# verify-web.ps1 being its own script rather than bolted onto verify-full.
#
# check-compose-tokens.ps1 runs above because :desktopApp has composables of its own (the window
# host in Main.kt), and a desktopApp-only push never reaches verify-full where the guard also runs.
$tasks = @(
    ":desktopApp:desktopTest"
)
if (!$SkipSpotless) {
    $tasks = @("spotlessCheck") + $tasks
}

.\gradlew.bat @tasks --console=plain
if ($LASTEXITCODE -ne 0) {
    throw "Desktop verification failed."
}
