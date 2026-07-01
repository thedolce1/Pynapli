# Pynapli Analytics And Metrics Spec

Last updated: 2026-04-24

## Purpose

This document defines Pynapli's product analytics, operational metrics, privacy boundaries, and event taxonomy.

Core rule:

> Measure product health without leaking sensitive lifestyle, location, message, album, or relationship data.

## Analytics Principles

1. Do not send sensitive lifestyle tags to third-party analytics by default.
2. Do not send message contents.
3. Do not send private album URLs or media identifiers.
4. Do not send exact location.
5. Prefer aggregate/coarse events.
6. Ktor remains the source for authoritative business metrics.
7. Firebase Analytics is for client behavior and funnel health, not sensitive domain state.
8. Every analytics event must have a purpose.

## Tooling

Client:

- Firebase Analytics for privacy-safe mobile events.
- Crashlytics for crash diagnostics with redaction.
- Remote Config for experiments and feature flags.

Server:

- Structured logs with redaction.
- Metrics via OpenTelemetry/Prometheus/Cloud Monitoring.
- Product event pipeline for internal analytics.
- Database queries/materialized reports for authoritative subscription and safety metrics.

## Data Sensitivity Levels

### Low Sensitivity

Allowed in Firebase if useful:

- Screen opened
- Button tapped
- Signup step completed
- Paywall shown
- Purchase attempt started
- Grid loaded
- Swipe deck opened
- Generic error code

### Medium Sensitivity

Prefer server/internal analytics; Firebase only if coarse:

- Partner link created
- Psync session completed
- Playing Tonight activated
- Private album request count
- Report submitted count
- Verification completed

### High Sensitivity

Do not send to Firebase Analytics:

- Message text
- Private album media IDs/URLs
- Exact location
- Specific lifestyle tags tied to user
- Sexual preference details
- Verification provider raw data
- Report notes
- Blocked user IDs
- Risk score details

## Event Naming

Use lowercase snake_case.

Examples:

- `signup_started`
- `signup_step_completed`
- `grid_opened`
- `grid_query_completed`
- `profile_opened`
- `swipe_deck_opened`
- `swipe_action_submitted`
- `match_created`
- `activity_opened`
- `message_thread_opened`
- `paywall_viewed`
- `subscription_purchase_started`

Do not include lifestyle labels in event names.

Bad:

- `full_swap_filter_used`
- `hotwife_profile_viewed`
- `private_album_nude_opened`

Good:

- `advanced_filter_applied`
- `profile_tag_filter_applied`
- `album_access_requested`

## Core Product Funnels

### Onboarding Funnel

Events:

- `signup_started`
- `signup_account_created`
- `signup_dob_completed`
- `signup_profile_basics_completed`
- `signup_photo_step_completed`
- `age_gate_completed`
- `location_permission_prompted`
- `location_permission_result`
- `notification_permission_prompted`
- `notification_permission_result`
- `first_grid_loaded`

Metrics:

- Signup completion rate
- Age-gate failure rate
- Location permission opt-in
- Notification opt-in
- Time to first grid

### Grid Funnel

Events:

- `grid_opened`
- `grid_query_completed`
- `grid_filter_opened`
- `grid_filter_applied`
- `grid_card_tapped`
- `grid_message_started`

Metrics:

- Grid load latency
- Grid card tap-through
- Profile open rate
- Grid message start rate
- Filter usage
- Empty grid rate

Do not track exact filters with sensitive values in Firebase.

### Swipe Funnel

Events:

- `swipe_deck_opened`
- `swipe_deck_built`
- `swipe_action_submitted`
- `rewind_used`
- `pineapple_like_used`
- `intro_note_used`
- `match_created`

Metrics:

- Deck build latency
- Likes per session
- Pass rate
- Match rate
- Rewind usage
- Intro Note acceptance
- Likes You paywall conversion

### Activity Funnel

Events:

- `activity_opened`
- `activity_tab_changed`
- `activity_item_tapped`
- `activity_paywall_viewed`
- `activity_paywall_converted`

Metrics:

- Activity opens per DAU
- Viewed You conversion
- Likes You conversion
- Flirt response rate

### Messaging Funnel

Events:

- `message_thread_opened`
- `message_send_attempted`
- `message_send_succeeded`
- `partner_invite_sent`
- `partner_invite_accepted`
- `album_access_requested`
- `album_access_granted`

Metrics:

- Message send success
- Reply rate
- Partner invite acceptance
- Album request/grant rate
- Report/block rate after first messages

Do not track message text.

### Partner Linking Funnel

Events:

- `partner_link_request_started`
- `partner_link_request_sent`
- `partner_link_accepted`
- `partner_link_denied`
- `partner_link_unlinked`
- `linked_partner_benefit_activated`

Metrics:

- Link completion rate
- Linked users as percentage of MAU
- Unlink rate
- Subscription benefit activation

### Psync Funnel

Events:

- `psync_invite_sent`
- `psync_invite_accepted`
- `psync_session_started`
- `psync_session_completed`
- `psync_together_match_created`
- `psync_session_expired`

Metrics:

- Session completion rate
- Average deck size
- Together match rate
- Drop/offline rate

Do not track partner-by-partner swipe choices in analytics.

### Playing Tonight Funnel

Events:

- `playing_tonight_started`
- `playing_tonight_partner_approved`
- `playing_tonight_live`
- `playing_tonight_interaction_received`
- `playing_tonight_ended`

Metrics:

- Sessions per active user
- Approval rate
- Session interaction rate
- Feed/map usage

Use zone/city-level only, no exact location.

### Monetization Funnel

Events:

- `paywall_viewed`
- `paywall_cta_tapped`
- `purchase_started`
- `purchase_validated`
- `purchase_failed`
- `subscription_restored`
- `boost_started`
- `inventory_used`

Metrics:

- Free to Social conversion
- Free to Select conversion
- Social to Select upgrade
- Churn
- ARPU / ARPPU
- Boost purchase conversion
- Pineapple Like usage
- Intro Note usage

Authoritative revenue data must come from server-side validated purchases, not Firebase alone.

## Safety Metrics

Internal only:

- Reports per 1,000 users
- Blocks per 1,000 users
- Report categories
- Median moderation SLA
- Risk tier distribution
- Spam held/deleted/released
- Appeal success rate
- False-positive recovery
- Underage report rate
- CSAM/severe escalation count

Do not send detailed safety reports to Firebase Analytics.

## Performance Metrics

Client:

- App cold start
- First grid render
- Grid filter visual update
- Swipe frame drops
- Swipe action queue size
- Message send latency
- Image load failures

Server:

- Grid query latency
- Swipe deck latency
- Message send latency
- Activity query latency
- WebSocket connections
- FCM send success
- DB slow queries
- Redis latency

## Experimentation

Allowed experiments:

- Paywall copy
- Free visible Activity count
- Swipe daily like limits
- Social vs Select inventory quantities
- Onboarding prompt order
- Filter defaults

Not allowed:

- Safety access
- Block/report access
- Age verification requirements
- Deceptive Activity
- Fake notifications
- Basic privacy controls

## User Privacy Controls

Settings should include:

- Analytics consent where required by jurisdiction
- Crash reporting consent where required
- Personalized ads off by default or carefully reviewed
- Data export/delete

Respect:

- App tracking transparency on iOS
- Google Play data safety disclosures
- Applicable privacy laws

## Event Payload Rules

Allowed payload examples:

```json
{
  "surface": "grid",
  "result_count_bucket": "20_50",
  "latency_ms_bucket": "500_1000",
  "tier": "free"
}
```

Avoid:

```json
{
  "exact_lat": 43.6426,
  "exact_lng": -79.3871,
  "lifestyle_tag": "full_swap",
  "message_text": "..."
}
```

Use buckets:

- Distance bucket
- Latency bucket
- Result count bucket
- Session duration bucket
- City/market, if needed

## Data Quality

Every event definition should include:

- Event name
- Owner
- Purpose
- Trigger
- Payload fields
- Sensitivity level
- Destination: Firebase, internal, both
- Retention

## Implementation Checklist

> Status (2026-07-01): the **server**-side authoritative metrics are done (F1 `ProductMetrics`).
> A **client**-side analytics layer (Firebase Analytics event logging) is **not wired yet** — there
> are zero `logEvent`/`FirebaseAnalytics` call sites — so the client-event items below (registry,
> low-sensitivity guarantee, no location/message text) are **vacuously satisfied and deferred** until
> a client analytics layer is actually added; building an event registry now would be dead code. When
> client analytics lands, route every event through a `:shared` registry that forbids sensitive params
> and add a guard test (mirroring the server `SensitiveLoggingGuardTest`).

- [ ] Analytics event registry exists. *(Deferred — no client analytics layer yet.)*
- [ ] Sensitive data rules documented in code. *(Deferred — with the client analytics layer.)*
- [ ] Firebase events are low-sensitivity only. *(Vacuously true — no events sent yet.)*
- [x] Server product events exist for authoritative metrics. (F1 `ProductMetrics`: signup/login, message_sent, match_created, purchase_verified.)
- [x] Purchase metrics come from validated server state. (F1: `purchase_verified` recorded only on the user-initiated Verified+active branch, from server-validated purchases.)
- [ ] Crash logs redact sensitive data. *(Crashlytics wired; redaction tuning pending real crash payloads.)*
- [ ] Exact location never sent to analytics. *(Vacuously true — no client analytics.)*
- [ ] Message text never sent to analytics. *(Vacuously true — no client analytics.)*
- [ ] Experiment flags cannot bypass server limits. *(No experiment/remote-config layer wired; limits stay server-enforced.)*

## Do Not Implement

- Do not send lifestyle preferences to Firebase by default.
- Do not log message contents.
- Do not log private album URLs.
- Do not log raw exact location.
- Do not track Psync private swipe choices.
- Do not use fake Activity for conversion.
- Do not make analytics events the source of truth for billing or safety.

