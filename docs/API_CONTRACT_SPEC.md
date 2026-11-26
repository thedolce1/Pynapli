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

### Continue with Google / Apple

#### Section 5 contract (2026-11-26; server built, behind a flag)

Rules and privacy analysis: [ONBOARDING_AUTH_SPEC.md](ONBOARDING_AUTH_SPEC.md#social-sign-in-continue-with-google--apple).
DTOs: `shared/.../network/dto/SocialAuthDtos.kt`. Off unless `PYNAPLI_SOCIAL_SIGN_IN_ENABLED=true`.

**Config** (server env): `PYNAPLI_GOOGLE_CLIENT_IDS` (comma list of accepted `aud`: web + Android
client ids); `PYNAPLI_APPLE_CLIENT_IDS` (Services ID + bundle id); `PYNAPLI_APPLE_TEAM_ID`,
`PYNAPLI_APPLE_KEY_ID`, `PYNAPLI_APPLE_PRIVATE_KEY` (the .p8 PEM text, secret manager only);
`PYNAPLI_APPLE_REDIRECT_URIS` (`servicesId=https://...`, the redirect_uri a web code exchange must
repeat); `PYNAPLI_SOCIAL_TOKEN_KEY` (seals Apple refresh tokens; strong and distinct, required in
staging/production when the flag is on). Test-only overrides, refused in staging/production:
`PYNAPLI_GOOGLE_JWKS_URL`, `PYNAPLI_APPLE_JWKS_URL`, `PYNAPLI_APPLE_TOKEN_URL`, `PYNAPLI_APPLE_REVOKE_URL`.
Google is on when the flag is on and a client id is set; Apple only when the client ids AND the team
id, key id and key are all set (deletion must be able to revoke).

**Endpoints**

- `GET /v1/auth/social/config` (public) → `SocialSignInConfigDto {google, apple}`. Show a button only
  when its flag is true.
- `POST /v1/auth/social/nonce` (public; any body, e.g. `{deviceId?}`, is accepted and ignored;
  `AUTH_SOCIAL_NONCE_IP` 120/h per IP) →
  `SocialNonceDto {nonce, expiresInSeconds: 600}`. Single use; only its hash is stored (Redis). 404
  `SOCIAL_SIGN_IN_UNAVAILABLE` when no provider is on.
  - **Google**: pass the nonce as is (Credential Manager `setNonce`, GIS `nonce`).
  - **Apple**: pass `lowercase hex SHA-256(nonce)` to Apple (iOS `ASAuthorizationAppleIDRequest.nonce`,
    Sign in with Apple JS `nonce`); the token's `nonce` claim is then that hash. The raw value is also
    accepted. Always send the raw nonce to Pynapli.
- `POST /v1/auth/social/{google|apple}` (public; `AUTH_SOCIAL_IP` 100/h per IP, `AUTH_SOCIAL_SIGN_IN`
  30/h per provider identity) with `SocialSignInRequestDto {idToken, nonce, authorizationCode?,
  birthdate?, confirmedAdult, acceptedTerms, acceptedPrivacy, deviceId?, platform?, appVersion?}`. The
  age-gate fields are exactly email sign-up's (`EmailSignupRequestDto`). There is no `displayName`: the
  provider's name is never used (the Profile step sets one). Answers, in this order:
  1. Provider off or unknown → `404 SOCIAL_SIGN_IN_UNAVAILABLE`.
  2. Token fails any check (signature/JWKS, `iss`, `aud` not exactly one configured client id, `exp`
     passed or missing, `iat` > 5 min ahead, no `sub`, `nonce` claim ≠ the nonce) or the nonce was
     never issued / expired / already spent → `401 INVALID_SOCIAL_TOKEN`. Provider keys unreachable,
     or the nonce store down → `503 SOCIAL_SIGN_IN_TEMPORARILY_UNAVAILABLE`.
  3. **From here the nonce is spent, whatever the answer.** Every retry needs a fresh nonce AND a fresh
     ID token (and, for Apple, the fresh authorization code that comes with it).
  4. Known identity → `200 AuthSessionDto` (same as email sign-in); a suspended/banned/paused account
     → `403 ACCOUNT_NOT_ACTIVE` (appeal below).
  5. Unknown identity whose **provider-verified** address already belongs to an account →
     `409 SOCIAL_EMAIL_HAS_ACCOUNT` ("sign in the way you usually do, then add Google/Apple in
     Settings"). Checked before the age gate. Never linked automatically.
  6. Unknown identity, `birthdate` null/blank → `422 SOCIAL_SIGNUP_NEEDS_AGE_GATE`; nothing created.
     Client: run Birthday → Policies, then a fresh nonce + fresh provider sign-in, and send again with
     the fields.
  7. Unknown identity with the fields → `validateSignupAgeGate` exactly as email sign-up
     (`400 AGE_RESTRICTED`, `BIRTHDATE_REQUIRED`, `INVALID_BIRTHDATE`, `ADULT_CONFIRMATION_REQUIRED`,
     `TERMS_ACCEPTANCE_REQUIRED`, `PRIVACY_ACCEPTANCE_REQUIRED`), then the account is created and
     `200 AuthSessionDto` (no email code). Apple: send `authorizationCode` so deletion can revoke.
- `GET /v1/auth/identities` (bearer) → `LinkedIdentitiesDto {identities: [{provider: email|google|apple,
  linkedAt, emailHint?}], hasPassword}`. `emailHint` is masked (`j•••@gmail.com`).
- `POST /v1/auth/identities/{google|apple}` (bearer; `SOCIAL_IDENTITY_CHANGE` 20/h per account) with
  `LinkSocialIdentityRequestDto {idToken, nonce, authorizationCode?}` → `200 LinkedIdentitiesDto`
  (also when it was already linked to this account). `409 SOCIAL_IDENTITY_IN_USE` (the identity signs
  in to another account), `409 SOCIAL_PROVIDER_ALREADY_LINKED` (this account already has a different
  Google/Apple identity), plus the token errors above, except that a bad/expired/wrong-nonce provider
  token answers **`400 INVALID_SOCIAL_TOKEN`** here (not 401): a 401 on a signed-in route makes the
  client refresh and replay, which would only resend a spent nonce. A 401 on this route always means
  the bearer session.
- `DELETE /v1/auth/identities/{google|apple}` (bearer; same limit) → `200 UnlinkedIdentityDto
  {unlinked: true}`; `409 LAST_SIGN_IN_METHOD`; `404 SOCIAL_IDENTITY_NOT_LINKED`. Works while the
  provider is switched off. Unlinking Apple revokes its grant.
- `POST /v1/auth/account-appeal/social/{google|apple}` (public; `ACCOUNT_APPEAL_IP`) with
  `SocialAccountAppealRequestDto {idToken, nonce, reason}` → `AppealDto`, the social twin of
  `/v1/auth/account-appeal`: `409 ACCOUNT_NOT_RESTRICTED` for an active account, `404
  SOCIAL_ACCOUNT_NOT_FOUND` when the proven identity has no account, plus the token errors.
- Email sign-up is unchanged: an address held by a Google/Apple account gets the same 202 and the
  neutral "already has an account" notice email, never a code.
- Account deletion revokes stored Apple grants (best-effort, after commit) and scrubs every identity;
  the data export lists `signInMethods` (provider, linked_at, masked hint, email_verified,
  last_used_at), never subjects or tokens.

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

#### Section 2 contract (plan G2–G12, 2026-11-24)

Every field below is additive with a default, so older clients still parse (`PynapliJson` ignores
unknown keys) and older requests mean what they meant.

- `GridCardDto.fresh: Boolean = false`: the "New" marker. Server rule: the account is under 72 hours
  old or added an approved public photo in the last 24 hours, **and** was active in the last 24 hours.
- `GridCardDto.activeAgo: String? = null`: a coarse server-computed key, never a timestamp. One of
  `online`, `15m`, `1h`, `3h`, `today`, `week`; each covers the window up to its bound after the
  previous one (`15m` ≤ 15 min, `1h` ≤ 1 h, `3h` ≤ 3 h, `today` ≤ 24 h, `week` ≤ 7 days). `online`
  always agrees with `online = true`. Null when older than 7 days, never seen, or the member hides their
  activity (incognito; such members' online dot follows today's rules). Clients should treat an unknown
  key as null. Since the dot holds 15 minutes after the last activity, the first key after it is
  normally `1h`. Favorites cards carry both fields too.
- `GridQueryRequestDto.freshOnly: Boolean = false`: the Fresh quick chip; only cards whose `fresh`
  would be true. Free. It replaces "Recently joined": `recentlyJoinedOnly = true` (older clients, saved
  filters) is read as `freshOnly = true`. Send `freshOnly` from now on.
- Sparse areas: a first page that would show fewer than 12 people is answered from the 100 km step
  instead, and **pages normally**: send the returned `nextCursor` with the same request (same
  `radiusMeters` as before; the cursor carries the widened scope).
  `GridQueryResponseDto.widened: Boolean = false` is true on that page and on every page continuing its
  cursor, so the client can say "Showing people farther away".
- Order: boosted, then everyone else, then demoted (no activity in 30 days, or no approved photo), each
  by distance. Online is a filter, never an order.
- Grid messaging reason: `PublicProfileDto.messagingUnavailableReason: String? = null` (profile detail,
  `GET /v1/profiles/{userId}`, where `allowGridMessages` already lives). `"VERIFY_TO_MESSAGE"` exactly when
  `allowGridMessages` is false because the owner takes Grid messages from photo-verified members only and
  the viewer is not verified: link to photo verification. Null in every other case, including the owner's
  matches-only, no-one or off settings; blocks and hides never get a reason (the profile is simply not
  found). The send path is unchanged: a refused Grid open is still `403 MESSAGING_NOT_ALLOWED`.
- Presence: see "Realtime" (`POST /v1/presence/heartbeat`).

#### Section 3 contract (paywall on tap, 2026-11-25)

Additive and defaulted, like Section 2. Rules: GRID_IMPLEMENTATION_SPEC "Locked cards past the tier
depth".

- `GridQueryRequestDto.acceptLockedCards: Boolean = false`. Send `true` only from a client that
  renders locked cards as paywall tiles. Without it nothing changes: the Grid stops at the tier depth
  with `resultLimitReached = true` and no `nextCursor`. A cursor from an opted-in request is
  `INVALID_GRID_CURSOR` on a request without the flag, and the other way round.
- With it, paging continues past the depth: Free positions 60–599 (0-based) and Social 600–1199
  come back locked; Select is never locked. `resultLimitReached` then means the end of the locked
  window (more people exist behind it), with `resultLimitTier` the viewer's tier.
- `GridQueryResponseDto.lockedFromIndex: Int? = null`: the 0-based position in the whole result
  where locking starts (60 for Free, 600 for Social). Set on the page holding the first locked card,
  on every later page, and on an unlocked page whose next page starts locked. Null otherwise and
  always null without the opt-in.
- `GridQueryResponseDto.unlockTier: String? = null`: the plan that unlocks them, `"social"` (Free)
  or `"select"` (Social); set whenever `lockedFromIndex` is.
- `GridCardDto.locked: Boolean = false`. A locked card has no identity:
  - `userId` and `rankToken`: the same opaque token, `"locked_"` + 22 base64url characters, stable
    for this viewer and this member, different for every other viewer. Use it as a list key only. It
    is never a user id: every route that takes one (profile detail, favourites, block, hide, report,
    Spark, album request, private album, message, Swipe like and like-back, partner-link invite)
    refuses it with that route's usual malformed-id answer (400 or 404), and the presence stream
    ignores it.
  - `displayName = ""`; `linkedPartner`, `activeAgo`, `visitingFrom`, `visitingUntil` are null.
  - Kept: `thumbnailUrl` (the approved thumbnail, same URL rules), `online`, `distanceLabel` and
    `distanceMeters` (null when the member hides distance), `visiting`, `tonightActive`, `verified`,
    `coupleVerified`, `fresh`.
  - Tapping a locked card opens the paywall; the client must never fetch a profile for it.
- Locked pages are rate-limited more tightly: see SECURITY_ROUTE_MATRIX (`GRID_LOCKED_PAGE`, 60 an
  hour, `429 RATE_LIMITED`).
- The plan text said the targeted routes answer `ENTITLEMENT_REQUIRED` for a locked card. They don't:
  a locked card carries no id to send, so the routes answer as for any malformed id. That way the
  refusal does not tell the caller that the token belongs to a real, locked member.

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
  option. `PublicProfileDto.allowGridMessages` is computed for the viewer under the same rule, and
  `PublicProfileDto.messagingUnavailableReason` says `"VERIFY_TO_MESSAGE"` when only the viewer's
  missing photo verification stands in the way (see Grid, "Section 2 contract").
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
  `ACCOUNT_NOT_ACTIVE` for such accounts; an active account gets `ACCOUNT_NOT_RESTRICTED` (409). An
  account that signs in with Google or Apple appeals through `POST
  /v1/auth/account-appeal/social/{provider}` with a fresh provider sign-in (Section 5 contract).

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
[BACKEND_ARCHITECTURE_SPEC.md](BACKEND_ARCHITECTURE_SPEC.md#realtime).

Presence (plan G2, 2026-11-24; privacy review 2026-11-25): any authenticated HTTP request and a
realtime socket **opening** mark the caller online for 15 minutes; a socket's periodic re-auth does not.
`POST /v1/presence/heartbeat` (re-added; bearer auth, no body, `204` with no body, echoes nobody's
presence) keeps an idle foregrounded app online: call it about every 60 s while in the foreground, never
in the background. Calling it more often does no harm and no extra writes (one write per user a minute).
Logout and account deletion end presence at once. The presence stream sends
`PresenceDeltaDto(online = true)` when a subscribed person comes online and `online = false`, after a
random 0–60 s delay, when they go offline; only for members the Grid could show (never incognito,
Grid-hidden, hidden while travelling, suspended or deleted). A subscribe frame keeps at most the first 200
distinct valid ids; nothing is sent on subscribe. `lastActiveAt` is the emission minute.

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
