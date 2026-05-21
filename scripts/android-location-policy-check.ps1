param(
    [string]$ManifestPath = "androidApp\src\main\AndroidManifest.xml",
    [string]$MainActivityPath = "androidApp\src\main\kotlin\com\pynapli\app\MainActivity.kt",
    [switch]$RunUnitTests
)

$ErrorActionPreference = "Stop"

function Assert-Contains {
    param(
        [string]$Text,
        [string]$Pattern,
        [string]$Message
    )

    if ($Text -notmatch $Pattern) {
        throw $Message
    }
}

function Assert-NotContains {
    param(
        [string]$Text,
        [string]$Pattern,
        [string]$Message
    )

    if ($Text -match $Pattern) {
        throw $Message
    }
}

if (!(Test-Path $ManifestPath)) {
    throw "Android manifest was not found at '$ManifestPath'."
}

if (!(Test-Path $MainActivityPath)) {
    throw "MainActivity was not found at '$MainActivityPath'."
}

$manifest = Get-Content $ManifestPath -Raw
$mainActivity = Get-Content $MainActivityPath -Raw

Assert-Contains $manifest "android\.permission\.ACCESS_FINE_LOCATION" "Android Grid location needs fine location permission for nearby results."
Assert-Contains $manifest "android\.permission\.ACCESS_COARSE_LOCATION" "Android Grid location needs coarse location permission fallback."
Assert-NotContains $manifest "android\.permission\.ACCESS_BACKGROUND_LOCATION" "Pynapli should not request background location for the Grid."
Assert-NotContains $manifest "android\.permission\.FOREGROUND_SERVICE_LOCATION" "Pynapli should not run a foreground location service for the Grid."
Assert-NotContains $manifest "foregroundServiceType\s*=\s*`"location`"" "Pynapli should not declare a foreground location service for the Grid."

Assert-Contains $mainActivity "getLastKnownLocation" "Grid location startup should try last-known location before requesting a fresh fix."
Assert-Contains $mainActivity "getCurrentLocation" "Android Grid should use one-shot getCurrentLocation when last-known location is unavailable."
Assert-Contains $mainActivity "CancellationSignal\(\)" "One-shot location requests must be cancellable."
Assert-NotContains $mainActivity "requestLocationUpdates" "Continuous requestLocationUpdates is not allowed for the Grid path."

$onStopMatch = [regex]::Match($mainActivity, "override fun onStop\(\)\s*\{(?<body>[\s\S]*?)\n    \}")
if (!$onStopMatch.Success) {
    throw "MainActivity.onStop was not found."
}

$onStopBody = $onStopMatch.Groups["body"].Value
Assert-Contains $onStopBody "pendingLocationSignal\?\.cancel\(\)" "MainActivity.onStop must cancel any pending one-shot location request."
Assert-Contains $onStopBody "pendingLocationSignal\s*=\s*null" "MainActivity.onStop must clear pendingLocationSignal after canceling."
Assert-Contains $onStopBody "presenceStreamJob\?\.cancel\(\)" "MainActivity.onStop must stop the Grid presence stream."
Assert-Contains $onStopBody "activityStreamJob\?\.cancel\(\)" "MainActivity.onStop must stop the Activity realtime stream."

if ($RunUnitTests) {
    .\gradlew.bat :composeApp:desktopTest --tests com.pynapli.app.location.LocationStateHolderTest --console=plain
    if ($LASTEXITCODE -ne 0) {
        throw "LocationStateHolder tests failed."
    }
}

Write-Host "Android location policy check passed: no background location, no continuous updates, one-shot requests cancel on stop."
