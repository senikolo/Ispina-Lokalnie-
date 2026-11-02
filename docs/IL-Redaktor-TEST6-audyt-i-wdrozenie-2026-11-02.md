# IL Redaktor Smart Professional TEST-6 (50) — audyt i stan wdrożenia

Data: 2 listopada 2026. Repozytorium: senikolo/Ispina-Lokalnie-. PR #30 (draft), oparty na PR #29.

## Poprawki zapisane w kodzie
1. WordPress MCP OAuth 2.1: domyślny zakres auth z instrukcji WordPress.com z 17.09.2026; opcjonalny zakres global tylko na wyraźne żądanie właściciela. Zapis wybranego zakresu razem z tymczasowym stanem PKCE także podczas powrotu z przeglądarki.
2. Zapamiętanie sygnatury edytora i oryginalnego żądania POST w szkicu oraz kopii. Po restarcie późniejsze lokalne edycje mają pozostać niewysłane, a wynik niepewnego zapisu nie może dawać fałszywego sukcesu.
3. ChatGPT Free: ręczne przekazanie polecenia i nowy przycisk Wklej i porównaj jednym kliknięciem. Żadnej automatycznej publikacji, żadnego płatnego API.
4. Radar: priorytetowe indeksy BIP, dokumentów PDF i zamówień; nie zwiększono liczby profili. Data publikacji, dziennik przeszukiwania i pamięć zostają zachowane.
5. CI: osobny workflow dla gałęzi TEST-6; Android API29 na GPU swangle z 3072 MB RAM w odpowiedzi na awarię systemu emulatora TEST-5.

## Ograniczenia
- Zakres auth jest udokumentowany przez WordPress.com, ale starsze próby MCP zgłaszały czasem wymaganie global. Bez testu prawdziwego konta nie można potwierdzić, która autoryzacja będzie skuteczna. Global może obejmować całe konto, więc nie jest domyślny.
- Radar wykrywa odnośniki do PDF, nie odczytuje pełnych dokumentów PDF ani OCR. Nie obiecuje znalezienia każdej publikacji.
- ChatGPT Free nie udostępnia płatnego API. Ręczny transfer tekstu zostaje częścią przepływu.
- Testy symulowane nie potwierdzają sesji WordPressa ani rzeczywistego zapisu szkicu.
- Klucz podpisu APK musi zgadzać się z instalacją na telefonie. Nie odinstalowywać programu ani nie usuwać danych bez wcześniejszych kopii.

## Warunki akceptacji stabilnej wersji
1. Zielone CI: testy JS, Android 8/10/11/13 oraz rzeczywiste wyszukiwanie na emulatorze. Błędy CI diagnozować i nie oznaczać jako PASS na podstawie samej kompilacji.
2. Eksport szkiców i pamięci Radaru na telefonie właściciela przed aktualizacją.
3. Rzeczywisty OAuth → odczyt własnych artykułów → niepubliczny szkic → POST → GET → potwierdzenie pól → podgląd. Bez publikowania tekstów testowych.
4. Zerwanie sieci po POST, restart i sprawdzenie bez powtórnej publikacji; późniejsze lokalne zmiany pozostają niewysłane.
5. Radar: 7/30/90 dni, Most/Autobusy/OSP/Cokolwiek nowego, dziennik na żywo i trwałość historii.
6. ChatGPT Free: przekazanie polecenia i świadome porównanie skopiowanej odpowiedzi; brak automatycznej akceptacji.
7. APK aktualizacyjna podpisana dotychczasowym certyfikatem i zbadana na telefonie bez reinstalacji.

Status tego dokumentu: kontrolna lista testowa. Wyniki CI i telefonu trzeba potwierdzić osobno, nie wpisywać zielonych ocen z góry.