const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const {env}=require('./harness.cjs');
const results=[];
async function test(name,options,run){const e=env(options);try{await e.start();delete e.w.SVGElement.prototype.getTotalLength;await run(e);assert.deepEqual(e.errors,[]);results.push({name,passed:true});console.log('PASS',name)}finally{e.close()}}
function slot(e,id){return e.el(id).closest('.plfield').querySelector('.il-r12-confirm-slot')}
(async()=>{
 await test('Both fields animate without SVG measurement APIs',{},async e=>{
  e.input('pfrom','Ispina Most');e.accept('pfrom');await e.advance(340);
  assert(slot(e,'pfrom').classList.contains('il-r13-animating'));
  assert(+slot(e,'pfrom').querySelector('.il-r13-circle').style.strokeDashoffset>0);
  assert(+slot(e,'pfrom').querySelector('.il-r13-short').style.strokeDashoffset>0);
  await e.advance(500);assert.equal(e.d.activeElement.id,'pto');
  e.input('pto','Krak');await e.advance(160);
  const option=Array.from(e.el('psto').querySelectorAll('.plsug')).find(b=>!b.textContent.includes('wszystkie'));
  assert(option,'A named Kraków stop is offered');e.click(option);await e.advance(900);assert(!e.accepted('pto'));e.accept('pto');await e.advance(340);
  assert(slot(e,'pto').classList.contains('il-r13-animating'));
  await e.advance(600);assert.equal(e.d.querySelectorAll('#vplan .il-r13-complete').length,2);
  assert(!e.el('iaP').classList.contains('il-r10-editing'));
 });
 await test('Explicitly disabled confirmation gives a static tick without SVG APIs',{reduced:true,initialStorage:{il_confirm_motion_v2:'0'}},async e=>{
  e.input('pfrom','Ispina Most');e.accept('pfrom');
  assert(slot(e,'pfrom').classList.contains('il-r13-complete'));
  assert.equal(e.el('pfrom').style.transform,'');
 });
 await test('Explicit tick replay preserves disabled automatic animations',{},async e=>{
  e.el('s1').checked=false;e.el('s1').onchange();
  e.input('pfrom','Ispina Most');e.accept('pfrom');await e.advance(90);
  assert(slot(e,'pfrom').classList.contains('il-r13-animating'));await e.advance(900);
  assert(slot(e,'pfrom').classList.contains('il-r13-complete'));
  e.click(slot(e,'pfrom').querySelector('.il-r12-tick'));await e.advance(90);
  assert(slot(e,'pfrom').classList.contains('il-r13-animating'));
  assert.notEqual(e.el('pfrom').style.transform,'');
  await e.advance(900);assert(slot(e,'pfrom').classList.contains('il-r13-complete'));
  assert.equal(e.w.localStorage.getItem('ianim'),'0');
 });
 await test('Editing cancels an explicit replay and clears the stop identity',{},async e=>{
  e.input('pfrom','Ispina Most');e.accept('pfrom');await e.advance(900);
  e.click(slot(e,'pfrom').querySelector('.il-r12-tick'));await e.advance(90);
  e.input('pfrom','Zzz');await e.advance(1100);
  assert(!e.accepted('pfrom'));assert.equal(e.el('pfrom').getAttribute('data-il-stop-key'),null);
  assert(!slot(e,'pfrom').classList.contains('il-r13-complete'));
 });
 fs.mkdirSync(path.join(__dirname,'output'),{recursive:true});
 fs.writeFileSync(path.join(__dirname,'output/motion-results.json'),JSON.stringify({passed:results.length,total:4,results},null,2)+'\n');
 console.log(results.length+'/4 passed');
})().catch(e=>{console.error(e);process.exitCode=1});
