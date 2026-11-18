# Pynapli Grid Implementation Spec

## Purpose

This document captures how Pynapli should implement its Grindr-inspired grid so it stays fast, minimal, location-aware, battery-conscious, and technically correct.

The grid is not a social feed and not a swipe deck. It is a live ranked card index.

Core rule:

> Grid is for now. Swipe is for intent.

For grid load-test targets, dense-city scale requirements, and production performance gates, use [PRODUCTION_READINESS_AND_QUALITY_SPEC.md](PRODUCTION_READINESS_AND_QUALITY_SPEC.md) alongside this document.

## Product Contract

The grid answers:

> Who is relevant near me right now?

The grid should feel:

- Instant
- Dense
- Minimal
- Alive
- Stable
- Filterable
- Low-clutter

Users can message from the grid without matching, subject to the recipient's messaging settings.

## Non-Negotiable UX Rules

1. Every grid card represents exactly one autonomous user.
2. No primary couple cards.
3. Linked partners appear only as a small linked-partner icon/avatar.
4. Grid cards stay extremely minimal.
5. Filters carry complexity, not cards.
6. Opening and closing a profile must return the user to the exact same grid position.
7. Filter changes must not visually nuke the whole grid.
8. Distance and presence updates must not constantly reshuffle the grid.
9. Grid messaging is allowed without matching.
10. Battery usage must be conservative.

## Grid Card Design

Show only:

- Main thumbnail
- Display name
- Online indicator
- Distance, if visible
- Tiny linked-partner avatar/icon, if linked

Do not show:

- Bio
- Intent chips
- Lifestyle tags
- Full verification stacks
- Album labels, unless later proven necessary and kept extremely subtle
- Compatibility scores
- Preference lists

The card should answer:

1. Who is this?
2. Are they active?
3. How close are they?
4. Are they linked?

Everything else belongs in filters, profile view, Activity, or chat.

## Linked Partner Display

Every linked partner remains an autonomous account.

Example:

- Alex is 1 km away.
- Jamie is 9 km away.
- Alex and Jamie are linked.

Grid behavior:

- Alex appears as Alex at 1 km.
- Jamie appears as Jamie at 9 km.
- Alex's card can show a tiny Jamie avatar/link icon.
- Jamie's card can show a tiny Alex avatar/link icon.
- The grid must not merge them into a single couple card.

Linked icon tap/long press can show:

- Linked with Jamie
- Partner online/recent state, if allowed
- Partner distance, if allowed
- View profile

If the linked partner hides distance, omit distance or show "distance hidden."

## Backend Architecture

### Data Shape

`POST /v1/grid/query` returns lightweight card DTOs (`GridDtos.kt`), never full profiles: no full bio,
photo lists, album metadata, full preferences, chat history or sensitive profile fields. Full profile
data loads only when a profile opens (`GET /v1/profiles/{id}`).

### Grid Query Pipeline

```text
authenticated user
-> privacy and safety exclusions
-> block/hide exclusions
-> location radius (PostGIS ST_DWithin, <-> nearest-neighbor ordering, GiST index)
-> active filters
-> subscription visibility limits
-> ranking
-> stable cursor pagination
-> lightweight card DTOs
```

Redis holds presence TTLs, the ranked-candidate cache (see Performance Targets), rate limits and
boost state. Authoritative profile state never lives only in Redis.

### Pagination

Sealed, stable cursor pagination. Never offset pagination for the live grid (inserts and removals
make offset pages unstable). The cursor carries the filter hash, rank position (boost bucket, order
bucket, order tiebreak), user-id tie-breaker and seen-count state for tier depth. It is **opaque**:
AES-256-GCM under a key derived from the cursor secret, random nonce, `g2.` prefix; old `v1.`
cursors are rejected as `INVALID_GRID_CURSOR`. Pages are at least 12 cards (`MIN_PAGE_SIZE`); only
the tier depth cap can make a final page shorter.

### Location privacy (load-bearing)

An ordinary account can report any location and read every field, so every Grid observable —
displayed distance, order, radius membership, cursor — is a function of (viewer cell, target
cell) only:

- **Cell:** the effective location (active travel point, else home GPS) snapped to a 0.01-degree
  grid (~1.1 km N-S) by `pynapli_location_cell` (V94). Ranking starts from the viewer's cell centre;
  the shared rank window is keyed by that cell, so viewers sharing a window share an origin.
- **Displayed distance:** cell to cell, then bucketed: under 2 km reads "1 km", whole km to 10 km,
  5 km steps to 50 km, 10 km steps beyond. Deterministic (no per-request noise to average out).
- **Order:** boost first, then a whole-km bucket of the cell-to-cell distance, then a stable
  pseudo-random tiebreak seeded by the viewer cell (V95). Someone who hides distance gets an order
  bucket that does not depend on where they are (uniform over the first min(radius, 25 km)).
- **Radius:** snapped up to 2, 5, 10, 25, 50 or 100 km; membership is cell-to-cell on the
  effective location (a traveller matches only where they travel, never by home). The GiST
  pre-filter pads the radius by 1.6 km of cell slack.

Proof: `GridLocationPrivacyTest`, `GridDistancePrivacyTest`, `GridRankCacheCrossViewerTest`.

Favorites follow the same rules without a radius: the displayed distance is the public bucket of the
cell-to-cell distance (as on profile detail), the order key is that public bucket (never finer than the
card reads), and someone who hides distance gets a bucket that does not depend on where they are
(`FavoriteServiceIntegrationTest`).

## Ranking

Base ranking:

```text
eligible profiles
-> privacy/block/safety filters
-> location radius
-> active filter rules
-> subscription visibility rules
-> rank by distance + recency + trust + engagement
-> stable sort buckets
```

Suggested rank inputs:

- Distance
- Online/recent activity
- Boost
- Verification
- Profile completeness
- Response rate
- Low report/block rate
- Mutual intent match
- Linked partner clarity
- Event overlap
- Freshness

Avoid rewarding spam:

- High message volume alone should not boost rank.
- Low reply rate and high report/block rate should suppress rank.
- Repeated private album spam should suppress rank.

### Stable Sort Buckets

Do not reorder constantly on tiny changes.

Use coarse buckets:

- distance bucket
- recency bucket
- boost bucket
- trust bucket

Within buckets, use deterministic tie-breakers.

This keeps the grid feeling alive without feeling chaotic.

## Presence States

Recommended states:

- Online: active foreground session or socket; expires quickly.
- Recently active: active in last 60 minutes.
- Active today: active in last 24 hours.
- Visible in grid: eligible to appear based on privacy and filters.
- Stale: deprioritized after 7-14 days.
- Dormant: hidden or heavily deprioritized after 30+ days.

Recommended TTLs:

- Online: 2-5 minutes after last foreground heartbeat/socket event.
- Recently active: 60 minutes.
- Active today: 24 hours.
- Stale: 7-14 days.
- Dormant: 30+ days.

## Location Strategy

Location should be useful but battery-conscious and privacy-aware.

### Location Rules

- Use last known location for instant first render.
- Request fresh location after initial render.
- Use while-in-use location by default.
- Avoid continuous exact GPS streaming.
- Snap to coarse cells and coarse buckets, never per-request noise (see Location privacy).
- Respect hide-distance settings, including in ordering.
- Stop active location updates when app backgrounds.
- Use movement thresholds before sending location updates to backend.

### Movement Thresholds

Recommended behavior:

- Less than 100-250m movement: no grid reorder; optionally update visible distance text.
- 250m-1km movement: refresh ranking quietly, preferably offscreen first.
- City/neighborhood change: fetch new grid.
- Filter change: fetch filtered IDs, diff, animate.

Current implementation:

- The mobile client keeps the exact last-submitted device coordinate locally.
- Movement under 250m is not submitted to the backend and does not refresh the grid.
- Movement from 250m to 1km submits location and refreshes the existing grid quietly: the grid state holder keeps the existing cards visible, never flips `isRefreshing` while cards exist, and a failed quiet refresh leaves the visible cards and prior `errorMessage`/`errorCode` untouched. The new feed is swapped in atomically when it arrives.
- Movement of 1km or more submits location and requests a normal grid refresh (clears `errorMessage`, sets `isRefreshing`, and surfaces failures).
- Android uses last-known location first, then a cancellable one-shot `getCurrentLocation` request only when needed. `MainActivity.onStop` cancels any pending one-shot location request so the Grid does not keep GPS work alive after the app backgrounds.
- Denied location keeps the Grid in a recovery state: no Grid cards are shown, the `Use current location` action remains available, and the app displays `Location permission is required to load nearby profiles.` after Android permission denial.

## Client Implementation

`GridStateHolder` exposes `StateFlow` UI state; `GridProfileGrid` renders a `LazyVerticalGrid` with
stable keys (`key = card.id`), a `contentType`, and `Modifier.animateItem()` so inserts, removals and
reorders keep identity and animate. Images load through Coil 3 (the only image loader). Compact panes
keep a fixed 3-up grid; wider panes use adaptive columns at the same tile size (see
[WEB_PWA_GAMEPLAN.md](WEB_PWA_GAMEPLAN.md)).

## Grid State Retention

Opening a profile must not destroy the grid.

Persist:

- Current cards
- Active filters
- Pagination cursor
- `LazyGridState`
- First visible item index
- First visible item offset
- Last grid version
- Last location bucket

The profile should open as:

- a modal/bottom sheet, or
- a navigation destination over retained state.

Do not refetch/rebuild the grid when closing a profile unless the user explicitly refreshes or the grid state is expired.

## Filter Application

On filter change:

1. Compute filter hash.
2. Request filtered card IDs/DTOs.
3. Keep old list visible while loading.
4. Apply returned list as one state update.
5. Let stable keys animate changed cards.
6. Preserve scroll where reasonable.

Do not:

- Clear the whole grid first.
- Show a full-screen spinner if old data exists.
- Reset scroll to top unless the user chooses a radically different context or location.

## Default Deck / Never-Empty

The Grid must never look broken because a viewer left filters at their defaults or in a sparse
area. Two layers guarantee this:

1. **Default = all nearby.** With no filters selected, the Grid is an unfiltered proximity query:
   every discoverable, eligible profile within the viewer's radius, ranked normally (Boosted →
   proximity → recency). No structured filter means no `WHERE` predicate is applied for that
   dimension — the cardinality gate (`cardinality(?::text[]) = 0 OR ...`) short-circuits an empty
   filter to "match everyone", so an empty filter set never narrows the result.
2. **Widen-on-empty.** If the **first page** at the requested radius returns zero candidates and
   the radius is below the maximum (`MAX_RADIUS_METERS = 100_000`), the server re-queries once at
   the maximum radius and returns that page instead. This is server-side and transparent to the
   client; the viewer simply sees nearby-ish people rather than an empty grid. It mirrors the Swipe
   deck's radius relaxation (`SwipeDeckRelaxation.relaxRadius`).

Rules for the widen-on-empty fallback (`GridService.queryGrid`):

- **First page only.** It fires when `cursor == null`. A later empty page is honest exhaustion of a
  scrolled deck, not a broken-looking first impression — do not widen mid-pagination.
- **Edge-fill, no pagination.** A widened page returns `nextCursor = null`. The wide radius is never
  baked into a cursor scoped to the viewer's narrow radius (the follow-up request still carries the
  narrow radius, so a wide-scoped cursor would be rejected as `INVALID_GRID_CURSOR`). One page of
  wider results beats an empty grid; a viewer who wants more widens their own radius filter.
- **Respects explicit filters.** Widening only relaxes *distance*. Any structured filters the viewer
  did select (gender, orientation, intents, lifestyle, verified, online-only, etc.) still apply at
  the wide radius — a filter is still a promise. Widening searches farther, it does not silently show
  non-matching people.
- **Honest empties remain.** Emptiness caused by a viewer-specific post-filter (block/hidden lists,
  `onlineOnly` with nobody online nearby) is not "no candidates within radius", so it keeps its own
  honest empty state rather than triggering a radius widen.

## Realtime Updates

Use WebSocket deltas for:

- Online/offline state
- Recently active changes
- Boost starts/ends
- Viewed/flirted activity
- Incoming message badges
- Typing status

Do not use WebSockets to stream full grid reorders constantly.

Presence delta example:

```kotlin
@Serializable
data class PresenceDelta(
    val userId: String,
    val online: Boolean,
    val lastActiveAt: Instant
)
```

Client behavior:

- Update visible online dots immediately.
- Update offscreen card models.
- Do not reorder unless threshold rules say to.

## Image Loading

The grid lives or dies by thumbnail performance.

Rules:

- CDN thumbnails.
- Fixed thumbnail sizes.
- Preload upcoming visible range.
- Use memory + disk cache.
- Avoid original images in grid.
- Decode to displayed size.
- Use placeholders for missing photos.
- Avoid layout shifts when thumbnails load.

Recommended card image ratio:

- Square, 1:1.

This keeps the grid stable and dense.

## Subscription Gating

Grid volume is gated by tier (Free / Social / Select page size and depth, enforced server-side; see
[MONETIZATION_SPEC.md](MONETIZATION_SPEC.md)). **Never gate** basic safety filters, basic
singles/linked/intent clarity, block/report, or basic location privacy.

## Boost

Boosted users should appear higher in the grid, but not destroy relevance.

Boost rank should still respect:

- Blocks
- Safety exclusions
- Privacy exclusions
- Recipient visibility settings
- Location context

Boost should not put a user into feeds where they are wholly irrelevant or disallowed.

## Anti-Abuse

The open grid messaging model needs protections:

- Message rate limits
- First-message cooldowns
- Duplicate intro detection
- Report/block suppression
- Private album request throttles
- New-account trust limits
- Suspicious automation detection

Do not reward raw outbound message volume in ranking.

Reward:

- Replies received
- Positive engagement
- Good standing
- Verification
- Profile completeness
- Low block/report rates

## Performance Targets

- First cached render under 500ms after the app shell is ready; first network render under 1.5s on a
  normal mobile network; first page 30–60 cards; CDN thumbnails, never originals.
- Profile open/close never loses grid state or scroll position; a filter apply never blanks the grid
  if old cards exist; presence updates never trigger a full refetch.
- Local API budgets (p95, `scripts/grid-api-load-test.ps1`, 5,000 candidates): grid initial 800ms,
  filter 500ms, page two 800ms, profile open 800ms. The last 500-concurrent warm run (2026-06-15)
  was zero-error and inside every budget. `-ServerProfile` runs need
  `PYNAPLI_API_PROFILING_ENABLED=true`. Run history is in git and
  `docs/archive/IMPLEMENTATION_HISTORY.md`.
- How the Grid meets the budget: a two-phase query (rank/cap candidate ids, then hydrate only the
  page), covering partial indexes (`V43`), and a shared ranked-candidate cache
  (`GridCandidateRankCache`: in-memory per instance, `RedisGridCandidateRankCache` in production, key
  `grid:candidates:v2:cell:{lonE2}_{latE2}:radius:{r}:w:{window}:f:{filterHash}` keyed by the
  viewer's 0.01-degree cell, TTL 5s). The cache
  is exclusion-free: per-viewer blocks/hides are applied after it in `GridService`, and linked-only and
  travel-active viewers bypass it. **The Redis path fails open** to direct queries on any Redis error.

Battery: no continuous exact GPS in the background, no tight polling loops, stop foreground location
updates when the app pauses, prefer event deltas over polling.

## Implementation Checklist

- [x] Lightweight card DTO; full profile endpoint is separate.
- [x] PostGIS radius query (GiST pre-filter, cell-to-cell membership) with coarse privacy-safe ordering.
- [x] Redis presence TTLs; ranked-candidate cache (above).
- [x] Sealed (encrypted) stable cursor pagination, 12-card minimum page; tier volume caps with
  seen-count state in the cursor.
- [x] Location privacy: coarse cells, coarse display buckets, coarse order, snapped radius steps,
  effective-location membership (L-07..L-10, L-14).
- [x] Redis-backed Grid query rate limiting (`RateLimitService`).
- [x] Load harness and 500-concurrent proof inside budget (local single instance).
- [x] Compose grid: stable keys, fixed square cards, `LazyGridState` hoisted to the screen so it
  survives body branch flips and the profile sheet (`GridScreenScrollPreservationTest`).
- [x] Filter changes keep cards visible until the new feed lands (`GridStateHolderTest`); the filter
  sheet scrolls to its actions on small screens.
- [x] Visual-signature regression (`GridScreenVisualRegressionTest`) and connected Android QA
  (`scripts/android-grid-qa.ps1`, goldens in `scripts/android-grid-qa-goldens.json`).
- [x] Presence deltas over `/v1/presence/stream` flip online dots in place (no reorder, no refetch);
  per-delta block filter and explicit subscription scope; `PresenceOfflineScanner` emits offline
  deltas; client reconnects with backoff. Android WebSockets need the OkHttp engine.
- [x] Pull-to-refresh (`GridPullToRefresh`) preserves filters and scroll; a Refresh button covers
  pointer devices.
- [x] Instant cached first render (`GridSnapshotStore`, last 60 cards) followed by a quiet refresh.
- [x] Location updates are thresholded before submission; denied-location QA
  (`scripts/android-grid-denied-location-qa.ps1`).
- [ ] Partial: no background location, guarded by `scripts/android-location-policy-check.ps1`;
  physical-device battery profiling (`scripts/android-grid-battery-profile.ps1`) and store policy
  review remain.
- [x] Thumbnail prefetch of the next 12 cards into Coil's cache.
- [x] Message rate limiting; ranking does not reward spam volume.
- [x] Never-empty: with no filters the Grid is a plain proximity query, and an empty **first page**
  widens once to `MAX_RADIUS_METERS` as edge-fill only (`nextCursor = null`). Explicit filters still
  apply; viewer-specific empties (blocks, online-only with nobody online) stay honestly empty.
- [ ] Larger dense-metro and production-hardware load runs; Redis hit rate under multi-instance load.
- [x] Favorites (V105): private star in profile detail; `PUT`/`DELETE /v1/favorites/{id}` (idempotent,
  no activity or notification, cap 500, free) and a paged `GET /v1/favorites` of Grid cards, online first
  then the public distance bucket; hidden-distance people get a location-independent position; Grid
  visibility rules on every read (blocks, hides, incognito, banned, deleted, quarantined drop out);
  sealed viewer-bound cursor (`FavoriteServiceIntegrationTest`). Nearby/Favorites switch on the Grid.
- [x] Favorites: online dots update live through the Grid presence stream while the view is showing;
  the starred list is in the account data export. A long-press quick menu on Grid cards is deliberately
  not built: one tap opens the profile, which holds the star and every other action.
- [x] Last seen (V106): profile detail shows a server bucket (`online_now`, `active_15m`, `active_today`,
  `active_week`) from a throttled heartbeat and session activity; never a timestamp; none for incognito
  (`LastActiveIntegrationTest`). Not on the card face.
- [x] Tab return keeps the Grid's cards, order and scroll (quiet refetch only after 15 minutes); a block or
  hide also leaves the cold-start cache; sign-out or a dead session wipes the Grid and its cache
  (`GridInstantLoadTest`).
- [x] A failed next page stops auto-paging and offers Retry; an unfiltered empty Grid shows an empty state
  (`GridPagingAndEmptyStateTest`).
## Do Not Implement

- Do not use offset pagination for live grid pages.
- Do not fetch full profile data for every visible card.
- Do not rebuild the whole grid on every presence update.
- Do not reset scroll after viewing a profile.
- Do not continuously reorder for tiny location movements.
- Do not make linked users into couple cards.
- Do not put intent/tag clutter on grid cards.
- Do not make background GPS a default requirement.
- Do not reward users for blasting messages.
- Do not paywall safety-critical filtering or block/report.
