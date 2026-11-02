# Radar IL: naprawa pustych wyników i podgląd źródeł na żywo

**Wersja testowa: 2.0.0-professional-test.3 (47). PR #27.** Zmiany na gałęzi `fix/il-radar-live-recall-20261102`, bez scalania do main.

## Co potwierdziło zgłoszenie właściciela

Wcześniejsze testy poprawnie sprawdzały działanie dat i interfejsu, ale nie zapewniały skutecznego odnajdywania rzeczywistych publikacji. Było to niewystarczające kryterium gotowości wyszukiwarki.

Ponownie pobrano publiczne kanały i strony. Dla 90 dni od 2 listopada 2026:

| Odczyt tych samych źródeł | Stary sposób | Poprawiony sposób |
|---|---|---|
| Google News | `Ispina Drwinia`: 12 pozycji, żadna w okresie | `Ispina`: 21 pozycji, w tym materiał o konserwacji mostu z 16.09.2026 |
| Eko-Drwinia RSS | Skrócona zajawka nie zawierała Ispiny; pełne `content:encoded` pomijano | Pełna treść zawiera Ispinę; publikacja ma datę 12.10.2026 |
| Gmina Drwinia | Indeks HTML pomijano w skanie datowanym; tytuł bez Ispiny uniemożliwiał dopasowanie | Odczyt listy, wyszukiwarki i właściwego artykułu: wzmianka o Ispinie w treści i data 9.10.2026 |
| Bochnia ON | Brak osobnego kanału wydawcy | Publiczny kanał wyszukania zwrócił trzy wpisy, w tym publikacje z 9.10 i 16.09.2026 mieszczące się w 90 dniach |
| Bing RSS | Serwer zwrócił poprawny technicznie XML z zerową liczbą wpisów | Pusty kanał jest oznaczany jako nierozstrzygający, a nie jako potwierdzenie braku wiadomości |

Są to pomiary konkretnych kanałów, nie pełny pomiar całego internetu ani odczyt telefonu właściciela.

Przykłady odczytane w oryginalnych źródłach:

- Gmina Drwinia: https://www.drwinia.pl/aktualnosci/mistrzowie-agro-2026,news-3296 — data przy artykule 9 października 2026; Ispina występuje w treści, nie w tytule.
- Eko-Drwinia: https://eko.drwinia.pl/ponad-35-mln-pln-wsparcia-finansowego-na-realizacje-inwestycji-wodno-kanalizacyjnych-w-ramach-pozyczek-malopolska-woda/ — publikacja 12 października 2026; data 23 września w treści dotyczy zdarzenia.

Adresy tych konkretnych artykułów **nie są wpisane do produkcyjnego silnika jako wyniki**. Silnik odkrywa publikacje z kanałów, wyszukiwarki i list wydawców. Powyższe adresy służą opisaniu i sprawdzeniu zgłoszonego błędu.

## Naprawiony silnik

1. Ogólne zapytanie nie wymaga już, aby materiał zawierał równocześnie słowa Ispina i Drwinia. Zachowano zapytania tematyczne i kontekst miejscowości.
2. Parser czyta pełną treść RSS, w tym `content:encoded` i treść z przestrzeniami nazw XML. Dopasowanie odbywa się przed skróceniem opisu. Czyta również mieszane elementy tekstu, a nie tylko prostą zajawkę. Dopasowanie obejmuje także poprawny przymiotnik „ispiński” z polskim „ń” i jego formy, np. „ispińskiej”; dodano regresję pełnej treści RSS.
3. Dodano bezpośrednie kanały wydawców regionalnych (Bochnianin, Bochnia z Bliska, Informator Brzeski i Bochnia ON) oraz publiczny odczyt wpisów Ispina Lokalnie. Materiały własnego portalu są oznaczane jako **Twój portal**, więc nie są przedstawiane jako niezależne potwierdzenie. Wyniki zewnętrzne mają pierwszeństwo; archiwum własnego portalu jest w osobnej, początkowo zwiniętej grupie. Podsumowanie osobno liczy obie grupy, zamiast przykrywać wiadomości zewnętrzne własnymi wpisami.
4. Odczyt gminy obejmuje wyszukiwarkę, dalszą stronę jej wyników, najnowszą listę artykułów i treści artykułów. Parser oddziela treść od stopki i nawigacji; nie bierze dzisiejszej daty z widgetu jako daty publikacji.
5. Daty są odczytywane m.in. z `datePublished`, metadanych artykułu, elementów `time.published` oraz właściwego pola daty gminy. Data aktualizacji bez daty publikacji nie jest automatycznie zamieniana na nową publikację.
6. Linki bez ustalonej daty nie są po cichu wyrzucane: mogą pojawić się w osobnej grupie **Materiały do ustalenia daty**. Nie są zaliczane do nowych publikacji z wybranego okresu.
7. Serwer HTTP 200 z pustym kanałem nie dowodzi braku materiałów. Wynik pusty albo niepełny otrzymuje stan **nierozstrzygający** wraz z liczbami odczytanych pozycji i ograniczeniami źródeł.
8. Obsługiwane są ograniczone przekierowania HTTPS, bez przesyłania haseł, cookies ani tokenów do wydawców i bez wyłączania kontroli TLS. Rzeczywisty serwer gminy zwraca dla `robots.txt` przekierowanie 302 na `http://www.drwinia.pl/`. Pierwotnie kontrola HTTPS zatrzymywała cały adapter. Aplikacja odczytuje teraz wyłącznie sprawdzony odpowiednik HTTPS tego adresu w tej samej domenie. Nie wysyła żądania HTTP; inne nieszyfrowane przekierowania nadal są odrzucane. Regresję uruchamia instrumentacja na wszystkich czterech wersjach Androida.
9. Pełne teksty służą dopasowaniu podczas skanu i są usuwane z odpowiedzi przekazywanej do interfejsu/pamięci. Zapis metadanych nie powoduje wysyłania do SQLite całych artykułów. Zbyt duży zapis pamięci zgłasza błąd zamiast milcząco czekać na timeout.

## Podgląd na żywo — dodatkowa uwaga właściciela

Na ekranie pytań i w szerokim Radarze jest dziennik **Na żywo — sprawdzane strony**. Pokazuje:

- nazwę i rzeczywisty adres odczytywanego źródła;
- rozróżnienie: bezpośredni kanał wydawcy, strona artykułu, wyszukiwarka wydawcy, indeks wyszukiwarki albo API własnego portalu;
- etap: czytam, odczytano treść, zakończono, błąd, pusty kanał lub pominięcie;
- liczbę dopasowań i szczegóły błędu, np. HTTP 403;
- podsumowanie liczby adresów i zakończonych odczytów.

Zdarzenia pochodzą z rzeczywistego odczytu Androida. Nie jest to animacja losowych nazw stron. Dziennik sam otwiera się i jednorazowo przewija do widoku przy pierwszym rzeczywistym zdarzeniu; dalsze zdarzenia nie odbierają użytkownikowi kontroli nad przewijaniem. Aktualnie czytane adresy są u góry. Nagłówek dziennika jest odsunięty od nakładanego paska trwającej operacji. Dziennik zostaje po zakończeniu. Zdarzenia starego lub anulowanego żądania nie dopisują się do nowego skanu. Skrócono też objaśnienia nad wyszukiwaniem, aby dziennik znajdował się bliżej przycisków.

Przycisk tematu nie nadpisuje pytania, jeżeli poprzedni skan jeszcze trwa. Nowy tekst wpisany w czasie skanu nie jest kasowany po nadejściu odpowiedzi poprzedniego zapytania.

## Testy i dowody

Wzmocniony test live w przebiegu [41400889456](https://github.com/senikolo/Ispina-Lokalnie-/actions/runs/41400889456) rzeczywiście zakończył się **FAIL**: dwie publikacje zewnętrzne i zero z oryginalnego HTML. Zapis zdarzeń wskazał zatrzymanie na przekierowaniu `robots.txt`, nie brak artykułów. Widoczność dziennika na ekranie przeszła w tym przebiegu. Ten wynik nie jest zaliczany jako sukces; po poprawce wykonano przebieg końcowy opisany poniżej.

W przebiegu [41402510874](https://github.com/senikolo/Ispina-Lokalnie-/actions/runs/41402510874) przeszły kompilacja i Android 8/10/13. API30 zachował dane przy aktualizacji, a w ponowieniu zaliczył też testy interfejsu i edytora; później emulator przestał być dostępny (`device offline`, brak procesu na porcie emulatora). Próby live nie uznano za wykonaną ani zaliczoną. Rozdzielono ją od testów aktualizacji: ma oddzielny świeży emulator Android 11, aktualny programowy tryb renderowania `-gpu software` i zapis logcat/diagnostyki hosta. Przyczyna zniknięcia poprzedniego emulatora pozostaje niepotwierdzona.

- 198/198 lokalnych testów JS — PASS.
- 12/12 testów zaplecza — PASS; TypeScript i transpilacja WebView — PASS.
- Parser Java uruchomiony na faktycznie pobranej stronie artykułu gminy, jej liście i publicznym JSON WordPressa — poprawnie wydobył artykuł, datę i etykietę własnego portalu. To test rzeczywistych odpowiedzi, nie tylko syntetycznego przykładu.
- Nowe testy instrumentacyjne sprawdzają pełną treść RSS, dopasowanie w treści artykułu, pomijanie Ispiny ze stopki, właściwą datę publikacji i pokazanie postępu w rzeczywistym WebView.
- Dodano oddzielny test **live-recall na Androidzie 11/API30** przez rzeczywisty most aplikacji. Wykonuje normalne pytanie o nowości z 90 dni. Musi znaleźć przynajmniej trzy datowane publikacje zewnętrzne, w tym materiał odczytany z oryginalnego HTML wydawcy, i otrzymać zdarzenia postępu. Sprawdza także faktyczną widoczność adresów na ekranie podczas trwającego odczytu. Wynik, raport kanałów i zdarzenia postępu zapisuje jako metadane w `real-source-recall.json`, bez pełnych tekstów artykułów. Wewnętrzne zrzuty ekranu powstają podczas skanu i po jego zakończeniu. Gdy odzysk jest zbyt słaby, test nie przechodzi.

CI końcowe: https://github.com/senikolo/Ispina-Lokalnie-/actions/runs/41406019996

Kod końcowy: `a1f37bf7104e96e3ccc260f75e7cf41b3efde4e3`. Wszystkie sześć zadań CI zakończyło się sukcesem.

| Sprawdzenie końcowe | Wynik |
|---|---|
| Kompilacja APK, tożsamość pakietu, wersja 47, podpis testowy | PASS |
| JavaScript interfejsu — 198/198 | PASS |
| Zaplecze — 12/12, TypeScript i transpilacja WebView | PASS |
| Android 8 / API26 — aktualizacja, zachowanie danych, instrumentacja i UI | PASS |
| Android 10 / API29 — aktualizacja, zachowanie danych, instrumentacja i UI | PASS |
| Android 11 / API30 — aktualizacja, zachowanie danych, instrumentacja i UI | PASS |
| Android 13 / API33 — aktualizacja, zachowanie danych, instrumentacja i UI | PASS |
| Android 11 — oddzielny rzeczywisty skan publicznych wydawców i widoczność postępu | PASS |

Ostatni skan 90 dni przez normalny interfejs i most natywny zwrócił **5 datowanych publikacji zewnętrznych oraz osobno 26 wpisów własnego portalu**. Jedną publikację odczytano z oryginalnego HTML gminy. Dziennik otrzymał **139 zdarzeń dla 56 adresów**, w tym jawne pominięcia; nie jest to deklaracja przeczytania 56 artykułów. Adapter gminy odczytał 24 artykuły z 47 odkrytych adresów. W wybranym okresie dopasował artykuł o plebiscycie z 9.10.2026. Pozostałe wyniki zewnętrzne: Eko-Drwinia 12.10, Bochnia ON 9.10 i 16.09 oraz materiał o moście z indeksu Google News 16.09. Daty i treść wymagają weryfikacji redaktora.

Metadane wyników, pokrycie źródeł i cały strumień postępu: [rzeczywisty dowód JSON](qa/IL-Radar-live-recall-2026-11-02.json). Nie zawiera pełnych tekstów artykułów ani danych logowania. Sprawdzono wewnętrzne zrzuty `radar-scanning.png` i `radar-live.png` z tego samego testu; adresy są widoczne podczas pracy, a dziennik pozostaje po zakończeniu.

Końcowe testy używają wcześniejszego profilu SwiftShader dla API26/29/33, profilu SwANGLE dla API30 i osobnego świeżego emulatora programowego dla skanu live. Instalator CI używa `--no-streaming -r`; aktualizacja nadal zastępuje pakiet bez odinstalowywania i bez kasowania danych. Wcześniejsze przerwane próby nie są zaliczone do wyników PASS. Testy na urządzeniu właściciela pozostają niewykonane.


## APK i bezpieczna aktualizacja

- Plik testowy: `IL-Redaktor-Smart-2.0.0-Professional-TEST-3.apk` (3 223 219 bajtów).
- SHA-256 pliku: `bd6bdca17c3476ec0da966555271f7c935d9f8b53a875a5696064bfa9f59830c`.
- Lokalnie uruchomiono `apksigner verify --verbose --print-certs`: PASS, podpis v2.
- Certyfikat debug SHA-256: `8b8a1e9f224f648077af8ebdb00b9c8f92460d1c0afb3c01361d42292a0a02a5`.
- To wersja **TEST**, nie wydanie stabilne. Podpis jest inny niż w wcześniej udostępnionych APK testowych. Nie jest potwierdzoną aktualizacją instalacji właściciela.
- Nie odinstalowywać dotychczasowej aplikacji ani nie kasować jej danych. Najpierw wyeksportować szkice i pamięć. Bez zgodnego klucza prywatnego testować na osobnym urządzeniu lub emulatorze. Aktualizacja istniejącej instalacji wymaga zbudowania i zweryfikowania APK tym samym kluczem.
- Workflow przygotowuje również niepodpisany release i opcjonalnie podpisuje go istniejącym kluczem z sekretów repozytorium. Sprawdza zgodność odcisku z `release-certificate.sha256`; nie tworzy nowego klucza jako zamiennika starego.

Instrukcja dla redaktora: [IL-Redaktor-Professional-instrukcja-pl.md](IL-Redaktor-Professional-instrukcja-pl.md).

Testy Androida wykonano na emulatorach. Nie wykonano testów na fizycznym telefonie właściciela ani rzeczywistego logowania i zapisu na jego koncie WordPress. Testy aktualizacji używają tej samej pary kluczy CI dla obu APK, co nie dowodzi zgodności z podpisem już zainstalowanej aplikacji.

Nie wykonano zapisów ani publikacji w WordPressie. Wyszukiwanie używa publicznych odczytów; testy nie wymagają logowania właściciela. Podczas prac nie wywoływano operacji zapisu ani publikacji w WordPressie.

## Ograniczenia, których nadal nie należy ukrywać

- Nie jest to pełny przegląd wszystkich stron, BIP i PDF. Nie dodano OCR. Kanały regionalne mogą zawierać starsze wpisy; są filtrowane według dat, a nie przedstawiane jako nowe.
- Skan gminy ma limit do 24 artykułów, dwóch równoległych odczytów treści i do 25 sekund na te odczyty, w ramach 39 sekund dla całego adaptera. Działa obok pozostałych profili; nie przegląda każdej podstrony gminy. Dziennik i raport źródeł pokazują zakres faktycznego odczytu.
- Niektóre wyniki Google News prowadzą przez odnośnik Google, a nie bezpośrednio do adresu artykułu. Bezpośrednie odczyty wydawców mają adresy oryginałów.
- Deduplikacja usuwa powtarzające się adresy i parametry śledzące. Nie scala wszystkich przedruków w jedno wydarzenie; dwie publikacje o tym samym plebiscycie mogą pozostać osobnymi źródłami. Liczba publikacji nie oznacza liczby niezależnie potwierdzonych wydarzeń.
- Data podana przez indeks nadal wymaga sprawdzenia w oryginale. Parser wydawcy i kanał RSS potwierdzają metadane, nie prawdziwość wszystkich twierdzeń w artykule.
- Wynik z własnego portalu jest użyteczny jako archiwum i powiązany wpis, ale nie stanowi niezależnego zewnętrznego źródła.
- Live-recall jest testem chwilowej dostępności konkretnych źródeł i odnajdywania materiałów. Nie gwarantuje identycznej liczby wyników zawsze ani na każdej sieci telefonicznej.
- Testowy APK może mieć inny podpis niż zainstalowana wersja. Nie usuwać działającej aplikacji ani jej danych, aby obejść odmowę instalacji. Zgodna aktualizacja wymaga właściwego klucza; bez niego najbezpieczniejszy test to osobne urządzenie lub emulator.
