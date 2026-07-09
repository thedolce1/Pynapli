# Pynapli Docs

Canonical product and engineering specs for Pynapli. **Read the relevant spec before changing a feature area** — they encode rules that are not yet expressed in code. Specs are authoritative; on conflict, the spec wins over AI memory or community posts (see `OFFICIAL_TECH_REFERENCE_GUIDE.md` for version policy).

The app is a Kotlin Multiplatform + Compose Multiplatform + Ktor social lifestyle app. The server is the product authority; Firebase is supporting infrastructure. See the root `CLAUDE.md` for build commands and architecture ground rules.

> **Specs vs. living docs.** The **Product**, **Architecture**, **Features**, **Design**, **Trust/Safety**, and **Quality** sections below are authoritative specs — treat them as source of truth. The **Deployment & infra** and **Plans & status** sections are *living working docs* (runbooks, audits, gameplans) that describe current state and intent, not fixed rules.

## Product & strategy

| Doc | What it covers |
| --- | --- |
| [PYNAPLI_PRODUCT_SPEC.md](PYNAPLI_PRODUCT_SPEC.md) | Top-level product vision, scope, and core experience. |
| [MVP_ROADMAP.md](MVP_ROADMAP.md) | Phased MVP plan and delivery sequencing. |
| [LAUNCH_STRATEGY_SPEC.md](LAUNCH_STRATEGY_SPEC.md) | Go-to-market and launch readiness. |
| [LEGACY_RULES_IMPORT_REVIEW.md](LEGACY_RULES_IMPORT_REVIEW.md) | Review of legacy rules carried into the current product. |

## Architecture & engineering

| Doc | What it covers |
| --- | --- |
| [BACKEND_ARCHITECTURE_SPEC.md](BACKEND_ARCHITECTURE_SPEC.md) | Server authority model, domain services, Ktor layout. |
| [MOBILE_APP_ARCHITECTURE_SPEC.md](MOBILE_APP_ARCHITECTURE_SPEC.md) | KMP/Compose client architecture and state conventions. |
| [PROJECT_STRUCTURE_AND_DEPENDENCIES_SPEC.md](PROJECT_STRUCTURE_AND_DEPENDENCIES_SPEC.md) | Module ownership and dependency rules (enforced). |
| [API_CONTRACT_SPEC.md](API_CONTRACT_SPEC.md) | Versioned `/v1/` HTTP/WebSocket API surface. |
| [SECURITY_ROUTE_MATRIX.md](SECURITY_ROUTE_MATRIX.md) | Per-route auth / App Check / rate-limit requirements. |
| [DATA_MODEL_SPEC.md](DATA_MODEL_SPEC.md) | Postgres/PostGIS schema and core domain entities. |
| [DATABASE_MIGRATION_PLAN.md](DATABASE_MIGRATION_PLAN.md) | Flyway migration strategy and conventions. |
| [OFFICIAL_TECH_REFERENCE_GUIDE.md](OFFICIAL_TECH_REFERENCE_GUIDE.md) | Version baseline and stable-release-only policy. |

## Features

| Doc | What it covers |
| --- | --- |
| [ONBOARDING_AUTH_SPEC.md](ONBOARDING_AUTH_SPEC.md) | Signup/login, opaque tokens, account bootstrap. |
| [GRID_IMPLEMENTATION_SPEC.md](GRID_IMPLEMENTATION_SPEC.md) | Nearby-profile grid discovery and ranking. |
| [SWIPE_IMPLEMENTATION_SPEC.md](SWIPE_IMPLEMENTATION_SPEC.md) | Swipe deck, ranking, and offline action queue. |
| [ACTIVITY_SPEC.md](ACTIVITY_SPEC.md) | Activity generation and feed. |
| [MESSAGING_SPEC.md](MESSAGING_SPEC.md) | Conversations, delivery vs. moderation status, realtime deltas. |
| [PLAYING_TONIGHT_SPEC.md](PLAYING_TONIGHT_SPEC.md) | "Playing Tonight" intent and map feature. |
| [PSYNC_SPEC.md](PSYNC_SPEC.md) | Partner linking / synchronized swipe (Psync). |
| [FILTERS_SPEC.md](FILTERS_SPEC.md) | Discovery filters. |
| [PROFILE_FILTER_SYNC_SPEC.md](PROFILE_FILTER_SYNC_SPEC.md) | Syncing profile and filter state. |
| [PROFILE_SETTINGS_SPEC.md](PROFILE_SETTINGS_SPEC.md) | Profile management and account settings. |
| [NOTIFICATIONS_SPEC.md](NOTIFICATIONS_SPEC.md) | Push/FCM notifications. |
| [MONETIZATION_SPEC.md](MONETIZATION_SPEC.md) | Subscriptions, entitlement, billing. |

## Design & UX

| Doc | What it covers |
| --- | --- |
| [DESIGN_AND_NAVIGATION_SPEC.md](DESIGN_AND_NAVIGATION_SPEC.md) | Navigation model and screen structure. |
| [FRONTEND_DESIGN_DIRECTION_SPEC.md](FRONTEND_DESIGN_DIRECTION_SPEC.md) | Visual design direction and principles. |
| [DESIGN_TOKENS.md](DESIGN_TOKENS.md) | Color, type, and spacing tokens. |
| [LANGUAGE_AND_TAXONOMY_SPEC.md](LANGUAGE_AND_TAXONOMY_SPEC.md) | Product vocabulary and taxonomy. |

## Trust, safety & moderation

| Doc | What it covers |
| --- | --- |
| [TRUST_AND_SAFETY_SPEC.md](TRUST_AND_SAFETY_SPEC.md) | Account risk/trust model and safety controls. |
| [MODERATION_AND_AGE_VERIFICATION_SPEC.md](MODERATION_AND_AGE_VERIFICATION_SPEC.md) | Content moderation and age verification. |
| [ADMIN_MODERATION_CONSOLE_SPEC.md](ADMIN_MODERATION_CONSOLE_SPEC.md) | Internal admin/moderation console. |
| [COMMUNITY_GUIDELINES.md](COMMUNITY_GUIDELINES.md) | User-facing community guidelines. |

## Quality & operations

| Doc | What it covers |
| --- | --- |
| [PRODUCTION_READINESS_AND_QUALITY_SPEC.md](PRODUCTION_READINESS_AND_QUALITY_SPEC.md) | Production gates, load, and security bars. |
| [TESTING_STRATEGY_SPEC.md](TESTING_STRATEGY_SPEC.md) | Testing layers and release checklist. |
| [ANALYTICS_AND_METRICS_SPEC.md](ANALYTICS_AND_METRICS_SPEC.md) | Analytics events and product metrics. |

## Deployment & infra

Runbooks and current cloud state. See also `../web/HOSTING.md` for the pynapli.com Firebase Hosting deploy.

| Doc | What it covers |
| --- | --- |
| [DEVOPS_INFRA_SETUP_SPEC.md](DEVOPS_INFRA_SETUP_SPEC.md) | Local/CI/cloud infra, Firebase & GCP setup plan, secrets, environments. |
| [CLOUD_STATUS.md](CLOUD_STATUS.md) | One-page Firebase/GCP snapshot — what's live vs. coded-but-not-provisioned. |
| [GCP_PROVISIONING_CHECKLIST.md](GCP_PROVISIONING_CHECKLIST.md) | Step-by-step runbook to stand up the backend on GCP, then PWA + site. |
| [STAGING_TESTER_LANE.md](STAGING_TESTER_LANE.md) | Firebase-enabled staging APK → Cloud Run staging API lane. |
| [WEB_BROWSER_QA_CHECKLIST.md](WEB_BROWSER_QA_CHECKLIST.md) | Manual browser QA checklist for the Wasm PWA. |
| [THIRD_PARTY_DATA.md](THIRD_PARTY_DATA.md) | Bundled datasets, licenses, and attribution obligations (e.g. GeoNames CC BY 4.0). |

## Plans & status (living)

Working documents that track intent and progress — not fixed specs.

| Doc | What it covers |
| --- | --- |
| [IMPLEMENTATION_AUDIT.md](IMPLEMENTATION_AUDIT.md) | Running audit of what's built vs. specced. |
| [ANDROID_PRODUCTION_GAMEPLAN.md](ANDROID_PRODUCTION_GAMEPLAN.md) | Phased plan to take Android to production grade. |
| [WEB_PWA_GAMEPLAN.md](WEB_PWA_GAMEPLAN.md) | Compose-for-Web Wasm PWA plan (W0–W6). |
| [FRONTEND_POLISH_PASS.md](FRONTEND_POLISH_PASS.md) | Phased enterprise-grade frontend polish pass. |
| [OPENAPI_HANDOFF_PLAN.md](OPENAPI_HANDOFF_PLAN.md) | Plan for OpenAPI generation and client handoff. |

## Legal & policy

| Doc | What it covers |
| --- | --- |
| [PRIVACY_POLICY_DRAFT.md](PRIVACY_POLICY_DRAFT.md) | Draft privacy policy. |
| [TERMS_DRAFT.md](TERMS_DRAFT.md) | Draft terms of service. |
