# Pynapli Notifications Spec

Last updated: 2026-04-24

## Purpose

Notifications bring users back into Pynapli without exposing sensitive lifestyle context.

Core rule:

> Ktor decides notification eligibility and copy. Firebase Cloud Messaging delivers it.

## Notification Categories

- Messages
- Matches
- Activity
- Flirts
- Partner links
- Partner chat invites
- Psync
- Playing Tonight
- Private albums
- Subscriptions
- Safety/moderation
- System/account

## Privacy Detail Levels

Users can choose:

- Private: generic only.
- Standard: names for messages/matches.
- Detailed: names and activity types.

Default:

- Private or Standard, depending platform onboarding choice.

Avoid explicit lifestyle terms in push notifications by default.

## Copy Rules

Good:

- You have new Activity.
- Someone viewed your profile.
- You have a new match.
- Alex sent a message.
- Jamie invited you to a chat.
- Your session ends in 15 minutes.

Avoid:

- A hot couple wants you.
- Someone wants to swap.
- Your private album got attention.
- Lifestyle action nearby.

## Actionable Notifications

Action buttons can be useful for:

- Partner link request: Accept / Deny.
- Partner chat invite: Accept / Deny.
- Psync invite: Accept / Deny.
- Playing Tonight approval: Approve / Decline.

Rules:

- Background action sends result to Ktor.
- Ktor validates current state.
- If stale, app shows neutral stale-state copy.

## Do Not Disturb

Respect:

- iOS Focus.
- Android Do Not Disturb.
- User-defined quiet hours.

During quiet hours:

- Safety/moderation/account-critical notifications can still be queued or delivered according to severity.
- Social notifications should be queued and grouped.

## Grouping

Group noisy social signals:

- Views.
- Flirts.
- Likes.

Keep action-required notifications individual:

- Partner invite.
- Psync invite.
- Playing Tonight approval.
- Safety/moderation.

## FCM Payload Rules

Payload should include:

- Notification type.
- Deep link.
- Privacy-safe title/body.
- Actor ID where allowed.
- Thread/session ID where allowed.
- Collapse/group key.

Do not include:

- Message contents if user disabled previews.
- Explicit lifestyle tags.
- Exact location.
- Private album URLs.
- Sensitive verification data.

## Android Channels

Recommended channels:

- `messages`
- `matches_activity`
- `partner_actions`
- `system_safety`
- `silent_updates`

## In-App Bell

The bell is for action-required or account/system items, not a duplicate Activity feed.

Bell includes:

- Partner link requests.
- Partner chat invites.
- Psync invites.
- Playing Tonight approvals.
- Subscription/account notices.
- Moderation/safety notices.

Activity includes:

- Views.
- Likes.
- Flirts.
- Matches.
- Album requests.

Messages includes:

- Chat threads.

## Rate Limits

Server-side notification limits:

- Message notifications grouped per thread.
- Flirt notifications capped per sender/recipient window.
- View notifications aggregated.
- Boost/result notifications capped.

## Implementation Checklist

- [ ] FCM token management exists.
- [ ] Ktor notification service exists.
- [ ] Notification detail settings exist.
- [ ] Quiet hours exist.
- [ ] Actionable notifications validate through Ktor.
- [ ] Bell is not a duplicate Activity feed.
- [ ] Push copy avoids explicit terms by default.

## Do Not Implement

- Do not send explicit lifestyle terms by default.
- Do not include private media URLs in payloads.
- Do not bypass OS Do Not Disturb.
- Do not send fake notifications.
- Do not let Firebase decide product eligibility.

