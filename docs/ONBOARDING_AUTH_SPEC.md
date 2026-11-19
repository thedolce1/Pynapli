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
3. Create account with email/password. This only *starts* the sign-up: nothing is created and
   no session exists yet (see Membership privacy).
4. "Check your email": the 6-digit code from the email. The account and the session are created
   only when it checks out. "Send a new code" and "Use a different email" are always there.
5. Location permission, after value is explained; skippable.
6. "Your profile": one consolidated, skippable step — photo (honest nudge copy)
   plus display name (15-char cap), single Next.
7. First grid.

Gender/orientation basics, lifestyle tags, and looking-for are deferred entirely to
profile edit — the stepper collects only what the first grid needs. Notifications
permission is requested post-signup by the existing authenticated prompt flow, not
inside the stepper.

## Membership Privacy

Founder decision (2026-11): the worst outcome for a member is someone they know confirming they
have an account. Sign-up must prove the person owns the address and must never reveal whether an
address is registered. Built 2026-11-19 (`EmailSignupService`, V118).

### Flow

1. `POST /v1/auth/email/signup` validates exactly as before (format, 12+ character password, the
   age gate, 18+/Terms/Privacy acceptances; those errors are about the request, not the address) and
   then answers **202 with the same `EmailSignupStartedDto`** for every syntactically valid request:
   `{verificationRequired, signupToken, codeLength, codeExpiresInSeconds}`. The token is random for
   everyone. On the request the server does the same work for any address: rate limits, validation,
   one password hash, one random token, one queued job. It never looks the address up.
2. Off the request path (the application background scope; no queue, no new dependency):
   - **New address:** a pending sign-up (`pending_email_signups`) holding the email hash, password
     hash, birthdate, acceptance time and versions, the token hash and the code hash; then the code by
     email. A new sign-up for the same address replaces the row, its code and its token.
   - **Address with an account** (any status but deleted, as sign-in treats them; banned and
     suspended included): a neutral "someone tried to create an account with this address; sign in
     or reset your password" email. Nothing is created or changed.
   - **Deleted account's address:** treated as new, as before (deletion frees the address).
3. `POST /v1/auth/email/signup/verify` with email, code and the token. Success creates the account
   from the pending row in one transaction, with exactly the rows sign-up always created (user,
   email identity, profile, discovery settings) plus `users.email_verified_at`, deletes the pending
   row, and returns the session. Every failure (wrong code, wrong token, unknown address, expired,
   already verified, too many attempts, lost race) answers the same `400 INVALID_SIGNUP_CODE` after
   the same work: one lookup, two constant-time hash comparisons against placeholders when there is
   no row, one failed-attempt write (to a placeholder id when nothing matched).
4. `POST /v1/auth/email/signup/resend` with email and token: always `202 {sent: true}`. Off the
   request path, only a live pending sign-up whose token matches gets a fresh code (fresh attempts,
   same row).
5. No email provider configured: sign-up and resend answer `503 SIGNUP_UNAVAILABLE` ("Creating an
   account by email isn't available right now. Contact support@pynapli.com.") for every address
   before doing anything (fail closed; same as reset's `PASSWORD_RESET_UNAVAILABLE`).

The token closes a takeover: without it, someone who knows the address could start their own
sign-up for it (replacing the owner's), wait for the owner to type the newest code, and own an
account with their password. With it, only the device that started a sign-up can finish or re-code
it; and wrong guesses only count against the code when the token matches, so a stranger can't burn
the owner's attempts.

### Codes and limits

- Code: 6 digits from `SecureRandom`, stored as `SHA-256(pendingId:code)`, 30 minutes, 5 failed
  attempts (same as the reset code). The pending sign-up lives 24 hours; a resend within it re-codes
  the same row.
- Visible limits (answer `429 RATE_LIMITED`) key on **address + client IP**: sign-up 10/h, resend
  10/h, verify 15/h; plus per device (sign-up, resend) and per IP at request level (sign-up and resend
  share 30/h; verify 60/h; off in local). Someone elsewhere can't use these to stop the owner.
- Silent per-address email caps: 5 per hour, 15 per day, counting codes and notices alike, the same
  for members and non-members. Over the cap a sign-up is a complete no-op (nothing replaced, nothing
  sent) and the answer doesn't change. Resend spends the budget only after the token matched.
  Trade-off: the cap protects the owner's inbox from a flood, so someone spraying sign-ups for an
  address from several networks can use up that address's emails for up to a day; the owner sees the
  flood of notices/codes, and a pending sign-up already started keeps working (a capped sign-up
  can't replace it).

### Emails

No email template existed (the reset mailer has no provider yet; the local mailer writes only
"timestamp address code"). These set the convention, founder to confirm before a provider goes live
(`SignupEmailWording`): sender name "Account verification"; subjects "Your verification code" and
"Account notice"; no product name, no link, nothing that says what kind of app this is. Code body:
"Your verification code is 123456. Enter it in the app to finish creating your account. It works for
30 minutes. If you didn't ask for this, ignore this email. No account is created without the code."
Notice body: someone tried to create an account with this address, you already have one, nothing
was created or changed, sign in or use "Forgot password?".

### App copy

"Check your email: If {address} can be used for a new account, we've sent a 6-digit code to it. It
works for 30 minutes. If that address already has an account, the email says how to sign in
instead." True for both cases. After "Send a new code": "If this address can be used, a new code is
on its way. Use the newest one." The device keeps the address, the token and the start time in the
device-local onboarding progress (never the password); an app killed before the code reopens on the
code step; past 24 hours it restarts cleanly. Signing in any other way drops a waiting sign-up.

### Still distinguishable

- **By the inbox owner:** which email arrives (a code or a notice). That is the owner's own inbox.
- **Timing, off the request path only:** none on the request; the queued work differs by membership
  but nobody waits on it.
- **Verify** after a correct code: success obviously differs from failure, but a correct code proves
  inbox access.
- **Login and password reset:** unchanged here; they are equalised on a parallel branch (login always
  performs one password check; reset runs member-only work off the request path).
- **Database leak** of `pending_email_signups`: for each row, an *unsalted* SHA-256 of the address
  (the same hash as `users.email_hash`, so a list of candidate addresses can be checked against it),
  which reveals "this address started a sign-up in the last 24 hours and did not have an account".
  Plus a PBKDF2 password hash, birthdate, acceptance time and versions, and hashes of the token and
  code. Nothing is written for an address that has an account, so the table never records "someone
  tried to join with a member's address". No location, profile or device labels.
- Data export and account deletion don't apply: a pending sign-up belongs to no account.

### Existing accounts and later contact blocking

Accounts created before this have `users.email_verified_at = NULL` and nothing is gated on it. A
later contact-blocking feature will only bind accounts with a verified email, so it will need a
verify-on-next-sign-in step for them: after a successful sign-in with `email_verified_at` NULL,
email a code to the address typed at sign-in (it matched the account, so it is the owner's), ask for
it before enabling the feature (not before using the app), and set `email_verified_at` on success.
Not built.

### Dev and QA

Local only: `POST /v1/dev/auth/email/signup` creates a verified account at once through the same
account-creation function as verify (409 for an existing address); `POST
/v1/dev/auth/email/signup/latest-code` returns the last code the local file mailer "sent" to an
address. Both exist only when `PYNAPLI_DEV_SIGNUP_SHORTCUTS_ENABLED` is on (defaults on only for
`PYNAPLI_ENV=local`, refused at startup in staging/production as `DEV_SIGNUP_SHORTCUTS_FORBIDDEN`),
the environment is local, and the local file mailer (`PYNAPLI_PASSWORD_RESET_MAILER=log`) is in use;
otherwise they are 404. The seeds and load tests use the first; `scripts/android-onboarding-qa.ps1`
types the code from the second. A demo tunnel to a local server exposes them too.

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

- App closed mid-signup: before the emailed code, reopen the code step for that address (expired
  after 24 hours: start over); after it, resume last completed step.
- Social account reused: log into existing account.
- Photo upload fails: retry or skip.
- Verification fails in required market: retry/support.
- Location denied: first grid empty/location-required state.

## Implementation Checklist

- [ ] Partial: email auth is implemented; social auth and full onboarding auth set are not complete.
- [x] Android secure token storage: the persisted session is encrypted at rest with a non-exportable AndroidKeyStore AES-256-GCM key (`SessionTokenCipher` expect/actual; `enc1:` format), legacy plaintext rows upgrade on first read, and an undecryptable value surfaces as a signed-out session. Proven on-device by `scripts/android-secure-token-qa.ps1` (ciphertext in the SQLite DB+WAL, no plaintext token JSON, session survives restart). iOS Keychain actual lands with the iOS phase (pass-through until then, per the Android production gameplan scope, now [archived](archive/ANDROID_PRODUCTION_GAMEPLAN.md)).
- [x] Signup progress saved: signup arms a device-local resume flag pinned to the signing-up account (`OnboardingProgressStore`, deliberately separate from the server-synced `AppSettings` blob); next launch re-enters the stepper at the consolidated "Your profile" step with the saved display name prefilled. A different account signing in on the same device never inherits the stepper.
- [x] Skippable photo (the consolidated "Your profile" step uploads to the profile album with moderation-pending honesty copy, or skips). Tags/looking-for are deferred entirely to profile edit by design — nothing to skip at signup.
- [x] Membership-private, verified email sign-up (see Membership Privacy): same answer and request work for every address, emailed 6-digit code, token-bound verify, equalised failures and resend, silent per-address email caps, fail-closed without a mailer, `users.email_verified_at` for new accounts, resume at the code step, local-only dev shortcuts for seeds/QA. Needs a real email provider before anyone can sign up outside local development (PATH_TO_TESTERS).
- [x] Age gate before access for email signup: typed DD/MM/YYYY birthday before any account data is collected (calendar-strict validation, underage stop screen), explicit 18+ confirmation, and Terms+Privacy acceptance on the dedicated Policies step required before the session is created.
- [x] Permission-denied onboarding QA: location step asks after value is explained, with denied/skip continuing to the grid's location-required empty state; notification permission unchanged (post-signup prompt + denied QA already covered). `scripts/android-onboarding-qa.ps1` covers signup happy path (typed DOB, policies Accept), denied location, force-stop resume, and nudge show/dismiss on the emulator.
- [x] First grid uses signup data: the location submitted at the Location step feeds the distance-ordered first grid; DOB drives display age.
- [x] Post-signup nudges are bounded: add-a-photo grid banner, dismissible, capped at 3 impression sessions, skipped in the signup session itself, deep-links to Media Albums. (Tags/complete-profile/partner-link nudges remain future work.)

## Do Not Implement

- Do not force explicit lifestyle tags at signup.
- Do not require partner linking during onboarding.
- Do not block first grid for optional photo.
- Do not nag indefinitely.
