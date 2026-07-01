# Pynapli Profile Filter Sync Spec

Last updated: 2026-04-24

## Purpose

Filters are only trustworthy if profile data, discovery settings, and indexes stay synchronized.

Core rule:

> A filter is a promise.

## Single Source Of Truth

The profile a user edits is the one canonical record. Everything else renders or queries that record —
never a parallel copy:

- **Profile = preview = card tap.** The public projection (`PublicProfileDto`) is rendered by one detail
  sheet (`ProfileDetailSheet`) wherever a card is tapped — Grid, Swipe, Activity, and the hub "Preview
  profile". What a user edits (fields, photos, tags, gender/orientation/status) is exactly what they
  preview and exactly what others see on tap. Grid/Swipe cards are thumbnail summaries of the same record,
  not a separate dataset.
- **Filterable fields are drawn from the same taxonomy the profile stores.** Gender/orientation come from
  `ProfileTaxonomies`; discovery style, lifestyle, logistics, and intent tokens come from
  `DiscoveryTagTaxonomies` — both in `:shared`. The Ktor server validates profile writes AND
  discovery-filter payloads against these same sets, and the clients build profile-edit chips and filter
  chips from them. A profile can therefore only carry values a filter can match, and a filter can only
  offer values a profile can store. Client filter enums are bound to the shared sets by a build-time
  alignment test (`DiscoveryTaxonomiesAlignmentTest`) so the loop can never silently drift.
- A filter selection returns real profiles whose stored fields satisfy it (array overlap for multi-value
  tags/intents, equality for single-value fields), within the propagation targets below.
- Gender and orientation are offered as explicit discovery filters (founder decision 2026-06-14; consistent
  with the bidirectional "orientation/gender compatibility" filter below). Profiles that leave them blank
  soft-include. This does not change the rule against inferring couple composition from gender alone.

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

- [x] Optional lifestyle/logistics fields soft-include by default and only hard-exclude when the viewer explicitly applies the matching overlap filter; full taxonomy coverage remains intentionally curated.
- [x] Has-photo excludes profiles with no public photo. Opt-in `withPhotosOnly` filter (free, FILTERS_SPEC L377) on Grid + Swipe: a hard `EXISTS` gate to at least one approved (`media_assets` image/ready/approved), visible profile-album photo — the same definition as the public profile-photo projection. Wired through `DiscoveryFilters` (in `hasAdvancedFilters`, not entitlement-gated) into the grid rank SQL + all four swipe deck SQL functions + both rank-cache class keys, plus client Grid/Swipe filter sheets ("Has photos" chip). Covered by `GridAndSwipeRepositoryIntegrationTest` "grid/swipe … withPhotosOnly excludes profiles without an approved public photo", `SwipeRankCacheKeyTest`/`GridRankCacheKeyTest`, and client round-trip tests.
- [x] Verified-only excludes unverified profiles.
- [x] Blocks exclude bidirectionally.
- [x] Dealbreakers are respected. Swipe dealbreakers (`SwipeDealbreakerKey`: age, distance, verified, discoveryStyles, intents, linkedOnly) are validated server-side against an allow-list and honored as hard gates that are skipped from deck relaxation (`SwipeDeckRelaxation`); identity filters (gender/orientation) are likewise kept hard through relaxation. See SWIPE_IMPLEMENTATION_SPEC "Default Deck / Never-Empty" + checklist; coverage in `SwipeServiceTest` (relaxation policy) and `SwipeFiltersSheetInteractionTest`.
- [x] Linked does not imply together.
- [x] Partner unlink removes active linked visibility: `getPublicProfile` drops the linked-partner mini once the link is unlinked. Regression coverage in `ProfileRepositoryIntegrationTest.publicProfileDropsLinkedPartnerMiniAfterUnlink` (active link projects the mini → unlink → mini gone).
- [x] Suspension removes profile from all discovery quickly.
- [x] Profile update propagates within target: `updateProfile` changes are reflected in the target's `getPublicProfile` (neighborhood/bio) and the updated lifestyle tags satisfy the grid/swipe overlap filter predicate — `ProfileRepositoryIntegrationTest.updateProfilePersistsBioTagsAndSelfDescribeAndMatchesTheFilterPredicate`.

## Do Not Implement

- Do not rely on bio text for filters.
- Do not hard-exclude blank optional lifestyle fields by default.
- Do not expose exact location through index fields.
- Do not use one universal filter behavior for Grid and Swipe.
