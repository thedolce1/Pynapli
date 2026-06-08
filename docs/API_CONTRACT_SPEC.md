# Pynapli API Contract Spec

Last updated: 2026-05-19

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
- Email signup requires `birthdate`, `confirmedAdult=true`, `acceptedTerms=true`, and `acceptedPrivacy=true`.
- Ktor validates adult age from DOB, then records acceptance timestamps and server-controlled terms/privacy versions.

## Account Lifecycle API

```http
GET  /v1/account/data-export
POST /v1/account/data-export
POST /v1/account/delete
```

Rules:

- Account lifecycle routes require bearer auth.
- `GET /v1/account/data-export` returns the latest export request or `204 No Content`.
- `POST /v1/account/data-export` records a server-side export request and returns its status.
- Production archive generation and signed download delivery happen asynchronously after the request.
- `POST /v1/account/delete` requires `confirmation = "DELETE"`.
- Account deletion is server-authoritative: set user status to `deleted`, set `deleted_at`, hide the profile, disable Grid/Swipe/events/viewed-list discovery, clear active travel visibility, revoke sessions, and record a deletion event.
- Safety, moderation, and legal-retention records are preserved where required instead of hard-deleted.

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
data class GridQueryResponseDto(
    val items: List<GridCardDto>,
    val nextCursor: String? = null,
    val adSupported: Boolean = false,
    val advancedFiltersApplied: Boolean = false,
    val resultLimitReached: Boolean = false,
    val resultLimitTier: String? = null
)

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
- Ktor enforces tier volume caps. Free is page 60 / depth 60, Social is page 90 / depth 600, and Select is page 120 / uncapped depth.
- Cursor tokens include scope and seen-count state so clients cannot bypass tier depth by changing requested limits.
- `resultLimitReached` is true when a capped tier reached its available Grid depth and more eligible candidates exist behind the paid gate.

## Swipe API

```http
POST /v1/swipe/deck/query
POST /v1/swipe/actions
POST /v1/swipe/rewind
GET  /v1/swipe/likes-you
GET  /v1/swipe/picks
```

Deck query:

```kotlin
@Serializable
data class SwipeDeckRequest(
    val radiusMeters: Int = 25_000,
    val limit: Int = 30,
    val cursor: String? = null
)
```

Action:

```kotlin
@Serializable
data class SwipeActionRequest(
    val actionId: String,
    val deckSessionId: String,
    val cardToken: String,
    val targetUserId: String,
    val action: SwipeActionTypeDto,
    val intentScope: IntentScopeDto? = null,
    val noteText: String? = null
)
```

Rewind:

```kotlin
@Serializable
data class SwipeRewindResponse(
    val rewound: Boolean,
    val deckSessionId: String = "",
    val card: SwipeCardDto? = null
)
```

Rules:

- Match creation is server-authoritative.
- `actionId` is a client-generated idempotency key for retrying the same swipe action safely.
- The server rejects a reused `actionId` when target/action/note payload differs from the original action.
- Idempotent retries must not spend Intro Note or Pineapple Like inventory again.
- Intro Notes require entitlement/inventory and recipient eligibility.
- Rewind is entitlement-gated, soft-rewinds the latest eligible active Like/Pass, and returns a fresh deck session/card token for the restored card when successful.
- Rewind returns `rewound=false` when there is no safe eligible action to restore.
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
GET  /v1/activity/summary
WS   /v1/activity/stream
POST /v1/activity/album-requests/{eventId}/respond
POST /v1/activity/{eventId}/dismiss
```

Query params:

- `limit`
- `tab`: `all`, `views`, `flirts` (inbound interest: taps + swipe-likes + pineapple likes + intro notes), `matches`, or `requests` (album-access + partner/psync/tonight invites)
- `cursor`: signed, viewer/tab-scoped cursor token

Realtime:

- `/v1/activity/stream` requires the same bearer token as HTTP routes.
- Stream frames are `ActivityDeltaDto` invalidation hints for the authenticated recipient only: `type`, `tab`, `occurredAt`, and optional `eventId`.
- Clients refresh summary/feed after deltas and must not mark background-refreshed rows seen.

Feed item fields used for row CTAs:

- `albumRequestStatus`: drives pending/approved/denied album request review state.
- `matchId`: present on match rows so the client can open the match conversation path.
- `conversationId`: present on partner-chat invite rows so the client can open the invite conversation context.

Summary response fields:

- `badgeCount`: meaningful Activity badge count; excludes profile views.
- `profileViewCount`: separate Viewed-list count for in-screen use.
- `flirtCount`, `likeCount`, `matchCount`, `albumRequestCount`, `partnerInviteCount`.
- `latestBadgeOccurredAt`: latest visible badge-worthy Activity timestamp.

Rules:

- Ktor applies tier gating.
- Ktor applies viewed-list opt-out/incognito/block rules.
- Cursors carry seen-count state so reveal-depth gates apply consistently across pages.
- Summary applies the same visibility/block/risk filters as the feed.
- Summary excludes profile views from the bottom-nav badge; views are counted separately.
- If the recipient disables Viewed lists, profile-view rows are hidden from their Activity feed and summary count.
- Album request respond is owner-scoped and only applies to the recipient's own pending request row.
- Match and partner-invite CTAs must use the server-provided identifiers; clients cannot invent a conversation from an Activity row.
- Dismiss only affects an Activity event owned by the authenticated recipient.
- Activity is not Messages.

## Messages API

```http
GET  /v1/messages/threads
POST /v1/messages/threads
GET  /v1/messages/threads/{threadId}
POST /v1/messages/threads/{threadId}/messages
POST /v1/messages/threads/{threadId}/typing
POST /v1/messages/threads/{threadId}/read
POST /v1/messages/{messageId}/delete
POST /v1/messages/threads/{threadId}/partner-invite
POST /v1/messages/threads/{threadId}/partner-invite/accept
POST /v1/messages/threads/{threadId}/partner-invite/decline
POST /v1/messages/threads/{threadId}/leave
GET  /v1/messages/stream
```

Rules:

- Swipe chat requires match unless Intro Note accepted flow.
- Partner invite requires linked partner and third-party consent.
- `ConversationDto.partnerInvite` is present while an invite is unresolved, with partner and third-party consent status so the client can show the correct action/waiting state.
- Risk policy can hold outbound first messages for review.
- `/v1/messages/threads/{threadId}/typing` accepts `{ isTyping: Boolean }`, is Select-only, and silently no-ops for users/recipients without typing entitlement.
- Read receipt status is server-masked. Social/Select senders can see `read` only when all active recipient participants are Social/Select eligible; Free viewers receive `sent`.
- `/v1/messages/stream` emits authenticated recipient-scoped deltas only. Message create/delete/read/conversation-update deltas trigger HTTP refetch; typing deltas are short-lived UI hints with actor metadata and expiry.

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
GET  /v1/subscriptions/inventory
POST /v1/subscriptions/play-store/purchases
POST /v1/subscriptions/dev/purchases // local/dev only
POST /v1/boosts

Planned production parity:
POST /v1/subscriptions/app-store/validate
POST /v1/subscriptions/restore
```

Rules:

- Never trust client premium flags.
- Linked partner benefit is server-computed.
- `GET /v1/subscriptions/inventory` returns tier catalog, premium product catalog, and the authenticated user's current premium inventory balances.
- `POST /v1/subscriptions/dev/purchases` is only enabled in local/dev configuration for emulator QA and must return `403` in production-like environments.
- Use idempotency keys.

## Reports / Safety API

```http
POST /v1/reports
GET  /v1/reports
POST /v1/blocks/{userId}
DELETE /v1/blocks/{userId}
GET  /v1/blocks
POST /v1/hidden-profiles/{userId}
DELETE /v1/hidden-profiles/{userId}
GET  /v1/hidden-profiles
GET  /v1/support/contact
GET  /v1/safety/community-guidelines
```

Rules:

- Block/report are never premium.
- Blocks apply across Grid, Swipe, Activity, Messages.
- Hidden profiles are never premium.
- Hidden profiles remove the target from the viewer's discovery surfaces without treating the target as blocked.
- Report history is reporter-scoped and returns only user-safe status, category, target type, display name, and timestamps.
- Report history must not expose moderator notes, internal risk state, admin reasons, or private reported-user data.
- Support contact is never premium and returns server-configured contact details with a user-safe account reference.

## Verification API

```http
GET  /v1/verification/requirements
POST /v1/verification/start
POST /v1/verification/callback
GET  /v1/verification/status
POST /v1/admin/verification/age/{verificationId}/review
```

Rules:

- Market requirements are server-controlled.
- Do not expose raw provider data to clients.
- Authenticated user endpoints return only status/method/provider/timestamps and user-safe messages.
- `POST /v1/verification/start` requires `confirmedAdult=true`, an adult DOB on the profile, and a supported verification method.
- Callback/admin review endpoints are not mobile-client surfaces; they require admin/provider authorization and a review reason.
- Successful verification sets the age-verification record and the profile age-verification timestamp used by age-gated filters.

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
GET /v1/presence/stream
GET /v1/activity/stream
GET /v1/messages/stream
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
- `message.deleted`
- `message.read`
- `typing.started`
- `typing.stopped`
- `conversation.updated`
- `activity.created`
- `activity.dismissed`
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
/v1/admin/reports/{reportId}/mark-under-review
/v1/admin/reports/{reportId}/resolve
/v1/admin/reports/{reportId}/resolve-remove-media
/v1/admin/reports/{reportId}/dismiss
/v1/admin/moderation-cases
/v1/admin/risk
/v1/admin/appeals
/v1/admin/audit
```

Rules:

- Admin actions audited.
- Not exposed to mobile clients.

## Implementation Checklist

- [x] `/v1` versioning.
- [x] Shared DTO conventions.
- [ ] Partial: signed cursor pagination is implemented for Grid, Swipe decks, and Activity, but other live cursor behavior is incomplete.
- [ ] Partial: idempotency keys exist for notification/outbox, swipe actions, and some workflows, but purchases and all mutations are not consistently idempotent.
- [x] Server-side entitlement checks.
- [x] Server-side block/privacy/risk checks.
- [x] Age-verification requirements/status/start plus admin review/provider callback contract exists.
- [ ] Partial: Grid presence, Activity, and Messages streams reconnect and keep HTTP/state fetch authoritative; remaining realtime event families are future work.
- [ ] Partial: error codes are mapped centrally, but the full API error catalog is not complete.
- [ ] Partial: admin routes are under `/v1/admin`, but production admin separation, roles, and MFA are not complete.

## Do Not Implement

- Do not expose internal risk score.
- Do not expose exact raw location.
- Do not let Firebase decide product authorization.
- Do not use offset pagination for live surfaces.
- Do not trust client premium/subscription state.
- Do not make partner-link APIs mutate another user's account settings.
