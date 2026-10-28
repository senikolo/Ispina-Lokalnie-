// Exercises the complete main_ui.js extracted from the resulting APK.
// YouTube responses are mocked; this verifies integration, not live playback.
const {chromium} = require('playwright');
const assert = require('node:assert/strict');
const fs = require('node:fs');

(async () => {
  const browser = await chromium.launch({headless: true, args: ['--no-sandbox']});
  try {
    const context = await browser.newContext({viewport: {width: 360, height: 740}});
    const page = await context.newPage();
    const requests = [];
    const errors = [];
    page.on('pageerror', e => errors.push(e.message));
    await context.route('**/*', async route => {
      const request = route.request();
      if (request.url() === 'https://ispinalokalnie.wordpress.com/') {
        return route.fulfill({contentType: 'text/html', body: '<!doctype html><meta name="viewport" content="width=device-width, initial-scale=1"><title>Ispina Lokalnie</title><body><main></main></body>'});
      }
      if (request.url().startsWith('https://www.youtube-nocookie.com/embed/')) {
        requests.push({url: request.url(), headers: await request.allHeaders()});
        return route.fulfill({contentType: 'text/html', body: '<!doctype html><title>Mock player</title>'});
      }
      return route.abort();
    });
    await page.goto('https://ispinalokalnie.wordpress.com/');
    await page.addScriptTag({content: fs.readFileSync(process.argv[2], 'utf8')});
    await page.waitForSelector('#iaBar');
    // Allow the baseline's initial navigation and delayed UI enhancements.
    await page.waitForTimeout(2000);
    const video = '#ilWideoViewSmart78';
    assert.equal(await page.locator('#ilWideoTabSmart78').count(), 1);
    assert.equal(requests.length, 0, 'players must stay unloaded before opening Wideo');
    await page.click('#iaM');
    await page.click('#ilWideoTabSmart78');
    await page.waitForSelector(video + '.on');
    await page.waitForFunction(() => document.querySelectorAll('#ilWideoViewSmart78 iframe[src^="https:"]').length === 3);
    await page.waitForFunction(() => document.querySelectorAll('#ilWideoViewSmart78 iframe').length === 3);
    await page.locator(video + ' iframe').last().scrollIntoViewIfNeeded();
    await page.waitForTimeout(500);
    assert.equal(await page.locator('#sec').innerText(), 'Wideo');
    assert.equal(await page.locator('#iaD').evaluate(e => e.classList.contains('on')), false);
    assert.equal(new URL(page.url()).hostname, 'ispinalokalnie.wordpress.com');
    const urls = await page.locator(video + ' iframe').evaluateAll(frames => frames.map(f => f.src));
    assert.deepEqual(urls.map(u => new URL(u).pathname.split('/').pop()).sort(), ['ZG7I26z3nZs', 'uH6bZTGhFdE', 'uY9qCLdLzuY'].sort());
    for (const width of [320, 360, 800]) {
      await page.setViewportSize({width, height: 740});
      for (const frame of await page.locator(video + ' iframe').all()) {
        const box = await frame.boundingBox();
        assert.ok(box.width >= 200 && box.height >= 200, 'YouTube minimum viewport');
      }
      assert.equal(await page.locator(video).evaluate(e => e.scrollWidth <= e.clientWidth), true);
    }
    assert.equal(requests.length, 3);
    for (const r of requests) {
      assert.equal(r.headers.referer, 'https://ispinalokalnie.wordpress.com/');
      assert.equal(new URL(r.url).searchParams.get('origin'), 'https://ispinalokalnie.wordpress.com');
    }
    const historyLength = await page.evaluate(() => history.length);
    await page.click('#iaM');
    await page.click('#ilWideoTabSmart78');
    assert.equal(await page.evaluate(() => history.length), historyLength, 'reopening must not stack history entries');
    await page.click('#iaM');
    await page.click('#nav .nb[data-u=":dash"]');
    await page.waitForFunction(() => [...document.querySelectorAll('#ilWideoViewSmart78 iframe')].every(f => f.getAttribute('src') === 'about:blank'));
    await page.click('#iaM');
    await page.click('#ilWideoTabSmart78');
    await page.evaluate(() => dispatchEvent(new PopStateEvent('popstate')));
    await page.waitForFunction(() => !document.querySelector('#ilWideoViewSmart78').classList.contains('on'));
    await page.waitForFunction(() => [...document.querySelectorAll('#ilWideoViewSmart78 iframe')].every(f => f.getAttribute('src') === 'about:blank'));
    assert.ok(!errors.some(e => /__IL_ULTRASMART_WIDEO|ilWideo/.test(e)), errors.join('\n'));
    console.log('PASS: full UltraSmart UI, navigation, three embedded players, Referer, layout, stop-on-exit, Back');
  } finally {
    await browser.close();
  }
})().catch(e => {console.error(e); process.exitCode = 1;});
