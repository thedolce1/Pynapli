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
| `AuthRoutes.kt` | Public/auth mixed | Login and refresh are public but rate-limited by hashed anonymous subject; login counts failed attempts only, per account + trusted client IP (no per-email lockout); logout requires bearer token. |
| `EmailSignupRoutes.kt` | Public/local-dev mixed | Sign-up, verify and resend are public (no account exists yet), rate-limited per address + trusted client IP, per device and per IP, and membership-private: the same answer for every address, the session only from a verified code bound to the token the sign-up returned. `/v1/dev/auth/email/signup` and `/latest-code` are local-dev only: registered only with `PYNAPLI_DEV_SIGNUP_SHORTCUTS_ENABLED=true` (off by default, refused in production-like environments), PYNAPLI_ENV=local and the local file mailer, and even then 404 unless the socket peer is loopback AND the request carries no forwarding/tunnel header. |
| `GridRoutes.kt` | User-authenticated | Grid query is token-derived and rate-limited. |
| `FavoriteRoutes.kt` | User-authenticated | Favorites are token-derived and private to the owner; add/remove are idempotent, rate-limited and capped; the list applies Grid visibility, block and hide rules on every read. |
| `SavedGridFilterRoutes.kt` | User-authenticated | Saved filters are owner-scoped. |
| `LocationRoutes.kt` | User-authenticated | Current location updates only affect the token-derived user. |
| `TravelLocationRoutes.kt` | User-authenticated | Travel mode is token-derived and tier-gated. |
| `MediaRoutes.kt` | User-authenticated/local-dev mixed | Album and completion routes require bearer token. Local upload `GET/PUT /v1/media/uploads/local/{assetId}` is local/test storage only; production must use signed cloud storage. |
| `MessageStreamRoutes.kt` | User-authenticated | SSE stream authenticates bearer token before subscription. |
| `MessagingRoutes.kt` | User-authenticated | Thread membership, match/grid-origin rules, blocking, idempotency, and rate limits are service-owned. |
| `MetInPersonRoutes.kt` | User-authenticated | "We've met in person" confirm and withdraw (`POST /v1/messages/threads/{threadId}/met-in-person[/withdraw]`) act only for the token-derived user, who must be an active participant; every refusal is the same `MET_IN_PERSON_NOT_AVAILABLE`. The answer is the caller's own state for that person only (never a count or anyone else's confirmation). Nothing is notified. The voucher is stored only as K3 tokens (TRUST_AND_SAFETY_SPEC "Met in person"). |
| `AdminMetInPersonRoutes.kt` | Admin-authenticated | Disabled unless `PYNAPLI_ADMIN_API_TOKEN` is configured. View (`GET /v1/admin/met-in-person/users/{userId}`), void one confirmation, void everything one voucher gave (voucher id in the body, never the path), and freeze/unfreeze a subject's badge. Every route needs `requireAdminActor` plus a senior admin, step-up MFA (`X-Admin-MFA`) and `X-Admin-Reason`, the same gate as the admin user lookup; each writes a `met_in_person.*` audit row naming the subject and counts (a voucher only by token). |
| `AdminAppealRoutes.kt` | Admin-authenticated | Disabled unless `PYNAPLI_ADMIN_API_TOKEN` is configured. Requires admin actor/reason. |
| `AdminModerationRoutes.kt` | Admin-authenticated | Disabled unless `PYNAPLI_ADMIN_API_TOKEN` is configured. Requires admin actor/reason and writes audit rows. |
| `AdminReportRoutes.kt` | Admin-authenticated | Disabled unless `PYNAPLI_ADMIN_API_TOKEN` is configured. Requires admin actor/reason. |
| `AdminRiskRoutes.kt` | Admin-authenticated | Disabled unless `PYNAPLI_ADMIN_API_TOKEN` is configured. Requires admin actor/reason. `set-tier`, `lock` and `ban` also require a senior admin and step-up MFA (`X-Admin-MFA`); lock and ban drop cached sessions and close open sockets on every replica at once. |
| `AdminMetricsRoutes.kt` | Admin-authenticated | Disabled unless `PYNAPLI_ADMIN_API_TOKEN` is configured. |
| `ProductMetricsRoutes.kt` | Admin-authenticated | Disabled unless `PYNAPLI_ADMIN_API_TOKEN` is configured. Counts-only KPI snapshot via `requireAdminActor`. |
| `PrometheusMetricsRoutes.kt` | Scrape-token-authenticated | `GET /metrics`; not installed unless `PYNAPLI_METRICS_TOKEN` (16+ chars) is set, bearer compared in constant time. Counts and gauges only, no user data. |
| `AdminUserLookupRoutes.kt` | Admin-authenticated | Disabled unless `PYNAPLI_ADMIN_API_TOKEN` is configured. Consolidated user lookup via `requireAdminActor`; additionally requires `X-Admin-Reason` and writes a `user_lookup` audit row. |
| `AdminPartnerLinkReviewRoutes.kt` | Admin-authenticated | Disabled unless `PYNAPLI_ADMIN_API_TOKEN` is configured. Partner-link review via `requireAdminActor`; additionally requires `X-Admin-Reason` and writes a `partner_link_review` audit row. |
| `AdminSubscriptionSupportRoutes.kt` | Admin-authenticated | Disabled unless `PYNAPLI_ADMIN_API_TOKEN` is configured. Read-only subscription support view via `requireAdminActor`; additionally requires `X-Admin-Reason` and writes a `subscription_support_view` audit row. Excludes raw purchase tokens/order ids. |
| `AdminDashboardRoutes.kt` | Admin-authenticated | Disabled unless `PYNAPLI_ADMIN_API_TOKEN` is configured. Counts-only backlog dashboard across the reports/appeals/held-message/risk queues via `requireAdminActor`; aggregate counts only, so no `X-Admin-Reason`/audit (the metrics pattern). |
| `AdminMediaReviewRoutes.kt` | Admin-authenticated | Disabled unless `PYNAPLI_ADMIN_API_TOKEN` is configured. Pending profile photo list/approve/reject via `requireAdminActor` (operator-level: no senior role or MFA, these are photos the owner asked to show publicly). Reject needs a reason; decisions write `media_review.approve/reject` audit rows and a rejection sends the owner a system-safety push. |
| `NotificationRoutes.kt` | User-authenticated | Preferences and device tokens are token-derived. |
| `PresenceStreamRoutes.kt` | User-authenticated | SSE stream authenticates bearer token and block-filters target updates. |
| `ProfileRoutes.kt` | User-authenticated | Profile reads/editing use token-derived viewer/owner and block checks. |
| `ProfileLinkRoutes.kt` | User-authenticated | Invite/accept/decline/unlink are token-derived and self/block guarded. |
| `PsyncSessionRoutes.kt` | User-authenticated | Session participant checks happen in service/repository layer. |
| `PsyncStreamRoutes.kt` | User-authenticated | Stream authenticates bearer token before subscription. |
| `HealthRoutes.kt` | Public | Health/deep-health are intentionally anonymous infrastructure probes. |
| `AppealRoutes.kt` | User-authenticated | Appeals are owner-scoped. |
| `SafetyRoutes.kt` | User-authenticated | Blocks, hidden profiles, reports, and support contact are token-derived. Reports are rate-limited. |
| `SubscriptionRoutes.kt` | User/admin/webhook mixed | User store/entitlement routes require bearer token and mutation rate limits; admin subscription routes require admin token; Play RTDN requires configured token or Google OIDC. |
| `MatchCelebrationRoutes.kt` | User-authenticated | Token-derived; lists and marks only the caller's own matches (unseen list hides blocked, hidden and gone profiles); marking is idempotent. |
| `SavedSwipeFilterRoutes.kt` | User-authenticated | Saved filters are owner-scoped and entitlement-gated. |
| `SwipeRoutes.kt` | User-authenticated | Deck/action/rewind/like-back are token-derived; actions require server-dealt card/session tokens and are rate-limited/idempotent. |
| `TonightFeedRoutes.kt` | User-authenticated | Feed is token-derived and discovery-scoped. |
| `TonightSessionRoutes.kt` | User-authenticated | Session lifecycle is token-derived and participant-scoped. |
| `AgeVerificationRoutes.kt` | User/admin mixed | Requirements/status/start require bearer token; start is rate-limited; review requires admin auth. The provider callback is authenticated only by an HMAC-SHA256 signature with its own secret (`PYNAPLI_AGE_VERIFICATION_CALLBACK_SECRET`, 5-minute timestamp window) and is not served without one; the admin token is not accepted there. |
| `PhotoVerificationRoutes.kt` | User-authenticated | Selfie photo verification status/challenge/upload/submit act only on the token-derived user; challenge (10/hour), upload slot (media upload limit) and submit (5/day) are rate-limited. The selfie is a private `verification` media asset: never in an album, never ready/approved, never returned to other users. |
| `AdminPhotoVerificationRoutes.kt` | Admin-authenticated | Disabled unless `PYNAPLI_ADMIN_API_TOKEN` is configured. Queue, approve, reject and revoke all need `requireAdminActor` plus a senior admin and step-up MFA (`X-Admin-MFA`): they reveal selfies and change trust status. The queue needs `X-Admin-Reason` and writes a `photo_verification.queue_view` audit row; decisions write `photo_verification.approve/reject/revoke` audit rows with the reviewer id. Selfie URLs are short-lived signed URLs. |

## Current Security Coverage

- Auth: private endpoints require `Authorization: Bearer`; session lookup is server-side and status-aware. `AccountStatusRouteSecurityTest` pins the HTTP boundary for paused/suspended/banned/deleted sessions across the core protected routes so non-active accounts return 403 before Grid, Swipe, Profile, Location, Media, or Messages services run.
- BOLA: core profile, media, messages, activity, saved filters, profile links, Psync, Swipe, and account lifecycle paths enforce owner/membership/participant checks in services or repositories. At the HTTP layer, `WrongUserObjectAccessRouteTest` pins the route contract those checks rely on across 9 surfaces (profiles, message delete/read/send, media upload-complete/private-album/grant-revoke, activity dismiss/album-respond, profile-link accept, appeal withdraw, saved grid/swipe filter delete, swipe action): the acting identity passed to every domain service is the token-derived user (caller-supplied IDs only ever arrive as object/target data), and the wrong-user outcome maps to 404 — not 200, not 5xx.
- Blocked/hidden route boundary: `BlockedHiddenRouteSecurityTest` pins service-denial mapping across profile open, direct conversation create, message send, flirts, private-album requests/views, and stale hidden swipe actions. Routes keep the token-derived actor ID and return non-success statuses without turning blocked/hidden state into a successful direct-call path.
- Rate limits: auth signup/login/refresh, grid queries, profile views, flirts, album requests, first messages, reports, swipe actions, Psync nudges, media uploads, age-verification start, store purchase submissions, boost activation, and profile/discovery-settings edits.
- App Check boundary (`AppCheckPlugin`): when `PYNAPLI_APP_CHECK_ENFORCED=true`, state-changing requests (POST/PUT/PATCH/DELETE) must carry a verified `X-Firebase-AppCheck` header attesting a genuine app instance; reads and non-app origins (health, Play RTDN webhook, age-verification callback) are exempt. Off by default. With `PYNAPLI_APP_CHECK_PROJECT_NUMBER` set, tokens are **cryptographically verified** (`FirebaseAppCheckVerifier` — RS256 JWT vs Google's App Check JWKS via `TokenVerifier`); without it, enforcement requires only a non-blank header (staged rollout). The Android client now installs Firebase App Check (debug provider for debug builds, Play Integrity for release) and the shared Ktor client attaches the header when a token is available.
- Idempotency: implemented for message send, swipe action IDs, notification outbox, Play RTDN event ingestion, and selected activity producers.
- Secrets: service accounts, signing keys, admin token, webhook token, cursor secrets, and deck-token secrets are server config/env only.
- Logging: request logs omit headers and query strings (`MonitoringPluginTest`); `SensitiveLoggingGuardTest` fails the build if any `logger.*` call references a sensitive field (secrets/tokens, message bodies, exact location, private media URLs, sensitive tags).
- Admin audit: admin actions are audited two ways — sensitive handlers write rich reason/target rows, and the general `AdminAuditPlugin` middleware records every successful admin mutation (2xx POST/PUT/PATCH/DELETE under `/v1/admin`, keyed on `X-Admin-Id`) as an `admin_request` row so no admin action goes unlogged even if a handler omits its own write.
- Admin roles + step-up MFA: a two-tier operator/senior model (`PYNAPLI_ADMIN_ROLES`) gates the sensitive set — PII reveals (user lookup / subscription support / partner-link review), entitlement grant/revoke, appeal decisions, reported-media removal — behind `requireSeniorAdmin` (403 for operators/unmapped actors); a no-op when unconfigured. On the same set, `requireAdminMfa` enforces a per-admin TOTP step-up (RFC 6238, `X-Admin-MFA`) when `PYNAPLI_ADMIN_MFA_ENFORCED` is set — the real second factor on top of the shared token + self-declared actor id.
- Pagination/session tokens: grid, activity-feed, and swipe deck/card/cursor tokens are HMAC-signed (constant-time compare), viewer-bound, and scope/filter-bound; swipe tokens additionally carry generated/expiry windows. Contracts pinned by `DiscoveryCursorCodecTest`/`ActivityCursorCodecTest`/`SwipeDeckTokenCodecTest`.

## Remaining Hardening

- Admin access control: a two-tier operator/senior **role gate** (`requireSeniorAdmin`), **step-up TOTP MFA** (`requireAdminMfa`), and the general **audit middleware** now ship (all off/no-op until configured). Still remaining before production: production `/v1/admin` API separation, and eventually real per-admin identities with enrollment (replacing the shared-token + self-declared-id bootstrap).
- Wrong-user matrix shipped 2026-06-09 (`WrongUserObjectAccessRouteTest`, see BOLA above); it also caught `MESSAGE_NOT_FOUND` mapping to 400 instead of 404 (fixed). Extend to new object-ID routes as they land (Psync/Tonight session routes are covered at the service layer).
- Add idempotency keys to remaining high-value mutations where duplicates could spend inventory or create duplicate state.
- The App Check boundary ships with cryptographic verification (`AppCheckPlugin` + `FirebaseAppCheckVerifier`, off by default), and Android token attachment is now wired. Remaining before a public launch: register/validate App Check in Firebase, allowlist debug tokens for dev/emulator QA, flip `PYNAPLI_APP_CHECK_ENFORCED=true` with `PYNAPLI_APP_CHECK_PROJECT_NUMBER` in staging before production, and add WAF/IP-level throttles.
- Add alert thresholds for repeated auth failures, media upload abuse, verification abuse, scraping patterns, and suspicious account creation.
