# Pynapli Design Spec

Visual direction, navigation and token rules for the Compose Multiplatform UI (`:composeApp`, and
Compose code in `:webApp` / `:desktopApp`). Merged on 2026-11-17 from `DESIGN_AND_NAVIGATION_SPEC.md`,
`FRONTEND_DESIGN_DIRECTION_SPEC.md` and `DESIGN_TOKENS.md`; code comments that cite those names
mean this file. Wide-window layout rules live in [WEB_PWA_GAMEPLAN.md](WEB_PWA_GAMEPLAN.md).

> Pynapli should feel native, dense, private, restrained and premium: a private members' app, not a
> marketing page, a generic Material demo, or a stretched web card stack.

## Tokens: code is the source of truth

Token **values** live in code, not here:

- Colors: `design/PynapliColors.kt` (`PynapliColors` dark palette is the default;
  `PynapliColorsLight`; roles in `PynapliSemantic`). The Material `ColorScheme` in
  `PynapliTheme.kt` is **derived from** these objects and must not re-hardcode hexes.
- Spacing, layout, widths, header, shapes, type, sheets, motion, grid tile and swipe card:
  `design/PynapliDimens.kt` (`PynapliSpacing`, `PynapliLayout`, `PynapliContentWidth`,
  `PynapliHeaderTokens`, `PynapliShape`, `PynapliType`, `PynapliSheetTokens`, `PynapliMotion`,
  `GridTileTokens`, `SwipeCardTokens`).
- The marketing site mirrors the load-bearing hexes in `web/src/styles/global.css`;
  `scripts/check-web-tokens.ps1` fails when they diverge from `PynapliColors.kt`.

Rules:

- **Feature code consumes tokens.** Radii and colors must be tokens, never literals. Enforced by
  `scripts/check-compose-tokens.ps1` over `composeApp/src`, `webApp/src` and `desktopApp/src`
  (excluding `design/` and test source sets): it fails on `RoundedCornerShape(<number>)`, raw
  `Color(0x…)`, and bare `Color.White/Black/...`. The escape hatch is a `token-guard:allow` comment
  on or above the line, for values that are genuinely not a palette or shape decision.
- **Pick a `PynapliShape` by the element's role:** `SmallShape` grid tiles and indicators,
  `PhotoShape` photos and thumbnails, `CardShape` cards, banners, rows and inputs, `SheetShape`
  sheets (`DialogShape` for their wide-window dialog form), `PillShape` chips.
- **Accent is pineapple gold `PynapliColors.Accent` (`#FFC928`, founder-locked).** Route every use
  through that token so a swap is one edit. Gold is a premium/highlight accent, never the whole UI.
  Green (`PynapliSemantic.Online`) is only for online/available signals.
- `PynapliColors.TextTertiary` is `#8A847C`, a founder-approved deviation for AA contrast. Don't
  revert it.
- **Spacing is deliberately not on a fixed scale.** Gaps are hand-tuned (8dp and 12dp dominate).
  Use `PynapliSpacing` / `PynapliLayout` where a shared rhythm applies (screen padding is
  `PynapliLayout.ScreenPadding`), hand-tune inside components, don't snap existing screens to a
  scale, and **don't** add a spacing rule to the token guard. The Grid stays denser on purpose.
- Type: neutral sans-serif, `sp` units, no negative letter spacing, no viewport-scaled type,
  compact labels on dense surfaces; large text only for onboarding, match and major empty states.

## Pointer, keyboard and wide windows

- Anything a pointer can activate carries `Modifier.handCursor(enabled = <its enabled>)`
  (`design/PynapliPointer.kt`). The token guard fails a clickable modifier
  (`clickable`/`combinedClickable`/`selectable`/`toggleable`) or a clickable Material control
  without it. Scrims (`indication = null`) are exempt.
- Full-screen surfaces close on Esc with `Modifier.closeOnEscape` (`design/PynapliEscape.kt`), only
  while the surface itself has focus.
- New chips and small icon buttons use `Modifier.wideChipHeight()` / `pynapliIconButtonSize(...)`
  (`design/PynapliWideControls.kt`) so they draw at 40dp on Medium+ windows; phones keep the compact
  size.

## Sheets and modals

Every modal goes through `design/PynapliSheet.kt` (`PynapliBottomSheet` / `PynapliSheetScaffold`).
Never hand-roll a `ModalBottomSheet`. On Medium+ windows the same sheet presents per
`PynapliWideSheetPresentation`: a centered dialog by default, a right-anchored side panel for the
profile peek.

**Full-screen profile on phones (Grid only).** A profile opened from the Grid uses
`PynapliBottomSheet(compactFullScreen = true)`: edge to edge, square corners, no drag handle, the
scaffold at full height; back, the close button and a downward drag still close it. A horizontal
swipe moves to the next or previous person, so photos on those pages change by tapping the left or
right half, with the pips kept. On Medium+ windows it stays the side panel, with Previous/Next above
the profile and the Left/Right arrow keys. Swipe, Activity and Messages keep the regular peek.

## Navigation

- Bottom navigation (Compact) / navigation rail (Medium+) tabs: **Grid, Swipe, Activity, Messages**.
- **No Store tab** (founder decision 2026-11-17; the earlier "five locked tabs" rule is reversed).
  Sell from context: every paywall opens the same plans/store sheet (`store/StorePaywallSheet.kt`,
  a `PynapliBottomSheet`; a centered dialog on Medium+), scrolled to what it sells
  (`StorePaywallFocus`: plans, the Select plan, or Pineapple Likes). The profile menu's
  Subscription section has a **Plans and store** row. `/store` and store deep links open the sheet;
  browser back closes it.
- Profile and settings open from the header avatar. **Profile is never a bottom tab.**
- Header structure (`design/PynapliScreenHeader.kt`, `PynapliHeaderTokens`): left avatar, center
  context/location/search pill, right context action. Use the shared header slots; don't hand-roll
  per-surface headers.
- State is preserved per tab (Grid scroll and filters, Swipe deck, Activity tab, Messages list).
  Opening a profile, settings or detail view must not destroy the tab underneath. **Grid
  profile open/close returns to the exact same `LazyGridState` scroll position**; don't refetch or
  reorder unless the user refreshes or state expires.
- Deep links require auth where needed, are held until the user is signed in, and land on a
  neutral empty state if the target is stale or deleted.
- No tab-switch animation.

## Surfaces

| Surface | Density | Rules |
| --- | --- | --- |
| Grid | Highest | Photo fills the tile; short functional bottom gradient; name, online dot, a small verified check after the name for photo-verified people, distance, linked-partner mini icon (with a small verified check on the mini when the couple is verified: both partners photo-verified). The only other marks allowed, each a product feature: the Playing Tonight moon and a small gold "New" tag (the server's Fresh rule) top-left, the Visiting pill in the caption row, and a one-line "Active 15m ago / 1h / 3h / today / this week" caption above the name for people not online now. **No other tags or chips on tiles**; filters carry the complexity. No photo: one neutral raised surface with a person silhouette, never a colour chosen by list position. Tight gap (~1dp, `GridTileGap`). Filter changes animate items in/out/around instead of blanking the grid. Loading is incremental. |
| Swipe | Low, high focus | One person at a time, photo-first, name/age/distance, linked partner shown as context (never a couple card), one short bio line, details on expand. Physical gesture feel. No messaging unless matched (Pineapple Like with an optional note is the paid exception; Intro Notes were merged into it). |
| Activity | Medium-high | Newest first, compact rows: avatar, action, timestamp, partner icon. Gated rows tease without feeling scammy; blur and lock states stay readable. |
| Messages | Medium | Scannable inbox, clear sent/received alignment, timestamp and state without clutter, neutral system messages. Partner invite-to-chat states are explicit; private album interactions are consent-based; safety/report actions reachable without clutter. |
| Profile and settings | Medium-low | Calm, organized, never a marketing surface. Account, privacy, subscription, partner link, safety, albums and app management easy to find. |
| Store sheet and paywalls | Medium | Tiers understandable at a glance; linked-partner benefit prominent; add-ons clear and optional. **No fake scarcity or countdown pressure.** Same subdued visual language. |

## Motion

Motion clarifies state; it never decorates or delays. Allowed: swipe drag/spring-back/fly-out,
grid insert/remove/move, sheet open/close, match moment, partner invite and album request state
changes, a subtle Playing Tonight pulse, Activity rows entering after refresh. Not allowed: tab-switch
animation, constant decorative motion, animated backgrounds, motion that shifts layout. Respect
reduced-motion settings where the platform exposes them.

## Interaction and accessibility

- 48dp minimum touch targets (`PynapliLayout.MinTouchTarget`); icon buttons have stable sizes and
  content descriptions for meaningful icons.
- WCAG AA contrast. Text must not overflow buttons, cards, rows, sheets or headers at supported font
  sizes; dynamic text size must not cause overlap.
- Destructive actions require confirmation (`design/PynapliConfirmDialog.kt`). Important actions get
  haptic feedback where the platform supports it.
- Background refresh never moves cards or jumps the user to the top: a quiet Grid refresh updates
  presence in place and holds new people behind an "N new nearby" pill. Applying a new order (the
  pill, pull-to-refresh, the wide-window Refresh button) returns to the top. No full-screen spinner
  over cached content.
- Every production screen has empty, loading, error, offline and gated states, and works with
  location and notification permissions denied.

## Do not

- Make Profile a bottom tab.
- Put tags or chips on grid tiles beyond the Tonight moon, New tag, Visiting pill and active caption.
- Hardcode hex values or corner radii in feature code.
- Use negative letter spacing or viewport-scaled type.
- Use oversized radii, cards inside cards, heavy drop shadows, or generic rounded panels everywhere.
- Add decorative gradient orbs/blobs, loud gradients, neon or nightclub styling, cheesy pineapple
  overload, or flashy VIP treatment.
- Reset the Grid when a profile closes.
- Animate tab switching.

## Checklist

- [x] Tokens exist and the token guard runs in CI and every verify gate.
- [x] Grid tile follows the minimal tile rules; Swipe card follows the richer card rules.
- [x] Activity rows support gating.
- [x] Message bubbles support view-once media reveal (`POST /v1/messages/{id}/reveal`).
- [x] Avatar opens the profile hub; unified header system (`PynapliScreenHeader.kt`).
- [x] Every modal goes through `PynapliSheet.kt`.
- [ ] Partial: per-tab state is retained in state holders; full navigation regression coverage is
  missing.
- [ ] Partial: deep-link commands are held in `AppDeepLinkStateHolder` until consumed, so they
  survive login; the stale-target empty state is not verified on every target.
- [ ] Accessibility pass for touch targets and text overflow.
- [x] Store tab replaced by the store sheet, opened from every paywall and a profile-menu row (founder decision 2026-11-17).
