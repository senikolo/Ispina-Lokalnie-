import type { Forecast } from './weather.mjs';
export function summarizeWeatherChanges(data: Forecast, nowMs?: number): string[];
export interface WeatherBriefEntry { emoji: string; text: string; kind: 'storm'|'snow'|'rain'|'wind'|'calm'|'stable' }
export interface WeatherBrief { period: string; entries: WeatherBriefEntry[] }
export function summarizeWeatherBrief(data: Forecast, nowMs?: number): WeatherBrief;
