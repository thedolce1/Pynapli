# Pynapli Frontend Design Direction Spec

Last updated: 2026-04-24

## Purpose

This document defines the visual and interaction direction for Pynapli's Kotlin Multiplatform + Compose frontend.

Core rule:

> Pynapli should feel native, classy, subdued, sexy, premium, and private. It should never feel like a generic React Native dating app template.

This spec is a product constraint. Feature teams should use it when deciding layout, components, motion, density, and visual polish.

## Product Feel

Pynapli should feel like:

- A private members social app.
- A polished location-based lifestyle app.
- A premium, adult, modern mobile experience.
- Dense where speed matters.
- Calm where trust and privacy matter.
- Confident without being loud.

Pynapli should not feel like:

- A nightclub flyer.
- A hookup spam app.
- A casino-styled swipe game.
- A web view inside a mobile shell.
- A generic Material demo.
- A copied Tinder or Grindr skin.
- A React Native card stack with oversized rounded panels.

## Kotlin And Compose Advantages

Use Kotlin Multiplatform and Compose to make the app feel deeply native:

- Real Compose gesture handling for swipe, drag, sheets, and scroll.
- Stable lazy grids and lazy lists with item keys.
- Native-feeling scroll physics per platform.
- Fast state-driven UI updates without full screen refreshes.
- Tactile haptics for meaningful actions.
- Platform-specific permission and media flows where needed.
- Smooth shared transitions between grid tile, profile, chat, and album surfaces.
- Local caching so core surfaces appear immediately.

The frontend should not chase web layout patterns. Compose should be used to build mobile-native surfaces with tight state control, predictable recomposition, and polished transitions.

## Visual Direction

### Dark First

Dark mode is the default Pynapli mood.

Use charcoal, ink, graphite, muted raised surfaces, soft borders, and warm restrained accents. Avoid pure black everywhere unless it helps photography, privacy, or battery life.

### Premium Restraint

The app should use:

- Tight spacing.
- Clear hierarchy.
- Limited accent color.
- Subtle borders instead of heavy shadows.
- Photo-first discovery surfaces.
- Quiet empty states.
- Focused typography.

Avoid:

- Loud gradients.
- Decorative blobs.
- Oversized cards.
- Heavy drop shadows.
- Excessive pills and chips.
- Flashy VIP treatment.
- Too many simultaneous badges.

### Sexy Without Being Cheesy

Sexiness comes from restraint:

- Photography.
- Motion.
- Confidence.
- Privacy.
- Clean surfaces.
- Strong composition.

Do not use cheesy lifestyle symbols, pineapple overload, flaming hearts, neon adult-site language, or novelty visual treatments. The brand can have warmth and edge without becoming cartoonish.

## Screen Personalities

### Grid

The grid is immediate, local, and dense.

Rules:

- Photo tiles dominate the screen.
- Cards show only name, online dot, distance, and linked-partner mini icon.
- Filters carry complexity; cards stay simple.
- Grid state is preserved exactly after opening and closing profiles.
- Filter changes animate items sliding in, out, and around rather than blanking the whole grid.
- Loading should feel incremental, not like a full restart.

The grid should feel alive, but not chaotic.

### Swipe

Swipe is curated, focused, and slightly more cinematic.

Rules:

- One primary person at a time.
- Photo-first card.
- Minimal metadata.
- Linked partner visible as context, not as a couple card.
- Gesture response should feel physical and high quality.
- Match moments can feel elevated but should not become childish.
- No messaging unless matched, except tightly capped premium Intro Notes.

Swipe is where Pynapli can feel more premium, but it should still be restrained.

### Activity

Activity is FOMO with control.

Rules:

- Newest-to-oldest.
- Compact rows.
- Clear categories: viewed, flirted, liked, matched, album request, partner invite, Psync, Playing Tonight.
- Gated insight can tease value without feeling scammy.
- Blur and locked states should be tasteful and readable.
- Avoid noisy notification-wall styling.

### Messages

Messages are private, quiet, and efficient.

Rules:

- Conversation list should be scannable.
- Chat should prioritize text and media clarity.
- Partner invite-to-chat states must be explicit.
- Private album interactions should feel controlled and consent-based.
- Safety/report actions must be accessible without clutter.

### Profile And Settings

Profile and settings should feel private and practical.

Rules:

- Access through the header avatar.
- No profile bottom tab.
- Settings hub should be calm and organized.
- Account, privacy, subscription, partner link, safety, albums, and app management should be easy to find.
- Avoid turning settings into a marketing surface.

### Store And Paywalls

Store should feel premium, not predatory.

Rules:

- Free, Social, and Select tiers should be understandable at a glance.
- Linked-partner subscription benefit should be prominent.
- Microtransactions should be clear, optional, and grounded in convenience or reach.
- No fake scarcity or manipulative countdown styling.
- Paid surfaces should match the app's subdued visual language.

## Motion Direction

Motion should clarify state and make the app feel responsive.

Use motion for:

- Grid insert, remove, and reposition.
- Swipe drag, spring-back, and fly-out.
- Profile sheet open and close.
- Match confirmation.
- Partner invite acceptance and denial.
- Private album request state changes.
- Playing Tonight subtle availability indicator.
- Activity rows entering after refresh.

Avoid:

- Constant decorative animation.
- Animated backgrounds.
- Tab-switch animations.
- Motion that shifts layout unexpectedly.
- Motion that delays user actions.

Respect reduced-motion settings where available.

## Native Interaction Rules

Touch and gesture rules:

- Important actions should have haptic feedback.
- Gesture thresholds should be forgiving and testable.
- Destructive actions require confirmation.
- Icon buttons need stable sizes and content descriptions.
- Bottom sheets should feel anchored and platform-appropriate.
- Pull-to-refresh should not reset scroll state unnecessarily.
- Long-press actions should be available for profile/chat safety actions where appropriate.

Scroll rules:

- Preserve per-tab scroll state.
- Preserve grid position after profile close.
- Avoid loading spinners that block already cached content.
- Do not jump the user to the top after background refresh.

## Density Rules

Pynapli should be dense, but not cramped.

Recommended density:

- Grid: highest density.
- Activity: medium-high density.
- Messages: medium density.
- Profile: medium density.
- Swipe: low density, high focus.
- Store: medium density with clear comparison.
- Settings: medium-low density for trust and clarity.

Do not apply one spacing model to every screen. Intent determines density.

## Photography And Media

Discovery surfaces rely on user media, so the UI should frame media well.

Rules:

- Crop consistently.
- Avoid overlays that obscure faces or important photo content.
- Keep grid gradients short and functional.
- Use tasteful placeholders for empty or private media.
- Private album locked states should look intentional, not broken.
- Respect explicit media rules and moderation outcomes.

## Typography Direction

Typography should be quiet and legible.

Rules:

- Use neutral sans-serif typography.
- Use compact labels on dense surfaces.
- Avoid negative letter spacing.
- Avoid giant text except onboarding, match, or major empty states.
- Never scale font directly from viewport width.
- Text must fit buttons, cards, rows, sheets, and headers across supported devices.

## Color Direction

Primary tone:

- Charcoal.
- Graphite.
- Warm black.
- Soft white text.
- Muted gray metadata.
- Restrained pineapple gold for premium/accent.
- Green only for online/available signals.

Avoid:

- Dominant purple-blue gradients.
- Beige lifestyle palettes.
- Brown/orange lounge cliches.
- Neon adult-app palettes.
- Overuse of gold.

Gold should feel like a premium accent, not the whole brand.

## Component Style Rules

Use:

- Icon buttons for repeated tools.
- Segmented controls for modes.
- Bottom sheets for filters and quick actions.
- Stable photo tiles.
- Compact rows.
- Native permission surfaces.
- Clean tab bar icons.

Avoid:

- Cards inside cards.
- Floating page sections.
- Generic rounded rectangles everywhere.
- Decorative badges with no product purpose.
- Big marketing hero sections inside the app.
- Over-explaining UI text.

## Anti-React-Native Checklist

Before accepting a screen, verify:

- It does not look like stacked web cards.
- It does not use generic oversized rounded panels.
- It does not rely on shadows to create hierarchy.
- It does not have inconsistent spacing from component to component.
- It does not reset state after navigation.
- It does not show full-screen loading when cached content exists.
- It does not use web-style modals for native mobile flows.
- It uses Compose-native animation and gesture handling.
- It has platform-aware touch, haptic, permission, billing, and media behavior.

## Screen Acceptance Checklist

Every production screen must pass:

- Native scroll and gesture feel.
- No text overflow at supported font sizes.
- No incoherent overlap.
- No unnecessary card nesting.
- Correct dark-first token usage.
- Stable layout dimensions for repeated elements.
- Touch targets meet minimum size.
- State survives tab switches.
- Empty, loading, error, offline, and gated states exist.
- Reduced-motion behavior is acceptable.
- The screen feels like Pynapli, not a generic dating app.

## Related Specs

- [DESIGN_AND_NAVIGATION_SPEC.md](DESIGN_AND_NAVIGATION_SPEC.md)
- [DESIGN_TOKENS.md](DESIGN_TOKENS.md)
- [MOBILE_APP_ARCHITECTURE_SPEC.md](MOBILE_APP_ARCHITECTURE_SPEC.md)
- [GRID_IMPLEMENTATION_SPEC.md](GRID_IMPLEMENTATION_SPEC.md)
- [SWIPE_IMPLEMENTATION_SPEC.md](SWIPE_IMPLEMENTATION_SPEC.md)
- [ACTIVITY_SPEC.md](ACTIVITY_SPEC.md)
- [PROFILE_SETTINGS_SPEC.md](PROFILE_SETTINGS_SPEC.md)
