# Pynapli

Pynapli is a Kotlin Multiplatform + Compose + Ktor social lifestyle app.

## Initial Modules

- `shared`: cross-platform product logic, API clients, models, state, and local persistence.
- `composeApp`: Compose Multiplatform app shell and shared UI.
- `androidApp`: Android application entry point.
- `server`: Ktor backend authority.

## Local Prerequisites

- JDK 17
- Android Studio with Android SDK
- Docker Desktop
- Gradle wrapper, included in this repo
- Xcode on macOS for iOS builds

Run the local tooling check:

```powershell
.\scripts\check-local-tooling.ps1
```

Start local infrastructure:

```powershell
docker compose up -d
```

Check local infrastructure:

```powershell
docker compose ps
docker exec pynapli-redis redis-cli ping
docker exec pynapli-postgres pg_isready -U pynapli -d pynapli_dev
```

Run JVM/server checks once Gradle is available:

```powershell
.\gradlew.bat :shared:jvmTest :server:test
```

Run the first Android debug assemble:

```powershell
.\gradlew.bat :androidApp:assembleDebug
```

Run the Ktor server locally after Docker is running:

```powershell
.\gradlew.bat :server:run
```

For local Grid load testing, start the server first, then run:

```powershell
.\scripts\grid-api-load-test.ps1 -CandidateCount 5000 -ViewerCount 100 -ConcurrentUsers 100 -OnlineCandidateCount 1000
```

The server database pool can be tuned for load runs with:

```powershell
$env:PYNAPLI_DATABASE_MAX_POOL_SIZE='32'
$env:PYNAPLI_DATABASE_MIN_IDLE='8'
$env:PYNAPLI_DATABASE_CONNECTION_TIMEOUT_MS='5000'
.\gradlew.bat :server:run
```

For profiled Grid load runs, enable local timing headers before starting Ktor:

```powershell
$env:PYNAPLI_API_PROFILING_ENABLED='true'
$env:PYNAPLI_API_PROFILING_SLOW_SPAN_MS='250'
.\gradlew.bat :server:run
```

Then request and aggregate the server timings:

```powershell
.\scripts\grid-api-load-test.ps1 -CandidateCount 5000 -ViewerCount 100 -ConcurrentUsers 100 -OnlineCandidateCount 1000 -ServerProfile
```

Seed local development accounts after the server is running:

```powershell
.\scripts\seed-dev-data.ps1
```

The Android debug build shows a `Dev login` button on the auth screen. It signs in with:

```text
Email: dev@pynapli.local
Password: PynapliDevPass123!
```

The seed script creates that viewer account, updates its profile/current location, and creates nearby grid profiles so the first authenticated grid has cards to render.

Health endpoints:

```text
GET http://localhost:8080/health
GET http://localhost:8080/health/deep
```

`/health` verifies the app process. `/health/deep` verifies Postgres and Redis. Flyway migrations run at server startup.

Initial auth endpoints:

```text
POST http://localhost:8080/v1/auth/email/signup
POST http://localhost:8080/v1/auth/email/login
POST http://localhost:8080/v1/auth/refresh
POST http://localhost:8080/v1/auth/logout
GET  http://localhost:8080/v1/me
PATCH http://localhost:8080/v1/me/profile
GET  http://localhost:8080/v1/profiles/{userId}
POST http://localhost:8080/v1/location/current
POST http://localhost:8080/v1/presence/heartbeat
POST http://localhost:8080/v1/grid/query
GET  http://localhost:8080/v1/messages/threads
POST http://localhost:8080/v1/messages/threads
GET  http://localhost:8080/v1/messages/threads/{threadId}?before={cursor}&limit=50
POST http://localhost:8080/v1/messages/threads/{threadId}/read
POST http://localhost:8080/v1/messages/threads/{threadId}/messages
POST http://localhost:8080/v1/media/uploads
POST http://localhost:8080/v1/media/uploads/{assetId}/complete
```

Auth currently uses server-owned opaque access/refresh tokens, PBKDF2 password hashing, and hashed email/token storage in Postgres.

Signup creates a default profile and default discovery settings. `GET /v1/me` returns account, profile, and discovery settings.
Current location is stored server-side for discovery and does not echo exact coordinates back to clients.
Presence uses Redis TTL keys for online indicators. The first grid query uses server-owned current location and returns lightweight nearby profile cards.
Grid-origin direct messages create or reuse a one-to-one conversation, then append visible messages after server-side participation checks.
Thread summaries include unread counts derived from per-participant read markers.
Message payloads separate moderation `status` from delivery `deliveryStatus` (`sent`, `read`, or `received`).
The mobile composer validates blank and over-limit drafts locally before calling the message API.
The mobile app uses authenticated WebSocket convenience deltas for Messages; inbox summaries and an open conversation still refetch canonical HTTP state after each delta.
Conversation detail returns the newest message page first and uses opaque `nextCursor` tokens to load older history.
Media foundation tables model profile/private albums, owned storage assets, album items, access requests, and revocable per-user private album grants.
Media upload slots create pending owned assets and return a PUT contract that can later be backed by Firebase/GCS signed URLs.
The mobile grid header profile icon opens the Albums screen for managing profile photos and private album upload slots.

## Specs

The product and engineering specs live in [`docs/`](docs/) — see the [docs index](docs/README.md) for a grouped list of every spec. They are the canonical references during early buildout.
