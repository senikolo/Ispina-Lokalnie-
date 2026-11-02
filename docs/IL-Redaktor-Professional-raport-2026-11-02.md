# IL Redaktor Smart 2.0 Professional — raport prac 2 listopada 2026

## Stan wydania

**Wersja testowa 2.0.0-professional-test.1 (45), nie stabilna.** Zmiany są na nowej gałęzi `codex/il-redaktor-professional-20261102`, PR #25. Nie scalano do main. Baza: rzeczywisty head PR #24 `d7dabc224fa34c6a69f32a17bbc35458c2abdb2b`, który w chwili rozpoczęcia był otwartym szkicem. PR #24 nie był scalony.

Nie opublikowano żadnych artykułów ani nie zmieniono katalogu na produkcyjnym portalu. Wywołania WordPress w testach zapisu były atrapą transportu, a testy sieciowe dotyczyły publicznego odczytu. Nie przeprowadzono rzeczywistego logowania właściciela ani testu prywatnego szkicu.

## Potwierdzone problemy i zmiany

| Problem | Wdrożenie i zakres | Sprawdzenie |
|---|---|---|
| Ostatni CI PR #24 zatrzymywał się na Androidzie 8 | Test oczekiwał versionCode 43 przy zbudowanym 44; używa teraz BuildConfig dla aktualnej APK | Pierwszy nowy przebieg przeszedł API 26 |
| Numer wpisu nie był utrwalany przed odczytem weryfikacyjnym | Zapis ID zaraz po odpowiedzi POST, przed GET | Test błędu GET i odtworzenia szkicu po restarcie |
| Niepewna operacja wysyłania istniała tylko w RAM | Dziennik niepotwierdzonego zapisu w trwałym szkicu i eksporcie; świadome potwierdzenie ponowienia | Test timeout POST → restart → odmowa ponowienia; test czyszczenia po sukcesie |
| Pamięć nie obejmowała notatek i użycia źródła | Notatki, powiązania z lokalnym ID szkicu i numerem WordPress, FTS4 | Testy instrumentacyjne notatek, wyszukiwania i powiązań |
| Lista kończyła się na 50 rekordach | Paginacja z total/hasMore; ignorowanie spóźnionej odpowiedzi poprzedniego filtra | Test konkurencyjnych odpowiedzi JS; test offsetu SQLite |
| Brak rozdzielenia nowych, znanych i zmienionych materiałów | Osobne pole discovery, niezależne od ręcznej weryfikacji | Test filtrowania zmian i unieważniania starej oceny |
| Kopia pamięci nie miała kontroli integralności | Wersja schematu, kanoniczny JSON i SHA-256; weryfikacja przed transakcją importu, zgodność ze starym formatem | Test zmodyfikowanej kopii oraz odtworzenia notatek |
| Brak rzeczywistej migracji rozszerzonej pamięci | Addytywne przejście v1 → v2, zachowanie historii i statusów, indeks FTS utrzymywany triggerami | Instrumentacyjna baza v1; lokalne wykonanie dokładnego SQL i triggerów |
| Radar ograniczony do RSS/Atom | Dwa bezpośrednie indeksy HTML, robots.txt, osobny profil PDF i błędnych odmian nazwy, zachowane kanały wyszukiwarek | Testy parsera HTML i reguł robots; ograniczenia opisane poniżej |
| Szczegóły niedostępnych kanałów były pomijane w interfejsie | Rozwijany zakres skanu także dla pustych wyników, bezpieczne kodowanie treści błędów | Test raportu HTTP 403 i ochrony przed HTML w komunikacie |
| Nieznana data 0 mogła wyglądać jak styczeń 2000 | Jawny brak daty; rozdzielenie daty aktualizacji i nieklasyfikowanej daty od publikacji | Test daty 0; pola dateType/updatedAt w bazie i kopii |
| Brak kopii pełnej strony przed zmianą Usług | Prywatny AtomicFile, SHA-256 i kontrolny odczyt; błąd kopii blokuje POST; podgląd odzyskiwania | Test odmowy zapisu przy braku miejsca i dokładnej treści kopii; instrumentacyjny test uszkodzenia |
| Test aktualizacji na Androidzie 10 używał wersji 1.15 | Testy przechodzą z ostatniej rzeczywistej wersji 1.29.4, z zasobem WebView po transpilacji | Osobne zadania CI API 26/29/30/33 |
| Awaria jednego Androida pomijała kolejne | Macierz czterech niezależnych zadań, fail-fast false, raporty także przy awarii | Wyniki końcowego przebiegu CI poniżej |

## Walidacja lokalna

- 184/184 testy JavaScript interfejsu i zachowania aplikacji — uruchomione, zaliczone.
- 12/12 testów opcjonalnego zaplecza — uruchomione, zaliczone.
- TypeScript `tsc --noEmit` — zaliczony.
- Transpilacja rzeczywistego interfejsu dla starego WebView — zaliczona.
- Dokładny SQL z kodu: utworzenie bazy, FTS4, synchronizacja insert/update/delete — wykonane na SQLite, zaliczone. Nie zastępuje to testów Android SQLite.
- `git diff --check` — zaliczony.
- Statyczna kontrola 174 deklarowanych w HTML obsług zdarzeń — nie znaleziono brakujących nazw funkcji. Nie oznacza to testu działania każdego przycisku.

Testy JS wykonują kod aplikacji z atrapą DOM i transportu; nie są ręcznym testem wszystkich elementów na fizycznym telefonie.

## CI i urządzenia

Końcowy przebieg: https://github.com/senikolo/Ispina-Lokalnie-/actions/runs/41373793021

| Zadanie końcowego CI | Wynik |
|---|---|
| Budowa APK, testy JS/zaplecza, kontrola OAuth i publicznych kanałów | SUKCES |
| Android 8 / API 26 — aktualizacja 1.29.4 → 2.0 i testy instrumentacyjne | SUKCES |
| Android 10 / API 29 — aktualizacja 1.29.4 → 2.0 i testy instrumentacyjne | SUKCES |
| Android 11 / API 30 — aktualizacja 1.29.4 → 2.0 i testy instrumentacyjne | SUKCES |
| Android 13 / API 33 — aktualizacja 1.29.4 → 2.0 i testy instrumentacyjne | SUKCES |

Testy instrumentacyjne obejmowały m.in. utrzymanie szkiców po instalacji przez `adb install -r`, odtworzenie biblioteczki, SQLite i migrację v1, FTS, notatki/powiązania, import/eksport i uszkodzoną kopię, zabezpieczenie strony Usługi, przepływy edytora/źródeł, porównywanie zmian, ustawienia wibracji i obsługę potwierdzeń WebView. Wywołania zapisujące WordPressa były symulowane. Wszystkie testy uruchomiono; nie były to fizyczne telefony.

Pierwszy przebieg kontrolny `41372755298`: kompilacja i weryfikacja podpisu testowego APK przeszły; API 26 przeszło; API 29 zgłosiło `Draft seeding failed: null` na starej bazie 1.15. API 30 przeszło. Nie traktujemy nieukończonego testu API 33 z tego przebiegu jako zaliczonego. Test bazowy został zmieniony na 1.29.4; końcowe wyniki należy oceniać według powyższego przebiegu.

Nie użyto fizycznych telefonów Android 8, 10, 11 ani 13. Nie potwierdzono działania autoryzacji ani publikacji na rzeczywistym koncie właściciela. Wibracje na emulatorze weryfikują kod i ustawienia, a nie odczucia z silnika wibracyjnego telefonu.

## Podpis i bezpieczna aktualizacja

Porównano certyfikat rzeczywistego pliku `IL-Redaktor-Smart-1.29.4-test.1.apk` z wcześniejszą próbną kompilacją nowej gałęzi:

- 1.29.4, certyfikat Android Debug SHA-256: `e0e583cc9416f4bfdeb25ac7019d94166e4a33ee96964fe96419805624eb0718`.
- Wcześniejszy nowy debug APK: `950b2915e3538916fa4d458f5355ba6484375d26f91e6234c949876718c234c9`.
- Oczekiwany certyfikat wydaniowy zapisany w repo: `e02f06118cb5c74e367de92ffd33bc8b39b52c9aa9f2b5a9f03b8cda08df2e4c`.

Te certyfikaty są różne. Ten fakt wyklucza deklarowanie wszystkich plików jako zgodnych aktualizacji zainstalowanej wersji. Końcowy APK ma odcisk certyfikatu `3dc6827ed2f77c531416c418f22748b0e9cdaa91516b6ed8e51139b6e73d41c4`. Sam plik APK ma SHA-256 `ed023dff23ede65856d1edd4daf02b39e4b8000b7fb9e2c27af5a4dd32ff9d1d`; lokalne `apksigner verify` potwierdziło podpis v2. To również certyfikat Android Debug, różny od certyfikatu starego APK. W końcowym CI zabrakło sekretów podpisu wydaniowego; nie utworzono zgodnego podpisanego UPDATE.apk. Nie posiadano klucza prywatnego użytego do wskazanego APK 1.29.4. Zdalny opcjonalny mechanizm podpisu nie ujawnia kluczy i porównuje certyfikat z plikiem repozytorium; nie dowodzi zgodności z dowolną wcześniejszą wersją debug.

Nie kasuj dotychczasowej instalacji. Najpierw eksport szkiców i pamięci oraz dodatkowa kopia najważniejszego tekstu. Przy braku właściwego klucza testuj osobne urządzenie/emulator. Docelowa aktualizacja tej samej instalacji wymaga oryginalnego klucza albo prawidłowej historii rotacji podpisu. Nie przygotowano jeszcze osobnego pakietu migracyjnego do instalacji obok starej aplikacji.

## Uczciwe ograniczenia i prace pozostałe

1. To realizacja istotnej części modernizacji, nie deklaracja ukończenia wszystkich 16 punktów specyfikacji.
2. Radar nadal nie przeszukuje pełnej treści wszystkich BIP/PDF/załączników. HTML obejmuje ograniczone indeksy i linki z nazwą miejscowości; PDF to osobny profil indeksu wyszukiwarki. Nie dodano OCR ani gwarancji pełnego pokrycia e-Zamówień, archiwów i InPost. Odczyt robots.txt, limity, błędy sieci i przekierowania mogą ograniczyć skan. Próba bezpośredniego sprawdzenia czterech adresów Drwinia/Eko-Drwinia z lokalnego środowiska zakończyła się błędami URL; nie zadeklarowano ich lokalnego odczytu na żywo jako zaliczonego.
3. FTS obejmuje zapisane metadane i notatki, nie pełną treść niepobranych dokumentów. Wykrywanie zmian dotyczy metadanych, a nie każdej zmiany w pliku PDF lub na serwerze. Nie ma pełnego archiwum historycznych wersji wszystkich dokumentów.
4. Wybór okresu to istniejące ostatnie dni / bez ograniczenia; nie dodano dowolnej pary dat od–do ani automatycznego ustalania dat wydarzeń.
5. Obsługa ChatGPT Free jest przepływem polecenie → zewnętrzny ChatGPT → odpowiedź → porównanie → zgoda. Nie potwierdzono bezpośredniej automatycznej integracji konta Free ani nie zagwarantowano dostępności modeli. Nie omijano limitów i nie przejmowano sesji.
6. Nie zmieniano mechanicznie `scope=global` na `scope=auth`. Istniejące PKCE, deep link, kontrola state i szyfrowanie tokenów pozostają w projekcie; rzeczywisty przebieg OAuth wymaga testu konta. Nie wydano zapewnienia, że naprawiono wszystkie zgłaszane problemy logowania.
7. Galeria zachowuje istniejące funkcje wyboru i obróbki. Nie wdrożono kompletnego systemu praw autorskich, deduplikacji oraz optymalizacji całej biblioteki mediów.
8. Katalog zachowuje stare oferty i tworzy pełną kopię przed zmianą. Jednolita natywna edycja wszystkich historycznych ofert i kategoryzacja nie są jeszcze wdrożone. Kopia odzyskiwania obejmuje ostatnią lokalną zmianę, nie pełne archiwum wielowersyjne.
9. Ochrona przed duplikatami nie zapewnia serwerowej idempotencji WordPressa. Nie ma automatycznych ponowień POST; ostrzeżenie wymaga świadomej decyzji. Równoczesna edycja przez inne narzędzie pomiędzy kontrolnym GET a POST nadal wymaga ostrożności.
10. Nie przeprowadzono kompletnego ręcznego audytu każdego przycisku na wszystkich fizycznych urządzeniach ani pomiarów baterii i RAM. Nie oznaczono takich testów jako zaliczonych.

## Dokumentacja i następny test właściciela

Instrukcja: `docs/IL-Redaktor-Professional-instrukcja-pl.md`.

Warunkiem wydania stabilnego pozostaje zgodny podpis, potwierdzenie migracji na docelowym telefonie, prawdziwy OAuth i — dopiero za osobną zgodą — zapis niepublicznego szkicu z ponownym odczytem. Żaden z tych braków nie został zastąpiony deklaracją sukcesu.
