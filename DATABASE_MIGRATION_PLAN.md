# Pynapli Database Migration Plan

Last updated: 2026-04-24

## Purpose

This document defines the initial migration sequence for Pynapli's PostgreSQL/PostGIS database.

Core rule:

> Create stable identity, privacy, safety, and entitlement foundations before discovery and monetization features depend on them.

## Tooling

Recommended:

- Flyway or Liquibase
- PostgreSQL
- PostGIS extension
- Repeatable migrations only for views/functions
- Versioned migrations for schema changes

Migration naming:

```text
V001__extensions_and_enums.sql
V002__users_auth_sessions.sql
V003__profiles_settings_photos.sql
...
```

## Migration Order

### V001 Extensions And Core Types

Create:

- PostGIS extension
- UUID generation support if needed
- Core enum types

Enums:

- user status
- moderation status
- visibility status
- subscription tier
- risk tier
- thread type
- activity type
- session status

### V002 Users And Auth

Create:

- `users`
- `auth_identities`
- `user_sessions`

Indexes:

- provider subject unique index
- session `jwt_jti`
- user status

### V003 Profiles And Settings

Create:

- `profiles`
- `profile_tags`
- `profile_photos`
- `discovery_settings`
- `filter_presets`

Indexes:

- profile visibility/moderation
- tag lookup
- primary photo lookup

### V004 Location

Create:

- `user_locations`
- `travel_locations`

Indexes:

- GiST on `geom`
- geohash
- updated_at

### V005 Blocks And Hides

Create:

- `blocks`
- `hidden_profiles`

Indexes:

- unique blocker/blocked
- reverse lookup for exclusions

### V006 Partner Links

Create:

- `partner_links`

Constraints:

- prevent self-link
- enforce one active partner link per user at launch

### V007 Grid And Swipe

Create:

- `swipe_deck_sessions`
- `swipe_deck_cards`
- `swipe_actions`
- `matches`

Indexes:

- deck session cards
- actor/target swipe actions
- target likes
- match participants

### V008 Messages

Create:

- `chat_threads`
- `chat_participants`
- `messages`
- `message_receipts`
- `partner_chat_invites`

Indexes:

- participant thread lookup
- thread last message
- message created_at

### V009 Albums And Media

Create:

- `albums`
- `album_media`
- `album_grants`

Indexes:

- owner
- recipient grants
- active grants

### V010 Activity And Notifications

Create:

- `activity_events`
- `notification_events`
- `push_tokens`

Indexes:

- recipient + created_at desc
- event type
- notification status

### V011 Subscriptions And Inventory

Create:

- `subscriptions`
- `entitlements`
- `linked_partner_benefits`
- `premium_inventory`
- `boosts`

Indexes:

- user entitlement active lookup
- external subscription ID
- active boosts by region/surface if region stored

### V012 Trust Safety Moderation

Create:

- `account_risk_states`
- `risk_events`
- `reports`
- `moderation_cases`
- `moderation_actions`
- `age_verifications`

Indexes:

- reports by status/priority
- cases by status/priority
- risk events by user/time

### V013 Psync

Create:

- `psync_sessions`
- `psync_participants`
- `psync_actions`
- `psync_recaps`

Indexes:

- partner link active sessions
- session participants
- session actions

### V014 Playing Tonight

Create:

- `playing_tonight_sessions`

Indexes:

- active sessions by expires_at
- zone/status
- owner
- partner link

### V015 Admin

Create:

- `admin_users`
- `admin_audit_log`

Indexes:

- admin role/status
- audit target
- audit admin/time

## Required Database Functions / Views

Possible repeatable migrations:

- active entitlement view
- active partner link view
- active playing tonight view
- blocked user exclusion helper
- current user discovery eligibility view

Do not hide too much business logic in SQL functions early. Ktor should remain understandable.

## Data Retention Jobs

Scheduled cleanup jobs:

- Expired album grants
- Expired push tokens
- Expired Psync sessions
- Expired Playing Tonight sessions
- Old notification events
- Old product-visible Activity events
- Risk short-window cleanup
- Deleted account anonymization/deletion process

## Migration Safety Rules

- Never drop columns with data without a backup and staged rollout.
- Add nullable columns first, backfill, then enforce not-null.
- Use concurrent indexes where production size requires it.
- Backward-compatible migrations before app deploys.
- App deploy reads both old/new fields during migration windows where needed.

## Seed Data

Staging seed:

- Test users
- Test linked partners
- Test profiles/photos
- Test grid locations
- Test subscription entitlements
- Test admin accounts
- Test moderation cases

Production seed:

- Admin roles
- System settings
- Community guideline version
- Default feature flags

## Implementation Checklist

- [ ] Migration tool selected.
- [ ] PostGIS extension migration.
- [ ] Core users/auth migration.
- [ ] Profile/location migrations.
- [ ] Blocks before discovery.
- [ ] Partner links before Psync/Tonight.
- [ ] Subscriptions before paid gates.
- [ ] Risk/moderation before beta.
- [ ] Admin audit before admin console.
- [ ] Cleanup jobs defined.

## Do Not Implement

- Do not start with feature tables before users/privacy/safety.
- Do not store exact location history by default.
- Do not store raw verification media.
- Do not store private media as public URLs.
- Do not make destructive migrations without a staged plan.

