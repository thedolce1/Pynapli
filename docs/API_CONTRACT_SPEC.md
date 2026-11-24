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

### Auth sessions (`POST /v1/auth/refresh`)

- Access and refresh tokens are opaque and server-owned. A refresh rotates both: the presented
  refresh token is exchanged for new tokens **exactly once** (compare-and-swap), even when several
  requests present it at the same moment.
- The exchange is **idempotent for 60 seconds** (`AUTH_REFRESH_REPLAY_GRACE`). Presenting the token
  a rotation just consumed again — the first response was lost, or another tab won the race — returns
  the **same** access and refresh tokens that rotation issued. Nothing new is minted and the session
  is not extended. Only the single most recent previous token of a live session qualifies; a token two
  rotations old, a revoked or expired session, or a retry after the grace answers
  `INVALID_REFRESH_TOKEN`, and an account that is no longer active answers `ACCOUNT_NOT_ACTIVE`, as
  a normal refresh does.
- The server stores only token hashes, so it keeps the issued tokens sealed (AES-256-GCM) under a key
  derived from the consumed refresh token, which it never stores. Revocation clears the seal.
- Clients keep the stored session on a transient refresh failure (network, 5xx) and retry with the
  token they hold; they clear it only on `INVALID_REFRESH_TOKEN`, `ACCOUNT_NOT_ACTIVE` or a 401 that
  the refresh path has already judged terminal.

### Email sign-up (`POST /v1/auth/email/signup`, `/verify`, `/resend`)

Membership-private and verified ([ONBOARDING_AUTH_SPEC.md](ONBOARDING_AUTH_SPEC.md#email-sign-up-membership-privacy)).

- `POST /v1/auth/email/signup` (`EmailSignupRequestDto`, unchanged) answers **`202
  EmailSignupStartedDto`** `{verificationRequired: true, signupToken, codeLength: 6,
  codeExpiresInSeconds: 1800}` for every syntactically valid request, whether or not the address has
  an account. **No session.** Request errors are unchanged (`INVALID_EMAIL`, now also for anything but
  one strict address, `PASSWORD_TOO_SHORT` (under 10 characters), `PASSWORD_TOO_COMMON` (on the
  shared common-password list, `PasswordRules` in `:shared`, checked case-insensitively; also on
  password reset and change, never on sign-in), the age-gate and acceptance codes, `RATE_LIMITED`);
  `EMAIL_ALREADY_EXISTS` is never returned here any more. `503 SIGNUP_UNAVAILABLE` when no email provider is configured (for everyone).
- `POST /v1/auth/email/signup/verify` (`EmailSignupVerifyRequestDto` `{email, code, signupToken,
  deviceId?, platform?, appVersion?}`) answers `200 AuthSessionDto` (the session sign-up used to
  return) or `400 INVALID_SIGNUP_CODE` for every failure.
- "Hide from people you know" (authenticated; [TRUST_AND_SAFETY_SPEC](TRUST_AND_SAFETY_SPEC.md#contact-blocks-hide-from-people-you-know)):
  `GET /v1/me/contact-blocks` → `200 ContactBlockSummaryDto {count, maxAddresses}`;
  `POST /v1/me/contact-blocks` and `POST /v1/me/contact-blocks/remove` (`ContactBlockAddressRequestDto
  {email}`) → `204` whether or not the address belongs to a member, was already added, or (remove) was
  ever added; `DELETE /v1/me/contact-blocks` (remove all) → `204`. Errors are about the request only:
  `400 INVALID_EMAIL`, `409 CONTACT_BLOCK_LIMIT_REACHED` (1,000), `429 RATE_LIMITED` (100 adds a day,
  `BLOCK_OR_HIDE` per request). No list endpoint exists.
- `POST /v1/auth/email/signup/resend` (`EmailSignupResendRequestDto` `{email, signupToken,
  deviceId?}`) always answers `202 EmailSignupCodeResentDto {sent: true}`.
- Old clients: there has been no store release, so no installed production client exists. A debug
  build from before this change decodes the 202 body as `AuthSessionDto`, fails, and shows its generic
  network error; no account is created and it can't proceed. The body deliberately has no
  `accessToken`, so the shared client can't mistake it for a session.
- Local development only, off by default, and only for a direct loopback request with no forwarding
  header (404 otherwise): `POST /v1/dev/auth/email/signup` (verified account at once,
  `AuthSessionDto`, 409 `EMAIL_ALREADY_EXISTS` for an existing address) and `POST
  /v1/dev/auth/email/signup/latest-code` (`{email}` -> `DevSignupCodeDto {code?}`; codes only).

### Grid (`POST /v1/grid/query`)

- Cards stay minimal (id, name, thumbnail, online, distance label, linked-partner mini, `verified`,
  `coupleVerified`). `coupleVerified` is server-computed: true only while the person and the partner in
  the mini are both photo-verified, so it is false whenever the mini is hidden. Profile detail
  (`PublicProfileDto`) carries the same `coupleVerified`.
- `verifiedCouplesOnly` (Grid query and Swipe deck request, default false) returns only people whose
  card would carry `coupleVerified` for this viewer. It does not set or change `linkedOnly`.
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
- `GET /v1/matches` was removed. Matches reach users through conversations.

### Activity

- Tabs: `all`, `views`, `flirts` (taps, swipe likes, Pineapple Likes, notes), `matches`, `requests`
  (album access, partner/Psync/Tonight invites). The mobile app no longer uses the `matches` tab.
- Cursors are signed and viewer/tab-scoped and carry seen-count state, so reveal-depth gates apply
  consistently across pages.
- The summary applies the same visibility/block/risk filters as the feed. `badgeCount` excludes
  profile views (`profileViewCount` is separate) and matches (no Activity tab shows them, so they
  could never be marked seen; `matchCount` is kept for installed clients and is always `0`).
  `latestBadgeOccurredAt` follows the same set. If the recipient disables Viewed lists, view rows
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
- `DiscoverySettingsDto.gridMessagePolicy` is one of `everyone`, `verified_only` (sender
  photo-verified), `matches_only`, `no_one`; `PATCH /v1/me/discovery-settings` rejects anything else
  with `INVALID_GRID_MESSAGE_POLICY`. A refused Grid open is `403 MESSAGING_NOT_ALLOWED` for every
  option. `PublicProfileDto.allowGridMessages` is computed for the viewer under the same rule.
- Risk policy can hold outbound first messages for review.
- Partner invite requires an active link plus partner **and** third-party consent.
  `ConversationDto.partnerInvite` is present while unresolved, with each side's consent status.
- Several invites can be open in one conversation (added 2026-11-19). `ConversationDto.partnerInvites`
  (list, empty from older servers) carries every open invite the viewer is party to; `partnerInvite`
  stays for older clients as the one the viewer answers first. `POST .../partner-invite/accept` and
  `.../decline` take an optional `?inviteId=` naming the invite a third party answers. Without it the
  server acts on the caller's own open invite, treats a repeat after the caller's own invite was
  resolved as an idempotent accept (a decline is refused), and otherwise acts only when exactly one
  open invite awaits the caller's approval. An invited person naming another invite, an id from another
  conversation, or an ambiguous id-less call gets `PARTNER_INVITE_NOT_FOUND`. An invite that would make
  someone active next to a person they are blocked with gets `MESSAGING_NOT_ALLOWED`. An invited
  (not yet active) viewer's `participants` hold only the active participants and themselves, with
  `invitedByUserId` only on their own row; their `partnerInvites` hold only their own invite. An
  invitee's `partnerStatus` goes back to `pending` when someone else joins or an active person leaves.
  Re-inviting within 24 hours of a decline by either side is `PARTNER_INVITE_COOLDOWN`;
  `ConversationDto.ownPartnerInviteCoolingDown` (read mark only, null elsewhere and from older servers)
  says so for the viewer's own partner so clients hide the manual Invite button.
- `ConversationDto.partnerOffers` (added 2026-11-18, optional; sent on
  `POST /v1/messages/threads/{id}/read` only, which every client open and refresh ends with; null
  elsewhere and from older servers) is the per-viewer "Bring your partner" offer on a matched
  conversation: `bringPartner { partnerUserId, partnerDisplayName }` is the viewer's own linked partner,
  acted on with the existing `POST .../partner-invite` (same accepts, nothing new); `participantPartners
  [{ participantUserId, partnerDisplayName }]` names another participant's linked partner the viewer is
  already allowed to see, display only. Another open invite does not hide it. Absent once that partner
  has had any invite to the thread, on any block among the would-be participants, and in the later of
  a Psync together match's two conversations. Rules: [MESSAGING_SPEC.md](MESSAGING_SPEC.md) "Partner
  Invite Flow".
- `ConversationDto.newMatch` (Boolean, added 2026-11-18) is the server's per-viewer answer to "does
  this conversation belong in my new-matches rail". Clients render it and never infer it; `null` only
  comes from an older server, where clients fall back to `origin == "swipe" && lastMessage == null`.
  `POST /v1/messages/threads/{id}/read` (opening the thread) clears a match on a thread with history
  for the opener; any message that becomes visible in the thread clears it for both, for good. Rules:
  [MESSAGING_SPEC.md](MESSAGING_SPEC.md) "New-matches rail".
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
was removed.

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
