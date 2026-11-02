import {test} from 'node:test';
import assert from 'node:assert/strict';
import {normalizeMetno,createMetnoLoader,metnoCode,summarizeMetno} from './weather-metno.mjs';
import {createWeatherLoader} from './weather.mjs';
import {metnoFixture} from '../tests/fixtures/metno.mjs';
import {fixture} from '../tests/fixtures/weather.mjs';
const now=Date.parse('2026-11-02T15:25:00Z');
test('MET Norway adapter keeps missing probabilities, gusts and apparent temperature unknown',()=>{
 const d=normalizeMetno(metnoFixture(now),now);
 assert.equal(d.source,'metno');assert.equal(d.current.wind_speed_10m,10.8);
 assert.equal(d.current.apparent_temperature,null);assert.equal(d.current.precipitation_hours,1);
 assert.deepEqual(d.daily.precipitation_probability_max,[null,null,null]);
 assert.ok(d.hourly.wind_gusts_10m.every(x=>x===null));
 assert.equal(d.daily.time.length,3);
});
test('rejects wrong location, units, stale model, missing days, duplicate times and invalid values',()=>{
 for(const mutate of [
  r=>r.geometry.coordinates[1]=51,
  r=>r.properties.meta.units.wind_speed='km/h',
  r=>r.properties.meta.updated_at=new Date(now-19*3600e3).toISOString(),
  r=>r.properties.timeseries.splice(12),
  r=>r.properties.timeseries[2].time=r.properties.timeseries[1].time,
  r=>r.properties.timeseries[0].data.instant.details.air_temperature=null,
  r=>r.properties.timeseries[0].data.next_1_hours.summary.symbol_code='unknown'
 ]){const raw=metnoFixture(now);mutate(raw);assert.throws(()=>normalizeMetno(raw,now));}
});
test('supports three Warsaw calendar days at midnight and the DST transition',()=>{
 for(const t of ['2026-11-02T23:05:00Z','2026-10-25T01:05:00Z','2026-03-29T01:05:00Z']){
  const clock=Date.parse(t);assert.equal(normalizeMetno(metnoFixture(clock),clock).daily.time.length,3);
 }
});
test('maps symbols conservatively and keeps warnings and forecast rain visible',()=>{
 assert.equal(metnoCode('clearsky_night'),0);assert.equal(metnoCode('rainshowersandthunder_day'),95);
 assert.equal(metnoCode('sleet'),69);assert.equal(metnoCode('unexpected'),null);
 const raw=metnoFixture(now);raw.properties.timeseries[1].data.next_1_hours.details.precipitation_amount=1.2;
 const summary=summarizeMetno(normalizeMetno(raw,now),now,[{text:'Ostrzeżenie IMGW'}]);
 assert.equal(summary.entries[0].kind,'warning');assert.equal(summary.entries[1].kind,'rain');
});
test('identifies the app, shares requests, honours Expires and handles conditional 304',async()=>{
 let clock=now,calls=0;
 const load=createMetnoLoader(async(url,opts)=>{
  calls++;assert.equal(url.searchParams.get('lat'),'50.1133');
  assert.match(opts.headers['User-Agent'],/IspinaLokalnie.*https:/);
  const headers=new Headers({'Expires':new Date(clock+3600e3).toUTCString(),'Last-Modified':new Date(now-1000).toUTCString()});
  if(calls===2){assert.ok(opts.headers['If-Modified-Since']);return {ok:false,status:304,headers};}
  return {ok:true,status:200,headers,json:async()=>metnoFixture(clock)};
 },()=>clock);
 await Promise.all([load(),load()]);assert.equal(calls,1);
 clock+=30*60000;await load();assert.equal(calls,1);
 clock+=31*60000;await load();assert.equal(calls,2);
});
test('falls back after primary 503, coalesces callers, then returns to the primary source',async()=>{
 let clock=now,healthy=false,primary=0,backup=0;
 const load=createWeatherLoader(async url=>{
  if(url.hostname==='api.met.no'){backup++;return {ok:true,status:200,headers:new Headers(),json:async()=>metnoFixture(clock)};}
  primary++;return healthy?{ok:true,status:200,json:async()=>fixture(clock)}:{ok:false,status:503};
 },()=>clock);
 const [a,b]=await Promise.all([load(),load()]);assert.strictEqual(a,b);assert.equal(a.data.source,'metno');
 assert.equal(primary,2);assert.equal(backup,1);
 await load();assert.equal(primary,2);
 clock+=301000;healthy=true;const recovered=await load();assert.equal(recovered.data.source,undefined);
 assert.equal(primary,3);assert.equal(backup,1);
});
test('does not keep an old forecast when both sources fail',async()=>{
 const load=createWeatherLoader(async()=>({ok:false,status:503}),()=>now);
 await assert.rejects(load(),e=>e.code==='all-sources');
 await assert.rejects(load(),e=>e.code==='all-sources');
});
