// Web companion QA: drives the production Wasm bundle in headless Chrome at desktop, tablet and phone
// sizes and records pass/fail checks plus a screenshot per stage. Run it through scripts/web-qa.ps1,
// which builds, serves, seeds and checks the screenshots against scripts/web-qa-goldens.json.
//
// usage: node scripts/web-qa/run.mjs --out <dir> [--app http://localhost:8081] [--api http://localhost:8080]
//                                    [--viewports desktop,tablet,phone]
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

try {
  for (const viewport of viewports) {
    if (!VIEWPORTS[viewport]) throw new Error(`Unknown viewport ${viewport}; use ${Object.keys(VIEWPORTS).join(', ')}`);
    await surfaces(viewport);
    await psync(viewport);
  }
  if (viewports.includes('desktop')) await largeText();
} catch (error) {
  check('run completed', false, String(error?.message ?? error));
}

writeFileSync(join(out, 'results.json'), JSON.stringify({ checks, screenshots }, null, 2));
const failed = checks.filter(c => !c.pass).length;
console.log(`---- ${checks.length - failed}/${checks.length} checks passed`);
process.exit(failed ? 1 : 0);
