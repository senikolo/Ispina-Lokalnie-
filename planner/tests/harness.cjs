const fs = require('node:fs');
const assert = require('node:assert/strict');
process.env.TZ=process.env.IL_TEST_TIMEZONE||'Europe/Warsaw';
const { JSDOM, VirtualConsole } = require(process.env.IL_JSDOM_PATH||'jsdom');
const path = require('node:path');
const root = path.join(__dirname,'../test-data');
const raw = fs.readFileSync(process.env.IL_UI_PATH || path.join(__dirname, '../assets/main_ui.js'), 'utf8');
const mld = JSON.parse(fs.readFileSync(path.join(root, 'mld-embedded.json'), 'utf8'));
const gminna = JSON.parse(fs.readFileSync(path.join(root, 'linia-gminna.json'), 'utf8'));
const evidence = [];
function env({ nativeSchedules = true, reduced = false, instrumentation = false, schedule = mld, municipalSchedule = gminna, nativeVersion = null, initialStorage = {} } = {}) {
  const clock = {now: +new Date('2026-10-10T05:00:00+02:00')};
  const errors = [], requests = [], nativeCalls = [], queue = new Map();
  let nextId = 1;
  const vc = new VirtualConsole();
  vc.on('jsdomError', e => { if (!e.message.includes('Could not parse CSS')) errors.push(e.message); });
  const dom = new JSDOM('<!doctype html><html><head><title>IL audit</title></head><body><main></main></body></html>', {url:'https://ispinalokalnie.wordpress.com/',runScripts:'outside-only',pretendToBeVisual:true,virtualConsole:vc});
  const w = dom.window, d = w.document, RealDate = w.Date;
  for(const [key,value] of Object.entries(initialStorage))w.localStorage.setItem(key,String(value));
  w.Date = class extends RealDate {constructor(...a){super(...(a.length ? a : [clock.now]));} static now(){return clock.now;} };
  w.setTimeout = (fn, delay = 0) => {const id=nextId++; if (Number(delay)<4000) queue.set(id,{fn,time:clock.now+Math.max(0,Number(delay)||0),every:0});return id;};
  w.setInterval = (fn, delay=0) => {const id=nextId++;if (Number(delay)<900)queue.set(id,{fn,time:clock.now+Math.max(1,Number(delay)||1),every:Math.max(1,Number(delay)||1)});return id;};
  w.clearTimeout = w.clearInterval = id => queue.delete(id);
  w.requestAnimationFrame = fn => w.setTimeout(()=>fn(clock.now),16);
  w.cancelAnimationFrame = w.clearTimeout;
  w.scrollTo=()=>{};w.HTMLElement.prototype.scrollIntoView=()=>{};
  w.matchMedia = q => ({matches:q.includes('reduced-motion') && reduced,media:q,addListener(){},removeListener(){},addEventListener(){},removeEventListener(){}});
  w.SVGElement.prototype.getTotalLength = function(){ if(this.tagName==='circle')return 2*Math.PI*Number(this.getAttribute('r'));return this.getAttribute('class').includes('short')?Math.hypot(4.5,4.3):Math.hypot(11.2,11.4); };
  w.fetch=()=>Promise.reject(new Error('Network intentionally disabled'));
  class XHR {open(method,url){this.url=url;this.method=method;}setRequestHeader(){}send(){requests.push(this);}abort(){}complete(data,status=200){this.status=status;this.readyState=4;this.responseText=JSON.stringify(data);if(this.onload)this.onload();if(this.onreadystatechange)this.onreadystatechange();}}
  w.XMLHttpRequest=XHR;
  const core={getAppInfoJson:()=>JSON.stringify(nativeVersion || {versionName:"1.83.19",versionCode:18319,packageName:"com.ispina.mldcheck"}),getScheduleSyncStatus:()=>JSON.stringify({mldLastSuccess:clock.now,gminnaLastSuccess:clock.now}),getMldScheduleJson:()=>nativeSchedules?JSON.stringify(schedule):'',getGminnaScheduleJson:()=>JSON.stringify(municipalSchedule),getMldRealtimeJson:()=>core.rt||'',getLatestPostJson:()=>'',getWasteData:()=>''};
  w.IspinaNative=new Proxy(core,{get:(o,k)=>o[k]||( (...a)=>{nativeCalls.push({name:k,args:a});return '';} )});
  w.addEventListener('error',e=>errors.push(e.message));
  let src=raw;
  if(instrumentation){
    const marker='loadNativeSchedules();setTimeout(function(){loadNativeSchedules()},4500);';
    assert.equal(src.split(marker).length,2);
    src=src.replace(marker,"window.__AUDIT={municipal:function(){return RG},refresh:function(){loadNativeSchedules()},rows:function(){return IL_PLAN_ROWS},transfers:function(){return IL_TRANSFER_ROWS},poll:function(){ilPoll()},live:function(x){return ilLive(x)},collectDirect:function(f,t,n){var o=[],nt=+n;for(var i=0;i<3;i++){var day=new Date(n);day.setHours(0,0,0,0);day.setDate(day.getDate()+i);rplan(f,t,day,nt,o);gp(f,t,day,nt,o)}return o;}};"+marker);
  }
  w.eval(src);
  async function advance(ms){const target=clock.now+ms;let count=0;while(true){let id=null,item=null;for(const [i,x] of queue){if(x.time<=target&&(!item||x.time<item.time)){id=i;item=x;}}if(!item)break;if(++count>40000)throw Error('Timer runaway');clock.now=item.time;if(item.every)item.time+=item.every;else queue.delete(id);try{item.fn();}catch(e){errors.push(e.stack);}await Promise.resolve();}clock.now=target;await new Promise(r=>setImmediate(r));}
  const el=id=>{const e=d.getElementById(id);assert.ok(e,id);return e;};
  const click=e=>e.dispatchEvent(new w.MouseEvent('click',{bubbles:true,cancelable:true}));
  const touch=(target,type,{x=20,y=20,id=1,count=1}={})=>{const p={identifier:id,clientX:x,clientY:y};const event=new w.Event(type,{bubbles:true,cancelable:true});Object.defineProperties(event,{touches:{value:type==='touchend'||type==='touchcancel'?[]:[p,...(count>1?[{identifier:2,clientX:x+20,clientY:y}]:[])]},changedTouches:{value:[p]}});target.dispatchEvent(event);return event;};
  const input=(id,value)=>{const e=el(id);e.focus();e.value=value;e.dispatchEvent(new w.Event('input',{bubbles:true}));return e;};
  const start=async()=>{await advance(20);click(d.querySelector('#nav [data-u=":plan"]'));await advance(1200);};
  const search=async(from,to,date='2026-10-10',time='05:00',mode='auto')=>{el('pfrom').value=from;el('pto').value=to;el('pdate').value=date;el('ptime').value=time;el('ilTransferMode').value=mode;click(el('pgo'));await advance(1000);return el('pres').textContent;};
  const accept=id=>click(el(id+'-review').querySelector('.il-r12-accept'));
  const accepted=id=>el(id).closest('.plfield').classList.contains('il-r12-confirmed');
  return {w,d,core,clock,errors,requests,nativeCalls,queue,advance,el,click,touch,input,start,search,accept,accepted,close:()=>dom.window.close()};
}
async function test(name,run){try{await run();console.log('PASS',name);}catch(e){console.log('FAIL',name,e.message);evidence.push({name,error:e.stack});}}
async function using(options,fn){const e=env(options);try{await e.start();await fn(e);}finally{e.close();}}
module.exports={env,evidence};
