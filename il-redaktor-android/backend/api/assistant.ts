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

async function verifyWordPressAccess(token: string): Promise<boolean> {
  const response = await fetch(MCP_URL, {
    method: 'POST',
    headers: {
      Authorization: `Bearer ${token}`,
      Accept: 'application/json',
      'Content-Type': 'application/json',
    },
    cache: 'no-store',
    body: JSON.stringify({
      jsonrpc: '2.0',
      id: 1,
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
    }),
  });
  if (response.status === 401 || response.status === 403) return false;
  if (!response.ok) throw new Error('WordPress verification temporarily failed.');
  const result = await response.json() as { error?: unknown; result?: { isError?: boolean } };
  return !result.error && !result.result?.isError;
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

Zwróć wyłącznie propozycję. Nie wykonuj żadnych zapisów w WordPressie i nie twierdź, że coś opublikowano. Wybierz intent pasujący do prośby: article_draft, service_draft, seo_review lub question. Gdy tworzysz usługę, nie wymyślaj telefonu, adresu ani danych firmy; niewiadome pozostaw puste. Dla artykułu przygotuj czytelny HTML WordPressa, a nie surowe bloki komentarzy.`,
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
