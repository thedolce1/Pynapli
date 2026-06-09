# Security Route Matrix

This matrix turns Pynapli's "never trust the app" rule into a route-level checklist. Every route file must be classified as one of:

- User-authenticated: derives `user.id` from `Authorization: Bearer ...` and never from request body user IDs.
- Admin-authenticated: disabled unless the admin API token is configured, then gated by admin auth plus admin actor/reason.
- Webhook-authenticated: gated by a configured webhook token or provider identity check.
- Public: intentionally anonymous.
- Local-dev: intentionally anonymous only when the server runs in a local/test storage mode.

This document is enforced by `RouteSecurityMatrixTest`: every `*Routes.kt` file must be represented here and must contain a recognized auth gate unless it is an explicit public/local-dev exception.

## Current Route Classification

| Route file | Classification | Notes |
| --- | --- | --- |
| `AccountLifecycleRoutes.kt` | User-authenticated | Export/delete uses token-derived account owner. |
| `ActivityRoutes.kt` | User-authenticated | Feed, summary, seen, dismiss, flirts, and album requests are recipient/actor scoped. |
| `ActivityStreamRoutes.kt` | User-authenticated | SSE stream authenticates bearer token before subscription. |
| `AuthRoutes.kt` | Public/auth mixed | Signup, login, refresh are public but rate-limited by hashed anonymous subject; logout requires bearer token. |
| `GridRoutes.kt` | User-authenticated | Grid query is token-derived and rate-limited. |
| `SavedGridFilterRoutes.kt` | User-authenticated | Saved filters are owner-scoped. |
| `LocationRoutes.kt` | User-authenticated | Current location updates only affect the token-derived user. |
| `TravelLocationRoutes.kt` | User-authenticated | Travel mode is token-derived and tier-gated. |
| `MediaRoutes.kt` | User-authenticated/local-dev mixed | Album and completion routes require bearer token. Local upload `GET/PUT /v1/media/uploads/local/{assetId}` is local/test storage only; production must use signed cloud storage. |
| `MessageStreamRoutes.kt` | User-authenticated | SSE stream authenticates bearer token before subscription. |
| `MessagingRoutes.kt` | User-authenticated | Thread membership, match/grid-origin rules, blocking, idempotency, and rate limits are service-owned. |
| `AdminAppealRoutes.kt` | Admin-authenticated | Disabled unless `PYNAPLI_ADMIN_API_TOKEN` is configured. Requires admin actor/reason. |
| `AdminModerationRoutes.kt` | Admin-authenticated | Disabled unless `PYNAPLI_ADMIN_API_TOKEN` is configured. Requires admin actor/reason and writes audit rows. |
| `AdminReportRoutes.kt` | Admin-authenticated | Disabled unless `PYNAPLI_ADMIN_API_TOKEN` is configured. Requires admin actor/reason. |
| `AdminRiskRoutes.kt` | Admin-authenticated | Disabled unless `PYNAPLI_ADMIN_API_TOKEN` is configured. Requires admin actor/reason. |
| `AdminMetricsRoutes.kt` | Admin-authenticated | Disabled unless `PYNAPLI_ADMIN_API_TOKEN` is configured. |
| `NotificationRoutes.kt` | User-authenticated | Preferences and device tokens are token-derived. |
| `PresenceRoutes.kt` | User-authenticated | Heartbeat is token-derived. |
| `PresenceStreamRoutes.kt` | User-authenticated | SSE stream authenticates bearer token and block-filters target updates. |
| `ProfileRoutes.kt` | User-authenticated | Profile reads/editing use token-derived viewer/owner and block checks. |
| `TonightAvailabilityRoutes.kt` | User-authenticated | Availability belongs to token-derived user. |
| `ProfileLinkRoutes.kt` | User-authenticated | Invite/accept/decline/unlink are token-derived and self/block guarded. |
| `PsyncSessionRoutes.kt` | User-authenticated | Session participant checks happen in service/repository layer. |
| `PsyncStreamRoutes.kt` | User-authenticated | Stream authenticates bearer token before subscription. |
| `HealthRoutes.kt` | Public | Health/deep-health are intentionally anonymous infrastructure probes. |
| `AppealRoutes.kt` | User-authenticated | Appeals are owner-scoped. |
| `SafetyRoutes.kt` | User-authenticated | Blocks, hidden profiles, reports, and support contact are token-derived. Reports are rate-limited. |
| `SubscriptionRoutes.kt` | User/admin/webhook mixed | User store/entitlement routes require bearer token and mutation rate limits; admin subscription routes require admin token; Play RTDN requires configured token or Google OIDC. |
| `SavedSwipeFilterRoutes.kt` | User-authenticated | Saved filters are owner-scoped and entitlement-gated. |
| `SwipeRoutes.kt` | User-authenticated | Deck/action/matches are token-derived; actions require server-dealt card/session tokens and are rate-limited/idempotent. |
| `TopPicksRoutes.kt` | User-authenticated | Picks are token-derived and backed by persisted swipe deck sessions. |
| `TonightFeedRoutes.kt` | User-authenticated | Feed is token-derived and discovery-scoped. |
| `TonightSessionRoutes.kt` | User-authenticated | Session lifecycle is token-derived and participant-scoped. |
| `AgeVerificationRoutes.kt` | User/admin mixed | Requirements/status/start require bearer token; start is rate-limited; callback/review require admin auth. |

## Current Security Coverage

- Auth: private endpoints require `Authorization: Bearer`; session lookup is server-side and status-aware.
- BOLA: core profile, media, messages, activity, saved filters, profile links, Psync, Swipe, and account lifecycle paths enforce owner/membership/participant checks in services or repositories.
- Rate limits: auth signup/login/refresh, grid queries, profile views, flirts, album requests, first messages, reports, swipe actions, Psync nudges, media uploads, age-verification start, store purchase submissions, and boost activation.
- Idempotency: implemented for message send, swipe action IDs, notification outbox, Play RTDN event ingestion, and selected activity producers.
- Secrets: service accounts, signing keys, admin token, webhook token, cursor secrets, and deck-token secrets are server config/env only.
- Logging: request logs omit headers and query strings; admin-sensitive actions write audit rows where implemented.

## Remaining Hardening

- Replace admin shared-token bootstrap with real admin identities, MFA, roles, and step-up auth before production.
- Expand object-level authorization tests into a full wrong-user matrix for every object-ID route.
- Add idempotency keys to remaining high-value mutations where duplicates could spend inventory or create duplicate state.
- Add provider-grade App Check/device attestation and WAF/IP-level throttles before a public launch.
- Add alert thresholds for repeated auth failures, media upload abuse, verification abuse, scraping patterns, and suspicious account creation.
