# Pynapli Messaging Spec

Last updated: 2026-04-24

## Purpose

Messaging is where Grid immediacy, Swipe matching, partner invites, private albums, and safety controls meet.

Core rule:

> Grid can start conversations. Swipe unlocks conversations through matches. Partners are invited, never silently added.

## Messaging Rules By Origin

| Origin | Gate | Messaging |
|---|---|---|
| Grid/profile | Recipient settings and rate limits | Open messaging |
| Swipe | Mutual match | Match-gated |
| Intro Note | Paid/high-intent exception | Activity item until accepted |
| Mutual flirt | Optional soft match | Can unlock chat if product enables it |
| Psync together match | Target consent + both linked partners liked | Together-mode chat eligible |
| Partner invite | All required accepts | Partner joins existing chat |

## Thread Types

- Solo thread: two individual users.
- Match thread: created from Swipe match.
- Partner-invited thread: one or both linked partners have joined by consent.
- Psync together thread: eligible only after a valid together match.
- Event/Tonight thread: optional later, tied to event or Tonight context.

No thread type is a shared account. Participants are always individual accounts.

## Partner Invite Flow

Rules:

- A user can invite their linked partner into an existing chat.
- The linked partner must accept.
- The existing third party/parties must accept before the partner joins.
- No silent adds.
- Denial should be neutral.
- Anyone can leave anytime.
- Album access does not transfer to the partner.

Recommended cooldown:

- If the third party denies a partner invite, require a 24-hour cooldown before re-inviting that same partner to that same thread.

## Media And Private Albums

Private album access is not the same as sending a photo.

Message media rules:

- Sent photos use consent-tap reveal where feasible.
- Full media loads only after recipient action.
- Private album grants are explicit and revocable.
- Each participant reveals media independently in multi-person threads.
- Linked partner does not inherit photo or album access.
- Photos are not saved to device by default.

## Message States

Base states:

- Sending
- Sent
- Delivered
- Failed

Paid/Select states:

- Read receipts
- Typing status

Trust/safety internal states:

- Held for review
- Removed
- Blocked

Client copy can stay minimal, but server state must be honest.

## Deletion And Expiry

Default:

- Users can delete their own sent messages.
- Deletion removes the message for everyone.
- A placeholder appears: `[Name] deleted a message`.

Premium privacy options:

- Conversation expiry timer: 1 hour, 24 hours, 7 days, 30 days.
- Silent deletion can be considered for Select, but must be handled carefully because it affects conversation integrity.

Expiry rules:

- Both participants see a system message when an expiry timer is set or changed.
- If both users set timers, stricter timer wins.
- Expiry must not remove moderation/legal retention where required.

## Blocking And Reporting

Blocking:

- Immediate.
- Bidirectional visibility removal where possible.
- Archives or disables the thread.
- Does not auto-block linked partners.
- No notification sent to blocked user.

Reporting:

- Separate from blocking.
- Available from profile, thread, message, media, and album contexts.
- Can be used with or without blocking.

## Monetization

Free:

- Grid messaging.
- Swipe match chat.
- Partner invite.
- Basic media.
- Basic deletion.
- Block/report.

Social/Select:

- Read receipts.
- Typing status.
- Message expiry.
- Expanded media controls.
- Intro Notes, depending tier.

Do not paywall:

- Partner invite.
- Leaving a chat.
- Blocking/reporting.
- Revoking album access.

## Implementation Checklist

- [ ] Open grid messaging respects recipient settings.
- [ ] Swipe messaging requires mutual match.
- [ ] Intro Notes remain outside chat until accepted/matched.
- [ ] Partner invite requires linked partner and third-party consent.
- [ ] Album access is separate from chat membership.
- [ ] Media reveal is per-recipient.
- [ ] Block archives/disables thread.
- [ ] Report is separate from block.
- [ ] Message expiry exists only with clear system messaging.
- [x] Server owns message permissions.

## Do Not Implement

- Do not silently add linked partners.
- Do not auto-share albums with partners.
- Do not let Swipe bypass match gating except capped Intro Notes.
- Do not paywall safety controls.
- Do not make deleted messages misleading for safety/moderation.
