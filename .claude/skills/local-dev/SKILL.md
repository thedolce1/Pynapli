---
name: local-dev
description: Start Pynapli locally — Docker infra, Ktor server, dev seed data + dev login, health checks, web/desktop dev shells and hot reload. Use when running, seeding, or smoke-testing the app locally.
---

# Local dev environment

Local infra (Docker Desktop must be running):

```powershell
docker compose up -d                                  # pynapli-postgres (PostGIS) + pynapli-redis
docker compose ps
docker exec pynapli-redis redis-cli ping
docker exec pynapli-postgres pg_isready -U pynapli -d pynapli_dev
```

Run:

```powershell
.\gradlew.bat :server:run                             # Ktor server on :8080 (Flyway migrates at startup)
.\gradlew.bat :androidApp:assembleDebug               # Android debug APK
.\gradlew.bat :webApp:wasmJsBrowserDevelopmentRun     # web PWA dev server on :8081 (API stays on :8080)
.\gradlew.bat :desktopApp:run                         # desktop shell — same Compose UI, no emulator (dev-only)
.\gradlew.bat :desktopApp:hotRunDesktopAsync          # hot reload (needs a JetBrains Runtime)
.\gradlew.bat :desktopApp:reload                      # push edits into the running hot shell, keeping state
.\scripts\compose-metrics.ps1                         # Compose stability report (opt-in; -Android for shipping variant)
```

Seed (server must be running):

```powershell
.\scripts\seed-dev-data.ps1                              # dev@pynapli.local + ~8 nearby grid profiles
.\scripts\seed-dev-data.ps1 -SetAndroidEmulatorLocation  # also sets emulator GPS via adb
```

Dev login (shown on the auth screen in debug builds): `dev@pynapli.local` / `PynapliDevPass123!`.

Health: `GET http://localhost:8080/health` (process), `GET http://localhost:8080/health/deep` (Postgres + Redis).

Repeated dev logins trip the auth rate limit — `docker exec pynapli-redis redis-cli FLUSHDB` resets it (seeded data lives in Postgres).
