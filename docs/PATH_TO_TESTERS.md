# Path to Testers

The ordered founder path from today to the first external testers on Android, web and iOS, plus the
current cloud state and the staging tester lane. Merged on 2026-11-17 from `CLOUD_STATUS.md` and
`STAGING_TESTER_LANE.md`. GCP commands: [../infra/cloud-run/README.md](../infra/cloud-run/README.md).
iOS detail: [IOS_BRINGUP_PLAN.md](IOS_BRINGUP_PLAN.md).

Legend: 🔵 founder ops (accounts, cloud, money, devices, store consoles) · 🟢 codeable · 🍎 needs macOS

> **Deploying the Ktor API is the single blocker for Android testers and the PWA.** Every client is
> server-authoritative and points at `localhost` today. Do the backend first, once.

## Cloud state

The backend is code-complete and deploy-ready; live provisioning is not done. Product authority stays
in Ktor; Firebase is FCM, Crashlytics, Analytics, Remote Config, App Check and Hosting for the
website. Live resource state cannot be verified from the repo: confirm in the
[Firebase console](https://console.firebase.google.com/project/pynapli) and the
[GCP console](https://console.cloud.google.com/).

| Thing | Value |
| --- | --- |
| Firebase project id / number | `pynapli` / `209241465883` |
| Firebase admin service account | `firebase-adminsdk-fbsvc@pynapli.iam.gserviceaccount.com` |
| Android package id | `com.pynapli.app` |
| Domain | `pynapli.com` (purchased, DNS access confirmed) |
| Backend service | `pynapli-api` (Cloud Run), region `us-central1` |
| FCM/Firebase credentials | Application Default Credentials; **no JSON key** (org policy blocks key creation) |

**Open decision, project naming:** the DevOps spec plans `pynapli-dev` / `-staging` / `-prod`, but
the only real project is `pynapli`. Recommended: use `pynapli` as staging now, and create a separate
`pynapli-prod` (own Firebase app and `google-services.json`) before real users.

| Component | State | Notes |
| --- | --- | --- |
| Firebase project, Android app registered | Done | `androidApp/google-services.json` present locally, gitignored |
| CI | Live | `.github/workflows/ci.yml`: Format, Verify, Web, Docker Smoke |
| FCM server pipeline | Code-complete, not enabled | env vars in [DEVOPS_INFRA_SETUP_SPEC.md](DEVOPS_INFRA_SETUP_SPEC.md#fcm-enablement) |
| App Check | Client code in all build types; not enforced | [DEVOPS_INFRA_SETUP_SPEC.md](DEVOPS_INFRA_SETUP_SPEC.md#app-check-enablement) |
| Cloud Run API | Image, scripts and workflow ready; not deployed | `infra/server.Dockerfile`, `scripts/gcp-cloud-run-deploy.ps1`, `.github/workflows/staging-backend.yml` |
| Cloud SQL, Memorystore, Artifact Registry, Secret Manager, GCS bucket | Not confirmed | required before deploy |
| Firebase Hosting (website) | Configured (`firebase.json`, `.firebaserc`), never deployed | [../web/HOSTING.md](../web/HOSTING.md) |
| Branch protection on `main` | Not applied | command in [DEVOPS_INFRA_SETUP_SPEC.md](DEVOPS_INFRA_SETUP_SPEC.md#branch-protection) |
| Billing / cost alerts | Not set up | required before any prod resource |
| Email aliases | Not created | founder ops |
| Transactional email provider | **Not chosen. Required:** without one nobody can sign up (or reset a password) outside local development; both answer "unavailable" | below, A6 |

Verify live state: `GET https://<cloud-run-url>/health` and `/health/deep` (both `200`).

## Track A: Backend (blocks everything) 🔵

| # | Step | Detail |
|---|---|---|
| A1 | Lock project naming | above |
| A2 | Provision GCP: APIs, Cloud SQL (Postgres+PostGIS) + `pynapli_staging` DB, Memorystore Redis + VPC connector, Artifact Registry, 8 Secret Manager secrets, GCS media bucket, runtime service account | [infra/cloud-run/README.md](../infra/cloud-run/README.md) steps 1–7 |
| A3 | Deploy the API to Cloud Run, ideally at **`api.pynapli.com`** (the PWA default) | infra README step 8, or the **Deploy Staging API** workflow |
| A4 | Budget alerts. **Do not skip.** | infra README step 9 |
| A5 | Verify `/health` and `/health/deep` on the live URL | |
| A6 | **Choose and wire a transactional email provider.** Sign-up now emails a 6-digit code before any account exists, so without a provider *no one can create an account* outside local development (sign-up answers `503 SIGNUP_UNAVAILABLE`; reset answers `PASSWORD_RESET_UNAVAILABLE`). A provider must implement both server interfaces: `SignupMailer` (`sendSignupCode(email, code)`, `sendAlreadyRegisteredNotice(email)`, `isConfigured = true`) and `PasswordResetMailer` (`sendResetCode(email, code)`), be selected by a new `PYNAPLI_PASSWORD_RESET_MAILER` value (today only `log`, local-only, and `none` exist) wired in `buildEmailSignupDependencies` and `buildCoreDependencies`, take its API key from Secret Manager, send from a neutral sender with the exact wording in `SignupEmailWording` (no product name in sender or subject), and have SPF/DKIM/DMARC set up for the sending domain. | ONBOARDING_AUTH_SPEC "Membership Privacy" |
| A7 | **Decide Cloud Run CPU allocation for background work (cost).** Sign-up emails (and password-reset emails) are sent on the server's background scope *after* the response. With Cloud Run's default request-based CPU, an instance may be throttled to near zero CPU between requests, so those emails can be delayed until the next request or lost on scale-down. `scripts/gcp-cloud-run-deploy.ps1` does not set `--no-cpu-throttling` (CPU always allocated), which fixes this but bills CPU for the instance's whole lifetime. Founder decision: always-on CPU (more cost), or keep throttling and accept delays (or move email sending to a queue later). Not changed in the script. | ONBOARDING_AUTH_SPEC "Email Sign-up" |
| A8 | **Create the two "Hide from people you know" keys.** Staging and production refuse to start without `PYNAPLI_CONTACT_BLOCK_EMAIL_KEY` (K1) and `PYNAPLI_CONTACT_BLOCK_TARGET_KEY` (K2): two strong random values (32+ characters), different from each other and from the three cursor/deck secrets. Store them as two Secret Manager secrets and deploy with `scripts/gcp-cloud-run-deploy.ps1 -ContactBlockEmailKeySecretName <name> -ContactBlockTargetKeySecretName <name>` (8 secrets in total now). Never rotate them casually: a new key silently orphans every stored code. Keep them out of reach of database-only access: with the database alone a leak shows counts, not who; with K2 it shows who hides from whom. The resolver for blocks on existing members runs on a background worker, so it needs CPU between requests (A7); each instance also sweeps every pending row on its first tick. | TRUST_AND_SAFETY_SPEC "Contact Blocks" |
| A9 | **Turn on photo pre-screening (Cloud Vision).** Staging and production now default to `PYNAPLI_MEDIA_MODERATION_PROVIDER=cloud_vision`, and production refuses to start on `manual` unless `PYNAPLI_MEDIA_MODERATION_ALLOW_MANUAL_IN_PRODUCTION=true`. Steps: (1) enable the **Cloud Vision API** (`vision.googleapis.com`) on the project the media service account belongs to, with **billing** on (SafeSearch is billed per image after the free monthly tier; check current pricing); (2) the scanner uses the media signing service account (`PYNAPLI_MEDIA_GCS_SIGNING_SA_JSON`, already required for GCS) and passes Vision a `gs://` URI, so that account needs object read on the media bucket, which signing already implies; confirm against the Vision docs ("Detect explicit content", image source from Cloud Storage) that no extra role is needed; (3) deploy, upload one ordinary photo and check the startup log line says `moderation=cloud_vision` and the photo turns approved within seconds; (4) **live run with real images** (founder track): a handful of clean, suggestive and explicit test images, confirming clean and suggestive photos are approved and explicit ones land in `GET /v1/admin/media/pending`. Tune `PYNAPLI_MEDIA_MODERATION_MAX_ADULT/_MAX_VIOLENCE/_MAX_RACY` only if that run says so. Side effect: the same setting turns on Vision face counting for verification selfies. (4b) **Bucket CORS for web uploads:** the media bucket's CORS config must allow `PUT` from `https://app.pynapli.com` with `responseHeader` including `Content-Type` and `x-goog-if-generation-match` (uploads are signed create-only); without it every browser upload fails its preflight. (5) **Privacy policy:** name Google Cloud Vision as a processor of profile photos and verification selfies before go-live. Private-album and chat photos are not sent to Vision; `PYNAPLI_MEDIA_MODERATION_SCAN_PRIVATE_MEDIA` stays `false` unless you and legal decide to scan intimate media (it then removes private photos and refuses chat sends on a strong violence signal only), and the policy must say so first. | TRUST_AND_SAFETY_SPEC "Photo pre-screening" |
| A10 | **Continue with Google / Apple (optional; off until done).** The client is built and hidden; each provider shows only when the server flag is on *and* the client carries its id. **Google** (one Google Cloud project, Google Auth Platform): (1) consent screen and brand verification (the app name shows on Google's sheet only once verified); (2) a **Web** OAuth client: authorised JavaScript origins `https://app.pynapli.com` (plus `http://localhost` and `http://localhost:8081` for local web runs); its client id is what the server checks as the audience and what both apps use; (3) an **Android** OAuth client with package `com.pynapli.app` and the **release** keystore's SHA-1 (and the Play App Signing SHA-1 once Play signs; a debug SHA-1 for local QA); without it Credential Manager returns no credential; (4) put the web client id in `pynapli.android.googleServerClientId` (gradle property, `local.properties` or `-P`) and in `index.html` as `window.__PYNAPLI_GOOGLE_CLIENT_ID__`; (5) **logo file:** download the button assets from the Sign in with Google branding page (developers.google.com/identity/branding-guidelines, "Download" / pre-approved assets), take the standard colour "G" SVG as used in the dark-theme button, save it as `composeApp/src/commonMain/composeResources/files/brand/google-g.svg`, then set `GOOGLE_MARK_IS_OFFICIAL_ARTWORK = true` in `design/PynapliSocialButtons.kt` (until then no Google button shows anywhere). **Apple** (Developer Program): (1) an App ID with Sign in with Apple; (2) a **Services ID** (e.g. `com.pynapli.web`) with domain `app.pynapli.com` and a return URL (the page origin `https://app.pynapli.com/` unless `__PYNAPLI_APPLE_REDIRECT_URI__` says otherwise); (3) a Sign in with Apple **key** (.p8) for the server (client secret JWT, revocation); (4) the private email relay: register the sending domain/address if the app ever emails relay addresses; (5) set `window.__PYNAPLI_APPLE_SERVICES_ID__` in `index.html`; (6) **logo file:** download the Sign in with Apple resources from Apple Design Resources (developer.apple.com/design/resources/, "Sign in with Apple"), take the logo-only SVG in black (for the white button), save it as `composeApp/src/commonMain/composeResources/files/brand/apple-logo-black.svg`, then set `APPLE_MARK_IS_OFFICIAL_ARTWORK = true` in `design/PynapliSocialButtons.kt` (until then no Apple button shows anywhere, whatever the server flag says). **Server settings:** `PYNAPLI_GOOGLE_CLIENT_IDS` must list the **web** client id (Android also sends tokens whose audience is the web client id, because that is its `serverClientId`; listing the Android client id too is harmless); `PYNAPLI_APPLE_CLIENT_IDS` lists the Services ID and the bundle id; `PYNAPLI_APPLE_REDIRECT_URIS` must contain `<Services ID>=<return URL>` with exactly the URL the web client sends (`__PYNAPLI_APPLE_REDIRECT_URI__`, default the page origin + `/`), or Apple's code exchange fails; Apple team id, key id and the .p8 key and `PYNAPLI_SOCIAL_TOKEN_KEY` go in the secret manager, never in the repo. `scripts/gcp-cloud-run-deploy.ps1` does not pass these secrets yet: add them before the first live run. **Hosting:** the app site's headers from WEB_PWA_GAMEPLAN "Response headers for app.pynapli.com" (CSP origins, COOP `same-origin-allow-popups`); check the browser console for CSP violations on the first live run (the Apple origins are not from an Apple document). Then switch the server flag on per provider and do a live run: new account through Birthday/Policies, a returning sign-in, the email-has-account notice, link/unlink in Settings. | ONBOARDING_AUTH_SPEC "Continue with Google / Apple: client" |

**A3 done = Android testers and the PWA both become possible.**

## Track B: Android to testers

| # | Step | Owner | Notes |
|---|---|---|---|
| B1 | Release signing keystore; set `pynapli.android.releaseKeystore*` in `local.properties` | 🔵 | Builds unsigned without it. **Back the keystore up: losing it means you can never update the app on Play.** |
| B2 | Staging APK pointed at the A3 URL | 🟢 | Staging lane below |
| B3 | Real-device QA on one physical mid-range phone | 🔵 | Emulator-only so far: battery/location, push delivery, low network. Harnesses exist. |
| B4 | Play Console: app entry, internal testing track, upload the signed build | 🔵 | [archive/ANDROID_PRODUCTION_GAMEPLAN.md](archive/ANDROID_PRODUCTION_GAMEPLAN.md) H3 |
| B5 | Data Safety form, content rating, store listing | 🔵 | A dating/lifestyle app is scrutinised here; budget real time |

Staging and prod need their own `google-services.json` per Firebase app if projects are split at A1.

### Staging tester lane

A Firebase-enabled APK pointed at a Cloud Run staging API, without laptop IPs or emulator addresses.
It is not the Play lane: it does not replace release signing, Play internal testing, Data Safety, or
production App Check / Play Integrity. Ktor remains the authority for sessions, discovery, messages,
safety, entitlements, media grants and subscriptions.

- `staging` Android build type: API `pynapli.android.stagingApiBaseUrl`, WebSocket
  `pynapli.android.stagingWebsocketBaseUrl`, Firebase config from `androidApp/google-services.json`,
  App Check **debug** provider (so testers can be allowlisted).
- **Deploy Staging API** workflow (`staging-backend.yml`): builds and pushes the image, deploys Cloud
  Run with staging env, Secret Manager bindings, Cloud SQL, Memorystore, GCS, FCM and App Check, then
  smoke-tests `/health` and `/health/deep`.
- **Staging Tester APK** workflow (`staging-tester-apk.yml`): writes `google-services.json` from a
  secret, builds `:androidApp:assembleStaging`, uploads the APK artifact, optionally distributes via
  Firebase App Distribution.

GitHub secrets:

| Secret | Purpose |
| --- | --- |
| `ANDROID_GOOGLE_SERVICES_JSON_B64` | Base64 `androidApp/google-services.json` for Firebase-enabled CI APKs |
| `GCP_WORKLOAD_IDENTITY_PROVIDER` | Workload Identity provider resource for GitHub Actions → GCP |
| `GCP_CI_SERVICE_ACCOUNT` | Service account for Artifact Registry, Cloud Run, App Distribution |
| `FIREBASE_ANDROID_APP_ID` | Firebase Android app id (`1:...:android:...`) for App Distribution |

Also required: the GCP resources and eight secrets from Track A; App Check registered for
`com.pynapli.app` with staging/debug devices allowlisted; at least one App Distribution tester group
(default `founders`).

Order:

1. Run **Deploy Staging API**; copy the Cloud Run URL from the summary.
2. Run **Staging Tester APK** with `api_base_url = <Cloud Run URL>` and
   `websocket_base_url = <same URL with https:// changed to wss://>`.
3. Install the `pynapli-staging-tester-apk` artifact, or let App Distribution send invites.
4. Real-device smoke: login/signup, Grid load, Swipe load and action, Messages open/send, Activity
   badge/feed, Store open, media upload, push delivery.

Local build against a deployed staging API (requires `androidApp/google-services.json` unless
`-SkipGoogleServicesCheck`; the WebSocket URL is derived unless overridden):

```powershell
.\scripts\android-staging-apk.ps1 `
  -ApiBaseUrl "https://api-staging.pynapli.com" `
  -WebsocketBaseUrl "wss://api-staging.pynapli.com"
```

## Track C: PWA and website 🔵

Founder decision (2026-07-09): launch the funnel together (backend → PWA → site), so the site's
"Create account" button reaches a working app on day one.

| # | Step | Owner | Notes |
|---|---|---|---|
| C1 | Point the PWA at the API: set `window.__PYNAPLI_API_BASE__` in `webApp/src/wasmJsMain/resources/index.html`, or leave `null` for the `https://api.pynapli.com` default | 🟢 | No Wasm rebuild |
| C2 | Build `webApp/build/dist/wasmJs/productionExecutable` and host at **`app.pynapli.com`** with an SPA rewrite (serve `index.html` for unknown paths) | 🔵 | Deep links 404 without the rewrite. Can be a second Firebase Hosting site. |
| C3 | CORS: non-local defaults to `https://app.pynapli.com`; `PYNAPLI_CORS_ALLOWED_ORIGINS` **replaces** that default, so include it if you set the variable | 🔵 | |
| C4 | Deploy `pynapli.com` | 🔵 | below |

```bash
cd web && npm run build && cd ..
firebase login
firebase deploy --only hosting     # publishes web/dist (project pynapli)
```

Then Firebase console → Hosting → add custom domain `pynapli.com`. See [../web/HOSTING.md](../web/HOSTING.md).

## Track D: iOS to TestFlight 🍎

Independent of A–C for authoring, but a usable TestFlight build still needs A3.

| # | Step | Owner | Notes |
|---|---|---|---|
| D1 | Xcode project + first framework link | 🟢 on Mac | [IOS_BRINGUP_PLAN.md](IOS_BRINGUP_PLAN.md) step 1; carries most of the risk |
| D2 | Verify the Keychain session cipher on simulator/device | 🟢 on Mac | **Security blocker:** tokens are plaintext until proven |
| D3 | APNs + Firebase Apple SDK | 🟢 on Mac + 🔵 certs | Needs an Apple Developer account |
| D4 | StoreKit 2 (can follow the first release; Store read-only as on web) | 🟢 on Mac | |
| D5 | App Store Connect entry, TestFlight build, privacy nutrition labels | 🔵 | Developer Program enrolment takes time: start early |

## Before public launch (not blocking testers)

- Final Privacy Policy and Terms (currently DRAFT + `noindex`); GeoNames attribution in-app
  ([THIRD_PARTY_DATA.md](THIRD_PARTY_DATA.md)).
- Wire the website waitlist form to an email service or remove it (currently a no-op).
- Email aliases: `support@`, `privacy@`, `security@`, `legal@`, `moderation@`, `admin@`, `no-reply@`.
- Branch protection on `main`; separate prod project and DB; automated backups; rollback procedure;
  billing alerts.
