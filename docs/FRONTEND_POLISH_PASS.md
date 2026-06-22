# Frontend Polish Pass — to enterprise-grade

Structured, phased plan to take the Pynapli frontend from "mature MVP" to enterprise-grade
production-ready, **within the locked design language** (dark-first, restrained, dense Grid,
pineapple-gold accent routed through `PynapliColors.Accent`, handoff tokens). This refines the
existing surfaces; it does not introduce a new aesthetic. See
`FRONTEND_DESIGN_DIRECTION_SPEC.md` / `DESIGN_AND_NAVIGATION_SPEC.md` for the direction and
`docs/IMPLEMENTATION_AUDIT.md` for the running slice log.

## Baseline (already strong)

Single token source (`PynapliColors` → Material scheme), unified header (`PynapliScreenHeader`)
and confirm-dialog (`PynapliConfirmDialog`) systems, shared sheet scaffold (`PynapliSheet`),
loading states present, pervasive empty/error state logic, and a connected, ANR-resilient QA
harness (`scripts/android-*-qa.ps1`).

## The five gaps to close

1. **Motion is nearly absent** — almost no `AnimatedVisibility` / `animate*AsState` / `Crossfade`
   in `commonMain`. The app feels static.
2. **Typography incomplete** — system `FontFamily.SansSerif` (no brand face); only 5 of ~15 type
   roles defined, so `titleLarge`/headlines inherit raw Material3 defaults.
3. **Token leakage** — residual hardcoded `Color(0x…)` in feature screens (Swipe card, Grid card,
   Activity, Tonight, Profile preview) breaks the one-token-edit guarantee.
4. **States functional, not designed** — empty states are plain text; loading is bare spinners,
   not skeletons; error handling is scattered.
5. **No formal accessibility pass** — sub-48dp touch targets, no semantic grouping for cards,
   font-scale untested.

## Phases (each ships as CI-gated slices; pause at phase boundaries)

### Phase 0 — Foundations
- **0a Type scale**: define every Material type role in `PynapliTypography` with a deliberate,
  compact dark-first scale (font-family controlled, ready for a brand-font swap). Founder call on
  a bundled brand display face vs. tuned system sans (keep the APK lean for low-end devices).
- **0b Token sweep**: move residual on-photo / scrim / decorative `Color(0x…)` literals into
  `PynapliColors` (on-photo overlay + scrim groups); align on-photo text to `OnPhotoPrimary`.

### Phase 1 — Motion system
`PynapliMotion` tokens (durations, easing, springs) + high-impact moments only: bottom-nav
content crossfade, staggered first-load reveals (Grid/Activity/Messages), loading→content
crossfade, animated count/badge/online-dot changes, consistent press feedback, sheet spring.
Honor reduce-motion; keep cheap for low-end devices.

### Phase 2 — Designed states
Shared `PynapliEmptyState` (icon + headline + subtext + CTA) for empty Grid/Swipe/Activity/
Messages/Albums; skeleton/shimmer loaders matching content shape (reuse `GradientPhotoPlaceholder`);
one shared error+retry primitive.

### Phase 3 — Accessibility
TalkBack labels on every interactive control; semantic grouping so a card reads as one node +
state descriptions (online/unread/selected); touch targets ≥48dp (tile buttons are 28dp);
font-scale 1.3–2.0× safety; contrast check on tertiary / on-photo text.

### Phase 4 — Surface-by-surface refinement
One slice per surface (Grid → Swipe → Activity → Messages → Store → Profile/Albums → Onboarding →
Tonight → Psync): spacing rhythm, density, hierarchy, accent restraint, hero/card treatments.
Regenerate visual goldens deliberately.

### Phase 5 — Cross-platform parity (later)
Verify the Compose surfaces hold up on the web/Wasm PWA and iOS targets the handoff also covers.

## Definition of done (per surface)

Designed loading + empty + error states · meaningful motion · token-only colors · complete type
scale · TalkBack-labeled + 48dp targets + font-scale-safe · emulator-QA green.

## Progress

- 2026-06-21 — **Phase 0 complete.** 0a: full type scale in `PynapliTypography`. 0b: token sweep —
  added on-photo overlay + scrim + warm-placeholder groups to `PynapliColors`; replaced residual
  `Color(0x…)` literals and aligned on-photo text to `OnPhotoPrimary` across Swipe card, Grid card,
  Activity, and the private-album preview. (Tonight's 3 zone-map literals deferred to its Phase 4
  surface slice.) verify-full green; Swipe/Grid/auth eyeballed on-device.
- 2026-06-21 — **Phase 1 (motion) started.** 1a: extended `PynapliMotion` with easing + stagger +
  `standardTween`/`gentleSpringFloat`, added the `Modifier.pynapliEntrance` staggered-reveal helper,
  applied a low-end-safe staggered first-load reveal to the Grid, and a scale+fade enter to the
  swipe match overlay. verify-full green; grid settles correctly on-device.
- 2026-06-21 — Phase 1b: extended the staggered reveal to the **Activity feed** and **Messages
  inbox** lists (same `pynapliEntrance` helper; `ConversationRow`/`ActivityRow` gained a modifier
  param). Consistent reveal language now across Grid / Activity / Messages. verify-full green;
  Messages settles correctly on-device.
- 2026-06-21 — Phase 1c: animated bottom-nav badge — `popSpringFloat` token + an Animatable
  scale "pop" when a tab's badge appears or its count increments (Messages unread / Activity).
- 2026-06-21 — Phase 1d: loading→content crossfade — wrapped the Messages state machine
  (loading/error/empty/content) in a `Crossfade` so the spinner dissolves as the inbox arrives
  (it coexists with the staggered reveal: spinner fades out, rows stagger in). Pattern is
  extendable to Grid/Activity/Store. verify-full green; Messages renders correctly on-device.
- 2026-06-21 — **Phase 2 (designed states) started.** 2a: added a shared `PynapliEmptyState`
  primitive (tinted icon disc + title + optional subtitle + optional CTA, gentle entrance) and
  refactored the Messages and Activity empties onto it. Copy preserved (QA-safe). verify-full green.
- 2026-06-21 — Phase 2b: **skeleton loaders + Grid empty unification.** Added a shimmer primitive
  (`PynapliSkeleton`: `rememberPynapliShimmerBrush` + `PynapliSkeletonBlock`; one cheap infinite
  float, token-only colors, low-end-safe) and a `GridLoadingSkeleton` that mirrors `ProfileGrid`
  (3-col square shimmer tiles), replacing the Grid's bare loading spinner. Routed the Grid
  filters-empty onto `PynapliEmptyState` (copy preserved) and wrapped the whole `GridBody` state
  machine in a loading→content `Crossfade` (extends the Messages 1d pattern; phase stays `Content`
  across profile open/close so grid scroll restoration is untouched). Audited the remaining empties:
  Swipe's `EmptyDeck`/`SwipeError` are already designed (icon + title + subtitle + dual actions —
  converting would lose the two-button logic, so left as-is); album/grants "nothing here" lines are
  inline notes, not full-screen empties (left inline). verify-full green; grid-QA green (60 cards,
  filters).
- 2026-06-21 — Phase 2b cont.: **skeleton loaders on Activity + Messages.** Generalized the grid
  skeleton into reusable `PynapliGridSkeleton(columns, rows, aspect, shape, gap, padding)` +
  `PynapliListSkeleton` (avatar + two text lines) in the design layer; `GridLoadingSkeleton` now
  delegates to it. Messages first-load shows a list skeleton; Activity first-load is tab-aware (a
  2-up grid skeleton for the FOMO tabs, a list skeleton for Requests), both inside the existing
  Crossfade so they dissolve into content. Reconciled the long-stale `android-activity-qa-goldens.json`
  to the #98 four-tab FOMO structure (added `viewsTab`/`requestsTab`, dropped the list-only `Message`
  text from `matchesTab`, removed dead stages) — activity QA had been red on main since #98.
  verify-full green; activity-QA + messages-QA green.
- 2026-06-21 — **Phase 2c: shared error+retry primitive — Phase 2 complete.** Added `PynapliErrorState`
  (tinted icon disc + title + message + Retry, sharing one private `PynapliStatusBlock` layout with
  `PynapliEmptyState` so empty and error states read as siblings). Routed every full-surface load
  failure through it: Grid error, Swipe `SwipeError`, Messages error, Activity error — replacing the
  hand-rolled columns and bare red server strings with a consistent retry affordance. verify-full
  green; grid/swipe/activity/messages QA green.
- **Phase 2 done — pausing at the phase boundary.** Designed states now: shared empty primitive,
  content-shaped skeleton loaders, loading→content crossfades, and a unified error+retry block across
  the core surfaces. Next phase (3, accessibility) is the boundary to resume at: TalkBack labels,
  semantic card grouping + state descriptions, ≥48dp touch targets, font-scale 1.3–2.0× safety,
  on-photo/tertiary contrast.
- 2026-06-21 — **Phase 3 (accessibility) started.** 3a: the dense Grid tile now reads as a single
  TalkBack item — `semantics(mergeDescendants = true)` groups the photo + name + presence + visiting
  + distance + partner/tonight badges into one node, with `role = Role.Button` and an "Open profile"
  click label; `OnlineDot` gained an optional `contentDescription` so the otherwise-silent presence
  dot announces "Online" inside the merged card. Chose `mergeDescendants` over `clearAndSetSemantics`
  so child text + testTags survive in the unmerged tree (keeps `GridCardTonightIndicatorTest` and the
  content-desc-aware QA matchers working). verify-full green; grid-QA green (card discovery + tap-open
  intact through the merged node). All four core QA scripts are content-desc-aware, so the pattern
  extends safely.
- 2026-06-21 — Phase 3b: **inbox + activity items read as single nodes.** Messages `ConversationRow`
  (role Button + "Open conversation" label, unread badge announces "N unread" via
  `clearAndSetSemantics`, the avatar online dot announces "Online"), Activity FOMO tiles (role Button +
  "Open profile" label), and the Activity list row (grouped for reading) all use
  `semantics(mergeDescendants = true)` so each reads as one item while nested controls (accept/decline,
  review/invite, dismiss) stay their own focusable buttons. verify-full green; activity-QA + messages-QA
  green (golden text + tab nav + row taps intact through the merged nodes).
- 2026-06-21 — Phase 3c: **Swipe card identity reads as one node.** The card footer
  (`semantics(mergeDescendants = true)`) now reads as a single item (name + presence + verified +
  visiting + age + distance + bio + tags); `SwipeOnlineDot` announces "Online". Audited the action
  bar — Rewind/Pass/Intro Note/Pineapple Like/Like already carry `contentDescription`s and the
  verified badge says "Verified", so the accessible swipe actions were already complete. verify-full
  green; swipe-QA green (golden text + drag/gesture flow intact through the merged footer). **The
  "every card reads as one node + state descriptions" item is done across all four core surfaces
  (Grid / Swipe / Activity / Messages).**
- 2026-06-21 — Phase 3d: **≥48dp touch targets.** Applied `minimumInteractiveComponentSize()` to the
  shared header primitives (`PynapliHeaderAvatar` 42dp, `PynapliHeaderActionButton` 44dp — every
  screen's header) and the Activity dismiss controls (FOMO tile 28dp, list-row 36dp), reserving a
  ≥48dp touch target while keeping the founder-locked compact visuals unchanged. verify-full green;
  grid/swipe/activity/messages QA green (headers tap correctly through the expanded targets).
- 2026-06-21 — Phase 3e: **contrast + font-scale — Phase 3 complete.** Contrast: lifted the dimmest
  text token `TextTertiary` from the handoff's `#6B6660` (~3.4:1 on `#0B0B0E`, fails WCAG AA normal)
  to `#8A847C` (~5:1, passes AA) — a deliberate, founder-approved a11y deviation from the handoff
  (same warm hue, one step lighter). Font-scale: verified all four core surfaces on-device at 2.0×
  (`settings put system font_scale 2.0`) — Grid names ellipsize, Activity FOMO tiles / Messages rows
  grow to fit, the Swipe footer + action bar hold, no clipping or overflow; the only effect is
  bottom-nav labels wrapping, which is standard Material `NavigationBar` behavior at extreme scale, so
  no code fix was needed (screenshots in `artifacts/a11y-fontscale/`). verify-full green; grid/swipe/
  activity/messages QA green.
- **Phase 3 (accessibility) done — pausing at the phase boundary.** Every compound item reads as one
  TalkBack node with state descriptions, all interactive controls meet a ≥48dp touch target, the
  dimmest text meets WCAG AA, and the core surfaces survive 2.0× font scale. Next phase to resume at:
  Phase 4 (surface-by-surface refinement) or Phase 5 (web/iOS parity).
- 2026-06-22 — **Phase 4 (surface-by-surface) started — Grid.** Grid was already heavily
  handoff-aligned (dense tiles, token-clean, a11y'd, goldens that deliberately lock its accent), so
  the one concrete gap vs the handoff `Grid.jsx` was the **density/context strip** under the
  quick-filter row — added `GridContextStrip` (`{count}` bold + `NEARBY` + the loaded cards' distance
  range, all restrained tertiary meta), shown only when cards are present. The handoff's bottom
  "Playing Tonight" inline banner was left to the Tonight surface slice (overlaps the active Playing
  Tonight initiative). verify-full green; grid-QA green.
- 2026-06-22 — Phase 4 (Swipe). Diffed against handoff `Swipe.jsx`: the hero card used
  `RoundedCornerShape(8.dp)` (hardcoded) but the design north-star + handoff specify **16** — fixed by
  bumping the previously-unused `SwipeCardTokens.Radius` 8→16, adding `SwipeCardTokens.Shape`, and
  routing the card's clip + border through it (north-star compliance + token cleanup). Also
  de-emphasized the age beside the name (OnPhotoSecondary + Normal weight) to match the handoff's
  bold-name / light-age hierarchy. (Handoff's `distance · hood · Online` meta needs `neighborhood` on
  `SwipeCardDto` — a server change, deferred.) verify-full green; swipe-QA green.
- 2026-06-22 — Phase 4 (Activity). Activity was founder-restructured to 4 tabs (#98) after the
  handoff, so it's structurally past the handoff's 3-tab `Other.jsx`; the one carry-over worth keeping
  is the handoff's per-tab `GridSectionHeader` (count + label). Added `ActivityContextStrip` — a
  restrained `{revealed+gated} {TAB LABEL}` count under the tab row, styled to match the Grid's
  `GridContextStrip` for cross-surface cohesion and to reinforce the FOMO model (high total, most
  gated). Shown only when the tab has items. verify-full green; activity-QA green.
- 2026-06-22 — Phase 4 (Messages). Messages was already heavily polished (bubbles #137, avatars,
  new-matches rail #101, Phase 2/3), so this was a tight polish pass: (1) the header subtitle is now
  pluralized and drops the unread clause at zero ("6 conversations" rather than "6 conversations · 0
  unread"); (2) completed the Phase 3 a11y coverage the New Matches rail had missed — each rail avatar
  now reads as one labeled item (role Button + "Open conversation", online dot announces "Online",
  decorative initials glyph suppressed from semantics). verify-full green; messages-QA green.
- 2026-06-22 — Phase 4 (Store) — **core loop complete.** Store was already token-clean (#138 cards +
  flagship "Recommended" hero), so the one handoff `StoreScreen` element it lacked was the dedicated
  **linked-partner benefit callout** ("One subscription · two people" — the core Pynapli
  differentiator), previously surfaced only as a perk line. Added `StoreLinkedPartnerBenefitRow`
  (accent link-icon disc + headline + subtext on the shared raised-card treatment) as the final Store
  item. verify-full green; store-QA green. **All five core-loop surfaces (Grid / Swipe / Activity /
  Messages / Store) are now Phase 2 + 3 + 4 complete.**
- Next (Phase 4): the differentiators + remaining surfaces — Profile/Albums → Onboarding → Tonight
  (incl. its deferred zone-map color literals) → Psync, one slice each.
