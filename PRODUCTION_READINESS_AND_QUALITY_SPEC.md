# Pynapli Production Readiness And Quality Spec

Last updated: 2026-05-14

## Purpose

This document defines the engineering bar that prevents Pynapli from becoming a fragile, insecure, slow, or unscalable app.

Core rule:

> Pynapli must be built as a real production system from day one. AI-generated code is allowed only when it passes the same architecture, security, performance, and testing gates as human-written code.

## Quality Philosophy

Scale, security, testing, and performance are not final polish. They are architecture requirements.

Pynapli should be built so that:

- The app loads useful cached content immediately.
- The grid remains fast in dense cities.
- Security rules are server-owned and testable.
- Abuse controls exist before public launch.
- Critical flows have automated tests.
- Every production incident can be diagnosed from logs, metrics, and traces.
- New features cannot bypass consent, privacy, billing, or moderation rules.

## Non-Negotiables

Do not ship if:

- Grid query performance is unknown.
- Block/report is untested.
- Partner consent flows are untested.
- Subscription entitlements are trusted from the client.
- Private album grants are not server-authoritative.
- API authorization checks are missing for object IDs.
- Push notifications can leak sensitive content.
- Location privacy controls are incomplete.
- Secrets are stored in source control.
- No rollback path exists for backend deploys.
- No crash/error monitoring exists in beta.

## Anti-Slop Engineering Rules

Every meaningful code change must answer:

1. What product rule does this enforce?
2. Where is the source of truth?
3. What happens when the network is slow?
4. What happens when the user is blocked, hidden, banned, unlinked, unmatched, or unsubscribed?
5. What test proves this does not regress?
6. What metric/log would show this failing in production?

Rules:

- No placeholder security.
- No TODO-based authorization.
- No fake backend assumptions in mobile UI.
- No hardcoded feature flags in production code.
- No full-profile DTOs in lightweight discovery responses.
- No silent catch blocks around security or billing decisions.
- No direct database access from routes without domain service checks.
- No UI-only enforcement for consent, privacy, albums, chat, or subscriptions.

## Architecture Gates

### Server Authority

Ktor must be authoritative for:

- Discovery eligibility.
- Grid ranking and pagination.
- Swipe deck and match creation.
- Chat membership.
- Partner link state.
- Partner invite state.
- Private album grants.
- Subscription entitlement.
- Boost inventory.
- Moderation state.
- Risk-based throttling.

The client can cache, display, queue, and optimistically animate. It cannot decide authorization.

### Lightweight Discovery

Grid and swipe card endpoints must return only the data needed for the surface.

Grid page response should stay small:

- Target page size: 60-120 cards depending device and tier.
- No full bios.
- No full photo lists.
- No private album metadata beyond minimal gated indicators if approved.
- No sensitive preference data.
- No unnecessary nested objects.

Full profiles load only when opened.

### Stable State

The mobile app must preserve:

- Grid scroll state.
- Grid card IDs and cursor.
- Active filters.
- Swipe deck session.
- Current swipe card.
- Activity cursor/filter.
- Message list scroll.
- Draft messages.

Navigation must not trigger full state loss.

## Grid Scale Requirements

Pynapli must be designed for dense metros where a user may have thousands of eligible nearby candidates.

### Required 5,000+ Nearby Candidate Scenario

Before public beta, load test a metro-cell scenario with:

- 5,000 eligible nearby users.
- 1,000 online/recently active users.
- 500 concurrent grid users.
- 100 concurrent users applying filters.
- 50 concurrent users opening profiles from grid.
- Presence updates every few minutes, not every few seconds.
- Mixed free, Social, and Select visibility caps.

The app must not request or render all 5,000 profiles.

Current status: Docker-backed repository integration tests prove stable two-page Grid cursor paging over 5,000 nearby candidates and a 100-concurrent filtered query regression guardrail. Latest local Testcontainers run on 2026-05-13: two-page dense query 170ms; 100-concurrent filtered p95 1230ms, max 1692ms.

The API-level Ktor load harness now exists at `scripts/grid-api-load-test.ps1`. It exercises 5,000 dense candidates, 1,000 online presence keys, 100 concurrent mixed Free/Social/Select users, presence heartbeats, first grid query, filtered grid query, page-two query where entitlement allows it, and profile opens.

Earlier pre-optimization local runs on 2026-05-13 produced zero HTTP errors but missed p95 budgets:

| Server Shape | Scenario | P95 | Budget | Status |
| --- | --- | ---: | ---: | --- |
| Default DB pool 10 | Grid initial | 4338ms | 800ms | Over |
| Default DB pool 10 | Grid filter | 3766ms | 500ms | Over |
| Default DB pool 10 | Grid page two | 3369ms | 800ms | Over |
| Default DB pool 10 | Presence heartbeat | 479ms | 300ms | Over |
| Default DB pool 10 | Profile open | 4596ms | 800ms | Over |
| Tuned DB pool 32 / min idle 8 | Grid initial | 1977ms | 800ms | Over |
| Tuned DB pool 32 / min idle 8 | Grid filter | 1509ms | 500ms | Over |
| Tuned DB pool 32 / min idle 8 | Grid page two | 1795ms | 800ms | Over |
| Tuned DB pool 32 / min idle 8 | Presence heartbeat | 341ms | 300ms | Over |
| Tuned DB pool 32 / min idle 8 | Profile open | 1181ms | 800ms | Over |

This is useful evidence, not a failure to hide: the API path was stable under this local load shape, but it needed profiling and optimization before the Grid could be called beta-ready.

Pre-optimization profiled follow-up on 2026-05-13 with `PYNAPLI_API_PROFILING_ENABLED=true` and the same DB pool 32 / min idle 8 showed:

- Grid initial p95 1793ms; server route p95 1749ms; service p95 1607ms; repository p95 1138ms; DB candidate query p95 1064ms; DB connection wait p95 161ms.
- Grid filter p95 1650ms; repository p95 1018ms; DB candidate query p95 897ms; DB connection wait p95 189ms.
- Grid page two p95 1807ms; repository p95 1340ms; DB candidate query p95 1226ms.
- Presence heartbeat p95 272ms and is inside the current budget.
- Profile open p95 1188ms; activity record p95 604ms; profile repository p95 305ms; public profile SQL p95 170ms; DB connection wait p95 126ms.
- Hikari snapshots reached active 32 / total 32, with waiting threads p95 around 24-27 across hot scenarios.

Representative `EXPLAIN (ANALYZE, BUFFERS)` for the no-filter Grid candidate query returned 61 cards in about 103ms alone, touched about 98k shared buffers, and looped through about 5,100 candidates while running lateral profile-photo, linked-partner, block, and boost checks. The evidence points to DB pressure from over-hydrating/ranking the full dense candidate set under concurrency.

The Grid repository now uses a two-phase SQL shape: it ranks eligible candidate IDs/distance/boost state first, caps to the requested page plus cursor buffer, then hydrates profile thumbnail and linked-partner details only for that page. Post-optimization profiled follow-up on 2026-05-13 with the same 5,000-candidate / 100-concurrent / DB pool 32 shape produced zero HTTP errors:

| Scenario | Client P95 | Server Route P95 | Service P95 | Repository P95 | Candidate Query P95 | Budget | Status |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| Grid initial | 884ms | 798ms | 779ms | 571ms | 557ms | 800ms | Slightly over client budget |
| Grid filter | 737ms | 634ms | 614ms | 444ms | 441ms | 500ms | Over filter budget |
| Grid page two | 872ms | 795ms | 775ms | 640ms | 604ms | 800ms | Slightly over client budget |
| Presence heartbeat | 171ms | 71ms | 42ms | n/a | n/a | 300ms | OK |
| Profile open | 439ms | 374ms | 351ms | 109ms | n/a | 800ms | OK |

The side-query optimization pass now reduces repeated entitlement reads with a short-lived server cache, uses a single Redis Lua call for rate-limit counters, folds profile-view eligibility into one upsert statement, avoids a separate notification-preference round trip, and moves production profile-view activity recording onto a bounded background path. Latest 2026-05-13 profiled follow-up with the same load shape produced zero HTTP errors:

| Scenario | Client P95 | Server Route P95 | Service P95 | Repository P95 | Candidate Query P95 | Notable Side-Query P95 | Budget | Status |
| --- | ---: | ---: | ---: | ---: | ---: | --- | ---: | --- |
| Grid initial | 876ms | 788ms | 743ms | 641ms | 610ms | entitlement 73ms, rate-limit 47ms | 800ms | Slightly over client budget |
| Grid filter | 831ms | 777ms | 741ms | 661ms | 651ms | entitlement 0ms, rate-limit 38ms | 500ms | Over filter budget |
| Grid page two | 1103ms | 1023ms | 979ms | 901ms | 869ms | entitlement 0ms, rate-limit 49ms | 800ms | Over page budget |
| Presence heartbeat | 198ms | 105ms | 45ms | n/a | n/a | Redis heartbeat 45ms | 300ms | OK |
| Profile open | 360ms | 281ms | 237ms | 173ms | n/a | activity enqueue 0.1ms | 800ms | OK |

Decision: the side-query pressure is materially reduced, but Grid is still not beta-scale complete because candidate SQL and DB pool saturation dominate filter/page-two p95. Redis candidate caching is now back on the decision table, but the next evidence-driven step should be a focused `EXPLAIN`/index pass on the two-phase candidate query, especially cursor/page-two behavior, before adding Redis candidate caches.

Focused `EXPLAIN`/index pass on 2026-05-13:

- PostgreSQL underestimates dense `ST_DWithin` result size in the local 5,000-candidate metro seed, so broad and page-two Grid queries still scan/rank most nearby candidates.
- Covering partial indexes for active users, visible profiles, and visible Grid discovery settings reduced the no-filter candidate plan from about 57.5k shared buffers / 73ms to about 42.3k shared buffers / 61ms.
- Filtered candidate plans were materially cheaper: relationship-context and style+intent filters were about 20-22k shared buffers / 46-47ms, and the age-range filter was about 25.5k shared buffers / 37ms.
- Page two remains candidate-scan-bound because cursor filtering depends on computed boost/distance/user ordering. This is acceptable at the current 100-concurrent warm API target, but it is the first place to re-evaluate for 500-concurrent beta targets.
- `scripts/grid-api-load-test.ps1` now refreshes planner statistics after bulk seeding, and the dense repository performance tests do the same before timing.

Latest warm profiled follow-up after migration `V43__add_grid_query_covering_indexes.sql` with the same 5,000-candidate / 100-concurrent / DB pool 32 shape produced zero HTTP errors:

| Scenario | Client P95 | Server Route P95 | Service P95 | Repository P95 | Candidate Query P95 | Budget | Status |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| Grid initial | 450.4ms | 407.3ms | 396.4ms | 332.3ms | 320.7ms | 800ms | OK |
| Grid filter | 354.5ms | 293.1ms | 280.9ms | 235.6ms | 223.5ms | 500ms | OK |
| Grid page two | 477.0ms | 417.3ms | 400.9ms | 365.4ms | 357.2ms | 800ms | OK |
| Presence heartbeat | 118.0ms | 41.0ms | 22.7ms | n/a | n/a | 300ms | OK |
| Profile open | 138.8ms | 93.2ms | 79.3ms | 52.4ms | n/a | 800ms | OK |

500-concurrent proof runs on 2026-05-14 used 5,000 candidates, 1,000 online candidate presence keys, 500 mixed-tier viewers, and 500-way concurrency. The load harness now writes `summary.json` and `results.csv` artifacts and supports `-ColdCache` to clear Redis before a run.

| Run | Errors | Grid Initial P95 | Grid Filter P95 | Grid Page Two P95 | Presence P95 | Profile Open P95 | Status |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| Warm local run | 0 | 838.3ms | 702.6ms | 943.4ms | 220.3ms | 318.9ms | Stable, over Grid p95 budgets |
| Cold Redis-cleared local run | 0 | 638.2ms | 492.0ms | 709.8ms | 144.4ms | 167.9ms | Stable, within current budgets |

Decision after the index pass and first 500-concurrent proofs: do not add Redis candidate caching yet just because it is in the architecture plan. The current 500-concurrent API path is stable with zero HTTP errors, but warm-run p95 variance means the next step should be repeat 500-concurrent runs with server profiling enabled. Revisit Redis/H3-style candidate caching if repeated 500-concurrent profiled runs show candidate SQL/page-two scans cannot reliably meet budget, or when larger dense metro datasets increase p95.

Connected Android rendering QA now exists through `scripts/android-grid-qa.ps1` and structural golden assertions in `scripts/android-grid-qa-goldens.json`. It verifies Grid load, filter sheet, Online empty state, profile open, filter-clear stability, profile-return stability, screenshot signatures, and visible card order/position. Denied-location QA now exists through `scripts/android-grid-denied-location-qa.ps1`; it removes the dev viewer's server location, exercises Android permission denial, verifies the denied state, and restores dev data. Android location policy QA now exists through `scripts/android-location-policy-check.ps1`; it rejects background location, foreground location services, continuous location updates, and missing one-shot cancellation on app stop. Battery/location profile artifact capture now exists through `scripts/android-grid-battery-profile.ps1`, but real-device profiling remains open until a physical Android device is connected and reviewed.

Expected behavior:

- Backend narrows candidates with PostGIS and indexed filters.
- Redis/H3-style coarse location caches hold hot candidate ID sets if the API-level load test shows PostGIS cannot meet warm/filter p95 budgets without them.
- API returns a capped lightweight page.
- Client appends/reorders incrementally.
- Filter changes animate differences instead of blanking the grid.
- Small location changes do not refresh or reshuffle the grid.
- Presence/distance changes do not reshuffle the whole grid.

### Grid Performance Budgets

Targets before beta:

- Warm grid API p95: under 300ms server time.
- Cold grid API p95: under 800ms server time.
- First visible cached grid: under 500ms after app shell opens.
- First network-refreshed grid: under 1.5s on normal mobile network.
- Filter response p95: under 500ms server time.
- Filter UI transition: under 300ms after response.
- Profile open from grid: under 800ms perceived load with cached shell.
- Return from profile: exact same grid position.

Targets are starting budgets. They should tighten after real telemetry exists.

### Grid Query Safety

Every grid query must apply:

- Authenticated user check.
- Age/adult eligibility.
- Location privacy rules.
- Blocks and hides.
- Moderation exclusions.
- Risk/quarantine exclusions.
- Partner visibility rules.
- Subscription visibility limits.
- Rate limits.

Current implementation enforces Grid page/depth caps server-side by entitlement tier: Free page 60 / depth 60, Social page 90 / depth 600, Select page 120 / uncapped depth. Cursor payloads include seen-count state, so a capped tier cannot bypass depth by changing requested page size.

These rules must be tested as backend integration tests.

## Mobile Performance Requirements

### Startup

Targets:

- App shell visible quickly.
- Cached user/session state restored before network refresh.
- Cached grid/messages/activity displayed if fresh enough.
- Heavy work off the main thread.
- No blocking network call before first UI.

### Compose Performance

Rules:

- Use stable item keys in lazy lists/grids.
- Avoid rebuilding whole screen state for small deltas.
- Avoid passing unstable large objects deep into composables.
- Keep grid tile composables tiny.
- Decode and cache thumbnails at display-appropriate sizes.
- Use pagination and prefetching.
- Use Compose performance tooling before beta.

### Battery

Rules:

- Use while-in-use location only unless a future feature justifies more.
- Location updates use movement/time thresholds.
- Android one-shot location requests are canceled when the app stops.
- Denied location shows a clear recovery path and does not leak Grid cards.
- Presence heartbeats are throttled.
- WebSocket reconnect uses backoff.
- Push handles inactive users instead of constant polling.
- Do not stream full grid movement.

Battery test scenarios:

- 30 minutes active grid browsing.
- 30 minutes swipe session.
- 30 minutes chat/media use.
- App backgrounded for 2 hours.
- Poor network reconnect loop.

Current tooling:

- `scripts/android-grid-denied-location-qa.ps1` proves denied-location UI behavior on a connected emulator/device and restores dev data after the run.
- `scripts/android-grid-battery-profile.ps1 -ForegroundMinutes 30 -BackgroundSeconds 7200` is the physical-device battery/location profile path. `-AllowEmulator` is only for harness smoke testing and must not be counted as real battery evidence.

## Backend Performance Requirements

### Database

Required:

- PostgreSQL with PostGIS.
- Geospatial indexes for discovery.
- Composite indexes for common filter patterns.
- `EXPLAIN ANALYZE` checks for slow queries.
- Migration review for every schema change.
- Query timeouts.
- Connection pool limits.

Avoid:

- Offset pagination on live grid.
- Unbounded `SELECT *`.
- Loading full profiles for grid.
- Cross joins for recommendations.
- Runtime migrations during request handling.

### Redis

Use Redis for:

- Presence TTLs.
- Candidate ID caches.
- Rate limits.
- Boost queues.
- Short-lived action dedupe.
- WebSocket fanout coordination if needed.

Redis is not authoritative for:

- Profile state.
- Partner links.
- Album grants.
- Subscriptions.
- Moderation.

### API

Every endpoint must have:

- Authentication requirement documented.
- Authorization checks documented.
- Rate limits.
- Request size limits.
- Error shape.
- Idempotency requirement for retryable mutations.
- Observability labels.

## Security Requirements

Pynapli should align with:

- OWASP API Security Top 10.
- OWASP MASVS for mobile app security.
- OWASP ASVS for application security verification.
- NIST digital identity guidance where relevant.

### API Security

Must protect against:

- Broken object-level authorization.
- Broken authentication.
- Broken object property authorization.
- Unrestricted resource consumption.
- Broken function-level authorization.
- Unrestricted access to sensitive business flows.
- SSRF.
- Security misconfiguration.
- Improper API inventory.
- Unsafe third-party API consumption.

Practical requirements:

- Never trust client-provided user IDs for ownership.
- Every object access checks subject, action, object, and context.
- DTO allowlists prevent mass assignment.
- Request body size limits.
- Per-user, per-device, per-IP, and per-risk-tier rate limits.
- Audit logs for sensitive operations.
- Separate admin auth from user auth.
- Admin endpoints unavailable from public mobile clients.

### Mobile Security

Required:

- Secure token storage: Android Keystore, iOS Keychain.
- TLS only.
- Certificate pinning evaluated before beta.
- No sensitive logs.
- No exact location in crash reports.
- No private media URLs in logs.
- App lock/biometric option.
- Screenshot/privacy protection evaluated for sensitive views.
- Root/jailbreak signals may affect risk but should not be sole ban triggers.

### Secrets

Rules:

- No secrets in repo.
- No secrets in mobile binaries except public platform config.
- Server secrets in Secret Manager or equivalent.
- Separate dev/staging/prod secrets.
- Rotate secrets after accidental exposure.
- CI uses least-privilege credentials.

### Media Security

Private media:

- Stored outside public CDN paths.
- Delivered through short-lived signed URLs or proxy authorization.
- Grant/revoke is server-authoritative.
- Revocation prevents future access.
- Watermarking/screenshot friction can be evaluated, but cannot be trusted as full protection.

### Location Security

Location rules:

- Store exact location only where necessary.
- Use rounded/fuzzed distances where privacy setting requires it.
- Never expose exact coordinates to other clients.
- Distance hidden does not mean discovery sorting stops unless product explicitly says so.
- Location changes have anti-spoofing/risk checks.

## Testing Gates

### Required Test Layers

- Unit tests for pure domain rules.
- Ktor integration tests with Testcontainers.
- Database migration tests.
- Contract tests for API DTOs.
- Mobile ViewModel/state tests.
- Compose screenshot/regression tests for core components. Current Grid coverage includes a shared-UI visual-signature pixel test in `GridScreenVisualRegressionTest`, connected Android screenshot/golden assertions in `scripts/android-grid-qa.ps1`, denied-location screenshots/assertions in `scripts/android-grid-denied-location-qa.ps1`, Android location policy checks in `scripts/android-location-policy-check.ps1`, and physical-device battery/profile capture in `scripts/android-grid-battery-profile.ps1`. Current Swipe coverage includes `SwipeScreenVisualInteractionTest` for shared-UI visual signature plus Like/Pass/Intro Note interaction checks.
- E2E tests for critical journeys.
- Load tests for grid, swipe, messages, activity, and auth.
- Abuse tests for spam, scraping, and rate limits.
- Security tests for authorization and sensitive DTO fields.

### Critical Backend Test Matrix

Every protected endpoint should test:

- Unauthenticated request.
- Wrong user accessing object.
- Blocked user.
- Hidden user.
- Banned/quarantined user.
- Expired entitlement.
- Revoked album grant.
- Unlinked partner.
- Rate-limited actor.
- Stale idempotency key.

### Critical Mobile Test Matrix

Every major screen should test:

- Loading.
- Empty.
- Error.
- Offline/stale cache.
- Permission denied.
- Subscription gated.
- Blocked/removed target.
- Large text.
- Slow network.
- Navigation away/back state preservation.

## Load Testing

Use k6, Gatling, PowerShell 7 local harnesses, or equivalent.

Required scenarios before beta:

- Login/session refresh.
- First grid query.
- Paginated grid query.
- Grid filter apply.
- Profile open from grid.
- Swipe deck load.
- Swipe action burst.
- Match creation.
- Message send/receive.
- Activity feed read.
- Album grant/revoke.
- Partner link accept/unlink.
- Playing Tonight activation.

Current local Grid API harness:

```powershell
.\scripts\grid-api-load-test.ps1 -CandidateCount 5000 -ViewerCount 100 -ConcurrentUsers 100 -OnlineCandidateCount 1000
```

Use `-FailOnBudget` when the harness should fail the shell on p95 budget regressions.

Use `-ServerProfile` only when Ktor was started with `PYNAPLI_API_PROFILING_ENABLED=true`; it requests per-response timing headers and prints route/service/DB/Redis/Hikari summaries.

Minimum beta load targets:

- 500 concurrent active users.
- 5,000 users in a dense city test dataset.
- 50 messages/second burst.
- 100 swipe actions/second burst.
- 100 grid filter changes/minute.
- 99% successful API responses under expected load.

Pre-launch scale targets should be revised upward after beta telemetry.

## Observability

Required before beta:

- Structured server logs.
- Request IDs.
- User/session correlation IDs with privacy-safe hashing.
- API latency metrics by endpoint.
- Error rate metrics by endpoint.
- Database slow query logs.
- Redis latency/error metrics.
- WebSocket connection metrics.
- Push send success/failure metrics.
- Subscription validation metrics.
- Moderation queue metrics.
- Client crash reporting.
- Client performance traces for startup/grid/swipe/messages.

Do not log:

- Message bodies.
- Private album URLs.
- Exact coordinates.
- Verification documents.
- Payment tokens.
- Raw access tokens.
- Sensitive lifestyle tags in high-cardinality logs.

## CI/CD Gates

Before merge to `main`:

- Build passes.
- Unit tests pass.
- Server integration tests pass.
- Static analysis passes.
- Dependency vulnerability scan passes or has approved exception.
- Secrets scan passes.
- API contract changes are reviewed.
- Migration tests pass if schema changed.

Before deploy to staging:

- Docker image builds.
- Health check passes.
- Migrations run successfully.
- Smoke tests pass.

Before production deploy:

- Staging smoke tests pass.
- Rollback path exists.
- Error budget is healthy.
- No unresolved P0/P1 security findings.

## Release Gates

Alpha can launch when:

- Core flows work end-to-end.
- Basic monitoring exists.
- Crash reporting exists.
- Block/report works.
- Subscription validation is server-owned if billing is enabled.

Beta can launch when:

- Grid load test passes 5,000-user dense dataset.
- Critical E2E journeys pass.
- Security authorization test matrix passes.
- Abuse/rate-limit tests pass.
- Moderation/admin workflows exist.
- Privacy/terms/community guideline links exist.

Public launch can happen when:

- Production monitoring and alerts are active.
- Backup/restore has been tested.
- Incident response process exists.
- App store review risks are addressed.
- Support inbox and escalation process exist.
- Performance budgets are met on mid-range devices.

## AI-Assisted Coding Policy

AI can help generate code, tests, docs, and refactors.

AI-generated code cannot be merged unless:

- It compiles.
- It is reviewed.
- It follows project architecture.
- It includes relevant tests.
- It does not bypass security rules.
- It does not add unnecessary dependencies.
- It does not silently weaken privacy, consent, billing, or safety behavior.

Reject AI output that:

- Invents nonexistent APIs.
- Adds broad catch-all error handling.
- Hardcodes secrets or fake config.
- Adds fake data paths into production code.
- Moves business rules into UI.
- Removes tests to make builds pass.
- Adds broad permissions without product need.

## Related Specs

- [BACKEND_ARCHITECTURE_SPEC.md](BACKEND_ARCHITECTURE_SPEC.md)
- [GRID_IMPLEMENTATION_SPEC.md](GRID_IMPLEMENTATION_SPEC.md)
- [MOBILE_APP_ARCHITECTURE_SPEC.md](MOBILE_APP_ARCHITECTURE_SPEC.md)
- [PROJECT_STRUCTURE_AND_DEPENDENCIES_SPEC.md](PROJECT_STRUCTURE_AND_DEPENDENCIES_SPEC.md)
- [TESTING_STRATEGY_SPEC.md](TESTING_STRATEGY_SPEC.md)
- [TRUST_AND_SAFETY_SPEC.md](TRUST_AND_SAFETY_SPEC.md)
- [DEVOPS_INFRA_SETUP_SPEC.md](DEVOPS_INFRA_SETUP_SPEC.md)

## Source References

- OWASP API Security Top 10 2023: https://owasp.org/API-Security/editions/2023/en/0x11-t10/
- OWASP Mobile Application Security Verification Standard: https://mas.owasp.org/MASVS/
- OWASP Application Security Verification Standard: https://owasp.org/www-project-application-security-verification-standard/
- NIST SP 800-63B Digital Identity Guidelines: https://pages.nist.gov/800-63-4/sp800-63b.html
