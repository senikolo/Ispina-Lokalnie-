# Kindle przez Wi-Fi 2.28.0 — Radio i diagnostyka

## Gotowy wynik

Przygotowano instalowalny APK tej samej aplikacji, z tym samym identyfikatorem `pl.ispinalokalnie.kindlewifi.v23`, numerem wersji 2.28.0 i kodem 45. Nie powstała osobna aplikacja radiowa. APK zawiera zaktualizowaną wtyczkę IL Radio i zachowane pozostałe moduły. Osobny ZIP wtyczki służy jako kopia; telefon instaluje ją bezpośrednio przez Wi-Fi.

Podstawą były źródła 2.26, poprawki wskazanej gałęzi do 2.27.4 oraz najnowsza wtyczka 2.27.6. Dzięki temu nie cofnięto późniejszych napraw klawiszy, zatrzymywania procesów i odtwarzacza plików. Źródła udostępniono w czytelnym katalogu `kindle-wifi` zamiast uzależniać dalszą pracę od odtwarzania serii patchy.

## Aktualizacja bez komputera

1. Zatrzymaj radio i odtwarzanie audiobooka na Kindle.
2. Pobierz i zainstaluj `Kindle-przez-WiFi-2.28.0.apk` na telefonie jako aktualizację. Nie usuwaj obecnej aplikacji. Certyfikat porównano z wydaniem 2.27.6 i jest identyczny. Jeśli używasz dawnej, osobnej aplikacji „Radio TEST”, ma ona inny identyfikator — główna aplikacja pozostaje osobną instalacją.
3. Połącz telefon i Kindle z tą samą siecią Wi-Fi. Użyj dotychczasowego połączenia w aplikacji.
4. Wybierz **Radio → Zainstaluj lub zaktualizuj IL Radio**. Instalator sprawdza pliki i zachowuje kopię poprzedniej wtyczki.
5. Uruchom ponownie KOReadera. Otwórz **Narzędzia → Więcej narzędzi → Radio i audiobooki · v2.28.0**.
6. Na początek wybierz **Oszczędny**, limit **48 kb/s**, kodek **Według trybu**. Jeżeli masz działający MPlayer, pozostaw go jako preferowany silnik. Uruchom RMF FM lub RMF Classic.
7. Sprawdź **Co gra?** i **Szczegóły źródła**. Po rzeczywistym odsłuchu wybierz **Potwierdź, że słychać dźwięk**.

Gdy aktualizacja wtyczki okaże się nieudana, użyj w telefonie opcji **Przywróć kopię IL Radio** i ponownie uruchom KOReadera. Nie trzeba przywracać całej konfiguracji czytnika.

## Co zawiera wersja

Zachowano cztery tryby jakości, limity 24/32/48/64/96/128/bez limitu, wybór preferowanego kodeka, najmniejszy transfer, edycję własnych adresów na Androidzie, podgląd katalogu, wysyłanie ustawień i instalację wtyczki przez Wi-Fi. „Bez limitu” nie znosi ograniczenia trybów Ultraoszczędnego i Oszczędnego; dotyczy Auto i Wysokiej jakości. W trybie oszczędnym AAC ma limit 48, a MP3 jest wariantem zapasowym do 64 kb/s; ręczny limit 48 ogranicza także MP3.

Zachowano sterowanie radiem, stacje poprzednią/następną, pauzę i stop, ekranowe przyciski głośności z możliwością ukrycia, obsługę klawiszy K3, ulubione, zapamiętywanie stacji oraz ograniczony mechanizm ponownych prób. Wtyczka dopuszcza maksymalnie osiem uruchomień silnika i sprawdza ograniczoną kolejkę źródeł. Wymagające przekroczenia limitu źródło wymaga jednorazowego wyboru użytkownika.

Nowe zmiany w 2.28.0:

- Ponownie sprawdzono katalog i rozdzielono potwierdzone próbki od adresów wymagających kolejnej próby. Zachowano wszystkie 42 stacje. Dodano kandydatów z oficjalnego API RMF oraz adres AAC Radia ZET z jawnym oznaczeniem zewnętrznego operatora i niepotwierdzonego powiązania.
- Usunięto możliwość rozpoczęcia transmisji w ramach skończonego limitu tylko na podstawie wpisu katalogowego, gdy brakuje narzędzia do pobrania i sprawdzenia próbki. Zapobiega to obchodzeniu limitu przez nieaktualny katalog.
- Dodano własne, syntetyczne próbki HE-AAC v1 32 kb/s i HE-AAC v2 24 kb/s, sprawdzone dodatkowym dekoderem FDK. Każdą próbkę można uruchomić osobno w każdym wykrytym silniku.
- Próba HE-AAC wymaga odpowiedniego wyjścia PCM: WAV 44,1 kHz stereo i odpowiedniej długości. Samo powstanie małego pliku lub odkodowanie rdzenia 22,05 kHz nie daje już pozytywnego wyniku tych prób.
- Doprecyzowano ekran źródła: serwer i parametry pomiaru dotyczą próby przed rozpoczęciem odtwarzania. Nie są przedstawiane jako pewna informacja o późniejszej, osobnej sesji CDN.
- Android pokazuje pomiar bitrate’u, format, datę i adres po przekierowaniu próby oraz informację o niepotwierdzonych adresach.
- Dodano do diagnostyki pamięć procesów bieżącej sesji, bez stałego monitorowania w tle.
- Nazwa ustawienia bufora wyjaśnia, że działa ono zależnie od silnika. MPlayer zachowuje `-nocache`, ponieważ jego stary port miał problemy z cache i nieprzewijalnym AAC. Nie obiecujemy, że suwak steruje buforem każdego silnika.

## Sprawdzenie stacji

Przebadano 85 adresów. W ostatniej serii 40 prób pozwoliło potwierdzić format i parametry próbki; pozostałe wymagają ponownego sprawdzenia. Timeout z tego środowiska nie dowodzi trwałego wyłączenia adresu. Pełne adresy, przekierowania, nagłówki i wyniki są w plikach źródłowych `reports/catalog-evidence.json` i `streams.tsv`.

| Stacja | Wynik dla małego transferu |
|---|---|
| RMF FM | Potwierdzona próbka HE-AAC v2, nominalnie 48 kb/s |
| RMF Classic | Potwierdzona próbka HE-AAC v2, nominalnie 48 kb/s |
| RMF MAXX | Potwierdzona próbka HE-AAC v2, nominalnie 48 kb/s |
| RMF Chillout | Potwierdzony HE-AAC około 64 kb/s; nie potwierdzono wariantu do 48 |
| Radio ZET | Nie potwierdzono wariantu do 48; adres AAC operatora wymaga kolejnej próby |
| Chillizet / Chilli ZET | Nie potwierdzono wariantu do 48 |
| Antyradio | Nie potwierdzono wariantu do 48 |
| TOK FM | Potwierdzony zapasowy MP3 64 kb/s; brak potwierdzonego AAC do 48 |
| ESKA Kraków | Potwierdzony HE-AAC, nominalnie 48 kb/s |
| ESKA Warszawa | Potwierdzony HE-AAC, nominalnie 48 kb/s |
| ESKA ROCK | Potwierdzony HE-AAC, nominalnie 48 kb/s |
| Radio Kraków | Potwierdzony MP3 256 kb/s; nie potwierdzono wariantu do 48 |
| Jedynka PR1 | Nie potwierdzono wariantu do 48; istniejące adresy zachowano do próby |
| Dwójka PR2 | Nie potwierdzono wariantu do 48; istniejące adresy zachowano do próby |
| Trójka PR3 | Nie potwierdzono wariantu do 48; istniejące adresy zachowano do próby |
| Radio 357 | Nie potwierdzono wariantu do 48; istniejące adresy zachowano do próby |
| Radio Nowy Świat | Nie potwierdzono wariantu do 48; istniejące adresy zachowano do próby |
| VOX FM | Potwierdzony HE-AAC, nominalnie 48 kb/s |

**Nie znaleziono potwierdzonego, działającego wariantu tych stacji 24 lub 32 kb/s.** Nie oznacza to dowodu, że żaden nadawca takiego wariantu nie udostępnia. Nie dopisano fikcyjnych adresów. Próbki 24/32 kb/s w diagnostyce są plikami testowymi, nie transmisją stacji. Tryb Ultraoszczędny może więc poprosić o zmianę limitu zamiast uruchomić daną rozgłośnię.

Wartości około 48 kb/s mogą chwilowo wynosić np. 48,2–48,4 w krótkiej próbce. Ekran zachowuje pomiar i osobno wartość nominalną serwera. Ograniczenie jest wyborem rzeczywistego wariantu, nie ograniczaniem tempa pobierania ani przekodowywaniem na czytniku.

## Kodeki i silniki

Ważne ustalenie audytu: FFprobe 7.1.5 w tym środowisku oznaczał część plików jako LC mimo występującego SBR i PS. Kontrola niezależnym dekoderem FDK potwierdziła te rozszerzenia. Dlatego samą etykietę FFprobe uznano za niewystarczającą; wcześniejszy wniosek o braku HE-AAC w próbkach nie jest rozstrzygający.

| Silnik | Co potwierdzono | Co wymaga Kindle |
|---|---|---|
| MPlayer z dotychczasowego instalatora | Archiwum ma oczekiwany SHA-256. Plik ELF to ARM EABI5 z atrybutami ARMv6; zawiera symbole FAAD i obsługę SBR. To istniejący port Kindle z MobileRead, nie dołączona nowa binarka. | Uruchomienie na firmware 3.4.3, AAC-LC, HE-AAC v1/v2, M4A, ADTS, ALSA i klawisze. Statyczne symbole nie dowodzą powodzenia odtwarzania. |
| GStreamer 0.10 | Wtyczka wykrywa programy i dostępne elementy, korzysta z dekodera systemowego lub pobierania do FIFO. | Obecność `faad`/`ffdec_aac`, `qtdemux`, wyjścia audio i transportu na konkretnym czytniku. |
| GStreamer 1.x | Zachowana obsługa, jeśli jest zainstalowany; sprawdzane elementy obejmują `avdec_aac`. | Dostępność binarki i dekoderów dla K3; nie założono ich obecności. |
| mpg123 | Silnik MPEG Audio/MP3; selektor nie kieruje do niego AAC. | Działanie zainstalowanego portu MP3 i jego wyjścia audio. |
| mpv | Zachowana ścieżka wyboru i diagnostyki, jeśli dostępny. | Nie dostarczono i nie uruchomiono binarki mpv na K3. |

Obsługa HTTPS zależy również od wersji curl/wget, certyfikatów i TLS, nie tylko od dekodera. Nie wyłączono sprawdzania certyfikatów. Diagnostyka rozróżnia transport, TLS, adres, sieć, buforowanie i dekoder. Sam proces odtwarzacza nie jest traktowany jako potwierdzenie słyszalnego dźwięku.

Źródła techniczne: [port MPlayera na MobileRead](https://www.mobileread.com/forums/showthread.php?t=119851), [GStreamer avdec_aac](https://gstreamer.freedesktop.org/documentation/libav/avdec_aac.html), [mpg123](https://www.mpg123.de/), [oficjalny katalog RMF FM](https://api.rmfon.pl/stations/5/streams). Odpowiedzi API RMF dla trzech głównych stacji zapisano w raporcie źródłowym. Adres IP lub niepotwierdzone powiązanie CDN nie otrzymuje automatycznie etykiety „oficjalny serwer”.

## Wykonane testy

| Obszar | Wykonanie i wynik |
|---|---|
| Budowanie Androida | Pełne `assembleRelease`, wynik poprawny; wersja telefonu poniżej 30 MB |
| Podpis APK | `apksigner`: poprawne v2 i v3; sprawdzenie zakresu API 26–35. `zipalign`: poprawne. Certyfikat identyczny z APK 2.27.6 |
| Zawartość APK | Pliki wtyczki zgodne bajt w bajt z wydanym ZIP-em |
| Polityka jakości | Lua: tryby, limity, priorytety, ramki AAC/MP3 i fałszywy deklarowany bitrate — poprawnie |
| Menu i klawisze | Lua z kontrolowanymi komponentami: nowy i starszy moduł wejścia KOReadera — poprawnie |
| Brak możliwości sprawdzenia próbki | Test potwierdza, że skończony limit nie dopuszcza startu na podstawie samego katalogu |
| AAC-LC, HE-AAC, M4A, MP3 | Siedem plików diagnostycznych zdekodowano do PCM na hoście z podstawionym odtwarzaczem. Wyjście tylko 22,05 kHz odrzucono w testach HE |
| Sieć | Lokalny serwer: przekierowanie HTTP, poprawne HTTPS z zaufanym certyfikatem, odrzucony certyfikat, 404, brak serwera i timeout — poprawnie |
| Przesyłanie | Kontrolny serwer SSH/SFTP z rzeczywistym podsystemem OpenSSH: 2 MB, Unicode, sumy kontrolne, retry, brak nadpisania, uszkodzenie i ucięty plik — poprawnie |
| Menedżer plików i audio | SFTP: zmiana nazwy, przeniesienie, usuwanie, metadane czytania, ochrona ścieżek — poprawnie |
| Wtyczki | SFTP: instalacja, aktualizacja, przywrócenie i usunięcie; obca wtyczka zachowana — poprawnie |

Nie wykonano tych testów na fizycznym Kindle ani na telefonie użytkownika. Sprawdzenie podpisu i manifestu nie jest testem uruchomienia na Androidzie 8/10/11/13. Dodano odrębny workflow emulatorów tych czterech wersji; jego stan należy sprawdzać osobno, bez traktowania definicji workflow jako zaliczonego testu.

## Pamięć, procesor i bateria

Zachowano ograniczone logi, małe próbki, brak przekodowywania na Kindle i brak osobnego odpytywania serwera o metadane. Typowy nadzór ustabilizowanego radia odbywa się co 20 sekund; nie odświeża stale ekranu. Kontroler ma ograniczony budżet prób. PCM diagnostyczny jest tymczasowy i ma ograniczenie czasu i rozmiaru. Dodany odczyt pamięci działa na żądanie.

Nie wykonano pomiaru energii ani wielogodzinnej próby na K3. Nie można rzetelnie obiecać, że HE-AAC zużyje mniej baterii niż MP3: mniejszy transfer może być równoważony kosztem dekodowania. 48 zamiast 128 kb/s oznacza o 62,5% mniej danych samego audio; nie obejmuje narzutu protokołu i nie oznacza 62,5% oszczędności baterii.

## Co przetestować na własnym K3

1. Instalacja wtyczki z telefonu i widoczny numer 2.28.0 po restarcie KOReadera.
2. W menu prób dekodera: AAC-LC 48, HE-AAC v1 32, HE-AAC v2 24, M4A i MP3 64. Wybierz MPlayer, następnie systemowy GStreamer; zanotuj różnice.
3. RMF FM i Classic przy limicie 48. Sprawdź źródło, słyszalny dźwięk i brak samoczynnego przejścia na MP3 128.
4. Przy limicie 32 potwierdź, że radio pyta o zgodę na wyższy wariant. Wybór odmowy ma pozostawić limit.
5. Sprawdź fizyczne +/−, poziom w menu oraz przyciski ekranowe. Dopiero po udanym teście ukryj ekranowe.
6. Pauza, wznowienie, następna stacja, Stop. Po Stop nie powinno być dźwięku; kolejna stacja nie może nakładać się na poprzednią.
7. Wyłącz na chwilę Wi-Fi podczas audycji i włącz ponownie. Sprawdź ograniczone próby oraz czytelny komunikat przy dłuższej przerwie.
8. Wznów audiobook, zmień rozdział i sprawdź pozycję po restarcie KOReadera.
9. Prześlij mały EPUB i MP3, zmień nazwę pliku; sprawdź zegar, pogodę i ustawienia czytania.
10. Po krótkich próbach przeprowadź odsłuch przez 2–3 godziny. Zanotuj przerwy, zmianę baterii i pamięć w diagnostyce. Do porównania AAC/MP3 użyj tego samego poziomu głośności i warunków sieci; pojedynczy wynik nie wystarcza do oceny baterii.

Osiągnięto etap gotowych plików instalacyjnych i sprawdzonego działania dostępnych elementów na hoście. Zgodność z rzeczywistym zestawem dekoderów, odsłuch, klawisze i stabilność sprzętowa pozostają do potwierdzenia na czytniku.
