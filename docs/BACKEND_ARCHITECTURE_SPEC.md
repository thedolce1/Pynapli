# Pynapli Backend Architecture Spec

How Ktor and Firebase divide the work, and the server rules each domain must keep. Module layout and
dependency baseline: [PROJECT_STRUCTURE_AND_DEPENDENCIES_SPEC.md](PROJECT_STRUCTURE_AND_DEPENDENCIES_SPEC.md).
Schema rules: [DATA_MODEL_SPEC.md](DATA_MODEL_SPEC.md). HTTP conventions:
[API_CONTRACT_SPEC.md](API_CONTRACT_SPEC.md). Infra: [DEVOPS_INFRA_SETUP_SPEC.md](DEVOPS_INFRA_SETUP_SPEC.md).

> **Ktor is the product authority. Firebase is supporting infrastructure where it gives leverage.**

## Who owns what

**Ktor owns:** the public API; auth sessions and authorization; account risk scoring and trust policy;
the profile model; Grid discovery; Swipe decks and ranking; partner linking; Activity generation;
notification orchestration; age verification and moderation workflows; chat membership; private
album grants and revocations; subscription entitlement and the linked-partner benefit; Boost and
premium inventory; rate limiting and quarantine; admin APIs; WebSocket events.

**Firebase supports:** FCM delivery, Crashlytics, Analytics, Remote Config, App Check, Hosting for the
website. Firebase Auth is not used: Google and Apple sign-in are verified by Ktor directly (see
"Firebase usage rules").

**Firebase is never the source of truth for:** discovery ranking, location eligibility, subscription
entitlements, partner link state, private album permissions, chat membership, moderation decisions or
safety exclusions. Firebase security rules are never the only authorization layer.

## Stack

Ktor (Netty) · PostgreSQL + PostGIS (authoritative; Flyway migrations; HikariCP; explicit SQL, with
PostGIS never hidden behind a weak abstraction) · Redis (cache, presence, rate limits, realtime
relay) · GCS for media with short-lived signed URLs, Cloud CDN for public thumbnails · FCM ·
Google Play Developer API + RTDN for purchase validation (StoreKit 2 not built yet). Background
workers run in the API process and take a Postgres advisory lock (`db/JobLock.kt`). Don't split
services before the product needs it.

## Domain rules

### Location

- Clients use while-in-use location only and send updates after movement/time thresholds. Background
  GPS is never a default dependency.
- The server computes distance; **never trust client-calculated distance or client location for
  ranking without server validation.** Distance shown to others is rounded per privacy settings.
- **Current location is never echoed back to clients.**

### Grid

- `POST /v1/grid/query` applies privacy exclusions, blocks and hides, radius, hard filters, tier
  volume gates (page size and result depth come from `subscriptions/SubscriptionPolicy.kt`), ranking,
  and returns lightweight card DTOs with a stable cursor. No full profile data in grid queries.
- Cursor pagination only; never offset pagination for the live grid.

### Swipe

Deck generation, dealbreakers, ranking, like limits, match creation, Pineapple Like (Intro Notes were
merged into it as an optional note), Boost priority and Activity events are server-side. Swipe
actions are server-authoritative; deck cards carry server-issued tokens.

### Partner links

Both users consent to a link; either can unlink. A link never merges accounts and **never grants
media or chat access**. It can grant the subscription benefit if active and eligible. Link visibility
is user-controlled.

### Messages

- Ktor owns membership and permissions.
- Grid-originated chat can start without a match if the recipient's settings allow; Swipe-originated
  chat requires a match (Pineapple Like note is the exception).
- A partner joins a chat only with explicit acceptance from everyone involved.
- Album grants are separate from chat membership.

### Activity

Ktor decides which events exist and what detail is allowed: tier gating, signed viewer/tab-scoped
cursors, privacy and incognito settings, block exclusions, linked-partner separation, retention.

### Notifications

Ktor owns eligibility, copy and detail level, deep links, quiet hours and rate limits; FCM only
delivers after Ktor allows it. Detail levels: Private (generic only), Standard (names for messages
and matches), Detailed (names and activity types). **No explicit lifestyle terms in push by default.**

### Trust, safety, moderation, age verification

Ktor owns risk scoring, risk-tier limits, holding suspicious messages for review, throttling flirts,
album requests, profile views and swipes, suppressing high-risk accounts from discovery, requiring
verification for risky accounts, preserving severe-abuse evidence, report and appeal intake, and age
verification state. Public profile content stays hidden until moderation allows it; private content
remains reportable and subject to legally required scanning.

### Private albums

Album metadata and grants live in Postgres, bytes in object storage (private by default). Access is
explicit; a linked partner gets none automatically; grants are revocable any time; timed grants
expire server-side; signed URLs are short-lived. Private media is never a public CDN asset.

### Subscriptions

Store purchases are validated server-side (receipts and server notifications) and normalized into
entitlements. **Never trust client-reported premium state.** The linked-partner benefit is computed
server-side, one beneficiary at a time, with cooldowns against link abuse.

## Firebase usage rules

- **Analytics:** coarse, privacy-safe event names only. Never send lifestyle preferences, sensitive
  tags, private album actions with sensitive labels, exact location, or message contents. Also never
  send sexual orientation, gender, relationship or partner details, couple-link state, or Playing
  Tonight status, as event parameters or user properties: for this audience they can be
  special-category data (GDPR art. 9). Advertising-ID collection stays off. (Founder decision,
  2026-11-26; the legal review of analytics consent is still the founder's.)
- **Crashlytics:** never log message contents, private album URLs, exact location, full profile data,
  or sensitive tags.
- **Remote Config:** feature flags, rollout percentages, copy variants, defaults. It never bypasses
  server authority; the server validates every limit it might influence.
- **Firebase Auth: not used (founder decision, 2026-11-26).** "Continue with Google / Apple" is
  verified directly by Ktor against Google's and Apple's own keys, which then issues its own opaque
  session, the same as email sign-in (ONBOARDING_AUTH_SPEC "Social Sign-in"). This keeps one identity
  system, server-owned, and no account data passing through Firebase. If that ever changes, the swap is
  contained: Ktor would verify a Firebase ID token instead and map it to the internal user id; the
  Firebase UID is never the domain boundary.
- **App Check:** `AppCheckPlugin` requires `X-Firebase-AppCheck` on state-changing requests when
  `PYNAPLI_APP_CHECK_ENFORCED=true`, verified against the App Check JWKS for
  `PYNAPLI_APP_CHECK_PROJECT_NUMBER`. It is not complete bot protection. Rollout steps:
  [DEVOPS_INFRA_SETUP_SPEC.md](DEVOPS_INFRA_SETUP_SPEC.md#app-check-enablement).
- Review Firebase data-residency implications before launch; keep sensitive authoritative data out
  of Firebase.

## Realtime

WebSocket streams: `/v1/presence/stream`, `/v1/activity/stream`, `/v1/messages/stream`,
`/v1/psync/stream`. Events: message created/deleted, conversation updated, typing, read receipt,
presence delta, Activity delta, partner invite, album grant update, match created, Psync session
changes.

- **WebSockets are convenience, never the sole authority.** Clients refetch canonical HTTP state on
  reconnect.
- Auth is a header, or a single `StreamAuthFrameDto` first frame within 5s for browsers. Never a URL
  query token (it would land in access logs).
- Deltas relay across replicas over Redis with per-socket backpressure; session revocation closes
  open sockets on every replica.
- Don't stream full grid reorders.

## Security

- Server-side authorization on every endpoint, including object ownership.
- Rate limits on login, messaging, album requests, flirts, reports and swipes; a request body cap
  enforced on bytes read; global per-IP and per-user request ceilings (TRUST_AND_SAFETY_SPEC, Rate
  Limits).
- Audit logs for admin and moderation actions.
- Blocks enforced across Grid, Swipe, Activity and Messages, and on every new discovery surface.
- No client-trusted premium state or distance.
- Idempotency keys for purchase and action endpoints; never retry a non-idempotent mutation without one.

## Privacy

Sensitive data: location, sexual/lifestyle preferences, linked-partner state, private albums,
messages, Activity views, verification data. Minimize analytics payloads; no exact location,
message bodies, private media URLs or sensitive tags in logs or crash reports (guarded by
`SensitiveLoggingGuardTest`); privacy-safe notification copy; support account deletion and data
export.

## Server implementation rules

- Prefer one well-shaped SQL query, or a small bounded set, over many parallel JDBC calls. Parallelize
  only independent, non-authoritative side work, and only with profiling evidence (Hikari wait time,
  p95 route timing).
- **Keep mutations that affect safety, billing, inventory, match state, moderation, blocks or partner
  consent transactional and ordered.**
- Pool settings are environment-driven (`PYNAPLI_DATABASE_MAX_POOL_SIZE`, `PYNAPLI_DATABASE_MIN_IDLE`,
  `PYNAPLI_DATABASE_CONNECTION_TIMEOUT_MS`; local defaults 10 / 1 / 5000ms). Pool tuning is not a
  substitute for query optimization.
- Request profiling (`PYNAPLI_API_PROFILING_ENABLED`, `_HEADER_REQUIRED`, `_SLOW_SPAN_MS`) adds timing
  headers when a request sends `X-Pynapli-Profile: true`. Local/staging only.
- **Startup validation** runs before any connection opens. `staging`, `prod` and `production` fail
  fast on local DB/Redis, local media storage, default cursor/deck secrets, missing cryptographic App
  Check settings, missing admin token, missing Firebase project id, missing GCS signing credentials,
  or dev-only store/media toggles. Cloud SQL connector settings are staging/prod only.

## Observability

JSON logs with request ids, token-gated Prometheus `/metrics`, `/health` and `/health/deep`. Server
metrics to watch: Grid and deck latency, message send latency, WebSocket connections, FCM
success/failure and outbox lag, purchase validation latency, moderation queue size, rate-limit hits,
error rates by endpoint. Product metrics: [ANALYTICS_AND_METRICS_SPEC.md](ANALYTICS_AND_METRICS_SPEC.md).

## Open items

- [ ] Production bucket/CDN and live GCS/Cloud Vision validation (needs founder credentials).
- [ ] StoreKit 2 server validation; real Play credentials.
- [ ] Crashlytics redaction validation.
- [ ] Production App Check rollout and real-device validation.
- [ ] Two-replica load run on real infrastructure.

## Do not

- Make Firestore the primary database for discovery.
- Put exact location into Firebase Analytics.
- Trust client-side premium flags.
- Store private album media as public CDN assets.
- Let Firebase security rules be the only authorization layer.
- Let Remote Config change server-enforced limits without validation.
- Expose sensitive lifestyle terms in push notifications by default.
- Use offset pagination for the live grid.
- Make background GPS a default dependency.
- Over-split services before the product needs it.
