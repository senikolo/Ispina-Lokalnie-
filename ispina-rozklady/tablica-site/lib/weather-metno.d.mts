import type { Forecast } from './weather.mjs';
export function metnoUrl(): URL;
export function metnoCode(symbol: string): number | null;
export function normalizeMetno(raw: unknown, now?: number): Forecast;
export function validateMetnoForecast(data: Forecast, now?: number): Forecast;
export function summarizeMetno(data: Forecast, now?: number, warnings?: unknown[]): {period:string;entries:{kind:string;text:string}[]};
export function createMetnoLoader(fetcher?: typeof fetch, clock?: () => number): () => Promise<Forecast>;
