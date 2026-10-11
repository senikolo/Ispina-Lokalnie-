const fs=require('node:fs');
const path=require('node:path');
const {createRequire}=require('node:module');
const runtime=createRequire(process.env.CODEX_PRIMARY_RUNTIME_NODE_MODULES+'/noop.js');
const {chromium}=runtime('playwright');
const root=fs.existsSync(path.join(__dirname,'test-data'))?__dirname:path.resolve(__dirname,'../il_motion_sources/r14/IL-R14');
const source=path.resolve(process.argv[2]||path.join(__dirname,'assets/main_ui.js'));
const output=path.resolve(process.argv[3]||path.join(__dirname,'visual-before'));
const build=JSON.parse(fs.readFileSync(source,'utf8').split('var info=')[1].split(';try{var b=')[0]);
fs.mkdirSync(output,{recursive:true});
const mld=JSON.parse(fs.readFileSync(path.join(root,'test-data/mld-embedded.json')));
const gminna=JSON.parse(fs.readFileSync(path.join(root,'test-data/linia-gminna.json')));
let activeBrowser;
(async()=>{
 const browser=activeBrowser=await chromium.launch({headless:true,executablePath:process.env.IL_CHROME_PATH,args:['--no-sandbox']});
 const context=await browser.newContext({viewport:{width:390,height:844},deviceScaleFactor:1,isMobile:true,hasTouch:true,reducedMotion:process.env.IL_VISUAL_OLD_PREFS==='1'?'reduce':'no-preference',timezoneId:'Europe/Warsaw'});
 const page=await context.newPage();const errors=[];
 page.on('pageerror',e=>errors.push(e.message));
 await page.route('**/*',async route=>{
  if(route.request().resourceType()==='document')await route.fulfill({contentType:'text/html',body:'<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"></head><body><main></main></body></html>'});
  else await route.fulfill({status:200,contentType:'application/json',body:'{}'});
 });
 await page.addInitScript(({mld,gminna,build,noLength,oldPrefs})=>{
  const core={getAppInfoJson:()=>JSON.stringify(build),getScheduleSyncStatus:()=>JSON.stringify({mldLastSuccess:Date.now(),gminnaLastSuccess:Date.now()}),getMldScheduleJson:()=>JSON.stringify(mld),getGminnaScheduleJson:()=>JSON.stringify(gminna),getMldRealtimeJson:()=>'',getLatestPostJson:()=>'',getWasteData:()=>''};
  window.IspinaNative=new Proxy(core,{get:(o,k)=>o[k]||(()=> '')});
  if(oldPrefs){localStorage.setItem('ianim','0');localStorage.setItem('il_confirm_motion','0')}
  if(noLength)SVGGeometryElement.prototype.getTotalLength=undefined;
 },{mld,gminna,build,noLength:process.env.IL_VISUAL_NO_SVG_LENGTH==='1',oldPrefs:process.env.IL_VISUAL_OLD_PREFS==='1'});
 await page.goto('https://ispinalokalnie.wordpress.com/');
 await page.addScriptTag({path:source});
 await page.waitForTimeout(450);
 await page.locator('#nav [data-u=":plan"]').evaluate(e=>e.click());
 await page.waitForTimeout(500);
 await page.locator('#pfrom').fill('Isp');
 await page.waitForTimeout(200);
 await page.screenshot({path:path.join(output,'00-suggestions.png')});
 const before=await page.evaluate(()=>({settings:{animations:localStorage.getItem('ianim'),confirmation:localStorage.getItem('il_confirm_motion'),reduced:matchMedia('(prefers-reduced-motion: reduce)').matches},body:document.body.className,source:window.IL_PLANNER_UI_VERSION}));
 await page.locator('#psfrom .plsug').filter({hasText:'Ispina Most'}).first().tap();
 await page.waitForTimeout(1000);
 const startProvisional=await page.locator('#pfrom').evaluate(e=>({accepted:e.closest('.plfield').classList.contains('il-r12-confirmed'),animating:!!e.closest('.plfield').querySelector('.il-r13-animating'),transform:getComputedStyle(e).transform,acceptDisplay:getComputedStyle(document.getElementById(e.id+'-review').querySelector('.il-r12-accept')).display}));
 if(startProvisional.accepted||startProvisional.animating||startProvisional.transform!=='none'||startProvisional.acceptDisplay==='none')throw Error('Start auto-confirmed before Akceptuj: '+JSON.stringify(startProvisional));
 await page.screenshot({path:path.join(output,'00a-start-awaiting-accept.png')});
 await page.locator('#pfrom-review .il-r12-accept').tap();
 const frames=[];
 for(let i=0;i<6;i++){
  frames.push(await page.evaluate(()=>{
   const input=document.getElementById('pfrom'),field=input.closest('.plfield'),tick=field.querySelector('.il-r12-tick');
   return {now:performance.now(),accepted:field.className,tick:tick&&{display:getComputedStyle(tick).display,opacity:getComputedStyle(tick).opacity,box:tick.getBoundingClientRect().toJSON()},transform:getComputedStyle(input).transform,stroke:tick&&Array.from(tick.querySelectorAll('circle,path')).map(e=>({dash:getComputedStyle(e).strokeDashoffset,box:e.getBoundingClientRect().toJSON()})),focus:document.activeElement.id};
  }));
  await page.screenshot({path:path.join(output,String(i+1).padStart(2,'0')+'-confirm.png')});
  await page.waitForTimeout(100);
 }
 if(!frames.some(f=>Number.parseFloat(f.transform.replace(/^matrix\(/,''))>1)||!frames.some(f=>Number.parseFloat(f.stroke[0].dash)>0&&Number.parseFloat(f.stroke[0].dash)<81.68))throw Error('First Akceptuj skipped the visible start animation');
 await page.waitForTimeout(200);
 const startFinished=await page.locator('#pfrom').evaluate(e=>({complete:e.closest('.plfield').querySelector('.il-r13-complete')!==null,focus:document.activeElement.id}));
 if(!startFinished.complete||startFinished.focus!=='pto')throw Error('Start did not finish before moving focus to destination');
 await page.screenshot({path:path.join(output,'07-start-complete.png')});
 await page.locator('#pto').fill('Krak');await page.waitForTimeout(200);
 await page.locator('#psto .plsug').filter({hasText:'Czyżyny Dworzec'}).first().tap();
 await page.waitForTimeout(1000);
 const destinationProvisional=await page.locator('#pto').evaluate(e=>({accepted:e.closest('.plfield').classList.contains('il-r12-confirmed'),animating:!!e.closest('.plfield').querySelector('.il-r13-animating'),acceptDisplay:getComputedStyle(document.getElementById(e.id+'-review').querySelector('.il-r12-accept')).display}));
 if(destinationProvisional.accepted||destinationProvisional.animating||destinationProvisional.acceptDisplay==='none')throw Error('Destination auto-confirmed before Akceptuj');
 await page.screenshot({path:path.join(output,'07a-destination-awaiting-accept.png')});
 await page.locator('#pto-review .il-r12-accept').tap();
 const destinationFrames=[];
 for(let i=0;i<6;i++){
  destinationFrames.push(await page.locator('#pto').evaluate(e=>({now:performance.now(),focus:document.activeElement.id,stroke:Array.from(e.closest('.plfield').querySelectorAll('circle,path')).map(p=>getComputedStyle(p).strokeDashoffset)})));
  await page.screenshot({path:path.join(output,String(i+8).padStart(2,'0')+'-destination.png')});await page.waitForTimeout(100);
 }
 if(!destinationFrames.some(f=>Number.parseFloat(f.stroke[0])>0&&Number.parseFloat(f.stroke[0])<81.68))throw Error('First Akceptuj skipped destination animation');
 await page.waitForTimeout(250);
 const finished=await page.evaluate(()=>({complete:document.querySelectorAll('#vplan .il-r13-complete').length,editing:document.getElementById('iaP').classList.contains('il-r10-editing')}));
 if(finished.complete!==2||finished.editing||errors.length)throw Error('Destination did not finish cleanly: '+JSON.stringify({finished,errors}));
 await page.screenshot({path:path.join(output,'14-both-complete.png')});
 await page.locator('#pfrom-review .il-r15-change').tap();await page.locator('#pfrom').fill('Czyżyny Dworzec');await page.locator('#pfrom-review .il-r12-accept').tap();await page.waitForTimeout(1000);
 await page.locator('#pto-review .il-r15-change').tap();await page.locator('#pto').fill('Nowe Brzesko');await page.waitForTimeout(200);await page.locator('#psto .plsug').filter({hasText:'Nowe Brzesko Rynek'}).first().tap();await page.locator('#pto-review .il-r12-accept').tap();await page.waitForTimeout(1000);
 // Calendar/time controls use hidden native inputs; set their controlled test values.
 await page.locator('#pdate').evaluate(e=>{e.value='2026-10-10';e.dispatchEvent(new Event('change',{bubbles:true}))});
 await page.locator('#ptime').evaluate(e=>{e.value='05:00';e.dispatchEvent(new Event('change',{bubbles:true}))});
 await page.locator('#pgo').tap();await page.waitForTimeout(1400);
 const planner=await page.evaluate(()=>({from:document.getElementById('pfrom').value,to:document.getElementById('pto').value,horizon:document.getElementById('ilPlannerHorizon').textContent,lines:Array.from(document.querySelectorAll('#ilDirectResults .prline')).map(e=>e.textContent),days:Array.from(document.querySelectorAll('.il-r14-day')).map(e=>e.textContent)}));
 if(planner.to!=='Nowe Brzesko Rynek'||!planner.horizon.includes('do końca dnia')||!['A50','A57','gminna'].every(line=>planner.lines.includes(line))||planner.days.length!==1)throw Error('Wrong one-day corridor results '+JSON.stringify(planner));
 await page.locator('#ilPlannerHorizon').scrollIntoViewIfNeeded();
 await page.screenshot({path:path.join(output,'15-corridor-one-day.png')});
 const result={source,before,startProvisional,frames,startFinished,destinationProvisional,destinationFrames,finished,planner,errors};
 fs.writeFileSync(path.join(output,'result.json'),JSON.stringify(result,null,2));
 console.log(JSON.stringify({source,build:build.variant,startProvisional,startFinished,destinationProvisional,finished,planner,errors,firstCircleOffsets:frames.map(f=>f.stroke[0].dash),destinationCircleOffsets:destinationFrames.map(f=>f.stroke[0])}));
 await browser.close();
})().catch(async e=>{console.error(e);if(activeBrowser)await activeBrowser.close();process.exitCode=1});
