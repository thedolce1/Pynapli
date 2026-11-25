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
the tier depth cap can make a final page shorter. A sparse-area widened answer (see "Default Deck /
Never-Empty") pages under its own scope: the 100 km step plus the radius the viewer asked for
(`gridScopeHash(widenedFromRadiusMeters = ...)`). The client keeps sending its own radius; a cursor that
does not decode under the normal scope is tried under the widened one, so a widened cursor only ever
continues the request that produced it.

### Location privacy (load-bearing)

An ordinary account can report any location and read every field, so every Grid observable —
displayed distance, order, radius membership, cursor — is a function of (viewer cell, target
cell) only:

- **Cell:** the effective location (active travel point, else home GPS) snapped to a 0.01-degree
  grid (~1.1 km N-S) by `pynapli_location_cell` (V94; pinned in V116 to the explicit
  `round(x / 0.01) * 0.01` formula, round to nearest, ties to even, so a PostGIS upgrade cannot move
  it). Ranking starts from the viewer's cell centre; the shared rank window is keyed by that cell,
  so viewers sharing a window share an origin.
- **Stored point:** since V116 `user_locations.geom` holds only the cell centre. Clients snap the fix
  with `com.pynapli.shared.location.LocationCell` (the Kotlin twin, checked against the SQL by
  `LocationCellStorageIntegrationTest`) and send no accuracy. `JdbcLocationRepository` snaps again in
  SQL, stores `accuracy_meters = NULL` and sets `geohash` to the cell label. Nothing observable
  changed: every reader already went through the cell helpers, and snapping is idempotent. The 1.6 km
  pre-filter padding was sized for raw points and is now more than enough; it is left as is.
  `travel_locations.geom` (a picked place, not a device fix) is stored as given and read through the
  same cells.
- **Displayed distance:** cell to cell, then bucketed: under 2 km reads "1 km", whole km to 10 km,
  5 km steps to 50 km, 10 km steps beyond. Deterministic (no per-request noise to average out).
- **Order:** rank tier first (boosted, everyone else, demoted; see "Ranking"), then a whole-km bucket
  of the cell-to-cell distance, then a stable pseudo-random tiebreak seeded by the viewer cell (V95).
  The tier depends only on the candidate, never on where anyone is. Someone who hides distance gets an order
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

**What ships (plan G3, 2026-11-24): distance-first, like Grindr.** Grindr does not sort by online, so
neither do we; Online is a one-tap filter. The keyset order is:

1. **Rank tier** (`boost_bucket`, the first keyset column; the name is kept so older cursors and cached
   windows still decode): `0` boosted (an active boost outranks everything, demotion included), `1`
   everyone else, `2` demoted. Demoted (`GridActivitySql.demoted`) means no activity for 30 days (last
   activity is the later of `user_last_active` and account creation) or no approved, ready, visible
   public photo. A thin member base should show real, active, pictured people first; demoted people are
   never removed, they come after everyone else.
2. Whole-km distance bucket (or a location-independent bucket for people who hide distance).
3. Stable tiebreak seeded by the viewer cell, then user id.

The tier is a function of the candidate only, so the shared, cross-viewer rank window stays shareable and
its cache key is unchanged. `GridRankOrderPinTest` pins it (re-pinned deliberately for G3).

Not copied from Grindr: removing offline people after an hour or two (it would empty a sparse grid) and a
map view (precise-looking pins work against location privacy).

The rest of this section is the longer-term ranking intent, not shipped behaviour.

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

What ships (plan G2/G4/G11, 2026-11-24):

- **Online** (green dot): a Redis key `presence:user:{id}` with a **15-minute** TTL
  (`RedisPresenceRepository.ONLINE_TTL_SECONDS = 900`), refreshed by any authenticated HTTP request
  (`AuthService` `onAuthenticated` hook), by every open realtime socket (opened, then its 60 s re-auth
  tick), and by `POST /v1/presence/heartbeat`, which a foregrounded app calls about every 60 s so an idle
  screen keeps its dot. Writes are claimed per user at most once a minute per instance
  (`PresenceService.claimRefresh`) and run off the request path (bounded launcher), so a request never
  waits on Redis for presence. One refresh is one `SET … EX 900 GET` and, at most once a minute, one
  `user_last_active` upsert.
- **Came online / went offline:** a refresh whose `SET … GET` finds no previous key emits a "came
  online" delta; `PresenceOfflineScanner` emits "went offline" when the key has expired. Both carry the
  time cut to the minute. Neither is emitted for a member who hides their activity (incognito), and the
  check fails closed.
- **Last activity** (`GridActivitySql.lastActiveAt`): the later of `user_last_active` (V106, backfilled
  from sessions in V127) and account creation. Server-side only, never returned.
- **Card activity key** (`GridCardDto.activeAgo`, `GridActiveAgo`): `online`, `15m`, `1h`, `3h`, `today`,
  `week`, or null (older than 7 days, never seen, or incognito). Each key covers the window up to its bound
  after the previous one. Because the dot already holds 15 minutes, `15m` shows only when presence and
  last activity disagree (a Redis flush); the usual first offline key is `1h`. A hidden member keeps
  today's online-dot rules but gets no key.
- **Fresh** (`GridCardDto.fresh`, the `freshOnly` chip): the account is under 72 hours old or added an
  approved public photo in the last 24 hours (album `added_at`; there is no approval timestamp), and was
  active in the last 24 hours. Looser than Grindr's 1 hour because we are sparser. Replaces "Recently
  joined"; `recentlyJoinedOnly` from older clients and saved filters is read as `freshOnly`.
- **Dormant:** no activity for 30 days: demoted in the rank (see "Ranking"), never hidden.
- Profile detail keeps its own `lastActive` buckets (`LastActiveBucket`, which also reads session
  activity).

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
- Denied location keeps the Grid in a recovery state: no Grid cards are shown, the `Use current location` action remains available, and the app displays `Location permission is required to load nearby profiles.` after Android permission denial. Under it the state says neutrally what not sharing costs ("Without your location, you won't appear to anyone nearby."), and when asking again cannot help (permission blocked for good, location services off, a browser that remembers the refusal) it offers "Open Settings" on Android or directions to the site setting on the web (ONBOARDING_AUTH_SPEC, Permissions).
- Resume refresh (2026-11-24, Section 1): on app resume (Android `onStart`, and once at cold start after the session settles) and on a web tab becoming visible (and a signed-in page load), the silent refresh runs iff `silentLocationRefreshAllowed` holds: signed in, not in the sign-up stepper, the OS or browser permission *already* granted, and the server already holding a current location for this account, seen as the last live Grid answer not being `LOCATION_REQUIRED` (`GridStateHolder.serverHasLocation`: null until a live answer and after sign-out, so cached cards never count; no location is echoed to the client). Nothing about it is stored on the device. A cold start waits up to 20 s for that first live answer. A member who skipped at sign-up has no server location, so another account's OS grant on the same device never sends theirs. Travel needs no exception: a Travel-mode viewer without a current location is answered `LOCATION_REQUIRED` (`GridTravelOnlyViewerTest`), because the viewer lookup reads `user_locations` and only overlays the travel point. Accepted residual: an existing member who signs in on someone else's device where location is already granted gets their location refreshed without a new prompt, as on Grindr. Such a member gets a fresh fix, silently: no progress, no error text, never a prompt (Android checks the permission; the web checks `navigator.permissions` reports `granted` first). Android uses a last-known fix under 2 minutes old, else one cancellable `getCurrentLocation` that `onStop` cancels. `LocationStateHolder.refreshInBackground` applies the thresholds above plus one more rule: a fix still in the last sent 0.01-degree cell is not sent. When it is sent, the grid refreshes quietly (cards swap in place). The movement anchor stays in memory only, so the first fix after a process start is always sent. Coordinates are never logged.
- Sign-out resets `LocationStateHolder` (state, anchor, and any submit still in flight), so the next account on the device sends its own first fix.

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

### Profile open (2026-11-24, Section 2, plan G5/G6)

- A profile opened from the Grid (Nearby or Favorites) is one page of a horizontal pager over that
  list, in grid order, starting at the tapped card (`profile/GridProfileBrowser.kt`, rules in
  `ProfilePagerController`). Phones show it full screen (`PynapliBottomSheet(compactFullScreen)`:
  back, the close button and a downward drag close it); Medium+ windows keep the right-hand panel
  with Previous/Next and the Left/Right arrow keys (only while the panel itself has focus, so the
  composer keeps its keys; Esc closes).
- A page paints from its card at once. The full profile (`GET /v1/profiles/{id}`), and with it the
  server's profile view, loads only when a page **settles**; pages passed through mid-swipe fetch
  and record nothing, and the tapped card is not fetched twice. A slower answer for an earlier page
  is dropped (`ProfileDetailStateHolder.open`).
- Settling within 3 cards of the end calls the list's own `loadMore()` (the grid or Favorites state
  holder), once per list size. The grid's scroll position is not moved by paging; closing returns
  to where the viewer was.
- Photos inside a pager page change by tapping the left/right half (pips stay); the horizontal
  swipe belongs to the pager.
- Block or hide removes the card from the Grid, Favorites and the cold-start cache and the pager
  moves on to the next person (the previous one at the end; it closes when nobody is left).
  Reporting then swiping on drops the reported person the same way closing does. A card leaving the
  list for any other reason (un-starred in Favorites, a quiet refresh) stays on screen until the
  viewer moves on.
- A profile opened from outside the list (a linked partner's mini) keeps the single-profile sheet.
- Actions (G6): when the profile allows grid messages, a "Say something…" composer is pinned at the
  foot; sending opens or reuses the Grid conversation and posts the message (MESSAGING_SPEC
  "Starting from a profile"), then lands in the thread. When the server returns
  `messagingUnavailableReason = "VERIFY_TO_MESSAGE"`, the profile offers "Verify to message", which
  opens the photo-verification sheet; any other refusal keeps the disabled Message button. Spark is
  one tap. Favourite and hide/block/report stay in the top bar.
- Locked cards (Section 3): `ProfilePagerController.isLocked` is the per-card seam; a locked page
  shows the paywall and never fetches a profile. Nothing is locked yet.

### Launch path (2026-11-24, Section 1, plan G1)

- A stored session goes straight to the shell; `GET /v1/me` runs in the background, and only a
  terminal auth answer signs out (ONBOARDING_AUTH_SPEC, Returning members). The cached grid
  therefore paints without waiting on the network.
- While the cold-start snapshot is read, the grid shows the tile skeleton (the same phase as a first
  network load), and cached cards crossfade in over it.
- The first page joins a load already in flight: when the Location step's `submitAndRefresh` (or a
  resume location refresh) has a request on the wire for the current filters, `GridScreen`'s
  `primeFromCacheThenLoad` waits for it instead of starting a second request that would supersede
  it. Only that compose-time path joins. A filter change, pull-to-refresh or location move always
  calls `load()`, which bumps the generation and drops the older answer. A joined load that is
  cancelled before it answers makes the grid load for itself.

### Liveness (2026-11-24, Section 2, plan G7)

- Cards never move under the finger. While the Grid is on screen, Nearby is showing and the app is
  in front, `GridStateHolder.runLiveRefresh` asks for the first page every 60 s
  (`LIVE_REFRESH_INTERVAL`) through `refreshLive`: cards already shown update in place (online dot,
  `activeAgo`, New marker, photo), shown cards missing from page one stay (they may be on page two),
  and people not on screen are held back behind an "N new nearby" pill (`GridNewNearbyPill`).
  Tapping the pill, pull-to-refresh or the wide-window Refresh button applies the held-back order and
  scrolls to the top.
- `refreshLive` is not a `load()`: it never bumps the feed generation (a page being appended
  survives), never touches filters, spinner or errors, fails silently, and skips while any other
  Grid request is on the wire. A load, filter change, travel change (`invalidate`), block/hide (for
  that person) or sign-out drops the held-back page.
- The loop rides with the realtime jobs (`PynapliCommonGraph.startRealtime`), so Android and iOS
  cancel it in the background; the web shell pauses it on a hidden tab (`setAppVisible`).
- Resume and tab return: presence older than 60 s refreshes in place; an order older than 15 min
  (`RESULTS_EXPIRE_AFTER`) is replaced quietly. Two time marks keep the rules apart (last live answer
  vs. last applied order). App resume refreshes only the Grid on screen; a later tab return covers it.
- Cached cards (cold-start snapshot) paint with presence unknown (no dot, no active caption, no New
  marker) until the first live answer.
- A presence "went offline" delta sets `activeAgo = "15m"` (the server sends it when its online hold
  ends); "came online" sets `"online"`.

### Header, tile and web cache (2026-11-24, Section 2, plans G8, G11, G12)

- Header: avatar, the location pill ("Nearby", or the Travel city while Travel mode is on; never a
  precise place) which opens the same `TravelModeSheet` and `TravelLocationStateHolder` Swipe uses
  (Select paywall focus; every travel change reloads the Grid and marks the Swipe deck stale), then
  Refresh (wide windows) and Tonight; no header Filters icon. Below it one chip row for both views: Filters (with its active count),
  Online, Favorites (a view switch, not a filter; it replaced the Nearby/Favorites switch), Fresh,
  Tonight, Verified. Active chips carry a check as well as the accent outline. Singles and Linked
  live in the sheet's Who section; distance lives in the sheet. A filter chip tapped while Favorites
  shows returns to Nearby.
- Tile: online dot, name, verified check, distance bucket, partner mini, Tonight moon, Visiting
  pill, a gold "New" tag when the server marks the card `fresh`, and an "Active 15m ago / 1h / 3h /
  today / this week" caption from `activeAgo` when not online. No photo is a neutral raised surface
  with a silhouette, the same for everyone (no colour by list position).
- Web keeps the cold-start snapshot in `sessionStorage` only (`sessionStorageGridSnapshotStore`):
  a reload paints instantly, closing the tab forgets it, and any `localStorage` copy under the key is
  removed. It is stored with the account's id and painted only for that account
  (`AccountBoundGridSnapshotStore`): a cross-tab account switch, or no session, reads as empty and
  removes it; sign-out clears it.
- Empty: "No one nearby yet" only when there are no cards, no active filter and no error; the server
  widens a sparse first page, the client only renders. When the server says it widened
  (`GridQueryResponseDto.widened`), the context strip adds "Showing people farther away".
- Presence heartbeat (plan G2, client): `PresenceHeartbeat` sends `POST /v1/presence/heartbeat`
  once on becoming eligible and about every 60 s while signed in, out of the sign-up stepper and
  visible; launched with the realtime jobs (stopped in the background on Android and iOS, paused on
  a hidden web tab); never retried (`PresenceHeartbeatTest`).

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

Paid filters and persistence (2026-11-24, Section 2, plan G9):

- The paid set mirrors the server's `DiscoveryFilters.requiresAdvancedEntitlement` exactly: any
  Lifestyle tag, and every Logistics tag except "Public meet first". Choosing one clears only that
  filter (`GridFilters.withoutPaidFilters`), keeps the viewer's other choices, shows the paywall row
  and opens the plans (`GridEvent.OpenPaywall`). The client always asks; a server
  `ENTITLEMENT_REQUIRED` strips the paid filters and re-asks once for the rest (the client's view
  of the plan can lag a purchase, so it never pre-strips). The plans open only for filters the
  viewer just applied, never for a cold-start restore after a plan lapsed or a quiet refresh (those
  still strip and show the row). A refusal superseded by a newer choice or sign-out changes
  nothing. `AGE_VERIFICATION_REQUIRED` takes out only the lifestyle tags. The sheet shows a lock on
  paid chips for Free viewers (the profile hub's plan, `GridFilters.advancedFiltersAllowedFor`;
  display only, unknown shows none).
- Filters persist across cold starts on the device only (`GridFilterStore`): SQLDelight `app_meta`
  on Android and iOS, a file on the dev desktop shell, `localStorage` on web. Stored in the saved
  preset shape (no cursor, no cards, no place), restored before the first query, cleared on sign-out.
  Bound to the signed-in account (`AccountBoundGridFilterStore`, privacy review F9): stored with
  the account's id and restored only for it; a cross-tab account switch, no session or a corrupt or
  unbound blob reads as nothing saved and is deleted. A filter read or write already under way at
  sign-out is dropped (account epoch), and the sign-out clear always lands after it (one lock).
- Fresh (`freshOnly`) replaced "Recently joined"; a preset saved with `recentlyJoinedOnly` restores
  as Fresh.

## Default Deck / Never-Empty

The Grid must never look broken because a viewer left filters at their defaults or in a sparse
area. Two layers guarantee this:

1. **Default = all nearby.** With no filters selected, the Grid is an unfiltered proximity query:
   every discoverable, eligible profile within the viewer's radius, ranked normally (Boosted →
   proximity → recency). No structured filter means no `WHERE` predicate is applied for that
   dimension — the cardinality gate (`cardinality(?::text[]) = 0 OR ...`) short-circuits an empty
   filter to "match everyone", so an empty filter set never narrows the result.
2. **Widen when sparse (plan G10, 2026-11-24; was widen-on-empty).** If the **first page** at the
   requested radius would show fewer than 12 people (`SPARSE_FIRST_PAGE`) and the radius is below the
   maximum (`MAX_RADIUS_METERS = 100_000`), the server ranks once more at the maximum radius and serves
   that instead. Distance-first ordering makes it a superset that starts with the same nearby people.
   Server-side and transparent to the client. It mirrors the Swipe deck's radius relaxation
   (`SwipeDeckRelaxation.relaxRadius`).

Rules for widening (`GridService.queryGrid`):

- **First page only.** It fires when `cursor == null`. A later short page is honest exhaustion of a
  scrolled grid — never widen mid-pagination.
- **It pages normally.** The widened answer carries a cursor sealed to the widened scope (100 km plus
  the radius asked for; see "Pagination"), and the follow-up pages are ranked at 100 km. (Before G10 a
  widened page was edge-fill only with `nextCursor = null`.)
- **Respects explicit filters.** Widening only relaxes *distance*. Any structured filters the viewer
  did select (gender, orientation, intents, lifestyle, verified, online-only, Fresh, etc.) still apply
  at the wide radius — a filter is still a promise.
- **Counts what the viewer would see.** The 12 are counted after blocks, hides, contact blocks and the
  online filter. This deliberately replaces the earlier "honest empties" rule (viewer-specific empties
  never widened): with a threshold rather than zero, the number that matters is the number of people on
  screen, so "Online" in a quiet area now shows online people farther out instead of two tiles.
- **Location privacy is unchanged:** the wide query is the same cell-to-cell rank at a fixed radius
  step, so it reveals nothing a viewer could not get by choosing 100 km themselves.
- **Cost:** a sparse first page costs two rank queries (the narrow one is small by definition; the
  100 km one is the same query the load test measures), each served from the shared 5 s window when
  another viewer in the cell asked first.

## Realtime Updates

Use WebSocket deltas for:

- Online/offline state
- Recently active changes
- Boost starts/ends
- Viewed/flirted activity
- Incoming message badges
- Typing status

Do not use WebSockets to stream full grid reorders constantly.

Presence deltas (`/v1/presence/stream`, `PresenceDeltaDto`) are sent for both transitions: `online =
true` when someone comes online, `online = false` when their 15-minute key lapses. `lastActiveAt` is
cut to the minute. Incognito members' transitions are never sent. Presence delta shape:

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
  travel-active viewers bypass it. A page is served from the window only when a window row remains
  past it or the window came back short (it holds everyone in range); otherwise that page is ranked
  for the viewer directly, so a cached walk equals an uncached one and never ends early.
  **The Redis path fails open** to direct queries on any Redis error.
- Plan G3/G10 cost (2026-11-24, not yet measured): the rank tier adds, per in-radius candidate on a
  cache miss, one `user_last_active` primary-key probe and the approved-photo `EXISTS` (one
  `media_albums (owner_user_id, kind)` probe, which stops there for people with no album, else the album
  item and asset probes), after the boost check. On the 5,000-candidate load seed (no photos) that is
  about two extra index probes per candidate. Page hydration adds a `users`, `discovery_settings` and
  `user_last_active` lookup plus the Fresh photo check per page card. The load test queries at 100 km,
  so it never widens; a widened first page elsewhere costs at most one extra rank of that same size. The
  500-concurrent Grid load test (including presence, now one `SET … GET` per user a minute) must be
  rerun before this is called inside budget.

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
- [x] Pull-to-refresh (`GridPullToRefresh`) preserves filters; a Refresh button covers pointer
  devices. Since 2026-11-24 an explicit refresh applies the new order and returns to the top (plan
  G7); background refreshes never move cards.
- [x] Instant cached first render (`GridSnapshotStore`, last 60 cards) followed by a quiet refresh.
- [x] Location updates are thresholded before submission; denied-location QA
  (`scripts/android-grid-denied-location-qa.ps1`).
- [ ] Partial: no background location, guarded by `scripts/android-location-policy-check.ps1`;
  physical-device battery profiling (`scripts/android-grid-battery-profile.ps1`) and store policy
  review remain.
- [x] Thumbnail prefetch of the next 12 cards into Coil's cache.
- [x] Message rate limiting; ranking does not reward spam volume.
- [x] Never-empty: with no filters the Grid is a plain proximity query, and a **first page** showing
  fewer than 12 people widens once to `MAX_RADIUS_METERS` and pages normally under a widened cursor
  scope (plan G10; `GridServiceTest`). Explicit filters still apply.
- [x] Presence like Grindr's (plan G2): any authenticated request, open socket or
  `POST /v1/presence/heartbeat` keeps the dot for 15 minutes; the stream sends came-online and
  went-offline, never for incognito members (`PresenceServiceTest`). Grid load test presence run pending.
- [x] Rank tier (plan G3): boosted, normal, then demoted (dormant 30 days or no approved photo), pinned
  in `GridRankOrderPinTest`.
- [x] Fresh (plan G4): server-side rule for the card's `fresh` flag and the `freshOnly` chip, which
  replaced "Recently joined" (`GridFilterDiscoveryTest`).
- [x] Card `activeAgo` key (plan G11; `GridActiveAgoIntegrationTest`).
- [ ] Larger dense-metro and production-hardware load runs; Redis hit rate under multi-instance load.
- [x] Favorites (V105): private star in profile detail; `PUT`/`DELETE /v1/favorites/{id}` (idempotent,
  no activity or notification, cap 500, free) and a paged `GET /v1/favorites` of Grid cards, online first
  then the public distance bucket; hidden-distance people get a location-independent position; Grid
  visibility rules on every read (blocks, hides, incognito, banned, deleted, quarantined drop out);
  sealed viewer-bound cursor (`FavoriteServiceIntegrationTest`). A Favorites chip in the Grid's chip
  row switches views (it replaced the Nearby/Favorites switch, 2026-11-24).
- [x] Favorites: online dots update live through the Grid presence stream while the view is showing;
  the starred list is in the account data export. A long-press quick menu on Grid cards is deliberately
  not built: one tap opens the profile, which holds the star and every other action.
- [x] Last seen (V106): profile detail shows a server bucket (`online_now`, `active_15m`, `active_today`,
  `active_week`) from a throttled heartbeat and session activity; never a timestamp; none for incognito
  (`LastActiveIntegrationTest`). The card face shows the Grid card's own coarse `activeAgo` bucket
  (2026-11-24, plan G11), never a timestamp.
- [x] Tab return keeps the Grid's cards, order and scroll (presence refreshes in place after 60 s; the
  order is replaced quietly only after 15 minutes); a block or
  hide also leaves the cold-start cache; sign-out or a dead session wipes the Grid and its cache
  (`GridInstantLoadTest`).
- [x] A failed next page stops auto-paging and offers Retry; an unfiltered empty Grid shows an empty state
  (`GridPagingAndEmptyStateTest`, `GridPhaseTest`: "No one nearby yet" only with no cards, filters or
  error).
- [x] Liveness (plan G7): 60 s quiet in-place refresh while visible and in front, "N new nearby" pill,
  60 s resume refresh, cached presence unknown (`GridLivenessTest`, virtual clock).
- [x] Header (plan G8): Filters count + Online/Favorites/Fresh/Tonight/Verified chips; Nearby or
  Travel-city pill opens Travel mode from the Grid.
- [x] Filters (plan G9): a paid filter clears only itself and opens the paywall; locks in the sheet;
  filters persist locally and clear on sign-out (`GridPaidFiltersAndPersistenceTest`).
- [x] Tile (plan G11): New marker, active-ago caption, neutral no-photo silhouette
  (`GridCardLivenessMarksTest`); DESIGN_SPEC allows the Tonight moon, Visiting pill and ~1 dp gap.
- [x] Web cached first paint in `sessionStorage` only (plan G12), bound to the signed-in account
  (`AccountBoundGridSnapshotStoreTest`).
- [x] Free-cap row names the plan that lifts the cap (Social from Free, Select from Social;
  `GridPaywallTierTest`). The row itself changes in Section 3.
- [ ] Section 2: emulator + browser QA of the pill, chips, travel pill, locks and profile pager.
- [x] Swipe between profiles in grid order, full screen on phones, panel with Previous/Next and arrow keys
  on wider windows; fetch on settle only; next page at the end; block/hide moves on; tap-to-change photos;
  inline first-message composer and "Verify to message" (`ProfilePagerControllerTest`,
  `ProfileComposerStateHolderTest`, `ProfileDetailPagingTest`). Emulator and browser walk still owed.
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
