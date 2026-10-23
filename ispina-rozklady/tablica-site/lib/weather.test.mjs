import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createWeatherLoader, validateWeather, weatherUrl, localDate, REFRESH_MS } from './weather.mjs';

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
test('rejects incomplete, null, nonfinite, stale, future and wrong-location data', () => {
  assert.doesNotThrow(() => validateWeather(fixture(baseline), baseline));
  for (const mutate of [d => d.current.temperature_2m = null, d => d.current.wind_speed_10m = NaN, d => d.current.time -= 3600, d => d.current.time += 3600, d => d.daily.time.pop(), d => d.daily.precipitation_probability_max[1] = null, d => d.daily.weather_code[0] = 999, d => d.longitude = 20.45, d => d.timezone = 'UTC', d => d.current_units.wind_speed_10m = 'm/s', d => d.daily.time[0] -= 86400]) {
    const d = fixture(baseline); mutate(d);
    assert.throws(() => validateWeather(d, baseline));
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
  await assert.rejects(load()); assert.equal(calls,3);
  await assert.rejects(load()); assert.equal(calls,3);
  now += 60001; fail = false; await load(); assert.equal(calls,4);
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
