# Ispina Most — tablica odjazdów

Publiczna tablica Ispina Lokalnie. Oba kierunki, A4/A7/A65 z Mostu oraz gminna orientacyjnie według oficjalnych godzin Domu Ludowego (Ispina III). Użytkownik zaakceptował 23.10.2026 tolerancję około 2 minut dla tej informacji gminnej; nie potwierdza to odrębnego punktu rozkładowego Most.

Dane planowe: GitHub `senikolo/Ispina-Lokalnie-/ispina-rozklady/out/tablica.json`, generowane i weryfikowane przez GitHub Actions sześć razy na dobę. Zmiana PDF, wygasły audyt lub dane starsze niż 30 godzin blokują odpowiednie godziny.

API `/api/odjazdy` pobiera oficjalny GTFS Realtime KMŁ. Tożsamość kursu wymaga zgodnego trip_id, route_id oraz dnia, przystanku i kolejności. Prognozy starsze niż 180 sekund są odrzucane, opóźnienia nie są przenoszone wstecz. NO_DATA przerywa propagację, a odwołania i pominięcia mają jawny status. Podstawą prognozy jest czas GTFS; nie dodajemy różnicy minuty PDF A7 do opóźnienia.

Przy awarii API klient pobiera ten sam zweryfikowany plik planowy bezpośrednio z GitHuba, sprawdza datę kontroli i oznacza tryb rozkładowy. Nie ma lokalnej pamięci starych prognoz ani fałszywych danych.

Walidacja: `node --test lib/realtime.test.mjs`, `node node_modules/typescript/bin/tsc --noEmit`, helper budowania Sites. Sprawdzono rzeczywisty widok w przeglądarce dla szerokości 320, 768, 1280; brak poziomego przepełnienia. Sprawdzono filtry Gminna (28 kursów w piątek) oraz A7 (14 w niedzielę 25.10). Fizyczne urządzenia nie były dostępne.

Obsługa audytu kończy się 30.11.2026; kolejne wersje PDF wymagają świadomej ponownej kontroli. A7 ma sprawdzoną rozbieżność jednej minuty, dlatego godziny rozkładowe czytamy z oficjalnego PDF.
