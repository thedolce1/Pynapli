# Pynapli Swipe Implementation Spec

## Purpose

This document captures how Pynapli should implement its Tinder-inspired swipe system so it stays intentional, match-gated, monetizable, smooth, and distinct from the grid.

The swipe deck is not the grid. It is not a live nearby directory. It is a curated discovery and matching engine.

Core rule:

> Swipe is for intent. Grid is for now.

Psync, Pynapli's linked-partner synchronized swipe mode, builds on this swipe system. Its session-specific rules are defined in [PSYNC_SPEC.md](PSYNC_SPEC.md).

## Product Contract

Swipe answers:

> Who should I intentionally consider?

The swipe experience should feel:

- Curated
- Smooth
- Match-based
- Slightly scarce
- Game-like but not noisy
- Lower-spam than grid
- More deliberate than browsing

Users cannot freely message from swipe unless there is a mutual match, except for the note a Pineapple Like can carry.

## Non-Negotiable UX Rules

1. Swipe cards represent one autonomous user, not a couple account.
2. Linked partners appear only as a small linked-partner module/avatar.
3. Swipe messaging requires mutual match.
4. Message-before-match is a premium exception, not the norm.
5. Swipe filters are lighter than grid filters.
6. Swipe filters can be flexible unless marked as dealbreakers.
7. Swipe must remember pending actions and handle offline/poor-network states.
8. Likes, passes, rewinds, Pineapple Like notes, and matches must be server-authoritative.
9. Card motion must feel physical and immediate.
10. The swipe deck should never feel like a dense profile questionnaire.

## Swipe Card Design

Swipe cards can show more than grid cards, but should remain restrained.

Show:

- Photo carousel
- Name
- Age
- Distance or city
- Online/recent state, if appropriate
- Linked-partner mini avatar/module, if linked
- One short bio line
- Up to 2-3 visible tags after expansion

Do not show:

- Full preference walls
- Large tag clouds
- Full private album state
- Exhaustive lifestyle checklist
- Compatibility score as a primary element

The card should answer:

1. Am I interested enough to Like, Pass, or Super Like?
2. Are they broadly aligned with my intent?
3. Are they linked, and can I inspect that context?

Current MVP implementation:

- Server-side hydration in `JdbcSwipeRepository` reads `profiles.bio` plus the union of `profiles.lifestyle_tags` and `profiles.logistics_tags`. The profile photo LATERAL subquery now returns both the cover thumbnail and an ordered array of up to 5 ranked profile photo media asset IDs for the carousel.
- `SwipeService.toDto` trims the bio, drops blanks, and caps at 120 characters (one-line "short" sized); tags are case-insensitively deduped, blanks dropped, capped at 3; `photoUrls` is mapped through `MediaAssetUrlResolver` and capped at `MAX_VISIBLE_PHOTOS = 5` (defense in depth alongside the SQL `rn <= 5`).
- The Compose card renders the bio as a single ellipsised line in the bottom gradient and shows up to 3 small pill tag chips below it. The exhaustive lifestyle checklist and large tag clouds called out in "Do not show" are intentionally avoided by the cap.
- The photo region is a `HorizontalPager` with `userScrollEnabled = false` so the pager's own horizontal drag never competes with the like/pass drag. Tap zones on the top card route by x-fraction: tap-left (< 30%) advances to the previous photo if available, tap-right (> 70%) advances to the next, and any other tap opens the profile sheet — matching the standard tap-edges-to-advance pattern. Slim page indicator bars are overlaid at the top of the card. Non-top cards render only the cover image (no pager).
- Image prefetch: the top card preloads up to all 5 carousel photos; the next visible cards in the prefetch window preload only their cover plus the linked-partner thumbnail.
- Verified status is hydrated from `profiles.verified_at IS NOT NULL` (the same signal as the `verifiedOnly` discovery filter) and shown as a small accent check icon next to the displayed user's name in the bottom gradient. `verified_at` and `face_photo_verified_at` are both written when a reviewer approves a selfie photo verification (TRUST_AND_SAFETY_SPEC "Photo Verification"); `age_verified_at` is the separate age check.
- Open: the couple verified flag on Swipe cards. Grid cards and profile detail already carry a server-computed `coupleVerified`; the deck hydration (`JdbcSwipeDeckHydrationSql`) needs `(linked_profile.verified_at IS NOT NULL) AS linked_partner_verified` in its linked-partner lateral, then `coupleVerified = verified && linkedPartnerUserId != null && linkedPartnerVerified` on `SwipeCardDto`.

## Linked Partner Display

Every swipe card represents exactly one user.

If a user is linked:

- Show a small linked-partner avatar/module.
- Tapping it opens a lightweight partner preview.
- The primary swipe action remains on the displayed user.

Partner preview can show:

- Linked with Jamie
- Partner profile photo
- Partner active state, if allowed
- Partner distance/city, if allowed
- View profile

Current MVP implementation:

- Tapping the linked-partner badge on the top swipe card opens a lightweight `LinkedPartnerPreviewDialog` showing "Linked with [partner]", partner photo (or avatar fallback), and a clarifying line that swipe stays on the displayed user.
- Partner active state is hydrated server-side from `PresenceService` (a single presence call covers both target and partner user IDs per page) and shown as a small green dot with "Active now" in the dialog when the partner is online.
- "View profile" routes through the existing `ProfileDetailStateHolder.openLinkedPartner` flow with `ProfileDetailActionMode.Grid`, so the partner is shown without swipe-style action buttons.
- Partner distance/city is not yet surfaced because `SwipeCardRecord` does not carry the partner's coordinates; adding it requires a candidate-SQL change to compute partner-to-viewer distance and a "show partner distance" allow rule.

Do not merge linked users into a couple card.

A Like never asks "solo or with their partner?" (cut 2026-11-18). Couples who want to choose
together use Psync, and each person's discovery style says whether they are looking solo, together
or socially.

## Swipe Actions

Before match:

- Like
- Pass
- Super Like / Pineapple Like, with an optional note
- Rewind, if allowed
- View linked-partner preview
- Report
- Block

After mutual match:

- Chat
- Invite linked partner
- Request/grant private album
- Move to group chat
- Unmatch
- Block/report

## Match Model

Server-authoritative action model:

```kotlin
enum class SwipeActionType {
    LIKE,
    PASS,
    SUPER_LIKE, // optional noteText; Intro Notes merged into it 2026-11-17
    REWIND
}

data class SwipeActionRequest(
    val actionId: String,
    val actorUserId: String,
    val targetUserId: String,
    val actionType: SwipeActionType,
    val noteText: String?,
    val deckSessionId: String,
    val cardToken: String
)
```

Rules:

- A match is created when compatible Like/Super Like actions are mutual.
- Pass removes the target from the active deck for a cooldown period.
- Rewind reverses only the most recent eligible local/server action.
- Block always overrides match/like state.
- Unmatch removes the active match and conversation access according to safety policy.

## Pineapple Like note (Intro Notes merged, shipped 2026-11-17)

There is no separate Intro Note action or product. A Pineapple Like (`action = pineapple_like`)
optionally carries `noteText`:

- Trimmed; blank is no note; 140 characters max (`PineappleLikeNote.MAX_LENGTH`,
  `PINEAPPLE_LIKE_NOTE_TOO_LONG`). A note on any other action is `INVALID_SWIPE_ACTION`, and so is the
  retired `intro_note` action.
- One Pineapple Like unit is spent either way, in the record transaction under the pair lock;
  rewinding refunds it.
- It is a positive swipe: matches in both orders, can be liked back from Activity, counts in Psync.
- The recipient's `Allow notes with Pineapple Likes` toggle off refuses only the note
  (`PINEAPPLE_LIKE_NOTE_NOT_ACCEPTED`, nothing spent). The client reopens the Pineapple Like sheet
  without the note field and offers `Send without note`.
- Client: the Pineapple Like button (and the up-arrow key on wide windows) opens the Pineapple Like
  sheet with an optional note field; sending plays the same swipe-up. The swipe-up gesture sends it
  without a note.
- Legacy `intro_note` rows were converted by V92.

## Super Like / Pineapple Like

Pynapli should have a high-intent Like equivalent.

Possible names:

- Pineapple Like
- Spark
- Super Like

Behavior:

- Prioritizes the sender in recipient's swipe/activity queue.
- May show a distinct visual treatment.
- May allow an attached note for premium users.
- Can be included weekly in higher tiers.
- Can be sold in packs.
- Expires if granted as subscription inventory and unused.

Do not make Super Likes unlimited. Scarcity is what gives them meaning.

## Rewind

Rewind should undo the most recent eligible swipe decision.

Rules:

- Paid feature for Social and Select.
- Can undo Like, Pass, or Super Like when technically safe.
- Cannot undo after a block/report.
- Cannot undo if the target has already matched and entered a resolved state where reversal would be confusing.
- Must be server-aware, not only local UI rollback.

Current MVP implementation:

- Social and Select users can server-rewind the latest eligible Like or Pass.
- The server soft-rewinds the previous action, keeps history, and issues a fresh deck session/card token for the restored card.
- Local queued swipes can be undone before sync.
- Rewinding a Pineapple Like refunds the exact inventory row it consumed, in the same transaction.

## Likes You

Likes You is an Activity/FOMO surface, but it affects swipe.

Behavior:

- Shows users who liked the current user before the current user swipes on them.
- Free users can see counts and blurred/limited previews.
- Premium users can open and act on all Likes You profiles.
- Swiping right on a Likes You profile creates an instant match if still valid.

Pynapli should place Likes You primarily in Activity, with entry points from Swipe.

## Top Picks / Curated Picks

**Frozen** (2026-11-17): not part of the product definition. The code (`GET /v1/swipe/picks`,
`top_pick_sessions`, one free Pick per day, a daily set for paid tiers, 24h refresh) stays but gets no
further investment, and it should not be advertised as a paid perk.

## Boost / Super Boost

Swipe Boost is visibility acceleration.

Boost:

- Places user higher in local swipe queues for a limited time.
- Recommended duration: 30 minutes.
- Should be available as a la carte.
- Can be included monthly in higher subscriptions.

Super Boost:

- Stronger boost during high-activity windows.
- Should be limited to subscribers or high-trust users.
- Should be priced and capped carefully.

Rules:

- Boost must respect blocks, privacy, age, safety, and recipient filters.
- Boost should not show users to people who are incompatible by hard/dealbreaker filters.
- Boost should increase impressions, not guarantee matches.
- Boost results can be reported with honest metrics: profile views, likes, matches.

Current implementation (Swipe):

- A boosted person who passes every filter, block, hidden-profile, risk, visibility and privacy
  rule, has not already been swiped by the viewer, has not been dealt earlier in the deck session,
  and is inside the viewer's radius step is brought into the candidate set however far down the
  ordinary order they sit (the rank query returns its window plus the best boosted candidates in
  the whole step), and is placed near the top of the page.
- Cap: while any unboosted card is left, boosted cards go only in the first card of every four
  (positions 0, 4, 8, ...), so a page holds at most one boosted card in four whether the Boost lifted
  them or they were near anyway (`SwipeDeckBoostPlacement`). Exception: once no unboosted card is
  left, the remaining boosted ones follow rather than leaving the deck short.
- Blocked people are excluded before ranking, so a blocked boosted person never takes a slot.
- One active Boost per person (V101 partial unique index behind the activation lock; activation
  expires the person's lapsed Boosts in the same transaction).

## Passport / Travel Mode

Pynapli should include a travel discovery mode.

Behavior:

- User can select one virtual city/location at a time.
- Swipe deck uses that city context.
- Likes sent from travel mode can remain deliverable for a limited window after leaving that city.
- Users should be visibly marked as traveling or "visiting" where appropriate.
- Some regions may require additional verification before using travel mode.

Recommended TTL:

- Likes sent while traveling remain visible for up to 24 hours after switching location, mirroring Tinder's public Passport behavior.

## Discovery Settings

Swipe filters should be lighter than grid filters.

Base settings:

- Age range
- Distance
- Gender/orientation preferences
- Singles / linked people / everyone
- Dating solo / together / social
- Verified only
- Has photos
- Travel location

### Dealbreakers

Borrow Tinder's dealbreaker pattern.

Each major preference can be:

- Flexible
- Dealbreaker

Recommended dealbreakers:

- Age
- Distance
- Verified status
- Looking solo/together
- Open to singles
- Open to linked partners
- Social/events only

If flexible, the deck can expand slightly when recommendations are low. If dealbreaker, the deck must respect it.

## Recommendation Pipeline

Swipe recommendations should be a ranked deck, not pure distance order.

Pipeline:

```text
authenticated user
-> privacy/block/safety exclusions
-> discovery visibility settings
-> hard dealbreakers
-> candidate generation
-> ranking
-> diversity controls
-> deck session
-> card DTOs
```

Candidate generation inputs:

- Location radius or Passport location
- Age
- orientation/gender preferences
- solo/together/social intent
- linked-partner context
- verified status
- activity recency
- prior actions/cooldowns
- blocks/reports/hides

Ranking inputs:

- Mutual intent
- Distance
- Activity/recency
- Profile quality
- Verification
- Response likelihood
- Like-back likelihood
- Shared interests/events
- Good standing
- Linked-partner clarity
- Boost/Pineapple Like priority

Ranking should avoid:

- Infinite repetition of the same profile
- Showing passed users too soon
- Over-delivering boosted profiles at the expense of relevance
- Punishing free users so hard the deck feels dead
- Rewarding spammy behavior

## Default Deck / Never-Empty

A fresh deck never feels dead because filters were left at defaults or the area is sparse:

1. **Default = all nearby, ranked.** With no filters, only non-negotiable exclusions apply
   (privacy/block/safety, visibility, prior-action cooldowns). Empty filter sets add no predicate.
2. **Relaxation when the pool runs low** (`SwipeDeckRelaxation`). Below the low-water mark
   (`(limit/3).coerceAtLeast(3)`) the server regenerates with soft filters dropped and the radius
   doubled (capped; not if `distance` is a dealbreaker).

Relaxation never touches dealbreakers or identity filters (gender, orientation): a relaxed deck must
never show people the viewer explicitly excluded. A deck that stays small after relaxation shows the
empty-state copy rather than a silent blank. This mirrors the Grid's widen-on-empty fallback.

## Deck Session

Swipe should operate on a server-generated deck session.

Example:

```kotlin
data class SwipeDeckResponse(
    val deckSessionId: String,
    val cards: List<SwipeCardDto>,
    val nextCursor: String?,
    val generatedAt: Instant
)

data class SwipeCardDto(
    val cardToken: String,
    val userId: String,
    val displayName: String,
    val age: Int,
    val distanceLabel: String?,
    val photoUrls: List<String>,
    val linkedPartner: LinkedPartnerPreviewDto?,
    val shortBio: String?,
    val tags: List<String>,
    val verified: Boolean,
    val online: Boolean
)
```

The MVP card DTO already carries `online`, populated from the presence service alongside the rest of the deck mapping; the Compose card surfaces it as a small dot next to the displayed name (matching the Grid card's visual language).

Why deck sessions matter:

- Prevent duplicate cards.
- Support rewind.
- Support offline action queueing.
- Give backend fraud/rate-limit context.
- Make recommendations reproducible enough for debugging.

## Action Queue And Offline Handling

Swipe actions should feel instant even on poor networks.

Client behavior:

- Optimistically animate card away.
- Queue action locally.
- Send action to server with a stable client-generated action ID.
- Resolve match state when server responds.
- If failure is recoverable, retry.
- If action is rejected, show subtle correction and restore or skip as appropriate.

Rules:

- Server remains authoritative.
- Action IDs are idempotency keys; retrying the same action ID with the same payload must not consume inventory or create duplicate side effects.
- Reusing an action ID with different target/action/note payload must be rejected.
- Rewind must account for queued actions.
- Do not allow unlimited offline swiping without server sync.
- Cap local unsynced actions.

Current MVP implementation:

- Pending swipe actions use stable action IDs and a 10-action cap.
- Android persists pending actions through SQLDelight, scoped by viewer user ID.
- Restored pending targets are filtered out of a freshly loaded deck until Ktor accepts or rejects the queued action.
- Cross-platform store wiring for iOS and process-death device QA remain open.

## Compose Swipe Implementation

`SwipeStateHolder` exposes `StateFlow` deck state; `SwipeCardStack` uses `pointerInput` drag detection
and `Animatable` offset/rotation (custom gestures, not `anchoredDraggable`).

- Render only the top card (interactive), the next card (scales and fades up with the top card's
  drag progress) and a third (image preloaded). Never render the whole deck. Key cards by person
  (`userId`) so a promoted card keeps its state and loaded photo.
- Top card (`SwipeCard`, `SwipePhysics`): follows the finger on both axes; rotation is proportional
  to horizontal drag around a pivot set by where it was grabbed (top half pivots at the bottom,
  bottom half at the top, so they turn opposite ways). Release: a fast short flick commits, a slow
  drag past the threshold (28% width, 18% height up) commits, anything else settles on a spring. A
  committed card exits along its fling direction at its fling speed (never against the decision).
  LIKE / NOPE / PINEAPPLE stamps fade in with the drag. Buttons and arrow keys play the same exit;
  Rewind brings a card back in from the side it left (`SwipeExitMemory`). Per-frame values are read
  only in `graphicsLayer`.
- Swiping up past the threshold opens the Pineapple Like sheet (optional note; spends one only on
  Send), whose Send plays the upward exit. It never spends inventory on a gesture alone.
- Haptics (`PynapliHaptics`, expect/actual): a light tick when the drag crosses a threshold in
  either direction, a firmer one on commit, a distinct pattern on a match. Android View haptics
  (follows the system touch-feedback setting), `navigator.vibrate` on the web where present, Compose
  haptics on iOS, none on the desktop dev shell.
- Photos: tap the left / right half to step through photos instantly (segmented bars at the top);
  tap the bottom info area or the expand button to open the shared profile sheet. Tags show only in
  the expanded profile.
- Keyboard on wide panes: left pass, right like, up Pineapple Like, Space / Enter open the profile.
- Empty deck offers "Widen filters" and "Try Travel mode"; offline says saved swipes send on
  reconnect; the first load is a card-shaped placeholder.
- On wide panes the deck is a centered stage with arrow-key shortcuts (see
  [WEB_PWA_GAMEPLAN.md](WEB_PWA_GAMEPLAN.md)).

## Image Loading

Swipe is photo-heavy, so preloading matters.

Rules:

- Preload all photos for the top two cards.
- Preload the first photo for the rest of the next few cards.
- Use CDN image sizes appropriate to card dimensions.
- Avoid original images in swipe deck.
- Keep image aspect ratios stable to avoid layout shift.
- Preserve photo carousel index per card while active.

## Match Creation UX

When a mutual match occurs:

- Both people get the full-screen "It's a match" moment (`MatchCelebrationScreen`, app-wide, not a
  sheet): both photos, gold title, "Send a message", "Keep swiping", success haptic; it swallows
  input so the deck underneath cannot move; Esc / back close it. Sheets render in their own window,
  so it waits (unseen, no haptic) while the chat sheet or a profile peek is open, and the wide-pane
  Swipe stage takes keyboard focus back when it closes.
- Exactly once per person per match across devices: the server records who has seen it
  (`match_celebrations`, V108; existing matches backfilled as seen). The swiper gets it from the
  swipe response; the other person from `GET /v1/matches/celebrations` on sign-in, on foreground,
  on the activity socket's `match` delta and after a reconnect. The client marks it seen with the
  idempotent `POST /v1/matches/celebrations/seen` once it is on screen.
- A Psync together match (two canonical matches for the target) is one moment with the pair
  presentation ("Maya & Alex"). Each partner's own moment is individual.
- Match alerts off: the moment is marked seen without showing. Discreet notification detail keeps
  names off it. The in-app activity banner does not repeat a match.
- If linked-partner context applies, offer Invite Partner later, not immediately unless user intent makes it natural.

Do not automatically add linked partners to match chats.

## Activity Integration

Swipe should feed Activity:

- Someone liked you
- Someone Super/Pineapple Liked you
- Someone sent a Pineapple Like with a note (the note shows on the like)
- You matched
- Your Boost generated views/likes
- Your Pick expires soon

Activity handles FOMO. Swipe should stay focused on decisions.

Current implementation note:

- Server-authoritative Swipe actions now emit Activity rows for normal Likes, Pineapple Likes (with their optional note), and mutual matches after action validation/recording succeeds.
- Match Activity is written for both matched users.
- Idempotent Swipe action retries do not create duplicate Activity rows.
- These Activity rows, the match conversation and the match push are crash-safe: see "Match side
  effects".

## Match side effects

A Swipe action, a like-back from Activity or a Psync completion that records a like or makes a
match owes follow-up work: the like in the target's Likes You, the match Activity row for both
people, the actor's match conversation in Messages, and the match push to the target.

- That work is written to `match_side_effect_outbox` (V100) in the SAME transaction as the like or
  match, so a crash between the commit and the work can never lose it.
- Fast path: the request delivers the rows straight after commit and marks them delivered; users
  see no added latency. A failure there is logged and left in the outbox; it never fails the
  request.
- Each new row is leased to the committing request for 60 seconds. `MatchSideEffectDrainWorker`
  (every 30 s, one replica at a time under `PostgresJobLock`) delivers rows whose lease has passed,
  claiming them with a lease of its own, backing off exponentially on failure (30 s doubling to an
  hour) and giving up after 12 attempts (the row stays with its error class for an operator).
  Delivered rows are pruned after 7 days.
- Every delivery step is idempotent (Activity rows are unique per pair and type, the conversation
  is get-or-create, the push carries an idempotency key), so the fast path and the drainer never
  duplicate. An unmatch deletes the match and any undelivered announcement with it.
- Opening the match conversation now reports failures (it used to swallow them) so the outbox can
  retry it. The push is still best effort: the notification service logs and drops its own write
  errors, so the outbox cannot retry a lost push.
- Proofs: `MatchSideEffectOutboxIntegrationTest` (crash before delivery, drainer delivers exactly one
  of each; fast path plus drainer never duplicates; like-back), and the Psync crash case in
  `PsyncSessionServiceIntegrationTest`.

## Subscription Gating

Tiers are Free, Social and Select; the per-tier like limits, Rewind, Travel, Likes You reveal,
Pineapple Likes and Boost allowances live in [MONETIZATION_SPEC.md](MONETIZATION_SPEC.md) and
`subscriptions/SubscriptionPolicy.kt`. Swipe never gates block/report, basic intent clarity, or
safety. The free tier is not "ad-supported": the ad slot and "No ads" perk are being removed.

## Safety And Consent

Swipe safety rules:

- Block/report must be available from every card.
- Age verification can gate chat or Pineapple Like notes.
- Verified-only chat mode should be supported.
- Contact blocking should be considered for launch or early roadmap.
- Discovery off should stop showing the profile to new swipe users while preserving existing matches/chats.
- Users should control whether they appear in swipe, grid, or both.

Pynapli-specific:

- Users should choose whether they are discoverable for solo, together, or social intent.
- Linked partner visibility is user-controlled.
- Partner chat invite always requires explicit acceptance.
- Private album access is never implied by a swipe match.

## Performance Targets

Initial deck:

- First card visible quickly after app shell.
- Fetch 10-20 cards per deck page.
- Render only 2-3 cards.
- Preload images for next cards.

Gesture:

- 60fps target on modern devices.
- No network call should block card fly-out animation.
- Action submit happens after or parallel to animation.

Network:

- Optimistic action queue.
- Server authoritative reconciliation.
- Cap unsynced actions.

Load proof: `scripts/swipe-api-load-test.ps1` (run history in git).

## Implementation Checklist

- [x] Swipe card DTO; signed persisted deck sessions and card tokens; the action endpoint validates
  dealt-card state.
- [x] Like / Pass / Pineapple Like (optional note; Intro Notes merged in, V92) actions; server-authoritative match creation.
- [x] Cursor pagination continues a deck session without duplicates; the client auto-loads the next
  page below a threshold, deduping against pending actions.
- [x] Rewind is server-aware for the latest eligible Like/Pass/Pineapple Like and local queued
  actions. **Pineapple Like inventory is refunded atomically** with the rewind
  (`swipe_actions.consumed_inventory_id`, same transaction).
- [x] Likes You is represented in Activity.
- [x] Top Picks exists (frozen).
- [x] Boost is a real lift in Swipe without violating filters: eligible boosted people inside the
  radius step are pulled onto the page, at most one boosted card in every four (see "Boost / Super
  Boost"; `SwipeBoostDeckTest`, `SwipeDeckBoostPlacementTest`).
- [x] Match side effects are crash-safe (outbox V100 + drainer; see "Match side effects").
- [x] A rewind racing the other person's like (from Swipe or like-back) ends as either a match with
  the like live or a rewound like with no match, never both (`SwipeConcurrencyIntegrationTest`).
- [x] Deck order is coarse (L-08): soft score, a whole-km cell-to-cell bucket (distance-independent
  for people who hide distance), then a stable tiebreak seeded by the viewer's cell; ranking starts
  at the viewer's cell centre and card distances are cell to cell. A traveller is never ranked by
  their home GPS.
- [x] Deck location privacy matches the Grid (L-09/L-10/L-14): the requested (and relaxed) radius
  is rounded up to the 2/5/10/25/50/100 km steps; membership is the cell-to-cell distance of the
  candidate's effective location (travel point when travelling) from the viewer's effective cell,
  after a GiST `ST_DWithin` pre-filter padded by the cell slack, so a traveller is matched only
  where they travel; the rank cache is keyed by the viewer's cell. The action-eligibility check and
  rewound cards use the same cell membership and cell-to-cell distance. Proofs:
  `SwipeLocationPrivacyTest` (two hidden users 300 m apart indistinguishable, 30 m attacker steps
  change nothing, a requested radius returns exactly its step, a traveller's home never leaks,
  paging cached and uncached).
- [x] Travel mode for Swipe and Grid (`travel_locations`, `TravelLocationService`; Select unlimited,
  Social one activation per 30 days); planned trips and "Visiting soon" (`travel_trips`).
- [x] Dealbreakers (`SwipeDealbreakerKey`: `age`, `distance`, `verified`, `discoveryStyles`,
  `intents`, `linkedOnly`, `swap`, `room`, `experience`, `boundaries`) are hard gates and never
  relaxed; the filters sheet has a per-section lock. The locks are part of the deck scope hash, so a
  paging cursor cannot cross a change of locks.
- [x] Never-empty deck: `SwipeDeckRelaxation` drops soft filters and doubles the radius (capped) when
  a deck is below low water (the doubled radius is rounded up to its step); dealbreakers and identity
  filters (gender/orientation) stay hard.
- [x] Compose stack renders only 2–3 cards; physics-based gestures; image preloading.
- [x] Tinder-grade card feel: grab-point pivot, velocity fling, fling-speed exit, next card rising,
  LIKE / NOPE / PINEAPPLE stamps, button exits, directional Rewind, threshold/commit/match haptics
  (SwipePhysicsTest, SwipeCardGestureTest). Real-device feel QA still owed.
- [x] Full-screen match moment for both people, once per person across devices (V108,
  MatchCelebrationIntegrationTest, MatchCelebrationStateHolderTest).
- [x] Swipe cards carry coupleVerified (both partners verified, partner visible), like the Grid.
- [x] Empty / offline / loading deck states with next actions (widen filters, Travel mode).
- [x] API load proof (`scripts/swipe-api-load-test.ps1`, 5,000 candidates, 100- and 500-concurrent).
- [x] Connected Android visual/gesture QA (`scripts/android-swipe-qa.ps1`) and queue QA
  (`scripts/android-swipe-queue-qa.ps1`).
- [ ] Partial: offline action queue with a cap, stable action ids and SQLDelight persistence on
  Android and iOS (`SqlDelightSwipeActionQueueStore`); iPhone process-death QA needs a Mac.
- [x] Block/report from the card; linked-partner preview never becomes a couple card.
- [ ] Optional map-pin destination picker for Travel.

## Do Not Implement

- Do not allow normal free messaging from swipe before match.
- Do not make swipe filters as dense as grid filters.
- Do not show couple cards as primary swipe cards.
- Do not auto-invite linked partners into match chats.
- Do not make Super Likes unlimited.
- Do not let Boost bypass blocks, safety, age, privacy, or dealbreakers.
- Do not render the entire deck in Compose.
- Do not block swipe animation on network latency.
- Do not lose deck state on rotation/navigation.
- Do not let offline action queues grow without limit.
- Do not show users outside dealbreakers just to fill the deck.
