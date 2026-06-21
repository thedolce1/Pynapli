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
- Next: 2b cont. — extend the skeleton loaders to Activity/Messages first-load (reuse
  `PynapliSkeletonBlock` in list-row shape); 2c — one shared error+retry primitive (unify Grid
  error + Swipe `SwipeError` + scattered inline errors).
