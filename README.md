# Pynapli

Pynapli is a Kotlin Multiplatform + Compose Multiplatform + Ktor social lifestyle app. The Ktor server
is the product authority; Firebase is supporting infrastructure.

## Modules

- `shared`: cross-platform product logic, DTOs, the Ktor API client, validation, SQLDelight storage.
- `composeApp`: shared Compose UI and the feature state holders.
- `androidApp`: Android entry point (`Application`, `Activity`, manifest, resources).
- `server`: Ktor backend (auth, discovery, messaging, moderation, entitlements, WebSockets).
- `webApp`: Wasm PWA shell for `app.pynapli.com` ([docs/WEB_PWA_GAMEPLAN.md](docs/WEB_PWA_GAMEPLAN.md)).
- `desktopApp`: dev-only JVM shell for fast UI iteration; never shipped.
- `iosApp/`: iOS shell (Xcode project, not a Gradle module; [docs/IOS_BRINGUP_PLAN.md](docs/IOS_BRINGUP_PLAN.md)).
- `web/`: the Astro marketing site (separate from the app; see `web/HOSTING.md`).

## Prerequisites

- JDK 17, Android Studio with the Android SDK, Docker Desktop
- Xcode on macOS for iOS builds
- The Gradle wrapper is included

Check your tooling:

```powershell
.\scripts\check-local-tooling.ps1
```

## Run locally

```powershell
docker compose up -d                 # Postgres (PostGIS) + Redis
.\gradlew.bat :server:run            # Ktor on :8080; Flyway migrates at startup
.\scripts\seed-dev-data.ps1          # dev account + nearby profiles (server must be running)
.\gradlew.bat :androidApp:installDebug
```

Debug Android builds show a **Dev login** button that signs in as the seeded `dev@pynapli.local`
account (debug builds only; release and staging builds don't contain the credentials).

Health: `GET /health` (process) and `GET /health/deep` (Postgres + Redis).

## Verify before pushing

```powershell
git config core.hooksPath scripts/hooks   # once per clone: the pre-push hook picks the gates below
.\scripts\verify-fast.ps1      # tooling + docker + Spotless + shared JVM + server tests
.\scripts\verify-full.ps1      # full local gate, incl. the minified release APK
.\scripts\verify-web.ps1       # Wasm compile + token checks
.\scripts\verify-desktop.ps1   # desktop shell tests
```

Emulator QA scripts live in `scripts/android-*-qa.ps1` (start with `android-e2e-qa.ps1`).

## Android release signing

`assembleRelease` builds unsigned unless a keystore is supplied, so the release variant stays
buildable (and gate-able) on every machine. To produce a Play-uploadable artifact, set these in
`local.properties`, `~/.gradle/gradle.properties`, or as `-P` flags, and never commit the keystore
(`*.jks` / `*.keystore` are gitignored):

```properties
pynapli.android.releaseKeystore=../pynapli-upload.jks   # path, resolved from the repo root
pynapli.android.releaseKeystorePassword=…
pynapli.android.releaseKeyAlias=…
pynapli.android.releaseKeyPassword=…
```

Version metadata is overridable the same way (the app reports `versionName` to the server):
`-Ppynapli.android.versionCode=42 -Ppynapli.android.versionName=1.0.0`. Staging APKs:
`scripts/android-staging-apk.ps1`.

## Load testing

```powershell
.\scripts\grid-api-load-test.ps1 -CandidateCount 5000 -ViewerCount 100 -ConcurrentUsers 100 -OnlineCandidateCount 1000
```

Tune the pool with `PYNAPLI_DATABASE_MAX_POOL_SIZE` / `_MIN_IDLE` / `_CONNECTION_TIMEOUT_MS`, and add
`-ServerProfile` with `PYNAPLI_API_PROFILING_ENABLED=true` for per-span server timings.

## Docs

The specs in [`docs/`](docs/) are canonical; start at the [docs index](docs/README.md). The API
contract is [docs/API_CONTRACT_SPEC.md](docs/API_CONTRACT_SPEC.md), and contributor rules for AI
assistants are in [`CLAUDE.md`](CLAUDE.md).
