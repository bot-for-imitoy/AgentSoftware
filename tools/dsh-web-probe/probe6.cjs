// Probe 6: open the running session and watch whether NEW steps stream in live.
const { chromium } = require('/home/openclaw/ai/deepseek-harness/apps/web/node_modules/playwright');
const fs = require('fs');

(async () => {
  const browser = await chromium.launch({
    headless: true,
    executablePath: '/home/openclaw/.cache/ms-playwright/chromium-1234/chrome-linux64/chrome',
    args: ['--no-sandbox', '--disable-dev-shm-usage'],
  });
  const page = await browser.newPage({ viewport: { width: 1500, height: 950 } });
  const events = [];
  page.on('console', (msg) => { const t = msg.text(); if (!/Failed to load resource/.test(t)) events.push(`[c.${msg.type()}] ${t.slice(0, 200)}`); });
  page.on('pageerror', (err) => events.push(`[pageerror] ${String(err.stack || err.message).slice(0, 800)}`));
  const sockets = [];
  page.on('websocket', (ws) => {
    sockets.push(ws.url());
    ws.on('framesent', (f) => { const s = String(f.payload); if (s.length > 2) events.push(`WS> ${s.slice(0, 400)}`); });
    ws.on('framereceived', (f) => { const s = String(f.payload); if (s.length > 2) events.push(`WS< ${s.slice(0, 400)}`); });
  });
  page.on('response', async (res) => {
    const ct = res.headers()['content-type'] || '';
    if (ct.includes('text/event-stream')) {
      events.push(`SSE ${res.url()}`);
      try {
        const buf = await res.body();
        events.push(`SSE-BODY ${String(buf).slice(0, 500)}`);
      } catch (e) { /* streamed */ }
    }
  });
  await page.goto('http://127.0.0.1:3080/', { waitUntil: 'domcontentloaded', timeout: 45000 }).catch(() => {});
  await page.waitForTimeout(12000);
  const clicked = await page.evaluate(() => {
    const target = '修复Web界面消息显示';
    const all = Array.from(document.querySelectorAll('*'));
    for (const el of all) {
      const txt = (el.textContent || '').trim();
      if (txt === target && el.children.length <= 2) { el.click(); return true; }
    }
    return false;
  });
  events.push(`[click] ${clicked}`);
  await page.waitForTimeout(8000);
  const tailOf = () => page.evaluate(() => document.body.innerText.slice(-1500));
  const snapshots = [];
  for (let i = 0; i < 6; i++) {
    await page.waitForTimeout(10000);
    snapshots.push(`--- t+${(i + 1) * 10}s ---\n` + (await tailOf()));
  }
  fs.writeFileSync('/home/openclaw/workspace/AgentSoftware/tools/dsh-web-probe/live-tail.txt', snapshots.join('\n'));
  fs.writeFileSync('/home/openclaw/workspace/AgentSoftware/tools/dsh-web-probe/live-events.txt', events.join('\n') + '\n=== SOCKETS ===\n' + sockets.join('\n'));
  console.log('snapshot chars', snapshots.join('').length, 'events', events.length, 'sockets', sockets.length);
  await browser.close();
})().catch((e) => { console.error('FATAL', e); process.exit(1); });
