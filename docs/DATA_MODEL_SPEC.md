# Pynapli Data Model Spec

Principles and rules for the backend data model. **The schema itself is the Flyway migrations**
(`server/src/main/resources/db/migration/V*.sql`); read them for columns, indexes and constraints.
This file keeps only what the migrations don't say: why the model is shaped this way and the rules
every change must keep.

> Model autonomous users first. Partner links, sessions, chats, subscriptions and discovery are
> relationships around users, never replacements for them.

## Storage

- **PostgreSQL + PostGIS** is authoritative.
- **Redis** is ephemeral/cache only (presence, grid candidate cache, rate limits, realtime relay,
  short-window risk counters). It never holds permanent product state.
- **Object storage** (GCS) holds originals and private media; a CDN serves only public thumbnails and
  approved public variants. Private media is never a public CDN asset.
- Analytics is a privacy-safe pipeline and is never authoritative product state.

## IDs and timestamps

- Primary keys are server-generated random `UUID`s. Never expose sequential ids.
- Tables carry `created_at` / `updated_at`; use `deleted_at` / `status` where soft deletion or a
  lifecycle applies.

## Domains and their tables

| Domain | Tables |
| --- | --- |
| Accounts and auth | `users`, `auth_identities`, `user_sessions`, `password_reset_codes`, `account_device_links`, `account_data_export_requests`, `account_deletion_events` |
| Profile and discovery | `profiles` (incl. structured lifestyle/logistics/intent tags), `discovery_settings`, `saved_grid_filters`, `saved_swipe_filters` |
| Location and travel | `user_locations`, `travel_locations`, `travel_trips`, `travel_places`, `travel_places_dataset` |
| Partner linking | `user_profile_links`, `linked_partner_benefits` (no writer; the partner benefit is computed live from links + subscriptions) |
| Swipe | `swipe_deck_sessions`, `swipe_deck_cards`, `swipe_actions`, `matches`, `top_pick_sessions`, `top_pick_session_cards` (Top Picks is frozen) |
| Psync | `psync_sessions`, `psync_session_participants`, `psync_session_cards`, `psync_session_actions`, `psync_session_matches` |
| Playing Tonight | `tonight_sessions`, `tonight_session_participants` (the legacy `profiles.tonight_available_until` flag is being merged into sessions) |
| Messaging | `conversations`, `conversation_participants`, `direct_conversations`, `messages`, `message_media_reveals`, `message_review_queue`, `partner_chat_invites` |
| Media and albums | `media_assets`, `media_albums`, `media_album_items`, `media_album_access_grants`, `media_album_access_requests` |
| Activity | `activity_events` |
| Monetization | `subscriptions`, `entitlements`, `premium_inventory`, `boost_activations`, `play_store_rtdn_events`, `play_store_product_purchases` |
| Safety and moderation | `user_blocks`, `user_hidden_profiles`, `safety_reports`, `moderation_appeals`, `account_risk_states`, `account_risk_events`, `age_verifications`, `admin_audit_log` |
| Notifications | `push_devices`, `notification_preferences`, `notification_outbox` |

## Rules

### Accounts and auth

- A user is always one autonomous adult. **Never model a couple as a user**; there is no `couples`
  table as an identity.
- Account-level age, terms and privacy acceptance live on `users`; profile DOB and display age live
  on `profiles`.
- Access and refresh tokens are opaque random values (not JWTs). **Only their hashes are stored**, so
  a leaked database can't be replayed as sessions. Password-reset codes are stored only as
  `SHA-256(userId:code)`.
- **Account deletion** soft-deletes `users` (`status='deleted'`, `deleted_at`) and keeps the row for
  the integrity of retained safety/legal records. In the same transaction, GDPR/CCPA erasure nulls
  identity hashes (`email_hash`, `phone_hash`, `firebase_uid`), clears `auth_identities` credentials
  and tombstones `provider_subject`, anonymizes profile PII (display name, bio, birthdate, gender,
  orientation), **hard-deletes the `user_locations` row**, soft-deletes owned `media_assets`, and ends
  `user_profile_links` (and any `linked_partner_benefits`) on both sides. Reports and the
  `account_deletion_events` audit are retained where required.
- Data export requests are created synchronously and server-side; the archive is built and delivered
  asynchronously.

### Profiles and filters

- **Bio is never a filter source.** Everything filterable is a structured, indexed column or tag.
- Saved filter presets may be JSON; live filterable profile data may not.
- Public photos need moderation approval before public visibility. The Grid uses thumbnails only.
- `profiles.verified_at` / `face_photo_verified_at` are written only by an admin approving a selfie
  photo verification (`photo_verifications`, V103) and cleared by an admin revoke. Filters, badges and
  `partner_visibility = 'verified_only'` read them. `age_verified_at` is written by age verification.
- `photo_verifications` keeps each attempt (pose, status, decision, reviewer id, reason, timestamps)
  for audit. The selfie is a `media_assets` row with `intended_album_kind = 'verification'`, never in
  an album and never ready/approved; it is deleted 7 days after the decision (1 hour if never submitted).

### Location

- Store the current, eligible discovery location, not long-term location history.
- **Exact location is never exposed to clients**, in any DTO or log.
- PostGIS `geography(Point, 4326)` with a GiST index backs proximity queries.

### Partner linking

- One active link per user (partial unique index). A link never merges accounts.
- **A link never grants chat, album or media access by itself.**

### Swipe, matches, Psync, Tonight

- Together matches still reference individual users, and **target consent is required for
  together-mode matching**.
- Psync: no compatibility score; recaps are aggregate; solo pass-through is private by default.
- Playing Tonight: linked-together mode needs **both partners' approval**; no couple grid cards; no
  precise map pins.

### Messaging and albums

- A partner joins a conversation only after **both the partner and the third party approve**
  (`partner_chat_invites`).
- Album access is an explicit, revocable grant. A linked partner gets no automatic access.
  **Revocation is free and immediate.**
- Activity events are separate from messages; Activity is not a message table.

### Monetization

- Entitlements are server-authoritative. **Never trust client subscription state.**
- One active beneficiary per subscriber for the linked-partner benefit; it ends when the link ends.

### Safety and moderation

- Blocks and hides are separate. Hidden is a one-way discovery exclusion; blocked is bidirectional.
- One open or under-review appeal per user/action/target. User-facing appeal history **never exposes
  `admin_reason` or audit metadata**. Severe legal/CSAM decisions are non-appealable by policy above
  the table.
- Age verification never stores raw ID or biometric data unless legally required and reviewed. A
  successful verification sets `profiles.age_verified_at`; discovery reads that timestamp, not
  provider data.

## Discovery exclusion rules

Every discovery query (Grid, Swipe, Psync, Tonight) must exclude:

- Deleted, suspended and banned users.
- Blocked users, in both directions.
- Users the viewer has hidden.
- Users outside their own visibility settings.
- Users failing age or safety requirements.
- High-risk quarantined users.

## Do not store

Unless legally reviewed and required: long-term exact location history, raw biometric data, raw
government ID images, private media as public CDN assets, message contents in logs, sensitive
lifestyle tags in analytics payloads.

## Do not

- Create a couple account table as a primary identity.
- Make a partner link imply media or chat access.
- Use bio text as a filter source.
- Trust client subscription state.
- Expose raw exact location.
- Make Activity a message table.
