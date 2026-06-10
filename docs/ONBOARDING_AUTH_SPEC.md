# Pynapli Onboarding And Auth Spec

Last updated: 2026-06-10

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
- [x] Android secure token storage: the persisted session is encrypted at rest with a non-exportable AndroidKeyStore AES-256-GCM key (`SessionTokenCipher` expect/actual; `enc1:` format), legacy plaintext rows upgrade on first read, and an undecryptable value surfaces as a signed-out session. Proven on-device by `scripts/android-secure-token-qa.ps1` (ciphertext in the SQLite DB+WAL, no plaintext token JSON, session survives restart). iOS Keychain actual lands with the iOS phase (pass-through until then, per ANDROID_PRODUCTION_GAMEPLAN scope).
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
