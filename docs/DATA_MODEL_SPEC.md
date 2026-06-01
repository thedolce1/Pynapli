# Pynapli Data Model Spec

Last updated: 2026-05-13

## Purpose

This document defines the canonical data model for Pynapli's backend.

Core rule:

> The database should model autonomous users first. Partner links, sessions, chats, subscriptions, and discovery are relationships around users, not replacements for users.

## Storage Stack

Authoritative storage:

- PostgreSQL
- PostGIS

Ephemeral/cache:

- Redis

Media:

- Object storage for originals/private media
- CDN for public thumbnails and approved public media variants

Analytics:

- Privacy-safe event pipeline
- Do not use analytics as authoritative product state

## ID Strategy

Recommended:

- UUIDv7 or ULID-style sortable IDs for primary entities.
- Avoid exposing sequential database IDs.

Every table should include:

- `id`
- `created_at`
- `updated_at`

Where useful:

- `deleted_at` for soft deletion
- `created_by_user_id`
- `status`

## Core User Tables

### users

Authoritative account record.

Fields:

- `id`
- `email_hash`
- `phone_hash`
- `firebase_uid`, nullable if Firebase Auth is used
- `status`: active, paused, suspended, banned, deleted
- `adult_confirmed_at`
- `terms_accepted_at`
- `terms_version`
- `privacy_accepted_at`
- `privacy_version`
- `created_at`
- `updated_at`
- `deleted_at`

Rules:

- A user is always one autonomous person.
- Never model a couple as a user.
- Account-level age/terms/privacy acceptance is stored on the user; profile DOB/display age remains on the profile for discovery/profile behavior.

### auth_identities

Login providers.

Fields:

- `id`
- `user_id`
- `provider`: email, apple, google, firebase
- `provider_subject`
- `created_at`
- `last_used_at`

Indexes:

- unique `(provider, provider_subject)`

### user_sessions

Active auth sessions/devices.

Fields:

- `id`
- `user_id`
- `device_id`
- `jwt_jti`
- `refresh_token_hash`
- `platform`
- `app_version`
- `last_seen_at`
- `revoked_at`

Indexes:

- `user_id`
- `jwt_jti`

### account_data_export_requests

Server-backed data export requests.

Fields:

- `id`
- `user_id`
- `status`: requested, processing, ready, failed, expired
- `requested_at`
- `completed_at`
- `expires_at`
- `download_url`
- `error_message`

Rules:

- Request creation is immediate and server-authoritative.
- Archive generation/download URL delivery is asynchronous.

### account_deletion_events

Audit record for user-requested account deletion.

Fields:

- `id`
- `user_id`
- `requested_at`
- `completed_at`
- `revoked_session_count`
- `retention_note`

Rules:

- Account deletion soft-deletes `users` (`status='deleted'`, `deleted_at`) and the row is retained for referential integrity of preserved safety/legal records.
- Profile and discovery settings are removed from discovery surfaces.
- GDPR/CCPA erasure runs in the same transaction: identity hashes (`email_hash`/`phone_hash`/`firebase_uid`) are nulled, `auth_identities` credentials are cleared and `provider_subject` is tombstoned, profile PII (display name, bio, birthdate, gender, orientation) is anonymized, the exact `user_locations` row is hard-deleted, owned `media_assets` are soft-deleted, and `user_profile_links` + `linked_partner_benefits` are ended on both sides.
- Safety/legal retention data (reports, the `account_deletion_events` audit) is preserved where required.

## Profile Tables

### profiles

Public and semi-public profile state.

Fields:

- `user_id`
- `display_name`
- `birthdate_encrypted` or age-verification-derived DOB where allowed
- `display_age`
- `bio`
- `gender`
- `orientation`
- `relationship_context`
- `discovery_style`: solo, together, both, social, ask_in_chat
- `experience_level`
- `profile_completeness_score`
- `moderation_status`: pending, approved, limited, rejected
- `visibility_status`: visible, paused, hidden
- `verified_at`, nullable trust timestamp
- `face_photo_verified_at`, nullable trust timestamp
- `age_verified_at`, nullable age-assurance timestamp

Rules:

- Bio is not a filter source.
- Filterable fields must be structured.

### profile_tags

Structured tags.

Fields:

- `id`
- `user_id`
- `tag_key`
- `category`: lifestyle, intent, logistics, safety, interest
- `visibility`: public, matches_only, hidden

Indexes:

- `user_id`
- `(tag_key, category)`

### profile_photos

Public profile photos.

Fields:

- `id`
- `user_id`
- `storage_key`
- `thumbnail_url`
- `position`
- `moderation_status`
- `is_primary`
- `created_at`

Rules:

- Public photos require moderation before public visibility.
- Grid uses thumbnail only.

## Partner Linking

### partner_links

Mutual linked-partner relationship.

Fields:

- `id`
- `user_a_id`
- `user_b_id`
- `status`: requested, active, denied, unlinked, expired
- `requested_by_user_id`
- `accepted_at`
- `unlinked_at`
- `visibility_a`: public, matches_only, verified_only, hidden
- `visibility_b`: public, matches_only, verified_only, hidden

Rules:

- Unique active link per user at launch.
- Link does not merge accounts.
- Link does not grant chat/media access.

Indexes:

- `user_a_id`
- `user_b_id`
- partial unique active link per user

## Discovery Settings

### discovery_settings

User-level discovery preferences.

Fields:

- `user_id`
- `grid_visible`
- `swipe_visible`
- `events_visible`
- `show_distance`
- `show_in_viewed_lists`
- `incognito`
- `allow_grid_messages`
- `grid_message_policy`
- `allow_flirts`
- `allow_intro_notes`
- `allow_album_requests`
- `solo_enabled`
- `together_enabled`
- `social_enabled`

### filter_presets

Saved filters.

Fields:

- `id`
- `user_id`
- `surface`: grid, swipe, psync, playing_tonight
- `name`
- `filter_json`
- `is_default`

Rules:

- JSON is acceptable for saved presets, but active filterable profile data must still be structured/indexed.

## Location

### user_locations

Current discovery location.

Fields:

- `user_id`
- `geom geography(Point, 4326)`
- `geohash`
- `city`
- `region`
- `country`
- `location_source`: gps, network, travel, manual_city
- `accuracy_meters`
- `updated_at`

Rules:

- Store current/eligible discovery location, not long-term location history by default.
- Exact raw location must not be exposed to clients.

Indexes:

- GiST on `geom`
- `geohash`
- `updated_at`

### travel_locations

Passport/travel mode.

Fields:

- `id`
- `user_id`
- `city`
- `region`
- `country`
- `geom`
- `active_from`
- `active_until`
- `status`

## Grid

Grid does not need a permanent "grid cards" table. Cards are query results from profiles, location, presence, partner links, subscriptions, filters, and trust state.

Optional materialized/cache tables later:

- `grid_candidate_snapshots`
- `grid_boost_slots`

## Swipe

### swipe_deck_sessions

Server-generated deck sessions.

Fields:

- `id`
- `user_id`
- `surface`: swipe, psync
- `filters_hash`
- `status`
- `generated_at`
- `expires_at`
- `cursor`

### swipe_deck_cards

Cards served in deck session.

Fields:

- `id`
- `deck_session_id`
- `target_user_id`
- `card_token`
- `position`
- `rank_score`
- `served_at`

Indexes:

- `deck_session_id`
- unique `(deck_session_id, target_user_id)`
- unique `card_token`

### swipe_actions

User decisions.

Fields:

- `id`
- `actor_user_id`
- `target_user_id`
- `deck_session_id`
- `card_token`
- `action`: like, pass, pineapple_like, intro_note
- `intent_scope`: solo, together, social, ask_in_chat
- `note_text`, nullable
- `created_at`
- `reversed_at`, nullable

Indexes:

- `(actor_user_id, target_user_id)`
- `target_user_id, created_at`

### matches

Mutual match state.

Fields:

- `id`
- `match_type`: solo, together, psync_together
- `user_a_id`
- `user_b_id`
- `partner_link_id`, nullable
- `source`: swipe, psync, flirt
- `status`: active, unmatched, blocked, expired
- `created_at`

Rules:

- Together matches still reference individual users.
- Target consent is required for together-mode matching.

## Psync

### psync_sessions

Synchronized linked-partner swipe sessions.

Fields:

- `id`
- `partner_link_id`
- `initiator_user_id`
- `status`: invited, configuring, ready, active, paused, completed, expired, cancelled
- `filters_json`
- `deck_session_id`
- `deck_size`
- `created_at`
- `started_at`
- `expires_at`
- `completed_at`

### psync_participants

Fields:

- `id`
- `session_id`
- `user_id`
- `status`: invited, accepted, denied, active, paused, left
- `accepted_at`
- `left_at`

### psync_actions

Fields:

- `id`
- `session_id`
- `actor_user_id`
- `target_user_id`
- `card_token`
- `action`
- `created_at`

### psync_recaps

Fields:

- `id`
- `session_id`
- `viewer_user_id`
- `profiles_reviewed`
- `both_liked_count`
- `together_match_count`
- `solo_match_count_for_viewer`
- `created_at`

Rules:

- No Psync Score.
- Recaps are aggregate.
- Solo passthrough private by default.

## Playing Tonight

### playing_tonight_sessions

Short-lived availability sessions.

Fields:

- `id`
- `owner_user_id`
- `partner_link_id`, nullable
- `mode`: solo, together, social
- `status`: pending_partner_approval, active, ended, expired, cancelled
- `intent`
- `hosting_status`
- `location_precision`: zone_only, map_list
- `zone_id`
- `note`
- `session_photo_id`, nullable
- `starts_at`
- `expires_at`
- `ended_at`

Rules:

- Linked-together mode requires both partners to approve.
- No couple grid cards.
- No precise map pins.

## Messages

### chat_threads

Fields:

- `id`
- `thread_type`: solo, match, partner_invited, psync, event
- `source_id`, nullable
- `status`: active, archived, blocked
- `created_at`
- `last_message_at`

### chat_participants

Fields:

- `id`
- `thread_id`
- `user_id`
- `role`: participant
- `status`: active, invited, denied, left, removed
- `joined_at`
- `left_at`

Indexes:

- `thread_id`
- `user_id`

### messages

Fields:

- `id`
- `thread_id`
- `sender_user_id`
- `message_type`: text, media, system, intro_note
- `body_encrypted`
- `status`: sent, delivered, held_for_review, removed, failed
- `created_at`
- `deleted_at`

### message_receipts

Fields:

- `id`
- `message_id`
- `user_id`
- `delivered_at`
- `read_at`
- `revealed_at`, for media reveal

### partner_chat_invites

Fields:

- `id`
- `thread_id`
- `inviter_user_id`
- `invited_partner_user_id`
- `third_party_status`
- `partner_status`
- `created_at`
- `resolved_at`

Rules:

- Partner and third party must approve before partner joins.

## Albums And Media

### albums

Fields:

- `id`
- `owner_user_id`
- `album_type`: private
- `status`
- `created_at`

### album_media

Fields:

- `id`
- `album_id`
- `owner_user_id`
- `storage_key`
- `thumbnail_key`
- `moderation_status`
- `position`
- `created_at`

### album_grants

Fields:

- `id`
- `album_id`
- `owner_user_id`
- `recipient_user_id`
- `thread_id`, nullable
- `status`: active, revoked, expired
- `granted_at`
- `expires_at`
- `revoked_at`

Rules:

- Linked partner access is not automatic.
- Revocation is free and immediate.

## Activity

### activity_events

Fields:

- `id`
- `recipient_user_id`
- `actor_user_id`, nullable
- `event_type`
- `source_id`, nullable
- `visibility`: visible, blurred, count_only, hidden
- `metadata_json`
- `created_at`
- `expires_at`

Indexes:

- `(recipient_user_id, created_at desc)`
- `event_type`

Rules:

- Activity is not Messages.
- Incognito/viewed-list settings affect visibility.

## Subscriptions And Monetization

### subscriptions

Fields:

- `id`
- `user_id`
- `platform`: app_store, play_store, stripe
- `external_subscription_id`
- `tier`: free, social, select
- `status`
- `current_period_start`
- `current_period_end`
- `created_at`

### entitlements

Fields:

- `id`
- `user_id`
- `tier`
- `source`: own_subscription, linked_partner_benefit, promo, admin
- `source_id`
- `starts_at`
- `ends_at`
- `status`

### linked_partner_benefits

Fields:

- `id`
- `subscriber_user_id`
- `beneficiary_user_id`
- `partner_link_id`
- `tier`
- `status`
- `starts_at`
- `ends_at`

Rules:

- One active beneficiary per subscriber.
- Ends when partner link ends.

### premium_inventory

Fields:

- `id`
- `user_id`
- `inventory_type`: boost, super_boost, pineapple_like, intro_note, extra_pick
- `quantity`
- `source`
- `expires_at`

Implemented table:

- `premium_inventory`

### boosts

Fields:

- `id`
- `user_id`
- `boost_type`
- `surface`: grid, swipe, playing_tonight
- `starts_at`
- `ends_at`
- `status`
- `result_json`

## Trust, Safety, Moderation

### account_risk_states

Fields:

- `user_id`
- `score`
- `tier`: clean, watch, friction, quarantine, terminate
- `updated_at`
- `last_reviewed_at`

### risk_events

Fields:

- `id`
- `user_id`
- `event_type`
- `score_delta`
- `metadata_json`
- `created_at`

### reports

Fields:

- `id`
- `reporter_user_id`
- `reported_user_id`
- `target_type`: profile, message, media, album, session
- `target_id`
- `category`
- `note`
- `status`
- `created_at`

### moderation_cases

Fields:

- `id`
- `subject_user_id`
- `case_type`
- `priority`
- `status`
- `assigned_moderator_id`
- `created_at`
- `resolved_at`

### moderation_actions

Fields:

- `id`
- `case_id`
- `moderator_id`
- `action`
- `reason`
- `created_at`

### age_verifications

Fields:

- `id`
- `user_id`
- `market`
- `method`
- `provider`
- `provider_reference`, nullable
- `status`
- `requested_at`
- `verified_at`
- `expires_at`
- `reviewed_at`
- `reviewer_id`
- `review_reason`
- `created_at`
- `updated_at`

Indexes:

- `(user_id, created_at desc)`
- `(status, created_at desc)`
- unique `(provider, provider_reference)` where provider reference exists

Rules:

- Do not store raw ID/biometric data unless legally required and reviewed.
- Successful verification also updates `profiles.age_verified_at`; discovery filters use the profile timestamp, not raw provider data.

## Blocks And Hides

### blocks

Fields:

- `id`
- `blocker_user_id`
- `blocked_user_id`
- `reason`, nullable
- `created_at`

Indexes:

- unique `(blocker_user_id, blocked_user_id)`
- reverse lookup for exclusion

### hidden_profiles

Fields:

- `id`
- `hider_user_id`
- `hidden_user_id`
- `created_at`

Rules:

- Hidden is not blocked.
- Hidden is a one-way discovery exclusion.
- Hidden applies across discovery surfaces where applicable.

## Notifications

### notification_events

Fields:

- `id`
- `user_id`
- `event_type`
- `source_id`
- `detail_level`
- `status`: pending, sent, failed, suppressed, queued_quiet_hours
- `created_at`
- `sent_at`

### push_tokens

Fields:

- `id`
- `user_id`
- `platform`
- `token_hash`
- `token_encrypted`
- `last_seen_at`
- `revoked_at`

## Admin

### admin_users

Fields:

- `id`
- `email_hash`
- `role`
- `status`

### admin_audit_log

Fields:

- `id`
- `admin_user_id`
- `action`
- `target_type`
- `target_id`
- `created_at`

## Redis Keys

Recommended keys:

```text
presence:user:{userId}
presence:cell:{cellId}
grid:candidates:{filterHash}:{cellId}
entitlement:cache:{userId}
ws:sessions:{userId}
rate:first_message:{userId}
rate:message:{userId}
rate:album_request:{userId}
rate:flirt:{userId}
rate:profile_view:{userId}
rate:swipe:{userId}
risk:short_window:{userId}
boost:active:{regionId}
notification:quiet_queue:{userId}
```

Redis is not authoritative for permanent product state.

## Required Exclusion Rules

Every discovery query must exclude:

- Deleted users.
- Suspended/banned users.
- Blocked users bidirectionally.
- Users hidden by the viewer where applicable.
- Users outside visibility settings.
- Users failing age/safety requirements.
- High-risk quarantined users.

## Do Not Store

Do not store unless legally reviewed and required:

- Long-term exact location history.
- Raw biometric data.
- Raw government ID images.
- Private media as public CDN assets.
- Message contents in logs.
- Sensitive lifestyle tags in analytics payloads.

## Implementation Checklist

- [x] Autonomous user model exists.
- [x] Partner links are relationships, not accounts.
- [x] PostGIS location table exists.
- [x] Swipe action, match, and persisted swipe deck session/card tables exist.
- [ ] Psync session tables exist.
- [ ] Playing Tonight session table exists.
- [x] Chat participant model supports partner invite.
- [x] Album grants are explicit and revocable.
- [x] Activity events are separate from messages.
- [x] Entitlements are server-authoritative.
- [x] Risk state and moderation tables exist.
- [x] Age verification records and profile age timestamp exist.
- [x] Blocks and hides are separate.
- [x] Notification events are Ktor-owned.
- [x] Redis only stores ephemeral/cache data.

## Do Not Implement

- Do not create a `couples` account table as a primary identity.
- Do not make partner link imply media/chat access.
- Do not use bio text as filter source.
- Do not trust client subscription state.
- Do not expose raw exact location.
- Do not make Activity a message table.
