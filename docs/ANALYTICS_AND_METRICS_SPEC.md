# Pynapli Analytics and Metrics Spec

Privacy boundaries for product analytics and the metrics that matter. Server observability is in
[BACKEND_ARCHITECTURE_SPEC.md](BACKEND_ARCHITECTURE_SPEC.md#observability).

> Measure product health without leaking lifestyle, location, message, album or relationship data.

## Current state

- **Server:** authoritative product events exist in `metrics/ProductMetrics.kt` (`signup`, `login`,
  `message_sent`, `match_created`, `purchase_verified`; the last is recorded only for
  server-validated active purchases). Prometheus `/metrics` covers operational metrics.
- **Client:** no analytics layer yet (zero `logEvent` / `FirebaseAnalytics` call sites). Crashlytics
  is wired on Android.
- When client analytics is added, **route every event through one `:shared` registry that rejects
  sensitive parameters, and add a guard test** like the server's `SensitiveLoggingGuardTest`.

## Principles

1. Never send sensitive lifestyle tags, sexual preferences, message contents, private album URLs or
   media ids, or exact location to third-party analytics.
2. Prefer coarse, aggregate events with bucketed values.
3. Ktor is the source of authoritative business metrics (revenue, safety, matches). Analytics events
   are never the source of truth for billing or safety.
4. Firebase Analytics is for client behavior and funnel health only.
5. Every event has a stated purpose.

## Sensitivity levels

| Level | Examples | Destination |
| --- | --- | --- |
| Low | screen opened, signup step completed, paywall shown, purchase started, grid loaded, deck opened, generic error code | Firebase allowed |
| Medium | partner link created, Psync session completed, Tonight activated, album request count, report count, verification completed | Server/internal preferred; Firebase only if coarse |
| High | message text, album media ids/URLs, exact location, lifestyle tags tied to a user, sexual preferences, verification provider data, report notes, blocked user ids, risk details | **Never** to Firebase Analytics |

## Event rules

- Names are lowercase `snake_case` and **never contain lifestyle labels** (`profile_tag_filter_applied`,
  not `hotwife_profile_viewed`).
- Payloads use buckets: distance, latency, result count, session duration, city/market if needed.
  Never raw coordinates, tag values or message text.
- Don't record which filter values were used, a partner's individual Psync swipe choices, or
  Playing Tonight location below zone/city level.
- Each event definition states: name, owner, purpose, trigger, payload fields, sensitivity level,
  destination (Firebase, internal, both), retention.

## Metrics that matter

| Area | Metrics |
| --- | --- |
| Onboarding | signup completion, age-gate failure, location and notification opt-in, time to first grid |
| Grid | load latency, card tap-through, message start rate, filter usage, empty-grid rate |
| Swipe | deck build latency, likes per session, match rate, Rewind and Pineapple Like usage, Likes You conversion |
| Activity | opens per DAU, Viewed-me and Likes-you conversion, flirt response rate |
| Messaging | send success, reply rate, partner invite acceptance, album request/grant rate, report/block rate after first messages |
| Partner linking | link completion, linked users as % of MAU, unlink rate, benefit activation |
| Psync | session completion, together-match rate, drop/offline rate |
| Playing Tonight | sessions per active user, partner approval rate, interaction rate |
| Monetization | Free→Social, Free→Select, Social→Select, churn, ARPU/ARPPU, Boost conversion (from server-validated purchases only) |
| Safety (internal only) | reports and blocks per 1,000 users, categories, moderation SLA, risk-tier distribution, spam held/released, appeal success, false-positive recovery, underage reports, severe escalations |
| Performance | cold start, first grid render, swipe frame drops, queue size, message latency, image load failures |

## Experiments

Allowed: paywall copy, free Activity visible count, swipe like limits, inventory quantities,
onboarding prompt order, filter defaults. **Never experiment on:** safety access, block/report
access, age-verification requirements, basic privacy controls, deceptive Activity, fake
notifications. Experiment flags never bypass server-enforced limits.

## User privacy controls

Analytics and crash-reporting consent where a jurisdiction requires it; data export and deletion;
iOS App Tracking Transparency; Google Play Data Safety disclosures.

## Do not

- Send lifestyle preferences to Firebase.
- Log message contents, private album URLs, or raw exact location.
- Track Psync private swipe choices.
- Use fake Activity for conversion.
- Make analytics the source of truth for billing or safety.
