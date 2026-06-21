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
- Next: Phase 1 — motion system.
