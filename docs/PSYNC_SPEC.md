# Pynapli Psync Spec

Last updated: 2026-04-24

## Purpose

Psync is Pynapli's synchronized swipe feature for linked partners.

It lets two linked partners swipe through the same bounded deck from their own devices, privately make decisions, and surface together-mode matches only when everyone involved has consented.

Core rule:

> Psync is shared discovery without shared accounts.

## Product Role

Psync gives Pynapli a signature linked-partner feature that Grindr, Tinder, and standard couple-profile apps do not offer.

It should:

- Give linked partners a reason to use Pynapli together.
- Preserve individual autonomy.
- Keep each partner's swipe choices private during the session.
- Support together-mode matching without assuming couple consent.
- Preserve solo matching where appropriate.
- Create a lightweight session recap.

It should not:

- Create couple accounts.
- Create couple cards.
- Let one partner control the other.
- Expose private swipe choices by default.
- Turn partner compatibility into a score.
- Auto-add partners to chats.

## Non-Negotiable Rules

1. Every account remains autonomous.
2. Psync is available only to actively linked partners.
3. Both partners must consent before a session starts.
4. Either partner can pause, leave, or end their participation.
5. Decks are bounded, not infinite.
6. Swipes are private during the session.
7. Together matches require both partners' interest and target consent.
8. Solo passthrough matches are private by default.
9. There is no Psync Score.
10. Session recap is useful, private, and restrained.

## Entry Points

Possible entry points:

- Swipe screen: `Start Psync`
- Partner section in profile/settings
- Activity prompt after linking partner
- Partner chat quick action later

Do not put Psync on the Grid as a primary control. Grid is for immediate open discovery; Psync belongs to Swipe/Partner flows.

## Session Flow

### 1. Initiate

Either linked partner can initiate a Psync session.

Initiator chooses:

- Session filters
- Deck size
- Intent scope
- Location context

The initiator sends an invite to their linked partner.

### 2. Partner Review

The invited partner sees:

- Who invited them
- Session filters
- Deck size
- Intent scope
- Location context

They can:

- Accept
- Deny
- Suggest changes
- Ignore

### 3. Filter Agreement

If the invited partner suggests changes:

- Initiator sees suggested changes.
- Initiator can accept, reject, or modify.
- Session does not start until both partners approve the final setup.

No partner should be forced into a session based only on the initiator's settings.

### 4. Start

Session starts only after both partners tap Start.

Ktor creates a server-authoritative Psync session with:

- Shared deck session ID
- Ordered card list
- Session filters
- Partner participants
- Expiration time

### 5. Swipe

Both partners receive the same profiles in the same order.

Each partner swipes privately:

- Like
- Pass
- Pineapple Like, if allowed
- Report/block

Neither partner sees the other's individual swipes in real time.

### 6. Pause / Leave / Resume

Either partner can pause.

Either partner can leave.

If one partner disconnects:

- Session pauses.
- State is preserved.
- Partner can resume later if session has not expired.

Recommended paused-session expiration:

- 24 hours

### 7. Complete

Session ends when:

- Both partners finish the deck.
- Either partner ends the session.
- Session expires.
- Partner link ends.

After completion, Pynapli creates a session recap and any eligible matches.

## Deck Rules

Psync decks should be bounded.

Recommended initial deck sizes:

- 25 cards
- 50 cards

Do not generate an unbounded deck.

Reasons:

- Easier sync
- Better performance
- Better resume behavior
- Better session recap
- More intentional experience

After a deck ends, users can:

- Load another deck
- Widen distance
- Change filters
- End session

## Psync Filters

Psync filters should be based on Swipe filters, not Grid filters.

Recommended filters:

- Age range
- Distance / location
- Gender/orientation preferences
- Singles / linked people / everyone
- Solo / together / social intent
- Verified preference
- Has photos
- Dealbreakers
- Advanced lifestyle filters, optional

Rules:

- Filters apply to the shared deck.
- Both partners must approve final filters.
- Each partner's individual privacy and location settings still apply.
- Dealbreakers must be respected.
- Psync should not show profiles that are incompatible with either partner's hard safety/privacy settings.

## Target Consent

A together-mode match requires consent from the target user.

Do not assume that because someone liked one partner, they are open to both.

Target users can express:

- Interested in Alex solo
- Interested in Jamie solo
- Interested in Alex and linked partner
- Open to together-mode interest
- Ask in chat

Psync together match requires:

1. Partner A likes target in Psync.
2. Partner B likes target in Psync.
3. Target has liked/accepted together-mode interest or is explicitly open to together-mode matching.

If target only likes one partner, handle as solo match eligibility, not together match.

## Match Outcomes

### Together Match

Condition:

- Both partners liked the target.
- Target consented to together-mode matching.

Result:

- Psync Together Match appears in Activity.
- Both linked partners can see the together match.
- A chat can be created only according to chat consent rules.
- Partner chat invite still requires explicit acceptance if not auto-created by mutually agreed Psync flow.

### Solo Passthrough Match

Condition:

- One partner liked the target.
- Other partner passed or did not like.
- Target liked that individual user.

Result:

- Standard solo match for the individual user.
- Private by default.
- Not visible to linked partner unless the user opts into sharing solo passthrough results.

Default:

- Solo passthrough is private.

Optional setting:

- Share my Psync solo passthrough matches with linked partner.

### No Match

Condition:

- Both partners passed.
- Target did not reciprocate.
- Target is not open to required intent scope.

Result:

- No match.
- No chat.
- No partner notification.

## Chat Rules

Psync does not silently add anyone to a chat.

Together Match chat options:

- Create a Psync match thread with both partners only if all parties consented to together-mode matching.
- Or create Activity item first and let users start chat.

Partner invite rules still apply:

- No silent partner adds.
- Anyone can leave.
- Private album access is separate.
- Blocks override all match/chat state.

Solo passthrough chat:

- Belongs to the individual user.
- Linked partner is not included by default.
- User may invite partner later if chat permissions allow.

## Privacy Rules

Swipes:

- Individual swipes are private during the session.
- Do not expose swipe-by-swipe history to partner by default.

Session recap:

- Shared recap can show aggregate counts.
- Do not show which partner liked/passed each profile unless both opted into that later.

Solo passthrough:

- Private by default.
- User-controlled sharing only.

Linked partner:

- Psync ends if partner link ends.
- Historical recaps should remain visible only according to retention/privacy rules.

## Session Recap

Use recap, not score.

Show:

- Profiles reviewed
- Both-liked count
- Together matches
- Solo matches for current user
- Session date/time
- Filters used, summarized

Example:

- 25 profiles reviewed
- 6 both liked
- 2 together matches
- 1 solo match

Do not show:

- Psync Score
- Compatibility percentage
- Public "in sync" badge
- Partner performance comparison
- Pressure-inducing language

Session recap should be visible in Activity under Psync.

## Activity Integration

Activity should include:

- Psync invite received
- Psync session started
- Psync together match
- Psync session recap
- Psync session expired

Activity should not expose:

- Individual private swipes
- Solo passthrough matches to partner by default
- Partner's private Activity

Recommended tab/section:

- Activity > Matches or Activity > Psync

## Monetization

Psync should be available enough to make linked-partner value obvious.

Recommended launch model:

Free:

- Limited Psync sessions or limited deck size.
- Basic filters.

Social:

- More/larger Psync sessions.
- Saved Psync filters.
- Linked partner included.

Select:

- Larger decks.
- Advanced Psync filters.
- Travel-mode Psync.
- More recap history.
- Priority/Pineapple Like inventory usable in Psync.

Do not paywall:

- Leaving a Psync session.
- Rejecting a Psync invite.
- Privacy controls.
- Blocking/reporting during Psync.

## Backend Model

Ktor owns Psync state.

Example:

```kotlin
data class PsyncSession(
    val id: String,
    val partnerLinkId: String,
    val initiatorUserId: String,
    val participantUserIds: List<String>,
    val status: PsyncSessionStatus,
    val filters: PsyncFilters,
    val deckSessionId: String,
    val deckSize: Int,
    val createdAt: Instant,
    val startedAt: Instant?,
    val expiresAt: Instant
)

enum class PsyncSessionStatus {
    INVITED,
    CONFIGURING,
    READY,
    ACTIVE,
    PAUSED,
    COMPLETED,
    EXPIRED,
    CANCELLED
}
```

Swipe action:

```kotlin
data class PsyncSwipeAction(
    val sessionId: String,
    val actorUserId: String,
    val targetUserId: String,
    val cardToken: String,
    val action: SwipeActionType,
    val createdAt: Instant
)
```

Recap:

```kotlin
data class PsyncSessionRecap(
    val sessionId: String,
    val profilesReviewed: Int,
    val bothLikedCount: Int,
    val togetherMatchCount: Int,
    val soloMatchCountForViewer: Int,
    val completedAt: Instant
)
```

## API Shape

Recommended endpoints:

```http
POST /v1/psync/sessions
POST /v1/psync/sessions/{id}/accept
POST /v1/psync/sessions/{id}/deny
POST /v1/psync/sessions/{id}/suggest-filters
POST /v1/psync/sessions/{id}/start
POST /v1/psync/sessions/{id}/pause
POST /v1/psync/sessions/{id}/leave
POST /v1/psync/sessions/{id}/actions
GET  /v1/psync/sessions/{id}
GET  /v1/psync/sessions/{id}/recap
```

Rules:

- Server validates active partner link.
- Server validates both users accepted.
- Server validates target eligibility.
- Server enforces deck bounds.
- Server creates matches.
- Server writes Activity events.

## Realtime

Use Ktor WebSockets for:

- Invite received
- Partner accepted/denied
- Filter suggestion
- Session ready
- Partner paused/left
- Deck progress summary
- Session completed

Do not stream partner swipe choices in real time.

Nudge:

- Optional.
- Cooldown required.
- Subtle haptic/pulse only.
- Do not shame lagging partner.

Recommended nudge cooldown:

- 60 seconds

## Edge Cases

| Scenario | Behavior |
|---|---|
| Partner declines invite | Session cancelled; initiator notified neutrally |
| Partner ignores invite | Invite expires |
| Partner goes offline | Session pauses |
| Partner link ends | Active session cancelled |
| One partner blocks target | Target removed/invalidated |
| Target blocks one partner | Target removed/invalidated |
| One partner reports target | Session can continue, target removed |
| Session idle > expiration | Session expires |
| Deck exhausted | Show recap/load-more options |
| Solo passthrough match | Private to matching individual by default |

## Copy Guidelines

Use:

- Start Psync
- Invite partner
- Review together
- Session paused
- Session recap
- Together match
- Solo match
- Leave session

Avoid:

- Couple score
- In sync percentage
- Partner compatibility score
- Couple account
- Shared profile
- Hot together
- Play together now

## Implementation Checklist

- [x] Psync session model exists. (PS-1: `V63` `psync_sessions` + `psync_session_participants`; `com.pynapli.server.psync`.)
- [x] Both partners must consent before start. (PS-1: invite → accept; both `approved` required to reach `ready`; deny/leave → `cancelled`. Ktor-owned status machine.)
- [x] Filters can be suggested before start. (PS-1: `suggest-filters` replaces the agreed filters, resets both approvals except the suggester, status → `configuring`; both must re-approve to return to `ready`.)
- [x] Deck is bounded. (PS-2: `V64` `psync_session_cards`; deck generated once when both partners `start`, oversampled ≤60 then bounded to `deck_size`.)
- [x] Same ordered deck is served to both partners. (PS-2: deck snapshot persisted as JSONB; `getDeck` serves the same ordered cards to both, reissuing deterministic HMAC card tokens.)
- [x] Swipes are private during session. (PS-2: `psync_session_actions`, one decision per actor+target; `getDeck` carries the caller's OWN action only — a partner's choices are never exposed.)
- [x] Together match requires target consent. (PS-3: together match needs both partners' likes + target `discovery_settings.together_enabled` + target reciprocation (liked ≥1 partner); otherwise falls through to per-partner solo eligibility.)
- [x] Solo passthrough is private by default. (PS-3: solo match is the liking partner's standard match; the recap's `soloMatchCount` is always the VIEWER's own. PS-6d: opt-in `discovery_settings.share_psync_solo_matches` lets a partner surface their solo count to the other via `partnerSoloMatchCount` — null unless they opted in.)
- [x] Psync likes reach the target. (On completion each partner's like is also written to `swipe_actions`, tagged `psync_session_id` (V80), and an unanswered one appears in the target's Likes You as an ordinary like from that individual; the target liking back later creates a normal match. Passes are never delivered; a blocked target or a partner's earlier Swipe decision on the target is skipped. Reciprocity uses the Swipe definition of a live like: Pineapple Likes count, rewound likes don't. Not yet: delivery when a session is cancelled or expires, a "liked by a linked pair" presentation for the target, Pineapple Like inventory charging.)
- [x] Session recap exists. (PS-3: `GET .../recap` + `POST .../complete` return aggregate counts — profiles reviewed, both-liked, together matches, viewer's solo matches, dates, filters summary — recap not score.)
- [x] Psync Score does not exist.
- [x] Either partner can pause/leave. (PS-1: `leave` → `cancelled`. PS-2: `pause`/`resume` round-trip `active↔paused`; actions are refused while paused.)
- [x] Partner link ending cancels active session. (PS-6c: `JdbcProfileLinkRepository.unlinkCurrent` atomically cancels any non-terminal `psync_sessions` for the unlinked `partner_link_id` via a data-modifying CTE.)
- [x] Blocks/reports invalidate targets. (PS-2: deck generation drops anyone blocked/hidden by either partner. PS-3: match resolution skips a blocked target. PS-6b: a block applied MID-SESSION removes the target from the live deck for both partners — `getDeck` filters anyone blocked/hidden by either partner — so it can no longer be acted on.)
- [x] Activity integration exists. (PS-4a: resolved together + solo matches replay into the existing match→Activity feed + realtime delta + MATCH push for both parties — a partner's private solo match never surfaces to the other. PS-4b: realtime session channel (`/v1/psync/stream`) + nudge (60s cooldown). PS-4c: dedicated `psync_invite`/`psync_started`/`psync_recap`/`psync_expired` Activity feed cards (idempotent per recipient+session+type, under the Invites tab), wired to create/activate/complete/expiry.)
- [x] Ktor owns session and match state. (Server-authoritative throughout: status machine, deck generation, private actions, and PS-3 match resolution all run server-side under `FOR UPDATE`.)
- [x] WebSocket events do not reveal private swipes. (PS-4b: `/v1/psync/stream` emits session-level convenience deltas only — partner_started/session_active/partner_finished/session_completed/session_paused/session_resumed/nudge — keyed to the recipient; never swipe-by-swipe detail. HTTP fetch stays canonical.)

## Do Not Implement

- Do not create couple cards.
- Do not create shared couple accounts.
- Do not let one partner control the session unilaterally.
- Do not expose live partner swipes.
- Do not make solo passthrough visible by default.
- Do not create Psync Score.
- Do not show public "in sync" badges.
- Do not create unbounded decks.
- Do not assume target consent for together matching.
- Do not silently add linked partner to chats.
- Do not paywall leaving/rejecting/blocking/reporting.
