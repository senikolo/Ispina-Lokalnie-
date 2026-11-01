# IL Redaktor Smart — Master Prompt QA

Używaj tego promptu po każdej aktualizacji aplikacji. Uzupełnij sekcję **Dane przebiegu** przed testami i zachowaj w raporcie dowody dla każdego statusu.

---

## Rola i cel

Pracujesz jako zespół QA dla aplikacji Android **IL Redaktor Smart**, redaktora portalu [Ispina Lokalnie](https://ispinalokalnie.wordpress.com/). Oceniasz kod, gotowy APK i — jeśli udostępniono bezpieczny dostęp — rzeczywistą integrację z WordPress.com.

Twoim celem jest wykryć regresje, potwierdzić działanie funkcji oraz przygotować powtarzalny raport. Nie twórz aplikacji od nowa. Sprawdź istniejące rozwiązanie i poprawki w bieżącej gałęzi.

### Dane przebiegu

- Commit / gałąź: `[uzupełnij]`
- Wersja i versionCode: `[uzupełnij]`
- Data, urządzenie lub emulator, wersja Androida: `[uzupełnij]`
- Rodzaj API: `staging / WordPress.com prod tylko odczyt / WordPress.com prod ze szkicem QA`
- Build APK i fingerprint certyfikatu: `[uzupełnij]`
- Użyte narzędzia i wersje: `[uzupełnij]`

## Zasady dowodów i bezpieczeństwa

1. Każdy przypadek otrzymuje dokładnie jeden status: **PASS**, **FAIL** albo **BLOCKED**.
2. **PASS** oznacza faktyczne wykonanie wszystkich kroków i zapisany dowód: wynik testu, log bez danych poufnych, zrzut ekranu, identyfikator wpisu albo hash APK. Samo istnienie kodu, udana kompilacja lub odpowiedź HTTP nie wystarcza.
3. **FAIL** wymaga odtworzenia błędu, kroków, wyniku oczekiwanego i uzyskanego. Wskaż commit oraz plik lub log, który pozwala go sprawdzić.
4. **BLOCKED** podaj, gdy brakuje urządzenia, emulatora, konta, zgody OAuth, dostępu do API albo sekretu CI. Napisz dokładnie, co trzeba udostępnić, aby odblokować test.
5. Nie wpisuj haseł, kodów OAuth, tokenów ani adresu callback do raportu, zrzutu ekranu, konsoli lub logów. Nie wklejaj ich do promptu.
6. Preferuj staging i konto testowe. Na żywym portalu wykonuj odczyty i operacje niepubliczne. Dozwolony test zapisu to wyłącznie jednoznacznie nazwany szkic QA, po świadomym potwierdzeniu operatora; zweryfikuj go ponownym odczytem. Nie publikuj publicznie treści testowej i nie twórz szkiców automatycznie. Nie usuwaj istniejących treści ani wpisów, których nie utworzył ten przebieg.
7. Po niejednoznacznym timeoutcie nie ponawiaj operacji zapisu. Najpierw odczytaj wpisy po ID lub tytule, aby wykluczyć duplikat.
8. Google Sign-In nie zastępuje zgody OAuth WordPress.com. Zalogowanie jest PASS dopiero po potwierdzeniu dostępu do właściwej witryny.
9. Nie oznaczaj testu urządzenia, produkcyjnego API, kompatybilności ani wydajności jako PASS na podstawie mocka.

## Procedura przebiegu

### A. Inwentaryzacja i build

1. Sprawdź branch, commit, `git status`, `versionName`, `versionCode`, `applicationId`, `minSdk`, `targetSdk`, manifest, uprawnienia oraz URI OAuth.
2. Zidentyfikuj wszystkie źródła danych i endpointy. Potwierdź, że staging nie wskazuje na żywy portal.
3. Uruchom istniejące testy. Dla projektu IL Redaktor Smart:

   ```sh
   node --check il-redaktor-android/tests/publishing.test.cjs
   node --test il-redaktor-android/tests/publishing.test.cjs
   cd il-redaktor-android/backend && npm ci --ignore-scripts --no-audit --no-fund && npm run typecheck
   cd .. && gradle --no-daemon :app:assembleDebug
   ```

4. Zweryfikuj APK: plik istnieje i nie jest pusty, package/version są oczekiwane, `apksigner verify` przechodzi, zasoby i monogram IL są w pakiecie. Zapisz SHA-256 i certyfikat. Nie buduj ani nie podmieniaj klucza release.
5. Zainstaluj APK na emulatorze lub urządzeniu. Jeśli certyfikat jest inny niż w poprzednim APK, test aktualizacji oznacz FAIL lub BLOCKED z uzasadnieniem; nie usuwaj danych użytkownika bez zgody.

### B. Testy automatyczne i przegląd kodu

1. **Jednostkowe:** walidatory, konwersję tekstu/HTML, generowanie lokalnej kopii, kategorie i tagi, rozpoznawanie polskich odmian „Ispina / w Ispinie / do Ispiny / z Ispiny / ispiński”, daty publikacji, URL-e, parser usług i wyjściowe stany operacji.
2. **Integracyjne:** OAuth DCR + PKCE, zgodność redirect URI między DCR, authorize, token exchange i Android Manifest; obsługa cold start/onNewIntent; zapis tokenu w Android Keystore; odświeżanie tokenu; MCP `initialize` → `notifications/initialized` → `tools/call`; odczyt po zapisie.
3. **Kontrakt WordPress:** sprawdź, że operacje dotyczą site ID `252442125` / `ispinalokalnie.wordpress.com`, statusy są jawne, pola wpisu są mapowane, kategorie/tagi/media używają rzeczywistych ID, a zapis nie jest automatycznie ponawiany.
4. **Statyczne bezpieczeństwo:** szukaj sekretów i tokenów w repo, build config, logach, URL-ach, WebView bridge, niezaszyfrowanych SharedPreferences, nadmiernych uprawnień, niezaufanych przekierowań, otwartego cleartext HTTP oraz publicznych operacji usuwania bez potwierdzenia.
5. **Backend AI:** sprawdź, że endpoint wymaga HTTPS, weryfikuje sesję WordPress, stosuje timeout i rate limit, zwraca walidowany JSON, nie zapisuje tokenu/promptu do logów i odmawia działania bez wymaganych konfiguracji. W raporcie podaj kosztowy wymóg modelu AI i dostępny wariant bezpłatny.

### C. Testy na Androidzie — Android 8, 10, 11 i 13

Na każdym dostępnym poziomie API wykonaj instalację, cold start, powrót OAuth, edycję, podgląd, nawigację i powrót z procesu tła. Android 11 jest obowiązkowym priorytetem. Sprawdź rozmiar tekstu 100% i maksymalny, tryb ciemny, rotację, klawiaturę, systemowe ograniczenie animacji, tryb oszczędzania baterii oraz urządzenie bez wibracji.

Testuj ekrany z czytnikiem TalkBack lub równoważnym sprawdzeniem etykiet, obsługę jedną ręką, stany disabled/busy/error/success, semantyczne `aria-live`, kontrast i brak obcięcia przy dużej czcionce. Mierz zimny start, czas ekranu, ANR, zużycie pamięci i CPU na starszym urządzeniu; odnotuj narzędzie i metodę pomiaru.

### D. OAuth i sesja — na urządzeniu

1. Rozpocznij logowanie z aplikacji. Zaloguj się do **WordPress.com**; jeśli ekran WordPress.com oferuje konto Google, wybierz je w tej samej autoryzacji.
2. Zatwierdź wyłącznie świadomie zaakceptowane uprawnienia. Sprawdź nazwę właściwej witryny i ostrzeżenie o zakresie `global`.
3. Po callbacku sprawdź ekran sukcesu dopiero po odczycie wpisów przez MCP. Zweryfikuj status połączenia i akcję „Sprawdź dostęp do wpisów”.
4. Odtwórz: zły lub spóźniony `state`, odmowę zgody, brak kodu, brak przeglądarki, utratę sieci podczas exchange, ubity proces, ponowne uruchomienie, wygasły access token, wygasły refresh token, wylogowanie, brak uprawnienia do witryny i ponowne logowanie.
5. Upewnij się, że błąd mówi, co zrobić dalej, nie pokazuje tokenu ani callbacku, a poprawny stan oczekujący OAuth nie znika po sfałszowanym callbacku.

### E. WordPress.com — wyłącznie bezpieczne operacje

1. Przed zapisem sprawdź stronę docelową i jej typ. Nie zakładaj, że „Usługi” to kategoria wpisów; zbadaj stronę, custom post type, bloki lub używaną wtyczkę.
2. Przeprowadź odczyt witryny, wpisów, kategorii, tagów, mediów i katalogu Usługi. Oznacz każde API jako odczyt/zapis i podaj właściwy site ID.
3. Stwórz najwyżej jeden opisany szkic testowy na wyraźne polecenie operatora; sprawdź zwrócone ID, odczytaj wpis ponownie przez API i potwierdź `status`, tytuł, treść, datę, kategorie, tagi i media. Nie usuwaj go automatycznie.
4. Publikację publiczną sprawdzaj na staging. Potwierdź `status=publish`, ID i rzeczywisty link z WordPressa. Otwórz link w przeglądarce. Link zastępczy może być użyty tylko do witryny IL i tylko z niepustym ID.
5. Harmonogram sprawdź na staging: data w przyszłości, status `future`, ponowny odczyt `date_gmt`/`date` zgodny z wybraną chwilą, bez fałszywego publicznego linku.
6. Katalog usług sprawdź na testowym wpisie. Weryfikuj nazwę, opis, telefon, kategorię, zdjęcie, widoczność, edycję, duplikat, ukrywanie i usunięcie własnej karty. Zewnętrzna, ręczna zawartość strony musi pozostać nienaruszona.

### F. Scenariusze błędów i regresji

Wykonaj co najmniej 30 przypadków z poniższej tabeli. Zapisz status każdego — również wtedy, gdy narzędzia nie są dostępne.

| ID | Scenariusz | Dowód wymagany dla PASS |
|---|---|---|
| QA-01 | Składnia JS i testy logiki redaktora | wynik test runnera |
| QA-02 | Walidacja pustego tytułu/treści | brak żądania POST |
| QA-03 | HTML i tekst z nagłówkiem/akapitem | poprawne pole `content` |
| QA-04 | Zajawka i właściwa kategoria | body żądania/odczyt wpisu |
| QA-05 | Tagi istniejące i nowe | ID tagów zwrócone przez WP |
| QA-06 | Brak dostępu do tagów i odmowa kontynuacji | brak POST artykułu |
| QA-07 | Media ID jako obraz wyróżniający | odczyt `featured_media` |
| QA-08 | Zapis szkicu | status `draft` po ponownym GET |
| QA-09 | Publikacja na staging | status `publish` i rzeczywisty permalink |
| QA-10 | Brak potwierdzenia GET po POST | błąd, żadnego ponownego POST |
| QA-11 | Nieoczekiwany status serwera | brak fałszywego sukcesu |
| QA-12 | Timeout zapisu / utrata internetu | brak auto-retry; instrukcja sprawdzenia ID |
| QA-13 | Dwukrotne szybkie naciśnięcie Publikuj | dokładnie jedno POST |
| QA-14 | Odmowa potwierdzenia publikacji | zero żądań sieciowych |
| QA-15 | Zaplanowanie z poprawną datą | `future`, data i odczyt zweryfikowane |
| QA-16 | Brak daty / data zbyt bliska | zero POST, komunikat zrozumiały |
| QA-17 | Inna data zwrócona przez WordPress | błąd `TERMIN NIEPOTWIERDZONY` |
| QA-18 | Edycja istniejącego wpisu | POST `posts/{id}`, brak duplikatu |
| QA-19 | Weryfikacja ostatniego zapisu | samo GET, bez zapisu |
| QA-20 | Prawdziwy permalink artykułu | otwiera post IL, nigdy pusty URL |
| QA-21 | Utrata sieci przed i po stronie serwera | uczciwy status niepewny/błąd |
| QA-22 | Przerwanie operacji i anulowanie | spinner kończy się, stan nie kłamie |
| QA-23 | Zamknięcie i odtworzenie szkicu lokalnego | treść i termin zachowane |
| QA-24 | OAuth poprawny callback i PKCE | dostęp do właściwej witryny |
| QA-25 | OAuth zły `state`, odmowa i wygasły kod | brak tokenu, jasny błąd |
| QA-26 | Powrót z przeglądarki: cold start / onNewIntent | stan sesji odzyskany |
| QA-27 | Token wygasły / odświeżenie odrzucone | odświeżenie lub ponowne logowanie |
| QA-28 | Ponowne uruchomienie i ponowna instalacja | stan/utrata stanu opisana; bez wycieku |
| QA-29 | Publiczna strona Usługi — parser struktury | wszystkie legacy karty rozpoznane |
| QA-30 | Edycja/ukrycie/usunięcie karty własnej | odczyt po zapisie, legacy HTML nienaruszone |
| QA-31 | Powtórne dodanie istniejącej firmy | wykrycie możliwego duplikatu |
| QA-32 | Zdjęcie z telefonu i biblioteki | bezpieczny HTTPS URL i poprawne ID |
| QA-33 | Android 8 / 10 / 11 / 13 | wynik z rzeczywistego urządzenia/API |
| QA-34 | Rotacja, duża czcionka, klawiatura | brak utraty treści/uciętych akcji |
| QA-35 | Tryb ciemny i ruch ograniczony przez system | render bez błędów i zbędnych animacji |
| QA-36 | Tryb oszczędzania baterii | animacje/efekty są ograniczone |
| QA-37 | Wibracje: off/delikatne/mocne | ustawienie pamiętane; brak haptyki co tap |
| QA-38 | Brak internetu i wolna sieć | timeout, ponowienie ręczne, bez duplikatu |
| QA-39 | APK: package, version, podpis, instalacja | `aapt`, `apksigner`, instalacja i start |
| QA-40 | Aktualizacja nad poprzednim APK | zgodny package i fingerprint certyfikatu |
| QA-41 | Logi, storage i uprawnienia | brak sekretów, minimum uprawnień |
| QA-42 | AI, lokalne sugestie i brak backendu | wyraźna informacja o koszcie/ograniczeniu |

## Raport wyjściowy

Zapisz do `QA_AUDIT_<wersja>_<data>.md`:

1. commit, wersję APK, urządzenia, narzędzia i źródło API;
2. tabelę wszystkich 42+ scenariuszy z PASS/FAIL/BLOCKED, uzasadnieniem i dowodem;
3. błędy, przyczynę, zmienione pliki, poprawki i testy regresyjne;
4. osobne listy „zweryfikowano na żywym API”, „mock/emulator” oraz „niezweryfikowano”;
5. SHA-256 i certyfikat APK, instalację/aktualizację oraz link do istniejącego artefaktu;
6. pozostałe blokady oraz dokładny wymagany dostęp/autoryzację.

Nie kończ raportu słowem „gotowe”, jeśli krytyczne logowanie lub publikowanie są BLOCKED. Wyraźnie odróżnij gotowość kodu i testy kontraktowe od potwierdzenia na telefonie i na koncie WordPress.com.
