# Wideo — kanał YouTube i jeden odtwarzacz

Ten PR zastępuje podejście z roboczego PR #6. WordPress nie jest źródłem listy filmów. Kod aplikacji nie analizuje HTML YouTube, nie używa InnerTube ani usług pośrednich opartych na scrapingu i nie zawiera klucza Data API.

## Potwierdzone źródło

Właściciel wskazał 28.10.2026: <https://youtube.com/@slawomirfranciszekkozak-s1n9f> (parametr udostępniania `si` pominięto).

Oficjalna strona tego adresu zwróciła kanoniczny odnośnik:

```html
<link rel="canonical" href="https://www.youtube.com/channel/UCeeKoKHiH-rGyQOZn6wW-7A">
```

`og:url` wskazywało ten sam adres, `og:title`: „Sławomir Franciszek Kozak”. To jednorazowa weryfikacja tożsamości wskazanego przez właściciela kanału, nie mechanizm pobierania listy. Kod serwera przypina **UCeeKoKHiH-rGyQOZn6wW-7A**, więc późniejsza zmiana nazwy/uchwytu nie przekieruje aplikacji na inny kanał. Trzy filmy ze starej galerii nie są listą awaryjną.

## Przepływ danych

1. Otwarcie Wideo lub **Odśwież listę** → `GET https://ispina-most-odjazdy.vercel.app/api/youtube-videos`.
2. Serwer: `channels.list(part=snippet,contentDetails,id=UCeeKoKHiH-rGyQOZn6wW-7A)` → **rzeczywista** wartość `contentDetails.relatedPlaylists.uploads`. Nie wyliczamy ID playlisty przez zamianę UC na UU.
3. `playlistItems.list(playlistId=...,maxResults=50)` → `nextPageToken`; aplikacja pobiera kolejne strony aż do końca, bez limitu trzech, trzydziestu ani pięćdziesięciu filmów. Zduplikowane filmy są scalane, zapętlony token kończy się błędem, nie nieskończoną pętlą.
4. Serwer: `videos.list(part=snippet,status,id=...)` dla każdej strony → aktualne tytuły, daty, przynależność do kanału, publiczna widoczność i zgoda na osadzanie.
5. Aplikacja → **jeden** iframe `youtube-nocookie.com` obsługiwany przez oficjalny **YouTube IFrame Player API**. Wybór pozycji wywołuje `loadVideoById` na istniejącym obiekcie. `ENDED` przełącza go na następny film z listy kanału; nie tworzy kolejnego odtwarzacza. Poprzedni/Następny omijają filmy z wyłączonym osadzaniem. Wszystkie publiczne pozycje pozostają widoczne, z odnośnikiem do YouTube.

Kolejność odpowiada playliście przesłanych filmów zwracanej przez YouTube. Jest to kolejka aplikacji z tej playlisty, nie pobieranie plików wideo. Nie opieramy pełnej listy na liczbie pozycji zwróconych przez odtwarzacz ani na kanale RSS, który nie jest pełnym archiwum. Nowe publiczne nagranie pojawi się po uwzględnieniu go przez YouTube API i ponownym pobraniu listy; nowy APK nie jest potrzebny.

## Uruchomienie endpointu — wymagane przed odbiorem na urządzeniu

**PR sam nie uruchamia usługi.** Istniejący projekt serwera Next.js znajduje się w `ispina-rozklady/tablica-site`, hosting: `ispina-most-odjazdy`. Według dokumentacji repozytorium nie ma podłączonego automatycznego wdrażania z GitHub. W tym zadaniu nie wdrażano serwera ani nie publikowano aplikacji.

Najprostsza konfiguracja do wykonania po decyzji właściciela:

1. W Google Cloud utworzyć projekt (lub użyć osobnego istniejącego projektu IL), włączyć **YouTube Data API v3**, utworzyć klucz i ograniczyć go do tego API. Publiczne dane nie wymagają logowania odbiorcy ani OAuth.
2. Zapisać wartość jako sekret serwera **`IL_YOUTUBE_API_KEY`** w ustawieniach hostingu tego projektu. Bez `NEXT_PUBLIC_`, bez wpisywania wartości do kodu, APK, opisu PR lub logów. W repozytorium jest wyłącznie pusty `.env.example`; lokalny `.env.local` jest ignorowany.
3. Wdrożyć sprawdzony kod endpointu na powyższy adres HTTPS. Nie wykonywać tego kroku bez zgody właściciela. Zweryfikować odpowiedź `schema:1`, `channel.id`, `uploadsPlaylistId`, aktualne `items` i `nextPageToken`.
4. Dopiero wtedy sprawdzić APK na urządzeniu. Bez sekretu endpoint świadomie zwraca **503 `not_configured`**, a aplikacja pokazuje czytelny stan oraz odnośnik do kanału, zamiast udawać aktualną listę.

Klucz jest przekazywany do Google wyłącznie przez serwer w nagłówku `X-Goog-Api-Key`. Endpoint nie przyjmuje adresu upstream, kanału ani ID dowolnej playlisty. Przyjmuje tylko token strony; błędy/logi nie zawierają surowych odpowiedzi Google, URL-i żądań ani klucza.

CDN i pamięć procesu buforują stronę przez 60 s, informacje o playliście kanału przez godzinę. Odświeżenie pyta endpoint za każdym razem, ale świeżość może opóźnić się o około dwie minuty plus propagację danych w YouTube. Równoczesne wywołania strony w procesie są łączone. To nie jest rozproszony limit żądań: wiele instancji lub celowy ruch może zużyć limit API. Publiczne CORS nie jest kontrolą dostępu. W konsoli Google trzeba obserwować wykorzystanie limitu; przy większym ruchu zastosować limitowanie na hostingu lub wspólny cache. Nie dodano płatnej bazy ani nowej płatnej usługi.

`channels.list`, `playlistItems.list` i `videos.list` kosztują po 1 jednostce za wywołanie. Pobranie N stron to zwykle `1 + 2N` jednostek na zimnym cache, mniej przy trafieniach. Dokumentacja Google podaje domyślnie 10 000 jednostek dziennie łącznie dla tych metod; rzeczywisty limit projektu należy sprawdzić w konsoli. Większy limit może wymagać audytu. Hosting ma własne limity.

## Zachowanie aplikacji i ograniczenia YouTube

- Otwarcie, powrót do aplikacji i ponowne wczytanie odtwarzacza tylko przygotowują film (`autoplay=0`, `cueVideoById`). Dźwięk zaczyna się po działaniu użytkownika. Przejście po zakończeniu wymaga widocznego odtwarzacza; gdy użytkownik przewinął go poza ekran, następny film jest przygotowany bez dźwięku. WebView może wymagać dodatkowego dotknięcia; komunikat `onAutoplayBlocked` to wyjaśnia.
- Wyjście z Wideo, Wstecz, ukrycie dokumentu i opuszczenie strony usuwają iframe i unieważniają trwające pobranie. Spóźniona odpowiedź nie odtworzy ukrytego filmu. Odświeżanie listy zachowuje bieżący odtwarzacz i wybraną pozycję, jeśli nadal jest dostępna.
- Po pełnym poprawnym pobraniu zapisujemy metadane w localStorage na najwyżej **24 godziny**. Błąd odświeżenia zachowuje oznaczoną datą poprzednią listę; przerwane stronicowanie nie nadpisuje jej częścią wyników. Wygaśnięty/nieprawidłowy zapis jest usuwany. Pusta odpowiedź usuwa stare pozycje. Nie zapisujemy plików wideo; odtwarzanie wymaga internetu. Zapis może być niedostępny lub zapełniony — aplikacja nadal działa online.
- Lista obejmuje publiczne filmy zwrócone przez API dla tego kanału. Nie wykrywa prywatnych ani niepublicznych filmów. Wyłączone osadzanie, usunięcie filmu, ograniczenia wieku/regionu, prawa autorskie, transmisje i decyzje YouTube mogą uniemożliwić odtwarzanie. Dostępny jest odnośnik do YouTube. Komunikaty obejmują błędy 100, 101, 150 i 153 oraz brak odpowiedzi.
- Nowy ekran używa składni ES5 i XMLHttpRequest, bez zależności od `fetch`, `URLSearchParams`, `AbortController` lub NodeList.forEach. Minimalny viewport odtwarzacza: 200×200. Zgodność aplikacji Android 6+ jest zachowana; sam YouTube może przestać obsługiwać starą wersję silnika WebView. Test Chromium nie jest testem fizycznego Androida 6.
- Oryginalna lokalna powłoka używa `loadDataWithBaseURL` z bazą `https://ispinalokalnie.wordpress.com/`. Zachowujemy ten origin i automatyczny HTTPS Referer, `referrerpolicy=strict-origin-when-cross-origin`; `widget_referrer` identyfikuje pakiet. Ten parametr analityczny nie zastępuje nagłówka Referer. Natywnego pochodzenia dokumentu nie zmieniamy w tym PR. Aktualne wymagania YouTube opisują także identyfikację aplikacji przez app ID; błąd 153 wymaga kontroli na rzeczywistym urządzeniu i ewentualnej zmiany natywnej integracji, nie podszywania się pod inną witrynę.

## Baza APK i zakres

Tak jak w PR #6, jedyna zatwierdzona baza generatora to `downloads/Ispina-Lokalnie-UltraSmart-77-Drogowskaz.apk`, SHA-256 `7c49ebf0c20563335148952f6c6e6da27028bd974a700216cb2d711028184a23`, pakiet `com.ispina.mldcheck.smartai44demo`, wersja `2.0.0-smart.77`, kod 20348. Wynik: `2.0.0-smart.79`, kod 20350. Inna baza i nadpisanie oryginału są odrzucane.

Zmieniamy manifest, `assets/main_ui.js`, `assets/il-build.json` i stałą HTML/CSP w `classes4.dex`; pozostałe bajty DEX, instrukcje i pliki APK pozostają identyczne. CSP dopuszcza wyłącznie `https://www.youtube-nocookie.com` dla ramek, `object-src 'none'` pozostaje. Zachowano porządkowanie Galerii z PR #6.

To **nie jest obietnica aktualizacji opublikowanego pakietu UltraSmart 78**: strona aplikacji opisuje go jako osobny pakiet. Przed wydaniem trzeba ustalić docelowy zainstalowany pakiet, jego bazę i pasujący oryginalny podpis. Generator z tego PR wytwarza wyłącznie roboczy **UNSIGNED**, który nie jest instalacyjny. Nie dodano workflow podpisującego ani publikującego. Nie łączono PR #6.

## Sprawdzenie

```sh
python3 tools/ultrasmart78-video-tab/test_patch.py
python3 tools/ultrasmart78-video-tab/patch_ultrasmart78_video.py \
  downloads/Ispina-Lokalnie-UltraSmart-77-Drogowskaz.apk /tmp/il-video-unsigned.apk
python3 tools/ultrasmart78-video-tab/extract_ui_fixture.py /tmp/il-video-unsigned.apk /tmp/il-video-ui
python3 tools/ultrasmart78-video-tab/extract_ui_fixture.py \
  downloads/Ispina-Lokalnie-UltraSmart-77-Drogowskaz.apk /tmp/il-original-ui
npm install --prefix /tmp/il-video-tests --no-save --ignore-scripts playwright@1.56.1 acorn@8.15.0
node /tmp/il-video-tests/node_modules/playwright/cli.js install --with-deps chromium
NODE_PATH=/tmp/il-video-tests/node_modules node tools/ultrasmart78-video-tab/test_ui.cjs /tmp/il-video-ui.js /tmp/il-original-ui.html --expect-csp-block
NODE_PATH=/tmp/il-video-tests/node_modules node tools/ultrasmart78-video-tab/test_ui.cjs /tmp/il-video-ui.js /tmp/il-video-ui.html
cd ispina-rozklady/tablica-site
npm ci --ignore-scripts
npm test
npm run build
```

CI `validate-youtube-video.yml` wykonuje kontrole bez kluczy, podpisywania, publikowania i udostępniania APK. Test UI uruchamia pełny JavaScript oraz rzeczywistą powłokę HTML/CSP wyciągnięte z APK. Sprawdza jeden iframe, wybór tego samego odtwarzacza, przejście po ENDED, stronicowanie, nowy film, tekstowe tytuły, awarie/offline/wygasły zapis/pusty kanał, opóźnione odpowiedzi, zatrzymanie po ukryciu, Wstecz i szerokości 320/360/800 px. Odpowiedzi YouTube i API są atrapami. Nie dowodzi to rzeczywistego streamingu ani poprawności klucza.

Odbiór ręczny **po konfiguracji usługi i uzgodnieniu podpisanej wersji testowej**:

1. Android 6 i nowszy: Wideo → sprawdzić brak dźwięku przy wejściu, nazwę kanału i listę względem YouTube.
2. Wybrać drugą pozycję: zmienia się film w jednym oknie. Poprzedni/Następny i koniec krótkiego filmu przechodzą w tej samej kolejce; ostatni film kończy kolejkę.
3. Dodać publiczny film na kanale; po propagacji YouTube i upływie cache wybrać Odśwież lub ponownie otworzyć zakładkę. Film ma się pojawić bez innego APK.
4. Wyłączyć internet: wcześniejsza lista ma datę i ostrzeżenie; pierwsze uruchomienie bez zapisu pokazuje błąd. Włączyć internet i odświeżyć.
5. Wyjść do Pulpitu, nacisnąć Wstecz, ukryć aplikację — dźwięk ustaje. Powrót nie wznawia dźwięku.
6. Sprawdzić nagranie z wyłączonym osadzaniem, błąd 153, obrót ekranu i Tryb Senior. Odnośniki do YouTube pozostają dostępne.

Nie wykonano testu prawdziwej listy przez Data API (brak skonfigurowanego klucza), rzeczywistego odtwarzania, podpisania ani instalacji na Androidzie. To pozostaje warunkiem odbioru, nie deklarowanym wynikiem testów.

## Oficjalna dokumentacja

- [Pobieranie filmów kanału](https://developers.google.com/youtube/data/guides/implementation/videos#retrieve_a_channels_uploaded_videos)
- [channels.list / forHandle](https://developers.google.com/youtube/data/reference/rest/v3/channels/list)
- [playlistItems.list i nextPageToken](https://developers.google.com/youtube/data/reference/rest/v3/playlistItems/list)
- [videos.list](https://developers.google.com/youtube/data/reference/rest/v3/videos/list)
- [IFrame Player API](https://developers.google.com/youtube/iframe_api_reference)
- [Wymagania WebView, Referer i rozmiaru odtwarzacza](https://developers.google.com/youtube/terms/required-minimum-functionality)
- [Limity YouTube API](https://developers.google.com/youtube/data/guides/quota_and_compliance_audits)
- [Klucz API w nagłówku](https://docs.cloud.google.com/docs/authentication/api-keys-use)
