# IL Redaktor Smart

Niezależna aplikacja Android do redagowania portalu Ispina Lokalnie. Nie zmienia aplikacji dla mieszkańców. Aktualna wersja robocza: **1.4.1-test**, pakiet `com.ispinalokalnie.redaktorsmart.ipv4fix.test`, Android 8 i nowszy.

## Logowanie WordPress.com

Aplikacja używa oficjalnego OAuth 2.1 WordPress.com: dynamicznie rejestruje publicznego klienta, używa Authorization Code + PKCE, otwiera przeglądarkę systemową i odbiera kod przez lokalny adres `http://127.0.0.1:38477/callback`. Nie ma Client Secret ani hasła w APK. Użytkownik loguje się do WordPress.com i może wybrać Google, jeśli ta metoda jest skonfigurowana dla jego konta.

Po wymianie kodu aplikacja szyfruje token dostępu i token odświeżający kluczem Android Keystore. Zalogowanie jest potwierdzone dopiero, gdy aplikacja odczyta co najmniej jeden istniejący, opublikowany wpis przez WordPress.com MCP. Odmowa lub błąd API jest pokazywany wraz z odpowiedzią serwera. Token odświeżający nie jest gwarantowany przez WordPress.com; przy jego braku aplikacja prosi o ponowne logowanie po wygaśnięciu tokenu.

W ustawieniach konta WordPress.com trzeba włączyć dostęp MCP i zatwierdzić odpowiednie uprawnienia do witryny. Zgoda Google uwierzytelnia konto WordPress.com, ale sama nie przyznaje aplikacji dostępu do publikowania.

## Funkcje

- Pulpit redakcyjny, lista istniejących wpisów i szkiców, edycja wpisu, zapis szkicu i publikacja po dodatkowym potwierdzeniu.
- Lokalna kopia robocza, podgląd, formatowanie HTML, kategorie, tagi, zajawka i zdjęcie wyróżniające.
- Odczyt biblioteki mediów, wybór zdjęcia z telefonu i optymalizacja wymiarów przed wysłaniem.
- Odczyt ręcznie dodanych kart z katalogu „Usługi w okolicy” (strona 818). Ręczne karty są obecnie tylko podglądane; zapis z aplikacji modyfikuje wyłącznie własny, oznaczony obszar Redaktora i przerywa, jeśli strona zmieniła się od ostatniego odczytu.
- Lokalne wskazówki redakcyjne, szablony i sugestie SEO działają bez usługi AI. Nie zastępują weryfikacji faktów historycznych.

## Kompilacja i podpis

Workflow **IL Redaktor Smart - Android APK** sprawdza JavaScript, asercje bezpieczeństwa i zawartość APK, buduje APK debug oraz przechowuje artefakt przez 30 dni. Aby ominac konflikt podpisu miedzy kolejnymi APK debug, wersja 1.4.1-test uzywa osobnego identyfikatora aplikacji (`com.ispinalokalnie.redaktorsmart.ipv4fix.test`) i nazwy z numerem wersji. Instaluje sie obok wczesniejszych testow; nie kasuj starej aplikacji z lokalnymi szkicami przed weryfikacja. Kolejne aktualizacje tego samego pakietu nadal wymagaja stalego klucza podpisujacego.

Aby workflow budował także aktualizowalny APK release, skonfiguruj w GitHub Actions secrets:

- `IL_RELEASE_KEYSTORE_B64` – keystore w Base64.
- `IL_RELEASE_KEY_ALIAS`.
- `IL_RELEASE_KEYSTORE_PASSWORD`.
- `IL_RELEASE_KEY_PASSWORD`.

Te sekrety muszą pochodzić z prywatnego, bezpiecznie przechowywanego klucza podpisującego. Nie dodawaj keystore ani haseł do repozytorium. Bez właściwego klucza nie da się zainstalować nowego APK jako aktualizacji istniejącej instalacji.

## Zakres testów

Workflow potwierdza poprawność składni JavaScript i kompilację APK. Nie zastępuje testu OAuth na telefonie i koncie redaktora. W bieżącym środowisku nie ma emulatora ani podłączonego urządzenia; przed uznaniem logowania za działające produkcyjnie należy wykonać autoryzację na docelowym telefonie Android 8–13. Nie zapisuj testowych treści ani nie modyfikuj publicznego katalogu bez zatwierdzenia redaktora.

## AI

Backend źródłowy jest w `backend/`, ale nie jest jeszcze wdrożony i nie ma skonfigurowanego klucza. APK bez `IL_ASSISTANT_ENDPOINT` działa w trybie podstawowym, bez generatywnego modelu. Do uruchomienia potrzebny jest osobny klucz API Gemini, Vercel Function oraz Upstash Redis do limitowania zapytań; subskrypcja Gemini Pro nie jest kluczem do Gemini API. Nie umieszczaj klucza API ani prywatnego endpointu z uprawnieniami WordPressa w aplikacji.

Cennik Google dla `gemini-3.5-flash-lite` aktualnie podaje bezpłatny limit, a po jego przekroczeniu **0,30 USD za 1 mln tokenów wejściowych** i **2,50 USD za 1 mln tokenów wyjściowych**. W przypadku bezpłatnego poziomu Google może używać przekazanych promptów do ulepszania produktów; płatny poziom nie używa ich w tym celu. Vercel i Upstash są osobnymi usługami i mogą naliczać własne opłaty. Cenniki i limity mogą się zmieniać.

### Logowanie na Androidzie (1.4.1-test)

Poprzednia wersja uzywala `localhost`, ktory na czesci telefonow jest rozwiazywany do adresu IPv6 `::1`, mimo ze aplikacja nasluchiwala wylacznie na IPv4 `127.0.0.1`. W tej wersji rejestracja OAuth, adres przekierowania i serwer lokalny uzywaja tego samego jawnego adresu IPv4. Zmiana adresu automatycznie wymusza nowa rejestracje klienta OAuth na urzadzeniu, nie trzeba czyscic danych aplikacji. Jesli pojawi sie blad po autoryzacji, prosimy o zrzut z ekranu aplikacji (bez kodow i tokenow). Weryfikacja na fizycznym telefonie nadal jest wymagana.
