const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const {createRequire}=require('node:module');
const runtime=createRequire(process.env.CODEX_PRIMARY_RUNTIME_NODE_MODULES+'/noop.js');
const {chromium}=runtime('playwright');
const root=__dirname,source=path.resolve(process.argv[2]||path.join(root,'assets/main_ui.js'));
const output=path.resolve(process.argv[3]||path.join(root,'visual-safe-picker'));
const ui=fs.readFileSync(source,'utf8'),build=JSON.parse(ui.split('var info=')[1].split(';try{var b=')[0]);
const mld=JSON.parse(fs.readFileSync(path.join(root,'test-data/mld-embedded.json'))),gminna=JSON.parse(fs.readFileSync(path.join(root,'test-data/linia-gminna.json')));
fs.mkdirSync(output,{recursive:true});
let browser;
async function setup(viewport,senior=false){
 const context=await browser.newContext({viewport,hasTouch:true,isMobile:true,deviceScaleFactor:1,timezoneId:'Europe/Warsaw'}),page=await context.newPage(),errors=[];
 page.on('pageerror',e=>errors.push(e.message));
 await page.route('**/*',route=>route.fulfill({status:200,contentType:route.request().resourceType()==='document'?'text/html':'application/json',body:route.request().resourceType()==='document'?'<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"></head><body><main></main></body></html>':'{}'}));
 await page.addInitScript(({build,mld,gminna})=>{const core={getAppInfoJson:()=>JSON.stringify(build),getScheduleSyncStatus:()=>JSON.stringify({mldLastSuccess:Date.now(),gminnaLastSuccess:Date.now()}),getMldScheduleJson:()=>JSON.stringify(mld),getGminnaScheduleJson:()=>JSON.stringify(gminna),getMldRealtimeJson:()=>'',getLatestPostJson:()=>'',getWasteData:()=>''};window.IspinaNative=new Proxy(core,{get:(o,k)=>o[k]||(()=> '')});},{build,mld,gminna});
 await page.goto('https://ispinalokalnie.wordpress.com/');await page.addScriptTag({path:source});await page.waitForTimeout(400);
 if(senior){await page.locator('#s0').evaluate(e=>{e.checked=true;e.onchange()});await page.waitForTimeout(1400);}
 await page.locator('#nav [data-u=":plan"]').evaluate(e=>e.click());await page.waitForTimeout(350);
 return {context,page,errors};
}
const metrics=page=>page.evaluate(()=>{const f=document.getElementById('pfrom'),r=document.getElementById('pfrom-review'),box=document.getElementById('psfrom'),b=r.querySelector('.il-r12-accept');return {width:innerWidth,height:innerHeight,bodyWidth:document.documentElement.scrollWidth,value:f.value,expanded:f.getAttribute('aria-expanded'),readonly:f.readOnly,body:document.body.className,editing:document.getElementById('iaP').className,navDisplay:getComputedStyle(document.getElementById('ilDayBottom')).display,accepted:f.closest('.plfield').classList.contains('il-r12-confirmed'),review:b.getBoundingClientRect().toJSON(),card:f.closest('.plcard').getBoundingClientRect().toJSON(),list:box.getBoundingClientRect().toJSON(),rowHeights:Array.from(box.querySelectorAll('.plsug')).map(x=>x.getBoundingClientRect().height),selected:Array.from(box.querySelectorAll('.il-r15-chosen')).map(x=>decodeURIComponent(x.dataset.v)),acceptText:b.textContent};});
(async()=>{
 browser=await chromium.launch({headless:true,executablePath:process.env.IL_CHROME_PATH,args:['--no-sandbox']});
 const all=[];
 for(const [name,viewport,senior] of [['normal',{width:390,height:844},false],['small-keyboard',{width:320,height:420},false],['senior',{width:390,height:640},true]]){
  if(process.env.IL_VISUAL_CASE&&name!==process.env.IL_VISUAL_CASE)continue;
  const {context,page,errors}=await setup(viewport,senior);
  await page.locator('#pfrom').fill('Isp');await page.waitForTimeout(180);
  const before=await metrics(page);assert(before.rowHeights.every(h=>h>=(senior?78:66)),JSON.stringify({name,before}));assert(before.bodyWidth<=viewport.width+1);
  await page.screenshot({path:path.join(output,name+'-01-suggestions.png')});
  const stop=page.locator('#psfrom .plsug').filter({hasText:'Ispina Most'}).first();await stop.scrollIntoViewIfNeeded();
  if(name==='normal'){
   const list=await page.locator('#psfrom').boundingBox(),row=await stop.boundingBox(),session=await context.newCDPSession(page);
   const x=row.x+Math.min(60,row.width/2),y=Math.max(list.y+18,Math.min(row.y+row.height/2,list.y+list.height-18));
   await session.send('Input.dispatchTouchEvent',{type:'touchStart',touchPoints:[{x,y,id:1}]});
   for(let i=1;i<=5;i++){await session.send('Input.dispatchTouchEvent',{type:'touchMove',touchPoints:[{x,y:y-i*13,id:1}]});await page.waitForTimeout(25);}
   await session.send('Input.dispatchTouchEvent',{type:'touchEnd',touchPoints:[]});await page.waitForTimeout(150);
   assert.equal(await page.locator('#pfrom').inputValue(),'Isp','Native touch swipe must not select a stop');
   await session.detach();
  }
  await stop.tap();await page.waitForTimeout(350);const pending=await metrics(page);
  assert.equal(pending.value,'Ispina Most');assert.equal(pending.expanded,'true');assert.equal(pending.accepted,false);assert.equal(pending.acceptText,'Akceptuj: Ispina Most');assert.deepEqual(pending.selected,['Ispina Most']);
  assert(pending.review.width>viewport.width*.7&&pending.review.height>=54);assert(pending.review.x>=0&&pending.review.right<=viewport.width);
  await page.locator('#pfrom-review .il-r12-accept').scrollIntoViewIfNeeded();await page.screenshot({path:path.join(output,name+'-02-awaiting-accept.png')});
  await page.locator('#pfrom-review .il-r12-accept').tap();await page.waitForTimeout(140);await page.screenshot({path:path.join(output,name+'-03-confirmation.png')});await page.waitForTimeout(800);
  assert.equal(await page.locator('#pfrom').getAttribute('aria-readonly'),'true');
  const locked=await page.locator('#pfrom').inputValue();await page.locator('#pfrom').pressSequentially('Przypadek');assert.equal(await page.locator('#pfrom').inputValue(),locked);
  await page.locator('#pto').fill('Czyżyny Dworzec');await page.locator('#pto-review .il-r12-accept').tap();await page.waitForTimeout(1100);
  await page.screenshot({path:path.join(output,name+'-04-accepted.png')});
  await page.locator('#pfrom-review .il-r15-change').tap();await page.waitForTimeout(150);
  const changed=await metrics(page);assert.equal(changed.readonly,false);assert.equal(changed.accepted,false);assert.equal(changed.expanded,'true');assert.equal(await page.locator('#pto').getAttribute('aria-readonly'),'true');
  assert.deepEqual(errors,[]);all.push({name,viewport,senior,before,pending,changed,errors,status:'PASS'});await context.close();
 }
 fs.writeFileSync(path.join(output,'result.json'),JSON.stringify({source,build,tests:all},null,2)+'\n');console.log(JSON.stringify({passed:all.length,tests:all.map(x=>({name:x.name,status:x.status,heights:x.before.rowHeights,acceptText:x.pending.acceptText}))}));await browser.close();
})().catch(async e=>{console.error(e);if(browser)await browser.close();process.exitCode=1});
