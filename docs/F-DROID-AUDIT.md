# Audyt przygotowania Ispina Lokalnie do F-Droida

**Data audytu:** 10–11 października 2026 r.  
**Status:** NIEGOTOWA DO ZGŁOSZENIA  
**Wersja docelowa:** 1.83.20 (versionCode 18320)  
**Identyfikator docelowy:** `com.ispina.mldcheck`  
**Licencja wybrana dla kodu, do którego autor ma prawa:** `GPL-3.0-only`

## Wniosek

Nie znaleziono kompletnego projektu źródłowego wersji 1.83.20. Znaleziono starszy, rzeczywisty projekt Androida 1.80.66 oraz osobny prototyp aplikacji 2.0.x. Starszy projekt może pomóc jako punkt odniesienia, ale nie jest źródłem bieżącego wydania i nie wolno publikować go jako jego zamiennika. Nie przeprowadzono kompilacji aktualnej aplikacji ani pełnego audytu praw do zasobów.

## Źródła i kompilacja — BLOKER

- Repozytorium zawiera interfejs JavaScript, moduły, testy i narzędzia. `planner/build_motion_apk.py` wymaga istniejącego APK przez `--base`; nie buduje aplikacji od zera.
- Archiwum źródeł 1.83.19 zostało rozpakowane i zinwentaryzowane: zawiera interfejs JavaScript, moduły i testy, narzędzia pomocnicze oraz bazowy APK R14.3. Nie zawiera projektu Gradle ani źródeł Java/Kotlin aplikacji; nie jest pełnym projektem natywnym.
- Wcześniejsza próba dekompilacji APK narzędziem JADX wygenerowała około 1 992 pliki Java, ale zgłosiła 83 błędy, głównie przy Kotlin/coroutines i bibliotekach. Utworzony szkielet Gradle miał błędny `applicationId` (`com.ispina.lokalnie`), pustą listę zależności i brak wrappera; wygenerowane pliki były materiałem diagnostycznym, a nie kompilowalnym źródłem wydania.
- Odnaleziono starszy projekt Androida 1.80.66 osadzony w archiwalnym notebooku budowania. Zawierał Gradle, moduł aplikacji, pliki Java `MainActivity` i synchronizacji rozkładów oraz zasoby. Jego archiwum przechodzi test ZIP i zgadza się z sumą SHA-256 wpisaną w notebooku.
- Ten projekt ustawia `applicationId/namespace com.ispina.lokalnie`, versionCode 18066 i versionName 1.80.66. Wersja docelowa ma identyfikator `com.ispina.mldcheck`. README odzyskanego projektu opisuje uproszczoną wersję bez radia i ostrzega, że kompilacja debug może wymagać odinstalowania istniejącej aplikacji. To nie jest bezpieczna podmiana wersji 1.83.20.
- Znaleziono też osobne notebooki z prototypami 2.0.x. Nie potwierdzono, że są źródłem aktualnego pakietu; nie zostały użyte do zastąpienia bieżącej aplikacji.
- W tym środowisku dostępny jest JDK 17, ale nie ma Gradle ani Android SDK. Nie wykonano kompilacji źródeł 1.80.66 ani 1.83.20.
- Nie ma jeszcze wiarygodnej recepty F-Droid dla wersji 1.83.20. Nie wolno umieszczać wcześniejszego APK w procesie kompilacji.

## Licencje i prawa do zasobów — NIEZWERYFIKOWANE

Dodano pełny tekst GPLv3. Oznaczenie `GPL-3.0-only` dotyczy wyłącznie kodu, do którego autor ma prawa i który udostępnia na tej licencji. Sam plik `LICENSE` nie nadaje praw do cudzych materiałów.

Przed zgłoszeniem trzeba udokumentować właściciela, licencję i prawo do redystrybucji zdjęć, ilustracji, ikon, fontów, rozkładów MLD i gminnych, transmisji radiowych i telewizyjnych, treści aktualności i historii oraz danych i usług pogodowych. Dostępność materiału w Internecie nie oznacza zgody na jego kopiowanie ani objęcia go GPL.

## Biblioteki, reklamy i śledzenie — WSTĘPNIE SPRAWDZONE, NIEZAMKNIĘTE

Statyczny przegląd APK wskazuje na AndroidX Core, WorkManager, Room, Kotlin/kotlinx.coroutines, Guava i adnotacje JetBrains. Brak projektu źródłowego nie pozwala ustalić pełnych wersji, grafu zależności i licencji. W przeglądzie nie znaleziono znanych sygnatur SDK AdMob, Firebase ani analityki; to nie wyklucza własnego śledzenia, kodu dynamicznego ani skryptów strony ładowanej z sieci. Przed zgłoszeniem potrzebny jest audyt pełnych źródeł i zachowania aplikacji.

## Uprawnienia, sieć i usługi — WSTĘPNIE USTALONE Z APK

Manifest wydania 1.83.20 deklaruje `INTERNET`, `ACCESS_NETWORK_STATE`, `WAKE_LOCK`, `RECEIVE_BOOT_COMPLETED`, `FOREGROUND_SERVICE`, `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE_DATA_SYNC`, `VIBRATE` i `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, a także własne uprawnienie odbiornika chronione podpisem aplikacji. Nie znaleziono uprawnień do lokalizacji, SMS-ów, telefonu ani kontaktów. Manifest ustawia `minSdk 23`, `targetSdk 34` i wyłącza cleartext HTTP. Wymaga to jeszcze kontroli po odtworzeniu manifestu źródłowego.

W statycznie znalezionych ścieżkach i zasobach występują m.in. Open-Meteo (pogoda), portal/REST WordPress (aktualności i artykuły), `gtfs.kolejemalopolskie.com.pl` (dane GTFS), wdrożony Google Apps Script (rozkład gminny), API rozkładów Polskiego Radia i RMF oraz strumienie audio/wideo wskazane w treści portalu. Nie zweryfikowano warunków tych usług, praw do danych i transmisji, zakresu wysyłanych danych ani zachowania przy błędach sieci.

## Aktualizacje — POTWIERDZONY WŁASNY MECHANIZM

W zasobach APK znaleziono mechanizm sprawdzania wydań GitHub, który filtruje pakiet, kanał i certyfikat, a następnie oferuje użytkownikowi pobranie APK zewnętrznym kanałem. Nie znaleziono automatycznej instalacji APK. Wariant F-Droid powinien wyłączyć lub zastąpić ten przepływ, aby aktualizacje odbywały się przez klienta F-Droid; wariant produkcyjny musi pozostać osobny do czasu ustalenia bezpiecznej migracji.

## Podpis i migracja — CERTYFIKAT ZNANY, KLUCZ NIEDOSTĘPNY

Certyfikat APK ma odcisk SHA-256 `6D:75:BA:F7:F4:40:2F:F9:3C:93:50:5F:D6:D7:A5:C0:EA:B3:D1:C7:C2:AA:2A:39:EE:B5:E4:AE:16:6F:62:5F`, zgodny z odciskiem zapisanym w zasobie APK. Nie odzyskano prywatnego klucza.

Zachowanie `com.ispina.mldcheck` nie wystarczy do aktualizacji instalacji — wymagany jest zgodny podpis i wyższy `versionCode`. Jeśli uda się odtworzyć źródła i uzyskać powtarzalny build, można sprawdzić zgodność z APK autora. Nie zostało to jeszcze wykazane. W przeciwnym razie potrzebny będzie jawny plan migracji, obejmujący lokalne dane użytkowników.

## Testy i metadane — NIEZAKOŃCZONE

Repozytorium opisuje 84 testy JavaScript Planera. Kontrola GitHub Actions dla roboczego PR przeszła, ale sprawdza planer i nie buduje natywnej aplikacji Android. Nie wykonano `fdroid lint`, `fdroid build`, instalacji APK ani testów uruchomienia na urządzeniu.

Poniższe metadane są szkicem. Nie dodano jeszcze właściwego wpisu do `fdroiddata`, ikony ani zrzutów ekranu z potwierdzonym prawem do publikacji.

## Robocze metadane

| Pole | Wartość robocza |
|---|---|
| Nazwa | Ispina Lokalnie Live |
| Application ID | `com.ispina.mldcheck` |
| Autor | Sławomir Franciszek Kozak (`senikolo`) |
| Licencja kodu | `GPL-3.0-only` |
| Strona | https://ispinalokalnie.com |
| Kod | https://github.com/senikolo/Ispina-Lokalnie- |
| Zgłoszenia | https://github.com/senikolo/Ispina-Lokalnie-/issues |
| Wersja | 1.83.20 (18320) |

### Krótki opis — polski (roboczy)

Planer komunikacji i lokalne informacje o Ispinie.

### Opis — polski (roboczy)

Ispina Lokalnie Live łączy planer podróży, rozkłady komunikacji i materiały o Ispinie. Aplikacja zawiera planer połączeń MLD i komunikacji gminnej, odjazdy, aktualności, historię i ciekawostki historyczne, pogodę oraz czytelny tryb Senior. Część funkcji wymaga połączenia z Internetem.

### Short description — English (draft)

Local transport planner and information about Ispina.

### Description — English (draft)

Ispina Lokalnie Live brings together a journey planner, local transport schedules and information about Ispina, Poland. It includes planning for MLD and municipal services, departures, local news, village history and historical facts, weather, and a senior-friendly display. Some features require an Internet connection.

### Zmiany w 1.83.20 — polski (robocze)

Dodano pozycję „Ciekawostki historyczne”. Po wybraniu celu Planer automatycznie wyszukuje połączenia na teraz. Uporządkowano też menu, nazwy przystanków i informacje o wersji.

### Changes in 1.83.20 — English (draft)

Added a “Historical facts” section. After a destination is selected, the journey planner automatically searches for connections departing now. The menu, stop names and version information were also refined.

## Co jest potrzebne przed zgłoszeniem

1. Odzyskać i porównać natywny kod wersji 1.83.20 lub wykazać, że starsza baza 1.80.66 może być bezpiecznie zaktualizowana bez utraty funkcji.
2. Ustawić docelowe ID `com.ispina.mldcheck` i zbudować aktualny wariant od zera bez APK jako składnika.
3. Zweryfikować zależności, licencje, uprawnienia, domeny, trackery, reklamy, usługi, transmisje i źródła danych.
4. Udokumentować prawa do materiałów lub zastąpić je legalnie redystrybuowanymi odpowiednikami.
5. Przygotować wariant F-Droid bez własnego pobierania APK oraz sprawdzić podpis i migrację.
6. Uruchomić testy instalacji, działania i regresji kluczowych funkcji.
7. Dodać metadane, legalne materiały graficzne i przejść `fdroid lint` oraz `fdroid build`.

Zgłoszenie do oficjalnego repozytorium F-Droid nie zostało wysłane.

## Oficjalne źródła F-Droid

- [Inclusion Policy](https://f-droid.org/en/docs/Inclusion_Policy/)
- [FAQ dla autorów aplikacji](https://f-droid.org/en/docs/FAQ_-_App_Developers/)
- [Submitting to F-Droid: Quick Start Guide](https://f-droid.org/en/docs/Submitting_to_F-Droid_Quick_Start_Guide/)
- [Build Metadata Reference](https://f-droid.org/en/docs/Build_Metadata_Reference/)
- [Reproducible Builds](https://fdroid.gitlab.io/jekyll-fdroid/en/docs/Reproducible_Builds/)


## Dodatkowa weryfikacja wydania 1.83.20 — 10 października 2026

- Widok GitHub Releases sprawdzony 10 października 2026 r. wskazuje `v1.83.20` jako najnowsze wydanie. APK wydania ma opublikowaną sumę SHA-256 `c9c0a0107310c3229b421a87832de1f1fc738b07db7ab1dc41eea2ead417e664`.
- Drzewo tagu `v1.83.20` zawiera katalog `planner/`, workflow oraz pliki README i XML. Nie zawiera `gradlew`, `settings.gradle`, `build.gradle`, modułu `app/` ani źródeł Java/Kotlin. To potwierdza, że automatyczny snapshot źródeł GitHuba nie jest samodzielnym projektem do zbudowania aplikacji.
- `planner/README.md` opisuje budowanie zmian interfejsu przez skrypt wymagający `--base` z istniejącą APK R14.3; podpisanie wymaga osobnej prywatnej konfiguracji. Taki skrypt nie jest receptą F-Droida, która kompiluje aplikację od zera.
- Główny `AndroidManifest.xml` w tagu wymienia `INTERNET` i `ACCESS_NETWORK_STATE`, lecz README oznacza pliki XML jako historyczne zasoby, a manifest nie należy do kompletnego projektu Gradle. Nie można na tej podstawie potwierdzić uprawnień faktycznej APK 1.83.20.
- Kontrolowana analiza statyczna odzyskanej APK pozwoliła wstępnie odczytać manifest, bibliotekę zależności, domeny i certyfikat; ich ograniczenia opisano w sekcjach poniżej. Nie zastępuje ona pełnej kontroli źródeł, warunków usług ani testów działania.


## Dodatkowo sprawdzone archiwa źródłowe

### Projekt 1.80.60 — Java, interfejs i rozkłady

- Archiwum ZIP osadzone w notebooku ma poprawną sumę SHA-256 zgodną z notebookiem (81e5ea146939e74045e7ef0457d6a4b39e428ff49215889ab0383e36126e172e) i przechodzi test integralności ZIP.
- Zawiera projekt Gradle, MainActivity.java, WebView, interfejs JavaScript, lokalne pliki danych i kod synchronizacji. Parsowanie GTFS obejmuje linie MLD A4/A7/A65. Linia gminna jest pobierana z adresu wdrożonego Google Apps Script, z lokalnym JSON jako awaryjnym źródłem. Pogoda jest pobierana z Open-Meteo.
- applicationId i namespace to pl.ispina.lokalnie, versionCode to 18060, a versionName to 1.80.60. README określa aplikację jako uproszczoną wersję bez radia. Nie jest to bieżący projekt com.ispina.mldcheck ani pełna funkcjonalność IL.
- Manifest tego archiwum deklaruje INTERNET i ACCESS_NETWORK_STATE. To nie potwierdza uprawnień ani ruchu sieciowego APK 1.83.20. Zewnętrzny skrypt Google wymaga osobnej oceny dostępności, warunków i przepływu danych.

### Projekt 2.0.0 — odrębny prototyp Kotlin/Compose

- Archiwum zawiera źródła Kotlin/Compose, skrypty Gradle, ekran portalu otwieranego w WebView, pogodę z Open-Meteo i odtwarzanie radia przez Media3. Test integralności ZIP przeszedł; archiwum nie jest związane z tagiem v1.83.20.
- Ustawia applicationId pl.ispinalokalnie.app2026, versionCode 20000 i versionName 2.0.0. Manifest deklaruje dostęp do Internetu i sieci, usługę odtwarzania w tle, blokadę uśpienia oraz powiadomienia.
- README wymienia aktualności i rozkłady autobusów jako funkcje do ewentualnego dodania. Projekt nie odtwarza więc Planera i funkcji transportowych opisanych dla IL. Nie może być bezpiecznym zamiennikiem obecnej aplikacji.
- Bezpośrednie zależności obejmują m.in. AndroidX/Compose, Retrofit, OkHttp, kotlinx.serialization, DataStore, Media3, Guava i Accompanist. Wymagają one odrębnego sprawdzenia źródeł, wersji i licencji. RadioConfig.kt wskazuje zewnętrzny stream Polskiego Radia; prawo do jego redystrybucji i trwałość adresu nie zostały potwierdzone.
- Archiwum nie zawiera pliku LICENSE; w katalogu Gradle jest plik gradle-wrapper.properties, lecz nie ma skryptów gradlew ani gradlew.bat i pliku wrapper JAR. Nie wykonano kompilacji tego prototypu.

Nie dodano tych archiwów do repozytorium ani nie przypisano im licencji GPL. Wymagają potwierdzenia praw do kodu, grafik, fontów, danych, transmisji i usług przed wykorzystaniem ich fragmentów.

## Wymagania F-Droid potwierdzone w aktualnej dokumentacji

- Oficjalna [polityka włączenia](https://f-droid.org/docs/Inclusion_Policy/) wymaga wolnego oprogramowania i dostępnego źródła, z którego F-Droid może zbudować aplikację. Zależności binarne muszą pochodzić z kompilacji źródłowej lub z dozwolonych zaufanych repozytoriów. Wszystkie grafiki i fonty muszą mieć udokumentowane prawa do redystrybucji.
- Polityka zabrania własnościowych bibliotek reklamowych i śledzących. Dodatkowe pliki wykonywalne, w tym mechanizmy autoaktualizacji APK, wymagają wyraźnej zgody opt-in i ostrzeżenia, że omijają kontrole F-Droida. Aplikacja opisująca funkcje musi faktycznie je implementować; opakowanie strony WWW powinno wnosić natywne funkcje.
- [Opis Anti-Features](https://f-droid.org/docs/Anti-Features/) wymaga przejrzystego wskazania usług sieciowych i innych cech ograniczających prywatność lub swobodę wyboru. Użycie zewnętrznego API samo w sobie nie przesądza wyniku audytu, ale trzeba opisać jego rolę, dane i warunki.
- [Dokumentacja powtarzalnych kompilacji](https://f-droid.org/docs/Reproducible_Builds/) opisuje cel F-Droid polegający na sprawdzaniu, czy kompilacja ze źródeł odtwarza opublikowany APK. Dla IL nie można jeszcze porównać artefaktów, bo brak zweryfikowanych źródeł właściwego wydania.


## Dodatkowy projekt archiwalny 1.80.19 i kontrola APK

- Archiwum projektu 1.80.19 osadzone w notebooku przechodzi test ZIP, a jego SHA-256 zgadza się z sumą podaną w notebooku. Ustawia `applicationId pl.ispina.lokalnie`, versionCode 18019 i versionName `1.80.19-clean-details`. README opisuje uproszczoną wersję bez radia: pobiera pogodę z Open-Meteo i otwiera portal w WebView. Nie zawiera źródeł wersji 1.83.20 ani docelowego identyfikatora pakietu; archiwum nie zawiera pliku licencji.
- W wydaniu `v1.83.20` widoczny jest jeden dołączony APK oraz automatyczne snapshoty źródeł ZIP/TAR, bez osobnego natywnego archiwum projektu. Próba pobrania APK w przeglądarce Work przekroczyła limit czasu; APK odzyskano później lokalnie. Jego SHA-256 i warstwę DEX zweryfikowano, a statyczną analizę manifestu, bibliotek, domen i certyfikatu opisano w dalszej części. Nie zastępuje ona kontroli źródeł ani pełnego audytu zachowania.
## Weryfikacja odzyskanego APK i warstwy DEX — 11 października 2026
Odzyskana lokalnie APK wydania 1.83.20 ma SHA-256 c9c0a0107310c3229b421a87832de1f1fc738b07db7ab1dc41eea2ead417e664, zgodny z sumą opublikowaną w README repozytorium. Wszystkie sześć plików DEX z tej APK jest bajt w bajt identycznych z plikami DEX w odzyskanej bazowej APK R14.3.
Tabela klas DEX potwierdza obecność natywnych komponentów, których źródeł nie ma w kandydacie F-Droid: HomeActivity, MainActivity, sync/MldRealtime, sync/NativeReader, sync/NotificationDiagnostics, klasy pakietów news i waste, tv/TvFullscreen oraz klasy pakietu radio. Kandydat zawiera obecnie siedem plików Java, w tym starszy MainActivity i uproszczony NativeScheduleBridge; nie zawiera wymienionych klas ani ich źródeł.
To była kontrola tabel klas i nazw metod DEX, a nie odzyskanie oryginalnego kodu Java/Kotlin. Nie potwierdza licencji, zależności ani zachowania aplikacji. Zgodność DEX z bazową APK nie czyni z kandydata 1.80.66 źródeł wydania 1.83.20.
**Wniosek pozostaje bez zmian: nie wysyłać zgłoszenia do fdroiddata i nie przedstawiać kandydata jako źródeł APK 1.83.20.** Potrzebny jest pełny projekt źródłowy albo nowa wersja FOSS z jasno opisanym zakresem, buildem od zera i weryfikacją praw do wszystkich materiałów.


## Dodatkowa statyczna inspekcja APK — 11 października 2026


- Manifest APK 1.83.20 deklaruje uprawnienia INTERNET, ACCESS_NETWORK_STATE, WAKE_LOCK, RECEIVE_BOOT_COMPLETED, FOREGROUND_SERVICE, POST_NOTIFICATIONS, FOREGROUND_SERVICE_DATA_SYNC, VIBRATE i FOREGROUND_SERVICE_MEDIA_PLAYBACK, a także własne uprawnienie odbiornika chronione podpisem. Statyczna inspekcja nie wykazała uprawnień do lokalizacji, SMS-ów, telefonu ani kontaktów; minSdk to 23, targetSdk 34, a cleartext HTTP jest wyłączony.
- W DEX i zasobach APK rozpoznano AndroidX Core, WorkManager, Room, Kotlin/kotlinx.coroutines, Guava i adnotacje JetBrains. Nie znaleziono znanych sygnatur SDK AdMob, Firebase ani analityki; wynik nie wyklucza niestandardowego trackingu, dynamicznego kodu ani skryptów sieciowych.
- W statycznych zasobach występują Open-Meteo, WordPress REST, gtfs.kolejemalopolskie.com.pl, wdrożony Google Apps Script, API rozkładów Polskiego Radia i RMF oraz odnośniki do strumieni audio/wideo. Prawa do transmisji i danych, warunki usług oraz zakres wysyłanych danych pozostają do sprawdzenia.
- APK zawiera sprawdzanie wydań GitHub i oferuje ręczne pobranie APK po filtrze pakietu, kanału i certyfikatu; nie znaleziono automatycznej instalacji.
- Odcisk SHA-256 certyfikatu podpisującego APK: `6D:75:BA:F7:F4:40:2F:F9:3C:93:50:5F:D6:D7:A5:C0:EA:B3:D1:C7:C2:AA:2A:39:EE:B5:E4:AE:16:6F:62:5F`. Prywatny klucz nie został odzyskany.

Są to ustalenia ze statycznej inspekcji APK, nie pełny audyt źródeł, ruchu sieciowego ani praw do treści.


## Uzupełnienie po sprawdzeniu dostępnych archiwów — 11 października 2026

- Archiwum IL-1.83.19-ZRODLA-I-TESTY.zip zostało rozpakowane i zinwentaryzowane. Zawiera interfejs JavaScript, moduły i testy, narzędzia pomocnicze oraz bazowy plik base-R14-3.apk. Nie zawiera projektu Gradle ani źródeł Java/Kotlin aplikacji. To materiały warstwy interfejsu i bazowy APK, nie kompletne źródła wydania 1.83.20.
- Archiwum kandydata F-Droid zawiera siedem plików Java, pliki projektu Androida i osobny snapshot JavaScript. Weryfikacja DEX opisana wyżej wykazała, że brakuje mu natywnych klas obecnych w APK 1.83.20. Przejście assembleDebug w GitHub Actions potwierdza kompilację tego kandydata, ale nie zgodność z APK 1.83.20 ani kompletność jego źródeł.
- Pakiet startowy własnego repozytorium F-Droid zawiera skrypty, metadane i instrukcję publikacji binarnego APK. Nie zawiera projektu źródłowego ani recepty budującej aplikację. Jego README wyraźnie rozróżnia własne repozytorium binarne od zgłoszenia do katalogu f-droid.org.

Te dodatkowe kontrole nie zmieniają wniosku audytu: obecny kandydat i własne repozytorium binarne nie stanowią źródłowego zgłoszenia do oficjalnego F-Droida. Nadal potrzebny jest kompletny, licencjonowany projekt, który buduje APK 1.83.20 od zera.


## Dodatkowy przegląd odzyskanych archiwów — 11 października 2026 r.

- `Ispina-Lokalnie-open-source-prep.zip` opisuje się jako techniczną bazę z linii 1.80.x, wymagającą przeniesienia późniejszych zmian z 1.82.x i testów regresji przed wydaniem. Zawiera projekt `com.ispina.lokalnie` w wersji 1.80.66 i siedem plików Java; nie zawiera wrappera Gradle, a README mówi, że licencja wymaga jeszcze decyzji. Nie jest źródłem 1.83.20.
- `Ispina-Lokalnie-1.81.10-projekt.zip` to dekompilacja APK przez apktool (w metadanych widnieje APK 1.81.09), z 3 765 plikami smali. Nie zawiera oryginalnego projektu Gradle ani kompletnego projektu źródłowego.
- `Ispina-Lokalnie-1.82.03-KOD-I-TESTY.zip` zawiera punktowe poprawki, testy i elementy smali oraz opisuje się jako pakiet poprawki/testów bez przebudowy całej aplikacji; nie zawiera kompletnego projektu Androida.
- `Ispina-Lokalnie-Live-1.82.93-TV-SAVER-patch-source.zip` opisuje poprawkę na bazie podpisanego APK 1.82.91. Zawiera bazowy APK/classes5.dex i zmiany dotyczące ekranu TV, a nie pełne źródła aplikacji.

Żadne z tych archiwów nie uzupełnia brakującego, kompletnego źródła wydania 1.83.20 ani nie pozwala przygotować uczciwej recepty budowania F-Droid.
