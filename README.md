# Ispina Lokalnie — aplikacja Android

Samodzielnie napisana aplikacja dla mieszkańców Ispiny i okolic. Interfejs, style, dane przykładowe i logika JavaScript są w repozytorium; Android obsługuje pobieranie rozkładu, przypomnienia, polski lektor, wibrację i linki zewnętrzne. Interfejs działa lokalnie i nie ładuje zdalnego kodu.

## Co potrafi

- wyszukuje połączenia MLD oraz roboczą linię gminną — bezpośrednie i z jedną przesiadką;
- pobiera na żądanie aktualny rozkład MLD w formacie GTFS i zapisuje go na telefonie, aby planer działał później offline;
- pokazuje wiadomości i historię portalu Ispina Lokalnie, pobrane jako dane i oczyszczone przed wyświetleniem;
- wyświetla trzydniową prognozę, wschód i zachód słońca dla Ispiny;
- przypomina o odbiorze odpadów dzień wcześniej według harmonogramu na 2026 r.;
- ma systemowego polskiego lektora z pauzą i regulacją tempa, tryb Senior, wibrację oraz udostępnianie trasy;
- otwiera oficjalne strony nadawców radiowych i TV.

Przycisk LIVE prowadzi do oficjalnych komunikatów o utrudnieniach. Ta wersja nie pobiera danych GTFS-Realtime ani nie pokazuje pozycji pojazdów.

## Dane i prywatność

Statyczny rozkład MLD pobiera się ręcznie z oficjalnego feedu Kolei Małopolskich. Aplikacja pokazuje datę pobrania. Po zapisaniu rozkład pozostaje na urządzeniu. Tabela linii gminnej jest robocza, a część godzin pośrednich szacowana — planer wyraźnie o tym ostrzega. Terminy odpadów dotyczą 2026 r. i po upływie ważności przestaną być oznaczane jako aktualne.

Odświeżenie wiadomości, historii i pogody wymaga internetu. Aplikacja nie zawiera reklam ani analityki; nie wysyła ustawień ani zapisanej trasy. Dane i ich warunki wykorzystania opisano w [SOURCES.md](SOURCES.md), a przepływy danych w [PRIVACY.md](PRIVACY.md).

## Budowanie i testy

Wymagane: JDK 17, Gradle 8.9, Android SDK Platform 35 i Build Tools 35.0.0. Z katalogu repozytorium: 

```sh
gradle --no-daemon clean testFdroidReleaseUnitTest assembleFdroidRelease
(cd tests && npm test)
```

Wariant F-Droid ma identyfikator `com.ispina.mldcheck.fdroid`. Wynikiem lokalnego buildu jest `app/build/outputs/apk/fdroid/release/app-fdroid-release-unsigned.apk`. CI buduje warianty debug i release, uruchamia testy planera oraz sprawdza uruchomienie na emulatorze Android 11. Artefakt debug służy do testów, a release pozostaje niepodpisany — nie ma w repozytorium klucza produkcyjnego. Szczegóły są w [FDROID.md](FDROID.md) i [RELEASE_CHECKLIST.md](RELEASE_CHECKLIST.md).

## Warianty aplikacji

`com.ispina.mldcheck.fdroid` i `com.ispina.mldcheck.direct` są odrębnymi aplikacjami. Nie zastępują produkcyjnego `com.ispina.mldcheck` i nie współdzielą z nim podpisu.

Kod modułu Android `app/` jest na licencji GPL-3.0-only; szczegóły i tekst licencji wskazuje [app/LICENSE](app/LICENSE). Główny plik [LICENSE](LICENSE) dotyczy wcześniejszych plików repozytorium i nie zmienia licencji danych ani treści wymienionych w SOURCES.md.
