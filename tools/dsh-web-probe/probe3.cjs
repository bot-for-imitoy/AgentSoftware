// Probe 3: capture postData + response bodies for the session/history/event APIs.
const { chromium } = require('/home/openclaw/ai/deepseek-harness/apps/web/node_modules/playwright');
const fs = require('fs');

(async () => {
  const browser = await chromium.launch({
    headless: true,
    executablePath: '/home/openclaw/.cache/ms-playwright/chromium-1234/chrome-linux64/chrome',
    args: ['--no-sandbox', '--disable-dev-shm-usage'],
  });
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  const out = [];
  const capture = async (req, kind) => {
    const u = req.url();
    try {
      let body = '';
      if (kind === 'req') body = (await req.postData()) || '';
      else { const r = await req.response(); body = await r.text(); }
      if (u.includes('/api/session') || u.includes('/api/events') || u.includes('history') || u.includes('events.mux') || u.includes('describe') || u.includes('subagent')) {
        out.push(`\n### ${kind.toUpperCase()} ${req.method()} ${u}\n` + body.slice(0, 6000));
      }
    } catch (e) { /* ignore */ }
  };
  page.on('request', (req) => { if (req.resourceType() === 'xhr' || req.resourceType() === 'fetch') capture(req, 'req'); });
  page.on('response', async (res) => {
    const req = res.request();
    if ((req.resourceType() === 'xhr' || req.resourceType() === 'fetch') && res.status() === 200) capture(req, 'res');
  });
  // websocket frames
  page.on('websocket', (ws) => {
    out.push(`\n### WS ${ws.url()}`);
    ws.on('framesent', (f) => out.push(`WS> ${String(f.payload).slice(0, 1500)}`));
    ws.on('framereceived', (f) => out.push(`WS< ${String(f.payload).slice(0, 1500)}`));
  });
  await page.goto('http://127.0.0.1:3080/', { waitUntil: 'domcontentloaded', timeout: 45000 }).catch((e) => out.push(`[goto-error] ${e.message}`));
  await page.waitForTimeout(25000);
  fs.writeFileSync('/home/openclaw/workspace/AgentSoftware/tools/dsh-web-probe/api-traffic.txt', out.join('\n'));
  console.log('written', out.length, 'chars; ws count', out.filter(l => l.startsWith('### WS')).length);
  await browser.close();
})().catch((e) => { console.error('FATAL', e); process.exit(1); });
