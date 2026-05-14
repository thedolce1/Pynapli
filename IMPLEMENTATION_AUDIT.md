# Pynapli Implementation Audit

Date: 2026-05-13

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
- PostgreSQL/PostGIS migrations through `server/src/main/resources/db/migration/V43__add_grid_query_covering_indexes.sql`.
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
- Connected Android Grid QA exists at `scripts/android-grid-qa.ps1`. It seeds dev data, builds/installs the debug APK unless skipped, logs into the installed app on a connected emulator/device, captures Grid/filter/profile screenshots, checks for app error text, and verifies profile close returns to the same first visible Grid card.

Current CI state:

- `.github/workflows/ci.yml` now runs the same Gradle verification gate as `scripts/verify.ps1`.
- Docker service startup remains local-script behavior. If server integration tests begin requiring live containers in CI, the workflow should add explicit Postgres/Redis services.

## Alignment Matrix

| Area | Status | What Is In Place | Gaps / Notes |
| --- | --- | --- | --- |
| Product model | Aligned | Individual autonomous accounts, optional linked partner, linked partner preview, partner chat invite flow, linked subscription benefit. | Keep avoiding couple-owned accounts or couple cards. |
| Language and positioning | Mostly aligned | Specs define LS-aware, classy, non-cheesy language. | Needs continuous UI copy review as screens mature. |
| Frontend design direction | Partially implemented | Subdued Compose surfaces, bottom nav, header profile entry, grid/swipe/messages/activity/store/settings surfaces, an automated Grid visual-signature regression over the shared Compose UI, and a connected Android Grid QA harness for the installed app. | Needs golden-image assertions, real-device screenshot review, and wider flow coverage before visual polish can be called complete. |
| Auth and onboarding | Partially implemented | Email signup/login/refresh/logout, dev login, session handling, signup DOB, explicit 18+ confirmation, Terms acceptance, Privacy acceptance. | Social auth parity, signup progress resume, and enhanced/provider age verification are not complete. |
| Profile | Partially implemented | Profile edit, discovery settings, profile detail, linked partner display, age-verification entry. | Data export/delete, blocked/hidden management, and full safety center need completion. |
| Location and presence | Partially implemented | Location current endpoint, PostGIS distance queries, Redis presence TTLs. | Server-side location trust/spoofing checks and movement thresholds need hardening. |
| Grid | Partially implemented | Lightweight grid DTO, separate profile endpoint, PostGIS radius/distance query, signed stable cursor pagination, tier page/depth caps, Redis-backed query rate limit, fixed square cards, stable Compose keys, shared-UI visual-signature regression, connected Android Grid QA harness, 5,000-candidate dense integration proof, 100-concurrent filtered repository regression proof, API-level mixed-tier Ktor load harness, opt-in server profiling, two-phase candidate-rank then card-hydration SQL, V43 covering lookup indexes, side-query optimization for entitlement/rate-limit/profile-view paths, planner-stat refresh in dense load tests, client movement-threshold policy, retained lazy grid state while screen is alive, block/risk filtering, partner preview, WebSocket presence deltas, and explicit thumbnail prefetching. | Current warm 100-concurrent API harness is zero-error and inside local p95 budgets. Page-two remains candidate-scan-bound, so 500-concurrent beta load, larger dense metros, cold-cache repeatability, connected Android golden assertions, and platform battery/location QA are still needed. |
| Swipe | Partially implemented | Swipe deck endpoint, action endpoint, server-authoritative match creation, immutable normal actions, block/report path, intro notes, pineapple-like inventory, boost influence, partner preview. | Robust deck-session/rank-token validation, cursor pagination, rewind, offline action cap, image preloading, top picks, passport/travel mode, and dealbreaker settings remain incomplete. |
| Messaging | Mostly implemented for MVP | Direct conversations, grid-open messaging, swipe match gating, intro-note handling, partner chat invite/accept/decline/leave, block-aware conversations, message review path. | WebSocket realtime, typing/read realtime, and deeper consent tests should be added before beta. |
| Activity | Partially implemented | Activity event model, newest-first activity, flirts, album requests, gating by entitlement, block checks, risk checks, and bounded background profile-view recording for production profile opens. | Tabs, opt-outs/incognito behavior, partner-context activity separation, and complete reveal-depth UX need review. |
| Partner links | Strong MVP path | Invite, accept, decline, unlink, one active beneficiary, cooldown/abuse controls, linked partner subscription benefit. | Support/admin review and forced unlink workflow need moderation tooling. |
| Private albums/media | Partially implemented | Upload contract, local-dev upload endpoint, albums, private grants, album request activity. | Production object storage signed URLs, CDN strategy, moderation/scanning policy, and secure media cleanup jobs remain. |
| Notifications | Partially implemented | Preferences, device registration, FCM gateway, grouping/rate-limit logic. | Real device token lifecycle, APNs/iOS path, notification analytics without sensitive payloads, and denied-permission QA remain. |
| Monetization | Partially implemented | Free/Social/Select tiers, server entitlements, linked partner benefit, activity/filters gates, intro notes, pineapple likes, boosts, ads policy, safety-free rule. | StoreKit 2 path is missing. Google Play path exists but needs real console credentials/config validation. Paywall copy needs final Social/Select review in app. |
| Trust and safety | Partially implemented | Risk model/events, Redis rate limits, duplicate message checks, held-message review, reports queue, risk queue, social action restrictions, severe action triggers, partner subscription abuse cooldown, age-verification status/start/admin review/callback path. | Risk middleware, device/account-farm signals, location spoofing signals, verification provider/market integration, appeals/support flow, false-positive metrics, and account notice workflow remain. |
| Admin moderation | Partially implemented | Reports queue/actions/audit, risk review/actions/audit, held message review/release/remove/audit. | Roles/permissions, MFA, user lookup, appeals, subscription support, partner-link review, sensitive reveal reason flow outside held messages, and separated admin API need work. |
| API contract | Partially implemented | `/v1` routes, shared DTOs, Ktor error handling, many server-side entitlement/block/risk checks, signed Grid cursor pagination, age-verification endpoints, and the Grid presence WebSocket contract. | Cursor pagination remains incomplete on other live surfaces, idempotency keys are not consistently implemented, and realtime contracts for messages/activity/notifications remain incomplete. |
| Backend architecture | Partially implemented | Ktor authoritative services, PostgreSQL/PostGIS, Flyway, Redis, Docker local services, FCM path, server-side subscription model, and Grid presence WebSockets. | Redis candidate cache, signed object storage, sensitive log redaction, analytics redaction, and formal cloud environment setup remain. |
| Mobile architecture | Partially implemented | KMP shared network/client/domain types, Compose feature state holders, Android platform integrations for billing/FCM/location, and Android Grid presence stream wiring. | iOS platform integrations, secure token storage review, offline queue rules, and broader emulator/device visual QA coverage need work. |
| Testing | Good start, not enough | Server test suite restored, shared tests, Compose Android host tests, Compose desktop Grid visual-signature test, local verification script. | Android instrumented/E2E tests, iOS tests, real DB container integration coverage expansion, abuse tests, beta-scale load tests, and connected-device screenshot regression need work. |
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
   - Page two still scans most eligible candidates because cursor ordering depends on computed distance. Redis candidate caching should be reconsidered for 500-concurrent beta targets, larger metros, cold-cache repeatability, or if page-two p95 regresses.
   - Shared Compose Grid visual-signature regression and connected Android Grid scripted QA exist, but emulator golden assertions and broader device coverage are not complete.

2. Swipe server authority needs another hardening pass.
   - Deck-session/rank-token validation is still too lightweight.
   - Cursor pagination is incomplete.
   - Offline action caps and rewind entitlement flow are not implemented.

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

2. Finish Grid hardening before starting more Swipe behavior.
   - Grid: extend load testing from the current warm 100-concurrent API proof toward the beta target, including 500 concurrent Grid users, cold-cache repeatability, and larger dense metro datasets.
   - Grid later: decide Redis candidate caching from that evidence if candidate SQL cannot meet the higher beta targets.
   - Grid: promote connected Android QA screenshots into golden assertions, then run platform battery/location QA.
   - Swipe later: deck session validation, rank-token validation, cursor pagination, action idempotency, rewind path.

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
