# Path to testers — the one ordered list

Last updated: 2026-07-29

**Why this file exists:** the steps were all written down, but spread across
`CLOUD_STATUS.md` (backend → PWA → site), `GCP_PROVISIONING_CHECKLIST.md` (the GCP detail),
`ANDROID_PRODUCTION_GAMEPLAN.md` H1–H3 (Android tail) and `IOS_BRINGUP_PLAN.md` (iOS). Nowhere said
**what order to do them in across all three targets, or what depends on what.** This does only that.
It deliberately does not restate the detail — each step links to the doc that owns it.

## The one thing that unlocks everything

> **Deploying the Ktor API is the single blocker for both Android testers and the PWA.**

Every client is server-authoritative and currently points at `localhost`. An APK handed to a tester
today reaches nothing, and the PWA has no API to call. So the backend deploy is not "step 3 of the
web track" — it is the gate for *all* tester access. Do it first, once.

## Track A — Backend (blocks everything else) 🔵 founder

| # | Step | Owner | Detail |
|---|---|---|---|
| A1 | Lock project naming (`pynapli` vs `-dev`/`-staging`/`-prod` split) | 🔵 | `CLOUD_STATUS.md` |
| A2 | Provision GCP: APIs, Cloud SQL (Postgres+PostGIS), Memorystore Redis, Artifact Registry, 6 Secret Manager secrets, GCS media bucket | 🔵 | `GCP_PROVISIONING_CHECKLIST.md` steps 1–7 |
| A3 | Deploy the API to Cloud Run, ideally at **`api.pynapli.com`** | 🔵 | `GCP_PROVISIONING_CHECKLIST.md` step 8 + `infra/cloud-run/README.md` |
| A4 | Cost controls / budget alerts — **do not skip** | 🔵 | `GCP_PROVISIONING_CHECKLIST.md` step 9 |
| A5 | Verify `/health` and `/health/deep` against the live URL | 🔵 | `CLOUD_STATUS.md` "How to verify live state" |

**A3 done = Android testers and the PWA both become possible.** Nothing below works before it.

## Track B — Android to testers 🔵 founder, 🟢 me where noted

Code is complete and gated; everything here is provisioning or a physical device.

| # | Step | Owner | Notes |
|---|---|---|---|
| B1 | Create a release signing keystore; set `pynapli.android.releaseKeystore*` in `local.properties` | 🔵 | Build is already `-P`-configurable and builds unsigned without it. **Back the keystore up — losing it means you can never update the app on Play.** |
| B2 | Build the staging APK pointed at the A3 URL | 🟢 | `scripts/build-staging-apk.ps1`; `-Ppynapli.android.apiBaseUrl=`/`websocketBaseUrl=` |
| B3 | Real-device QA on one physical mid-range phone (**H2**) | 🔵 | Emulator-only so far. Battery/location profile, push delivery, low-network. Harnesses exist. |
| B4 | Play Console: app entry, internal testing track, upload the signed build | 🔵 | `ANDROID_PRODUCTION_GAMEPLAN.md` H3 |
| B5 | Data Safety form + content rating + store listing copy/screenshots | 🔵 | H3; a dating/lifestyle app is scrutinised here — budget real time |

`google-services.json` is already present locally and correctly untracked, so FCM/Crashlytics/App
Check are wired for debug. Staging/prod variants need their own file per Firebase app if you split
projects at A1.

## Track C — PWA to testers 🔵 founder

| # | Step | Owner | Notes |
|---|---|---|---|
| C1 | Point the PWA at the API — set `window.__PYNAPLI_API_BASE__` in `index.html` (or leave `null` for the `api.pynapli.com` default) | 🟢 | One line, no Wasm rebuild |
| C2 | Build `webApp/build/dist/wasmJs/productionExecutable` and host at **`app.pynapli.com`** with an SPA rewrite (serve `index.html` for unknown paths) | 🔵 | The rewrite is required — deep links 404 without it |
| C3 | Confirm CORS: non-local defaults to `https://app.pynapli.com`; override with `PYNAPLI_CORS_ALLOWED_ORIGINS` only if the host differs | 🔵 | |
| C4 | Deploy the marketing site `pynapli.com` so its CTAs reach a live app | 🔵 | `web/HOSTING.md`; already in `firebase.json` |

**Founder decision on record (2026-07-09):** launch the funnel together, not piecemeal — a live site
whose "Create account" button works beats a brochure with dead CTAs.

## Track D — iOS to TestFlight 🍎 needs the Mac

Independent of A–C for *authoring*, but a TestFlight build still needs A3 to be usable.

| # | Step | Owner | Notes |
|---|---|---|---|
| D1 | Create the Xcode project + first framework link | 🟢 on Mac | Not hand-writable; see `IOS_BRINGUP_PLAN.md` step 1 — carries most of the remaining risk |
| D2 | Verify the Keychain session cipher on a simulator/device | 🟢 on Mac | **Security blocker:** tokens are plaintext until this is proven working |
| D3 | APNs + Firebase Apple SDK setup | 🟢 on Mac + 🔵 certs | Needs an Apple Developer account (🔵) |
| D4 | StoreKit 2 (can follow the first release — Store ships read-only, as on web) | 🟢 on Mac | |
| D5 | App Store Connect entry, TestFlight build, privacy nutrition labels | 🔵 | Apple Developer Program enrolment is 🔵 and takes time — **start it early** |

Plan estimate: **3–6 working days to a TestFlight build**, Store read-only.

## Not blocking testers, but before *public* launch

- Finalize real Privacy Policy / Terms copy (currently DRAFT + `noindex`).
- Decide the waitlist form's fate — wire it to an email service or remove it; it is currently a no-op.
- Reserve the email aliases (`support@`, `privacy@`, `security@`, `legal@`, `moderation@`, `no-reply@`).
- Branch protection on `main` (still off; see `project_ci_billing_block`).

## Legend

🔵 founder ops (accounts, cloud, money, devices, store consoles) · 🟢 codeable · 🍎 requires macOS
