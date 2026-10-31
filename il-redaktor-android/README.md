# IL Redaktor Smart 1.19.0

Stały applicationId `com.ispinalokalnie.redaktorsmart.stable`, versionCode 25. Podpisuj tym samym kluczem co 1.14/1.15/1.16/1.17/1.18; nie odinstalowuj przy aktualizacji.

Wersja 1.19 dodaje bezpłatne przekazanie polecenia do aplikacji ChatGPT, a gdy nie ma odbiorcy ChatGPT — próbę otwarcia Gemini. Nie używa klucza API ani nie wysyła tekstu bez dotknięcia przycisku. Po powrocie odpowiedź można wkleić ze schowka i porównać z treścią, tytułem, zajawką lub tekstem na Facebooka. Zmiana artykułu wymaga zatwierdzenia w porównaniu. Bez aplikacji przyjmującej tekst pozostaje kopiowanie polecenia i ręczne wklejenie.

Szablony krok po kroku obejmują MLD, wydarzenia i OSP, aktualności, historię oraz usługi lokalne. Puste informacje są oznaczane jako „DO UZUPEŁNIENIA”; lokalna kontrola pokazuje też brakujące źródło, daty i formy nazwy Ispina. Pakiet publikacyjny zbiera tytuł, zajawkę, propozycję na Facebooka i listę sprawdzeń.

ChatGPT/Gemini są zewnętrznymi aplikacjami. Wysłanie treści wymaga świadomego działania użytkownika, a powrót odpowiedzi następuje przez schowek. Odbiór tekstu zależy od wersji i ustawień danej aplikacji.

Wersja 1.18 kieruje krótką wibrację do każdego dotkniętego kafelka pulpitu, zakładki dolnej nawigacji i dnia w kalendarzu. Usunięto ograniczenie tłumiące szybkie kolejne tapnięcia, a intensywności mają wyraźniejsze czasy i amplitudy. Wibracje nadal można wyłączyć lub regulować w ustawieniach. Test UI obejmuje wiele kolejnych tapnięć oraz natywny most Androida.

Wersja 1.17 dodaje lokalne generowanie i edycję krótkiej zajawki na Facebooka z tytułu oraz zajawki artykułu. Dla zmian rozkładów aplikacja proponuje CTA „Zobacz, co się zmieni.” Kopiowanie i systemowe Udostępnij wymagają działania użytkownika; aplikacja nie publikuje na Facebooku. Ustawienia wibracji pokazują, czy Android wykrywa silnik, a test podaje czytelny wynik dla trybu wyłączonego, telefonu bez wibracji i wysłanego sygnału.

## Zmiany

Kopia do pliku ma teraz version 2; aplikacja wczytuje też wcześniejsze kopie version 1. Starszy Redaktor 1.15 odrzuci plik version 2, zamiast pominąć nowe źródła i zdjęcia. Wewnętrzny format biblioteczki pozostaje version 1 dla migracji danych.

- Odczyt pełnej wersji wpisu przed przygotowaniem zapisu oraz ponownie tuż przed posts.update. Wersja odniesienia obejmuje treść, tytuł, zajawkę, kategorie, tagi, zdjęcie, status, datę i znacznik modified. Zmiana lub brak punktu odniesienia zatrzymuje wysłanie wpisu i otwiera porównanie. Wczytanie strony zachowuje starszą kopię lokalną; Zachowaj obie kopie odłącza lokalny szkic od WordPress ID. Zmiana lokalna podczas odczytu przerywa zapis. Nie ma przycisku ślepego nadpisania.
- Ograniczenie integracji: posts.update w aktualnym schemacie MCP nie oferuje atomowego compare-and-set ani warunku if-unmodified-since. Dwa odczyty wykrywają zmiany widoczne w tych momentach; nie eliminują wyścigu, gdy inny klient zapisze dokładnie między ostatnim GET i POST. Nie jest to serwerowa blokada współbieżnej edycji.
- Źródła przy szkicu: linki http/https, notatki, ręczny status sprawdzenia i zdjęcia dokumentów. Zdjęcia są pomniejszane i zapisywane wyłącznie w lokalnej biblioteczce (bez media.create). Są dołączane do kopii JSON jako deduplikowane sourceFiles, a historia trzyma tylko odnośniki. Niepublikowane notatki i zdjęcia nie są wysyłane w payload artykułu. Osobny przycisk pozwala jawnie dodać odsyłacz do treści. Do 20 źródeł na szkic, 600 KB na zdjęcie i 8 MB całej biblioteczki/kopii. Pliki niewskazywane już przez żaden szkic lub jego historię są pomijane przy kolejnym zapisie.
- Kalendarz redakcyjny: plany lokalne, kopie terminów, opublikowane i zaplanowane wpisy z WordPressa. Odświeżenie to wyłącznie odczyt, do 100 future i 100 ostatnich publish. Plan lokalny nie zleca publikacji. Daty date_gmt są przeliczane na strefę telefonu; przy braku date_gmt używana jest data zwrócona przez serwer. Kalendarz nie jest powiadomieniem ani usługą cyklicznej synchronizacji.
- Podgląd roboczy 360 px / 1024 px w izolowanej ramce. Układ motywu WordPressa może się różnić; rzeczywisty widok opublikowanego artykułu pozostaje dostępny.
- Szukaj i zamień: dosłowna fraza, wielkość liter, wybór treści/tytułu/zajawki i potwierdzenie w porównaniu korekt. HTML pomija znaczniki, atrybuty i bloki code/pre/script/style.
- Nowe kafelki, łagodne animacje otwierania i przejść, bez ciągłego pulsowania logo. Respektują ustawienia aplikacji, oszczędzanie baterii i prefers-reduced-motion.

Instrukcje i ograniczenia wcześniejszego wydania:

# IL Redaktor Smart 1.15.0

Aktualizacja stabilnego pakietu `com.ispinalokalnie.redaktorsmart.stable`, versionCode 21.
Podpisuj tym samym prywatnym kluczem co 1.14; odcisk jest przypięty w release-certificate.sha256.

- Podstawowa biblioteczka jest zapisywana w natywnym AtomicFile z synchronizacją przed potwierdzeniem zapisu. WebView localStorage jest tylko pamięcią pomocniczą; szkice 1.14 są migrowane przy odczycie. Test starej wersji pozwala jej asynchronicznemu magazynowi DOM dokończyć zapis przed zakończeniem procesu testowego.
- Plik kopii JSON zawiera wyłącznie szkice i do 12 wersji historii, bez tokenów i ustawień konta. Eksport/import używa systemowego wyboru dokumentu, limit 8 MB. Import pokazuje podsumowanie, wymaga Dołącz szkice, nie nadpisuje istniejących i pomija identyczne kopie. Kopia nie jest szyfrowana: przechowuj ją w swoim bezpiecznym miejscu.
- Korekty lokalne i propozycje artykułów AI przechodzą przez porównanie przed/po z wyborem pól, kontrolą zmienionego tekstu i cofaniem. Zachowują WordPress ID. Korekta HTML nie modyfikuje atrybutów ani bloków code/pre/script/style.
- Pomoc przy tekście lokalnie obsługuje nazwy, skracanie zwykłego wstępu do dwóch zdań, tytuł z początku tekstu i szablon zakończenia. Swobodne polecenia wymagają istniejącego, skonfigurowanego backendu AI. Domyślny APK nie ma klucza Gemini ani backendu; abonament Gemini Pro nie udostępnia API. Żaden tekst nie jest wysyłany do AI przez lokalne przyciski.
- Audyt nazw i faktów wskazuje błędne formy, skrót Niepoł i daty wymagające źródeł. Nie potwierdza automatycznie prawdziwości informacji.
- Pakiet promocyjny powstaje po potwierdzonym odczycie opublikowanego wpisu: Facebook, krótka wiadomość, zaproszenie. Tekst można zmienić, skopiować lub otworzyć systemowe Udostępnij. Nie publikuje w serwisach społecznościowych.
- Edytor nowych zdjęć: EXIF, obrót 90°, pełny kadr / 16:9 / 1:1, przesuwanie kadru, podpis, autor i alt. Edytuje kopię do 1600 px, wysyła pojedynczy media.create po świadomym wyborze Dodaj do biblioteki WordPress, bez automatycznego ponawiania. Podpis i autor trafiają do metadanych; widoczność przy zdjęciu wyróżniającym zależy od motywu. Oryginalny plik pozostaje bez zmian. Edycja dotyczy zdjęć Z telefonu; istniejąca galeria pozostaje do wyboru gotowych plików.

Test emulatora buduje poprzednią 1.14 i nową 1.15 tym samym testowym kluczem debug, zapisuje szkice w 1.14 i aktualizuje do 1.15 bez odinstalowania. Sprawdza szkice, historię i znacznik preferencji. Dodatkowe testy uruchamiają rzeczywiste porównanie/cofanie, zapis i odczyt strumieni pliku kopii (testowy URI prywatnego pliku; nie sterują interfejsem dostawcy SAF), kadrowanie, EXIF i anulowanie zdjęcia. Nie używają prawdziwych tokenów ani nie wykonują zapisów do WordPressa.

---
Dokumentacja wcześniejszej wersji:

# IL Redaktor Smart 1.14.0

Stały pakiet: `com.ispinalokalnie.redaktorsmart.stable`, versionCode 20.
Pierwsza instalacja obok poprzednich wydań testowych wymaga ponownego logowania.
Nie odinstalowuj starego wydania przed skopiowaniem lokalnych tekstów: jego dane nie przenoszą się między pakietami.

## Aktualizacje i podpis

Przyszłe aktualizacje muszą zachować applicationId, prywatny klucz podpisujący i zwiększać versionCode.
Publiczny odcisk SHA256 certyfikatu znajduje się w release-certificate.sha256.
Prywatny klucz i jego kopia pozostają poza repozytorium i artefaktami Actions.
Actions tworzy unsigned release; podpisywanie odbywa się prywatnym kluczem poza CI.
Debug APK z Actions służy wyłącznie do testów. Nie dystrybuować go jako aktualizacji stabilnej instalacji.
Podpisany release trzeba sprawdzić przez apksigner verify --verbose --print-certs i porównać odcisk.
Test emulatora sprawdza zachowanie dwóch szkiców, historii i znacznika preferencji przy ponownej instalacji adb install -r tego samego debug APK; nie używa prawdziwych danych logowania.

## Szkice i publikacja

Moje szkice przechowują wiele tekstów lokalnie, autosave 450 ms i do 12 poprzednich wersji (checkpoint przy przełączaniu lub co 30 s zmian).
Przywrócenie wersji zachowuje obecną w historii. Błąd pamięci blokuje czyszczenie edytora.
Kopie lokalne nie są kopią w chmurze i nie przetrwają odinstalowania ani wyczyszczenia danych; ważne teksty zachowuj także w WordPressie lub przez Kopia tekstu.
Przed publikacją jest podsumowanie i izolowany podgląd. Zdjęcie i zajawka pozostają opcjonalne.
Jedno potwierdzenie wysyła jeden POST, a SUKCES wymaga ponownego odczytu wpisu z WordPressa.

---
Poniżej dokumentacja historyczna wcześniejszych wersji testowych.

# IL Redaktor Smart

Niezależna aplikacja Android do redagowania portalu Ispina Lokalnie. Nie zmienia aplikacji dla mieszkańców. Aktualna wersja robocza: **1.13.0-test**, pakiet `com.ispinalokalnie.redaktorsmart.publish194.test` (versionCode 19), Android 8 i nowszy.

Przed kolejnym wydaniem użyj [Master Prompt QA](QA_MASTER_PROMPT.md) i zapisz wyniki według [raportu audytu 1.10.0](QA_AUDIT_1.10.0_2026-10-31.md). Testy kodu nie zastępują logowania na urządzeniu ani testu na koncie WordPress.com.

## Logowanie WordPress.com

Aplikacja używa oficjalnego OAuth 2.1 WordPress.com: dynamicznie rejestruje publicznego klienta, używa Authorization Code + PKCE, otwiera przeglądarkę systemową i odbiera kod przez adres `com.ispinalokalnie.redaktorsmart.publish194:/callback`. Nie ma Client Secret ani hasła w APK. Użytkownik loguje się do WordPress.com i może wybrać Google, jeśli ta metoda jest skonfigurowana dla jego konta. Osobny identyfikator aplikacji pozwala zainstalować tę wersję obok poprzedniej bez usuwania jej szkiców; do nowej instalacji należy zalogować się ponownie.

Po wymianie kodu aplikacja szyfruje token dostępu i token odświeżający kluczem Android Keystore. Zalogowanie jest potwierdzone dopiero, gdy aplikacja odczyta co najmniej jeden istniejący, opublikowany wpis przez WordPress.com MCP. Odmowa lub błąd API jest pokazywany wraz z odpowiedzią serwera. Token odświeżający nie jest gwarantowany przez WordPress.com; przy jego braku aplikacja prosi o ponowne logowanie po wygaśnięciu tokenu.

W ustawieniach konta WordPress.com trzeba włączyć dostęp MCP i zatwierdzić odpowiednie uprawnienia do witryny. Zgoda Google uwierzytelnia konto WordPress.com, ale sama nie przyznaje aplikacji dostępu do publikowania.

## Funkcje

- Wersja 1.13 naprawia brak natywnej obsługi `confirm()` w Android WebView: użytkownik widzi okno „Potwierdź w Redaktorze IL”, a zapis zaczyna się dopiero po wybraniu „Potwierdzam”. Anulowanie i zamknięcie okna niczego nie wysyła. CI sprawdza te trzy ścieżki na emulatorze Android 11 z prawdziwym WebView, bez zapisu do WordPressa. Wcześniejsze testy JavaScript z zastąpionym `confirm()` nie wykrywały braku obsługi w Androidzie.
- Główny przycisk „Opublikuj teraz” działa bez daty. Harmonogram jest domyślnie schowany pod „Zaplanuj na później”. Podczas zapisu okno pokazuje etap i blokuje ponowne kliknięcie. Potwierdzona publikacja kończy się zielonym napisem „SUKCES!”, wibracją i opcjonalnym sygnałem dźwiękowym (z uwzględnieniem trybu cichego), a przycisk „Zobacz artykuł na WordPressie” otwiera właściwy wpis w aplikacji.
- Pulpit redakcyjny, lista opublikowanych wpisów, szkiców i wpisów zaplanowanych; edycja wpisu, zapis szkicu, harmonogram i publikacja po dodatkowym potwierdzeniu. Zapis jest sukcesem dopiero po ponownym odczycie wpisu z WordPress.com. Po publikacji aplikacja wyświetla rzeczywistą stronę artykułu we własnym, bezpiecznym widoku WebView.
- Propozycje „Przeczytaj także” dobierane spośród istniejących, publicznych wpisów Ispina Lokalnie; pokazują prawdziwy tytuł, zajawkę i adres, pozwalają skopiować zachętę lub po potwierdzeniu wstawić link do redagowanego artykułu. Asystent AI może przygotować krótką promocję na podstawie przekazanych artykułów. Podpowiedzi nie dopisują się do wpisu samodzielnie.
- Lokalna kopia robocza, podgląd, formatowanie HTML, kategorie, tagi, zajawka i zdjęcie wyróżniające.
- Odczyt biblioteki mediów, wybór zdjęcia z telefonu i optymalizacja wymiarów przed wysłaniem.
- Katalog „Usługi w okolicy” jest stroną WordPress (ID 818) z kartami Gutenberg, a nie zwykłą kategorią wpisów. Aplikacja rozpoznaje istniejące nagłówki kart i zarządza własnym oznaczonym obszarem: dodaje/edytuje, ukrywa lub usuwa własne oferty. Ręczna zawartość strony pozostaje poza tym obszarem; zapis przerywa się, jeśli strona zmieniła się od ostatniego odczytu.
- Lokalne wskazówki redakcyjne, szablony i sugestie SEO działają bez usługi AI. Nie zastępują weryfikacji faktów historycznych.

## Kompilacja i podpis

Workflow **IL Redaktor Smart - Android APK** sprawdza JavaScript, testy kontraktowe, asercje integracji i zawartość APK, buduje APK debug oraz przechowuje artefakt przez 30 dni. APK z versionCode 19 używa osobnego pakietu `com.ispinalokalnie.redaktorsmart.publish194.test`, więc można go zainstalować obok poprzedniej wersji bez usuwania jej lokalnych szkiców. Zaloguj się raz w nowej instalacji. Aktualizacje tej nowej instalacji zachowujące dane wymagają podpisu stałym kluczem release.

Aby workflow budował aktualizowalny APK release, skonfiguruj w GitHub Actions secrets:

- `IL_RELEASE_KEYSTORE_B64` – keystore w Base64.
- `IL_RELEASE_KEY_ALIAS`.
- `IL_RELEASE_KEYSTORE_PASSWORD`.
- `IL_RELEASE_KEY_PASSWORD`.

Te sekrety muszą pochodzić z **istniejącego** prywatnego klucza podpisującego, który odpowiada zainstalowanemu APK. Nie dodawaj keystore ani haseł do repozytorium. Nie twórz nowego klucza tylko po to, by ominąć ten błąd — Android nadal odrzuciłby go jako aktualizację starej instalacji.

## Zakres testów

Test suite wykonuje przypadki logiki aplikacji z transportem WordPress zastąpionym mockiem. Nie zastępuje testu OAuth na telefonie ani zapisu/odczytu na koncie redaktora. Testy na urządzeniu Android 8–13 i bezpieczny szkic na koncie właściciela wymagają środowiska oraz autoryzacji redaktora. Nie publikuj treści testowych na żywo.

## AI

Backend źródłowy jest w `backend/`, ale nie jest jeszcze wdrożony i nie ma skonfigurowanego klucza. APK bez `IL_ASSISTANT_ENDPOINT` działa w trybie podstawowym, bez generatywnego modelu. Do uruchomienia potrzebny jest osobny klucz API Gemini, Vercel Function oraz Upstash Redis do limitowania zapytań; subskrypcja Gemini Pro nie jest kluczem do Gemini API. Nie umieszczaj klucza API ani prywatnego endpointu z uprawnieniami WordPressa w aplikacji.

Cennik Google dla `gemini-3.5-flash-lite` aktualnie podaje bezpłatny limit, a po jego przekroczeniu **0,30 USD za 1 mln tokenów wejściowych** i **2,50 USD za 1 mln tokenów wyjściowych**. W przypadku bezpłatnego poziomu Google może używać przekazanych promptów do ulepszania produktów; płatny poziom nie używa ich w tym celu. Vercel i Upstash są osobnymi usługami i mogą naliczać własne opłaty. Cenniki i limity mogą się zmieniać.

### Logowanie na Androidzie (1.4.1-test)

Poprzednia wersja uzywala `localhost`, ktory na czesci telefonow jest rozwiazywany do adresu IPv6 `::1`, mimo ze aplikacja nasluchiwala wylacznie na IPv4 `127.0.0.1`. W tej wersji rejestracja OAuth, adres przekierowania i serwer lokalny uzywaja tego samego jawnego adresu IPv4. Zmiana adresu automatycznie wymusza nowa rejestracje klienta OAuth na urzadzeniu, nie trzeba czyscic danych aplikacji. Jesli pojawi sie blad po autoryzacji, prosimy o zrzut z ekranu aplikacji (bez kodow i tokenow). Weryfikacja na fizycznym telefonie nadal jest wymagana.


## Diagnostyka logowania 1.5.0-test (31.10.2026)

Po potwierdzeniu logowania przez WordPress na telefonie Oukitel WP15 pojawiał się komunikat Brave `ERR_CONNECTION_REFUSED` pod `127.0.0.1:38477/callback?code=...`. Oznacza to, że lokalny serwer nie nasłuchiwał, gdy przeglądarka wracała z kodem. To możliwe, gdy Android ubije proces w tle. Wersja 1.5 usuwa serwer lokalny i przekierowuje wprost do aplikacji, za pomocą `com.ispinalokalnie.redaktorsmart.oauth:/callback`, który obsługuje AndroidManifest i MainActivity (także po odtworzeniu procesu). Przechowuje stan i weryfikator PKCE na czas autoryzacji, sprawdza state i czas ważności, wymienia code na token poza WebView. Jeśli WordPress.com nie pozwoli zarejestrować niestandardowego schematu, program pokaże błąd rejestracji zamiast udawać udane logowanie. Działanie end-to-end wymaga sprawdzenia na telefonie. Nie udostępniaj pełnego URL callback zawierającego `code`.

## Poprawka sesji MCP 1.5.1-test (31.10.2026)

Zrzut ekranu użytkownika z Androida pokazał: HTTP 400, `Invalid Request: Missing Mcp-Session-Id header`. Autoryzacja OAuth przekazała dostęp do WordPress.com, ale poprzedni kod natychmiast wywoływał `tools/call` bez MCP `initialize`. Zaimplementowano oficjalny handshake zgodny z WordPress MCP Adapter: `initialize` -> odczyt `Mcp-Session-Id` -> `notifications/initialized` -> `tools/call` z nagłówkiem i uzgodnioną wersją protokołu. Sesja jest trzymana wyłącznie w pamięci aplikacji, unieważniana po rozłączeniu lub zmianie tokenu i ponawiana po wygaśnięciu. Osobny schemat linku `com.ispinalokalnie.redaktorsmart.mcp151:/callback` unika konfliktu z wcześniej zainstalowaną wersją 1.5.

Test CI potwierdza kompilację, obecność cyklu sesji w kodzie i poprawność syntaktyczną. Realny dostęp do wpisów wymaga przetestowania na telefonie właściciela. Nie publikuj żadnych testowych treści bez osobnego potwierdzenia.

## Wyróżnienie ikony 1.5.2-test

Mała czerwona kropka w prawym górnym rogu ikony launchera Android odróżnia Redaktor Smart od aplikacji dla mieszkańców. Monogram IL pozostaje bez zmian na ekranie głównym i wewnątrz aplikacji. Modyfikowane są wyłącznie mipmap/ic_launcher i ic_launcher_round w czasie budowania APK. Osobny identyfikator APK pozwala zainstalować test obok wcześniejszych wersji o innych podpisach debug. Zmieniono również schemat logowania Android, aby system nie kierował odpowiedzi do starszej wersji.

## Poprawka OAuth 1.5.3 (31.10.2026)

Test na telefonie ujawnił błąd: `Required scope: global. Granted scope(s): auth`. Wersja 1.5.2 prosiła w OAuth 2.1 o zakres `auth`, który pozwala tylko na podstawowe uwierzytelnienie i nie uprawnia do WordPress.com MCP content-authoring. Wersja 1.5.3 prosi o `scope=global`, wymuszając ponowne logowanie za pomocą odrębnego identyfikatora APK i nowego adresu powrotu `com.ispinalokalnie.redaktorsmart.mcp153:/callback`. Dostęp `global` jest szeroki: może obejmować inne strony i dane tego konta WordPress.com. Aplikacja sama kieruje operacje wyłącznie do Ispina Lokalnie, ale przyznane uprawnienia są szersze niż jedna witryna. Użytkownik powinien świadomie zaakceptować ten zakres. Tokeny pozostają zaszyfrowane przy użyciu Android Keystore, bez zapisywania haseł.

CI sprawdza obecność `scope=global`, zgodność callback, generowanie ikony z czerwoną kropką i kompilację APK. Ostateczne logowanie i odczyt treści muszą zostać przetestowane na telefonie. Nie scalać PR do main bez udanego testu.
