# Pynapli API Contract Spec

Conventions and behavioral contracts for the Ktor API. **The route list is the code**
(`server/src/main/kotlin/com/pynapli/server/**/*Routes.kt`, client side `shared/network/*Api.kt`), and
every route file's auth class is in [SECURITY_ROUTE_MATRIX.md](SECURITY_ROUTE_MATRIX.md). This file
keeps the rules a route list can't show.

> Ktor is the authority. Clients request actions; Ktor validates, applies policy, persists state and
> emits events.

## Principles

1. All product-critical state is server-authoritative.
2. Every authenticated endpoint requires a valid session (opaque bearer token).
3. Privacy, block, subscription and risk policy are enforced server-side.
4. Cursor pagination for live feeds; never offset pagination for Grid, Activity, Messages or Swipe
   decks.
5. Idempotency keys for purchase and action endpoints.
6. **Never expose internal risk scores, exact location, or moderation internals** (admin reasons,
   audit metadata, moderator notes) to normal clients.
7. DTOs are minimal per surface. Shared DTO nullables default to `null` (guarded by
   `SharedDtoNullDefaultsTest`).

## Transport and headers

- JSON over HTTPS, `kotlinx.serialization`, versioned under `/v1`.
- Requests: `Authorization: Bearer <access_token>`, `X-Firebase-AppCheck: <token>` when App Check is
  available. Responses carry `X-Request-Id`.
- Idempotency keys travel in the body (`actionId` for swipe actions, `clientIdempotencyKey` for
  messages and similar mutations).
- Rate-limited requests answer `429 RATE_LIMITED` with `Retry-After`.

## Errors

`ApiErrorDto(code, message, requestId, details)` (`shared/src/commonMain/kotlin/com/pynapli/shared/network/dto/ApiErrorDto.kt`). Messages are
user-safe; `details` never exposes policy thresholds, risk scores or internals. Common codes:
`UNAUTHENTICATED`, `FORBIDDEN`, `NOT_FOUND`, `VALIDATION_ERROR`, `RATE_LIMITED`,
`ENTITLEMENT_REQUIRED`, `VERIFICATION_REQUIRED`, `ACTION_LIMITED`, `CONFLICT`, `STALE_STATE`.

## Surface contracts

### Grid (`POST /v1/grid/query`)

- Cards stay minimal (id, name, thumbnail, online, distance label, linked-partner mini, `verified`,
  `coupleVerified`). `coupleVerified` is server-computed: true only while the person and the partner in
  the mini are both photo-verified, so it is false whenever the mini is hidden. Profile detail
  (`PublicProfileDto`) carries the same `coupleVerified`.
- Blocks, privacy and risk apply before cards are returned.
- Tier volume caps (page size and depth) are enforced server-side. **Cursor tokens carry scope and
  seen-count state**, so a client can't bypass tier depth by changing the requested limit. Grid
  cursors are sealed (AES-GCM, opaque to the client) and pages are at least 12 cards.
- `radiusMeters` is snapped up to 2, 5, 10, 25, 50 or 100 km; distances, order and radius
  membership are computed between coarse location cells (see GRID_IMPLEMENTATION_SPEC "Location
  privacy"). `distanceMeters`/`distanceLabel` are null when the person hides distance.
  `resultLimitReached` is true when a capped tier hit its depth and more candidates exist behind the
  paid gate.
- Open messaging from the Grid is allowed when the recipient's settings and risk policy allow.
- The `adSupported` flag is being removed with the fake ad slot.

### Swipe

- Match creation is server-authoritative. Dealbreakers are hard; soft filters expand only when
  allowed.
- `actionId` is a client-generated idempotency key. A reused `actionId` with a different
  target/action/note is rejected. **Idempotent retries never spend Pineapple Like inventory twice.**
- `noteText` is only valid on `pineapple_like` (trimmed, 1-140 characters, else
  `PINEAPPLE_LIKE_NOTE_TOO_LONG`); the `intro_note` action is retired (`INVALID_SWIPE_ACTION`). When
  the recipient turned notes off the request fails `PINEAPPLE_LIKE_NOTE_NOT_ACCEPTED` (409) with
  nothing spent; the same Pineapple Like without the note succeeds. `ActivityEventDto.noteText`
  carries the note on the recipient's `pineapple_like` item. The Play product
  `pynapli_intro_notes_3` is no longer in the catalog; `POST /v1/store/play-store/purchases/one-time`
  still accepts it and credits 3 Pineapple Likes.
- Rewind is entitlement-gated, soft-rewinds the latest eligible Like/Pass, and returns a fresh deck
  session and card token for the restored card; `rewound=false` when nothing can be safely restored.
- `GET /v1/matches` is being removed (no client caller); matches reach users through conversations.
  `GET /v1/swipe/picks` (Top Picks) is frozen.

### Activity

- Tabs: `all`, `views`, `flirts` (taps, swipe likes, Pineapple Likes, notes), `matches`, `requests`
  (album access, partner/Psync/Tonight invites). The `matches` tab is being merged into the Messages
  new-matches rail.
- Cursors are signed and viewer/tab-scoped and carry seen-count state, so reveal-depth gates apply
  consistently across pages.
- The summary applies the same visibility/block/risk filters as the feed. `badgeCount` excludes
  profile views (`profileViewCount` is separate). If the recipient disables Viewed lists, view rows
  disappear from their feed and count.
- Album-request respond and dismiss only act on the recipient's own rows.
- Match and partner-invite CTAs use server-provided ids (`matchId`, `conversationId`); **clients never
  invent a conversation from an Activity row.**
- `/v1/activity/stream` sends `ActivityDeltaDto` invalidation hints for the authenticated recipient
  only. Clients refetch after a delta and **don't mark background-refreshed rows seen**.
- Activity is not Messages.

### Messages

- Swipe-originated chat requires a match (Pineapple Like note is the exception). Grid chat follows
  recipient settings and risk policy.
- Risk policy can hold outbound first messages for review.
- Partner invite requires an active link plus partner **and** third-party consent.
  `ConversationDto.partnerInvite` is present while unresolved, with each side's consent status.
- Typing (`{ isTyping }`) is Select-only and silently no-ops without entitlement.
- **Read receipts are server-masked:** a Social/Select sender sees `read` only when every active
  recipient is Social/Select eligible; Free viewers get `sent`.
- `/v1/messages/stream` emits recipient-scoped deltas. Create/delete/read/conversation-update deltas
  trigger an HTTP refetch; typing deltas are short-lived hints with expiry.
- Cursor, status/deliveryStatus and composer rules: [MESSAGING_SPEC.md](MESSAGING_SPEC.md).

### Media and private albums

Signed URLs are short-lived. Linked-partner access is never automatic. Revoking access is free and
immediate.

### Partner links

Either user can unlink. A link never grants control of the other account, its media or its chats,
and partner-link APIs never mutate another user's settings. The subscription benefit is computed
separately.

### Subscriptions

- Never trust client premium flags; the linked-partner benefit is server-computed.
- `GET /v1/subscriptions/inventory` returns the tier catalog, product catalog and the user's balances.
- `POST /v1/subscriptions/dev/purchases` is local/dev only and must return `403` in production-like
  environments.
- App Store validation and restore are not built (iOS).

### Safety, reports, appeals

- Block, report, hide, appeals and support contact are **never premium**.
- Blocks apply across Grid, Swipe, Activity and Messages. Hiding removes a profile from the viewer's
  discovery without treating it as blocked.
- A `message` report needs `targetId` = a message the reported person sent into a conversation the
  reporter belongs to (left or not); anything else answers `REPORT_TARGET_NOT_FOUND`. The text is
  copied into the report (`evidence_text`) so moderators see it even after deletion.
- Report history is reporter-scoped and user-safe (status, category, target type, display name,
  timestamps); never moderator notes, risk state, admin reasons or private reported-user data.
- Appeal history is owner-scoped (status, user reason, optional admin note, target, timestamps). One
  open appeal per target.
- A suspended or banned account appeals through `POST /v1/auth/account-appeal` with email and password
  (rate-limited like login). **The password is checked before account status**, there and at sign-in,
  so a wrong password never reveals that an account exists or is suspended. Sign-in answers
  `ACCOUNT_NOT_ACTIVE` for such accounts; an active account gets `ACCOUNT_NOT_RESTRICTED` (409).

### Age verification

- Market requirements are server-controlled. Raw provider data never reaches clients; user endpoints
  return status, method, provider, timestamps and user-safe messages.
- `POST /v1/verification/start` requires `confirmedAdult=true`, an adult DOB on the profile and a
  supported method.
- The provider callback and admin review are not client surfaces; they need provider/admin
  authorization and a review reason.
- `GET /v1/verification/requirements` has no client caller and is frozen.

### Photo verification

- `GET /v1/verification/photo` returns `PhotoVerificationStatusDto` (`none`, `challenge` with the pose,
  `pending`, `verified`, `rejected` with `rejectionReason` and `retryAvailableAt`).
- `POST /v1/verification/photo/challenge` issues a random server pose valid 10 minutes (10/hour;
  `PHOTO_VERIFICATION_PENDING` / `_ALREADY_VERIFIED` 409, `_COOLDOWN` 429).
- `POST /v1/verification/photo/uploads` returns a signed upload slot for the selfie; the client PUTs the
  bytes there, then `POST /v1/verification/photo/submit` with `challengeId` + `mediaAssetId` (5/day).
  A failed submit is not retried blindly: refetch the status (a lost response leaves it pending).
- The selfie URL is never returned to the client after upload; only admin reviewers get a signed URL.

### Realtime

Streams: `/v1/presence/stream`, `/v1/activity/stream`, `/v1/messages/stream`, `/v1/psync/stream`.
WebSockets are never the sole authority; clients refetch canonical state on reconnect. Don't stream
full grid reorders or a partner's individual Psync swipes. Auth rules:
[BACKEND_ARCHITECTURE_SPEC.md](BACKEND_ARCHITECTURE_SPEC.md#realtime). `POST /v1/presence/heartbeat`
is being removed (no client caller).

### Admin

All admin routes live under `/v1/admin`, require admin auth (roles, MFA) and are audited. They are
never exposed to app clients. Only the moderation queues (reports, review queue, appeals, risk, age
review, photo verification, pending profile photos) are active; the other admin endpoints (dashboard, user lookup, subscription support,
partner-link review, metrics) are frozen. See
[ADMIN_MODERATION_CONSOLE_SPEC.md](ADMIN_MODERATION_CONSOLE_SPEC.md).

## Open items

- [ ] Idempotency is consistent for swipe actions, messages and notifications, but not yet for every
  purchase and mutation.
- [ ] Full API error catalog.

## Do not

- Expose internal risk scores or exact raw location.
- Let Firebase decide product authorization.
- Use offset pagination for live surfaces.
- Trust client premium or subscription state.
- Let partner-link APIs mutate another user's account settings.
