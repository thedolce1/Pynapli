# Pynapli Playing Tonight Spec

Last updated: 2026-04-24

## Purpose

Playing Tonight is Pynapli's immediate availability signal for linked partners and social lifestyle users.

It is inspired by Grindr's Right Now, adapted for Pynapli's partner-link model.

Core rule:

> Playing Tonight is an explicit short-lived availability signal, not a couple account.

## Product Role

Playing Tonight answers:

> Who is active, available, and intentionally open tonight?

It should support:

- Linked partners looking together.
- Individuals looking solo.
- Social/event availability.
- Public-meet-first intent.
- Venue/event discovery.

## Session Rules

For linked-together sessions:

- Either partner can propose.
- Both partners must approve before it goes live.
- Either partner can end it.
- Session ends if partner link ends.
- Structural changes require re-approval.

For solo sessions:

- User can activate their own solo Tonight state.
- Linked partner is not automatically included.

Recommended duration:

- 2 hours.

Recommended reminder:

- 15 minutes before expiration.

## What Others See

Keep public details restrained:

- Display name/profile context.
- Linked partner mini-avatar if linked and visible.
- Availability intent: solo, together, social, events.
- Hosting/travel/public meet first.
- Approximate zone/city.
- Optional short note, max 100 characters.
- Optional moderated session photo.

No explicit content.

## Surfaces

Grid:

- Individual grid tile can show a subtle Playing Tonight indicator.
- No couple card on the grid.
- If both linked partners are active, each tile shows its own distance and a linked icon.

Playing Tonight feed:

- Can show session rows/cards because the feed is session-specific.
- Session row must still make individual accounts clear.
- Tapping opens the relevant individual profile/session context.

Map:

- Zone-based only.
- No street-level pins.
- Default is zone-only.
- Map/list visibility is opt-in per session.

## Interactions

A user can:

- Message from Grid/session if allowed.
- Flirt.
- View profile.
- Request private album if allowed.
- Block/report.

For linked-together sessions:

- A "message session" action can create a thread visible to both linked partners only if the incoming user chooses the together/session context and the session participants allow it.
- Messaging one individual from their profile remains individual unless partner invite flow is used.

Do not automatically route every message/flirt during a session to both partners.

## Monetization

Free:

- Limited weekly sessions.
- Browse Playing Tonight.

Social:

- More/unlimited sessions.
- Saved defaults.

Select:

- Priority in Tonight feed.
- Advanced Tonight filters.
- Travel/event Tonight features.

Do not paywall:

- Ending session.
- Declining partner session.
- Block/report.
- Location privacy.

## Edge Cases

- Partner declines: no public signal.
- Partner goes offline: pause linked-together session briefly, then end if not restored.
- Location permission lost: remove from map/feed until restored.
- Photo rejected: session continues without photo.
- Free limit reached: current session completes, next blocked until reset.
- Block/report: target removed from session context.

## Implementation Checklist

- [ ] Solo and linked-together Tonight modes are distinct.
- [ ] Linked-together session requires both approvals.
- [ ] Session duration and expiry.
- [ ] Grid indicator on individual tiles.
- [ ] Feed uses session context without creating couple accounts.
- [ ] Zone map has no precise pins.
- [ ] Session interactions respect individual vs together context.
- [ ] Activity labels "via Playing Tonight" where appropriate.

## Do Not Implement

- Do not create couple grid cards.
- Do not auto-share all session interactions with partner.
- Do not expose precise location.
- Do not allow explicit session photos/notes.
- Do not paywall ending or declining.

