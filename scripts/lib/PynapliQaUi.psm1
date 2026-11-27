<#
.SYNOPSIS
    Shared UI-hierarchy helpers for the android-*-qa.ps1 connected QA scripts.

.DESCRIPTION
    Every QA script used to carry its own copy of these. That is what caused the 2026-07-26 bug
    cluster: fixes landed in one copy and not the others. Concretely, `5c00a2c`'s bare-integer filter
    and the "NEARBY" chrome entry reached android-grid-qa.ps1 but never android-tonight-qa.ps1, so
    Tonight kept resolving a filter chip (and then a context-strip label) as its "first grid card";
    and android-grid-qa.ps1's substring-matching wait let a broken profile-detail stage report green
    for months. An inventory found 388 function definitions across 18 scripts, with Dump-Ui alone in
    9 different versions.

    SCOPE — this module deliberately holds only the *pure* layer: functions that take a parsed UI
    hierarchy (and nothing else) and return a value. The stateful layer (Dump-Ui, Save-Screen,
    Tap-Text, Wait-ForAnyText, Scroll-SheetToText, Invoke-Adb) stays in each script, because those
    read per-script state via `$script:Adb` / `$script:DeviceId` / `$script:ArtifactDir` /
    `$script:WaitSeconds`. A PowerShell module gets its OWN `$script:` scope, so moving them here
    would silently resolve those to $null instead of the caller's values. Threading that state
    through is a separate change; do not "finish the job" by moving them without addressing it.

    android-e2e-qa.ps1 is intentionally NOT a consumer. It has its own deliberately minimal dialect
    (exact-match Find-NodeByText, a reduced Get-Bounds), and converting it would change its
    behaviour rather than merely deduplicate it.
#>

Set-StrictMode -Off

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

<#
    Substring match on text, then on content-desc.

    NOTE the substring semantics — they are load-bearing and have bitten this suite before. "Message"
    matches the "Messages" bottom-nav tab, and "Link" matches the "Linked" filter chip; both are on
    screen behind an open conversation. Waiting on such a string returns instantly and asserts
    nothing. When a target could be a substring of persistent chrome, use Find-NodeByExactText.
#>
function Find-NodeByText {
    param(
        [xml]$Xml,
        [string]$Text
    )

    $nodes = Get-UiTextNodes $Xml

    foreach ($node in $nodes) {
        if ($node.GetAttribute("text").Contains($Text)) { return $node }
    }
    foreach ($node in $nodes) {
        if ($node.GetAttribute("content-desc").Contains($Text)) { return $node }
    }
    return $null
}

<#
    Exact match. -TextOnly skips the content-desc pass: android-messages-qa.ps1 needs it so the
    composer's "Message" can never be satisfied by some other element's content-desc. The default
    (text then content-desc) is what the other consumers were already using.
#>
function Find-NodeByExactText {
    param(
        [xml]$Xml,
        [string]$Text,
        [switch]$TextOnly
    )

    foreach ($node in Get-UiTextNodes $Xml) {
        if ($node.GetAttribute("text") -eq $Text) { return $node }
    }
    if ($TextOnly) { return $null }
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

function Get-ScreenSize {
    param([xml]$Xml)

    $root = $Xml.SelectSingleNode("/hierarchy/node")
    if ($null -ne $root) {
        $bounds = Get-Bounds $root
        return [pscustomobject]@{ Width = $bounds.Right; Height = $bounds.Bottom }
    }

    $right = 0
    $bottom = 0
    foreach ($node in $Xml.SelectNodes("//*[@bounds]")) {
        $bounds = Get-Bounds $node
        if ($bounds.Right -gt $right) { $right = $bounds.Right }
        if ($bounds.Bottom -gt $bottom) { $bottom = $bounds.Bottom }
    }
    if ($right -le 0 -or $bottom -le 0) {
        throw "Unable to determine Android screen size from UI hierarchy."
    }
    return [pscustomobject]@{ Width = $right; Height = $bottom }
}

# Exact match constrained to the bottom-nav band, returning the lowest match. Used to disambiguate a
# nav tab from same-named content elsewhere on screen.
function Find-BottomNavNodeByText {
    param(
        [xml]$Xml,
        [string]$Text
    )

    $screen = Get-ScreenSize $Xml
    $navTop = [int]($screen.Height * 0.82)
    $hits = @()

    foreach ($node in Get-UiTextNodes $Xml) {
        $nodeText = $node.GetAttribute("text")
        if ([string]::IsNullOrWhiteSpace($nodeText)) {
            $nodeText = $node.GetAttribute("content-desc")
        }
        if ($nodeText -eq $Text) {
            $bounds = Get-Bounds $node
            if ($bounds.CenterY -ge $navTop) {
                $hits += [pscustomobject]@{ Node = $node; Bounds = $bounds }
            }
        }
    }

    if ($hits.Count -eq 0) { return $null }
    return ($hits | Sort-Object { $_.Bounds.CenterY } -Descending | Select-Object -First 1).Node
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

function Assert-TextAbsent {
    param(
        [xml]$Xml,
        [string]$Text,
        [string]$Stage
    )

    if ($null -ne (Find-NodeByText $Xml $Text)) {
        $visible = (Get-VisibleTexts $Xml) -join " | "
        throw "$Stage showed forbidden text '$Text'. Visible text: $visible"
    }
}

<#
    -Allowed exists so a caller can exempt copy that would otherwise trip these patterns.

    android-grid-denied-location-qa.ps1 tried to do this locally and its version was dead code: it
    held full sentences ("Location permission is required to load nearby profiles.") and tested
    `$allowed -notcontains $pattern` against pattern tokens like "Illegal input", which is always
    true. It therefore behaved exactly like the plain version, which is why dropping it here changes
    nothing. Kept as a real parameter so the intent can be expressed correctly if ever needed.
#>
function Assert-NoAppErrorText {
    param(
        [xml]$Xml,
        [string]$Stage,
        [string[]]$Allowed = @()
    )

    $allText = (Get-VisibleTexts $Xml) -join "`n"
    $errorPatterns = @(
        "Illegal input",
        "NETWORK_ERROR",
        "Network request failed",
        "Request failed",
        "UNAUTHENTICATED",
        "HTTP_",
        "Exception"
    )

    foreach ($pattern in $errorPatterns) {
        if ($allText.Contains($pattern) -and $Allowed -notcontains $pattern) {
            throw "$Stage displayed app error text matching '$pattern'. Visible text: $allText"
        }
    }
}

<#
    Grid card detection. This is the function whose divergence caused the Tonight bug, so treat the
    two filters below as load-bearing:

    - The Top floor sits below the horizontally-scrolling quick-filter chip row (~348-406), not just
      below the app bar. That row grows every time a filter ships — the Trust chips
      (Photo verified / Age verified) are the current tail — and any chip left of the first
      card sorts ahead of it, so a lower floor resolves a chip as "the first grid card".
    - Bare integers are the density/context-strip count; card names are never purely numeric.
#>
function Get-GridCardSnapshot {
    param(
        [xml]$Xml,
        [int]$Limit = 12
    )

    $chromeTexts = @(
        "Current location",
        "Filters",
        "Online",
        "Singles",
        "Linked",
        "Verified",
        "Nearby",
        "Favorites",
        "Grid",
        "Swipe",
        "Activity",
        "Messages",
        "Store",
        "NEARBY",
        # Grid chrome and tile marks added since Section 2 (chips, context strip, tile badges).
        "Fresh",
        "Tonight",
        "No single men",
        "Profile",
        "Showing people farther away",
        "New",
        "Playing tonight",
        "Playing Tonight",
        "Linked partner"
    )

    $candidates = @(
        foreach ($node in Get-UiTextNodes $Xml) {
            $text = $node.GetAttribute("text")
            if ([string]::IsNullOrWhiteSpace($text)) { continue }
            if ($chromeTexts -contains $text) { continue }
            # The Filters chip reads "Filters · 1" while a filter is active.
            if ($text -like "Filters *") { continue }
            if ($text -match "^\s*(<=|≤)?\s*\d+(\.\d+)?\s*(m|km)\s*$") { continue }
            if ($text -match "^\s*\d+\s*$") { continue }
            # Tile captions such as "Active 15m ago" / "Active today" are not a card's name.
            if ($text -like "Active *") { continue }

            $bounds = Get-Bounds $node
            if ($bounds.Top -lt 420 -or $bounds.Top -gt 2100) { continue }

            [pscustomobject]@{
                Node = $node
                Text = $text
                Top = $bounds.Top
                Left = $bounds.Left
                Right = $bounds.Right
                Bottom = $bounds.Bottom
            }
        }
    )

    @($candidates | Sort-Object Top, Left | Select-Object -First $Limit)
}

function Get-FirstGridCardNode {
    param([xml]$Xml)

    $first = @(Get-GridCardSnapshot $Xml 1 | Select-Object -First 1)
    if ($first.Count -eq 0) {
        throw "No visible Grid card text was found."
    }

    return $first[0].Node
}

function Assert-GridSnapshotStable {
    param(
        [object[]]$Before,
        [object[]]$After,
        [string]$Stage,
        [int]$MinimumCards = 9,
        [int]$MaxPositionDeltaPx = 8
    )

    $count = [Math]::Min($Before.Count, $After.Count)
    if ($count -lt $MinimumCards) {
        throw "$Stage did not expose enough comparable Grid cards. Before=$($Before.Count) After=$($After.Count)"
    }

    for ($index = 0; $index -lt $MinimumCards; $index++) {
        $beforeCard = $Before[$index]
        $afterCard = $After[$index]
        if ($beforeCard.Text -ne $afterCard.Text) {
            throw "$Stage changed Grid card order at position $index. Before='$($beforeCard.Text)' After='$($afterCard.Text)'"
        }

        $leftDelta = [Math]::Abs([int]$beforeCard.Left - [int]$afterCard.Left)
        $topDelta = [Math]::Abs([int]$beforeCard.Top - [int]$afterCard.Top)
        if ($leftDelta -gt $MaxPositionDeltaPx -or $topDelta -gt $MaxPositionDeltaPx) {
            throw "$Stage moved Grid card '$($beforeCard.Text)' too far. LeftDelta=$leftDelta TopDelta=$topDelta"
        }
    }
}

Export-ModuleMember -Function @(
    "Get-UiTextNodes",
    "Get-VisibleTexts",
    "Find-NodeByText",
    "Find-NodeByExactText",
    "Get-Bounds",
    "Get-ScreenSize",
    "Find-BottomNavNodeByText",
    "Assert-TextPresent",
    "Assert-TextAbsent",
    "Assert-NoAppErrorText",
    "Get-GridCardSnapshot",
    "Get-FirstGridCardNode",
    "Assert-GridSnapshotStable"
)
