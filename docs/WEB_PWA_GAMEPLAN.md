# Web PWA Companion

The Pynapli companion PWA: Compose Multiplatform for Web (Kotlin/Wasm) at **app.pynapli.com**, for
members on desktop, laptop and tablet browsers. Phases W0–W8 are complete; their log, including the
slice numbers that code comments cite ("W2.1", "W3.2", ...), is in
[archive/WEB_PWA_PHASE_LOG.md](archive/WEB_PWA_PHASE_LOG.md).

The defining requirement: **the PWA must not look, feel, or act like a stretched-out mobile app.**
Every surface is designed per window size class. Large screens get desktop-native layouts
(multi-pane, pointer + keyboard, hover); phone-sized browsers match the mobile layouts.

## Founder decisions

1. **Scope**: the web is a full companion (2026-11-10). Auth/Onboarding, Grid, Swipe, Activity,
   Messages, Profile/Settings, Playing Tonight (`/tonight`, side panel beside the map on Medium+) and
   Psync (`/psync`, centered `PynapliContentWidth.Stage` column). Browser back closes Tonight/Psync.
2. **No web payments**: the web Store is read-only (current plan, linked-partner benefit, inventory
   balances, "purchase on the mobile app" guidance; `purchasesAvailable=false` hides every buy and
   Restore button). Stripe web checkout is a separate later initiative (server webhooks +
   entitlement reconciliation). The Store bottom tab is being replaced by paywall sheets plus a
   profile-menu row (2026-11-17); the read-only rule carries over.
3. **Domain**: the Astro marketing site keeps `pynapli.com`; the PWA has its own origin
   (`app.pynapli.com`), manifest scope, service worker and deploy. Marketing CTAs route through
   `web/src/lib/appLinks.ts`.
4. **Swipe on desktop is a desktop-native deck**: centered stage, arrow keys (← pass, → like,
   ↑ pineapple) routed through the same callbacks as the buttons, drag still works.

## Architecture (module ownership)

- `:shared` has a `wasmJs` target. DTOs, repositories, `PynapliApiClient` and validation reuse
  as-is; Ktor uses the JS engine. The session-token cipher is a documented pass-through on wasm
  (browsers have no app-controlled keystore; mitigations are CSP + no third-party JS except the
  sign-in providers below, which load only when switched on).
- `:composeApp` has a `wasmJs` target. All screens, the token design system and state wiring
  compile to web; `MediaImageCodec` uses the skia decode-then-downscale path.
- `:webApp` is the web mirror of `:androidApp`: `main()` + `ComposeViewport`, `index.html`, manifest
  + icons, service worker, `WebAppGraph`, and browser adapters. **No product rules here.**
- `:server` stays the product authority. Web-specific changes are limited to the CORS allowlist
  (`PYNAPLI_CORS_ALLOWED_ORIGINS`, local default `http://localhost:8081`) and first-frame stream
  auth (below). No authority moves client-side.

### Web platform adapters (in `:webApp`)

| Concern | Android | Web |
| --- | --- | --- |
| Session persistence | SQLDelight + KeyStore cipher | `LocalStorageSessionStore` (no SQLDelight driver on wasm) |
| Location | FusedLocation, while-in-use | Browser Geolocation, one-shot fix on explicit action, denied/unavailable states (`WebLocationAdapter`) |
| Media pick/crop | photo picker | `<input type=file>` → skia decode/crop (`WebMediaPicker`) |
| Presence | server-side | server-side; no client sends an HTTP heartbeat (`POST /v1/presence/heartbeat` was removed) |
| Realtime | WebSocket with auth header | Browsers can't set WebSocket headers, so streams accept one `StreamAuthFrameDto` first frame within 5s. **Never** put the token in a URL query. Tab wake (`visibilitychange`) refetches over HTTP. |
| Push | FCM | Deferred (Web Push is post-v1) |
| Deep links | Android intents | URL routes + history (`PynapliRoutes`, `WebUrlRouter`, webpack `historyApiFallback`); deep links queue until authenticated |
| Install | Play Store | Manifest + `beforeinstallprompt` + in-app install pill |

### Window size classes (the "not a stretched phone app" contract)

Material 3 breakpoints via `PynapliWindowSizeClass` (no new dependency):

- **Compact (< 600 dp)**: phone parity. Bottom nav, single pane, mobile layouts unchanged.
- **Medium (600–839 dp)**: nav rail replaces bottom nav; Grid densifies; sheets become centered
  dialogs (`PynapliWideSheetPresentation.CenteredDialog`).
- **Expanded (≥ 840 dp)**: nav rail + multi-pane. Messages = inbox + open conversation side by side;
  profile peek = right-anchored side panel (`SidePanel`) so the grid stays visible; Swipe = centered
  stage with keyboard hints; Activity = wider FOMO grid.
- Grid and Activity answer their **own pane width** (`BoxWithConstraints`), not the window: wider
  panes mean more tiles at phone density, never bigger tiles. Single-column surfaces cap their width
  with `PynapliMaxWidthColumn` / `PynapliContentWidth` tokens (caps are no-ops below their width).

Pointer, keyboard and text size on every width (members on a computer or tablet skew older):

- **Hand cursor** on everything clickable (`Modifier.handCursor`, `design/PynapliPointer.kt`). The
  token guard fails a clickable modifier or Material control without one; scrims
  (`indication = null`) are exempt.
- **Esc closes** Playing Tonight, Psync, the profile hub and albums (`Modifier.closeOnEscape`), only
  while the surface itself has focus, so Esc in a text field never drops an edit. Composer: Enter
  sends, Shift+Enter newline, Esc closes.
- **Browser font size is honored**: it becomes Compose `fontScale`, 1.0–1.5x
  (`pynapliTextScaleForBrowserFont`). Text grows, layout units don't; the Grid's wide-pane tile
  minimum grows with it, and pill labels never wrap.
- **Comfortable control sizes** on Medium+ (`design/PynapliWideControls.kt`): pills, chips and small
  icon buttons draw at 40dp. Phones are unchanged.

Design language is unchanged on web: same tokens, dark-first, dense, restrained gold. Desktop
layouts are compositions of existing surfaces, not restyles.

## Version-policy note

Compose Multiplatform for Web (Kotlin/Wasm) is **Beta** (still Beta at CMP 1.11.0). The repo is on
the stable CMP 1.11.0 release; the Beta *web target* is a founder-approved exception. Revisit when
JetBrains declares web stable. Kotlin/Wasm needs WebAssembly GC: Chrome/Edge 119+, Firefox 120+,
Safari 18.2+. `index.html` probes for WasmGC and shows a "needs a newer browser" message instead of
an endless splash; error handlers and a 45s watchdog guarantee a visible failure.

## Security and privacy on web

- **Opaque tokens, same server auth.** Access token in memory; refresh token in `localStorage`.
  `httpOnly` cookie sessions would be a server auth-model change and are out of scope. Mitigations:
  strict CSP (`default-src 'self'`; the only third-party scripts are Google Identity Services and
  Sign in with Apple JS, requested only when the server's social config switches that provider on),
  no token in URLs, server-side refresh
  rotation + revocation. A refresh is idempotent for 60 s: a tab that retries after a lost response,
  or that loses a refresh race to another tab, gets the same rotated session back instead of being
  signed out (`docs/API_CONTRACT_SPEC.md`, Auth sessions).
- **The service worker caches the app shell only**: never API responses, profile photos, or
  anything user-specific (shared/family computers).
- Client logging rules apply unchanged (no message bodies, private media URLs, exact location,
  sensitive tags).
- Age gate and Terms/Privacy acceptance are the same server-enforced onboarding.

### Response headers for `app.pynapli.com`

The app's `index.html` carries no CSP of its own (its inline boot scripts and the wasm bundle make a
meta policy easy to get wrong without a boot test). Set these on the hosting site for the app (a
second Firebase Hosting site's `firebase.json`, separate from the marketing site's, whose
`same-origin` COOP would break the sign-in popups):

```
Content-Security-Policy:
  default-src 'self';
  script-src 'self' 'wasm-unsafe-eval' 'sha256-<boot script 1>' 'sha256-<boot script 2>'
             https://accounts.google.com/gsi/client https://appleid.cdn-apple.com;
  style-src 'self' 'unsafe-inline' https://accounts.google.com/gsi/style;
  frame-src https://accounts.google.com/gsi/ https://appleid.apple.com;
  connect-src 'self' https://api.pynapli.com wss://api.pynapli.com https://storage.googleapis.com
              https://accounts.google.com/gsi/ https://appleid.apple.com;
  img-src 'self' data: blob: https://storage.googleapis.com;
  font-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'
Cross-Origin-Opener-Policy: same-origin-allow-popups
```

- The Google entries are from Google's setup guide (developers.google.com/identity/gsi/web/guides/
  get-google-api-clientid, "Content Security Policy" and "Cross Origin Opener Policy"): popup mode
  needs `same-origin-allow-popups`, or the popup opens blank.
- The Apple entries are **not** from an Apple document (Apple publishes none for Sign in with Apple
  JS): the script origin is the documented SDK URL, and `appleid.apple.com` is the authorization
  origin. Confirm both in the browser console (CSP violations are logged there) on the first live
  run, and add whatever the console reports.
- The two `sha256-` values are the hashes of `index.html`'s inline scripts; regenerate them on every
  edit of those scripts (see `web/HOSTING.md`, "regenerating the script hashes"). The media origin
  is the signed-URL bucket host; adjust if it differs.

## Open items

- Founder ops: DNS for `app.pynapli.com`, static hosting of
  `webApp/build/dist/wasmJs/productionExecutable` with SPA rewrite, deployed API with
  `PYNAPLI_CORS_ALLOWED_ORIGINS=https://app.pynapli.com`, TLS.
- Manual browser QA in [WEB_BROWSER_QA_CHECKLIST.md](WEB_BROWSER_QA_CHECKLIST.md).
- Post-v1: Stripe web payments, Web Push, SQLDelight web-worker cache, deeper web accessibility pass.
