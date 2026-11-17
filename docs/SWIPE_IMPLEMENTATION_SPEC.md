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

Users cannot freely message from swipe unless there is a mutual match, except for tightly capped premium/a la carte intro notes.

## Non-Negotiable UX Rules

1. Swipe cards represent one autonomous user, not a couple account.
2. Linked partners appear only as a small linked-partner module/avatar.
3. Swipe messaging requires mutual match.
4. Message-before-match is a premium exception, not the norm.
5. Swipe filters are lighter than grid filters.
6. Swipe filters can be flexible unless marked as dealbreakers.
7. Swipe must remember pending actions and handle offline/poor-network states.
8. Likes, passes, rewinds, intro notes, and matches must be server-authoritative.
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
- Verified status is hydrated from `profiles.verified_at IS NOT NULL` (the same signal as the `verifiedOnly` discovery filter) and shown as a small accent check icon next to the displayed user's name in the bottom gradient. Photo and age verification are tracked separately in the schema (`face_photo_verified_at`, `age_verified_at`); surfacing them as distinct badges is deferred until the product needs them.

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

If the displayed user supports together-mode interest, the UI may ask after a Like/Super Like:

- Interested in Alex solo
- Interested in Alex and linked partner

Only show this when relevant. Do not interrupt every swipe.

## Swipe Actions

Before match:

- Like
- Pass
- Super Like / Pineapple Like
- Rewind, if allowed
- Intro Note, if available
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
    SUPER_LIKE,
    INTRO_NOTE,
    REWIND
}

data class SwipeActionRequest(
    val actionId: String,
    val actorUserId: String,
    val targetUserId: String,
    val actionType: SwipeActionType,
    val intentScope: IntentScope?,
    val noteText: String?,
    val deckSessionId: String,
    val cardToken: String
)

enum class IntentScope {
    SOLO,
    TOGETHER,
    SOCIAL,
    ASK_IN_CHAT
}
```

Rules:

- A match is created when compatible Like/Super Like actions are mutual.
- Pass removes the target from the active deck for a cooldown period.
- Rewind reverses only the most recent eligible local/server action.
- Block always overrides match/like state.
- Unmatch removes the active match and conversation access according to safety policy.

## Intro Notes

Being merged into Pineapple Like as an optional note (founder decision 2026-11-17). Until then, and
for the merged note:

- 140 characters max; requires the recipient to be eligible (privacy setting, age/verification).
- Delivery is not guaranteed; it spends a weekly or purchased allowance.
- It is a high-intent like with an attached note; the recipient accepts by liking back or dismisses.

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
    val canReceiveIntroNote: Boolean,
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

- Render only the top card (interactive), the next card (slight scale/offset) and a third (image
  preloaded, minimal composition). Never render the whole deck. Key cards by `cardToken`.
- Top card: horizontal drag with slight vertical tolerance, rotation proportional to drag, like/pass
  label reveal, velocity-aware release, spring back below threshold, fly out beyond threshold or on
  fling. Pineapple Like is a button, not a vertical swipe; vertical gestures stay free for photo
  scroll.
- On wide panes the deck is a centered stage with arrow-key shortcuts (see
  [WEB_PWA_GAMEPLAN.md](WEB_PWA_GAMEPLAN.md)).

## Image Loading

Swipe is photo-heavy, so preloading matters.

Rules:

- Preload all photos for top card.
- Preload first photo for next 2-3 cards.
- Use CDN image sizes appropriate to card dimensions.
- Avoid original images in swipe deck.
- Keep image aspect ratios stable to avoid layout shift.
- Preserve photo carousel index per card while active.

## Match Creation UX

When a mutual match occurs:

- Show match animation.
- Offer Message.
- Offer Keep Swiping.
- If linked-partner context applies, offer Invite Partner later, not immediately unless user intent makes it natural.

Do not automatically add linked partners to match chats.

## Activity Integration

Swipe should feed Activity:

- Someone liked you
- Someone Super/Pineapple Liked you
- Someone sent an Intro Note
- You matched
- Your Boost generated views/likes
- Your Pick expires soon

Activity handles FOMO. Swipe should stay focused on decisions.

Current implementation note:

- Server-authoritative Swipe actions now emit Activity rows for normal Likes, Pineapple Likes, Intro Notes, and mutual matches after action validation/recording succeeds.
- Match Activity is written for both matched users.
- Idempotent Swipe action retries do not create duplicate Activity rows.

## Subscription Gating

Tiers are Free, Social and Select; the per-tier like limits, Rewind, Travel, Likes You reveal,
Pineapple Likes and Boost allowances live in [MONETIZATION_SPEC.md](MONETIZATION_SPEC.md) and
`subscriptions/SubscriptionPolicy.kt`. Swipe never gates block/report, basic intent clarity, or
safety. The free tier is not "ad-supported": the ad slot and "No ads" perk are being removed.

## Safety And Consent

Swipe safety rules:

- Block/report must be available from every card.
- Age verification can gate chat or intro notes.
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
- [x] Like / Pass / Pineapple Like / Intro Note actions; server-authoritative match creation.
- [x] Cursor pagination continues a deck session without duplicates; the client auto-loads the next
  page below a threshold, deduping against pending actions.
- [x] Rewind is server-aware for the latest eligible Like/Pass/Pineapple Like and local queued
  actions. **Pineapple Like inventory is refunded atomically** with the rewind
  (`swipe_actions.consumed_inventory_id`, same transaction).
- [x] Likes You is represented in Activity.
- [x] Top Picks exists (frozen).
- [x] Boost affects ranking without violating filters.
- [x] Travel mode for Swipe and Grid (`travel_locations`, `TravelLocationService`; Select unlimited,
  Social one activation per 30 days); planned trips and "Visiting soon" (`travel_trips`).
- [x] Dealbreakers (`SwipeDealbreakerKey`: `age`, `distance`, `verified`, `discoveryStyles`,
  `intents`, `linkedOnly`) are hard gates and never relaxed; the filters sheet has a per-section lock.
- [x] Never-empty deck: `SwipeDeckRelaxation` drops soft filters and doubles the radius (capped) when
  a deck is below low water; dealbreakers and identity filters (gender/orientation) stay hard.
- [x] Compose stack renders only 2–3 cards; physics-based gestures; image preloading.
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
