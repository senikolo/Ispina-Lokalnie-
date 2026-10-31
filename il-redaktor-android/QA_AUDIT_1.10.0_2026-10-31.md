# Raport QA i audytu — IL Redaktor Smart 1.10.0-test

**Data:** 2026-10-31  
**Repozytorium:** `senikolo/Ispina-Lokalnie-`  
**Gałąź:** `codex/il-redaktor-native-android-20261031`  
**PR:** [#9](https://github.com/senikolo/Ispina-Lokalnie-/pull/9)  
**Wersja źródeł:** versionName `1.10.0-test`, versionCode `16`, package `com.ispinalokalnie.redaktorsmart.publish191.test`, minSdk 26.

**Commit źródłowy:** [`beee36ac693fde42b8711dbd41cfa02b56604624`](https://github.com/senikolo/Ispina-Lokalnie-/commit/beee36ac693fde42b8711dbd41cfa02b56604624)
**GitHub Actions APK run:** [41198188559](https://github.com/senikolo/Ispina-Lokalnie-/actions/runs/41198188559), zakończony sukcesem.

## Werdykt

Zmiany kodu i automatyczne testy kontraktowe są gotowe do weryfikacji na Androidzie i na koncie redaktora. **Nie potwierdzono w tym przebiegu logowania OAuth ani zapisu do konta właściciela na fizycznym urządzeniu.** Testy automatyczne nie używały prawdziwego tokenu WordPress i nie zapisywały niczego w portalu.

| Zakres | Wynik |
|---|---|
| Testy JavaScript w Node VM z mockiem transportu | 51/51 PASS |
| Składnia skryptu aplikacji i testu | PASS |
| Odczyt publicznej strony katalogu Usługi | PASS, tylko odczyt — strona 818 |
| Logowanie OAuth na Androidzie | BLOCKED — potrzebny telefon/emulator i konto WordPress redaktora |
| Tworzenie szkicu przez prawdziwe MCP | BLOCKED — brak autoryzowanej sesji w tym środowisku |
| Emulator / test zgodności Android 8, 10, 11 i 13 | BLOCKED — brak Android SDK, `adb` i emulatora lokalnie |
| APK 1.10.0 — build, badging, `apksigner`, monogram | PASS — GitHub Actions run 41198188559 |
| Aktualizacja 1.9.1 → 1.10.0 podpisem debug | FAIL — fingerprint certyfikatu różni się; Android odrzuci instalację aktualizacyjną dla tego samego package ID |
| APK release z istniejącym podpisem | BLOCKED — workflow pominął signed release z powodu braku skonfigurowanego klucza; nie tworzono nowego |

Nie należy scalać PR do `main` jako wydania produkcyjnego, dopóki nie przejdą test logowania, szkicu i podglądu na urządzeniu właściciela.

## Przyczyny błędów logowania ustalone w historii projektu

W istniejącej dokumentacji projektu zapisano dwa odtworzone błędy krytyczne:

1. OAuth prosił o `scope=auth`, podczas gdy WordPress MCP wymagał `scope=global`; odpowiedź wskazywała dokładnie `Required scope: global. Granted scope(s): auth`.
2. Aplikacja wywoływała MCP `tools/call` bez wcześniejszego cyklu `initialize` i nagłówka `Mcp-Session-Id`; telefon zwrócił `HTTP 400: Missing Mcp-Session-Id header`.

Wcześniejszy mechanizm powrotu przez lokalny serwer `localhost` mógł też zawieść po zatrzymaniu procesu Androida w tle; PR #9 opisuje również ryzyko nieaktualnego redirectu i identyfikatora OAuth klienta. Obecny kod prosi o `global`, używa rejestracji publicznego klienta OAuth 2.1 z PKCE i natywnego callbacku Android, zapisuje tymczasowy verifier/state przed otwarciem przeglądarki oraz wykonuje handshake MCP. Sukces logowania pojawia się dopiero po odczycie wpisów.

**Ograniczenie:** brak aktualnego nagrania ekranu/logu z urządzenia oznacza, że nie da się przypisać zgłoszenia użytkownika do konkretnej przyczyny nowego builda ani potwierdzić, że obecna autoryzacja działa na jego koncie. `OAuthBrowserLogin` dodatkowo nie usuwa prawidłowej oczekującej sesji po callbacku z błędnym `state`.

## Przyczyny błędów publikowania i katalogu

- Aplikacja potwierdza sukces zapisu dopiero po otrzymaniu ID i ponownym odczycie `posts/{id}`. Przy niejednoznacznym timeoutcie nie ponawia POST, żeby nie tworzyć duplikatu. Wymagana publikacja nadal nie była testowana na koncie redaktora.
- Dodano zaplanowanie przez status `future`; sprawdzane są ID, status oraz data ponownie odczytana z WordPressa (tolerancja 90 sekund). Niedopasowanie terminu kończy się błędem, bez fałszywego sukcesu.
- Publiczna strona „Usługi w okolicy” to strona WordPress ID 818 z kartami Gutenberg, nie zwykła kategoria wpisów. Odczyt publicznej strony pokazał karty z nagłówkami `<h3>`, m.in. RemoTerm i N-Tech. Parser wcześniej rozpoznawał jedynie własny marker aplikacji, więc nie widział istniejących kart i mógł nie ostrzec o duplikacie. Obecnie odczytuje nagłówki legacy poza markerem własnego obszaru.
- Zapis katalogu modyfikuje tylko blok oznaczony przez Redaktor Smart, porównuje treść strony przed zapisem i odczytuje stronę ponownie. Ukrywanie i usuwanie są ograniczone do kart utworzonych przez aplikację. Rzeczywisty zapis do WordPressa pozostaje BLOCKED.

## Poprawki i zmienione pliki

| Plik | Zmiana |
|---|---|
| `app/src/main/assets/index.html` | potwierdzanie i komunikaty wyników; prawdziwy link artykułu; cztery akcje sukcesu; harmonogram z kontrolą daty; lista wpisów przyszłych; panel usług i parser kart Gutenberg; ustawienia UI i haptics; obsługa bezpiecznego zdjęcia |
| `app/src/main/java/com/ispinalokalnie/redaktor/MainActivity.java` | zapamiętywany zoom WebView 80–160%; ograniczenie częstotliwości wibracji; callbacks ustawień |
| `app/src/main/java/com/ispinalokalnie/redaktor/OAuthBrowserLogin.java` | callback ze złym `state` nie kasuje prawidłowego oczekującego verifiera |
| `app/src/main/java/com/ispinalokalnie/redaktor/WpMcp.java` | status `future`; zaktualizowana metadana klienta MCP |
| `backend/api/assistant.ts` | poprawny MCP initialize → initialized → tools/call, obsługa JSON/SSE, timeout i brak cache |
| `tests/publishing.test.cjs` | rozbudowa zestawu do 51 testów kontraktowych z mockiem transportu |
| `app/build.gradle` | wersja `1.10.0-test`, versionCode 16 |
| `app/src/main/AndroidManifest.xml` | etykieta APK 1.10.0-test |
| `.github/workflows/il-redaktor-apk.yml` | asercje wersji, publikacji, harmonogramu i bezpieczeństwa; testy i pakowanie APK |
| `README.md` | aktualny pakiet, zakres funkcji i ograniczenia QA |
| `QA_MASTER_PROMPT.md` | powtarzalny prompt QA z 42 scenariuszami |
| `QA_AUDIT_1.10.0_2026-10-31.md` | bieżący raport przebiegu |

## Wyniki testów automatycznych

Polecenia wykonane w lokalnym środowisku:

```sh
node --check il-redaktor-android/tests/publishing.test.cjs
node --check /tmp/il-editor-script.js
node --test il-redaktor-android/tests/publishing.test.cjs
cd il-redaktor-android/backend && npm run typecheck
```

Skrypt UI wydzielono z `app/src/main/assets/index.html` do tymczasowego pliku przed sprawdzeniem składni. Wyniki: **51 PASS, 0 FAIL** oraz `tsc --noEmit` dla opcjonalnego backendu AI — PASS. Poniższe przypadki uruchomiono na JavaScript aplikacji w Node VM. Transport WordPress, OAuth i natywne API Androida były mockowane lub statycznie sprawdzane; statusy te nie potwierdzają testu na żywym koncie.

| # | Przypadek wykonany | Status / dowód |
|---:|---|---|
| 1 | Pojedynczy POST i GET przed sukcesem publikacji | PASS — test Node VM |
| 2 | Szkic z potwierdzonym statusem `draft` | PASS — test Node VM |
| 3 | Odróżnienie nieoczekiwanego statusu | PASS — test Node VM |
| 4 | Nieudany GET po zapisie bez duplikatu | PASS — test Node VM |
| 5 | Odrzucony POST pokazuje błąd | PASS — test Node VM |
| 6 | Opublikowany wpis otwiera permalink i przycisk trwały | PASS — test Node VM |
| 7 | Brak kanonicznego linku używa publicznego `?p=ID` IL | PASS — test Node VM |
| 8 | Błąd tagów można pominąć po potwierdzeniu | PASS — test Node VM |
| 9 | Podwójne kliknięcie blokuje równoległy zapis | PASS — test Node VM |
| 10 | Weryfikacja ostatniego zapisu jest tylko GET | PASS — test Node VM |
| 11 | Zmiana katalogu wymaga read-after-write | PASS — test Node VM |
| 12 | Formularz odzyskania OAuth i intensywność haptics | PASS — test Node VM |
| 13 | Oczekiwanie OAuth nie jest wiecznym spinnerem | PASS — test Node VM |
| 14 | Diagnostyka tworzy szkic i weryfikuje przez GET | PASS — test Node VM |
| 15 | Nieudana diagnostyka nie ponawia POST | PASS — test Node VM |
| 16 | Harmonogram wysyła `future` i sprawdza datę po GET | PASS — test Node VM |
| 17 | Brak daty odrzuca zapis | PASS — test Node VM |
| 18 | Termin krótszy niż dwie minuty odrzuca zapis | PASS — test Node VM |
| 19 | Anulowanie harmonogramu nie wysyła żądania | PASS — test Node VM |
| 20 | Nieoczekiwany status harmonogramu nie daje sukcesu | PASS — test Node VM |
| 21 | Rozbieżna data harmonogramu kończy się błędem | PASS — test Node VM |
| 22 | Edycja wpisu przyszłego zachowuje harmonogram | PASS — test Node VM |
| 23 | Pola harmonogramu i opis strefy telefonu są obecne | PASS — asercja UI |
| 24 | Tytuł, treść, zajawka i kategoria są wysyłane | PASS — test Node VM |
| 25 | ID zdjęcia trafia do `featured_media` | PASS — test Node VM |
| 26 | Tryb HTML zachowuje markup | PASS — test Node VM |
| 27 | Utworzony tag trafia do wpisu | PASS — test Node VM |
| 28 | Odmowa kontynuacji po błędzie tagów blokuje POST | PASS — test Node VM |
| 29 | Anulowanie publikacji natychmiastowej blokuje sieć | PASS — test Node VM |
| 30 | Edycja wysyła update do istniejącego ID | PASS — test Node VM |
| 31 | Timeout po POST pokazuje błąd bez ponowienia | PASS — test Node VM |
| 32 | Zewnętrzny permalink odrzuca się i stosuje fallback IL | PASS — test Node VM |
| 33 | Parser legacy rozpoznaje karty H3 i pomija własny blok | PASS — test Node VM |
| 34 | Obraz usługi dopuszcza wyłącznie zaufany HTTPS WordPress | PASS — test Node VM |
| 35 | Ukrywanie oferty zachowuje ręczny HTML strony | PASS — test Node VM |
| 36 | Usunięcie oferty wymaga potwierdzenia | PASS — test Node VM |
| 37 | Motyw i tryb baterii zapisują się w preferencjach | PASS — test Node VM |
| 38 | Callback zoomu akceptuje wspierane wartości | PASS — test Node VM |
| 39 | Ustawienia motywu, animacji, baterii i tekstu w UI | PASS — asercja UI |
| 40 | OAuth kontroluje `state`; tokeny używają Keystore AES-GCM | PASS — asercja kodu |
| 41 | Mapowanie MCP akceptuje status `future` | PASS — asercja kodu |
| 42 | Podwójne kliknięcie harmonogramu nie dubluje POST | PASS — test Node VM |
| 43 | Akcje sukcesu pokazują się tylko dla potwierdzonej publikacji | PASS — test Node VM |
| 44 | Wersja Gradle zgadza się z workflow APK | PASS — asercja kodu |
| 45 | Skrót AI przekazuje bieżący tekst do widocznego, edytowalnego promptu bez wysyłania | PASS — test Node VM |
| 46 | Darmowa wersja Facebooka używa wyłącznie tytułu i zajawki artykułu | PASS — test Node VM |
| 47 | Wynik AI jest escapowany i pokazuje akcje Facebook/galeria zdjęć | PASS — test Node VM |
| 48 | Lokalna korekta sprawdza formy „w Ispinie / do Ispiny / z Ispiny” | PASS — test Node VM |
| 49 | Kontrakt AI nie dopuszcza fikcyjnych URL zdjęć | PASS — asercja kodu |
| 50 | DCR, PKCE, uprawnienie global i redirect Android są zgodne | PASS — asercja kodu |
| 51 | Upload skaluje i kompresuje zdjęcie przed wysłaniem | PASS — asercja kodu |

## Zakres rzeczywiście sprawdzony na WordPress.com

- **Publiczny odczyt:** strona „Usługi w okolicy”, ID 818, została obejrzana przez WordPress.com REST API. Potwierdzono strukturę strony i istniejące nagłówki kart. Żaden wpis ani strona nie zostały zmodyfikowane.
- **OAuth, MCP z tokenem redaktora, tworzenie szkicu, aktualizacja, tagi, zdjęcia i publikacja:** BLOCKED — nie było autoryzowanej sesji WordPress.com w tym przebiegu.
- **Asystent generatywny:** BLOCKED — backend nie ma konfiguracji klucza Gemini i limitowania; build bez endpointu korzysta z darmowych lokalnych szablonów i reguł. Generatywne AI wymaga odrębnego klucza API Gemini oraz usługi limitującej zapytania; to odrębne usługi, których koszt zależy od planu i użycia.
- **Legalność zdjęć:** aplikacja umożliwia wybór z biblioteki WordPress lub telefonu; nie wykonano weryfikacji licencji ani wyszukiwania źródeł stockowych.
- **Optymalizacja zdjęć:** źródło Android skaluje bitmapę do maksymalnie ok. 1600 px, zapisuje JPEG jakości 80 i ogranicza payload; nie zmierzono tego na urządzeniu.

## Testy wymagające urządzenia lub autoryzacji

Statusy poniżej są BLOCKED, ponieważ nie wykonano ich w tym środowisku. Zestaw 51 mocków nie zmienia tych statusów.

| Zakres | Status | Co jest potrzebne |
|---|---|---|
| Instalacja i cold start APK | BLOCKED | APK z aktualnego CI i emulator/telefon |
| Android 8, 10, 11, 13 | BLOCKED | emulatory/urządzenia z tymi wersjami; Android 11 priorytet |
| OAuth, callback, Google jako dostawca WordPress.com | BLOCKED | konto redaktora i test na urządzeniu |
| Sesja po zamknięciu aplikacji, rotacja, ponowne uruchomienie | BLOCKED | urządzenie i przebieg interaktywny |
| Wygaśnięcie tokenu / refresh / ponowne logowanie | BLOCKED | konto testowe lub autoryzowana sesja |
| Szkic na prawdziwym API | BLOCKED | świadoma zgoda redaktora na utworzenie szkicu QA; brak tokenu |
| Publikacja i rzeczywisty podgląd | BLOCKED | staging WordPress lub kontrolowany wpis QA i autoryzacja |
| Harmonogram w WordPressie | BLOCKED | konto testowe i ponowny odczyt `date_gmt` |
| Zmiany katalogu Usługi | BLOCKED | autoryzacja redaktora; wymagany bezpieczny szkic/obszar testowy |
| Brak/słaba sieć, utrata w trakcie żądania | BLOCKED | emulator/telefon z kontrolowaną siecią |
| Duża czcionka, TalkBack, rotacja, klawiatura | BLOCKED | test UI na Androidzie |
| Zużycie RAM/CPU, start i ANR na starszym urządzeniu | BLOCKED | urządzenie i narzędzia pomiaru |
| Fingerprint podpisu poprzedniego i nowego APK | FAIL — porównano certyfikaty; podpisy się różnią |
| Instalacja aktualizacyjna na Androidzie | BLOCKED — brak urządzenia; różny fingerprint już wyklucza aktualizację |

## APK i wydanie

Build i weryfikacja APK 1.10.0 zakończyły się sukcesem:

- Run: [41198188559](https://github.com/senikolo/Ispina-Lokalnie-/actions/runs/41198188559), commit `beee36ac693fde42b8711dbd41cfa02b56604624`.
- `aapt dump badging` potwierdził package `com.ispinalokalnie.redaktorsmart.publish191.test`, versionCode 16 i versionName `1.10.0-test`. `apksigner verify` i kontrola monogramu IL przeszły w CI.
- APK direct release: [IL-Redaktor-Smart-1.10.0-TEST.apk](https://github.com/senikolo/Ispina-Lokalnie-/releases/download/il-redaktor-smart-1.10.0-test/IL-Redaktor-Smart-1.10.0-TEST.apk).
- SHA-256 APK: `bae8b4fd10cec48b8cd2922681aaa205040d8b5b39575ff791adf3ecd87339b4`.
- Niepodpisany keystore release nie został skonfigurowany: workflow pominął oba kroki podpisanego release. To debug APK testowe.
- Porównanie certyfikatów z pobranych artefaktów: 1.9.1 `D9:54:92:D2:6C:43:AC:3A:FF:AB:D1:58:DA:80:01:72:55:42:C0:27:A8:16:56:65:8A:F1:E0:DE:32:4E:F1:37`; 1.10.0 `D5:4D:CD:49:C3:9B:F6:E8:16:FE:29:F8:B0:0D:DD:2A:8E:42:4B:B8:EC:D4:57:8F:9F:52:C6:57:F4:29:8F:D9`. Package ID jest ten sam, więc APK 1.10.0 **nie może aktualizować** tamtej 1.9.1 instalacji. Android wymaga odinstalowania starej wersji; skopiuj lokalne szkice przed usunięciem aplikacji. Do aktualizacji bez utraty danych potrzebny jest istniejący klucz release zgodny z APK zainstalowanym u redaktora.
- SHA-256 APK 1.9.1 porównanego z artefaktu CI: `6191f9f80fe3e0a571972523e75ad695322d05e2726fe216b09b32be1cc3a2d9`.

## Pozostałe ograniczenia

- Brak urządzenia i sesji właściciela blokuje potwierdzenie krytycznych kryteriów A i B: logowania oraz publikacji.
- W tej gałęzi jest debug package `.test`; nie zastępuje stabilnej produkcyjnej aplikacji.
- Nie istnieje potwierdzony stały klucz do podpisu aktualizacji. Nie generowano nowego klucza.
- Dokładną konfigurację i wymagane uprawnienia WordPress należy potwierdzić na ekranie zgody. `global` jest szerokim zakresem konta, mimo że aplikacja kieruje operacje do witryny IL.
- AI generatywne nie jest aktywne bez wdrożonego backendu i sekretnych konfiguracji poza repozytorium.

## Instalacja i następny krok

Pobierz APK z powyższego GitHub Release. **Przed instalacją nad 1.9.1 skopiuj lokalne szkice i odinstaluj starą wersję** — certyfikaty debug są różne, a odinstalowanie czyści jej lokalne dane. Zainstaluj APK i otwórz **IL Redaktor Smart 1.10.0 TEST**. Zaloguj się do WordPress.com; jeśli konto oferuje Google, wybierz je na ekranie WordPress.com. Po potwierdzeniu, że portal i zakres zgody są prawidłowe, uruchom „Sprawdź dostęp do wpisów”. Test zapisu utworzy niepubliczny szkic pozostawiony na koncie — użyj go tylko za świadomą zgodą redaktora.
