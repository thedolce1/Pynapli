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
- Run detekt/ktlint if selected
- Run backend integration tests
- Build Android debug

Later:

- iOS build
- Maestro E2E
- k6 load tests
- Docker image build
- Deploy staging
- Database migration dry run

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

- [ ] Confirm `pynapli.com` DNS access.
- [ ] Create `support@pynapli.com`.
- [ ] Create `privacy@pynapli.com`.
- [ ] Create `security@pynapli.com`.
- [ ] Create `legal@pynapli.com`.
- [ ] Create `moderation@pynapli.com`.
- [ ] Create `admin@pynapli.com`.
- [ ] Create `no-reply@pynapli.com`.
- [ ] Create GitHub organization `pynapli`, if available.
- [ ] Create private GitHub repository `pynapli`, if desired now.
- [ ] Create or confirm Google Cloud billing account under Sebastien Mayeras.
- [ ] Reserve/create GCP projects `pynapli-dev`, `pynapli-staging`, `pynapli-prod` when ready.
- [ ] Create Apple Developer account.
- [ ] Create Google Play Console account.

If `pynapli` is unavailable as a GitHub org, fallback options:

- `pynapli-app`
- `pynapli-social`
- `pynaplihq`

## Implementation Checklist

- [ ] GitHub repo created.
- [ ] Branch protections configured.
- [ ] Domain purchased.
- [ ] Email aliases reserved.
- [ ] GCP billing account exists.
- [ ] Firebase project names reserved.
- [ ] Apple Developer account exists.
- [ ] Google Play Console exists.
- [ ] Docker Desktop installed.
- [ ] GitHub CLI installed.
- [ ] No secrets committed.
- [ ] Cost alerts planned before cloud deploy.

## Do Not Implement

- Do not create production cloud resources before CI/deploy plan.
- Do not share secrets in chat.
- Do not put real user data in dev/staging.
- Do not use Firestore as primary discovery database.
- Do not skip branch protection.
- Do not launch without billing alerts.
