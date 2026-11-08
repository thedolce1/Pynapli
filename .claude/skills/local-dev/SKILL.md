---
name: local-dev
description: Run Pynapli locally — infra, server, seed data, dev login, web/desktop dev shells. Use when running or smoke-testing the app locally.
---

# Local dev environment

Docker infra, server start, seed script, dev login and health checks: `README.md` ("Local Prerequisites" onward). Dev login: `dev@pynapli.local` / `PynapliDevPass123!` (after `.\scripts\seed-dev-data.ps1`; add `-SetAndroidEmulatorLocation` to set emulator GPS).

Dev shells not in the README:

```powershell
.\gradlew.bat :webApp:wasmJsBrowserDevelopmentRun     # web PWA dev server on :8081 (API stays on :8080)
.\gradlew.bat :desktopApp:run                         # desktop shell — same Compose UI, no emulator (dev-only)
.\gradlew.bat :desktopApp:hotRunDesktopAsync          # hot reload (needs a JetBrains Runtime)
.\gradlew.bat :desktopApp:reload                      # push edits into the running hot shell, keeping state
.\scripts\compose-metrics.ps1                         # Compose stability report (opt-in; -Android for shipping variant)
```

Repeated dev logins trip the auth rate limit — `docker exec pynapli-redis redis-cli FLUSHDB` resets it (seeded data lives in Postgres).
