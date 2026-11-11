# Kandydat źródłowy Ispina Lokalnie dla F-Droida

**Status: szkic do przeglądu i kompilacji, nie zgłoszenie do oficjalnego F-Droida.**

Kandydat łączy zachowany, pełny projekt Android/Java z archiwum 1.80.66 ze skryptem Planera zapisanym w materiałach projektu (wewnętrzna etykieta skryptu: 1.82.31). Skrypt trzeba jeszcze porównać z bieżącym plikiem w publicznym repozytorium. Używa identyfikatora `com.ispina.mldcheck`, `versionCode 18321` oraz ikony wyciągniętej z APK. To nowy, źródłowo składany kandydat — nie są to źródła, z których zbudowano APK 1.83.20.

## Znane różnice

- Warstwa Java pochodzi z projektu 1.80.66.
- Kandydujący wariant nie ma implementacji bieżących danych LIVE MLD; interfejs pokaże rozkład planowy i brak aktualnych danych LIVE.
- `openReader` otwiera artykuły w zewnętrznej przeglądarce.
- Nie przeprowadzono jeszcze kompilacji Androida ani testu na urządzeniu.
- Nie potwierdzono praw do wszystkich zdalnych materiałów i usług ani pełnego audytu prywatności.
- Wydanie oficjalnie podpisane przez F-Droid może nie aktualizować APK podpisanych dotychczasowym kluczem autora.

Przed zgłoszeniem trzeba uzyskać udany build z F-Droid Build Server, sprawdzić funkcje i uprawnienia na Androidzie, potwierdzić licencje zasobów/usług oraz przygotować aktualne zrzuty ekranu.
