import {test} from 'node:test';
import assert from 'node:assert/strict';
import {summarizeWeatherChanges, summarizeWeatherBrief} from './weather-trends.mjs';
import {fixture} from '../tests/fixtures/weather.mjs';

const now=Date.parse('2026-10-24T06:15:00Z'); // 08:15 in Ispina
const hour=t=>Number(new Intl.DateTimeFormat('en-GB',{timeZone:'Europe/Warsaw',hour:'2-digit',hourCycle:'h23'}).format(new Date(t*1000)));
const isToday=t=>new Intl.DateTimeFormat('en-CA',{timeZone:'Europe/Warsaw',year:'numeric',month:'2-digit',day:'2-digit'}).format(new Date(t*1000))==='2026-10-24';
function setPeriod(data, hours, values) {
  data.hourly.time.forEach((t,i)=>{
    if(isToday(t) && hours.includes(hour(t))) for(const [field,value] of Object.entries(values)) data.hourly[field][i]=value;
  });
}
test('short dry-day message without 24 hourly temperatures',()=>{
 const messages=summarizeWeatherChanges(fixture(now),now);
 assert.ok(messages.some(x=>/Do końca dnia prognoza nie wskazuje opadów/.test(x)),JSON.stringify(messages));
 assert.equal(messages.join(' ').includes('°C'),false);
});
test('rain onset and end are presented as approximate changes',()=>{
 const data=fixture(now);
 setPeriod(data,[14,15],{precipitation_probability:80,precipitation:1,weather_code:61});
 const messages=summarizeWeatherChanges(data,now);
 assert.match(messages[0],/Około 14:00 możliwe opady deszczu/);
 assert.ok(messages.some(x=>/16:00 opady powinny ustąpić/.test(x)),JSON.stringify(messages));
});
test('strong evening wind is reported without hourly wind table',()=>{
 const data=fixture(now);
 setPeriod(data,[19,20,21],{wind_gusts_10m:65,wind_speed_10m:37});
 const messages=summarizeWeatherChanges(data,now);
 assert.ok(messages.some(x=>/Wieczorem wiatr się nasili.*65 km\/h/.test(x)),JSON.stringify(messages));
});
test('not enough signal never invents certain rain or exact timing',()=>{
 const data=fixture(now);
 setPeriod(data,[14],{precipitation_probability:45,precipitation:0.1,weather_code:61});
 const messages=summarizeWeatherChanges(data,now);
 assert.equal(messages.some(x=>/możliwe opady deszczu/.test(x)),false);
});
test('hourly data on a DST day supports a normal short summary',()=>{
 const d=fixture(Date.parse('2026-10-25T05:00:00Z'));
 const m=summarizeWeatherChanges(d,Date.parse('2026-10-25T05:00:00Z'));
 assert.ok(m.length>0&&m.length<=3);
});

test('after 18:00: moon for dry calm night and rain warning for tomorrow morning',()=>{
 const when=Date.parse('2026-10-24T19:05:00Z'); // 21:05 Ispina
 const d=fixture(when);
 const date=t=>new Intl.DateTimeFormat('en-CA',{timeZone:'Europe/Warsaw',year:'numeric',month:'2-digit',day:'2-digit'}).format(new Date(t*1000));
 d.hourly.time.forEach((t,i)=>{
   if(date(t)==='2026-10-25' && [7,8,9].includes(hour(t))) {
      d.hourly.precipitation_probability[i]=90;
      d.hourly.precipitation[i]=2;
      d.hourly.weather_code[i]=61;
   }
 });
 const o=summarizeWeatherBrief(d,when);
 assert.equal(o.period,'NOC I JUTRO');
 assert.equal(o.entries[0].emoji,'🌙');
 assert.match(o.entries[0].text,/Spokojna noc/);
 assert.equal(o.entries[1].emoji,'🌧️');
 assert.match(o.entries[1].text,/Jutro rano możliwy deszcz/);
});
test('night rain and thunder must take priority over misleading calm moon',()=>{
 const when=Date.parse('2026-10-24T19:05:00Z');
 const d=fixture(when);
 const date=t=>new Intl.DateTimeFormat('en-CA',{timeZone:'Europe/Warsaw',year:'numeric',month:'2-digit',day:'2-digit'}).format(new Date(t*1000));
 d.hourly.time.forEach((t,i)=>{
   if((date(t)==='2026-10-24' && hour(t)===23) ||
      (date(t)==='2026-10-25' && hour(t)===1)) {
      d.hourly.precipitation_probability[i]=80;
      d.hourly.precipitation[i]=4;
      d.hourly.weather_code[i]=95;
   }
 });
 const o=summarizeWeatherBrief(d,when);
 assert.equal(o.entries[0].emoji,'⛈️');
 assert.ok(!o.entries.some(x=>x.text.includes('Spokojna noc')));
});
test('night gust alerts have higher priority than a moon',()=>{
 const when=Date.parse('2026-10-24T19:05:00Z');
 const d=fixture(when);
 const date=t=>new Intl.DateTimeFormat('en-CA',{timeZone:'Europe/Warsaw',year:'numeric',month:'2-digit',day:'2-digit'}).format(new Date(t*1000));
 d.hourly.time.forEach((t,i)=>{
  if(date(t)==='2026-10-25' && hour(t)===2) d.hourly.wind_gusts_10m[i]=75;
 });
 const o=summarizeWeatherBrief(d,when);
 assert.equal(o.entries[0].emoji,'💨');
 assert.match(o.entries[0].text,/porywy wiatru/);
});
test('after midnight summary cannot pick unrelated rain later in the day',()=>{
 const when=Date.parse('2026-10-26T01:15:00Z');
 const d=fixture(when);
 d.hourly.time.forEach((t,i)=>{
   const dt=new Date(t*1000);
   if(dt.toISOString().startsWith('2026-10-26T14:')) {
      d.hourly.precipitation_probability[i]=95;
      d.hourly.precipitation[i]=3;
      d.hourly.weather_code[i]=61;
   }
 });
 const o=summarizeWeatherBrief(d,when);
 assert.equal(o.period,'NOC I PORANEK');
 assert.equal(o.entries[0].emoji,'🌙');
});
test('daytime headline keeps rain emoji and explicit approximate time',()=>{
 const d=fixture(now);
 setPeriod(d,[14,15],{precipitation_probability:80,precipitation:1,weather_code:61});
 const o=summarizeWeatherBrief(d,now);
 assert.equal(o.period,'DZIŚ');
 assert.equal(o.entries[0].emoji,'🌧️');
 assert.match(o.entries[0].text,/Około 14:00/);
});

test('uncertain chance is distinguished from no precipitation',()=>{
 const d=fixture(now);
 d.hourly.time.forEach((t,i)=>{
   if(isToday(t)&&hour(t)===14)d.hourly.precipitation_probability[i]=58;
 });
 const messages=summarizeWeatherChanges(d,now);
 assert.match(messages[0],/Do końca dnia możliwe opady/);
 assert.doesNotMatch(messages[0],/bez większych opadów/);
});
test('low chance is labelled unlikely, not dry',()=>{
 const d=fixture(now);
 d.hourly.time.forEach((t,i)=>{
   if(isToday(t)&&hour(t)===14)d.hourly.precipitation_probability[i]=28;
 });
 assert.match(summarizeWeatherChanges(d,now)[0],/opady są mało prawdopodobne/);
});
