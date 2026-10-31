# Ispina Most — tablica odjazdów

Publiczna tablica: **https://ispina-most-odjazdy.vercel.app/**. Hasło: „Łączy nas Ispina”. Migracja z Sites: 23 października 2026. Starego wdrożenia nie usunięto.

## Hosting i niezależność

Next.js 16 / React 19, Vercel Hobby, region Frankfurt, publiczny HTTPS bez logowania. Konto ma aktywny Hobby, bez triala. Bez bazy danych, płatnych API, AI i sekretów. W granicach planu koszt to 0 zł/miesiąc; przekroczenie limitów Hobby może wstrzymać usługę. Nie włączono płatności ani Pro. Domena `vercel.app` jest wystarczająca; dotychczasowa domena `.com` ma odrębne ewentualne opłaty.

Dane nie zależą od ChatGPT Plus. Wymagają utrzymania darmowych kont GitHub i Vercel oraz dostępności oficjalnych źródeł. Nie należy obiecywać niezmienności regulaminów darmowych usług.

Projekt Vercela: `ispina-most-odjazdy`, ID `prj_5ACAO0boV6unNVu9tU9V4BZ4QqVg`. Kod wdrożono bezpośrednio przez API. Automatyczne **dane** działają niezależnie od wdrażania **kodu**. Git integration nie została połączona: Vercel zgłosił brak GitHub Login Connection. Zmiany interfejsu wymagają osobnego wdrożenia lub późniejszego połączenia repozytorium z Vercel. Nie zakładaj, że sam push aktualizuje widok.

Oceniono Cloudflare Workers Free (alternatywa wymagająca adaptacji Next i dostępu do konta) oraz GitHub Pages (sama platforma nie uruchamia tego API). Wybrano Hobby, ponieważ obsługuje istniejący interfejs i serwer pobierający GTFS RT bez zmiany stosu. Ten projekt społeczny jest niekomercyjny.

## Źródła i zabezpieczenia

- GitHub `ispina-rozklady/out/tablica.json`: zweryfikowane godziny na 7 dni, generowane sześć razy dziennie przez istniejący GitHub Actions.
- Oficjalny operacyjny GTFS KMŁ i sprawdzone PDF. A7 zachowuje zaakceptowaną różnicę minuty między PDF a GTFS; danych nie zmieniano na potrzeby wyglądu.
- Oficjalny GTFS Realtime: zgodność kursu, route_id, dnia, przystanku i kolejności. NO_DATA zatrzymuje propagację. Odwołania i pominięcia mają jawne oznaczenia. Prognozy starsze niż 180 sekund są odrzucane.
- Gminna: wyłącznie orientacyjne godziny Mostu według Domu Ludowego, oznaczone „ok.”; tolerancja około 2 minut. Brak LIVE i kursów w niedziele/święta.
- Awaria LIVE: godziny planowe. Awaria API: niezależny plik GitHub, po kontroli świeżości. Awaria obu: usunięcie danych i czytelny komunikat; żadne wcześniejsze LIVE nie pozostaje na ekranie.
- Dane starsze niż 30 godzin oraz zmieniony PDF lub wygasły audyt blokują odpowiednie godziny. **Audyt kończy się 30 listopada 2026**; wymaga ponownej kontroli źródeł. Automatyczne pobieranie nie zastępuje audytu.

## Ograniczanie zapytań

Klient odświeża co 30 sekund i wstrzymuje odpytywanie w ukrytej karcie. API buforowane w CDN 20 sekund bez `stale-while-revalidate`; pamięć procesu: rozkład 60 sekund, RT 20 sekund, błędy 10 sekund. Współbieżne żądania korzystają z jednego pobrania. Klient uwzględnia HTTP Age/Date, więc cache nie przedłuża ważności LIVE. Odpowiedź zawiera tylko potrzebne pola, pobrania mają limity czasu/rozmiaru. Nie wdrożono płatnego magazynu ani rozproszonego licznika. CDN nie zwalnia z limitu żądań całego konta.

## Uruchomienie i testy

```sh
npm ci --ignore-scripts
npm test
npm run build
npm start
```

20 testów Node obejmuje RT, cache, łączenie równoczesnych pobrań oraz rzeczywisty komponent React w jsdom: obie awarie, fallback, wygasłe prognozy i rozkład oraz wiek CDN. Osobno uruchomiono 19 testów Python generatora. Przeglądarka: filtry A4/A7/A65/gminna w piątek/sobotę/niedzielę, oba kierunki, zakres dnia, właściwe logo i 5 szerokości w ramkach testowych. To nie jest test fizycznego Androida.

WordPress: wymieniono tylko adres przycisku w sekcji `automatyczne-rozklady` strony 385, z kontrolą wersji treści. Brak JavaScript/iframe/wtyczek. Zachowano grafiki, KiedyPrzyjedzie i pełny rozkład gminny.

## Android

Obie oryginalne APK pobrano z Releases i sprawdzono ich SHA-256. Zbadano JS i adresy w DEX; brak ChatGPT Sites/OpenAI. Pomocnik UltraSmart interpretuje pytania lokalnie. Usługi online (rozkłady, pogoda, WordPress, media, rozpoznawanie mowy) mają odrębne zależności internetowe. Nie potwierdzono pełnego działania natywnego na urządzeniach, wszystkich streamów ani lektora online. Live 1.83.20 zawiera dodatkowy serwer TTS `ahm7xmakki.com`, którego dostępność i koszty po stronie operatora nie są gwarantowane; obecność alternatywnego TTS nie dowodzi działania każdego głosu.

Nie zmieniono APK ani podpisów. Brakuje pełnego projektu Java/Kotlin i właściwych prywatnych kluczy. Bezpieczny dostęp do nowej tablicy: przeglądarka lub przycisk na stronie WordPress; nie twierdzimy, że zmieniło to wbudowany moduł odjazdów/planera. Stary WebView może wymagać aktualizacji lub otwarcia w aktualnej przeglądarce.

## Lista filmów dla Androida — wdrożona usługa, nieprawidłowy klucz

Aktualizacja 28.10.2026: endpoint wdrożono na istniejący hosting po potwierdzeniu właściciela. Sekret `IL_YOUTUBE_API_KEY` istnieje w Production, ale Google zwraca `API_KEY_INVALID` (HTTP 400); klient otrzymuje 503. Wartości klucza nie odczytywano. Dalszy odbiór czeka na podmianę nieprawidłowej wartości i ponowne wdrożenie z aktualnym sekretem.

`GET /api/youtube-videos` jest osobnym, tylko do odczytu endpointem dla kanału `UCeeKoKHiH-rGyQOZn6wW-7A`. Wymaga **serwerowego** sekretu `IL_YOUTUBE_API_KEY` (pusty przykład w `.env.example`); bez niego zwraca 503 `not_configured`. To nowa opcjonalna zależność od Google Cloud / YouTube Data API, niezależna od istniejących odjazdów i pogody. Dotychczasowy opis hostingu „bez sekretów” odnosi się do tych istniejących funkcji. Sam PR nie wdraża nowego kodu.

Konfiguracja, źródło kanału, limity, pełne stronicowanie, cache i scenariusze odbioru: [Wideo Android](../../tools/ultrasmart78-video-tab/README.md). Endpoint przyjmuje tylko `pageToken`; identyfikator playlisty przesłanych filmów odczytuje z oficjalnego `channels.list`. Odpowiedzi są buforowane 60 s, a klucz ani surowe błędy Google nie trafiają do klienta. Nie dodano płatnej usługi ani automatycznego wdrożenia.
