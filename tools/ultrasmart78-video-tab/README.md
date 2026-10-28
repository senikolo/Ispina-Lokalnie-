# UltraSmart 79 — Wideo synchronizowane ze stroną IL

Jedyną bazą jest `downloads/Ispina-Lokalnie-UltraSmart-77-Drogowskaz.apk`:

- pakiet: `com.ispina.mldcheck.smartai44demo`
- wersja bazowa: `2.0.0-smart.77`, `versionCode 20348`
- SHA-256 pliku bazowego: `7c49ebf0c20563335148952f6c6e6da27028bd974a700216cb2d711028184a23`
- SHA-256 certyfikatu podpisującego: `e17105f5c66beec08508ac2e7d60f9f27b85b80ef0f6e23183a70642c6dae8a3`

Wynik ma wersję `2.0.0-smart.79`, `versionCode 20350`, ten sam pakiet i zachowuje zasoby oraz instrukcje kodu natywnego. Zmieniają się manifest, dwa pliki w `assets` (`main_ui.js` i `il-build.json`) oraz stała HTML lokalnego ekranu w `classes4.dex`. Oryginalne `frame-src 'none'` blokowało wszystkie filmy. Poprawka zezwala wyłącznie na `https://www.youtube-nocookie.com`, zachowuje `object-src 'none'` i przelicza sumy DEX bez zmiany jego układu ani instrukcji. Skrypt odrzuca inne pliki bazowe.

Przycisk **Wideo** znajduje się w istniejącym menu aplikacji, przy sekcjach o Ispinie. Po otwarciu zakładki aplikacja pobiera aktualną treść sekcji „Wideo” ze strony „Ispina na zdjęciach” przez publiczne API WordPress.com i tworzy odtwarzacze z filmów osadzonych tam. Po dodaniu filmu do tej sekcji pojawi się on w aplikacji przy następnym otwarciu zakładki — bez kolejnej aktualizacji APK. Przy braku połączenia lub błędzie odczytu aplikacja pokazuje trzy dotychczasowe nagrania zapisane awaryjnie. Filmy muszą być publiczne lub niepubliczne i mieć włączone osadzanie. Odtwarzacze są wczytywane po otwarciu Wideo i usuwane z pamięci po opuszczeniu panelu lub ukryciu aplikacji. W galerii aplikacji usuwane są odnośniki „Poznaj historię Ispiny”, „Przekaż fotografię lub wspomnienie” i „Wideo” oraz zdublowana lista filmów; treść witryny pozostaje bez zmian. Lokalny ekran aplikacji używa `loadDataWithBaseURL` z bazą `https://ispinalokalnie.wordpress.com/`; odtwarzacze zachowują HTTPS Referer, mają określony `origin` i minimalny rozmiar 200 × 200 px.

## Klucz aktualizacyjny

W **Settings → Secrets and variables → Actions → Repository secrets** właściciel dodaje:

| Sekret | Zawartość |
| --- | --- |
| `IL_ULTRASMART77_KEYSTORE_B64` | Base64 **oryginalnego keystore UltraSmart 77**, zawierającego klucz prywatny o powyższym certyfikacie. JKS i PKCS12 są rozpoznawane automatycznie. |
| `IL_ULTRASMART77_KEYSTORE_PASS` | Prawdziwe hasło tego keystore. |
| `IL_ULTRASMART77_KEY_PASS` | Tylko jeśli hasło klucza prywatnego jest inne niż hasło keystore; w przeciwnym razie można pominąć. |

Nie dodawać keystore ani haseł do kodu, załączników zgłoszenia ani logów. Workflow nie tworzy klucza zastępczego i nie próbuje odzyskiwać haseł. Alias wybiera na podstawie certyfikatu. Certyfikat jest sprawdzany na oryginalnym APK, ponownie na kluczu przed podpisaniem oraz na gotowym APK po podpisaniu. Hasła przekazywane są przez zmienne środowiskowe, a pliki tymczasowe są usuwane zawsze.

Następnie uruchomić **Actions → Build Ispina Lokalnie UltraSmart 79 Wideo → Run workflow** dla gałęzi `codex/il-video-sync-from-site-20261028`. Workflow uruchamia się również po zmianach tego narzędzia na tej gałęzi.

- `IL-UltraSmart-79-WIDEO-SIGNED`: podpisany APK oraz raport certyfikatu, metadane i suma kontrolna.
- `IL-UltraSmart-79-WIDEO-UNSIGNED`: wyłącznie plik roboczy; **nie nadaje się do instalacji ani aktualizacji**.
- Brak sekretów lub niezgodny certyfikat kończy zadanie `sign` błędem. Zielone zadanie `patch` samo nie oznacza gotowego aktualizacyjnego APK.

## Weryfikacja

`test_patch.py` sprawdza tożsamość bazy, manifest, metadane, integralność ZIP, zachowanie wszystkich pozostałych plików i poprawne uruchomienie dopisanego JavaScript. `test_ui.cjs` uruchamia pełny interfejs z wynikowego APK w Chromium z rzeczywistym HTML/CSP wyciągniętym z APK. Odtwarza błąd na starej polityce, sprawdza działanie trzech osadzeń po poprawce, utrzymanie blokady obcych ramek, usunięcie wskazanych treści galerii, Referer, rozmiary ekranu, powrót i zatrzymanie odtwarzaczy. W teście odpowiedzi YouTube są zastąpione atrapami; nie jest to test rzeczywistego strumienia ani instalacji na Androidzie.

Po uzyskaniu właściwego klucza końcowy test na urządzeniu obejmuje instalację APK jako aktualizacji UltraSmart 78 (bez odinstalowywania) i ręczne odtworzenie każdego filmu. Dostępność materiałów, zgoda właścicieli na osadzanie i odtwarzanie w danym WebView pozostają zależne od YouTube; testy atrap tego nie potwierdzają.
