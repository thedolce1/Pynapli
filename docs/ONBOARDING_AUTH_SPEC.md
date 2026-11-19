# Pynapli Onboarding And Auth Spec

Last updated: 2026-11-19

## Purpose

Onboarding should collect the minimum information needed to make the first grid useful, then defer deeper profile completion.

Core rule:

> Get users safely to a useful grid fast. Deep detail comes later.

## Auth Methods

Launch:

- Email/password.
- Apple Sign In.
- Google Sign In.

Optional later:

- Phone verification.

Session security:

- Secure token storage: iOS Keychain / Android Keystore.
- Refresh-token rotation.
- Remote logout per device.
- Active sessions list in settings.

## Membership Privacy (no account oracles)

Someone who knows a member must not be able to confirm the member has an account. Unauthenticated
auth endpoints that take an email therefore answer, and do the same work, whether or not the
address is a member (server rules, enforced 2026-11-19):

- **Sign-in** (`/v1/auth/email/login`, and `/v1/auth/account-appeal` which shares
  `AuthService.verifyCredentials`): the password is always checked once with PBKDF2 at the current
  parameters; an unknown address is checked against `PasswordHasher.decoyHash` (random bytes at the
  current iteration count). Unknown address, wrong password, and a suspended/banned/paused account
  with a wrong password all return the same `401 INVALID_CREDENTIALS`, and spend the same failed
  sign-in budgets. Account status (`ACCOUNT_NOT_ACTIVE`, `ACCOUNT_NOT_RESTRICTED`) is revealed only
  after the right password. Deleted accounts never match (treated as unknown).
- **Password reset request** (`/v1/auth/password/reset-request`): answers `200 {"sent":true}` after
  only the provider check, the rate limit and the email-format check, none of which depend on
  membership. The account lookup, the code write and the email run off the request path on the
  server's background scope (`launchResetWork`; the handler never waits for them); a failure there is logged by exception type only
  (never the address or code) and cannot change the answer. Non-active accounts get no email, like
  unknown addresses.
- **Password reset completion** (`/v1/auth/password/reset`): an unknown address, an account without
  a live code, a dead code and a wrong code all do the same reads and the same failed-attempt write
  (a zero-row update when there is no live code) and answer `INVALID_RESET_CODE`.
- Rate limits for these flows key on the typed address (and client IP / device), never on whether
  it is a member.

Known residuals:

- **Sign-up is still an oracle (open, awaiting a founder decision):** `/v1/auth/email/signup`
  answers `409 EMAIL_ALREADY_EXISTS` for a registered address. Closing it needs an email-ownership
  step (always answer "check your email"; create or activate the account on confirmation; an
  existing address gets an "you already have an account" email instead), which changes onboarding.
- Tiny timing differences remain (an index hit versus miss; a one-row versus zero-row update on a
  wrong reset code). If the PBKDF2 iteration count is ever raised, accounts still on the old count
  verify faster than the decoy: rehash on successful sign-in when that happens.
- The reset budgets are per typed address (`AUTH_PASSWORD_RESET` 5/h, `AUTH_PASSWORD_RESET_VERIFY`
  15/h, five wrong tries kill a code), so anyone can exhaust them for a member's address and delay
  that member's reset for up to an hour. Not an oracle (a stranger's address behaves the same); a
  per-address-and-IP split is the proposed fix and needs a decision because it trades against the
  per-address email cap.

## Signup Flow

Shipped ordering (founder Grindr-reference capture 2026-06-10, refining the
2026-06-09 DOB-first decision — an underage visitor is stopped before any account
data is collected):

1. Date of birth: typed DD/MM/YYYY numeric fields (DD/MM/YYYY ordering
   founder-confirmed for the future Europe launch); calendar-strict validation;
   underage stop screen on failure.
2. Policies: dedicated "Review our policies" step — Terms of Service and Privacy
   Policy rows each with a Read link to the canonical URLs, one Accept button
   recording both acceptances.
3. Create account with email/password.
4. Location permission, after value is explained; skippable.
5. "Your profile": one consolidated, skippable step — photo (honest nudge copy)
   plus display name (15-char cap), single Next.
6. First grid.

Gender/orientation basics, lifestyle tags, and looking-for are deferred entirely to
profile edit — the stepper collects only what the first grid needs. Notifications
permission is requested post-signup by the existing authenticated prompt flow, not
inside the stepper.

## Deferred Until Profile Edit

- Bio.
- Prompts.
- Additional photos.
- Private album.
- Detailed lifestyle tags.
- Logistics.
- Partner linking.
- Advanced privacy.

## Photo Step

Rules:

- Optional at signup.
- One photo initially.
- Crop tool.
- Moderation pending state.
- Honest nudge: profiles with photos get seen more.
- No guilt-heavy copy.

## Permissions

Location:

- Ask after value is explained.
- Use while-in-use.
- If denied, show useful empty state and Settings link.

Notifications:

- Ask after explaining messages/matches/invites.
- If denied, app still works.

## Post-Signup Nudges

Use non-blocking nudges:

- Add a photo.
- Add lifestyle tags.
- Complete profile.
- Link partner.

Rules:

- Dismissible.
- Stop after a small number of sessions.
- Deep-link to relevant edit section.

## Profile Completeness

Use completeness internally for ranking and gentle nudges.

Do not:

- Publicly shame incomplete profiles.
- Hard-block core usage solely for incomplete optional fields.

## Edge Cases

- App closed mid-signup: resume last completed step.
- Social account reused: log into existing account.
- Photo upload fails: retry or skip.
- Verification fails in required market: retry/support.
- Location denied: first grid empty/location-required state.

## Implementation Checklist

- [ ] Partial: email auth is implemented; social auth and full onboarding auth set are not complete.
- [x] Android secure token storage: the persisted session is encrypted at rest with a non-exportable AndroidKeyStore AES-256-GCM key (`SessionTokenCipher` expect/actual; `enc1:` format), legacy plaintext rows upgrade on first read, and an undecryptable value surfaces as a signed-out session. Proven on-device by `scripts/android-secure-token-qa.ps1` (ciphertext in the SQLite DB+WAL, no plaintext token JSON, session survives restart). iOS Keychain actual lands with the iOS phase (pass-through until then, per the Android production gameplan scope, now [archived](archive/ANDROID_PRODUCTION_GAMEPLAN.md)).
- [x] Signup progress saved: signup arms a device-local resume flag pinned to the signing-up account (`OnboardingProgressStore`, deliberately separate from the server-synced `AppSettings` blob); next launch re-enters the stepper at the consolidated "Your profile" step with the saved display name prefilled. A different account signing in on the same device never inherits the stepper.
- [x] Skippable photo (the consolidated "Your profile" step uploads to the profile album with moderation-pending honesty copy, or skips). Tags/looking-for are deferred entirely to profile edit by design — nothing to skip at signup.
- [x] Age gate before access for email signup: typed DD/MM/YYYY birthday before any account data is collected (calendar-strict validation, underage stop screen), explicit 18+ confirmation, and Terms+Privacy acceptance on the dedicated Policies step required before the session is created.
- [x] Permission-denied onboarding QA: location step asks after value is explained, with denied/skip continuing to the grid's location-required empty state; notification permission unchanged (post-signup prompt + denied QA already covered). `scripts/android-onboarding-qa.ps1` covers signup happy path (typed DOB, policies Accept), denied location, force-stop resume, and nudge show/dismiss on the emulator.
- [x] First grid uses signup data: the location submitted at the Location step feeds the distance-ordered first grid; DOB drives display age.
- [x] Post-signup nudges are bounded: add-a-photo grid banner, dismissible, capped at 3 impression sessions, skipped in the signup session itself, deep-links to Media Albums. (Tags/complete-profile/partner-link nudges remain future work.)

## Do Not Implement

- Do not force explicit lifestyle tags at signup.
- Do not require partner linking during onboarding.
- Do not block first grid for optional photo.
- Do not nag indefinitely.
