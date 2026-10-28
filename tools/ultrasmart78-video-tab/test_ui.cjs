// Complete native HTML/CSP + complete main_ui.js from the patched APK.
// Network responses and the documented IFrame Player interface are mocked.
const {chromium} = require('playwright');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const CHANNEL = 'UCeeKoKHiH-rGyQOZn6wW-7A';
const CACHE = 'il-youtube-uploads-v1-' + CHANNEL;
const PLAYER_API = `
window.__ytCalls=[];
window.YT={Player:function(frame,options){
 const self=this; const events=options.events; self.events=events; self.frame=frame;
 self.id=1+window.__ytCalls.filter(c=>c.method==='create').length;
 frame.dataset.instance=String(self.id); window.__ytCurrent=self;
 function log(method,id){window.__ytCalls.push({method,id,instance:self.id});}
 log('create');
 self.cueVideoById=function(id){log('cue',id);events.onStateChange({data:5});};
 self.loadVideoById=function(id){log('load',id);events.onStateChange({data:1});};
 self.destroy=function(){log('destroy');frame.remove();};
 setTimeout(()=>events.onReady({target:self}),30);
}};
window.onYouTubeIframeAPIReady();`;

(async () => {
 const browser = await chromium.launch({headless:true, executablePath:process.env.IL_CHROME_PATH || undefined, args:['--no-sandbox']});
 try {
  const context = await browser.newContext({viewport:{width:360,height:740}});
  const page = await context.newPage();
  const requests = [], errors = [], apiRequests = [];
  let mode = 'ok', revision = 0, scriptFail = false;
  const id = n => String(n).padStart(11,'0');
  const video = n => ({id:id(n),title:n === 5 ? '<img src=x onerror="window.XSS=1">' : 'Film kanału ' + n,
    publishedAt:'2026-10-28T10:00:00Z',embeddable:n !== 3});
  const response = (token) => ({schema:1,channel:{id:CHANNEL,title:'Ispina Lokalnie'},uploadsPlaylistId:'UUfromOfficialApiNotDerived',
    fetchedAt:new Date().toISOString(),items:mode === 'empty' ? [] : token ? [video(3),video(4)] : revision ? [video(5),video(1),video(2)] : [video(1),video(2)],
    nextPageToken:mode === 'empty' || token ? null : 'PAGE_2'});
  const expectBlocked = process.argv.includes('--expect-csp-block');
  if (!process.argv[3]) throw new Error('Native HTML/CSP fixture is required');
  await context.addInitScript(() => {
   window.__ilCspEvents=[];
   document.addEventListener('securitypolicyviolation',e=>window.__ilCspEvents.push({directive:e.effectiveDirective,uri:e.blockedURI}));
  });
  page.on('pageerror',e=>errors.push(e.message));
  await context.route('**/*',async route=>{
   const request=route.request(),url=request.url();
   if(url==='https://ispinalokalnie.wordpress.com/')return route.fulfill({contentType:'text/html',body:fs.readFileSync(process.argv[3],'utf8')});
   if(url.startsWith('https://ispina-most-odjazdy.vercel.app/api/youtube-videos')){
    apiRequests.push(url);
    if(mode==='slow')await new Promise(r=>setTimeout(r,300));
    if(mode==='fail')return route.abort();
    if(mode==='setup')return route.fulfill({status:503,contentType:'application/json',headers:{'Access-Control-Allow-Origin':'*'},body:'{"error":"not_configured"}'});
    const token=new URL(url).searchParams.get('pageToken');
    const payload=response(token);
    if(mode==='repeat')payload.nextPageToken='PAGE_2';
    return route.fulfill({contentType:'application/json',headers:{'Access-Control-Allow-Origin':'*'},body:JSON.stringify(payload)}).catch(()=>{});
   }
   if(url==='https://www.youtube.com/iframe_api')return scriptFail ? route.abort() : route.fulfill({contentType:'application/javascript',body:PLAYER_API});
   if(url.startsWith('https://www.youtube-nocookie.com/embed/')){
    requests.push({url,headers:await request.allHeaders()});
    return route.fulfill({contentType:'text/html',body:'<!doctype html><title>Mock YouTube</title>'});
   }
   if(url.includes('/wp-json/wp/v2/pages?slug=galeria'))return route.fulfill({contentType:'application/json',body:JSON.stringify([{id:1,title:{rendered:'Galeria'},content:{rendered:'<p><a href="/historia-ispiny/">Poznaj historię Ispiny</a> · <a href="/kontakt/">Przekaż fotografię lub wspomnienie</a> · <a href="#wideo">Wideo</a></p><h2>Wideo</h2><h3>Stary film</h3><h2>Zdjęcia</h2><img src="https://images.invalid/photo.jpg"><a href="/archiwum/">Inne zdjęcia</a>'}}])});
   return route.abort();
  });
  async function init(){await page.goto('https://ispinalokalnie.wordpress.com/');await page.addScriptTag({content:fs.readFileSync(process.argv[2],'utf8')});await page.waitForSelector('#iaBar');await page.waitForTimeout(1500);}
  async function open(){await page.click('#iaM');await page.click('#ilWideoTabSmart78');await page.waitForSelector('#ilWideoViewSmart78.on');}
  async function close(){await page.click('#iaM');await page.click('#nav .nb[data-u=":dash"]');await page.waitForFunction(()=>!document.querySelector('#ilWideoViewSmart78').classList.contains('on'));await page.waitForTimeout(30);}
  async function loaded(count){await page.waitForFunction(n=>document.querySelectorAll('#ilvList li').length===n && !document.querySelector('#ilvRefresh').disabled,count);}
  async function ready(){await page.waitForFunction(()=>window.__ytCalls && window.__ytCalls.some(c=>c.method==='cue'));}
  await init(); assert.equal(apiRequests.length,0); assert.equal(requests.length,0);
  await open(); await loaded(4); await ready();
  assert.equal(await page.locator('#ilvFrame iframe').count(),1);
  if(expectBlocked){
   await page.waitForFunction(()=>window.__ilCspEvents.some(e=>/youtube-nocookie/.test(e.uri)));
   assert.equal(requests.length,0);
   console.log('PASS: original native CSP reproduces blocked YouTube player');return;
  }
  assert.equal(await page.locator('#sec').innerText(),'Wideo');
  assert.equal(apiRequests.length,2,'must read every page, beyond the old three-video list');
  assert.equal(await page.locator('#ilvList .ilv-select:disabled').count(),1);
  assert.equal(await page.evaluate(()=>window.__ytCalls.filter(c=>c.method==='load').length),0,'opening cannot start sound');
  const initialFrame=await page.locator('#ilvFrame iframe').getAttribute('data-instance');
  await page.locator('#ilvList .ilv-select').nth(1).click();
  assert.equal(await page.locator('#ilvFrame iframe').getAttribute('data-instance'),initialFrame);
  assert.equal(await page.evaluate(()=>window.__ytCalls.at(-1).id),id(2));
  await page.evaluate(()=>window.__ytCurrent.events.onStateChange({data:0}));
  assert.equal(await page.evaluate(()=>window.__ytCalls.at(-1).id),id(4),'ended skips a non-embeddable item');
  assert.equal(await page.locator('#ilvNext').isDisabled(),true);
  await page.evaluate(()=>window.__ytCurrent.events.onStateChange({data:0}));
  assert.match(await page.locator('#ilvPlayerStatus').innerText(),/Koniec playlisty/);
  await page.click('#ilvPrevious');
  assert.equal(await page.evaluate(()=>window.__ytCalls.at(-1).id),id(2));
  assert.equal(await page.locator('#ilvFrame iframe').count(),1);
  revision=1; await page.click('#ilvRefresh'); await loaded(5);
  assert.equal(await page.locator('#ilvFrame iframe').getAttribute('data-instance'),initialFrame,'refresh must preserve player');
  assert.equal(await page.locator('#ilvList li').first().innerText().then(s=>s.includes('<img')),true);
  assert.equal(await page.locator('#ilvList img').count(),0,'YouTube titles are text, never HTML');
  assert.equal(await page.evaluate(()=>window.XSS),undefined);
  const previousCalls=apiRequests.length; mode='fail'; await page.click('#ilvRefresh'); await loaded(5);
  assert.ok(apiRequests.length>previousCalls); assert.match(await page.locator('#ilvStatus').innerText(),/zapisaną listę/);
  await page.evaluate(()=>window.__ytCurrent.events.onError({data:153}));
  assert.match(await page.locator('#ilvPlayerStatus').innerText(),/153/);
  await page.evaluate(()=>window.__ytCurrent.events.onAutoplayBlocked());
  assert.match(await page.locator('#ilvPlayerStatus').innerText(),/dotknięcia/);
  for(const width of [320,360,800]){
   await page.setViewportSize({width,height:740});const box=await page.locator('#ilvFrame iframe').boundingBox();
   assert.ok(box.width>=200 && box.height>=200);assert.equal(await page.locator('#ilWideoViewSmart78').evaluate(e=>e.scrollWidth<=e.clientWidth),true);
  }
  assert.equal(requests[0].headers.referer,'https://ispinalokalnie.wordpress.com/');
  assert.equal(new URL(requests[0].url).searchParams.get('autoplay'),'0');
  assert.equal(new URL(requests[0].url).searchParams.get('origin'),'https://ispinalokalnie.wordpress.com');
  const historyLength=await page.evaluate(()=>history.length);
  await open(); await loaded(5); assert.equal(await page.evaluate(()=>history.length),historyLength);
  await close(); assert.equal(await page.locator('#ilvFrame iframe').count(),0);
  const beforeReopen=apiRequests.length;mode='ok';await open();await loaded(5);
  await page.waitForFunction(()=>window.__ytCalls.filter(c=>c.method==='create').length===2);
  await page.waitForTimeout(50);
  assert.ok(apiRequests.length>beforeReopen);
  assert.equal(await page.evaluate(()=>window.__ytCalls.at(-1).method),'cue','returning cannot resume sound');
  await page.evaluate(()=>{Object.defineProperty(document,'hidden',{configurable:true,value:true});document.dispatchEvent(new Event('visibilitychange'));});
  assert.equal(await page.locator('#ilvFrame iframe').count(),0);
  await page.evaluate(()=>{Object.defineProperty(document,'hidden',{configurable:true,value:false});document.dispatchEvent(new Event('visibilitychange'));});
  await loaded(5);await page.waitForSelector('#ilvFrame iframe');
  await page.evaluate(()=>dispatchEvent(new PopStateEvent('popstate')));
  await page.waitForFunction(()=>!document.querySelector('#ilWideoViewSmart78').classList.contains('on'));
  assert.equal(await page.locator('#ilvFrame iframe').count(),0);
  mode='slow';await open();await close();await page.waitForTimeout(400);
  assert.equal(await page.locator('#ilvFrame iframe').count(),0,'late responses cannot resurrect a closed player');
  mode='repeat';await open();await loaded(5);assert.match(await page.locator('#ilvStatus').innerText(),/zapisaną listę/);
  mode='empty';await page.click('#ilvRefresh');await loaded(0);assert.equal(await page.locator('#ilvFrame iframe').count(),0);
  mode='fail';await init();await open();await loaded(0);assert.equal(await page.locator('#ilvFrame iframe').count(),0);
  await page.evaluate(({cache,channel})=>localStorage.setItem(cache,JSON.stringify({schema:1,channel:{id:channel},fetchedAt:new Date(Date.now()-25*3600000).toISOString(),items:[{id:'00000000001',title:'expired',embeddable:true}]})),{cache:CACHE,channel:CHANNEL});
  await init();await open();await loaded(0);assert.match(await page.locator('#ilvStatus').innerText(),/Nie udało się pobrać/);
  assert.equal(await page.evaluate(cache=>localStorage.getItem(cache),CACHE),null);
  mode='setup';await page.click('#ilvRefresh');await loaded(0);assert.match(await page.locator('#ilvStatus').innerText(),/nie jest jeszcze dostępna/);
  scriptFail=true;mode='ok';await init();await open();await loaded(5);
  await page.waitForFunction(()=>document.querySelector('#ilvPlayerStatus').textContent.includes('nie może'));
  assert.equal(await page.locator('#ilvFrame iframe').count(),0);
  scriptFail=false;await page.click('#ilvRetry');await page.waitForSelector('#ilvFrame iframe');
  await close();await page.click('#iaM');await page.click('#nav .nb[data-u$="/galeria/"]');await page.waitForSelector('#ilpBody1851 img');
  const gallery=page.locator('#ilpBody1851');assert.equal(await gallery.locator('h3').count(),0);assert.equal(await gallery.locator('img').count(),1);
  await page.evaluate(()=>{let f=document.createElement('iframe');f.src='https://untrusted.invalid/';document.body.appendChild(f);});
  await page.waitForFunction(()=>window.__ilCspEvents.some(e=>e.directive==='frame-src' && /untrusted/.test(e.uri)));
  assert.ok(!errors.some(e=>/ilv|ilWideo|IL_ULTRASMART_YOUTUBE/.test(e)),errors.join('\n'));
  console.log('PASS: one player, all pages, selection, sequential playback, refresh/new video, text safety, offline/expiry/empty/setup/error states, visibility, races, Back, layout, Referer and native CSP');
 } finally { await browser.close(); }
})().catch(e=>{console.error(e);process.exitCode=1;});
