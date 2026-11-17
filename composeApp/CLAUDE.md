# composeApp — design rules

Screens consume UI state + callbacks from feature state holders, which live here in `:composeApp` next to their UI models (every client shares this module, and `:server` depends on `:shared`, so moving them would put UI state on the server classpath). Product rules still belong in `:shared`/`:server`, not in holders. These design rules also apply to Compose code in `:webApp` and `:desktopApp`. Full design spec: `docs/DESIGN_SPEC.md`.

Grid profile open/close must return to the exact same `LazyGridState` scroll position; don't refetch/reorder unless the user refreshes or state expires.

Every modal goes through `design/PynapliSheet.kt`; never hand-roll a `ModalBottomSheet`.

**Accent = pineapple-gold `#FFC928`** (founder-locked). Keep accent routed through the single token `PynapliColors.Accent` so any future swap is one edit, and use gold as a premium/highlight accent rather than flooding it across the UI. `TextTertiary` `#8A847C` is a founder-approved AA deviation; don't revert it.

**Spacing is deliberately not on a fixed scale** (settled; don't re-litigate). Use `PynapliSpacing`/`PynapliLayout` where a shared rhythm genuinely applies (screen padding is `PynapliLayout.ScreenPadding`), hand-tune inside components, and **do not** add a spacing rule to the token guard. Section rhythm is 8dp or 12dp; the Grid stays denser on purpose.

Radii and colors **must** be tokens, not literals — enforced by `scripts/check-compose-tokens.ps1` (its header lists the rules and the `token-guard:allow` escape). It scans `composeApp/src`, `webApp/src` and `desktopApp/src`, and runs in CI plus `verify-full`/`-android`/`-web`/`-desktop`. Pick a `PynapliShape` by the element's *role*: `SmallShape` grid tiles/indicators, `PhotoShape` photos/thumbnails, `CardShape` cards/banners/rows/inputs, `SheetShape` sheets, `PillShape` chips.

Anything a pointer can activate carries `Modifier.handCursor(enabled = <its enabled>)` (design/PynapliPointer.kt); the same guard fails a clickable modifier or clickable Material control without it. Full-screen surfaces close on Esc with `Modifier.closeOnEscape` (design/PynapliEscape.kt). New chips or small icon buttons take `Modifier.wideChipHeight()` / `pynapliIconButtonSize(...)` (design/PynapliWideControls.kt) so they draw at 40dp on tablets and computers; phones keep the compact size.

Update visual-regression goldens deliberately when restyling (they are loose luminance ratios, not pixel goldens).
