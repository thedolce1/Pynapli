# Pynapli codebase review — 2026-11-08

Scope: every module (server, shared, composeApp, androidApp, webApp, desktopApp), build and dependencies, tests, docs and scripts. Method: a mechanical baseline (Stage 1), five read-only area reviews (Stage 2), then the orchestrator re-read the cited code for every finding marked ✔ below. Findings marked ◐ come from a reviewer's report with evidence but were not individually re-read. Plan: `~/.claude/plans/federated-plotting-lollipop.md`.

## Summary

**Overall verdict:** the codebase is lean and mostly modern. The mechanical signals are very good:
- 8 compiler warnings across every module
- 16 dead declarations out of 2,250
- a 3.29 MB release APK
- 1 lint warning
- all 1,091 composable lambdas memoized
- 1,513 tests in 142 s

There is no large bloat problem. What the review did find, in priority order:

1. **Real defects the gates don't catch.** A GDPR feature marked shipped is unmerged. Picked photos are read on the main thread. Real-time sockets reconnect in a hot loop once the access token expires. Three security-relevant tests can never fail. The chat thread renders every message without a lazy list.
2. **Copy-paste at the infrastructure layer** rather than in features: 23 auth helpers, 6 transaction wrappers, 4+4 stream clients, 4 shell graphs, test fixtures, QA helpers.
3. **Docs drift.** `IMPLEMENTATION_AUDIT.md` is 160 KB, about 89% changelog, and contradicts itself. Several specs still describe JWT, Koin and an 8dp spacing grid.

| Question | Verdict |
|---|---|
| Optimized? | Mostly. Server I/O is on `Dispatchers.IO` throughout (245 sites), indexes match the hot queries, Grid is Redis-cached, and Compose stability is healthy. The gaps are specific (Tier 1). |
| Modern practice? | Mostly. StateFlow everywhere, cancellation-safe `runCatchingCancellable`, keyed lazy lists, strong skipping, Billing 8, edge-to-edge, AGP 9. Behind: hand-rolled bearer auth instead of Ktor's Authentication plugin; tests using JUnit4 and `runBlocking`; configuration cache disabled in every gate. |
| Bloat? | Moderate, in duplicated infrastructure (Tier 3). It doesn't show up in APK size or file size. Large screens are already split internally. |
| Unnecessary code? | Little. 16 verified dead declarations, 3 unused build dependencies/plugins, 1 dead `expect class` that causes 4 of the 8 warnings, sample fixtures in main code, and 4 superseded docs. |

## Baseline metrics (Stage 1 — re-run these to measure fixes)

| Metric | Value | How measured |
|---|---|---|
| Compiler warnings (full recompile, all modules) | 8 unique: 4 expect/actual Beta, 3 deprecated `BackHandler`, 1 Skiko | `gradlew <all compile tasks> --rerun-tasks --no-build-cache --no-configuration-cache` |
| Release lint | 1 warning (compileSdk 37 available — settled by the stable-only policy) | `:androidApp:lintRelease` |
| Release APK (minified) | 3.29 MB; R8 removed 185 app classes entirely | `:androidApp:assembleRelease`, `mapping/release/usage.txt` |
| Compose (composeApp) | 1,119 composables, 69% skippable, 304/16,112 unstable args, 1,091/1,091 lambdas memoized, strong skipping on | `scripts/compose-metrics.ps1` |
| Dead declarations | 15 candidates / 2,250 scanned; 13 confirmed dead, plus 3 more found by reviewers (16 total) | one-off reference scan + reviewer verification |
| Tests | 225 suites, 1,513 tests, 142 s summed; slowest 23 s is container start-up, not test cost | JUnit XML results |
| Version catalog | every library referenced; 3 declared-but-unused per module | catalog vs `build.gradle.kts` vs imports |

## Tier 1 — Defects and real performance issues (fix first)

**Status:** #2, #3, #4, #6, #7, #8 and #9 were fixed in the correctness hot-fixes slice (`fix/correctness-hotfixes`). #4 turned out to cover 4 tests, not 3: `PresenceStreamRouteTest.deltaForBlockedRelationshipIsFiltered` had the same never-failing check. The regression tests for #3 and #6 were confirmed to fail against the old code. #8 has no unit test, because `:shared` has no JVM SQLDelight driver; the emulator E2E run covers it. #1 shipped as 82ea6aa (the data-export commit replayed onto main, plus a download-expiry fix), then gained stale-claim retry, a generic user-facing failure message, and GCS storage. #5 shipped as b9405cf (keyed `LazyColumn`; idempotent message append). #11 and #12 shipped as bcffda3 (per-build-type dev login, verified absent from the release dex; `BuildConfig.VERSION_NAME`). Tier 2's dead-code, unused-build-pieces, duplicate-engine and unused-import items shipped in the dead-code slice (252 unused imports, not ~100; `kotlinx-datetime` stays in composeApp/webApp/desktopApp, which use it). #10 confirmed by EXPLAIN ANALYZE on 106k seeded users (seq scan of every location row) and fixed with a `nearby_ids` CTE (UNION of two GiST lookups): dense SF viewer ~425 → ~230 ms, rural viewer ~300 → ~16 ms. The docs truth pass fixed #13, the Tier 2 docs items and S12 (`IMPLEMENTATION_AUDIT.md` is now an 8.6 KB status table; the changelog moved to `docs/archive/IMPLEMENTATION_HISTORY.md`), and applied judgment calls 1 (docs now say state holders live in `:composeApp`) and 3 (4 docs archived). Tier 3: S9 shipped (87 server test files moved from JUnit4 to `kotlin.test`; `junit` + `junit-vintage-engine` dropped; suite still 123 classes / 889 tests). S1 shipped with one shared `auth/BearerToken.kt` (23 copies + 4 variant parsers removed; 107 call sites → `call.authenticatedUser(authService)`; lowercase-scheme bug fixed) instead of the Ktor `Authentication` plugin, which would change the JSON 401 body clients parse and doesn't model post-upgrade WebSocket auth. S3 shipped: 43 of 50 `ServerDependencies` fields are non-null (7 stay nullable because config can switch them off), 39 route null-guards and the `test()` factory + runtime `requireRoutableServices()` check are gone, and health routes take only (environment, check). S8 shipped: `testsupport/` now has `denyingRateLimitService()` (×11; it denies, it isn't "unlimited"), `SingleSessionAuthRepository` (×5), `StaticSubscriptionRepository` (×7) and `testServerConfig()` (×15); variants that genuinely differ (6 `insertUserWithProfile` shapes, 6 subscription fakes) stay local. S2 shipped: `db/Jdbc.kt` (`DataSource.withConnection`, `Connection.inTransaction`); 133 connection blocks and 6 named transaction wrappers + 28 hand-rolled transactions converted. Left as written: the swipe deck query (it suspends inside the connection for profiling spans), 5 blocks that do more than borrow a connection, and 4 transactions with extra catch clauses. S14 shipped: the shell is a pure dispatcher and each surface/tab is its own composable that collects only its state (hoisting into `when` branches alone would not have helped: a branch is not a recomposition scope). S13 shipped: new `PynapliButton` primitive (Tonight + Psync), Tonight intent filter on `PynapliPill`, one `TonightIntent.label`, Tonight empty state on `PynapliEmptyState`; Tonight golden gold-ratio recalibrated 0.0003 → 0.0001 for the outlined pills. S10 shipped: 221 common tests moved to `runTest`; the `yield()` races in `GridStateHolderTest` and `AuthStateHolderTest` replaced by `runCurrent()` + a proof the load is in flight; the stream backoff test now asserts an exact count under virtual time. Common tests now run on wasm (74 shared + 228 composeApp, headless Chrome) and `verify-web` runs them. S4 shipped: one `JsonStreamClient<T>` + `KtorStreamSessionFactory` + `StreamBackoff` (`RealtimeStream.kt`) replace the 4 client copies; one `RealtimeStreams` per platform replaces 16 builders and shares one HTTP client; server `recipientStream()` replaces 3 route copies (Psync gains browser first-frame auth) and presence shares `authenticateStream()`. S6 shipped: typed `authGet/authPost/authDelete/authRequest/authSend` on `KtorApiSupport` replace 95 of the 105 authenticated blocks (the review's ~230 overcounted; 10 with custom status handling stay); `AppResult.map` replaces all 16 failure pass-throughs; one `SavedFilterStateHolder<F, Req, Item>` backs both Grid and Swipe presets. S5 shipped: `PynapliCommonGraph.appGraph(...)` and `startRealtime(scope)` plus `LocationStateHolder.submitAndRefresh(grid)` replace the per-shell 22-argument mappings (×4), 49 pass-through getters, realtime start-up (×4) and location→Grid refresh (×3); the Android location-policy gate now checks the single `realtimeJobs` cancel in `onStop`. S7 shipped: `PushRegistrationSync` (follow session + settings, sync on foreground, when to ask for notification permission) and `PickedMediaRouter`/`MediaPickTarget` moved to `:composeApp`; Android, web and desktop all route picked photos through the router; `MainActivity` is now 388 lines of Android-only code (location, picker, launcher alias, edge-to-edge, the OS permission dialog). S11 shipped: `scripts/lib/PynapliQaGolden.psm1` (superset `Get-ImageSignature`/`Assert-ImageSignature`, `Assert-GoldenStageSpec`, `Get-Sha256Base64Url`) replaces copies in 10 scripts (−781 lines). `Write-Summary` (12 copies) turned out to be 12 different per-script summaries, so it stays; the review's −1,200 estimate counted it. All 10 migrated scripts pass on Pynapli_Phone, goldens included. **Tier 3 complete.**

| # | Finding | Evidence | Fix | Effort |
|---|---|---|---|---|
| 1 ✔ | **GDPR data-export archive marked shipped but not on `main`.** The archive generator commit `d3052a9` exists only on `feat/phase3-data-export` (local + origin). `main` only records export requests and never generates an archive. | `docs/ANDROID_PRODUCTION_GAMEPLAN.md:59`, `docs/PROFILE_SETTINGS_SPEC.md:677` mark it ✅; `git merge-base --is-ancestor d3052a9 HEAD` → not an ancestor | Rebase and merge the branch through the normal gates, or revert both checklist items | M |
| 2 ✔ | **Picked images read on the main thread.** Possible ANR with cloud-backed Photo Picker URIs. | `androidApp/.../MainActivity.kt:51` `MainScope()`, `:366` `readPickedImage(uri)` with no IO dispatch | `withContext(Dispatchers.IO) { readPickedImage(uri) }` | S |
| 3 ✔ | **All 4 real-time stream clients reconnect with no delay after a normal close.** When the 15-minute access token expires, the server closes after the upgrade, and each client spins (a DB read + decrypt per loop). | `shared/.../ActivityStreamClient.kt:78` (and Message, Presence, Psync) resets `attempt = 0` on connect; the token is never refreshed | Back off after short-lived sessions or a policy close; refresh the token before reconnecting | S |
| 4 ✔ | **3 "frame must not reach the wrong user" tests can never fail.** `tryReceive()` returns immediately, so the `withTimeout(300)` never applies. These guard real-time privacy. | `ActivityStreamRouteTest.kt:112`, `MessageStreamRouteTest.kt:193`, `PresenceStreamRouteTest.kt:141` | `withTimeoutOrNull(300) { incoming.receive() }`; replace `delay(50)` with a subscriber signal | S |
| 5 ✔ | **The chat thread is not lazy.** `Column` + `verticalScroll` + `messages.forEach` with no keys; it composes every loaded message, and "older messages" grows it without limit. | `composeApp/.../conversation/ConversationMessages.kt:66,97` | `LazyColumn(reverseLayout = true)` keyed by `messageId`, with day dividers precomputed in the holder | M |
| 6 ✔ | **Grid `loadMore` has no stale-response guard.** A filter change mid-page appends old-filter cards and a stale cursor. If the request is cancelled, `isLoadingMore` sticks and paging stops for the session. | `composeApp/.../grid/GridStateHolder.kt:161-191` | Reuse Activity's `refreshGeneration` pattern; reset the flag in `finally` | S |
| 7 ✔ | **Uploads use the 15 s API timeout** for files up to 15 MB, so they fail on slow cellular. | `shared/.../PynapliApiClient.kt:119`, `MediaByteUploader.kt:27` | A per-request upload timeout; share one client | S |
| 8 ✔ | **Session read from SQLite and decrypted on every API call**, on the caller's (often Main) dispatcher, with no in-memory cache. The grid snapshot is also saved on Main. | `shared/.../auth/SqlDelightSessionStore.kt:9-13`, `KtorApiSupport.kt:43` | Cache the session in memory (invalidate on save/clear); `withContext(IO)` around DB access | S |
| 9 ✔ | **Blocking HTTP inside a `suspend` moderation scan** on the upload-complete path. It holds a Ktor worker for up to 10 s, and `runCatching` swallows cancellation. | `server/.../media/CloudVisionMediaModerationScanner.kt:53,77,97`; caller `MediaUploadActions.kt:138` | Wrap the body in `Dispatchers.IO` (as `PlayStorePurchaseVerifier` already does); use `runCatchingCancellable` | S |
| 10 ✔/◐ | **Grid discovery's spatial `OR` across two tables** (home location vs LEFT-JOINed travel location) likely defeats the GiST index, so uncached Grid queries scan every visible user. Swipe works around this with a pre-fetch; Grid doesn't. | `server/.../grid/JdbcGridRankSql.kt:112-115` (SQL confirmed; index impact plausible) | Run `EXPLAIN ANALYZE` on seeded data first; then `UNION ALL` home and travel candidates, or reuse Swipe's pre-fetch | M |
| 11 ✔ | **Dev-login password string compiled into release builds**, and debug fault-injection code ships in `main`, gated only at runtime. | `androidApp/.../AndroidAppGraph.kt:248`; `swipe/DebugFaultInjectingSwipeRepository.kt`, `debug/DebugQaFlags.kt` | Move them to `src/debug` with a release pass-through (the pattern `PynapliAppCheckProviderFactory` already uses) | S |
| 12 ✔ | **`appVersion = "0.1.0"` hardcoded**, so the server's auth and push device records always show the wrong version. | `AndroidAppGraph.kt:132,146`, `IosAppGraph.kt:117`, `WebAppGraph.kt:112` | `BuildConfig.VERSION_NAME` on Android, and build-provided values elsewhere | S |
| 13 ✔ | **`PATH_TO_TESTERS.md` step B2 names a script that doesn't exist** (`build-staging-apk.ps1`; the real one is `android-staging-apk.ps1`) and the wrong Gradle property. This is the founder's route to testers. | `docs/PATH_TO_TESTERS.md:38` | Correct the script name and use `stagingApiBaseUrl` | S |

## Tier 2 — Quick-win cleanup (low risk, mostly deletions)

- **Dead code, verified across all targets ✔:**
  - shared: `core/config/PynapliConfigs`, `grid/domain/GridCard` (used only by its own test), `PresenceApi`/`KtorPresenceApi` (the client never calls it; the server route stays), and in `SafetyDtos.kt`: `REPORT_NOTE_MAX_LENGTH`, `AppealActionType`, `AppealTargetType`, `AppealStatus`.
  - composeApp: `GradientPhotoPlaceholder`, `PynapliDivider`, `PynapliSheetHeader`, `stripCursor`, `SampleMediaAlbumsRepository`, `SampleSwipeRepository`, iosMain `IosSwipeFactory`.
  - server: `JdbcSwipeDeckCandidateSql.hasAnyActiveProfileLinksNear`.
  - `PlatformInfoProvider` (the only `expect class`, plus its 4 actuals). Deleting it also removes 4 of the 8 compiler warnings.
- **Unused build pieces ✔:**
  - `kotlinx-datetime` in `shared` (zero imports)
  - `kotest-assertions-core` in `server` (zero imports)
  - the `io.ktor.plugin` Gradle plugin in `server` (no `ktor {}` block)
  - ◐ the `android.library` alias and the stale root `printProjectStructure` task
- **Duplicate Ktor engines ✔:** `shared` androidMain declares both `ktor-client-android` and `ktor-client-okhttp`, and the REST engine is whichever ServiceLoader finds first. Drop `ktor-client-android`.
- **Unused imports ◐:** 31 in `AndroidAppGraph.kt` and 34 in `WebAppGraph.kt`, plus 7–14 in each of five server discovery test specs. ktlint isn't catching them.
- **Docs that contradict settled decisions ◐:**
  - JWT/`jwt_jti` in `DATA_MODEL_SPEC`, `DATABASE_MIGRATION_PLAN`, `DEVOPS_INFRA_SETUP_SPEC`, `OPENAPI_HANDOFF_PLAN` (tokens are opaque)
  - "8dp base grid" in `DESIGN_AND_NAVIGATION_SPEC:62` and `DESIGN_TOKENS:124`
  - Koin and a stale version table in `PROJECT_STRUCTURE_AND_DEPENDENCIES_SPEC:30-50` (link to the catalog instead)
  - `TopPicksRow` in `SWIPE_IMPLEMENTATION_SPEC:769`
- **README ◐:** lists 4 modules (missing `webApp`, `desktopApp`, `web/`); no verify gates or hook setup. Trim it to setup plus links. `docs/README.md` index is missing `IOS_BRINGUP_PLAN` and `PATH_TO_TESTERS`.
- **QA scripts ◐:** `android-e2e-qa.ps1`, the default in the QA matrix, redefines 11 device helpers instead of importing `scripts/lib/PynapliQa*.psm1`, and lacks the module's ANR-dialog handling.
- **Small server items ◐:**
  - `"23505"` is defined 6 times.
  - `setNullableUuid` is duplicated.
  - Rate limiting sends the full Lua script each call (EVAL instead of EVALSHA).
  - The network error message says "this APK" on iOS and web (`KtorApiSupport.kt:66`).
- **CI ◐:** no `concurrency:` group (superseded PR pushes keep running) and no `permissions:` block.

## Tier 3 — Structural de-duplication (medium effort, low risk, each its own slice)

| # | Finding | Fix | Effort |
|---|---|---|---|
| S1 ✔ | **Server auth:** 23 private copies of `requiredBearerToken()` and 107 `authenticate(call.requiredBearerToken())` call sites. All copies share a case bug (it fails closed, so it isn't a security hole). | Ktor `install(Authentication) { bearer { … } }` with `authenticate {}` route blocks | M |
| S2 ✔/◐ | **Server transactions:** 6 identical transaction wrappers, ~20 hand-rolled commit/rollback blocks, and 245 copies of `withContext(IO){ dataSource.connection.use … }` | One `db/` helper: `DataSource.withConnectionIO {}` + `Connection.inTransaction {}` | M |
| S3 ✔ | **`ServerDependencies`:** 50 nullable service fields and ~54 null-guards in routes put back at runtime the missing-dependency check that manual DI exists to give at compile time (the CLAUDE.md rationale) | Non-null services; health-only tests call `configureHealthRoutes` directly | M |
| S4 ✔/◐ | **Real-time streams:** 4 near-identical client stream classes (~165 lines each) + 4 platform factories, and 4 near-identical server stream routes | Generic `JsonStreamClient<T>(serializer)` + one factory per platform; one server `streamRoute(...)`. Pairs with Tier 1 #3 | M |
| S5 ◐ | **Shell graphs:** Android and web each repeat 20 pass-through getters and the 22-argument `PynapliAppGraph(...)` mapping; location→grid refresh ×3; realtime start-up ×4. Desktop already does this the right way | `PynapliCommonGraph.appGraph(...)`, `startRealtime(scope)`, `LocationStateHolder.submitAndRefresh(grid)` | M |
| S6 ◐ | **Client repositories/holders:** ~230 repeated authenticated-call blocks; 16 hand-written `Failure -> result` pass-throughs; twin `SavedGrid/SwipeFilterStateHolder` (45 lines apart); three different concurrency strategies | `authGet/authPost/authDelete`; `AppResult.map/fold`; one generic saved-filter holder; a latest-wins loader helper | M |
| S7 ◐ | **`MainActivity` (506 lines):** push-sync orchestration (`startPushRegistrationSync` / `startNotificationPermissionPromptSync`, :241-275) and media routing are product rules iOS has to copy | Move them to `:composeApp`/`:shared`; ~300 lines remain, all genuinely platform code | M |
| S8 ◐ | **Server tests:** `insertUserWithProfile` ×7, a 50-line fake auth repository ×5, a `SubscriptionRepository` fake ×13, an unlimited rate limiter ×12, `testConfig()` ×12 | Promote `grid/DiscoveryTestFixtures.kt` to `testsupport/`; add `TestAuth.kt` and `TestFakes.kt` | M |
| S9 ✔ | **Server test frameworks:** 87 JUnit4 files, 22 `kotlin.test`, 11 Kotest. The JUnit4 files use nothing JUnit4-specific | Mechanical move to `kotlin.test`; drop `junit` + `junit-vintage-engine` | S–M |
| S10 ◐ | **Client tests:** 250 `runBlocking` in commonTest (doesn't compile on wasm); real-time waits; a `yield()`-based race in `GridStateHolderTest:360,549` | `runTest` + a test dispatcher | M |
| S11 ◐ | **QA scripts:** `Write-Summary` ×12, image-signature/golden helpers ×8, `Start-App` ×7, `Complete-LoginIfNeeded` ×7 | `scripts/lib/PynapliQaGolden.psm1`, about −1,200 lines; re-run the goldens | M |
| S12 ◐ | **`IMPLEMENTATION_AUDIT.md`:** 160 KB, 89% dated changelog. The header says 2026-05-21 ✔ and V55; the gap list contradicts the matrix. The root cause is that `ship-slice` appends narrative there every slice | A ~15 KB current-status table (Area / Status / Open gaps / Spec) + `docs/archive/IMPLEMENTATION_HISTORY.md`; `ship-slice` updates only the row | M |
| S13 ◐ | **Small UI duplication:** Tonight's `PillButton`/`IntentPill` duplicate `PynapliPill`; Psync has its own buttons (there is no button primitive); a duplicate `intentDisplay`/`intentLabel`; a hand-built empty state in Tonight | Use or add design-system primitives | S |
| S14 ◐ | **`AppMainContent.kt:71`** collects 9 state flows at the top, so any update re-runs the whole shell | Hoist each collection into its branch | S |

## Judgment calls (founder decision)

1. **State-holder placement.** Recommend **correcting the docs, not moving the code.** All 22 holders live in `:composeApp`, while CLAUDE.md, `composeApp/CLAUDE.md`, `MOBILE_APP_ARCHITECTURE_SPEC:96,105` and `PROJECT_STRUCTURE…:146,393` say `:shared`. The holders depend on composeApp UI models and copy, and every client (including iOS via `ComposeUIViewController`) already shares composeApp, so a move gains no reuse. `:server` depends on `:shared`, so a move would put UI state on the server classpath.
2. **Configuration cache.** It's enabled in `gradle.properties` but disabled (`--no-configuration-cache`) in every CI job and verify script since the first scaffold, with no recorded reason. Test compatibility and enable it, or delete the property. Also consider `org.gradle.parallel=true`.
3. **Archive 4 superseded docs** to `docs/archive/`: `DATABASE_MIGRATION_PLAN`, `OPENAPI_HANDOFF_PLAN`, `MVP_ROADMAP`, `LEGACY_RULES_IMPORT_REVIEW`.
4. **Web Tonight/Psync.** The web shell hides them while the common graph builds them anyway, and two comments disagree on which is intended (`webApp/.../Main.kt:127-147` vs `WebAppGraph.kt:69-71,140-142`).
5. **CI runs commonTest twice** (`testAndroidHostTest` + the JVM test task) with no `androidHostTest` sources. Keep this only if exercising Android actuals is the point.
6. **`compose.materialIconsExtended`** (61 icons used). It may no longer be updated upstream. Verify against the official Compose Multiplatform docs before acting; vendoring the 61 icons would cut wasm and desktop size.
7. **Sample repositories and `rememberXStateHolder()` defaults** in main code: if a caller forgets a parameter, the screen silently shows fake data. Make the parameters required and move the samples into tests.

## Checked and fine (not findings)

- **Server:** all Redis keys have TTLs, there is no `GlobalScope`, the only `runBlocking` is at start-up, routes contain no SQL, and hot-query indexes exist.
- **Build:** R8 rules contain no over-broad keeps. `androidx.fragment` must stay (lint's `InvalidFragmentVersionForActivityResult`). The `gcp-*` and load-test scripts are current.
- **Compose:** every lazy list has keys. Large screens are already split into small private composables, so splitting them further would only be tidying.
- **`BackHandler` deprecation:** no action. There is no stable JetBrains `navigationevent` artifact yet (documented at `AppOverlays.kt:65-82`), and it's 3 sites once one ships.
- **Not dead after all:** `FeatureAccessPolicy`/`PynapliFeature` (server uses them), `LegalLinks`, `PassThroughSessionTokenCipher` (iOS/JVM/wasm actuals), and `IosPushTokenProvider`/`IosPushRegistrationStore` (Swift entry points).
- **Settled decisions respected:** manual DI, no spacing scale, no detekt, stable versions only, no edits to applied migrations.

**False positives dropped:** 11 Stage-1 leads were rejected on verification (5 dead-code candidates still in use; 6 "missing identifiers" in docs that are file names or Apple APIs), along with 2 reviewer suggestions (`BackHandler` migration, removing `androidx.fragment`).

## Suggested slices (in order)

1. **Correctness hot-fixes:** Tier 1 #2, #3, #6, #7, #8, #9, plus #4 (the tests). Small, high value.
2. **Data export:** Tier 1 #1. Merge the branch, or correct the docs.
3. **Chat thread lazy list:** Tier 1 #5, with emulator QA (`android-messages-qa.ps1`).
4. **Release hygiene:** Tier 1 #11, #12.
5. **Dead code + unused dependencies:** Tier 2. Compile all targets and run tests.
6. **Grid query:** Tier 1 #10. EXPLAIN first, then the rewrite, with discovery tests.
7. **Docs truth pass:** Tier 1 #13, Tier 2 docs, judgment calls 1 and 3, S12.
8. Then the Tier 3 structural slices one at a time, starting with S1 (auth) and S2 (transactions).
