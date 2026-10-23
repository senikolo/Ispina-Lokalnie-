const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');
const {pathToFileURL} = require('node:url');
const ts = require('typescript');

test('actual PNG renderer serves both responsive cards, full widget, and outage card', async () => {
  const route = path.join(__dirname, '../app/api/pogoda.png/route.ts');
  const temporary = path.join(path.dirname(route), `.render-test-${process.pid}.mjs`);
  let source = ts.transpileModule(await fs.readFile(route, 'utf8'), {compilerOptions:{module:ts.ModuleKind.ESNext, target:ts.ScriptTarget.ES2022}}).outputText;
  source = source.replace('"next/og"', JSON.stringify(pathToFileURL(require.resolve('next/og')).href));
  const originalFetch = global.fetch;
  try {
    await fs.writeFile(temporary, source);
    const {fixture} = await import('./fixtures/weather.mjs');
    global.fetch = async () => ({ok:true, json:async()=>fixture(Date.now())});
    const {GET} = await import(pathToFileURL(temporary).href + '?ok');
    for (const [query,width,height] of [['?part=now',600,550],['?part=daily',600,610],['?layout=compact',600,1080],['',1200,500]]) {
      const response = await GET(new Request('https://weather.example/api/pogoda'+query));
      assert.equal(response.headers.get('content-type'),'image/png');
      assert.equal(response.headers.get('x-weather-status'),'ok');
      const bytes = Buffer.from(await response.arrayBuffer());
      assert.equal(bytes.subarray(1,4).toString(),'PNG');
      assert.equal(bytes.readUInt32BE(16),width); assert.equal(bytes.readUInt32BE(20),height);
      assert.ok(bytes.length > 5000);
    }
    global.fetch = async () => {throw new Error('simulated Open-Meteo outage');};
    const failed = await import(pathToFileURL(temporary).href + '?outage');
    const response = await failed.GET(new Request('https://weather.example/api/pogoda?part=now'));
    assert.equal(response.status,200); // A readable image even during an API outage.
    assert.equal(response.headers.get('x-weather-status'),'unavailable');
    assert.equal(response.headers.get('vercel-cdn-cache-control'),'no-store');
    assert.equal(response.headers.get('x-weather-data-time'),null);
    assert.equal(Buffer.from(await response.arrayBuffer()).subarray(1,4).toString(),'PNG');
  } finally {global.fetch = originalFetch; await fs.rm(temporary, {force:true});}
});
