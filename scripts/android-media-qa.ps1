param(
    [string]$BaseUrl = "http://127.0.0.1:8080",
    [string]$ViewerEmail = "dev@pynapli.local",
    [string]$Password = "PynapliDevPass123!",
    [string]$PackageName = "com.pynapli.app",
    [string]$LaunchActivity = ".MainActivity",
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

try {
    Add-Type -AssemblyName System.Drawing
} catch {
    throw "System.Drawing is required for Android media screenshot signatures. Run this script on Windows with the local desktop tooling."
}

function Find-Adb {
    $command = Get-Command "adb" -ErrorAction SilentlyContinue
    if ($null -ne $command) { return $command.Source }

    $sdkCandidates = @(
        $env:ANDROID_HOME,
        $env:ANDROID_SDK_ROOT,
        (Join-Path $env:LOCALAPPDATA "Android\Sdk")
    ) | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }

    foreach ($sdk in $sdkCandidates) {
        $candidate = Join-Path $sdk "platform-tools\adb.exe"
        if (Test-Path $candidate) { return $candidate }
    }

    throw "adb was not found. Start Android Studio once or install Android SDK Platform Tools."
}

function Invoke-Adb {
    param([string[]]$Arguments)

    $output = & $script:Adb -s $script:DeviceId @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "adb failed: $($Arguments -join ' '). Output: $(($output | Out-String).Trim())"
    }
    return $output
}

function Select-AdbDevice {
    $lines = & $script:Adb devices
    if ($LASTEXITCODE -ne 0) { throw "Unable to list adb devices." }

    $devices = @(
        $lines |
            Where-Object { $_ -match "^\S+\s+device$" } |
            ForEach-Object { ($_ -split "\s+")[0] }
    )

    if (-not [string]::IsNullOrWhiteSpace($script:DeviceId)) {
        if ($devices -notcontains $script:DeviceId) {
            throw "Requested adb device '$script:DeviceId' is not connected. Connected devices: $($devices -join ', ')"
        }
        return
    }

    if ($devices.Count -eq 0) {
        throw "No connected Android emulator/device found. Start an emulator and retry."
    }
    if ($devices.Count -gt 1) {
        throw "Multiple Android devices are connected. Rerun with -DeviceId. Connected devices: $($devices -join ', ')"
    }
    $script:DeviceId = $devices[0]
}

function Save-Screen {
    param([string]$Name)

    $remote = "/sdcard/$Name.png"
    $local = Join-Path $script:ArtifactDir "$Name.png"
    Invoke-Adb @("shell", "screencap", "-p", $remote) | Out-Null
    Invoke-Adb @("pull", $remote, $local) | Out-Null
    Invoke-Adb @("shell", "rm", $remote) | Out-Null
    return (Resolve-Path $local).Path
}

function Dump-Ui {
    param(
        [string]$Name,
        [switch]$Keep
    )

    $remote = "/sdcard/window.xml"
    $fileName = if ($Keep) { "$Name.xml" } else { "_latest.xml" }
    $local = Join-Path $script:ArtifactDir $fileName
    $lastDumpOutput = ""

    for ($attempt = 1; $attempt -le 12; $attempt++) {
        $previousErrorActionPreference = $ErrorActionPreference
        $ErrorActionPreference = "Continue"
        try {
            $dumpOutput = & $script:Adb -s $script:DeviceId shell uiautomator dump $remote 2>&1
            $dumpExit = $LASTEXITCODE
        } finally {
            $ErrorActionPreference = $previousErrorActionPreference
        }
        $lastDumpOutput = ($dumpOutput | Out-String).Trim()

        if ($dumpExit -eq 0 -and $lastDumpOutput -notmatch "ERROR:\s+null root") {
            & $script:Adb -s $script:DeviceId pull $remote $local 2>$null | Out-Null
            if ($LASTEXITCODE -ne 0 -or -not (Test-Path $local)) {
                throw "Unable to pull Android UI hierarchy."
            }

            try {
                return [xml](Get-Content -Raw -Path $local)
            } catch {
                if ($attempt -eq 12) {
                    throw "Unable to parse Android UI hierarchy at $local"
                }
            }
        }

        Start-Sleep -Milliseconds 500
    }

    throw "Unable to dump Android UI hierarchy after retries. Last output: $lastDumpOutput"
}

function Get-UiTextNodes {
    param([xml]$Xml)

    @($Xml.SelectNodes("//*[@text or @content-desc]"))
}

function Get-VisibleTexts {
    param([xml]$Xml)

    Get-UiTextNodes $Xml |
        ForEach-Object {
            $text = $_.GetAttribute("text")
            if ([string]::IsNullOrWhiteSpace($text)) {
                $text = $_.GetAttribute("content-desc")
            }
            $text
        } |
        Where-Object { -not [string]::IsNullOrWhiteSpace($_) } |
        Select-Object -Unique
}

function Find-NodeByText {
    param(
        [xml]$Xml,
        [string]$Text
    )

    foreach ($node in Get-UiTextNodes $Xml) {
        $nodeText = $node.GetAttribute("text")
        if ($nodeText.Contains($Text)) { return $node }
    }
    foreach ($node in Get-UiTextNodes $Xml) {
        $nodeDescription = $node.GetAttribute("content-desc")
        if ($nodeDescription.Contains($Text)) { return $node }
    }
    return $null
}

function Find-NodeByExactText {
    param(
        [xml]$Xml,
        [string]$Text
    )

    foreach ($node in Get-UiTextNodes $Xml) {
        if ($node.GetAttribute("text") -eq $Text) { return $node }
    }
    foreach ($node in Get-UiTextNodes $Xml) {
        if ($node.GetAttribute("content-desc") -eq $Text) { return $node }
    }
    return $null
}

function Get-Bounds {
    param($Node)

    $bounds = $Node.GetAttribute("bounds")
    if ($bounds -notmatch "\[(\d+),(\d+)\]\[(\d+),(\d+)\]") {
        throw "Invalid node bounds: $bounds"
    }

    return [pscustomobject]@{
        Left = [int]$Matches[1]
        Top = [int]$Matches[2]
        Right = [int]$Matches[3]
        Bottom = [int]$Matches[4]
        CenterX = [int](([int]$Matches[1] + [int]$Matches[3]) / 2)
        CenterY = [int](([int]$Matches[2] + [int]$Matches[4]) / 2)
    }
}

function Wait-ForAnyText {
    param(
        [string[]]$Texts,
        [int]$TimeoutSeconds = $script:WaitSeconds
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $xml = Dump-Ui "wait"
        foreach ($text in $Texts) {
            if ($null -ne (Find-NodeByText $xml $text)) {
                return [pscustomobject]@{ Text = $text; Xml = $xml }
            }
        }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)

    $visible = (Get-VisibleTexts $xml) -join " | "
    throw "Timed out waiting for one of: $($Texts -join ', '). Visible text: $visible"
}

function Tap-Node {
    param(
        $Node,
        [string]$Label
    )

    $bounds = Get-Bounds $Node
    Invoke-Adb @("shell", "input", "tap", "$($bounds.CenterX)", "$($bounds.CenterY)") | Out-Null
    Write-Host "[tap] '$Label' at $($bounds.CenterX),$($bounds.CenterY)"
}

function Tap-Text {
    param(
        [string]$Text,
        [int]$TimeoutSeconds = $script:WaitSeconds
    )

    $wait = Wait-ForAnyText @($Text) $TimeoutSeconds
    Tap-Node (Find-NodeByText $wait.Xml $Text) $Text
}

function Tap-ExactText {
    param(
        [string]$Text,
        [int]$TimeoutSeconds = $script:WaitSeconds
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $xml = Dump-Ui "wait_exact"
        $node = Find-NodeByExactText $xml $Text
        if ($null -ne $node) {
            Tap-Node $node $Text
            return
        }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)

    $visible = (Get-VisibleTexts $xml) -join " | "
    throw "Timed out waiting for exact text '$Text'. Visible text: $visible"
}

function Assert-TextPresent {
    param(
        [xml]$Xml,
        [string]$Text,
        [string]$Stage
    )

    if ($null -eq (Find-NodeByText $Xml $Text)) {
        $visible = (Get-VisibleTexts $Xml) -join " | "
        throw "$Stage did not show expected text '$Text'. Visible text: $visible"
    }
}

function Assert-NoAppErrorText {
    param(
        [xml]$Xml,
        [string]$Stage
    )

    $allText = (Get-VisibleTexts $Xml) -join "`n"
    $errorPatterns = @(
        "Something went wrong",
        "Illegal input",
        "NETWORK_ERROR",
        "Network request failed",
        "Request failed",
        "UNAUTHENTICATED",
        "HTTP_",
        "Exception"
    )
    foreach ($pattern in $errorPatterns) {
        if ($allText.Contains($pattern)) {
            throw "$Stage displayed app error text matching '$pattern'. Visible text: $allText"
        }
    }
}

function Get-ImageSignature {
    param([string]$Path)

    $bitmap = [System.Drawing.Bitmap]::new($Path)
    $buckets = @{}
    try {
        $stepX = [Math]::Max(1, [int][Math]::Floor($bitmap.Width / 96))
        $stepY = [Math]::Max(1, [int][Math]::Floor($bitmap.Height / 160))
        $sampleCount = 0
        $bright = 0
        $nonBlack = 0

        for ($y = 0; $y -lt $bitmap.Height; $y += $stepY) {
            for ($x = 0; $x -lt $bitmap.Width; $x += $stepX) {
                $color = $bitmap.GetPixel($x, $y)
                $sampleCount += 1
                $luma = (0.2126 * $color.R) + (0.7152 * $color.G) + (0.0722 * $color.B)
                if ($luma -gt 210) { $bright += 1 }
                if ($luma -gt 15) { $nonBlack += 1 }
                $bucket = "$([int]($color.R / 32)):$([int]($color.G / 32)):$([int]($color.B / 32))"
                $buckets[$bucket] = $true
            }
        }

        return [pscustomobject]@{
            width = $bitmap.Width
            height = $bitmap.Height
            sampleCount = $sampleCount
            brightRatio = [Math]::Round($bright / [double]$sampleCount, 4)
            nonBlackRatio = [Math]::Round($nonBlack / [double]$sampleCount, 4)
            colorBucketCount = $buckets.Count
        }
    } finally {
        $bitmap.Dispose()
    }
}

function Assert-ImageSignature {
    param(
        [string]$StageName,
        [string]$ScreenshotPath,
        $StageSpec
    )

    if ($null -eq $StageSpec.image) { return $null }
    $signature = Get-ImageSignature $ScreenshotPath
    $checks = @{
        minWidth = "width"
        minHeight = "height"
        minNonBlackRatio = "nonBlackRatio"
        maxBrightRatio = "brightRatio"
        minColorBucketCount = "colorBucketCount"
    }

    foreach ($check in $checks.GetEnumerator()) {
        $expected = $StageSpec.image.($check.Key)
        if ($null -eq $expected) { continue }

        $actual = $signature.($check.Value)
        if ($check.Key.StartsWith("max")) {
            if ([double]$actual -gt [double]$expected) {
                throw "$StageName screenshot signature exceeded $($check.Key): actual=$actual expectedMax=$expected path=$ScreenshotPath"
            }
        } elseif ([double]$actual -lt [double]$expected) {
            throw "$StageName screenshot signature missed $($check.Key): actual=$actual expectedMin=$expected path=$ScreenshotPath"
        }
    }

    return $signature
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

    foreach ($text in @($stageSpec.requiredTexts)) {
        Assert-TextPresent $Xml $text "$StageName golden"
    }

    $visibleText = (Get-VisibleTexts $Xml) -join "`n"
    foreach ($text in @($stageSpec.forbiddenTexts)) {
        if (-not [string]::IsNullOrWhiteSpace($text) -and $visibleText.Contains($text)) {
            throw "$StageName golden displayed forbidden text '$text'. Visible text: $visibleText"
        }
    }

    return Assert-ImageSignature $StageName $ScreenshotPath $stageSpec
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

function Get-Sha256Base64Url {
    param([string]$Value)

    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        $bytes = [System.Text.Encoding]::UTF8.GetBytes($Value.Trim().ToLowerInvariant())
        $hash = $sha.ComputeHash($bytes)
        return [Convert]::ToBase64String($hash).TrimEnd("=").Replace("+", "-").Replace("/", "_")
    } finally {
        $sha.Dispose()
    }
}

function Invoke-DevSql {
    param([string]$Sql)

    $docker = Get-Command docker -ErrorAction SilentlyContinue
    if ($null -eq $docker) {
        throw "Docker was not found; Android Media QA needs direct dev database cleanup."
    }

    $output = & $docker.Source exec pynapli-postgres psql -U pynapli -d pynapli_dev -t -A -v ON_ERROR_STOP=1 -c $Sql 2>&1
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
$script:DeviceId = $DeviceId
Select-AdbDevice

$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$script:ArtifactDir = Join-Path (Get-Location) (Join-Path $OutputRoot $timestamp)
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
    & pwsh -NoProfile -ExecutionPolicy Bypass -File ".\scripts\seed-dev-data.ps1" -BaseUrl $BaseUrl -SetAndroidEmulatorLocation
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
