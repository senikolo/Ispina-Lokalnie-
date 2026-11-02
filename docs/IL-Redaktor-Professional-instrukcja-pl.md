# IL Redaktor Smart 2.0 Professional — instrukcja wersji testowej

## TEST-6: dostęp WordPress i bezpłatna praca redakcyjna

- Główny przycisk logowania używa standardowego zakresu `auth` z aktualnej instrukcji WordPress.com MCP. Do tworzenia wpisów musisz też nadać dostęp w WordPress.com → AI i MCP → Content Authoring (Odczyt oraz Zapis). Po logowaniu aplikacja sprawdza rzeczywisty odczyt artykułów.
- Jeżeli serwer zgłasza brak zakresu `global`, rozwiń **Serwer odmawia dostępu do wpisów?**, zapoznaj się z ostrzeżeniem i samodzielnie potwierdź ponowienie logowania z szerszym zakresem. `global` może obejmować całe konto WordPress.com. Nie ma automatycznego podwyższania uprawnień.
- Po powrocie z ChatGPT Free możesz wybrać **Wklej i porównaj jednym kliknięciem**. Operacja odczytuje schowek po Twoim kliknięciu i wyświetla propozycję zmian, ale nie zapisuje ani nie publikuje artykułu. Osobno zaakceptuj treść po sprawdzeniu źródeł.
- Przy przerwanym zapisie **Sprawdź ostatni zapis** porównuje pierwotne żądanie także po restarcie. Dodatkowe zmiany wprowadzone później zostają lokalne, a nie automatycznie uznane za zsynchronizowane.
- W Radarze indeksy PDF/BIP/zamówień mają wyższy priorytet w ramach tego samego ograniczonego czasu. Wykrycie odnośnika PDF **nie oznacza odczytania jego treści**.

Status: Professional TEST-6 / 50, PR #30 (draft). Warunki przejścia do stabilnego wydania i ryzyka: [raport TEST-6](IL-Redaktor-TEST6-audyt-i-wdrozenie-2026-11-02.md). Nie usuwaj aktualnej aplikacji ani jej danych, dopóki nie wyeksportujesz szkiców i pamięci Radaru oraz nie potwierdzisz zgodności podpisu APK.

---

Wersja źródeł: 2.0.0-professional-test.5, versionCode 49 (nowa APK jeszcze nie zbudowana). Hasło: „Łączy nas Ispina”.

Aktualizacja instrukcji z 2 listopada 2026 r.: Radar ma dziennik „Na żywo — sprawdzane strony”, bezpośrednie kanały lokalnych wydawców i odczyt artykułów gminy. Test na rzeczywistych źródłach na Androidzie 11 znalazł 5 datowanych publikacji zewnętrznych, w tym artykuł gminy; odebrano 139 zdarzeń postępu. To wynik konkretnej próby, nie gwarancja kompletności wszystkich źródeł.

Aktualny stan TEST-5 i ograniczenia: [raport kontynuacji](IL-Redaktor-TEST5-stan-pracy-2026-11-02.md). Ponowne sprawdzenie niepotwierdzonego zapisu porównuje również treść. Starsze szkice bez kopii wysłanego żądania wymagają ręcznego porównania w WordPressie.

## Przed instalacją

Ta wersja jest przeznaczona do testów. Nie odinstalowuj dotychczasowego Redaktora i nie kasuj jego danych. Identyczny identyfikator pakietu nie wystarcza do aktualizacji: potrzebny jest też zgodny certyfikat podpisujący.

1. W działającej aplikacji otwórz **Moje szkice → Zapisz kopię szkiców do pliku**. Zachowaj JSON poza pamięcią prywatną aplikacji.
2. Jeżeli Twoja wersja ma pamięć redakcyjną, otwórz **Pamięć redakcyjna → Zapisz kopię JSON**. To osobna kopia od szkiców.
3. Najważniejszy niedokończony tekst zachowaj dodatkowo przez **Kopia tekstu**. Jeśli starsza wersja nie ma eksportu, nie próbuj rozwiązywać problemu aktualizacji przez odinstalowanie.
4. Zamknij aplikację po zapisaniu i wyeksportowaniu danych. Aktualizuj istniejącą instalację dopiero po potwierdzeniu podpisu. Komunikat „Aplikacja nie została zainstalowana” przy różnym podpisie nie oznacza, że należy usunąć poprzednią aplikację.
5. Przy braku zgodnego klucza testuj APK na osobnym telefonie bez dotychczasowej instalacji albo na emulatorze. Odzyskanie klucza prywatnego z certyfikatu APK nie jest możliwe.

Testy CI aktualizują 1.29.4 → 2.0 w ramach tego samego przebiegu i tego samego klucza testowego. To sprawdza zachowanie danych, ale nie potwierdza zgodności z kluczem dowolnej APK już zainstalowanej na Twoim telefonie. Odzyskany prywatny klucz zgadza się z `release-certificate.sha256`, lecz różni się od udostępnionych debug APK 1.29.4-test.1 i Professional TEST 2. Nowa APK podpisana kluczem wydaniowym nie aktualizuje bezpośrednio tych dwóch wersji. Nie odinstalowuj ich bez wyeksportowania i sprawdzenia kopii danych.

## Znajdź informacje o Ispinie

1. Otwórz **Radar Ispiny** i wpisz krótki temat, np. „most w Ispinie” albo „OSP Ispina”.
2. Wybierz 7, 30 albo 90 dni; domyślnie 30 dni. Okres wyrażony w pytaniu ma pierwszeństwo. Pełny formularz badań oferuje też cały dostępny zakres. Ta wersja nie ma dowolnej pary dat od–do.
3. Uruchom wyszukiwanie. Przycisk anulowania przerywa pobieranie; skan ma ograniczony czas.
4. Obserwuj **Na żywo — sprawdzane strony**, a po skanie otwórz **Zakres sprawdzenia**. Widać rzeczywiste adresy, etapy odczytu, kanały niedostępne i pominięte oraz powody ograniczeń. Dziennik pozostaje dostępny po skanie. Publikacje własnego portalu są oddzielone od materiałów zewnętrznych.
5. Czytaj oryginalny dokument przed wykorzystaniem faktów. Wyszukiwanie PDF wskazuje linki z indeksu wyszukiwarki; nie wykonuje OCR ani pełnego odczytu wszystkich załączników.
6. Przy filtrze czasu źródła bez potwierdzonej daty publikacji są oznaczane jako ograniczenie skanu. Data aktualizacji nie zastępuje daty publikacji.

Zwykłe pytanie o nowości zawsze sprawdza internet. Dopiero jawne pytanie o pamięć, np. „Co znaleźliśmy w pamięci o moście?”, odczytuje wcześniejsze materiały zamiast nowego skanu. Przycisk **Szukaj głębiej w ChatGPT Free** przygotowuje polecenie z tematem i okresem do ręcznego użycia w ChatGPT.

Radar używa pełnych treści kanałów RSS, odczytu list i artykułów gminy, kanałów regionalnych, indeksów wyszukiwarek i publicznego API własnego portalu. Respektuje rozpoznane reguły robots.txt i nie obchodzi logowania, blokad ani CAPTCHA. Błąd lub przekierowanie źródła może ograniczyć wyniki. Skan ma limit czasu i liczby artykułów; nie gwarantuje kompletności internetu. Wyszukiwanie PDF nadal wskazuje linki z indeksu; nie obejmuje OCR ani pełnego odczytu wszystkich załączników.

## Wróć do zapisanych materiałów

Otwórz **Pamięć redakcyjna**. Wyszukiwanie obejmuje zapisane tytuły, opisy, pytania i notatki. Prefiks „konserwatorsk” może znaleźć „konserwatorski”. Nie jest to wyszukiwanie w pełnej treści niepobranych dokumentów ani model językowy.

- **Nowe**: źródło zapisano pierwszy raz.
- **Znane**: powtórnie odnalezione bez zmiany zapisanych metadanych albo ręcznie sprawdzone.
- **Zmienione**: zmienił się zapisany tytuł, opis lub data; ponowne wyszukanie nie usuwa tego oznaczenia przed ręczną weryfikacją.
- **Do sprawdzenia**: nie oznaczyłeś źródła jako sprawdzonego; ten status jest niezależny od powyższych.
- **Sprawdzone przez redaktora**: świadomie potwierdziłeś przeczytanie oryginału. Zmiana zapisanych metadanych unieważnia to oznaczenie.

Dodaj własną notatkę i kliknij **Zapisz notatkę**. Przycisk **Do szkicu** dopisuje źródło do bieżącego szkicu i zapisuje powiązanie. Po potwierdzonym zapisie artykułu powiązanie może obejmować również numer WordPress. Powiązania są informacją historyczną: usunięcie szkicu nie usuwa automatycznie jego śladu w pamięci.

**Wczytaj kolejne źródła** pokazuje następne 50 rekordów. Eksport zachowuje wszystkie źródła w granicach limitu pliku, a nie tylko aktualnie widoczną stronę.

## Przygotuj artykuł z ChatGPT Free

1. Wpisz temat lub przygotuj szkic, dodaj źródła i wybierz zadanie asystenta.
2. Przejrzyj polecenie. Wybierz otwarcie/udostępnienie w ChatGPT albo skopiuj polecenie.
3. W ChatGPT wyślij polecenie samodzielnie. Obowiązują limity Twojego konta, także planu Free.
4. Skopiuj odpowiedź, wróć do Redaktora i wklej ją w polu odpowiedzi.
5. Otwórz porównanie i zaakceptuj wybrane zmiany. Odpowiedź nie zastępuje artykułu automatycznie.

Ten przepływ nie wymaga płatnego klucza API. Nie potwierdzono w tych pracach bezpośredniego automatycznego generowania przez konto ChatGPT Free. Zapisane w projekcie opcjonalne mechanizmy AI nie oznaczają gwarantowanego dostępu konta do modelu.

Lokalna korekta nazwy, formatowanie i szablony są opisane jako reguły i szablony. Nie sprawdzają automatycznie faktów. Poprawne formy: w Ispinie, do Ispiny, z Ispiny, ispiński.

## Edycja i zapis

Pracuj etapami **Tekst → Zdjęcia i źródła → Kontrola → Publikacja**. Szkice zapisują się lokalnie; historia pozwala wracać do poprzednich wersji. Podgląd nie publikuje tekstu.

Po zapisaniu w WordPressie aplikacja odczytuje wpis ponownie. Sukces wymaga potwierdzenia statusu i zawartości. Numer otrzymanego wpisu zapisuje się lokalnie przed ponownym odczytem.

Jeżeli połączenie zostanie przerwane podczas wysyłania, stan niepewnej operacji pozostaje w szkicu także po ponownym uruchomieniu i w jego kopii eksportowej. Najpierw użyj **Sprawdź ostatni zapis**, a następnie sprawdź listę artykułów w WordPressie. Nie wysyłaj drugi raz bez tej kontroli. Świadome ponowienie po ostrzeżeniu nadal może utworzyć duplikat, jeżeli pierwszy zapis doszedł do serwera, ale odpowiedź nie wróciła.

Test rzeczywistego zapisu na koncie właściciela należy wykonać dopiero po osobnej zgodzie, jako niepubliczny szkic. W tych pracach nie wykonano takiego zapisu.

## Zdjęcia i Usługi

Zdjęcie wybieraj z telefonu albo galerii WordPress. Sprawdź autora, prawo do użycia, ALT i podpis. W tej wersji nie potwierdzono kompleksowego deduplikowania ani automatycznej optymalizacji całej galerii.

Przed zmianą katalogu **Usługi** odczytaj aktualną stronę. Aplikacja zachowuje pozostałą treść i sprawdza, czy strona nie zmieniła się od odczytu. Przed wysłaniem zmiany tworzy dokładną lokalną kopię całej strony. Jeśli nie może jej zapisać, nie wysyła zmiany.

**Kopia sprzed ostatniej zmiany** pokazuje pełny kod WordPress, który można zaznaczyć i skopiować do edytora kodu strony. Sam podgląd kopii nie zapisuje niczego w WordPressie. Kopia obejmuje ostatnią zmianę wykonaną przez tę aplikację; nie jest wielowersyjnym archiwum strony ani częścią eksportu szkiców. Starsze ręcznie dodane oferty edytuje się przez wskazany przycisk WordPressa; nie przebudowano ich automatycznie na nowe karty.

## Kopie i odzyskiwanie

Import pamięci dopisuje brakujące rekordy, zachowując istniejące. Nowe kopie mają sumę SHA-256; niezgodność powoduje odrzucenie importu przed zmianą bazy. To wykrywanie uszkodzeń, nie szyfrowanie ani podpis autora. Starszy format bez sumy nadal może być importowany dla zgodności.

Szkice i pamięć mają osobne pliki eksportowe. Zachowuj obydwa. Baza jest migrowana przez dodawanie kolumn i indeksów, bez kasowania źródeł lub historii. Nie instaluj starszej APK na nowszej bazie i nie kasuj danych aplikacji.
