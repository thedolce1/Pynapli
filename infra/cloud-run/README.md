# Pynapli Cloud Run Deployment

How to stand up the Ktor API on Google Cloud: Artifact Registry, Cloud Run, Cloud SQL
PostgreSQL/PostGIS, Memorystore Redis, Secret Manager and Cloud Storage. Local Docker Compose stays
local. Includes the former GCP provisioning checklist (merged 2026-11-17). The ordered
founder path across all targets is [docs/PATH_TO_TESTERS.md](../../docs/PATH_TO_TESTERS.md); infra
rules are in [docs/DEVOPS_INFRA_SETUP_SPEC.md](../../docs/DEVOPS_INFRA_SETUP_SPEC.md).

## Rules

- **Staging first.** Do not create production resources until billing alerts, backups, branch
  protection and a rollback procedure are in place.
- These steps create **billable** resources. Set budget alerts (step 9) early, not last.
- The scripts require the project id explicitly, so a wrong project can't be targeted by accident.
  Replace `PROJECT_ID` below with the chosen project. Region is `us-central1` throughout. Run `gcloud`
  after `gcloud auth login`; run `.ps1` scripts from the repo root.
- Machine sizes below are small defaults; check cost against the official docs before committing.

## Step 0: decisions only the founder can make

- [ ] **Project naming.** The existing Firebase project is `pynapli` (number `209241465883`; mobile FCM
  and App Check). Recommended: use `pynapli` as staging now; create a separate `pynapli-prod` (own
  Firebase app + `google-services.json`) before real users. The isolation that matters is prod ≠
  everything else. (A temporary console project id, `project-1899348f-bca3-49fd-8aa`, also exists;
  don't deploy to it by mistake.)
- [ ] **Billing account** attached (founder-owned, recovery enabled).
- [ ] **API hostname.** The PWA defaults to `https://api.pynapli.com`; to use another, set
  `window.__PYNAPLI_API_BASE__` in `webApp/src/wasmJsMain/resources/index.html` at deploy.

## Local container proof

```powershell
.\scripts\docker-server-build.ps1 -ImageName pynapli-api:local
.\scripts\docker-server-run.ps1 -ImageName pynapli-api:local -StartDependencies
Invoke-WebRequest http://127.0.0.1:18080/health
Invoke-WebRequest http://127.0.0.1:18080/health/deep
```

## Step 1: project, billing, APIs

```bash
gcloud config set project PROJECT_ID
gcloud services enable run.googleapis.com artifactregistry.googleapis.com sqladmin.googleapis.com \
  secretmanager.googleapis.com redis.googleapis.com vpcaccess.googleapis.com \
  cloudresourcemanager.googleapis.com --project PROJECT_ID
```

Confirm billing is linked (Console → Billing).

## Step 2: build and push the image (Artifact Registry)

```powershell
.\scripts\gcp-server-image-push.ps1 `
  -ProjectId PROJECT_ID `
  -Region us-central1 `
  -Repository pynapli `
  -ImageName pynapli-api `
  -CreateRepository
```

Produces `us-central1-docker.pkg.dev/PROJECT_ID/pynapli/pynapli-api:<git-short-sha>`.

## Step 3: Cloud SQL (Postgres + PostGIS)

The first Flyway migration runs `CREATE EXTENSION IF NOT EXISTS postgis;`.

```bash
gcloud sql instances create pynapli-postgres --database-version=POSTGRES_16 \
  --region=us-central1 --tier=db-custom-1-3840 --storage-size=10GB --project PROJECT_ID
gcloud sql databases create pynapli_staging --instance=pynapli-postgres --project PROJECT_ID
gcloud sql users create pynapli --instance=pynapli-postgres --password='STRONG_DB_PASSWORD' --project PROJECT_ID
```

The server reaches Cloud SQL through the Java PostgreSQL socket factory:

```text
PYNAPLI_DATABASE_URL=jdbc:postgresql:///pynapli_staging
PYNAPLI_DATABASE_CLOUD_SQL_INSTANCE=PROJECT_ID:us-central1:pynapli-postgres
PYNAPLI_DATABASE_CLOUD_SQL_UNIX_SOCKET_PATH=/cloudsql/PROJECT_ID:us-central1:pynapli-postgres
PYNAPLI_DATABASE_CLOUD_SQL_REFRESH_STRATEGY=lazy
```

## Step 4: Memorystore Redis + VPC connector

Cloud Run reaches Memorystore through a Serverless VPC Access connector (or Direct VPC egress) in the
same region and VPC.

```bash
gcloud compute networks vpc-access connectors create pynapli-staging-connector \
  --region=us-central1 --range=10.8.0.0/28 --project PROJECT_ID
gcloud redis instances create pynapli-redis --size=1 --region=us-central1 \
  --redis-version=redis_7_0 --project PROJECT_ID
gcloud redis instances describe pynapli-redis --region=us-central1 --project PROJECT_ID --format='value(host)'
```

Then `PYNAPLI_REDIS_URL=redis://MEMORYSTORE_PRIVATE_IP:6379`.

## Step 5: Cloud Storage media bucket

```bash
gcloud storage buckets create gs://pynapli-staging-media --location=us-central1 \
  --uniform-bucket-level-access --project PROJECT_ID
```

## Step 6: Secret Manager (6 secrets)

Values must be strong and unique per environment (`openssl rand -base64 32`). The media signer JSON
is a service-account key with signing rights for the bucket: the current GCS V4 signing code needs it
and fails fast without it (a later change could use IAMCredentials `signBlob` instead).

```bash
printf 'STRONG_DB_PASSWORD' | gcloud secrets create pynapli-staging-db-password            --data-file=- --project PROJECT_ID
gcloud secrets create pynapli-staging-media-signer-json --data-file=path/to/signer.json    --project PROJECT_ID
openssl rand -base64 32 | gcloud secrets create pynapli-staging-admin-api-token          --data-file=- --project PROJECT_ID
openssl rand -base64 32 | gcloud secrets create pynapli-staging-discovery-cursor-secret  --data-file=- --project PROJECT_ID
openssl rand -base64 32 | gcloud secrets create pynapli-staging-activity-cursor-secret   --data-file=- --project PROJECT_ID
openssl rand -base64 32 | gcloud secrets create pynapli-staging-swipe-deck-token-secret  --data-file=- --project PROJECT_ID
```

## Step 7: Cloud Run service account + IAM

```bash
gcloud iam service-accounts create pynapli-api --project PROJECT_ID
```

Grant (one `add-iam-policy-binding` per role): `roles/cloudsql.client`,
`roles/secretmanager.secretAccessor`, `roles/storage.objectAdmin` (scoped to the media bucket), and
`roles/firebasecloudmessaging.admin` so ADC covers push. No JSON key: the org policy blocks key
creation, and ADC comes from the attached service account.

## Step 8: deploy the API

```powershell
.\scripts\gcp-cloud-run-deploy.ps1 `
  -ProjectId PROJECT_ID `
  -Region us-central1 `
  -ServiceName pynapli-api `
  -Repository pynapli `
  -ImageName pynapli-api `
  -Environment staging `
  -FirebaseProjectId pynapli `
  -AppCheckProjectNumber 209241465883 `
  -CloudSqlInstance PROJECT_ID:us-central1:pynapli-postgres `
  -DatabaseName pynapli_staging `
  -DatabaseUser pynapli `
  -DatabasePasswordSecret pynapli-staging-db-password `
  -MediaStorageBucket pynapli-staging-media `
  -MediaGcsSigningServiceAccountJsonSecret pynapli-staging-media-signer-json `
  -AdminApiTokenSecret pynapli-staging-admin-api-token `
  -DiscoveryCursorSecretName pynapli-staging-discovery-cursor-secret `
  -ActivityCursorSecretName pynapli-staging-activity-cursor-secret `
  -SwipeDeckTokenSecretName pynapli-staging-swipe-deck-token-secret `
  -RedisUrl redis://MEMORYSTORE_PRIVATE_IP:6379 `
  -VpcConnector pynapli-staging-connector `
  -ServiceAccount pynapli-api@PROJECT_ID.iam.gserviceaccount.com
```

Or run the **Deploy Staging API** GitHub workflow.

- The script deploys with `--allow-unauthenticated` because the apps must reach the API over HTTPS;
  Pynapli still enforces app-level auth, object authorization, rate limits and App Check on
  state-changing routes. Use `-RequireIam` only for internal/admin-only services.
- Verify `/health` and `/health/deep` both return `200`, and that the logs show Flyway migrations
  applied at startup.
- **CORS:** non-local environments allow `https://app.pynapli.com` by default.
  `PYNAPLI_CORS_ALLOWED_ORIGINS` **replaces** the default, so include that origin when you set it.
  Startup rejects any entry that is not `scheme://host[:port]`, and in staging/prod any entry that is
  not `https`, contains a wildcard, or is a loopback host. An empty list is allowed (mobile-only).

### Config pre-flight (read before the first deploy)

The server **refuses to start** on unsafe staging/prod config and names the problem (issue codes such
as `ADMIN_API_TOKEN_REQUIRED`, `GCS_MEDIA_STORAGE_REQUIRED`, `APP_CHECK_ENFORCEMENT_REQUIRED`). Every
variable it reads is in `.env.example`, which is the source of truth.

Silent defaults to set on purpose:

- [ ] `PYNAPLI_ACTIVITY_RETENTION_ENABLED` defaults ON for local and OFF elsewhere. Unset on
  staging/prod, the activity table grows without bound. Set `true`.
- [ ] `PYNAPLI_MEDIA_MODERATION_PROVIDER` defaults to `manual` (no automated image scanning). For a
  public launch, choose deliberately; `cloud_vision` (plus signing credentials) enables scanning.
- [ ] `PYNAPLI_DATABASE_MAX_POOL_SIZE` / `PYNAPLI_DATABASE_MIN_IDLE`: instance count multiplies
  connections against the Cloud SQL limit.

Rejected outright in production: `PYNAPLI_DEV_STORE_PURCHASES_ENABLED`,
`PYNAPLI_PLAY_STORE_SANDBOX_ENABLED`, `PYNAPLI_MEDIA_DEV_AUTO_APPROVE_UPLOADS`,
`PYNAPLI_DEV_SIGNUP_SHORTCUTS_ENABLED`.

Email: with no email provider wired, nobody can create an account (sign-up answers
`503 SIGNUP_UNAVAILABLE`) or reset a password. See docs/PATH_TO_TESTERS.md step A6.

## Step 9: cost controls (do not skip)

- [ ] Console → Billing → Budgets & alerts: monthly budget with 50/90/100% email alerts.
- [ ] Cloud SQL not oversized; log retention capped.

## Step 10: domain, PWA, site

- [ ] Map **`api.pynapli.com`** to the Cloud Run service (Cloud Run → Manage custom domains, or a load
  balancer). TLS is provisioned for you.
- [ ] PWA: leave `window.__PYNAPLI_API_BASE__ = null` (uses `api.pynapli.com`) or set the API URL;
  build `webApp/build/dist/wasmJs/productionExecutable`; deploy to **`app.pynapli.com`** (a second
  Firebase Hosting site, SPA rewrite to `index.html`).
- [ ] Site: `firebase deploy --only hosting` for **`pynapli.com`** (`web/dist`; see
  [web/HOSTING.md](../../web/HOSTING.md)).

## Step 11: push and App Check

- [ ] **FCM:** `PYNAPLI_NOTIFICATION_PUSH_PROVIDER=firebase`, `PYNAPLI_NOTIFICATION_DISPATCH_ENABLED=true`,
  `PYNAPLI_FIREBASE_PROJECT_ID=pynapli`. Verify a `SENT` count in
  `GET /v1/admin/metrics/notifications` (admin-token gated).
- [ ] **App Check:** register the app in Firebase, allowlist debug tokens for QA, then set
  `PYNAPLI_APP_CHECK_ENFORCED=true` and `PYNAPLI_APP_CHECK_PROJECT_NUMBER=209241465883`. Validate on
  staging before prod.

## Before real users (production)

A separate prod project and DB, automated backups, a rollback procedure, branch protection, billing
alerts, final Privacy/Terms (currently DRAFT), and GeoNames attribution in-app
([docs/THIRD_PARTY_DATA.md](../../docs/THIRD_PARTY_DATA.md)).

## Official references

- Cloud Run container deployment: https://docs.cloud.google.com/run/docs/deploying
- Artifact Registry Docker images: https://docs.cloud.google.com/artifact-registry/docs/docker/store-docker-container-images
- Cloud Run to Cloud SQL PostgreSQL: https://docs.cloud.google.com/sql/docs/postgres/connect-run
- Cloud SQL Java PostgreSQL connector: https://github.com/GoogleCloudPlatform/cloud-sql-jdbc-socket-factory/blob/main/docs/jdbc.md
- Cloud Run to Memorystore Redis: https://docs.cloud.google.com/memorystore/docs/redis/connect-redis-instance-cloud-run
