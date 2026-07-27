# iOS bring-up plan

Written 2026-07-27 from a Windows machine. This document exists so day one on a Mac is execution
rather than discovery.

## What a non-Mac host can and cannot do (measured, not assumed)

An earlier draft of this file said no iOS code could be built on Windows. That was wrong, and the
distinction matters:

| Task | Result on Windows |
|---|---|
| `compileKotlinIosSimulatorArm64` | **Works.** Produces a real klib; iOS Kotlin is fully type-checked. Verified by injecting a deliberate type error and watching the compiler reject it with correct line numbers. |
| `linkDebugFrameworkIosSimulatorArm64` | **Reports BUILD SUCCESSFUL and produces nothing.** The link task silently does not run; no `.framework` appears on disk. |

That second row is a trap: a green build that did no work. Do not read "BUILD SUCCESSFUL" on any
`link*` task from a non-Mac host as evidence of anything.

So `iosMain` Kotlin can be written and type-checked here; frameworks, the Xcode project, simulator
and device builds, `codesign`, and submission all require macOS. Steps 2 and 4 are therefore already done on the Kotlin side.

## Where iOS actually stands

Better than "not started". The shared layer is essentially done:

| | |
|---|---|
| `expect` declarations with iOS `actual`s | **4 of 4** — none missing |
| iOS Kotlin files | 9 — the original 5 plus `IosStreamClients`, `IosAppGraph`, `IosPynapliEntry`, `IosPushRegistration` (all 2026-07-27) |
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
| `presence/activity/message StreamClient` | **Done** — `buildIos*StreamClient` in `IosStreamClients.kt` (Darwin engine) |

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

### 2. `IosAppGraph` + entry point — **Kotlin side DONE 2026-07-27**
Written and type-checked on Windows:
- `shared/src/iosMain/.../network/IosStreamClients.kt` — the four `buildIos*StreamClient` builders
  (Darwin engine; header auth like Android, not the web first-frame path).
- `composeApp/src/iosMain/.../IosAppGraph.kt` — session/auth chain plus `PynapliCommonGraph`,
  exposing one `pynapliAppGraph` property.
- `composeApp/src/iosMain/.../IosPynapliEntry.kt` — **the entry point is Kotlin.**
  `ComposeUIViewController` builds the root `UIViewController`, so Swift never touches Compose.

Remaining on the Mac is the Swift shell and `installId` — a random UUID persisted in the Keychain,
deliberately **not** `identifierForVendor` or any hardware identifier, matching the Android choice
in `AndroidInstallId.kt`.

**Reference Swift — UNCOMPILED, never run.** Written from the Kotlin signatures as a starting point,
not as known-good code. Expect to adjust it against what Xcode actually generates:

```swift
// PynapliApp.swift
import SwiftUI
import PynapliCompose

final class AppState {
    let graph: IosAppGraph
    init() {
        let config = PynapliConfig(apiBaseUrl: "https://api.pynapli.com",
                                   websocketBaseUrl: "wss://api.pynapli.com",
                                   environment: .prod)
        graph = IosPynapliEntry().createGraph(config: config, installId: KeychainInstallId.value())
    }
}

struct ComposeRoot: UIViewControllerRepresentable {
    let state: AppState
    func makeUIViewController(context: Context) -> UIViewController {
        IosPynapliEntry().createViewController(
            graph: state.graph,
            onUseCurrentLocation: { LocationBridge.shared.requestOneShotFix(state.graph) },
            onPickMediaForAlbum: { kind in PhotoPicker.shared.present(for: kind) },
            onPickConversationPhoto: { PhotoPicker.shared.presentForConversation() },
            storePurchasesAvailable: false,
            testerDiagnosticLabel: nil)
    }
    func updateUIViewController(_ vc: UIViewController, context: Context) {}
}
```

Note the Kotlin object maps to `IosPynapliEntry()` in Swift, and default arguments do not cross the
interop boundary — pass every parameter explicitly.

**Exit test:** dev login reaches an authenticated Grid with seeded cards — the same first milestone
the web shell used.

### 3. Keychain session cipher (~half a day) — **security blocker, do not skip**
`SessionTokenCipher.ios.kt` returns `PassThroughSessionTokenCipher`, i.e. **session tokens are stored
unencrypted on iOS.** Commented as deferred rather than hidden, but it must close before any build
reaches a real user. Android's is Keystore-backed and proven by `android-secure-token-qa.ps1`, which
asserts `enc1:` ciphertext on disk and no plaintext token JSON.

**This was deliberately NOT written on Windows**, unlike the rest of step 2. Cryptographic code that
compiles but has never been executed is worse than a documented gap — it looks solved. Type-checking
proves the signatures line up; it proves nothing about whether `SecItemAdd` succeeds or the
ciphertext round-trips.

Recommended design, to keep the Mac work short and avoid hand-rolled crypto: **do not implement AES
in Kotlin/Native.** Instead let the Keychain be the secret store — `encrypt(plaintext)` writes the
value under a fresh random handle with `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly` and returns
`kc1:<handle>`; `decrypt("kc1:<handle>")` reads it back and returns null on any miss. The database
then holds only an opaque handle. This satisfies the same property the Android QA asserts (no
plaintext token JSON at rest) using the platform's own secure store rather than custom cryptography.

**Exit test:** port that QA's assertions — no plaintext `"accessToken"` in the container, value
restores across a cold launch, and returns null (signed-out, never a crash) after the Keychain item
is deleted.

### 4. Platform bridges (~1–2 days) — **push Kotlin side DONE 2026-07-27**
Already written and type-checked: `composeApp/src/iosMain/.../notifications/IosPushRegistration.kt`
— `IosPushTokenProvider` (Swift feeds it the APNs token from
`didRegisterForRemoteNotificationsWithDeviceToken`) and `IosPushRegistrationStore` (NSUserDefaults,
not Keychain: a device token is not a secret and should die with an uninstall, matching Android's
SharedPreferences choice).

Remaining, all Swift: CoreLocation one-shot fix (matching the policy
`android-location-policy-check.ps1` enforces — no background location, no continuous updates),
`PHPickerViewController` for photos, APNs registration, permission prompts, and the Firebase Apple
SDK for Crashlytics and App Check.

CoreLocation was deliberately not written in Kotlin: the delegate/threading model is exactly the kind
of interop that compiles cleanly and misbehaves at runtime, and it is short in Swift.

### 5. StoreKit 2 (~2–3 days, can follow first release)
Implement `StorePurchaseGateway` against StoreKit 2 and verify receipts server-side. The server
already owns entitlement; this is the client half only. Shipping read-only first is legitimate — web
does exactly that.

### 6. App Store submission
Signing, capabilities, privacy nutrition labels, review notes. Budget real calendar time for review,
and note the content category makes a rejection round plausible.

## Honest estimate

**3–6 working days to a TestFlight build** with Store read-only, assuming no Xcode/toolchain
surprises — not a safe assumption for a first KMP iOS integration. Step 1 carries most of the
remaining risk. Everything Kotlin in steps 2 and 4 is written and compiler-verified; the entry point
being Kotlin (`ComposeUIViewController`) means the Swift shell is roughly the snippet above plus the
bridges, rather than a UI layer.

Kotlin written pre-Mac, all type-checked by `compileKotlinIosSimulatorArm64`: 4 stream-client
builders, `IosAppGraph`, `IosPynapliEntry`, `IosPushTokenProvider`, `IosPushRegistrationStore`.
None of it has ever executed.

## Do not do before the Mac arrives

- Hand-writing `project.pbxproj`. It is a generated bundle; a subtly broken one costs more than it
  saves.
- Writing Swift blind. Unlike `iosMain` Kotlin, none of it can be compiled here.
- Implementing the session cipher blind — see step 3. "It compiles" is not adequate assurance for
  cryptography or Keychain interop.

Everything that a compiler can check has been done. What remains needs a device, a linker, or both.
