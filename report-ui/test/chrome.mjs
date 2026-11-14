// Just enough of the Chrome DevTools protocol to load a page headless, run script in it and read
// what it shows, with nothing to install: the Chrome or Chromium already on the machine (CI's
// runners have one), found on PATH or where it installs, or named by CHROME.
import { spawn, spawnSync } from 'node:child_process';
import { existsSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const PLACES = [
  '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
  '/Applications/Chromium.app/Contents/MacOS/Chromium',
  'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
  'C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe',
];

export function findChrome() {
  if (process.env.CHROME) return process.env.CHROME;
  for (const name of ['google-chrome', 'google-chrome-stable', 'chromium', 'chromium-browser', 'chrome']) {
    const found = spawnSync(process.platform === 'win32' ? 'where' : 'which', [name], { encoding: 'utf8' });
    if (found.status === 0 && found.stdout.trim()) return found.stdout.trim().split(/\r?\n/)[0];
  }
  return PLACES.find(existsSync) || null;
}

export async function launch() {
  const chrome = findChrome();
  if (!chrome) throw new Error('No Chrome or Chromium found: install one, or name it with CHROME=/path/to/chrome.');
  const profile = mkdtempSync(join(tmpdir(), 'report-ui-'));
  const proc = spawn(chrome, ['--headless=new', '--remote-debugging-port=0', `--user-data-dir=${profile}`, '--no-first-run',
    '--no-default-browser-check', '--disable-gpu', '--hide-scrollbars', '--mute-audio', 'about:blank'], { stdio: ['ignore', 'ignore', 'pipe'] });
  const url = await new Promise((resolve, reject) => {
    let err = '';
    const timer = setTimeout(() => reject(new Error(`Chrome didn't start: ${err}`)), 20000);
    proc.stderr.on('data', (d) => {
      err += d;
      const m = err.match(/DevTools listening on (ws:\/\/\S+)/);
      if (m) { clearTimeout(timer); resolve(m[1]); }
    });
    proc.on('exit', (code) => { clearTimeout(timer); reject(new Error(`Chrome exited (${code}): ${err}`)); });
  });
  const ws = new WebSocket(url);
  await new Promise((resolve, reject) => { ws.onopen = resolve; ws.onerror = reject; });
  let next = 0;
  const waiting = new Map();
  const listeners = [];
  ws.onmessage = (event) => {
    const msg = JSON.parse(event.data);
    if (msg.id && waiting.has(msg.id)) {
      const { resolve, reject } = waiting.get(msg.id);
      waiting.delete(msg.id);
      if (msg.error) reject(new Error(msg.error.message)); else resolve(msg.result);
    } else if (msg.method) listeners.forEach((l) => l(msg));
  };
  const send = (method, params = {}, sessionId) => new Promise((resolve, reject) => {
    const id = ++next;
    waiting.set(id, { resolve, reject });
    ws.send(JSON.stringify({ id, method, params, sessionId }));
  });
  return {
    async page({ width = 1280, height = 900, dark = false, forcedColors = false } = {}) {
      const { targetId } = await send('Target.createTarget', { url: 'about:blank' });
      const { sessionId } = await send('Target.attachToTarget', { targetId, flatten: true });
      const s = (method, params) => send(method, params, sessionId);
      const errors = [];
      listeners.push((msg) => {
        if (msg.sessionId !== sessionId) return;
        if (msg.method === 'Runtime.exceptionThrown') errors.push(msg.params.exceptionDetails.exception?.description || msg.params.exceptionDetails.text);
        if (msg.method === 'Runtime.consoleAPICalled' && msg.params.type === 'error') errors.push(msg.params.args.map((a) => a.value ?? a.description).join(' '));
      });
      await s('Page.enable');
      await s('Runtime.enable');
      await s('Emulation.setDeviceMetricsOverride', { width, height, deviceScaleFactor: 1, mobile: width < 600 });
      await s('Emulation.setEmulatedMedia', { features: [{ name: 'prefers-color-scheme', value: dark ? 'dark' : 'light' }, { name: 'forced-colors', value: forcedColors ? 'active' : 'none' }] });
      const page = {
        errors,
        async goto(url) {
          const loaded = new Promise((resolve) => listeners.push((m) => { if (m.sessionId === sessionId && m.method === 'Page.loadEventFired') resolve(); }));
          await s('Page.navigate', { url });
          await loaded;
          await page.settle();
        },
        async eval(expression) {
          const r = await s('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true });
          if (r.exceptionDetails) throw new Error(r.exceptionDetails.exception?.description || r.exceptionDetails.text);
          return r.result.value;
        },
        // Two frames: what a change of route or a click rendered is on screen.
        settle: () => page.eval('new Promise(r => requestAnimationFrame(() => requestAnimationFrame(r)))'),
        async route(hash) { await page.eval(`location.hash = ${JSON.stringify(hash)}`); await page.settle(); },
        async click(selector) { await page.eval(`document.querySelector(${JSON.stringify(selector)}).click()`); await page.settle(); },
        async key(key) {
          for (const type of ['keyDown', 'keyUp']) await s('Input.dispatchKeyEvent', { type, key, text: type === 'keyDown' && key.length === 1 ? key : undefined, code: key.length === 1 ? `Key${key.toUpperCase()}` : key });
          await page.settle();
        },
        text: (selector = 'body') => page.eval(`(document.querySelector(${JSON.stringify(selector)}) || {}).innerText || ''`),
        count: (selector) => page.eval(`document.querySelectorAll(${JSON.stringify(selector)}).length`),
        async axTree() { await s('Accessibility.enable'); return (await s('Accessibility.getFullAXTree')).nodes; },
        async screenshot(file, { fullPage = false } = {}) {
          let clip;
          if (fullPage) {
            const h = await page.eval('Math.ceil(document.documentElement.scrollHeight)');
            clip = { x: 0, y: 0, width, height: Math.min(h, 16000), scale: 1 };
          }
          const { data } = await s('Page.captureScreenshot', { format: 'png', clip, captureBeyondViewport: fullPage });
          writeFileSync(file, Buffer.from(data, 'base64'));
        },
        close: () => send('Target.closeTarget', { targetId }),
      };
      return page;
    },
    async close() {
      try { await send('Browser.close'); } catch { /* already gone */ }
      ws.close();
      await new Promise((resolve) => (proc.exitCode !== null ? resolve() : proc.on('exit', resolve)));
      rmSync(profile, { recursive: true, force: true, maxRetries: 5, retryDelay: 200 });
    },
  };
}
