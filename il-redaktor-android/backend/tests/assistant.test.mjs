import assert from 'node:assert/strict';
import test from 'node:test';
import { GET, OPTIONS, POST } from '../api/assistant.ts';
import { extractGroundingQueries, getBackendReadiness, normalizeGroundedSources, rateLimitExceeded, RESEARCH_PROFILES } from '../lib/assistant-utils.ts';
test('readiness requires all three nonblank environment values', () => {
  assert.deepEqual(getBackendReadiness({ GOOGLE_GENERATIVE_AI_API_KEY: 'key', UPSTASH_REDIS_REST_URL: 'url', UPSTASH_REDIS_REST_TOKEN: 'token' }), { ready: true, missing: [] });
  assert.deepEqual(getBackendReadiness({ GOOGLE_GENERATIVE_AI_API_KEY: 'key', UPSTASH_REDIS_REST_URL: ' ', UPSTASH_REDIS_REST_TOKEN: 'token' }), { ready: false, missing: ['UPSTASH_REDIS_REST_URL'] });
});
test('research has six distinct profiles', () => { assert.equal(RESEARCH_PROFILES.length, 6); assert.equal(new Set(RESEARCH_PROFILES.map(p => p.id)).size, 6); });
test('grounded sources keep only unique safe HTTP URLs', () => {
  assert.deepEqual(normalizeGroundedSources([
    { sourceType: 'url', title: 'One', url: 'https://example.com/page#section' },
    { sourceType: 'url', title: 'Duplicate', url: 'https://example.com/page#other' },
    { sourceType: 'url', title: 'Credentials', url: 'https://user:pass@example.com/' },
    { sourceType: 'url', title: 'Script', url: 'javascript:alert(1)' },
    { sourceType: 'text', title: 'Text', url: 'https://example.com/text' },
  ]), [{ title: 'One', url: 'https://example.com/page' }]);
});
test('grounding queries are trimmed and deduplicated', () => {
  assert.deepEqual(extractGroundingQueries({ google: { groundingMetadata: { webSearchQueries: [' Ispina przetarg ', 'Ispina przetarg', '', 7] } } }), ['Ispina przetarg']);
});
test('rate limiter requires both user and IP buckets to pass', () => {
  assert.equal(rateLimitExceeded(true, true), false); assert.equal(rateLimitExceeded(false, true), true); assert.equal(rateLimitExceeded(true, false), true);
});
test('GET readiness does not disclose missing environment variable names', async () => {
  const names=['GOOGLE_GENERATIVE_AI_API_KEY','UPSTASH_REDIS_REST_URL','UPSTASH_REDIS_REST_TOKEN'],prev=Object.fromEntries(names.map(n=>[n,process.env[n]]));
  try { names.forEach(n=>delete process.env[n]); const r=await GET(),b=await r.json(); assert.equal(r.status,503); assert.equal(b.ready,false); assert.equal(JSON.stringify(b).includes('UPSTASH_REDIS_REST_TOKEN'),false); }
  finally { names.forEach(n=>prev[n]===undefined?delete process.env[n]:process.env[n]=prev[n]); }
});
test('preflight advertises GET readiness', async () => { const r=await OPTIONS(); assert.equal(r.status,204); assert.match(r.headers.get('Access-Control-Allow-Methods')||'',/\bGET\b/); });
test('POST reports an unconfigured backend before WordPress auth', async () => {
  const names=['GOOGLE_GENERATIVE_AI_API_KEY','UPSTASH_REDIS_REST_URL','UPSTASH_REDIS_REST_TOKEN'],prev=Object.fromEntries(names.map(n=>[n,process.env[n]]));
  try { names.forEach(n=>delete process.env[n]); const r=await POST(new Request('https://example.test/api/assistant',{method:'POST',body:'{}'})); assert.equal(r.status,503); }
  finally { names.forEach(n=>prev[n]===undefined?delete process.env[n]:process.env[n]=prev[n]); }
});
