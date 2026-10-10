# Audyt przygotowania Ispina Lokalnie do F-Droida

**Data audytu:** 10 października 2026 r.  
**Status:** NIEGOTOWA DO ZGŁOSZENIA  
**Wersja bazowa:** 1.83.20, versionCode 18320  
**Identyfikator:** `com.ispina.mldcheck`  
**Wybrana licencja kodu:** `GPL-3.0-only`

## Wniosek

W repozytorium znajduje się kod interfejsu, testy i skrypty, ale nie ma pełnego projektu aplikacji Android ani źródeł jej warstwy Java/Kotlin. Udokumentowany skrypt aktualizujący interfejs wymaga wcześniejszego APK jako pliku wejściowego. Taki proces nie jest budową aplikacji od zera, więc nie może posłużyć jako recepta F-Droida. Nie udało się potwierdzić zgodności licencyjnej wszystkich zasobów ani przeprowadzić kompilacji Androida.

Wyniki oznaczone jako „niezweryfikowane” wymagają sprawdzenia na pełnym, aktualnym kodzie aplikacji. Nie są potwierdzeniem braku danej funkcji.

## Ustalenia

### Źródła i kompilacja — BLOKER

- Najnowszym wydaniem widocznym na GitHubie jest stabilna wersja 1.83.20 z 10 października 2026 r.
- `planner/` zawiera interfejs JavaScript, moduły, dane testowe, testy i narzędzia kontroli. README wyraźnie wskazuje, że repozytorium nie zawiera pełnych źródeł Java/Kotlin ani projektu Gradle.
- `planner/build_motion_apk.py` przyjmuje istniejący APK przez parametr `--base`. Nie buduje kompletnej aplikacji ze źródeł. F-Droid wymaga publicznego, aktualnego kodu oraz działającego procesu budowania; nie można włączyć do kompilacji wcześniejszego APK.
- Nie ma podstaw do utworzenia wiarygodnej recepty `fdroiddata` dla wersji 1.83.20 ani do nazwania projektu kompletnym.

### Licencje i prawa do zasobów — NIEZWERYFIKOWANE

W gałęzi dodano pełny tekst GPLv3. Oznaczenie `GPL-3.0-only` dotyczy kodu projektu, do którego autor ma prawa i który udostępnia na tej licencji. Sam plik `LICENSE` nie nadaje praw do cudzych materiałów.

Przed zgłoszeniem trzeba zinwentaryzować i udokumentować właściciela, licencję i zgodę na redystrybucję dla co najmniej:

- zdjęć, ilustracji, ikon, fontów i innych grafik;
- rozkładów i danych przewozowych MLD oraz komunikacji gminnej;
- adresów i warunków użycia transmisji radiowych i telewizyjnych;
- treści wiadomości, historii i ciekawostek pobieranych z zewnętrznych stron;
- usług pogodowych i pozostałych usług sieciowych.

Dostępność materiału w Internecie nie oznacza automatycznie prawa do jego kopiowania ani objęcia go GPL. Zgodnie z aktualną polityką F-Droid zasoby muszą mieć prawidłową podstawę prawną; część nieprogramowych grafik lub fontów może mieć odrębną licencję, o ile pozwala ona na redystrybucję.

### Biblioteki, reklamy i śledzenie — NIEZWERYFIKOWANE

Brak plików Gradle i natywnych źródeł uniemożliwia sporządzenie listy zależności aplikacji. Nie można rzetelnie potwierdzić ani wykluczyć obecności Google Play Services, Firebase, bibliotek reklamowych, analityki, trackerów lub innych nieopen-source komponentów. Pliki związane z testami JavaScript nie zastępują audytu zależności Androida.

### Uprawnienia i połączenia sieciowe — CZĘŚCIOWO ZNANE

Widoczny w repozytorium `AndroidManifest.xml` deklaruje `INTERNET` i `ACCESS_NETWORK_STATE`. README opisuje jednak starsze XML-e w katalogu głównym jako historyczne zasoby, dlatego nie można uznać ich za manifest wydania 1.83.20. Rzeczywistych uprawnień APK i pełnej listy hostów sieciowych nie zweryfikowano.

Aplikacja korzysta z funkcji internetowych, w tym informacji transportowych, pogody, aktualności i transmisji. Po odzyskaniu źródeł trzeba ustalić, dokąd wysyłane są żądania, czy przesyłane są dane użytkownika, czy działają trackery oraz jakie warunki obowiązują dla usług zewnętrznych.

### Aktualizacje — WYMAGANY OSOBNY WARIANT

Wydania opisują stabilny kanał aktualizacji. Aktualna polityka F-Droid ogranicza pobieranie plików wykonywalnych i automatycznych aktualizacji z pominięciem weryfikacji F-Droida. Zalecany wariant F-Droid powinien wyłączać pobieranie i instalowanie APK z GitHuba, a aktualizacje dostarczać przez klienta F-Droid. Produkcyjny wariant APK należy pozostawić bez zmian.

### Podpis i migracja użytkowników — BLOKER

Zachowanie `com.ispina.mldcheck` samo w sobie nie wystarcza do aktualizacji istniejącej aplikacji. Android wymaga zgodnego certyfikatu podpisu. Jeśli APK z F-Droida zostanie podpisany innym kluczem, nie zastąpi instalacji podpisanej kluczem produkcyjnym.

Najpierw trzeba odzyskać pełne źródła i sprawdzić, czy da się odtworzyć wydanie w sposób reprodukowalny oraz zweryfikować zgodność z dotychczasowym certyfikatem. Nie wolno zmieniać ani ujawniać prywatnego klucza produkcyjnego. Jeżeli zgodności podpisu nie da się potwierdzić, migracja wymaga osobnego, jawnego planu; odinstalowanie może usunąć lokalne dane użytkownika.

### Testy i metadane — NIEZAKOŃCZONE

Repozytorium opisuje 84 testy interfejsu i logiki Planera, ale nie jest to test kompilacji Androida ani instalacji aktualnej aplikacji na urządzeniu. Nie wykonano tutaj `fdroid lint`, `fdroid build`, instalacji APK ani testu uruchomienia po czystej instalacji. Nie ma jeszcze właściwego pliku metadanych `fdroiddata` ani legalnie sprawdzonych zrzutów ekranu.

## Robocze metadane

To szkic do dalszej weryfikacji, a nie gotowy plik do `fdroiddata`.

| Pole | Wartość robocza |
|---|---|
| Nazwa | Ispina Lokalnie Live |
| Application ID | `com.ispina.mldcheck` |
| Autor | Sławomir Franciszek Kozak (`senikolo`) |
| Licencja kodu | `GPL-3.0-only` |
| Strona | https://ispinalokalnie.com |
| Kod | https://github.com/senikolo/Ispina-Lokalnie- |
| Zgłoszenia | https://github.com/senikolo/Ispina-Lokalnie-/issues |
| Aktualna wersja | 1.83.20 (18320) |

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

1. Pełny, aktualny projekt Android Gradle wraz ze źródłami Java/Kotlin i potrzebnymi zasobami.
2. Budowa od zera, bez APK jako składnika, oraz skuteczna próba budowy w środowisku zgodnym z F-Droidem.
3. Spis zależności, uprawnień, domen, trackerów, reklam i usług zewnętrznych na podstawie faktycznych źródeł.
4. Potwierdzenie praw do grafik, transmisji, danych i treści lub ich wymiana na legalnie redystrybuowane odpowiedniki.
5. Wariant F-Droid bez samodzielnego pobierania APK oraz sprawdzony plan podpisu i migracji użytkowników.
6. Test instalacji i uruchomienia oraz regresji kluczowych funkcji: Planera, rozkładów, aktualności, historii, ciekawostek, pogody i trybu Senior.
7. Właściwe metadane, legalnie udostępnione ikona i zrzuty ekranu oraz pomyślne `fdroid lint` i `fdroid build`.

Zgłoszenie do oficjalnego repozytorium F-Droid nie zostało wysłane.

## Oficjalne źródła F-Droid

- [Inclusion Policy](https://f-droid.org/en/docs/Inclusion_Policy/)
- [FAQ dla autorów aplikacji](https://f-droid.org/en/docs/FAQ_-_App_Developers/)
- [Submitting to F-Droid: Quick Start Guide](https://f-droid.org/en/docs/Submitting_to_F-Droid_Quick_Start_Guide/)
- [Build Metadata Reference](https://f-droid.org/en/docs/Build_Metadata_Reference/)
- [Reproducible Builds](https://fdroid.gitlab.io/jekyll-fdroid/en/docs/Reproducible_Builds/)
