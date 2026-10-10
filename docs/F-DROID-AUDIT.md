# Audyt przygotowania Ispina Lokalnie do F-Droida

**Data audytu:** 10 października 2026 r.  
**Status:** NIEGOTOWA DO ZGŁOSZENIA  
**Wersja docelowa:** 1.83.20 (versionCode 18320)  
**Identyfikator docelowy:** `com.ispina.mldcheck`  
**Licencja wybrana dla kodu, do którego autor ma prawa:** `GPL-3.0-only`

## Wniosek

Nie znaleziono kompletnego projektu źródłowego wersji 1.83.20. Znaleziono starszy, rzeczywisty projekt Androida 1.80.66 oraz osobny prototyp aplikacji 2.0.x. Starszy projekt może pomóc jako punkt odniesienia, ale nie jest źródłem bieżącego wydania i nie wolno publikować go jako jego zamiennika. Nie przeprowadzono kompilacji aktualnej aplikacji ani pełnego audytu praw do zasobów.

## Źródła i kompilacja — BLOKER

- Repozytorium zawiera interfejs JavaScript, moduły, testy i narzędzia. `planner/build_motion_apk.py` wymaga istniejącego APK przez `--base`; nie buduje aplikacji od zera.
- Opis archiwum źródeł wydania 1.83.19 wskazuje na interfejs, moduły, testy i bazowy APK, a nie pełny projekt natywny. Archiwum nie zostało niezależnie zweryfikowane.
- Odnaleziono starszy projekt Androida 1.80.66 osadzony w archiwalnym notebooku budowania. Zawierał Gradle, moduł aplikacji, pliki Java `MainActivity` i synchronizacji rozkładów oraz zasoby. Jego archiwum przechodzi test ZIP i zgadza się z sumą SHA-256 wpisaną w notebooku.
- Ten projekt ustawia `applicationId/namespace com.ispina.lokalnie`, versionCode 18066 i versionName 1.80.66. Wersja docelowa ma identyfikator `com.ispina.mldcheck`. README odzyskanego projektu opisuje uproszczoną wersję bez radia i ostrzega, że kompilacja debug może wymagać odinstalowania istniejącej aplikacji. To nie jest bezpieczna podmiana wersji 1.83.20.
- Znaleziono też osobne notebooki z prototypami 2.0.x. Nie potwierdzono, że są źródłem aktualnego pakietu; nie zostały użyte do zastąpienia bieżącej aplikacji.
- W tym środowisku dostępny jest JDK 17, ale nie ma Gradle ani Android SDK. Nie wykonano kompilacji źródeł 1.80.66 ani 1.83.20.
- Nie ma jeszcze wiarygodnej recepty F-Droid dla wersji 1.83.20. Nie wolno umieszczać wcześniejszego APK w procesie kompilacji.

## Licencje i prawa do zasobów — NIEZWERYFIKOWANE

Dodano pełny tekst GPLv3. Oznaczenie `GPL-3.0-only` dotyczy wyłącznie kodu, do którego autor ma prawa i który udostępnia na tej licencji. Sam plik `LICENSE` nie nadaje praw do cudzych materiałów.

Przed zgłoszeniem trzeba udokumentować właściciela, licencję i prawo do redystrybucji zdjęć, ilustracji, ikon, fontów, rozkładów MLD i gminnych, transmisji radiowych i telewizyjnych, treści aktualności i historii oraz danych i usług pogodowych. Dostępność materiału w Internecie nie oznacza zgody na jego kopiowanie ani objęcia go GPL.

## Biblioteki, reklamy i śledzenie — NIEZWERYFIKOWANE

Dla wersji 1.83.20 brak projektu Gradle i natywnego kodu uniemożliwia wiarygodne zestawienie zależności. Nie można potwierdzić ani wykluczyć Google Play Services, Firebase, bibliotek reklamowych, analityki, trackerów ani komponentów niewolnych.

W odzyskanym projekcie 1.80.66 widoczna była zależność WorkManager 2.9.1; nie jest to dowód dotyczący wersji 1.83.20. Trzeba oddzielnie sprawdzić wszystkie zależności aktualnego wydania i ich licencje.

## Uprawnienia, sieć i usługi — CZĘŚCIOWO ZNANE

Odzyskany projekt 1.80.66 deklaruje `INTERNET` i `ACCESS_NETWORK_STATE`, pobiera pogodę z Open-Meteo, otwiera stronę Ispina Lokalnie w WebView i przewiduje synchronizację rozkładów przez usługę internetową. Jego README wyraźnie określa go jako wersję bez radia. Te informacje dotyczą tylko starszego projektu. Manifestu, domen i przesyłanych danych aplikacji 1.83.20 nie zweryfikowano.

## Aktualizacje — WYMAGANY OSOBNY WARIANT

Wydania opisują kanał aktualizacji. Po odzyskaniu kodu trzeba ustalić jego działanie. Wariant F-Droid powinien korzystać z aktualizacji dostarczanych przez klienta F-Droid i nie pobierać samodzielnie APK z pominięciem weryfikacji katalogu. Wariant produkcyjny należy pozostawić bez zmian do czasu zaplanowania migracji użytkowników.

## Podpis i migracja — BLOKER

Zachowanie identyfikatora `com.ispina.mldcheck` nie wystarczy do aktualizacji instalacji. Android wymaga zgodności certyfikatu podpisu. Dla wersji F-Droid trzeba ustalić, czy możliwy jest reprodukowalny build zgodny z dotychczasowym podpisem. Nie zmieniono ani nie ujawniono produkcyjnego klucza. Jeśli zgodności podpisu nie da się potwierdzić, potrzebny będzie jawny plan migracji i ochrony lokalnych danych.

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
- Nie udało się więc potwierdzić natywnego manifestu scalonego, bibliotek ani usług użytych w opublikowanej APK. Do takiego audytu potrzebny jest kompletny projekt lub kontrolowane odczytanie APK; sam snapshot repozytorium nie wystarcza.


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
