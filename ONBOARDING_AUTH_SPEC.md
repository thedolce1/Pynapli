# Pynapli Onboarding And Auth Spec

Last updated: 2026-04-24

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

Recommended steps:

1. Create account.
2. Date of birth.
3. Display name.
4. Gender/orientation basics.
5. Lifestyle tags, skippable.
6. Looking for, skippable.
7. Photo, skippable.
8. Age verification and terms.
9. Permissions: location and notifications.
10. First grid.

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

- [ ] Auth methods implemented.
- [ ] Secure token storage.
- [ ] Signup progress saved.
- [ ] Skippable photo/tags/looking-for.
- [ ] Age gate before access.
- [ ] Permission-denied states.
- [ ] First grid uses signup data.
- [ ] Post-signup nudges are bounded.

## Do Not Implement

- Do not force explicit lifestyle tags at signup.
- Do not require partner linking during onboarding.
- Do not block first grid for optional photo.
- Do not nag indefinitely.

