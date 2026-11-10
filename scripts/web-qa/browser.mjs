// Minimal headless-Chrome driver over the DevTools protocol (CDP). No dependencies: Node 22+
// ships fetch and WebSocket.
//
// Hard-won rules this encodes (each one failed SILENTLY before it was written down):
// - Chrome picks its own debugging port (--remote-debugging-port=0) and reports it in the
//   profile's DevToolsActivePort file. A fixed port lets a run attach to a stale Chrome left over
//   from an earlier run and test the wrong page.
// - On Windows, killing the spawned chrome.exe leaves its children alive; close() kills the tree.
// - Compose 1.11 draws into a CANVAS inside a shadow root, so DOM queries must walk shadowRoot.
// - Keys only reach Compose once the page has focus. Click first, as a user would.
import { spawn, spawnSync } from 'node:child_process';
import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

export const sleep = ms => new Promise(r => setTimeout(r, ms));

function chromePath() {
  const candidates = [
    process.env.CHROME_PATH,
    'C:/Program Files/Google/Chrome/Application/chrome.exe',
    'C:/Program Files (x86)/Google/Chrome/Application/chrome.exe',
    `${process.env.LOCALAPPDATA}/Google/Chrome/Application/chrome.exe`,
    '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
    '/usr/bin/google-chrome',
  ].filter(Boolean);
  const found = candidates.find(p => existsSync(p));
  if (!found) throw new Error('Chrome not found; set CHROME_PATH.');
  return found;
}

/**
 * Launches headless Chrome on a fresh profile. [defaultFontPx] presets the browser's font-size
 * setting (Chrome: Appearance > Font size), which the web app maps onto its text scale.
 */
export async function launch({ width, height, defaultFontPx } = {}) {
  const profile = mkdtempSync(join(tmpdir(), 'pynapli-web-qa-'));
  if (defaultFontPx) {
    mkdirSync(join(profile, 'Default'), { recursive: true });
    writeFileSync(join(profile, 'Default', 'Preferences'), JSON.stringify({ webkit: { webprefs: { default_font_size: defaultFontPx } } }));
  }
  const chrome = spawn(chromePath(), [
    '--headless=new', '--remote-debugging-port=0', `--user-data-dir=${profile}`,
    `--window-size=${width},${height}`, '--no-first-run', '--no-default-browser-check', 'about:blank',
  ], { stdio: 'ignore' });

  let port;
  for (let i = 0; i < 100 && !port; i++) {
    await sleep(100);
    const file = join(profile, 'DevToolsActivePort');
    if (existsSync(file)) port = readFileSync(file, 'utf8').split('\n')[0].trim();
  }
  if (!port) throw new Error('Chrome did not report a debugging port.');
  let pageWs;
  for (let i = 0; i < 50 && !pageWs; i++) {
    try { pageWs = (await (await fetch(`http://127.0.0.1:${port}/json/list`)).json()).find(t => t.type === 'page')?.webSocketDebuggerUrl; } catch { await sleep(100); }
  }
  if (!pageWs) throw new Error('No page target in Chrome.');

  const ws = new WebSocket(pageWs);
  await new Promise((resolve, reject) => { ws.onopen = resolve; ws.onerror = reject; });
  let nextId = 0;
  const pending = new Map();
  const problems = [];
  const sockets = [];
  ws.onmessage = event => {
    const m = JSON.parse(event.data);
    if (m.id && pending.has(m.id)) { pending.get(m.id)(m); pending.delete(m.id); }
    if (m.method === 'Runtime.exceptionThrown') problems.push(`exception: ${m.params.exceptionDetails.exception?.description ?? m.params.exceptionDetails.text}`.slice(0, 300));
    if (m.method === 'Runtime.consoleAPICalled' && m.params.type === 'error') problems.push(`console.error: ${m.params.args.map(a => a.value ?? a.description).join(' ')}`.slice(0, 300));
    if (m.method === 'Network.webSocketCreated') sockets.push(m.params.url.replace(/\?.*/, ''));
  };
  const send = (method, params = {}) => new Promise(resolve => {
    const id = ++nextId;
    pending.set(id, resolve);
    ws.send(JSON.stringify({ id, method, params }));
  });
  const evaluate = async expression => (await send('Runtime.evaluate', { expression, returnByValue: true })).result?.result?.value;

  await send('Page.enable');
  await send('Runtime.enable');
  await send('Network.enable');
  await send('Emulation.setDeviceMetricsOverride', { width, height, deviceScaleFactor: 1, mobile: false });

  return {
    problems,
    sockets,
    send,
    evaluate,
    async navigate(url, settleMs = 0) {
      await send('Page.navigate', { url });
      if (settleMs) await sleep(settleMs);
    },
    path: () => evaluate('location.pathname'),
    /** Polls until location.pathname equals [want] (or the timeout passes); returns the last value. */
    async waitForPath(want, timeoutMs = 6000) {
      let got;
      for (const end = Date.now() + timeoutMs; Date.now() < end;) {
        got = await evaluate('location.pathname');
        if (got === want) break;
        await sleep(250);
      }
      return got;
    },
    /** In-app navigation the way the router sees browser history moves. */
    pushRoute: route => evaluate(`history.pushState({}, '', '${route}'); dispatchEvent(new PopStateEvent('popstate')); 1`),
    back: () => evaluate('history.back(); 1'),
    async screenshot(file) {
      const r = await send('Page.captureScreenshot', { format: 'png' });
      writeFileSync(file, Buffer.from(r.result.data, 'base64'));
    },
    /** The CSS cursor Compose set at (x, y), read through the shadow root it renders into. */
    async cursorAt(x, y) {
      await send('Input.dispatchMouseEvent', { type: 'mouseMoved', x, y });
      await sleep(300);
      return evaluate(`(() => { let el = document.elementFromPoint(${x}, ${y}); while (el && el.shadowRoot) { const inner = el.shadowRoot.elementFromPoint(${x}, ${y}); if (!inner || inner === el) break; el = inner; } return el ? getComputedStyle(el).cursor : 'none'; })()`);
    },
    /** The browser's default font size (what `font-size: medium` resolves to). */
    defaultFontPx: () => evaluate("(() => { const d = document.createElement('div'); d.style.fontSize = 'medium'; document.body.appendChild(d); const v = parseFloat(getComputedStyle(d).fontSize); d.remove(); return v; })()"),
    async close() {
      try { ws.close(); } catch {}
      if (process.platform === 'win32') spawnSync('taskkill', ['/PID', String(chrome.pid), '/T', '/F'], { stdio: 'ignore' });
      else chrome.kill('SIGKILL');
      await sleep(300);
      try { rmSync(profile, { recursive: true, force: true }); } catch {}
    },
  };
}
