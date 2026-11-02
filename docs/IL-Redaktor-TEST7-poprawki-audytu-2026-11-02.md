# IL Redaktor Professional TEST-7 — poprawki audytu

Wersja `2.0.0-professional-test.7` (51), na bazie PR #31 i commitu `108b3c9c53fa4048559dec8ae619320b876fc73e`.

## Wdrożone zmiany

- Kontrola niegotowych pól obejmuje tytuł, treść i zajawkę oraz końcowy status publikacji. Dotyczy także harmonogramu i zmiany `draft` na `publish`/`future` przy edycji istniejącego wpisu. Nie blokuje roboczego szkicu, który nie jest publiczny.
- Usunięcie zdjęcia wysyła `featured_media: 0`. Odczyt weryfikacyjny musi potwierdzić brak zdjęcia.
- Wczytywany wpis zachowuje i pokazuje identyfikatory istniejących tagów. Nazwy są odczytywane przez obsługiwaną paginację `tags.list`; nieodczytane nazwy zachowują oznaczenie `[ID: numer]`. Spóźniony odczyt nie zastępuje wpisanych zmian ani innego szkicu.
- Świadome usunięcie tagów wysyła `tags: []`. Puste pole starszego szkicu zachowuje dotychczasowe identyfikatory, dopóki właściciel nie wybierze usunięcia lub nie wczyta ich do edycji. Stan przetrwa restart i eksport/import kopii; nie zmieniono wersji magazynu szkiców ani bazy Radaru.
- Weryfikacja porównuje pełne zestawy kategorii i tagów. Samo istnienie części oczekiwanej listy nie wystarcza do sukcesu.
- Ekran Połączenie oddziela zapisaną sesję, potwierdzony odczyt i potwierdzony zapis szkicu w bieżącej sesji. Ponowne logowanie/rozłączenie resetuje dowód zapisu. Diagnostyczny zapis szkicu sprawdza także tytuł i treść, nie tylko status.
- Powiększono etykiety nawigacji oraz wybrane objaśnienia, zachowując regulację wielkości tekstu i tryby wyglądu.
- CI zapisuje osobny raport podpisu APK, SHA-256 i dostępności aktualizacji ze stałym kluczem. Raport nie utożsamia testu migracji na emulatorze ze zgodnością z telefonem.

## Walidacja lokalna

- JavaScript: **234/234 PASS**, w tym 21 nowych przypadków. Nowa atrapa WordPressa zachowuje pominięte pola tak jak aktualizacja istniejącego obiektu, zamiast wyłącznie powtarzać wysłane żądanie.
- Backend/WebView: **12/12 PASS**.
- TypeScript: **PASS**.
- Składnia YAML i osadzonego Pythona w CI oraz `git diff --check`: **PASS**.
- Brak lokalnego Android SDK i Gradle — nowa kompilacja i testy Androida wymagają GitHub Actions. Wyniki CI należy sprawdzić dla commitu z niniejszą zmianą; wcześniejszego zielonego przebiegu nie traktować jako wyniku TEST-7.

## Co wymaga dalszego potwierdzenia

1. Rzeczywisty OAuth WordPress.com, zapis niepublicznego szkicu i podgląd na koncie właściciela. Nie wykonano tego w niniejszej zmianie. Domyślny `auth` oraz osobna, świadoma zgoda na `global` pozostały zachowane. Nie zapisano ani nie opublikowano testowej treści w WordPressie.
2. Zgodność podpisu aktualizacji z istniejącą instalacją telefonu. Brak klucza nie jest rozwiązywany przez wygenerowanie nowego „stałego” klucza ani przez odinstalowanie programu. Jeżeli CI nie udostępni `IL-Redaktor-Signed-Upgrade`, debug APK pozostaje tylko testowym pakietem bez gwarancji aktualizacji telefonu.
3. Pełny odczyt BIP/PDF/OCR pozostaje do osobnego etapu. Zachowano obecny Radar, jego pamięć i jawne limity pokrycia; niniejsza poprawka nie deklaruje rozszerzenia kompletności wyszukiwania.

Wersja nadal **TEST**, bez scalenia do `main`. Najpierw trzeba potwierdzić przebieg na telefonie i zgodność aktualizacji.
