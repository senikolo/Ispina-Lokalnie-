import {test} from 'node:test';
import assert from 'node:assert/strict';
import {localImgwWarnings,parseWarsawTime,createImgwLoader,BOCHNIA_TERYT} from './imgw-warnings.mjs';
const now=Date.parse('2026-10-24T19:00:00Z');
const example=(teryt)=>({id:'test',nazwa_zdarzenia:'Silny wiatr',stopien:'2',
 obowiazuje_od:'2026-10-24 20:00:00',obowiazuje_do:'2026-10-25 13:00:00',
 opublikowano:'2026-10-24 15:00:00',teryt});
test('only county 1201 triggers, never adjacent counties',()=>{
 assert.equal(BOCHNIA_TERYT,'1201');
 assert.equal(localImgwWarnings([example(['1202','1207'])],now).length,0);
 assert.equal(localImgwWarnings([example(['1201'])],now)[0].level,2);
});
test('rejects expired, distant future or malformed warnings',()=>{
 const past=example(['1201']);past.obowiazuje_do='2026-10-24 17:00:00';
 const bad=example(['1201']);bad.stopien='very high';
 assert.equal(localImgwWarnings([past,bad],now).length,0);
});
test('Warsaw wall clock handles both sides of DST transition',()=>{
 assert.equal(parseWarsawTime('2026-10-24 20:00:00'),Date.parse('2026-10-24T18:00:00Z'));
 assert.equal(parseWarsawTime('2026-10-25 15:00:00'),Date.parse('2026-10-25T14:00:00Z'));
});
test('official outage does not silently claim no warnings',async()=>{
 const load=createImgwLoader(async()=>{throw new Error('outage');},()=>now);
 assert.equal(await load(),null);
});
test('cache suppresses repeated API requests',async()=>{
 let calls=0;
 const load=createImgwLoader(async()=>{calls++;return {ok:true,json:async()=>[example(['1201'])]};},()=>now);
 assert.equal((await load()).warnings.length,1);
 assert.equal((await load()).warnings.length,1);
 assert.equal(calls,1);
});
