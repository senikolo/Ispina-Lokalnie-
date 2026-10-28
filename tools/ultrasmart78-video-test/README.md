# UltraSmart 78 Wideo — osobna aplikacja TEST

Ten wariant jest przeznaczony do instalacji **obok UltraSmart 77**. Nie odinstalowywać wersji 77. To nie jest aktualizacja dotychczasowego pakietu i nie otrzymuje jego prywatnych ustawień ani zapisanych danych.

| Właściwość | Wartość |
| --- | --- |
| Jedyna baza | UltraSmart 77, `2.0.0-smart.77`, kod 20348 |
| Nazwa w telefonie | `Ispina Lokalnie 78 TEST` |
| Pakiet testowy | `com.ispina.mldcheck.smartai78test` |
| Wersja | `2.0.0-smart.78-test`, kod 20349 |
| Podpis | Nowy, osobny klucz testowy; publiczny odcisk w `test-certificate.json` |

Generator wywołuje istniejącą, sprawdzoną poprawkę Wideo z `../ultrasmart78-video-tab`. Zmienia tożsamość pakietu w manifeście, własne uprawnienie aplikacji, authority providera AndroidX, nazwę pakietu w zasobach oraz metadane interfejsu. Nazwy klas natywnych i wszystkie DEX pozostają identyczne. Etykieta w telefonie i pasek aplikacji wyraźnie wskazują TEST. Trzy odtwarzacze Wideo pozostają wewnątrz aplikacji.

```bash
python3 tools/ultrasmart78-video-test/test_variant.py
python3 tools/ultrasmart78-video-test/make_test_variant.py \
  downloads/Ispina-Lokalnie-UltraSmart-77-Drogowskaz.apk unsigned-test.apk
```

`sign_test_apk.py` przyjmuje ścieżkę do prywatnego keystore i narzędzi Android Build Tools. Hasło czyta wyłącznie ze zmiennej `IL_ULTRASMART78_TEST_KEYSTORE_PASS`; opcjonalnie osobne hasło klucza ze zmiennej `IL_ULTRASMART78_TEST_KEY_PASS`. Wymaga certyfikatu testowego zapisanego w `test-certificate.json` i odmawia podpisania pakietu produkcyjnego. Po podpisaniu sprawdza certyfikat APK, poprawność podpisów i wyrównanie ZIP. Prywatny keystore ani hasło nie należą do repozytorium ani publicznych artefaktów.

Workflow `build-ultrasmart78-video-test.yml` buduje i testuje osobny wariant. Do podpisywania kolejnych testowych wydań trzeba dodać **ten sam nowy klucz testowy** do sekretów GitHub: `IL_ULTRASMART78_TEST_KEYSTORE_B64`, `IL_ULTRASMART78_TEST_KEYSTORE_PASS`, opcjonalnie `IL_ULTRASMART78_TEST_KEY_PASS`. Nie zastępować go innym kluczem. Bez tych sekretów workflow udostępnia jedynie wynik UNSIGNED i jednoznacznie raportuje, że nie jest instalacyjny.

Klucz aktualizacji UltraSmart 77 i jego workflow pozostają osobną ścieżką: `../ultrasmart78-video-tab/README.md`. Nie wolno podpisać starego pakietu nowym kluczem testowym.

Testy automatyczne sprawdzają izolację pakietu, niezmienność kodu natywnego i nawigację pełnego interfejsu z osadzonymi atrapami YouTube. Nie zastępują odtworzenia trzech rzeczywistych filmów na telefonie.
