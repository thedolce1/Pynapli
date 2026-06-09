# Android Production Gameplan

Last updated: 2026-06-08

## Purpose

A logical, optimized, **slice-by-slice** plan to take Pynapli on **Android** from "mature MVP with a
production-grade core loop" to **production-grade, end-to-end, Google-Play-shippable**. When this is
done (see [Definition of Done](#definition-of-done-android)), we move to the **PWA companion** (Wasm).

This is the working roadmap; the canonical gap analysis stays in `IMPLEMENTATION_AUDIT.md` and the
per-area `*_SPEC.md` checklists. Specs are the **guideline**, not a bible — improve on them as gaps
surface. Ship one feature branch + PR per slice, CI-gated, per the slice workflow.

## Scope rules

- **In scope:** everything needed to ship and safely operate Android for real users.
- **Out of scope (deferred):** iOS-specific work (StoreKit bridge, iOS secure storage, iOS process-death
  QA) and the Compose-for-Web/Wasm **PWA companion** — both come *after* Android is production-grade.
  iOS-only spec checkboxes are intentionally not counted as Android blockers.
- **Two execution tracks run in parallel:**
  - **🟢 Codeable** — engineering an agent can implement + locally `verify.ps1`-gate now.
  - **🔵 Founder-ops** — external accounts, cloud/billing provisioning, real-device QA, legal sign-off.
    These *gate release* but rarely block writing the code (we build against mocks/sandboxes, then flip).

## Current state (baseline, 2026-06-07)

- **Core loop is strong:** Grid, Swipe, Activity, Messages, Store all built; Grid perf (pull-to-refresh,
  cached first paint, Redis candidate cache) shipped; Psync epic complete; Activity restructured to
  Flirts/Views/Matches/Requests; Messages new-matches rail. Travel/Passport mode shipped.
- **God-object refactor** core done; backend hardening sweep started (notification metrics, log redaction).
- **Immediate blocker:** GitHub Actions billing is suspended → no PR can merge. PRs **#102, #103, #104**
  are locally green and queued. **This is the first thing to clear.** (See `project_ci_billing_block`.)

---

## Phase 0 — Unblock the pipeline (do first)

| Slice | Track | What |
|------|-------|------|
| 0.1 | 🔵 | **Restore GitHub Actions billing** (Settings → Billing & plans). Nothing merges until this is fixed. |
| 0.2 | 🟢 | Merge the queued PRs in order: **#103 → #102 → #104** (reconcile #104's seed/QA "Likes"→Flirts on the way), pull main. |
| 0.3 | 🔵 | Reserve email aliases (`support@`, `privacy@`, `security@`, `legal@`, `moderation@`, `admin@`, `no-reply@`), GitHub org decision, **branch protection** on `main`. |

---

## Phase A — Trust, Safety & Compliance (launch-blocking; mostly 🟢)

Google Play will reject a dating/lifestyle app that can't demonstrate age-gating, moderation, and
reporting. This phase is the hard gate to submission and is mostly codeable.

| Slice | Track | What | Spec |
|------|-------|------|------|
| A1 | 🟢 | **Age-verification enforcement end-to-end** — gate discovery/posting/age-restricted features until verified; wire the existing `AGE_VERIFICATION_REQUIRED` path everywhere it's required; verification UI completeness. | MODERATION_AND_AGE_VERIFICATION |
| A2 | 🟢🔵 | **Media moderation pipeline** — public/profile media stays hidden until approved; private-album media is consent-gated with severe scanner removal/escalation; system reports + risk events + reported-media admin removal are now code-complete locally. Live Vision tuning, CSAM/hash matching, legal retention, and production admin operations remain founder/legal/provider work. | MODERATION, TRUST_AND_SAFETY |
| A3 | 🟢 | **Appeals flow** — user-facing submission/status/withdraw plus server states and audited admin decisions are code-complete locally; senior-review separation, MFA/roles, user notice polish, and final severe/legal non-appealable policy remain. | MODERATION, ADMIN_CONSOLE |
| A4 | 🟢 | **Risk middleware completeness** — Ktor risk middleware coverage, device/account-farm hash signals, location-spoofing signals, "verification lowers risk", false-positive metrics. | TRUST_AND_SAFETY |
| A5 | 🟢🔵 | **Legal surfaces in-app** — Privacy Policy + Terms + Community Guidelines public URLs wired into onboarding/settings; finalize the drafts (🔵 legal review). | PRIVACY_POLICY_DRAFT, TERMS_DRAFT, COMMUNITY_GUIDELINES |
| A6 | ✅ (local) | **Account lifecycle** — data-export **archive generation** + async worker + owner-only local-dev delivery + account deletion already shipped (`AccountDataExportWorker`, pluggable `AccountExportStorage`). Only the production **GCS signed delivery** swap remains → folded into **D1**. | PROFILE_SETTINGS, API_CONTRACT |
| A7 | 🟢 | **Analytics sensitivity guarantees** — event registry; never send exact location / message text / lifestyle payloads; crash-log redaction; experiment flags can't bypass server limits. | ANALYTICS_AND_METRICS |

---

## Phase B — Monetization & Notifications (launch-critical; 🟢 code + 🔵 creds)

| Slice | Track | What | Spec |
|------|-------|------|------|
| B1 | 🟢🔵 | **Play Billing end-to-end** — real Play Console receipt validation + entitlement reconciliation against server state (code now against sandbox; 🔵 needs Play Console + service-account creds). | MONETIZATION, BACKEND |
| B2 | 🟢🔵 | **FCM notifications** — finish push wiring + on-device deep-link delivery; **cap boost/result notifications**; denied-permission logic device QA (🔵 needs `google-services.json`). | NOTIFICATIONS |

---

## Phase C — Auth & Onboarding hardening (🟢)

| Slice | Track | What | Spec |
|------|-------|------|------|
| C1 | 🟢 | **Secure token storage** — Android Keystore-backed `expect/actual` for session/refresh tokens (replace plain storage). | ONBOARDING_AUTH, MOBILE_ARCH |
| C2 | 🟢 | **Onboarding completeness** — signup progress saved; skippable photo/tags/looking-for; first grid uses signup data; bounded post-signup nudges; permission-denied QA. | ONBOARDING_AUTH |
| C3 | 🟢🔵 | *(Optional for v1)* Social auth (Google) — defer Apple to iOS. | ONBOARDING_AUTH, API_CONTRACT |

---

## Phase D — Backend production hardening (🟢 + 🔵 cloud)

| Slice | Track | What | Spec |
|------|-------|------|------|
| D1 | 🟢🔵 | **Production signed object storage** — real GCS signed upload/download for profile media + private albums (🔵 bucket/SA/lifecycle pending per `project_gcp_provisioning_pending`). | BACKEND, MODERATION |
| D2 | 🟢 | **Hardening pass** — grid/swipe cursor/session hardening, rate-limit completeness across mutation endpoints, finish sensitive-log redaction, App Check boundary. | BACKEND, API_CONTRACT |
| D3 | 🟢 | **Realtime gaps (launch-needed only)** — add reconnecting streams where the product needs them (receipts/entitlement/album-grant); HTTP stays authoritative. Defer the rest. | BACKEND, MOBILE_ARCH |

---

## Phase E — Admin / Moderation console (🟢; ops must exist to launch safely)

| Slice | Track | What | Spec |
|------|-------|------|------|
| E1 | 🟢 | **Unified moderation console** — reports + risk + held-message queues in one console; user lookup; subscription support view; partner-link review. | ADMIN_CONSOLE |
| E2 | 🟢 | **Admin access control** — roles/permissions, **MFA**, general audit-log middleware, production `/v1/admin` API separation, sensitive-reveal reason flow. | ADMIN_CONSOLE |

---

## Phase F — Observability & Quality gates (🟢 + 🔵 device)

| Slice | Track | What | Spec |
|------|-------|------|------|
| F1 | 🟢🔵 | **Crash + metrics** — Crashlytics (🔵 Firebase), server product-event metrics for authoritative KPIs, purchase metrics from validated server state. | ANALYTICS, PRODUCTION_READINESS |
| F2 | 🟢 | **Critical E2E suite on Android** — login → grid → swipe → match → message → store, automated. | TESTING_STRATEGY |
| F3 | 🟢 | **Release-grade abuse/risk suite** — expand beyond core abuse tests. | TESTING, TRUST_AND_SAFETY |
| F4 | 🟢🔵 | **Performance proof** — repeat 500-concurrent server-profiled Grid runs + larger metro datasets; close warm-run p95 variance. | TESTING, GRID |

---

## Phase G — Feature / polish completeness (🟢)

| Slice | Track | What | Spec |
|------|-------|------|------|
| G1 | 🟢 | **Playing Tonight finish** — together/approval flow variant + remaining spec items (core + zone map shipped). | PLAYING_TONIGHT |
| G2 | 🟢 | **Profile/filter correctness** — has-photo excludes photoless profiles, dealbreakers respected, profile-update propagation, partner-unlink visibility regression coverage. | PROFILE_FILTER_SYNC |
| G3 | 🟢 | **Discreet app icon** + any remaining settings items. | PROFILE_SETTINGS |
| G4 | 🟢 | Final UI micro-polish sweep across the five surfaces to the handoff. | FRONTEND_DESIGN |

---

## Phase H — Infra, CI/CD, Real-device QA & Store submission (🔵-heavy; the finish line)

| Slice | Track | What | Spec |
|------|-------|------|------|
| H1 | 🔵🟢 | **Cloud finalization** — confirm `pynapli-dev/staging/prod` GCP+Firebase projects, billing/cost alerts; **deploy pipeline** (build → migrate → deploy server) to staging then prod. | DEVOPS_INFRA |
| H2 | 🔵 | **Real-device QA matrix** — mid-range Android + older supported device: grid battery/location profile, Playing Tonight battery + push delivery, low-network paths, notification delivery. Harnesses exist; need a physical device. | TESTING |
| H3 | 🔵🟢 | **Store submission** — Play Console listing, app-store-safe screenshots + copy, **Data Safety form**, content rating, signing/release track (internal → closed → production). | DEVOPS, MODERATION, LAUNCH_STRATEGY |

---

## Sequencing & optimization rationale

1. **Phase 0 first** — nothing ships while CI can't run; clearing it also unblocks 3 finished PRs.
2. **Phase A early** — it's the hardest *submission* gate and almost entirely codeable, so it shouldn't
   wait on cloud/creds. Do it while the founder provisions accounts in parallel.
3. **B/C/D/E/F interleave** — these are largely independent; codeable parts proceed immediately, and
   creds-gated parts (B1 Play, B2 FCM, D1 GCS, F1 Crashlytics) have their **code built against
   sandboxes/mocks now** and are flipped to production when the founder's creds land (Phase H window).
4. **G (polish)** runs as filler between gated waits — low risk, high visible value.
5. **H last** — real-cloud, real-device, and store submission are the finish line and depend on
   everything above plus founder-only inputs.

**Critical path to "submittable":** 0 → A (all) → B1 + B2 → F1 → H3. Everything else hardens quality
and ops around that path.

## Definition of Done (Android)

Android is **production-grade end-to-end** when:

- ✅ Age-gating, media moderation + CSAM escalation, reporting, and appeals are enforced and operable.
- ✅ Privacy/Terms/Guidelines are public + in-app; data export + deletion work; analytics carry no
  sensitive data.
- ✅ Play Billing validates real receipts and reconciles entitlements server-side.
- ✅ FCM push (incl. deep links) delivered + capped on a real device; secure token storage in place.
- ✅ A moderation console with roles/MFA/audit exists for ops.
- ✅ Crash reporting + authoritative server metrics live; critical E2E + abuse suites green in CI.
- ✅ Performance budgets met under repeat 500-concurrent profiled load.
- ✅ Real-device QA matrix passed (mid-range + low-network + battery + push).
- ✅ Deployed to a real staging/prod environment via CI/CD; cost alerts set.
- ✅ Submitted (at least internal/closed track) to Google Play with listing + Data Safety complete.

## Next phase after Android: PWA companion

Once the DoD holds, start the **Compose-for-Web (Wasm) PWA** companion: reuse `:shared` product logic
+ DTOs + state holders; web-specific auth/storage/notification adapters; responsive Grid/Swipe/
Activity/Messages/Store; web payment path; SEO/share/PWA install. Track it in a separate
`WEB_PWA_GAMEPLAN.md` when we get there.
