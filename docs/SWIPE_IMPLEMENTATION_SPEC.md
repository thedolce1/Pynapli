# Pynapli Swipe Implementation Spec

Last updated: 2026-05-18 (Swipe API load proof extended for Boost, Top Picks, Travel, 500-concurrent core runs, Android visual/gesture QA, and Android queue QA)

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

## Public Research Signals

Tinder publicly documents the following mechanics:

- Swipe Right means Like; if the other person Likes back, it is a Match.
- Discovery settings include sexual orientation, age, and distance.
- Tinder may show profiles outside age/distance preferences when recommendations run low unless those preferences are set as Dealbreakers.
- Discovery can be turned off while existing matches remain available.
- Plus includes unlimited Likes, unlimited Rewinds, Passport, hide ads, and Incognito.
- Gold adds See Who Likes You, daily Top Picks, weekly Super Likes, and sometimes a monthly Boost depending on plan/testing.
- Platinum adds Message before Matching, Prioritized Likes, and viewing Likes sent in the last 7 days.
- Super Like prioritizes the sender's profile to the recipient.
- First Impressions allow a short message before match, capped at 140 characters, and do not guarantee delivery.
- Top Picks refresh every 24 hours; extra purchased Picks also expire within the refresh window.
- Boost makes a user one of the top profiles in their area for 30 minutes, with up to 10x more views.
- Super Boost is tested for subscribers and can provide up to 100x more views.
- Passport lets users virtually discover in one selected city at a time; people liked there may still see the profile up to 24 hours after location changes.
- Likes You lets Gold/Platinum users see who liked them before swiping.
- Photo Verified Chat lets verified users receive messages only from other verified users.

Sources:

- Tinder Subscription Tiers: https://tinder.com/feature/subscription-tiers/
- Tinder Discovery Settings: https://www.help.tinder.com/hc/en-us/articles/115003340963-Discovery-Settings
- Tinder Super Like: https://www.help.tinder.com/hc/en-us/articles/115004493543-Super-Like
- Tinder First Impressions: https://www.help.tinder.com/hc/en-us/articles/360046358932-Message-Before-Matching-with-Super-Like
- Tinder Platinum: https://tinder.com/feature/platinum/
- Tinder Top Picks: https://www.help.tinder.com/hc/en-us/articles/360005039092-Top-Picks
- Tinder Boost: https://www.help.tinder.com/hc/pt/articles/115004506186-Boost
- Tinder Super Boost: https://www.help.tinder.com/hc/en-us/articles/360029087891-Super-Boost
- Tinder Likes You: https://www.help.tinder.com/hc/ja/articles/115005246123-Likes-You
- Tinder Passport: https://www.help.tinder.com/hc/en-us/articles/115004490423-Passport-Mode
- Tinder Photo Verified Chat: https://www.help.tinder.com/hc/en-us/articles/4408385774989-Photo-Verified-Chat

Tinder does not publicly document its exact recommendation/ranking algorithm. Any ranking details below are Pynapli architecture decisions, not claims about Tinder internals.

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

Pynapli's Tinder-style message-before-match feature should be tightly controlled.

Possible names:

- First Note
- Intro Note
- Pineapple Note

Rules:

- 140 character max.
- Requires target user to be eligible to receive intro notes.
- Cannot be sent to users who fail age/verification requirements.
- Does not guarantee delivery.
- Should count against a weekly/purchased allowance.
- Should create a Like/Super Like style action with attached note.
- Recipient can accept by liking/matching or dismiss.

This preserves swipe's social contract while allowing monetized high-intent exceptions.

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
- Pineapple Like rewind remains intentionally incomplete until inventory refund semantics are defined.

## Likes You

Likes You is an Activity/FOMO surface, but it affects swipe.

Behavior:

- Shows users who liked the current user before the current user swipes on them.
- Free users can see counts and blurred/limited previews.
- Premium users can open and act on all Likes You profiles.
- Swiping right on a Likes You profile creates an instant match if still valid.

Pynapli should place Likes You primarily in Activity, with entry points from Swipe.

## Top Picks / Curated Picks

Pynapli should use a daily curated set similar to Tinder's Top Picks, adapted for lifestyle intent.

Possible names:

- Picks
- Top Picks
- Tonight's Picks
- Lifestyle Picks

Rules:

- All users receive one free Pick per day.
- Premium users receive a daily set.
- Picks refresh every 24 hours.
- Purchased extra Picks expire at refresh.
- Picks can be scoped by Passport/Travel location.
- Picks should be high quality: verified, active, aligned intent, good standing.

Picks should not be a replacement for swipe. They are a premium shortcut into the swipe engine.

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

Recommended Kotlin client stack:

- Compose Multiplatform
- Pointer input / drag gestures
- `Animatable` for offset/rotation
- `StateFlow` for deck state
- `SharedFlow` for one-off events
- Ktor Client
- Coil 3 or Kamel for image loading

Official Compose docs support low-level drag detection using `pointerInput` and `detectDragGestures`; newer anchored swipe components use `anchoredDraggable`, while custom Tinder-like cards often need lower-level gesture control.

Sources:

- Compose drag/swipe/fling: https://developer.android.com/develop/ui/compose/touch-input/pointer-input/drag-swipe-fling
- Compose pointer input: https://developer.android.com/jetpack/compose/gestures
- StateFlow/SharedFlow: https://developer.android.com/kotlin/flow/stateflow-and-sharedflow

### Swipe Card Stack

Render only a small stack:

- Active top card
- Next card
- Third card for depth/preload

Do not render the entire deck.

Recommended stack:

```text
top card: fully interactive
second card: slight scale/offset
third card: preloaded image, minimal composition
```

### Gesture Behavior

Top card should support:

- Horizontal drag
- Slight vertical tolerance
- Rotation proportional to drag distance
- Like/pass label reveal
- Velocity-aware release
- Spring back if below threshold
- Fly out if beyond threshold or fling velocity

Thresholds:

- Like: drag right beyond threshold
- Pass: drag left beyond threshold
- Super Like/Pineapple Like: button action, not necessarily vertical swipe

Keep vertical gestures available for photo/profile scroll if the card expands.

### Pseudocode

```kotlin
@Composable
fun SwipeDeck(
    state: SwipeDeckState,
    onAction: (SwipeAction) -> Unit
) {
    val visibleCards = state.cards.take(3)

    Box(Modifier.fillMaxSize()) {
        visibleCards.asReversed().forEachIndexed { index, card ->
            val isTop = index == visibleCards.lastIndex
            SwipeCardLayer(
                card = card,
                interactive = isTop,
                onAction = onAction
            )
        }
    }
}
```

Use stable `cardToken` keys for each visible card.

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

Free:

- Limited likes
- Limited rewinds or no rewind
- One free daily Pick
- Basic filters
- Swipe match chat

Plus:

- Unlimited likes
- Rewind
- Passport/travel mode
- Hide ads
- Incognito
- Expanded filters

Premium:

- See who liked you
- Daily curated Picks
- Weekly Pineapple Likes
- Monthly Boost
- Intro Notes
- Priority Likes
- Sent Likes history
- Linked partner included

A la carte:

- Pineapple Likes
- Intro Notes
- Boost
- Super Boost
- Extra Picks

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

Current API proof:

- `scripts/swipe-api-load-test.ps1` exercises real Ktor endpoints over a 5,000-candidate dense dataset with mixed Free/Social/Select viewers.
- 2026-05-18 extended run passed 100 concurrent users with Boost, Top Picks, Travel activation, Travel deck, filtered deck, relaxed deck, page two, Like/Pass, Rewind, and presence inside local p95 budgets: `artifacts/swipe-api-load/20260518-210752`.
- 2026-05-18 500-concurrent warm core run passed local p95 budgets: `artifacts/swipe-api-load/20260518-210836`.
- 2026-05-18 Ktor-restarted true-cold 500-concurrent core run passed local p95 budgets: `artifacts/swipe-api-load/20260518-211103`.
- 2026-05-18 connected Android visual/gesture/golden QA passed on emulator: `artifacts/android-swipe-qa/20260518-214507`.
- 2026-05-18 connected Android queue QA passed on emulator: `artifacts/android-swipe-queue-qa/20260518-212037`.

## Implementation Checklist

- [x] Swipe card DTO exists.
- [x] Deck query endpoint returns signed persisted deck sessions/card tokens and action endpoint validates dealt-card state.
- [x] Like/pass/Pineapple Like/Intro Note action endpoint exists.
- [x] Match creation is server-authoritative.
- [x] Cursor pagination continues a persisted deck session without duplicate returned cards. Client auto-loads the next page when the visible stack drops below the threshold, deduping against current cards and pending action targets.
- [x] Rewind is server-aware for the latest eligible Like/Pass/Pineapple Like and local queued actions. Pineapple Like inventory is refunded atomically with the rewind: `swipe_actions.consumed_inventory_id` (V48) records the `premium_inventory.id` decremented when the action was submitted, and `JdbcSwipeRepository.rewindSwipeAction` increments that exact row's quantity in the same transaction as the `rewound_at` update. Coverage in `SwipeServiceTest.paidRewindOfPineappleLikeRefundsConsumedInventoryRow` and `GridAndSwipeRepositoryIntegrationTest` "swipe rewind atomically refunds the consumed Pineapple Like inventory row".
- [x] Intro Note inventory and delivery rules exist.
- [x] Likes You is represented in Activity.
- [x] Top Picks/Picks daily refresh exists. `top_pick_sessions` (V49) stores one curated set per viewer per 24h window; subsequent reads inside the window return the same `sessionId` and same raw card tokens (deterministically reissued via `SwipeDeckTokenCodec`). Generation runs through the existing swipe ranker with `verifiedPreferred=true` for the quality bias (spec L311). Free tier gets 1 pick/day, paid tier gets 10. A backing `swipe_deck_sessions` row is upserted alongside the Pick session so the existing `submitAction` flow accepts Pick targets without changes — Picks are a shortcut into the swipe engine per spec L313. Client `TopPicksRow` renders a horizontal LazyRow above the deck; tap opens profile detail. Endpoint `GET /v1/swipe/picks`. Coverage in `TopPicksServiceTest` (tier limits, session reuse, refresh after expiry, LOCATION_REQUIRED, verifiedPreferred bias) and `GridAndSwipeRepositoryIntegrationTest` "top picks generates and reuses a daily session backed by a real deck session".
- [x] Boost affects swipe ranking without violating filters.
- [ ] Partial: Passport/travel mode core shipped 2026-05-17. V50 `travel_locations` table per DATA_MODEL_SPEC L286-301 (one active row per user enforced by partial unique index on `user_id WHERE status='active' AND active_until IS NULL`; cleared rows retained for the 24h Like tail rule a later slice will read). `TravelLocationService` (Select unlimited; Social preview one activation per 30 days), endpoints `GET/POST/DELETE /v1/location/travel` per API_CONTRACT L163-169. Discovery override: `JdbcGridRepository.queryNearby` and all four `viewer_location` CTEs in `JdbcSwipeRepository` (deck rank, action precondition, target eligibility, rewind candidate) LEFT-JOIN `travel_locations` and COALESCE `tl.geom` over `ul.geom` so an active travel row redirects every discovery query. Top Picks inherits via `queryDeck`. Constraint: travel requires the viewer to also have a base `user_locations` row (the LEFT JOIN keeps the single-? bind ergonomics; realistic for Select-tier users). Client `TravelLocationStateHolder` + `TravelModeSheet` with a hardcoded 10-city preset list (NYC, LA, SF, Miami, Toronto, London, Berlin, Paris, Tokyo, Sydney) and an entry chip in `SwipeHeader`. Coverage in `TravelLocationServiceTest` (entitlement gates, validation, supersede-on-set, clear semantics) and `GridAndSwipeRepositoryIntegrationTest` "grid and swipe discovery follows active travel_locations row when present" (viewer in SF physical, travel to NYC, grid+swipe return NYC profiles only). 24h post-leave Like persistence (L347, L353) shipped 2026-05-17: V51 adds `swipe_actions.travel_location_id UUID NULL REFERENCES travel_locations(id) ON DELETE SET NULL` (mirroring the `consumed_inventory_id` pattern from the Pineapple Like rewind slice). `SwipeService.act` looks up the actor's active travel row before recording, threads the id into `recordSwipeAction`. The `targetLikedActor` match query in `JdbcSwipeRepository.recordSwipeAction` LEFT JOINs `travel_locations` and enforces: `sa.travel_location_id IS NULL OR tl.active_until IS NULL OR tl.active_until > now() - INTERVAL '24 hours'`. Coverage in `GridAndSwipeRepositoryIntegrationTest` "travel-mode Like still creates a match when reciprocated within the 24h tail" and "...becomes inert when reciprocated outside the 24h tail" (cleared 36h ago → no match). The same LEFT JOIN + predicate should apply to the future Likes You activity query once that lands. Target-side travel override + visiting badge (L344-348) shipped 2026-05-17: `JdbcSwipeRepository.queryDeckRank` now LEFT JOINs `travel_locations` as `target_travel` and broadens the spatial filter to `ST_DWithin(v.geom, ul.geom, ?) OR (target_travel.geom IS NOT NULL AND ST_DWithin(v.geom, target_travel.geom, ?))`, so targets in travel mode appear in destination decks the same way they appear in their own home city. Distance display and KNN ordering use `COALESCE(target_travel.geom, ul.geom)` so the viewer sees the target where they look. `SwipeCardRecord.visiting` / `SwipeCardDto.visiting` reflects `target_travel.id IS NOT NULL`. Client renders a small "Visiting" pill next to the verified badge on the main swipe card and a "Visiting" caption next to the distance on Top Picks thumbs. Coverage in `GridAndSwipeRepositoryIntegrationTest` "deck cards expose visiting flag when target has active travel_locations row" (viewer in NYC, target physically in SF with active travel to NYC, deck returns target with `visiting=true`). The KNN index optimization on the ORDER BY is traded for an in-memory sort over the (already-filtered) candidate set; both ST_DWithin halves remain index-driven. Grid target-side override shipped 2026-05-17: `JdbcGridRepository.queryNearby` mirrors the Swipe deck — same LEFT JOIN to `target_travel`, same OR'd `ST_DWithin` (both halves index-eligible), `ST_Distance` and `distance_bucket_meters` use `COALESCE(target_travel.geom, ul.geom)`, `visiting` propagates through `base_candidates` → `paged_candidates` → final SELECT. `GridCardRecord.visiting` / `GridCardDto.visiting` → `GridProfileCardUi.visiting` → small "Visiting" pill on Grid card overlay (same wording + tone as Swipe). Coverage in `GridAndSwipeRepositoryIntegrationTest` "grid cards expose visiting flag and surface travel-mode targets in destination grids". Region verification gate (L349) mechanism shipped 2026-05-17: `TravelRegionPolicy` `fun interface` returns a `TravelRegionRequirement` (None | AgeVerified) per destination. `TravelLocationService.set` consults the policy after the entitlement check and, when AgeVerified is required, calls `GridViewerTrustRepository.isAgeVerified(viewer)` (reusing the same trust check as `SavedGridFilterService`); throws the existing `AGE_VERIFICATION_REQUIRED` code so the client routes to the existing verification UI. Production default is `EmptyTravelRegionPolicy` — no destination requires extra verification — so the gate ships as mechanism only; ops swaps the policy via DI when the regional safety review is in place, avoiding political/regional safety calls baked into source. Coverage in `TravelLocationServiceTest` (gated-region unverified → error, gated-region verified → success, unflagged region unverified → success). Social-tier basic preview (MONETIZATION_SPEC L186) shipped 2026-05-17: `TravelLocationService.requireTravelTier` now allows Social with a rate limit (`SOCIAL_PREVIEW_ACTIVATIONS_PER_WINDOW = 1` per `SOCIAL_PREVIEW_WINDOW = 30d`). New repo method `countActivationsSince(userId, since)` powers the check via the existing `idx_travel_locations_user_active_from` index; second activation inside the window returns `TRAVEL_PREVIEW_RATE_LIMITED` (mapped to 429 in StatusPages). `clear()` bypasses the rate limit so Social viewers can always exit travel mode. Select tier still has no cap. Coverage in `TravelLocationServiceTest` (Social activates once, Social second activation rate-limited, Social can clear without hitting limit, Select bypasses limit). "Visible while traveling" privacy toggle (PROFILE_SETTINGS_SPEC L221-245) shipped 2026-05-17: V52 adds `discovery_settings.visible_while_traveling BOOLEAN NOT NULL DEFAULT true`. Grid and Swipe deck SQL gain `AND (target_travel.id IS NULL OR ds.visible_while_traveling = true)` — when a target has an active travel_locations row AND opted out, they're hidden from every discovery surface (Grid, Swipe, Top Picks via `queryDeck`) for the duration of the session. Default true preserves the documented passport behavior; opt-out is the privacy-strict alternative. Read/write API: `DiscoverySettingsDto.visibleWhileTraveling` exposed via `/v1/me` and `UpdateDiscoverySettingsRequestDto.visibleWhileTraveling` settable via the existing discovery-settings update path. Coverage in `GridAndSwipeRepositoryIntegrationTest` "visible_while_traveling=false hides travel-mode targets from every discovery surface". Client settings toggle for `visibleWhileTraveling` shipped 2026-05-17: `ProfileHubScreen` "Discovery visibility" section gains a "Visible while traveling" row beside the Grid/Swipe toggles; round-trips through `PrivacySettingsDraft.visibleWhileTraveling`, `DiscoverySettingsDto.toPrivacyDraft`, and `PrivacySettingsDraft.toRequest`. Action-precondition/target-eligibility checks (`JdbcSwipeActionEligibilitySql`) and the rewind candidate query (`JdbcSwipeRewindSql`, 2026-06-09) now mirror the deck's target-side travel override (same `target_travel` LEFT JOIN, OR'd `ST_DWithin`, COALESCE'd display distance, `visible_while_traveling` predicate), so dealt visiting cards stay actionable and rewindable; coverage in `GridAndSwipeRepositoryIntegrationTest` "travel-mode target dealt in destination deck remains actionable" and "travel-mode target liked in destination deck remains rewindable". **Deferred to follow-up slices:** Psync travel context (PSYNC_SPEC) and map-pin/city-search picker (current client uses a 10-city preset list). Recommended city-search approach (assessed 2026-06-09, founder call pending): seed a `travel_cities` table via Flyway from a bundled city dataset (GeoNames/SimpleMaps are CC-BY 4.0 — attribution required, licensing decision needed), expose `GET /v1/location/travel/cities?q=` with trigram/prefix search, and add a search field above the preset chips in `TravelModeSheet`; no external geocoding API.
- [x] Dealbreaker settings exist. Server-side validation against the `SwipeDealbreakerKey` allow-list (`age`, `distance`, `verified`, `discoveryStyles`, `intents`, `linkedOnly`) honors them as hard gates and skips them from B3 relaxation. Client `SwipeFiltersSheet` exposes a per-section `Lock` chip that toggles each key; clearing the underlying filter auto-drops the lock (no orphaned hard gates). Coverage in `SwipeServiceTest` (relaxation policy) and `SwipeFiltersSheetInteractionTest` (UX reversibility).
- [x] Compose card stack renders only 2-3 cards.
- [x] Swipe gestures use physics/thresholds.
- [x] Card image preloading exists for the active/near-next Swipe stack.
- [x] Swipe API load proof exists for the current MVP backend shape. The harness validates deck/session authority, filtered and relaxed decks, page-two cursor path, Like/Pass, Rewind, presence, and optional Boost/Top Picks/Travel scenarios; 100-concurrent extended and 500-concurrent core runs passed on 2026-05-18.
- [x] Connected Android Swipe visual/gesture/golden QA exists. `scripts/android-swipe-qa.ps1` builds/installs the debug app, logs in, opens the real Swipe screen, verifies structural golden screenshots/XML for initial deck, filters, travel mode, Top Picks deck (Top-Picks-as-filter mode with the gold daily-refresh caption), linked deck, linked partner preview, and Intro Note dialog, then proves left/right gestures advance the deck. Goldens regenerated 2026-06-09 for the slim-header layout (#93-96: title/subtitle block and TopPicksRow texts removed, Top Picks chip added). Latest emulator artifact with golden assertions enforced: `artifacts/android-swipe-qa/20260609-185148`.
- [ ] Partial: offline action cap exists with stable action IDs and SQLDelight persistence on both mobile platforms. `SqlDelightSwipeActionQueueStore` in `composeApp` commonMain runs against `PynapliDatabase` on Android (via `AndroidPynapliDatabaseFactory`) and iOS (via `IosPynapliDatabaseFactory`). `composeApp:iosMain` exposes `IosSwipeFactory.createSwipeActionQueueStore(database)` / `createSwipeStateHolder(repository, database)` so the Swift shell wires the queue with one call once it exists. Android connected QA now proves a recoverable failed Like stays pending across force-stop/relaunch and clears on retry (`scripts/android-swipe-queue-qa.ps1`, latest artifact `artifacts/android-swipe-queue-qa/20260518-212037`). Process-death device QA on a real iPhone is still pending — Windows CI cross-compiles via Kotlin/Native but can't drive an iOS simulator.
- [x] Block/report available from card.
- [x] Linked partner preview does not turn into couple card.

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
