# Pynapli Legacy Rules Import Review

Last updated: 2026-04-24

## Purpose

This document records what was reused from the older `.pynapli-rules` folder and what was intentionally changed or rejected after the newer product decisions.

Legacy source folder:

`C:/Users/Smaye/Pynapli-Project/.pynapli-rules`

## Adopted Into Canonical Specs

### Security

Adopted:

- Risk scoring.
- Tiered anti-abuse response.
- Device/account-farm signals.
- Rate limits.
- Message review queues.
- False-positive recovery.
- Appeals.
- Testing requirements.

Canonical spec:

- [TRUST_AND_SAFETY_SPEC.md](TRUST_AND_SAFETY_SPEC.md)

Modified:

- Reframed bot score as broader account risk score.
- Replaced aggressive whole-app shadow degradation with targeted friction and quarantine.

Rejected:

- Fake push notifications.
- Fake matches.
- Fake payment success.
- Fake user pools presented as real users.

### Psync

Adopted:

- Same ordered deck for linked partners.
- Both partners swipe privately.
- Session invite/accept flow.
- Session recap/history idea.
- Nudge as optional subtle action.

Canonical spec:

- [PSYNC_SPEC.md](PSYNC_SPEC.md)

Modified:

- Both partners must approve final setup.
- Decks are bounded.
- Together matches require target consent.
- Solo passthrough is private by default.

Rejected:

- Psync Score.
- Public "in sync" percentage.
- Solo passthrough visible to partner by default.

### Messaging

Adopted:

- Grid open messaging.
- Swipe match-gated messaging.
- Partner invite flow.
- Consent-tap media reveal.
- Separate block/report behavior.
- Read receipts and privacy features as paid options.

Canonical spec:

- [MESSAGING_SPEC.md](MESSAGING_SPEC.md)

Modified:

- Partner invite requires both linked partner consent and third-party consent.
- Internal message states must remain honest for trust/safety.

### Notifications

Adopted:

- FCM delivery.
- Server-side notification decisions.
- Notification categories.
- Actionable partner/Psync/Playing Tonight notifications.
- Quiet hours.
- Per-type notification preferences.
- In-app bell distinction.

Canonical spec:

- [NOTIFICATIONS_SPEC.md](NOTIFICATIONS_SPEC.md)

Modified:

- More privacy-safe default copy.
- Bell is for action-required/account items, not a duplicate Activity feed.

### Moderation And Age Verification

Adopted:

- 18+ baseline.
- Market-driven enhanced age verification.
- Public/private content zones.
- Public content moderation before visibility.
- Report categories.
- Appeals and legal process.
- App-store-safe positioning.

Canonical spec:

- [MODERATION_AND_AGE_VERIFICATION_SPEC.md](MODERATION_AND_AGE_VERIFICATION_SPEC.md)

Modified:

- Removed precise legal-date claims from canonical product spec; current law must be verified with legal counsel before launch.

### Onboarding/Auth

Adopted:

- Minimum useful onboarding.
- Skippable tags/photo.
- First grid uses signup data.
- Post-signup nudges.
- Secure token storage.
- Resume signup progress.

Canonical spec:

- [ONBOARDING_AUTH_SPEC.md](ONBOARDING_AUTH_SPEC.md)

Modified:

- Kept explicit lifestyle detail optional and deferred.

### Profile/Filter Sync

Adopted:

- "A filter is a promise."
- Structured filterable fields.
- Propagation targets.
- Soft include for incomplete optional fields.
- Bidirectional identity/dealbreaker filters.
- One-directional preference filters.
- Completeness as internal ranking only.

Canonical spec:

- [PROFILE_FILTER_SYNC_SPEC.md](PROFILE_FILTER_SYNC_SPEC.md)

Modified:

- Canonical version does not require Elasticsearch specifically. Ktor/PostGIS/Redis remain the launch architecture, with search/index layer optional later.

### Playing Tonight

Adopted:

- Short-lived availability signal.
- 2-hour session.
- Partner approval for linked-together sessions.
- Zone-based map/list idea.
- 15-minute warning.
- Session-specific note/photo with moderation.

Canonical spec:

- [PLAYING_TONIGHT_SPEC.md](PLAYING_TONIGHT_SPEC.md)

Modified:

- No couple grid cards.
- Solo and linked-together Tonight modes are distinct.
- Interactions are not automatically shared with linked partner unless the user contacted the together/session context.

### Testing

Adopted:

- Critical E2E journeys.
- Grid/swipe/message performance benchmarks.
- Ktor integration tests.
- Abuse/security tests.
- Release checklist.

Canonical spec:

- [TESTING_STRATEGY_SPEC.md](TESTING_STRATEGY_SPEC.md)

### Launch

Adopted:

- City-by-city launch.
- Toronto/GTA first.
- Venue partnerships.
- Referral engine.
- App-store safety preparation.
- Expansion gates based on density and retention.

Canonical spec:

- [LAUNCH_STRATEGY_SPEC.md](LAUNCH_STRATEGY_SPEC.md)

Modified:

- Updated tier names to Free/Social/Select.
- Reframed from "couple as a unit" to individual-first accounts with linked-partner features.

### Design/Navigation

Adopted:

- Tokenized design system.
- Compose component thinking.
- Grid tile/swipe card/message bubble component focus.
- Header/avatar settings pattern.
- Per-tab state preservation.
- Deep-link handling.

Canonical spec:

- [DESIGN_AND_NAVIGATION_SPEC.md](DESIGN_AND_NAVIGATION_SPEC.md)

Modified:

- Removed negative letter spacing.
- Reduced default radii.
- Preserved current frontend rules: minimal grid tiles, no decorative clutter, no profile bottom tab.

## Remaining Legacy Files

The old `.pynapli-rules` files should now be treated as historical context, not current source of truth.

Current source of truth is the root-level canonical spec set in:

`C:/Users/Smaye/OneDrive/Documents/New project`

## Do Not Reintroduce

- Couple grid cards.
- Shared couple accounts.
- Psync Score.
- Public partner compatibility metrics.
- Fake anti-bot notifications/matches/payment success.
- Explicit push notification copy by default.
- Overly couple-centric positioning that excludes singles.
- Old tier names Scene/Elite.

