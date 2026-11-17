# Web PWA Phase Log (W0–W8)

Archived 2026-11-17 from `docs/WEB_PWA_GAMEPLAN.md`, which keeps the architecture and the window-size contract. Code comments cite the slice numbers below (for example "W2.1", "W3.2"). History only; not current guidance. Paths such as `IMPLEMENTATION_AUDIT.md` are relative to `docs/`.

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
| W6.2 | ✅ | First production distribution built + verified booting (`wasmJsBrowserDistribution` → served statically → app runs from the optimized bundle). **Measured v1 baseline: ~5.5 MB gzipped initial download** (two wasm modules 3.3 MB + 2.1 MB gz + 101 KB js; 16 MB raw). Accepted for v1 (downloads once; SW caches the shell on the deployed origin). Follow-ups logged: migrate off the pinned-1.7.3 `materialIconsExtended` (done 2026-11-10: icons vendored; the bundle barely moved because the optimizer already stripped unused icons), watch CMP wasm size work. First-paint: the boot screen is now the pulsing brand mark (reduced-motion respected) instead of bare text. Error/empty/loading states already ride the shared polish-pass primitives on every surface. |
| W6.3 | ✅→🔵 | Chrome (the automatable engine here) was driven end-to-end across W0–W5. `docs/WEB_BROWSER_QA_CHECKLIST.md` now carries the human half: the four automation-blind items (real-keyboard Enter/arrows, geolocation prompt, file picker) + Firefox/Safari/Edge smoke passes + Lighthouse on the deployed origin. WasmGC baseline documented (Chrome/Edge 119+, Firefox 120+, Safari 18.2+). |
| W6.4 | 🔵 | **Founder ops (the remaining gate to real users):** DNS `app.pynapli.com`, static hosting for `webApp/build/dist/wasmJs/productionExecutable` (SPA rewrite: serve `index.html` for unknown paths), deployed API + `PYNAPLI_CORS_ALLOWED_ORIGINS=https://app.pynapli.com`, TLS. Same bucket as the pending pynapli.com deploy. |

**v1 status: every codeable slice W0–W6.3 is COMPLETE.** Definition-of-done items that
remain are exactly the W6.4 ops list plus the human checklist above.

### Phase W7 — "Not a stretched phone app" hardening pass (🟢) — ✅ COMPLETE 2026-07-09

A full sweep for the founder's defining requirement (no horizontal-stretch layouts on any wide
surface), closing the gaps the phase work left in single-column surfaces, and one real deep-link
bug found while verifying. Verified in Chrome at Compact (390), Medium (800), and Expanded (1440),
plus `verify-web` + `:composeApp:desktopTest` green.

| Slice | What |
|------|------|
| W7.1 | Generalized the max-width primitive: `PynapliMaxWidthColumn` gained a `columnModifier` (so scroll/inset/padding act *within* the cap — for forms), and a new `PynapliContentWidth.Conversation = 720dp` token. All caps are no-ops below their width, so Compact is byte-for-byte unchanged. |
| W7.2 | Capped/centered every remaining single-column surface that had been stretching edge-to-edge on wide windows: **Auth** + **Onboarding** (`Form` 440, replacing the hand-rolled centering), **Conversation** (`Conversation` 720 — a readable chat column in the wide two-pane Messages instead of bubbles + composer stretched across the pane), **Profile Hub** and **Media Albums** (`Feed` 760, via `Modifier.align(CenterHorizontally).widthIn(max)` at their `ColumnScope` call sites — no wrapper/reindent). Activity (`WideFeed` 900) + Store (`Feed` 760) picked up the generalized primitive. |
| W7.3 | **Deep-link boot fix:** `index.html` loaded `pynapli.js` via a *relative* src, so any multi-segment deep link (e.g. `/messages/{threadId}`) resolved it to `/messages/pynapli.js` → 404 and the app never booted on refresh. Made the script src absolute (`/pynapli.js`), matching the already-absolute manifest/icon hrefs. Single-segment routes worked by luck before; nested routes now boot too. |
| W7.4 | Centered the `isCheckingSession` restore splash (was a bare top-left "Pynapli"); now a centered brand mark for the brief session-restore frame. |

### Phase W8 — Boot resilience: fail visibly, never silently (🟢) — ✅ COMPLETE 2026-07-28

`index.html` loaded the bundle with a bare `<script src>` and had **no failure path at all** — no
error handler, no timeout, no capability check, and nothing that ever removed the boot overlay. So
every way booting can fail looked identical to each other *and* to success-in-progress: a gold "P"
pulsing forever, with no message and no way out.

Not hypothetical. Kotlin/Wasm requires WebAssembly GC — per the official docs **Chrome 119+,
Firefox 120+, Safari 18.2+**, and for Safari older than 18.2 the docs say plainly *"not supported,
no workaround available."* Pynapli has no iOS app yet, so iOS visitors are precisely who the web app
exists to serve, and anyone on iOS 17 / 18.0 / 18.1 was getting the infinite splash. The marketing
site's "Create account on the web" CTA leads straight into it.

| Slice | What |
|------|------|
| W8.1 | **Capability probe** — a positive WasmGC check (a type section holding a zero-field struct; `0x5F` is the struct form GC introduced) gates the load and, when absent, shows "Pynapli needs a newer browser" with the exact minimum versions and **no** retry button, because reloading cannot help. Verified in node that `WebAssembly.validate` genuinely discriminates here: the GC module validates while an invalid type form and a bad magic number both return false, ruling out a blanket `true`. |
| W8.2 | **Error handlers + watchdog** — `script.onerror`, `window.error`, `unhandledrejection`, a 12s "still loading" reassurance, and a 45s give-up with **Try again**. This layer, not the probe, is what guarantees the outcome: if the probe is ever wrong, users still get a real error instead of a dead screen. |
| W8.3 | **Deterministic removal of the boot overlay.** Nothing removed it before — it merely ended up behind the Compose content, which is luck, not a contract. A `MutationObserver` now clears it and cancels the timers as soon as Compose mounts. |
| W8.4 | a11y + affordance: the boot block is `role="status" aria-live="polite"` so state changes are announced, the retry control is a real `<button>` at a ≥44px target, and the pulse stops on failure so "stalled" is visually distinct from "loading". |

**A regression caught before shipping, worth recording.** W8.3 first detected boot by watching for a
`<canvas>` inside `#pynapli-app`. Measured against the real bundle over CDP, that is wrong: **Compose
Multiplatform 1.11 mounts a `DIV` and the document contains no canvas element at all.** The observer
would never have fired, so the 45s watchdog would have painted a false "taking longer than expected"
error — on a full-viewport, click-catching overlay — over a perfectly working app. Reproduced
exactly that (`errorTextShown: "Pynapli is taking longer than expected…"` on a booted app) before
switching the check to "any element child", then re-verified: overlay removed, no false error at 50s.

**Tooling caveats that nearly hid all of this** (recorded because both fail *silently*):
`chrome --dump-dom` returns an **empty string** in this Chrome build, so every DOM assertion "passes"
against nothing — a live CDP `Runtime.evaluate` is the only trustworthy check here. And `PrintWindow`
capture of a Chrome window can come back blank for a page that is rendering fine, which briefly
looked like a broken boot screen that was never broken.

Verified via a standalone harness (`?mode=missing|throws|silent|ok|nogc`) driven by headless Chrome
with `--virtual-time-budget` to fast-forward the timers deterministically: all six states — no-GC,
404, exception, 12s slow notice, 45s give-up, and success — plus the real bundle end-to-end.

### Deferred (explicitly post-v1)

Stripe web payments, Web Push notifications, SQLDelight
web-worker cache, Compose-for-Web accessibility deep-pass beyond the Lighthouse gate (the a11y
semantics work from the mobile polish pass carries over where CMP web supports it), iOS parity
(separate phase).
