# Pynapli Messaging Spec

Last updated: 2026-05-24

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

Paid states:

- Social/Select: read receipts, visible only when both sender and active recipient participants are receipt-eligible.
- Select: typing status, delivered as a short-lived realtime hint.

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
- Message expiry.
- Expanded media controls.
- Intro Notes, depending tier.

Select:

- Typing status.

Do not paywall:

- Partner invite.
- Leaving a chat.
- Blocking/reporting.
- Revoking album access.

Current implementation note:

- Linked-partner chat invites emit `partner_chat_invite` Activity rows for the invited partner after the messaging repository creates the invite.
- Partner invite consent is tracked in `partner_chat_invites`: the linked partner can accept first, but remains an invited participant until the existing third party also approves.
- If the existing third party declines, the invited partner is removed from the thread context and a 24-hour cooldown blocks re-inviting that same partner to that same thread.
- Messages inbox rows and the conversation sheet surface the correct state: Accept/Decline, waiting for chat approval, waiting for partner response, or pending.
- Own-message deletion uses the existing retained message row: `deleted_at` is set, the original body is not returned to normal clients, and every participant sees a neutral deleted-message placeholder.
- Message/conversation realtime now uses authenticated `/v1/messages/stream` WebSocket deltas for `message_created`, `message_deleted`, `message_read`, `conversation_updated`, `typing_started`, and `typing_stopped`.
- Message create/delete/read/conversation-update deltas are convenience signals; clients refetch canonical thread/list state over HTTP.
- Typing deltas are lightweight UI hints only: `POST /v1/messages/threads/{threadId}/typing` is Select-only, emits only to active Select recipients, includes actor display metadata, and expires client-side after a short TTL.
- Read receipt visibility is server-masked. Free viewers never see `read`; paid senders see `read` only when every active recipient participant is Social/Select eligible.
- Connected Android Messages QA covers login -> Messages -> open thread -> send -> delete placeholder -> third-party partner approval -> invited-partner acceptance with structural screenshot/golden checks.
- Message expiry (2026-06-13): each participant sets their own disappearing-messages timer (1h/24h/7d/30d or off) via `PUT /v1/messages/threads/{threadId}/expiry`, Social/Select-gated (`ENTITLEMENT_REQUIRED`); the effective timer is the strictest non-null across active participants (`message_expiry_seconds` on `conversation_participants`, V75). Every change records a centered `message_type='system'` notice both participants see. Expired `user` messages are filtered from delivery (the message-page + last-message SQL) but rows are retained for moderation/legal; system notices never expire from view.
- Per-recipient media reveal (2026-06-13): a chat photo is uploaded as a one-off image asset (`intended_album_kind='message'`, never an album asset — sending a photo is not album access) via `POST /v1/messages/media/uploads`, then sent with `POST /v1/messages/threads/{threadId}/media` (`messages.media_asset_id`, V76; moderation runs before send, SEVERE refuses). The sender always sees their own photo; each recipient sees a locked "tap to reveal" placeholder until `POST /v1/messages/{messageId}/reveal` records their row in the per-recipient `message_media_reveals` ledger — only then does the listing populate the (signed/local) `mediaUrl`. Image-only, one photo per message. Messaging stays decoupled from the media stack behind a `MessageMediaGateway` seam implemented in the media module.

## Implementation Checklist

- [x] Open grid messaging respects recipient settings.
- [x] Swipe messaging requires mutual match.
- [x] Intro Notes remain outside chat until accepted/matched.
- [x] Partner invite requires linked partner and third-party consent.
- [x] Partner invite appears in invited partner Activity.
- [x] Album access is separate from chat membership.
- [x] Media reveal is per-recipient.
- [x] Own-message deletion returns a visible placeholder.
- [x] Social/Select read receipts are server-masked by entitlement and participant reciprocity.
- [x] Select typing indicators use recipient-scoped realtime hints.
- [x] Block archives/disables thread.
- [x] Report is separate from block.
- [x] Message expiry exists only with clear system messaging.
- [x] Server owns message permissions.
- [x] Message/conversation realtime refetches canonical HTTP state.
- [x] A new match appears in both people's Messages straight away. (Swipe and Psync matches get-or-create the swipe-origin direct conversation (`MatchConversationOpener`, implemented by `MessagingService`) and push a `conversation_updated` delta to both, so the match shows in the new-matches rail without anyone tapping Message first. A conversation that already exists is reused; a blocked pair gets none; a Psync together match opens each partner's own conversation and never adds the partner.)

## Do Not Implement

- Do not silently add linked partners.
- Do not auto-share albums with partners.
- Do not let Swipe bypass match gating except capped Intro Notes.
- Do not paywall safety controls.
- Do not make deleted messages misleading for safety/moderation.
