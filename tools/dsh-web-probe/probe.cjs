// Probe the DSH Web GUI at 127.0.0.1:3080 with headless chromium,
// capturing console, page errors, failed requests, and rendered content.
const { chromium } = require('/home/openclaw/ai/deepseek-harness/apps/web/node_modules/playwright');

(async () => {
  const browser = await chromium.launch({
    headless: true,
    executablePath: process.env.CHROME_PATH || '/home/openclaw/.cache/ms-playwright/chromium-1234/chrome-linux64/chrome',
    args: ['--no-sandbox', '--disable-dev-shm-usage'],
  });
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  const logs = [];
  page.on('console', (msg) => logs.push(`[console.${msg.type()}] ${msg.text()}`));
  page.on('pageerror', (err) => logs.push(`[pageerror] ${err.stack || err.message}`));
  page.on('requestfailed', (req) => logs.push(`[requestfailed] ${req.url()} :: ${req.failure() && req.failure().errorText}`));
  page.on('response', (res) => {
    if (res.status() >= 400) logs.push(`[http ${res.status()}] ${res.url()}`);
  });
  const url = process.env.PROBE_URL || 'http://127.0.0.1:3080/';
  await page.goto(url, { waitUntil: 'networkidle', timeout: 45000 }).catch((e) => logs.push(`[goto-error] ${e.message}`));
  await page.waitForTimeout(12000);
  const bodyText = await page.evaluate(() => document.body ? document.body.innerText.slice(0, 6000) : '(no body)');
  const htmlLen = await page.evaluate(() => document.documentElement.outerHTML.length);
  await page.screenshot({ path: '/home/openclaw/workspace/AgentSoftware/tools/dsh-web-probe/shot.png' });
  console.log('=== BODY TEXT ===');
  console.log(bodyText);
  console.log('=== HTML LENGTH ===', htmlLen);
  console.log('=== LOGS ===');
  for (const l of logs) console.log(l);
  await browser.close();
})().catch((e) => { console.error('FATAL', e); process.exit(1); });
