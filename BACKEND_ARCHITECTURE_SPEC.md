# Pynapli Backend Architecture Spec

Last updated: 2026-04-24

## Purpose

This document defines how Pynapli should use Ktor and Firebase together without letting Firebase become the whole backend.

Core rule:

> Ktor is the product authority. Firebase is supporting infrastructure where it gives leverage.

For concrete server module/folder layout and dependency baselines, use [PROJECT_STRUCTURE_AND_DEPENDENCIES_SPEC.md](PROJECT_STRUCTURE_AND_DEPENDENCIES_SPEC.md) alongside this document.

For production scale, security, performance, observability, and release gates, use [PRODUCTION_READINESS_AND_QUALITY_SPEC.md](PRODUCTION_READINESS_AND_QUALITY_SPEC.md) alongside this document.

Pynapli needs a server-authoritative backend because it handles:

- Location discovery
- Swipe ranking
- Partner linking
- Chat permissions
- Private album access
- Subscriptions and linked-partner entitlements
- Activity visibility
- Trust and safety
- Moderation
- Rate limiting
- Privacy controls

These should not be left entirely to client-side Firebase rules.

## Backend Responsibilities

### Ktor Owns

Ktor should own:

- Public API
- Authentication session exchange and authorization
- Account risk scoring and trust policy
- User/profile domain model
- Grid discovery
- Swipe recommendations
- Partner linking
- Activity event generation
- Notification orchestration
- Age verification state and moderation workflows
- Chat membership rules
- Private album grants/revocations
- Subscription entitlement validation
- Linked-partner subscription benefit
- Boost and premium inventory
- Trust/safety policy enforcement
- Risk-based rate limiting and quarantine
- Moderation workflows
- Admin tooling APIs
- WebSocket realtime events
- Rate limits and anti-abuse

### Firebase Supports

Firebase can support:

- Push notifications via Firebase Cloud Messaging
- Crashlytics
- Analytics
- Remote Config
- App Check
- Optional Firebase Auth as identity provider
- Optional Cloud Messaging topic delivery for coarse notifications

Firebase should not be the source of truth for:

- Discovery ranking
- Location eligibility
- Subscription entitlements
- Partner link state
- Private album permissions
- Chat membership authority
- Moderation decisions
- Safety exclusions

## Recommended Stack

Core:

- Ktor server
- PostgreSQL
- PostGIS
- Redis
- Object storage, such as S3 or GCS
- CDN for images/thumbnails
- Firebase Cloud Messaging
- Firebase Crashlytics
- Firebase Analytics
- Firebase Remote Config
- StoreKit / Google Play Billing server validation

Optional:

- Firebase Auth
- Firebase App Check
- Cloud Tasks / queue system
- OpenSearch or Meilisearch for admin/profile search
- Kafka/PubSub later if event volume justifies it

## High-Level Architecture

```text
Mobile App (KMP + Compose)
    |
    | Ktor Client / WebSocket
    v
Ktor API
    |
    |-- PostgreSQL + PostGIS: authoritative relational/location data
    |-- Redis: cache, presence, rate limits, queues
    |-- Object Storage: media originals/private assets
    |-- CDN: public thumbnails
    |-- Firebase FCM: push notifications
    |-- Firebase Analytics/Crashlytics/Remote Config
    |-- StoreKit/Google Play: purchase validation
```

## Domain Boundaries

### Profiles

Ktor owns profile CRUD and visibility.

Stored in PostgreSQL:

- User account
- Profile fields
- Discovery settings
- Intent settings
- Lifestyle tags
- Linked partner visibility
- Verification state
- Privacy settings
- Moderation status

Firebase should not directly expose profile writes to clients.

### Location

Ktor owns location ingestion and grid eligibility.

PostGIS stores:

- Last known eligible location
- Rounded/fuzzed location where needed
- Geospatial indexes
- City/neighborhood bucket
- Travel/passport location

Redis stores:

- Hot presence/location buckets
- Nearby candidate cache
- Boost queues

Rules:

- Use while-in-use location on client.
- Use last known location for quick render.
- Send updates only after movement/time thresholds.
- Round/fuzz distance based on privacy settings.
- Never rely on client-calculated distance for authority.

### Grid

Ktor owns grid results.

Grid endpoint:

```http
POST /v1/grid/query
```

Responsibilities:

- Apply privacy exclusions
- Apply blocks/hides
- Apply location radius
- Apply hard filters
- Apply tier volume gates
- Apply ranking
- Return lightweight card DTOs
- Return stable cursor

Do not fetch full profile data in grid query.

### Swipe

Ktor owns deck generation and swipe actions.

Endpoints:

```http
POST /v1/swipe/deck
POST /v1/swipe/action
POST /v1/swipe/rewind
```

Responsibilities:

- Generate deck session
- Apply dealbreakers
- Rank candidates
- Enforce like limits
- Create matches
- Validate Intro Notes
- Apply Boost/Super Like priority
- Queue Activity events

Swipe actions are server-authoritative.

### Partner Links

Ktor owns partner link lifecycle.

Endpoints:

```http
POST /v1/partner-links/request
POST /v1/partner-links/{id}/accept
POST /v1/partner-links/{id}/deny
POST /v1/partner-links/{id}/unlink
PATCH /v1/partner-links/{id}/visibility
```

Rules:

- Both users consent to link.
- Either user can unlink.
- Link does not merge accounts.
- Link does not grant media/chat access.
- Link can grant subscription entitlement if active and eligible.
- Link visibility is user-controlled.

### Messages

Ktor owns chat membership and permissions.

Realtime can use Ktor WebSockets.

Database stores:

- Chat threads
- Participants
- Messages
- Message media references
- Read receipts
- Typing indicators, optionally ephemeral in Redis
- Partner invite state

Rules:

- Grid-originated chat can start without match if recipient settings allow.
- Swipe-originated chat requires match except Intro Note flow.
- Partner invite requires explicit acceptance.
- Private album grants are separate from chat membership.

### Activity

Ktor owns Activity event generation and visibility.

Endpoint:

```http
GET /v1/activity
```

Ktor applies:

- Tier gating
- Privacy settings
- Incognito effects
- Block exclusions
- Linked partner separation
- Retention windows

Firebase can deliver push notifications for Activity, but Ktor decides what event exists and what copy/detail is allowed.

### Notifications

Ktor owns notification eligibility, copy/detail level, deep links, quiet-hour handling, and rate limits. Firebase Cloud Messaging only delivers notifications after Ktor decides they are allowed.

Push notifications must avoid explicit lifestyle terms by default.

### Moderation And Age Verification

Ktor owns age-gate state, market-driven verification requirements, moderation queues, report intake, and enforcement decisions.

Public profile content should not become visible until moderation allows it. Private content remains reportable and subject to legally required safety scanning.

### Trust And Safety

Ktor owns account risk scoring and enforcement.

Core responsibilities:

- Compute account risk score from behavior signals.
- Apply risk-tier limits.
- Hold suspicious messages for review.
- Throttle spammy flirts, album requests, profile views, and swipe actions.
- Suppress high-risk accounts from discovery.
- Require verification for risky accounts.
- Preserve severe abuse evidence.
- Support moderation review and appeals.

Firebase should not decide whether a user is trusted, banned, quarantined, or visible in discovery.

### Private Albums

Ktor owns album metadata and access grants.

Object storage owns media bytes.

Database stores:

- Album
- Media item metadata
- Grant recipient
- Grant scope
- Expiration
- Revocation
- Audit trail

Rules:

- Public photos are moderation-first.
- Private album access is explicit.
- Linked partner access is not automatic.
- Grants can be revoked anytime.
- Timed grants expire server-side.
- Signed URLs should be short-lived.

### Subscriptions

Ktor owns entitlements.

StoreKit/Google Play validate purchases. Ktor stores normalized entitlements.

Database stores:

- Purchase records
- Subscription state
- Entitlement tier
- Expiration
- Renewal status
- Linked partner beneficiary
- Fraud/abuse flags

Rules:

- Never trust client-reported premium state.
- Validate receipts/server notifications.
- Linked partner benefit is computed server-side.
- One active beneficiary link at a time.
- Cooldowns prevent subscription-link abuse.

## Firebase Usage Details

### Firebase Cloud Messaging

Use FCM for:

- New message notification
- New match notification
- Partner chat invite
- Album request
- Generic Activity notification
- Safety/moderation notices

Ktor should produce notification jobs and decide:

- Recipient
- Notification type
- Privacy level
- Copy
- Deep link
- Whether notification is allowed

Notification detail levels:

- Private: generic only
- Standard: names for messages/matches
- Detailed: names and activity types

Do not send explicit lifestyle terms by default.

### Firebase Analytics

Use Firebase Analytics for product metrics, but avoid sending sensitive lifestyle labels where possible.

Track:

- Screen views
- Funnel steps
- Subscription conversion
- Feature usage counts
- Crash-free engagement
- Onboarding completion

Avoid sending:

- Explicit lifestyle preferences
- Private album actions with sensitive labels
- Detailed sexual/lifestyle terms
- Exact location
- Message contents

Use coarse, privacy-safe event names.

### Firebase Crashlytics

Use for client crash diagnostics.

Do not log:

- Message contents
- Private album URLs
- Exact location
- Full profile data
- Sensitive tags

### Firebase Remote Config

Use for:

- Feature flags
- Rollout percentages
- Paywall copy variants
- Filter defaults
- Onboarding experiments
- Notification detail defaults

Do not use Remote Config to bypass server authority.

Example safe flags:

```text
grid_default_radius_km
activity_free_visible_count
swipe_daily_free_likes
enable_intro_notes
enable_partner_chat_invites
```

Server must still validate all limits.

### Firebase Auth

Firebase Auth is optional.

If used:

- Client signs in with Firebase.
- Client sends Firebase ID token to Ktor.
- Ktor verifies token.
- Ktor creates/updates internal user account.
- Ktor issues or manages its own session/JWT if desired.

Ktor still owns authorization.

Do not use Firebase Auth UID as the only domain model boundary. Maintain internal user IDs.

### Firebase App Check

Consider App Check to reduce API abuse from non-genuine clients.

Ktor can verify App Check tokens on sensitive endpoints.

Do not treat App Check as complete bot protection.

## Database Model Sketch

Core tables:

```text
users
profiles
profile_photos
profile_settings
locations
partner_links
blocks
hides
swipe_actions
matches
chat_threads
chat_participants
messages
activity_events
albums
album_media
album_grants
subscriptions
entitlements
boosts
moderation_cases
reports
account_risk_states
risk_events
moderation_appeals
notification_events
age_verifications
playing_tonight_sessions
psync_sessions
```

PostGIS:

```text
locations.geom geography(Point, 4326)
```

Indexes:

- `locations.geom` GiST
- `activity_events.recipient_user_id, created_at`
- `messages.thread_id, created_at`
- `swipe_actions.actor_user_id, target_user_id`
- `partner_links.user_a_id, user_b_id`
- `album_grants.owner_user_id, recipient_user_id`
- `blocks.blocker_user_id, blocked_user_id`
- `account_risk_states.user_id`
- `risk_events.user_id, created_at`

## Redis Model Sketch

Keys:

```text
presence:user:{userId}
presence:cell:{cellId}
grid:candidates:{filterHash}:{cellId}
rate:first_message:{userId}
rate:album_request:{userId}
rate:flirt:{userId}
rate:first_message:{userId}
rate:profile_view:{userId}
risk:short_window:{userId}
boost:active:{regionId}
ws:sessions:{userId}
entitlement:cache:{userId}
```

Use TTLs aggressively for ephemeral state.

Redis is cache/ephemeral state, not authoritative records.

## API Shape

Recommended API groups:

```text
/v1/auth
/v1/me
/v1/profiles
/v1/grid
/v1/swipe
/v1/activity
/v1/messages
/v1/partner-links
/v1/albums
/v1/subscriptions
/v1/boosts
/v1/reports
/v1/settings
```

Use `kotlinx.serialization` for DTOs.

Use consistent:

- Request IDs
- Idempotency keys for purchase/action endpoints
- Cursor pagination
- Error codes
- Rate-limit headers where useful

## WebSocket Architecture

Ktor WebSocket channel:

```http
GET /v1/realtime
```

Realtime events:

- Message received
- Typing status
- Read receipt
- Presence delta
- Activity delta
- Partner invite
- Album grant update
- Match created

Rules:

- WebSocket is convenience/realtime, not sole authority.
- Client should refetch canonical state after reconnect.
- Use heartbeat/ping.
- Store session mapping in Redis.
- Do not stream full grid reorders.

## Queues And Workers

Use background workers for:

- Image moderation
- Thumbnail generation
- Push notifications
- Subscription webhook processing
- Activity aggregation
- Expiring album grants
- Expiring Picks
- Boost result summaries
- Abuse detection

At small scale:

- Ktor worker module + Redis queue can work.

At larger scale:

- Cloud Tasks, Pub/Sub, Kafka, or managed queue.

## Security

Requirements:

- Server-side authorization on every endpoint.
- Object storage private by default.
- Signed URLs for private media.
- Short-lived signed URLs.
- Rate limits on messaging, album requests, flirts, login, reports.
- App Check or equivalent client attestation where useful.
- Audit logs for moderation/admin actions.
- Strong block enforcement.
- No client-trusted premium state.
- No client-trusted location distance.

## Privacy

Sensitive data:

- Location
- Sexual/lifestyle preferences
- Linked partner state
- Private albums
- Messages
- Activity views
- Verification data

Rules:

- Minimize analytics payloads.
- Avoid exact location in logs.
- Avoid sensitive tags in crash reports.
- Keep notification copy privacy-safe.
- Support account deletion.
- Support data export where required.
- Review Firebase data residency implications before launch.

Firebase privacy note:

- Firebase can process/store data in Google-managed locations depending on service. Avoid placing sensitive authoritative lifestyle data in Firebase unless reviewed.

## Performance Targets

Grid:

- Minimal card DTOs.
- First grid render fast from cached/last known state.
- PostGIS + Redis for candidate generation.
- CDN thumbnails.

Swipe:

- Deck sessions of 10-20 cards.
- Render 2-3 cards client-side.
- Optimistic action queue.

Messages:

- WebSocket realtime.
- Paginated history.
- Media lazy-loaded.

Activity:

- Event query indexed by recipient + createdAt.
- Aggregated counts cached.

## Deployment Shape

Early stage:

- One Ktor API service
- One Ktor worker service
- PostgreSQL + PostGIS
- Redis
- Object storage
- Firebase integrations

Later:

- Split discovery/ranking service
- Split chat service
- Split moderation worker
- Split billing worker
- Add event bus
- Add search/admin service

Do not over-split before product fit.

## Kotlin Implementation Notes

Recommended libraries/patterns:

- Ktor server with Netty/CIO
- Ktor client in KMP app
- `kotlinx.serialization`
- Coroutines
- `StateFlow`/`SharedFlow` on client
- Exposed, jOOQ, or SQLDelight-style explicit SQL for DB
- Flyway/Liquibase migrations
- HikariCP for JDBC pooling
- OpenTelemetry for tracing
- Structured logging

For geospatial queries, prefer explicit SQL over hiding PostGIS behind a weak abstraction.

## Observability

Track server metrics:

- Grid query latency
- Swipe deck latency
- Message send latency
- WebSocket connection count
- FCM success/failure
- Purchase validation latency
- Moderation queue size
- Rate-limit hits
- Error rates by endpoint

Track product metrics:

- Grid opens
- Profile views
- Grid messages sent
- Swipe likes/passes
- Match rate
- Activity opens
- Album requests/grants
- Partner links created/unlinked
- Subscription conversion
- Boost purchases/results

Keep sensitive payloads out of logs.

## Implementation Checklist

- [ ] Ktor is authoritative for product rules.
- [ ] Firebase role is limited and explicit.
- [ ] PostgreSQL/PostGIS selected for location.
- [ ] Redis selected for presence/cache/rate limits.
- [ ] Object storage selected for media.
- [ ] FCM notification pipeline goes through Ktor.
- [ ] Analytics avoids sensitive lifestyle payloads.
- [ ] Subscription validation is server-side.
- [ ] Linked partner entitlement is server-side.
- [ ] Private albums use signed URLs.
- [ ] WebSocket reconnect refetches canonical state.
- [ ] Rate limits exist for messaging/flirts/album requests.
- [ ] Blocks are enforced across grid/swipe/activity/messages.
- [ ] Location is not trusted from client for ranking without server validation.
- [ ] Sensitive logs are redacted.

## Do Not Implement

- Do not make Firestore the primary database for discovery.
- Do not put exact location streams into Firebase Analytics.
- Do not trust client-side premium flags.
- Do not store private album media as public CDN assets.
- Do not let Firebase security rules be the only authorization layer.
- Do not let Remote Config change server-enforced limits without validation.
- Do not expose sensitive lifestyle terms in push notifications by default.
- Do not use offset pagination for live grid.
- Do not make background GPS a default dependency.
- Do not over-split services before the MVP needs it.
