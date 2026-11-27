# Pynapli Activity Spec

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

Activity is a primary bottom tab (Grid, Swipe, Activity, Messages; there is no Store tab, paywalls
open the store sheet, see [DESIGN_SPEC.md](DESIGN_SPEC.md)). The Likes You / Viewed You reveal
opens the store sheet on the plans. It is both an engagement and a monetization
surface.

## Ordering

Activity is ordered newest to oldest.

Reason:

- It should feel live.
- Recent interest is more actionable.
- FOMO depends on freshness.
- Old activity should feel like history, not the main experience.

## Activity Screen Structure

Compact header, tabs, the list, and upgrade/reveal rows where appropriate. Tabs on mobile: **Flirts**
(default; taps, swipe likes, Pineapple Likes, notes), **Views**, **Requests**. New matches live in
the Messages new-matches rail and are not in the Activity badge.

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
- Implemented (plan G2-4): every plan sees the last 7 days; the plan decides how many of those
  viewers are revealed.
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
- Can carry an optional note (up to 140 characters), shown on the like; the note is refused, not
  the like, when the recipient turned off `Allow notes with Pineapple Likes`.
- Should be rate-limited and scarce.
- Should respect privacy, age, verification, and block settings.

### Pineapple Like note (formerly Intro Note)

Intro Notes merged into the Pineapple Like (2026-11-17); there is no separate `intro_note` card.
V92 converted existing ones into `pineapple_like` cards. The note (`ActivityEventDto.noteText`) is
read from the live like, so a rewound like shows none.

Rules:

- Max 140 characters.
- Appears in Activity, not Messages, until matched.
- Liking back makes the match (and its conversation), like any Pineapple Like.
- Dismissing creates no chat.

### Match

Created when compatible Likes/Super Likes are mutual.

Rules:

- Match appears in Activity and creates/updates Messages.
- Activity row CTA can be `Message`.
- Message CTA opens the existing match conversation path using server-provided match metadata.
- Match animation happens in Swipe, but the historical record lives in Activity.

### Private Album Request

Created when one user requests access to another user's private album.

Rules:

- Appears in Albums/All.
- Recipient can Grant, Deny, or Ignore.
- Activity row first shows `Review`, then reveals Grant/Deny so accidental album decisions are less likely.
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
- Activity row CTA can open the invite conversation context using the server-provided conversation id.
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
- Allow notes with Pineapple Likes
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

Activity is event-based: `activity_events` rows with a recipient, an optional actor, a type, a
timestamp, a visibility (`visible`, `blurred`, `count_only`, `hidden`) and metadata. Types cover views,
flirts, swipe likes, Pineapple Likes, notes, matches, album requests and grants, partner chat invites,
partner link events, Psync and Playing Tonight events, and Boost results. Partial unique indexes stop
duplicate interest, match and invite rows; idempotent swipe retries never duplicate Activity.

### Event Aggregation

Aggregate where useful:

- Repeat profile views
- Multiple likes count

Do not aggregate:

- Partner chat invites
- Album access grants/revocations
- Blocks/reports
- Matches, unless shown in a separate summary

## Retention Windows

Implemented by the Activity retention worker (`ActivityRetentionService`): views and flirts leave
product-visible Activity after 30 days; likes, notes, album and invite rows after 90 days; matches
persist unless product state removes them. Staging/prod must set
`PYNAPLI_ACTIVITY_RETENTION_ENABLED=true` (off by default outside local). Internal safety/audit
retention is separate and subject to legal/privacy review.

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

## Badge Counts

Bottom nav Activity badge should show meaningful new activity, not noise.

Count:

- New Likes You
- New flirts
- Album requests
- Partner invites

New matches are **not** in the Activity badge. Activity has no Matches tab (matches live in the
Messages new-matches rail), so no client ever loads a match row to mark it seen; counting it would
leave the badge stuck. A new match is announced by the full-screen match moment (once per person
per match, `match_celebrations`), the match push (`target: messages`) and the new-matches rail.

Current backend behavior:

- `badgeCount` includes unseen flirts, likes, pending album requests, and partner chat invites.
- Unseen `match` events are excluded from `badgeCount` and from `latestBadgeOccurredAt`.
  `matchCount` stays in the summary DTO for already-installed clients and is always `0`.
- Profile views are not included in `badgeCount`.
- `profileViewCount` is returned separately for in-screen Viewed-list copy.
- Badge counts honor dismiss, block, actor risk, actor visibility, incognito, and Viewed-list opt-out rules.
- `seen_at` is set only through the seen endpoint for event IDs owned by the authenticated user; cross-user IDs are ignored.
- Re-aggregated flirts/profile views/album requests become unread again only when the cooldown allows a meaningful new count.

Current client behavior:

- Bottom-nav Activity badge uses the server `badgeCount`.
- Opening Activity, switching Activity tabs, or loading another visible page marks only those loaded rows as seen, then refreshes the summary badge.
- Background Activity feed refresh does not mark rows seen.
- Summary refresh runs independently of full feed refresh so badge behavior does not depend on the currently selected Activity tab or revealed feed page.
- Logout resets local Activity feed and summary state.

Do not necessarily count:

- Every single view
- Repeated view spam

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

Do not turn flirts or Pineapple Like notes into chat threads until match/chat eligibility is satisfied.

## Activity, Grid and Swipe Boundaries

Grid and Swipe are where people act (view, message, like, pass, match). Activity records the signals
those actions create (views from full profile opens, flirts, likes, Pineapple Likes, notes, matches,
Boost results) so people can respond, and hosts premium reveals. **Grid impressions and swipe card
appearances never count as profile views.**

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
- [x] Repeat views aggregate with cooldown: one row per viewer with a count, shown as "viewed you 3x"
  (checked in code 2026-11-27, plan G2-4).
- [x] Viewed Me is a 7-day list (plan G2-4, as Grindr, 2026-11-27): the Views feed, its gated "N more"
  count (`gatedCount`, from the same query) and `profileViewCount` only take views whose latest
  `occurred_at` is in the last 7 days (`JdbcActivityFeedSql`, `JdbcActivitySummarySql`). A row stays
  stored until the retention worker removes it at 30 days (unchanged), but is never listed or counted.
  A view more than 7 days after the previous one restarts the count at 1, so the count is about the
  week shown. Reciprocity, incognito, blocks, contact blocks, risk suppression and the plan reveal
  (Free 1, Social 10, Select all; gated viewers never sent) are unchanged. Proof:
  `ActivityRepositoryIntegrationTest.viewedMeListsAndCountsOnlyTheLastSevenDays`,
  `aRepeatViewAfterAWeekQuietStartsTheCountAgain`. Free still opens the latest viewer (kept on
  purpose; Grindr shows but does not let Free act on it).
- [x] Flirts appear in Activity, not Messages.
- [x] Likes You appears in Activity.
- [x] Likes You (Flirts) and Views render the Grid's own card in the Grid's 3-up layout, with the activity
  and any Pineapple Like note as a caption; gating is the server's Activity reveal rule, unchanged.
- [x] A like opened from Likes You offers Like back in the shared profile sheet (Activity's like-back).
- [x] Pineapple Likes appear in Activity.
- [x] A Pineapple Like's note appears on the like in Activity, not Messages (Intro Notes merged into Pineapple Like, V92).
- [x] Matches appear in Activity for both matched users (shown in the Messages new-matches rail).
- [x] Likes You and match rows survive a crash between the like or match commit and their delivery: they are written to the match side-effect outbox in the same transaction and redelivered by a drainer (SWIPE_IMPLEMENTATION_SPEC "Match side effects").
- [x] Match Activity rows can open the message path.
- [x] Partner chat invites appear in invited partner Activity.
- [x] Partner chat invites require explicit action.
- [x] Partner chat invite Activity rows can open the invite conversation context.
- [x] A like can be answered from Activity. (`swipe_like` / `pineapple_like` items offer Like back, `POST /v1/swipe/like-back`: same rules as a Swipe like (blocks, risk, rate limit, 24h travel like tail), replaces an earlier pass, and makes the match with its activity, push and Messages conversation. Without it a received like, including one delivered from Psync, only became a match if that person came up in the viewer's Swipe deck.)
- [x] Album requests can be granted/denied/ignored.
- [x] A granted album request reaches the requester. (`album_granted` Activity item (V83) under Requests, one live card per owner and requester that re-surfaces on a re-grant, plus an `album_granted` push under the album-request notification setting, at most one per day per pair. A denial stays silent on purpose.)
- [x] Album request rows use Review before grant/deny actions.
- [x] Free/Social/Select gating is implemented.
- [x] Viewed opt-out suppresses outgoing view events and disables the user's own Viewed feed/counts.
- [x] Incognito suppresses view events.
- [x] Blocks remove Activity visibility.
- [x] Linked partner Activity remains separate.
- [x] Notification detail settings exist.
- [x] Activity badge avoids noisy view spam.
- [x] Activity summary/badge endpoint exists.
- [x] Bottom-nav Activity badge consumes the server summary endpoint.
- [x] Activity read/seen endpoint exists.
- [x] Activity badge clears only for loaded visible rows.
- [x] Activity rows visually distinguish unseen badge-worthy rows.
- [x] Activity has a connected Android QA harness for the real installed app path.
- [x] Activity is not a message inbox.
- [x] Realtime Activity deltas.
- [x] OS notification-to-Activity tab deep links with row focus support when the payload includes an Activity event id.
- [x] Activity row distances follow location privacy: the public bucket of the cell-to-cell distance between both people's effective locations (a traveller's travel point, never their home), and none when the actor hides their distance (`SwipeLocationPrivacyTest`).

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
