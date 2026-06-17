# Pynapli Official Tech Reference Guide

Last updated: 2026-04-24

## Purpose

This document defines the official technical references Pynapli should use when making implementation, dependency, architecture, and compatibility decisions.

Core rule:

> Prefer official documentation and primary source release notes over blog posts, templates, outdated tutorials, or AI memory.

## Why This Matters

Pynapli is a Kotlin Multiplatform + Compose + Ktor product. The ecosystem moves quickly, especially around:

- Kotlin versions.
- Compose Multiplatform compatibility.
- Android Gradle Plugin compatibility.
- Gradle compatibility.
- Firebase SDK changes.
- Ktor client/server releases.
- Store billing requirements.

Using official sources keeps the build grounded and reduces the risk of stale, incompatible, or tutorial-driven architecture.

## Primary Kotlin Sources

Use these first:

- Kotlin home: https://kotlinlang.org/
- Kotlin documentation: https://kotlinlang.org/docs/home.html
- Kotlin Multiplatform: https://kotlinlang.org/docs/multiplatform.html
- Kotlin Multiplatform project structure: https://kotlinlang.org/docs/multiplatform/multiplatform-discover-project.html
- Kotlin Multiplatform compatibility guide: https://kotlinlang.org/docs/multiplatform/multiplatform-compatibility-guide.html
- Compose Multiplatform: https://kotlinlang.org/compose-multiplatform/
- Compose Multiplatform compatibility: https://kotlinlang.org/docs/multiplatform/compose-compatibility-and-versioning.html
- Compose Multiplatform new project guide: https://kotlinlang.org/docs/multiplatform/compose-multiplatform-new-project.html
- Kotlin Multiplatform samples: https://kotlinlang.org/docs/multiplatform/multiplatform-samples.html

## Ktor Sources

Use these for backend and shared client networking:

- Ktor documentation: https://ktor.io/docs/
- Ktor releases: https://ktor.io/docs/releases.html
- Ktor client platforms: https://ktor.io/docs/client-supported-platforms.html
- Ktor server: https://ktor.io/docs/server-create-a-new-project.html

## Android Sources

Use these for Android build, store, platform, and Compose behavior:

- Android Kotlin Multiplatform guidance: https://developer.android.com/kotlin/multiplatform
- Android Gradle Plugin releases: https://developer.android.com/build/releases/gradle-plugin
- Android Gradle Plugin 9.2 release notes: https://developer.android.com/build/releases/agp-9-2-0-release-notes
- Jetpack Compose documentation: https://developer.android.com/compose
- Android app quality: https://developer.android.com/quality
- Google Play Billing release notes: https://developer.android.com/google/play/billing/release-notes
- Google Play policy: https://support.google.com/googleplay/android-developer/answer/16070163

## iOS And Apple Sources

Use these for iOS shell, App Store, StoreKit, APNs, and privacy:

- Apple Developer documentation: https://developer.apple.com/documentation/
- App Store Review Guidelines: https://developer.apple.com/app-store/review/guidelines/
- StoreKit: https://developer.apple.com/storekit/
- APNs: https://developer.apple.com/notifications/
- Sign in with Apple: https://developer.apple.com/sign-in-with-apple/

## Firebase And Google Cloud Sources

Use these for Firebase support infrastructure and cloud deployment:

- Firebase documentation: https://firebase.google.com/docs
- Firebase Android release notes: https://firebase.google.com/support/release-notes/android
- Firebase Apple SDK releases: https://github.com/firebase/firebase-ios-sdk/releases
- Firebase Cloud Messaging: https://firebase.google.com/docs/cloud-messaging
- Firebase Crashlytics: https://firebase.google.com/docs/crashlytics
- Firebase App Check: https://firebase.google.com/docs/app-check
- Google Cloud Run: https://cloud.google.com/run/docs
- Cloud SQL for PostgreSQL: https://cloud.google.com/sql/docs/postgres
- Secret Manager: https://cloud.google.com/security/products/secret-manager

## Build And Dependency Sources

Use these for version compatibility:

- Gradle compatibility matrix: https://docs.gradle.org/current/userguide/compatibility.html
- Gradle releases: https://gradle.org/releases/
- Maven Central search: https://central.sonatype.com/
- Gradle Plugin Portal: https://plugins.gradle.org/
- Kotlin libraries catalog: https://klibs.io/

## Static Analysis And Formatting Tooling

Current decision (2026-05-31):

- **Spotless `8.6.0` + ktlint `1.7.1`** is the Kotlin format gate (`spotlessCheck`), wired into
  `scripts/verify-fast.ps1`/`scripts/verify-full.ps1` and CI, enforced on **all** Kotlin files. The codebase was formatted to
  `ktlint_official` in one pass (recorded in `.git-blame-ignore-revs`), so the earlier
  `ratchetFrom` incremental mode was removed. ktlint is configured by the root `.editorconfig`.
  The non-auto-fixable, stylistic ktlint rules (`max-line-length`, `property-naming`,
  `function-naming`, `filename`, `package-name`) are disabled and left to code review — they
  conflict with existing Kotlin/KMP conventions (e.g. long DTO type names, `Platform.android.kt`
  actuals) and ktlint cannot auto-fix them. Every auto-fixable formatting rule stays enforced.
- **detekt is intentionally deferred** (documented exception per the "Rules For Using References"
  policy above). As of 2026-05, no **stable** detekt release supports Kotlin 2.3 — only
  `2.0.0-alpha.x` — and the version policy disallows alpha dependencies without a written reason.
  Re-evaluate adding detekt once a stable Kotlin-2.3-compatible release ships. Compatibility table:
  https://detekt.dev/docs/introduction/compatibility/
- **Android Lint** (built into AGP, no extra dependency) is the planned static-analysis layer for
  the Android-targeted modules, gated via per-module `lint-baseline.xml` so only new issues fail.

## Security Sources

Use these for security requirements and review checklists:

- OWASP API Security Top 10: https://owasp.org/API-Security/editions/2023/en/0x11-t10/
- OWASP MASVS: https://mas.owasp.org/MASVS/
- OWASP ASVS: https://owasp.org/www-project-application-security-verification-standard/
- NIST SP 800-63B: https://pages.nist.gov/800-63-4/sp800-63b.html

## Rules For Using References

Before coding or upgrading:

- Check official docs for current setup and compatibility.
- Check release notes for breaking changes.
- Prefer stable versions.
- Record intentional exceptions in the relevant spec or pull request.
- Avoid copying architecture from random templates without validating against official docs.
- Use community posts only to understand tradeoffs, not as source of truth.

## AI Usage Rule

AI can suggest code and architecture, but official references win when there is a conflict.

If AI suggests:

- A dependency version.
- A Gradle plugin.
- A Compose API.
- A Ktor API.
- A Firebase module.
- A StoreKit or Play Billing behavior.

Verify against official documentation before treating it as correct.

## Related Specs

- [PROJECT_STRUCTURE_AND_DEPENDENCIES_SPEC.md](PROJECT_STRUCTURE_AND_DEPENDENCIES_SPEC.md)
- [PRODUCTION_READINESS_AND_QUALITY_SPEC.md](PRODUCTION_READINESS_AND_QUALITY_SPEC.md)
- [MOBILE_APP_ARCHITECTURE_SPEC.md](MOBILE_APP_ARCHITECTURE_SPEC.md)
- [BACKEND_ARCHITECTURE_SPEC.md](BACKEND_ARCHITECTURE_SPEC.md)
- [DEVOPS_INFRA_SETUP_SPEC.md](DEVOPS_INFRA_SETUP_SPEC.md)
