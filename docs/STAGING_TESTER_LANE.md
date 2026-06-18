# Staging Tester Lane

This lane is the bridge between local dev APKs and production release builds. It gives trusted testers
a Firebase-enabled APK pointed at a Cloud Run staging API, without using laptop IPs or emulator-only
addresses.

## What This Lane Provides

- `staging` Android build type:
  - API: `pynapli.android.stagingApiBaseUrl`
  - WebSocket: `pynapli.android.stagingWebsocketBaseUrl`
  - Firebase config from `androidApp/google-services.json`
  - Firebase App Check debug provider, so staging testers can be allowlisted before Play Integrity is used in production
- `Deploy Staging API` GitHub workflow:
  - Builds the Ktor Cloud Run image
  - Pushes it to Artifact Registry
  - Deploys Cloud Run with staging env vars, Secret Manager bindings, Cloud SQL, Memorystore, GCS, FCM, and App Check
  - Smoke-tests `/health` and `/health/deep`
- `Staging Tester APK` GitHub workflow:
  - Writes `google-services.json` from a GitHub secret
  - Builds `:androidApp:assembleStaging`
  - Uploads the APK as a GitHub artifact
  - Optionally distributes to Firebase App Distribution groups

## Required GitHub Secrets

| Secret | Purpose |
| --- | --- |
| `ANDROID_GOOGLE_SERVICES_JSON_B64` | Base64 encoded `androidApp/google-services.json`; required for Firebase-enabled APK artifacts in CI. |
| `GCP_WORKLOAD_IDENTITY_PROVIDER` | Full Workload Identity provider resource for GitHub Actions to authenticate to GCP. |
| `GCP_CI_SERVICE_ACCOUNT` | Service account email used by GitHub Actions for Artifact Registry, Cloud Run, and Firebase App Distribution. |
| `FIREBASE_ANDROID_APP_ID` | Firebase Android App ID, e.g. `1:...:android:...`, used by App Distribution. |

## Required GCP/Firebase Setup

- Artifact Registry Docker repo exists or the deploy workflow has permission to create it.
- Cloud SQL PostgreSQL/PostGIS instance and `pynapli_staging` database exist.
- Memorystore Redis exists and is reachable from Cloud Run through the VPC connector.
- GCS bucket exists for staging media.
- Secret Manager contains:
  - `pynapli-staging-db-password`
  - `pynapli-staging-media-signer-json`
  - `pynapli-staging-admin-api-token`
  - `pynapli-staging-discovery-cursor-secret`
  - `pynapli-staging-activity-cursor-secret`
  - `pynapli-staging-swipe-deck-token-secret`
- Firebase App Check is registered for `com.pynapli.app`; staging/debug test devices are allowlisted.
- Firebase App Distribution has at least one tester group, default `founders`.

## Workflow Order

1. Run **Deploy Staging API**.
2. Copy the Cloud Run URL from the workflow summary.
3. Run **Staging Tester APK** with:
   - `api_base_url = <Cloud Run URL>`
   - `websocket_base_url = <Cloud Run URL with https:// changed to wss://>`
4. Install the `pynapli-staging-tester-apk` artifact or let Firebase App Distribution send tester invites.
5. Run real-device smoke:
   - Login/signup
   - Grid load
   - Swipe load/action
   - Messages open/send
   - Activity badge/feed
   - Store open
   - Media upload
   - Push notification delivery

## Local Staging APK Build

For a local build pointed at an already deployed staging API:

```powershell
.\scripts\android-staging-apk.ps1 `
  -ApiBaseUrl "https://api-staging.pynapli.com" `
  -WebsocketBaseUrl "wss://api-staging.pynapli.com"
```

The script requires `androidApp/google-services.json` unless `-SkipGoogleServicesCheck` is passed.

## Non-Goals

- This is not the production Play Store lane.
- This does not replace release signing, Play Internal Testing, Data Safety, or production App Check/Play Integrity validation.
- This does not move product authority to Firebase. Ktor remains the source of truth for auth sessions, discovery, messages, safety, entitlements, media grants, and subscriptions.
