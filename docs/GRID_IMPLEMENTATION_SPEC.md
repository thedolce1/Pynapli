# Pynapli Grid Implementation Spec

Last updated: 2026-05-14 (connected Android golden QA and 500-concurrent proof)

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

## Public Research Signals

Grindr publicly documents the following grid behaviors:

- The Grid is the home page.
- By default, the Grid shows nearby users and Boosted users.
- Grid controls can combine tags, filters, Fresh, and Explore.
- Free users can view dozens of nearby profiles.
- XTRA users can view up to a subset of 500 profiles.
- Unlimited users can view all profiles.
- Popular highlights active, responsive, highly tapped, and highly chatted profiles.
- Location is collected to roughly 100m accuracy.
- Even when distance display is off, proximity can still sort the grid.

Sources:

- Grindr Grid: https://help.grindr.com/hc/en-us/articles/12155355365011-The-Grid
- Grindr Filters: https://help.grindr.com/hc/en-us/articles/12155443240851-Filters
- Grindr location/safety: https://help.grindr.com/hc/en-us/articles/1500009290262-Safety-tips/
- Grindr location troubleshooting: https://help.grindr.com/hc/en-us/articles/1500008383842-Location-troubleshooting

Grindr does not publicly document its exact backend, client state management, TTLs, or ranking algorithms. Any implementation details below are Pynapli architecture decisions, not claims about Grindr internals.

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

Recommended stack:

- Ktor server
- PostgreSQL + PostGIS
- Redis
- CDN-backed object storage for thumbnails
- Ktor WebSockets for presence/activity deltas

### Data Shape

The grid endpoint must return lightweight card DTOs, not full profiles.

Example:

```kotlin
data class GridCardDto(
    val userId: String,
    val displayName: String,
    val thumbnailUrl: String?,
    val online: Boolean,
    val distanceMetersRounded: Int?,
    val linkedPartnerUserId: String?,
    val linkedPartnerThumbUrl: String?,
    val rankToken: String
)
```

Do not include:

- Full bio
- Full photo lists
- Private album metadata beyond maybe "has private album" if later needed
- Full preferences
- Chat history
- Sensitive profile fields

Full profile data loads only when the user opens a profile.

### Grid Query Pipeline

Recommended backend flow:

```text
authenticated user
-> privacy and safety exclusions
-> block/hide exclusions
-> location radius
-> active filters
-> subscription visibility limits
-> ranking
-> stable cursor pagination
-> lightweight card DTOs
```

### Geospatial Search

Use PostGIS for location-aware queries.

Recommended primitives:

- `ST_DWithin` for radius filtering.
- `<->` nearest-neighbor ordering for fast distance sorting.
- GiST/SP-GiST indexes on location columns.

References:

- ST_DWithin: https://access.crunchydata.com/documentation/postgis/latest/ST_DWithin.html
- Nearest-neighbor search: https://postgis.net/workshops/postgis-intro/knn.html

### Redis Usage

Use Redis for:

- Presence TTLs
- Candidate ID caches
- Filter bucket caches
- Rate limits
- Boost state
- Recent activity counters

Do not store authoritative profile state only in Redis.

### Candidate Cache

Cache nearby candidate IDs by coarse location bucket and broad filter class.

Example cache keys:

```text
grid:candidates:v1:cell:{h3Cell}:radius:{radius}:profileType:{type}
presence:user:{userId}
boost:active:{region}
```

Cache only IDs and lightweight ranking hints. Hydrate cards from database/cache layer after exclusions.

### Pagination

Use stable cursor pagination. Avoid offset pagination for live grids because inserts/removals make offset pages unstable.

Cursor should include:

- rank score
- distance bucket
- user ID tie-breaker
- grid version/filter hash

Example:

```kotlin
data class GridCursor(
    val filterHash: String,
    val rankScore: Double,
    val distanceBucket: Int,
    val userId: String
)
```

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

These are Pynapli decisions. Grindr does not publicly document exact online/grid TTL behavior.

## Location Strategy

Location should be useful but battery-conscious and privacy-aware.

### Location Rules

- Use last known location for instant first render.
- Request fresh location after initial render.
- Use while-in-use location by default.
- Avoid continuous exact GPS streaming.
- Round or fuzz distance.
- Respect hide-distance settings.
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

### Battery References

Android recommends removing location updates when not needed, setting timeouts, batching requests, and throttling background location.

Source:

- Android location battery optimization: https://developer.android.com/develop/sensors-and-location/location/battery/optimize

## Client Architecture

Recommended Kotlin client stack:

- Compose Multiplatform
- `LazyVerticalGrid`
- `rememberLazyGridState`
- `StateFlow` for grid UI state
- `SharedFlow` for one-off events and realtime deltas
- Ktor Client
- Ktor WebSocket client
- Coil 3 or Kamel for image loading/caching

References:

- Compose lazy lists/grids: https://developer.android.com/develop/ui/compose/lists
- StateFlow/SharedFlow: https://developer.android.com/kotlin/flow/stateflow-and-sharedflow
- Ktor WebSockets: https://ktor.io/docs/server-websockets.html
- Kamel: https://github.com/Kamel-Media/Kamel

## Compose Grid Implementation

Use stable item keys.

```kotlin
@Composable
fun PynapliGrid(
    cards: List<GridCardUiModel>,
    gridState: LazyGridState,
    onCardClick: (String) -> Unit
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        state = gridState
    ) {
        items(
            items = cards,
            key = { it.userId },
            contentType = { "grid-card" }
        ) { card ->
            GridCard(
                card = card,
                modifier = Modifier.animateItem(),
                onClick = { onCardClick(card.userId) }
            )
        }
    }
}
```

Why:

- Stable keys preserve item identity.
- `contentType` helps lazy layout reuse.
- `animateItem()` lets inserts/removals/reorders feel smooth.
- `LazyVerticalGrid` only composes visible items.

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

Grid volume can be gated similarly to Grindr's public model.

Possible Pynapli tiers:

- Free: dozens of nearby profiles.
- Plus: expanded nearby grid.
- Premium: largest/all eligible grid, advanced filters.

Do not gate:

- Basic safety filters.
- Basic singles/linked/intent clarity.
- Block/report.
- Basic location privacy.

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

Initial grid:

- First cached render: under 500ms after app shell is ready.
- First network grid render: target under 1.5s on normal mobile network.
- First page: 30-60 cards.
- Thumbnail size: CDN optimized, no originals.

Interaction:

- Profile open: no grid state loss.
- Profile close: same scroll position.
- Filter apply: no blank grid if old cards exist.
- Presence update: no full refetch.

Current API-level baseline:

- `scripts/grid-api-load-test.ps1` seeds and exercises the mixed Grid workload through real Ktor endpoints.
- Latest 2026-05-13 local full run used 5,000 candidates, 1,000 online presence keys, 100 mixed Free/Social/Select viewers, and 100-way API concurrency.
- The run produced zero HTTP errors.
- With a tuned local DB pool of 32 / min idle 8, p95 remained over target: initial Grid 1977ms, filtered Grid 1509ms, page two 1795ms, profile open 1181ms, presence heartbeat 341ms.
- The profiled follow-up run showed the DB candidate query and pool saturation are the main Grid pressure points: initial Grid DB candidate query p95 1064ms, filtered Grid DB candidate query p95 897ms, page-two DB candidate query p95 1226ms, with Hikari waiting threads p95 around 24-27 under the 100-way load shape.
- Representative `EXPLAIN (ANALYZE, BUFFERS)` returned 61 cards in about 103ms alone but touched about 98k shared buffers and looped through about 5,100 candidates with lateral media/link/boost checks.
- The Grid SQL is now two-phase: phase one ranks/caps candidate IDs using location, filters, safety, blocks, boost, and cursor; phase two hydrates only the returned page cards and linked partner metadata.
- A follow-up side-query pass added short-lived server entitlement caching, one Redis Lua call for rate limits, one profile-view eligibility/upsert statement, a combined notification delivery gate query, and bounded background profile-view recording for production profile opens.
- A focused `EXPLAIN (ANALYZE, BUFFERS)` pass on the current two-phase candidate query showed PostgreSQL underestimates dense `ST_DWithin` results, so the broad Grid path still scans/ranks most nearby candidates. Covering partial indexes reduced the no-filter candidate plan from about 57.5k shared buffers / 73ms to about 42.3k shared buffers / 61ms in the local dense dataset. Filtered plans were cheaper: relationship-context and style+intent filters were about 20-22k shared buffers / 46-47ms, and the age-range filter was about 25.5k shared buffers / 37ms. Page two still scans most eligible candidates because the cursor depends on computed distance ordering.
- Migration `V43__add_grid_query_covering_indexes.sql` adds covering partial lookup indexes for active users, visible profiles, and visible Grid discovery settings.
- The API load harness now refreshes planner statistics after bulk seeding so local dense-load timings reflect the query plan, not stale Testcontainers/dev seed statistics.
- Latest warm 2026-05-13 profiled API run after the index pass produced zero HTTP errors with 5,000 candidates, 1,000 online candidates, 100 mixed-tier viewers, and 100-way API concurrency. Client p95: Grid initial 450.4ms, Grid filter 354.5ms, Grid page two 477.0ms, profile open 138.8ms, presence heartbeat 118.0ms. Server route p95: Grid initial 407.3ms, Grid filter 293.1ms, Grid page two 417.3ms, profile open 93.2ms, presence heartbeat 41.0ms.
- 500-concurrent local proof runs on 2026-05-14 used 5,000 candidates, 1,000 online candidate presence keys, 500 mixed-tier viewers, and 500-way API concurrency. Warm run: zero HTTP errors; p95 Grid initial 838.3ms, Grid filter 702.6ms, Grid page two 943.4ms, profile open 318.9ms, presence heartbeat 220.3ms. Cold Redis-cleared run: zero HTTP errors; p95 Grid initial 638.2ms, Grid filter 492.0ms, Grid page two 709.8ms, profile open 167.9ms, presence heartbeat 144.4ms.
- Decision: the Grid now meets the current 100-concurrent warm API budget locally after the index pass and is stable at 500-concurrent locally with zero HTTP errors. Redis candidate caching should not be added blindly yet; the warm 500-concurrent p95 variance means the next scale step is repeat 500-concurrent runs with `-ServerProfile` before deciding whether Redis/H3-style candidate caches are justified.
- Update 2026-06-02 (candidate cache justified by evidence, then shipped): a 500-concurrent `-ServerProfile` run proved all three Grid scenarios over p95 budget, dominated by `grid.db.candidateQuery` (initial 1847ms / filter 1158ms / page2 2404ms p95) with the Hikari pool saturated (waiting threads p95 ~21-27). This met the bar for the candidate cache. A shared, exclusion-free **ranked-candidate cache** (mirroring the reviewed Swipe deck rank cache `JdbcSwipeDeckRankCache`) was added in `JdbcGridRepository`: `queryNearby` now splits into a cacheable RANK phase (keyed by coarse E3 ~111m location bucket + radius + filter class) and a cheap page-id HYDRATE phase. Per-viewer block/hidden are filtered post-repository in `GridService`; self is excluded in Kotlin over the shared window; linkedOnly and travel-active viewers bypass the cache. Re-running the identical 500-concurrent load: every scenario now clears budget with zero errors — Grid initial 2566→300ms, filter 1723→310ms, page2 2931→572ms, profile open 664→145ms, presence heartbeat 408→154ms; candidateQuery p95 collapsed (initial 30ms, filter 64ms, page2 366ms); Hikari waiting p95 → 0. The in-memory implementation (TTL 5s, window/maxEntries 128) is per-instance; the cache was made pluggable behind `GridCandidateRankCache` and a **Redis-backed `RedisGridCandidateRankCache`** is now wired in production (`ServerDependencies`) so the ranked window is shared across instances under the spec's named key `grid:candidates:v1:cell:...` (fails open to direct queries on any Redis error). Single-instance and tests keep the in-memory default. Artifacts in `artifacts/grid-api-load/G3-candidate-cache-result.md`.

Battery:

- No continuous exact GPS in background.
- No tight polling loops.
- Stop foreground location updates when screen/app pauses.
- Prefer event deltas over polling.

## Implementation Checklist

- [x] Lightweight grid card DTO exists.
- [x] Full profile endpoint is separate.
- [x] PostGIS radius query implemented.
- [x] PostGIS nearest-neighbor ordering implemented.
- [x] Redis presence TTLs implemented.
- [x] Candidate rank cache is implemented (2026-06-02): a shared, exclusion-free ranked-candidate window keyed by coarse location bucket + radius + filter class, mirroring the Swipe deck rank cache. Pluggable behind `GridCandidateRankCache` with two impls — `InMemoryGridCandidateRankCache` (per-instance default, used by tests/single-instance) and **`RedisGridCandidateRankCache`** (wired in production via `ServerDependencies`) which shares the window across instances using the spec's named key `grid:candidates:v1:cell:{lonE3}_{latE3}:radius:{r}:w:{window}:f:{filterHash}` (TTL 5s, JSON window blob, SHA-256-hashed filter class). The Redis path **fails open** to direct queries on any Redis error, so a Redis outage degrades rather than breaks the Grid. Redis also remains in use for presence/rate limits.
- [x] Signed stable cursor pagination is implemented for live grid responses.
- [x] 5,000-candidate dense grid integration proof exists for stable cursor pages.
- [x] 100-concurrent filtered repository regression proof exists against the same dense dataset. Dense bulk-seed tests now refresh planner statistics before timing the query path so the regression guardrail reflects the intended indexed plan.
- [x] API-level mixed-tier Grid load harness exists at `scripts/grid-api-load-test.ps1`, covering dense candidates, online presence, filter changes, pagination, and profile opens through real Ktor endpoints. It writes `summary.json` and `results.csv` artifacts and supports `-ColdCache` Redis-cleared runs.
- [x] Latest warm API-level 5,000-candidate / 100-concurrent run is zero-error and inside current local p95 budgets.
- [x] Opt-in server profiling exists for Grid, profile open, and presence heartbeat using `PYNAPLI_API_PROFILING_ENABLED=true` plus the load harness `-ServerProfile` switch.
- [x] 500-concurrent API proof is zero-error and within every p95 budget (Phase F4, 2026-06-15): a true 500-viewer / 500-throttle warm run over the 5,000-candidate dense dataset returned 0 errors with grid_initial p95 148ms (budget 800), grid_filter 216ms (500), grid_page2 407ms (800), profile_open 87ms (800), presence_heartbeat 76ms (300). This **closes the previously-flagged warm-run variance** (the Redis candidate-rank cache warms more effectively at higher viewer counts). The proof had to be un-broken first: `scripts/grid-api-load-test.ps1` seeded a free-text `gender = "open"` that the structured-gender write path (#118) now rejects (`INVALID_GENDER`), so the seed never completed — fixed to valid `ProfileTaxonomies` keys. Larger metros and a prod-hardware run (the budgets above are on a local single-instance dev box) remain founder/ops (🔵, Phase H); `-ServerProfile` runs need `PYNAPLI_API_PROFILING_ENABLED=true`.
- [x] Server-enforced Grid volume caps exist by tier: Free page 60 / depth 60, Social page 90 / depth 600, Select page 120 / uncapped depth. Cursor payloads carry seen-count state so capped tiers cannot bypass depth by asking for larger pages.
- [x] Redis-backed Grid query rate limiting is wired through `RateLimitService` in production dependencies.
- [x] Compose grid uses stable keys.
- [x] Compose grid lifts `rememberLazyGridState()` to the screen-level composable so it survives `GridBody` branch flips (loading/error/loaded) and the profile detail sheet, with `GridScreenScrollPreservationTest` exercising scroll-to-index → open profile sheet → dismiss → assert `firstVisibleItemIndex` and `firstVisibleItemScrollOffset` unchanged. The test runs on the `composeApp` desktop JVM target via `runComposeUiTest`.
- [x] Grid cards use fixed square dimensions.
- [x] Filter updates keep existing state: `GridStateHolder` swaps cards atomically without clearing the visible list, and `LazyVerticalGrid` uses stable `key = card.id` + `Modifier.animateItem()` so card identity is preserved across changes. `GridStateHolderTest.filterChangeKeepsCardsVisibleUntilNewFeedLands` enforces non-clearing at the state-holder level; `GridScreenScrollPreservationTest.filterChangeWithSameFeedKeepsScrollPosition` enforces the rendered scroll position survives a filter change end-to-end.
- [x] The Grid filter sheet is scrollable on constrained Android screens, so lower sections and Clear/Cancel/Apply controls remain reachable. `GridScreenScrollPreservationTest.filtersSheetScrollsToActionButtons` covers this regression, and manual emulator QA confirmed the Lifestyle gate, Logistics, Save preset row, and Apply return path.
- [x] Automated Grid visual-signature regression exists in `GridScreenVisualRegressionTest`: it renders the real `GridScreen` in a fixed mobile viewport with the dark Pynapli theme, captures pixels, and asserts the premium dark surface, visible card grid, online dots, linked-partner accents, readable text, and filter-sheet surface are present. This runs in the Compose desktop JVM test gate and protects the shared UI used by Android.
- [x] Connected Android Grid QA exists at `scripts/android-grid-qa.ps1` with structural golden assertions in `scripts/android-grid-qa-goldens.json`. It runs the installed Android app against local Ktor, captures screenshots/XML/signatures, verifies Grid load, filter sheet, Online empty state, profile detail, filter-clear stability, and profile-close return to the same visible card order/position.
- [x] WebSocket presence deltas update dots without full refresh: server installs Ktor WebSockets at `/v1/presence/stream`, authenticates via the existing Bearer token, and fans out `PresenceDeltaDto` events from `PresenceBroadcaster`. `PresenceService.heartbeat` emits `online=true` deltas; `PresenceOfflineScanner` emits `online=false` deltas every 30s for tracked users that have left Redis. The route applies a per-delta `BlockRepository.isBlockedEitherWay(viewer, target)` privacy filter and a subscription scope so a viewer only receives deltas for userIds they explicitly subscribed to. `PresenceStreamRouteTest` covers happy path, unsubscribed-target filtering, and blocked-relationship filtering; `PresenceOfflineScannerTest` covers offline emission for disappeared users and no-emission for still-online users. The shared `PresenceStreamClient` (in `:shared`) reconnects with exponential backoff (1s→30s) and re-sends the current `userIds` subscription on every flow update, covered by `PresenceStreamClientTest`. `GridStateHolder.applyPresenceDelta` flips the matching card's `online` flag in place — no reorder, no `load()` call — covered by `GridStateHolderTest.presenceDeltaUpdatesOnlineFlagWithoutReorderingOrRefreshing`. Android wires the stream via `AndroidAppGraph.gridPresenceStreamCoordinator` and starts/stops collection in `MainActivity.onStart`/`onStop`. WebSocket transport on Android requires the OkHttp engine because `ktor-client-android` does not support WebSockets in Ktor 3.x.
- [x] Pull-to-refresh is implemented: `GridPullToRefresh` wraps `GridBody` in the Material3 `PullToRefreshBox` (token-styled dark/gold indicator) and calls `GridStateHolder.refresh()`, which re-fetches the current filters non-quietly (surfaces the indicator + errors) while preserving filters and scroll position; stable card keys animate the diff so the grid is never blanked. The indicator shows only when cards already exist (the cold first load uses the body's centered spinner). Covered by `GridStateHolderTest.refreshReFetchesCurrentFiltersAndPreservesThem` and connected Android Grid QA.
- [x] Instant cached first render is implemented: `GridStateHolder.primeFromCacheThenLoad()` renders the last persisted Grid cards immediately (if any) then runs a quiet network refresh that swaps in the live feed atomically — no blank screen, no scroll reset. Cards are persisted on every successful load via `GridSnapshotStore` (`SqlDelightGridSnapshotStore` reuses the `app_meta` KV table as a JSON blob, capped at 60 cards, no migration; `GridProfileCardUi`/tones are `@Serializable`). A decode failure or empty snapshot falls back to a normal load. Covered by `GridStateHolderTest.primeFromCacheRendersCachedCardsImmediatelyThenSwapsInFreshFeed` + `...FallsBackToNormalLoadWhenNoSnapshot`.
- [x] Location updates use thresholds before backend submission and grid refresh.
- [x] Denied-location Android QA exists at `scripts/android-grid-denied-location-qa.ps1`, covering server `LOCATION_REQUIRED`, Android permission denial, absent Grid cards, screenshots/XML, permission dumps, and dev data restoration.
- [ ] Partial: Android background location is avoided and guarded by `scripts/android-location-policy-check.ps1`, including no background permission, no foreground location service, no continuous location updates, and one-shot cancellation on app stop. Battery/profile artifact capture exists at `scripts/android-grid-battery-profile.ps1`, but physical-device battery profiling and store policy review are still needed.
- [x] Thumbnail prefetch is implemented: `planThumbnailPrefetch` derives the next `GRID_THUMBNAIL_PREFETCH_WINDOW` (12) cards beyond the last visible index, deduplicated and skipping null URLs, covered by `ThumbnailPrefetchPlannerTest`. `ProfileGrid` enqueues the planned URLs into the Coil 3 `SingletonImageLoader` from a `LaunchedEffect` keyed on the planned URL list, populating the memory/disk cache without rendering.
- [x] Message rate limiting exists.
- [x] Ranking does not reward spam volume.
- [x] Default deck / never-empty: with no filters the Grid is an unfiltered proximity query (empty filter sets short-circuit to "match everyone" via the cardinality gate), and `GridService.queryGrid` widens once to `MAX_RADIUS_METERS` when the **first page** at the requested radius returns zero candidates. The widened page is edge-fill only (`nextCursor = null`, no depth-limit claim) so the wide radius never leaks into the narrow-radius cursor scope; explicit structured filters still apply at the wide radius (widening relaxes distance only); viewer-specific post-filter empties (block/hidden, `onlineOnly` with nobody online) keep their honest empty state. Covered by `GridServiceTest.queryGridWidensToMaxRadiusWhenFirstPageEmpty`, `...DoesNotPaginateAWidenedFallbackPage`, and `...DoesNotWidenWhenFirstPageHasCards`.

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
