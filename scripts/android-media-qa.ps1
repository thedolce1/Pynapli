param(
    [string]$BaseUrl = "http://127.0.0.1:8080",
    [string]$ViewerEmail = "dev@pynapli.local",
    [string]$Password = "PynapliDevPass123!",
    [string]$PackageName = "com.pynapli.app",
    [string]$LaunchActivity = ".DefaultLauncherAlias",
    [string]$DeviceId = "",
    [string]$OutputRoot = "artifacts\android-media-qa",
    [switch]$SkipSeed,
    [switch]$SkipBuild,
    [switch]$SkipInstall,
    [switch]$KeepAppData,
    [string]$GoldenSpec = "scripts\android-media-qa-goldens.json",
    [switch]$SkipGoldenAssertions,
    [switch]$SkipCropStage,
    [int]$WaitSeconds = 35
)

$ErrorActionPreference = "Stop"

# Shared UI-hierarchy helpers. These used to be copy-pasted into every QA script, which is
# how android-tonight-qa.ps1 ended up with a stale copy of the Grid card detection.
Import-Module (Join-Path $PSScriptRoot "lib\PynapliQaUi.psm1") -Force -DisableNameChecking
Import-Module (Join-Path $PSScriptRoot "lib\PynapliQaDevice.psm1") -Force -DisableNameChecking
Import-Module (Join-Path $PSScriptRoot "lib\PynapliQaGolden.psm1") -Force -DisableNameChecking

try {
    Add-Type -AssemblyName System.Drawing
} catch {
    throw "System.Drawing is required for Android media screenshot signatures. Run this script on Windows with the local desktop tooling."
}

function Assert-GoldenStage {
    param(
        [string]$StageName,
        [xml]$Xml,
        [string]$ScreenshotPath
    )

    if ($SkipGoldenAssertions -or $null -eq $script:GoldenSpecData) { return $null }

    $stageSpec = $script:GoldenSpecData.stages.$StageName
    if ($null -eq $stageSpec) {
        throw "Golden stage '$StageName' is missing from $GoldenSpec"
    }

    return Assert-GoldenStageSpec $StageName $Xml $ScreenshotPath $stageSpec
}

function Capture-MediaStage {
    param(
        [string]$StageName,
        [string]$ScreenshotName
    )

    Start-Sleep -Milliseconds 900
    $xml = Dump-Ui $ScreenshotName -Keep
    Assert-NoAppErrorText $xml $StageName
    $shot = Save-Screen $ScreenshotName
    $script:Screenshots[$StageName] = $shot
    $script:VisualSignatures[$StageName] = Assert-GoldenStage $StageName $xml $shot
    return $xml
}

# ---------------------------------------------------------------------------
# Dev API helpers (photo top-up + canonical order verification through HTTP).
# ---------------------------------------------------------------------------

function Invoke-PynapliJson {
    param(
        [ValidateSet("GET", "POST", "PATCH", "PUT", "DELETE")]
        [string]$Method,
        [string]$Path,
        [object]$Body = $null,
        [string]$AccessToken = $null
    )

    $headers = @{ Accept = "application/json" }
    if (-not [string]::IsNullOrWhiteSpace($AccessToken)) {
        $headers.Authorization = "Bearer $AccessToken"
    }

    $parameters = @{
        Method      = $Method
        Uri         = "$BaseUrl$Path"
        Headers     = $headers
        ErrorAction = "Stop"
    }

    if ($null -ne $Body) {
        $parameters.ContentType = "application/json"
        $parameters.Body = $Body | ConvertTo-Json -Depth 10
    }

    try {
        return @{
            StatusCode = 200
            Body = Invoke-RestMethod @parameters
        }
    } catch {
        $statusCode = 0
        $content = $_.ErrorDetails.Message
        if ($_.Exception.Response) {
            $statusCode = [int]$_.Exception.Response.StatusCode
        }
        return @{
            StatusCode = $statusCode
            Body = $null
            RawError = $content
        }
    }
}

function Assert-ApiSuccess {
    param(
        [hashtable]$Response,
        [string]$Action
    )

    if ($Response.StatusCode -lt 200 -or $Response.StatusCode -ge 300) {
        throw "$Action failed with HTTP $($Response.StatusCode): $($Response.RawError)"
    }
}

function Invoke-DevSql {
    param([string]$Sql)

    $docker = Get-Command docker -ErrorAction SilentlyContinue
    if ($null -eq $docker) {
        throw "Docker was not found; Android Media QA needs direct dev database cleanup."
    }

    $output = & $docker.Source exec pynapli-postgres psql -U pynapli -d $(if ($env:PYNAPLI_DATABASE_NAME) { $env:PYNAPLI_DATABASE_NAME } else { 'pynapli_dev' }) -t -A -v ON_ERROR_STOP=1 -c $Sql 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "Dev SQL failed: $output"
    }
    return ($output | Out-String).Trim()
}

function Reset-StaleUploadSlots {
    # An interrupted prior run can leave open pending_upload slots, which the server
    # deliberately counts toward the album cap. Purge them so the cap stage's API
    # top-up is deterministic (production relies on MediaCleanupWorker for this).
    $viewerHash = Get-Sha256Base64Url $ViewerEmail
    $sql = @"
DELETE FROM media_assets
WHERE owner_user_id = (SELECT id FROM users WHERE email_hash = '$viewerHash')
  AND processing_status = 'pending_upload'
  AND deleted_at IS NULL;
"@
    Invoke-DevSql $sql | Out-Null
    Write-Host "[ok] Cleared stale pending upload slots for $ViewerEmail"
}

function Get-ViewerSession {
    $login = Invoke-PynapliJson -Method POST -Path "/v1/auth/email/login" -Body @{
        email = $ViewerEmail
        password = $Password
        deviceId = "media-qa-$([guid]::NewGuid().ToString('N').Substring(0, 8))"
        platform = "qa"
        appVersion = "0.1.0"
    }
    Assert-ApiSuccess $login "Dev viewer login"
    return $login.Body
}

function Get-ProfileAlbumAssetIds {
    param([string]$AccessToken)

    $album = Invoke-PynapliJson -Method GET -Path "/v1/media/albums/profile" -AccessToken $AccessToken
    Assert-ApiSuccess $album "List profile album"
    return @($album.Body.items | ForEach-Object { $_.mediaAsset.mediaAssetId })
}

function Wait-ForProfileAlbumOrder {
    # The UI mutation (reorder/delete) is optimistic and commits asynchronously; on a loaded
    # emulator the server write can land a few seconds after the tap. Poll the canonical order
    # until it matches (still asserting the exact expected order, just tolerating slow devices).
    param(
        [string]$AccessToken,
        [string[]]$Expected,
        [string]$Action,
        [int]$TimeoutSeconds = 15
    )

    $expectedJoined = $Expected -join ","
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $actual = Get-ProfileAlbumAssetIds $AccessToken
        if (($actual -join ",") -eq $expectedJoined) { return $actual }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)

    throw "Server album order after $Action was $($actual -join ', '); expected $($Expected -join ', ')."
}

function New-QaPhotoBytes {
    param([int]$Index)

    $width = 900
    $height = 1200
    $hues = @(
        @{ Fill = "#B0743A"; Ink = "#FFF3E2" },
        @{ Fill = "#3A6E5C"; Ink = "#E8FFF6" },
        @{ Fill = "#6E3A55"; Ink = "#FFE8F3" },
        @{ Fill = "#3A506E"; Ink = "#E8F1FF" },
        @{ Fill = "#6E663A"; Ink = "#FFFCE8" },
        @{ Fill = "#54376B"; Ink = "#F3E8FF" }
    )
    $hue = $hues[$Index % $hues.Count]

    $bitmap = [System.Drawing.Bitmap]::new($width, $height)
    $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
    $stream = [System.IO.MemoryStream]::new()
    try {
        $graphics.Clear([System.Drawing.ColorTranslator]::FromHtml($hue.Fill))
        $font = [System.Drawing.Font]::new([System.Drawing.FontFamily]::GenericSansSerif, 220, [System.Drawing.FontStyle]::Bold, [System.Drawing.GraphicsUnit]::Pixel)
        $brush = [System.Drawing.SolidBrush]::new([System.Drawing.ColorTranslator]::FromHtml($hue.Ink))
        $format = [System.Drawing.StringFormat]::new()
        $format.Alignment = [System.Drawing.StringAlignment]::Center
        $format.LineAlignment = [System.Drawing.StringAlignment]::Center
        $graphics.DrawString("$($Index + 1)", $font, $brush, [System.Drawing.RectangleF]::new(0, 0, $width, $height), $format)
        $format.Dispose()
        $brush.Dispose()
        $font.Dispose()
        $bitmap.Save($stream, [System.Drawing.Imaging.ImageFormat]::Png)
        return $stream.ToArray()
    } finally {
        $stream.Dispose()
        $graphics.Dispose()
        $bitmap.Dispose()
    }
}

function Add-ProfilePhoto {
    param(
        [string]$AccessToken,
        [int]$Index
    )

    # The [byte[]] cast matters: PowerShell unrolls the returned array into object[],
    # and Invoke-WebRequest would stringify that into space-separated decimals.
    [byte[]]$bytes = New-QaPhotoBytes -Index $Index
    $slot = Invoke-PynapliJson -Method POST -Path "/v1/media/uploads" -AccessToken $AccessToken -Body @{
        albumKind = "profile"
        mediaType = "image"
        contentType = "image/png"
        byteSize = $bytes.LongLength
        pixelWidth = 900
        pixelHeight = 1200
    }
    Assert-ApiSuccess $slot "Create QA photo upload slot"

    $uploadUrl = $slot.Body.uploadUrl
    try {
        $uploadUri = [System.Uri]$uploadUrl
        $uploadUrl = "$BaseUrl$($uploadUri.PathAndQuery)"
    } catch {
        $uploadUrl = $slot.Body.uploadUrl
    }

    Invoke-WebRequest -Method PUT -Uri $uploadUrl -ContentType "image/png" -Body $bytes -ErrorAction Stop | Out-Null

    $assetId = $slot.Body.mediaAsset.mediaAssetId
    $complete = Invoke-PynapliJson -Method POST -Path "/v1/media/uploads/$assetId/complete" -AccessToken $AccessToken -Body @{
        albumId = $slot.Body.album.albumId
        sortOrder = $Index
    }
    Assert-ApiSuccess $complete "Complete QA photo upload"
}

function Set-ProfileAlbumPhotoCount {
    param(
        [string]$AccessToken,
        [int]$TargetCount
    )

    $assetIds = Get-ProfileAlbumAssetIds $AccessToken
    while ($assetIds.Count -gt $TargetCount) {
        $extra = $assetIds[$assetIds.Count - 1]
        $delete = Invoke-PynapliJson -Method DELETE -Path "/v1/media/assets/$extra" -AccessToken $AccessToken
        Assert-ApiSuccess $delete "Trim QA photo $extra"
        $assetIds = Get-ProfileAlbumAssetIds $AccessToken
    }
    $index = $assetIds.Count
    while ($assetIds.Count -lt $TargetCount) {
        Add-ProfilePhoto -AccessToken $AccessToken -Index $index
        $index += 1
        $assetIds = Get-ProfileAlbumAssetIds $AccessToken
    }
    Write-Host "[ok] Profile album holds $($assetIds.Count) photos"
    return $assetIds
}

function New-CropSourcePngBytes {
    # Landscape 1200x800 on purpose: a square crop of it can be at most 800px, so the
    # server-side dimension assertion proves the crop actually ran.
    $width = 1200
    $height = 800
    $bitmap = [System.Drawing.Bitmap]::new($width, $height)
    $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
    $stream = [System.IO.MemoryStream]::new()
    try {
        $graphics.Clear([System.Drawing.ColorTranslator]::FromHtml("#C2563A"))
        $rightHalf = [System.Drawing.SolidBrush]::new([System.Drawing.ColorTranslator]::FromHtml("#2E6E62"))
        $graphics.FillRectangle($rightHalf, 600, 0, 600, 800)
        $rightHalf.Dispose()
        $font = [System.Drawing.Font]::new([System.Drawing.FontFamily]::GenericSansSerif, 180, [System.Drawing.FontStyle]::Bold, [System.Drawing.GraphicsUnit]::Pixel)
        $brush = [System.Drawing.SolidBrush]::new([System.Drawing.Color]::White)
        $format = [System.Drawing.StringFormat]::new()
        $format.Alignment = [System.Drawing.StringAlignment]::Center
        $format.LineAlignment = [System.Drawing.StringAlignment]::Center
        $graphics.DrawString("QA", $font, $brush, [System.Drawing.RectangleF]::new(0, 0, $width, $height), $format)
        $format.Dispose()
        $brush.Dispose()
        $font.Dispose()
        $bitmap.Save($stream, [System.Drawing.Imaging.ImageFormat]::Png)
        return $stream.ToArray()
    } finally {
        $stream.Dispose()
        $graphics.Dispose()
        $bitmap.Dispose()
    }
}

function Push-CropSourceImage {
    $localPath = Join-Path $script:ArtifactDir "crop-source.png"
    [byte[]]$bytes = New-CropSourcePngBytes
    [System.IO.File]::WriteAllBytes($localPath, $bytes)
    Invoke-Adb @("push", $localPath, "/sdcard/Pictures/pynapli-qa-crop.png") | Out-Null
    Invoke-Adb @(
        "shell", "am", "broadcast",
        "-a", "android.intent.action.MEDIA_SCANNER_SCAN_FILE",
        "-d", "file:///sdcard/Pictures/pynapli-qa-crop.png"
    ) | Out-Null
    Start-Sleep -Milliseconds 1500
    Write-Host "[ok] Pushed crop source image into the emulator gallery"
}

function Tap-FirstPickerPhoto {
    param([int]$TimeoutSeconds = 30)

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $xml = Dump-Ui "wait_picker"
        foreach ($node in Get-UiTextNodes $xml) {
            $desc = $node.GetAttribute("content-desc")
            if ($desc -like "Photo taken*" -or $desc -like "*pynapli-qa-crop*") {
                Tap-Node $node "Picker photo ($desc)"
                return
            }
        }
        Start-Sleep -Milliseconds 600
    } while ((Get-Date) -lt $deadline)

    $visible = (Get-VisibleTexts $xml) -join " | "
    throw "Timed out waiting for a photo in the system picker. Visible text: $visible"
}

function Start-App {
    $component = "$PackageName/$LaunchActivity"
    Grant-QaNotificationPermission -PackageName $PackageName
    Invoke-Adb @("shell", "am", "start", "-n", $component) | Out-Null
    Write-Host "[step] Launched $component"
}

function Complete-LoginIfNeeded {
    $landing = Wait-ForAnyText @("Dev login", "Grid Load", "Dense", "Filters") 45
    if ($landing.Text -eq "Dev login") {
        Tap-Text "Dev login"

        Wait-ForAnyText @("Allow Pynapli", "Don", "Current location", "Filters", "Grid Load", "Dense") 45 | Out-Null
        Start-Sleep -Milliseconds 1200
        $afterLoginXml = Dump-Ui "after_login"
        if ($null -ne (Find-NodeByText $afterLoginXml "Don")) {
            Tap-Text "Don"
        }
    }
}

function Open-MediaAlbums {
    Wait-ForAnyText @("Grid Load", "Dense", "Filters") 45 | Out-Null
    Tap-Text "Profile"
    Tap-Text "Photos and private album"
    Wait-ForAnyText @("Profile photos") 45 | Out-Null
}

function Write-Summary {
    param([hashtable]$Summary)

    $path = Join-Path $script:ArtifactDir "qa-summary.json"
    $Summary | ConvertTo-Json -Depth 8 | Set-Content -Path $path -Encoding UTF8
    Write-Host ""
    Write-Host "Android Media QA passed."
    Write-Host "Artifact directory: $script:ArtifactDir"
    Write-Host "Summary: $path"
}

if (-not (Test-Path ".\gradlew.bat")) {
    throw "Run this script from the Pynapli repo root."
}

$script:GoldenSpecData = $null
if (-not $SkipGoldenAssertions) {
    if (-not (Test-Path $GoldenSpec)) {
        throw "Golden spec not found at $GoldenSpec. Pass -SkipGoldenAssertions to run without screenshot golden checks."
    }
    $script:GoldenSpecData = Get-Content -Raw -Path $GoldenSpec | ConvertFrom-Json
}

$script:Adb = Find-Adb
$script:DeviceId = Resolve-QaDeviceId -Adb $script:Adb -RequestedDeviceId $DeviceId
# API level in the banner: this suite ran only on android-36 for months with nothing in its
# output saying so, which is how an edge-to-edge defect stayed invisible on API <=34.
Write-Host "Device: $script:DeviceId (API $(Get-QaDeviceApiLevel -Adb $script:Adb -DeviceId $script:DeviceId))"

$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$script:ArtifactDir = Join-Path (Get-Location) (Join-Path $OutputRoot $timestamp)
# Hand the shared adb helpers their per-run state. Must follow the artifact-dir assignment.
Set-PynapliQaContext -Adb $script:Adb -DeviceId $script:DeviceId -ArtifactDir $script:ArtifactDir -WaitSeconds $WaitSeconds -DumpMaxAttempts 12
New-Item -ItemType Directory -Force -Path $script:ArtifactDir | Out-Null
$script:Screenshots = @{}
$script:VisualSignatures = @{}

Write-Host "Pynapli Android Media QA"
Write-Host "------------------------"
Write-Host "Device: $script:DeviceId"
Write-Host "ADB: $script:Adb"
Write-Host "Artifacts: $script:ArtifactDir"

try {
    $health = Invoke-RestMethod -Uri "$BaseUrl/health/deep" -TimeoutSec 5
    if ($health.status -ne "ok") {
        throw "Unexpected health status: $($health | ConvertTo-Json -Compress)"
    }
    Write-Host "[ok] Ktor health: $BaseUrl/health/deep"
} catch {
    throw "Ktor is not healthy at $BaseUrl/health/deep. Start the local server before running Android Media QA. $($_.Exception.Message)"
}

if (-not $SkipSeed) {
    Write-Host "[step] Seeding dev data and emulator location"
    & pwsh -NoProfile -ExecutionPolicy Bypass -File ".\scripts\seed-dev-data.ps1" -BaseUrl $BaseUrl -SetAndroidEmulatorLocation -DeviceId $script:DeviceId
    if ($LASTEXITCODE -ne 0) { throw "Dev data seed failed." }
}

Write-Host "[step] Topping the dev profile album up to 3 photos via the API"
$session = Get-ViewerSession
Reset-StaleUploadSlots
$initialAssetIds = Set-ProfileAlbumPhotoCount -AccessToken $session.accessToken -TargetCount 3

if (-not $SkipBuild) {
    Write-Host "[step] Building androidApp debug APK"
    .\gradlew.bat :androidApp:assembleDebug --console=plain
    if ($LASTEXITCODE -ne 0) { throw "Android debug build failed." }
}

$apk = Get-ChildItem -Path ".\androidApp\build\outputs\apk\debug" -Filter "*.apk" |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1
if ($null -eq $apk) {
    throw "No debug APK found. Run without -SkipBuild first."
}

if (-not $SkipInstall) {
    Write-Host "[step] Installing $($apk.FullName)"
    Invoke-Adb @("install", "-r", $apk.FullName) | Out-Null
}

if (-not $KeepAppData) {
    Write-Host "[step] Clearing app data for fresh login"
    Invoke-Adb @("shell", "pm", "clear", $PackageName) | Out-Null
}

Start-App
Complete-LoginIfNeeded
Open-MediaAlbums

# Stage 1: album management surface with 3 photos, cap affordance, Main badge, and the
# new reorder affordances (long-press hint + per-tile Set as main / Delete actions).
$initialXml = Capture-MediaStage "albumsInitial" "01_albums_initial"
Assert-TextPresent $initialXml "3 of 6" "Initial albums"
Assert-TextPresent $initialXml "Main" "Initial albums"
Assert-TextPresent $initialXml "Hold a photo to reorder" "Initial albums"
Assert-TextPresent $initialXml "Set as main photo" "Initial albums"
Assert-TextPresent $initialXml "Delete photo" "Initial albums"

# Stage 2: promote the second photo to primary via the Set as main affordance; the server
# order is canonical. Drag-to-reorder is the primary gesture, but a long-press drag is not
# deterministic through `adb input`, so QA drives the accessible one-tap path to the same write.
$node = Find-NodeByExactText $initialXml "Set as main photo"
if ($null -eq $node) { throw "No 'Set as main photo' affordance found on the initial albums screen." }
Tap-Node $node "Set as main photo (second tile)"

$expectedAfterMove = @($initialAssetIds[1], $initialAssetIds[0]) + @($initialAssetIds | Select-Object -Skip 2)
$orderAfterMove = Wait-ForProfileAlbumOrder -AccessToken $session.accessToken -Expected $expectedAfterMove -Action "Set as main"
Write-Host "[ok] Server confirmed the reorder: $($orderAfterMove -join ', ')"
$reorderedXml = Capture-MediaStage "albumsReordered" "02_albums_reordered"
Assert-TextPresent $reorderedXml "Main" "Reordered albums"

# Stage 3: delete the (new) primary photo through the confirm dialog.
$deleteNode = Find-NodeByExactText $reorderedXml "Delete photo"
if ($null -eq $deleteNode) { throw "No 'Delete photo' affordance found after reorder." }
Tap-Node $deleteNode "Delete photo (first tile)"
Wait-ForAnyText @("Delete this photo?") 15 | Out-Null
$confirmXml = Capture-MediaStage "deleteConfirm" "03_delete_confirm"
Assert-TextPresent $confirmXml "It will be removed from your profile." "Delete confirm dialog"

Tap-ExactText "Delete"

$expectedAfterDelete = @($expectedAfterMove | Select-Object -Skip 1)
$orderAfterDelete = Wait-ForProfileAlbumOrder -AccessToken $session.accessToken -Expected $expectedAfterDelete -Action "delete"
Write-Host "[ok] Server confirmed the delete: $($orderAfterDelete.Count) photos remain"
$afterDeleteXml = Capture-MediaStage "albumsAfterDelete" "04_albums_after_delete"
Assert-TextPresent $afterDeleteXml "2 of 6" "Albums after delete"

# Stage 4: pick a real gallery photo through the system picker, crop it, and verify
# the uploaded asset is the square JPEG the crop produced (side <= the 800px source
# height proves a pass-through upload would fail this stage).
$stageNames = @("albumsInitial", "albumsReordered", "deleteConfirm", "albumsAfterDelete")
if (-not $SkipCropStage) {
    Write-Host "[step] Crop stage: picking the pushed gallery photo"
    Push-CropSourceImage
    $preCropIds = Get-ProfileAlbumAssetIds $session.accessToken

    Tap-ExactText "Add"
    Tap-FirstPickerPhoto
    Wait-ForAnyText @("Crop photo") 30 | Out-Null

    # One pan across the square exercises the gesture path before confirming.
    Invoke-Adb @("shell", "input", "swipe", "540", "1150", "760", "1150", "260") | Out-Null
    Start-Sleep -Milliseconds 600
    $cropScreenXml = Capture-MediaStage "cropScreen" "05_crop_screen"
    Assert-TextPresent $cropScreenXml "Use photo" "Crop screen"

    Tap-ExactText "Use photo"

    $deadline = (Get-Date).AddSeconds(40)
    $newIds = @()
    do {
        Start-Sleep -Milliseconds 1500
        $ids = Get-ProfileAlbumAssetIds $session.accessToken
        $newIds = @($ids | Where-Object { $preCropIds -notcontains $_ })
    } while ($newIds.Count -eq 0 -and (Get-Date) -lt $deadline)
    if ($newIds.Count -eq 0) { throw "Cropped upload never appeared in the profile album." }

    $album = Invoke-PynapliJson -Method GET -Path "/v1/media/albums/profile" -AccessToken $session.accessToken
    Assert-ApiSuccess $album "List profile album after crop"
    $cropAsset = @($album.Body.items | Where-Object { $_.mediaAsset.mediaAssetId -eq $newIds[0] })[0].mediaAsset
    if ($cropAsset.pixelWidth -ne $cropAsset.pixelHeight) {
        throw "Cropped upload is not square: $($cropAsset.pixelWidth)x$($cropAsset.pixelHeight)."
    }
    if ([int]$cropAsset.pixelWidth -gt 800) {
        throw "Cropped side $($cropAsset.pixelWidth) exceeds the 800px source height - the crop did not happen."
    }
    if ($cropAsset.contentType -ne "image/jpeg") {
        throw "Cropped upload contentType was $($cropAsset.contentType); expected image/jpeg."
    }
    Write-Host "[ok] Server confirmed the cropped upload: $($cropAsset.pixelWidth)x$($cropAsset.pixelHeight) JPEG"

    $afterCropXml = Capture-MediaStage "albumsAfterCrop" "06_albums_after_crop"
    Assert-TextPresent $afterCropXml "3 of 6" "Albums after crop"
    $stageNames += @("cropScreen", "albumsAfterCrop")
}

# Stage 5: top the album up to the cap via the API and confirm the disabled Add tile.
Set-ProfileAlbumPhotoCount -AccessToken $session.accessToken -TargetCount 6 | Out-Null
Tap-Text "Back"
Tap-Text "Photos and private album"
Wait-ForAnyText @("Profile photos") 45 | Out-Null
$capXml = Capture-MediaStage "albumsAtCap" "07_albums_at_cap"
Assert-TextPresent $capXml "6 of 6" "Albums at cap"
Assert-TextPresent $capXml "Album full" "Albums at cap"
$stageNames += "albumsAtCap"

# Leave the dev album in the usual 3-photo state for other QA surfaces.
Set-ProfileAlbumPhotoCount -AccessToken $session.accessToken -TargetCount 3 | Out-Null

Write-Summary @{
    baseUrl = $BaseUrl
    deviceId = $script:DeviceId
    artifactDir = $script:ArtifactDir
    screenshots = $script:Screenshots
    visualSignatures = $script:VisualSignatures
    stages = $stageNames
}
