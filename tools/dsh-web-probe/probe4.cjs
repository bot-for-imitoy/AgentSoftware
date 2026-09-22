// Probe 4: open the real running session "修复Web界面消息显示" and dump the conversation DOM.
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
  page.on('console', (msg) => { const t = msg.text(); if (!/Failed to load resource/.test(t)) logs.push(`[console.${msg.type()}] ${t}`); });
  page.on('pageerror', (err) => logs.push(`[pageerror] ${String(err.stack || err.message).slice(0, 2000)}`));
  await page.goto('http://127.0.0.1:3080/', { waitUntil: 'domcontentloaded', timeout: 45000 }).catch((e) => logs.push(`[goto-error] ${e.message}`));
  await page.waitForTimeout(15000);

  // Find and click the running session in the sidebar
  const clicked = await page.evaluate(() => {
    const target = '修复Web界面消息显示';
    const all = Array.from(document.querySelectorAll('*'));
    for (const el of all) {
      const txt = (el.textContent || '').trim();
      if (txt === target && el.children.length <= 2) {
        el.click();
        return true;
      }
    }
    return false;
  });
  logs.push(`[click-session] ${clicked}`);
  await page.waitForTimeout(12000);

  const text = await page.evaluate(() => document.body.innerText);
  fs.writeFileSync('/home/openclaw/workspace/AgentSoftware/tools/dsh-web-probe/body-after-click.txt', text);
  const center = await page.evaluate(() => {
    // Find elements that look like message bubbles / chat rows
    const hits = [];
    const seen = new Set();
    const walk = (el, depth) => {
      if (depth > 12 || hits.length > 400) return;
      const cls = typeof el.className === 'string' ? el.className : '';
      if (/message|bubble|turn|step|tool|think|reason|row|item|assistant|user|chat/i.test(cls)) {
        const id = el.id ? '#' + el.id : '';
        const key = el.tagName + '.' + cls.split(' ').sort().join('.') + id;
        if (!seen.has(key)) {
          seen.add(key);
          const t = (el.textContent || '').trim().replace(/\s+/g, ' ').slice(0, 220);
          hits.push({ key: key.slice(0, 120), tag: el.tagName, t });
        }
      }
      for (const c of el.children) walk(c, depth + 1);
    };
    walk(document.body, 0);
    return hits;
  });
  fs.writeFileSync('/home/openclaw/workspace/AgentSoftware/tools/dsh-web-probe/center-keys.txt', center.map((h) => `${h.key}\n    ${h.t}`).join('\n'));
  console.log('=== TEXT LEN ===', text.length);
  console.log('=== CENTER KEYS ===', center.length);
  console.log('=== LOGS ===');
  for (const l of logs) console.log(l.slice(0, 1500));
  console.log('=== LAST 800 CHARS OF BODY ===');
  console.log(text.slice(-800));
  await page.screenshot({ path: '/home/openclaw/workspace/AgentSoftware/tools/dsh-web-probe/shot2.png' });
  await browser.close();
})().catch((e) => { console.error('FATAL', e); process.exit(1); });
