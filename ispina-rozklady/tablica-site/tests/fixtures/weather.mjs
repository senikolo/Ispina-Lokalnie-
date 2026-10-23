import {localDate} from '../../lib/weather.mjs';
export function fixture(now) {
  const today = localDate(now / 1000);
  return {
    latitude: 50.113632, longitude: 20.381836, timezone: 'Europe/Warsaw', utc_offset_seconds: 7200,
    current_units: {time: 'unixtime', temperature_2m: '°C', apparent_temperature: '°C', wind_speed_10m: 'km/h', precipitation: 'mm'},
    daily_units: {time: 'unixtime', temperature_2m_max: '°C', temperature_2m_min: '°C', precipitation_probability_max: '%'},
    current: {time: Math.floor(now / 900000) * 900, interval: 900, temperature_2m: 6.5, apparent_temperature: 4.7, precipitation: 0, wind_speed_10m: 4.3, weather_code: 2, is_day: 0},
    // Noon UTC is inside the corresponding Warsaw date in both DST seasons.
    daily: {time: [0,1,2].map(i => Date.parse(today + 'T12:00:00Z') / 1000 + i * 86400), temperature_2m_max: [14,10,14], temperature_2m_min: [2,2,3], precipitation_probability_max: [0,10,94], weather_code: [3,51,61]}
  };
}
