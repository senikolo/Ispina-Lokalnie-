# Poczta i książki na Kindle Keyboard 3 — TEST 2.30.0

Rozszerzenie istniejącej aplikacji **Kindle przez Wi-Fi**. Telefon służy tylko do instalacji wtyczki przez SFTP. Po instalacji poczta łączy się z serwerem bezpośrednio przez Wi-Fi Kindle'a.

## Nowości 2.30.0

- **Zapamiętaj hasło** — po wpisaniu hasła aplikacji do poczty w KOReaderze wybierz **Konto i połączenie → Zapamiętaj hasło (utwórz PIN)**. Ustal własny PIN 4–8 cyfr i powtórz go. Hasło aplikacji zostaje zaszyfrowane przez PBKDF2-HMAC-SHA256 (180 tys. iteracji) i AES-256-GCM z solą i nonce z `/dev/urandom`. W ustawieniach zapisany jest wyłącznie zaszyfrowany rekord.
- **Odblokuj pocztę (PIN)** — po ponownym uruchomieniu KOReadera nie trzeba wpisywać 16 znaków hasła aplikacji, wystarczy wybrany PIN. Po trzech błędnych próbach minutowa blokada w bieżącej sesji.
- **Zablokuj pocztę** usuwa hasło z aktywnej sesji. **Usuń zapamiętane hasło i PIN** kasuje zapis.
- **Odpowiedz na wiadomość** — otwórz wiadomość w Odebranych, następnie wybierz odpowiadanie w menu. Wtyczka wykorzystuje `Reply-To` lub `From`, ustawia temat `Re:`, dodaje nagłówki `In-Reply-To` i `References`, jeżeli identyfikator wiadomości jest poprawny.
- **10 polskich krótkich odpowiedzi**; każdą można edytować przed wysłaniem. **Napisz własną odpowiedź** oraz możliwość zapisania maksymalnie 10 własnych szablonów. Przed wysłaniem pojawia się potwierdzenie.

## Bezpieczeństwo i ważne ograniczenia

**PIN czterocyfrowy to wygodna ochrona przed przypadkowym dostępem, nie silna ochrona przed kimś, kto ma fizyczny dostęp do plików Kindle'a.** Przeciwnik dysponujący kopią zaszyfrowanego hasła może próbować odgadywać czterocyfrowy PIN poza urządzeniem. Wybierz PIN dłuższy niż cztery cyfry i nie korzystaj z przykładowego `2282`. Do poufnej poczty nie zaleca się tego rozwiązania.

Szyfrowanie działa **wyłącznie, jeśli w KOReaderze dostępne są natywne biblioteki OpenSSL z AES-GCM i PBKDF2**. Wtyczka nie stosuje zapisu otwartym tekstem w razie braku tych bibliotek. Nie można odzyskać zapisanego hasła bez właściwego PIN-u — w razie zgubienia PIN-u usuń zapis i ponownie wpisz hasło aplikacji Google.

Wersja eksperymentalna. Brak testów na fizycznym Kindle Keyboard 3. Użytkownik musi sprawdzić dostępność LuaSec/SSL i działanie IMAP/SMTP u swojego dostawcy. Serwery: IMAP TLS 993, SMTP TLS 465; treść e-mail do 5000 znaków; ostatnie 12 wiadomości; załączniki EPUB/PDF do 10 MB w `/mnt/us/documents`.

## Instalacja testowa

W aplikacji Android **Kindle przez Wi-Fi – Poczta 2.30.0 TEST** (oddzielna, podpisana kluczem testowym, nie zastępuje instalacji głównej), po sparowaniu przez Wi-Fi wybierz **Poczta i książki → Zainstaluj lub zaktualizuj pocztę**. Po komunikacie o powodzeniu uruchom ponownie KOReadera. Menu: **Narzędzia → Więcej narzędzi → Poczta i książki · TEST**.

Ważne: dostawcy usług mogą blokować IMAP/SMTP, a Gmail wymaga włączenia weryfikacji dwuetapowej i utworzenia hasła aplikacji. Wtyczka nie obsługuje OAuth.

Nie scalaj zmian do gałęzi stabilnej przed testami na fizycznym urządzeniu.

## Audyt 11.11.2026 — zmiany 2.30.0

- IL Radio: timer 30, 60, 90, 120 minut działa w osobnym lekkim procesie powłoki i wyłącza dźwięk także gdy menu nie jest otwarte. Można go anulować. Zamykanie KOReadera usuwa timer.
- Radio nie uznaje dwóch kolejnych identycznych odczytów pozycji za wystarczającą przyczynę zerwania odtwarzania. Wymaga dłuższego zacięcia po uzyskaniu stabilnego odtwarzania.
- Menu Radio/MP3 ma mniej powtarzających się pozycji; stan, format, głośność i utwór w prostym widoku, szczegóły w osobnej diagnostyce.
- Poczta: diagnostyka obecności bibliotek sieciowych i certyfikatów, czytelniejsze błędy połączenia, ograniczenie powtórnych operacji, ostrzeżenie przed przykładowym PIN 2282. PIN należy ustalić samodzielnie.
- Weryfikacja w CI wymaga działającego interpretera Lua i faktycznie uruchamia testy pocztowe oraz radiowe. Brak Lua nie może już dać fałszywego sukcesu.
- **Nadal wymagane testy na Kindle K3**: IMAP/SMTP, OpenSSL i AES-GCM, klawisze głośności, odtwarzanie radia przez 2 godziny, reakcja na utratę Wi-Fi i pracę timera przy wygaszonym ekranie.
- Testowy APK ma odrębny identyfikator pakietu i nie usuwa wcześniejszej aplikacji.
