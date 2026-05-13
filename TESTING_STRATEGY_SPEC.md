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

The current profiled baseline is zero-error after the Grid two-phase SQL optimization and side-query optimization. Latest 2026-05-13 run with 5,000 candidates / 100 concurrent mixed-tier users / DB pool 32 produced client p95 of Grid initial 876ms, Grid filter 831ms, Grid page two 1103ms, profile open 360ms, and presence heartbeat 198ms. Entitlement lookup is near-zero on filter/page-two cache hits and profile-view activity enqueue is near-zero. The next performance test should rerun this exact harness after a focused EXPLAIN/index pass on the candidate query and page-two cursor path.

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
- [ ] Performance benchmarks under thresholds.
- [ ] Partial: Grid API load harness exists and runs zero-error locally, but Grid filter and client-observed initial/page-two p95 timings are still above beta budgets.
- [ ] Partial: Grid API profiling exists, the two-phase Grid SQL optimization is implemented, and entitlement/rate-limit/profile-view side-query pressure has been reduced; remaining hot-path work is candidate query and DB pool pressure under filter/page-two concurrency.
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
