# composeApp — design rules

Screens consume UI state + callbacks; state holders live in `:shared` unless platform constraints force otherwise. These design rules also apply to Compose code in `:webApp`.

Grid profile open/close must return to the exact same `LazyGridState` scroll position; don't refetch/reorder unless the user refreshes or state expires.

## Design north-star (load-bearing)

The visual target is the Claude design handoff at `C:\Users\Smaye\Downloads\Pynapli-Handoff` (JSX prototype + screenshots + the Messages/Tonight Map HTML specs; token values in `components/primitives.jsx`). The founder confirmed it is a guide, not the bible: it refines the existing design language rather than replacing it. The current palette lives in `PynapliColors`; when restyling, move tokens toward the handoff values.

**Accent = pineapple-gold `#FFC928`** (founder-locked 2026-05-31). Keep accent routed through the single token `PynapliColors.Accent` so any future swap is one edit, and use gold as a premium/highlight accent rather than flooding it across the UI.

**Spacing is deliberately not on a fixed scale** (settled 2026-07-23, don't re-litigate). The handoff has no spacing scale — its gaps and paddings are hand-tuned across ~25 values, and the app matches (~700 call sites, 8 and 12 dominant). Snapping to an 8dp/4dp grid would re-rhythm every screen with no defect to justify it. Use `PynapliSpacing`/`PynapliLayout` where a shared rhythm genuinely applies (screen padding is `PynapliLayout.ScreenPadding`), hand-tune inside components, and **do not** add a spacing rule to the token guard. Section rhythm is 8dp or 12dp; the Grid stays denser on purpose.

Radii and colors **must** be tokens, not literals — enforced by `scripts/check-compose-tokens.ps1` (its header lists the rules and the `token-guard:allow` escape). It scans `composeApp/src`, `webApp/src` and `desktopApp/src`, and runs in CI plus `verify-full`/`-android`/`-web`/`-desktop`. Pick a `PynapliShape` by the element's *role*: `SmallShape` grid tiles/indicators, `PhotoShape` photos/thumbnails, `CardShape` cards/banners/rows/inputs, `SheetShape` sheets, `PillShape` chips.

Everything else in `docs/FRONTEND_DESIGN_DIRECTION_SPEC.md` / `docs/DESIGN_AND_NAVIGATION_SPEC.md` still holds (dark-first, dense Grid, restrained, no decorative blobs). Update visual-regression goldens deliberately when restyling.
