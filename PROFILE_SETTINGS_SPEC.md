# Pynapli Profile And Settings Spec

Last updated: 2026-04-24

## Purpose

This document defines the profile icon/header menu, account management, profile editing, privacy controls, and app settings.

Pynapli should follow the Grindr-style pattern:

- Bottom navigation is for doing.
- Profile avatar is for managing.

Core rule:

> The avatar menu is the user's control center. Do not turn it into another discovery surface.

## Product Role

The profile icon/header menu should provide access to:

- Profile editing
- Partner linking
- Private albums
- Subscription and Store
- Privacy and visibility
- Grid settings
- Swipe/discovery settings
- Activity settings
- Message settings
- Notifications
- Verification
- Safety center
- Blocked/hidden users
- Discreet app icon
- Data/export/delete account
- Logout

It should not compete with Grid, Swipe, Activity, Messages, or Store as a primary engagement surface.

## Header Avatar Behavior

The avatar appears in the compact top header across primary app surfaces.

Header pattern:

- Left: user avatar
- Center: current context pill
- Right: context action

Avatar tap opens the profile/settings hub.

Avatar long press can optionally open quick privacy actions later:

- Go incognito
- Hide distance
- Pause discovery
- Open discreet mode

Do not put too many quick actions on long press in MVP.

## Profile / Settings Hub

The hub should be a clean management screen, not a feed.

Recommended top section:

- User avatar/photo
- Display name
- Verification badge
- Subscription badge
- Linked partner preview, if visible
- Edit profile button

Recommended sections:

- Profile
- Partner
- Albums
- Subscription
- Privacy
- Discovery
- Messages
- Activity
- Notifications
- Safety
- App settings
- Account

Keep section labels plain.

## Profile Section

Profile management includes:

- Edit public profile
- Manage public photos
- Bio
- Display name
- Age display controls, where legally/product-wise appropriate
- Gender/orientation fields
- Relationship context
- Discovery style
- Interested in
- Lifestyle tags
- Logistics tags
- Social links, if supported later
- Preview profile

Rules:

- Profile fields should use structured data for filters.
- Do not rely on bio text for filtering.
- Lifestyle tags should be optional.
- High-sensitivity lifestyle terms should be age-gated.
- Users should be able to preview how others see them.

## Partner Section

Partner management includes:

- Link partner
- View linked partner
- Partner visibility
- Invite partner
- Unlink partner
- Partner chat invite settings
- Linked-partner subscription benefit status

### Partner Link Rules

- Both users must consent to link.
- Either user can unlink.
- Link does not merge accounts.
- Link does not grant chat/media access.
- Link does not automatically mean looking together.
- Link visibility is user-controlled.

### Partner Visibility Options

- Show partner publicly
- Show partner to matches only
- Show partner to verified users only
- Hide partner

### Partner Chat Invite Settings

- Allow partner invites from my chats
- Ask every time
- Never allow partner invites

Do not allow silent partner adds.

## Albums Section

Album management includes:

- Public photos
- Private album
- Upload media
- Reorder media
- Request/grant history
- Active grants
- Timed grants
- Revoked grants
- Expiring photos

### Private Album Rules

- Private album belongs to one individual user.
- Linked partner access is not automatic.
- Access can be granted per user or chat.
- Access can be revoked anytime.
- Timed access should show expiration.
- Signed URLs should be short-lived.
- Public photos require moderation.

Album settings:

- Who can request access
  - Everyone
  - Chats only
  - Matches only
  - Verified only
  - No one
- Default grant duration
- Allow expiring photos

## Subscription Section

Subscription management includes:

- Current tier
- Benefits
- Renewal/expiration
- Restore purchases
- Manage subscription
- Linked partner benefit
- Boost inventory
- Pineapple Like/Super Like inventory
- Intro Note inventory
- Extra Picks inventory

### Linked Partner Subscription Benefit

Show:

- Beneficiary linked partner
- Whether benefit is active
- What tier is shared
- When benefit ends
- Cooldown/eligibility warnings

Rules:

- Server-authoritative.
- One active linked partner beneficiary at a time.
- Benefit ends when link ends.
- Cooldowns prevent abuse.

## Privacy Section

Privacy settings include:

- Show me in Grid
- Show me in Swipe
- Show me in Explore/Travel searches
- Show distance
- Hide exact distance
- Incognito
- Show me in Viewed lists
- Show linked partner
- Profile visibility
- App lock / PIN
- Discreet app icon

### Visibility Controls

Recommended controls:

- Visible in Grid
- Visible in Swipe
- Visible in Events
- Visible while traveling
- Pause discovery

Pause discovery should:

- Stop showing user to new people.
- Preserve existing matches/chats.
- Preserve account.

### Viewed List Tradeoff

If user disables "Show me in Viewed lists":

- They stop appearing in other users' Viewed lists.
- Their own Viewed list is disabled.
- Existing product-visible Viewed history is deleted.

## Discovery Section

Discovery settings are cross-mode preferences.

Include:

- Current location
- Travel mode location
- Age range
- Distance
- Interested in
- Discovery style
- Dealbreakers
- Saved filters

Grid-specific settings should link to Grid filters.

Swipe-specific settings should link to Swipe filters.

Do not duplicate every filter inside settings. Settings are defaults; filters are working controls.

## Grid Settings

Grid settings include:

- Default radius
- Default filters
- Show distance
- Online status visibility
- Grid message permissions
- Hide users
- Unhide users
- Recently hidden profiles

Rules:

- Grid filters remain on Grid.
- Settings define defaults and privacy.
- Hidden profiles are not the same as blocked users.

## Swipe Settings

Swipe settings include:

- Discovery on/off
- Age range
- Distance
- Dealbreakers
- Who to see
- Solo/together/social preference
- Verified preference
- Travel mode
- Rewind availability
- Intro Note receiving rules

Rules:

- Swipe messaging remains match-gated.
- Intro Notes are exceptions and can be disabled/restricted.

## Activity Settings

Activity settings include:

- Show me in Viewed lists
- Allow flirts
- Allow repeat-view signals
- Allow Likes You reveal
- Activity notification detail
- Viewed list visibility tradeoff explanation

Flirt controls:

- Everyone
- Verified only
- People matching my intent
- No one

## Message Settings

Message settings include:

- Grid message permissions
- Verified-only messages
- Allow media messages
- Allow voice messages
- Read receipts, if premium
- Typing status, if premium
- Saved phrases, if supported
- Message request filtering
- Partner invite settings

Grid message permissions:

- Anyone
- Verified users only
- People matching my intent
- Nearby only
- No first messages, flirts only

Swipe chat remains match-gated regardless of this setting.

## Notification Settings

Notification categories:

- Messages
- Matches
- Activity
- Flirts
- Album requests
- Partner invites
- Events
- Boost results
- Picks expiring
- Safety/account

Detail levels:

- Private: generic only
- Standard: names for messages/matches
- Detailed: names and activity types

Default should be privacy-conscious.

Avoid explicit lifestyle terms in push notifications by default.

## Verification Section

Verification includes:

- Age verification
- Photo verification
- ID + photo verification, if supported
- Linked partner verification state
- Verification badge explanation

Rules:

- Verification improves trust but does not guarantee safety.
- Verification status should be clear but not overpromised.
- Verification can gate sensitive features where required.

## Safety Center

Safety center includes:

- Community guidelines
- Consent and boundaries
- Block/report explanation
- Scam awareness
- Meeting safety
- Location privacy explanation
- Private album safety
- Partner linking safety
- Contact support

Safety center should be accessible from profile/settings and relevant flows.

## Blocked And Hidden Users

Separate:

- Blocked users
- Hidden profiles

Blocked:

- Removes mutual visibility where possible.
- Prevents contact.
- Removes or restricts Activity visibility.
- Applies across grid/swipe/activity/messages.

Hidden:

- Removes profile from user's grid/deck.
- Does not necessarily imply abuse.
- Can be undone.

Settings should include:

- View blocked users
- Unblock user
- View hidden profiles
- Unhide profile

## App Settings

App settings include:

- Theme
- Units: miles/km
- Language
- Accessibility
- App lock / PIN
- Biometric unlock
- Discreet app icon
- Cache/media storage
- Help/support
- About

Discreet app icon should be considered a free privacy feature.

## Account Section

Account settings include:

- Email/phone/login methods
- Password/auth provider
- Restore purchases
- Data export
- Delete account
- Logout

### Delete Account

Delete account must be clear and compliant.

Rules:

- Explain what is deleted.
- Explain what may be retained for safety/legal reasons.
- Confirm intentionally.
- Revoke sessions.
- Remove profile from discovery.

### Logout

Logout should not delete account.

Offer:

- Logout
- Logout and keep local media cache, if safe
- Logout and clear local cache

## Information Architecture

Recommended settings list:

```text
Profile
  Edit profile
  Public photos
  Preview profile

Partner
  Link/manage partner
  Partner visibility
  Partner invite settings

Albums
  Private album
  Album access
  Expiring photos

Subscription
  Current plan
  Linked partner benefit
  Boosts and premium inventory
  Restore purchases

Privacy
  Visibility
  Distance
  Incognito
  Viewed list
  App lock

Discovery
  Grid defaults
  Swipe defaults
  Travel mode
  Saved filters

Messages
  Who can message me
  Verified-only messages
  Read receipts
  Typing status

Activity
  Viewed settings
  Flirt settings
  Activity notifications

Notifications
  Notification categories
  Detail level

Safety
  Safety center
  Blocked users
  Hidden profiles
  Report history, if supported

App
  Theme
  Units
  Language
  Discreet app icon
  Cache

Account
  Login methods
  Data export
  Delete account
  Logout
```

## Copy Guidelines

Use:

- Link partner
- Unlink partner
- Partner visible
- Partner hidden
- Private album
- Request access
- Grant access
- Revoke access
- Hide distance
- Pause discovery
- Incognito

Avoid:

- Couple account
- Merge profiles
- Add your other half
- Naughty album
- Secret pics
- Hot settings
- Spice up profile
- Bring partner into the fun

## Implementation Notes

Settings should be server-backed where they affect discovery, privacy, messaging, Activity, or safety.

Server-authoritative:

- Visibility
- Distance privacy
- Viewed list opt-in
- Partner link settings
- Album request settings
- Grid message permissions
- Swipe discovery settings
- Notification detail level
- Subscription state
- Blocked users
- Hidden users

Client-local:

- Theme
- App lock/PIN, with secure storage
- Biometric unlock
- Cache controls
- Local notification presentation preferences, where applicable

## Implementation Checklist

- [x] Header avatar opens profile/settings hub.
- [x] Bottom nav does not include Profile.
- [x] Profile editing exists.
- [x] Partner management exists.
- [x] Private album management exists.
- [x] Subscription and linked-partner benefit management exists.
- [x] Privacy controls exist.
- [x] Grid defaults and message permissions exist.
- [x] Swipe discovery settings exist.
- [x] Activity settings exist.
- [x] Notification detail levels exist.
- [ ] Verification flow entry exists.
- [ ] Safety center exists.
- [ ] Blocked and hidden users are separate.
- [ ] Discreet app icon exists or is planned.
- [ ] Partial: logout exists; data export and account deletion are not implemented.
- [x] Server-authoritative settings are not only local.

## Do Not Implement

- Do not make Profile a bottom nav tab.
- Do not put discovery feeds inside the avatar menu.
- Do not merge profile settings with Store only.
- Do not treat hidden users as blocked users.
- Do not let linked partners manage each other's account settings.
- Do not make private album access automatic for linked partners.
- Do not bury block/report controls.
- Do not make notification copy explicit by default.
- Do not make safety controls premium.
- Do not store server-authoritative privacy settings only on device.
