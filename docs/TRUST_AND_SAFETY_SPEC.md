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

## Met in person

A member who has met someone in real life privately confirms it from their conversation; once enough
independent confirmations exist, the weekly batch gives a badge the person can choose to show. Founder
decisions: threshold 3, a couple badge in v1, opt-in display (off by default). Selfie verification says a
person is real; "Met in person" says several members met them. Neither says what anyone did, and the badge
is not checked by Pynapli. Code: `server/.../metinperson/`, migration V120.

The promise this design keeps: nobody learns who met whom. Not the subject (whatever they do), not a
voucher (whatever they toggle), not someone holding a copy of the database without the key K3.

### Who can confirm whom (write time, `MetInPersonRules`)

- Voucher and subject are both photo-verified (`profiles.verified_at`, the verified badge condition).
- The voucher's account is active and at least 30 days old. The voucher's risk tier is NOT checked here
  (the menu would reveal the tier to its owner); it is applied at the batch.
- Messages between the two of them only: each sent at least one visible message (`message_type =
  'user'`, `moderation_status = 'visible'`; held and removed do not count, a message the sender later
  deleted does) at or after the moment both were in the conversation (the later of their
  `COALESCE(invitation_responded_at, joined_at)`); the earliest such message is at least 24 hours old and
  the latest at most 90 days old. In a group, other people's messages never count. Messages are checked
  only when confirming, never at the batch.
- Never (at write time, facts the voucher knows): any `user_profile_links` row between the two, any
  status; a block by the voucher.
- Once per directed pair, ever: A confirming B and B confirming A are separate rows. A repeat of a
  standing confirmation changes nothing, not even its date.
- At most 10 given per voucher per rolling 30 days, counting withdrawn and voided ones: taking one back
  never refunds its slot. A couple confirmation uses two. One voucher's writes are serialised by an
  advisory lock (keyed by a token), so concurrent confirms cannot pass the cap.
- Taking back: silent, within 30 days of giving, also after leaving the conversation. After taking it
  back, the same pair cannot give it again for 180 days (the same uniform refusal); after that, or after
  the 24-month expiry, it is the same row restored with a new date (the first-given day never moves).
- Only from the conversation overflow menu, at the voucher's initiative; the menu asks the server for
  the viewer's own options when it opens (`GET .../met-in-person`), never on open or refresh. The subject
  cannot request one and is never told, in any channel. Every refusal is `MET_IN_PERSON_NOT_AVAILABLE`.

### Couples

The voucher may tick "and [partner]" only when the subject's active linked partner is also an active
participant of the same conversation (a group chat, which only forms through a partner invite), the
profile's own linked-partner rule shows that partner to the voucher in both directions
(`CoupleVerifiedFilterSql.partnerShownToViewer`, shared with the couples filter: partner active, visible,
not incognito, not quarantined or terminated, not blocked either way with the viewer, not hidden by the
viewer, and each owner's partner visibility lets the viewer see the other), and the partner independently
passes every rule above with the voucher in that conversation (their own messages between the two of
them). Confirming each partner from separate direct chats gives two personal credits, not a couple credit.
A couple badge needs 3 independent vouchers who each confirmed both partners together under the couple's
current link. Unlinking records the end (`vouch_link_ends`) and turns the couple badge off at once; couple
confirmations dated before the latest end never count again, and a couple badge published before the
link's latest change never shows (link rows are reused on relink). Personal badges stay.

### What counts at the batch, and whose act each exclusion is

The rule: nothing the subject does may change which confirmations count. Every exclusion is classified:

| Exclusion | Whose act |
| --- | --- |
| Withdrawn | the voucher |
| The voucher blocked the subject | the voucher (a block by the subject is ignored entirely) |
| The voucher's account inactive, deleted, or not photo-verified | the voucher, or the service (ban, verification revoked) |
| The voucher quarantined or terminated | the service. A single person's reports are capped below quarantine (`ReportScoringPolicy`), so the subject alone cannot cause it; `friction`, which one report can reach, is deliberately not excluded |
| A partner link between the two accepted on or before the day given | fixed at that day (a link needs the voucher's own acceptance; a pending invite, which the subject can create alone, never counts; the check reads both direction rows, so a re-invite that resets one row cannot erase it) |
| A device both used on or before the day given | fixed at that day (device rows created later are ignored) |
| Voided by an admin | the service |
| Older than 24 months | time |
| The voucher's token matches none of the subject's conversation partners | never happens in practice: participant rows are never deleted |

The subject's own state (active, photo-verified, not quarantined or terminated, read from the stored tier;
lazy decay is not applied in the batch, so a tier can be stale until the account's next risk read) turns
the whole badge off, never a single voucher; the profile also hides the badge at once for a quarantined or
terminated subject.

Independence (fakery):

- Vouchers linked to each other (an accepted link, now or before) or that ever shared a device count as
  one voucher. A voucher who shared a device with the subject before confirming does not count.
- At least one counted voucher must be "clean": not confirmed by the subject first or on the same day
  (first-given days never move, so the subject confirming someone later cannot change it). A same-day
  clique of four mutual confirmers cannot badge itself.
- The couple badge needs three independent vouchers by the same rules, at least one clean for both
  partners.

### Counting, delay and display

- Only `MetInPersonBadgeBatch` writes earned badges. It runs weekly at a random moment inside each UTC
  Monday-to-Monday week (`SecureRandom`): claimed in its own transaction holding the
  `vouch_batch_schedule` row lock (due check and move to next week's random time together, so it runs
  once across replicas and deploys), then members in chunks of 500, each its own short transaction; it
  never loads every confirmation. It refuses to run when the key check fails.
- Hysteresis (`MetInPersonHysteresis`): each crossing, on or off, takes effect only after a random 1 to 4
  further batches, chosen per subject per crossing and stored (`vouch_badge_states`, keyed by the subject
  token); turning off also needs the count to stay below the threshold for 2 consecutive batches first; a
  crossing that reverses before it lands is cancelled. Resulting window: from the confirmation that
  completes a threshold to the badge appearing is the wait for the next weekly run (0 to 7 days) plus 1
  to 4 more weekly runs, so roughly 1 to 5 weeks, never at the batch that first sees it. From the
  confirmation (or withdrawal, void, block) that ends it to the badge going off is the wait plus 2 runs
  plus 1 to 4, roughly 3 to 7 weeks. A voucher toggling cannot make the badge follow them: taking back
  costs a slot and blocks giving again for 180 days, and the off delay outlasts any weekly toggle.
- The badge is two booleans on the profile sheet (`PublicProfileDto.metInPerson`, `metInPersonAsCouple`),
  shown only when the person turned on "Show my Met in person badge"
  (`discovery_settings.show_met_in_person_badge`, off by default); the couple badge needs both partners'
  opt-ins and the partner mini visible to this viewer. Never a count, not a filter, not on Grid or Swipe.
  An admin freeze hides both at once and the batch keeps them off. The subject sees their own badge state
  (profile preview, export) exactly when everyone else would, never earlier: only published state is ever
  read.
- The voucher learns nothing at once: confirm and withdraw answer with the voucher's own state only.
- Nothing is notified: no notifier, Activity, broadcaster or outbox dependency; tests assert no
  `notification_outbox`, `activity_events` or `match_side_effect_outbox` row appears.

### Storage, the key, and what a copy of the database shows

`vouches(id, pair_token UNIQUE, subject_token, partner_token, link_token, first_given_at, created_at,
withdrawn_at, voided_at, key_version)`: no user, conversation or link id. Tokens are HMAC-SHA256 under K3
(`PYNAPLI_MET_IN_PERSON_TOKEN_SECRET`; fixed local default; staging and production refuse to start
without a strong, distinct value) with one domain prefix per kind: pair (ordered voucher > subject),
subject, partner, link, audit-voucher, key check. Dates are whole UTC days. `vouch_badges` is keyed by user
id because the profile reads it, but holds only the published booleans (plus a freeze); its row is deleted
when both are off and the member is not frozen. `vouch_badge_states` (pending crossings) is keyed by the
subject token. `vouch_link_ends` records when a link row last ended. `met_in_person_key` holds a check
value (an HMAC of a constant): writes, the menu, the admin view and the batch refuse to run when the
configured key does not reproduce it, so a lost or rotated key cannot silently reset caps or turn badges
off; `key_version` marks rows for a future re-tokenising rotation (not built).

The voucher's own rows (cap, withdraw, void-all, deletion, export) are found by recomputing their pair
tokens over everyone they ever shared a conversation with; participant rows are never hard-deleted (no
code deletes `conversation_participants` or `conversations`), so no extra record was needed. The batch
and admin view recompute each member's subject token and the pair tokens over that member's partners.

A copy of the database without K3 shows: the number of rows, their days (given, first given, withdrawn,
voided), and which rows concern the same (unknown) subject, since a subject's token is stable. It shows
who has a published badge (`vouch_badges` by user id, which is public anyway when shown). It does not show
who any row is about, who gave it, which conversation, or that two rows are a mutual pair: in a mutual
confirmation the two rows share no token. For a couple confirmation, the two rows (one per partner) share
the same `link_token` and the same day, so a copy shows "one couple got a joint confirmation that day", not
which couple or from whom; `partner_token` on each is a partner-kind token, unequal to either row's subject
token. Correlation remains possible with outside knowledge (for example, a subject token whose row count
rises the week a known badge appears). With K3, everything can be resolved by recomputing tokens.

### Abuse and moderation

Admin (`AdminMetInPersonRoutes`, new routes under the existing admin gate; the frozen admin endpoints are
untouched): `GET /v1/admin/met-in-person/users/{userId}` (published state, setting, independent
qualifying count now, each confirmation with its voucher resolved through K3 and reciprocal, closed-group
and shared-device flags), `POST .../confirmations/{id}/void` (body `{subjectUserId, voided}`; false
un-voids), `POST .../vouchers/void` (body `{voucherUserId, voided}`; the id is never in the path, which
request audit rows store) and `POST .../users/{userId}/freeze` (`{"frozen": false}` unfreezes). All need a
senior admin, step-up MFA and `X-Admin-Reason`, and write `met_in_person.*` audit rows. An audit row never
holds a voucher id in clear: it carries `voucherRef` (an audit-voucher K3 token) and the confirmation ids,
so a key holder can tell whose confirmations were voided and lower roles cannot. A void is invisible to
the voucher.

Not closed:

- A ring of three real, verified, unlinked friends on different devices who did not confirm each other
  first can badge each other (or a fake couple). Larger collusion goes further: a clique that staggers its
  confirmations over days (each confirms someone before being confirmed back) passes the clean-voucher
  rule, and five or more mutual friends always can. The reciprocal and closed-group flags help a moderator
  see it; nothing prevents it.
- The clean-voucher rule treats a same-day confirmation by the subject as "first". A subject who happens
  to confirm someone on the exact day that person confirmed them makes that one voucher unclean; this
  only matters if that voucher was the only clean one, and the subject cannot see days.
- A subject who has met few people can guess roughly who confirmed when the badge appears; the delay only
  blurs it.
- A voucher whose confirmation completes the threshold learns nothing at once but may notice the badge
  weeks later.
- The batch reads stored risk tiers (no lazy decay) and the couple badge's write re-checks the link at
  write time; the display then also requires the badge to postdate the link's last change, which covers
  an unlink racing the batch.

### Account deletion and export

Deletion, inside the deletion transaction: the confirmations the person gave (their pair tokens over
their conversation partners), the ones they received (their subject token), their pending state and their
badge are deleted, and couple credit with them is cleared from their partner's rows. Export: a voucher's
archive lists what they gave (the subject's display name at export time, the day, the day withdrawn, with
partner or not); a subject's archive holds only their published badge state and setting.

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
- [x] "Met in person": private confirmations stored only as K3 tokens, a weekly randomised batch with per-subject hysteresis, independence and subject-proof exclusions, opt-in person and couple badges, admin view and actions with undo (see "Met in person").

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
