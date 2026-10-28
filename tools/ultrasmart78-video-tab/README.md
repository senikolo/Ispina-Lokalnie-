# Wideo — kanał YouTube i jeden odtwarzacz

**Bieżący status odbioru: zablokowany przez nieprawidłowy klucz API.** Usługę `/api/youtube-videos` wdrożono po potwierdzeniu konfiguracji przez właściciela. Hosting widzi sekret produkcyjny, ale Google zwraca HTTP 400 z powodami `badRequest` i `API_KEY_INVALID`; publiczna odpowiedź aplikacji to 503. Wartości sekretu nie odczytywano. Bez poprawnej wartości klucza nie da się sprawdzić pełnej listy ani uznać funkcji za gotową. Wcześniej przekazany APK 79 TEST nie realizuje tego celu.

Ekran Wideo nie ma już własnych odnośników „Otwórz w YouTube”, odnośników do kanału ani komunikatów kierujących do zewnętrznej strony. Wybór dostępnego filmu steruje wyłącznie osadzonym odtwarzaczem. Oryginalne logo i kontrolki YouTube pozostają zgodne z wymaganiami dostawcy. Film z zabronionym osadzaniem pokazuje przyczynę w aplikacji; nie obchodzimy tej blokady.

Generator aktualizacji przygotowuje następny kandydat **2.0.0-smart.80 / kod 20353**. Jego domyślne wywołanie najpierw uruchamia `verify_youtube_service.py`: wymaga rzeczywistej odpowiedzi HTTP 200, poprawnego CORS, właściwego kanału, świeżych danych, wszystkich stron i co najmniej jednego filmu z dozwolonym osadzaniem. Przy niedziałającej usłudze kończy się błędem **przed utworzeniem APK do przekazania**. Flaga `--allow-unconfigured-test` służy wyłącznie CI i testom offline; nie jest zgodą na przekazanie takiego wyniku jako działającej poprawki. Po konfiguracji API trzeba jeszcze sprawdzić rzeczywiste odtwarzanie na Androidzie.

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
5. Aplikacja → **jeden** iframe `youtube-nocookie.com` obsługiwany przez oficjalny **YouTube IFrame Player API**. Wybór pozycji wywołuje `loadVideoById` na istniejącym obiekcie. `ENDED` przełącza go na następny film z listy kanału; nie tworzy kolejnego odtwarzacza. Poprzedni/Następny omijają filmy z wyłączonym osadzaniem. Wszystkie publiczne pozycje pozostają widoczne; brak zgody na osadzanie jest opisany w aplikacji bez przekierowania.

Kolejność odpowiada playliście przesłanych filmów zwracanej przez YouTube. Jest to kolejka aplikacji z tej playlisty, nie pobieranie plików wideo. Nie opieramy pełnej listy na liczbie pozycji zwróconych przez odtwarzacz ani na kanale RSS, który nie jest pełnym archiwum. Nowe publiczne nagranie pojawi się po uwzględnieniu go przez YouTube API i ponownym pobraniu listy; nowy APK nie jest potrzebny.

## Uruchomienie endpointu — wymagane przed odbiorem na urządzeniu

**PR sam nie uruchamia usługi.** Usługę uruchomiono osobnym wdrożeniem po potwierdzeniu właściciela; pozostał opisany wyżej błąd klucza. Istniejący projekt serwera Next.js znajduje się w `ispina-rozklady/tablica-site`, hosting: `ispina-most-odjazdy`. Według dokumentacji repozytorium nie ma podłączonego automatycznego wdrażania z GitHub. Wdrożono serwer listy; aplikacji nie publikowano ani PR nie scalono.

Najprostsza konfiguracja do wykonania po decyzji właściciela:

1. W Google Cloud utworzyć projekt (lub użyć osobnego istniejącego projektu IL), włączyć **YouTube Data API v3**, utworzyć klucz i ograniczyć go do tego API. Publiczne dane nie wymagają logowania odbiorcy ani OAuth.
2. Zapisać wartość jako sekret serwera **`IL_YOUTUBE_API_KEY`** w ustawieniach hostingu tego projektu. Bez `NEXT_PUBLIC_`, bez wpisywania wartości do kodu, APK, opisu PR lub logów. W repozytorium jest wyłącznie pusty `.env.example`; lokalny `.env.local` jest ignorowany.
3. Wdrożyć sprawdzony kod endpointu na powyższy adres HTTPS. Nie wykonywać tego kroku bez zgody właściciela. Zweryfikować odpowiedź `schema:1`, `channel.id`, `uploadsPlaylistId`, aktualne `items` i `nextPageToken`.
4. Dopiero wtedy sprawdzić APK na urządzeniu. Bez sekretu endpoint świadomie zwraca **503 `not_configured`**, a aplikacja pokazuje czytelny stan niedostępności listy.

Klucz jest przekazywany do Google wyłącznie przez serwer w nagłówku `X-Goog-Api-Key`. Endpoint nie przyjmuje adresu upstream, kanału ani ID dowolnej playlisty. Przyjmuje tylko token strony; błędy/logi nie zawierają surowych odpowiedzi Google, URL-i żądań ani klucza.

CDN i pamięć procesu buforują stronę przez 60 s, informacje o playliście kanału przez godzinę. Odświeżenie pyta endpoint za każdym razem, ale świeżość może opóźnić się o około dwie minuty plus propagację danych w YouTube. Równoczesne wywołania strony w procesie są łączone. To nie jest rozproszony limit żądań: wiele instancji lub celowy ruch może zużyć limit API. Publiczne CORS nie jest kontrolą dostępu. W konsoli Google trzeba obserwować wykorzystanie limitu; przy większym ruchu zastosować limitowanie na hostingu lub wspólny cache. Nie dodano płatnej bazy ani nowej płatnej usługi.

`channels.list`, `playlistItems.list` i `videos.list` kosztują po 1 jednostce za wywołanie. Pobranie N stron to zwykle `1 + 2N` jednostek na zimnym cache, mniej przy trafieniach. Dokumentacja Google podaje domyślnie 10 000 jednostek dziennie łącznie dla tych metod; rzeczywisty limit projektu należy sprawdzić w konsoli. Większy limit może wymagać audytu. Hosting ma własne limity.

## Zachowanie aplikacji i ograniczenia YouTube

- Otwarcie, powrót do aplikacji i ponowne wczytanie odtwarzacza tylko przygotowują film (`autoplay=0`, `cueVideoById`). Dźwięk zaczyna się po działaniu użytkownika. Przejście po zakończeniu wymaga widocznego odtwarzacza; gdy użytkownik przewinął go poza ekran, następny film jest przygotowany bez dźwięku. WebView może wymagać dodatkowego dotknięcia; komunikat `onAutoplayBlocked` to wyjaśnia.
- Wyjście z Wideo, Wstecz, ukrycie dokumentu i opuszczenie strony usuwają iframe i unieważniają trwające pobranie. Spóźniona odpowiedź nie odtworzy ukrytego filmu. Odświeżanie listy zachowuje bieżący odtwarzacz i wybraną pozycję, jeśli nadal jest dostępna.
- Po pełnym poprawnym pobraniu zapisujemy metadane w localStorage na najwyżej **24 godziny**. Błąd odświeżenia zachowuje oznaczoną datą poprzednią listę; przerwane stronicowanie nie nadpisuje jej częścią wyników. Wygaśnięty/nieprawidłowy zapis jest usuwany. Pusta odpowiedź usuwa stare pozycje. Nie zapisujemy plików wideo; odtwarzanie wymaga internetu. Zapis może być niedostępny lub zapełniony — aplikacja nadal działa online.
- Lista obejmuje publiczne filmy zwrócone przez API dla tego kanału. Nie wykrywa prywatnych ani niepublicznych filmów. Wyłączone osadzanie, usunięcie filmu, ograniczenia wieku/regionu, prawa autorskie, transmisje i decyzje YouTube mogą uniemożliwić odtwarzanie. Przyczyna pozostaje opisana w aplikacji. Komunikaty obejmują błędy 100, 101, 150 i 153 oraz brak odpowiedzi.
- Nowy ekran używa składni ES5 i XMLHttpRequest, bez zależności od `fetch`, `URLSearchParams`, `AbortController` lub NodeList.forEach. Minimalny viewport odtwarzacza: 200×200. Zgodność aplikacji Android 6+ jest zachowana; sam YouTube może przestać obsługiwać starą wersję silnika WebView. Test Chromium nie jest testem fizycznego Androida 6.
- Oryginalna lokalna powłoka używa `loadDataWithBaseURL` z bazą `https://ispinalokalnie.wordpress.com/`. Zachowujemy ten origin i automatyczny HTTPS Referer, `referrerpolicy=strict-origin-when-cross-origin`; `widget_referrer` identyfikuje pakiet. Ten parametr analityczny nie zastępuje nagłówka Referer. Natywnego pochodzenia dokumentu nie zmieniamy w tym PR. Aktualne wymagania YouTube opisują także identyfikację aplikacji przez app ID; błąd 153 wymaga kontroli na rzeczywistym urządzeniu i ewentualnej zmiany natywnej integracji, nie podszywania się pod inną witrynę.

## Baza APK i zakres

Starsza ścieżka generatora odziedziczona z PR #6 przyjmuje `downloads/Ispina-Lokalnie-UltraSmart-77-Drogowskaz.apk`, SHA-256 `7c49ebf0c20563335148952f6c6e6da27028bd974a700216cb2d711028184a23`, pakiet `com.ispina.mldcheck.smartai44demo`, wersja `2.0.0-smart.77`, kod 20348. Wynik: `2.0.0-smart.79`, kod 20350. Inna baza i nadpisanie oryginału są odrzucane.

Zmieniamy manifest, `assets/main_ui.js`, `assets/il-build.json` i stałą HTML/CSP w `classes4.dex`; pozostałe bajty DEX, instrukcje i pliki APK pozostają identyczne. CSP dopuszcza wyłącznie `https://www.youtube-nocookie.com` dla ramek, `object-src 'none'` pozostaje. Zachowano porządkowanie Galerii z PR #6.

### Podpisana aktualizacja TEST opublikowanej wersji 78

Dodatkowy generator `patch_stable78_youtube.py` przyjmuje dokładny [publiczny APK stabilnej wersji 78](https://github.com/senikolo/Ispina-Lokalnie-/releases/download/v2.0.0-smart.78/Ispina-Lokalnie-UltraSmart-78.apk), SHA-256 `58c3ee07a21e85afe250f29252bdb79d94c33b263a1bbf33a55bf3f959562443`. Potwierdzono pakiet `com.ispina.mldcheck.smartai78test`, versionCode **20351**, Android od API 23 i certyfikat `3c7438761d2accca13da08defe4b62a814c3a3d2201c374f5c2e943115591d82`.

Wynik: **2.0.0-smart.79, kod 20352**, ten sam pakiet, etykieta **Ispina Lokalnie YouTube TEST**. Zmieniono tylko manifest i dwa pliki assets; DEX i zasoby pozostają identyczne ze stabilną 78. Stary moduł trzech odtwarzaczy jest usuwany przed dopisaniem nowego. Do podpisania użyto zachowanego klucza, po sprawdzeniu zgodności certyfikatu. Zweryfikowano podpisy v1/v2/v3, wyrównanie ZIP i integralność APK. Ten plik może aktualizować wskazaną stabilną 78 bez jej odinstalowania; nie aktualizuje oryginalnego pakietu 77.

Wcześniejszy podpisany `Ispina-Lokalnie-79-YouTube-TEST.apk` przekazano prywatnie właścicielowi; nie jest on działającym rozwiązaniem pełnego Wideo, ponieważ usługa nie została uruchomiona. Rozmiar: 7 981 056 bajtów, SHA-256 `8977c73696ad2db35c9325400437f1f47b8bd9bebcd037aa8c63f1aaaf364986`. Klucz i hasło nie zostały dodane do repozytorium. **To wersja testowa: lista nadal wymaga konfiguracji klucza Data API i wdrożenia endpointu.** W aplikacji jest widoczna ta informacja. Nie twierdzimy, że potwierdzono streaming lub instalację na fizycznym Androidzie.

```sh
python3 tools/ultrasmart78-video-tab/test_stable78_update.py /path/to/stable78.apk
python3 tools/ultrasmart78-video-tab/patch_stable78_youtube.py /path/to/stable78.apk /tmp/youtube79-unsigned.apk
```

Sam generator nadal wytwarza UNSIGNED i nie zawiera klucza. Nie dodano workflow podpisującego ani publikującego; CI pobiera dokładną publiczną bazę 78 i sprawdza dodatkowo tę ścieżkę oraz jej pełny interfejs. Nie łączono PR #6 ani #7.


### Natywna obsługa osadzonego odtwarzacza w kandydacie 80

W `HomeActivity$3` oba warianty `shouldOverrideUrlLoading` wcześniej kierowały wszystkie adresy do `handleUri`, a ten otwierał adresy spoza WordPressa zewnętrznie. Dotyczyło to także nawigacji podramek. Poprawka `classes3.dex` dodaje dwa warunki przed dotychczasową obsługą:

- Android 7+: żądanie z `isForMainFrame() == false` zwraca `false`, pozostawiając podramkę w WebView.
- Starszy callback, używany na Androidzie 6: adres z dokładnym prefiksem `https://www.youtube-nocookie.com/embed/` zwraca `false`. Pozostałe adresy przechodzą do dotychczasowej obsługi.

Nowe lokalne rejestry nie nadpisują parametrów callbacków. Pozostałe klasy i metody są porównywane po deasemblacji przebudowanego pliku; oryginalny ciąg instrukcji w obu zmienionych metodach pozostaje zachowany za warunkiem. Budowanie używa API 23 i przypiętych sumami SHA-256 narzędzi smali/baksmali 2.5.2. Narzędzia są pobierane z Maven Central i nie trafiają do APK.

```sh
python3 tools/ultrasmart78-video-tab/prepare_smali_tools.py /tmp/il-smali
export IL_SMALI_TOOLS=/tmp/il-smali
python3 tools/ultrasmart78-video-tab/test_stable78_update.py /path/to/stable78.apk
```

W aktualizacji 80 zmieniają się manifest, `classes3.dex` i dwa pliki assets. Nie przebudowujemy innych plików DEX ani zasobów. To nie jest jeszcze potwierdzenie rzeczywistego streamingu na urządzeniu — jego próba czeka na poprawną odpowiedź listy.

## Sprawdzenie

```sh
python3 tools/ultrasmart78-video-tab/test_patch.py
python3 tools/ultrasmart78-video-tab/test_youtube_service.py
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
6. Sprawdzić nagranie z wyłączonym osadzaniem, błąd 153, obrót ekranu i Tryb Senior. Aplikacja nie przekierowuje do zewnętrznej strony w celu odtwarzania.

Nie wykonano testu prawdziwej listy przez Data API (brak skonfigurowanego klucza), rzeczywistego odtwarzania ani instalacji na fizycznym Androidzie. Podpisano i sprawdzono opisany powyżej APK TEST. To pozostaje warunkiem odbioru, nie deklarowanym wynikiem testów.

## Oficjalna dokumentacja

- [Pobieranie filmów kanału](https://developers.google.com/youtube/data/guides/implementation/videos#retrieve_a_channels_uploaded_videos)
- [channels.list / forHandle](https://developers.google.com/youtube/data/reference/rest/v3/channels/list)
- [playlistItems.list i nextPageToken](https://developers.google.com/youtube/data/reference/rest/v3/playlistItems/list)
- [videos.list](https://developers.google.com/youtube/data/reference/rest/v3/videos/list)
- [IFrame Player API](https://developers.google.com/youtube/iframe_api_reference)
- [Wymagania WebView, Referer i rozmiaru odtwarzacza](https://developers.google.com/youtube/terms/required-minimum-functionality)
- [Limity YouTube API](https://developers.google.com/youtube/data/guides/quota_and_compliance_audits)
- [Klucz API w nagłówku](https://docs.cloud.google.com/docs/authentication/api-keys-use)
