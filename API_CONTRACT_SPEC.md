# Pynapli API Contract Spec

Last updated: 2026-05-06

## Purpose

This document defines the shape of Pynapli's Ktor API: endpoint groups, DTO conventions, auth rules, pagination, realtime events, and error handling.

Core rule:

> Ktor is the authority. Clients request actions; Ktor validates, applies policy, persists state, and emits events.

## API Principles

1. All product-critical state is server-authoritative.
2. All authenticated endpoints require a valid user session.
3. Privacy, block, subscription, and risk policies are enforced server-side.
4. Use cursor pagination for live feeds.
5. Use idempotency keys for purchase/action endpoints.
6. Do not expose raw internal risk scores, exact location, or moderation internals to normal clients.
7. DTOs should be minimal by surface.

## Transport

HTTP:

- JSON over HTTPS.
- `kotlinx.serialization`.
- Versioned under `/v1`.

Realtime:

- Ktor WebSocket under `/v1/realtime`.
- Reconnect requires canonical refetch for important state.

## Common Headers

Requests:

```text
Authorization: Bearer <access_token>
X-Request-Id: <uuid>
Idempotency-Key: <uuid> // for mutation endpoints where needed
X-App-Version: <version>
X-Platform: android|ios|web
X-App-Check: <token> // optional where enabled
```

Responses:

```text
X-Request-Id: <uuid>
X-RateLimit-Limit: <n>
X-RateLimit-Remaining: <n>
X-RateLimit-Reset: <unix_time>
```

## Error Shape

```kotlin
@Serializable
data class ApiErrorDto(
    val code: String,
    val message: String,
    val requestId: String,
    val details: Map<String, String> = emptyMap()
)
```

Rules:

- Messages should be user-safe.
- Details should not expose policy thresholds, risk scores, or sensitive internals.

Common codes:

- `UNAUTHENTICATED`
- `FORBIDDEN`
- `NOT_FOUND`
- `VALIDATION_ERROR`
- `RATE_LIMITED`
- `ENTITLEMENT_REQUIRED`
- `VERIFICATION_REQUIRED`
- `ACTION_LIMITED`
- `CONFLICT`
- `STALE_STATE`

## Pagination

Use cursor pagination.

```kotlin
@Serializable
data class PageDto<T>(
    val items: List<T>,
    val nextCursor: String?
)
```

Do not use offset pagination for:

- Grid
- Activity
- Messages
- Swipe decks

## Auth API

```http
POST /v1/auth/email/signup
POST /v1/auth/email/login
POST /v1/auth/social/apple
POST /v1/auth/social/google
POST /v1/auth/refresh
POST /v1/auth/logout
GET  /v1/auth/sessions
DELETE /v1/auth/sessions/{sessionId}
```

Rules:

- Tokens stored securely client-side.
- Ktor verifies Apple/Google/Firebase tokens where used.
- Refresh tokens rotate.

## Me / Profile API

```http
GET   /v1/me
PATCH /v1/me/profile
GET   /v1/profiles/{userId}
GET   /v1/profiles/{userId}/card
POST  /v1/profile-photos
PATCH /v1/profile-photos/{photoId}
DELETE /v1/profile-photos/{photoId}
```

DTO separation:

- Grid card DTO: tiny.
- Swipe card DTO: richer.
- Full profile DTO: lazy-loaded on tap.

## Settings API

```http
GET   /v1/settings
PATCH /v1/settings/privacy
PATCH /v1/settings/discovery
PATCH /v1/settings/messages
PATCH /v1/settings/activity
PATCH /v1/settings/notifications
PATCH /v1/settings/app
```

Rules:

- Server-authoritative settings include privacy, discovery, messaging, Activity, notification detail, partner visibility.
- Client-local settings include theme, local app lock, and cache preferences.

## Location API

```http
POST /v1/location/current
POST /v1/location/travel
DELETE /v1/location/travel
```

Rules:

- Client sends location updates only after thresholds.
- Ktor rounds/fuzzes where needed.
- Ktor never trusts client distance display as authority.

## Grid API

```http
POST /v1/grid/query
POST /v1/grid/hide/{userId}
DELETE /v1/grid/hide/{userId}
```

Request:

```kotlin
@Serializable
data class GridQueryRequest(
    val filters: GridFiltersDto,
    val cursor: String? = null,
    val locationContext: LocationContextDto
)
```

Response:

```kotlin
@Serializable
data class GridCardDto(
    val userId: String,
    val displayName: String,
    val thumbnailUrl: String?,
    val online: Boolean,
    val distanceLabel: String?,
    val linkedPartner: LinkedPartnerMiniDto?
)
```

Rules:

- Open messaging is allowed from Grid if recipient settings and risk policy allow.
- Grid cards stay minimal.
- Apply blocks/privacy/risk before returning cards.

## Swipe API

```http
POST /v1/swipe/deck
POST /v1/swipe/action
POST /v1/swipe/rewind
GET  /v1/swipe/likes-you
GET  /v1/swipe/picks
```

Action:

```kotlin
@Serializable
data class SwipeActionRequest(
    val deckSessionId: String,
    val cardToken: String,
    val targetUserId: String,
    val action: SwipeActionTypeDto,
    val intentScope: IntentScopeDto? = null,
    val noteText: String? = null
)
```

Rules:

- Match creation is server-authoritative.
- Intro Notes require entitlement/inventory and recipient eligibility.
- Dealbreakers are hard.
- Soft filters may expand only when allowed.

## Psync API

```http
POST /v1/psync/sessions
POST /v1/psync/sessions/{id}/accept
POST /v1/psync/sessions/{id}/deny
POST /v1/psync/sessions/{id}/suggest-filters
POST /v1/psync/sessions/{id}/start
POST /v1/psync/sessions/{id}/pause
POST /v1/psync/sessions/{id}/leave
POST /v1/psync/sessions/{id}/actions
GET  /v1/psync/sessions/{id}
GET  /v1/psync/sessions/{id}/recap
```

Rules:

- Active partner link required.
- Both partners must approve before start.
- Do not expose live partner swipes.
- Together match requires target consent.

## Playing Tonight API

```http
POST /v1/playing-tonight/sessions
POST /v1/playing-tonight/sessions/{id}/approve
POST /v1/playing-tonight/sessions/{id}/decline
PATCH /v1/playing-tonight/sessions/{id}
POST /v1/playing-tonight/sessions/{id}/end
GET  /v1/playing-tonight/feed
GET  /v1/playing-tonight/zones
```

Rules:

- Solo and linked-together modes are distinct.
- Linked-together sessions require partner approval.
- Zone map has no exact pins.
- Public session content must be moderated.

## Activity API

```http
GET  /v1/activity
POST /v1/activity/{eventId}/dismiss
```

Query params:

- `tab`
- `cursor`

Rules:

- Ktor applies tier gating.
- Ktor applies viewed-list opt-out/incognito/block rules.
- Activity is not Messages.

## Messages API

```http
GET  /v1/messages/threads
POST /v1/messages/threads
GET  /v1/messages/threads/{threadId}
POST /v1/messages/threads/{threadId}/messages
POST /v1/messages/{messageId}/delete
POST /v1/messages/threads/{threadId}/invite-partner
POST /v1/messages/partner-invites/{inviteId}/accept
POST /v1/messages/partner-invites/{inviteId}/deny
POST /v1/messages/threads/{threadId}/leave
```

Rules:

- Swipe chat requires match unless Intro Note accepted flow.
- Partner invite requires linked partner and third-party consent.
- Risk policy can hold outbound first messages for review.

## Albums API

```http
GET  /v1/albums/private
POST /v1/albums/private/media
DELETE /v1/albums/private/media/{mediaId}
POST /v1/albums/private/request-access
POST /v1/albums/private/grants
DELETE /v1/albums/private/grants/{grantId}
GET  /v1/albums/private/access-list
```

Rules:

- Signed URLs are short-lived.
- Linked partner access is not automatic.
- Revoke access is free and immediate.

## Partner Links API

```http
POST /v1/partner-links/request
POST /v1/partner-links/{id}/accept
POST /v1/partner-links/{id}/deny
POST /v1/partner-links/{id}/unlink
PATCH /v1/partner-links/{id}/visibility
GET  /v1/partner-links/current
```

Rules:

- Either user can unlink.
- Link does not grant account/media/chat control.
- Subscription benefit computed separately.

## Subscriptions API

```http
GET  /v1/subscriptions/entitlement
POST /v1/subscriptions/app-store/validate
POST /v1/subscriptions/play-store/validate
POST /v1/subscriptions/stripe/validate
POST /v1/subscriptions/restore
GET  /v1/subscriptions/inventory
POST /v1/boosts
```

Rules:

- Never trust client premium flags.
- Linked partner benefit is server-computed.
- Use idempotency keys.

## Reports / Safety API

```http
POST /v1/reports
POST /v1/blocks/{userId}
DELETE /v1/blocks/{userId}
GET  /v1/blocks
GET  /v1/safety/community-guidelines
```

Rules:

- Block/report are never premium.
- Blocks apply across Grid, Swipe, Activity, Messages.

## Verification API

```http
GET  /v1/verification/requirements
POST /v1/verification/start
POST /v1/verification/callback
GET  /v1/verification/status
```

Rules:

- Market requirements are server-controlled.
- Do not expose raw provider data to clients.

## Notifications API

```http
GET   /v1/notifications/bell
PATCH /v1/notifications/preferences
POST  /v1/notifications/register-token
POST  /v1/notifications/revoke-token
```

Rules:

- Bell is for action-required/account items.
- Activity and Messages remain separate.

## Realtime WebSocket

Endpoint:

```http
GET /v1/realtime
```

Event envelope:

```kotlin
@Serializable
data class RealtimeEventDto(
    val id: String,
    val type: String,
    val occurredAt: Instant,
    val payload: JsonObject
)
```

Event types:

- `presence.delta`
- `message.created`
- `message.receipt`
- `activity.created`
- `match.created`
- `partner_link.updated`
- `partner_chat_invite.created`
- `psync.updated`
- `playing_tonight.updated`
- `album_grant.updated`
- `entitlement.updated`

Rules:

- WebSocket is not sole authority.
- Client refetches canonical state after reconnect.
- Do not stream full grid reorders.
- Do not stream partner Psync swipes.

## Admin API

Separate admin auth and network controls.

Groups:

```http
/v1/admin/users
/v1/admin/reports
/v1/admin/moderation-cases
/v1/admin/risk
/v1/admin/appeals
/v1/admin/audit
```

Rules:

- Admin actions audited.
- Not exposed to mobile clients.

## Implementation Checklist

- [ ] `/v1` versioning.
- [ ] Shared DTO conventions.
- [ ] Cursor pagination.
- [ ] Idempotency keys for actions/purchases.
- [x] Server-side entitlement checks.
- [ ] Server-side block/privacy/risk checks.
- [ ] WebSocket reconnect canonical refetch.
- [ ] Error codes standardized.
- [ ] Admin API separated.

## Do Not Implement

- Do not expose internal risk score.
- Do not expose exact raw location.
- Do not let Firebase decide product authorization.
- Do not use offset pagination for live surfaces.
- Do not trust client premium/subscription state.
- Do not make partner-link APIs mutate another user's account settings.
