
# Compose token drift guard: feature code must express corner radii and colors as design tokens,
# never as raw literals. Companion to check-web-tokens.ps1, which guards the *cross-platform* hexes
# (PynapliColors.kt vs the marketing site CSS); this one guards drift *inside* the app.
#
# Why this exists: a 2026-07-23 cohesion audit found 110 hardcoded RoundedCornerShape literals
# against 13 token usages, spread over nine distinct radii where the handoff defines four - plus
# on-photo pure-white text on the two surfaces the OnPhotoPrimary token was written for. None of it
# was carelessness. It accumulated because nothing failed when someone typed
# `RoundedCornerShape(10.dp)`. A cleanup fixes the past; only a check keeps it fixed.
#
# Deliberately NOT checked: the spacing scale. PynapliSpacing is 8dp-based but ~700 call sites use
# 6/10/12/14/18dp, and the handoff gives no canonical answer, so a spacing rule would fail on day
# one and get switched off. Radii and colors are clean, so this guard ships green and stays on.
#
# Also guarded: the hand cursor. Compose shows no hand over clickables on its own, so on the web and
# desktop every button, tile and row showed the plain arrow (measured 2026-11-10) - the main
# "clickable" cue for mouse users, and the web is how many members use the app. Every clickable
# modifier and clickable Material control must carry `handCursor(...)` (design/PynapliPointer.kt).
# Scrims (`indication = null`) are exempt: a hand over the whole backdrop would be wrong.
#
# Escape hatch: put `token-guard:allow` in a comment on the offending line, for the rare value that
# is genuinely not a palette/shape decision (e.g. a pure-black text drop shadow, which is an
# occlusion effect). Greppable, so the exceptions stay reviewable.

$ErrorActionPreference = "Stop"

# Feature code in every module with Compose UI: :composeApp, plus the :webApp and :desktopApp
# shells, whose own chrome (install pill, window host) consumes the same tokens. Two exclusions:
#  - design/  : where tokens are legitimately defined from raw values.
#  - *Test/   : visual-regression tests read raw color channels on purpose (luminance-band ratios).
#
# Roots resolve against the repo root, so this guard gives the same answer whatever directory it is
# invoked from. (The verify-*.ps1 gates that call it still expect to run from the repo root.)
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$roots = @("composeApp/src", "webApp/src", "desktopApp/src")
$excludedPattern = "[\\/]com[\\/]pynapli[\\/]app[\\/]design[\\/]|[\\/](composeApp|webApp|desktopApp)[\\/]src[\\/][A-Za-z]*[Tt]est[\\/]"

# A missing or empty root means a module moved or its sources were renamed. Skipping it would print
# "passed" over code that was never read, so each root must exist and yield files.
$missing = $roots | Where-Object { -not (Test-Path (Join-Path $repoRoot $_)) }
if ($missing) {
    throw "Compose token check: root(s) not found: $($missing -join ', '). Update `$roots if a module moved."
}

$rules = @(
    @{
        Name    = "raw corner radius"
        Pattern = "RoundedCornerShape\(\s*(percent\s*=\s*)?[0-9]"
        Hint    = "use a PynapliShape token (SmallShape / PhotoShape / CardShape / SheetShape / PillShape)"
    },
    @{
        Name    = "raw corner radius in a named argument"
        Pattern = "RoundedCornerShape\([^)]*=\s*[0-9]+(\.[0-9]+)?\.dp"
        Hint    = "build asymmetric corners from PynapliShape values (see ConversationMessages bubbleShape)"
    },
    @{
        Name    = "raw color literal"
        Pattern = "Color\(0x"
        Hint    = "add it to PynapliColors and consume the token"
    },
    @{
        Name    = "bare Compose color"
        Pattern = "Color\.(White|Black|Red|Green|Blue|Gray|LightGray|DarkGray|Yellow|Magenta|Cyan)\b"
        Hint    = "use PynapliColors - on-photo text/icons use OnPhotoPrimary/OnPhotoSecondary, scrims use OverlayScrim"
    }
)

$clickableModifier = "\.(clickable|combinedClickable|selectable|toggleable)\b\s*[({]"
$materialControl = "(?<![\w.])(TextButton|Button|OutlinedButton|IconButton|Surface|DropdownMenuItem|Switch|Slider|RangeSlider|FilterChip|NavigationRailItem|NavigationBarItem|Checkbox)\("

# Index of the ")" closing the "(" at $open. Paren counting only; argument lists here carry no
# unbalanced parens inside strings.
function Find-CloseParen([string]$text, [int]$open) {
    $depth = 0
    for ($i = $open; $i -lt $text.Length; $i++) {
        if ($text[$i] -eq "(") { $depth++ }
        elseif ($text[$i] -eq ")") { $depth--; if ($depth -eq 0) { return $i } }
    }
    return $text.Length - 1
}

$failures = @()
$scanned = 0
$emptyRoots = @()

foreach ($root in $roots) {
    $scannedBefore = $scanned
    Get-ChildItem -Path (Join-Path $repoRoot $root) -Filter *.kt -Recurse -File |
        Where-Object { $_.FullName -notmatch $excludedPattern } |
        ForEach-Object {
            $file = $_
            $scanned++
            $relative = $file.FullName.Substring($repoRoot.Length)

            $lines = [System.IO.File]::ReadAllLines($file.FullName)
            $lineNumber = 0
            foreach ($line in $lines) {
                $lineNumber++

                # Reviewable, greppable opt-out for genuinely non-token values. Honored on the
                # offending line or the line above it, so the justification can live in a comment
                # rather than being crammed onto an already-long expression.
                $previous = if ($lineNumber -ge 2) { $lines[$lineNumber - 2] } else { "" }
                if ($line -match "token-guard:allow" -or $previous -match "token-guard:allow") { continue }

                if ($line -cmatch $clickableModifier -and $line -notmatch "indication\s*=\s*null" -and
                    $line -notmatch "handCursor\(" -and $previous.Trim() -notmatch "handCursor\([^()]*\)$") {
                    $failures += [pscustomobject]@{
                        File = $relative.TrimStart("\", "/")
                        Line = $lineNumber
                        Rule = "clickable without a hand cursor"
                        Hint = "chain .handCursor(enabled = <same enabled>) right before it (design/PynapliPointer.kt)"
                        Text = $line.Trim()
                    }
                }

                foreach ($rule in $rules) {
                    # -cmatch, not -match: PowerShell regex is case-insensitive by default, which
                    # makes `color.red` (a Color channel property) collide with the `Color.Red` rule.
                    if ($line -cmatch $rule.Pattern) {
                        $failures += [pscustomobject]@{
                            File = $relative.TrimStart("\", "/")
                            Line = $lineNumber
                            Rule = $rule.Name
                            Hint = $rule.Hint
                            Text = $line.Trim()
                        }
                        break
                    }
                }
            }

            # Material controls span lines, so they are checked over the whole file text.
            $text = [System.IO.File]::ReadAllText($file.FullName)
            foreach ($match in [regex]::Matches($text, $materialControl)) {
                $lineIndex = ($text.Substring(0, $match.Index) -split "`n").Count - 1
                $current = $lines[$lineIndex]
                $above = if ($lineIndex -ge 1) { $lines[$lineIndex - 1] } else { "" }
                if ($current.TrimStart().StartsWith("//") -or $current.TrimStart().StartsWith("*") -or $current -match "\bfun\s+\w+\(") { continue }
                if ($current -match "token-guard:allow" -or $above -match "token-guard:allow") { continue }
                $open = $match.Index + $match.Length - 1
                $arguments = $text.Substring($open, (Find-CloseParen $text $open) - $open + 1)
                if ($match.Groups[1].Value -eq "Surface" -and $arguments -notmatch "onClick") { continue }
                if ($arguments -notmatch "handCursor\(") {
                    $failures += [pscustomobject]@{
                        File = $relative.TrimStart("\", "/")
                        Line = $lineIndex + 1
                        Rule = "clickable $($match.Groups[1].Value) without a hand cursor"
                        Hint = "add .handCursor(enabled = <same enabled>) to its modifier (design/PynapliPointer.kt)"
                        Text = $current.Trim()
                    }
                }
            }
        }
    if ($scanned -eq $scannedBefore) { $emptyRoots += $root }
}

if ($emptyRoots) {
    throw "Compose token check scanned 0 files under $($emptyRoots -join ', ') - refusing to report a pass."
}

if ($failures.Count -gt 0) {
    Write-Host ""
    Write-Host "Compose token drift check FAILED ($($failures.Count) in $scanned files):" -ForegroundColor Red
    Write-Host ""
    foreach ($f in $failures) {
        Write-Host ("  {0}:{1}" -f $f.File, $f.Line) -ForegroundColor Yellow
        Write-Host ("      {0}" -f $f.Text)
        Write-Host ("      {0} -> {1}" -f $f.Rule, $f.Hint) -ForegroundColor DarkGray
    }
    Write-Host ""
    Write-Host "Tokens live in composeApp/src/commonMain/kotlin/com/pynapli/app/design/ (PynapliDimens.kt, PynapliColors.kt)."
    Write-Host "If a value genuinely is not a palette/shape decision, add a 'token-guard:allow' comment on that line."
    throw "Compose token drift check failed."
}

Write-Host "Compose token check passed: no raw radii or colors, and every clickable has a hand cursor, in $scanned feature files."
