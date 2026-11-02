# IL Redaktor Smart TEST-5: bramka wydania i test na urządzeniu

Gałąź: `codex/il-draft-confirmation-20261102`, PR #29. VersionCode 49 / `2.0.0-professional-test.5`. Status: testowa, bez automatycznego scalenia.

## Kontrole GitHub Actions
1. Testy JavaScript i backendu, TypeScript oraz zgodność WebView: wszystkie PASS.
2. Build `assembleDebug`, `assembleDebugAndroidTest` oraz `assembleRelease`: PASS.
3. Android 8, 10, 11 i 13: scenariusze szkiców, pamięci Radaru, aktualizacji zachowującej dane: PASS.
4. `aapt` potwierdza identyfikator pakietu `com.ispinalokalnie.redaktorsmart.stable`, wersję 49 oraz nazwę wydania.
5. `apksigner` potwierdza integralność i podpis. Do aktualizacji istniejącej instalacji trzeba porównać odcisk podpisu z `release-certificate.sha256`. Nie używać podpisu debug jako zamiennika release.
6. Sprawdzić obecność artefaktu APK w przebiegu odnoszącym się do **SHA gałęzi TEST-5**, a nie do wcześniejszej wersji.

## Scenariusze wymagające telefonu właściciela
- Wykonać i sprawdzić eksport szkiców oraz pamięci Radaru przed aktualizacją. Nie odinstalowywać starej aplikacji dla obejścia błędu podpisu.
- OAuth WordPress.com: autoryzacja, powrót `com.ispinalokalnie.redaktorsmart.stable:/callback`, rzeczywisty odczyt własnych wpisów.
- Zapis tylko neutralnego **szkicu**: utworzenie → POST → GET z `status=draft` → zgodność treści, tytułu i kategorii → podgląd w WordPressie. Nigdy automatyczna publikacja.
- Ponowny zapis, zerwanie sieci po POST, restart aplikacji, porównanie z pierwotnie wysłanym żądaniem; żadnego drugiego POST bez świadomej decyzji.
- Zmiany lokalne dokonane podczas oczekiwania pozostają niewysłane. Brak lokalnego zapisu potwierdzenia nie daje komunikatu o pełnym sukcesie.
- Radar: 7/30/90 dni, OSP/Most/Autobusy/Cokolwiek nowego; widoczne adresy, pominięcia i błędy na żywo; historia nie znika.
- ChatGPT Free: ręczne przekazanie polecenia i ręczny powrót z odpowiedzią, bez płatnego API ani dostępu do sesji ChatGPT.

## Stop-release
Bez zielonego CI, zgodnego podpisu i pozytywnych prób OAuth oraz szkicu na fizycznym telefonie wersja pozostaje TEST-5. Nie scalać do main. Brak danych nie może być komunikatem „nic nowego”, a niezweryfikowany zapis nie może mieć statusu SUKCES.
