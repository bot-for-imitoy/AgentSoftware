// Probe 7: DOM state of expandable nodes (aria-expanded, details) in the opened conversation.
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
  await page.waitForTimeout(12000);
  const report = await page.evaluate(() => {
    const out = [];
    const els = Array.from(document.querySelectorAll('[aria-expanded], details, [role="button"], button'));
    for (const el of els) {
      const t = (el.textContent || '').trim().replace(/\s+/g, ' ').slice(0, 80);
      const cls = String(el.className || '').slice(0, 110);
      out.push({ tag: el.tagName, role: el.getAttribute('role'), exp: el.getAttribute('aria-expanded'), cls, t });
    }
    // detect scrollable chat area and whether tool/think rows are open by looking at line-clamp
    const clamps = [];
    for (const el of Array.from(document.querySelectorAll('*'))) {
      const cs = getComputedStyle(el);
      if (cs.webkitLineClamp && cs.webkitLineClamp !== 'none') {
        const t = (el.textContent || '').trim().replace(/\s+/g, ' ').slice(0, 70);
        clamps.push(`${el.tagName} clamp=${cs.webkitLineClamp} ${t}`);
      }
    }
    return { els: out.slice(0, 160), clamps: clamps.slice(0, 60), nButtons: out.length };
  });
  fs.writeFileSync('/home/openclaw/workspace/AgentSoftware/tools/dsh-web-probe/expandables.txt',
    '=== EXPANDABLES ===\n' + report.els.map((e) => `${e.tag}<${e.role || ''} exp=${e.exp} ${e.cls}> ${e.t}`).join('\n') + '\n=== CLAMPS ===\n' + report.clamps.join('\n'));
  console.log('written', report.nButtons);
  await browser.close();
})().catch((e) => { console.error('FATAL', e); process.exit(1); });
