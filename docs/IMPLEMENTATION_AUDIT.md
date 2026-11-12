# Pynapli Implementation Status

Updated: 2026-11-11

What is built and what is still open, per area, against the specs. The specs stay the source of
truth; this page is the current status snapshot only.

**How to update:** when a slice ships, edit the affected row (status, open gaps) in place, and
change the date above. Don't append narrative: the *how* of a slice belongs in its commit/PR, and
spec checklists record what is done. The pre-2026-11 dated changelog (160 KB) lives in
[archive/IMPLEMENTATION_HISTORY.md](archive/IMPLEMENTATION_HISTORY.md).

Status words: **Done** (meets the current milestone), **Mostly done** (MVP path works; named gaps
remain), **Partial** (real slices shipped; material gaps), **Not started**.

## Status

| Area | Status | Open gaps | Spec |
|---|---|---|---|
| Product model | Done | Keep avoiding couple-owned accounts and couple cards (every adult has their own account; partner is optional context). | [PYNAPLI_PRODUCT_SPEC](PYNAPLI_PRODUCT_SPEC.md) |
| Language and positioning | Mostly done | Ongoing UI copy review as screens mature. | [LANGUAGE_AND_TAXONOMY_SPEC](LANGUAGE_AND_TAXONOMY_SPEC.md) |
| Frontend design | Mostly done | Real-device screenshot review; wider flow coverage. The Swipe filters-sheet golden needs scroll-before-dump (or trimmed below-fold `requiredTexts`) since the sheet grew. | [FRONTEND_DESIGN_DIRECTION_SPEC](FRONTEND_DESIGN_DIRECTION_SPEC.md), [DESIGN_AND_NAVIGATION_SPEC](DESIGN_AND_NAVIGATION_SPEC.md) |
| Auth and onboarding | Partial | Forgot password (emailed 6-digit code) and change password exist; production needs an email provider for the codes (founder setup; until then reset answers "unavailable" and points to support). Social auth; live age-verification provider handshake/webhook (ops); iOS Keychain token cipher (iOS phase); legal drafts published at pynapli.com (founder/legal). | [ONBOARDING_AUTH_SPEC](ONBOARDING_AUTH_SPEC.md) |
| Profile and settings | Mostly done | Risk middleware; safety-center polish. Data export is complete end to end (worker, stale-claim retry, local or GCS storage, in-app download). | [PROFILE_SETTINGS_SPEC](PROFILE_SETTINGS_SPEC.md) |
| Location and presence | Mostly done | Physical-device battery profiling; founder/legal to supply the Travel mode region list (`PYNAPLI_TRAVEL_REGION_POLICY`). (Impossible-travel and repeated-travel-switching signals, daily switch cap, and risk-tier Travel lockout shipped.) | [GRID_IMPLEMENTATION_SPEC](GRID_IMPLEMENTATION_SPEC.md) |
| Grid | Mostly done | Larger dense-metro load runs and physical-device battery/location QA; Redis cache hit rate under real multi-instance load is unmeasured. Spatial candidate lookup is index-backed (2026-11). | [GRID_IMPLEMENTATION_SPEC](GRID_IMPLEMENTATION_SPEC.md), [FILTERS_SPEC](FILTERS_SPEC.md) |
| Swipe | Mostly done | Optional map-pin destination picker; iPhone process-death queue QA. | [SWIPE_IMPLEMENTATION_SPEC](SWIPE_IMPLEMENTATION_SPEC.md) |
| Messaging | Mostly done | A fresh connected-Android pass on receipt/typing polish. | [MESSAGING_SPEC](MESSAGING_SPEC.md) |
| Activity | Mostly done | Real-device Activity and Activity-notification QA. | [ACTIVITY_SPEC](ACTIVITY_SPEC.md) |
| Partner links | Mostly done | Support/admin review and a forced-unlink workflow (needs moderation tooling). | [PYNAPLI_PRODUCT_SPEC](PYNAPLI_PRODUCT_SPEC.md) |
| Private albums and media | Partial | GCS bucket lifecycle rules + IAM, Cloud CDN backend bucket + signing key, live end-to-end run against a real bucket/CDN and Cloud Vision, SafeSearch threshold tuning on real content (all need founder-provisioned credentials). | [PROFILE_SETTINGS_SPEC](PROFILE_SETTINGS_SPEC.md), [MODERATION_AND_AGE_VERIFICATION_SPEC](MODERATION_AND_AGE_VERIFICATION_SPEC.md) |
| Notifications | Partial | Native iOS shell + live APNs round-trip (needs a Mac); live FCM round-trip from a real device; metrics exporter/scrape endpoint. | [NOTIFICATIONS_SPEC](NOTIFICATIONS_SPEC.md) |
| Monetization | Partial | StoreKit 2 (iOS). Google Play is code-complete and sandbox-proven; real Play Console service account + products remain (ops). Final paywall copy. | [MONETIZATION_SPEC](MONETIZATION_SPEC.md) |
| Trust and safety | Partial | App Check rollout and staging enforcement; risk middleware; device/account-farm signals; verification provider/market integration; CSAM hash-matching provider; support review workflow; false-positive metrics; legal retention policy; senior-review separation; account notices. | [TRUST_AND_SAFETY_SPEC](TRUST_AND_SAFETY_SPEC.md) |
| Admin moderation | Partial | Roles/permissions, MFA, appeal UI and senior-review separation, subscription support view, partner-link review, unified dashboard, sensitive-reveal reason flow outside held messages, separated admin API (E1b–E1d, E2). | [ADMIN_MODERATION_CONSOLE_SPEC](ADMIN_MODERATION_CONSOLE_SPEC.md) |
| API contract | Mostly done | Cursor pagination on the remaining list surfaces; production notification delivery. (Mutation retry-safety audit complete.) | [API_CONTRACT_SPEC](API_CONTRACT_SPEC.md) |
| Backend architecture | Mostly done | Sensitive-log and analytics redaction review; production bucket/CDN validation; formal cloud environments. | [BACKEND_ARCHITECTURE_SPEC](BACKEND_ARCHITECTURE_SPEC.md) |
| Mobile architecture | Partial | iOS platform integrations; secure token storage review; iOS process-death queue QA; broader device visual QA. State holders live in `:composeApp` by decision (2026-11-09). | [MOBILE_APP_ARCHITECTURE_SPEC](MOBILE_APP_ARCHITECTURE_SPEC.md) |
| Testing | Partial | HTTP-layer cases for expired entitlement, revoked grant, unlinked, rate-limited and stale-idempotency paths (covered at service/repository level); iOS tests; a production-hardware, larger-metro load run. | [TESTING_STRATEGY_SPEC](TESTING_STRATEGY_SPEC.md) |
| CI/CD | Partial | GitHub Actions runs again (2026-11); the pre-push hook still gates locally. Remaining: branch protection and required checks (founder); staging workflows against live secrets; detekt once a Kotlin-2.3-compatible stable release exists. | [DEVOPS_INFRA_SETUP_SPEC](DEVOPS_INFRA_SETUP_SPEC.md) |
| DevOps and infra | Partial | Staging Cloud SQL/PostGIS, Memorystore, GCS, Secret Manager values, App Distribution groups, WIF secrets, custom domain/DNS, cost alerts, live App Check enforcement, Play/App Store production setup (all founder provisioning). | [DEVOPS_INFRA_SETUP_SPEC](DEVOPS_INFRA_SETUP_SPEC.md) |
| Analytics | Partial | Client behavioural analytics events (funnel KPIs and crash reporting are wired). Never send lifestyle-sensitive payloads. | [ANALYTICS_AND_METRICS_SPEC](ANALYTICS_AND_METRICS_SPEC.md) |
| Psync | Partial | Server state machine is complete; the product loop is not. Delivered likes reach the target as individual likes with no linked-pair presentation; the deck's location (home or travel) is explicit and agreed; no auto-pause on disconnect. Client: the deck now uses the real Swipe card stack (photos, gestures, profile peek with block/report, optimistic swipes) and keeps the recap on screen when a session ends; setup, partner review and suggest-changes exist with live updates to the other partner; pause, end-session, visible nudges and a waiting-screen safety refetch exist; Activity cards open their session; the recap has actions; no-partner and tier-limit states lead somewhere. Entry points: Swipe header, Activity cards, push, and the profile hub's linked-partner panel. Two-partner device QA: `scripts/android-psync-qa.ps1` (one partner on the emulator, the other over HTTP; inviter and invitee runs) passes invite, live accept, both-start, deck, partner progress without choice leakage, and recap. | [PSYNC_SPEC](PSYNC_SPEC.md) |
| Playing Tonight | Mostly done | Physical-device battery and notification-delivery pass (delivery also needs FCM `google-services.json`). | [PLAYING_TONIGHT_SPEC](PLAYING_TONIGHT_SPEC.md) |
| Web PWA | Partial | Tracked phase by phase in [WEB_PWA_GAMEPLAN](WEB_PWA_GAMEPLAN.md) (W0–W6). | [WEB_PWA_GAMEPLAN](WEB_PWA_GAMEPLAN.md) |
| iOS | Not started | Xcode project, StoreKit 2, APNs, UI validation and tests; see [IOS_BRINGUP_PLAN](IOS_BRINGUP_PLAN.md). Needs a Mac. | [IOS_BRINGUP_PLAN](IOS_BRINGUP_PLAN.md) |

## Highest-risk open items

1. **Nothing is provisioned in the cloud yet** (GCS, Cloud SQL, Secret Manager, Play Console,
   App Check). Code paths for media, exports, billing and push exist, but none has run against
   real infrastructure. Founder track: [PATH_TO_TESTERS](PATH_TO_TESTERS.md).
2. **No real-device QA.** Every Android pass so far is on an emulator (battery, push delivery,
   low-network, touch feel are unproven).
3. **iOS is not started**, so StoreKit, APNs and iOS process-death behaviour are untested.
4. **Trust and safety providers are stubs or seams**: age verification, CSAM hash matching and App
   Check enforcement all need live providers before a public launch.
5. **Branch protection is off**, so nothing forces CI to pass before a merge; the local pre-push
   hook is the only enforced gate. Keep `core.hooksPath` set on every clone.

## Ground rules

- Fix correctness and test issues as soon as they're found; a missing, skipped or non-matching test
  is a real problem until explained.
- Don't assume dependency, cloud, billing, Docker or platform behaviour: check official docs first.
- Keep product rules server-authoritative where they touch safety, privacy, monetization, blocks,
  matches, albums or partner links. Keep safety, report, block, consent and support flows free.
- Spec checklists: `[x]` = meets the current milestone with code evidence, `[ ] Partial:` = a real
  slice exists but isn't complete, `[ ]` = not started. A tick doesn't mean production-final unless
  the spec says so.
