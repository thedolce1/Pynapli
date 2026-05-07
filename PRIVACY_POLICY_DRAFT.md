# Pynapli Privacy Policy Draft

Last updated: 2026-04-24

## Legal Review Required

This is a product/engineering draft, not final legal text. A privacy lawyer must review before launch.

## Purpose

This draft defines the privacy commitments Pynapli's product and engineering implementation must support.

Core rule:

> Pynapli handles sensitive lifestyle, location, relationship, messaging, and media data. Collect less, protect more, and give users control.

## Data We Collect

Account data:

- Login identifiers
- Display name
- Date of birth / age verification state
- Auth provider IDs
- Device/session data

Profile data:

- Photos
- Bio
- Gender/orientation fields
- Relationship context
- Lifestyle tags users choose
- Discovery preferences

Location data:

- Current approximate discovery location
- Travel/passport location if enabled
- Zone/city information

Interaction data:

- Grid views/profile opens
- Likes/flirts/matches
- Activity events
- Messages metadata
- Album access requests/grants
- Partner link state
- Psync and Playing Tonight sessions

Payment data:

- Subscription status
- Store purchase identifiers
- Entitlements
- Premium inventory

Safety data:

- Reports
- Blocks
- Moderation actions
- Risk/anti-abuse signals
- Verification state

## Sensitive Data Commitments

Pynapli should not:

- Sell sensitive lifestyle data.
- Send exact location to analytics.
- Use verification data for advertising.
- Automatically share private albums with linked partners.
- Treat linked partner status as shared account control.
- Store raw verification media longer than necessary.

## Location

Pynapli uses location to power:

- Grid
- Distance labels
- Travel mode
- Playing Tonight zones
- Safety and abuse prevention

Rules:

- Use while-in-use location by default.
- Do not store long-term exact location history by default.
- Allow users to hide distance.
- Use approximate/zone-based location for maps.
- Do not expose exact coordinates to other users.

## Private Albums And Media

Private albums:

- Are controlled by the owner.
- Require explicit grants.
- Can be revoked anytime.
- Are not automatically shared with linked partners.

Public photos:

- Are moderated before public visibility.

## Messages

Messages are private between participants, subject to:

- Delivery
- Safety controls
- User reports
- Legal obligations
- Abuse detection where applicable

Pynapli should not use message contents for advertising.

## Linked Partners

Linked partners remain separate accounts.

Partner linking may show relationship context depending on settings, but does not grant:

- Account control
- Message access
- Album access
- Activity access
- Settings control

## Analytics

Pynapli may collect privacy-safe analytics to improve the app.

Analytics must not include:

- Message text
- Private album URLs
- Exact location
- Raw verification data
- Sensitive lifestyle labels tied to a user by default

## Data Sharing

Pynapli may share data with service providers for:

- Hosting
- Push notifications
- Payments
- Crash reporting
- Moderation
- Age verification
- Security/anti-abuse

Service providers should receive only what they need.

## User Controls

Users should be able to:

- Edit profile
- Hide distance
- Pause discovery
- Control Grid/Swipe visibility
- Control Viewed list participation
- Revoke album access
- Block users
- Report users/content
- Unlink partner
- Delete account
- Request data export where required

## Data Retention

Retention should be defined by category:

- Account data: while account exists, subject to deletion/legal holds.
- Location: current only by default.
- Activity: product-visible retention windows.
- Messages: retained unless deleted/expired, subject to safety/legal retention.
- Reports/moderation: retained for safety/legal period.
- Verification raw data: deleted as soon as possible after result.

## Account Deletion

Account deletion should:

- Remove user from discovery.
- Revoke sessions.
- End partner benefits.
- Remove or anonymize profile data where required.
- Retain limited safety/legal records where necessary.

## Children

Pynapli is 18+ and not intended for minors.

## Contact

Privacy contact placeholder:

- privacy@pynapli.com

Security contact placeholder:

- security@pynapli.com

## Implementation Checklist

- [x] Privacy controls exist in settings.
- [ ] Account deletion exists.
- [ ] Data export process defined.
- [ ] Analytics redaction rules implemented.
- [ ] Crash reporting redaction rules implemented.
- [x] Location retention minimized.
- [x] Private album grants revocable.
- [x] Linked partners do not share data automatically.
