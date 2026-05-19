# Pynapli Testing Strategy Spec

Last updated: 2026-05-19

## Purpose

Testing is part of the product architecture. Grid speed, swipe feel, partner consent, safety, and billing must be verified continuously.

Core rule:

> If a critical path cannot be tested, it is not ready for users.

For production release gates, load targets, security matrices, and CI/CD quality gates, use [PRODUCTION_READINESS_AND_QUALITY_SPEC.md](PRODUCTION_READINESS_AND_QUALITY_SPEC.md) alongside this document.

## Test Layers

- Unit tests: domain rules and pure logic.
- Integration tests: Ktor API with real database containers.
- E2E tests: mobile user journeys.
- Performance tests: grid, swipe, messages, location, battery.
- Security/abuse tests: bots, spam, blocks, moderation.

## Critical E2E Journeys

- Signup to first grid.
- Login to grid.
- Grid profile view and return to exact scroll position.
- Grid filter apply without blank reload.
- Grid message send/receive.
- Swipe like/pass/match/chat.
- Partner link request/accept/unlink.
- Partner invite to chat accept/deny/leave.
- Private album request/grant/revoke.
- Psync session start/complete/recap.
- Playing Tonight approval/live/expire.
- Subscription purchase/restore/linked partner entitlement.
- Block/report removes visibility.

## Visual Regression

Current Grid coverage:

- `GridScreenVisualRegressionTest` renders the real shared Compose `GridScreen` in a fixed mobile viewport with the dark Pynapli theme.
- The test captures pixels and checks for the intended visual signature: subdued dark surface, dense card grid, readable text/icons, online indicators, linked-partner/accent color, and filter-sheet surface after opening filters.
- This is a fast shared-UI guardrail that runs in `:composeApp:desktopTest`, so it is included in `scripts/verify.ps1`.
- `scripts/android-grid-qa.ps1` runs the installed Android debug app against local Ktor on a connected emulator/device. It seeds dev data, optionally builds/installs the APK, logs in through dev login, captures screenshots, verifies the Grid loads, opens the filter sheet, toggles the Online quick filter, opens a profile, and asserts closing the profile returns to the same visible Grid card sequence.
- `scripts/android-grid-qa-goldens.json` defines connected Android structural golden assertions: required/forbidden UI text, minimum visible Grid-card counts, and screenshot visual signatures for Grid, filter sheet, empty filtered state, profile detail, and return-to-grid.
- `scripts/android-grid-denied-location-qa.ps1` runs the installed Android app with the dev viewer's server-side location removed, exercises the Android location permission denial prompt, verifies the no-location and denied-permission UI states, confirms no Grid cards leak into the denied state, captures screenshots/XML, and restores the dev location afterward.
- `scripts/android-location-policy-check.ps1` verifies the Android Grid location path stays while-in-use only: no background location permission, no foreground location service, no continuous `requestLocationUpdates`, one-shot `getCurrentLocation`, and cancellation on `MainActivity.onStop`.
- `scripts/android-grid-battery-profile.ps1` captures a repeatable foreground/background Grid battery-location profile bundle through `adb`. It refuses emulator evidence unless `-AllowEmulator` is passed, so real-device evidence stays distinct from harness smoke testing.

Current Swipe coverage:

- `SwipeScreenVisualInteractionTest` renders the real shared Compose `SwipeScreen` in a fixed mobile viewport with the dark Pynapli theme.
- The test captures pixels and checks for the intended visual signature: subdued dark surface, tonal card body, readable text/icons, Pynapli gold accent, and Like action accent.
- The test also clicks Like and Pass through the real action buttons and verifies the deck advances, then verifies the Intro Note dialog opens.
- `SwipeImagePrefetchPlannerTest` verifies the active/near-next Swipe image prefetch window, URL ordering, and deduplication.
- `scripts/swipe-api-load-test.ps1` now exercises the Ktor Swipe API against a dense local dataset, including core deck/action/rewind flows and optional Boost, Top Picks, and Travel scenarios.
- `scripts/android-swipe-qa.ps1` runs the installed Android app against local Ktor, captures Swipe screenshots/XML, verifies structural golden signatures, opens filters and travel mode, opens the linked-partner preview, opens the Intro Note dialog, and proves left/right Swipe gestures advance the visible deck. Latest emulator proof: `artifacts/android-swipe-qa/20260518-214507`.
- `scripts/android-swipe-queue-qa.ps1` runs the installed Android app against local Ktor, verifies Swipe opens with real seeded data, checks a linked Swipe card, injects a recoverable Swipe action failure, force-stops/relaunches the app, verifies the pending action survives process death, then retries and clears the queue. Latest emulator proof: `artifacts/android-swipe-queue-qa/20260518-212037`.

Current Activity backend coverage:

- `ActivityServiceTest` covers profile-view/flirt/album request recording rules, block/risk filtering, entitlement reveal depth, tab filtering, signed cursor continuation, cursor scope rejection, and Activity dismiss behavior.
- `ActivityRepositoryIntegrationTest` runs against PostgreSQL/PostGIS through Testcontainers and covers profile-view writes, eligibility suppression, feed tab filtering, keyset cursor pagination, and owned soft-dismiss.
- Latest local proof on 2026-05-19: targeted Activity service/repository tests, full `:server:test`, and `:shared:jvmTest` passed.

Still open before beta:

- Exact-image golden comparisons are still optional future work; current connected Android golden coverage uses structural UI and screenshot-signature checks to avoid status-bar/time false positives.
- Manual device pass on mid-range and low-end Android hardware.
- Visual checks with empty Grid outside the Online-filter empty state and more real-device thumbnail loading still need coverage.
- Broader connected Android Swipe screenshot/gesture/golden QA now has a repeatable emulator path; expand it with real-device runs, slower network profiles, and match-overlay/profile-detail variants before beta.
- Real battery profiling with movement/background/foreground scenarios still needs a device pass.

## Performance Benchmarks

Targets:

- First network grid render: under 1.5s on normal mobile network.
- Grid filter apply: no blank grid; animation under 300ms after response.
- Swipe animation: 60fps target.
- Swipe action: no animation blocked by network.
- Message delivery online: target under 1-2s.
- Push notification: target under 30s.
- Profile update to discovery: within 60s.
- Ban/suspension removal: under 5s.

## Load Tests

Use k6 or equivalent for:

- Grid query.
- Swipe deck.
- Swipe action.
- Message send.
- Activity feed.
- Auth.
- Album grant/revoke.

Use PostgreSQL `EXPLAIN ANALYZE` for slow query regression.

Current local Grid API harness:

```powershell
.\scripts\grid-api-load-test.ps1 -CandidateCount 5000 -ViewerCount 100 -ConcurrentUsers 100 -OnlineCandidateCount 1000
```

The harness exercises real Ktor endpoints with mixed Free/Social/Select viewers, presence heartbeats, first Grid query, filtered Grid query, page-two Grid query where available, and profile opens. Add `-FailOnBudget` when using it as a hard performance gate.

The harness writes `summary.json` and `results.csv` under `artifacts/grid-api-load/<timestamp>` by default. Use `-OutputRoot` for named proof runs and `-ColdCache` to clear Redis before seeding/running the workload.

For endpoint breakdowns, start Ktor with:

```powershell
$env:PYNAPLI_API_PROFILING_ENABLED='true'
$env:PYNAPLI_DATABASE_MAX_POOL_SIZE='32'
$env:PYNAPLI_DATABASE_MIN_IDLE='8'
.\gradlew.bat :server:run
```

Then run:

```powershell
.\scripts\grid-api-load-test.ps1 -CandidateCount 5000 -ViewerCount 100 -ConcurrentUsers 100 -OnlineCandidateCount 1000 -ServerProfile
```

The current profiled 100-concurrent baseline is zero-error after the Grid two-phase SQL optimization, side-query optimization, and V43 covering-index pass. Latest warm 2026-05-13 run with 5,000 candidates / 100 concurrent mixed-tier users / DB pool 32 produced client p95 of Grid initial 450.4ms, Grid filter 354.5ms, Grid page two 477.0ms, profile open 138.8ms, and presence heartbeat 118.0ms. The harness refreshes planner statistics after bulk seeding before timing the API workload.

Latest 500-concurrent local proof runs on 2026-05-14 used 5,000 candidates, 1,000 online candidate presence keys, 500 mixed-tier viewers, and 500-way concurrency:

| Run | Errors | Grid Initial P95 | Grid Filter P95 | Grid Page Two P95 | Presence P95 | Profile Open P95 | Artifact |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| Warm | 0 | 838.3ms | 702.6ms | 943.4ms | 220.3ms | 318.9ms | `artifacts/grid-api-load-500/20260514-083700` |
| Cold Redis cache | 0 | 638.2ms | 492.0ms | 709.8ms | 144.4ms | 167.9ms | `artifacts/grid-api-load-500-cold/20260514-084048` |

Decision: the 500-concurrent API path is stable with zero HTTP errors. One warm local run exceeded the current p95 budgets for Grid initial/filter/page two, while the cold Redis-cleared repeat was inside budget. Do not hide this variance; repeat 500-concurrent runs with server profiling before deciding whether Redis candidate caching is justified.

Current local Swipe API harness:

```powershell
.\scripts\swipe-api-load-test.ps1 -CandidateCount 5000 -ViewerCount 100 -ConcurrentUsers 100 -OnlineCandidateCount 1000 -BoostedCandidateCount 200 -ServerProfile -FailOnBudget
```

The harness uses real Ktor endpoints with mixed Free/Social/Select viewers. Core scenarios cover first deck, filtered deck, relaxed filtered deck, page two, Like, Pass, Rewind, and presence. Extended scenarios add Boosted deck ranking, Top Picks, Travel activation, and Travel deck reads; use `-SkipExtendedScenarios` for a high-concurrency core proof.

Latest 2026-05-18 Swipe API proof runs:

| Run | Errors | Initial P95 | Page Two P95 | Filtered P95 | Relaxed P95 | Like P95 | Top Picks P95 | Travel Deck P95 | Artifact |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| 100-concurrent extended | 0 | 284.1ms | 267.7ms | 349.8ms | 302.6ms | 184.5ms | 296.5ms | 256.2ms | `artifacts/swipe-api-load/20260518-210752` |
| 500-concurrent warm core | 0 | 158.9ms | 170.9ms | 259.1ms | 183.8ms | 141.7ms | n/a | n/a | `artifacts/swipe-api-load/20260518-210836` |
| 500-concurrent true-cold core | 0 | 322.2ms | 348.3ms | 431.6ms | 343.3ms | 250.9ms | n/a | n/a | `artifacts/swipe-api-load/20260518-211103` |

For a true-cold Swipe proof, restart Ktor before running `-ColdCache`; the flag clears Redis, while the restart clears the in-process rank cache. The current 500-concurrent core and 100-concurrent extended runs are inside the local p95 budgets with zero unexpected failures.

## Connected Android Grid QA

Run this before and after meaningful Grid/profile/filter UI or API-contract changes:

```powershell
.\scripts\android-grid-qa.ps1
```

Useful faster rerun after the APK is already built:

```powershell
.\scripts\android-grid-qa.ps1 -SkipBuild
```

Requirements:

- Local Ktor must be healthy at `http://127.0.0.1:8080/health/deep`.
- Docker Postgres/Redis must be running for the local server.
- One Android emulator/device must be connected through `adb`, or pass `-DeviceId`.
- Debug builds must expose the `Dev login` button.

The script writes PNG screenshots, UI hierarchy XML, screenshot signatures, visible card sequence, and `qa-summary.json` under `artifacts/android-grid-qa/<timestamp>`. A passing run proves the installed app can complete the current critical Grid loop and preserve visible card order/position through filter clear and profile close, but it is not a replacement for real-device battery/location testing.

## Connected Android Denied-Location QA

Run this before beta and after any Grid/location permission changes:

```powershell
.\scripts\android-grid-denied-location-qa.ps1
```

Useful faster rerun after the APK is already built:

```powershell
.\scripts\android-grid-denied-location-qa.ps1 -SkipBuild
```

The script seeds dev data, removes only the dev viewer's `user_locations` row, installs/launches the app, dev-logs in, denies notification if prompted, verifies the `LOCATION_REQUIRED` empty state, taps the exact `Use current location` button, denies Android location permission, verifies the app shows `Location permission is required to load nearby profiles.`, confirms Grid card text is absent, writes screenshots/XML/permission dumps under `artifacts/android-grid-denied-location-qa/<timestamp>`, and restores the dev location at cleanup.

## Android Location Policy QA

Run this after Android location or lifecycle changes:

```powershell
.\scripts\android-location-policy-check.ps1 -RunUnitTests
```

The script is also part of `scripts/verify.ps1` without `-RunUnitTests`. It protects the Grid battery policy by rejecting background location permission, foreground location service declarations, continuous location updates, or missing one-shot cancellation on app stop. The optional unit-test flag also runs `LocationStateHolderTest`, which covers the 250m no-submit threshold, 250m-1km quiet refresh, and 1km+ normal refresh.

## Android Battery/Location Profile QA

Run this on a physical Android device before beta:

```powershell
.\scripts\android-grid-battery-profile.ps1 -ForegroundMinutes 30 -BackgroundSeconds 7200
```

Use `-DeviceId` when more than one Android device is attached. The script intentionally rejects emulators unless `-AllowEmulator` is provided, because emulator results are only harness smoke evidence. It captures battery dumps, batterystats, location dumps, activity service state, Grid screenshots, foreground observation, background observation, and a `battery-profile-summary.json` under `artifacts/android-grid-battery-profile/<timestamp>`.

Current local proof:

- `scripts/android-grid-denied-location-qa.ps1 -SkipBuild` passed on emulator on 2026-05-14 with artifacts under `artifacts/android-grid-denied-location-qa/20260514-091231`.
- `scripts/android-grid-battery-profile.ps1 -AllowEmulator -SkipBuild -ForegroundMinutes 0 -BackgroundSeconds 5` completed on 2026-05-14 with artifacts under `artifacts/android-grid-battery-profile/20260514-091341`. This proves the harness, not real battery behavior.

## Security/Abuse Tests

- Mass first-message bot throttled.
- Duplicate-message spam held.
- Album request spam blocked.
- Swipe automation rate-limited.
- Scraping profile views throttled.
- Quarantined account hidden from discovery.
- Blocked users removed from Activity/Messages/Grid/Swipe.
- False-positive recovery through verification/support.
- Partner subscription cycling triggers cooldown.

## Release Checklist

- [ ] Critical E2E tests passing on Android.
- [ ] Critical E2E tests passing on iOS.
- [ ] Partial: Ktor/server integration tests with Testcontainers exist and pass locally; full release-grade DB container coverage is not complete.
- [ ] Partial: Performance benchmarks are under current local 100-concurrent Grid budgets; 500-concurrent proof exists with zero errors, but warm-run p95 variance still needs repeat profiling.
- [x] Grid API load harness exists and runs zero-error locally for the current 5,000-candidate / 100-concurrent warm workload.
- [ ] Partial: Grid API profiling exists, the two-phase Grid SQL optimization is implemented, entitlement/rate-limit/profile-view side-query pressure has been reduced, V43 covering indexes are in place, and 500-concurrent proof artifacts exist; remaining hot-path work is repeat server-profiled 500-concurrent runs and larger dense metro datasets.
- [ ] Partial: Grid shared-UI visual-signature regression, connected Android Grid structural golden QA, denied-location QA, battery-profile harness, and Android location policy checks exist and run locally; real-device screenshot and battery coverage is still open.
- [ ] Partial: core abuse/risk tests exist and pass; release-grade abuse suite is not complete.
- [ ] Manual QA on mid-range Android and older supported iPhone.
- [x] Location denied path tested on connected Android emulator/device through `scripts/android-grid-denied-location-qa.ps1`.
- [ ] Real-device Grid battery/location profile completed and reviewed.
- [ ] Partial: notification denied logic has unit coverage; manual device QA is not complete.
- [ ] Low-network path tested.

## Do Not Implement

- Do not release without tested block/report.
- Do not release without tested partner consent flows.
- Do not release if grid scroll state is lost after profile view.
- Do not release if subscription entitlement is client-trusted.
