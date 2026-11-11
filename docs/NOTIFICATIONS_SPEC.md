# Pynapli Notifications Spec

Last updated: 2026-05-06

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

MVP behavior:

- Users can enable quiet hours from app settings and choose a preset overnight window.
- Android saves the user's local timezone with the preference.
- Ktor validates the saved window and timezone.
- The dispatch worker defers non-safety notifications until quiet hours end by returning the outbox row to `pending` with a future `available_at`.
- `system_safety` notifications bypass quiet hours.

## Grouping

Group noisy social signals:

- Views.
- Flirts.
- Likes.

MVP server grouping:

- Message notifications are throttled per recipient/conversation thread for 90 seconds.
- Profile view notifications are throttled per recipient for 6 hours.
- Flirt notifications are throttled per recipient/actor pair for 6 hours.
- Private album request notifications are throttled per recipient/actor pair for 6 hours.
- Action-required notifications are not grouped in this pass because they should remain individually visible.

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

## Server Delivery Path

Ktor owns notification eligibility, copy, and queueing. Product actions enqueue rows in `notification_outbox`; the dispatch worker claims pending rows, sends to active push devices, and marks each row `sent`, `skipped`, `pending` for retry, or `failed`.

Before each claim cycle, the dispatch worker recovers stale `processing` rows that have not been updated for 10 minutes. Recovered rows return to `pending` with `last_error = STALE_PROCESSING_RECOVERED`, which prevents a crash or forced stop from permanently stranding notifications.

Local development uses `PYNAPLI_NOTIFICATION_PUSH_PROVIDER=local`, which exercises the full dispatcher without contacting Firebase. Production/staging can switch to `PYNAPLI_NOTIFICATION_PUSH_PROVIDER=firebase` when `PYNAPLI_FIREBASE_SERVICE_ACCOUNT_PATH` or `PYNAPLI_FIREBASE_SERVICE_ACCOUNT_JSON` is configured.

The Firebase sender uses privacy-safe title/body plus a data payload containing IDs and routing metadata only. Message bodies, private media URLs, exact location, and explicit lifestyle language stay out of push payloads. The default notification detail level is discreet copy.

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

MVP implementation:

- Profile hub shows a bell/action center badge for pending action items.
- Profile link invites use the profile-link accept/decline Ktor endpoints.
- Partner chat invites use the conversation partner-invite accept/decline Ktor endpoints.
- The bell composes pending action state from source-of-truth APIs; Activity remains the social-history feed.
- In-app Activity banners route to the matching Activity tab when tapped.
- OS push notifications carry safe app deep-link payloads. Message notifications open Messages/conversation context, and Activity notifications open the relevant Activity tab with row focus when an `activityEventId` is supplied.

## Rate Limits

Server-side notification limits:

- [x] Message notifications grouped per thread.
- [x] Flirt notifications capped per sender/recipient window.
- [x] View notifications aggregated.
- [ ] Boost/result notifications capped.
- [x] Private album request notifications capped per requester/recipient window.

## Implementation Checklist

- [x] FCM token management exists.
- [x] Ktor notification service exists.
- [x] Notification detail settings exist.
- [x] Server notification outbox exists.
- [x] Local-dev dispatch worker exists.
- [x] Firebase Admin sender is provider-gated by server config.
- [x] Quiet hours exist.
- [x] Actionable notifications validate through Ktor.
- [x] Bell is not a duplicate Activity feed.
- [x] Push copy avoids explicit terms by default.
- [x] Noisy social notifications have server-side throttle keys.
- [x] Stale processing notification rows are recovered.
- [x] In-app Activity notification banners route to the relevant Activity tab.
- [x] OS notification deep links open the relevant Activity tab/row fallback path.
- [x] Every notification kind can be stored. (V84 brings the `notification_outbox` kind check in line with `NOTIFICATION_KINDS`; it had drifted since V24, so for any user with a registered device the Playing Tonight, Psync and album-grant pushes violated it and failed the request that sent them. `NotificationOutboxKindsIntegrationTest` inserts one row per kind against the real schema so the two can't drift again, and an outbox write failure is now logged and dropped rather than failing the action that triggered it.)

## Do Not Implement

- Do not send explicit lifestyle terms by default.
- Do not include private media URLs in payloads.
- Do not bypass OS Do Not Disturb.
- Do not send fake notifications.
- Do not let Firebase decide product eligibility.
