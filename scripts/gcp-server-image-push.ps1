param(
    [Parameter(Mandatory = $true)]
    [string]$ProjectId,
    [string]$Region = "us-central1",
    [string]$Repository = "pynapli",
    [string]$ImageName = "pynapli-api",
    [string]$Tag = "",
    [switch]$CreateRepository,
    [switch]$SkipLocalBuild
)

$ErrorActionPreference = "Stop"

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
Push-Location $repoRoot

try {
    if ([string]::IsNullOrWhiteSpace($Tag)) {
        $Tag = (& git rev-parse --short HEAD).Trim()
        if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($Tag)) {
            throw "Could not resolve git short SHA for the image tag. Pass -Tag explicitly."
        }
    }

    $registryHost = "$Region-docker.pkg.dev"
    $imageRef = "$registryHost/$ProjectId/$Repository/$ImageName`:$Tag"

    if ($CreateRepository) {
        Write-Host "Ensuring Artifact Registry repository '$Repository' exists in $ProjectId/$Region..."
        & gcloud artifacts repositories describe $Repository --project $ProjectId --location $Region 1>$null 2>$null
        if ($LASTEXITCODE -ne 0) {
            & gcloud artifacts repositories create $Repository `
                --project $ProjectId `
                --location $Region `
                --repository-format docker `
                --description "Pynapli container images"
            if ($LASTEXITCODE -ne 0) {
                throw "Artifact Registry repository creation failed with exit code $LASTEXITCODE."
            }
        }
    }

    Write-Host "Configuring Docker auth for $registryHost..."
    & gcloud auth configure-docker $registryHost --quiet
    if ($LASTEXITCODE -ne 0) {
        throw "gcloud auth configure-docker failed with exit code $LASTEXITCODE."
    }

    if (-not $SkipLocalBuild) {
        & (Join-Path $PSScriptRoot "docker-server-build.ps1") -ImageName $imageRef
        if ($LASTEXITCODE -ne 0) {
            throw "Local Docker build failed with exit code $LASTEXITCODE."
        }
    }

    Write-Host "Pushing $imageRef..."
    & docker push $imageRef
    if ($LASTEXITCODE -ne 0) {
        throw "docker push failed with exit code $LASTEXITCODE."
    }

    Write-Host "Pushed $imageRef"
} finally {
    Pop-Location
}
