// ES5 + XMLHttpRequest for Android 6 WebView. No Data API credentials in this file.
;(function () {
 'use strict';
 try {
  var d = document, nav = d.getElementById('nav'), panel = d.getElementById('iaP');
  if (!nav || !panel || d.getElementById('ilWideoViewSmart78')) return;
  var CHANNEL = 'UCeeKoKHiH-rGyQOZn6wW-7A';
  var CHANNEL_URL = 'https://www.youtube.com/channel/' + CHANNEL;
  var ENDPOINT = 'https://ispina-most-odjazdy.vercel.app/api/youtube-videos';
  var CACHE = 'il-youtube-uploads-v1-' + CHANNEL, MAX_AGE = 24 * 3600000;
  var items = [], selected = '', player = null, ready = false, wantPlay = false;
  var serial = 0, xhr = null, apiLoading = false, apiTimer = null, playerTimer = null;
  function each(nodes, fn) { Array.prototype.forEach.call(nodes, fn); }
  function el(tag, cls, text) { var e = d.createElement(tag); if (cls) e.className = cls; if (text != null) e.textContent = text; return e; }
  var style = el('style');
  style.textContent = '#ilWideoViewSmart78{box-sizing:border-box;padding:14px 14px 30px;color:#173b32;max-width:900px;margin:auto}' +
   '#ilWideoViewSmart78 .ilv-head{padding:14px;background:#edf6f2;border:1px solid #d7e9e1;border-radius:16px}' +
   '#ilWideoViewSmart78 h2{margin:0 0 7px;color:#174875;font-size:25px}' +
   '#ilWideoViewSmart78 p{font-size:14px;line-height:1.5}' +
   '#ilWideoViewSmart78 .ilv-frame{position:relative;width:100%;height:52vw;min-height:200px;max-height:480px;margin:14px 0;background:#102c3c}' +
   '#ilWideoViewSmart78 iframe{display:block;position:absolute;left:0;top:0;width:100%;height:100%;border:0}' +
   '#ilWideoViewSmart78 button{font:inherit;min-height:44px;cursor:pointer}' +
   '#ilWideoViewSmart78 .ilv-controls{display:flex;flex-wrap:wrap;gap:8px;margin:10px 0}' +
   '#ilWideoViewSmart78 .ilv-controls button{padding:8px 12px;border:1px solid #b9d5cc;border-radius:10px;color:#174875;background:#fff}' +
   '#ilWideoViewSmart78 button:disabled{opacity:.5;cursor:default}' +
   '#ilWideoViewSmart78 .ilv-list{list-style:none;padding:0;margin:0}' +
   '#ilWideoViewSmart78 .ilv-list li{padding:10px;margin:8px 0;background:#fff;border:1px solid #d7e9e1;border-radius:12px}' +
   '#ilWideoViewSmart78 .ilv-list li[aria-current=true]{border:2px solid #176f77;background:#edf6f2}' +
   '#ilWideoViewSmart78 .ilv-select{display:block;width:100%;padding:8px;border:0;background:transparent;text-align:left;color:#174875;overflow-wrap:break-word}' +
   '#ilWideoViewSmart78 small{display:block;color:#526477;margin:4px 8px}' +
   '#ilWideoViewSmart78 a{color:#176f77}#ilWideoViewSmart78 button:focus{outline:2px solid #174875;outline-offset:2px}';
  d.head.appendChild(style);
  var view = el('div', 'view'); view.id = 'ilWideoViewSmart78'; view.setAttribute('aria-label', 'Wideo Ispina Lokalnie');
  view.innerHTML = '<div class="ilv-head"><h2>Wideo</h2><p>Filmy z kanału Ispina Lokalnie na YouTube. Wybierz nagranie z listy. Kolejne dostępne filmy odtwarzają się w tym samym oknie.</p><a href="' + CHANNEL_URL + '">Kanał w YouTube</a></div>' +
   '<p id="ilvStatus" role="status" aria-live="polite"></p><div class="ilv-controls"><button type="button" id="ilvRefresh">Odśwież listę</button></div>' +
   '<div class="ilv-frame" id="ilvFrame"></div><p id="ilvPlayerStatus" role="status" aria-live="polite">Wybierz film po pobraniu listy.</p>' +
   '<p><a id="ilvWatch" href="' + CHANNEL_URL + '">Otwórz w YouTube</a></p>' +
   '<div class="ilv-controls"><button type="button" id="ilvPrevious" disabled>Poprzedni</button><button type="button" id="ilvNext" disabled>Następny</button><button type="button" id="ilvRetry">Wczytaj odtwarzacz ponownie</button></div>' +
   '<h3>Filmy z YouTube</h3><ol class="ilv-list" id="ilvList"></ol>';
  panel.appendChild(view);
  function q(id) { return d.getElementById(id); }
  var status = q('ilvStatus'), playerStatus = q('ilvPlayerStatus'), frame = q('ilvFrame'), list = q('ilvList');
  function visible() { return view.classList.contains('on') && panel.classList.contains('on') && !d.hidden; }
  function currentIndex() { for (var i = 0; i < items.length; i++) if (items[i].id === selected) return i; return -1; }
  function adjacent(direction) {
   var i = currentIndex() + direction;
   while (i >= 0 && i < items.length) { if (items[i].embeddable) return items[i]; i += direction; }
   return null;
  }
  function controls() {
   q('ilvPrevious').disabled = !adjacent(-1); q('ilvNext').disabled = !adjacent(1);
   q('ilvWatch').href = selected ? 'https://www.youtube.com/watch?v=' + selected : CHANNEL_URL;
   each(list.children, function (row) { if (row.getAttribute('data-id') === selected) row.setAttribute('aria-current', 'true'); else row.removeAttribute('aria-current'); });
  }
  function playerVisible() {
   var r = frame.getBoundingClientRect(), h = Math.min(r.bottom, window.innerHeight) - Math.max(r.top, 70);
   return visible() && h > r.height / 2;
  }
  function stopPlayer() {
   clearTimeout(playerTimer); ready = false; wantPlay = false;
   if (player) { try { player.destroy(); } catch (_) {} player = null; }
   frame.textContent = '';
  }
  function errorText(code) {
   if (code === 100) return 'Film został usunięty lub jest prywatny.';
   if (code === 101 || code === 150) return 'Właściciel filmu wyłączył odtwarzanie w aplikacjach.';
   if (code === 153) return 'YouTube nie rozpoznał aplikacji (błąd 153). Zaktualizuj WebView lub otwórz film w YouTube.';
   return 'YouTube nie może odtworzyć filmu' + (code ? ' (błąd ' + code + ')' : '') + '. Sprawdź internet i wersję WebView lub otwórz film w YouTube.';
  }
  function createPlayer() {
   if (!visible() || player || !selected || !window.YT || !window.YT.Player) return;
   var iframe = el('iframe'); iframe.id = 'ilvYoutubePlayer'; iframe.title = 'Odtwarzacz YouTube — Ispina Lokalnie';
   iframe.setAttribute('allow', 'autoplay; encrypted-media; picture-in-picture');
   iframe.setAttribute('allowfullscreen', 'allowfullscreen');
   iframe.setAttribute('referrerpolicy', 'strict-origin-when-cross-origin');
   // The real native shell uses loadDataWithBaseURL(https://ispinalokalnie.wordpress.com/).
   // Keep its HTTPS origin / Referer; never use a data: or file: player URL.
   var origin = location.protocol === 'https:' ? location.protocol + '//' + location.host : 'https://ispinalokalnie.wordpress.com';
   iframe.src = 'https://www.youtube-nocookie.com/embed/' + selected + '?enablejsapi=1&autoplay=0&playsinline=1&rel=0&origin=' + encodeURIComponent(origin) + '&widget_referrer=' + encodeURIComponent('https://com.ispina.mldcheck.smartai44demo/');
   frame.appendChild(iframe);
   playerStatus.textContent = 'Wczytuję odtwarzacz YouTube…';
   var instance;
   player = instance = new window.YT.Player(iframe, {events:{
    onReady:function () {
     if (player !== instance || !visible()) return;
     clearTimeout(playerTimer); ready = true;
     if (wantPlay) { wantPlay = false; instance.loadVideoById(selected); }
     else { instance.cueVideoById(selected); playerStatus.textContent = 'Dotknij odtwarzacza lub wybierz film, aby rozpocząć.'; }
    },
    onStateChange:function (event) {
     if (player !== instance || !visible()) return;
     if (event.data === 1) playerStatus.textContent = 'Odtwarzanie. Po filmie przejdziemy do następnego nagrania.';
     if (event.data === 0) {
      var next = adjacent(1);
      if (next) choose(next.id, playerVisible());
      else playerStatus.textContent = 'Koniec playlisty kanału.';
     }
    },
    onError:function (event) { if (player === instance) { clearTimeout(playerTimer); wantPlay = false; playerStatus.textContent = errorText(event.data); } },
    onAutoplayBlocked:function () { if (player === instance) playerStatus.textContent = 'WebView wymaga dotknięcia przycisku odtwarzania w oknie YouTube.'; }
   }});
   playerTimer = setTimeout(function () { if (player === instance && !ready) playerStatus.textContent = 'Odtwarzacz nie odpowiada. Sprawdź internet, spróbuj ponownie lub otwórz film w YouTube.'; }, 15000);
  }
  var previousApiReady = window.onYouTubeIframeAPIReady;
  window.onYouTubeIframeAPIReady = function () {
   apiLoading = false; clearTimeout(apiTimer);
   if (typeof previousApiReady === 'function') { try { previousApiReady(); } catch (_) {} }
   createPlayer();
  };
  function ensurePlayer() {
   if (!visible() || !selected || player) return;
   if (window.YT && window.YT.Player) { createPlayer(); return; }
   if (apiLoading) return;
   apiLoading = true; playerStatus.textContent = 'Wczytuję odtwarzacz YouTube…';
   var old = q('ilvIframeApi'); if (old) old.parentNode.removeChild(old);
   var script = el('script'); script.id = 'ilvIframeApi'; script.src = 'https://www.youtube.com/iframe_api';
   function failed() { apiLoading = false; clearTimeout(apiTimer); wantPlay = false; if (visible()) playerStatus.textContent = errorText(); }
   script.onerror = failed; apiTimer = setTimeout(failed, 15000); d.head.appendChild(script);
  }
  function choose(id, play) {
   selected = id; controls(); wantPlay = !!play;
   if (player && ready) {
    wantPlay = false;
    if (play) player.loadVideoById(id);
    else { player.cueVideoById(id); playerStatus.textContent = 'Następny film jest gotowy. Dotknij odtwarzacza, aby rozpocząć.'; }
   } else ensurePlayer();
  }
  function render(data) {
   items = data; var selectedItem = null, first = null;
   each(items, function (item) { if (item.embeddable && !first) first = item; if (item.id === selected && item.embeddable) selectedItem = item; });
   if (!selectedItem) { stopPlayer(); selected = first ? first.id : ''; }
   list.textContent = '';
   each(items, function (item) {
    var row = el('li'); row.setAttribute('data-id', item.id);
    var button = el('button', 'ilv-select', item.title); button.type = 'button'; button.disabled = !item.embeddable;
    button.addEventListener('click', function () { if (visible()) { frame.scrollIntoView(true); choose(item.id, true); } });
    row.appendChild(button);
    var date = new Date(item.publishedAt), label = isNaN(date.getTime()) ? '' : date.toLocaleDateString('pl-PL');
    row.appendChild(el('small', '', label + (item.embeddable ? ' · YouTube' : ' · Odtwarzanie tylko na YouTube')));
    var link = el('a', '', 'Otwórz w YouTube'); link.href = 'https://www.youtube.com/watch?v=' + item.id; row.appendChild(link);
    list.appendChild(row);
   });
   controls(); ensurePlayer();
   if (!first) playerStatus.textContent = items.length ? 'Te filmy można obejrzeć na YouTube — osadzanie jest wyłączone.' : 'Na kanale nie ma obecnie dostępnych publicznych filmów.';
  }
  function validData(data) {
   if (!data || data.schema !== 1 || !data.channel || data.channel.id !== CHANNEL || !Array.isArray(data.items)) throw new Error('invalid');
   var age = Date.now() - Date.parse(data.fetchedAt);
   if (!isFinite(age) || age < -300000 || age > MAX_AGE) throw new Error('expired');
   var result = [], seen = {};
   each(data.items, function (item) {
    if (!item || !/^[A-Za-z0-9_-]{11}$/.test(item.id) || typeof item.title !== 'string' || typeof item.embeddable !== 'boolean') throw new Error('invalid');
    if (!seen[item.id]) { result.push({id:item.id, title:item.title, publishedAt:item.publishedAt, embeddable:item.embeddable}); seen[item.id] = true; }
   });
   return result;
  }
  function cached() {
   try { var data = JSON.parse(localStorage.getItem(CACHE) || 'null'); validData(data); return data; }
   catch (_) { try { localStorage.removeItem(CACHE); } catch (ignore) {} return null; }
  }
  function cancelRequest() { serial++; if (xhr) { xhr.abort(); xhr = null; } q('ilvRefresh').disabled = false; }
  function refresh() {
   cancelRequest(); var request = serial, all = [], seenTokens = {}, stamp = '', uploads = '';
   var saved = cached(); if (!items.length && saved) render(validData(saved));
   status.textContent = 'Pobieram aktualną listę z kanału YouTube…'; q('ilvRefresh').disabled = true;
   function failed(code) {
    if (request !== serial) return;
    xhr = null; q('ilvRefresh').disabled = false;
    var old = cached();
    if (old) { render(validData(old)); status.textContent = 'Nie udało się odświeżyć. Pokazuję zapisaną listę z ' + new Date(old.fetchedAt).toLocaleString('pl-PL') + '. Odtwarzanie wymaga internetu.'; }
    else { render([]); status.textContent = code === 'not_configured' ? 'Lista filmów nie jest jeszcze dostępna. Spróbuj później lub otwórz kanał w YouTube.' : 'Nie udało się pobrać listy filmów. Sprawdź internet i naciśnij „Odśwież listę” lub otwórz kanał w YouTube.'; }
   }
   function page(token) {
    if (request !== serial) return;
    xhr = new XMLHttpRequest(); xhr.open('GET', ENDPOINT + (token ? '?pageToken=' + encodeURIComponent(token) : ''), true); xhr.timeout = 25000;
    xhr.onerror = xhr.ontimeout = function () { failed(); };
    xhr.onload = function () {
     if (request !== serial) return;
     var data;
     try {
      data = JSON.parse(xhr.responseText);
      if (xhr.status < 200 || xhr.status >= 300) { failed(data.error); return; }
      var batch = validData(data);
      if (typeof data.uploadsPlaylistId !== 'string' || !/^[A-Za-z0-9_-]{10,80}$/.test(data.uploadsPlaylistId) || (uploads && uploads !== data.uploadsPlaylistId)) throw new Error('playlist changed');
      uploads = data.uploadsPlaylistId;
      if (!stamp || Date.parse(data.fetchedAt) < Date.parse(stamp)) stamp = data.fetchedAt;
      all = all.concat(batch);
      var next = data.nextPageToken;
      if (next != null && (typeof next !== 'string' || !/^[A-Za-z0-9_=-]{1,512}$/.test(next) || seenTokens[next])) throw new Error('invalid page');
      if (next) { seenTokens[next] = true; status.textContent = 'Pobieram kolejne filmy z YouTube… (' + all.length + ')'; page(next); return; }
      var snapshot = {schema:1, channel:{id:CHANNEL}, fetchedAt:stamp, items:all};
      snapshot.items = validData(snapshot);
      try { localStorage.setItem(CACHE, JSON.stringify(snapshot)); } catch (_) {}
      xhr = null; q('ilvRefresh').disabled = false; render(snapshot.items);
      status.textContent = snapshot.items.length ? 'Filmy z YouTube: ' + snapshot.items.length + '. Lista odświeżona ' + new Date(stamp).toLocaleString('pl-PL') + '.' : 'Brak publicznych filmów na kanale.';
     } catch (_) { failed(); }
    };
    try { xhr.send(); } catch (_) { failed(); }
   }
   page('');
  }
  var button = el('button', 'nb place'); button.type = 'button'; button.id = 'ilWideoTabSmart78';
  button.setAttribute('data-u', ':video'); button.setAttribute('aria-controls', view.id);
  button.innerHTML = '<span class="ni" aria-hidden="true">▶</span>Wideo';
  var camera = nav.querySelector('.nb[data-u=":camera"]');
  if (camera) camera.parentNode.insertBefore(button, camera.nextSibling);
  else { var group = null; each(nav.querySelectorAll('.ng'), function (n) { if (n.textContent.trim() === 'O ISPINIE') group = n; }); var next = group && group.nextElementSibling; while (next && !next.classList.contains('ng')) next = next.nextElementSibling; nav.insertBefore(button, next); }
  function open() {
   try { if (!(history.state && history.state.ia)) history.pushState({ia:1,video:1}, '', location.href); } catch (_) {}
   var drawer = q('iaD'), shade = q('sh'); if (drawer) drawer.classList.remove('on'); if (shade) shade.classList.remove('on');
   each(panel.querySelectorAll('.view'), function (x) { x.classList.remove('on'); });
   view.classList.add('on'); panel.classList.add('on'); if (q('sec')) q('sec').textContent = 'Wideo';
   panel.scrollTop = 0; wantPlay = false; refresh();
  }
  button.addEventListener('click', function (e) { e.preventDefault(); e.stopPropagation(); open(); }, true);
  q('ilvRefresh').addEventListener('click', refresh);
  q('ilvRetry').addEventListener('click', function () { stopPlayer(); ensurePlayer(); });
  q('ilvNext').addEventListener('click', function () { var next = adjacent(1); if (next) choose(next.id, true); });
  q('ilvPrevious').addEventListener('click', function () { var previous = adjacent(-1); if (previous) choose(previous.id, true); });
  var observer = new MutationObserver(function () { if (!visible()) { cancelRequest(); stopPlayer(); } else ensurePlayer(); });
  observer.observe(view, {attributes:true, attributeFilter:['class']}); observer.observe(panel, {attributes:true, attributeFilter:['class']});
  d.addEventListener('visibilitychange', function () { if (!visible()) { cancelRequest(); stopPlayer(); } else { wantPlay = false; refresh(); } });
  window.addEventListener('pagehide', function () { cancelRequest(); stopPlayer(); });
  // The app has a dedicated Wideo view. Keep the gallery focused on photos,
  // including content restored from its existing offline cache.
  var gallery=d.getElementById('vilPage1851'),galleryBody=d.getElementById('ilpBody1851');
  function cleanGallery(){
   if(!gallery||!galleryBody||gallery.getAttribute('data-il-page-slug')!=='galeria')return;
   each(galleryBody.querySelectorAll('a[href]'),function(a){
    var text=(a.textContent||'').replace(/\s+/g,' ').trim().toLowerCase();
    if(!/^(poznaj historię ispiny|przekaż fotografię lub wspomnienie|wideo)$/.test(text))return;
    var p=a.closest('p');a.remove();
    if(p&&!p.querySelector('img,figure,a')&&!p.textContent.replace(/[\s·•|]/g,''))p.remove();
   });
   each(galleryBody.querySelectorAll('h2'),function(h){
    if((h.textContent||'').trim().toLowerCase()!=='wideo')return;
    var next=h.nextSibling;
    while(next){
     if(next.nodeType===1&&/^H[12]$/.test(next.tagName))break;
     var old=next;next=next.nextSibling;old.remove();
    }
    h.remove();
   });
  }
  if(gallery&&galleryBody){
   var galleryObserver=new MutationObserver(cleanGallery);
   galleryObserver.observe(gallery,{attributes:true,attributeFilter:['data-il-page-slug']});
   galleryObserver.observe(galleryBody,{childList:true,subtree:true});
   cleanGallery();
  }

 } catch (e) { try { console.error('IL YouTube view:', e); } catch (_) {} }
})();
