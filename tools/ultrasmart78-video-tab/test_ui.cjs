// Exercises the complete main_ui.js extracted from the resulting APK.
// YouTube responses are mocked; this verifies integration, not live playback.
const {chromium} = require('playwright');
const assert = require('node:assert/strict');
const fs = require('node:fs');

(async () => {
  const browser = await chromium.launch({headless: true, executablePath: process.env.IL_CHROME_PATH || undefined, args: ['--no-sandbox']});
  try {
    const context = await browser.newContext({viewport: {width: 360, height: 740}});
    const page = await context.newPage();
    const requests = [];
    const errors = [];
    const expectBlocked = process.argv.includes('--expect-csp-block');
    if (!process.argv[3]) throw new Error('The real native HTML/CSP fixture is required');
    await context.addInitScript(() => {
      window.__ilCspEvents = [];
      document.addEventListener('securitypolicyviolation', e => window.__ilCspEvents.push({directive:e.effectiveDirective, uri:e.blockedURI}));
    });
    page.on('pageerror', e => errors.push(e.message));
    await context.route('**/*', async route => {
      const request = route.request();
      if (request.url() === 'https://ispinalokalnie.wordpress.com/') {
        return route.fulfill({contentType: 'text/html', body: fs.readFileSync(process.argv[3], 'utf8')});
      }
      if (request.url().includes('/wp-json/wp/v2/pages?slug=galeria')) {
        const content = '<p>Galeria łączy zdjęcia archiwalne i współczesne.</p><p><a href="/historia-ispiny/">Poznaj historię Ispiny</a> · <a href="/kontakt/">Przekaż fotografię lub wspomnienie</a> · <a href="#wideo">Wideo</a></p><h2 id="wideo">Wideo</h2><p>Trzy nagrania związane z Ispiną.</p><h3>Archiwalny materiał TVP Kraków o pacyfikacji Ispiny</h3><h3>Ispina w programie Jedź Bezpiecznie</h3><h3>Ispina w TVN Turbo</h3><h2>Ispina współcześnie</h2><figure><img src="https://images.invalid/photo.jpg" alt="Ispina"></figure><a href="/archiwum/">Inne zdjęcia</a>';
        return route.fulfill({contentType:'application/json', body:JSON.stringify([{id:1, title:{rendered:'Ispina na zdjęciach'}, content:{rendered:content}}])});
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
    if (expectBlocked) {
      await page.waitForFunction(() => window.__ilCspEvents.some(e => /youtube/.test(e.uri)));
      assert.equal(requests.length, 0, 'original native CSP must reproduce the reported failure');
      console.log('PASS: reproduced ERR_BLOCKED_BY_CSP with the original native HTML shell');
      return;
    }
    assert.equal(await page.locator('#sec').innerText(), 'Wideo');
    assert.equal(await page.locator('#iaD').evaluate(e => e.classList.contains('on')), false);
    assert.equal(new URL(page.url()).hostname, 'ispinalokalnie.wordpress.com');
    const urls = await page.locator(video + ' iframe').evaluateAll(frames => frames.map(f => f.src));
    assert.deepEqual(urls.map(u => new URL(u).pathname.split('/').pop()).sort(), ['ZG7I26z3nZs', 'uH6bZTGhFdE', 'uY9qCLdLzuY'].sort());
    const cards = await page.locator(video + ' article.ilv-card').evaluateAll(items => items.map(card => ({
      title: card.querySelector('h3').textContent.trim(),
      id: new URL(card.querySelector('iframe').getAttribute('data-src')).pathname.split('/').pop()
    })));
    assert.deepEqual(cards, [
      {title: 'Pacyfikacja Ispiny - materiał TVP Kraków', id: 'ZG7I26z3nZs'},
      {title: 'Most w Ispinie - materiał TVP Kraków', id: 'uH6bZTGhFdE'},
      {title: 'Szykany przy moście w Ispinie - materiał TVN Turbo', id: 'uY9qCLdLzuY'}
    ], 'each video card title must match its selected YouTube video');
    for (const width of [320, 360, 800]) {
      await page.setViewportSize({width, height: 740});
      for (const frame of await page.locator(video + ' iframe').all()) {
        const box = await frame.boundingBox();
        assert.ok(box.width >= 200 && box.height >= 200, 'YouTube minimum viewport');
      }
      assert.equal(await page.locator(video).evaluate(e => e.scrollWidth <= e.clientWidth), true);
    }
    assert.equal(requests.length, 3);
    assert.equal(await page.evaluate(() => window.__ilCspEvents.filter(e => /youtube/.test(e.uri)).length), 0, 'YouTube must not be blocked by the real native CSP');
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
    await page.click('#iaM');
    await page.click('#nav .nb[data-u$="/galeria/"]');
    await page.waitForSelector('#ilpBody1851 img');
    const galleryBody = page.locator('#ilpBody1851');
    assert.equal(await galleryBody.locator('a').filter({hasText:/Poznaj historię Ispiny|Przekaż fotografię lub wspomnienie|^Wideo$/}).count(), 0);
    assert.equal(await galleryBody.locator('h2').filter({hasText:/^Wideo$/}).count(), 0);
    assert.equal(await galleryBody.locator('h3').count(), 0);
    assert.equal(await galleryBody.locator('img').count(), 1);
    assert.equal(await galleryBody.locator('a').filter({hasText:'Inne zdjęcia'}).count(), 1);
    await page.click('#ilpRefresh1851');
    await page.waitForTimeout(200);
    assert.equal(await galleryBody.locator('a').filter({hasText:/Poznaj historię Ispiny|Przekaż fotografię lub wspomnienie|^Wideo$/}).count(), 0);
    await page.evaluate(() => {
      const f=document.createElement('iframe');f.src='https://untrusted.invalid/';document.body.appendChild(f);
    });
    await page.waitForFunction(() => window.__ilCspEvents.some(e => e.directive==='frame-src' && /untrusted/.test(e.uri)));
    assert.ok(!errors.some(e => /__IL_ULTRASMART_WIDEO|ilWideo/.test(e)), errors.join('\n'));
    console.log('PASS: real native CSP permits three YouTube embeds and blocks other frames; UI, gallery cleanup, Referer, layout, stop-on-exit, Back');
  } finally {
    await browser.close();
  }
})().catch(e => {console.error(e); process.exitCode = 1;});
