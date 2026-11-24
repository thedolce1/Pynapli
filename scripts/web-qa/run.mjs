// Web companion QA: drives the production Wasm bundle in headless Chrome at desktop, tablet and phone
// sizes and records pass/fail checks plus a screenshot per stage. Run it through scripts/web-qa.ps1,
// which builds, serves, seeds and checks the screenshots against scripts/web-qa-goldens.json.
//
// usage: node scripts/web-qa/run.mjs --out <dir> [--app http://localhost:8081] [--api http://localhost:8080]
//                                    [--viewports desktop,tablet,phone] [--auth-walk on|off|only]
//
// --auth-walk (default on) adds the sign-up + sign-in walk (authWalk below): a brand-new account per run,
// needs the server's dev sign-up shortcuts for the emailed code, and reports time to first card.
//
// Navigation goes through URLs (deep links and browser history), not click coordinates, so a layout
// change cannot make it flaky. The two exceptions (hand cursor, and opening Tonight with a click then
// closing it with Esc) run at desktop size only, against header buttons anchored to the right edge.
import { writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { launch, sleep } from './browser.mjs';

const args = Object.fromEntries(process.argv.slice(2).reduce((pairs, value, i, all) => (value.startsWith('--') ? [...pairs, [value.slice(2), all[i + 1]]] : pairs), []));
const out = args.out ?? 'artifacts/web-qa';
const app = (args.app ?? 'http://localhost:8081').replace(/\/$/, '');
const api = (args.api ?? 'http://localhost:8080').replace(/\/$/, '');
const VIEWPORTS = { desktop: [1440, 900], tablet: [820, 1180], phone: [390, 844] };
const viewports = (args.viewports ?? 'desktop,tablet,phone').split(',');
const PASSWORD = 'PynapliDevPass123!';
const BOOT_MS = 9000; // Wasm boot + session restore + first fetch on a cold profile
const SETTLE_MS = 3000;

const checks = [];
const screenshots = [];
const check = (name, pass, detail = '') => {
  checks.push({ name, pass, detail });
  console.log(`${pass ? 'PASS' : 'FAIL'} ${name}${detail ? ` (${detail})` : ''}`);
};
/**
 * A bug reproduced on purpose (onboarding + first grid plan, Section 0) and not fixed yet: logged as
 * KNOWN and kept out of the failure count. When it starts passing, the slice that fixed it turns this
 * back into a plain check().
 */
const knownBug = (name, pass, detail = '', fixedIn = '') => {
  if (pass) {
    console.log(`PASS ${name} (now fixed: make it a plain check)`);
    checks.push({ name, pass: true, detail });
  } else {
    console.log(`KNOWN ${name}${detail ? ` (${detail})` : ''}${fixedIn ? ` - fixed in ${fixedIn}` : ''}`);
  }
};

/** One login per account for the whole run: the server rate-limits logins. */
const sessions = {};
async function sessionFor(email) {
  if (sessions[email]) return sessions[email];
  const response = await fetch(`${api}/v1/auth/email/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email, password: PASSWORD, platform: 'web', appVersion: '0.1.0', deviceId: 'web-qa' }),
  });
  const body = await response.json();
  if (!body.accessToken) throw new Error(`Login failed for ${email} (HTTP ${response.status}). Run seed-dev-data.ps1 and clear rate limits.`);
  sessions[email] = JSON.stringify({ accessToken: body.accessToken, refreshToken: body.refreshToken, userId: body.user.userId, userStatus: body.user.status });
  return sessions[email];
}

/** A signed-in browser at [route]: the session is seeded the way LocalStorageSessionStore stores it. */
async function signedIn(email, viewport, route, { defaultFontPx } = {}) {
  const [width, height] = VIEWPORTS[viewport];
  const browser = await launch({ width, height, defaultFontPx });
  await browser.send('Browser.grantPermissions', { origin: app, permissions: ['geolocation'] }).catch(() => {});
  await browser.send('Emulation.setGeolocationOverride', { latitude: 37.7749, longitude: -122.4194, accuracy: 20 });
  await browser.navigate(`${app}/`, 1500);
  await browser.evaluate(`localStorage.setItem('pynapli.auth.session.v1', ${JSON.stringify(await sessionFor(email))}); 1`);
  await browser.navigate(app + route, BOOT_MS);
  return browser;
}

async function shot(browser, viewport, stage) {
  const file = join(out, `${viewport}-${stage}.png`);
  await browser.screenshot(file);
  screenshots.push({ viewport, stage, file });
}

function noConsoleErrors(browser, label) {
  check(`${label}: no console errors`, browser.problems.length === 0, [...new Set(browser.problems)].slice(0, 3).join(' | '));
}

async function surfaces(viewport) {
  const browser = await signedIn('dev@pynapli.local', viewport, '/grid');
  try {
    const at = await browser.waitForPath('/grid');
    check(`${viewport}: /grid loads signed in`, at === '/grid', `pathname=${at}`);
    await shot(browser, viewport, 'grid');
    for (const route of ['/swipe', '/activity', '/messages', '/profile', '/store']) {
      await browser.pushRoute(route);
      const got = await browser.waitForPath(route);
      check(`${viewport}: ${route} opens`, got === route, `pathname=${got}`);
      await sleep(SETTLE_MS);
      await shot(browser, viewport, route.slice(1));
    }

    // Playing Tonight is a full-screen overlay with its own route: history opens and closes it.
    await browser.pushRoute('/grid');
    await browser.waitForPath('/grid');
    await browser.pushRoute('/tonight');
    const opened = await browser.waitForPath('/tonight');
    check(`${viewport}: /tonight opens Playing Tonight`, opened === '/tonight', `pathname=${opened}`);
    await sleep(SETTLE_MS);
    await shot(browser, viewport, 'tonight');
    await browser.back();
    const closed = await browser.waitForPath('/grid');
    check(`${viewport}: browser back closes Tonight`, closed === '/grid', `pathname=${closed}`);

    if (viewport === 'desktop') {
      const [width] = VIEWPORTS.desktop;
      // Grid header actions are right-aligned: refresh, then the moon (Tonight), then filters.
      const refresh = await browser.cursorAt(width - 160, 34);
      check('desktop: hand cursor over a header button', refresh === 'pointer', `cursor=${refresh}`);
      const label = await browser.cursorAt(Math.round(width / 2), 138);
      check('desktop: arrow over non-clickable text', label !== 'pointer', `cursor=${label}`);

      // Open Tonight the way a user does (this click doubles as giving the page keyboard focus).
      await browser.send('Input.dispatchMouseEvent', { type: 'mousePressed', x: width - 100, y: 34, button: 'left', clickCount: 1 });
      await browser.send('Input.dispatchMouseEvent', { type: 'mouseReleased', x: width - 100, y: 34, button: 'left', clickCount: 1 });
      const viaClick = await browser.waitForPath('/tonight');
      check('desktop: the moon button opens Tonight', viaClick === '/tonight', `pathname=${viaClick}`);
      await sleep(1500);
      for (const type of ['rawKeyDown', 'keyUp']) {
        await browser.send('Input.dispatchKeyEvent', { type, key: 'Escape', code: 'Escape', windowsVirtualKeyCode: 27, nativeVirtualKeyCode: 27 });
      }
      const afterEsc = await browser.waitForPath('/grid');
      check('desktop: Esc closes Tonight', afterEsc === '/grid', `pathname=${afterEsc}`);
    }
    noConsoleErrors(browser, `${viewport} surfaces`);
  } finally {
    await browser.close();
  }

  // A bookmark straight to the overlay.
  const direct = await signedIn('dev@pynapli.local', viewport, '/tonight');
  try {
    const at = await direct.waitForPath('/tonight');
    check(`${viewport}: a bookmarked /tonight loads Tonight`, at === '/tonight', `pathname=${at}`);
  } finally {
    await direct.close();
  }
}

async function psync(viewport) {
  // Maya is linked to Alex in the dev seed, so Psync has a partner.
  const browser = await signedIn('maya@pynapli.local', viewport, '/swipe');
  try {
    await browser.waitForPath('/swipe');
    await browser.pushRoute('/psync');
    const opened = await browser.waitForPath('/psync');
    check(`${viewport}: /psync opens Psync`, opened === '/psync', `pathname=${opened}`);
    await sleep(SETTLE_MS);
    await shot(browser, viewport, 'psync');
    const socket = browser.sockets.some(url => url.endsWith('/v1/psync/stream'));
    check(`${viewport}: Psync opens its live stream`, socket, [...new Set(browser.sockets)].map(u => u.replace(/^.*\/v1\//, '')).join(', '));
    await browser.back();
    const closed = await browser.waitForPath('/swipe');
    check(`${viewport}: browser back closes Psync`, closed === '/swipe', `pathname=${closed}`);
    noConsoleErrors(browser, `${viewport} psync`);
  } finally {
    await browser.close();
  }
}

async function largeText() {
  // Chrome "Very large" (24px): the app should scale text 1.5x and give the Grid fewer, larger tiles.
  const browser = await signedIn('dev@pynapli.local', 'desktop', '/grid', { defaultFontPx: 24 });
  try {
    const px = await browser.defaultFontPx();
    check('large text: browser font setting applied', px === 24, `font-size:medium=${px}px`);
    await browser.waitForPath('/grid');
    await sleep(SETTLE_MS);
    await shot(browser, 'desktop', 'grid-large-text');
    await browser.pushRoute('/tonight');
    await browser.waitForPath('/tonight');
    await sleep(SETTLE_MS);
    await shot(browser, 'desktop', 'tonight-large-text');
    noConsoleErrors(browser, 'large text');
  } finally {
    await browser.close();
  }
}

// ---------------------------------------------------------------------------------------------------
// Sign-up + sign-in walk (onboarding + first grid plan, Section 0). Drives the REAL UI: landing ->
// birthday -> policies -> email + password -> emailed code (from the local-only dev lookup) ->
// location (granted, inside the seeded area) -> profile (skipped) -> grid cards, then Log out from the
// Profile hub and sign back in on the same page, asserting the grid again.
//
// Compose draws into a canvas, so there is no DOM to query. Controls are found by the mouse cursor
// they set, which the token guard makes reliable: every clickable carries handCursor ('pointer') and
// Compose text fields show the I-beam ('text'). A scan down the form's centre line lists the fields and
// buttons in layout order; "wide" buttons (full-width primaries) also show the pointer 100 px either
// side of centre, which TextButton links do not. Each step is then confirmed by what the app sent and
// got back (CDP Network), the route, the stored session and the perf marks, never by pixels.
//
// Every layout assumption lives in AUTH_WALK below. If the onboarding layout changes, fix it there.
const AUTH_WALK = {
  viewport: 'desktop', // nav rail instead of a bottom bar, so the hub's last row is Log out
  probeStepPx: 8, // vertical scan resolution (fields ~56 px, buttons ~40 px tall)
  probeSettleMs: 30, // wait after a mouse move before reading the cursor
  // A full-width button (form max width 440 dp less 2 x 24 dp padding: ~196 px either side of centre)
  // still shows the pointer this far out; centred TextButton links (~125 px half-width) do not.
  wideOffsetPx: 160,
  // Controls closer together than probeStepPx merge into one region, so clicks aim this far inside
  // a region's first (or last) edge rather than at its middle.
  edgeInsetPx: 14,
  stepTimeoutMs: 20000,
  // The seeded area (scripts/seed-dev-data.ps1, the dev viewer's spot). Not the San Francisco fix
  // signedIn() uses: a new member's grid must find the seeded profiles.
  latitude: 37.422,
  longitude: -122.0841,
  birthday: ['01', '06', '2000'], // DD, MM, YYYY (each field hands focus to the next when full)
  password: 'WebQaSignupPass123!',
  seededNames: ['Seb Dev', 'Maya', 'Alex', 'Nico', 'Jules', 'Kai', 'Rae', 'Theo', 'Noa'],
  minNamedCards: 6,
};

/** Reads the cursor at (x, y) after hovering there: 'pointer' (clickable), 'text' (field), or other. */
async function probe(browser, x, y) {
  const cursor = await browser.cursorAt(x, y, AUTH_WALK.probeSettleMs);
  return cursor === 'pointer' || cursor === 'text' ? cursor : 'none';
}

/** Interactive regions down the vertical line at [x], top to bottom: { kind, top, bottom, y }. */
async function scanColumn(browser, x, height) {
  const regions = [];
  let current = null;
  for (let y = 4; y < height - 4; y += AUTH_WALK.probeStepPx) {
    const kind = await probe(browser, x, y);
    if (current && current.kind === kind) {
      current.bottom = y;
      continue;
    }
    if (current && current.kind !== 'none') regions.push(current);
    current = { kind, top: y, bottom: y };
  }
  if (current && current.kind !== 'none') regions.push(current);
  return regions.map(r => {
    const mid = Math.round((r.top + r.bottom) / 2);
    return { ...r, y: Math.min(r.top + AUTH_WALK.edgeInsetPx, mid), lastY: Math.max(r.bottom - AUTH_WALK.edgeInsetPx, mid) };
  });
}

/** Interactive segments along the horizontal line at [y] between [fromX] and [toX], left to right. */
async function scanRow(browser, y, fromX, toX, stepPx = 8) {
  const segments = [];
  let current = null;
  for (let x = fromX; x <= toX; x += stepPx) {
    const kind = await probe(browser, x, y);
    if (current && current.kind === kind) {
      current.right = x;
      continue;
    }
    if (current && current.kind !== 'none') segments.push(current);
    current = { kind, left: x, right: x };
  }
  if (current && current.kind !== 'none') segments.push(current);
  return segments.map(s => {
    const mid = Math.round((s.left + s.right) / 2);
    return { ...s, x: Math.min(s.left + AUTH_WALK.edgeInsetPx, mid), lastX: Math.max(s.right - AUTH_WALK.edgeInsetPx, mid) };
  });
}

async function layoutOf(browser) {
  const [width, height] = VIEWPORTS[AUTH_WALK.viewport];
  const cx = Math.round(width / 2);
  const regions = await scanColumn(browser, cx, height);
  const fields = regions.filter(r => r.kind === 'text');
  const pointers = regions.filter(r => r.kind === 'pointer');
  const wide = [];
  for (const p of pointers) {
    if ((await probe(browser, cx - AUTH_WALK.wideOffsetPx, p.y)) === 'pointer' && (await probe(browser, cx + AUTH_WALK.wideOffsetPx, p.y)) === 'pointer') wide.push(p);
  }
  return { cx, width, height, fields, pointers, wide, summary: regions.map(r => `${r.kind}@${r.y}`).join(' ') };
}

/** Rescans until [accept] holds (the screen settled on the expected step) or the step times out. */
async function waitForLayout(browser, label, accept, timeoutMs = AUTH_WALK.stepTimeoutMs) {
  let layout;
  for (const end = Date.now() + timeoutMs; Date.now() < end;) {
    layout = await layoutOf(browser);
    if (accept(layout)) return layout;
    await sleep(500);
  }
  throw new Error(`${label}: the screen never reached the expected layout (last scan: ${layout?.summary || 'nothing interactive'})`);
}

/** Clicks into [field], clears what it holds (sign-in keeps the email from sign-up), then types. */
async function fillField(browser, layout, field, text) {
  await browser.click(layout.cx, field.y);
  await sleep(250);
  await browser.clearFocusedField();
  await browser.typeText(text);
  await sleep(250);
}

async function authShot(browser, stage) {
  // Diagnostic only: kept out of `screenshots`, which the golden signatures check.
  try { await browser.screenshot(join(out, `auth-walk-${stage}.png`)); } catch {}
}

async function latestSignupCode(email) {
  for (let attempt = 0; attempt < 30; attempt++) {
    const response = await fetch(`${api}/v1/dev/auth/email/signup/latest-code`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ email }),
    });
    if (response.status === 404) throw new Error('The dev sign-up code lookup answered 404: start the server with PYNAPLI_DEV_SIGNUP_SHORTCUTS_ENABLED=true (see scripts/lib/PynapliDevSignup.psm1).');
    const body = await response.json().catch(() => ({}));
    if (body.code) return String(body.code);
    await sleep(500);
  }
  throw new Error('No sign-up code arrived within 15 s.');
}

const perfMark = (browser, name) => browser.evaluate(`(() => { const e = performance.getEntriesByName('pynapli:${name}')[0]; return e ? e.startTime : null; })()`);

/** Waits for a grid query that returned cards, and checks seeded members are among them. */
async function assertGridCards(browser, label, since) {
  let cards = [];
  for (const end = Date.now() + AUTH_WALK.stepTimeoutMs; Date.now() < end && cards.length === 0;) {
    const queries = browser.requests.slice(since).filter(r => r.method === 'POST' && r.finished && r.status === 200 && new URL(r.url).pathname.endsWith('/v1/grid/query'));
    for (const query of queries) {
      try {
        const items = JSON.parse((await browser.responseBody(query)) ?? '{}').items ?? [];
        if (items.length > cards.length) cards = items;
      } catch {}
    }
    if (cards.length === 0) await sleep(500);
  }
  const seeded = [...new Set(cards.map(c => c.displayName).filter(n => AUTH_WALK.seededNames.includes(n)))];
  check(`auth walk: ${label} grid query returned cards`, cards.length > 0, `cards=${cards.length}`);
  // Seeded names prove faces on a clean database; earlier QA and load-test accounts in the same cell
  // can outrank them, so a page of other members with names proves it too.
  const named = cards.filter(c => (c.displayName ?? '').trim().length > 0).length;
  check(`auth walk: ${label} grid shows real members`, seeded.length > 0 || named >= AUTH_WALK.minNamedCards, `seeded=[${seeded.join(', ')}] named=${named}`);
}

async function authWalk() {
  const [width, height] = VIEWPORTS[AUTH_WALK.viewport];
  const email = `web-qa-signup-${Date.now()}@pynapli.local`;
  const browser = await launch({ width, height });
  try {
    await browser.send('Browser.grantPermissions', { origin: app, permissions: ['geolocation'] }).catch(() => {});
    await browser.send('Emulation.setGeolocationOverride', { latitude: AUTH_WALK.latitude, longitude: AUTH_WALK.longitude, accuracy: 20 });
    await browser.navigate(`${app}/`, BOOT_MS);
    const atLogin = await browser.waitForPath('/login');
    check('auth walk: a fresh visitor lands on /login', atLogin === '/login', `pathname=${atLogin}`);

    // Welcome landing: no fields, [Create account] (wide) then [Sign in] (wide).
    let layout = await waitForLayout(browser, 'Welcome landing', l => l.fields.length === 0 && l.wide.length >= 2);
    await authShot(browser, '00-landing');
    await browser.click(layout.cx, layout.wide[0].y);
    const atSignup = await browser.waitForPath('/signup');
    check('auth walk: Create account opens the sign-up stepper', atSignup === '/signup', `pathname=${atSignup}`);

    // Birthday: the DD | MM | YYYY row; the centre line crosses MM, so find DD along the row.
    layout = await waitForLayout(browser, 'Birthday step', l => l.fields.length >= 1 && l.wide.length === 0);
    const dobRow = await scanRow(browser, layout.fields[0].y, Math.round(width * 0.2), Math.round(width * 0.8));
    const dobFields = dobRow.filter(s => s.kind === 'text');
    if (dobFields.length !== 3) throw new Error(`Birthday step: expected 3 fields in the row, found ${dobFields.length}`);
    await browser.click(dobFields[0].x, layout.fields[0].y);
    await sleep(250);
    for (const part of AUTH_WALK.birthday) {
      await browser.typeText(part);
      await sleep(300);
    }
    // Next is enabled (and so shows the pointer) only once all three fields are complete.
    layout = await waitForLayout(browser, 'Birthday step (filled)', l => l.wide.length >= 1, 6000);
    await authShot(browser, '01-birthday');
    await browser.click(layout.cx, layout.wide[0].y);

    // Policies: no fields; Accept is the wide button.
    layout = await waitForLayout(browser, 'Policies step', l => l.fields.length === 0 && l.wide.length >= 1);
    await authShot(browser, '02-policies');
    await browser.click(layout.cx, layout.wide[0].y);

    // Account: Email, Password, then Create account (wide).
    layout = await waitForLayout(browser, 'Account step', l => l.fields.length === 2 && l.wide.length >= 1);
    await fillField(browser, layout, layout.fields[0], email);
    await fillField(browser, layout, layout.fields[1], AUTH_WALK.password);
    await authShot(browser, '03-account');
    let since = browser.requests.length;
    await browser.click(layout.cx, layout.wide.find(w => w.top > layout.fields[1].bottom).y);
    const signup = await browser.waitForRequest('POST', '/v1/auth/email/signup', { since });
    let sent = {};
    try { sent = JSON.parse((signup && (await browser.requestBody(signup))) ?? '{}'); } catch {}
    check('auth walk: sign-up sent with the typed email and birthday', signup?.status === 202 && sent.email === email && sent.birthdate === '2000-06-01', `status=${signup?.status} birthdate=${sent.birthdate} emailMatches=${sent.email === email}`);

    // Code: one field, Verify (wide).
    layout = await waitForLayout(browser, 'Code step', l => l.fields.length === 1 && l.wide.length >= 1);
    const code = await latestSignupCode(email);
    await fillField(browser, layout, layout.fields[0], code);
    await authShot(browser, '04-code');
    since = browser.requests.length;
    await browser.click(layout.cx, layout.wide.find(w => w.top > layout.fields[0].bottom).y);
    const verify = await browser.waitForRequest('POST', '/v1/auth/email/signup/verify', { since });
    check('auth walk: the emailed code creates the account', verify?.status === 200, `status=${verify?.status}`);
    const stored = await browser.evaluate("!!localStorage.getItem('pynapli.auth.session.v1')");
    check('auth walk: a session is stored after the code', stored === true);

    // Location: Share my location (wide). The browser grants it and the step advances on its own.
    layout = await waitForLayout(browser, 'Location step', l => l.fields.length === 0 && l.wide.length >= 1);
    await authShot(browser, '05-location');
    since = browser.requests.length;
    // The first grid load can start here (it is the shared location's refresh), so look from here.
    const gridSince = since;
    await browser.click(layout.cx, layout.wide[0].y);
    const located = await browser.waitForRequest('POST', '/v1/location/current', { since });
    check('auth walk: the granted location reaches the server', located?.status === 200, `status=${located?.status}`);

    // Profile: Add photo (wide), Display name, Next (wide, below the field). Skipped: blank name.
    layout = await waitForLayout(browser, 'Profile step', l => l.fields.length === 1 && l.wide.some(w => w.top > l.fields[0].bottom));
    await authShot(browser, '06-profile');
    await browser.click(layout.cx, layout.wide.find(w => w.top > layout.fields[0].bottom).y);
    const atGrid = await browser.waitForPath('/grid', 10000);
    check('auth walk: finishing sign-up lands on /grid', atGrid === '/grid', `pathname=${atGrid}`);
    await assertGridCards(browser, 'first', gridSince);
    await sleep(SETTLE_MS);
    await authShot(browser, '07-first-grid');

    const tapMs = await perfMark(browser, 'create-account-tap');
    const submitMs = await perfMark(browser, 'account-submit');
    const firstCardMs = await perfMark(browser, 'first-grid-card');
    check('auth walk: first grid card composed (perf mark)', firstCardMs !== null && tapMs !== null, `create-account-tap=${tapMs} first-grid-card=${firstCardMs}`);
    if (firstCardMs !== null && tapMs !== null) {
      timings.createAccountTapToFirstCardMs = Math.round(firstCardMs - tapMs);
      if (submitMs !== null) timings.accountSubmitToFirstCardMs = Math.round(firstCardMs - submitMs);
      console.log(`PERF Create account (landing) -> first grid card: ${timings.createAccountTapToFirstCardMs} ms (includes scripted typing and the code lookup)`);
      if (submitMs !== null) console.log(`PERF Create account (Account step) -> first grid card: ${timings.accountSubmitToFirstCardMs} ms`);
    }

    // Log out from the Profile hub (its last row), then confirm in the dialog.
    await browser.pushRoute('/profile');
    await browser.waitForPath('/profile');
    await sleep(SETTLE_MS);
    for (let i = 0; i < 5; i++) {
      await browser.wheel(Math.round(width / 2), Math.round(height / 2), 2000);
      await sleep(300);
    }
    await sleep(800);
    layout = await layoutOf(browser);
    const logoutRow = layout.pointers.at(-1);
    if (!logoutRow) throw new Error(`Profile hub: no rows found (${layout.summary})`);
    await authShot(browser, '08-hub-bottom');
    await browser.click(layout.cx, logoutRow.lastY);
    await sleep(1000);
    // The dialog's confirm button is its bottom-right control: find the dialog's button row on a few
    // lines right of centre, then take the rightmost control on that row.
    let buttonRowY = null;
    for (const dx of [40, 100, 160, 220]) {
      const hits = (await scanColumn(browser, Math.round(width / 2) + dx, height)).filter(r => r.kind === 'pointer');
      for (const hit of hits) if (buttonRowY === null || hit.lastY > buttonRowY) buttonRowY = hit.lastY;
    }
    if (buttonRowY === null) throw new Error('Log out dialog: no buttons found');
    const dialogButtons = (await scanRow(browser, buttonRowY, Math.round(width * 0.2), Math.round(width * 0.8))).filter(s => s.kind === 'pointer');
    await authShot(browser, '09-logout-dialog');
    since = browser.requests.length;
    await browser.click(dialogButtons.at(-1).lastX, buttonRowY);
    const logout = await browser.waitForRequest('POST', '/v1/auth/logout', { since });
    check('auth walk: Log out signs out on the server', logout?.status === 200, `status=${logout?.status}`);
    const backAtLogin = await browser.waitForPath('/login', 10000);
    check('auth walk: Log out returns to /login', backAtLogin === '/login', `pathname=${backAtLogin}`);
    const cleared = await browser.evaluate("localStorage.getItem('pynapli.auth.session.v1') === null");
    check('auth walk: the stored session is gone after Log out', cleared === true);

    // Sign back in on the same page (same app process). B1 (fixed in Section 1): after a sign-up, Log
    // out used to leave the legacy create-account form. Now it lands on the welcome landing, whose
    // [Sign in] (second wide button) opens the two-field sign-in form.
    layout = await waitForLayout(browser, 'Welcome after Log out', l => l.wide.length >= 1, 10000).catch(() => layoutOf(browser));
    check('auth walk: after Log out the landing is the welcome screen (B1)', layout.fields.length === 0 && layout.wide.length >= 2, `fields=${layout.fields.length} wide=${layout.wide.length}`);
    if (layout.fields.length === 0 && layout.wide.length >= 2) await browser.click(layout.cx, layout.wide[1].y);
    layout = await waitForLayout(browser, 'Sign-in form', l => l.fields.length === 2 && l.wide.length >= 1);
    await fillField(browser, layout, layout.fields[0], email);
    await fillField(browser, layout, layout.fields[1], AUTH_WALK.password);
    await authShot(browser, '10-sign-in');
    since = browser.requests.length;
    await browser.click(layout.cx, layout.wide[0].y);
    const login = await browser.waitForRequest('POST', '/v1/auth/email/login', { since });
    check('auth walk: signing back in succeeds', login?.status === 200, `status=${login?.status}`);
    const againAtGrid = await browser.waitForPath('/grid', 15000);
    check('auth walk: signing back in lands on /grid', againAtGrid === '/grid', `pathname=${againAtGrid}`);
    // The first-grid-card mark fires once per page, so this second grid rests on the API answer.
    await assertGridCards(browser, 'after sign-in', since);
    await sleep(SETTLE_MS);
    await authShot(browser, '11-grid-again');
    noConsoleErrors(browser, 'auth walk');
  } finally {
    await browser.close();
  }
}

const timings = {};
const authMode = args['auth-walk'] ?? 'on'; // on | off | only

try {
  if (authMode !== 'only') {
    for (const viewport of viewports) {
      if (!VIEWPORTS[viewport]) throw new Error(`Unknown viewport ${viewport}; use ${Object.keys(VIEWPORTS).join(', ')}`);
      await surfaces(viewport);
      await psync(viewport);
    }
    if (viewports.includes('desktop')) await largeText();
  }
} catch (error) {
  check('run completed', false, String(error?.message ?? error));
}

if (authMode !== 'off') {
  try {
    await authWalk();
  } catch (error) {
    check('auth walk completed', false, String(error?.message ?? error));
  }
}

writeFileSync(join(out, 'results.json'), JSON.stringify({ checks, screenshots, timings }, null, 2));
const failed = checks.filter(c => !c.pass).length;
console.log(`---- ${checks.length - failed}/${checks.length} checks passed`);
process.exit(failed ? 1 : 0);
