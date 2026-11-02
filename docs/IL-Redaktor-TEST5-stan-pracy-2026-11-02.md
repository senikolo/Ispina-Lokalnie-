# IL Redaktor Smart — TEST-5, stan pracy 2 listopada 2026

## Punkt wyjścia i zakres
Repozytorium senikolo/Ispina-Lokalnie-. PR #28: b37941cafcf3987a3b898efcc8e22e89de311f0c, Professional TEST-4 (48).
Sprawdzono najnowsze PR-y i gałęzie. Późniejsze zmiany gałęzi PR #27 kończące się na 785dcd3 zostały scalone lokalnie: poprawka odmiany „ispiński”, testy emulatora i dziennik postępu. Zachowano dokumentację i metadane TEST-4, następnie podniesiono kod nowej wersji do 49 / 2.0.0-professional-test.5. To wersja źródeł, nie deklaracja wydania APK.
Gałąź robocza: codex/il-draft-confirmation-20261102. Nie scalono do main.

## Naprawione błędy
1. Ponowne sprawdzenie zapisu wcześniej usuwało ostrzeżenie po samym znalezieniu ID. Teraz odczyt musi potwierdzić wysłaną treść, tytuł, zajawkę, status, kategorie i wysłane tagi/zdjęcie, a przy harmonogramie termin. Niezgodność zachowuje ostrzeżenie i daje link do wpisu w WordPressie.
2. Dokładny zestaw danych wysłanego POST jest zapisywany przed wysłaniem w istniejącym szkicu; pozostaje po restarcie i w eksporcie/importowaniu kopii. Starsze kopie bez tego zestawu są nadal czytelne, lecz nie otrzymują fałszywego potwierdzenia treści.
3. Sprawdzanie blokuje przełączenie szkicu i równoległy zapis. Blokada jest zwalniana także po błędzie.
4. Nowy lub przełączony szkic nie dziedziczy ostatniej próby zapisu poprzedniego szkicu.
5. Brak miejsca na zapis lokalnego potwierdzenia nie jest przedstawiany jako pełny sukces. Ostrzeżenie pozostaje.
6. Tekst zmieniony podczas oczekiwania na odczyt potwierdzający nie jest oznaczany jako zsynchronizowany. Pozostaje lokalny, oznaczony jako niewysłany; potwierdzenie wyraźnie to opisuje.
7. Udane sprawdzenie przerwanego zapisu odtwarza wersję odniesienia WordPressa dla kolejnej edycji.

Nie zmieniano bazy SQLite Radaru, klucza szkiców, identyfikatora pakietu ani mechanizmu przechowywania sesji. Nie kasowano danych. Nie zmieniano transportu OAuth bez potwierdzonego błędu na koncie właściciela. Bezpłatny przepływ ChatGPT nadal oznacza przekazanie polecenia do aplikacji/strony, ręczny odbiór odpowiedzi i porównanie przed zastosowaniem; nie dodano płatnego API.

## Wykonane testy
- JavaScript aplikacji: 207/207 PASS, w tym 9 nowych regresji (dwa warianty niezgodności, odtworzenie po restarcie z późniejszymi zmianami, równoległe operacje, starszy format kopii, przełączanie szkicu, termin, brak miejsca, późniejsza edycja).
- Backend i zgodność WebView: 12/12 PASS.
- TypeScript: tsc --noEmit PASS.
- Transpilacja bieżącego HTML do zgodnego WebView: PASS.
- Kontrola składni transpilowanego JavaScript: PASS.
- git diff --check: PASS.
- Testy transportu używają mocków. Nie wykonywano zapisu, publikacji, tworzenia tagów ani testowych szkiców w rzeczywistym WordPressie.

## Niewykonane i przeszkody
Nie wykonano nowego builda Android ani emulatorów. W bieżącym środowisku nie ma skonfigurowanego Android SDK, Gradle ani klucza wydaniowego. Nie przygotowano nowej podpisanej APK. Wyniki CI starszych gałęzi nie są wynikami tej zmiany.
Push do dokładnie wskazanego publicznego repozytorium został dwukrotnie odrzucony przez automatyczną kontrolę uprawnień, także po potwierdzeniu adresu i prawa push. Kontrola wymaga wyraźnej zgody na publiczne umieszczenie zmian; nie obchodzono odmowy przez API. Zatem nie ma nowego zdalnego PR ani uruchomienia CI.
Kod zapisano lokalnymi commitami i przygotowano archiwum źródeł oraz przyrostowy git bundle do kontynuacji. Kopię pakietu należy pobrać, jeżeli nie zostanie odblokowany push.

## Kontynuacja po zgodzie na push
1. Otworzyć gałąź codex/il-draft-confirmation-20261102 i sprawdzić git status.
2. Wysłać gałąź do origin w senikolo/Ispina-Lokalnie-. Otworzyć draft PR względem codex/il-radar-final-20261102, opisując również włączenie późniejszych zmian #27. Nie scalać automatycznie.
3. Poczekać na build i testy API 26/29/30/33 workflow il-redaktor-apk.yml. Artefakt debug nie jest automatycznie zgodny podpisem z TEST-4.
4. Release podpisać istniejącym kluczem właściciela, zweryfikować apksigner oraz zgodność z release-certificate.sha256. Nie generować zastępczego klucza dla aktualizacji.
5. Na telefonie właściciela wykonać kopie szkiców i pamięci, następnie sprawdzić zgodną aktualizację bez odinstalowywania.
6. Test wymagający telefonu/logowania: OAuth → prywatny szkic → zapis → potwierdzenie → otwarcie szkicu w WordPressie → edycja i ponowny zapis. Nie publikować. Osobno potwierdzić działanie powrotu z ChatGPT Free oraz zachowanie danych po restarcie.

## Odtworzenie z paczki
Plik git bundle jest przyrostowy względem b37941cafcf3987a3b898efcc8e22e89de311f0c, który jest dostępny na gałęzi PR #28. W klonie repozytorium z pobraną tą bazą wykonać git bundle verify, potem git fetch z pliku bundle, podając gałąź roboczą jako źródło i cel. Plik sources.zip zawiera bieżące źródła modułu, workflow oraz dokumentację; nie zawiera sekretów ani node_modules. Raport testów JS znajduje się w docs/qa/IL-TEST5-js-tests.txt.
