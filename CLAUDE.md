# CLAUDE.md

Pynapli is a Kotlin Multiplatform + Compose Multiplatform + Ktor social lifestyle app. Gradle modules: `:shared`, `:composeApp`, `:androidApp`, `:server`, `:webApp` (Wasm PWA shell — `docs/WEB_PWA_GAMEPLAN.md`), `:desktopApp` (dev-only JVM shell — see below). The iOS shell is `iosApp/` (Xcode project, not a Gradle module).

The `docs/*_SPEC.md` files are the canonical product and engineering specs. Read the relevant spec before changing a feature area — they encode rules not yet expressed in code. Module-local rules live in `composeApp/CLAUDE.md` (its design/token rules also apply to Compose code in `:webApp` and `:desktopApp`) and `server/CLAUDE.md`; local run/seed steps are the `local-dev` skill; the slice shipping process is the `ship-slice` skill.

## Verify gates

```powershell
.\scripts\verify-fast.ps1      # tooling + docker up + Spotless + shared JVM + server tests
.\scripts\verify-android.ps1   # Android policy + shared/Compose host tests + debug APK + release lint
.\scripts\verify-full.ps1      # full local gate (incl. minified release APK); verify.ps1 is an alias
.\scripts\verify-web.ps1       # spotless + wasm compile (:shared/:composeApp/:webApp) + shared JVM tests
.\scripts\verify-desktop.ps1   # spotless + :desktopApp:desktopTest
```

The `pre-push` hook runs the gates a change needs (selection rules: header of `scripts/hooks/pre-push`). `core.hooksPath` is local config — enable once per clone with `git config core.hooksPath scripts/hooks`. **Never run `gradlew` (incl. `--stop`) while a verify gate is running** — it disrupts the daemon.

## Module ownership (enforced — `docs/PROJECT_STRUCTURE_AND_DEPENDENCIES_SPEC.md`)

- `:shared` — product logic, DTOs, repository interfaces, Ktor *client*, validation, state holders, SQLDelight, `expect/actual`. **Must not** contain Firebase/StoreKit/Play-Billing/raw OS calls — keep those behind platform services in `:androidApp` / `iosApp`. No `iosX64` target: Compose Multiplatform 1.11 stopped publishing Intel-simulator artifacts.
- `:composeApp` — shared Compose UI. **Screens must not call Ktor directly.**
- `:androidApp` — `AndroidManifest`, `Application`, `Activity`, resources only. **Do not** collapse the Android entry point into the KMP library modules.
- `:desktopApp` — **dev-only, never shipped**: session tokens are plaintext on disk, so `DesktopRuntimeConfig` refuses non-local API hosts without `-Dpynapli.allowRemote=true`. Green here is **not** a substitute for `scripts/android-*-qa.ps1` — it can't see safe-area insets, touch feel, IME, billing, or push.

## Server-authoritative model (load-bearing)

> Ktor is the product authority. Firebase is supporting infrastructure where it gives leverage.

The server owns auth, account risk/trust, profile model, grid discovery, swipe ranking, partner linking, activity generation, chat membership, private album grants, subscription entitlement, age verification, moderation, rate limiting, WebSocket events. Firebase is only FCM, Crashlytics, Analytics, Remote Config, App Check (optional Firebase Auth as IdP). **Never** put discovery ranking, location eligibility, entitlement, partner state, album permissions, or chat membership authority in client-side Firebase rules or mobile UI. Access/refresh tokens are opaque and server-owned. Current location is stored server-side and is **never** echoed back to clients (watch this in `:shared` DTOs). WebSocket events are convenience updates; HTTP fetch is canonical — on reconnect, refetch.

## Client conventions

- `StateFlow` for screen state, `SharedFlow` for one-off events, immutable UI state objects.
- Messages: inbox and open conversation refetch canonical HTTP state after each WebSocket delta (cursor, status/deliveryStatus, composer rules: `docs/MESSAGING_SPEC.md`).
- Don't retry non-idempotent mutations without an idempotency key. Don't log message bodies, private media URLs, exact location, or sensitive tags.

## Version policy (load-bearing — `docs/OFFICIAL_TECH_REFERENCE_GUIDE.md`)

- **Stable releases only.** No alpha/beta/RC/EAP/dev/preview without a written reason.
- All external versions in `gradle/libs.versions.toml`; build files use catalog aliases, no inline versions. Internal deps use type-safe project accessors.
- Compose Multiplatform must stay ≥ 1.11.0: 1.10.3's wasm target dropped clicks inside scrollable containers. Android `minSdk = 23` is driven by Firebase 34.x, not Compose.
- **Verify dependency / Gradle plugin / Compose / Ktor / Firebase / StoreKit / Play Billing suggestions against official docs.** Official references win over AI memory on conflict.

## Constraints

- Don't commit `google-services.json`, `GoogleService-Info.plist`, `service-account*.json`, `.env`, `.env.*` (`.env.example` is allowed). Don't override `.gitignore` on these.
- **DI is manual constructor injection, on purpose — no DI framework.** Koin (unused) was removed 2026-07-27. Manual wiring fails the build on a missing dependency instead of the first screen that resolves it, which matters across five targets that can't all be run locally. Shared wiring lives in `PynapliCommonGraph`; each shell supplies only platform-specific pieces. Don't add a DI framework without a written reason.
- Don't introduce a second image loader or DB abstraction (Coil 3 / SQLDelight are chosen).
- Don't put product business rules in Compose screens or Android/iOS shells — they belong in `:shared` and `:server`.
- Tiny features don't need full `data/`/`domain/`/`presentation/` layering.

## Specs by area

- Backend / API: `docs/BACKEND_ARCHITECTURE_SPEC.md`, `docs/API_CONTRACT_SPEC.md`, `docs/DATA_MODEL_SPEC.md`
- Mobile: `docs/MOBILE_APP_ARCHITECTURE_SPEC.md`; production gates: `docs/PRODUCTION_READINESS_AND_QUALITY_SPEC.md`; testing: `docs/TESTING_STRATEGY_SPEC.md`
- Per-feature: `docs/GRID_*`, `docs/SWIPE_*`, `docs/ACTIVITY_*`, `docs/MESSAGING_*`, `docs/MODERATION_*`, `docs/MONETIZATION_*`, `docs/PSYNC_*`, `docs/PLAYING_TONIGHT_*`, etc.

## Production-readiness initiative (active)

Driving Pynapli to production grade for Google Play, the App Store, and `pynapli.com` (Wasm PWA). `docs/IMPLEMENTATION_AUDIT.md` is the authoritative gap analysis. Sequencing (founder-locked 2026-05-31): **polish the core loop first** — Grid, Swipe, Activity, Messages, Store and their backend — before new surfaces or differentiators. One PR per slice; pause for review only at phase boundaries (process: `ship-slice` skill).
