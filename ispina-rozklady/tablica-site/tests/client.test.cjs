const {test}=require('node:test');const assert=require('node:assert/strict');
const fs=require('node:fs'),path=require('node:path'),Module=require('node:module');
const ts=require('typescript'),{JSDOM}=require('jsdom'),React=require('react');
const {createRoot}=require('react-dom/client'),{act}=React;
const entry=path.resolve(__dirname,'../app/page.tsx');
const compiled=ts.transpileModule(fs.readFileSync(entry,'utf8'),{compilerOptions:{jsx:ts.JsxEmit.ReactJSX,module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2017}}).outputText;
const mod=new Module(entry,module);mod.filename=entry;mod.paths=Module._nodeModulePaths(path.dirname(entry));mod._compile(compiled,entry);const Page=mod.exports.default;
global.IS_REACT_ACT_ENVIRONMENT=true;
async function fixture(run){
 const dom=new JSDOM('<div id="root"></div>',{url:'https://test.example/'});
 global.window=dom.window;global.document=dom.window.document;
 const originalFetch=global.fetch;const now=Math.floor(Date.now()/1000);
 const today=new Intl.DateTimeFormat('sv-SE',{timeZone:'Europe/Warsaw'}).format(new Date(now*1000));
 const row={line:'A7',time:'12:00',scheduled:now+600,direction:'north',destination:'Nowe Brzesko Rynek',stop_name:'Ispina Most',stop:'most',source:'test',pdf:'test',trip_id:'test',kind:'live',expected:now+660,live_until:now+180};
 const board={schema:1,server_now:now,schedule_valid_until:now+3600,generated_at:new Date(now*1000).toISOString(),days:[{date:today,departures:[row],warnings:[]}],live_available:true,gminna_notice:''};
 let mode='ok';global.fetch=async url=>{if(mode==='both-fail'||(mode==='fallback'&&url==='/api/odjazdy'))throw Error('offline');return {ok:true,headers:{get:name=>board.testHeaders?.[name]??null},json:async()=>structuredClone(board)}};
 const root=createRoot(document.getElementById('root'));
 try{await act(async()=>root.render(React.createElement(Page)));await run({board,row,mode:m=>mode=m,render:async()=>act(async()=>root.render(React.createElement(Page))),refresh:async()=>act(async()=>document.querySelector('.refresh').click())});}
 finally{await act(async()=>root.unmount());dom.window.close();global.fetch=originalFetch;delete global.window;delete global.document;}
}
test('both upstream failures erase previously displayed LIVE and all departures',()=>fixture(async f=>{
 assert.match(document.querySelector('.trip-meta').textContent,/Na żywo/);f.mode('both-fail');await f.refresh();
 assert.equal(document.querySelectorAll('.departure').length,0);assert.match(document.body.textContent,/Odjazdy są chwilowo niedostępne/);assert.doesNotMatch(document.body.textContent,/z ostatniego pomiaru/);
}));
test('schedule fallback strips all predictions and labels itself clearly',()=>fixture(async f=>{
 f.mode('fallback');await f.refresh();assert.equal(document.querySelectorAll('.departure').length,1);assert.match(document.querySelector('.trip-meta').textContent,/Rozkład/);assert.doesNotMatch(document.querySelector('.trip-meta').textContent,/Na żywo/);assert.match(document.querySelector('.fallback-note').textContent,/chwilowo niedostępne/);
}));
test('expired individual prediction immediately returns to scheduled time',()=>fixture(async f=>{
 f.row.live_until=Date.now()/1000-1;await f.refresh();assert.match(document.querySelector('.trip-meta').textContent,/Rozkład/);assert.equal(document.querySelector('.original-time'),null);
}));
test('expired schedule is hidden even when API responds with HTTP 200',()=>fixture(async f=>{
 f.board.schedule_valid_until=Date.now()/1000-1;await f.refresh();assert.equal(document.querySelectorAll('.departure').length,0);assert.match(document.body.textContent,/Rozkład wymaga aktualizacji/);
}));
test('expired GitHub fallback cannot rescue a failed API',()=>fixture(async f=>{
 f.board.generated_at=new Date(Date.now()-31*3600000).toISOString();f.mode('fallback');await f.refresh();assert.equal(document.querySelectorAll('.departure').length,0);assert.match(document.body.textContent,/Odjazdy są chwilowo niedostępne/);
}));

test('CDN Age cannot extend the validity of an expired LIVE prediction',()=>fixture(async f=>{
 f.board.testHeaders={Age:'200'};await f.refresh();assert.match(document.querySelector('.trip-meta').textContent,/Rozkład/);assert.equal(document.querySelector('.original-time'),null);
}));
