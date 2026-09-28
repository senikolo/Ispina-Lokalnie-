# Ispina Lokalnie — Android

Otwarte repozytorium aplikacji **Ispina Lokalnie** dla Androida.

> **Status tej gałęzi:** przygotowanie infrastruktury GitHub/F-Droid. Kod aplikacji w tym pakiecie jest techniczną bazą źródłową z linii 1.80.x i nie powinien być publikowany jako aktualna wersja produkcyjna 1.82.x bez przeniesienia późniejszych zmian oraz testów regresji.

## Cel repozytorium

- przechowywanie pełnego kodu źródłowego zamiast gotowych APK jako jedynego źródła,
- automatyczne budowanie APK w GitHub Actions,
- publikowanie wydań przez GitHub Releases,
- przygotowanie projektu do zgłoszenia do F-Droid,
- zachowanie historii wersji i możliwości łatwego audytu.

## Technologia

- Android / Java 17
- AndroidX WorkManager
- WebView + warstwa interfejsu w `assets/main_ui.js`
- synchronizacja rozkładów MLD i linii gminnej
- Open-Meteo jako źródło pogody

## Budowanie

```bash
./gradlew :app:assembleDebug
```

W GitHub Actions build uruchamia się automatycznie po pushu i można go też uruchomić ręcznie.

## F-Droid — stan przygotowania

Przed zgłoszeniem do oficjalnego katalogu F-Droid trzeba jeszcze:

1. przenieść wszystkie zmiany z aktualnej linii 1.82.x do źródeł,
2. zastąpić font Drogowskaz zasobem na zgodnej licencji FLOSS,
3. docelowo przenieść JSON linii gminnej z Google Apps Script na otwarte, stabilne źródło,
4. ustalić ostateczną licencję projektu i dodać plik `LICENSE`,
5. wykonać czysty build i testy na Androidzie 10/11 oraz nowszych wersjach.

Szczegóły: [`docs/F-DROID.md`](docs/F-DROID.md).
