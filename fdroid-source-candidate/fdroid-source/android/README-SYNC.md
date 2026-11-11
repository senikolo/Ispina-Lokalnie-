# Ispina Lokalnie – automatyczna aktualizacja rozkładów (wersja robocza)

## Cel

- MLD A4/A7/A65: automatyczne pobieranie statycznego GTFS Kolei Małopolskich raz na dobę, planowo około 01:05.
- Linia gminna: automatyczne pobieranie JSON z własnego Google Drive przez Google Apps Script Web App.
- Ostatni poprawny rozkład pozostaje aktywny przy błędzie sieci/serwera/pliku.
- Po uruchomieniu aplikacji wykonywana jest kontrola; jeśli od ostatniej próby minęło ponad 26 h, uruchamia się dodatkowa synchronizacja.

## Co jest już w kodzie

1. `ScheduleSync` – WorkManager, cykl 24 h i pierwsze uruchomienie około 01:05.
2. `ScheduleSyncWorker` – pobranie, walidacja, atomowa podmiana cache.
3. `GtfsSubsetParser` – z pełnego GTFS zachowuje wyłącznie A4, A7 i A65: trasy, kursy, stop_times, przystanki, calendar i calendar_dates.
4. `ScheduleStore` – cache w prywatnym katalogu aplikacji i status ostatniej synchronizacji.
5. `NativeScheduleBridge` – most WebView/JavaScript:
   - `getMldScheduleJson()`
   - `getGminnaScheduleJson()`
   - `getScheduleSyncStatus()`
   - `requestScheduleSync()`
   - `setGminnaApiUrl(url)`
6. `main_ui.js` – robocza warstwa planera potrafi użyć zdalnego cache MLD oraz linii gminnej; stary, wbudowany rozkład pozostaje fallbackiem.

## MLD

Worker próbuje oficjalne adresy KMŁ zapisane w `SyncConfig.MLD_GTFS_URLS`. Każdy pobrany ZIP jest odrzucany, jeśli nie ma wszystkich linii A4, A7 i A65 albo brakuje wymaganych plików GTFS. Nieprawidłowy plik nigdy nie zastępuje ostatniego poprawnego cache.

## Linia gminna

Obecny plik produkcyjny `linia-gminna.json` na Dysku Google nie został nadpisany.
W folderze danych aplikacji dodano `linia-gminna-v2-draft.json`, ponieważ poprawiona trasa zawiera powtarzające się przystanki po nawrocie w Gawłówku i stary parser oparty na `indexOf()` nie obsługuje tego bezpiecznie.

Nowy parser planera bierze pod uwagę wszystkie wystąpienia przystanku w trasie. Dopiero po testach wersję v2 można ustawić jako `linia-gminna.json`.

## Jedyny krok ręczny przed testem linii gminnej

Google Apps Script musi być raz wdrożony jako Web App. Po wdrożeniu skopiuj adres kończący się na `/exec` i ustaw go w `SyncConfig.DEFAULT_GMINNA_API_URL` albo przez `IspinaNative.setGminnaApiUrl(url)`.

Nie trzeba później wydawać APK po zmianie godzin linii gminnej – wystarczy zmienić JSON na Dysku Google.

## Ważne

To jest źródło robocze, nie finalny APK. Nie należy jeszcze publikować ani instalować tej wersji jako produkcyjnej.


## Fallback 1.80.60

Do APK dołączony jest `assets/linia-gminna.json` jako lokalny fallback. Jeśli zdalny adres linii gminnej nie jest jeszcze skonfigurowany lub sieć zawiedzie, planer nadal używa tej wersji zamiast pustego rozkładu.
