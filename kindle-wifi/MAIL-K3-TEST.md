# Poczta i książki na Kindle Keyboard 3 — TEST 2.30.20

Rozszerzenie istniejącej aplikacji **Kindle przez Wi-Fi**. Telefon służy tylko do instalacji wtyczki przez SFTP. Po instalacji poczta łączy się z serwerem bezpośrednio przez Wi-Fi Kindle'a.

## Wersja 2.30.20 TEST — PIN i automatyczne załączniki (12.11.2026)

Po wejściu do sekcji **Poczta i książki** w KOReaderze pojawia się prośba o PIN (jeżeli konto i zapamiętane hasło zostały wcześniej skonfigurowane). Po poprawnym odblokowaniu wtyczka przez Wi-Fi Kindle'a sprawdza metadane **ostatnich 20 wiadomości**. Nie pobiera treści ani załączników podczas skanowania.

Jeżeli znajdzie niepobrane EPUB/PDF, proponuje przejście do listy, na której można zaznaczyć i odznaczyć pliki. **Dopiero po potwierdzeniu** pobiera zaznaczone załączniki do folderu **/mnt/us/documents/Poczta**. Zapamiętuje identyfikatory już zapisanych załączników (konto, UIDVALIDITY, UID i numer części) i nie proponuje ich ponownie. Pozostaje ręczne zapisywanie załączników z otwartej wiadomości oraz dotychczasowe odczytywanie, odpowiadanie i wysyłanie e-maili.

**Nowy PIN** ma dokładnie cztery cyfry i wymaga dwukrotnego wpisania. Zachowano odblokowywanie starszych zapisów 4–8-cyfrowych. Po trzech błędnych próbach następuje minutowa blokada w bieżącej sesji. Cztery cyfry to wygoda, nie silna ochrona przed osobą mającą dostęp do pamięci Kindle.

**Pierwsza konfiguracja:** w sekcji **Konto i połączenie** wpisz adres e-mail, serwery IMAP/SMTP i hasło aplikacji. Następnie wybierz **Zapamiętaj hasło (utwórz PIN)**. Po ponownym wejściu do poczty użyj PIN-u. Bez konta, hasła i prawidłowego TLS automatyczne skanowanie nie może działać.

**Ograniczenia:** automatyczne skanowanie dotyczy ostatnich 20 wiadomości; inne pliki można zapisać po ręcznym otwarciu wcześniejszego e-maila. Obowiązuje limit 10 MiB rozkodowanego załącznika. Nowe funkcje nie były testowane na fizycznym Kindle K3; wynik testów automatycznych nie gwarantuje zgodności OpenSSL, sieci IMAP i interfejsu z konkretnym czytnikiem.

Po zainstalowaniu testowego APK na Androidzie otwórz w nim **Poczta i książki → Zainstaluj lub zaktualizuj pocztę**, a następnie uruchom ponownie KOReadera. Telefon nie jest potrzebny podczas korzystania z poczty, tylko do instalacji modułu.

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

## Diagnostyka VOX FM — 2.30.1

Po uruchomieniu MPlayera Kindle wykazał rozpoznanie MP3 (64 kb/s), inicjalizację ALSA (44,1 kHz stereo) i komunikat Starting playback. Nie dowodzi to odsłuchu w głośniku; połączenie później przestało być aktywne. Wersja 2.30.1 zapisuje kod wyjścia dekodera także dla radia, odróżnia zakończenie transmisji od braku silnika, nie odłącza aktywnego radia na podstawie niewiarygodnego licznika czasu MPlayera, poprawia token PID timera i dodaje alternatywny CDN VOX FM (ic2.smcdn.pl).

Weryfikacja na fizycznym Kindle K3 nadal wymagana: czas działania radia, automatyczny powrót po chwilowej utracie Wi‑Fi, brak gry po wyjściu z KOReadera, klawisze głośności i timer.

## Naprawa błędnego „Serwer odrzucił adres” — 2.30.2

- Diagnostyka rozróżnia rzeczywiste HTTP 4xx, błędy serwera 5xx/429, przekierowania, timeout i niejednoznaczny błąd próbkowania. Nie wolno nazywać każdego nieznanego kodu „odrzuceniem adresu”.
- Jeśli starszy MPlayer nie potrafi pobrać próbki znanego strumienia HTTP MP3, ale znane są jego kodek i bitrate mieszczący się w ustawionym limicie, system może wykonać pojedynczą bezpośrednią próbę odtwarzania. Nie dotyczy to nieznanych adresów, przekroczeń bitrate ani rzeczywistych błędów HTTP, DNS i TLS.
- VOX FM: na serwerze Icecast potwierdzono adres 3990-1.mp3 przy 128 kb/s oraz 3990-2.aac przy 48 kb/s. Przy limicie 64 kb/s nie wolno automatycznie przełączać na MP3 128 kb/s. Użytkownik musi zezwolić na wyższy transfer lub uruchomić działający wariant AAC.
- Testy obejmują przypadki HTTP 200 + błąd próbkowania, HTTP 403/404/429/503 oraz próbę odtworzenia znanego MP3 bez próbki.

## Naprawa regresji 2.30.3

- Radio: przy niejednoznacznym błędzie pobierania próbki MP3 (także timeout starszego MPlayera) dopuszczana jest kontrolowana próba bezpośrednia, tylko dla HTTP MP3 z katalogu i znanym bitrate'em. Potwierdzone HTTP 4xx/5xx, DNS i TLS nadal są respektowane.
- W menu każdej stacji jest opcja „Spróbuj MP3 (tryb zgodności)”. Wariant przekraczający limit transferu wymaga osobnej zgody i nie zmienia zapamiętanych ustawień.
- Poczta: konfiguracja konta pozostaje otwarta przy kolejnych edycjach parametrów IMAP, SMTP, adresu i hasła. Po zapisie widać potwierdzenie.
- Nadal nie przeprowadzono fizycznego testu na Kindle Keyboard 3 ani testu IMAP/SMTP na koncie użytkownika.

## Audyt i poprawki 2.30.4 (zdjęcia z Kindle K3, 11.11.2026)

- MP3 i audiobooki: nie zatrzymuj rzeczywiście aktywnego odtwarzacza wyłącznie po 5 cyklach bez tekstowego potwierdzenia odtwarzania. Po rzeczywistym zakończeniu lub błędzie wciąż uruchamiany jest zapasowy silnik.
- Radio: opóźnione logi MPlayera nie powodują fałszywego błędu buforowania, gdy proces nadal żyje i kontroler nie zgłasza awarii. Dla radia HTTP MP3 MPlayer korzysta z wybranego bufora 64/128/256 KiB; dla starszego AAC pozostaje tryb bez cache.
- Głośność: dodano oddzielne ustawienie wzmocnienia programowego MPlayera: 110% (dotychczasowy poziom), 140% lub 170%. Nowe ustawienie działa po ponownym uruchomieniu dźwięku. Wyższe wartości mogą przesterować dźwięk, dlatego domyślnie pozostaje 110%.
- Diagnostyka: odczyt poziomów ALSA Master/PCM/Speaker/Headphone bez modyfikowania miksera.
- Timer: numeryczny token procesu umożliwia skuteczne anulowanie timera.
- Poczta: zakodowanie Base64 pustych nagłówków Reply-To lub Message-ID nie może już wywołać `backend.lua:15 attempt to index a nil value`.
- Nadal brak odsłuchu na fizycznym Kindle K3. W szczególności zrzut ekranu z kodem wyjścia MPlayera `2` nie pozwala bez ostatnich linii logu jednoznacznie rozstrzygnąć przyczyny zakończenia procesu.

## Wersja 2.30.6 TEST — ECO 128 i załączniki EPUB/PDF (12.11.2026)

- Radio ma **twardy limit 128 kb/s** również w trybie Wysoka jakość, przy starszych ustawieniach telefonu, niestandardowych adresach i w jednorazowym trybie zgodności. Nie uruchamia automatycznie strumieni 160/256/320 kb/s.
- Przy pierwszym uruchomieniu wtyczki po aktualizacji ustawiany jest profil Automatyczny + Najmniejszy transfer + 128 kb/s. W kolejnych uruchomieniach użytkownik może wybrać niższy limit.
- Odtwarzacz najpierw sprawdza oszczędne warianty AAC, a potem dostępne MP3 do 128 kb/s. Niepowodzenie AAC nie oznacza zgody na MP3 ponad limit.
- Dodano oficjalne Radio Wnet 32/64 kb/s AAC oraz **testowe** regionalne warianty Radia Kraków Tarnów i Nowy Sącz, opisywane w zewnętrznych katalogach jako 96 kb/s. Mogą nadawać częściowo inny program; serwer i dekoder Kindle nadal muszą przejść test.
- Gdy stacja nie ma zweryfikowanego źródła do 128 kb/s, może pozostać niedostępna. Program nie zmniejsza sztucznie przepływności strumienia 256 kb/s.
- Poczta: naprawiono odrzucanie poprawnych załączników komunikatem `Attachment size changed`. Rozmiar z `BODYSTRUCTURE` jest traktowany orientacyjnie; pobieranie sprawdza rzeczywistą długość literału IMAP, identyfikator wiadomości oraz limity 20 MiB transferu i 10 MiB po rozkodowaniu. Testy obejmują różne rozmiary deklarowane i przerwaną transmisję.
- Po zainstalowaniu APK na telefonie wybierz instalację/aktualizację **obu** wtyczek w KOReaderze (Radio oraz Poczta), a następnie uruchom ponownie KOReadera. Stary EPUB testowy w skrzynce może posłużyć ponownie do sprawdzenia.
- Uwaga: testy automatyczne nie zastępują weryfikacji na fizycznym Kindle Keyboard 3 Wi-Fi. Podczas testu radio powinno grać dłużej niż kilka minut, a EPUB zapisać się w `/mnt/us/documents` i otworzyć w KOReaderze.
