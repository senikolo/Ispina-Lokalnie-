Kindle przez Wi-Fi / IL Radio i MP3 2.27.6

Instalacja i aktualizacja

1. Zainstaluj APK na telefonie jako aktualizację dotychczasowej aplikacji.
2. W aplikacji zaktualizuj lub ponownie zainstaluj wtyczkę Radio i audiobooki na połączonym Kindle.
3. Uruchom ponownie KOReader. Menu powinno pokazać wersję 2.27.6.

ZIP jest alternatywną paczką do ręcznej aktualizacji. Po zatrzymaniu radia zastąp folder ilradio_k3.koplugin w /mnt/us/koreader/plugins/, a następnie uruchom ponownie KOReader.

Poprawki

- Prawidłowy podpis APK v1/v2/v3, zgodny z dotychczasowym certyfikatem.
- Wyrównana tablica zasobów i spójny numer wydania 2.27.6.
- Zaokrąglanie krótkiej próbki AAC pozwala wybrać nominalny wariant 48 kb/s. Szczegóły nadal pokazują zmierzony bitrate.
- Tryb „Najmniejszy transfer” porównuje bitrate także pomiędzy różnymi kodekami.
- Wget zachowuje nagłówki, rozróżnia błędy TLS, sieci i adresu oraz obsługuje wersje bez opcji -S. Gdy nie można ustalić serwera po przekierowaniu, interfejs podaje to wprost.
- Zatrzymywanie obejmuje procesy potomne i wymusza zakończenie zawieszonego silnika. Komunikat sukcesu wymaga potwierdzenia zakończenia sesji.
- „Co gra?” potrafi odtworzyć dane aktywnej sesji radiowej po utracie stanu interfejsu.
- Obsługa istniejących nazw klawiszy głośności, globalnej ścieżki zdarzeń i wykrywania wejścia według jego możliwości.
- Lokalne pliki korzystają z zapasowych silników, jeżeli pierwszy silnik nie uruchomi odtwarzania.
- Audiobooki preferują MPlayer, który obsługuje pozycję i wznowienie. Odczyt pozycji czeka na świeżą odpowiedź.
- MP3 pokazuje tytuł i wykonawcę z tagów ID3v1 oraz ID3v2.2–2.4, w tym polskie znaki.
- Kolejka jest zapamiętywana. Po normalnym zakończeniu pliku odtwarzacz przechodzi do następnego utworu lub rozdziału.
- Próbki diagnostyczne 24 i 32 kb/s są prawidłowo opisane jako AAC-LC.

Sprawdzenie

Przeszły testy logiki w Lua, polskich tagów ID3, kolejki, zapasowego silnika, zdarzeń głośności i rozróżniania końca pliku od błędu. Sprawdzono kontroler MPlayera z podstawionym procesem, świeży odczyt pozycji, pauzę, wznowienie, głośność, zatrzymywanie zawieszonego procesu, sprzątanie pobierania oraz prawdziwy wget z lokalnym serwerem HTTP, nagłówkami, przekierowaniem i odpowiedzią 404. Podpis APK sprawdzono narzędziem Android apksig dla API 26, 29, 30, 33 i 34.

Ograniczenia wymagające próby na urządzeniu

- Nie przeprowadzono instalacji na fizycznym telefonie ani odsłuchu na Kindle K3. Dostępność obecnych adresów stacji nie została ponownie potwierdzona.
- Wznawianie audiobooków wymaga MPlayera. Jeżeli go brakuje, dostępny silnik odtworzy plik od początku i interfejs o tym poinformuje. W aplikacji pozostaje funkcja instalacji MPlayera dla Kindle K3.
- Zmiana głośności na żywo wymaga MPlayera albo działającego miksera systemowego. W przypadku braku obsługi pojawia się informacja zamiast cichej zmiany samego ustawienia.
- Dołączone próbki offline testują AAC-LC i MP3. Obsługę HE-AAC oraz HE-AAC v2 należy sprawdzić na odpowiedniej stacji. Nazwy dwóch plików testowych zachowano dla zgodności z instalatorem.
- Nowa paczka nie zawiera osobnego silnika dekodującego. Korzysta z odtwarzaczy dostępnych na Kindle i dotychczasowej funkcji instalacji MPlayera.
