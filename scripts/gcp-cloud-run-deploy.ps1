param(
    [Parameter(Mandatory = $true)]
    [string]$ProjectId,
    [string]$Region = "us-central1",
    [string]$ServiceName = "pynapli-api",
    [string]$Repository = "pynapli",
    [string]$ImageName = "pynapli-api",
    [string]$Tag = "",
    [string]$ImageRef = "",
    [string]$Environment = "staging",
    [string]$FirebaseProjectId = "",
    [string]$AppCheckProjectNumber = "",
    [string]$CloudSqlInstance = "",
    [string]$DatabaseName = "pynapli_staging",
    [string]$DatabaseUser = "pynapli",
    [string]$DatabasePasswordSecret = "",
    [string]$RedisUrl = "",
    [string]$VpcConnector = "",
    [string]$ServiceAccount = "",
    [int]$MinInstances = 0,
    [int]$MaxInstances = 5,
    [string]$Memory = "1Gi",
    [string]$Cpu = "1",
    [switch]$RequireIam
)

$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($ImageRef)) {
    if ([string]::IsNullOrWhiteSpace($Tag)) {
        $Tag = (& git rev-parse --short HEAD).Trim()
        if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($Tag)) {
            throw "Could not resolve git short SHA for the image tag. Pass -Tag or -ImageRef explicitly."
        }
    }
    $ImageRef = "$Region-docker.pkg.dev/$ProjectId/$Repository/$ImageName`:$Tag"
}

$envVars = @(
    "PYNAPLI_ENV=$Environment",
    "PYNAPLI_SERVER_PORT=8080",
    "PYNAPLI_DATABASE_URL=jdbc:postgresql:///$DatabaseName",
    "PYNAPLI_DATABASE_USER=$DatabaseUser",
    "PYNAPLI_MEDIA_STORAGE_PROVIDER=gcs",
    "PYNAPLI_NOTIFICATION_PUSH_PROVIDER=firebase",
    "PYNAPLI_NOTIFICATION_DISPATCH_ENABLED=true",
    "PYNAPLI_ACTIVITY_RETENTION_ENABLED=true",
    "PYNAPLI_MEDIA_CLEANUP_ENABLED=true"
)

if (-not [string]::IsNullOrWhiteSpace($FirebaseProjectId)) {
    $envVars += "PYNAPLI_FIREBASE_PROJECT_ID=$FirebaseProjectId"
}

if (-not [string]::IsNullOrWhiteSpace($AppCheckProjectNumber)) {
    $envVars += "PYNAPLI_APP_CHECK_ENFORCED=true"
    $envVars += "PYNAPLI_APP_CHECK_PROJECT_NUMBER=$AppCheckProjectNumber"
}

if (-not [string]::IsNullOrWhiteSpace($CloudSqlInstance)) {
    $envVars += "PYNAPLI_DATABASE_CLOUD_SQL_INSTANCE=$CloudSqlInstance"
    $envVars += "PYNAPLI_DATABASE_CLOUD_SQL_UNIX_SOCKET_PATH=/cloudsql/$CloudSqlInstance"
    $envVars += "PYNAPLI_DATABASE_CLOUD_SQL_REFRESH_STRATEGY=lazy"
}

if (-not [string]::IsNullOrWhiteSpace($RedisUrl)) {
    $envVars += "PYNAPLI_REDIS_URL=$RedisUrl"
}

$deployArgs = @(
    "run",
    "deploy",
    $ServiceName,
    "--project", $ProjectId,
    "--region", $Region,
    "--platform", "managed",
    "--image", $ImageRef,
    "--port", "8080",
    "--execution-environment", "gen2",
    "--cpu", $Cpu,
    "--memory", $Memory,
    "--min-instances", "$MinInstances",
    "--max-instances", "$MaxInstances",
    "--set-env-vars", ($envVars -join ",")
)

if ($RequireIam) {
    $deployArgs += "--no-allow-unauthenticated"
} else {
    $deployArgs += "--allow-unauthenticated"
}

if (-not [string]::IsNullOrWhiteSpace($ServiceAccount)) {
    $deployArgs += @("--service-account", $ServiceAccount)
}

if (-not [string]::IsNullOrWhiteSpace($CloudSqlInstance)) {
    $deployArgs += @("--add-cloudsql-instances", $CloudSqlInstance)
}

if (-not [string]::IsNullOrWhiteSpace($DatabasePasswordSecret)) {
    $deployArgs += @("--set-secrets", "PYNAPLI_DATABASE_PASSWORD=${DatabasePasswordSecret}:latest")
}

if (-not [string]::IsNullOrWhiteSpace($VpcConnector)) {
    $deployArgs += @("--vpc-connector", $VpcConnector, "--vpc-egress", "private-ranges-only")
}

Write-Host "Deploying $ServiceName to Cloud Run with image $ImageRef..."
& gcloud @deployArgs
if ($LASTEXITCODE -ne 0) {
    throw "gcloud run deploy failed with exit code $LASTEXITCODE."
}
