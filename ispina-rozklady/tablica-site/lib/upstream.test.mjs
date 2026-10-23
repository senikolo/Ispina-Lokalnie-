import {test} from 'node:test';
import assert from 'node:assert/strict';
import {cachedLoader} from './upstream.mjs';
test('concurrent visitors share one upstream request',async()=>{
 let calls=0,release;
 const gate=new Promise(r=>release=r);
 const load=cachedLoader(async()=>{calls++;await gate;return {ok:true};},60000);
 const requests=Array.from({length:30},()=>load());release();
 const values=await Promise.all(requests);assert.equal(calls,1);assert.equal(values.length,30);
 await load();assert.equal(calls,1);
});
test('an outage is briefly cached without returning previously successful data',async()=>{
 let calls=0;const original=Date.now;let now=1000;Date.now=()=>now;
 try{
 const load=cachedLoader(async()=>{calls++;if(calls>1)throw new Error('offline');return 'old';},10,10);
 assert.equal(await load(),'old');now+=11;
 await assert.rejects(load(),/offline/);await assert.rejects(load(),/offline/);assert.equal(calls,2);
 now+=11;await assert.rejects(load(),/offline/);assert.equal(calls,3);
 }finally{Date.now=original;}
});
