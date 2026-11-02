# Ispina Lokalnie - zakończenie pakietu prac 2 listopada 2026

## Radar i aplikacja

Wersja testowa 2.0.0-professional-test.4 (48). Gałąź `codex/il-radar-final-20261102`, PR #28, kod aplikacji `2033f0bd68738f6024b04ed8753c366b23e7726a`.

Scalono najnowsze zmiany PR #27, w tym kanał Bochnia ON, dokładniejsze liczniki odczytanych artykułów i zachowanie dowodów również po nieudanym teście. Naprawiono utratę publikacji gminy wskutek odpowiedzi `302` na `https://www.drwinia.pl/robots.txt` do `http://www.drwinia.pl/`. Dla tego konkretnego wydawcy używane jest HTTPS; inne niezabezpieczone przekierowania pozostają odrzucane. Regresje obejmują również przekierowanie względne oraz niewłaściwy port.

Zachowano zakresy 7/30/90 dni, domyślne 30, etykietę Autobusy, szerokie pytanie o Ispinę, rzeczywiste adresy w dzienniku, osobne materiały bez dat i oddzielenie własnego portalu. Wynik częściowy nie jest przedstawiany jako dowód braku nowych informacji.

## Dowody

CI: https://github.com/senikolo/Ispina-Lokalnie-/actions/runs/41403162674

Kompilacja i testy Android 8/API26, 10/API29, 11/API30 oraz 13/API33: PASS. Test rzeczywistych źródeł na Androidzie 11: 5 datowanych publikacji zewnętrznych, 1 artykuł odczytany bezpośrednio w HTML wydawcy, 139 zdarzeń postępu. Wynik obejmuje m.in. październikowy materiał Eko-Drwinia, nominacje Mistrzowie Agro z gminy i Bochnia ON oraz wrześniowe materiały o moście i psach znalezionych w Ispinie. Dziennik raportuje 56 adresów. Materiały własnego portalu są liczone osobno.

Nie wykonano logowania i publikacji z tej APK na fizycznym telefonie właściciela. Wyszukiwanie nadal ma ograniczony czas i liczbę odczytów; nie obejmuje pełnego OCR ani każdego dokumentu BIP.

## Podpis APK

Podpisano skompilowane wydanie kluczem prywatnym zgodnym z certyfikatem wydaniowym zapisanym w repozytorium. Zweryfikowano APK Signature Scheme v2 i v3 oraz integralność ZIP.

SHA-256 APK: `63fbae296e2d3ed896759a647fb5a4d76ddef0e8ba424b3e3c13cf238a4e16d8`.

SHA-256 certyfikatu: `e02f06118cb5c74e367de92ffd33bc8b39b52c9aa9f2b5a9f03b8cda08df2e4c`.

Certyfikat różni się od ostatnich debug APK. Przed instalacją konieczne są eksporty szkiców i pamięci. Nie zaleca się usuwania dotychczasowej aplikacji ani danych w celu obejścia błędu instalacji. Wydanie pozostaje testowe.

## Kopie i instrukcja

Trzy zachowane archiwa przeszły sprawdzenie integralności ZIP. Zawierają 82 PDF, 28 EPUB, 25 MOBI oraz 14 APK (3 główne i 11 dodatkowych). Nowe APK test.4 i nowy pięciostronicowy poradnik PDF są osobnymi rezultatami. Prywatny klucz podpisujący nie trafił do repozytorium.

Próba wysłania archiwum dokumentów na podłączony Dysk Google zakończyła się HTTP 403 `storageQuotaExceeded`. Nie zgłoszono wysyłki jako wykonanej. Nie usuwano istniejących plików w celu zwolnienia miejsca.

## Artykuł o moście

Zastano opublikowany wpis: https://ispinalokalnie.wordpress.com/2026/11/02/most-w-swiniarach-koszty-historia-zasadnosc-budowy/

Potwierdzono publiczne HTTP 200, pojedynczy H1, tytuł, opis meta, prawidłowy canonical, obraz Open Graph, dwie fotografie w treści, opisy alternatywne, podpis autora, 22 pozycje źródłowe i linki do dwóch powiązanych artykułów. Wpisu nie publikowano drugi raz i nie nadpisywano jego nowszej treści.
