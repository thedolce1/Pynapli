# Pynapli Swipe Implementation Spec

Last updated: 2026-05-15 (linked-partner preview popover + partner active state + short bio + tag chips + photo carousel + verified badge added)

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

## Implementation Checklist

- [x] Swipe card DTO exists.
- [x] Deck query endpoint returns signed persisted deck sessions/card tokens and action endpoint validates dealt-card state.
- [x] Like/pass/Pineapple Like/Intro Note action endpoint exists.
- [x] Match creation is server-authoritative.
- [x] Cursor pagination continues a persisted deck session without duplicate returned cards. Client auto-loads the next page when the visible stack drops below the threshold, deduping against current cards and pending action targets.
- [x] Rewind is server-aware for the latest eligible Like/Pass/Pineapple Like and local queued actions. Pineapple Like inventory is refunded atomically with the rewind: `swipe_actions.consumed_inventory_id` (V48) records the `premium_inventory.id` decremented when the action was submitted, and `JdbcSwipeRepository.rewindSwipeAction` increments that exact row's quantity in the same transaction as the `rewound_at` update. Coverage in `SwipeServiceTest.paidRewindOfPineappleLikeRefundsConsumedInventoryRow` and `GridAndSwipeRepositoryIntegrationTest` "swipe rewind atomically refunds the consumed Pineapple Like inventory row".
- [x] Intro Note inventory and delivery rules exist.
- [x] Likes You is represented in Activity.
- [ ] Top Picks/Picks daily refresh exists.
- [x] Boost affects swipe ranking without violating filters.
- [ ] Passport/travel mode has one active location.
- [x] Dealbreaker settings exist. Server-side validation against the `SwipeDealbreakerKey` allow-list (`age`, `distance`, `verified`, `discoveryStyles`, `intents`, `linkedOnly`) honors them as hard gates and skips them from B3 relaxation. Client `SwipeFiltersSheet` exposes a per-section `Lock` chip that toggles each key; clearing the underlying filter auto-drops the lock (no orphaned hard gates). Coverage in `SwipeServiceTest` (relaxation policy) and `SwipeFiltersSheetInteractionTest` (UX reversibility).
- [x] Compose card stack renders only 2-3 cards.
- [x] Swipe gestures use physics/thresholds.
- [x] Card image preloading exists for the active/near-next Swipe stack.
- [ ] Partial: offline action cap exists with stable action IDs and Android SQLDelight persistence; iOS wiring and process-death device QA remain incomplete.
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
