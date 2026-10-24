import {test} from 'node:test';
import assert from 'node:assert/strict';
import {summarizeWeatherSmart} from './weather-smart.mjs';
import {fixture} from '../tests/fixtures/weather.mjs';
const now=Date.parse('2026-10-24T07:00:00Z');
const hour=t=>Number(new Intl.DateTimeFormat('en-GB',{timeZone:'Europe/Warsaw',hour:'2-digit',hourCycle:'h23'}).format(new Date(t*1000)));
const day=t=>new Intl.DateTimeFormat('en-CA',{timeZone:'Europe/Warsaw',year:'numeric',month:'2-digit',day:'2-digit'}).format(new Date(t*1000));
function set(d,calendar,hours,change){
 d.hourly.time.forEach((t,i)=>{
  if(day(t)===calendar&&hours.includes(hour(t)))for(const [k,v]of Object.entries(change))d.hourly[k][i]=v;
 });
}
test('bursts of thunder and wind outrank drizzle',()=>{
 const d=fixture(now);
 set(d,'2026-10-24',[12,13],{precipitation_probability:85,precipitation:2,weather_code:61});
 set(d,'2026-10-24',[15],{precipitation_probability:80,precipitation:4,weather_code:95});
 set(d,'2026-10-24',[16],{wind_gusts_10m:80});
 const o=summarizeWeatherSmart(d,now);
 assert.equal(o.entries[0].kind,'storm');
 assert.equal(o.entries[1].kind,'wind');
});
test('afternoon rain has approximate start and end',()=>{
 const d=fixture(now);
 set(d,'2026-10-24',[14,15],{precipitation_probability:80,precipitation:1,weather_code:61});
 const o=summarizeWeatherSmart(d,now);
 assert.match(o.entries[0].text,/14:00/);
 assert.match(o.entries[1].text,/16:00/);
});
test('night fog blocks unjustified calm moon',()=>{
 const at=Date.parse('2026-10-24T19:00:00Z');
 const d=fixture(at);
 set(d,'2026-10-25',[2,3],{weather_code:45});
 const o=summarizeWeatherSmart(d,at);
 assert.equal(o.entries[0].kind,'fog');
 assert.doesNotMatch(o.entries[0].text,/Spokojna noc/);
});
test('late night shows next-morning rain even when remainder of tonight is dry',()=>{
 const at=Date.parse('2026-10-24T19:00:00Z');
 const d=fixture(at);
 set(d,'2026-10-25',[8,9],{precipitation_probability:85,precipitation:2,weather_code:63});
 const o=summarizeWeatherSmart(d,at);
 assert.equal(o.period,'NOC I JUTRO');
 assert.equal(o.entries[0].kind,'calm');
 assert.equal(o.entries[1].kind,'rain');
 assert.match(o.entries[1].text,/08:00/);
});
test('verified IMGW warning overrides a dry forecast',()=>{
 const d=fixture(now);
 const w={kind:'wind',text:'IMGW: Silny wiatr, stopień 2. od 25.10, 08:00.'};
 const o=summarizeWeatherSmart(d,now,[w]);
 assert.equal(o.entries[0].kind,'warning');
 assert.equal(o.entries[0].source,'imgw');
});

test('dry forecast says explicitly that precipitation is not expected',()=>{
 const o=summarizeWeatherSmart(fixture(now),now);
 assert.equal(o.entries[0].kind,'stable');
 assert.match(o.entries[0].text,/prognoza nie wskazuje opadów/);
 assert.doesNotMatch(o.entries[0].text,/większych|znacznych/);
});
test('low but nonzero rain probability is not described as certainly dry',()=>{
 const d=fixture(now);
 set(d,'2026-10-24',[11,12,13],{precipitation_probability:28});
 const o=summarizeWeatherSmart(d,now);
 assert.match(o.entries[0].text,/opady są mało prawdopodobne/);
 assert.doesNotMatch(o.entries[0].text,/bez opadów/);
});
test('a moderate hourly rain risk without a confirmed event is still disclosed',()=>{
 const d=fixture(now);
 set(d,'2026-10-24',[15],{precipitation_probability:58});
 const o=summarizeWeatherSmart(d,now);
 assert.equal(o.entries[0].kind,'rain');
 assert.match(o.entries[0].text,/możliwe opady.*pora jest niepewna/);
});
test('night risk is never labelled as calm only because rainfall is weak',()=>{
 const at=Date.parse('2026-10-24T19:00:00Z');
 const d=fixture(at);
 set(d,'2026-10-24',[23],{precipitation_probability:60});
 const o=summarizeWeatherSmart(d,at);
 assert.equal(o.entries[0].kind,'rain');
 assert.match(o.entries[0].text,/W nocy możliwe opady/);
});
