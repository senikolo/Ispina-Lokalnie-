import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createWeatherLoader, validateWeather, weatherUrl, weatherErrorCode, localDate, REFRESH_MS } from './weather.mjs';

import {fixture} from '../tests/fixtures/weather.mjs';
const baseline = Date.parse('2026-10-23T10:00:00Z');
test('requests Ispina coordinates, Warsaw time and explicit units', () => {
  const url = weatherUrl();
  assert.equal(url.origin, 'https://api.open-meteo.com');
  assert.equal(url.searchParams.get('latitude'), '50.11336');
  assert.equal(url.searchParams.get('longitude'), '20.37746');
  assert.equal(url.searchParams.get('timezone'), 'Europe/Warsaw');
  assert.equal(url.searchParams.get('wind_speed_unit'), 'kmh');
});
test('uses explicit Warsaw dates and a new upstream URL after local midnight', () => {
  const before = weatherUrl(Date.parse('2026-10-23T21:59:00Z'));
  const after = weatherUrl(Date.parse('2026-10-23T22:00:00Z'));
  assert.equal(before.searchParams.get('start_date'), '2026-10-23');
  assert.equal(after.searchParams.get('start_date'), '2026-10-24');
  assert.equal(after.searchParams.get('end_date'), '2026-10-26');
  assert.notEqual(before.href, after.href);
});
test('rejects incomplete, null, nonfinite, stale, future and wrong-location data', () => {
  assert.doesNotThrow(() => validateWeather(fixture(baseline), baseline));
  for (const mutate of [d => d.current.temperature_2m = null, d => d.current.wind_speed_10m = NaN, d => d.current.time -= 3600, d => d.current.time += 3600, d => d.daily.time.pop(), d => d.daily.precipitation_probability_max[1] = null, d => d.daily.weather_code[0] = 999, d => d.longitude = 20.45, d => d.timezone = 'UTC', d => d.current_units.wind_speed_10m = 'm/s', d => d.daily.time[0] -= 86400]) {
    const d = fixture(baseline); mutate(d);
    assert.throws(() => validateWeather(d, baseline));
  }
});
test('includes hourly probability, gusts and cloud forecast in the existing API request', () => {
  const u=weatherUrl(baseline);
  assert.equal(u.searchParams.get('hourly'),'precipitation_probability,precipitation,weather_code,wind_speed_10m,wind_gusts_10m,cloud_cover');
});
test('rejects incomplete hourly forecasts, null probabilities and wrong wind units', () => {
  for(const mutate of [
    d=>d.hourly.time.pop(),
    d=>d.hourly.precipitation_probability[1]=null,
    d=>d.hourly.wind_gusts_10m[0]=Infinity,
    d=>d.hourly.cloud_cover[0]=101,
    d=>d.hourly.time[2]=d.hourly.time[1],
    d=>d.hourly_units.wind_gusts_10m='mph'
  ]) {
    const d=fixture(baseline);mutate(d);
    assert.throws(()=>validateWeather(d,baseline));
  }
});
test('coalesces requests, refreshes after thirty minutes and drops expired data on failure', async () => {
  let now = baseline, calls = 0, fail = false;
  const load = createWeatherLoader(async (url, options) => {
    calls++;
    assert.equal(options.cache, 'no-store');
    await Promise.resolve();
    if (fail) throw new Error('timeout');
    return {ok: true, json: async () => fixture(now)};
  }, () => now);
  const [a,b] = await Promise.all([load(), load()]);
  assert.strictEqual(a,b); assert.equal(calls,1);
  now += REFRESH_MS - 1000; assert.strictEqual(await load(), a);
  now += 1001; const refreshed = await load(); assert.equal(calls,2); assert.ok(refreshed.fetchedAt > a.fetchedAt);
  now += REFRESH_MS + 1; fail = true;
  await assert.rejects(load()); assert.equal(calls,4);
  await assert.rejects(load()); assert.equal(calls,4);
  now += 60001; fail = false; await load(); assert.equal(calls,5);
});
test('recovers from a transient HTTP 503 with one retry', async () => {
  let calls = 0, cancelled = false;
  const load = createWeatherLoader(async () => {
    calls++;
    if (calls === 1) return {ok:false, status:503, body:{cancel:async()=>{cancelled=true;}}};
    return {ok:true, status:200, json:async()=>fixture(baseline)};
  }, () => baseline);
  const result = await load();
  assert.equal(calls,2); assert.equal(cancelled,true);
  assert.equal(result.fetchedAt,baseline);
});
test('retains the cause during cooldown and never retries a rate limit', async () => {
  for (const [status, expectedCalls] of [[503,2],[429,1],[400,1]]) {
    let calls = 0;
    const load = createWeatherLoader(async () => {calls++; return {ok:false,status};}, () => baseline);
    await assert.rejects(load(),e=>weatherErrorCode(e)===`http-${status}`);
    await assert.rejects(load(),e=>weatherErrorCode(e)===`http-${status}`);
    assert.equal(calls,expectedCalls);
  }
});
test('does not retry invalid data and exposes only a safe diagnostic code', async () => {
  let calls = 0;
  const load = createWeatherLoader(async () => {calls++; return {ok:true,status:200,json:async()=>({})};},()=>baseline);
  await assert.rejects(load(),e=>weatherErrorCode(e)==='invalid-data');
  assert.equal(calls,1);
  assert.equal(weatherErrorCode(new DOMException('timeout','TimeoutError')),'timeout');
  assert.equal(weatherErrorCode(new SyntaxError('bad JSON')),'invalid-json');
  assert.equal(weatherErrorCode(new Error('private error details')),'network');
});
test('rejects HTTP errors and malformed payloads without keeping the previous forecast', async () => {
  for (const response of [{ok:false}, {ok:true, json:async()=>({})}, {ok:true, json:async()=>{throw new Error('bad JSON')}}]) {
    const load = createWeatherLoader(async () => response, () => baseline);
    await assert.rejects(load());
  }
});
test('changes Dziś at local midnight and accepts the DST transition calendar', async () => {
  let now = Date.parse('2026-10-24T21:55:00Z'), calls = 0;
  const load = createWeatherLoader(async () => {calls++; return {ok:true, json:async()=>fixture(now)};}, () => now);
  await load(); now += 10 * 60000; await load(); assert.equal(calls,2);
  assert.doesNotThrow(() => validateWeather(fixture(Date.parse('2026-10-25T01:15:00Z')), Date.parse('2026-10-25T01:15:00Z')));
});
test('accepts real API fixed-offset daily boundaries across the autumn DST change', () => {
  const now = Date.parse('2026-10-23T22:05:00Z');
  const data = fixture(now);
  data.daily.time = [1792792800,1792879200,1792965600];
  assert.doesNotThrow(() => validateWeather(data, now));
  data.daily.time[2] -= 86400;
  assert.throws(() => validateWeather(data, now));
});
