# iOS bring-up plan

Written 2026-07-27, from a Windows machine, so **nothing here has been compiled**. Kotlin/Native
Apple targets require a macOS host with Xcode; that is a platform requirement, not a configuration
one. This document exists so day one on a Mac is execution rather than discovery.

## Where iOS actually stands

Better than "not started". The shared layer is essentially done:

| | |
|---|---|
| `expect` declarations with iOS `actual`s | **4 of 4** — none missing |
| iOS Kotlin files | 5 (`Platform.ios`, `IosPynapliDatabaseFactory`, `SessionTokenCipher.ios`, `MediaImageCodec.ios`, `IosSwipeFactory`) |
| Framework config | Done — `PynapliCompose`, static, `iosArm64` + `iosSimulatorArm64` |
| Shared UI | 100% reused; `composeApp` is already multiplatform |
| `iosApp/` | **README only — no Xcode project** |

What is missing is almost entirely the Swift/Xcode shell. `PynapliCommonGraph` (added 2026-07-27)
means iOS supplies a small seam and inherits ~20 state holders; before that it would have been a
third hand-written copy of the Android wiring.

## The seam iOS must supply

`PynapliCommonGraph` takes these; everything downstream of the API client comes free:

| Parameter | iOS answer |
|---|---|
| `apiClient` | Build as Android/Web do: `KtorPynapliApiClient` + `SessionRefresherHolder` + `StoredSessionRefresher` + `SessionInvalidationBus` |
| `gridSnapshotStore` | `SqlDelightGridSnapshotStore` over `IosPynapliDatabaseFactory` (already exists) |
| `swipeQueueStore` | `SqlDelightSwipeActionQueueStore` — `IosSwipeFactory` already does this |
| `swipeRepository` | `ApiSwipeRepository` (no debug fault injector needed) |
| `storePurchaseGateway` | **StoreKit 2 — new work.** Ship read-only first (`UnavailableStorePurchaseGateway`, as web does) and add purchases as a second slice |
| `presence/activity/message StreamClient` | **`buildIos*StreamClient` do not exist yet.** Android and Web have theirs; iOS needs the same three |

Then `PynapliApp(graph = PynapliAppGraph(...), platform = PynapliPlatform(...))` — the same two
objects the other two shells pass.

## Ordered plan

### 1. Xcode project (Mac, ~half a day)
Create `iosApp/iosApp.xcodeproj` **in Xcode**, not by hand. `project.pbxproj` is a generated bundle;
hand-authoring it is error-prone and unverifiable, and a subtly broken one costs more than it saves.
Add the `PynapliCompose` framework via the standard KMP integration, confirm `./gradlew
:composeApp:linkDebugFrameworkIosSimulatorArm64` succeeds, and get a blank Compose view on a
simulator before wiring anything else.

**Exit test:** the app launches on a simulator and renders any Compose content.

### 2. `IosAppGraph` + entry point (~1 day)
Mirror `AndroidAppGraph`: session store, API client, auth chain, then `PynapliCommonGraph` with the
seam above. Swift side is a thin `UIViewController` host. Write the three `buildIos*StreamClient`
builders alongside the Android ones in `shared/.../network/`.

**Exit test:** dev login reaches an authenticated Grid with seeded cards — the same first milestone
the web shell used.

### 3. Keychain session cipher (~half a day) — **security blocker, do not skip**
`SessionTokenCipher.ios.kt` currently returns `PassThroughSessionTokenCipher`, i.e. **session tokens
are stored unencrypted on iOS.** It is honestly commented as deferred, not hidden, but it must close
before any build reaches a real user. Android's equivalent is Keystore-backed and proven by
`android-secure-token-qa.ps1`, which asserts `enc1:` ciphertext on disk and no plaintext token JSON.

**Exit test:** port that QA's assertions — ciphertext present, no plaintext `"accessToken"` — against
the iOS container.

### 4. Platform bridges (~1–2 days)
Location (one-shot, matching the Android policy that `android-location-policy-check.ps1` enforces:
no background location, no continuous updates), photo picker + crop, APNs → the existing
`PushRegistrationCoordinator`, permission prompts, Firebase Apple SDK (Crashlytics, App Check).

### 5. StoreKit 2 (~2–3 days, can follow first release)
Implement `StorePurchaseGateway` against StoreKit 2 and verify receipts server-side. The server
already owns entitlement; this is the client half only. Shipping read-only first is legitimate — web
does exactly that.

### 6. App Store submission
Signing, capabilities, privacy nutrition labels, review notes. Budget real calendar time for review,
and note the content category makes a rejection round plausible.

## Honest estimate

**5–8 working days to a TestFlight build** with Store read-only, assuming no Xcode/toolchain
surprises — which is not a safe assumption for a first KMP iOS integration. Steps 1 and 2 carry the
risk; 3–5 are well-understood work against existing contracts.

## Do not do before the Mac arrives

- Hand-writing `project.pbxproj`.
- Writing large amounts of Swift or `iosMain` Kotlin blind. Nothing iOS-targeted can be compiled on
  Windows, so every line would be unverified — that is a liability delivered as progress.

The Kotlin surface is already complete and verified. The remaining work needs a compiler.
