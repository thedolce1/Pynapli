# Pynapli Project Structure And Dependencies Spec

Last updated: 2026-04-24

## Purpose

This document defines the initial repository structure, module boundaries, folder conventions, dependency baseline, and version policy for Pynapli.

Core rule:

> Start with a boring, explicit architecture that lets Kotlin Multiplatform, Compose, and Ktor do the heavy lifting without hiding product rules in random screens or backend handlers.

## Version Policy

Use stable releases by default.

Rules:

- Do not use alpha, beta, RC, EAP, dev, or preview versions unless there is a specific written reason.
- Pin all versions in a Gradle version catalog.
- Re-verify dependency versions before scaffolding the repo.
- Use [OFFICIAL_TECH_REFERENCE_GUIDE.md](OFFICIAL_TECH_REFERENCE_GUIDE.md) when checking setup, compatibility, and release notes.
- Upgrade deliberately, one toolchain family at a time.
- Keep Kotlin, Compose Multiplatform, Android Gradle Plugin, Gradle, and Xcode compatibility visible in pull requests that touch build files.
- Keep Firebase as platform-specific support infrastructure, not a common shared product authority.

## Toolchain Versions

`gradle/libs.versions.toml` is the single source of truth for every version (the version policy in
`CLAUDE.md` and `docs/OFFICIAL_TECH_REFERENCE_GUIDE.md` governs changes: stable releases only,
verified against official docs). Versions aren't repeated here, because copies drift: the table that
used to sit here still listed Compose Multiplatform 1.10.3 and Koin after both had changed.

## Platform Baseline

Android:

- Package ID: `com.pynapli.app`
- Minimum SDK: `23`
- Target SDK: latest store-required SDK at release time
- Compile SDK: match current stable Android SDK available in CI
- AGP, Gradle wrapper, Play Billing, Firebase BoM: see `gradle/libs.versions.toml` and `gradle/wrapper/gradle-wrapper.properties`

Why Android minSdk 23:

- Compose Multiplatform supports Android API 21, but Firebase Android SDK 34.x release notes include API 23 minimum requirements for multiple Firebase libraries.
- Pynapli expects Firebase Cloud Messaging, Crashlytics, Analytics, Remote Config, and App Check to be used where helpful.
- Supporting API 21-22 would add complexity for little practical launch value.

iOS:

- Minimum iOS: `13`
- App bundle ID: `com.pynapli.app`
- iOS shell: Swift/SwiftUI only where needed to host Compose and platform services
- Firebase Apple SDK: version pinned in the iOS project once it exists (`docs/IOS_BRINGUP_PLAN.md`)
- Payments: StoreKit 2
- Push: APNs with Firebase Cloud Messaging bridge if useful

Server:

- Kotlin/JVM
- Ktor server
- PostgreSQL + PostGIS
- Redis
- Flyway migrations
- Dockerized local dependencies
- Cloud Run compatible container image

JDK:

- Use JDK 17 as the initial CI/runtime baseline for maximum Android Gradle Plugin compatibility.
- Server can move to JDK 21 later if a specific benefit is worth the build matrix cost.
- Do not start on JDK 25/26 just because it is available.

## Repository Shape

Recommended repository:

```text
pynapli/
  .github/
    workflows/
    dependabot.yml
  .run/
  build-logic/
  config/
  docs/
  gradle/
    libs.versions.toml
  composeApp/
  androidApp/
  shared/
  server/
  admin/
  iosApp/
  infra/
  scripts/
  docker-compose.yml
  settings.gradle.kts
  build.gradle.kts
  gradle.properties
  README.md
```

## Module Ownership

### `shared`

Owns cross-platform product logic.

Contains:

- Domain models.
- DTOs.
- Repository interfaces.
- Client networking.
- Validation rules.
- Local persistence schema.
- Platform abstraction interfaces.

Does not contain:

- Android-only Firebase calls.
- iOS-only Firebase calls.
- StoreKit direct calls.
- Google Play Billing direct calls.
- Raw OS location APIs.
- Admin-only backend workflows.

### `composeApp`

Owns shared Compose UI for Android and iOS.

Contains:

- Screens.
- Shared design system.
- Navigation host.
- Feature composables.
- Feature state holders (`*StateHolder`, exposing `StateFlow` UI state).
- Shared UI animation.

Does not contain:

- Business rules that need to be enforced by the server.
- Direct SQL or raw network calls from composables.
- Product authorization decisions.

### `androidApp`

Owns the Android application shell.

Contains:

- Android manifest.
- Android `Application`.
- Android `Activity`.
- Android resources.
- Android app packaging settings.
- Android-only permission declarations.

Does not contain:

- Shared product UI.
- Product business rules.
- Server-authoritative decisions.

### `iosApp`

Owns the native iOS shell.

Contains:

- Xcode project.
- Swift app entry.
- Compose host view.
- iOS permissions.
- APNs setup.
- StoreKit bridge.
- Keychain bridge.
- Firebase Apple SDK setup.

### `desktopApp`

Owns the JVM development shell (added 2026-07-28). Exists so shared Compose UI can be iterated on a
Windows host without an emulator install per change, and without waiting for a Mac.

Contains:

- Compose Desktop entry point (`com.pynapli.desktop.MainKt`).
- `DesktopAppGraph` — the same seam the other shells supply to `PynapliCommonGraph`.
- Desktop stand-ins: file-backed session/settings stores, a Swing file-chooser media picker, and a
  fixed-coordinate location adapter.

Rules:

- **Never shipped.** It is a development target; there is no distribution track for it.
- Session tokens are stored in plaintext (no app-controlled keystore on desktop), so
  `DesktopRuntimeConfig` must refuse non-local API hosts unless explicitly overridden.
- It does not replace device/emulator QA — insets, gestures, IME, billing and push are invisible
  here. See "Desktop proxy for UI iteration" in `IOS_BRINGUP_PLAN.md`.

### `server`

Owns backend authority.

Contains:

- Ktor API.
- WebSocket gateway.
- Domain services.
- PostgreSQL repositories.
- Redis services.
- Moderation/risk services.
- Subscription validation.
- Activity generation.
- Migrations.
- Backend tests.

### `admin`

Owns the internal admin/moderation console.

Initial recommendation:

- Keep admin as a separate web app or server-rendered admin module once backend exists.
- Do not mix moderation console UI into the mobile app modules.
- Do not build admin first unless moderation tooling blocks beta.

### `infra`

Owns infrastructure-as-code and deployment definitions.

Contains:

- Dockerfiles.
- Cloud Run config.
- Terraform/OpenTofu later if needed.
- Environment templates.
- Local service config.

### `build-logic`

Owns Gradle convention plugins.

Use this to keep module build files short:

- `pynapli.kotlin.multiplatform`
- `pynapli.compose.app`
- `pynapli.android.application`
- `pynapli.ktor.server`
- `pynapli.testing`

## Shared Module Structure

Recommended:

```text
shared/src/commonMain/kotlin/com/pynapli/shared/
  core/
    config/
    errors/
    events/
    logging/
    permissions/
    result/
    time/
    validation/
  network/
    ApiClient.kt
    RealtimeClient.kt
    RequestContext.kt
    dto/
  storage/
    DatabaseDriverFactory.kt
    PynapliDatabase.sq
  auth/
  profile/
  grid/
  swipe/
  activity/
  messages/
  albums/
  partner/
  psync/
  playingTonight/
  subscriptions/
  settings/
  safety/
  notifications/
  design/
  platform/
```

Feature package pattern:

```text
feature/
  data/
    FeatureRepository.kt
    FeatureRemoteDataSource.kt
    FeatureLocalDataSource.kt
  domain/
    FeatureModel.kt
    FeaturePolicy.kt
    FeatureUseCase.kt
  presentation/
    FeatureViewModel.kt
    FeatureUiState.kt
    FeatureUiEvent.kt
```

Keep this pattern pragmatic. Tiny features do not need fake layers.

## Compose App Structure

Recommended:

```text
composeApp/src/commonMain/kotlin/com/pynapli/app/
  App.kt
  navigation/
    AppNavHost.kt
    AppRoute.kt
    BottomTabs.kt
  design/
    PynapliTheme.kt
    PynapliColors.kt
    PynapliType.kt
    PynapliSpacing.kt
    PynapliMotion.kt
    components/
  shell/
    MainScaffold.kt
    HeaderBar.kt
    BottomNavigationBar.kt
  grid/
    GridScreen.kt
    GridTile.kt
    GridFilterSheet.kt
  swipe/
    SwipeScreen.kt
    SwipeCard.kt
    MatchSheet.kt
  activity/
    ActivityScreen.kt
    ActivityRow.kt
  messages/
    MessagesScreen.kt
    ChatThreadScreen.kt
    MessageBubble.kt
  profile/
    ProfileScreen.kt
    ProfileSheet.kt
  settings/
    SettingsHubScreen.kt
  store/
    StoreScreen.kt
    PaywallSheet.kt
```

Rules:

- Feature screens consume UI state and callbacks.
- State holders live in `composeApp` next to the UI models they build; product rules they apply come from `shared` or the server.
- Design components stay in `composeApp/design`.
- Shared product models stay in `shared`.
- No screen should call Ktor directly.

Android app shell:

```text
androidApp/src/main/
  AndroidManifest.xml
  kotlin/com/pynapli/app/
    PynapliApplication.kt
    MainActivity.kt
  res/
```

AGP 9 note:

- `shared` and `composeApp` use `com.android.kotlin.multiplatform.library`.
- `androidApp` uses `com.android.application`.
- Do not collapse the Android app entry point into the KMP library modules.

## Server Structure

Recommended:

```text
server/src/main/kotlin/com/pynapli/server/
  Application.kt
  config/
  plugins/
    AuthPlugin.kt
    SerializationPlugin.kt
    MonitoringPlugin.kt
    RateLimitPlugin.kt
  routing/
    AuthRoutes.kt
    GridRoutes.kt
    SwipeRoutes.kt
    ActivityRoutes.kt
    MessageRoutes.kt
    PartnerRoutes.kt
    AlbumRoutes.kt
    SubscriptionRoutes.kt
    SafetyRoutes.kt
    AdminRoutes.kt
  domain/
    auth/
    profile/
    grid/
    swipe/
    activity/
    messages/
    albums/
    partner/
    psync/
    playingTonight/
    subscriptions/
    moderation/
    risk/
  persistence/
    db/
    repositories/
    migrations/
  realtime/
  notifications/
  billing/
  media/
  jobs/
  observability/
```

Server rules:

- Routes are thin.
- Domain services enforce product rules.
- Repositories handle storage mechanics.
- WebSocket events are convenience updates; HTTP/state fetch remains authoritative.
- Subscription entitlements are server-validated.
- Trust/safety risk decisions are server-owned.

## Gradle Version Catalog Rules

Use `gradle/libs.versions.toml`.

Rules:

- Every external dependency version belongs in the catalog.
- Internal project dependencies use type-safe project accessors.
- Avoid version strings in module build files.
- Group related versions clearly: Kotlin, Android, Compose, Ktor, Firebase, database, testing.
- Add comments for intentionally delayed upgrades.

## Dependency Choices

### Common KMP

Use:

- Kotlin Multiplatform.
- Compose Multiplatform.
- Ktor Client.
- Kotlinx Serialization.
- Kotlinx Coroutines.
- Kotlinx Datetime.
- SQLDelight.
- Coil 3 Compose.
- Manual constructor injection (`PynapliCommonGraph` + per-shell graphs). No DI framework: Koin was removed 2026-07-27, and adding one needs a written reason (`CLAUDE.md`).

Avoid initially:

- Multiple DI frameworks.
- Multiple image loaders.
- Multiple database abstractions.
- Experimental Compose APIs for core navigation unless needed.

### Android Platform

Use:

- Android Gradle Plugin.
- Firebase BoM.
- Firebase Cloud Messaging.
- Firebase Crashlytics.
- Firebase Analytics.
- Firebase Remote Config.
- Firebase App Check.
- Google Play Billing.

Rules:

- Do not use deprecated Firebase `-ktx` artifacts removed from BoM 34.x.
- Keep Firebase calls behind platform services.
- Do not let Firebase become the product data store.

### iOS Platform

Use:

- Firebase Apple SDK via Swift Package Manager unless CocoaPods is required by tooling.
- StoreKit 2.
- Keychain for secure storage.
- Core Location.
- PhotosUI/photo picker.
- APNs/FCM bridge only where useful.

Rules:

- Keep iOS native code as a thin platform shell.
- Do not duplicate product rules in Swift.
- Compose owns product UI unless a native screen is clearly better.

### Server

Use:

- Ktor Server.
- Kotlinx Serialization.
- PostgreSQL JDBC.
- Cloud SQL PostgreSQL socket factory for Cloud Run/Cloud SQL deployments.
- HikariCP.
- Flyway.
- Lettuce for Redis.
- Logback.
- Testcontainers.
- Kotest.
- MockK.

Database access decision:

- Start with explicit SQL repositories plus Flyway migrations.
- Consider Exposed or jOOQ only if query complexity justifies it.
- PostGIS queries should remain clear, inspectable, and index-aware.

## Compatibility Notes

Current checks from primary sources:

- Compose Multiplatform 1.10.3 supports Android API 21+ and iOS 13+, but Pynapli should use Android minSdk 23 because Firebase 34.x pushes several Firebase libraries to API 23+.
- Compose Multiplatform releases separately from Kotlin and Jetpack Compose. Keep Compose and Kotlin compatibility checked before upgrades.
- Android Gradle Plugin 9.2.0 lists Gradle 9.4.1 and JDK 17 in its compatibility table.
- Gradle 9.4.1 runs on JVM 17 through 26, but Android builds should stay conservative.
- Ktor 3.4.3 is the current stable Ktor release checked on 2026-04-24.
- Firebase Android BoM 34.12.0 and Firebase Apple SDK 12.12.1 were current at the time of this spec.
- Google Play Billing 8.3.0 was current at the time of this spec.

## Scaffolding Acceptance Checklist

The initial repo scaffold is acceptable when:

- `gradle/libs.versions.toml` contains the approved baseline.
- Android app builds from CLI.
- iOS app opens in Xcode and links the Kotlin framework.
- `shared` has common, Android, and iOS source sets.
- `server` starts a Ktor health endpoint.
- Docker Compose starts PostgreSQL/PostGIS and Redis.
- A first SQL migration runs locally.
- Common tests run.
- Server tests run.
- No Firebase secrets are committed.
- No product rule lives only in UI.

## Source References

- Kotlin Multiplatform project structure: https://kotlinlang.org/docs/multiplatform/multiplatform-discover-project.html
- KMP source set dependency rules: https://kotlinlang.org/docs/multiplatform/multiplatform-advanced-project-structure.html
- Google Android KMP guidance: https://developer.android.com/kotlin/multiplatform
- Compose Multiplatform compatibility: https://kotlinlang.org/docs/multiplatform/compose-compatibility-and-versioning.html
- Ktor releases: https://ktor.io/docs/releases.html
- Android Gradle Plugin 9.2.0 release notes: https://developer.android.com/build/releases/agp-9-2-0-release-notes
- Gradle compatibility matrix: https://docs.gradle.org/current/userguide/compatibility.html
- Firebase Android release notes: https://firebase.google.com/support/release-notes/android
- Firebase Apple SDK releases: https://github.com/firebase/firebase-ios-sdk/releases
- Google Play Billing release notes: https://developer.android.com/google/play/billing/release-notes
