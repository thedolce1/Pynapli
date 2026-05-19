# Pynapli Implementation Audit

Date: 2026-05-19

## Purpose

This audit compares the current Pynapli MVP implementation against the root specification documents. The goal is to slow down where quality matters: correctness, safety, security, performance, tests, and product alignment.

The specs remain the source of truth. The implementation should move only when the code, tests, and specs agree.

## Ground Rules Going Forward

- Fix correctness and test issues as soon as they are found.
- Treat a missing, skipped, misnamed, or non-matching test as a real problem until explained or fixed.
- Do not assume dependency compatibility, cloud behavior, billing behavior, Docker behavior, or platform policy. Use official documentation before changing those areas.
- Keep product rules server-authoritative when they affect safety, privacy, monetization, blocks, matches, albums, or partner links.
- Keep safety, report, block, consent, and support flows free.
- Keep Pynapli's core model intact: every adult has their own account, their own voice, and optional linked-partner context.

## Evidence Used

Primary local specs:

- `PYNAPLI_PRODUCT_SPEC.md`
- `GRID_IMPLEMENTATION_SPEC.md`
- `SWIPE_IMPLEMENTATION_SPEC.md`
- `MESSAGING_SPEC.md`
- `ACTIVITY_SPEC.md`
- `PROFILE_SETTINGS_SPEC.md`
- `MONETIZATION_SPEC.md`
- `TRUST_AND_SAFETY_SPEC.md`
- `BACKEND_ARCHITECTURE_SPEC.md`
- `API_CONTRACT_SPEC.md`
- `MOBILE_APP_ARCHITECTURE_SPEC.md`
- `PROJECT_STRUCTURE_AND_DEPENDENCIES_SPEC.md`
- `PRODUCTION_READINESS_AND_QUALITY_SPEC.md`
- `TESTING_STRATEGY_SPEC.md`
- `DEVOPS_INFRA_SETUP_SPEC.md`
- `OFFICIAL_TECH_REFERENCE_GUIDE.md`

Current code evidence:

- Kotlin Multiplatform shared module, Compose app module, Android app module, Ktor server module.
- PostgreSQL/PostGIS migrations through `server/src/main/resources/db/migration/V53__add_swipe_query_covering_indexes.sql`.
- Redis-backed presence/rate-limit paths.
- Firebase Cloud Messaging integration path.
- Google Play Billing client and server purchase verification path.
- Ktor domain modules for auth, profile, location, grid, swipe, messaging, activity, media, safety, subscriptions, notifications, profile links, moderation, and health.

## Current Verification Baseline

Latest known green baseline after restoring server JUnit 4 coverage:

- `:server:test` runs the restored JUnit 4 and JUnit 5 server tests.
- Server suite count observed: 248 tests.
- Targeted server test filters now work after adding the JUnit Vintage engine.
- `scripts/verify.ps1` passed.
- Full local gate used by `scripts/verify.ps1`:
  - `:shared:jvmTest`
  - `:shared:testAndroidHostTest`
  - `:server:test`
  - `:composeApp:testAndroidHostTest`
  - `:androidApp:assembleDebug`
- Ktor `/health` was confirmed healthy after the last restart.
- Age verification targeted tests passed for service rules, JDBC migration/profile timestamp side effects, and Profile Hub state behavior.
- Signup age-gate targeted tests passed for Auth service validation, JDBC persistence, and shared request forwarding.
- Grid API load harness exists at `scripts/grid-api-load-test.ps1`.
- Latest 2026-05-13 local full Grid API run used 5,000 candidates, 1,000 online candidates, 100 mixed-tier viewers, and 100-way concurrency.
- Opt-in server profiling now exists for Grid/profile/presence hot paths. Pre-optimization profiling showed Grid candidate-query over-hydration and Hikari waiting as the dominant hot path.
- The Grid SQL hot path now ranks/caps candidate IDs first and hydrates profile photo plus linked-partner card details only for the returned page. A follow-up side-query pass added a short-lived server entitlement cache, one Redis Lua call for rate limits, one profile-view eligibility/upsert statement, a combined notification delivery gate query, and bounded background profile-view recording for production profile opens.
- A focused `EXPLAIN`/index pass added V43 covering partial indexes for active user lookup, visible profile card lookup, and visible Grid discovery lookup. The load harness and dense repository performance tests now refresh planner statistics after bulk seeding.
- Latest warm profiled API run produced zero HTTP errors with 5,000 candidates, 1,000 online candidates, 100 mixed-tier viewers, and 100-way concurrency. Client p95: Grid initial 450.4ms, Grid filter 354.5ms, page two 477.0ms, profile open 138.8ms, presence heartbeat 118.0ms. Server route p95: Grid initial 407.3ms, Grid filter 293.1ms, page two 417.3ms, profile open 93.2ms, presence heartbeat 41.0ms.
- Connected Android Grid QA exists at `scripts/android-grid-qa.ps1`. It seeds dev data, builds/installs the debug APK unless skipped, logs into the installed app on a connected emulator/device, captures Grid/filter/profile screenshots, checks for app error text, verifies structural screenshot golden signatures, and verifies profile close plus filter clear return to the same visible Grid card sequence.
- Grid API load artifacts are now durable: `scripts/grid-api-load-test.ps1` writes `summary.json` and `results.csv`, supports named output roots, and has a `-ColdCache` mode that clears Redis before a proof run.
- Latest 2026-05-14 500-concurrent local API proofs used 5,000 candidates, 1,000 online presence keys, 500 mixed-tier viewers, and 500-way concurrency. Warm run: zero HTTP errors; p95 Grid initial 838.3ms, filter 702.6ms, page two 943.4ms, presence 220.3ms, profile open 318.9ms. Cold Redis-cleared run: zero HTTP errors; p95 Grid initial 638.2ms, filter 492.0ms, page two 709.8ms, presence 144.4ms, profile open 167.9ms.
- Android Grid location policy checks exist at `scripts/android-location-policy-check.ps1` and are included in `scripts/verify.ps1`; the check rejects background location, foreground location service use, continuous updates, and missing one-shot cancellation on app stop.
- Android denied-location QA exists at `scripts/android-grid-denied-location-qa.ps1`. Latest local emulator run on 2026-05-14 passed with screenshots/XML/permission dumps under `artifacts/android-grid-denied-location-qa/20260514-091231`.
- Android battery/location profile capture exists at `scripts/android-grid-battery-profile.ps1`. Latest local emulator smoke on 2026-05-14 completed under `artifacts/android-grid-battery-profile/20260514-091341`, but this is harness evidence only; physical-device battery profiling remains required.
- Swipe Rewind targeted coverage now exists for Social/Select entitlement, latest eligible Like/Pass restoration, fresh restored card tokens, matched-action protection, old action-id retry suppression, local queued-action rollback, and the desktop Swipe action row visual/interaction path.
- Activity feed service coverage now proves tab routing, signed viewer/tab-scoped cursor pagination, reveal-depth continuation across pages, invalid-tab/cursor rejection, and dismiss behavior.
- Activity repository integration coverage now proves the real JDBC feed query applies event-type tabs, keyset cursor pagination, newest-first ordering, and owned soft-dismiss behavior.
- Latest Activity slice verification on 2026-05-19: targeted `ActivityServiceTest`, targeted `ActivityRepositoryIntegrationTest`, full `:server:test`, and `:shared:jvmTest` all passed.

Current CI state:

- `.github/workflows/ci.yml` now runs the same Gradle verification gate as `scripts/verify.ps1`.
- Docker service startup remains local-script behavior. If server integration tests begin requiring live containers in CI, the workflow should add explicit Postgres/Redis services.

Current Swipe scale/proof additions:

- `scripts/swipe-api-load-test.ps1` now covers core deck/action/rewind paths plus optional extended Boost, Top Picks, and Travel scenarios. It writes `summary.json`/`results.csv`, fails on unexpected scenario errors, supports core-only runs through `-SkipExtendedScenarios`, and cleans deterministic load users by the same hashed email identity format the server uses.
- `JdbcSwipeRepository` keeps the rank cache usable when travel mode exists: viewer travel location is part of the cache key, active travel targets are fetched through a bounded supplement query, and local cached candidates are merged with visiting candidates before final ordering.
- 2026-05-18 proof artifacts: 100-concurrent extended run passed all budgets at `artifacts/swipe-api-load/20260518-210752`; 500-concurrent warm core run passed all budgets at `artifacts/swipe-api-load/20260518-210836`; Ktor-restarted true-cold 500-concurrent core run passed all budgets at `artifacts/swipe-api-load/20260518-211103`.
- Connected Android Swipe screenshot/gesture/golden QA passed on emulator `emulator-5554` at `artifacts/android-swipe-qa/20260518-214507`: the script built/installed debug APK, logged in, opened Swipe, captured structural golden screenshots/XML, opened filters and travel mode, opened linked-partner preview, opened the Intro Note dialog, and proved left/right gestures advance the deck.
- Connected Android Swipe queue QA passed on emulator `emulator-5554` at `artifacts/android-swipe-queue-qa/20260518-212037`: the script built/installed debug APK, logged in, opened Swipe, verified linked-card rendering, queued a recoverable Like failure, force-stopped/relaunched, verified the pending action survived, retried, and cleared the queue.

## Alignment Matrix

| Area | Status | What Is In Place | Gaps / Notes |
| --- | --- | --- | --- |
| Product model | Aligned | Individual autonomous accounts, optional linked partner, linked partner preview, partner chat invite flow, linked subscription benefit. | Keep avoiding couple-owned accounts or couple cards. |
| Language and positioning | Mostly aligned | Specs define LS-aware, classy, non-cheesy language. | Needs continuous UI copy review as screens mature. |
| Frontend design direction | Partially implemented | Subdued Compose surfaces, bottom nav, header profile entry, grid/swipe/messages/activity/store/settings surfaces, automated Grid and Swipe visual-signature regressions over the shared Compose UI, connected Android structural golden assertions for the installed app, and denied-location screenshot QA. | Needs real-device screenshot review and wider flow coverage before visual polish can be called complete. |
| Auth and onboarding | Partially implemented | Email signup/login/refresh/logout, dev login, session handling, signup DOB, explicit 18+ confirmation, Terms acceptance, Privacy acceptance. | Social auth parity, signup progress resume, and enhanced/provider age verification are not complete. |
| Profile | Partially implemented | Profile edit, discovery settings, profile detail, linked partner display, age-verification entry. | Data export/delete, blocked/hidden management, and full safety center need completion. |
| Location and presence | Partially implemented | Location current endpoint, PostGIS distance queries, Redis presence TTLs, client movement thresholds, Android while-in-use location policy guard, denied-location Android QA, and battery/location profile artifact capture. | Server-side location trust/spoofing checks and physical-device battery profiling remain. |
| Grid | Partially implemented | Lightweight grid DTO, separate profile endpoint, PostGIS radius/distance query, signed stable cursor pagination, tier page/depth caps, Redis-backed query rate limit, fixed square cards, stable Compose keys, shared-UI visual-signature regression, connected Android structural golden QA, Android denied-location QA, Android location policy guard, battery/profile harness, 5,000-candidate dense integration proof, 100-concurrent filtered repository regression proof, 500-concurrent API proof artifacts, cold Redis-cache proof artifacts, opt-in server profiling, two-phase candidate-rank then card-hydration SQL, V43 covering lookup indexes, side-query optimization for entitlement/rate-limit/profile-view paths, planner-stat refresh in dense load tests, client movement-threshold policy, retained lazy grid state while screen is alive, block/risk filtering, partner preview, WebSocket presence deltas, and explicit thumbnail prefetching. | Current 500-concurrent API shape is zero-error locally, but warm p95 variance still exceeds some budgets and needs repeat profiling. Larger dense metros and physical-device battery/location QA are still needed. |
| Swipe | Partially implemented | Swipe deck endpoint, persisted deck sessions/cards, signed deck-session/card-token validation, server-side cursor pagination, client-side auto-paging that loads the next deck page when the visible stack drops below threshold and dedupes against visible/pending cards, presence-driven online indicator on swipe cards (server hydrates `online` from `PresenceService`, client renders a dot next to the name), tapping the linked-partner badge opens a lightweight preview popover that surfaces partner active state (single presence call covers both target and partner user IDs per page) with "View profile" that reuses the existing `openLinkedPartner` route, swipe card now carries a short bio (server-trimmed, 120-char cap) and up to 3 deduped tag chips drawn from `profiles.lifestyle_tags` ∪ `profiles.logistics_tags`, photo carousel hydrates up to 5 ordered profile photos via a single LATERAL subquery and renders them in a `HorizontalPager` with tap-left/tap-right edges to advance (drag stays for like/pass) plus slim page indicators; top card preloads all carousel photos while next cards preload only their cover, verified status is hydrated from `profiles.verified_at IS NOT NULL` and shown as a small accent check icon next to the display name, action endpoint, server-authoritative match creation, immutable normal actions, idempotent swipe action IDs, server-aware Rewind for latest eligible Like/Pass, local queued-action rollback, bounded client action queue, Android SQLDelight pending-action persistence, active-stack image preloading, desktop visual/interaction QA, block/report path, intro notes, pineapple-like inventory, boost influence, partner preview. Phase A engine pass (2026-05-16) brought `swipe_deck_initial` p95 from 3,335ms → 1,158ms (-65%) and `swipe_action_like` p95 from 3,621ms → 1,009ms (-72%) at 100-way concurrent under the new `scripts/swipe-api-load-test.ps1` harness, via two-phase deck SQL (rank → hydrate-31), combined deck-card + target-eligibility precondition check, Hikari pool 10 → 60, request profiling middleware on swipe routes, KNN spatial ordering via PostGIS `<->`, dropping the unused `idx_swipe_deck_cards_session_position` (V46) plus `reWriteBatchedInserts=true`, and aligning the free-tier filter gate with FILTERS_SPEC (`filters.requiresAdvancedEntitlement` instead of `hasAdvancedFilters`). Phase B (FILTERS_SPEC engine) shipped 2026-05-16: expanded `SwipeDeckRequestDto` filter surface (verified/face-photo/age verified + discoveryStyles + intents + lifestyle + logistics tags), `SwipeDealbreakerKey` validation, B3 deck relaxation policy (radius widen + non-dealbreaker filter drop on low-result retry), B4 verified-preferred ranking weight via PostGIS soft-score ORDER. Phase C (UI) shipped 2026-05-16: `SwipeFilters` client model mirrors `DiscoveryFilters`, `SwipeFiltersSheet` ModalBottomSheet exposes the full filter surface with per-section `Lock` dealbreaker chips (auto-drop on filter clear so a stale lock can't silently constrain the deck) and a pre-emptive advanced-filter paywall banner when `requiresAdvancedEntitlement` is true. `SwipeScreen` Filters header button shows the active count; the empty-state copy switches to "No more recommendations match your filters" + Adjust filters CTA per FILTERS_SPEC L487-489 when the relaxed deck still returns nothing. Coverage in `SwipeStateHolderTest` (applyFilters → fresh deck load with mapped DTO, Clear-all restores defaults) and `SwipeFiltersSheetInteractionTest` (chip toggle, dealbreaker lock, auto-clean reversibility, paywall reveal). Phase D (saved swipe filter presets) shipped 2026-05-16: V47 `saved_swipe_filters` table mirrors `saved_grid_filters` (12/user cap, name ≤ 60, case-insensitive unique per user); `/v1/me/swipe-filters` GET/POST/DELETE behind `advancedFiltersAllowed`; `SavedSwipeFilterService` re-validates payload through `discoveryFiltersFrom` + `SwipeDealbreakerKey.ALL`; client `SavedSwipeFilterStateHolder` + "Saved filters" chip row and save-current row in `SwipeFiltersSheet`. Coverage in `SavedSwipeFilterServiceTest` (entitlement gate, dealbreaker key allow-list, name validation, age-verified lifestyle gate, cursor strip, preset cap, duplicate name) and `SavedSwipeFilterRepositoryIntegrationTest` (Testcontainers round-trip, case-insensitive name unique, cross-user delete protection). Pineapple Like inventory-safe rewind shipped 2026-05-16: V48 added `swipe_actions.consumed_inventory_id` pointing at the `premium_inventory.id` decremented at submit; rewind allow-list now accepts `pineapple_like`; `JdbcSwipeRepository.rewindSwipeAction` increments that row's quantity in the same TX as the rewound_at update, so a user who rewinds a Pineapple Like immediately gets the credit back. Coverage in `SwipeServiceTest.paidRewindOfPineappleLikeRefundsConsumedInventoryRow` and `GridAndSwipeRepositoryIntegrationTest` "swipe rewind atomically refunds the consumed Pineapple Like inventory row" (1→0 consume, 0→1 refund verified at the row level). Top Picks shipped 2026-05-17: V49 `top_pick_sessions` + `top_pick_session_cards` store one curated set per viewer per 24h window; `TopPicksService` find-or-generate flow reuses the swipe ranker with `verifiedPreferred=true` (free=1 pick/day, paid=10), persists a backing `swipe_deck_sessions` row so the existing `submitAction` accepts Pick targets, and deterministically re-issues raw card tokens on cached reads via `SwipeDeckTokenCodec`. Endpoint `GET /v1/swipe/picks`; client `TopPicksRow` renders a horizontal LazyRow above the swipe deck. Coverage in `TopPicksServiceTest` (tier limits, session reuse, expiry refresh, LOCATION_REQUIRED, verifiedPreferred bias) and integration in `GridAndSwipeRepositoryIntegrationTest`. iOS swipe queue wiring shipped 2026-05-17: `composeApp:iosMain` exposes `IosSwipeFactory.createSwipeActionQueueStore(database)` / `createSwipeStateHolder(repository, database)`, backed by the existing `SqlDelightSwipeActionQueueStore` in `commonMain` and `IosPynapliDatabaseFactory` in `shared:iosMain`. All three iOS targets (`iosX64`, `iosArm64`, `iosSimulatorArm64`) cross-compile cleanly. Passport / travel mode core shipped 2026-05-17: V50 `travel_locations` table (one active row per user via partial unique index), `TravelLocationService` with Select unlimited plus Social preview gating, endpoints `GET/POST/DELETE /v1/location/travel`, and discovery override across `JdbcGridRepository.queryNearby` + all four `viewer_location` CTEs in `JdbcSwipeRepository` (LEFT JOIN + COALESCE `tl.geom` over `ul.geom`); Top Picks inherits via `queryDeck`. Client `TravelLocationStateHolder` + `TravelModeSheet` with a 10-city preset picker and an entry chip in `SwipeHeader`. Coverage in `TravelLocationServiceTest` (entitlement gates, validation, supersede-on-set, clear) and `GridAndSwipeRepositoryIntegrationTest` "grid and swipe discovery follows active travel_locations row when present". 24h Like tail shipped 2026-05-17: V51 adds `swipe_actions.travel_location_id`; `SwipeService.act` threads the actor's active travel id into `recordSwipeAction`; the `targetLikedActor` match query LEFT JOINs `travel_locations` and enforces `sa.travel_location_id IS NULL OR tl.active_until IS NULL OR tl.active_until > now() - INTERVAL '24 hours'`, so a travel-mode Like only creates a match while the session is active or within 24h after it ends. Coverage in `GridAndSwipeRepositoryIntegrationTest` "travel-mode Like still creates a match when reciprocated within the 24h tail" and "...becomes inert when reciprocated outside the 24h tail". Visiting badge + target-side travel override shipped 2026-05-17: deck SQL LEFT JOINs `travel_locations` as `target_travel` and broadens spatial filter to `ST_DWithin(v.geom, ul.geom, ?) OR (target_travel.geom IS NOT NULL AND ST_DWithin(v.geom, target_travel.geom, ?))` so travel-mode targets appear in destination decks; distance and KNN ordering use `COALESCE(target_travel.geom, ul.geom)`; `SwipeCardDto.visiting` flag drives a "Visiting" pill on the swipe card + a "Visiting" caption on Top Picks thumbs. Coverage in `GridAndSwipeRepositoryIntegrationTest` "deck cards expose visiting flag when target has active travel_locations row". Grid target-side travel override shipped 2026-05-17: `JdbcGridRepository.queryNearby` mirrors the Swipe-deck change — `target_travel` LEFT JOIN, OR'd `ST_DWithin` (both index-eligible), `COALESCE` distance + bucket, `visiting` propagates to `GridCardDto` and a small "Visiting" pill renders on the Grid card overlay. Coverage in `GridAndSwipeRepositoryIntegrationTest` "grid cards expose visiting flag and surface travel-mode targets in destination grids". Region verification gate (SWIPE_IMPLEMENTATION_SPEC L349) shipped as mechanism 2026-05-17: `TravelRegionPolicy` interface returns AgeVerified/None per destination; `TravelLocationService.set` consults it after the entitlement check and verifies the viewer's `age_verified_at` via `GridViewerTrustRepository` (reusing the same trust check as SavedGridFilterService). Production default is `EmptyTravelRegionPolicy` (no destinations gated) so regional safety calls aren't baked into source — ops swaps via DI after a safety review. Coverage in `TravelLocationServiceTest` (gated-region unverified → AGE_VERIFICATION_REQUIRED, gated-region verified → success, unflagged region unverified → success). Social-tier preview (MONETIZATION_SPEC L186) shipped 2026-05-17: `requireTravelTier` widened to allow Social with rate limit (1 activation per 30 days via `countActivationsSince`); second activation returns `TRAVEL_PREVIEW_RATE_LIMITED` (mapped to 429); `clear()` bypasses the limit. Select stays unlimited. Coverage in `TravelLocationServiceTest` (Social can activate once, second activation rate-limited, Social can always clear, Select bypasses limit). "Visible while traveling" toggle (PROFILE_SETTINGS_SPEC L221-245) shipped 2026-05-17: V52 adds `discovery_settings.visible_while_traveling BOOLEAN NOT NULL DEFAULT true`; Grid + Swipe SQL excludes the target when active travel row + opt-out (`AND (target_travel.id IS NULL OR ds.visible_while_traveling = true)`); read/write via `DiscoverySettingsDto.visibleWhileTraveling` + `UpdateDiscoverySettingsRequestDto.visibleWhileTraveling`. Coverage in `GridAndSwipeRepositoryIntegrationTest` "visible_while_traveling=false hides travel-mode targets from every discovery surface". Settings UI toggle shipped 2026-05-17: `ProfileHubScreen` "Discovery visibility" section gains a "Visible while traveling" row; state holder `PrivacySettingsDraft` round-trips through the existing `/v1/me/discovery-settings` save path. | Travel-mode follow-ups: action precondition checks still viewer-only (rarely matters since deck card is token-validated for the target), Psync travel context, map-pin/city-search picker. Process-death device QA on a real iPhone simulator also remains incomplete. |
| Messaging | Mostly implemented for MVP | Direct conversations, grid-open messaging, swipe match gating, intro-note handling, partner chat invite/accept/decline/leave, block-aware conversations, message review path. | WebSocket realtime, typing/read realtime, and deeper consent tests should be added before beta. |
| Activity | Partially implemented | Activity event model, newest-first activity, server tabs (`all`, `views`, `flirts`, `likes`, `matches`, `albums`, `invites`), signed viewer/tab-scoped cursor pagination, entitlement reveal depth across pages, owned soft-dismiss endpoint, flirts, album requests, gating by entitlement, block checks, risk checks, incognito/profile-view suppression, and bounded background profile-view recording for production profile opens. | User-facing Activity screen still needs full tab/dismiss/reveal polish, own Viewed-list disabling needs review, more event producers for Likes/Matches/Invites need end-to-end wiring, and Activity realtime deltas remain future work. |
| Partner links | Strong MVP path | Invite, accept, decline, unlink, one active beneficiary, cooldown/abuse controls, linked partner subscription benefit. | Support/admin review and forced unlink workflow need moderation tooling. |
| Private albums/media | Partially implemented | Upload contract, local-dev upload endpoint, albums, private grants, album request activity. | Production object storage signed URLs, CDN strategy, moderation/scanning policy, and secure media cleanup jobs remain. |
| Notifications | Partially implemented | Preferences, device registration, FCM gateway, grouping/rate-limit logic. | Real device token lifecycle, APNs/iOS path, notification analytics without sensitive payloads, and denied-permission QA remain. |
| Monetization | Partially implemented | Free/Social/Select tiers, server entitlements, linked partner benefit, activity/filters gates, intro notes, pineapple likes, boosts, ads policy, safety-free rule. | StoreKit 2 path is missing. Google Play path exists but needs real console credentials/config validation. Paywall copy needs final Social/Select review in app. |
| Trust and safety | Partially implemented | Risk model/events, Redis rate limits, duplicate message checks, held-message review, reports queue, risk queue, social action restrictions, severe action triggers, partner subscription abuse cooldown, age-verification status/start/admin review/callback path. | Risk middleware, device/account-farm signals, location spoofing signals, verification provider/market integration, appeals/support flow, false-positive metrics, and account notice workflow remain. |
| Admin moderation | Partially implemented | Reports queue/actions/audit, risk review/actions/audit, held message review/release/remove/audit. | Roles/permissions, MFA, user lookup, appeals, subscription support, partner-link review, sensitive reveal reason flow outside held messages, and separated admin API need work. |
| API contract | Partially implemented | `/v1` routes, shared DTOs, Ktor error handling, many server-side entitlement/block/risk checks, signed Grid cursor pagination, signed Swipe deck cursor pagination consumed end-to-end by the Compose client, signed Activity cursor pagination, age-verification endpoints, swipe action idempotency, and the Grid presence WebSocket contract. | Cursor pagination remains incomplete on other live surfaces, idempotency keys are not consistently implemented outside selected workflows, and realtime contracts for messages/activity/notifications remain incomplete. |
| Backend architecture | Partially implemented | Ktor authoritative services, PostgreSQL/PostGIS, Flyway, Redis, Docker local services, FCM path, server-side subscription model, and Grid presence WebSockets. | Redis candidate cache, signed object storage, sensitive log redaction, analytics redaction, and formal cloud environment setup remain. |
| Mobile architecture | Partially implemented | KMP shared network/client/domain types, Compose feature state holders, bounded Swipe action queue with Android SQLDelight persistence, Android platform integrations for billing/FCM/location, and Android Grid presence stream wiring. | iOS platform integrations, secure token storage review, iOS process-death queue QA, and broader emulator/device visual QA coverage need work. |
| Testing | Good start, not enough | Server test suite restored, shared tests, Compose Android host tests, Compose desktop Grid visual-signature test, Compose desktop Swipe visual/interaction test, local verification script. | Android instrumented/E2E tests, iOS tests, real DB container integration coverage expansion, abuse tests, beta-scale load tests, and connected-device screenshot regression need work. |
| CI/CD | Partial | GitHub Actions runs the same Gradle verification gate as `scripts/verify.ps1`. | Add Docker service tests when needed, Android artifact checks, lint/static analysis, branch protection, and required status checks. |
| DevOps/infra | Early | Docker Compose local stack, repo initialized and pushed, scripts for local verification. | Firebase/GCP/Play/App Store production setup, secrets, cost alerts, App Check, environment separation, deploy plan, and branch protections remain. |
| Analytics | Not started | Specs exist. | Needs privacy-safe event taxonomy and implementation. No lifestyle-sensitive payloads. |
| Psync | Not started | Spec exists. | Still a differentiator, but should come after stronger partner-link, messaging, safety, and swipe foundations. |
| Playing Tonight | Not started | Spec exists. | Product feature can wait until core grid/swipe/messages/activity safety is stronger. |
| iOS | Mostly scaffold only | `iosApp` exists. | StoreKit 2, APNs, iOS UI validation, and iOS test path are not ready. |

## Spec Reconciliation Status

Reconciled on 2026-05-08.

The root spec checklists now use this practical convention:

- `[x]` means implemented enough for the current MVP milestone and backed by code evidence.
- `[ ] Partial:` means a real implementation slice exists, but the item is not complete enough to check off.
- `[ ]` means missing or not started.

A checked box should not mean "final production-ready" unless the surrounding spec explicitly says so. It means the current implementation satisfies the current milestone expectation for that item.

## Highest-Risk Gaps

1. Grid scalability is stronger but not fully proven to beta scale.
   - Redis candidate caching is not complete.
   - A 5,000-candidate repository proof, 100-concurrent filtered repository regression proof, full API-level mixed-tier Ktor load harness, focused EXPLAIN/index pass, and V43 covering indexes exist.
   - The current warm 100-concurrent API harness is zero-error and inside local p95 budgets.
   - A 2026-05-14 500-concurrent warm API run completed with zero HTTP errors but exceeded p95 budgets for Grid initial/filter/page two.
   - A 2026-05-14 500-concurrent cold Redis-cleared API run completed with zero HTTP errors and met the current p95 budgets.
   - Page two still scans most eligible candidates because cursor ordering depends on computed distance. Redis candidate caching should be reconsidered for 500-concurrent beta targets, larger metros, cold-cache repeatability, or if page-two p95 regresses.
   - Shared Compose Grid visual-signature regression and connected Android structural golden QA exist, but broader real-device coverage is not complete.

2. Swipe server authority + engine.
   - Signed persisted deck-session/card-token validation exists for action authorization.
   - Cursor pagination now continues a persisted deck session without duplicate returned cards, and the Compose client auto-loads the next page when the visible stack drops below threshold.
   - Swipe action IDs now make normal retries idempotent and prevent duplicate inventory spend.
   - A bounded client action queue exists, including local rollback for queued actions and Android SQLDelight persistence. Android emulator process-death queue QA now passes; iOS device/simulator process-death QA remains incomplete.
   - Rewind entitlement flow exists for Social/Select and latest eligible Like/Pass/Pineapple Like actions, including atomic Pineapple Like inventory refund.
   - Phase A engine pass (2026-05-16) shipped: two-phase deck query (rank → hydrate-31), combined deck-card + target-eligibility precondition check, Hikari pool 10 → 60, request profiling middleware on swipe routes (X-Pynapli-Profile headers), KNN spatial ordering via PostGIS `<->`, V46 migration dropping the unused `idx_swipe_deck_cards_session_position`, pgjdbc `reWriteBatchedInserts=true`, and aligning the free-tier filter gate with FILTERS_SPEC L432-440 via `filters.requiresAdvancedEntitlement` (matches GridService). Baseline harness at `scripts/swipe-api-load-test.ps1` (5000 candidates, 100 mixed-tier viewers, 100-way concurrent). Final p95 vs baseline: `swipe_deck_initial` 3,335 → 1,158ms (-65%), `swipe_action_like` 3,621 → 1,009ms (-72%), `swipe_deck_filtered` 2,516 → 1,020ms (-59%, now honest after the over-gate fix). p50 for `swipe_deck_initial` is 830ms (typical user under stress).
   - Phase B (FILTERS_SPEC engine) is in progress. B1 done (2026-05-16): `SwipeDeckRequestDto` now exposes `verifiedOnly`, `facePhotoVerifiedOnly`, `ageVerifiedOnly`, `discoveryStyles`, `intents`, `lifestyleTags`, `logisticsTags` (matching `DiscoveryFilters` field-for-field minus the Grid-only `onlineOnly`/`tonightAvailableOnly`). `JdbcSwipeRepository.queryDeckRank` Phase 1 SQL adds the corresponding WHERE predicates with the same `IS NOT NULL` / array-overlap patterns Grid uses. Server-side integration coverage in `GridAndSwipeRepositoryIntegrationTest`: "swipe deck verifiedOnly excludes unverified profiles", "facePhotoVerifiedOnly and ageVerifiedOnly each filter to their column", "discoveryStyles filter to matching style", "intents filters to overlapping intent and excludes empty profiles", "lifestyleTags filters to overlapping tag and excludes untagged profiles", "logisticsTags filters to overlapping tag and excludes untagged profiles". The `requiresAdvancedEntitlement` gate is now reachable: a free-tier viewer applying lifestyle or advanced-logistics filters via Swipe gets `ENTITLEMENT_REQUIRED`, matching FILTERS_SPEC L432-440 paywall semantics. B2 done (2026-05-16): `SwipeDealbreakerKey` object + `SwipeDeckRequestDto.dealbreakers: Set<String>` + service-side validation against the allow-list (`age`, `distance`, `verified`, `discoveryStyles`, `intents`, `linkedOnly`); unknown keys throw `INVALID_SWIPE_FILTER`. B3 done (2026-05-16): `SwipeService.queryDeck` runs a relaxed second `repository.queryDeck` call when the first-pass result is below `max(limit/3, 3)` AND any non-dealbreaker filter could relax. Non-dealbreaker fields are cleared on the retry; radius doubles (capped at `MAX_RADIUS_METERS`) when `distance` is not a dealbreaker; dealbreaker fields are preserved on both passes. First-pass cards stay at the head of the deck; relaxed cards append; duplicates deduped by userId. Coverage in `SwipeServiceTest` ("RelaxesNonDealbreakerFiltersWhenResultIsLow", "DoesNotRelaxWhenAllFiltersAreDealbreakers", "DoesNotRelaxWhenResultMeetsThreshold", "RelaxationPreservesDealbreakerFiltersOnRetry", "RelaxationWidensRadiusWhenDistanceIsNotDealbreaker", "RelaxesViaRadiusWhenDistanceIsNotDealbreakerEvenIfOtherFiltersAre"). B4 v1 done (2026-05-16): `verifiedPreferred: Boolean = false` on `SwipeDeckRequestDto` and `DiscoveryFilters` (Grid ignores). `JdbcSwipeRepository.queryDeckRank` SQL computes a `soft_score` column (`+1` when `verifiedPreferred` is on AND target `verified_at IS NOT NULL`) and `ORDER BY soft_score DESC, v.geom <-> ul.geom` so verified-preferred targets outrank unverified ones within their distance band. Coverage in `GridAndSwipeRepositoryIntegrationTest` "swipe deck verifiedPreferred ranks verified profiles above unverified within the same distance band" (verified target placed farther than unverified; with `verifiedPreferred=true` the verified target ranks first). Intent/style scoring deferred to B4 v2 — those signals would lose meaning on the B3 relaxed pass because the relaxed filter set drops `intents`/`discoveryStyles`; surfacing them as ranking would need a separate "ranking-hints" payload alongside filters. B5 done (2026-05-16): `scripts/swipe-api-load-test.ps1` now includes a `swipe_deck_filtered_relaxed` scenario where every viewer sends `intents=[open_to_singles]` (no seeded candidate matches; B3 relaxation always fires) plus `verifiedPreferred=true` and `discoveryStyles` branches in the existing `swipe_deck_filtered` rotation. Phase B end run at `artifacts/swipe-api-load/20260516-133418/`: 100 successful relaxed queries, 0 errors, p95 1,491ms within the 1500ms budget; new profile span `swipe.deck.repo.queryDeckRelaxed` p95 = 623ms (the extra DB pass cost under contention). **Phase B (FILTERS_SPEC engine) complete.** Cumulative engine progress since baseline: `swipe_deck_initial` p95 3,335 → 1,291ms (-61%, p50 829ms), `swipe_action_like` 3,621 → 1,362ms (-62%). Phase C (FILTERS_SPEC UI) shipped 2026-05-16. C1: `SwipeFilters` immutable client model mirrors `DiscoveryFilters` field-for-field and round-trips through `toSwipeDeckRequest`/`fromSwipeDeckRequest`; `SwipeRepository.loadDeck` now takes a `SwipeDeckRequestDto` so the state holder pushes filter+cursor in one shot. C2: `SwipeFiltersSheet` Material3 `ModalBottomSheet` covers Who/Age/Distance/Intent (Style+Looking for)/Verified (three-mode)/Advanced (face/age verified + lifestyle + logistics tags); body extracted as `SwipeFiltersSheetBody` so desktop interaction tests can drive the inner content without the popup window. C3: `SwipeScreen` Filters header button shows the active-filter count and opens the sheet; Apply routes through `stateHolder.applyFilters(filters)` which mutates `state.swipeFilters` then triggers a fresh `load()` per FILTERS_SPEC L401. Empty-deck copy switches to "No more recommendations match your filters" + "Adjust filters" CTA per FILTERS_SPEC L487-489 when the relaxed deck still returns nothing. C4: per-section `Lock` `FilterChip` toggles `SwipeDealbreakerKey` membership (FILTERS_SPEC L354-371); each lock is disabled until the underlying filter is eligible (range set, style selected, verified-only chosen) and auto-drops when the filter clears so no orphaned hard gate can silently shrink the deck. C5: `AdvancedPaywallBanner` renders pre-emptively when `draft.requiresAdvancedEntitlement` becomes true, with a Subscribe button that dismisses the sheet and routes through `onOpenStore`; server stays the entitlement authority, the banner just saves an `ENTITLEMENT_REQUIRED` round-trip. C6 coverage: `SwipeStateHolderTest` adds `applyFiltersIssuesFreshDeckRequestMatchingFilters` (verifies `radiusMeters`, `relationshipContexts`, `dealbreakers`, cursor reset) and `applyFiltersDefaultsRestoresDefaultRequest` (Clear-all path). `SwipeFiltersSheetInteractionTest` covers chip toggle → Apply, age-dealbreaker lock → Apply, discoveryStyles auto-clean on filter clear, and the gated-tag paywall reveal. Full UI test gate green (`:composeApp:desktopTest :composeApp:testAndroidHostTest :androidApp:assembleDebug`). Commits `4c5753f` (sheet UX) and `a4ef329` (tests) on `main`.

   - 2026-05-18 Swipe backend proof is now much closer to Grid's standard: 100-concurrent extended coverage exercises Boost, Top Picks, Travel activation, travel deck, filtered decks, relaxed decks, page two, Like/Pass, Rewind, and presence; 500-concurrent warm and Ktor-restarted true-cold core runs stayed inside p95 budgets with zero unexpected failures.

3. Production-grade CI controls are not complete.
   - The Gradle verification gate is wired into CI.
   - Branch protection, required status checks, Docker service tests, lint/static analysis, and artifact checks still need to be added.

4. Production safety is not complete.
   - Age verification has signup DOB/18+/terms capture and an MVP entry/review path, but provider/market integration, appeals/support, false-positive metrics, device/account-farm signals, and location spoofing signals are not done.
   - App Check is not wired into the production security posture.

5. Settings and account management are incomplete.
   - Safety center, blocked/hidden users, data export/delete, notification controls, and privacy settings need a complete user-facing path.

6. Media is still local-dev oriented.
   - Production signed object-storage URLs, moderation/scanning policy, cleanup jobs, and secure delivery need implementation.

7. Realtime is incomplete.
   - Grid presence uses WebSocket convenience updates.
   - Messages, activity, and notifications still depend on HTTP polling/refetch paths.

8. StoreKit/iOS is not ready.
   - Android billing has started.
   - iOS purchase validation and StoreKit 2 integration remain future work.

9. Analytics are not implemented.
   - Before adding analytics, the event taxonomy must avoid lifestyle-sensitive payloads.

10. Admin tooling needs production controls.
   - Roles, MFA, separated admin API, subscription support, partner-link review, and sensitive reveal workflows are not complete.

## Recommended Next Sequence

1. Strengthen CI from "runs the gate" to "protects the branch."
   - Add branch protection and required status checks in GitHub.
   - Add Docker-backed integration tests, lint/static analysis, and Android artifact checks when those suites are ready.

2. Keep Grid and Swipe proof-driven.
   - Grid: physical-device battery/location QA with `scripts/android-grid-battery-profile.ps1` remains the main open proof item; denied-location Android QA is covered.
   - Grid later: reconsider Redis candidate caching only from repeat evidence on larger dense metros or if page-two p95 regresses.
   - Swipe: backend API proof is now strong for the current MVP load shape, Android screenshot/gesture/golden QA is covered on emulator, and Android queue/process-death QA is covered on emulator. Next work should be iOS device/simulator queue QA, real-device Android Swipe runs, and remaining premium discovery polish.

3. Finish the profile/settings safety surface.
   - Safety center, block list, hidden users if retained, report history/support contact, data export/delete, notification settings, and production age-verification provider/market wiring.

4. Move media from local-dev to production-safe storage.
   - Signed upload/download URLs, private album grant enforcement, cleanup jobs, and scanning hooks.

5. Add realtime only after HTTP state is correct.
   - WebSocket events should be convenience updates.
   - HTTP refetch remains canonical after reconnect.

6. Add differentiators after core safety is stronger.
   - Psync and Playing Tonight should wait until grid, swipe, messaging, activity, profile links, and safety are more complete.

## Decision

The project is on the right track, but we should deliberately slow down before adding more large product features. The next work should be quality and architecture work: branch protection, grid/swipe hardening, account/safety completion, and production-safe media.

That is how Pynapli avoids becoming a fragile prototype. The current MVP is real enough now that quality debt matters.
