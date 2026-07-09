# Cloud Status — Firebase & Google Cloud

Last updated: 2026-07-09

Plain-English snapshot of what is **actually live** vs **coded-but-not-provisioned** vs
**not started**, so the current state doesn't have to be reassembled from three specs.
Deeper detail: [DEVOPS_INFRA_SETUP_SPEC.md](DEVOPS_INFRA_SETUP_SPEC.md),
[../infra/cloud-run/README.md](../infra/cloud-run/README.md),
[STAGING_TESTER_LANE.md](STAGING_TESTER_LANE.md).

## Mental model (read this first)

> The backend is **code-complete and deploy-ready**. The **live cloud provisioning is only
> partially done.** "We set up Firebase and GCP" means the code, scripts, runbooks, and the
> Firebase project exist — **not** that the Ktor API is running in the cloud yet.

Product authority stays in **Ktor**, not Firebase (Firebase = FCM, Crashlytics, Analytics,
Remote Config, App Check, and — as of 2026-07-09 — Hosting for the website).

> ⚠️ Live GCP/Firebase resource state **cannot be verified from this repo** — the states below
> come from the spec checklists + on-disk evidence. Confirm anything marked "unconfirmed" in the
> [Firebase console](https://console.firebase.google.com/) / [GCP console](https://console.cloud.google.com/).

## Project identity (the facts you'll keep needing)

| Thing | Value |
| --- | --- |
| Firebase project id | `pynapli` |
| Firebase project number | `209241465883` |
| Firebase admin service account | `firebase-adminsdk-fbsvc@pynapli.iam.gserviceaccount.com` |
| Android package id | `com.pynapli.app` |
| Domain | `pynapli.com` (purchased, DNS access confirmed) |
| Backend service name | `pynapli-api` (Cloud Run), `pynapli-worker` (later) |
| Region (planned) | `us-central1` |
| FCM/Firebase auth | Application Default Credentials — **no JSON key** (org policy blocks key creation) |

**Open question — project naming:** the DevOps spec plans a `pynapli-dev` / `pynapli-staging` /
`pynapli-prod` split, but the real project in use is just **`pynapli`**. This is **unresolved**
(`infra/cloud-run/README.md` flags it). Don't assume the 3-project split exists.

## Status by component

| Component | State | Evidence / notes |
| --- | --- | --- |
| Firebase project `pynapli` | ✅ exists | referenced everywhere; project number `209241465883` |
| Firebase **Android app** registered | ✅ done | `androidApp/google-services.json` present (gitignored) |
| **FCM** (push) server pipeline | 🟡 code-complete, not enabled | needs `PYNAPLI_NOTIFICATION_PUSH_PROVIDER=firebase`, `PYNAPLI_NOTIFICATION_DISPATCH_ENABLED=true`, `PYNAPLI_FIREBASE_PROJECT_ID=pynapli` in a deployed env |
| **App Check** | 🟡 client code both build types; not enforced | founder must register app + set `PYNAPLI_APP_CHECK_ENFORCED=true` + `PYNAPLI_APP_CHECK_PROJECT_NUMBER=209241465883` |
| **Cloud Run** (Ktor API) | 🟡 image + scripts + workflow ready; **not deployed** | `infra/server.Dockerfile`, `scripts/gcp-cloud-run-deploy.ps1`, `.github/workflows/staging-backend.yml`; no deploy has been run |
| **Cloud SQL** (Postgres + PostGIS) | ⛔ unconfirmed | required before deploy; `pynapli_staging` DB must exist |
| **Memorystore** (Redis) | ⛔ unconfirmed | required; reached via VPC connector |
| **Artifact Registry** | ⛔ unconfirmed | repo `pynapli`; deploy script can create it |
| **Secret Manager** secrets | ⛔ unconfirmed | 6 `pynapli-staging-*` secrets listed in cloud-run README |
| **GCS media bucket** | ⛔ unconfirmed | `pynapli-staging-media`; media signing needs signer JSON secret |
| **Firebase Hosting** (website) | 🟡 config added, **never deployed** | `firebase.json` + `.firebaserc` (PR #154, branch `web/security-headers-csp`); no `.firebase/` cache yet |
| **CI** (build/test) | ✅ live | `.github/workflows/ci.yml` — Format + Verify + Docker Smoke |
| **Branch protection** on `main` | ⛔ not applied | command is in DEVOPS spec §Branch protection |
| **Billing / cost alerts** | ⛔ not set up | required before any prod resource |
| **Email aliases** (`support@`, etc.) | ⛔ not created | founder ops |

Legend: ✅ done · 🟡 code/config ready, action pending · ⛔ not done / unconfirmed

## Next commands (when you're ready to actually provision)

**Deploy the website** (safest first real deploy — static, cheap, isolated):
```bash
cd web && npm run build && cd ..
firebase login
firebase deploy --only hosting     # publishes web/dist to Firebase Hosting (project pynapli)
```
Then Firebase console → Hosting → add custom domain `pynapli.com`. See [../web/HOSTING.md](../web/HOSTING.md).

**Deploy the staging backend** (bigger — provisions real GCP resources; do staging before prod):
1. Resolve the project-naming question above (which project id staging uses).
2. Enable the Google Cloud APIs (`infra/cloud-run/README.md` §Required Google Cloud APIs).
3. Create Cloud SQL + Memorystore + the 6 Secret Manager secrets + GCS bucket.
4. Run the **Deploy Staging API** workflow (or `scripts/gcp-cloud-run-deploy.ps1`).
5. Follow [STAGING_TESTER_LANE.md](STAGING_TESTER_LANE.md) for the tester APK.

**Before any production resource:** billing alerts, backups, branch protection, rollback plan
(hard rule — `infra/cloud-run/README.md` §Current Rule).

## How to verify live state yourself

- Firebase: https://console.firebase.google.com/project/pynapli — check Hosting, App Check, Cloud Messaging.
- GCP: https://console.cloud.google.com/ (select project `pynapli`) — check Cloud Run, SQL, Memorystore, Secret Manager, Storage, Billing.
- Deployed API health: `GET https://<cloud-run-url>/health` and `/health/deep`.
