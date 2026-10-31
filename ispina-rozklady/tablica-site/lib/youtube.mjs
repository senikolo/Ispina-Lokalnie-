// Fixed-channel, read-only YouTube Data API adapter. This module is server-only.
// The UC ID is the canonical link of the handle supplied by the owner (2026-10-28).
export const CHANNEL_ID = 'UCeeKoKHiH-rGyQOZn6wW-7A';
export const CHANNEL_URL = 'https://www.youtube.com/channel/' + CHANNEL_ID;
const API = 'https://www.googleapis.com/youtube/v3/';
const TTL = 60000;

export class YoutubeError extends Error {
  constructor(code, status = 503, diagnostic = null) { super(code); this.code = code; this.status = status; this.diagnostic = diagnostic; }
}

// Only enum-like reasons are retained. Google messages may echo the submitted
// key, so never log messages, metadata, full bodies, headers or request URLs.
const SAFE_REASONS = new Set(['API_KEY_INVALID','API_KEY_SERVICE_BLOCKED','API_KEY_HTTP_REFERRER_BLOCKED',
  'API_KEY_IP_ADDRESS_BLOCKED','SERVICE_DISABLED','BILLING_DISABLED','RATE_LIMIT_EXCEEDED','QUOTA_EXCEEDED',
  'ACCESS_TOKEN_SCOPE_INSUFFICIENT','keyInvalid','accessNotConfigured','quotaExceeded','dailyLimitExceeded',
  'ipRefererBlocked','forbidden','badRequest','playlistNotFound','channelNotFound','backendError']);
function diagnosticFor(text, resource, status) {
  let reasons = [];
  try {
    const data = JSON.parse(text);
    reasons = [...(data.error?.errors || []), ...(data.error?.details || [])]
      .map(item => item.reason).filter(reason => SAFE_REASONS.has(reason));
  } catch {}
  return {resource, status, reasons:[...new Set(reasons)]};
}

export function validPageToken(value) {
  return typeof value === 'string' && (value === '' || /^[A-Za-z0-9_=-]{1,512}$/.test(value));
}

export function createYoutubeService({fetcher = fetch, now = Date.now, getKey = () => process.env.IL_YOUTUBE_API_KEY} = {}) {
  let channel, channelUntil = 0, channelPending;
  const pages = new Map();
  async function request(resource, params) {
    const key = getKey()?.trim();
    if (!key) throw new YoutubeError('not_configured');
    const url = new URL(resource, API);
    Object.entries(params).forEach(([name, value]) => url.searchParams.set(name, value));
    // Never return upstream messages/URLs: they may contain credentials.
    try {
      const response = await fetcher(url.href, {cache:'no-store', redirect:'error',
        signal:AbortSignal.timeout(7000), headers:{'X-Goog-Api-Key':key}});
      const text = await response.text();
      if (text.length > 500000) throw new YoutubeError('invalid_response');
      if (!response.ok) throw new YoutubeError(response.status === 403 || response.status === 429 ? 'quota_or_key' : 'youtube_unavailable',
        503, diagnosticFor(text, resource, response.status));
      const data = JSON.parse(text);
      if (!Array.isArray(data.items)) throw new YoutubeError('invalid_response');
      return data;
    } catch (e) {
      if (e instanceof YoutubeError) throw e;
      const failure = ['TimeoutError','AbortError','SyntaxError','TypeError'].includes(e?.name) ? e.name : 'upstream_failure';
      throw new YoutubeError('youtube_unavailable',503,{resource,failure});
    }
  }
  async function getChannel() {
    if (channel && now() < channelUntil) return channel;
    if (channelPending) return channelPending;
    channelPending = (async () => {
      const data = await request('channels', {id:CHANNEL_ID, part:'snippet,contentDetails',
        fields:'items(id,snippet/title,contentDetails/relatedPlaylists/uploads)'});
      const item = data.items[0], uploads = item?.contentDetails?.relatedPlaylists?.uploads;
      if (data.items.length !== 1 || item.id !== CHANNEL_ID || typeof uploads !== 'string' || !/^[A-Za-z0-9_-]{10,80}$/.test(uploads)) {
        throw new YoutubeError('channel_unavailable');
      }
      channel = {id:CHANNEL_ID, title:String(item.snippet?.title || 'Ispina Lokalnie'), url:CHANNEL_URL, uploads};
      channelUntil = now() + 3600000;
      return channel;
    })();
    try { return await channelPending; } finally { channelPending = null; }
  }
  async function loadPage(token) {
    const source = await getChannel();
    const params = {playlistId:source.uploads, part:'contentDetails', maxResults:'50',
      fields:'nextPageToken,items(contentDetails/videoId)'};
    if (token) params.pageToken = token;
    const page = await request('playlistItems', params);
    if (page.items.length > 50 || (page.nextPageToken && !validPageToken(page.nextPageToken))) throw new YoutubeError('invalid_response');
    const ids = [...new Set(page.items.map(item => item.contentDetails?.videoId).filter(id => /^[A-Za-z0-9_-]{11}$/.test(id)))];
    // Resolve current titles, ownership, visibility and embed permission. Deleted /
    // private videos absent from videos.list cannot leak placeholder metadata.
    const details = ids.length ? await request('videos', {id:ids.join(','), part:'snippet,status',
      fields:'items(id,snippet(channelId,title,publishedAt),status(privacyStatus,embeddable))'}) : {items:[]};
    const byId = new Map(details.items.filter(item => item.snippet?.channelId === CHANNEL_ID && item.status?.privacyStatus === 'public')
      .map(item => [item.id, item]));
    const items = ids.filter(id => byId.has(id)).map(id => {
      const item = byId.get(id);
      return {id, title:String(item.snippet.title), publishedAt:item.snippet.publishedAt, embeddable:item.status.embeddable === true};
    });
    return {schema:1, channel:{id:source.id, title:source.title, url:source.url}, uploadsPlaylistId:source.uploads,
      fetchedAt:new Date(now()).toISOString(), nextPageToken:page.nextPageToken || null, items};
  }
  return async function getPage(token = '') {
    if (!validPageToken(token)) throw new YoutubeError('invalid_page', 400);
    const cached = pages.get(token);
    if (cached && now() < cached.until) {
      if (cached.error) throw cached.error;
      return cached.pending || cached.value;
    }
    // Bound memory even when strangers send arbitrary valid-looking page tokens.
    if (pages.size >= 64) pages.delete(pages.keys().next().value);
    const entry = {until:now() + TTL};
    pages.set(token, entry);
    entry.pending = loadPage(token);
    try {
      entry.value = await entry.pending;
      entry.until = now() + TTL;
      return entry.value;
    } catch (error) {
      entry.error = error;
      entry.until = now() + 10000;
      throw error;
    } finally { entry.pending = null; }
  };
}

const getPage = createYoutubeService();
const HEADERS = {'Access-Control-Allow-Origin':'*', 'X-Content-Type-Options':'nosniff'};
export async function youtubeResponse(request, service = getPage) {
  const url = new URL(request.url);
  const params = [...url.searchParams.keys()];
  if (params.some(name => name !== 'pageToken') || params.length > 1 || !validPageToken(url.searchParams.get('pageToken') || '')) {
    return Response.json({error:'invalid_page'}, {status:400, headers:{...HEADERS, 'Cache-Control':'no-store'}});
  }
  try {
    return Response.json(await service(url.searchParams.get('pageToken') || ''), {
      headers:{...HEADERS, 'Cache-Control':'public, max-age=0, s-maxage=60, must-revalidate'}});
  } catch (e) {
    const error = e instanceof YoutubeError ? e : new YoutubeError('youtube_unavailable');
    console.error('IL YouTube:', error.code, error.diagnostic || {}); // Sanitized enums only.
    return Response.json({error:error.code}, {status:error.status, headers:{...HEADERS, 'Cache-Control':'no-store', 'Retry-After':'60'}});
  }
}
