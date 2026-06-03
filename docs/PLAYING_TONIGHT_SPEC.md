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

- [x] Solo and linked-together Tonight modes are distinct. (PT-1: `tonight_sessions.mode` solo|together; solo goes live immediately, together starts `proposed`.)
- [x] Linked-together session requires both approvals. (PT-1: `tonight_session_participants` owner auto-approved + partner pending; session flips to `active` only when all approved. Either participant can end; decline sets `declined` with no public signal.)
- [x] Session duration and expiry. (PT-1: default 2h window 1..24, `expires_at` reset from activation; `expireStale()` sweep + reads filter `expires_at > now`.)
- [x] Grid indicator on individual tiles. (PT-2: `GridCardDto.tonightActive` hydrated per page from active sessions OR the legacy flag; subtle gold moon badge at the tile top-start — never a couple card. `tonightAvailableOnly` filter unified onto active sessions.)
- [x] Feed uses session context without creating couple accounts. (PT-3 backend: `GET /v1/tonight/feed` — one row per live session anchored on the owner with the linked partner as a mini-avatar, never a couple card. Grid-style PostGIS discovery with the same owner eligibility gates + block/hidden split; map-only opt-out via `map_visible`; zone-only location. Client list UI lands in PT-4.)
- [x] Zone map has no precise pins. (PT-4: Compose `PlayingTonightScreen` — abstract Canvas zone map with halos sized by session count and dots jittered within their coarse `zoneKey` bucket (never precise/street-level), a draggable bottom sheet with intent pills + session rows, and a live-session floater. Opened from a Grid-header moon button as a full-screen overlay; row/message taps open the individual's profile.)
- [~] Session interactions respect individual vs together context. (PT-5 shipped the client **start-session + linked-approval flow**: `StartTonightSheet` builds a solo/together proposal — solo goes live immediately, together starts `proposed`; the map floater shows the right state per viewer — owner "waiting", pending partner "Approve/Decline", or live "End". Row/message taps open the individual's profile. Remaining for PT-6: routing a "message session" thread to both partners only in the together/session context + not auto-sharing every interaction.)
- [ ] Activity labels "via Playing Tonight" where appropriate. (PT-5/PT-6)

### PT-6 — expiry sweep + monetization surfacing (shipped 2026-06-02)

`TonightSessionExpiryWorker` (mirrors `MediaCleanupWorker`) sweeps elapsed `proposed`/`active`
sessions to `expired` every 5 minutes (reads already filter `expires_at > now`, so this is
hygiene, not correctness); wired + started/closed in `ServerDependencies`/`Application`, covered
by an `expireStale` integration test. The client surfaces the free-tier weekly cap
(`TONIGHT_WEEKLY_LIMIT_REACHED` → friendly "Upgrade for unlimited sessions" banner;
ending/declining stay ungated). Server tier caps were already enforced in PT-1.

**Notifications shipped (2026-06-03):** `NotificationKind.TONIGHT_INVITE` (high-urgency, gated by
`partnerInvitesEnabled`) is emitted to the partner when a linked-together session is **proposed**;
`NotificationKind.TONIGHT_REMINDER` (gated by `activityEnabled`) is sent to every participant ~15
minutes before expiry. The reminder uses a `tonight_sessions.reminder_sent_at` latch claimed
atomically (`FOR UPDATE SKIP LOCKED`) so it fires at most once per session across worker ticks/
instances; the existing `TonightSessionExpiryWorker` runs the scan each tick. (V61 migration.)

**Partner-offline pause/end shipped (2026-06-03):** linked-together (`mode='together'`,
`status='active'`) sessions are presence-reconciled each `TonightSessionExpiryWorker` tick.
Pause is modeled as a `tonight_sessions.paused_at` timestamp (V62) — NOT a new status — so
`current()`/`end` semantics are unchanged and only DISCOVERY hides paused sessions
(`AND ts.paused_at IS NULL` added to the feed WHERE + the grid hydration/rank `tonight` EXISTS
subqueries). `TonightSessionService.reconcilePresence()` resolves the online set once via
`PresenceService.onlineUserIds(...)` and per session: all-online + paused → resume (clear
`paused_at`); any-offline + running → pause (`paused_at=now`); any-offline + paused ≥ 5-minute
grace → end (`status='ended'`). Solo sessions are never touched; a null `presenceService` is a
no-op. Covered by reconcile + feed-hiding integration tests.

**Moderated session photo — server gate shipped (2026-06-03):** the feed only surfaces a
session's `photo_media_asset_id` when its media asset is `moderation_status='approved'` AND
`processing_status='ready'` AND not deleted (a subquery in the feed SQL nulls it otherwise), so a
pending/held/rejected/deleted photo is never shown and the session "continues without photo" (Edge
Cases).

**Tracked follow-ups (each its own slice — not yet shipped):** session-photo **attach UI** (client
media upload, depends on the album upload pipeline) + row/map display; and **"via Playing Tonight"
activity labels** (activity-feed integration). The core create→approve→browse→expire loop is
functional end-to-end without these.

### PT-1 — session backend (shipped 2026-06-02)

`V60__create_tonight_sessions.sql` + `com.pynapli.server.tonight` (`TonightSessionService`/
`JdbcTonightSessionRepository`/routes). Endpoints (bearer-authed): `POST /v1/tonight/sessions`
(propose), `GET /v1/me/tonight/session` (current), `POST /v1/tonight/sessions/{id}/approve`,
`POST /v1/tonight/sessions/{id}/decline`, `DELETE /v1/tonight/sessions/{id}` (end). Free tier
capped at 3 sessions / rolling 7 days (ending/declining never gated); approve/decline/end are
transactional with a `FOR UPDATE` session lock; location is never stored on the session (feed/
map derive zone live in later slices). Covered by `TonightSessionServiceIntegrationTest`.

## Do Not Implement

- Do not create couple grid cards.
- Do not auto-share all session interactions with partner.
- Do not expose precise location.
- Do not allow explicit session photos/notes.
- Do not paywall ending or declining.

