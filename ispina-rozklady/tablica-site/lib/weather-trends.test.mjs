import {test} from 'node:test';
import assert from 'node:assert/strict';
import {summarizeWeatherChanges} from './weather-trends.mjs';
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
 assert.ok(messages.some(x=>/Do końca dnia bez większych opadów/.test(x)),JSON.stringify(messages));
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
