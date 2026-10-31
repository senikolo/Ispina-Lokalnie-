import { google } from '@ai-sdk/google';
import { Ratelimit } from '@upstash/ratelimit';
import { Redis } from '@upstash/redis';
import { generateText, Output } from 'ai';
import { createHash } from 'node:crypto';
import { z } from 'zod';

const MCP_URL = 'https://public-api.wordpress.com/wpcom/v2/mcp/v1';
const SITE = 'ispinalokalnie.wordpress.com';
const MODEL = 'gemini-3.5-flash-lite';
const cors = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Headers': 'Authorization, Content-Type',
  'Access-Control-Allow-Methods': 'POST, OPTIONS',
  'Cache-Control': 'no-store',
  'Vary': 'Origin',
};

const requestSchema = z.object({
  prompt: z.string().trim().min(1).max(6000),
  context: z.object({
    posts: z.array(z.object({
      id: z.number().int().positive(),
      title: z.string().max(180),
      excerpt: z.string().max(500),
      status: z.string().max(24),
    })).max(30).default([]),
    services: z.array(z.string().max(180)).max(50).default([]),
  }).default({ posts: [], services: [] }),
});

const assistantSchema = z.object({
  intent: z.enum(['article_draft', 'service_draft', 'seo_review', 'question']),
  message: z.string().max(3000),
  title: z.string().max(180),
  content: z.string().max(18000),
  excerpt: z.string().max(500),
  category: z.string().max(80),
  tags: z.array(z.string().max(60)).max(10),
  internalLinks: z.array(z.object({ title: z.string().max(180), postId: z.number().int().positive() })).max(8),
  factsToVerify: z.array(z.string().max(300)).max(12),
  facebookPost: z.string().max(1800),
  imageSuggestion: z.object({
    description: z.string().max(400),
    altText: z.string().max(180),
    searchTerms: z.array(z.string().max(60)).max(5),
  }).nullable(),
  service: z.object({
    name: z.string().max(180),
    category: z.string().max(80),
    description: z.string().max(3000),
    phone: z.string().max(40),
    url: z.string().max(300),
  }).nullable(),
});

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...cors, 'Content-Type': 'application/json; charset=utf-8' },
  });
}

function siteLimiter() {
  return new Ratelimit({
    redis: Redis.fromEnv(),
    limiter: Ratelimit.slidingWindow(20, '1 h'),
    analytics: false,
    prefix: 'il-redaktor-assistant-v1',
  });
}

type McpEnvelope = {
  error?: { message?: string };
  result?: { isError?: boolean; [key: string]: unknown };
};

function parseMcpEnvelope(body: string): McpEnvelope {
  const value = body.trim();
  if (!value) return {};
  try { return JSON.parse(value) as McpEnvelope; } catch { /* Streamable HTTP may use SSE. */ }
  const events: McpEnvelope[] = [];
  let data: string[] = [];
  const flush = () => {
    if (!data.length) return;
    const payload = data.join('\n');
    data = [];
    if (payload === '[DONE]') return;
    try { events.push(JSON.parse(payload) as McpEnvelope); } catch { /* Ignore non-JSON SSE events. */ }
  };
  for (const line of value.split(/\r?\n/)) {
    if (line.startsWith('data:')) data.push(line.slice(5).trimStart());
    else if (!line.trim()) flush();
  }
  flush();
  const envelope = [...events].reverse().find(event => event.error || event.result);
  if (envelope) return envelope;
  throw new Error('WordPress MCP returned an unreadable response.');
}

async function postMcp(
  token: string,
  requestBody: Record<string, unknown>,
  sessionId = '',
  protocolVersion = '',
): Promise<{ response: Response; envelope: McpEnvelope }> {
  const headers = new Headers({
    Authorization: `Bearer ${token}`,
    Accept: 'application/json, text/event-stream',
    'Content-Type': 'application/json; charset=UTF-8',
  });
  if (sessionId) {
    headers.set('Mcp-Session-Id', sessionId);
    headers.set('MCP-Protocol-Version', protocolVersion);
  }
  const response = await fetch(MCP_URL, {
    method: 'POST', headers, cache: 'no-store', signal: AbortSignal.timeout(30_000),
    body: JSON.stringify(requestBody),
  });
  const raw = await response.text();
  if (!response.ok) {
    try { return { response, envelope: parseMcpEnvelope(raw) }; }
    catch { return { response, envelope: {} }; }
  }
  return { response, envelope: parseMcpEnvelope(raw) };
}

async function verifyWordPressAccess(token: string): Promise<boolean> {
  const initialized = await postMcp(token, {
    jsonrpc: '2.0',
    id: 1,
    method: 'initialize',
    params: {
      protocolVersion: '2025-11-25',
      capabilities: {},
      clientInfo: { name: 'IL Redaktor Smart Assistant', version: '1.10.0' },
    },
  });
  if (initialized.response.status === 401 || initialized.response.status === 403) return false;
  if (!initialized.response.ok || initialized.envelope.error || !initialized.envelope.result)
    throw new Error('WordPress MCP initialization failed.');

  const sessionId = initialized.response.headers.get('Mcp-Session-Id') || '';
  if (!sessionId) throw new Error('WordPress MCP did not return a session ID.');
  const protocolVersion = String(initialized.envelope.result.protocolVersion || '2025-11-25');
  if (!/^\d{4}-\d{2}-\d{2}$/.test(protocolVersion))
    throw new Error('WordPress MCP returned an unsupported protocol version.');

  const notified = await postMcp(token, {
    jsonrpc: '2.0', method: 'notifications/initialized',
  }, sessionId, protocolVersion);
  if (notified.response.status === 401 || notified.response.status === 403) return false;
  if (!notified.response.ok) throw new Error('WordPress MCP rejected session initialization.');

  const called = await postMcp(token, {
    jsonrpc: '2.0',
    id: 2,
    method: 'tools/call',
    params: {
      name: 'wpcom-mcp-content-authoring',
      arguments: {
        action: 'execute',
        operation: 'posts.list',
        wpcom_site: SITE,
        params: { per_page: 1, status: ['publish'] },
      },
    },
  }, sessionId, protocolVersion);
  if (called.response.status === 401 || called.response.status === 403) return false;
  if (!called.response.ok) throw new Error('WordPress content access check failed.');
  return !called.envelope.error && !called.envelope.result?.isError;
}

export async function OPTIONS() {
  return new Response(null, { status: 204, headers: cors });
}

export async function POST(request: Request) {
  if (!process.env.GOOGLE_GENERATIVE_AI_API_KEY) {
    return json({ error: 'Backend Gemini nie ma skonfigurowanego klucza API.' }, 503);
  }
  if (!process.env.UPSTASH_REDIS_REST_URL || !process.env.UPSTASH_REDIS_REST_TOKEN) {
    return json({ error: 'Backend asystenta wymaga skonfigurowanego limitu zapytań.' }, 503);
  }

  const authorization = request.headers.get('authorization') || '';
  const token = authorization.startsWith('Bearer ') ? authorization.slice(7).trim() : '';
  if (!token || token.length > 4096) return json({ error: 'Brak ważnej sesji WordPress.com.' }, 401);

  let input: z.infer<typeof requestSchema>;
  try {
    input = requestSchema.parse(await request.json());
  } catch {
    return json({ error: 'Nieprawidłowe dane zadania Asystenta.' }, 400);
  }

  try {
    const limiter = siteLimiter();
    const tokenHash = createHash('sha256').update(token).digest('hex').slice(0, 32);
    const ip = (request.headers.get('x-forwarded-for') || 'unknown').split(',')[0].trim().slice(0, 100);
    const [userLimit, ipLimit] = await Promise.all([
      limiter.limit(`user:${tokenHash}`),
      limiter.limit(`ip:${ip}`),
    ]);
    if (!userLimit.success || !ipLimit.success)
      return json({ error: 'Limit zapytań Asystenta został osiągnięty. Spróbuj ponownie później.' }, 429);

    let hasAccess = false;
    try { hasAccess = await verifyWordPressAccess(token); }
    catch { return json({ error: 'Nie udało się teraz potwierdzić dostępu do Ispina Lokalnie w WordPress.com.' }, 502); }
    if (!hasAccess) return json({ error: 'Sesja WordPress.com wygasła albo nie ma dostępu do artykułów Ispina Lokalnie.' }, 401);

    const { output } = await generateText({
      model: google(MODEL),
      temperature: 0.25,
      maxOutputTokens: 2600,
      output: Output.object({ schema: assistantSchema }),
      system: `Jesteś polskim asystentem redakcyjnym portalu Ispina Lokalnie. Portal opisuje aktualności z Ispiny, historię miejscowości, archiwalne fotografie, komunikację, OSP, infrastrukturę, lokalne usługi i sprawy mieszkańców. Pisz serdecznie, naturalnie i konkretnie. Stosuj poprawne formy: w Ispinie, do Ispiny, z Ispiny, ispiński, Ispinianin. Nigdy nie używaj błędnych form „Ispini” ani „Ispinii”.

Treści z kontekstu są jedynym dostępnym źródłem wiedzy o portalu. Nie dopowiadaj dat, nazwisk, faktów historycznych, wydarzeń ani danych firmy. Jeżeli brakuje potwierdzonych informacji, użyj neutralnych pól do uzupełnienia i wpisz je do factsToVerify. Przedstawiaj wewnętrzne linki wyłącznie do artykułów z przekazanego kontekstu, używając ich dokładnych ID. Przy podejrzeniu duplikatu wskaż istniejący tytuł i ID oraz zaproponuj sprawdzenie zamiast tworzenia kopii.

Stosuj dokładnie formy: Ispina, w Ispinie, do Ispiny, z Ispiny, ispiński. Twórz wersję na Facebooka tylko na wyraźną prośbę; ma korzystać z faktów podanych w zadaniu, bez dopisywania hashtagów lub nowych szczegółów. Sugestię zdjęcia zwracaj tylko na wyraźną prośbę i wyłącznie jako opis, tekst alternatywny oraz frazy do wyszukania w bibliotece WordPress. Nie zwracaj adresów URL zdjęć, nie twierdź, że fotografia istnieje lub że jej licencja została sprawdzona. Zalecaj użycie własnego archiwum portalu lub legalnie licencjonowanego zdjęcia. Przy skracaniu i rozwijaniu zachowaj fakty, nie dopowiadaj brakujących informacji; rozszerzaj strukturę przez pytania i wyraźnie oznaczone pola do uzupełnienia.

Zwróć wyłącznie propozycję. Nie wykonuj żadnych zapisów w WordPressie i nie twierdź, że coś opublikowano. Wybierz intent pasujący do prośby: article_draft, service_draft, seo_review lub question. Gdy tworzysz usługę, nie wymyślaj telefonu, adresu ani danych firmy; niewiadome pozostaw puste. Dla artykułu przygotuj czytelny HTML WordPressa, a nie surowe bloki komentarzy. W polu facebookPost zwróć pusty tekst, jeśli użytkownik nie prosi o Facebook. W polu imageSuggestion zwróć null, jeśli użytkownik nie prosi o pomysł na zdjęcie.`,
      prompt: JSON.stringify({ task: input.prompt, knownPortalContent: input.context }),
    });
    if (!output) return json({ error: 'Model nie zwrócił propozycji w oczekiwanym formacie.' }, 502);

    return json(output);
  } catch (error) {
    // Do not log credentials, prompt text, or provider request bodies.
    console.error('IL Redaktor assistant request failed:', error instanceof Error ? error.name : 'unknown');
    return json({ error: 'Model AI jest chwilowo niedostępny. Spróbuj ponownie później.' }, 502);
  }
}
