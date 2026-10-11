# Ispina Lokalnie — nowy projekt Android

Samodzielnie napisany kandydat aplikacji Ispina Lokalnie. Interfejs HTML, CSS i JavaScript jest częścią źródeł, a natywna warstwa Androida obsługuje pobieranie rozkładu, powiadomienia, lektora, wibrację i bezpieczne otwieranie odnośników. Aplikacja nie ładuje zdalnej strony jako swojego interfejsu i nie pobiera zdalnego kodu.

## Funkcje

- ekran startowy i nawigacja z dolnego paska;
- wyszukiwanie połączeń MLD i linii gminnej, bezpośrednich oraz z jedną przesiadką;
- pobranie aktualnego feedu GTFS MLD na żądanie i lokalne zachowanie rozkładu;
- aktualności oraz strony historii pobierane jako dane z WordPress REST API, sanitizowane i wyświetlane lokalnie;
- prognoza pogody na trzy dni, wschód i zachód słońca z Open-Meteo;
- harmonogram odpadów Ispiny na 2026 r. z przypomnieniem dzień wcześniej;
- systemowy polski lektor z pauzą, wznowieniem i regulacją tempa;
- tryb Senior, wibracja, udostępnianie trasy i przycisk Wstecz prowadzący do ekranu startowego;
- radio i TVP przez oficjalne odtwarzacze nadawców.

Pozycja LIVE otwiera stronę z utrudnieniami Kolei Małopolskich. Kandydat nie zawiera własnego źródła danych LIVE. Odtwarzanie radia uruchamia oficjalne strony nadawców.

## Dane i ograniczenia

Rozkład MLD jest pobierany z oficjalnego publicznego feedu GTFS. Po pierwszym uruchomieniu planera trzeba wybrać „Pobierz MLD”; potem zapisany rozkład działa bez kolejnego pobrania. Aplikacja pokazuje datę jego pobrania.

Dołączona tabela linii gminnej ma charakter roboczy. Część czasów przystanków pośrednich jest szacowana. Planer wyraźnie pokazuje ostrzeżenie i odsyła do aktualnych informacji gminy. Nie należy opierać podróży na szacowanych godzinach bez potwierdzenia ich w oficjalnym rozkładzie.

Terminy odpadów dotyczą harmonogramu 2026 i przestaną być oznaczane jako aktualne po dacie ważności. Aktualności, historia i pogoda wymagają internetu do odświeżenia; ustawienia i pobrany GTFS pozostają na urządzeniu. Wiadomości z portalu są sanitizowane przed wyświetleniem. Linki zewnętrzne otwierają się poza aplikacją.

Źródła wymieniono w [`SOURCES.md`](SOURCES.md), a przepływy danych opisano w [`PRIVACY.md`](PRIVACY.md).
Lista sprawdzeń przed udostępnieniem jest w [`TEST-PLAN-ANDROID11.md`](TEST-PLAN-ANDROID11.md).

## Identyfikatory i podpisy

Wariant F-Droid ma identyfikator `com.ispina.mldcheck.fdroid`. Wariant bez dystrybucyjnego sklepu ma identyfikator `com.ispina.mldcheck.direct`. Żaden z nich nie używa identyfikatora obecnej aplikacji produkcyjnej `com.ispina.mldcheck`. Wydania testowe dodają `.debug`.

Warianty są osobnymi aplikacjami i nie zastępują produkcyjnego IL ani nie współdzielą z nim podpisu. Wydanie Androida podpisuje sklep lub operator repozytorium swoim kluczem. Nie ma tu klucza produkcyjnego ani podpisanego APK.

## Budowanie

Wymagane: JDK 17, Gradle 8.9, Android SDK Platform 35 oraz Build Tools 35.0.0. Z katalogu projektu:

```sh
gradle --no-daemon clean testFdroidReleaseUnitTest assembleFdroidRelease
```

Wynik F-Droid:

```text
app/build/outputs/apk/fdroid/release/app-fdroid-release-unsigned.apk
```

Testy planera, bez Android SDK:

```sh
cd tests
npm test
```

GitHub Actions w tym repozytorium wykonuje testy JavaScript i buduje warianty F-Droid `debug` oraz `release`. Po udanym buildzie zapisuje na 14 dni podpisany kluczem debug APK do testu na telefonie oraz osobno niepodpisany APK release dla procesu F-Droid. Lokalny kontener tej pracy nie ma Gradle ani Android SDK, dlatego kompilacja APK nie została tu potwierdzona. Wariant testowy nie jest wydaniem produkcyjnym.

## Licencja

Kod nowej aplikacji jest udostępniony na licencji MIT. Licencja kodu nie zmienia warunków wykorzystania treści WordPress, danych pogody, rozkładów ani znaków towarowych wymienionych w [`SOURCES.md`](SOURCES.md). W projekcie nie ma APK ani kodu wyciągniętego z APK; testy planera używają małych, ręcznie przygotowanych przykładów.
