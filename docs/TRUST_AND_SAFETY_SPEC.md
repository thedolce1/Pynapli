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
- **GET routes and enumeration.** Rate-limited reads: `GET /v1/profiles/{id}`, the private-album
  read, admin per-person lookups (`/v1/admin/users/{id}`, `/v1/admin/subscriptions/support/{id}`,
  `/v1/admin/partner-links/{id}`: 300/h per IP), plus the service-level grid, travel-search and
  profile-view limits. Every other GET has only the global ceilings, by design: they return the
  caller's own data (`/v1/me*`, settings, blocks, hidden, reports, appeals, sessions, entitlement,
  activity, exports), or are membership-checked by an unguessable id (message threads, Psync
  sessions, export downloads), so walking ids reveals nothing.
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
- [x] Verification lowers risk where appropriate: a verified age-verification outcome (admin review or provider callback) publishes a -20 risk event; the account score stays floored at 0.
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
