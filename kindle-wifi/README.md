# Kindle przez Wi-Fi 2.28.0

Pełne źródła istniejącej aplikacji (ten sam applicationId), IL Radio i pozostałych wtyczek. Ten katalog zastępuje konieczność odtwarzania źródeł z kolejnych patchy; stare paczki pozostają w repozytorium jako historia.

## Budowanie

Java 17, Gradle 8.7, Android SDK 35 i build-tools 35.0.0:

```sh
gradle --no-daemon -ParmOnly assembleRelease
```

Wersja telefonu zachowuje obie architektury ARM i modele OCR; ma mniej niż 30 MB. Bez `-ParmOnly` powstaje również wariant z bibliotekami emulatora. Podpisz wynik dotychczasowym kluczem poza repozytorium. Nie publikuj klucza ani hasła. Wyrównaj archiwum `zipalign` przed podpisaniem `apksigner`, następnie sprawdź oba narzędzia.

## Testy

```sh
bash tests/run-atomic-tests.sh
bash tests/run-radio-tests.sh
bash tests/run-stream-probe-tests.sh
python3 tests/run-he-diagnostic-tests.py
bash tests/run-sftp-tests.sh
java -cp /path/to/luaj-jse-3.0.1.jar lua tests/tuning-test.lua assets/ilradio_k3.koplugin
java -cp /path/to/luaj-jse-3.0.1.jar lua tests/radio-menu-test.lua assets/ilradio_k3.koplugin
java -cp /path/to/luaj-jse-3.0.1.jar lua tests/radio-menu-test.lua assets/ilradio_k3.koplugin legacy
```

Testy hosta używają kontrolowanych serwerów i zastępczych silników, nie symulują potwierdzonego działania sprzętu. Wyniki w `reports/` opisują wykonany zakres.

## Próbki diagnostyczne

`test-real-he32.aac` i `test-real-hev224.aac` są trzema sekundami własnego, syntetycznego sygnału stereo 440/660 Hz, kodowanymi FDK AAC (AOT 5 / 29; 32 / 24 kb/s). Nie zawierają audycji stacji. Kontrola FDK potwierdziła SBR i PS. W `tools/fdk-source-commit.txt` zapisano commit projektu `mstorsjo/fdk-aac`; `tools/fdk-diagnostic.patch` dodaje wypisywanie faktów do kontrolnego dekodera. Zbuduj narzędzia FDK przez CMake z `BUILD_PROGRAMS=ON`, na komputerze. FFprobe 7.1.5 w tym środowisku wyświetlał profil LC nawet dla strumieni zawierających SBR/PS; dlatego sam profil FFprobe nie jest tu dowodem braku HE-AAC. Manifest sum i wyników jest w `reports/sample-verification.json`.

Test PCM dla nowych próbek wymaga WAV 44,1 kHz stereo i odpowiedniej liczby bajtów, dzięki czemu odrzuca dekodowanie samego rdzenia 22,05 kHz. Nie potwierdza działania głośnika ani jakości rekonstrukcji PS.

## Katalog

`streams.tsv` rozróżnia dane katalogu, pomiar i datę kontroli. Puste pola oznaczają brak potwierdzenia w ostatniej próbie, nie trwałą niedostępność. Adresy z oficjalnego API RMF pozostawiono także wtedy, gdy próba połączenia z tego środowiska kończyła się timeoutem. Szczegóły są w `reports/catalog-evidence.json` i `reports/official-sources.json`.

`tools/verify_catalog.py` wymaga curl, FFprobe oraz zmiennej `FDK_DECODER` wskazującej kontrolny program FDK wypisujący pola `flags`, `core`, `rate`, `corech`, `ch`. Nie uruchamiaj tego audytu na Kindle. To narzędzie utrzymania katalogu, nie składnik odtwarzacza.

## Granice

Nie przeprowadzono odsłuchu na fizycznym K3, testu jego klawiszy, wielogodzinnej próby ani pomiaru poboru energii. Mniejszy bitrate oznacza mniej danych, nie potwierdzoną oszczędność baterii. Informacja o serwerze po przekierowaniu dotyczy krótkiej próby poprzedzającej odtwarzanie; druga sesja może trafić na inny serwer CDN.
