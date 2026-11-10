# Web PWA — Browser QA Checklist (v1)

Last updated: 2026-07-04. Companion to `WEB_PWA_GAMEPLAN.md` W6.3.

Chrome (desktop, wide + phone viewport) was driven end-to-end by automation throughout
W0–W5 — login, Grid, inspector peek, filters dialog, two-pane Messages with live
realtime delivery, Swipe stage, Activity, read-only Store, URL routing, install pill.
The rows below are what automation could NOT drive (real keyboard, permission prompts,
other browser engines) plus a per-browser smoke pass. Run against a local dev serve
(`:server:run` + `:webApp:wasmJsBrowserDevelopmentRun`, http://localhost:8081) or the
deployed app once W6.4 lands.

## Wasm GC baseline (why old browsers show a blank page)

Compose-for-Web needs WasmGC: Chrome/Edge 119+, Firefox 120+, Safari 18.2+. Older
browsers fail at load — v1 accepts this (Beta target); a friendly "browser too old"
message is a candidate follow-up if analytics show it matters.

## Real-keyboard pass (any one browser, 5 minutes) — automation-blind spots

- [ ] Messages composer: **Enter** sends; **Shift+Enter** inserts a newline; **Esc**
      closes the open conversation. (Handler is desktop-test-green; CDP-injected keys
      never reached the wasm handler, so this needs one human tap.)
- [ ] **Esc** closes Playing Tonight (moon button), Psync and the profile screen; Esc while
      typing in a profile field does NOT close it. (Browser-verified via CDP after a click
      gives the page focus; confirm once with a real keyboard.)
- [ ] Browser font size **Large** (Chrome: Settings > Appearance > Font size), then reload:
      text grows, Grid names are not cut off, Tonight's filter pills stay on one line.
- [ ] Swipe (window ≥ 950px): **←** passes, **→** likes, **↑** pineapples; hints row
      shows under the action bar; actions animate exactly like the buttons.
- [ ] "Use current location" on the Grid: browser permission prompt appears; Allow
      loads nearby profiles; Deny shows the denied-location message (not a spinner).
- [ ] Conversation photo button: file picker opens; a picked JPEG sends through the
      consent-tap flow. Profile → Manage photos: picked photo reaches the crop step.

## Per-browser smoke (each of Firefox / Safari / Edge, ~5 minutes each)

- [ ] App loads to Sign in (boot mark → auth screen, no blank page / console errors).
- [ ] Log in; Grid renders dense columns; tile click opens the side-panel peek;
      scrim click dismisses.
- [ ] Messages two-pane on a wide window; open a conversation; send a message.
- [ ] Realtime: send yourself a message from a second browser/account — it appears
      in the open conversation without a refresh (proves WebSocket first-frame auth
      on that engine).
- [ ] Refresh on a deep URL (`/messages`, `/swipe`) lands on the right surface;
      browser Back mirrors in-app navigation.
- [ ] Phone-sized window: bottom tab bar replaces the rail; layouts match the
      mobile app.
- [ ] Install: browser offers install (omnibox icon or the in-app pill); installed
      window opens standalone with the gold-P icon. (Safari: Add to Dock / Home
      Screen path.)

## Lighthouse (Chrome DevTools, deployed origin)

- [ ] PWA installability checks pass (manifest + service worker + icons).
- [ ] Accessibility ≥ 90 (canvas apps under-report — verify no regressions vs. the
      score at v1 sign-off rather than chasing absolutes).
- [ ] First load on "Fast 4G" throttling shows the boot mark, not a white page,
      until first frame.

## Known/accepted v1 limitations

- Wasm bundle size: see the budget note in `WEB_PWA_GAMEPLAN.md` W6.2.
- No Web Push (post-v1), no Playing Tonight / Psync surfaces (v1.x), Store is
  read-only (founder-locked).
- Service worker registers only off-localhost, so install/offline behaviors need the
  deployed origin (or a local non-localhost host alias) to test.
