# Pynapli Docs

Canonical product and engineering specs for Pynapli. **Read the relevant spec before changing a feature area** — they encode rules that are not yet expressed in code. Specs are authoritative; on conflict, the spec wins over AI memory or community posts (see `OFFICIAL_TECH_REFERENCE_GUIDE.md` for version policy).

The app is a Kotlin Multiplatform + Compose Multiplatform + Ktor social lifestyle app. The server is the product authority; Firebase is supporting infrastructure. See the root `CLAUDE.md` for build commands and architecture ground rules.

## Product & strategy

| Doc | What it covers |
| --- | --- |
| [PYNAPLI_PRODUCT_SPEC.md](PYNAPLI_PRODUCT_SPEC.md) | Top-level product vision, scope, and core experience. |
| [MVP_ROADMAP.md](MVP_ROADMAP.md) | Phased MVP plan and delivery sequencing. |
| [LAUNCH_STRATEGY_SPEC.md](LAUNCH_STRATEGY_SPEC.md) | Go-to-market and launch readiness. |
| [IMPLEMENTATION_AUDIT.md](IMPLEMENTATION_AUDIT.md) | Running audit of what's built vs. specced. |
| [LEGACY_RULES_IMPORT_REVIEW.md](LEGACY_RULES_IMPORT_REVIEW.md) | Review of legacy rules carried into the current product. |

## Architecture & engineering

| Doc | What it covers |
| --- | --- |
| [BACKEND_ARCHITECTURE_SPEC.md](BACKEND_ARCHITECTURE_SPEC.md) | Server authority model, domain services, Ktor layout. |
| [MOBILE_APP_ARCHITECTURE_SPEC.md](MOBILE_APP_ARCHITECTURE_SPEC.md) | KMP/Compose client architecture and state conventions. |
| [PROJECT_STRUCTURE_AND_DEPENDENCIES_SPEC.md](PROJECT_STRUCTURE_AND_DEPENDENCIES_SPEC.md) | Module ownership and dependency rules (enforced). |
| [API_CONTRACT_SPEC.md](API_CONTRACT_SPEC.md) | Versioned `/v1/` HTTP/WebSocket API surface. |
| [DATA_MODEL_SPEC.md](DATA_MODEL_SPEC.md) | Postgres/PostGIS schema and core domain entities. |
| [OFFICIAL_TECH_REFERENCE_GUIDE.md](OFFICIAL_TECH_REFERENCE_GUIDE.md) | Version baseline and stable-release-only policy. |
| [OPENAPI_HANDOFF_PLAN.md](OPENAPI_HANDOFF_PLAN.md) | Plan for OpenAPI generation and client handoff. |
| [DATABASE_MIGRATION_PLAN.md](DATABASE_MIGRATION_PLAN.md) | Flyway migration strategy and conventions. |
| [DEVOPS_INFRA_SETUP_SPEC.md](DEVOPS_INFRA_SETUP_SPEC.md) | Local/CI infra (Docker, Postgres, Redis) setup. |

## Features

| Doc | What it covers |
| --- | --- |
| [ONBOARDING_AUTH_SPEC.md](ONBOARDING_AUTH_SPEC.md) | Signup/login, opaque tokens, account bootstrap. |
| [GRID_IMPLEMENTATION_SPEC.md](GRID_IMPLEMENTATION_SPEC.md) | Nearby-profile grid discovery and ranking. |
| [SWIPE_IMPLEMENTATION_SPEC.md](SWIPE_IMPLEMENTATION_SPEC.md) | Swipe deck, ranking, and offline action queue. |
| [ACTIVITY_SPEC.md](ACTIVITY_SPEC.md) | Activity generation and feed. |
| [MESSAGING_SPEC.md](MESSAGING_SPEC.md) | Conversations, delivery vs. moderation status, realtime deltas. |
| [PLAYING_TONIGHT_SPEC.md](PLAYING_TONIGHT_SPEC.md) | "Playing Tonight" intent and map feature. |
| [PSYNC_SPEC.md](PSYNC_SPEC.md) | Partner linking / profile sync (PSync). |
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

## Legal & policy

| Doc | What it covers |
| --- | --- |
| [PRIVACY_POLICY_DRAFT.md](PRIVACY_POLICY_DRAFT.md) | Draft privacy policy. |
| [TERMS_DRAFT.md](TERMS_DRAFT.md) | Draft terms of service. |
