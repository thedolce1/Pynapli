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

- **Sign-up** (`/v1/auth/email/signup`, `/verify`, `/resend`): closed 2026-11-19 by verifying the
  address first; see "Email Sign-up (Membership Privacy)" below for its rules and residuals.
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
   no session exists yet (see Email Sign-up).
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

## Email Sign-up (Membership Privacy)

Founder decision (2026-11): the worst outcome for a member is someone they know confirming they
have an account. Sign-up must prove the person owns the address and must never reveal whether an
address is registered. Built 2026-11-19 (`EmailSignupService`, V118); this section is the sign-up
half of "Membership Privacy (no account oracles)" above.

### Flow

1. `POST /v1/auth/email/signup` validates the request (format; **one strict address**: exactly one
   `@`, no whitespace, control characters, commas, semicolons, angle brackets, double quotes or
   backticks (an apostrophe is fine in the local part: `o'brien@example.com`), local part
   at most 64 and whole address at most 254 characters, a dotted domain of letters/digits/hyphens;
   10+ character password that is not on the common-password list (`PasswordRules`, shared by client
   and server, checked case-insensitively before any address lookup); the age gate; 18+/Terms/Privacy acceptances; those errors are about the
   request, not the address) and then answers **202 with the same `EmailSignupStartedDto`** for every
   valid request: `{verificationRequired, signupToken, codeLength, codeExpiresInSeconds}`. The token is
   random for everyone. On the request the server does the same work for any address: rate limits,
   validation, one password hash, one random token, one queued job. It never looks the address up.
   The strict check applies to new sign-ups only: sign-in and reset keep the old check, so an existing
   account whose stored address would fail it still gets in.
2. Off the request path (the application background scope through a bounded launcher: at most 4
   jobs at once and 1000 waiting, past that a job is dropped, which only means no email; the answer
   never changes):
   - **New address:** its own pending sign-up (`pending_email_signups`, one row per sign-up token)
     holding the email hash, password hash, birthdate, acceptance time and versions, the token hash
     and the code hash; then the code by email, to the **normalised** address. Another sign-up for
     the same address (another device, or someone else) gets a row of its own; it never replaces
     the first. At most 20 live rows per address: past that a sign-up is refused silently, never
     evicting an earlier one.
   - **Address with an account** (any status but deleted, as sign-in treats them; banned and
     suspended included): a neutral "someone tried to create an account with this address; sign in
     or reset your password" email. Nothing is created or changed.
   - **Deleted account's address:** treated as new, as before (deletion frees the address).
3. `POST /v1/auth/email/signup/verify` with email, code and the token. It first **spends one attempt**
   in a single statement that always updates exactly one real row: the live sign-up for (address,
   token), or, when there is none, one of a **pool of 64 permanent placeholder rows** picked at random
   (created by V118; they never expire, and their hashes have the real format and length but hash
   fixed strings that are never valid inputs, so comparisons run over the same length and match
   nothing). Only then is the code compared, and only if that attempt was within the budget.
   Success creates the account from the pending row in one transaction, with exactly the rows sign-up
   always created (user, email identity, profile, discovery settings) plus `users.email_verified_at`,
   deletes every pending row of the address, and returns the session. Every failure (wrong code,
   wrong token, unknown address, a member's address, expired, already verified, budget spent, lost
   race) answers the same `400 INVALID_SIGNUP_CODE`; all but a lost race after a correct code did the
   same work: the one attempt write on one row.
4. `POST /v1/auth/email/signup/resend` with email and token: always `202 {sent: true}`, and the
   request does no database work at all. Off the request path, only a live sign-up whose token matches
   and whose guesses are not yet spent gets a new code; attempts are never given back.
5. No email provider configured: sign-up and resend answer `503 SIGNUP_UNAVAILABLE` ("Creating an
   account by email isn't available right now. Contact support@pynapli.com.") for every address
   before doing anything (fail closed; same as reset's `PASSWORD_RESET_UNAVAILABLE`).

The token closes a takeover: without it, someone who knows the address could start their own
sign-up for it, wait for the owner to type a code, and own an account with their password. With it,
only the device that started a sign-up can finish or re-code it, and guesses only spend the budget of
the sign-up whose token they carry, so a stranger can't burn the owner's attempts.

### Codes, guess budget and limits

- Code: 6 digits from `SecureRandom`, stored as `SHA-256(pendingId:code)`, valid 30 minutes.
- **Guess budget:** 5 attempts per pending sign-up for its whole life, spent atomically before each
  comparison (N parallel guesses get N distinct attempt numbers; only numbers 1 to 5 are compared;
  completion re-checks the budget). A sign-up gets at most 3 codes (the first plus two resends); a
  resend never restores attempts. The pending sign-up lives 24 hours.
- **Worst case for an attacker** who starts sign-ups for an address with no account, hoping to own a
  verified account on it: each sign-up is 5 guesses at a 1-in-1,000,000 code, so at most 5 x 10^-6
  per sign-up. Each sign-up costs one email, and the address's emails are capped at 20 a day, so at
  most 20 sign-ups x 5 = **100 guesses per address per day, a success chance of at most 10^-4 per
  address per day** (about 3.6% if sustained every day for a year, during which the owner receives
  20 unexpected codes a day). For a member's address the budget is irrelevant: no row exists.
- Visible limits (answer `429 RATE_LIMITED`) key on **address + client IP**: sign-up 10/h, resend
  10/h, verify 15/h; plus per device (sign-up, resend) and per IP at request level (sign-up and resend
  share 30/h; verify 60/h; off in local). Someone elsewhere can't use these to stop the owner.
- "Client IP" in every per-client key (sign-up, resend and verify limits, email caps) is the client's
  network: IPv4 as is, IPv6 bucketed to its /64 (one host usually holds a whole /64), an IPv4-mapped
  IPv6 address as its IPv4 address.
- Silent email caps, counting codes and notices alike, the same for members and non-members, checked
  in order and stopping at the first that is spent: **per (address family, client IP)** 3 per hour and
  6 per day, then **per address family** 20 per day. The address family is the address with any
  `+tag` removed (caps only; `email_hash` is never folded, so existing members are unaffected), so
  `a+1@x.com`, `a+2@x.com`... share every budget. An email refused for one network never counts
  against the family. Over a cap a sign-up is a complete no-op and the answer doesn't change. Resend
  spends the budget only after the token matched.
- **Remaining denial of service (accepted):** one network (one IPv4 address or one IPv6 /64) can no
  longer stop the owner: it gets 6 emails a day for the address, the owner's own network has its own
  budget, and the owner's sign-up can't be replaced or evicted. An attacker with a handful of distinct
  networks (4 or more in a day) can still use up the address's 20 emails, after which the owner's
  *new* sign-ups and resends silently send nothing until the day rolls over. The owner sees the
  attacker's unexpected codes arrive, sees "check your email" with nothing new, and can sign up the
  next day; any sign-up the owner had already started keeps working. Accepted: closing it fully needs
  something like a CAPTCHA or proof of work, which is out of scope.

### Emails

No email template existed (the reset mailer has no provider yet; the local mailer writes only
"timestamp address code"). These set the convention, founder to confirm before a provider goes live
(`SignupEmailWording`): sender name "Account verification"; subjects "Your verification code" and
"Account notice"; no product name, no link, nothing that says what kind of app this is. Code body:
"Your verification code is 123456. Enter it in the app to finish creating your account. It works for
30 minutes. If you didn't ask for this, ignore this email. No account is created without the code."
Notice body: someone tried to create an account with this address, you already have one, nothing
was created or changed, sign in or use "Forgot password?".

### App copy and the device

"Check your email: If {address} can be used for a new account, we've sent a 6-digit code to it. It
works for 30 minutes. If that address already has an account, the email says how to sign in
instead." True for both cases. After "Send a new code": "If this address can be used, a new code is
on its way. Use the newest one." The device keeps the address, the token and the start time in a
`PendingSignupStore` (never the password): encrypted with the session tokens' Keystore cipher on
Android, the platform default cipher on iOS (pass-through until the Keychain phase, like the session
default), **`sessionStorage` on the web** (gone when the tab closes, so the next person on a shared
computer never lands on it), memory only in the dev desktop shell. An app killed before the code
reopens on the code step with the address **masked** (`j•••@g•••.com`); past 24 hours it restarts
cleanly. Signing in any other way, or going back to sign-in from the stepper, drops a waiting sign-up.

Two dead ends have a way out, without any server difference: the app counts its own failed code
submissions for the current sign-up, and after the fifth (the server's budget) the code step says
"That's too many tries for this code. Start again to get a new one." and offers **Start again**
instead of Verify (no further guesses or resends go out). "No email? Start again" is also always on
the code step, for a sign-up whose email work was dropped (then no pending row exists, and a resend
can't deliver anything). Start again goes back to email and password with the same address kept;
the person types the password again (it is never stored).

### Still distinguishable

- **By the inbox owner:** which email arrives (a code or a notice). That is the owner's own inbox.
- **Off the request path only:** the queued work differs by membership, but nobody waits on it and
  the request answers first.
- **Verify:** every failure writes one row; which row it is (a real sign-up or a placeholder) is
  not observable. Failures with no live sign-up spread at random over 64 placeholder rows, so a
  flood doesn't queue on one row lock.
- **Success** after a correct code obviously differs from failure, but a correct code proves inbox
  access.
- **Database leak** of `pending_email_signups`: for each row, an *unsalted* SHA-256 of the address
  (the same hash as `users.email_hash`, so a list of candidate addresses can be checked against it),
  which reveals "this address started a sign-up in the last 24 hours and did not have an account",
  and how many sign-ups were started for it. Plus a PBKDF2 password hash, birthdate, acceptance time
  and versions, and hashes of the token and code. Nothing is written for an address that has an
  account, so the table never records "someone tried to join with a member's address". No location,
  profile, IP or device labels.
- Data export and account deletion don't apply: a pending sign-up belongs to no account.

### Existing accounts and later contact blocking

Accounts created before this have `users.email_verified_at = NULL` and nothing is gated on it. A
later contact-blocking feature will only bind accounts with a verified email, so it will need a
verify-on-next-sign-in step for them: after a successful sign-in with `email_verified_at` NULL,
email a code to the address typed at sign-in (it matched the account, so it is the owner's), ask for
it before enabling the feature (not before using the app), and set `email_verified_at` on success.
Not built.

Contact blocking now exists (2026-11-20, TRUST_AND_SAFETY_SPEC "Contact Blocks"). Verify stores the
account's contact token and settles rows naming the address before the first session is returned
(`EmailSignupService.onAccountVerified`); every successful email sign-in does the same
(`AuthService.onEmailSignIn`), which covers accounts verified between V118 and V119. Accounts with
`email_verified_at` NULL are still never bound until the step above is built.

### Dev and QA

Local only: `POST /v1/dev/auth/email/signup` creates a verified account at once through the same
account-creation function as verify (409 for an existing address; rate-limited at 2000/h);
`POST /v1/dev/auth/email/signup/latest-code` returns only the last code the local file mailer "sent"
to an address (nothing about notices). Four locks: `PYNAPLI_DEV_SIGNUP_SHORTCUTS_ENABLED=true` (**off
by default everywhere**; refused at startup in staging/production as `DEV_SIGNUP_SHORTCUTS_FORBIDDEN`),
`PYNAPLI_ENV=local`, the local file mailer (`PYNAPLI_PASSWORD_RESET_MAILER=log`), and per request a
**loopback socket peer with no forwarding header** (`X-Forwarded-*`, `Forwarded`, `Cf-*`, `Via`,
`X-Real-IP` and similar; a tunnel such as cloudflared runs on the same machine, so its peer IS
loopback, but it always adds these headers). Otherwise they are 404.

To seed or run QA: start the server with `$env:PYNAPLI_DEV_SIGNUP_SHORTCUTS_ENABLED = 'true'`, then
run the scripts; they check first and stop with that instruction when the routes are off
(`scripts/lib/PynapliDevSignup.psm1`). The seeds and load tests use the first route;
`scripts/android-onboarding-qa.ps1` types the code from the second. **Demo:** seed with the flag on,
restart the server without it, then run `scripts/start-demo-tunnel.ps1`, which refuses to start
while the server still serves the dev routes.

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
- [x] Membership-private, verified email sign-up (see Email Sign-up): same answer and request work for every address, emailed 6-digit code, token-bound verify, equalised failures and resend, silent per-address email caps, fail-closed without a mailer, `users.email_verified_at` for new accounts, resume at the code step, local-only dev shortcuts for seeds/QA. Needs a real email provider before anyone can sign up outside local development (PATH_TO_TESTERS).
- [x] Age gate before access for email signup: typed DD/MM/YYYY birthday before any account data is collected (calendar-strict validation, underage stop screen), explicit 18+ confirmation, and Terms+Privacy acceptance on the dedicated Policies step required before the session is created.
- [x] Permission-denied onboarding QA: location step asks after value is explained, with denied/skip continuing to the grid's location-required empty state; notification permission unchanged (post-signup prompt + denied QA already covered). `scripts/android-onboarding-qa.ps1` covers signup happy path (typed DOB, policies Accept), denied location, force-stop resume, and nudge show/dismiss on the emulator.
- [x] First grid uses signup data: the location submitted at the Location step feeds the distance-ordered first grid; DOB drives display age.
- [x] Post-signup nudges are bounded: add-a-photo grid banner, dismissible, capped at 3 impression sessions, skipped in the signup session itself, deep-links to Media Albums. (Tags/complete-profile/partner-link nudges remain future work.)

- [x] Acceptance harness that proves faces (2026-11-24, onboarding + first grid plan Section 0): `scripts/android-onboarding-qa.ps1 -LocationPath both|denied|allowed` adds a location-allowed path (permission granted, emulator fix in the seeded area) that fails on "Use current location" and passes only when real grid cards render (seeded names, or at least 6 card text nodes when earlier QA accounts in the cell outrank them); `scripts/web-qa.ps1 -AuthWalk on|only|off` walks sign-up (emailed code from the dev mailer, granted geolocation) to a grid of real members, Log out, and sign-in back to the grid. `PynapliPerfMarks` logs `create_account_tap`, `account_submit` and `first_grid_card` once per process (Android logcat tag `PynapliPerf`, web `performance.mark('pynapli:*')`, no member data). **Baseline (scripted, includes typing and the code lookup; compare only against the same script):** Android API 36 emulator: landing tap to first card 89.8 s, Account step to first card 33.2 s. Web (phone viewport): 52.4 s and 18.7 s. Bugs B1 to B4 and the photo-nudge race are reproduced as ignored failing tests in `OnboardingStateHolderTest`, `LocationStateHolderTest` and `GridStateHolderTest`, and B1 as a KNOWN web QA check; each fix removes its `@Ignore`. B5 (splash jump) and B6 (back closes the app mid-sign-up) are emulator-only.

## Do Not Implement

- Do not force explicit lifestyle tags at signup.
- Do not require partner linking during onboarding.
- Do not block first grid for optional photo.
- Do not nag indefinitely.
