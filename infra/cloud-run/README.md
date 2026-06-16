# Pynapli Cloud Run Deployment

This folder documents the first Google Cloud deployment path for the Ktor API. It follows
`docs/DEVOPS_INFRA_SETUP_SPEC.md`: local Docker Compose stays local, while Google Cloud uses
Artifact Registry, Cloud Run, Cloud SQL PostgreSQL/PostGIS, Memorystore Redis, Secret Manager, and
Cloud Storage.

## Current Rule

Do staging first. Do not create production resources until billing alerts, backups, branch
protection, and rollback procedures are in place.

The Firebase Android app currently references project number `209241465883`. Before creating hosted
resources, confirm whether the intended project id is `pynapli-staging`, `pynapli`, or the temporary
console project id shown in the browser (`project-1899348f-bca3-49fd-8aa`). The scripts require the
project id explicitly so this cannot happen by accident.

## Local Container Proof

Build the Ktor distribution and Docker image:

```powershell
.\scripts\docker-server-build.ps1 -ImageName pynapli-api:local
```

Run the image against local Docker Compose dependencies:

```powershell
.\scripts\docker-server-run.ps1 -ImageName pynapli-api:local -StartDependencies
```

Then verify:

```powershell
Invoke-WebRequest http://127.0.0.1:18080/health
Invoke-WebRequest http://127.0.0.1:18080/health/deep
```

## Required Google Cloud APIs

Enable these in the staging project:

```powershell
gcloud services enable run.googleapis.com artifactregistry.googleapis.com sqladmin.googleapis.com secretmanager.googleapis.com redis.googleapis.com vpcaccess.googleapis.com cloudresourcemanager.googleapis.com --project PROJECT_ID
```

## Artifact Registry

Build and push the API image:

```powershell
.\scripts\gcp-server-image-push.ps1 `
  -ProjectId PROJECT_ID `
  -Region us-central1 `
  -Repository pynapli `
  -ImageName pynapli-api `
  -CreateRepository
```

The pushed image will be:

```text
us-central1-docker.pkg.dev/PROJECT_ID/pynapli/pynapli-api:<git-short-sha>
```

## Cloud SQL

Use PostgreSQL with PostGIS support. The first Flyway migration runs:

```sql
CREATE EXTENSION IF NOT EXISTS postgis;
```

Create a database and user, then store the database password in Secret Manager. The server supports
Cloud SQL through the Java PostgreSQL socket factory:

```text
PYNAPLI_DATABASE_URL=jdbc:postgresql:///pynapli_staging
PYNAPLI_DATABASE_CLOUD_SQL_INSTANCE=PROJECT_ID:us-central1:pynapli-postgres
PYNAPLI_DATABASE_CLOUD_SQL_UNIX_SOCKET_PATH=/cloudsql/PROJECT_ID:us-central1:pynapli-postgres
PYNAPLI_DATABASE_CLOUD_SQL_REFRESH_STRATEGY=lazy
```

The Cloud Run service account needs the Cloud SQL Client role and Secret Manager access to the
database password secret.

## Required Runtime Secrets

Create these in Secret Manager before deploying staging/prod. Values must be strong and unique per
environment:

```text
pynapli-staging-db-password
pynapli-staging-media-signer-json
pynapli-staging-admin-api-token
pynapli-staging-discovery-cursor-secret
pynapli-staging-activity-cursor-secret
pynapli-staging-swipe-deck-token-secret
```

The media signer JSON is required by the current GCS V4 upload/download signing implementation. A
future improvement can replace this with IAMCredentials `signBlob`, but the current code correctly
fails fast unless signing credentials are present.

## Memorystore Redis

For staging, create Memorystore in the same region and VPC. Cloud Run reaches Memorystore through a
Serverless VPC Access connector or Direct VPC egress. Set:

```text
PYNAPLI_REDIS_URL=redis://MEMORYSTORE_PRIVATE_IP:6379
```

## Deploy Staging

Example staging deploy:

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

The script uses `--allow-unauthenticated` by default because the mobile app must reach the API over
HTTPS. Pynapli still enforces app-level auth, object authorization, rate limits, and App Check on
state-changing routes. Use `-RequireIam` only for internal/admin-only Cloud Run services.

## Official References

- Cloud Run container deployment: https://docs.cloud.google.com/run/docs/deploying
- Artifact Registry Docker images: https://docs.cloud.google.com/artifact-registry/docs/docker/store-docker-container-images
- Cloud Run to Cloud SQL PostgreSQL: https://docs.cloud.google.com/sql/docs/postgres/connect-run
- Cloud SQL Java PostgreSQL connector: https://github.com/GoogleCloudPlatform/cloud-sql-jdbc-socket-factory/blob/main/docs/jdbc.md
- Cloud Run to Memorystore Redis: https://docs.cloud.google.com/memorystore/docs/redis/connect-redis-instance-cloud-run
