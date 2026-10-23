# Checklista przed zgłoszeniem do F-Droid

Ta checklista opisuje kandydata `2.0.0-preview`; nie oznacza publikacji ani zastąpienia produkcyjnej aplikacji.

## Źródło i build

- [ ] CI przechodzi na commicie wpisanym w metadanych: testy planera, testy Gradle, `assembleFdroidRelease` i start na emulatorze API 30.
- [ ] Wpis `metadata/com.ispina.mldcheck.fdroid.yml` ma poprawny SPDX, publiczny adres repozytorium, pełny hash commita i wariant Gradle `fdroid`.
- [ ] Build F-Droid korzysta wyłącznie ze źródeł; nie wymaga klucza podpisującego ani prebuilt APK.
- [ ] Wariant `com.ispina.mldcheck.fdroid` instaluje się obok produkcyjnego `com.ispina.mldcheck`.

## Testy na urządzeniu

- [ ] Pierwsze uruchomienie i główne ekrany działają przy włączonym oraz wyłączonym internecie.
- [ ] Pobranie GTFS MLD działa z aktualnego feedu, zapisuje dane lokalnie, pokazuje datę pobrania i nie usuwa poprzedniego rozkładu przy błędzie sieci.
- [ ] Planer uwzględnia daty i wyjątki kalendarza oraz minimalny czas przesiadki.
- [ ] Linia gminna i szacowane czasy są czytelnie oznaczone jako robocze.
- [ ] Wiadomości, historia, pogoda, odnośniki zewnętrzne i komunikaty błędów zachowują się poprawnie.
- [ ] Powiadomienia działają po przyznaniu zgody; odmowa zgody nie blokuje aplikacji.
- [ ] Polski lektor, tryb Senior, udostępnianie trasy, przycisk Wstecz i wibracja są sprawdzone.
- [ ] Sprawdzono na Androidzie 11 lub nowszym; emulator CI nie zastępuje testu na fizycznym telefonie.

## Licencje i dane

- [ ] Ekran „O aplikacji” podaje GPL-3.0-only, brak gwarancji i sposób otwarcia pełnego tekstu licencji.
- [ ] Warunki ponownego wykorzystania aktualnego GTFS oraz dołączonych danych gminnych i odpadowych są sprawdzone i zapisane. Sam fakt publicznego udostępnienia danych nie przesądza o prawie do ich dalszej dystrybucji.
- [ ] Atrybucje są zgodne ze źródłami w [`SOURCES.md`](SOURCES.md); nazwy i znaki towarowe nie sugerują poparcia dla aplikacji.
- [ ] Po zamknięciu punktów powyżej metadane są walidowane i zgłaszane do `fdroiddata`; wydanie jest tagowane dopiero po zatwierdzeniu wersji.
