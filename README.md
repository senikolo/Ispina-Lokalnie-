# Ispina Lokalnie — Android

## Stabilne wydanie 1.83.20

Aktualne wydanie stabilne: **1.83.20** (10 października 2026).

[Pobierz APK](https://github.com/senikolo/Ispina-Lokalnie-/releases/download/v1.83.20/Ispina-Lokalnie-Live-1.83.20.apk) · [Opis wydania](https://github.com/senikolo/Ispina-Lokalnie-/releases/tag/v1.83.20)

W wersji 1.83.20 dodano pozycję „Ciekawostki historyczne” z przejściem do materiałów o Ispinie. Planer po wybraniu celu od razu wyszukuje połączenia na teraz. Zachowano pełne nazwy przystanków, większe podpowiedzi, bezpieczniejszy wybór podczas przewijania, przycisk „Akceptuj”, czytelniejszy tryb Senior i wyniki dla wybranego dnia.

### Instalacja stabilnej wersji

- Android 6.0 lub nowszy (minSdk 23, targetSdk 34).
- Pakiet produkcyjny: com.ispina.mldcheck, versionCode 18320.
- Stabilną wersję 1.82.99 z tym samym podpisem można aktualizować bez usuwania aplikacji.
- Starsza publiczna wersja 1.82.98 ma inny certyfikat. Android nie pozwoli zastąpić jej tą APK bez zmiany instalacji. Przed usunięciem starszej aplikacji zapisz potrzebne ustawienia; odinstalowanie usuwa lokalne dane.
- Testowe wydania com.ispina.mldtest6 są osobną aplikacją. Wydanie 1.83.20 instaluje lub aktualizuje aplikację produkcyjną.

### Kod i sprawdzenie stabilnego wydania

Katalog planner/ zawiera kod interfejsu assets/main_ui.js, wyodrębnione moduły, dane testowe, 84 testy regresji i skrypty pakowania oraz kontroli APK. planner/README.md opisuje budowę i ograniczenia. Workflow sprawdza te testy; nie próbuje budować brakującego projektu Gradle. Starsze pliki XML w katalogu głównym pozostają historycznymi zasobami.

Archiwum IL-1.83.19-ZRODLA-I-TESTY.zip ze źródłami interfejsu, modułami, testami i bazową APK R14.3 pozostaje przy wydaniu 1.83.19. Wydanie 1.83.20 udostępnia zaktualizowany APK. Repozytorium nie zawiera pełnych źródeł warstwy Java/Kotlin ani prywatnych kluczy.

Sprawdzono 84/84 testy i interfejs dotykowy w Chromium w trzech rozmiarach/trybach. APK ma podpisy v1/v2/v3, wyrównanie ZIP, integralność DEX i zgodne metadane. Nie wykonano instalacji na fizycznym telefonie; testy interfejsu korzystają z kontrolowanego mostu Androida.

SHA-256 APK: c9c0a0107310c3229b421a87832de1f1fc738b07db7ab1dc41eea2ead417e664

## Kandydat Android 2.0.0-preview do przeglądu F-Droid

To odrębna, samodzielnie napisana aplikacja Android. Nie zastępuje produkcyjnej wersji 1.83.20, nie używa jej identyfikatora ani podpisu i nie została jeszcze zgłoszona ani przyjęta do F-Droida.

Interfejs, style, dane przykładowe i logika JavaScript są w repozytorium; Android obsługuje pobieranie rozkładu, przypomnienia, polski lektor, wibrację i linki zewnętrzne. Interfejs działa lokalnie i nie ładuje zdalnego kodu.

### Funkcje

- Planer połączeń MLD i roboczej linii gminnej — bezpośrednio i z jedną przesiadką.
- Rozkład MLD pobierany na żądanie w formacie GTFS i zapisywany na telefonie, aby planer mógł później działać offline.
- Aktualności i historia portalu Ispina Lokalnie, pobierane jako dane i oczyszczane przed wyświetleniem.
- Trzydniowa prognoza, wschód i zachód słońca dla Ispiny.
- Harmonogram odbioru odpadów 2026 i lokalne przypomnienia dzień wcześniej.
- Systemowy polski lektor z pauzą i regulacją tempa, tryb Senior, wibracja oraz udostępnianie trasy.
- Odnośniki do oficjalnych stron nadawców radiowych i telewizyjnych.

Przycisk LIVE prowadzi do oficjalnych komunikatów o utrudnieniach. Wersja 2.0.0-preview nie pobiera danych GTFS-Realtime ani nie pokazuje pozycji pojazdów.

### Dane, prywatność i licencje

Statyczny rozkład MLD pobiera się ręcznie z oficjalnego feedu Kolei Małopolskich. Aplikacja pokazuje datę pobrania, a zapisany rozkład pozostaje na urządzeniu. Tabela linii gminnej jest robocza, a część godzin pośrednich szacowana — planer wyraźnie o tym ostrzega. Terminy odpadów dotyczą 2026 r. i po upływie ważności przestaną być oznaczane jako aktualne.

Odświeżenie wiadomości, historii i pogody wymaga internetu. Aplikacja nie zawiera reklam ani analityki; nie wysyła ustawień ani zapisanej trasy. Dane i ich warunki wykorzystania opisano w SOURCES.md, a przepływy danych w PRIVACY.md. Publiczna dostępność danych gminnych nie oznacza automatycznie zgody na ich redystrybucję; potwierdzenie tych praw pozostaje warunkiem zgłoszenia.

Kod modułu Android app/ ma licencję GPL-3.0-only. Główny plik LICENSE dotyczy wcześniejszych plików repozytorium. Licencja kodu nie obejmuje automatycznie danych i treści osób trzecich.

### Budowanie i testy

Wymagane są JDK 17, Gradle 8.9, Android SDK Platform 35 i Build Tools 35.0.0. Z katalogu repozytorium uruchom: gradle --no-daemon clean testFdroidReleaseUnitTest assembleFdroidRelease, a następnie npm test w katalogu tests/.

Wariant F-Droid ma identyfikator com.ispina.mldcheck.fdroid. Wynikiem lokalnego buildu jest app/build/outputs/apk/fdroid/release/app-fdroid-release-unsigned.apk. CI buduje wariant debug i release, uruchamia testy planera i sprawdza uruchomienie na emulatorze Android 11. Artefakt debug służy do testów, a release pozostaje niepodpisany; w repozytorium nie ma klucza produkcyjnego. Szczegóły są w FDROID.md i RELEASE_CHECKLIST.md.

Warianty com.ispina.mldcheck.fdroid i com.ispina.mldcheck.direct są odrębnymi aplikacjami. Żaden z nich nie zastępuje produkcyjnego com.ispina.mldcheck ani nie współdzieli z nim podpisu.
