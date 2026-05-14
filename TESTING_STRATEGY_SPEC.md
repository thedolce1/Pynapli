# Pynapli Testing Strategy Spec

Last updated: 2026-05-13

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
- `scripts/android-grid-qa.ps1` runs the installed Android debug app against local Ktor on a connected emulator/device. It seeds dev data, optionally builds/installs the APK, logs in through dev login, captures screenshots, verifies the Grid loads, opens the filter sheet, toggles the Online quick filter, opens a profile, and asserts closing the profile returns to the same first visible Grid card.

Still open before beta:

- Promote connected Android emulator screenshots from scripted QA artifacts into golden/regression comparisons for the installed `androidApp`.
- Manual device pass on mid-range and low-end Android hardware.
- Visual checks with real thumbnail loading, denied location state, empty Grid, and profile-detail sheet.

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

The current profiled baseline is zero-error after the Grid two-phase SQL optimization, side-query optimization, and V43 covering-index pass. Latest warm 2026-05-13 run with 5,000 candidates / 100 concurrent mixed-tier users / DB pool 32 produced client p95 of Grid initial 450.4ms, Grid filter 354.5ms, Grid page two 477.0ms, profile open 138.8ms, and presence heartbeat 118.0ms. The harness refreshes planner statistics after bulk seeding before timing the API workload.

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

The script writes PNG screenshots, UI hierarchy XML, and `qa-summary.json` under `artifacts/android-grid-qa/<timestamp>`. A passing run proves the installed app can complete the current critical Grid loop, but it is not a replacement for future golden-image assertions or real-device battery/location testing.

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
- [ ] Partial: Performance benchmarks are under current local 100-concurrent Grid budgets, but beta-scale 500-concurrent Grid testing is still open.
- [x] Grid API load harness exists and runs zero-error locally for the current 5,000-candidate / 100-concurrent warm workload.
- [ ] Partial: Grid API profiling exists, the two-phase Grid SQL optimization is implemented, entitlement/rate-limit/profile-view side-query pressure has been reduced, and V43 covering indexes are in place; remaining hot-path work is proving page-two/candidate-query behavior at 500-concurrent beta scale and larger dense metro datasets.
- [ ] Partial: Grid shared-UI visual-signature regression exists and runs in the local verification gate; connected Android Grid QA is scripted and produces screenshots, but golden comparisons are still open.
- [ ] Partial: core abuse/risk tests exist and pass; release-grade abuse suite is not complete.
- [ ] Manual QA on mid-range Android and older supported iPhone.
- [ ] Location denied path tested.
- [ ] Partial: notification denied logic has unit coverage; manual device QA is not complete.
- [ ] Low-network path tested.

## Do Not Implement

- Do not release without tested block/report.
- Do not release without tested partner consent flows.
- Do not release if grid scroll state is lost after profile view.
- Do not release if subscription entitlement is client-trusted.
