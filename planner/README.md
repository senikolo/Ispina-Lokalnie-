# Ispina Lokalnie 1.83.19 — wydanie stabilne

Pakiet `com.ispina.mldcheck`, versionCode `18319`, kanał `production`.

## Zmiany

- Pełne nazwy przystanków w komunikatach dojścia i odjazdów: „przystanek Ispina Most” i „przystanek Ispina Leśniczówka”.
- Większe wiersze podpowiedzi i odstępy; większe elementy w trybie Senior.
- Dotknięcie podpowiedzi pozostawia listę i jej pozycję. Dopiero „Akceptuj: [pełna nazwa]” zatwierdza wybór, zamyka listę i rysuje ptaszek.
- Przewijanie, przeciągnięcie, anulowany gest i wielodotyk nie wybierają przystanku. Odświeżenie rozkładu zachowuje otwartą listę.
- Zatwierdzone pole jest zablokowane do naciśnięcia „Zmień”. Enter nie zatwierdza wyboru.
- Zachowane poprawki wspólnego korytarza A50/A57/linia gminna i alias Nowe Brzesko Rynek. Wyniki obejmują jeden wybrany dzień.
- Stabilne wydania GitHuba są dobierane według pakietu, kanału, certyfikatu i nazwy pliku w metadanych wydania. Wydania testowe, prerelease i obce podpisy są pomijane.

## Sprawdzenie

84/84 testy: 46 planera/LIVE/interfejsu/aktualizacji, 4 animacji bez pomiarów SVG, 13 korytarza/aliasów/dnia, 5 pierwszego Akceptuj przy starych ustawieniach i 16 wyboru/gestów/Zmień.

Na kodzie wyciągniętym z podpisanej APK sprawdzono dotykowe widoki Chromium 131: 390 × 844, 320 × 420 i Senior 390 × 640. Sprawdzono rzeczywiste przewijanie, wybór wstępny, zatwierdzenie, blokadę pola i ponowną edycję. Osobno sprawdzono pierwszą animację obu pól i wyniki A50/A57/gminna do końca wybranego dnia. 420 px symuluje mniejszą dostępną powierzchnię; nie jest pomiarem klawiatury telefonu.

Podpisy v1/v2/v3, certyfikat zgodny ze stabilną 1.82.99, integralność ZIP/DEX, wyrównanie ZIP, zgodność interfejsu i metadanych: poprawne. Wszystkie atrybuty manifestu poza numerem wersji odpowiadają produkcyjnej 1.82.99, w tym nazwa pakietu, uprawnienia i provider. Kod DEX jest identyczny z bazą R14.3.

Nie wykonano instalacji ani testu na fizycznym Androidzie. Testy przeglądarkowe korzystają z kontrolowanego mostu Androida i danych. Czasy na części przystanków pośrednich linii gminnej są szacunkami i pozostają oznaczone w wynikach.

## Instalacja i podpis

Wydanie aktualizuje stabilną 1.82.99 (`com.ispina.mldcheck`) bez usuwania aplikacji i danych. Testowy pakiet `com.ispina.mldtest6` pozostaje osobną aplikacją. Publiczna 1.82.98 miała inny podpis i nie umożliwia bezpośredniej aktualizacji do tej APK; przed zmianą instalacji należy zachować potrzebne ustawienia i uwzględnić usunięcie lokalnych danych.

Certyfikat SHA-256: `6d75baf7f4402ff93c93505fd6d7a5c0eab3d1c7c2aa2a39eeb5e4ae166f625f`.

## Odtworzenie

To pełny kod interfejsu i źródła jego poprawek, a nie pełny projekt Java/Kotlin całej aplikacji. Bazowa APK R14.3 jest dołączona do archiwum źródeł w wydaniu. Jej SHA-256: `b6d84261e048d773340f66a065a04adbe7c3df0895e7dd0106b936b58ceb3ce9`.

Niepodpisana budowa:

```bash
python3 build_motion_apk.py --base /ścieżka/base-R14-3.apk
```

Podpisana budowa wymaga dodatkowo `--signer /ścieżka/apksigner.jar` i `--private-signing-config /ścieżka/prywatny.json`. JSON zawiera pola `keystore`, `alias` i `password`; nie należy go publikować. Kluczy nie dołączono. Kontrola gotowej podpisanej APK: `python3 verify_apk.py`.

Testy:

```bash
npm ci --ignore-scripts
npm test
```

`IL_JSDOM_PATH` pozwala wskazać istniejący jsdom, a `IL_UI_PATH` kod wyciągnięty z podpisanej APK. Skrypty `visual_probe.cjs` i `visual_safe_picker.cjs` wymagają Playwright pod `CODEX_PRIMARY_RUNTIME_NODE_MODULES` oraz przeglądarki pod `IL_CHROME_PATH`. Pierwszy obsługuje `IL_VISUAL_NO_SVG_LENGTH=1` i `IL_VISUAL_OLD_PREFS=1`, drugi `IL_VISUAL_CASE` do wybrania jednego widoku.

Bazowa APK, wygenerowane pakiety i klucze są wyłączone z repozytorium. Archiwum wydania zawiera bazę, źródła i raporty potrzebne do powtórzenia kontroli. Pliki `src/` odpowiadają dokładnym fragmentom `assets/main_ui.js`.
