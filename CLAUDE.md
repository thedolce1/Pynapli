# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

Pynapli is a Kotlin Multiplatform + Compose Multiplatform + Ktor social lifestyle app. Six Gradle modules: `:shared`, `:composeApp`, `:androidApp`, `:server`, `:webApp` (Compose-for-Web/Wasm PWA shell — see `docs/WEB_PWA_GAMEPLAN.md`), and `:desktopApp` (JVM shell for fast UI iteration — **dev-only, never shipped**; see "Desktop proxy" in `docs/IOS_BRINGUP_PLAN.md`). The iOS shell lives in `iosApp/` (Xcode project, not a Gradle module).

The repo is in early scaffolding/MVP buildout. The `docs/*_SPEC.md` files are the canonical product and engineering specs (all specs and design docs live under `docs/`). Read the relevant spec before changing a feature area — they encode rules that are not yet expressed in code.

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
.\scripts\verify-fast.ps1                             # tooling check + docker up + Spotless + shared JVM + server tests
.\scripts\verify-android.ps1                          # Android policy check + shared/Compose host tests + debug APK + release lint
.\scripts\verify-full.ps1                             # full local gate (incl. release lint + minified release APK); verify.ps1 is a compatibility alias
.\scripts\verify-web.ps1                              # web gate: spotless + wasm compile (:shared/:composeApp/:webApp) + shared JVM tests
.\scripts\verify-desktop.ps1                          # desktop gate: spotless + :desktopApp:desktopTest (compiles the JVM shell)
.\scripts\compose-metrics.ps1                         # Compose compiler skippability/stability report (opt-in; -Android for the shipping variant)
.\gradlew.bat :webApp:wasmJsBrowserDevelopmentRun     # web PWA dev server on :8081 (Ktor API stays on :8080)
.\gradlew.bat :desktopApp:run                         # desktop shell — same Compose UI, no emulator (dev-only)
.\gradlew.bat :desktopApp:hotRunDesktopAsync          # same shell with hot reload (needs a JetBrains Runtime — see below)
.\gradlew.bat :desktopApp:reload                      # push edits into the already-running hot shell, keeping app state
```

Server tests use JUnit Platform (Kotest runner), so `--tests` filters apply.

A `pre-push` hook runs the matching verify gates automatically. One platform gate —
`composeApp`/`androidApp`/`iosApp` → `verify-full`, otherwise `verify-fast` — plus any *additive*
gate the change affects, because no single gate covers every target: `shared`/`composeApp`/`webApp`
also run `verify-web` (neither fast nor full compiles wasm), and `desktopApp`/`shared`/`composeApp`
also run `verify-desktop` (nothing else compiles the JVM shell). Every selected gate must pass. It
lives in `scripts/hooks/` so it is version controlled, but `core.hooksPath` is *local* config —
enable it once per clone:

```powershell
git config core.hooksPath scripts/hooks     # one-time, per clone
git push --no-verify                        # escape hatch when you know why
```

Local dev seed (server must be running):

```powershell
.\scripts\seed-dev-data.ps1                           # creates dev@pynapli.local + nearby grid profiles
.\scripts\seed-dev-data.ps1 -SetAndroidEmulatorLocation  # also sets emulator GPS via adb
```

Dev login (visible on the auth screen in debug builds): `dev@pynapli.local` / `PynapliDevPass123!`. The seed script creates the viewer account, sets its location/profile, and creates ~8 nearby profiles so the first authenticated grid has cards.

Health checks: `GET http://localhost:8080/health` (process), `GET http://localhost:8080/health/deep` (Postgres + Redis). Flyway migrations run at server startup.

## Architecture

### Module ownership (enforced — see `docs/PROJECT_STRUCTURE_AND_DEPENDENCIES_SPEC.md`)

- `:shared` — KMP product logic, DTOs, repository interfaces, Ktor *client*, validation, state holders, SQLDelight schema, `expect/actual` platform abstractions. Targets: `jvm`, `android` (`com.android.kotlin.multiplatform.library`), `iosArm64`, `iosSimulatorArm64`, `wasmJs`. (No `iosX64`: Compose Multiplatform 1.11 stopped publishing Intel-simulator artifacts.) SQLDelight database `PynapliDatabase` lives in package `com.pynapli.shared.storage`. **Must not** contain Firebase/StoreKit/Play-Billing/raw OS calls — those are platform-specific.
- `:composeApp` — Shared Compose UI (Android + iOS framework `PynapliCompose`, static). Screens consume UI state + callbacks; state holders live in `:shared` unless platform constraints force otherwise. **Screens must not call Ktor directly.**
- `:androidApp` — `com.android.application` (`com.pynapli.app`, minSdk 23, compileSdk 36, targetSdk 36, JVM target 17). Owns `AndroidManifest`, `Application`, `Activity`, resources only. **Do not** collapse the Android entry point into the KMP library modules.
- `:server` — Ktor/Netty (`com.pynapli.server.ApplicationKt`). Postgres + PostGIS via JDBC + HikariCP, Flyway migrations, Lettuce Redis. Tests use Testcontainers + Kotest + MockK.
- `iosApp/` — Swift/SwiftUI shell that hosts the `PynapliCompose` framework. Not a Gradle module.
- `:desktopApp` — JVM/Compose-Desktop shell (`com.pynapli.desktop.MainKt`) for fast UI iteration without an emulator or a Mac. `DesktopAppGraph` mirrors the other shells over `PynapliCommonGraph`. **Dev-only, never shipped:** session tokens are plaintext on disk, so `DesktopRuntimeConfig` refuses any non-local API host without an explicit `-Dpynapli.allowRemote=true`. Green here is **not** a substitute for `scripts/android-*-qa.ps1` — it cannot see safe-area insets, touch-gesture feel, IME, billing, or push.

### Server-authoritative model (load-bearing)

> Ktor is the product authority. Firebase is supporting infrastructure where it gives leverage.

The server owns: auth, account risk/trust, profile model, grid discovery, swipe ranking, partner linking, activity generation, chat membership, private album grants, subscription entitlement, age verification, moderation, rate limiting, WebSocket events. Firebase is only used for FCM, Crashlytics, Analytics, Remote Config, App Check (and optional Firebase Auth as IdP). **Never** put discovery ranking, location eligibility, entitlement, partner state, album permissions, or chat membership authority in client-side Firebase rules or in mobile UI.

Auth uses server-owned **opaque** access/refresh tokens, PBKDF2 password hashing, hashed email/token storage in Postgres. Current location is stored server-side and is **not** echoed back to clients. Presence uses Redis TTL keys. WebSocket events are convenience updates; HTTP fetch remains canonical — on reconnect, refetch canonical state.

### Server layout

`server/src/main/kotlin/com/pynapli/server/Application.kt` wires `ServerConfig.fromEnvironment()` → `ServerDependencies` → `embeddedServer(Netty)`. Routes are configured per feature module (`auth/`, `profile/`, `location/`, `presence/`, `grid/`, `activity/`, `messaging/`, `media/`) via `configureXxxRoutes(...)` extension functions on `Application`. Plugins (`Serialization`, `StatusPages`, `Monitoring`, `HTTP`/CORS) live in `plugins/`. Flyway migrations are at `server/src/main/resources/db/migration/V*.sql` — add new ones with the next sequential number; **never edit applied migrations**, write a follow-up migration instead.

Server rules: routes are thin, domain services enforce product rules, repositories handle storage. WebSocket events are convenience-only; HTTP/state fetch remains authoritative. Subscription entitlements and trust/safety decisions are server-validated.

### Client conventions

- State: `StateFlow` for screen state, `SharedFlow` for one-off events, immutable UI state objects (e.g. `GridUiState`).
- Mobile uses authenticated WebSocket convenience deltas for Messages; inbox summaries and an open conversation still refetch canonical HTTP state after each delta.
- Conversation detail returns newest-first with opaque `nextCursor` tokens for older history.
- Message payloads separate moderation `status` from delivery `deliveryStatus` (`sent`/`read`/`received`).
- The composer validates blank/over-limit drafts locally before calling the message API.
- Grid profile open/close must return to the exact same `LazyGridState` scroll position; do not refetch/reorder unless the user refreshes or state expires.
- Do not retry non-idempotent mutations unless an idempotency key exists. Do not log message bodies, private media URLs, exact location, or sensitive tags.

### API surface (current)

Versioned under `/v1/`: `auth/email/{signup,login}`, `auth/{refresh,logout}`, `me`, `me/profile`, `profiles/{userId}`, `location/current`, `presence/heartbeat`, `grid/query`, `messages/threads[/{threadId}[/{read,messages}]]`, `media/uploads[/{assetId}/complete]`. Full list in `README.md`.

## Version policy (load-bearing — see `docs/OFFICIAL_TECH_REFERENCE_GUIDE.md`)

- **Use stable releases only.** No alpha/beta/RC/EAP/dev/preview versions without a written reason.
- All external versions live in `gradle/libs.versions.toml`. Module build files reference catalog aliases — no inline version strings.
- Internal deps use type-safe project accessors (`projects.shared`, `projects.composeApp`); `TYPESAFE_PROJECT_ACCESSORS` is enabled.
- Current baseline: Kotlin 2.3.21, Compose Multiplatform 1.11.0 (bumped from 1.10.3 on 2026-07-03: 1.10.3's wasm target dropped clicks inside scrollable containers — grid tiles, pills, list rows; 1.11.0's reworked web pointer processing fixes it), AGP 9.2.1, Ktor 3.4.3, Gradle 9.4.1, JDK 17, SQLDelight 2.3.2, Coil 3.4.0, Firebase BoM 34.12.0, Play Billing 8.3.0, Postgres JDBC 42.7.10, Flyway 12.4.0, Lettuce 7.5.1, Kotest 6.1.11.
- Android `minSdk = 23` (driven by Firebase 34.x requirements, not Compose). iOS minimum is 13.
- **Verify dependency / Gradle plugin / Compose API / Ktor API / Firebase / StoreKit / Play Billing suggestions against official docs before treating them as correct.** Official references win over AI memory or community posts on conflict.

## Constraints to respect

- Don't commit `google-services.json`, `GoogleService-Info.plist`, `service-account*.json`, `.env`, `.env.*` (`.env.example` is allowed). `.gitignore` covers these — don't override.
- **DI is manual constructor injection, on purpose — there is no DI framework.** Koin was a declared dependency with zero usages and was removed 2026-07-27. Manual wiring gives compile-time verification (a missing dependency fails the build, not the first screen that resolves it), which matters across five targets that can't all be run locally; a service locator reached from composables would also undo the explicit-dependency work in `PynapliAppGraph`. Shared wiring lives in `PynapliCommonGraph` (commonMain); each shell supplies only genuinely platform-specific pieces. Don't add a DI framework without a written reason.
- Don't introduce a second image loader or DB abstraction. Coil 3 / SQLDelight are the chosen ones.
- Don't put product business rules in Compose screens or Android/iOS shells. They belong in `:shared` (state holders / use cases) and `:server` (domain services).
- Don't add Firebase calls in `:shared`; keep them behind platform services in `:androidApp` / `iosApp`.
- Tiny features don't need full `data/`/`domain/`/`presentation/` layering — apply the pattern only where it pays for itself.

## Specs to consult by area

- Backend / API authority: `docs/BACKEND_ARCHITECTURE_SPEC.md`, `docs/API_CONTRACT_SPEC.md`, `docs/DATA_MODEL_SPEC.md`
- Mobile architecture: `docs/MOBILE_APP_ARCHITECTURE_SPEC.md`
- Module/dependency layout: `docs/PROJECT_STRUCTURE_AND_DEPENDENCIES_SPEC.md`
- Tech references and version policy: `docs/OFFICIAL_TECH_REFERENCE_GUIDE.md`
- Production gates / load / security: `docs/PRODUCTION_READINESS_AND_QUALITY_SPEC.md`
- Testing layers and release checklist: `docs/TESTING_STRATEGY_SPEC.md`
- Per-feature: `docs/GRID_*`, `docs/SWIPE_*`, `docs/ACTIVITY_*`, `docs/MESSAGING_*`, `docs/MODERATION_*`, `docs/MONETIZATION_*`, `docs/PSYNC_*`, `docs/PLAYING_TONIGHT_*`, etc.

## Production-readiness initiative (active)

Pynapli is being driven from "mature MVP" to **enterprise-grade, production-ready** for three targets: Google Play, the Apple App Store, and `pynapli.com` (Compose-for-Web Wasm PWA). `docs/IMPLEMENTATION_AUDIT.md` is the authoritative gap analysis; the full phased plan is the working roadmap.

Current sequencing (locked with the founder, 2026-05-31): **polish the core loop first** — finish and visually elevate the five core surfaces (Grid, Swipe, Activity, Messages, Store) and the backend behind them to production grade before standing up new surfaces (web/PWA, notifications) or the differentiators (Playing Tonight, Psync).

### Design north-star (load-bearing)

The visual target is the Claude design handoff at `C:\Users\Smaye\Downloads\Pynapli-Handoff` (JSX prototype + screenshots + the Messages/Tonight Map HTML specs). The founder confirmed the design docs are a guide, not the bible; the handoff refines the existing design language rather than replacing it. Handoff token values (`components/primitives.jsx`) to move toward:

- Background `#0B0B0E`, surface `#121216`, surfaceRaised `#16161C`, surfacePressed `#1E1E26`, border `#23232C`, **new** borderSoft `#1B1B22`.
- Text warm off-white `#F5F2EC` / secondary `#A8A29A` / tertiary `#6B6660` (slightly warmer than current `#F5F5F5/#B8B8B8/#7A7A7A`).
- Semantics unchanged: online `#18D77A`, verified/info `#4DA3FF`, danger `#FF4D5E`, warning `#FFA23A`.
- Radii in practice: grid tiles ~4, cards/banners 12, swipe card 16, sheets 16, pills 100.

**Accent = pineapple-gold `#FFC928`** (founder-locked 2026-05-31; matches the handoff default and the Messages/Tonight Map specs). Keep accent routed through a single token (`PynapliColors.Accent`) so any future swap is one edit, and follow the spec's caution to use gold as a premium/highlight accent rather than flooding it across the UI.

**Spacing is deliberately not on a fixed scale** (settled 2026-07-23, don't re-litigate). The handoff
has no spacing scale to conform to — its own gaps run 2,3,4,5,6,7,8,9,10,12,14,16 and its paddings 1–14,
all hand-tuned. The app matches that: ~700 call sites over ~25 values, with 8 and 12 dominant. Snapping
that to an 8dp (or 4dp) grid would visibly re-rhythm every screen, and no spacing-caused defect has been
found to justify it — unlike the radii, where one file's cards disagreed with its own banner. So: use
`PynapliSpacing`/`PynapliLayout` where a shared rhythm genuinely applies (screen padding is
`PynapliLayout.ScreenPadding`), hand-tune inside components, and **do not** add a spacing rule to the
token guard. Section rhythm in practice is 8dp or 12dp; the Grid stays denser on purpose.

Radii and colors in `:composeApp` feature code **must** be tokens, not literals — `scripts/check-compose-tokens.ps1` (in `verify-full` / `verify-android`) fails the gate on a raw `RoundedCornerShape(N.dp)`, `Color(0x…)`, or bare `Color.White`/`Color.Black`. Pick a `PynapliShape` token by the element's *role*: `SmallShape` grid tiles/indicators, `PhotoShape` photo and thumbnail corners, `CardShape` cards/banners/rows/inputs, `SheetShape` sheets, `PillShape` chips. For the rare value that genuinely is not a palette/shape decision (e.g. a pure-black drop shadow), add a `token-guard:allow` comment on that line or the one above. Spacing is deliberately **not** guarded — `PynapliSpacing` is 8dp-based but ~700 call sites use 6/10/12/14/18dp, so a rule would fail on day one; settle the intended scale before adding one.

Everything else in `FRONTEND_DESIGN_DIRECTION_SPEC.md` / `DESIGN_AND_NAVIGATION_SPEC.md` still holds (dark-first, dense Grid, restrained, no decorative blobs). When restyling, move tokens toward the handoff and update visual-regression goldens deliberately.

### Slice workflow (how this work ships)

One feature branch + PR per slice, CI-gated. Each slice: implement (server stays the product authority) → targeted module tests → `scripts/verify-fast.ps1` for backend/shared-only slices or `scripts/verify-full.ps1` when Android/Compose code is touched → emulator QA for UI slices (`scripts/android-*-qa.ps1` — match the surface, e.g. `android-grid-qa.ps1`, `android-swipe-qa.ps1`, `android-tonight-qa.ps1` for Playing Tonight) → push → CI → merge. Update `docs/IMPLEMENTATION_AUDIT.md` rows + relevant `*_SPEC.md` checklists per shipped slice. Pause for review only at **phase boundaries**. Never run `gradlew` (incl. `--stop`) while a verify gate is running — it disrupts the daemon.

### Agent "engineering team" model

Large slices may be delegated to specialized sub-agents (the founder asked for a full-team workflow), each given the relevant spec + handoff context. Sub-agents are reviewed by the orchestrator before anything merges; they never bypass the slice gates above:

- **Architect/Plan** (`Plan`) — design a slice's implementation strategy and critical files before coding.
- **Backend** — Ktor domain services, JDBC/PostGIS, Flyway migrations, perf; server-authoritative rules only.
- **Frontend/Design** (`frontend-design` skill) — restyle Compose surfaces to the handoff language using token-only primitives.
- **Explore** — fan-out codebase searches when locating call sites / conventions.
- **Reviewer** (`/code-review`, `/security-review`) — correctness + security pass on each diff before merge.

All security/privacy/billing/consent/moderation rules remain server-validated regardless of which agent wrote the code (`PRODUCTION_READINESS_AND_QUALITY_SPEC.md`).
