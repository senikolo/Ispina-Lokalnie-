export interface Forecast {
  source?: 'metno'; modelUpdatedAt?: number;
  latitude: number; longitude: number; timezone: string; utc_offset_seconds: number;
  current: { time: number; temperature_2m: number; apparent_temperature: number | null; precipitation: number; precipitation_hours?: number; wind_speed_10m: number; weather_code: number; is_day: number };
  daily: { time: number[]; temperature_2m_max: number[]; temperature_2m_min: number[]; precipitation_probability_max: (number | null)[]; weather_code: number[] };
  hourly: { time: number[]; precipitation_probability: (number | null)[]; precipitation: number[]; weather_code: number[]; wind_speed_10m: number[]; wind_gusts_10m: (number | null)[]; cloud_cover: number[] };
}
export const LOCATION: Readonly<{ latitude: number; longitude: number; timezone: string }>;
export const REFRESH_MS: number;
export function localDate(seconds: number): string;
export function weatherUrl(now?: number): URL;
export function weatherErrorCode(error: unknown): string;
export function validateWeather(data: unknown, now?: number): Forecast;
export interface WeatherResult { data: Forecast; fetchedAt: number; expires: number }
export function createWeatherLoader(fetcher?: typeof fetch, clock?: () => number, fallback?: (() => Promise<Forecast>) | null): () => Promise<WeatherResult>;
