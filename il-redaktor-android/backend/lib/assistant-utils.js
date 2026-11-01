export const REQUIRED_BACKEND_ENV = Object.freeze(['GOOGLE_GENERATIVE_AI_API_KEY']);

function asRecord(value) {
  return value && typeof value === 'object' ? value : null;
}

function redactProviderMessage(value) {
  return value
    .replace(/\\bAIza[0-9A-Za-z_-]{20,}\\b/g, '[redacted]')
    .replace(/\\bBearer\\s+[^\\s,;]+/gi, 'Bearer [redacted]')
    .replace(/((?:api[_-]?key|token|secret)\\s*[:=]\\s*)[^\\s,;]+/gi, '$1[redacted]')
    .replace(/([?&]key=)[^&\\s]+/gi, '$1[redacted]')
    .replace(/[\\r\\n\\t]+/g, ' ')
    .replace(/\\s{2,}/g, ' ')
    .trim()
    .slice(0, 240);
}

/**
 * Extracts only safe, actionable details from AI SDK retry/API errors.
 * Never includes request bodies, prompts, headers, or credentials.
 */
export function summarizeProviderError(error) {
  const root = asRecord(error);
  if (!root) return { category: 'unknown', statusCode: null, providerCode: '', retryable: null, message: '' };

  let info = asRecord(root.lastError)
    || (Array.isArray(root.errors) ? asRecord(root.errors.at(-1)) : null)
    || asRecord(root.cause)
    || root;
  for (let depth = 0; depth < 3; depth++) {
    if (Number.isInteger(info.statusCode) || info.responseBody) break;
    const nested = asRecord(info.lastError) || asRecord(info.cause);
    if (!nested) break;
    info = nested;
  }

  let responseError = null;
  if (typeof info.responseBody === 'string') {
    try { responseError = asRecord(JSON.parse(info.responseBody)); } catch { /* Ignore unstructured provider responses. */ }
  } else {
    responseError = asRecord(info.responseBody);
  }
  if (responseError && asRecord(responseError.error)) responseError = asRecord(responseError.error);

  const bodyCode = responseError?.code;
  const bodyStatus = responseError?.status;
  const statusFromBody = Number.isInteger(bodyCode) && bodyCode >= 400 && bodyCode <= 599 ? bodyCode : null;
  const statusCode = Number.isInteger(info.statusCode) ? info.statusCode : statusFromBody;
  const providerCode = typeof bodyStatus === 'string' ? bodyStatus
    : typeof bodyCode === 'string' ? bodyCode
    : typeof info.code === 'string' ? info.code : '';
  const rawMessage = typeof responseError?.message === 'string' ? responseError.message
    : typeof info.message === 'string' ? info.message : '';
  const message = redactProviderMessage(rawMessage);
  const normalizedCode = providerCode.toUpperCase();
  const lowerMessage = message.toLowerCase();

  let category = 'unknown';
  if (statusCode === 429 || normalizedCode === 'RESOURCE_EXHAUSTED'
    || /quota|rate limit|resource exhausted|billing/.test(lowerMessage)) category = 'quota';
  else if (statusCode === 401 || statusCode === 403
    || normalizedCode === 'UNAUTHENTICATED' || normalizedCode === 'PERMISSION_DENIED'
    || /api key.{0,30}(invalid|expired|denied)|permission denied/.test(lowerMessage)) category = 'auth';
  else if (statusCode === 400 || normalizedCode === 'INVALID_ARGUMENT') category = 'configuration';
  else if (statusCode === 404 || normalizedCode === 'NOT_FOUND') category = 'model';
  else if ((statusCode !== null && statusCode >= 500) || info.isRetryable === true) category = 'transient';

  return {
    category,
    statusCode: Number.isInteger(statusCode) ? statusCode : null,
    providerCode: providerCode.slice(0, 80),
    retryable: typeof info.isRetryable === 'boolean' ? info.isRetryable : null,
    message,
  };
}


export function getBackendRedisConfig(env) {
  return {
    url: env.UPSTASH_REDIS_REST_URL?.trim() || env.KV_REST_API_URL?.trim() || '',
    token: env.UPSTASH_REDIS_REST_TOKEN?.trim() || env.KV_REST_API_TOKEN?.trim() || '',
  };
}

export function getBackendReadiness(env) {
  const missing = REQUIRED_BACKEND_ENV.filter(name => !env[name]?.trim());
  const redis = getBackendRedisConfig(env);
  if (!redis.url) missing.push('UPSTASH_REDIS_REST_URL or KV_REST_API_URL');
  if (!redis.token) missing.push('UPSTASH_REDIS_REST_TOKEN or KV_REST_API_TOKEN');
  return { ready: missing.length === 0, missing };
}

export const RESEARCH_PROFILES = Object.freeze([
  { id: 'procurements', title: 'Przetargi i zamówienia', focus: 'Postępowania, zamówienia publiczne i umowy bezpośrednio dotyczące Ispiny. Numer, status i termin podaj wyłącznie po potwierdzeniu w dokumencie.' },
  { id: 'drwinia-bip', title: 'Gmina Drwinia i BIP', focus: 'Uchwały, komunikaty, inwestycje, załączniki i ogłoszenia Gminy Drwinia oraz jej Biuletynu Informacji Publicznej.' },
  { id: 'procurement-platforms', title: 'Platformy postępowań', focus: 'BZP, e-Zamówienia i inne platformy postępowań; uwzględnij wzmianki w załącznikach i opisach lokalizacji.' },
  { id: 'local-media', title: 'Lokalne i regionalne media', focus: 'Serwisy z powiatu bocheńskiego, Niepołomic i okolicy; uwzględniaj tylko wyraźny związek z Ispiną.' },
  { id: 'infrastructure', title: 'Infrastruktura i usługi publiczne', focus: 'Drogi, mosty, transport, OSP i infrastruktura w Ispinie; odróżniaj informacje o sąsiednich miejscowościach.' },
  { id: 'name-variants', title: 'Odmiany nazwy i materiały archiwalne', focus: 'Formy Ispina, w Ispinie, do Ispiny, z Ispiny oraz błędne warianty Ispini i Ispinii; archiwa i sąsiednie miejsca wyłącznie z potwierdzonym związkiem.' },
]);

export function normalizeGroundedSources(items) {
  if (!Array.isArray(items)) return [];
  const seen = new Set(), result = [];
  for (const item of items) {
    if (!item || typeof item !== 'object') continue;
    const source = item;
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

export function extractGroundingQueries(metadata) {
  if (!metadata || typeof metadata !== 'object') return [];
  const google = metadata.google;
  if (!google || typeof google !== 'object') return [];
  const grounding = google.groundingMetadata;
  if (!grounding || typeof grounding !== 'object') return [];
  const raw = grounding.webSearchQueries;
  if (!Array.isArray(raw)) return [];
  const seen = new Set(), queries = [];
  for (const item of raw) {
    if (typeof item !== 'string') continue;
    const query = item.trim().slice(0, 180);
    if (!query || seen.has(query)) continue;
    seen.add(query); queries.push(query);
    if (queries.length === 20) break;
  }
  return queries;
}

export function rateLimitExceeded(userSuccess, ipSuccess) { return !userSuccess || !ipSuccess; }
