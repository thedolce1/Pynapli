# Pynapli Production Readiness And Quality Spec

Last updated: 2026-04-24

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

Current status: a Docker-backed repository integration test proves stable two-page Grid cursor paging over 5,000 nearby candidates. The full beta load test with concurrent users, online presence distribution, mixed filters, and tier visibility remains open.

Expected behavior:

- Backend narrows candidates with PostGIS and indexed filters.
- Redis/H3-style coarse location caches hold hot candidate ID sets.
- API returns a capped lightweight page.
- Client appends/reorders incrementally.
- Filter changes animate differences instead of blanking the grid.
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
- Compose screenshot/regression tests for core components.
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

Use k6, Gatling, or equivalent.

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
