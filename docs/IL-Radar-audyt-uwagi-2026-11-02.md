# Audyt Radaru po uwagach właściciela — 2 listopada 2026

Wersja po poprawkach: **2.0.0-professional-test.2 (46)**. Baza audytu: ostatni kod PR #25, head `07f41fab610eb1857abc3a501f7ee6bec1db25f6`. Zmiany: nowa gałąź `fix/il-radar-freshness-20261102`, PR #26. Nie scalano do main i nie zmieniano treści portalu.

## Przyjęte znaczenie pytania

„Czy pojawiło się coś o OSP / moście?” oznacza: **sprawdź internet i pokaż kandydatów na nowe publikacje w wybranym okresie**, domyślnie ostatnie 30 dni. Dostępne okresy to 7, 30 i 90 dni. Podany wprost okres w pytaniu ma pierwszeństwo przed selektorem. Przykład: przy ustawionych 30 dniach pytanie „A z ostatnich 90 dni?” uruchamia zakres 90 dni.

„Nowe w wybranym okresie” i „pierwszy raz znalezione przez aplikację” to dwie różne rzeczy. Artykuł opublikowany kilka dni temu może być już znany pamięci, lecz nadal należy do wybranego okna publikacji. Samo odnalezienie starego dokumentu dzisiaj nie czyni go nową wiadomością.

## Ustalenia i poprawki

| Waga | Potwierdzony problem przed poprawką | Zmiana |
|---|---|---|
| Wysoka | Selektor 7/30/90 dni działał tylko dla szerokiego Radaru. Pytania i przyciski Most/OSP korzystały z `since=0, datedOnly=false`. | Nowy most `askIspinaChatWithDays`; filtr dat po stronie Androida, domyślne 30 dni i rozpoznawanie okresu w pytaniu. |
| Wysoka | Zwykłe pytanie bez kilku rozpoznanych słów mogło zatrzymać się na starej odpowiedzi z SQLite, bez sprawdzenia internetu. | Pamięć jest używana wyłącznie przy jawnym pytaniu o pamięć/archiwum/wcześniejsze materiały. Zwykłe pytanie zawsze sprawdza bieżące publiczne kanały. |
| Wysoka | Powtórne pytanie mogło korzystać z pięciominutowej kopii wyników bez filtra dat. | Wyszukiwanie nowych wiadomości zawsze używa datowanego skanu, omijającego tę kopię. |
| Średnia | Przy zmianie protokołu stara APK mogłaby po cichu uruchomić wyszukiwanie bez dat. | Interfejs odmawia takiego przejścia i podaje komunikat o wymaganej aktualizacji. |
| Średnia | Szerokie „co nowego” mogło odziedziczyć poprzedni temat, np. most, albo zawęzić się do jednej kategorii z instrukcji. | Rozpoznanie szerokiej intencji ma pierwszeństwo; „Cokolwiek nowego o Ispinie” nie dziedziczy poprzedniego wąskiego tematu. |
| Średnia | Przycisk „Najnowsze materiały” zawierał stałe „30 dni”, niezależne od wyboru użytkownika. | Przycisk **Cokolwiek nowego o Ispinie** korzysta z bieżącego wyboru 7/30/90. |
| Średnia | Nazwa „Transport” była szersza niż oczekiwana funkcja. | Przycisk **Autobusy**; dopasowanie autobusów obejmuje też A4, A7, A65, MLD i linię gminną. |
| Średnia | Te same ogólne zapytania dla wszystkich tematów ograniczały trafność. | Dodatkowe, priorytetowe profile: OSP/nazwa jednostki/PSP, most/remont/DW775/DW964/ZDW, autobusy/MLD/linia gminna/przewoźnik; szeroki wariant dla informacji dowolnego rodzaju. |
| Średnia | Sekwencyjne pobieranie i dwa indeksy HTML na początku mogły zużyć czas przed sprawdzeniem mediów czy komunikatów tematycznych. | Trzy ograniczone czasowo połączenia, priorytet zapytań ogólnych i tematycznych. Indeksy bez dat nie zużywają pobierania w skanie nowych wiadomości; ograniczenie pozostaje jawne. |
| Średnia | Pojedynczy wskaźnik połączenia nie wystarczałby do anulowania równoległego skanu. | Kontroler śledzi wszystkie aktywne połączenia, anuluje je i odrzuca dalsze dołączanie po zamknięciu skanu. |
| Średnia | Parser dopuszczał datę publikacji do 24 godzin w przyszłość. | W datowanym skanie data późniejsza niż bieżący czas nie jest traktowana jako publikacja z ostatnich dni. |
| Średnia | W odpowiedzi krótkiego wyszukiwania brakowało zakresu sprawdzonych kanałów. | Data przy każdym linku, wybrany okres w statusie i rozwijana lista kanałów także w odpowiedzi czatu, również przy zerowej liczbie wyników. |
| Niska | Odtworzone odpowiedzi z historii można było pomylić z nowym sprawdzeniem. | Oznaczenie **Archiwalna odpowiedź z pamięci — nie nowe wyszukiwanie**. |

## Co oznacza tutaj więcej inteligencji

- Rozpoznanie, że zwykłe pytanie o lokalny temat dotyczy nowych wiadomości.
- Zachowanie wybranego okna czasu i zmiana tego okna na podstawie jawnej informacji w pytaniu.
- Dziedziczenie konkretnego tematu przy krótkim dopowiedzeniu, lecz porzucenie go przy pytaniu o dowolne nowości.
- Zapytania dopasowane do OSP, mostu i autobusów zamiast jednej wspólnej frazy.
- Rozróżnienie nowego skanu, archiwalnej pamięci i źródeł do sprawdzenia.
- Przycisk **Szukaj głębiej w ChatGPT Free** przygotowuje istniejące szczegółowe polecenie wraz z tematem i okresem. To ręczny przepływ przez ChatGPT, bez automatycznego płatnego API i bez automatycznego publikowania.

To funkcje wyszukiwania, rozpoznawania intencji i reguł kontekstowych. Nie dodano nowego lokalnego modelu językowego ani nie potwierdzono automatycznej integracji modelu przez konto ChatGPT Free.

## Testy

Lokalnie uruchomiono **192/192 testy JS — PASS**, transpilację WebView oraz TypeScript. Nowe regresje sprawdzają pytania o OSP i most dla każdego z zakresów 7/30/90, brak podstawiania starych wyników, pierwszeństwo okresu z pytania, zapis selektora, odmowę starego mostu bez dat, jawne użycie archiwum oraz daty i błędy kanałów przy linkach.

Dodano rzeczywiste testy instrumentacyjne Androida: datowane wpisy sprzed 3/15/60/120 dni, odrzucanie braku daty i przyszłej daty, rozpoznawanie OSP/mostu/autobusów, szerokie pytanie po wcześniejszym moście i odłączenie dwóch równoległych połączeń po anulowaniu. Fixture nie jest wysyłany do WordPressa ani do wyszukiwarek.

Końcowy przebieg CI: https://github.com/senikolo/Ispina-Lokalnie-/actions/runs/41393960585

| Test końcowy | Wynik |
|---|---|
| Kompilacja APK, testy JS/zaplecza i kontrole źródeł | PASS |
| Android 8 / API 26 — aktualizacja, dane i instrumentacja | PASS |
| Android 10 / API 29 — aktualizacja, dane i instrumentacja | PASS |
| Android 11 / API 30 — aktualizacja, dane i instrumentacja | PASS |
| Android 13 / API 33 — aktualizacja, dane i instrumentacja | PASS |

Wszystkie testy instrumentacyjne uruchomiono. Testy aktualizacji używały tego samego klucza CI dla wersji bazowej 1.29.4 i nowej APK; nie potwierdza to zgodności z kluczem aplikacji zainstalowanej na telefonie użytkownika.

Końcowy APK: `IL-Redaktor-Smart-2.0.0-Professional-TEST-2.apk`. Plik ma SHA-256 `7adf3cb0aa4962cddbb7449af13f0fed870059f90140dac548342d577eacd228`. Lokalna weryfikacja apksigner potwierdziła poprawny podpis v2; certyfikat debug SHA-256 `d205192d388982cfb1c8390977676c1f757ab5cc60fd39b795cdc2fdb5a89184`. Różni się od wcześniej dostarczonych APK; nie jest zgodną aktualizacją ich instalacji.

Nie przeprowadzono testów na fizycznym WP15 ani na koncie WordPress właściciela. Testy parsera i kontekstu nie dowodzą kompletności otwartego internetu.

## Pozostałe ograniczenia i zalecenia

1. **Daty z kanału wymagają sprawdzenia w oryginale.** Filtr stosuje datę deklarowaną jako publikacja przez RSS/Atom, w tym kanał wyszukiwarki. Nie jest to niezależne potwierdzenie daty na stronie wydawcy. Znany dokument może zostać ponownie zindeksowany z nową metadaną; sam Radar nie rozstrzyga, czy zmieniła się treść dokumentu.
2. **Skan nie jest kompletnym przeglądem wszystkich stron.** Indeksy i zapytania mogą nie obejmować wpisu, w którym Ispina występuje tylko w PDF. Nadal brak pełnej ekstrakcji PDF, OCR i przeglądania każdego załącznika BIP. Przy rygorystycznym filtrze czasu link bez daty nie trafia na listę nowych wiadomości.
3. **Brak wyniku oznacza brak wyniku w odczytanych kanałach.** Nie oznacza „na pewno nic nie opublikowano”. Raport pokazuje źródła pominięte i niedostępne; wyniki nie są automatycznie zweryfikowanymi faktami.
4. **Maksymalnie trzy połączenia i około minuta skanu.** Część wolnych kanałów może być pominięta. To ograniczenie jawne, zamiast nieskończonego oczekiwania.
5. **Lista skanu pokazuje do 50 linków.** Nie ma jeszcze pełnej paginacji wszystkich wyników z indeksów wyszukiwarek. Pamięć lokalna ma własną paginację.
6. **Następny etap pogłębiania:** niezależne adaptery dokumentów BIP/ZDW i tekstowych PDF z rozpoznaniem dat wydawcy, kontrolą pobranej treści i historią zmian. OCR wymaga osobnego limitowania zasobów i testów na słabszych telefonach. Nie oznaczono tego etapu jako wykonanego.
7. **Podpis APK:** kolejne testowe kompilacje CI mogą mieć różne certyfikaty debug. Nie należy usuwać istniejącej aplikacji ani jej danych w celu instalacji. Zgodna aktualizacja wymaga właściwego klucza; kopie szkiców i pamięci są konieczne. Testowy APK można sprawdzić na osobnym urządzeniu/emulatorze.

## Instrukcja szybkiego sprawdzenia

1. Wybierz 7 dni i dotknij **OSP**. Status ma wskazywać ostatnie 7 dni i daty publikacji.
2. Wybierz 90 dni i dotknij **Most**. Powinien zostać wykonany nowy skan; wcześniejsza odpowiedź z pamięci nie zastępuje wyniku.
3. Przy ustawionych 30 dniach wpisz „A z ostatnich 90 dni o OSP?”. Wynik ma używać 90 dni.
4. Po rozmowie o moście dotknij **Cokolwiek nowego o Ispinie**. Nie powinno pozostać ograniczenie wyłącznie do mostu.
5. Dotknij **Autobusy**; sprawdź profile MLD, linii gminnej i przewoźnika.
6. Rozwiń zakres sprawdzenia przy odpowiedzi. Ograniczenia, np. niedostępny kanał lub brak daty indeksu, mają pozostać widoczne.
7. Wpisz „Co znaleźliśmy w pamięci o moście?”. Dopiero taki zamiar może uruchomić odpowiedź z archiwum.
