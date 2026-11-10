param(
    [string]$ImageName = "pynapli-api:local",
    [switch]$NoCache,
    [switch]$SkipGradle
)

$ErrorActionPreference = "Stop"

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
Push-Location $repoRoot

try {
    if (-not $SkipGradle) {
        Write-Host "Building Ktor server distribution..."
        $gradle = if ([System.IO.Path]::DirectorySeparatorChar -eq "\") { ".\gradlew.bat" } else { "./gradlew" }
        & $gradle :server:installDist --console=plain
        if ($LASTEXITCODE -ne 0) {
            throw "Gradle :server:installDist failed with exit code $LASTEXITCODE."
        }
    }

    $serverDistribution = Join-Path (Join-Path (Join-Path (Join-Path $repoRoot "server") "build") "install") "server"
    if (-not (Test-Path $serverDistribution)) {
        throw "Missing $serverDistribution. Run without -SkipGradle or run .\gradlew.bat :server:installDist first."
    }

    $dockerArgs = @("build", "-f", "infra/server.Dockerfile", "-t", $ImageName)
    if ($NoCache) {
        $dockerArgs += "--no-cache"
    }
    $dockerArgs += "."

    Write-Host "Building Docker image $ImageName..."
    & docker @dockerArgs
    if ($LASTEXITCODE -ne 0) {
        throw "docker build failed with exit code $LASTEXITCODE."
    }

    Write-Host "Built $ImageName"
} finally {
    Pop-Location
}
