# Prywatność

Ta wersja nie ma kont użytkownika, reklam, analityki ani identyfikatora reklamowego.

## Zapytania internetowe

- Przy odświeżeniu aktualności i historii aplikacja łączy się z publicznym WordPress REST API Ispina Lokalnie.
- Przy odświeżeniu prognozy łączy się z API Open-Meteo dla współrzędnych Ispiny.
- Przy ręcznym odświeżeniu MLD pobiera publiczny feed GTFS z serwera Kolei Małopolskich.
- Po wybraniu playera, artykułu lub odnośnika do gminy Android otwiera stronę zewnętrzną. Jej operator może otrzymać standardowe dane połączenia.

Serwery internetowe mogą rejestrować adres IP, czas zapytania i podstawowe nagłówki sieciowe. Aplikacja nie wysyła do tych usług ustawień, listy tras ani historii użycia.

## Dane na urządzeniu

Pobrany rozkład MLD i ustawienia są zapisywane lokalnie. Tryb Senior, tempo lektora, wibrację i przypomnienia można zmienić w aplikacji. Wyłączenie kopii zapasowej Androida zapobiega automatycznemu kopiowaniu tych danych przez system.

Powiadomienia o nowych aktualnościach sprawdzają publiczny identyfikator najnowszego wpisu, a przypomnienia o odpadach korzystają z dołączonego harmonogramu 2026. Sprawdzanie w tle jest realizowane przez Android JobScheduler.

## Uprawnienia

Wariant prosi o internet, wibrację, powiadomienia Androida 13+ i możliwość odtworzenia okresowego zadania po ponownym uruchomieniu systemu. Zgoda na powiadomienia jest proszona po ich włączeniu przez użytkownika.
