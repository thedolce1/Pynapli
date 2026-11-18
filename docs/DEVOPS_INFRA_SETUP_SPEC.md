# Pynapli DevOps and Infrastructure Spec

Rules for CI, Docker, Firebase, Google Cloud, secrets and environments. Step-by-step GCP commands are
in [../infra/cloud-run/README.md](../infra/cloud-run/README.md); the founder's ordered path and the
current cloud state are in [PATH_TO_TESTERS.md](PATH_TO_TESTERS.md). Toolchain and dependency
baseline: [PROJECT_STRUCTURE_AND_DEPENDENCIES_SPEC.md](PROJECT_STRUCTURE_AND_DEPENDENCIES_SPEC.md).

## Naming

| Thing | Name |
| --- | --- |
| Repository | `thedolce1/Pynapli` (GitHub org `pynapli` planned; fallbacks `pynapli-app`, `pynapli-social`, `pynaplihq`) |
| Branches | `main`, `develop`, feature branches |
| GCP/Firebase projects (planned) | `pynapli-dev`, `pynapli-staging`, `pynapli-prod`. Only `pynapli` exists today (see PATH_TO_TESTERS). |
| Package id | `com.pynapli.app` |
| Cloud Run services | `pynapli-api`, `pynapli-worker` (later) |
| Domain | `pynapli.com` |
| Email aliases | `support@`, `privacy@`, `security@`, `legal@`, `moderation@`, `admin@`, `no-reply@` (`pynapli.com`) |

Founder-owned accounts (business-controlled identity with recovery, never a throwaway email): GitHub,
Google Cloud billing, Firebase/GCP projects, Apple Developer Program, Google Play Console, domain
registrar, support email provider.

## Environments

| Env | Purpose | Data |
| --- | --- | --- |
| Dev / local | Developer testing | Fake/test only |
| Staging | Production-like QA | Test accounts only |
| Production | Real users | Strict access control, backups, audit logs |

Never put real user data in dev or staging.

## Local development

Tools: Git, GitHub CLI, JDK (version per the project-structure spec), Android Studio, Xcode on a Mac
for iOS, Docker Desktop, Firebase CLI, Google Cloud CLI. `docker compose up -d` starts PostgreSQL +
PostGIS and Redis from `docker-compose.yml`. Run/seed steps are the `local-dev` skill.

## CI

`.github/workflows/ci.yml` runs on pull requests (not for docs-only changes) and by hand
(`gh workflow run CI`); it no longer runs on pushes to `main`, because the pre-push hook already
runs the gates locally. A newer push to the same PR cancels the run in progress. It runs:

- **Format (Spotless)**: `spotlessCheck` with `ratchetFrom = "origin/main"` (needs `fetch-depth: 0`
  plus an explicit `git fetch origin main`). ktlint via Spotless; detekt deferred until a
  Kotlin-2.3-compatible stable release exists.
- **Verify (tests + assemble)**: `:shared:jvmTest`, `:server:test`, `:composeApp:desktopTest`,
  `:desktopApp:desktopTest`, `:androidApp:assembleDebug`, then release lint + minified release assemble. Server integration tests manage
  Postgres/Redis through Testcontainers.
- **Web (Wasm compile)**: brand and Compose token checks, then the wasm compile.
- **Docker Smoke (Cloud Run image)**: builds `:server:installDist` and `infra/server.Dockerfile`, starts
  Postgres/PostGIS and Redis, runs the API container, and requires `/health` and `/health/deep` to
  return `200`.

The local `pre-push` hook (`scripts/hooks/pre-push`) is the enforced gate until branch protection is
on.

### Branch protection

Not applied yet (founder action, admin token). Job names are the status-check contexts:

```bash
gh api -X PUT repos/thedolce1/Pynapli/branches/main/protection \
  -H "Accept: application/vnd.github+json" \
  -f 'required_status_checks[strict]=true' \
  -f 'required_status_checks[contexts][]=Format (Spotless)' \
  -f 'required_status_checks[contexts][]=Verify (tests + assemble)' \
  -f 'required_status_checks[contexts][]=Web (Wasm compile)' \
  -f 'required_status_checks[contexts][]=Docker Smoke (Cloud Run image)' \
  -f 'enforce_admins=true' \
  -f 'required_pull_request_reviews[required_approving_review_count]=1' \
  -f 'restrictions=' \
  -f 'allow_force_pushes=false' \
  -f 'allow_deletions=false'
```

After this, direct pushes to `main` are blocked; merge by PR once checks are green. Also enable
Dependabot, secret scanning and code scanning.

## Docker and deploy scripts

- `docker-compose.yml`: local `postgres` (PostGIS) and `redis` only. Don't containerize mobile builds.
- `infra/server.Dockerfile`: Cloud Run runtime image for the Ktor API.
- `scripts/docker-server-build.ps1` (`:server:installDist` + image), `scripts/docker-server-run.ps1`
  (runs it against Compose dependencies).
- `scripts/gcp-server-image-push.ps1` (Artifact Registry), `scripts/gcp-cloud-run-deploy.ps1`
  (explicit project, region, Cloud SQL, Redis, App Check and Secret Manager parameters).
- `.github/workflows/staging-backend.yml` (manual staging deploy) and
  `.github/workflows/staging-tester-apk.yml` (manual staging APK, optional App Distribution);
  `scripts/android-staging-apk.ps1` builds the staging APK locally.

**Startup validation** fails fast for `staging`, `prod` and `production` when local defaults or unsafe
toggles are present. Production-like environments must provide non-local Postgres/Redis, cryptographic
App Check config, GCS media storage with signing credentials, Firebase/FCM config, strong
admin/cursor/deck secrets, and no dev-only purchase or media auto-approval paths.

## Firebase

Firebase is used for FCM, Crashlytics, Analytics, Remote Config, App Check, Hosting (website), and
optionally Auth as an identity provider. Rules:

- No authoritative profile, discovery or chat data in Firestore. Firestore is never the discovery
  database.
- No sensitive lifestyle labels in Analytics; no private album URLs in Crashlytics.
- Ktor decides notifications; FCM only delivers.

### FCM enablement

The server pipeline is code-complete: device registration (`POST /v1/notifications/devices`) → outbox
on events → gated dispatch worker → FCM, with quiet hours, per-category preferences, dead-token
pruning and privacy-safe delivery metrics. To turn it on:

| Env var | Value | Why |
| --- | --- | --- |
| `PYNAPLI_NOTIFICATION_PUSH_PROVIDER` | `firebase` | Selects the FCM sender (default `local` only logs) |
| `PYNAPLI_NOTIFICATION_DISPATCH_ENABLED` | `true` | Starts the dispatch worker (on by default only in `local`) |
| `PYNAPLI_FIREBASE_PROJECT_ID` | `pynapli` | Routes FCM under the keyless ADC path |

Credentials use Application Default Credentials; **no JSON key** (the org policy blocks key creation):

- Local: `gcloud auth application-default login`, then
  `gcloud auth application-default set-quota-project pynapli`.
- Cloud Run: attach the runtime service account on deploy; ADC comes from the metadata server.
- `PYNAPLI_FIREBASE_SERVICE_ACCOUNT_JSON` / `_PATH` still override ADC if ever needed.

Client: `androidApp/google-services.json` (gitignored) drives token registration. Verify live by
triggering an event to a registered device and checking a `SENT` count in
`GET /v1/admin/metrics/notifications` (admin-token gated).

### App Check enablement

- Android debug builds use the App Check debug provider; release builds use Play Integrity.
- The shared Ktor client sends `X-Firebase-AppCheck` when Firebase returns a token.
- Ktor verifies it when `PYNAPLI_APP_CHECK_ENFORCED=true`; with `PYNAPLI_APP_CHECK_PROJECT_NUMBER`
  (the project **number**, not id) set, verification is cryptographic against Google's App Check JWKS.

Before enforcing: register `com.pynapli.app` for App Check; allowlist debug tokens from logcat for
emulator QA; validate on staging, then production. App Check reduces non-genuine traffic but does not
replace auth, object ownership checks, rate limits or abuse scoring.

## Google Cloud

Cloud Run (API), Cloud SQL PostgreSQL + PostGIS, Memorystore Redis, Secret Manager, Artifact Registry,
Cloud Storage (media), Cloud CDN, Cloud Logging/Monitoring; Pub/Sub or Cloud Tasks later. Don't
create production resources before the deploy pipeline, secrets plan and cost alerts exist.

## Secrets

GitHub Actions secrets for CI, Google Secret Manager at runtime, a local `.env` ignored by git
(`.env.example` lists every variable). Never commit or paste into chat:

- Firebase/service-account JSON, `google-services.json`, `GoogleService-Info.plist`.
- Database passwords.
- Server signing secrets: `PYNAPLI_DISCOVERY_CURSOR_SECRET`, `PYNAPLI_ACTIVITY_CURSOR_SECRET`,
  `PYNAPLI_SWIPE_DECK_TOKEN_SECRET`, admin token and MFA secrets. (Auth tokens are opaque and hashed;
  there is no JWT signing key.)
- Store API credentials, Apple/Google billing keys, age-verification provider keys.

## Cost controls

Before staging/prod: billing and daily spend alerts, Cloud SQL size limits, logging retention caps,
storage lifecycle policies, CDN budget monitoring.

## Domain and public pages

DNS on a stable provider; SPF/DKIM/DMARC for the email domain. Public pages needed before launch:
Privacy Policy, Terms, Community Guidelines, Safety Center, Support, security contact.

## Open items

- [ ] Branch protection on `main`.
- [ ] Email aliases.
- [ ] Final project naming; separate production project.
- [ ] Apple Developer and Google Play Console accounts.
- [ ] Cost alerts before any cloud deploy.
- [x] Domain purchased; GitHub repo; Docker image and deploy scripts; App Check client code; no
  secrets committed.

## Do not

- Create production cloud resources before the deploy pipeline and cost alerts exist.
- Share secrets in chat.
- Put real user data in dev or staging.
- Use Firestore as the discovery database.
- Skip branch protection or launch without billing alerts.
