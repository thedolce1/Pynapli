# Pynapli Trust And Safety Spec

## Purpose

Pynapli's trust, anti-spam, anti-bot, abuse-prevention and risk-based friction model.

Core rule:

> Protect real users first. Slow down suspicious behavior before it causes harm. Hard-ban only when confidence is high or the violation is severe.

## Product Philosophy

Pynapli is a sensitive lifestyle community. Trust is not optional.

Bots, spam, fake accounts, scrapers, abusive users, and subscription exploiters damage:

- User safety
- Community trust
- Message quality
- Private album safety
- Subscription conversion
- App-store standing
- Long-term retention

Pynapli should not rely only on immediate bans. Instant bans teach attackers what triggered detection and create false-positive risk. The better approach is:

1. Score behavior continuously.
2. Apply targeted friction to risky actions.
3. Quarantine high-risk actions before delivery.
4. Give real users a path to recover.
5. Hard-ban confirmed severe abuse.

## Important Refinement

The older anti-bot notes describe aggressive "shadow degradation." The concept is useful, but Pynapli should avoid making the entire app mysteriously worse for borderline real users.

Use:

- Risk-based friction
- Action throttles
- Shadow review queues
- Discovery suppression
- Verification prompts
- Moderation review
- Transparent limits for likely real users

Avoid:

- Fake push notifications
- Fake matches
- Fake payment success
- Pretending subscriptions worked when they did not
- Random whole-app degradation
- Anything that could be considered deceptive commerce

Target abusable actions, not the entire app.

## Trust Score Model

Each account has a hidden risk score.

Recommended name:

- `RiskScore`

Avoid externally exposing the score.

Scale:

- `0`: trusted/clean
- `100`: confirmed bot, spammer, fraudster, or severe bad actor

Example storage:

```kotlin
data class AccountRiskState(
    val userId: String,
    val score: Int,
    val tier: RiskTier,
    val updatedAt: Instant,
    val reasons: List<RiskReason>,
    val lastReviewedAt: Instant?
)

enum class RiskTier {
    CLEAN,
    WATCH,
    FRICTION,
    QUARANTINE,
    TERMINATE
}
```

## Positive Trust Signals

Signals that reduce risk:

- Age verified
- Photo verified
- ID verified, if supported
- Completed profile
- Public photo approved
- Account age
- Normal browsing/session patterns
- Healthy message/reply ratio
- Low block/report rate
- Low ignore rate
- Good subscription/payment history
- Linked partner verified
- Event attendance with no reports
- Manual support contact with credible context
- Successful appeal/moderation review
- No rate-limit hits over time

## Photo Verification

Selfie photo verification (one still selfie in a server-chosen pose, reviewed by a person) is what the verified badge and the "Verified
only" filters mean. Server-owned end to end (erification/PhotoVerificationService.kt):

- The user asks to verify. The server picks a random pose from a fixed server-side list
  (PhotoVerificationPoses), valid for 10 minutes. Challenges are rate-limited (10/hour) and
  submissions too (5/day).
- The user uploads one selfie through the normal media upload path. It is a private
  erification asset: never in an album, never ready/approved, never returned to other users, never
  logged. Only the owner (upload URL) and senior admins (short-lived signed review URL) reach it.
- When Cloud Vision is configured, face detection runs on submit: zero faces or more than one face is
  rejected automatically (10-minute retry). One face still waits for a human. There is no automatic
  face matching against profile photos (Vision has no comparison API); approval is always a person.
- A senior admin with step-up MFA approves or rejects with a reason the user sees (24-hour retry).
  Approval writes profiles.face_photo_verified_at and erified_at; an admin revoke clears both.
- Decisions, reviewer id and timestamps stay in photo_verifications and dmin_audit_log. The selfie
  object and asset row are deleted 7 days after the decision (1 hour if never submitted) by the
  retention worker under JobLock.
- **Founder decision (defaulted):** replacing all profile photos does **not** remove verification. The
  spec was silent; an admin revoke is the tool when photos no longer match the person.
- This is not parity with other apps (2025-2026): Tinder's Face Check is a mandatory liveness video
  with ongoing matching against new photos, and Bumble added optional government ID verification.
  Because replacing photos keeps verification here, Pynapli has no equivalent of Tinder's ongoing
  photo matching.

## Suspicious Signals

Signals that increase risk:

- Mass first messages
- Identical or near-identical messages to multiple users
- Link spam
- External contact/payment solicitation
- Excessive private album requests
- Excessive flirts
- Swipe-right rate over normal human patterns
- Very high profile-view velocity
- Perfectly regular request intervals
- Repeated rate-limit hits
- High block/report rate
- Very low reply rate
- Account-farm device/network signals
- Same device fingerprint as banned account
- Suspicious location jumps
- VPN/proxy patterns combined with other signals
- Rapid photo/profile changes after reports
- Repeated partner link/unlink cycles to exploit subscriptions
- Payment fraud/chargeback signals
- Scraping-like API usage

## Severe Hard-Ban Triggers

Some events bypass gradual friction.

Immediate lock/ban triggers:

- Confirmed CSAM or attempted CSAM
- Credible threat of violence
- Confirmed identity fraud
- Confirmed payment fraud
- Confirmed ban evasion after prior severe ban
- Law enforcement or legal escalation requiring immediate action
- Repeated severe harassment after warnings
- Verified scam operation

For severe cases, preserve evidence according to legal/privacy policy and restrict access immediately.

## Risk Tiers

### Tier 0: Clean

Score:

- 0-29

Behavior:

- Normal app experience.
- Normal rate limits.
- Normal discovery.

### Tier 1: Watch

Score:

- 30-49

Purpose:

- Increase observation and add light friction without harming real users.

Actions:

- Slightly lower first-message limits.
- Slightly lower flirt limits.
- Require cooldown between album requests.
- Increase logging for abuse signals.
- Add non-blocking verification nudges.
- Exclude from some high-visibility algorithmic boosts.

Avoid:

- Whole-app slowdown.
- Visible punishment language.

### Tier 2: Friction

Score:

- 50-69

Purpose:

- Limit harm while giving real users a recovery path.

Actions:

- Reduce first-message limits sharply.
- Rate-limit private album requests.
- Rate-limit flirts.
- Limit swipe velocity.
- Require verification for continued high-volume actions.
- Reduce grid/swipe visibility.
- Hold suspicious messages with links for review.
- Suppress suspicious Intro Notes.
- Disable Boost purchase eligibility until review.

User-facing copy when needed:

> Some actions are temporarily limited because your account activity looks unusual. Verify your profile or contact support.

### Tier 3: Quarantine

Score:

- 70-89

Purpose:

- Prevent harm while determining if the account is malicious.

Actions:

- Hide from other users' Grid/Swipe discovery.
- Hold outbound first messages in review queue.
- Disable private album requests.
- Disable Intro Notes.
- Disable link sending.
- Disable Boost/Super Boost.
- Disable partner-link beneficiary changes.
- Require verification or support review.
- Route account to moderation dashboard.

Important:

- Do not fake successful delivery.
- If a message is held, the sender can see "Sent" only briefly while delivery is pending, but backend must not claim recipient delivery/read state.
- If challenged, support should be able to explain that delivery was limited due to unusual activity.

### Tier 4: Terminate

Score:

- 90-100 or severe trigger

Purpose:

- Remove confirmed harmful actors.

Actions:

- Lock account.
- Revoke sessions/JWTs.
- Remove from discovery.
- Stop messages.
- Stop album access.
- Revoke active Boosts where allowed by policy.
- Flag device/payment/network indicators.
- Preserve moderation evidence.
- Send ban notice unless legally unsafe.
- Offer appeal only where appropriate.

No appeal:

- CSAM
- Confirmed fraud
- Law-enforcement-triggered severe restriction

Appeal allowed:

- Conduct bans
- Suspected spam
- False-positive automation

## Targeted Friction By Surface

### Grid

Risk controls:

- Limit first messages from Grid.
- Reduce profile visibility.
- Reduce ability to appear in "Online now" or "Tonight."
- Limit view velocity.
- Suppress scraping patterns.
- Require verification for high-volume browsing.

Do not:

- Reset innocent users' grid state.
- Randomly break browsing.

### Swipe

Risk controls:

- Limit swipe velocity.
- Limit Like/Super Like volume.
- Suppress suspicious Intro Notes.
- Exclude from Boosted placement.
- Prevent match creation when risk is high and pending review.

Do not:

- Create fake matches.
- Fake Likes You activity.

### Messages

Risk controls:

- First-message limits.
- Duplicate-message detection.
- Link blocking.
- Shadow review queue for suspicious first messages.
- Delivery delay for borderline messages.
- Recipient safety filters.

Message states should be honest internally:

- queued
- held_for_review
- delivered
- blocked
- removed

Client copy can be minimal, but backend state must not lie.

### Private Albums

Risk controls:

- Album request rate limits.
- Verification requirement after suspicious behavior.
- Disable requests in high-risk tiers.
- Revoke suspicious grants if account is locked.

Never paywall or block:

- Revoking album access.
- Reporting album abuse.

### Partner Links

Risk controls:

- Link/unlink cooldowns.
- One active subscription beneficiary.
- Fraud detection for cycling partners.
- Verification requirement if abuse detected.
- Server-side partner-link activation cooldown after recent unlink; unlink itself remains available.

Suspicious pattern:

- Linking with many accounts over short time.
- Switching beneficiary after each billing cycle.
- Account farms linking to one paid account.

### Activity

Risk controls:

- Suppress activity generated by quarantined accounts.
- Hide spam flirts.
- Remove activity from blocked/banned users.
- Do not let high-risk users farm Viewed Me visibility.

## Device And Account-Farm Signals

Use device and network signals carefully and privacy-consciously.

Possible signals:

- Device model/manufacturer
- OS version
- App version
- Install/session identifier
- App attestation/App Check signal
- Push token reuse
- Payment account reuse
- Network/IP reputation
- Velocity of accounts per device/network

Rules:

- Hash device fingerprints before storage.
- Do not store raw unnecessary identifiers.
- Treat fingerprint matches as risk signals, not always proof.
- Provide manual review for non-severe false positives.
- Document retention windows.

## Location Spoofing

Signals:

- Impossible travel velocity
- Large jumps combined with suspicious actions
- GPS/network/timezone mismatch
- Repeated travel-mode abuse
- Emulator/simulated location indicators where available

Actions:

- Require verification.
- Temporarily disable Travel mode.
- Reduce discovery visibility.
- Queue for review.

Do not hard-ban solely for one unusual location jump.

## Rate Limits

Rate limits should be Redis-backed and adjustable by risk tier.

Categories:

- Signup/login
- Password reset
- Grid query
- Profile views
- First messages
- Message sends
- Flirts
- Album requests
- Swipe actions
- Intro Notes
- Partner link requests
- Reports

Rate-limit hits should feed risk score, especially repeated hits.

Rules that must hold (implemented; numbers live in `RateLimitService.POLICIES`):

- **Sign-in can't be used to lock someone out.** Only failed sign-ins count, checked before the
  password: 10 failures per hour per account + client IP (the trusted proxy hop,
  `PYNAPLI_TRUSTED_PROXY_HOPS`) then `RATE_LIMITED`, so guessing from one address is bounded while
  the real user on another network still signs in. Past 100 failures per hour per account from all
  addresses, each attempt is delayed 3 s and a warning is logged with an account-hash prefix; it is
  never refused. Successful sign-ins spend nothing. The suspended-account appeal shares this budget.
- **Request-level limits** (`RequestRateLimitPlugin`, Redis-backed, fail open): bodies over 1 MB are
  refused 413 on the bytes actually read, so chunked or header-less bodies can't dodge the cap (the
  local media upload is exempt). Global per-minute ceilings on all traffic: 1,200 per client IP,
  120 per IP without a session, 600 per signed-in user (probes, `/metrics`, webhooks and the
  verification callback exempt). Route rules in `REQUEST_LIMIT_RULES`; signed-in rules are keyed by
  the user behind the token, so a fresh sign-in doesn't reset them. Per-IP rules and the global
  ceilings are off only when `PYNAPLI_ENV=local`.
- **Per-user route budgets** (hourly): message sends 600 per user across all conversations and 180
  per sender per conversation (text and media together, keyed by route + conversation id); profile
  fetches (`GET /v1/profiles/{id}`) 300; location updates 360. All keyed by the account, not the
  session.
- **GET routes and enumeration.** Rate-limited reads: `GET /v1/profiles/{id}`, the private-album
  read, admin per-person lookups (`/v1/admin/users/{id}`, `/v1/admin/subscriptions/support/{id}`,
  `/v1/admin/partner-links/{id}`: 300/h per IP), plus the service-level grid, travel-search and
  profile-view limits. Every other GET has only the global ceilings, by design: they return the
  caller's own data (`/v1/me*`, settings, blocks, hidden, reports, appeals, sessions, entitlement,
  activity, exports), or are membership-checked by an unguessable id (message threads, Psync
  sessions, export downloads), so walking ids reveals nothing.
- **Realtime sockets.** Frames over 64 KiB close the socket (1009); the pre-auth token frame is
  refused unparsed over 4 KiB. One account may hold 5 sockets per stream per replica: a sixth closes
  the *oldest* (`TOO_MANY_STREAMS`), because refusing the newest would lock out a user whose old
  socket is still half-open after a network change. Sockets re-check their session at once when the
  account's sessions are revoked (ban, lock, sign out everywhere), on every replica.
- **Auth keeps working when Redis is down.** Sign-up, sign-in, refresh and password reset/change
  limits fall back to bounded in-process counters (`RateLimitOutageFallback`: 50,000 keys, LRU,
  per replica) only while Redis fails; a warning is logged when it takes over and an info line when
  Redis is back (fallback counters are then dropped). `/metrics` exports
  `pynapli_rate_limit_fallback_active` and `pynapli_rate_limit_fallback_checks`. Other per-feature
  limits still fail as before; request-level limits fail open.

## Recovery And Appeals

False positives will happen.

Recovery paths:

- Normal behavior over time decays score.
- Photo verification reduces score.
- Age/ID verification reduces score.
- Support contact creates review task.
- Moderator can reset or lower score.
- Appeal path for non-severe bans.

Recommended score decay:

- Risk score decays slowly toward 0 after normal activity.
- Severe reasons should not decay without manual review.

Implemented (`safety/RiskDecayPolicy.kt`, `JdbcRiskRepository`, migration V109):

- The score is stored in two parts: `decayable_points` (minor, behavioural points) and the rest, which
  never decays. Only **rate-limit hits** (`rate_limit.*`) are decayable today; the list is an
  allow-list in `RiskDecayPolicy.DECAYABLE_SOURCES`. Reports, message-review holds, media moderation,
  location (impossible travel, repeated Travel switching), device/account-farm signals, verification
  events and every admin action are non-decaying, as is any source added later until it is classed
  deliberately. Whether message-review holds, repeated Travel switching or unconfirmed reports should
  also decay is an open founder question.
- Rate: 1 decayable point per day, linear (engineering choice; the spec gives no rate). A few
  rate-limit hits (2 to 6 points each) clear within a week.
- Lazy, no background job: due decay is applied under the account's row lock whenever its risk state
  is written or read through `RiskService.getState` / `RiskPolicyService`, the tier is recomputed from
  the new score, and each application is written to `account_risk_events` as `decay.minor`. Decay can
  take an account out of watch or friction (or further, if its score was all minor points). Discovery
  SQL and the admin queue read the stored tier, so an account untouched since its points fell due keeps
  the older tier there until its next read or write.
- Admin set-tier, lock and ban set the score absolutely and zero `decayable_points`, so decay never
  takes an account below an admin-set score; only minor points recorded after the admin action decay.
- Verification reductions come off the decayable part first, then the rest; the total drops by the full
  amount and is floored at 0, unless an admin floor applies (next item).
- **Verification never lowers an admin-set score (founder decision).** Admin set-tier, lock and ban
  write `admin_floor_score` (the score they set) in the same statement; mark-reviewed does not touch it.
  An age or photo verification reduction stops at that floor, and applies nothing when the score is
  already at or below it. Points added after the admin action can still be reduced, down to the floor.
  The ledger row is still written (so the photo credit counts as used); when the floor limited it, its
  `delta` is what actually applied (possibly 0) and the metadata carries `requestedDelta`,
  `adminFloorScore` and `limitedByAdminFloor`. An admin setting clean writes a floor of 0, which
  removes the protection. Migration V112 backfilled the floor from the most recent `admin_review.set_*`
  or `admin_enforcement.*` event's `score_after` and changed no score: accounts a verification had
  already taken below their admin score stay there (they show an age or photo verification event after
  the latest admin event with `score_after` below `admin_floor_score`).
- Points recorded before V109 were not backfilled: they stay non-decaying.
- Photo verification reduces score: an approved selfie verification records `verification.photo_verified`
  (-20, the same as age verification; the spec gives no amount) at most once per account, so a revoke
  and re-approval never subtracts twice. Migration V110 backfilled the same reduction (same source,
  reason and delta, metadata `{"backfill":"V110"}`) for accounts that were photo-verified before it
  existed, applied the way a live approval applies it (decayable part first, floored at 0, tier
  recomputed; verified accounts with no risk row get a 0/clean row and the ledger row, as live does).
  It skipped any account with an `admin_review.set_*` or `admin_enforcement.*` event, consistent with
  the admin-floor rule above. Couple verification has no approval path of its own (it is
  derived from both partners' photo verification) and gets no separate reduction.

Support signal:

- A coherent support request should be treated as a false-positive signal, not proof, because some bad actors will also contact support.

## Contact Blocks ("Hide from people you know")

Built 2026-11-20 (V119, `server/.../contactblocks/`). A member A enters email addresses of people who
should never come across them. If a member X with a **verified** address owns one of them, and the
two had no prior interaction, X never sees A anywhere, and nothing A does reaches X.

**The acceptance rule.** Nothing A can observe may depend on whether an address belongs to a member:
no list, count, error, change in anything A sees, or request timing. So the rule is **one-way and
passive**: it is applied only to X's reads and to pushes and realtime hints addressed to X, never to
anything A sees. A two-way version (X also disappearing for A) is forbidden: a profile vanishing when
A adds an address would confirm whose it is.

### Requests (A's side)

`GET /v1/me/contact-blocks` → `{count, maxAddresses}`; `POST /v1/me/contact-blocks {email}` and
`POST /v1/me/contact-blocks/remove {email}` → `204`; `DELETE /v1/me/contact-blocks` (remove all) →
`204`. Each does the same work for any valid address: the per-request limit (`BLOCK_OR_HIDE`), for an
add the daily budget (100 adds a day, every attempt counts), the strict syntax check
(`EmailAddressRules.isStrict`, `INVALID_EMAIL`), normalisation exactly as `users.email_hash`
(`normalizeEmail`: trim, lower-case), one HMAC, and **one statement on A's own rows** (never `users`);
an add then only nudges the resolver (`ContactBlockResolutionTrigger`), it never resolves. A duplicate
add, or removing an address never added, answers the same 204. Cap: 1,000 addresses
(`409 CONTACT_BLOCK_LIMIT_REACHED`, from A's own count only). There is no readable list: the server
cannot show one (it keeps only a keyed code), so the count includes non-members by construction.

### Storage and keys

`contact_blocks(id, blocker_user_id, email_token, target_token NULL, created_at)`, unique on
(blocker, email_token). `email_token = HMAC-SHA256(K1, normalised address)`. `target_token` is NULL
while the address belongs to no verified member ("pending"), `HMAC(K2, "target:" + X)` once bound, or
`HMAC(K2, "exempt:" + X)` when the pair was exempt (decided once, kept). `users.contact_token =
HMAC(K2, "member:" + email_token)` for a verified member, written only when the server holds the
plaintext (sign-up verify, every successful email sign-in). It is deliberately not the row's own
`email_token`, so the two tables can't be joined without K2. K1 = `PYNAPLI_CONTACT_BLOCK_EMAIL_KEY`,
K2 = `PYNAPLI_CONTACT_BLOCK_TARGET_KEY`: fixed development values locally; staging and production
refuse to start without explicit, strong values distinct from each other and from the cursor/deck
secrets (`CONTACT_BLOCK_KEYS_REQUIRED`, `CONTACT_BLOCK_KEYS_MUST_DIFFER`). Rotating a key orphans every
stored code (all rows go inert); no rotation path exists.

**What a leak reveals.**
- *The database alone* (both tables, no keys): how many addresses each member added and when, and that
  several members added the same (unknown) address; which rows are settled (bound or exempt look the
  same) and which pending. Not the addresses, not whom any row points at, not which members are
  hidden from whom.
- *With K1 only*: anyone can test a candidate address ("did any member add boss@example.com, and who?")
  against `email_token`. Not whom a settled row points at.
- *With K2 only*: `users.contact_token` can be joined to rows (`HMAC(K2, "member:" + email_token)`), and
  every settled row can be linked to its member by computing target/exempt tokens per user id: the full
  "A hides from X" graph, without the addresses.
- *With both*: everything above plus testing addresses. K1 and K2 must be stored separately from the
  database (Secret Manager) and from each other's access paths where possible.

### Interaction (the exemption)

Pairs with prior interaction are left completely alone (`ContactBlockInteractionSql`). That stops "they
stopped viewing me" from confirming the block, and stops anyone using this to vanish from a person they
are harassing (the ordinary block is for that). Exempt when, at resolution, there is (1) any of these
rows between A and X in either direction from **before** the address was added, or (2) any of them
**initiated by X at any time** (what A can already see from X):

- `activity_events`, any type: profile views (recorded only when visible to the recipient), flirts
  (including Playing Tonight flirts), likes, Pineapple Likes, matches, album requests and grants,
  partner chat invites, Psync and Tonight rows.
- `swipe_actions` likes and Pineapple Likes (a pass produces no visible signal and does not count).
- `matches` (always: a match needs X's like).
- A shared conversation (`conversation_participants` of both), counted when it was created before the
  add, or created by X, or X wrote in it.
- `favorite_profiles`, `user_profile_links` (any status, invites included), `user_blocks`,
  `media_album_access_requests`, `media_album_access_grants`, `psync_session_actions`.

Rows A creates toward X after adding the address never exempt the pair: they are exactly what the
feature hides.

### Resolution (off A's request path)

- **A adds an address whose member already has a token:** the worker (`ContactBlockResolverWorker`, its
  own background scope, one replica per tick via `PostgresJobLock`) runs after a nudge or every minute,
  always after a random 5–30 s delay, in batches of 500: it looks at every pending row from the last 30
  minutes (so a row that raced a verify is seen again), computes `HMAC(K2, "member:" + email_token)` per
  row and looks members up by it. About every 6 hours it pages through all pending rows (rows missed
  while the server was down).
- **X proves the address at sign-up verify:** the token is stored and every pending row of the address
  is settled after the account is created and **before the session is returned**
  (`EmailSignupService.onAccountVerified`), so no discovery request of X's can come first. A brand-new
  account has no interactions, so these bind.
- **Every successful email sign-in** stores the token if missing (accounts verified before V119, i.e.
  created between V118 and V119) and settles the address's pending rows **before the session is
  returned**; doing it every time also catches up a verify whose settling failed. Accounts created
  before V118 (`email_verified_at` NULL) are never given a token and **never bound**: a
  verify-on-next-sign-in step for them is still not built (ONBOARDING_AUTH_SPEC).
- **Unverified accounts are never bound** (otherwise someone registers the boss's address and compares
  views). Binding re-checks, under a `FOR SHARE` lock on X's user row, that X is live, verified and
  still holds the token.

### Where X could come across A, and how it's suppressed

The rule (`ContactBlockVisibility.hiddenFrom(X)`: one HMAC, one indexed lookup) is applied after the
shared rank caches, which stay free of per-viewer exclusions.

| Surface | Code | Suppression | Test |
| --- | --- | --- | --- |
| Grid (incl. Visiting soon, travel) | `GridService` | dropped with hides; partner mini and couple badge stripped; out of "Verified couples only" | two-worlds, partner, rank cache |
| Swipe deck, rewind, like-back, swipe submit | `SwipeDeckQueryAction`, `SwipeRewindAction`, `SwipeService.likeBack`, `SwipeSubmitAction` | excluded before ranking (after the cache); stripped minis; rewind refuses; like-back `LIKE_BACK_NOT_AVAILABLE`; a stale card `INVALID_SWIPE_TARGET` | two-worlds, partner |
| Psync decks and submits | `PsyncDeckCoordinator`, `PsyncActiveSessionActions` | out of the shared deck if hidden from either partner; minis stripped; stale card `PSYNC_INVALID_CARD` | not integration-tested |
| Playing Tonight feed and map zones | `TonightFeedService` | dropped like a blocked owner (zones come from feed rows; no server counts exist) | binding (Tonight) |
| Favorites | `FavoriteService` | can't be starred (`PROFILE_NOT_FOUND`), dropped from the list, minis stripped | two-worlds |
| Profile fetch by id, partner on a profile | `ProfileService.getPublicProfile` | `PROFILE_NOT_FOUND` (no view recorded); partner removed | two-worlds, partner |
| Activity feed, counts, badge | `JdbcActivityFeedSql`, `JdbcActivitySummarySql`, `ActivityFeedActions` | actor filtered in SQL; pair names stripped | two-worlds |
| Activity actions toward A | `ActivitySignalActions` | X's flirt/album request `*_NOT_ALLOWED`; realtime hint caused by A suppressed | two-worlds |
| Pushes and the notifications list | `NotificationEnqueueActions` | outbox write skipped when the actor hides from the recipient (the gate read still runs) | two-worlds (outbox) |
| Messages inbox, thread, preview, unread | `JdbcMessagingConversationLookupSql`, `JdbcMessagingMessageReadSql`, `MessagingConversationGuard`, `MessagingDtoMapper` | a conversation A started is never listed and every call on it is `CONVERSATION_NOT_FOUND` (detail, send, read mark, timer, leave); in a group A later joined, A is stripped from participants and invites and A's messages from pages, preview and unread | two-worlds (direct); group: not integration-tested |
| Message realtime (deltas, typing) | `MessagingRealtimePublisher` | no hint to a participant the actor (or an invited partner) hides from | two-worlds |
| Partner chat invites, bring-your-partner note | `MessagingDtoMapper`, `JdbcMessagingPartnerOfferSql` | invites naming A stripped; A never named or considered in the offer | not integration-tested |
| Partner link invites | `ProfileLinkService` | not in X's summary; accept/decline `PROFILE_LINK_INVITE_NOT_FOUND`; X→A `PROFILE_LINK_TARGET_NOT_FOUND`; admin view unfiltered | two-worlds |
| Private albums | `MediaPrivateAlbumAccessActions` | open/request `MEDIA_ALBUM_NOT_FOUND`/`INVALID_ALBUM_REQUEST_TARGET` | not integration-tested |
| Presence stream | `PresenceStreamRoutes` | no presence of A | not integration-tested |
| Match celebrations, new-matches rail | (none) | impossible: a match needs X to like A, which is prior interaction or refused | — |
| X's data export | `AccountDataExportArchiver` | Activity, album grants/requests received, conversations A started, link invites from A left out | two-worlds |
| Search | — | no people search exists | — |

Not affected, on purpose: reports and ordinary blocks (X can still report or block A by id),
moderation and admin tooling (repositories without the rule; partner-link admin lookup unfiltered),
risk and safety signals, account deletion. A cannot hide from moderators or from someone they are in a
dispute with (any interaction exempts the pair).

### Linked couples

A's block hides A, not A's partner P (P can add the address too). A's face never appears on P's card
for X: the partner mini and couple badge come off, as for a partner whose visibility is hidden.

### Deletion and export

Blocker deleted: their rows are deleted. Target deleted: rows pointing at them (bound or exempt) go back
to pending and their contact token is cleared, so they bind again only if the address re-registers and
is verified. A's export shows only `created_at` per address (`hideFromPeopleYouKnow`). X's export
leaves out what A caused.

### What it cannot do, and what stays inferable

- It does not hide A from anyone A already matched, chatted with, liked or was viewed by, or from a
  different address or a second account of the same person; it does not hide A's partner, does not
  hide X from A, and never applies to accounts whose address isn't verified (including every account
  created before verified sign-up, until a verify-on-sign-in step exists).
- **Remove, then re-add:** removing an address surfaces everything A did toward X while hidden (likes,
  views, messages). Re-adding then finds those rows dated before the new add, so the pair is exempt for
  good. The app's remove-all confirmation says so.
- **A throwaway comparison:** a contact who compares a real account with a throwaway can learn that A
  knows their address (A appears for one and not the other). It never tells anyone whether A is hidden
  from a specific member by A's own observation.
- **Unread is ambiguous, not proof:** A's message to X stays `sent`, exactly like a message nobody
  opened; a like that never becomes a match, a partner invite never answered, are the same.
- **X-side signals (not A's):** X's first sign-in or verify after someone added X's address does a few
  more indexed writes (timing). In a group conversation with a hidden A, X's own message can stay
  "sent" while every visible participant has read it (the read state still counts A). When A's partner
  P invites A into a chat with X, X never sees the invite and only X could approve it in a two-person
  chat, so it stays pending for P and A, like an invite X ignores.
- **A-side timing residuals:** for a push or realtime hint A causes, the skipped write/emit for X is a
  difference of one insert or one in-memory emit on A's request, the same as for a recipient with no
  device or the kind turned off. The binding race: X's like submitted in the same instant the row binds
  can still be recorded (milliseconds).
- **Contacts picker:** not built (needs platform code per shell). Typing is the only way in.

## Transparency Policy

Use selective transparency.

For low/medium risk:

- Friction can be subtle.
- Avoid telling attackers exact triggers.

For likely real users:

- Show generic action limits.
- Offer verification.
- Offer support.

For bans:

- Provide a clear account notice unless doing so would create legal/safety risk.

Do not expose:

- Exact risk score.
- Exact detection thresholds.
- Device fingerprint details.
- Moderator-only signals.

## Implementation Notes

- Ktor stays authoritative; clients never make risk decisions. Risk enforcement lives in the domain
  services (routes stay thin): outbound social mutations consult
  `RiskPolicyService.isSocialActionRestricted` (quarantine/terminate → `RISK_RESTRICTED`, 403).
- Redis holds rate-limit counters and short-window risk counters only. Authoritative risk state, risk
  events, appeals, bans, device-link hashes and the audit trail live in Postgres. Never keep risk
  state only in Redis.
- Device ids are hashed server-side (SHA-256) before storage (`account_device_links`); raw device ids
  are never stored.
- Admin queue actions are specified in [ADMIN_MODERATION_CONSOLE_SPEC.md](ADMIN_MODERATION_CONSOLE_SPEC.md).

## Testing Requirements

Mass first-message bots are throttled; duplicate-message spam enters review; album-request spam is
blocked; swipe automation and profile scraping are rate-limited; quarantined users are hidden from
discovery; blocked users disappear from Activity; a false-positive verified user recovers; partner
subscription cycling triggers a cooldown; a device/account-farm signal raises risk; a severe trigger
locks the account immediately; an appeal can restore an account. Risk checks must not materially slow
clean users, and the review queue must never delay clean messages.

## Metrics

Track:

- Risk tier distribution
- New accounts entering Watch/Friction/Quarantine
- Hard bans by reason
- False-positive recovery rate
- Appeal success rate
- Held messages released/deleted
- Spam reports per 1,000 active users
- Album request abuse rate
- Message reply rate by risk tier
- Rate-limit hits
- Account-farm detections
- Partner subscription abuse cases

Important:

- High false-positive rate means the system is too aggressive.
- High spam reports means the system is too weak.

## Implementation Checklist

- [x] Risk score model exists.
- [x] Risk event table exists.
- [x] Redis-backed rate limits exist.
- [x] Risk enforcement across the Ktor surface.
- [x] Tier policies exist.
- [x] Domain risk gates restrict quarantined/terminated social actions.
- [x] Quarantined/terminated accounts are suppressed from discovery-facing surfaces.
- [x] Message duplicate detection exists.
- [x] Message review queue exists.
- [x] Held first-message review supports audited release/remove actions.
- [x] Reports queue supports audited triage actions.
- [x] Severe media scanner decisions create system safety reports and media-moderation risk events.
- [x] Media reports support audited target-media removal.
- [x] Risk review queue supports audited clear/set-tier/review actions.
- [x] Appeal API supports user submission/status/withdraw and audited admin decisions.
- [x] Album request throttles exist.
- [x] Swipe velocity detection exists.
- [x] Profile view velocity detection exists.
- [x] Device/account-farm hash signals exist.
- [x] Reports can't be weaponised into automatic enforcement (`ReportScoringPolicy`): a reporter counts toward a target's risk score once per reason per 30 days (repeats are stored and accepted at zero weight); one distinct reporter caps the report-driven score at 69 (below quarantine), two or more at 89; reports alone never reach terminate, which needs a moderator.
- [x] Reporting someone takes them out of the reporter's discovery.
- [x] Travel mode abuse controls exist. Every tier is capped at 6 Travel mode destination switches per rolling 24 hours.
- [x] Location spoofing signals exist.
- [x] Partner subscription abuse cooldown exists.
- [x] Selfie photo verification: server pose challenge, private selfie, optional face-count check, senior-admin decision with audit, revoke, rate limits and selfie retention.
- [x] Verification lowers risk where appropriate: a verified age-verification outcome (admin review or provider callback) publishes a -20 risk event; an approved photo verification publishes a -20 risk event once per account; the account score stays floored at 0 and never drops below an admin-set score (`admin_floor_score`).
- [x] Risk score decay: rate-limit points decay 1 per day (lazily, ledgered as `decay.minor`); severe, report, other signal and admin-set points never decay.
- [ ] Partial: appeal review flow, admin roles and step-up MFA exist; support review workflow, account notices and senior-review separation remain.
- [x] Moderator dashboard requirements are tracked.
- [x] False-positive metrics exist.
- [x] Severe hard-ban triggers exist.
- [x] Fake/deceptive degradation is not implemented.

## Do Not Implement

- Do not immediately ban every suspected bot.
- Do not let suspected spam reach real users while waiting for review.
- Do not make the whole app randomly unusable for borderline users.
- Do not fake payment success.
- Do not send fake push notifications.
- Do not create fake matches.
- Do not use fake user pools as if they are real.
- Do not expose exact risk scores to users.
- Do not hard-ban solely from one weak signal.
- Do not store raw device identifiers unnecessarily.
- Do not trust client-side risk decisions.
- Do not block safety actions due to risk tier.
- Do not build payment flows that appear to work but are not processed, or any deceptive commerce behavior.
