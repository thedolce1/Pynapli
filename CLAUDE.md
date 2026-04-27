# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

Pynapli is a Kotlin Multiplatform + Compose Multiplatform + Ktor social lifestyle app. Four Gradle modules: `:shared`, `:composeApp`, `:androidApp`, `:server`. The iOS shell lives in `iosApp/` (Xcode project, not a Gradle module).

The repo is in early scaffolding/MVP buildout. The root-level `*_SPEC.md` files are the canonical product and engineering specs. Read the relevant spec before changing a feature area — they encode rules that are not yet expressed in code.

## Common commands

Local infra (Docker Desktop must be running):

```powershell
docker compose up -d                                  # starts pynapli-postgres (PostGIS) + pynapli-redis
docker compose ps
docker exec pynapli-redis redis-cli ping
docker exec pynapli-postgres pg_isready -U pynapli -d pynapli_dev
```

Build / test (Gradle wrapper):

```powershell
.\gradlew.bat :shared:jvmTest :server:test            # JVM+server unit tests (matches CI in .github/workflows/ci.yml)
.\gradlew.bat :androidApp:assembleDebug               # Android debug APK
.\gradlew.bat :server:run                             # run Ktor server on :8080
.\gradlew.bat :shared:jvmTest --tests "FullyQualifiedTestClass.testName"   # single test
.\scripts\verify.ps1                                  # tooling check + docker up + jvmTest + server:test + assembleDebug
```

Server tests use JUnit Platform (Kotest runner), so `--tests` filters apply.

Local dev seed (server must be running):

```powershell
.\scripts\seed-dev-data.ps1                           # creates dev@pynapli.local + nearby grid profiles
.\scripts\seed-dev-data.ps1 -SetAndroidEmulatorLocation  # also sets emulator GPS via adb
```

Dev login (visible on the auth screen in debug builds): `dev@pynapli.local` / `PynapliDevPass123!`. The seed script creates the viewer account, sets its location/profile, and creates ~8 nearby profiles so the first authenticated grid has cards.

Health checks: `GET http://localhost:8080/health` (process), `GET http://localhost:8080/health/deep` (Postgres + Redis). Flyway migrations run at server startup.

## Architecture

### Module ownership (enforced — see `PROJECT_STRUCTURE_AND_DEPENDENCIES_SPEC.md`)

- `:shared` — KMP product logic, DTOs, repository interfaces, Ktor *client*, validation, state holders, SQLDelight schema, `expect/actual` platform abstractions. Targets: `jvm`, `android` (`com.android.kotlin.multiplatform.library`), `iosX64`, `iosArm64`, `iosSimulatorArm64`. SQLDelight database `PynapliDatabase` lives in package `com.pynapli.shared.storage`. **Must not** contain Firebase/StoreKit/Play-Billing/raw OS calls — those are platform-specific.
- `:composeApp` — Shared Compose UI (Android + iOS framework `PynapliCompose`, static). Screens consume UI state + callbacks; state holders live in `:shared` unless platform constraints force otherwise. **Screens must not call Ktor directly.**
- `:androidApp` — `com.android.application` (`com.pynapli.app`, minSdk 23, compileSdk 36, targetSdk 36, JVM target 17). Owns `AndroidManifest`, `Application`, `Activity`, resources only. **Do not** collapse the Android entry point into the KMP library modules.
- `:server` — Ktor/Netty (`com.pynapli.server.ApplicationKt`). Postgres + PostGIS via JDBC + HikariCP, Flyway migrations, Lettuce Redis. Tests use Testcontainers + Kotest + MockK.
- `iosApp/` — Swift/SwiftUI shell that hosts the `PynapliCompose` framework. Not a Gradle module.

### Server-authoritative model (load-bearing)

> Ktor is the product authority. Firebase is supporting infrastructure where it gives leverage.

The server owns: auth, account risk/trust, profile model, grid discovery, swipe ranking, partner linking, activity generation, chat membership, private album grants, subscription entitlement, age verification, moderation, rate limiting, WebSocket events. Firebase is only used for FCM, Crashlytics, Analytics, Remote Config, App Check (and optional Firebase Auth as IdP). **Never** put discovery ranking, location eligibility, entitlement, partner state, album permissions, or chat membership authority in client-side Firebase rules or in mobile UI.

Auth uses server-owned **opaque** access/refresh tokens, PBKDF2 password hashing, hashed email/token storage in Postgres. Current location is stored server-side and is **not** echoed back to clients. Presence uses Redis TTL keys. WebSocket events are convenience updates; HTTP fetch remains canonical — on reconnect, refetch canonical state.

### Server layout

`server/src/main/kotlin/com/pynapli/server/Application.kt` wires `ServerConfig.fromEnvironment()` → `ServerDependencies` → `embeddedServer(Netty)`. Routes are configured per feature module (`auth/`, `profile/`, `location/`, `presence/`, `grid/`, `activity/`, `messaging/`, `media/`) via `configureXxxRoutes(...)` extension functions on `Application`. Plugins (`Serialization`, `StatusPages`, `Monitoring`, `HTTP`/CORS) live in `plugins/`. Flyway migrations are at `server/src/main/resources/db/migration/V*.sql` — add new ones with the next sequential number; **never edit applied migrations**, write a follow-up migration instead.

Server rules: routes are thin, domain services enforce product rules, repositories handle storage. WebSocket events are convenience-only; HTTP/state fetch remains authoritative. Subscription entitlements and trust/safety decisions are server-validated.

### Client conventions

- State: `StateFlow` for screen state, `SharedFlow` for one-off events, immutable UI state objects (e.g. `GridUiState`).
- Mobile uses lightweight polling for early realtime: inbox summaries refresh after sign-in; an open conversation refreshes while visible.
- Conversation detail returns newest-first with opaque `nextCursor` tokens for older history.
- Message payloads separate moderation `status` from delivery `deliveryStatus` (`sent`/`read`/`received`).
- The composer validates blank/over-limit drafts locally before calling the message API.
- Grid profile open/close must return to the exact same `LazyGridState` scroll position; do not refetch/reorder unless the user refreshes or state expires.
- Do not retry non-idempotent mutations unless an idempotency key exists. Do not log message bodies, private media URLs, exact location, or sensitive tags.

### API surface (current)

Versioned under `/v1/`: `auth/email/{signup,login}`, `auth/{refresh,logout}`, `me`, `me/profile`, `profiles/{userId}`, `location/current`, `presence/heartbeat`, `grid/query`, `messages/threads[/{threadId}[/{read,messages}]]`, `media/uploads[/{assetId}/complete]`. Full list in `README.md`.

## Version policy (load-bearing — see `OFFICIAL_TECH_REFERENCE_GUIDE.md`)

- **Use stable releases only.** No alpha/beta/RC/EAP/dev/preview versions without a written reason.
- All external versions live in `gradle/libs.versions.toml`. Module build files reference catalog aliases — no inline version strings.
- Internal deps use type-safe project accessors (`projects.shared`, `projects.composeApp`); `TYPESAFE_PROJECT_ACCESSORS` is enabled.
- Current baseline: Kotlin 2.3.21, Compose Multiplatform 1.10.3, AGP 9.1.1, Ktor 3.4.3, Gradle 9.4.1, JDK 17, SQLDelight 2.3.2, Coil 3.4.0, Koin 4.2.1, Firebase BoM 34.12.0, Play Billing 8.3.0, Postgres JDBC 42.7.10, Flyway 12.4.0, Lettuce 7.5.1, Kotest 6.1.11.
- Android `minSdk = 23` (driven by Firebase 34.x requirements, not Compose). iOS minimum is 13.
- **Verify dependency / Gradle plugin / Compose API / Ktor API / Firebase / StoreKit / Play Billing suggestions against official docs before treating them as correct.** Official references win over AI memory or community posts on conflict.

## Constraints to respect

- Don't commit `google-services.json`, `GoogleService-Info.plist`, `service-account*.json`, `.env`, `.env.*` (`.env.example` is allowed). `.gitignore` covers these — don't override.
- Don't introduce a second DI framework, image loader, or DB abstraction. Koin / Coil 3 / SQLDelight are the chosen ones.
- Don't put product business rules in Compose screens or Android/iOS shells. They belong in `:shared` (state holders / use cases) and `:server` (domain services).
- Don't add Firebase calls in `:shared`; keep them behind platform services in `:androidApp` / `iosApp`.
- Tiny features don't need full `data/`/`domain/`/`presentation/` layering — apply the pattern only where it pays for itself.

## Specs to consult by area

- Backend / API authority: `BACKEND_ARCHITECTURE_SPEC.md`, `API_CONTRACT_SPEC.md`, `DATA_MODEL_SPEC.md`
- Mobile architecture: `MOBILE_APP_ARCHITECTURE_SPEC.md`
- Module/dependency layout: `PROJECT_STRUCTURE_AND_DEPENDENCIES_SPEC.md`
- Tech references and version policy: `OFFICIAL_TECH_REFERENCE_GUIDE.md`
- Production gates / load / security: `PRODUCTION_READINESS_AND_QUALITY_SPEC.md`
- Testing layers and release checklist: `TESTING_STRATEGY_SPEC.md`
- Per-feature: `GRID_*`, `SWIPE_*`, `ACTIVITY_*`, `MESSAGING_*`, `MODERATION_*`, `MONETIZATION_*`, `PSYNC_*`, `PLAYING_TONIGHT_*`, etc.