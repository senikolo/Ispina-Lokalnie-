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
