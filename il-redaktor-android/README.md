# IL Redaktor Smart – niezależna aplikacja Android

Wersja testowa 1.0.0-test. Android 8 i nowsze. Odrębny pakiet com.ispinalokalnie.redaktor.

## Funkcje
- Logowanie WordPress.com przez OAuth2; token szyfrowany w Android Keystore.
- Artykuły: lista, edycja, szkice, publikacja z potwierdzeniem ID i statusu.
- Tytuły, formatowanie HTML, zajawki, kategorie, tagi i zdjęcia wyróżniające.
- Podstawowe lokalne sugestie redakcyjne i SEO bez modelu AI.
- Dodawanie i edycja własnych ofert w istniejącym katalogu Usługi w okolicy (strona ID 818).

## Połączenie z WordPress.com
Jednorazowo trzeba zarejestrować własną aplikację OAuth w WordPress.com Applications Manager:
https://developer.wordpress.com/apps/
Application name: IL Redaktor Smart
Redirect URI: https://ispinalokalnie.wordpress.com/il-redaktor-auth/
Następnie w APK należy wpisać PUBLICZNY numeryczny Client ID, przejść autoryzację konta i zatwierdzić dostęp.
Nie należy wpisywać nigdzie Client Secret ani hasła konta WordPress.
OAuth implicit może wygasać; wówczas połączenie trzeba odnowić.

## Kompilacja
Uruchom workflow GitHub Actions: IL Redaktor Smart - Android APK.
Artefakt IL-Redaktor-Smart-APK zawiera app-debug.apk.
Jest to APK podpisany kluczem debug, NIE finalny podpisany build produkcyjny.
Lokalnie: JDK 17, Android SDK 35, Gradle 8.9; komenda gradle :app:assembleDebug.

## Granice testowania
Nie przeprowadzono jeszcze autoryzowanego testu końcowego na telefonie i koncie właściciela WordPress.
Moduł edycji tekstu ma lokalne podpowiedzi, ale nie korzysta z generatywnego AI.
W katalogu Usługi tworzy dodatkowy obszar ofert; istniejących, ręcznie wpisanych ofert nie importuje do formularza.
Złożone bloki Gutenberg wyświetlane są w edycji jako kod HTML/bloków.
Publikacja wymaga wyraźnego potwierdzenia w aplikacji.
Nie należy uznawać działania produkcyjnego za zweryfikowane przed zapisem i kontrolą szkicu testowego.