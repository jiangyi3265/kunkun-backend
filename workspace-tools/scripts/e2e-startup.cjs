// Verify repeat navigation works without unused third-party authentication SDKs.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { chromium } = require('node:module').createRequire(process.env.APPDATA + '/npm/node_modules/')('playwright');
const env = require('./test-env.cjs');
const out = require('./test-output.cjs').dir();
let browser;
(async () => {
  browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: 390, height: 844 } });
  const external = [], errors = [], checks = [];
  page.on('pageerror', e => errors.push(e.message));
  await page.route('**/*', async route => {
    const url = new URL(route.request().url());
    if (['http:', 'https:'].includes(url.protocol) && url.origin !== env.mini) {
      external.push(url.origin + url.pathname);
      await route.abort();
    } else await route.continue();
  });
  for (const route of ['auth/index', 'index/index', 'auth/index']) {
    const started = Date.now();
    await page.goto(env.mini + '/#/pages/' + route, { waitUntil: 'domcontentloaded', timeout: 20000 });
    await page.getByText(route === 'auth/index' ? '把时间，留给热爱。' : '找高手，上分更轻松', { exact: true }).waitFor();
    assert.equal(await page.locator('script[src*="cloudauth"]').count(), 0);
    checks.push({ route, elapsedMs: Date.now() - started });
  }
  assert.deepEqual(external, [], 'App startup must not require external SDKs');
  assert.deepEqual(errors, []);
  await page.screenshot({ path: path.join(out, 'recheck-startup.png'), fullPage: true });
  const result = { passed: true, checks, external, errors };
  fs.writeFileSync(path.join(out, 'recheck-startup.json'), JSON.stringify(result, null, 2));
  console.log(JSON.stringify(result, null, 2));
})().catch(e => { console.error(e); process.exitCode = 1; }).finally(async () => browser?.close());
