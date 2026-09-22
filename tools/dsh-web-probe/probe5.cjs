// Probe 5: on-load layout map — where is each visible text on screen, plus localStorage state.
const { chromium } = require('/home/openclaw/ai/deepseek-harness/apps/web/node_modules/playwright');
const fs = require('fs');

(async () => {
  const browser = await chromium.launch({
    headless: true,
    executablePath: '/home/openclaw/.cache/ms-playwright/chromium-1234/chrome-linux64/chrome',
    args: ['--no-sandbox', '--disable-dev-shm-usage'],
  });
  const page = await browser.newPage({ viewport: { width: 1500, height: 950 } });
  const logs = [];
  page.on('pageerror', (err) => logs.push(`[pageerror] ${String(err.stack || err.message).slice(0, 1200)}`));
  page.on('console', (msg) => { const t = msg.text(); if (!/Failed to load resource/.test(t)) logs.push(`[c.${msg.type()}] ${t.slice(0, 400)}`); });
  await page.goto('http://127.0.0.1:3080/', { waitUntil: 'domcontentloaded', timeout: 45000 }).catch((e) => logs.push(`[goto] ${e.message}`));
  await page.waitForTimeout(20000);

  const info = await page.evaluate(() => {
    const ls = {};
    for (let i = 0; i < localStorage.length; i++) { const k = localStorage.key(i); ls[k] = String(localStorage.getItem(k)).slice(0, 300); }
    const map = [];
    const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
    let n;
    const rects = [];
    while ((n = walker.nextNode())) {
      const t = (n.textContent || '').trim();
      if (t.length < 2) continue;
      const r = n.parentElement.getBoundingClientRect();
      if (r.width === 0 && r.height === 0) continue;
      rects.push({ x: Math.round(r.x + r.width / 2), y: Math.round(r.y + r.height / 2), w: Math.round(r.width), h: Math.round(r.height), t: t.slice(0, 90) });
    }
    rects.sort((a, b) => a.y - b.y || a.x - b.x);
    // Active/selected element detection: elements with aria-current or data-active
    const active = [];
    const all = document.querySelectorAll('[aria-current], [data-active="true"], [data-selected="true"], [aria-selected="true"]');
    all.forEach((el) => active.push({ tag: el.tagName, cls: String(el.className).slice(0, 100), t: (el.textContent || '').trim().slice(0, 60) }));
    return { ls, rects, active, title: document.title };
  });
  fs.writeFileSync('/home/openclaw/workspace/AgentSoftware/tools/dsh-web-probe/layout.txt',
    '=== TITLE ===\n' + info.title + '\n=== LOCALSTORAGE ===\n' + JSON.stringify(info.ls, null, 1) + '\n=== ACTIVE ===\n' + JSON.stringify(info.active, null, 1) + '\n=== TEXT MAP ===\n' + info.rects.map((r) => `(${r.x},${r.y}) ${r.w}x${r.h}  ${r.t}`).join('\n'));
  console.log('done', info.rects.length, 'texts;', info.active.length, 'active; logs', logs.length);
  for (const l of logs) console.log(l.slice(0, 900));
  await browser.close();
})().catch((e) => { console.error('FATAL', e); process.exit(1); });
