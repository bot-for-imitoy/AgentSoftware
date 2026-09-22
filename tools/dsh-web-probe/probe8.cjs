// Probe 8: click a tool row in the open conversation; capture what becomes visible.
const { chromium } = require('/home/openclaw/ai/deepseek-harness/apps/web/node_modules/playwright');
const fs = require('fs');

(async () => {
  const browser = await chromium.launch({
    headless: true,
    executablePath: '/home/openclaw/.cache/ms-playwright/chromium-1234/chrome-linux64/chrome',
    args: ['--no-sandbox', '--disable-dev-shm-usage'],
  });
  const page = await browser.newPage({ viewport: { width: 1500, height: 950 } });
  await page.goto('http://127.0.0.1:3080/', { waitUntil: 'domcontentloaded', timeout: 45000 }).catch(() => {});
  await page.waitForTimeout(14000);
  await page.evaluate(() => {
    const target = '修复Web界面消息显示';
    const all = Array.from(document.querySelectorAll('*'));
    for (const el of all) {
      const txt = (el.textContent || '').trim();
      if (txt === target && el.children.length <= 2) { el.click(); return; }
    }
  });
  await page.waitForTimeout(10000);
  const before = await page.evaluate(() => document.body.innerText);
  // click the LAST Bash tool row (running-adjacent) - find rows whose class hints tool root e7LVCG_root
  const clicked = await page.evaluate(() => {
    const rows = Array.from(document.querySelectorAll('[class*="e7LVCG_root"]'));
    const row = rows[rows.length - 1];
    if (row) { row.click(); return true; }
    return false;
  });
  await page.waitForTimeout(6000);
  const after = await page.evaluate(() => document.body.innerText);
  fs.writeFileSync('/home/openclaw/workspace/AgentSoftware/tools/dsh-web-probe/tool-click-before.txt', before);
  fs.writeFileSync('/home/openclaw/workspace/AgentSoftware/tools/dsh-web-probe/tool-click-after.txt', after);
  // find the details panel content region (right side)
  const detailRegion = await page.evaluate(() => {
    const texts = [];
    const els = Array.from(document.querySelectorAll('*'));
    for (const el of els) {
      const r = el.getBoundingClientRect();
      const cls = String(el.className || '');
      if (r.width > 250 && r.x > 1100 && /panel|detail|pane/i.test(cls)) {
        texts.push(`<${el.tagName} ${cls.slice(0, 90)} x=${Math.round(r.x)} y=${Math.round(r.y)}> ${(el.textContent || '').trim().replace(/\s+/g, ' ').slice(0, 200)}`);
      }
    }
    return texts.slice(0, 40);
  });
  fs.writeFileSync('/home/openclaw/workspace/AgentSoftware/tools/dsh-web-probe/details-region.txt', detailRegion.join('\n'));
  console.log('clicked', clicked, 'before', before.length, 'after', after.length);
  await browser.close();
})().catch((e) => { console.error('FATAL', e); process.exit(1); });
