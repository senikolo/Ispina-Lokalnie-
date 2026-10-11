# Kandydat F-Droid

## Stan

Publiczny projekt zawiera samodzielnie przygotowaną aplikację Android oraz wariant Gradle `fdroidRelease` o identyfikatorze `com.ispina.mldcheck.fdroid`. Wariant nie zawiera reklam, analityki ani zdalnego kodu interfejsu. Kod modułu aplikacji jest na licencji GPL-3.0-only (szczegóły w `app/LICENSE`).

Repozytorium ma workflow CI, który uruchamia testy planera, testy jednostkowe Gradle, buduje APK i sprawdza start na emulatorze Android 11 (API 30). Najnowszy status trzeba sprawdzić w zakładce [Actions](https://github.com/senikolo/Ispina-Lokalnie-/actions); niepodpisany APK release jest artefaktem do weryfikacji buildu, a nie gotowym do dystrybucji plikiem.

Projekt jest kandydatem do przeglądu F-Droid, nie został jeszcze przyjęty ani opublikowany. Oficjalne zasady: [Inclusion Policy](https://f-droid.org/docs/Inclusion_Policy/) i [Build Metadata Reference](https://f-droid.org/docs/Build_Metadata_Reference/).

## Build F-Droid

Z katalogu repozytorium, przy JDK 17, Gradle 8.9, Android SDK Platform 35 i Build Tools 35.0.0:

```sh
gradle --no-daemon clean testFdroidReleaseUnitTest assembleFdroidRelease
```

Wariant Gradle to `fdroid`; zadanie release tworzy `app/build/outputs/apk/fdroid/release/app-fdroid-release-unsigned.apk`. Źródła aplikacji są w repozytorium; nie używamy prebuilt APK ani bibliotek pobranych z prywatnego storage. Aplikacja pobiera publiczny GTFS dopiero na żądanie użytkownika.

## Pakiet i wersja

- ID pakietu: `com.ispina.mldcheck.fdroid`
- Wersja kandydata: `2.0.0-preview` (`versionCode` 20000)
- Minimalne Android API: 23
- Docelowe Android API: 35
- Build: Java 17 / Android Gradle Plugin 8.7.3

Plik `metadata/com.ispina.mldcheck.fdroid.yml` jest szkicem wpisu do repozytorium metadanych F-Droid. Przed zgłoszeniem należy skopiować go do `metadata/` w `fdroiddata`, ustawić `Builds[].commit` na pełny hash sprawdzonego commita oraz wykonać walidację i build według aktualnej dokumentacji F-Droid. Nie przypinaj gałęzi ani taga jako commita.

## Rzeczy do zamknięcia przed zgłoszeniem

1. Sprawdzić, że CI przechodzi dla commita wskazanego w metadanych.
2. Zainstalować APK testowy na fizycznym telefonie i przejść listę w [`RELEASE_CHECKLIST.md`](RELEASE_CHECKLIST.md).
3. Uzyskać i udokumentować aktualne warunki ponownego użycia statycznego GTFS oraz danych harmonogramu gminnego/odpadów. Atrybucję i odnośniki zbiera [`SOURCES.md`](SOURCES.md); publikacja danych nie może być zakładana wyłącznie na podstawie ich publicznej dostępności.
4. Upewnić się, że ekran „O aplikacji” pokazuje licencję i sposób dotarcia do jej pełnego tekstu.
5. Po pozytywnym przeglądzie zgłosić metadane do `fdroiddata`; nie załączać podpisanego APK jako zamiennika buildu ze źródeł.

Produkcja `com.ispina.mldcheck` pozostaje osobną aplikacją z osobnym podpisem. Żaden klucz produkcyjny nie jest przechowywany w tym repozytorium.
