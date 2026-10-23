# Automatyczne rozkłady Ispina Lokalnie

Niezależny od Androida generator planowych odjazdów A4, A7 i A65. Nie wymaga haseł do WordPressa ani płatnych wtyczek. Wyniki: `out/README.md` (dostępne tekstowe tabele), `out/rozklady.json` i sześć obrazów dla trzech przystanków, na dwa dni.

## Zasada publikacji

Pobierany jest **operacyjny MLD GTFS.zip** z oficjalnego indeksu <https://gtfs.kolejemalopolskie.com.pl/>. Stary handlowy `ald-gtfs.zip` z głównej witryny KMŁ ma nieaktualne godziny i nie jest używany jako źródło zapasowe.

Dla każdego dnia i każdej linii osobno generator porównuje wszystkie odjazdy na sześciu stanowiskach z ręcznie sprawdzonymi tabelami PDF. Wymagana jest pełna zgodność godzin oraz liczby kursów w obu kierunkach. Błąd jednej linii ukrywa jej wszystkie godziny. Pozostałe zgodne linie mogą być opublikowane. `status=partial` oznacza częściową tablicę, a nie pełny wynik pozytywny. `valid=true` wymaga zgodności wszystkich trzech linii na oba dni.

Przed kontrolą godzin sprawdzany jest aktualny katalog PDF przewoźnika oraz suma SHA-256 odpowiedniego dokumentu. Nowy PDF, zmienione bajty, nieznane identyfikatory, nieważny kalendarz, błąd pobierania lub koniec okresu kontroli blokują dane. Referencje w `reference.json` są sprawdzone do 30 listopada 2026 r.; potem wymagają ponownego audytu. Nie należy przedłużać tego terminu bez sprawdzenia nowych PDF i kalendarzy świątecznych.

## Przystanki w operacyjnym GTFS

| Nazwa na stronie | Nazwa w GTFS | W stronę Nowego Brzeska | W stronę Bochni / Podłęża | parent_station |
| --- | --- | --- | --- | --- |
| Ispina Wiślisko | Ispina Wiślisko | 4222 | 4076 | 12515000000 |
| Ispina Dom Ludowy | Ispina Dom Ludowy | 4533 | 4352 | 12516000000 |
| Ispina Most | Ispina | 119 | 67 | 10216000000 |

Mapowanie Most ↔ Ispina sprawdzono na podstawie współrzędnych, kodów 4372/4373, kolejności przystanków i godzin A4/A65 w oficjalnych PDF. Identyfikatorów ze starego handlowego feedu nie wolno stosować do feedu operacyjnego. A7 nie obsługuje Wiśliska i Domu Ludowego; ma również przystanki Przedszkole i Leśniczówka, poza zakresem tych trzech tablic.

## Audyt 23 października 2026 r.

Sprawdzono 23, 24, 25, 26 i 31 października oraz 1, 11 i 30 listopada. A4 i A65 są w pełni zgodne na wszystkich stanowiskach. A7 ma rozbieżności w kierunku Nowego Brzeska/Proszowic, dlatego jej godziny są ukryte. Sam pomyślny odczyt GTFS nie potwierdza poprawności rozkładu.

14 testów zabezpieczeń obejmuje kalendarze, usunięcia/dodania kursów, zmianę czasu, święto 11 listopada, przejazd po północy, brak możliwości wsiadania, zmianę identyfikatora, błędne czasy, zmianę PDF i wygaśnięcie kontroli.

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
