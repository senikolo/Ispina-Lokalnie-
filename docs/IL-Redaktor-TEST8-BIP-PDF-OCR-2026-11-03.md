# IL Redaktor TEST-8 — odczyt PDF i OCR z BIP

Wersja testowa: 2.0.0-professional-test.8, versionCode 52. Zmiana jest oparta na TEST-7 i pozostaje w osobnym, niepołączonym PR.

## Co zmienia redaktor

- W szerokim, ręcznie uruchamianym wyszukiwaniu sprawdza znalezione strony BIP i ich załączniki PDF.
- Z tekstowych PDF-ów wydobywa tekst na urządzeniu. Strony skanowane renderuje lokalnie i rozpoznaje przez dołączony model ML Kit; treść dokumentów nie trafia do OCR w chmurze.
- Pokazuje status każdego wpisu/PDF, strony odczytane, liczbę prób i skutecznych stron OCR, podgląd tekstu oraz odsyłacze do PDF i wpisu BIP.
- Przekazuje wybrane, powiązane z Ispiną fragmenty do lokalnego szkicu, pamięci redakcyjnej i — dopiero po dotknięciu przycisku — do otwartej aplikacji ChatGPT/Gemini. Pamięć zachowuje krótkie fragmenty i źródła.
- Wskazuje częściowy odczyt. Treść PDF/OCR nadal wymaga sprawdzenia w oryginale; nie oznacza źródeł jako zweryfikowanych i niczego nie publikuje.
- Skan automatyczny w tle nadal jest lekki: wykrywa linki, nie pobiera PDF-ów i nie wykonuje OCR. Przed pierwszym alertem porównuje linki z istniejącą pamięcią ręcznych wyszukiwań.
- Przycisk „Najnowsze materiały” zachowuje wybrany temat i okres.

## Limity i ochrona

Odczyt jest ograniczony do maksymalnie 5 stron BIP i 4 PDF-ów na wyszukiwanie, 8 MB na PDF, 120 stron dokumentu, 24 prób OCR na PDF, 40 prób OCR na wyszukiwanie i około 72 sekund pracy czytnika. Pobieranie działa wyłącznie przez HTTPS i do zaufanych domen urzędowych/BIP; stare adresy HTTP tych domen są podnoszone do HTTPS bez wysyłania żądań HTTP. Pliki tymczasowe są usuwane po analizie. Limity mogą oznaczać wynik częściowy. Wyszukiwarka nadal zależy od dostępnych indeksów i nie przegląda całego BIP.

Po ręcznym uruchomieniu pełne wyszukiwanie może trwać do 150 sekund i można je anulować. Rozmowa i Radar w tle nie wykonują OCR. Dodane biblioteki PDFBox-Android i ML Kit zwiększają rozmiar APK; wielkość należy odczytać z artefaktu kompilacji.

## Walidacja

Wynik kompilacji i emulatorów dla TEST-8 należy sprawdzić w CI. Nie wykonano logowania OAuth, zapisu WordPress ani testu na telefonie właściciela. Wersja pozostaje testowa i nie jest scalona do main.
