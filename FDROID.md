# Kandydat F-Droid — stan

Projekt ma samodzielny wariant `fdroidRelease` z unikalnym identyfikatorem `com.ispina.mldcheck.fdroid`, bez usług reklamowych ani analitycznych. Zdalne odpowiedzi są traktowane jako dane. Zawartość interfejsu, JavaScript, CSS, ikona, harmonogram i kod Androida znajdują się w źródłach.

F-Droid wymaga wolnej licencji, kompletnego źródła i własnej kompilacji. Zgodnie z polityką repozytorium aplikacja opakowująca stronę musi też wnosić natywne funkcje. Kandydat dodaje planer, lokalne cache GTFS, natywny TTS, powiadomienia, tryb Senior i wibrację. Zasady sprawdzania: [Inclusion Policy](https://f-droid.org/docs/Inclusion_Policy/) i [Build Metadata Reference](https://f-droid.org/docs/Build_Metadata_Reference/).

To jeszcze nie jest wydanie gotowe do zgłoszenia. Zanim pojawi się w repozytorium, trzeba:

1. Umieścić ten projekt w publicznym repozytorium z historią źródeł i tagiem `2.0.0-preview`.
2. Przejść kompilację `assembleFdroidRelease` w CI/F-Droid; obecne środowisko robocze nie zawiera Android SDK ani Gradle.
3. Sprawdzić instalację i podstawowe funkcje na Androidzie 11, w tym pobranie GTFS, działanie offline, zgodę na powiadomienia, TTS i zachowanie ostrzeżeń o rozkładzie gminnym.
4. Przygotować metadane `fdroiddata` z prawdziwym commitem/tagiem; przykładową receptę dodaje się dopiero po udostępnieniu kodu pod docelowym URL.
5. Po pozytywnym teście zdecydować, czy publikować w oficjalnym repozytorium F-Droid, czy w istniejącym repozytorium IL.

Nie załączaj podpisanego APK do zgłoszenia jako substytutu źródeł. F-Droid ma zbudować pakiet z tego projektu. Obecna aplikacja produkcyjna i jej klucz podpisu pozostają nietknięte.
