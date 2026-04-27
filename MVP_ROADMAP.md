# Pynapli MVP Roadmap

Last updated: 2026-04-24

## Purpose

This document defines what Pynapli should build first, what belongs in beta, what belongs in v1, and what should wait.

Core rule:

> MVP must prove the grid, swipe, linked partners, messaging, trust/safety, and monetization loop. Everything else is secondary.

## Release Philosophy

Pynapli is a network app. The first version must be useful with limited density, safe enough for real users, and technically strong enough that the grid feels fast.

Do not build every advanced feature before testing the market.

## Phase 0: Foundation

Goal:

- Build the technical and safety foundation.

Must include:

- Ktor backend skeleton
- PostgreSQL/PostGIS
- Redis
- Auth/session model
- User/profile model
- Public photo upload + moderation pending state
- Location ingestion
- Basic grid query
- Basic profile view
- Block/report
- Admin/moderation skeleton
- Client app shell with bottom nav
- Header avatar/settings hub

Exit criteria:

- Test accounts can sign up, appear in grid, view profiles, and block/report.
- Grid uses real PostGIS location query.
- Admin can review reports and photos.

## Phase 1: Private MVP

Goal:

- Prove the core Grindr + Tinder product loop.

Must include:

- Individual profiles
- Nearby Grid
- Minimal grid cards
- Grid messaging
- Swipe deck
- Match-gated swipe chat
- Activity basic: views, flirts, likes, matches
- Basic filters
- Private albums basic request/grant/revoke
- Partner linking/unlinking
- Partner mini-icon on cards
- Partner invite to chat
- Push notifications for messages/matches/invites
- Basic subscription entitlement model
- Free/Social/Select feature gates in code, even if payments are test-only

Exit criteria:

- A small test group can discover, message, swipe, match, link partners, invite partner to chat, and use private albums.

## Phase 2: Safety Beta

Goal:

- Make the app safe enough for invitation-only real users.

Must include:

- Moderation queue
- Report handling
- Age gate
- Basic verification flow placeholder or provider integration where needed
- Risk scoring v1
- Rate limits
- Message spam throttles
- Album request throttles
- Block/hide lists
- Notification privacy settings
- Account deletion
- Admin audit logs

Exit criteria:

- Abuse paths are tested.
- Reports route to admin.
- Block/report works across grid, swipe, activity, and messages.
- No unmoderated public photos go live.

## Phase 3: Linked Partner Differentiators

Goal:

- Make Pynapli clearly different from Grindr/Tinder.

Must include:

- Psync v1
- Playing Tonight v1
- Linked-partner subscription benefit
- Partner link management in settings
- Activity integration for Psync/Tonight
- Better partner consent flows

Exit criteria:

- Linked users can run a Psync session.
- Linked-together users can activate Playing Tonight with both approvals.
- Subscription benefit correctly grants linked partner access.

## Phase 4: Monetization Beta

Goal:

- Validate willingness to pay without overpricing.

Must include:

- Store tab
- Free/Social/Select paywalls
- App Store / Play Billing test integration
- Restore purchases
- Boost
- Pineapple Likes
- Intro Notes
- Activity paywall
- Advanced filter gates
- Linked partner benefit abuse prevention

Exit criteria:

- Purchases validate server-side.
- Entitlements update on both clients.
- Linked partner benefit works and ends correctly.

## Phase 5: Toronto/GTA Private Beta

Goal:

- Prove real-world density and retention.

Must include:

- Production moderation workflow
- Analytics/metrics
- Crash reporting
- Performance monitoring
- Referral codes basic
- Venue/event code basic
- Support workflow
- Launch website or invite page

Exit criteria:

- Grid load target met.
- Message delivery target met.
- Reports handled within SLA.
- Day 7 retention and linked-partner adoption are measurable.

## V1 Launch

Goal:

- Public-ready app in selected market.

Must include:

- Full subscription flow
- Stable admin console
- Strong notification preferences
- Account/session management
- Data export/delete where required
- Community guidelines
- Privacy policy
- Terms
- App-store-safe screenshots/copy
- Production incident response

## Later / Not MVP

Defer:

- Full venue marketplace
- Complex event ticketing
- Public Psync badges
- Psync Score
- AI matching explanations
- Full web app parity
- Advanced maps
- Multi-partner constellations beyond one linked partner
- Complex in-app forums/groups

## MVP Non-Negotiables

- No couple accounts.
- No couple grid cards.
- Grid must be fast.
- Grid scroll state must persist.
- Swipe must be match-gated.
- Partner invite must require consent.
- Private album revocation must be free.
- Block/report must work everywhere.
- Subscriptions must be server-authoritative.
- Safety and age gate must exist before real users.

