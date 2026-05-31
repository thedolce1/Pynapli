# Pynapli Design And Navigation Spec

Last updated: 2026-04-24

## Purpose

This document captures reusable frontend design and navigation rules for the Compose Multiplatform app.

Core rule:

> Pynapli should feel dense, polished, private, and social, not like a marketing landing page or generic Material app.

For higher-level visual taste, native feel, motion philosophy, and anti-patterns, use [FRONTEND_DESIGN_DIRECTION_SPEC.md](FRONTEND_DESIGN_DIRECTION_SPEC.md) alongside this document.

## Design Philosophy

Use Material 3 as engineering infrastructure, but override visible surfaces with Pynapli-specific design tokens and components.

Principles:

- Native Kotlin/Compose feel, not generic cross-platform styling.
- Dense but readable.
- Minimal grid cards.
- Richer swipe cards.
- Quiet, privacy-conscious settings.
- Strong touch targets.
- No decorative clutter.
- No cheesy lifestyle visual language.

## Tokens

Use tokens for:

- Color.
- Typography.
- Spacing.
- Shapes.
- Motion durations.
- Elevation/borders.

Do not hardcode colors or dimensions directly in feature composables.

## Typography

Recommended:

- Inter or a similarly neutral, highly legible sans-serif.
- No negative letter spacing.
- No viewport-scaled font sizes.
- Compact labels for grid/message surfaces.
- Larger display text only for true match/onboarding moments.

## Shapes

Use restrained radii:

- Grid tiles: square image, minimal or no visible corner radius depending final visual direction.
- Cards: 8dp max unless a platform/component reason requires otherwise.
- Buttons/pills: use familiar shapes, but avoid over-rounded text pills when an icon button is more appropriate.
- Sheets/modals can use slightly larger top corners if consistent with platform expectations.

## Spacing

Use an 8dp base grid:

- 4dp micro.
- 8dp small.
- 16dp standard.
- 24dp large.
- 32dp section.

Grid tile gap:

- Very tight, approximately 1-2dp.

## Core Components

### Grid Tile

Rules:

- Photo fills the tile.
- Minimal bottom gradient.
- Display name.
- Online dot.
- Distance.
- Linked-partner mini icon if linked.
- No tags/chips.

### Swipe Card

Rules:

- Photo-heavy.
- Name, age, distance.
- Linked-partner preview.
- One short bio line.
- Expanded tags only on tap/expand.

### Activity Row

Rules:

- Avatar.
- Action text.
- Timestamp.
- Linked-partner mini icon if relevant.
- Gated blur where applicable.

### Message Bubble

Rules:

- Clear sent/received alignment.
- Timestamp and state available without clutter.
- Media reveal state supported.
- System messages neutral.

## Motion

Use motion for state clarity, not decoration.

Allowed:

- Swipe card spring-back/fly-out.
- Grid filter insert/remove/move animation.
- Match animation.
- Subtle Playing Tonight pulse.
- Bottom sheet transitions.

Avoid:

- Tab-switch animation.
- Constant decorative motion.
- Background orbs/blobs.
- Motion that shifts layout.

## Accessibility

Requirements:

- 48dp minimum touch targets.
- Content descriptions for meaningful icons.
- Contrast compliant in light/dark.
- Text must not overflow buttons/cards.
- Dynamic text size handled without overlap.
- App works with location/notification permissions denied.

## Navigation

Bottom nav:

- Grid.
- Swipe.
- Activity.
- Messages.
- Store.

Profile/settings:

- Accessed from avatar in header.
- Not a bottom tab.

Header:

- Left: avatar.
- Center: context/location/search pill.
- Right: context action.

## Navigation State

Preserve state per tab:

- Grid scroll/filter state.
- Swipe deck state.
- Activity tab/filter state.
- Messages scroll/thread list state.
- Store state.

Opening profile/settings/detail views should not destroy underlying tab state.

Deep links:

- Require auth if needed.
- Hold pending destination through login.
- If target is stale/deleted, show neutral empty state.

## Implementation Checklist

- [x] Design tokens exist.
- [x] Grid tile component follows minimal card rules.
- [x] Swipe card component follows richer card rules.
- [x] Activity row component supports gating.
- [ ] Message bubble supports media reveal.
- [x] Bottom nav uses five locked tabs.
- [x] Avatar opens settings hub.
- [ ] Partial: per-tab state is retained in current Compose state holders, but full navigation regression coverage is missing.
- [ ] Deep links respect auth/stale state.
- [ ] Accessibility pass for touch targets and text overflow.

## Do Not Implement

- Do not make Profile a bottom nav tab.
- Do not clutter grid tiles with tags.
- Do not use negative letter spacing.
- Do not use oversized card radii by default.
- Do not add decorative gradient orbs/blobs.
- Do not reset Grid when closing a profile.
- Do not animate tab switching.
