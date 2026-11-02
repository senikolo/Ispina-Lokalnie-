# IL Redaktor Smart 2.0 Professional — instrukcja wersji testowej

Wersja: 2.0.0-professional-test.3, versionCode 47. Hasło: „Łączy nas Ispina”.

## Przed instalacją

Ta wersja jest przeznaczona do testów. Nie odinstalowuj dotychczasowego Redaktora i nie kasuj jego danych. Identyczny identyfikator pakietu nie wystarcza do aktualizacji: potrzebny jest też zgodny certyfikat podpisujący.

1. W działającej aplikacji otwórz **Moje szkice → Zapisz kopię szkiców do pliku**. Zachowaj JSON poza pamięcią prywatną aplikacji.
2. Jeżeli Twoja wersja ma pamięć redakcyjną, otwórz **Pamięć redakcyjna → Zapisz kopię JSON**. To osobna kopia od szkiców.
3. Najważniejszy niedokończony tekst zachowaj dodatkowo przez **Kopia tekstu**. Jeśli starsza wersja nie ma eksportu, nie próbuj rozwiązywać problemu aktualizacji przez odinstalowanie.
4. Zamknij aplikację po zapisaniu i wyeksportowaniu danych. Aktualizuj istniejącą instalację dopiero po potwierdzeniu podpisu. Komunikat „Aplikacja nie została zainstalowana” przy różnym podpisie nie oznacza, że należy usunąć poprzednią aplikację.
5. Przy braku zgodnego klucza testuj APK na osobnym telefonie bez dotychczasowej instalacji albo na emulatorze. Odzyskanie klucza prywatnego z certyfikatu APK nie jest możliwe.

Testy CI aktualizują 1.29.4 → 2.0 w ramach tego samego przebiegu i tego samego klucza testowego. To sprawdza zachowanie danych, ale nie potwierdza zgodności z kluczem dowolnej APK już zainstalowanej na Twoim telefonie.

## Znajdź informacje o Ispinie

1. Otwórz **Radar** i wybierz **ostatnie 7, 30 lub 90 dni**. To okres publikacji, a nie data pierwszego odnalezienia linku przez aplikację.
2. Dotknij **Most**, **Autobusy**, **OSP** albo **Cokolwiek nowego o Ispinie**. Możesz też wpisać własne pytanie, np. „Czy pojawiło się coś o moście z ostatnich 90 dni?”. Okres podany w pytaniu ma pierwszeństwo przed selektorem.
3. W czasie skanu od razu otwiera się **Na żywo — sprawdzane strony**. Widać rzeczywiste adresy, sposób dostępu, etap odczytu, dopasowania i przyczyny błędów. Adres indeksu wyszukiwarki oznacza zapytanie do indeksu; nie oznacza, że aplikacja przeczytała wszystkie oryginalne strony.
4. Użyj **Anuluj wyszukiwanie**, aby przerwać pobieranie. Dziennik pozostanie do sprawdzenia. Kolejne wyszukiwanie zaczyna nowy dziennik. Skan trwa maksymalnie około minuty; natychmiastowy pusty kanał nie jest dowodem braku wiadomości w internecie.
5. Najpierw zobaczysz **publikacje zewnętrzne**, a osobno rozwijaną grupę **Twój portal — powiązane publikacje**. Własny artykuł może pomóc znaleźć powiązane tematy, ale nie jest niezależnym potwierdzeniem wiadomości.
6. Linki bez ustalonej daty mogą pojawić się w grupie **Materiały do ustalenia daty**. Nie są liczone jako nowe wiadomości z wybranego okresu. Data wydarzenia ani aktualizacji nie zastępuje daty publikacji.
7. Rozwiń **Zakres sprawdzenia** przy odpowiedzi. Pokazuje odczytane, niedostępne i pominięte kanały. Wynik częściowy nie pozwala stwierdzić, że „nic nowego się nie pojawiło”.
8. Otwórz oryginalny materiał przed wykorzystaniem faktów. **Opracuj szkic w ChatGPT/Gemini** przygotuje osobny szkic i kontekst; niczego nie opublikuje automatycznie.

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
