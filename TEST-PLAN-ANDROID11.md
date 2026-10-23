# Test na Androidzie 11

Wykonaj po zbudowaniu `fdroidRelease`. Wariant testowy ma identyfikator `com.ispina.mldcheck.fdroid`, więc może być zainstalowany obok produkcyjnej aplikacji.

## Najważniejsze ścieżki

- Uruchom aplikację online i offline. Ekran, nawigacja, pogoda, odpady i poprzednio pobrany rozkład powinny zachować się przewidywalnie bez sieci.
- W planerze pobierz MLD. Sprawdź, czy pojawia się data pobrania, a po wyłączeniu internetu można znaleźć połączenie z zapisanych danych.
- Wyszukaj połączenie bezpośrednie i z przesiadką. Sprawdź przyjęcie podpowiedzi, zmianę przystanku, zamianę początku z celem, ustawienie daty i minimalnego czasu przesiadki.
- Potwierdź, że planer wyświetla ostrzeżenie o roboczych danych linii gminnej i oznacza jej szacowane czasy.
- Otwórz aktualności i historię. Sprawdź czytanie wpisu i strony, pauzę, wznowienie oraz zatrzymanie lektora.
- Włącz tryb Senior i wibrację; sprawdź czy przyciski i tekst pozostają wygodne.
- Włącz powiadomienia, wyślij powiadomienie testowe i sprawdź termin odpadu dzień wcześniej. Odmów zgody systemowej i sprawdź, czy aplikacja nadal się otwiera.
- Otwórz oficjalny player radia, stronę utrudnień i PDF odpadów. Wróć przyciskiem Androida: ma wrócić do ekranu startowego, a następne naciśnięcie zamknąć aplikację.

## Warunki akceptacji

- Brak awarii przy starcie, zmianie orientacji, braku sieci i braku polskiego głosu TTS.
- Rozkład z pustym lub wygasłym kalendarzem nie jest przedstawiany jako działające połączenie.
- Odnośniki zewnętrzne otwierają się poza aplikacją; zdalne strony nie zastępują interfejsu IL.
- Aplikacja instaluje się obok obecnej IL i nie prosi o podpis produkcyjny.
