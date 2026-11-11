# Poczta na Kindle Keyboard 3 — wersja TEST 2.29.0

Ta gałąź rozwija istniejącą aplikację **Kindle przez Wi-Fi**, bez zastępowania działających modułów radia i plików. Telefon służy do zainstalowania wtyczki; późniejsze połączenia pocztowe wychodzą bezpośrednio z Kindle'a przez Wi-Fi.

## Funkcje
- IMAP z TLS na porcie 993: odczyt nagłówków ostatnich 12 e-maili, podgląd zwykłego tekstu (także tekstowych części MIME, bez HTML).
- Wysyłanie krótkiej wiadomości do 5000 znaków przez SMTPS 465 z AUTH LOGIN.
- Zapis załączników EPUB/PDF do `/mnt/us/documents`, ograniczenie do 10 MB, brak nadpisania, walidacja rozszerzeń i sygnatur.
- Konfiguracja e-mail, IMAP i SMTP w KOReaderze. Hasło aplikacji przechowywane **tylko w RAM** na czas sesji.
- TLS 1.2+ z weryfikacją łańcucha CA, SNI i nazwy hosta; brak przejścia na nieszyfrowany transport i brak proxy przez telefon.
- Bazuje na LuaSocket/LuaSec KOReadera, bez osobnej binarki Go. Jądro Kindle 3 to Linux 2.6.26, więc typowy nowy Go ARM byłby niezgodny.

## Instalacja
1. W telefonie potrzebna jest **podpisana dotychczasowym kluczem** aktualizacja aplikacji Kindle przez Wi-Fi 2.29.0. Artefakt CI `*-unsigned.apk` nie nadaje się do instalacji wprost.
2. Połącz telefon i Kindle z tą samą siecią Wi-Fi; w aplikacji wybierz **Poczta i książki → Zainstaluj lub zaktualizuj pocztę**.
3. Uruchom ponownie KOReadera. W menu **Narzędzia → Więcej narzędzi → Poczta i książki · TEST** ustaw e-mail, serwer IMAP, SMTP i hasło aplikacji.
4. Po instalacji telefon nie musi być włączony. Kindle musi mieć aktywne Wi-Fi i prawidłową datę.

Alternatywnie ZIP z Actions należy rozpakować w katalogu `/mnt/us/koreader/plugins/`.

## Ograniczenia i weryfikacja
**Wersja eksperymentalna, nie wydanie stabilne.** Test jednostkowy i statyczny oraz build CI są wymagane przed użyciem. Nie wykonano autoryzowanego logowania do prawdziwej skrzynki, próby fizycznej na Kindle Keyboard 3 ani instalacji na telefonie użytkownika. Szczególnie sprawdzić obecność i działanie `ssl`, `socket` oraz obsługę certyfikatu w konkretnym buildzie KOReadera.

Zależności Lua `imap.lua` i `mail.lua` zaadaptowano z projektu `finlater/sendtokoreader.koplugin` na licencji MIT; pełne przypisanie praw i tekst licencji znajdują się w `LICENSE-THIRD-PARTY.txt`.

Nie scalać do gałęzi bazowej ani nie nazywać instalowalną aktualizacją, dopóki nie istnieje **podpisany tym samym kluczem** APK i nie zostaną przeprowadzone testy na K3.