export const LOCATION = Object.freeze({ latitude: 50.11336, longitude: 20.37746, timezone: 'Europe/Warsaw' });
export const REFRESH_MS = 30 * 60 * 1000;
const MAX_CURRENT_AGE = 45 * 60;
const codes = new Set([0,1,2,3,45,48,51,53,55,56,57,61,63,65,66,67,71,73,75,77,80,81,82,85,86,95,96,99]);
const number = (v, min, max) => typeof v === 'number' && Number.isFinite(v) && v >= min && v <= max;
export const localDate = (seconds) => new Intl.DateTimeFormat('en-CA', { timeZone: LOCATION.timezone, year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date(seconds * 1000));

export function weatherUrl(now = Date.now()) {
  const url = new URL('https://api.open-meteo.com/v1/forecast');
  const start = localDate(now / 1000);
  const end = new Date(start + 'T12:00:00Z');
  end.setUTCDate(end.getUTCDate() + 2);
  // Explicit dates avoid an upstream response cached across Warsaw midnight.
  const params = { ...LOCATION, start_date: start, end_date: end.toISOString().slice(0, 10), timeformat: 'unixtime', temperature_unit: 'celsius', wind_speed_unit: 'kmh', precipitation_unit: 'mm', current: 'temperature_2m,apparent_temperature,precipitation,weather_code,wind_speed_10m,is_day', hourly: 'precipitation_probability,precipitation,weather_code,wind_speed_10m,wind_gusts_10m,cloud_cover', daily: 'temperature_2m_max,temperature_2m_min,precipitation_probability_max,weather_code' };
  for (const [key, value] of Object.entries(params)) url.searchParams.set(key, String(value));
  return url;
}

export function validateWeather(data, now = Date.now()) {
  const c = data?.current, d = data?.daily, u = data?.current_units, du = data?.daily_units;
  if (data?.timezone !== LOCATION.timezone || ![3600,7200].includes(data.utc_offset_seconds) || !number(data.latitude, LOCATION.latitude - 0.018, LOCATION.latitude + 0.018) || !number(data.longitude, LOCATION.longitude - 0.025, LOCATION.longitude + 0.025)) throw new Error('Wrong weather location');
  // The API returns the nearest weather model grid cell, not a municipality name.
  if (u?.time !== 'unixtime' || u?.temperature_2m !== '°C' || u?.apparent_temperature !== '°C' || u?.wind_speed_10m !== 'km/h' || u?.precipitation !== 'mm' || du?.time !== 'unixtime' || du?.precipitation_probability_max !== '%' || du?.temperature_2m_max !== '°C' || du?.temperature_2m_min !== '°C') throw new Error('Wrong weather units');
  if (!number(c?.time, now / 1000 - MAX_CURRENT_AGE, now / 1000 + 300) || c.interval !== 900 || !number(c.temperature_2m, -70, 60) || !number(c.apparent_temperature, -100, 80) || !number(c.wind_speed_10m, 0, 350) || !number(c.precipitation, 0, 1000) || !codes.has(c.weather_code) || ![0,1].includes(c.is_day)) throw new Error('Invalid current weather');
  for (const key of ['time','temperature_2m_max','temperature_2m_min','precipitation_probability_max','weather_code']) {
    if (!Array.isArray(d?.[key]) || d[key].length !== 3) throw new Error('Incomplete three-day forecast');
  }
  const today = localDate(now / 1000);
  for (let i = 0; i < 3; i++) {
    if (!number(d.time[i], 0, 1e11) || !number(d.temperature_2m_max[i], -70, 60) || !number(d.temperature_2m_min[i], -70, 60) || d.temperature_2m_min[i] > d.temperature_2m_max[i] || !number(d.precipitation_probability_max[i], 0, 100) || !codes.has(d.weather_code[i])) throw new Error('Invalid daily forecast');
    // Calendar dates, rather than 24-hour increments, handle DST changes.
    const date = new Date(today + 'T12:00:00Z');
    date.setUTCDate(date.getUTCDate() + i);
    // Open-Meteo daily boundaries use the response's fixed UTC offset even
    // across DST; converting each boundary with Intl would shift a day.
    const apiDate = new Date((d.time[i] + data.utc_offset_seconds) * 1000).toISOString().slice(0, 10);
    if (apiDate !== date.toISOString().slice(0, 10)) throw new Error('Wrong forecast dates');
  }
  const h=data.hourly, hu=data.hourly_units;
  if(hu?.time !== 'unixtime' || hu?.precipitation_probability !== '%' || hu?.precipitation !== 'mm' ||
    hu?.wind_speed_10m !== 'km/h' || hu?.wind_gusts_10m !== 'km/h' || hu?.cloud_cover !== '%') throw new Error('Wrong hourly forecast units');
  const keys=['time','precipitation_probability','precipitation','weather_code','wind_speed_10m','wind_gusts_10m','cloud_cover'];
  const count=h?.time?.length;
  // Three civil days have 71, 72 or 73 hourly instants across Warsaw DST.
  if(!Number.isInteger(count) || count<71 || count>73 || keys.some(k=>!Array.isArray(h[k]) || h[k].length!==count))
    throw new Error('Incomplete hourly change forecast');
  const hourlyDates=new Set();
  for(let i=0;i<count;i++){
    const t=h.time[i];
    if(!number(t,0,1e11) || (i && t-h.time[i-1]!==3600) ||
      !number(h.precipitation_probability[i],0,100) || !number(h.precipitation[i],0,1000) ||
      !codes.has(h.weather_code[i]) || !number(h.wind_speed_10m[i],0,350) ||
      !number(h.wind_gusts_10m[i],0,400) || !number(h.cloud_cover[i],0,100))
      throw new Error('Invalid hourly change forecast');
    hourlyDates.add(localDate(t));
  }
  const expectedDays=d.time.map((_,i)=>{
    const date=new Date(today+'T12:00:00Z');date.setUTCDate(date.getUTCDate()+i);
    return date.toISOString().slice(0,10);
  });
  if(expectedDays.some(day=>!hourlyDates.has(day)) || hourlyDates.size!==3 ||
     localDate(h.time[0])!==today || localDate(h.time[count-1])!==expectedDays[2])
    throw new Error('Wrong hourly forecast days');
  return data;
}

export function createWeatherLoader(fetcher = fetch, clock = Date.now) {
  let cached, expires = 0, pending, retryAfter = 0;
  return async function load() {
    const now = clock();
    if (cached && now < expires) {
      try { validateWeather(cached.data, now); return cached; } catch { cached = undefined; }
    }
    if (now < retryAfter) throw new Error('Weather temporarily unavailable');
    if (pending) return pending;
    // No Next.js stale-on-error cache: expired data is never a fallback.
    pending = (async () => {
      try {
        const response = await fetcher(weatherUrl(clock()), { cache: 'no-store', signal: AbortSignal.timeout(8000) });
        if (!response.ok) throw new Error('Weather source unavailable');
        const data = validateWeather(await response.json(), clock());
        const fetchedAt = clock();
        expires = Math.min(fetchedAt + REFRESH_MS, (data.current.time + MAX_CURRENT_AGE) * 1000);
        // Do not cache yesterday's labels beyond local midnight (including DST).
        for (let i = 1; i <= 30; i++) {
          const candidate = fetchedAt + i * 60000;
          if (localDate(candidate / 1000) !== localDate(fetchedAt / 1000)) { expires = Math.min(expires, candidate - 60000); break; }
        }
        cached = { data, fetchedAt, expires };
        return cached;
      } catch (error) {
        cached = undefined;
        retryAfter = clock() + 60000;
        throw error;
      } finally { pending = undefined; }
    })();
    return pending;
  };
}
