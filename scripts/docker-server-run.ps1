param(
    [string]$ImageName = "pynapli-api:local",
    [string]$ContainerName = "pynapli-api-local",
    [int]$HostPort = 18080,
    [string]$Environment = "local",
    [string]$DatabaseUrl = "jdbc:postgresql://host.docker.internal:5432/pynapli_dev",
    [string]$DatabaseUser = "pynapli",
    [string]$DatabasePassword = "pynapli_dev_password",
    [string]$RedisUrl = "redis://host.docker.internal:6379",
    [switch]$StartDependencies
)

$ErrorActionPreference = "Stop"

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
Push-Location $repoRoot

try {
    if ($StartDependencies) {
        Write-Host "Starting local Postgres/PostGIS and Redis dependencies..."
        & docker compose up -d postgres redis
        if ($LASTEXITCODE -ne 0) {
            throw "docker compose up failed with exit code $LASTEXITCODE."
        }
    }

    $existingContainer =
        docker ps -a --filter "name=^/$ContainerName$" --format "{{.Names}}" |
        Select-Object -First 1
    if ($existingContainer) {
        throw "Container '$ContainerName' already exists. Stop/remove it first, or pass a different -ContainerName."
    }

    Write-Host "Running $ImageName on http://127.0.0.1:$HostPort"
    & docker run --rm `
        --name $ContainerName `
        -p "${HostPort}:8080" `
        -e "PYNAPLI_ENV=$Environment" `
        -e "PYNAPLI_SERVER_PORT=8080" `
        -e "PYNAPLI_DATABASE_URL=$DatabaseUrl" `
        -e "PYNAPLI_DATABASE_USER=$DatabaseUser" `
        -e "PYNAPLI_DATABASE_PASSWORD=$DatabasePassword" `
        -e "PYNAPLI_REDIS_URL=$RedisUrl" `
        -e "PYNAPLI_MEDIA_STORAGE_PROVIDER=local" `
        -e "PYNAPLI_MEDIA_UPLOAD_BASE_URL=http://10.0.2.2:$HostPort" `
        -e "PYNAPLI_MEDIA_DEV_AUTO_APPROVE_UPLOADS=true" `
        $ImageName
} finally {
    Pop-Location
}
