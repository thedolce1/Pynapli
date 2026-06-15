# Pynapli DevOps And Infrastructure Setup Spec

Last updated: 2026-04-24

## Purpose

This document defines what should be set up before and during implementation: GitHub, local tooling, CI/CD, Docker, Firebase, Google Cloud, secrets, environments, and ownership.

Core rule:

> Do not create irreversible cloud complexity before the app skeleton exists. Do create the accounts, naming plan, and security boundaries early.

## What We Need From Founder

Founder decisions locked:

- Domain name: `pynapli.com`
- Google Cloud billing owner: Sebastien Mayeras
- Apple Developer account owner: Sebastien Mayeras / founder-controlled business account
- Google Play Console owner: Sebastien Mayeras / founder-controlled business account

Engineering naming decisions locked:

- GitHub org name: `pynapli`
- Main repository name: `pynapli`
- Firebase/GCP project names:
  - `pynapli-dev`
  - `pynapli-staging`
  - `pynapli-prod`
- Primary package ID: `com.pynapli.app`
- Backend service name: `pynapli-api`
- Worker service name: `pynapli-worker`

Founder should create email aliases:

- `support@pynapli.com`
- `privacy@pynapli.com`
- `security@pynapli.com`
- `legal@pynapli.com`
- `moderation@pynapli.com`
- `admin@pynapli.com`
- `no-reply@pynapli.com`

Do not share secrets in chat.

## Recommended Naming

Repository:

```text
pynapli
```

GitHub organization:

```text
pynapli
```

Branches:

```text
main
develop
codex/*
```

GCP/Firebase projects:

```text
pynapli-dev
pynapli-staging
pynapli-prod
```

Package IDs:

```text
com.pynapli.app
```

Cloud Run services:

```text
pynapli-api
pynapli-worker
```

Domains/emails:

```text
pynapli.com
support@pynapli.com
privacy@pynapli.com
security@pynapli.com
legal@pynapli.com
moderation@pynapli.com
admin@pynapli.com
no-reply@pynapli.com
```

## Setup Timing

### Do Now

Useful to set up now:

- GitHub repo
- GitHub branch protections
- Domain purchase
- Email aliases
- Apple Developer account
- Google Play Console account
- Google Cloud billing account
- Firebase/GCP project naming reserved
- Local Docker Desktop
- GitHub CLI
- Java/Kotlin tooling

### Do When Code Skeleton Exists

Set up after the repo has real Gradle/Ktor modules:

- CI workflows
- Docker Compose
- Local Postgres/PostGIS
- Local Redis
- Ktor health checks
- Testcontainers
- Firebase app registration
- FCM token plumbing
- Crashlytics/Analytics integration

### Do When Backend Deploys

Set up when staging backend is ready:

- Cloud Run or equivalent
- Cloud SQL PostgreSQL + PostGIS
- Memorystore Redis
- Artifact Registry
- Secret Manager
- Cloud Build/GitHub Actions deploy
- Staging domain
- Monitoring/alerts

### Do Before Beta

Required before real users:

- Production project
- Backups
- Moderation/admin console
- Support inbox
- Privacy policy
- Terms
- Community guidelines URL
- Incident response contact
- FCM production config
- Crash reporting
- Billing test environment
- Age gate
- Report/block tested

## Local Development

Required tools:

- Git
- GitHub CLI
- JDK 21 or repo-selected LTS
- Android Studio
- Xcode on Mac for iOS builds
- Docker Desktop
- Postgres client
- Firebase CLI
- Google Cloud CLI

The repo-selected toolchain and dependency baseline are defined in [PROJECT_STRUCTURE_AND_DEPENDENCIES_SPEC.md](PROJECT_STRUCTURE_AND_DEPENDENCIES_SPEC.md).

Local services:

```text
PostgreSQL + PostGIS
Redis
Ktor server
Optional local object storage emulator
```

Recommended local command later:

```powershell
docker compose up -d
```

## GitHub Setup

Repository settings:

- Private during build
- Branch protection on `main`
- Require pull request before merge
- Require status checks
- Require signed commits if desired
- Enable Dependabot
- Enable secret scanning
- Enable code scanning

Suggested labels:

- `area:grid`
- `area:swipe`
- `area:messages`
- `area:backend`
- `area:mobile`
- `area:safety`
- `area:monetization`
- `type:bug`
- `type:feature`
- `type:infra`
- `priority:p0`
- `priority:p1`
- `priority:p2`

## CI/CD

Initial GitHub Actions:

- Build shared Kotlin
- Build Ktor server
- Run unit tests
- Run Spotless/ktlint format check (detekt deferred — see `OFFICIAL_TECH_REFERENCE_GUIDE.md`)
- Run backend integration tests
- Build Android debug

Later:

- iOS build
- Maestro E2E
- k6 load tests
- Docker image build
- Deploy staging
- Database migration dry run

### Current state (2026-05-31)

`.github/workflows/ci.yml` runs two jobs on every push to `main`/`develop` and on every PR:

- **Format (Spotless)** — `./gradlew spotlessCheck`. Uses `fetch-depth: 0` + an explicit
  `git fetch origin main` so the Spotless `ratchetFrom = "origin/main"` ratchet can diff changed
  files. (ktlint via Spotless; detekt deferred — see `OFFICIAL_TECH_REFERENCE_GUIDE.md`.)
- **Verify (tests + assemble)** — `:shared:jvmTest :shared:testAndroidHostTest :server:test
  :composeApp:testAndroidHostTest :composeApp:desktopTest :androidApp:assembleDebug`. Server
  integration tests self-manage Postgres/Redis via Testcontainers (Docker is present on
  `ubuntu-latest`), so no GitHub service containers are needed. Uploads test reports (always) and
  the debug APK (on success) as artifacts.

### Branch protection (apply once, founder action)

Not yet applied. Run these with an admin token to require both checks and a passing PR before merge
to `main` (job names are the status-check contexts):

```bash
gh api -X PUT repos/thedolce1/Pynapli/branches/main/protection \
  -H "Accept: application/vnd.github+json" \
  -f 'required_status_checks[strict]=true' \
  -f 'required_status_checks[contexts][]=Format (Spotless)' \
  -f 'required_status_checks[contexts][]=Verify (tests + assemble)' \
  -f 'enforce_admins=true' \
  -f 'required_pull_request_reviews[required_approving_review_count]=1' \
  -f 'restrictions=' \
  -f 'allow_force_pushes=false' \
  -f 'allow_deletions=false'
```

After enabling, direct pushes to `main` are blocked; merge via PR once both checks are green.

## Docker

Initial `docker-compose.yml` should include:

- `postgres` with PostGIS
- `redis`
- optional `minio`

Do not containerize mobile builds early unless CI needs it.

## Firebase Setup

Use Firebase for:

- FCM
- Crashlytics
- Analytics
- Remote Config
- App Check
- Optional Auth

Projects:

- dev
- staging
- prod

Rules:

- Do not put authoritative profile/discovery/chat data in Firestore by default.
- Do not send sensitive lifestyle labels to Analytics.
- Do not include private album URLs in Crashlytics.
- Ktor decides notifications; FCM delivers.

### FCM backend enablement (production)

The server notification pipeline is **code-complete**: device registration (`POST /v1/notifications/devices`) → outbox enqueue on events (message, match, flirt, album request, partner/psync/tonight invites) → gated dispatch worker → FCM send, with quiet hours, per-category preferences, dead-token pruning, and privacy-safe delivery metrics. To turn it on in a deployed environment, set:

| Env var | Value | Why |
| --- | --- | --- |
| `PYNAPLI_NOTIFICATION_PUSH_PROVIDER` | `firebase` | Selects the FCM sender (default `local` only logs). |
| `PYNAPLI_NOTIFICATION_DISPATCH_ENABLED` | `true` | Starts the dispatch worker (defaults on only in `local`). |
| `PYNAPLI_FIREBASE_PROJECT_ID` | `pynapli` | Routes FCM under the keyless ADC path. |

Credentials — **no JSON key needed** (the GCP org policy blocks key creation; `resolveFirebaseCredentials` falls back to Application Default Credentials):

- **Local:** `gcloud auth application-default login` then `gcloud auth application-default set-quota-project pynapli`.
- **Cloud Run:** attach the service account on deploy (`--service-account firebase-adminsdk-fbsvc@pynapli.iam.gserviceaccount.com`); ADC comes from the metadata server.
- Explicit `PYNAPLI_FIREBASE_SERVICE_ACCOUNT_JSON` / `_PATH` still override ADC if ever required.

Client: `androidApp/google-services.json` (gitignored, package `com.pynapli.app`) drives FCM token registration.

**Verify live:** register a device from the app, trigger an event to that user (e.g. a message), and confirm a `SENT` count in `GET /v1/admin/metrics/notifications` (admin-token gated).

## Google Cloud Setup

Recommended services:

- Cloud Run for Ktor API
- Cloud SQL PostgreSQL + PostGIS
- Memorystore Redis
- Secret Manager
- Artifact Registry
- Cloud Storage for media
- Cloud CDN, if using Google stack
- Cloud Logging/Monitoring
- Pub/Sub or Cloud Tasks later

Do not create production resources until:

- schema exists
- deploy pipeline exists
- secrets plan exists
- cost alerts exist

## Secrets Management

Use:

- GitHub Actions secrets for CI bootstrap
- Google Secret Manager for cloud runtime
- Local `.env` ignored by git

Never commit:

- Firebase service account JSON
- DB passwords
- JWT signing keys
- Store API credentials
- Apple/Google billing keys
- Age verification provider keys

## Environments

### Dev

Purpose:

- Local and developer testing.

Data:

- Fake/test only.

### Staging

Purpose:

- Production-like QA.

Data:

- Test accounts only.

### Production

Purpose:

- Real users.

Data:

- Strict access control, backups, audit logs.

## Cost Controls

Set up before staging/prod:

- Billing alerts
- Daily spend alerts
- Cloud SQL size limits
- Logging retention caps
- Storage lifecycle policies
- CDN budget monitoring

## Domain And Email

Recommended:

- Buy `pynapli.com`
- Configure DNS through a stable provider
- Set up email aliases
- Add SPF/DKIM/DMARC

Public pages needed:

- Privacy Policy
- Terms
- Community Guidelines
- Safety Center
- Support
- Security contact

## Accounts To Create

Founder-owned:

- GitHub org/account: `pynapli`
- Google Cloud billing account
- Firebase/GCP projects: `pynapli-dev`, `pynapli-staging`, `pynapli-prod`
- Apple Developer Program
- Google Play Console
- Domain registrar for `pynapli.com`
- Support email provider

Do not create with a personal throwaway email. Use a founder/business-controlled identity with recovery enabled.

## Founder Setup Checklist

Sebastien should set up:

- [x] Confirm `pynapli.com` DNS access.
- [ ] Create `support@pynapli.com`.
- [ ] Create `privacy@pynapli.com`.
- [ ] Create `security@pynapli.com`.
- [ ] Create `legal@pynapli.com`.
- [ ] Create `moderation@pynapli.com`.
- [ ] Create `admin@pynapli.com`.
- [ ] Create `no-reply@pynapli.com`.
- [ ] Create GitHub organization `pynapli`, if available.
- [ ] Partial: GitHub repository exists at `thedolce1/Pynapli`; GitHub org/private repo decision remains.
- [ ] Partial: Google Cloud/Firebase workspace exists; billing/project naming still needs final confirmation.
- [ ] Partial: Firebase/GCP project creation has started; `pynapli-dev`, `pynapli-staging`, and `pynapli-prod` are not confirmed.
- [ ] Create Apple Developer account.
- [ ] Create Google Play Console account.

If `pynapli` is unavailable as a GitHub org, fallback options:

- `pynapli-app`
- `pynapli-social`
- `pynaplihq`

## Implementation Checklist

- [x] GitHub repo created.
- [ ] Branch protections configured.
- [x] Domain purchased.
- [ ] Email aliases reserved.
- [ ] Partial: GCP billing/project setup started; final production naming/cost controls are not confirmed.
- [ ] Partial: Firebase project setup started; final dev/staging/prod names are not confirmed.
- [ ] Apple Developer account exists.
- [ ] Google Play Console exists.
- [x] Docker Desktop installed.
- [x] GitHub CLI installed.
- [x] No secrets committed.
- [ ] Cost alerts planned before cloud deploy.

## Do Not Implement

- Do not create production cloud resources before CI/deploy plan.
- Do not share secrets in chat.
- Do not put real user data in dev/staging.
- Do not use Firestore as primary discovery database.
- Do not skip branch protection.
- Do not launch without billing alerts.
