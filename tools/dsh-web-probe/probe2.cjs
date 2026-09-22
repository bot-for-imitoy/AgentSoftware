// Probe 2: richer DOM dump + API surface of the DSH Web GUI.
const { chromium } = require('/home/openclaw/ai/deepseek-harness/apps/web/node_modules/playwright');
const fs = require('fs');

(async () => {
  const browser = await chromium.launch({
    headless: true,
    executablePath: '/home/openclaw/.cache/ms-playwright/chromium-1234/chrome-linux64/chrome',
    args: ['--no-sandbox', '--disable-dev-shm-usage'],
  });
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  const logs = [];
  const apiCalls = [];
  page.on('console', (msg) => { const t = msg.text(); if (!/Failed to load resource/.test(t)) logs.push(`[console.${msg.type()}] ${t}`); });
  page.on('pageerror', (err) => logs.push(`[pageerror] ${String(err.stack || err.message).slice(0, 1500)}`));
  page.on('request', (req) => {
    const u = req.url();
    if (u.includes('/api/') || u.includes('/remote') || u.includes('events')) apiCalls.push(`REQ ${req.method()} ${u}`);
  });
  page.on('requestfailed', (req) => logs.push(`[requestfailed] ${req.url()} :: ${req.failure() && req.failure().errorText}`));
  page.on('response', (res) => {
    const u = res.url();
    if (res.status() >= 400 && (u.includes('/api/') || u.includes('events'))) logs.push(`[http ${res.status()}] ${u}`);
  });
  await page.goto('http://127.0.0.1:3080/', { waitUntil: 'domcontentloaded', timeout: 45000 }).catch((e) => logs.push(`[goto-error] ${e.message}`));
  await page.waitForTimeout(18000);

  const text = await page.evaluate(() => document.body.innerText);
  fs.writeFileSync('/home/openclaw/workspace/AgentSoftware/tools/dsh-web-probe/body.txt', text);

  const outline = await page.evaluate(() => {
    const out = [];
    const walk = (root, depth) => {
      if (depth > 14 || out.length > 900) return;
      const kids = root.children;
      for (const el of kids) {
        const tag = el.tagName;
        const cls = typeof el.className === 'string' ? el.className : '';
        const clsS = cls.split(' ').filter(c => /message|convers|chat|turn|bubble|role|tool|think|content|stream|item|entry/i.test(c)).slice(0, 8).join('.');
        const id = el.id ? '#' + el.id : '';
        if (id || clsS || ['H1','H2','H3','MAIN','ASIDE','SECTION','ARTICLE'].includes(tag)) {
          const t = (el.textContent || '').trim().replace(/\s+/g, ' ').slice(0, 140);
          out.push(`${'  '.repeat(Math.min(depth,8))}<${tag.toLowerCase()}${id}${clsS ? '.' + clsS : ''}> ${t}`);
        }
        walk(el, depth + 1);
      }
    };
    walk(document.body, 0);
    return out;
  });
  fs.writeFileSync('/home/openclaw/workspace/AgentSoftware/tools/dsh-web-probe/outline.txt', outline.join('\n'));
  console.log('=== TEXT LENGTH ===', text.length);
  console.log('=== outline length ===', outline.length);
  console.log('=== API CALLS ===');
  const seen = new Set();
  for (const c of apiCalls) { if (!seen.has(c)) { seen.add(c); console.log(c.slice(0, 220)); } }
  console.log('=== LOGS ===');
  for (const l of logs) console.log(l.slice(0, 1000));
  await browser.close();
})().catch((e) => { console.error('FATAL', e); process.exit(1); });
