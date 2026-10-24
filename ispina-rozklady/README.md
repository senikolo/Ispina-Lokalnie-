# Automatyczne rozkłady Ispina Lokalnie

Niezależny od Androida generator planowych odjazdów A4, A7 i A65. Nie wymaga haseł do WordPressa ani płatnych wtyczek. Wyniki: `out/README.md` (dostępne tekstowe tabele), `out/rozklady.json` i dziesięć obrazów dla pięciu przystanków, na dwa dni.

## Zasada publikacji

Pobierany jest **operacyjny MLD GTFS.zip** z oficjalnego indeksu <https://gtfs.kolejemalopolskie.com.pl/>. Stary handlowy `ald-gtfs.zip` z głównej witryny KMŁ ma nieaktualne godziny i nie jest używany jako źródło zapasowe.

Dla każdego dnia i każdej linii osobno generator porównuje wszystkie odjazdy na sześciu stanowiskach z ręcznie sprawdzonymi tabelami PDF. A4 i A65 wymagają pełnej zgodności godzin oraz liczby kursów w obu kierunkach. Dla A7 dopuszczone jest wyłącznie wcześniej zweryfikowane przesunięcie GTFS do jednej minuty względem PDF: wszystkie kursy w obu kierunkach muszą dać się jednoznacznie dopasować w tej samej kolejności, a publikowane godziny pochodzą z PDF. Błąd którejkolwiek kontroli ukrywa wszystkie godziny danej linii. Pozostałe zgodne linie mogą być opublikowane. `status=partial` oznacza częściową tablicę, a nie pełny wynik pozytywny. `valid=true` wymaga zgodności wszystkich trzech linii na oba dni.

Przed kontrolą godzin sprawdzany jest aktualny katalog PDF przewoźnika oraz suma SHA-256 odpowiedniego dokumentu. Nowy PDF, zmienione bajty, nieznane identyfikatory, nieważny kalendarz, błąd pobierania lub koniec okresu kontroli blokują dane. Referencje w `reference.json` są sprawdzone do 30 listopada 2026 r.; potem wymagają ponownego audytu. Nie należy przedłużać tego terminu bez sprawdzenia nowych PDF i kalendarzy świątecznych.

## Przystanki w operacyjnym GTFS

| Nazwa na stronie | Nazwa w GTFS | W stronę Nowego Brzeska | W stronę Bochni / Podłęża | parent_station |
| --- | --- | --- | --- | --- |
| Ispina Wiślisko | Ispina Wiślisko | 4222 | 4076 | 12515000000 |
| Ispina Dom Ludowy | Ispina Dom Ludowy | 4533 | 4352 | 12516000000 |
| Ispina Most | Ispina | 119 | 67 | 10216000000 |

Mapowanie Most ↔ Ispina sprawdzono na podstawie współrzędnych, kodów 4372/4373, kolejności przystanków i godzin A4/A65 w oficjalnych PDF. Identyfikatorów ze starego handlowego feedu nie wolno stosować do feedu operacyjnego. A7 nie obsługuje Wiśliska i Domu Ludowego. Dodatkowo obsługuje przystanki Ispina Przedszkole i Ispina Leśniczówka, udostępnione teraz jako osobne tablice A7 na dwa dni. Na tych przystankach zweryfikowane PDF przewoźnika pokazują odjazdy odpowiednio 1 i 2 minuty po Moście w kierunku Podłęża oraz 1 i 2 minuty przed Mostem w kierunku Proszowic, zarówno przed zmianą, jak i od 25 października 2026 r. Generator wylicza godziny tylko przy uprzedniej pozytywnej weryfikacji obowiązującego PDF i kursów A7 na Moście; w przeciwnym wypadku ukrywa te odjazdy.

## Audyt 23 października 2026 r.

Sprawdzono 23, 24, 25, 26 i 31 października oraz 1, 11 i 30 listopada. A4 i A65 są w pełni zgodne na wszystkich stanowiskach. A7 ma rozbieżności do jednej minuty w kierunku Nowego Brzeska/Proszowic. Zatwierdzone godziny PDF są odtąd publikowane na grafikach dla Mostu, Przedszkola i Leśniczówki, po sprawdzeniu jednoznacznego dopasowania wszystkich kursów na Moście. A7 nie obsługuje Domu Ludowego i Wiśliska. Sam pomyślny odczyt GTFS nie potwierdza poprawności rozkładu.

Dodatkowe testy zgodności A7 oraz testy zabezpieczeń obejmują kalendarze, usunięcia/dodania kursów, zmianę czasu, święto 11 listopada, przejazd po północy, brak możliwości wsiadania, zmianę identyfikatora, błędne czasy, zmianę PDF i wygaśnięcie kontroli.

## Uruchomienie

```sh
python -m pip install -r ispina-rozklady/requirements.txt
python -m unittest discover -s ispina-rozklady -p 'test_*.py' -v
python ispina-rozklady/update.py
```

Workflow publikuje również plansze ostrzegawcze po niepowodzeniu generatora. Uruchamia się sześć razy na dobę i ręcznie. GitHub może opóźnić zaplanowany start. Nie używa sekretów ani dostępu do WordPressa. Zmienia wyłącznie `ispina-rozklady/out/`.

Na grafikach widnieje duża **bezwzględna data kursowania**. Darmowy WordPress i zewnętrzne serwery mogą buforować obrazy. Data inna niż dzień podróży oznacza, że obraz nie potwierdza właściwych godzin; wtedy trzeba otworzyć szczegóły lub rozkład przewoźnika. Nie nazywać tego tablicą LIVE ani nie przedstawiać starego obrazu jako aktualnego. Obraz sam nie może odczytać zegara ani ukryć się po wygaśnięciu.

## Aktualizacja audytu

Po zmianie PDF należy odczytać i wizualnie sprawdzić wszystkie wiersze, oba kierunki oraz grupy dni. Dopiero wtedy uaktualnić adres, datę, SHA-256 i referencyjne godziny w `reference.json`, sprawdzić reprezentatywne dni z obu stron zmiany i uruchomić workflow. Nie dopasowywać referencji do GTFS w celu obejścia rozbieżności.

## Tablica najbliższych odjazdów

`board.py` publikuje `out/tablica.json` na 7 dni. API tablicy odrzuca plik starszy niż 30 godzin. Generowanie sprawdza cały zestaw linii obsługujących Most, aby nowa linia MLD nie została pominięta bez ostrzeżenia.

A4 i A65 wymagają zgodności GTFS z oficjalnym PDF. A7 ma sprawdzoną rozbieżność jednej minuty: jej rozkładowe godziny pochodzą z zatwierdzonego PDF, a tożsamość kursu z GTFS jest przypisywana tylko bijekcją w porządku godzin i przy różnicy nie większej niż 60 sekund. Zmieniony PDF blokuje ten mechanizm.

**Linia gminna:** plik użytkownika `linia-gminna.json` (`2026-09-22-r2`) kopiował godziny Domu Ludowego na Most. Oficjalny PDF z bieżącego katalogu Gminy Drwinia nie potwierdza odrębnego odjazdu z Mostu. Dlatego gminne odjazdy są publikowane wyłącznie jako **Ispina Dom Ludowy / Ispina III**. Sprawdzono wszystkie 4 grupy: oba kierunki w dni robocze i soboty, po 14 i 5 kursów; brak kursów w niedziele i święta. Źródło i skrót kontrolny: `gminna-reference.json`.

Audyt jest ważny do 30 listopada 2026. Nowe lub zmienione źródło wymaga ponownego porównania; do tego czasu godziny objętej nim linii są ukrywane. Nie dotyczy to Androida ani istniejącego planera.

Tablica publiczna: https://ispina-most-odjazdy.senikolo.chatgpt.site

Kod widoku i API znajduje się do wglądu w `tablica-site/`. To kopia plików aplikacji wdrożonej w Sites, poza projektem Android. Dane odjazdów aktualizują się z Actions bez ponownego wdrażania widoku. Zmiany kodu widoku wymagają osobnego wdrożenia istniejącego projektu Sites; workflow GTFS nie publikuje kodu aplikacji.

23.10.2026 użytkownik dopuścił tolerancję około 2 minut dla gminnej. Tablica prezentuje zatem te godziny jako **orientacyjne dla Mostu, według Domu Ludowego**, z oznaczeniem „ok.” przy każdej godzinie i jawnym opisem pochodzenia. Nie zmienia to identyfikacji w danych: źródłowym przystankiem pozostaje Dom Ludowy. Starsza sekcja gminna WordPressa zachowuje swoją treść.
