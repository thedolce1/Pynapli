---
name: ship-slice
description: How a Pynapli production-readiness slice ships — branch/PR, which verify gate and emulator QA script to run, doc/audit updates, and the sub-agent "engineering team" model for large slices. Use when implementing, verifying, or merging a slice.
---

# Shipping a slice

One feature branch + PR per slice, CI-gated. Each slice:

1. Implement (server stays the product authority).
2. Targeted module tests.
3. `scripts/verify-fast.ps1` for backend/shared-only slices, `scripts/verify-full.ps1` when Android/Compose code is touched (plus `verify-web` / `verify-desktop` when `shared`/`composeApp`/`webApp`/`desktopApp` change — the pre-push hook selects these).
4. Emulator QA for UI slices: `scripts/android-*-qa.ps1` matching the surface (e.g. `android-grid-qa.ps1`, `android-swipe-qa.ps1`, `android-tonight-qa.ps1` for Playing Tonight).
5. Push → CI → merge.
6. Update `docs/IMPLEMENTATION_AUDIT.md` rows + relevant `*_SPEC.md` checklists.

Pause for review only at **phase boundaries**. Never run `gradlew` (incl. `--stop`) while a verify gate is running.

## Agent "engineering team" model

Large slices may be delegated to specialized sub-agents (the founder asked for a full-team workflow), each given the relevant spec + handoff context. The orchestrator reviews sub-agent work before anything merges; sub-agents never bypass the gates above.

- **Architect/Plan** (`Plan`) — implementation strategy and critical files before coding.
- **Backend** — Ktor domain services, JDBC/PostGIS, Flyway migrations, perf; server-authoritative rules only.
- **Frontend/Design** (`frontend-design` skill) — restyle Compose surfaces to the handoff language with token-only primitives.
- **Explore** — fan-out codebase searches for call sites / conventions.
- **Reviewer** (`/code-review`, `/security-review`) — correctness + security pass on each diff before merge.

All security/privacy/billing/consent/moderation rules remain server-validated regardless of which agent wrote the code (`docs/PRODUCTION_READINESS_AND_QUALITY_SPEC.md`).
