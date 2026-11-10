<#
.SYNOPSIS
    Shared golden-assertion helpers for the android-*-qa.ps1 connected QA scripts.

.DESCRIPTION
    Third tranche of the QA-harness deduplication (after PynapliQaUi.psm1 and PynapliQaDevice.psm1).
    These are pure functions: they read only their parameters, so unlike the device helpers they need
    no module context.

    - Get-ImageSignature / Assert-ImageSignature: 8 scripts carried them in 4 and 2 variants that
      differed only in WHICH metrics they computed/checked (dark, accent-yellow); every metric had the
      same formula everywhere. The canonical bodies here compute and check all of them. That is
      behavior-preserving because a check only runs for keys present in a stage's golden spec, and the
      scripts whose old copies ignored minDarkRatio/minAccentYellowRatio have no such keys.
    - Assert-GoldenStageSpec: the required/forbidden-text + image core of Assert-GoldenStage. Each
      script keeps a thin Assert-GoldenStage that resolves its own golden spec and skip flag (those
      live in the script's scope) and adds any script-specific checks (Grid card counts, Swipe deck).
    - Get-Sha256Base64Url: identical in 8 scripts.
#>

Set-StrictMode -Off
Add-Type -AssemblyName System.Drawing

# Assert-GoldenStageSpec reads the UI hierarchy (Get-VisibleTexts, Assert-TextPresent). No -Force,
# for the reason given in PynapliQaDevice.psm1: -Force would unload the script's own copy.
Import-Module (Join-Path $PSScriptRoot "PynapliQaUi.psm1") -DisableNameChecking

function Get-ImageSignature {
    param([string]$Path)

    $bitmap = [System.Drawing.Bitmap]::new($Path)
    $buckets = @{}
    try {
        $stepX = [Math]::Max(1, [int][Math]::Floor($bitmap.Width / 96))
        $stepY = [Math]::Max(1, [int][Math]::Floor($bitmap.Height / 160))
        $sampleCount = 0
        $dark = 0
        $bright = 0
        $nonBlack = 0
        $accentYellow = 0

        for ($y = 0; $y -lt $bitmap.Height; $y += $stepY) {
            for ($x = 0; $x -lt $bitmap.Width; $x += $stepX) {
                $color = $bitmap.GetPixel($x, $y)
                $sampleCount += 1
                $luma = (0.2126 * $color.R) + (0.7152 * $color.G) + (0.0722 * $color.B)
                if ($luma -lt 45) { $dark += 1 }
                if ($luma -gt 210) { $bright += 1 }
                if ($luma -gt 15) { $nonBlack += 1 }
                if ($color.R -gt 200 -and $color.G -gt 150 -and $color.B -lt 90) { $accentYellow += 1 }

                $bucket = "$([int]($color.R / 32)):$([int]($color.G / 32)):$([int]($color.B / 32))"
                $buckets[$bucket] = $true
            }
        }

        return [pscustomobject]@{
            width = $bitmap.Width
            height = $bitmap.Height
            sampleCount = $sampleCount
            darkRatio = [Math]::Round($dark / [double]$sampleCount, 4)
            brightRatio = [Math]::Round($bright / [double]$sampleCount, 4)
            nonBlackRatio = [Math]::Round($nonBlack / [double]$sampleCount, 4)
            accentYellowRatio = [Math]::Round($accentYellow / [double]$sampleCount, 4)
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

    if ($null -eq $StageSpec.image) {
        return $null
    }

    $signature = Get-ImageSignature $ScreenshotPath
    $checks = @{
        minWidth = "width"
        minHeight = "height"
        minDarkRatio = "darkRatio"
        maxBrightRatio = "brightRatio"
        minNonBlackRatio = "nonBlackRatio"
        minAccentYellowRatio = "accentYellowRatio"
        minColorBucketCount = "colorBucketCount"
    }

    foreach ($check in $checks.GetEnumerator()) {
        $expected = $StageSpec.image.($check.Key)
        if ($null -eq $expected) {
            continue
        }

        $actual = $signature.($check.Value)
        if ($check.Key.StartsWith("max")) {
            if ([double]$actual -gt [double]$expected) {
                throw "$StageName screenshot signature exceeded $($check.Key): actual=$actual expectedMax=$expected path=$ScreenshotPath"
            }
        } else {
            if ([double]$actual -lt [double]$expected) {
                throw "$StageName screenshot signature missed $($check.Key): actual=$actual expectedMin=$expected path=$ScreenshotPath"
            }
        }
    }

    return $signature
}

<#
.SYNOPSIS
    Asserts one golden stage: every requiredText is visible, no forbiddenText is, and the screenshot
    signature meets the stage's image thresholds. Returns the signature (or $null with no image spec).
#>
function Assert-GoldenStageSpec {
    param(
        [string]$StageName,
        [xml]$Xml,
        [string]$ScreenshotPath,
        $StageSpec
    )

    foreach ($text in @($StageSpec.requiredTexts)) {
        Assert-TextPresent $Xml $text "$StageName golden"
    }

    $visibleText = (Get-VisibleTexts $Xml) -join "`n"
    foreach ($text in @($StageSpec.forbiddenTexts)) {
        if (-not [string]::IsNullOrWhiteSpace($text) -and $visibleText.Contains($text)) {
            throw "$StageName golden displayed forbidden text '$text'. Visible text: $visibleText"
        }
    }

    if ([string]::IsNullOrWhiteSpace($ScreenshotPath)) { return $null }
    return Assert-ImageSignature $StageName $ScreenshotPath $StageSpec
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

Export-ModuleMember -Function Get-ImageSignature, Assert-ImageSignature, Assert-GoldenStageSpec, Get-Sha256Base64Url
