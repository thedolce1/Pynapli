$ErrorActionPreference = "Continue"

function Find-AndroidStudioJavaHome {
    $candidate = "C:\Program Files\Android\Android Studio\jbr"
    if (Test-Path (Join-Path $candidate "bin\java.exe")) {
        return $candidate
    }

    return $null
}

function Find-AndroidSdkHome {
    $candidate = Join-Path $env:LOCALAPPDATA "Android\Sdk"
    if (Test-Path $candidate) {
        return $candidate
    }

    return $null
}

function Test-Command($name) {
    $cmd = Get-Command $name -ErrorAction SilentlyContinue
    if ($null -eq $cmd) {
        Write-Host "[missing] $name"
        return $false
    }

    Write-Host "[ok] $name -> $($cmd.Source)"
    return $true
}

Write-Host "Pynapli local tooling check"
Write-Host "---------------------------"

$ok = $true
$ok = (Test-Command "git") -and $ok

if (!(Test-Command "java")) {
    $fallbackJavaHome = Find-AndroidStudioJavaHome
    if ($null -ne $fallbackJavaHome) {
        Write-Host "[ok] java fallback -> $fallbackJavaHome"
    } else {
        $ok = $false
    }
}

if (Test-Path ".\gradlew.bat") {
    Write-Host "[ok] gradle wrapper -> .\gradlew.bat"
} elseif (!(Test-Command "gradle")) {
    $ok = $false
}

$ok = (Test-Command "docker") -and $ok

if ($env:ANDROID_HOME -or $env:ANDROID_SDK_ROOT) {
    Write-Host "[ok] Android SDK env -> $($env:ANDROID_HOME)$($env:ANDROID_SDK_ROOT)"
} else {
    $fallbackSdk = Find-AndroidSdkHome
    if ($null -ne $fallbackSdk) {
        Write-Host "[ok] Android SDK fallback -> $fallbackSdk"
    } else {
        Write-Host "[missing] Android SDK"
        $ok = $false
    }
}

if ($ok) {
    Write-Host "All required command-line tools are available through PATH or detected local fallbacks."
    exit 0
}

Write-Host ""
Write-Host "Install or add missing tools to PATH before running the full build."
Write-Host "Recommended baseline: JDK 17, Gradle 9.4.1, Docker Desktop, Android Studio."
exit 1
