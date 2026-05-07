# Pynapli Profile Filter Sync Spec

Last updated: 2026-04-24

## Purpose

Filters are only trustworthy if profile data, discovery settings, and indexes stay synchronized.

Core rule:

> A filter is a promise.

## Indexing Principle

Filterable profile fields must be structured and queryable.

Index:

- Visibility state.
- Profile type/context.
- Linked-partner visibility.
- Discovery modes.
- Intent.
- Age bucket/display age where allowed.
- Gender/orientation fields.
- Lifestyle tags.
- Logistics tags.
- Verification state.
- Photo/public media flags.
- Availability/Tonight state.
- Location bucket/geospatial field.
- Subscription/ranking hints where appropriate.

Do not index:

- Message content.
- Private album contents.
- Exact raw GPS history.
- Verification raw data.
- Sensitive private notes.

## Propagation Targets

Targets:

- Profile filterable field update: within 60 seconds.
- Location update: within 10 seconds where needed.
- Account suspension/ban: immediate or under 5 seconds.
- Block: immediate.
- Partner link/unlink: within 60 seconds, with safety-critical visibility changes immediate where possible.

## Soft Include

For optional preference fields:

- Missing values should usually be included.
- This protects new users and privacy-conscious users.

Hard-existence filters:

- Has photo.
- Has face photo.
- Has private album.
- Verified.
- Online now.

These require the field/state to exist.

## Bidirectional Vs One-Directional Filters

Bidirectional filters affect both what a user sees and where they appear:

- Age with dealbreaker.
- Distance with dealbreaker.
- Orientation/gender compatibility.
- Solo/together/linked eligibility when hard intent requires it.
- Blocks/hides/safety exclusions.

One-directional filters affect only what the searching user sees:

- Lifestyle tags.
- Logistics tags.
- Hosting/travel.
- Experience level.
- Has album.
- Preferences.

Privacy-sensitive health fields, if ever implemented, should be handled with extreme care and legal/product review.

## Completeness Ranking

Profile completeness can improve ranking internally.

Use as:

- Ranking signal.
- Post-signup nudge.

Do not use as:

- Public score.
- Hard exclusion from core discovery.

## Architecture

Implementation can use:

- PostgreSQL/PostGIS as source of truth.
- Redis for hot presence/cache.
- Search/index layer if needed later.
- Event pipeline for index updates.

Ktor owns filter correctness.

## QA Checklist

- [ ] Partial: empty optional fields soft-include for implemented filters; full taxonomy coverage is incomplete.
- [ ] Has-photo excludes profiles with no public photo.
- [ ] Verified-only excludes unverified profiles.
- [x] Blocks exclude bidirectionally.
- [ ] Dealbreakers are respected.
- [x] Linked does not imply together.
- [ ] Partial: partner unlink removes active linked visibility in implemented profile-link flows; regression coverage still needed.
- [x] Suspension removes profile from all discovery quickly.
- [ ] Profile update propagates within target.

## Do Not Implement

- Do not rely on bio text for filters.
- Do not hard-exclude blank optional lifestyle fields by default.
- Do not expose exact location through index fields.
- Do not use one universal filter behavior for Grid and Swipe.
