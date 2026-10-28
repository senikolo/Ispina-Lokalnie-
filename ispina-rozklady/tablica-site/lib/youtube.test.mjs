import {test} from 'node:test';
import assert from 'node:assert/strict';
import {CHANNEL_ID, createYoutubeService, youtubeResponse, YoutubeError} from './youtube.mjs';

function fixture({fail, empty = false} = {}) {
  const calls = [];
  const uploads = 'UUfromOfficialApiNotDerived';
  const fetcher = async (raw, options) => {
    const url = new URL(raw); calls.push({url, options});
    if (fail) return new Response('upstream-secret-must-not-escape', {status:fail});
    let value;
    if (url.pathname.endsWith('/channels')) value = {items:[{id:CHANNEL_ID, snippet:{title:'Ispina Lokalnie'}, contentDetails:{relatedPlaylists:{uploads}}}]};
    else if (url.pathname.endsWith('/playlistItems')) {
      assert.equal(url.searchParams.get('playlistId'), uploads);
      assert.equal(url.searchParams.get('maxResults'), '50');
      value = empty ? {items:[]} : url.searchParams.has('pageToken') ? {items:[{contentDetails:{videoId:'ccccccccccc'}}]} : {
        nextPageToken:'PAGE_2', items:['aaaaaaaaaaa','bbbbbbbbbbb','private0000','wrongowner0'].map(videoId => ({contentDetails:{videoId}}))};
    } else if (url.pathname.endsWith('/videos')) value = {items:url.searchParams.get('id').split(',').map(id => ({id,
      snippet:{channelId:id === 'wrongowner0' ? 'another-channel' : CHANNEL_ID, title:'Title ' + id, publishedAt:'2026-10-28T10:00:00Z'},
      status:{privacyStatus:id === 'private0000' ? 'private' : 'public', embeddable:id !== 'bbbbbbbbbbb'}}))};
    else throw new Error('Unexpected resource');
    return Response.json(value);
  };
  return {fetcher, calls};
}

test('official uploads ID, fixed ownership, public videos, embed permission, all pages', async () => {
  const f = fixture(), service = createYoutubeService({fetcher:f.fetcher, getKey:() => 'test-secret'});
  const first = await service();
  assert.equal(first.uploadsPlaylistId, 'UUfromOfficialApiNotDerived');
  assert.deepEqual(first.items.map(v => [v.id, v.embeddable]), [['aaaaaaaaaaa',true],['bbbbbbbbbbb',false]]);
  const second = await service(first.nextPageToken);
  assert.equal(second.items[0].id, 'ccccccccccc');
  assert.equal(second.nextPageToken, null);
  assert.ok(!JSON.stringify(first).includes('test-secret'));
  for (const call of f.calls) {
    assert.equal(call.url.origin, 'https://www.googleapis.com');
    assert.equal(call.url.searchParams.has('key'), false);
    assert.equal(call.options.headers['X-Goog-Api-Key'], 'test-secret');
  }
});

test('parallel calls coalesce, cached lists expire and empty lists replace old data', async () => {
  const f = fixture({empty:true}); let now = 1793188800000;
  const service = createYoutubeService({fetcher:f.fetcher, getKey:() => 'test-secret', now:() => now});
  const [a,b] = await Promise.all([service(), service()]);
  assert.equal(a,b); assert.deepEqual(a.items,[]); assert.equal(f.calls.length,2);
  await service(); assert.equal(f.calls.length,2);
  now += 61000; await service(); assert.equal(f.calls.length,3);
});

test('HTTP route validates parameters and does not become a general YouTube proxy', async () => {
  let calls = 0;
  for (const suffix of ['?channelId=other','?pageToken=one&pageToken=two','?pageToken=%3Cscript%3E']) {
    const response = await youtubeResponse(new Request('https://example.test/api/youtube-videos' + suffix), async () => { calls++; });
    assert.equal(response.status,400);
  }
  assert.equal(calls,0);
  const response = await youtubeResponse(new Request('https://example.test/api/youtube-videos'), async () => ({items:[]}));
  assert.equal(response.headers.get('Access-Control-Allow-Origin'),'*');
  assert.match(response.headers.get('Cache-Control'),/s-maxage=60/);
});

test('missing key, upstream errors and raw exceptions return sanitized uncached errors', async () => {
  const service = createYoutubeService({getKey:() => ''});
  await assert.rejects(service(), e => e.code === 'not_configured');
  for (const status of [403,429,500]) {
    const f = fixture({fail:status});
    const response = await youtubeResponse(new Request('https://example.test/api/youtube-videos'),
      createYoutubeService({fetcher:f.fetcher, getKey:() => 'test-secret'}));
    assert.equal(response.status,503); assert.equal(response.headers.get('Cache-Control'),'no-store');
    assert.ok(!(await response.text()).includes('secret'));
  }
  const response = await youtubeResponse(new Request('https://example.test/api/youtube-videos'), () => { throw new Error('SECRET'); });
  assert.deepEqual(await response.json(),{error:'youtube_unavailable'});
});

test('channel mismatch, malformed pages and repeated failures are rejected and throttled in-process', async () => {
  let calls = 0;
  const service = createYoutubeService({getKey:() => 'test', fetcher:async () => { calls++; return Response.json({items:[]}); }});
  await assert.rejects(service(), e => e instanceof YoutubeError && e.code === 'channel_unavailable');
  await assert.rejects(service()); assert.equal(calls,1);
  await assert.rejects(service('bad!'), e => e.status === 400);
});

test('Google failure diagnostics retain only safe reasons, never echoed keys or messages', async () => {
  const service = createYoutubeService({getKey:() => ' test-secret ', fetcher:async (_url, options) => {
    assert.equal(options.headers['X-Goog-Api-Key'],'test-secret');
    return Response.json({error:{message:'test-secret',details:[{reason:'API_KEY_INVALID',metadata:{key:'test-secret'}},{reason:'test-secret'}]}},{status:400});
  }});
  await assert.rejects(service(), error => {
    assert.deepEqual(error.diagnostic,{resource:'channels',status:400,reasons:['API_KEY_INVALID']});
    assert.ok(!JSON.stringify(error).includes('test-secret'));
    return true;
  });
});
