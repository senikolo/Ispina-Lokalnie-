'use client';

import {useEffect, useState} from 'react';
import styles from './wideo.module.css';

type Video = {
  id: string;
  title: string;
  publishedAt: string;
  embeddable: boolean;
};
type VideoFeed = {
  schema: number;
  channel: {id: string; title: string; url: string};
  nextPageToken: string | null;
  items: Video[];
};
const VIDEO_API = 'https://ispina-most-odjazdy.vercel.app/api/youtube-videos';

function videoCountLabel(count: number) {
  if (count === 1) return 'film';
  const last = count % 10;
  const lastTwo = count % 100;
  return last >= 2 && last <= 4 && (lastTwo < 12 || lastTwo > 14) ? 'filmy' : 'filmów';
}

function isPlayable(video: Video) {
  return /^[A-Za-z0-9_-]{11}$/.test(video.id)
    && typeof video.title === 'string'
    && video.embeddable === true;
}

export default function VideoPage() {
  const [videos, setVideos] = useState<Video[]>([]);
  const [thumbnailsReady, setThumbnailsReady] = useState(false);
  const [selectedId, setSelectedId] = useState('');
  const [nextPageToken, setNextPageToken] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadingMore, setLoadingMore] = useState(false);
  const [autoplay, setAutoplay] = useState(false);
  const [playerVersion, setPlayerVersion] = useState(0);
  const [error, setError] = useState('');
  const [moreError, setMoreError] = useState('');
  const [retryKey, setRetryKey] = useState(0);

  useEffect(() => {
    let active = true;
    async function loadVideos() {
      setLoading(true);
      setError('');
      try {
        const response = await fetch(VIDEO_API, {cache: 'no-store'});
        const data = await response.json() as VideoFeed & {error?: string};
        if (!response.ok || data.error || !Array.isArray(data.items)) {
          throw new Error('feed_unavailable');
        }
        const playable = data.items.filter(isPlayable);
        if (!active) return;
        setVideos(playable);
        setThumbnailsReady(true);
        setNextPageToken(data.nextPageToken || null);
        const query = new URLSearchParams(window.location.search);
        const requestedId = query.get('v') || '';
        const selected = playable.some(video => video.id === requestedId)
          ? requestedId
          : playable[0]?.id || '';
        setSelectedId(selected);
        setAutoplay(Boolean(selected && selected === requestedId && query.get('autoplay') === '1'));
      } catch {
        if (active) setError('Nie udało się teraz pobrać listy filmów. Spróbuj ponownie za chwilę.');
      } finally {
        if (active) setLoading(false);
      }
    }
    void loadVideos();
    return () => { active = false; };
  }, [retryKey]);

  const selectedVideo = videos.find(video => video.id === selectedId);

  function chooseVideo(video: Video) {
    setSelectedId(video.id);
    setAutoplay(true);
    setPlayerVersion(value => value + 1);
    const url = new URL(window.location.href);
    url.searchParams.set('v', video.id);
    url.searchParams.set('autoplay', '1');
    window.history.replaceState({}, '', url.pathname + url.search + url.hash);
    document.getElementById('il-video-player')?.scrollIntoView({behavior: 'smooth', block: 'start'});
  }

  async function loadMore() {
    if (!nextPageToken || loadingMore) return;
    setLoadingMore(true);
    setMoreError('');
    try {
      const response = await fetch(VIDEO_API + '?pageToken=' + encodeURIComponent(nextPageToken), {cache: 'no-store'});
      const data = await response.json() as VideoFeed & {error?: string};
      if (!response.ok || data.error || !Array.isArray(data.items)) {
        throw new Error('next_page_unavailable');
      }
      const nextItems = data.items.filter(isPlayable);
      setVideos(current => {
        const known = new Set(current.map(video => video.id));
        return current.concat(nextItems.filter(video => !known.has(video.id)));
      });
      setNextPageToken(data.nextPageToken || null);
    } catch {
      setMoreError('Nie udało się wczytać kolejnych filmów.');
    } finally {
      setLoadingMore(false);
    }
  }

  const playerUrl = selectedId
    ? 'https://www.youtube-nocookie.com/embed/' + selectedId
      + '?autoplay=' + (autoplay ? '1' : '0') + '&rel=0&playsinline=1&modestbranding=1'
    : '';

  return (
    <div className={styles.page}>
      <header className={styles.header}>
        <a className={styles.brand} href="/" aria-label="Ispina Lokalnie — strona główna">
          <span className={styles.brandMark} aria-hidden="true">IL</span>
          <span>Ispina <strong>Lokalnie</strong></span>
        </a>
        <a className={styles.backLink} href="https://ispinalokalnie.com/galeria/#wideo">
          <span aria-hidden="true">←</span> Wróć do galerii
        </a>
      </header>

      <main className={styles.main}>
        <section className={styles.intro}>
          <p className={styles.kicker}>Ispina Lokalnie <span aria-hidden="true">·</span> wideo</p>
          <h1>Filmy z Ispiny<span className={styles.headingDot}>.</span></h1>
          <p className={styles.lead}>Wybierz kafelek, a film uruchomi się w odtwarzaczu powyżej.</p>
        </section>

        <section className={styles.playerCard} id="il-video-player" aria-label="Odtwarzacz filmów">
          <div className={styles.playerHeading}>
            <span className={styles.nowLabel}><span className={styles.liveDot} /> TERAZ OGLĄDASZ</span>
            <h2>{selectedVideo?.title || (loading ? 'Ładowanie filmów…' : 'Odtwarzacz IL')}</h2>
          </div>
          <div className={styles.playerFrame}>
            {selectedVideo ? (
              <iframe
                key={selectedId + ':' + playerVersion}
                src={playerUrl}
                title={'Odtwarzacz filmu: ' + selectedVideo.title}
                allow="accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope; picture-in-picture; web-share"
                allowFullScreen
                referrerPolicy="strict-origin-when-cross-origin"
              />
            ) : (
              <div className={styles.playerPlaceholder} role="status">
                {loading ? 'Przygotowujemy listę filmów…' : error ? 'Odtwarzacz będzie dostępny, gdy filmy zostaną wczytane.' : 'Wybierz film z listy poniżej.'}
              </div>
            )}
          </div>
          <p className={styles.playerNote}>Kafelek zmienia film w tym samym okienku.</p>
        </section>

        <section className={styles.playlist} aria-labelledby="playlist-title">
          <div className={styles.listHeading}>
            <div>
              <p className={styles.kicker}>Biblioteka filmów</p>
              <h2 id="playlist-title">Wybierz film</h2>
            </div>
            {!loading && !error && <span className={styles.count}>{videos.length} {videoCountLabel(videos.length)}</span>}
          </div>

          {error && (
            <div className={styles.message} role="alert">
              <span>{error}</span>
              <button type="button" onClick={() => setRetryKey(value => value + 1)}>Spróbuj ponownie</button>
            </div>
          )}

          {loading && <div className={styles.emptyState} role="status">Pobieramy najnowsze filmy…</div>}

          {!loading && !error && videos.length === 0 && (
            <div className={styles.emptyState}>Na kanale nie ma teraz filmów dostępnych w tym odtwarzaczu.</div>
          )}

          {!loading && !error && videos.length > 0 && (
            <ol className={styles.videoList}>
              {videos.map((video, index) => {
                const selected = video.id === selectedId;
                return (
                  <li key={video.id}>
                    <button
                      type="button"
                      className={[styles.tile, selected ? styles.tileSelected : ''].join(' ')}
                      aria-current={selected ? 'true' : undefined}
                      onClick={() => chooseVideo(video)}
                    >
                      <span className={styles.thumbnail}>
                        <img
                          src={thumbnailsReady ? 'https://i.ytimg.com/vi/' + video.id + '/hqdefault.jpg' : undefined}
                          alt=""
                          onError={event => { event.currentTarget.style.display = 'none'; }}
                        />
                        <span className={styles.thumbnailPlay} aria-hidden="true">▶</span>
                      </span>
                      <span className={styles.tileText}>
                        <span className={styles.tileMeta}>
                          <span className={styles.tileNumber}>{String(index + 1).padStart(2, '0')}</span>
                          {selected && <span className={styles.playing}>Teraz odtwarzany</span>}
                        </span>
                        <span className={styles.tileTitle}>{video.title}</span>
                      </span>
                      <span className={styles.tileArrow} aria-hidden="true">↗</span>
                    </button>
                  </li>
                );
              })}
            </ol>
          )}

          {moreError && <p className={styles.moreError} role="alert">{moreError}</p>}
          {nextPageToken && !loading && !error && (
            <button className={styles.loadMore} type="button" onClick={loadMore} disabled={loadingMore}>
              {loadingMore ? 'Wczytujemy…' : 'Pokaż kolejne filmy'}
            </button>
          )}
        </section>
      </main>

      <footer className={styles.footer}>
        <span className={styles.footerBrand}><span className={styles.brandMark} aria-hidden="true">IL</span> Ispina Lokalnie</span>
        <span>Filmy z kanału Ispina Lokalnie</span>
      </footer>
    </div>
  );
}
