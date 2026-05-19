# Pynapli Activity Spec

Last updated: 2026-05-19

## Purpose

Activity is Pynapli's interest inbox and FOMO engine.

It captures lightweight signals that are not full conversations yet:

- Views
- Flirts
- Swipe likes
- Matches
- Private album requests
- Partner chat invites
- Psync invites and recaps
- Playing Tonight interactions
- Repeat interest
- Boost results
- Pick expiration

Core rule:

> Activity is the interest inbox. Messages are conversations. Do not merge them.

## Product Role

Activity should:

- Show users that attention is happening.
- Convert passive interest into messages, matches, and subscriptions.
- Keep users returning without creating spammy notifications.
- Provide a premium reveal surface.
- Keep sensitive signals privacy-aware.

Activity should not:

- Become a second message inbox.
- Expose sensitive behavior without controls.
- Create drama between linked partners.
- Feel like surveillance.
- Use cheesy or explicit copy.

## Navigation

Recommended bottom navigation:

- Grid
- Swipe
- Activity
- Messages
- Store

Activity should be a primary tab because it is both an engagement and monetization surface.

## Ordering

Activity is ordered newest to oldest.

Reason:

- It should feel live.
- Recent interest is more actionable.
- FOMO depends on freshness.
- Old activity should feel like history, not the main experience.

## Activity Screen Structure

Recommended top structure:

- Compact app header
- Segmented control or tabs
- Activity list
- Upgrade/reveal modules where appropriate

Recommended tabs:

- All
- Views
- Flirts
- Likes
- Matches
- Albums
- Invites

Keep tabs horizontally scrollable if needed, but avoid clutter.

## Activity Row Design

Each row should be minimal and scannable.

Show:

- Avatar or blurred avatar
- Display name or gated label
- Action text
- Timestamp
- Distance/city if allowed
- Small linked-partner icon if linked
- Contextual CTA if allowed

Examples:

- Alex viewed you - 4 min ago
- Jamie sent a Spark - 12 min ago
- Morgan liked you in Swipe - 1 hr ago
- Taylor requested album access - Yesterday
- You matched with Sam - Yesterday
- Alex invited you to join a chat - 2 days ago

Do not use explicit or sensational copy.

Avoid:

- Alex is watching you
- Jamie wants you badly
- Taylor wants your private pics
- Things are heating up
- Your partner joined the fun

## Event Types

### Profile View

Created when one user opens another user's full profile.

Rules:

- Grid card impressions should not count as profile views.
- Swipe card appearances should not count as profile views.
- Profile view should require intentional profile opening or equivalent full-screen view.
- Repeat views should be aggregated.
- Blocked users must be removed or hidden.
- Incognito users should not create visible view events.

Recommended retention:

- Free visible history: latest item/counts.
- Paid visible history: 7-30 days depending tier.
- Internal fraud/safety retention can differ from product-visible retention.

### Repeat View

Created when a user views a profile multiple times within the retention window.

Rules:

- Aggregate counts as `2x`, `3x+`.
- Use a cooldown window so rapid open/close does not inflate counts.
- Do not show repeat views from users already in active chat unless deliberately designed.

Example:

- Alex viewed you 3x this week

### Flirt

A lightweight interest signal similar to Grindr's Tap.

Recommended flirt types:

- Wave: friendly/social interest
- Spark: interested
- Pineapple: lifestyle interest

Rules:

- Flirts appear in Activity, not Messages.
- Flirts can optionally create a notification.
- Flirts can be limited/rate-limited for anti-spam.
- Users can restrict who can send flirts.

Flirt controls:

- Everyone
- Verified only
- People matching my intent
- No flirts

### Swipe Like

Created when another user likes the current user in Swipe.

Rules:

- If not yet matched, appears in Likes/All.
- If the current user likes back, becomes a Match.
- Free users can see count and partial/blurred preview.
- Paid users can open and act on Likes You.

### Pineapple Like / Super Like

Created when another user sends a high-intent like.

Rules:

- Should appear more prominently than normal Like.
- Can allow attached Intro Note where inventory/eligibility permits.
- Should be rate-limited and scarce.
- Should respect privacy, age, verification, and block settings.

### Intro Note

Created when a user sends a premium message-before-match note from Swipe.

Rules:

- Max 140 characters.
- Appears in Activity, not Messages, until matched.
- If recipient likes back, a match/chat can be created.
- If recipient dismisses, no chat is created.
- Delivery is not guaranteed if recipient settings block it.

### Match

Created when compatible Likes/Super Likes are mutual.

Rules:

- Match appears in Activity and creates/updates Messages.
- Activity row CTA can be `Message`.
- Match animation happens in Swipe, but the historical record lives in Activity.

### Private Album Request

Created when one user requests access to another user's private album.

Rules:

- Appears in Albums/All.
- Recipient can Grant, Deny, or Ignore.
- Album requests should be rate-limited.
- Users can restrict album requests:
  - Everyone
  - Chats only
  - Matches only
  - Verified only
  - No requests

### Private Album Grant

Created when album access is granted.

Rules:

- Appears for recipient of access.
- Should show expiration if timed.
- Must not imply access to linked partner's album.

Example:

- Alex granted album access for 24 hours

### Album Access Expiring

Created before timed access expires.

Rules:

- Optional notification depending user settings.
- Should not be explicit.

Example:

- Alex's album access expires soon

### Partner Chat Invite

Created when a linked partner is invited into a chat.

Rules:

- Invitee receives Activity item.
- Invitee can Accept, Deny, or Ignore.
- No silent partner adds.
- Denying should not send drama-heavy notifications.
- Invite context should show who is in the chat.

Example:

- Jamie invited you to join a chat with Alex

### Partner Link Event

Created for partner-link lifecycle events.

Events:

- Partner link request
- Partner link accepted
- Partner unlinked
- Partner visibility changed

Rules:

- Sensitive events should use neutral language.
- Unlinking should not broadcast broadly.
- Partner-link events should appear only to involved users.

### Psync Event

Created for linked-partner synchronized swipe sessions.

Events:

- Psync invite received
- Psync session started
- Psync together match
- Psync session recap
- Psync session expired

Rules:

- Psync Activity must not expose individual private swipes.
- Together matches can be visible to both linked partners.
- Solo passthrough matches are private by default.
- Session recap should show aggregate counts only.
- Psync Score must not exist.

Example:

- Jamie invited you to start Psync
- Psync session recap ready
- You have a new together match

### Playing Tonight Event

Created for short-lived availability sessions.

Events:

- Playing Tonight approval request
- Playing Tonight session live
- Playing Tonight session ending soon
- Interaction via Playing Tonight
- Playing Tonight session ended

Rules:

- Linked-together sessions require both linked partners to approve.
- Solo Tonight sessions belong only to the individual user.
- Activity should label relevant interactions as "via Playing Tonight."
- Do not expose precise location.

### Boost Result

Created when a Boost/Super Boost produces measurable results.

Examples:

- Your Boost got 42 profile views
- Your Boost led to 6 likes
- Your Super Boost ended

Rules:

- Use honest metrics.
- Do not imply guaranteed matches.
- Should be visible in Activity and Store/Premium history.

### Pick Expiration

Created when daily/extra Picks are close to expiring.

Examples:

- Today's Picks refresh soon
- 2 extra Picks expire in 3 hours

Rules:

- Useful for FOMO.
- Avoid spammy frequency.
- Do not use explicit language.

## Free / Social / Select Gating

Activity is a natural premium surface.

### Free

Free users can see:

- Activity counts
- Latest visible item in some categories
- Matches
- Partner invites
- Album requests that require action
- Blurred previews for older/restricted items

Free users cannot fully access:

- Full Viewed You list
- Full Likes You list
- Full repeat viewers
- Full historical Activity filters

### Social

Plus users can see:

- More recent Activity
- Some unblurred views/flirts
- Basic filters
- Limited Likes You previews
- Open flirts from Activity

### Select

Premium users can see:

- Full Viewed You list
- Full Likes You list
- Repeat viewers
- Full Activity filters
- Message/open from Activity where allowed
- Longer history window
- Boost analytics
- Advanced privacy/incognito controls

## Gating UX

Good gating:

- Show count.
- Show one real/latest item.
- Blur or partially hide older items.
- Explain the benefit simply.
- Offer upgrade without blocking safety actions.

Example:

- 12 people viewed you
- Upgrade to see everyone who viewed your profile

Bad gating:

- Fake urgency.
- Misleading identities.
- Blocking partner invites.
- Blocking safety actions.
- Making Activity unusable.

## Privacy Controls

Activity must have strong privacy controls.

User settings:

- Show me in Viewed lists
- Show my distance in Activity
- Browse incognito
- Allow flirts
- Allow album requests
- Allow Intro Notes
- Allow partner chat invites
- Detailed notifications

### Viewed List Opt-Out

If a user turns off "Show me in Viewed lists":

- They stop appearing in others' Viewed activity.
- Their own Viewed list is disabled.
- Existing Viewed history should be deleted from product-visible Activity.
- Re-enabling does not restore old history.

This is a fair privacy tradeoff and prevents invisible browsing while still harvesting view data.

### Incognito

If a user is incognito:

- They should not appear in Viewed lists.
- They may appear offline or hidden according to subscription/product rules.
- Their own access to Viewed lists depends on tier/settings.

### Blocks

When User A blocks User B:

- User B should no longer see User A in Activity.
- User A should no longer see User B in Activity, unless needed for safety/reporting history.
- Existing chat visibility follows Messages/block policy.

### Linked Partner Privacy

Activity involving linked partners must avoid assumptions.

Rules:

- Viewing one partner does not create an activity event for the other partner.
- Album access granted by one partner does not notify or include the other partner.
- Partner chat invites require explicit acceptance.
- A linked partner cannot view another user's private Activity unless explicitly shared.
- Subscription sharing does not mean Activity sharing.

## Notifications

Notifications should be privacy-conscious.

Default notification copy:

- You have new Activity.
- Someone viewed your profile.
- You have a new match.
- Alex sent a message.
- Jamie invited you to a chat.

Avoid:

- A hot couple wants you.
- Someone wants to swap.
- Your private album got attention.
- Lifestyle action nearby.
- Don't miss tonight's fun.

Users should be able to choose notification detail level:

- Private: generic only
- Standard: names for messages/matches
- Detailed: names and activity types

High-sensitivity terms should not appear in push notifications by default.

## Backend Event Model

Activity should be event-based.

Example:

```kotlin
enum class ActivityEventType {
    PROFILE_VIEW,
    REPEAT_VIEW,
    FLIRT,
    SWIPE_LIKE,
    PINEAPPLE_LIKE,
    INTRO_NOTE,
    MATCH,
    ALBUM_REQUEST,
    ALBUM_GRANTED,
    ALBUM_EXPIRING,
    PARTNER_CHAT_INVITE,
    PARTNER_LINK_REQUEST,
    PARTNER_LINK_ACCEPTED,
    PARTNER_UNLINKED,
    PSYNC_INVITE,
    PSYNC_STARTED,
    PSYNC_TOGETHER_MATCH,
    PSYNC_RECAP,
    PSYNC_EXPIRED,
    PLAYING_TONIGHT_REQUEST,
    PLAYING_TONIGHT_LIVE,
    PLAYING_TONIGHT_INTERACTION,
    PLAYING_TONIGHT_EXPIRING,
    PLAYING_TONIGHT_ENDED,
    BOOST_RESULT,
    PICK_EXPIRING
}

data class ActivityEvent(
    val id: String,
    val recipientUserId: String,
    val actorUserId: String?,
    val type: ActivityEventType,
    val createdAt: Instant,
    val visibility: ActivityVisibility,
    val metadata: Map<String, String>
)

enum class ActivityVisibility {
    VISIBLE,
    BLURRED,
    COUNT_ONLY,
    HIDDEN
}
```

### Event Aggregation

Aggregate where useful:

- Repeat profile views
- Multiple likes count
- Boost results
- Pick expiration reminders

Do not aggregate:

- Partner chat invites
- Album access grants/revocations
- Blocks/reports
- Matches, unless shown in a separate summary

## Retention Windows

Recommended product-visible retention:

- Views: 7 days free/plus limited, 30 days premium
- Repeat views: 7 days
- Flirts: 30 days
- Likes You: until actioned, expired, or hidden by policy
- Matches: persistent unless unmatched/deleted
- Album requests: until actioned plus history window
- Partner invites: until actioned/expired
- Boost results: 90 days
- Pick expiration: until expired

Internal safety/audit retention may differ and should be defined separately with legal/privacy review.

## Realtime Updates

Activity should update live where reasonable.

Use WebSocket deltas for:

- New flirt
- New match
- New message badge
- New album request
- Partner chat invite
- Boost result update

Do not require constant polling.

If socket is unavailable:

- Fetch Activity on tab open.
- Fetch badge counts periodically with conservative interval.

## Current Implementation

Implemented and verified as of 2026-05-19:

- `GET /v1/activity` returns newest-first Activity with `limit`, `tab`, and signed `cursor` query params.
- Feed tabs are server-authoritative: `all`, `views`, `flirts`, `likes`, `matches`, `albums`, and `invites`.
- Cursor tokens are HMAC-signed and scoped to the authenticated viewer plus selected tab, so a cursor from one tab cannot page another tab.
- Free/Social/Select reveal depth is enforced across pages by the cursor's seen-count state.
- `POST /v1/activity/{eventId}/dismiss` soft-deletes the recipient's own Activity item without allowing cross-user dismissal.
- Swipe actions now create Activity events for `swipe_like`, `pineapple_like`, `intro_note`, and two-sided `match` rows after the server-authoritative action succeeds. Idempotent action retries do not duplicate Activity rows.
- Linked-partner chat invites now create `partner_chat_invite` Activity rows for the invited partner after the invite is accepted by the messaging engine.
- Database-level partial unique indexes protect Swipe interest, match, and partner-chat invite Activity rows from duplicate writes.
- Repository and service coverage proves tab filtering, cursor paging, entitlement reveal depth, invalid cursor rejection, block/risk filtering, and dismiss behavior.
- Compose Activity UI calls the real tab API instead of local-only filtering, supports cursor-based `Load more`, exposes per-row dismiss, and keeps album request grant/deny actions.
- Desktop UI interaction coverage proves Activity tab selection, cursor load-more, and dismiss through the rendered screen.

## Badge Counts

Bottom nav Activity badge should show meaningful new activity, not noise.

Count:

- New matches
- New Likes You
- New flirts
- Album requests
- Partner invites

Do not necessarily count:

- Every single view
- Repeated view spam
- Expired pick reminders

Use separate in-screen counts for views.

## Activity And Messages Boundary

Activity:

- Viewed you
- Flirted
- Liked you
- Requested album
- Invited partner
- Matched

Messages:

- Actual chat conversations
- Message threads
- Shared media inside chat
- Partner-invited group chats after acceptance

Do not turn flirts or intro notes into chat threads until match/chat eligibility is satisfied.

## Activity And Grid Boundary

Grid:

- Live nearby discovery
- Open messaging
- Profile view action

Activity:

- Records views/flirts generated from grid
- Lets users respond to signals
- Creates FOMO and premium reveals

Grid card impressions should not be Activity views.

## Activity And Swipe Boundary

Swipe:

- Like/pass decision
- Match creation moment
- Top Picks
- Boosted card exposure

Activity:

- Likes You
- Matches history
- Intro Notes
- Pineapple Likes
- Pick expiration
- Boost results

## Empty States

Good:

- No new Activity yet.
- Views, flirts, likes, and invites will appear here.
- No one has viewed you recently.
- No album requests yet.

Avoid:

- No one wants to play yet.
- Things are quiet.
- Your pineapple is waiting.
- Nothing hot yet.

## Implementation Checklist

- [x] Activity event model exists.
- [x] Activity tabs exist.
- [x] Activity screen uses server-backed tabs.
- [x] Newest-to-oldest ordering exists.
- [x] Signed cursor pagination exists for Activity.
- [x] Activity screen supports cursor load-more.
- [x] Activity dismiss exists.
- [x] Activity screen supports row dismiss.
- [x] Views require full profile opening.
- [x] Grid impressions do not count as views.
- [x] Swipe card appearances do not count as views.
- [x] Repeat views aggregate with cooldown.
- [x] Flirts appear in Activity, not Messages.
- [x] Likes You appears in Activity.
- [x] Pineapple Likes appear in Activity.
- [x] Intro Notes appear in Activity, not Messages.
- [x] Matches appear in Activity for both matched users.
- [x] Partner chat invites appear in invited partner Activity.
- [x] Partner chat invites require explicit action.
- [x] Album requests can be granted/denied/ignored.
- [x] Free/Social/Select gating is implemented.
- [ ] Partial: Viewed opt-out suppresses outgoing view events; own Viewed-list disabling still needs user-facing review.
- [x] Incognito suppresses view events.
- [x] Blocks remove Activity visibility.
- [x] Linked partner Activity remains separate.
- [x] Notification detail settings exist.
- [x] Activity badge avoids noisy view spam.
- [x] Activity is not a message inbox.

## Do Not Implement

- Do not merge Activity and Messages.
- Do not count grid impressions as profile views.
- Do not count swipe card impressions as profile views.
- Do not expose private album interest with explicit copy.
- Do not notify linked partners about each other's Activity.
- Do not silently add partners to chats.
- Do not block safety actions behind premium.
- Do not make push notifications explicit by default.
- Do not show blocked users in Activity.
- Do not let incognito users appear in Viewed lists.
- Do not make Activity feel like surveillance.
