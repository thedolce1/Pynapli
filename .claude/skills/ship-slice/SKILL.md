---
name: ship-slice
description: How a Pynapli slice ships — gates, emulator QA, doc/audit updates. Use when implementing, verifying, or merging a slice.
---

# Shipping a slice

One feature branch + PR per slice, CI-gated. Each slice:

1. Implement (server stays the product authority).
2. Targeted module tests.
3. `scripts/verify-fast.ps1` for backend/shared-only slices, `scripts/verify-full.ps1` when Android/Compose code is touched (the pre-push hook adds `verify-web` / `verify-desktop` as needed).
4. Emulator QA for UI slices: `scripts/android-*-qa.ps1` matching the surface (e.g. `android-grid-qa.ps1`, `android-swipe-qa.ps1`, `android-tonight-qa.ps1` for Playing Tonight).
5. Push → CI → merge.
6. Edit the affected row(s) in `docs/IMPLEMENTATION_AUDIT.md` in place (status + open gaps, bump its date) and tick the relevant `*_SPEC.md` checklists. Don't append narrative there: the how goes in the commit/PR.

Pause for review only at **phase boundaries**. Sub-agents, if used, never bypass the gates above, and the orchestrator reviews their work before anything merges. Security, privacy, billing, consent and moderation rules stay server-validated whoever wrote the code.
