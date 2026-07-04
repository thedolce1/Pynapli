# Web PWA Companion Gameplan

Last updated: 2026-07-03

## Purpose

A slice-by-slice plan to build the **Pynapli companion PWA** — Compose Multiplatform for Web
(Kotlin/Wasm) at **app.pynapli.com** — so members can use Pynapli from desktop, laptop, and tablet
browsers. This is the phase `ANDROID_PRODUCTION_GAMEPLAN.md` reserved ("Next phase after Android").

The defining product requirement, from the founder: **the PWA must not look, feel, or act like a
stretched-out mobile app.** Every surface is designed per window size class; large screens get
genuinely desktop-native layouts (multi-pane, pointer + keyboard, hover states), while phone-sized
browsers get parity with the mobile app's proven layouts.

## Founder decisions (locked 2026-07-03)

1. **V1 scope = core loop first**: Auth/Onboarding, Grid, Swipe, Activity, Messages,
   Profile/Settings, Store **read-only**. Playing Tonight + Psync follow as v1.x slices after the
   shell is proven.
2. **Web payments deferred**: the web Store shows current plan, linked-partner benefit, and
   inventory balances with "purchase on the mobile app" guidance. A Stripe web-checkout path is a
   separate later initiative (server webhooks + entitlement reconciliation).
3. **Domain = app.pynapli.com**: the Astro marketing site keeps `pynapli.com` (SEO); the PWA gets
   its own origin, manifest scope, service worker, and deploy. The marketing landing gains
   **Log in / Create account** CTAs that route to the app.
4. **Swipe exists on desktop as a desktop-native deck**: centered card stage sized to the viewport,
   keyboard shortcuts (←/→/↑ = pass/like/pineapple), click buttons, drag support.

## Architecture (module ownership)

Same KMP philosophy as Android — reuse everything that is already multiplatform, add a thin
platform shell:

- `:shared` — gains a `wasmJs` target. DTOs, repositories, `PynapliApiClient`, state holders,
  validation all reuse as-is. Ktor client uses the JS engine on wasm. Wasm actuals needed:
  `PlatformInfoProvider`, `createPlatformSessionTokenCipher` (documented pass-through, like
  desktop/iOS today — browsers have no app-controlled keystore; mitigations are CSP + no
  third-party JS, see Security).
- `:composeApp` — gains a `wasmJs` target. All screens, the token design system
  (`PynapliColors`/`PynapliDimens`/primitives/sheets), and state wiring compile to web. Wasm
  actuals for `decodePickedImageBitmap` / `encodeCroppedJpegBytes` reuse the skia
  (desktop/iOS-style) decode-then-downscale approach.
- `:webApp` — **new Gradle module**, the web mirror of `:androidApp`: `main()` +
  `ComposeViewport`, `index.html`, PWA manifest + icons, service worker, `WebAppGraph` (builds the
  same state-holder graph `AndroidAppGraph` builds), and the browser adapters — geolocation,
  localStorage session persistence, URL routing/history, install prompt, page visibility →
  presence. **No product rules here**, exactly like `:androidApp`.
- `:server` — stays the product authority, untouched except: CORS allowlist for the app origin
  (dev `localhost` port + `https://app.pynapli.com`), and any web-specific config. No new
  authority moves client-side.

### Web-only platform adapters (in `:webApp`)

| Concern | Android today | Web approach |
| --- | --- | --- |
| Session persistence | SQLDelight + KeyStore cipher | `LocalStorageSessionStore : SessionStore` (skip SQLDelight on wasm for v1 — the web-worker/sql.js driver adds weight for no v1 benefit) |
| Location | FusedLocation / while-in-use | Browser Geolocation API + graceful denied state (Grid already has a denied-location design) |
| Media pick/crop | photo picker + KeyStore | `<input type=file>` picker → skia decode/crop path |
| Presence | app lifecycle heartbeat | Page Visibility API + heartbeat while visible |
| Push | FCM | **Deferred** (Web Push is its own slice, post-v1) |
| Deep links | Android intents | URL routes + browser history (`/grid`, `/messages/{threadId}`, …) |
| Install | Play Store | PWA manifest + `beforeinstallprompt` capture + in-app install affordance |

### Window size classes (the "not a stretched phone app" contract)

Breakpoints follow Material 3 window size classes, implemented as a small `PynapliWindowSizeClass`
(no new dependency):

- **Compact (< 600 dp)** — phone parity: bottom nav, single pane, existing mobile layouts.
- **Medium (600–839 dp)** — nav rail replaces bottom nav; Grid densifies; sheets become centered
  dialogs where a full-width sheet would look absurd.
- **Expanded (≥ 840 dp)** — nav rail + **multi-pane**: Messages = inbox list + open conversation
  side by side; Grid = denser column count + profile detail as an anchored side panel (not a
  full-screen takeover); Swipe = centered deck stage with keyboard hints; Activity = wider FOMO
  grid. Hover states on interactive tiles; Enter-to-send; Esc-closes-pane.

Design language is unchanged: same tokens, dark-first, dense, restrained gold. Desktop layouts are
*compositions* of existing surfaces, not restyles.

## Version-policy note (load-bearing)

Compose Multiplatform for Web (Kotlin/Wasm) is **Beta** as of CMP 1.9→1.11 (verified against
JetBrains' official blog 2026-07-03; still Beta at 1.11.0). The stable-only policy is satisfied at
the framework level (CMP 1.10.3 is a stable release; we stay on the repo baseline), and the Beta
*web target* is a founder-blessed exception — the Wasm PWA has been the locked roadmap in
`CLAUDE.md` + `ANDROID_PRODUCTION_GAMEPLAN.md` since the initiative started. Revisit a CMP bump
when JetBrains declares web stable. Wasm-capable versions already pinned: Ktor 3.4.3, Coil 3.4.0,
Koin 4.2.1, kotlinx-* — no catalog changes expected beyond `ktor-client-js`.

## Security & privacy on web

- **Opaque tokens, same server auth.** Access token in memory; refresh token persisted in
  `localStorage` (no app-controlled keystore exists in browsers; `httpOnly` cookie sessions would
  be a server auth-model change — explicitly out of v1 scope). Mitigations: strict CSP
  (`default-src 'self'`, no third-party scripts at all), no token in URLs, server-side refresh
  rotation + revocation already exist.
- **Service worker caches the app shell only** — never API responses, profile photos, or anything
  user-specific (privacy: shared/family computers).
- Existing client logging rules apply unchanged (no message bodies, private media URLs, exact
  location, sensitive tags).
- Age gate + Terms/Privacy acceptance flows are the same server-enforced onboarding.

## Phases

Slice workflow unchanged: one feature branch per slice, local verify gate (`verify-fast` /
`verify-full` + the new `verify-web`), emulator/browser QA for UI slices, merge, update
`IMPLEMENTATION_AUDIT.md` + this doc. Pause for founder review at phase boundaries.

### Phase W0 — Foundation: it compiles, it signs in (🟢) — ✅ COMPLETE 2026-07-03

| Slice | What |
|------|------|
| W0.1 | ✅ `wasmJs` target on `:shared` (+ `ktor-client-js`, wasm actuals for `PlatformInfoProvider` / session-token cipher pass-through). SQLDelight runtime resolves for wasm; no driver is instantiated on web. |
| W0.2 | ✅ `wasmJs` target on `:composeApp` (+ skia wasm actuals for `MediaImageCodec`, same decode-then-downscale as desktop). All screens compile to wasm; Coil/Koin/icons/backhandler all resolve at pinned versions. |
| W0.3 | ✅ New `:webApp` module: `ComposeViewport` entry, `index.html` (dark boot screen), webpack dev server on :8081, `WebAppGraph` (full state-holder set over browser adapters: `LocalStorageSessionStore` / `LocalStorageAppSettingsStore` / `LocalStorageOnboardingProgressStore`, NoOp grid-snapshot + swipe-queue stores for now, `UnavailableStorePurchaseGateway`, random localStorage install id), origin-allowlisted CORS on `:server` (`PYNAPLI_CORS_ALLOWED_ORIGINS`, local default `http://localhost:8081`). **Proof of life passed: dev login → authenticated Grid (60 seeded cards, avatar photo over CORS, zero console errors) in real Chrome.** Settings repos moved to `PREFER_SETTINGS` + content-filtered Node/Yarn/Binaryen ivy repos (the Kotlin wasm toolchain adds project-level repos that `FAIL_ON_PROJECT_REPOS` rejects). |
| W0.4 | ✅ `scripts/verify-web.ps1` (spotless + 3-module wasm compile + shared JVM tests). CI wiring deferred while GitHub Actions billing is suspended (local gates are the merge gate). |

### Phase W1 — Responsive app shell + PWA plumbing (🟢) — ✅ COMPLETE 2026-07-03

| Slice | What |
|------|------|
| W1.1 | ✅ `PynapliWindowSizeClass` (M3 breakpoints 600/840dp from `LocalWindowInfo`, tracks live resizes) + adaptive shell: Compact keeps the phone bottom bar verbatim; Medium+ renders `PynapliNavigationRail` (same tabs/badges via one shared `PynapliTabIconWithBadge`, token-pure, `BorderSoft` hairline). Browser-verified incl. live rail↔bar swap on container resize. Tests: breakpoint unit test + rail desktop interaction test. |
| W1.2 | ✅ URL routing + history: outbound `PynapliApp.onNavigationRouteChanged` reports the canonical route (`pynapliRouteFor`; `PynapliTab`/`ProfileSurface` stay internal — only strings cross the module boundary); inbound paths ride the existing deep-link commands (new grid/swipe/store/profile targets), which queue until authenticated so deep links survive login. `WebUrlRouter` bridges pushState/replaceState/popstate (auth surfaces + `/` replace; in-app nav pushes). webpack `historyApiFallback` serves deep paths. Browser-proven: cold `/`→`/grid` (replace), tab→`/messages` (push), browser-back→`/grid` follows in-app, hard-load `/messages` lands on Messages. `PynapliRoutesTest` covers parse+format. |
| W1.3 | ✅ (routing part) `/login` + `/signup` are addressable; a signed-out hard-load of `/signup` starts the DOB-first onboarding stepper (verified in Chrome), an authenticated visitor lands normally. Browser-geolocation for the location step ships with W2.3; marketing-site CTAs remain W6.1. |
| W1.4 | ✅ Manifest (standalone, `#0B0B0E`, 192/512 + maskable icons generated from the brand mark), app-shell-only service worker (same-origin + shell-asset whitelist, navigate → cached-index offline fallback, **never** caches API/user data; registration skipped on localhost so dev bundles stay fresh), `beforeinstallprompt` captured pre-wasm + restrained in-app "Install Pynapli" pill (dismissal persists per browser). Pill verified live in Chrome. |

### Phase W2 — Discovery: Grid + profile detail (🟢) — ✅ COMPLETE 2026-07-03

| Slice | What |
|------|------|
| W2.1 | ✅ The grid answers its OWN pane width via `BoxWithConstraints` (not the window — correct beside rails/panels): panes <600dp keep the locked 3-up phone grid byte-for-byte; wider panes use `Adaptive(168dp)` so width means MORE tiles at phone density (7 columns at 1008dp), never bigger tiles. Pointer hover = quiet `OnPhotoHoverTint` brighten (new token). Header gains a Refresh button on Medium+ (`grid-refresh` tag) since mouse wheels can't pull-to-refresh; pull-to-refresh stays. Scroll restoration untouched (phase stays Content across peek open/close). |
| W2.2 | ✅ `PynapliBottomSheet` is now the adaptive host for EVERY modal: Compact keeps the bottom sheet; Medium+ mounts the same content per `PynapliWideSheetPresentation` — `CenteredDialog` (560dp max card, `DialogShape`, BorderSoft border) by default, `SidePanel` (420dp right-anchored inspector, full height, hairline divider) for the profile peek so the grid stays visible behind the scrim. `LocalPynapliSheetHost` lets `PynapliSheetScaffold` fill the panel fully. Browser-proven: peek inspector + scrim dismiss + filters-as-dialog. |
| W2.3 | ✅ Browser geolocation: `WebLocationAdapter` mirrors `MainActivity.requestCurrentLocation` (one-shot fix on explicit action, `markPermissionDenied`/`markUnavailable` states, movement-thresholded grid refresh); browser permission prompt is native (manual founder QA — automation can't grant it). Presence note: **no client on any platform calls `heartbeatPresence()`** — presence is server-side; web inherits parity with zero code, so the planned Page Visibility heartbeat was dropped as unnecessary. |

**W2 also forced a deliberate baseline bump: Compose Multiplatform 1.10.3 → 1.11.0 (stable).**
On 1.10.3 the wasm target dropped every click inside a scrollable container (grid tiles, quick-filter
pills, list rows) while non-scrollable targets worked — verified with two independent event-injection
paths; 1.11.0's reworked web pointer processing fixes it (release-notes item). Full verify gate re-run
on the bump.

### Phase W3 — Messages: the desktop win (🟢) — ✅ COMPLETE 2026-07-03

| Slice | What |
|------|------|
| W3.1 | ✅ Expanded windows get the two-pane messenger: 380dp inbox (New Matches rail included) + hairline divider + conversation pane, with a quiet `PynapliEmptyState` ("Your messages") when nothing is open. Compact/Medium keep the single-pane flow verbatim. Screen lambdas are single-sourced so the panes can't drift from the phone layout. Browser-proven: open-in-pane, inbox stays, thread URL deep link updates. |
| W3.2 | ✅ Realtime on web, same coordinators as Android. **Browsers can't set WebSocket headers**, so streams gained first-frame auth: client factories take `sendTokenAsFirstFrame` (web builders pass true; native header path unchanged), and the messages/presence/activity stream routes accept header OR one `StreamAuthFrameDto` frame within 5s (`awaitStreamToken` — never a URL param; query tokens would land in access logs). Coordinators start at page boot (clients idle on backoff until a token exists); tab wake (`visibilitychange`) refetches inbox + activity summary since HTTP stays canonical. Browser-proven: a message sent by another account landed in the open conversation + re-sorted the inbox with zero user action. Server tests: first-frame delivery + malformed-frame close. |
| W3.3 | ✅ Composer: Enter sends / Shift+Enter newline / Esc closes (hardware-key path via `onPreviewKeyEvent`; soft keyboards commit through the IME so phones are unchanged; NumPad Enter included). Desktop tests pin the behavior (`ConversationComposerKeyTest`). Photo attach: `WebMediaPicker` (browser file input → one base64 hop → `PickedMediaForUpload`) wired for conversation photos AND album/onboarding photos (`onPickMediaForAlbum`), matching `handlePickedMedia` on Android. **Manual founder QA note:** CDP-injected key events didn't reach the wasm key handler in automation — confirm Enter-to-send once with a real keyboard; the send button works regardless, and the same handler is desktop-test-green. |

### Phase W4 — Swipe: desktop-native deck (🟢) — ✅ COMPLETE 2026-07-03

| Slice | What |
|------|------|
| W4.1 | ✅ Pane-based centered stage (same `BoxWithConstraints` philosophy as the Grid): panes ≥600dp center the whole Swipe surface — header, pills, deck, action bar — at a 520dp max width instead of stretching one hero card across a monitor; phone-width panes are untouched. A restrained keyboard-hints row (icon glyphs + labels in `TextTertiary` — the bundled font has no arrow characters) sits under the action bar on wide panes only. Browser-proven. |
| W4.2 | ✅ Arrow keys drive the deck on wide panes: ← pass, → like, ↑ pineapple via `onPreviewKeyEvent` on the focused stage (focus requested whenever the wide stage appears), routed through the same callbacks as the action buttons so card fly-out animation, queueing, and the match overlay all behave identically; gated by the buttons' own enabled logic (`hasMore`, queue not full, not submitting). Pointer drag + match overlay + rewind already worked (existing `SwipeCardStack` gestures run on wasm since the CMP 1.11 pointer fix). Filters-as-dialog shipped with W2's adaptive sheets. `SwipeStageKeyTest` pins wide-pane key actions + compact-pane inertness. Same real-keyboard manual QA note as the composer. |

### Phase W5 — Activity, Profile/Settings, read-only Store (🟢) — ✅ COMPLETE 2026-07-03

| Slice | What |
|------|------|
| W5.1 | ✅ The FOMO grid answers its pane width (`BoxWithConstraints`): <600dp keeps the locked 2-up layout; wider panes go `Adaptive(200dp)` so a desktop window shows more of the tease at the same tile scale. Four tabs + Requests list unchanged (they already worked on wasm). Browser-proven: three flirt tiles at proper scale. |
| W5.2 | ✅ Albums/onboarding photo upload already worked via W3's `WebMediaPicker` (browser file input → crop → upload). The web-appropriate settings fix: the "Discreet app icon" toggle is Android-only (launcher aliases), so `discreetIconSupported` threads `PynapliApp → ProfileHubScreen → AppSettingsContent` and web passes false — no toggle that silently does nothing (iOS can reuse the same flag later). |
| W5.3 | ✅ Read-only Store (founder-locked): `purchasesAvailable=false` on web hides the header Restore and every Choose/price buy button (product prices remain as plain text), and a new `StoreManageOnMobileRow` ("Purchases live in the mobile app · your plan and balances stay in sync here") sits right under the current plan — same accent-disc callout language as the linked-partner benefit row. Catalog, current plan, entitlement source, and balances all stay visible. Browser-proven: callout + no Restore + Included chip intact. Android untouched (defaults true). |

### Phase W6 — Polish, landing integration, launch QA (🟢 + 🔵) — 🟢 codeable COMPLETE 2026-07-04

| Slice | Track | What |
|------|-------|------|
| W6.1 | ✅ | Marketing site: header gains a quiet **Log in** link and the hero gains a ghost **"Create account on the web"** beside the primary waitlist CTA (positioning preserved per the founder call). Both route through one `web/src/lib/appLinks.ts` (`https://app.pynapli.com` — dead until W6.4 ships; site + app deploy together). Astro build green. |
| W6.2 | ✅ | First production distribution built + verified booting (`wasmJsBrowserDistribution` → served statically → app runs from the optimized bundle). **Measured v1 baseline: ~5.5 MB gzipped initial download** (two wasm modules 3.3 MB + 2.1 MB gz + 101 KB js; 16 MB raw). Accepted for v1 (downloads once; SW caches the shell on the deployed origin). Follow-ups logged: migrate off the pinned-1.7.3 `materialIconsExtended` (known-large), watch CMP wasm size work. First-paint: the boot screen is now the pulsing brand mark (reduced-motion respected) instead of bare text. Error/empty/loading states already ride the shared polish-pass primitives on every surface. |
| W6.3 | ✅→🔵 | Chrome (the automatable engine here) was driven end-to-end across W0–W5. `docs/WEB_BROWSER_QA_CHECKLIST.md` now carries the human half: the four automation-blind items (real-keyboard Enter/arrows, geolocation prompt, file picker) + Firefox/Safari/Edge smoke passes + Lighthouse on the deployed origin. WasmGC baseline documented (Chrome/Edge 119+, Firefox 120+, Safari 18.2+). |
| W6.4 | 🔵 | **Founder ops (the remaining gate to real users):** DNS `app.pynapli.com`, static hosting for `webApp/build/dist/wasmJs/productionExecutable` (SPA rewrite: serve `index.html` for unknown paths), deployed API + `PYNAPLI_CORS_ALLOWED_ORIGINS=https://app.pynapli.com`, TLS. Same bucket as the pending pynapli.com deploy. |

**v1 status: every codeable slice W0–W6.3 is COMPLETE.** Definition-of-done items that
remain are exactly the W6.4 ops list plus the human checklist above.

### Deferred (explicitly post-v1)

Playing Tonight + Psync surfaces on web, Stripe web payments, Web Push notifications, SQLDelight
web-worker cache, Compose-for-Web accessibility deep-pass beyond the Lighthouse gate (the a11y
semantics work from the mobile polish pass carries over where CMP web supports it), iOS parity
(separate phase).

## Definition of done (v1)

- A first-time visitor on a desktop browser can create an account (full onboarding incl. age gate),
  set location via browser geolocation, browse a dense desktop Grid, open profiles in a side panel,
  swipe with arrow keys, see Activity, hold a two-pane conversation with realtime updates, edit
  their profile + photos, and see their plan in a read-only Store — with zero horizontal-stretch
  phone layouts on any surface ≥ 840 dp wide.
- The same app on a phone browser matches the mobile app's layout patterns.
- Installable as a PWA (manifest + offline shell); refresh/back/deep links never lose state
  incorrectly; strict CSP; service worker never caches user data.
- `verify-web` green in the local gate; browser QA matrix documented with artifacts.
- Marketing site routes Log in / Create account to the app.
