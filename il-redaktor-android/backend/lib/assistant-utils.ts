export const REQUIRED_BACKEND_ENV = ['GOOGLE_GENERATIVE_AI_API_KEY'] as const;
export function getBackendRedisConfig(env: Record<string, string | undefined>) {
  return {
    url: env.UPSTASH_REDIS_REST_URL?.trim() || env.KV_REST_API_URL?.trim() || '',
    token: env.UPSTASH_REDIS_REST_TOKEN?.trim() || env.KV_REST_API_TOKEN?.trim() || '',
  };
}
export function getBackendReadiness(env: Record<string, string | undefined>) {
  const missing = REQUIRED_BACKEND_ENV.filter(name => !env[name]?.trim());
  const redis = getBackendRedisConfig(env);
  if (!redis.url) missing.push('UPSTASH_REDIS_REST_URL or KV_REST_API_URL');
  if (!redis.token) missing.push('UPSTASH_REDIS_REST_TOKEN or KV_REST_API_TOKEN');
  return { ready: missing.length === 0, missing };
}
export const RESEARCH_PROFILES = [
  { id: 'procurements', title: 'Przetargi i zamówienia', focus: 'Postępowania, zamówienia publiczne i umowy bezpośrednio dotyczące Ispiny. Numer, status i termin podaj wyłącznie po potwierdzeniu w dokumencie.' },
  { id: 'drwinia-bip', title: 'Gmina Drwinia i BIP', focus: 'Uchwały, komunikaty, inwestycje, załączniki i ogłoszenia Gminy Drwinia oraz jej Biuletynu Informacji Publicznej.' },
  { id: 'procurement-platforms', title: 'Platformy postępowań', focus: 'BZP, e-Zamówienia i inne platformy postępowań; uwzględnij wzmianki w załącznikach i opisach lokalizacji.' },
  { id: 'local-media', title: 'Lokalne i regionalne media', focus: 'Serwisy z powiatu bocheńskiego, Niepołomic i okolicy; uwzględniaj tylko wyraźny związek z Ispiną.' },
  { id: 'infrastructure', title: 'Infrastruktura i usługi publiczne', focus: 'Drogi, mosty, transport, OSP i infrastruktura w Ispinie; odróżniaj informacje o sąsiednich miejscowościach.' },
  { id: 'name-variants', title: 'Odmiany nazwy i materiały archiwalne', focus: 'Formy Ispina, w Ispinie, do Ispiny, z Ispiny oraz błędne warianty Ispini i Ispinii; archiwa i sąsiednie miejsca wyłącznie z potwierdzonym związkiem.' },
] as const;
export type GroundedSource = { title: string; url: string };
export function normalizeGroundedSources(items: unknown): GroundedSource[] {
  if (!Array.isArray(items)) return [];
  const seen = new Set<string>(), result: GroundedSource[] = [];
  for (const item of items) {
    if (!item || typeof item !== 'object') continue;
    const source = item as Record<string, unknown>;
    if (source.sourceType !== 'url' || typeof source.url !== 'string') continue;
    try {
      const url = new URL(source.url);
      if ((url.protocol !== 'https:' && url.protocol !== 'http:') || url.username || url.password) continue;
      url.hash = '';
      if (seen.has(url.href)) continue;
      seen.add(url.href);
      const title = typeof source.title === 'string' && source.title.trim() ? source.title.trim().slice(0, 180) : url.hostname;
      result.push({ title, url: url.href });
      if (result.length === 30) break;
    } catch { /* Ignore malformed provider URLs. */ }
  }
  return result;
}
export function extractGroundingQueries(metadata: unknown): string[] {
  if (!metadata || typeof metadata !== 'object') return [];
  const google = (metadata as Record<string, unknown>).google;
  if (!google || typeof google !== 'object') return [];
  const grounding = (google as Record<string, unknown>).groundingMetadata;
  if (!grounding || typeof grounding !== 'object') return [];
  const raw = (grounding as Record<string, unknown>).webSearchQueries;
  if (!Array.isArray(raw)) return [];
  const seen = new Set<string>(), queries: string[] = [];
  for (const item of raw) {
    if (typeof item !== 'string') continue;
    const query = item.trim().slice(0, 180);
    if (!query || seen.has(query)) continue;
    seen.add(query); queries.push(query);
    if (queries.length === 20) break;
  }
  return queries;
}
export function rateLimitExceeded(userSuccess: boolean, ipSuccess: boolean) { return !userSuccess || !ipSuccess; }
