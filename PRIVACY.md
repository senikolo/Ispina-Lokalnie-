# Prywatność

Ta wersja nie ma kont użytkownika, reklam, analityki ani identyfikatora reklamowego.

## Zapytania internetowe

- Po uruchomieniu aplikacji oraz przy każdym powrocie na ekran główny automatycznie pobiera aktualności z publicznego WordPress REST API Ispina Lokalnie i prognozę z Open-Meteo. Odpowiednie przyciski pozwalają odświeżyć te dane ręcznie.
- Po wejściu na ekran historii aplikacja pobiera historię z publicznego WordPress REST API Ispina Lokalnie.
- Prognoza Open-Meteo używa stałych współrzędnych Ispiny (50.1136, 20.3781); aplikacja nie korzysta z lokalizacji urządzenia.
- Po ręcznym wybraniu „Pobierz MLD” aplikacja pobiera publiczny feed GTFS z serwera Kolei Małopolskich.
- Po wybraniu playera, artykułu lub odnośnika do gminy Android otwiera stronę zewnętrzną. Jej operator może otrzymać standardowe dane połączenia.

Serwery internetowe mogą rejestrować adres IP, czas zapytania i podstawowe nagłówki sieciowe. Aplikacja nie wysyła ustawień, listy tras ani historii użycia. Nie ma identyfikatora reklamowego ani własnej analityki.

## Dane na urządzeniu

Pobrany rozkład MLD i ustawienia są zapisywane lokalnie. Tryb Senior, tempo lektora, wibrację i przypomnienia można zmienić w aplikacji. Wyłączenie kopii zapasowej Androida zapobiega automatycznemu kopiowaniu tych danych przez system.

Powiadomienia o nowych aktualnościach sprawdzają publiczny identyfikator najnowszego wpisu, a przypomnienia o odpadach korzystają z dołączonego harmonogramu 2026. Sprawdzanie w tle jest realizowane przez Android JobScheduler.

## Uprawnienia

Wariant prosi o internet, wibrację, powiadomienia Androida 13+ i możliwość odtworzenia okresowego zadania po ponownym uruchomieniu systemu. Zgoda na powiadomienia jest proszona po ich włączeniu przez użytkownika.
