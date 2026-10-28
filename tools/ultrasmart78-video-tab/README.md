# UltraSmart 78 — Wideo

Jedyną bazą jest `downloads/Ispina-Lokalnie-UltraSmart-77-Drogowskaz.apk`:

- pakiet: `com.ispina.mldcheck.smartai44demo`
- wersja bazowa: `2.0.0-smart.77`, `versionCode 20348`
- SHA-256 pliku bazowego: `7c49ebf0c20563335148952f6c6e6da27028bd974a700216cb2d711028184a23`
- SHA-256 certyfikatu podpisującego: `e17105f5c66beec08508ac2e7d60f9f27b85b80ef0f6e23183a70642c6dae8a3`

Wynik ma wersję `2.0.0-smart.78`, `versionCode 20349`, ten sam pakiet i niezmieniony kod natywny oraz zasoby. Zmieniają się wyłącznie manifest i dwa pliki w `assets`: `main_ui.js` i `il-build.json`. Skrypt odrzuca inne pliki bazowe.

Przycisk **Wideo** znajduje się w istniejącym menu aplikacji, przy sekcjach o Ispinie. Trzy filmy są osadzone w panelu aplikacji jako odtwarzacze YouTube, bez automatycznego odtwarzania. Odtwarzacze są wczytywane po otwarciu Wideo i usuwane z pamięci po opuszczeniu panelu lub ukryciu aplikacji. Linki YouTube pozostają dodatkowymi opcjami. WebView bazowej aplikacji otwiera `https://ispinalokalnie.wordpress.com/`; odtwarzacze zachowują HTTPS Referer, mają określony `origin` i minimalny rozmiar 200 × 200 px.

## Klucz aktualizacyjny

W **Settings → Secrets and variables → Actions → Repository secrets** właściciel dodaje:

| Sekret | Zawartość |
| --- | --- |
| `IL_ULTRASMART77_KEYSTORE_B64` | Base64 **oryginalnego keystore UltraSmart 77**, zawierającego klucz prywatny o powyższym certyfikacie. JKS i PKCS12 są rozpoznawane automatycznie. |
| `IL_ULTRASMART77_KEYSTORE_PASS` | Prawdziwe hasło tego keystore. |
| `IL_ULTRASMART77_KEY_PASS` | Tylko jeśli hasło klucza prywatnego jest inne niż hasło keystore; w przeciwnym razie można pominąć. |

Nie dodawać keystore ani haseł do kodu, załączników zgłoszenia ani logów. Workflow nie tworzy klucza zastępczego i nie próbuje odzyskiwać haseł. Alias wybiera na podstawie certyfikatu. Certyfikat jest sprawdzany na oryginalnym APK, ponownie na kluczu przed podpisaniem oraz na gotowym APK po podpisaniu. Hasła przekazywane są przez zmienne środowiskowe, a pliki tymczasowe są usuwane zawsze.

Następnie uruchomić **Actions → Build Ispina Lokalnie UltraSmart 78 Wideo → Run workflow** dla gałęzi `codex/il-ultrasmart78-video-20261028`. Workflow uruchamia się również po zmianach tego narzędzia na tej gałęzi.

- `IL-UltraSmart-78-WIDEO-SIGNED`: podpisany APK oraz raport certyfikatu, metadane i suma kontrolna.
- `IL-UltraSmart-78-WIDEO-UNSIGNED`: wyłącznie plik roboczy; **nie nadaje się do instalacji ani aktualizacji**.
- Brak sekretów lub niezgodny certyfikat kończy zadanie `sign` błędem. Zielone zadanie `patch` samo nie oznacza gotowego aktualizacyjnego APK.

## Weryfikacja

`test_patch.py` sprawdza tożsamość bazy, manifest, metadane, integralność ZIP, zachowanie wszystkich pozostałych plików i poprawne uruchomienie dopisanego JavaScript. `test_ui.cjs` uruchamia pełny interfejs z wynikowego APK w Chromium i sprawdza menu, trzy osadzenia, Referer, rozmiary ekranu, powrót i zatrzymanie odtwarzaczy. W teście odpowiedzi YouTube są zastąpione atrapami; nie jest to test rzeczywistego strumienia ani instalacji na Androidzie.

Po uzyskaniu właściwego klucza końcowy test na urządzeniu obejmuje instalację APK jako aktualizacji UltraSmart 77 (bez odinstalowywania) i ręczne odtworzenie każdego filmu. Dostępność materiałów, zgoda właścicieli na osadzanie i odtwarzanie w danym WebView pozostają zależne od YouTube; testy atrap tego nie potwierdzają.
