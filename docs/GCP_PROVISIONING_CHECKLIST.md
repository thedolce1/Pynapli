# GCP Provisioning Checklist

A step-by-step runbook to stand up the Ktor backend on Google Cloud, then the PWA and site —
the founder-ops half of [CLOUD_STATUS.md](CLOUD_STATUS.md) §Go-live (steps 2–6). Authoritative
command details and the deploy scripts live in [../infra/cloud-run/README.md](../infra/cloud-run/README.md);
this is the ordered "what to do, in what order, and why" wrapper.

**Conventions used below:** replace `PROJECT_ID` with your chosen project (see Step 0). Region is
`us-central1` throughout. Run `gcloud` from anywhere after `gcloud auth login`; run the `.ps1`
scripts from the repo root. Machine tiers/sizes below are sensible small defaults — confirm against
the official docs linked in the cloud-run README before committing to cost.

> ⚠️ These steps create **billable** cloud resources. Do Step 9 (billing alerts) early, not last.
> Do everything as **staging first**; don't point real users at anything until the whole chain works.

---

## Step 0 — Decisions to lock first (only you can)

- [ ] **Project naming.** The spec plans `pynapli-dev` / `-staging` / `-prod`; your existing Firebase
  project (mobile FCM/App Check, `google-services.json`) is just **`pynapli`** (number `209241465883`).
  - **Recommended for now:** use the existing **`pynapli`** project to get the full stack live fastest —
    it already holds your Firebase mobile registration and App Check. Treat this first deploy as staging.
  - **Before real users:** create a separate **`pynapli-prod`** project (its own Firebase app +
    `google-services.json`) so a bad deploy or load test can never touch production data. The one
    isolation that truly matters is prod ≠ everything-else.
- [ ] **Billing account** attached to the project (founder-owned, recovery enabled).
- [ ] **Confirm the API hostname** you want: the PWA defaults to `https://api.pynapli.com`. Keep it, or
  pick another and set `window.__PYNAPLI_API_BASE__` in `webApp/.../index.html` at deploy.

## Step 1 — Project, billing, APIs

- [ ] Select the project: `gcloud config set project PROJECT_ID`
- [ ] Confirm billing is linked (Console → Billing).
- [ ] Enable the APIs (one command):
  ```bash
  gcloud services enable run.googleapis.com artifactregistry.googleapis.com sqladmin.googleapis.com \
    secretmanager.googleapis.com redis.googleapis.com vpcaccess.googleapis.com \
    cloudresourcemanager.googleapis.com --project PROJECT_ID
  ```

## Step 2 — Build & push the API image (Artifact Registry)

- [ ] From the repo root (creates the `pynapli` repo on first run):
  ```powershell
  .\scripts\gcp-server-image-push.ps1 -ProjectId PROJECT_ID -Region us-central1 `
    -Repository pynapli -ImageName pynapli-api -CreateRepository
  ```
  Produces `us-central1-docker.pkg.dev/PROJECT_ID/pynapli/pynapli-api:<git-sha>`.

## Step 3 — Cloud SQL (Postgres + PostGIS)

- [ ] Create the instance (PostGIS ships with Cloud SQL Postgres; the first Flyway migration runs
  `CREATE EXTENSION postgis`):
  ```bash
  gcloud sql instances create pynapli-postgres --database-version=POSTGRES_16 \
    --region=us-central1 --tier=db-custom-1-3840 --storage-size=10GB --project PROJECT_ID
  ```
- [ ] Create the database and app user (use a strong password — save it for Step 6):
  ```bash
  gcloud sql databases create pynapli_staging --instance=pynapli-postgres --project PROJECT_ID
  gcloud sql users create pynapli --instance=pynapli-postgres --password='STRONG_DB_PASSWORD' --project PROJECT_ID
  ```

## Step 4 — Memorystore Redis + VPC connector

- [ ] Serverless VPC Access connector (lets Cloud Run reach private Redis):
  ```bash
  gcloud compute networks vpc-access connectors create pynapli-staging-connector \
    --region=us-central1 --range=10.8.0.0/28 --project PROJECT_ID
  ```
- [ ] Redis instance, then note its **private IP** for Step 8:
  ```bash
  gcloud redis instances create pynapli-redis --size=1 --region=us-central1 \
    --redis-version=redis_7_0 --project PROJECT_ID
  gcloud redis instances describe pynapli-redis --region=us-central1 --project PROJECT_ID --format='value(host)'
  ```

## Step 5 — Cloud Storage media bucket

- [ ] ```bash
  gcloud storage buckets create gs://pynapli-staging-media --location=us-central1 \
    --uniform-bucket-level-access --project PROJECT_ID
  ```

## Step 6 — Secret Manager (6 secrets)

Generate strong unique values (`openssl rand -base64 32`) and store each. The media signer JSON is a
service-account key with signing rights for the bucket (the current GCS V4 signing impl requires it).

- [ ] ```bash
  printf 'STRONG_DB_PASSWORD'      | gcloud secrets create pynapli-staging-db-password            --data-file=- --project PROJECT_ID
  gcloud secrets create pynapli-staging-media-signer-json --data-file=path/to/signer.json --project PROJECT_ID
  openssl rand -base64 32 | gcloud secrets create pynapli-staging-admin-api-token          --data-file=- --project PROJECT_ID
  openssl rand -base64 32 | gcloud secrets create pynapli-staging-discovery-cursor-secret  --data-file=- --project PROJECT_ID
  openssl rand -base64 32 | gcloud secrets create pynapli-staging-activity-cursor-secret   --data-file=- --project PROJECT_ID
  openssl rand -base64 32 | gcloud secrets create pynapli-staging-swipe-deck-token-secret  --data-file=- --project PROJECT_ID
  ```

## Step 7 — Cloud Run service account + IAM

- [ ] Create the runtime SA:
  ```bash
  gcloud iam service-accounts create pynapli-api --project PROJECT_ID
  ```
- [ ] Grant it (repeat `add-iam-policy-binding` per role):
  `roles/cloudsql.client`, `roles/secretmanager.secretAccessor`, `roles/storage.objectAdmin`
  (scope to the media bucket), and FCM send permission (`roles/firebasecloudmessaging.admin`) so ADC
  covers push. *(No JSON key — org policy blocks key creation; ADC comes from the attached SA.)*

## Step 8 — Deploy the API to Cloud Run

- [ ] Fill the placeholders (Redis IP from Step 4) and run:
  ```powershell
  .\scripts\gcp-cloud-run-deploy.ps1 -ProjectId PROJECT_ID -Region us-central1 `
    -ServiceName pynapli-api -Repository pynapli -ImageName pynapli-api -Environment staging `
    -FirebaseProjectId pynapli -AppCheckProjectNumber 209241465883 `
    -CloudSqlInstance PROJECT_ID:us-central1:pynapli-postgres -DatabaseName pynapli_staging -DatabaseUser pynapli `
    -DatabasePasswordSecret pynapli-staging-db-password `
    -MediaStorageBucket pynapli-staging-media -MediaGcsSigningServiceAccountJsonSecret pynapli-staging-media-signer-json `
    -AdminApiTokenSecret pynapli-staging-admin-api-token `
    -DiscoveryCursorSecretName pynapli-staging-discovery-cursor-secret `
    -ActivityCursorSecretName pynapli-staging-activity-cursor-secret `
    -SwipeDeckTokenSecretName pynapli-staging-swipe-deck-token-secret `
    -RedisUrl redis://REDIS_PRIVATE_IP:6379 -VpcConnector pynapli-staging-connector `
    -ServiceAccount pynapli-api@PROJECT_ID.iam.gserviceaccount.com
  ```
- [ ] Verify: hit the Cloud Run URL `/health` and `/health/deep` (both must be `200`). Flyway migrations
  run at startup — check the logs show them applied.
- [ ] CORS: the server now auto-allows `https://app.pynapli.com`; set `PYNAPLI_CORS_ALLOWED_ORIGINS`
  only to add origins (e.g. a Firebase preview URL).

## Step 9 — Cost controls (do NOT skip)

- [ ] Console → Billing → Budgets & alerts: a monthly budget + 50/90/100% email alerts.
- [ ] Confirm Cloud SQL isn't oversized and log retention is capped.

## Step 10 — Domain, PWA, site (the go-live tail)

- [ ] Map **`api.pynapli.com`** → the Cloud Run service (Console → Cloud Run → Manage custom domains,
  or a load balancer). TLS is provisioned for you.
- [ ] PWA: leave `window.__PYNAPLI_API_BASE__ = null` (uses `api.pynapli.com`) or set your API URL;
  build `webApp/build/dist/wasmJs/productionExecutable`; deploy to **`app.pynapli.com`** (Firebase
  Hosting second site; SPA rewrite → `index.html`).
- [ ] Site: `firebase deploy --only hosting` for **`pynapli.com`** (`web/dist`; see
  [../web/HOSTING.md](../web/HOSTING.md)). Its CTAs now reach a working app.

## Step 11 — Turn on the safety/comms features

- [ ] **FCM:** set `PYNAPLI_NOTIFICATION_PUSH_PROVIDER=firebase`, `PYNAPLI_NOTIFICATION_DISPATCH_ENABLED=true`,
  `PYNAPLI_FIREBASE_PROJECT_ID=pynapli`. Verify a `SENT` count in `GET /v1/admin/metrics/notifications`.
- [ ] **App Check:** register the app in Firebase, allowlist debug tokens for QA, then set
  `PYNAPLI_APP_CHECK_ENFORCED=true` + `PYNAPLI_APP_CHECK_PROJECT_NUMBER=209241465883`. Validate on
  staging before prod.

---

## Before you flip to real users (production)

Per [../infra/cloud-run/README.md](../infra/cloud-run/README.md) §Current Rule and DEVOPS spec: a
separate prod project/DB, automated backups, rollback procedure, branch protection, billing alerts,
finalized Privacy/Terms (currently DRAFT), and the GeoNames attribution surfaced in-app
([THIRD_PARTY_DATA.md](THIRD_PARTY_DATA.md)) must all be in place first.
