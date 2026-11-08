param(
    [switch]$SkipDockerCompose
)

$ErrorActionPreference = "Stop"

.\scripts\verify-full.ps1 -SkipDockerCompose:$SkipDockerCompose
