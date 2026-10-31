# IL Redaktor Smart – niezależna aplikacja Android

Wersja testowa 1.1.0-test. Android 8 i nowsze. Odrębny pakiet testowy com.ispinalokalnie.redaktorsmart.test. Nie koliduje ze starszym testowym wydaniem, ale do aktualizacji tego samego pakietu między kompilacjami konieczny jest stały prywatny klucz podpisujący; standardowy klucz debug w GitHub Actions jest jednorazowy.

## Audyt i poprawki
- Zastąpiono fikcyjny monogram tekstowy oficjalną grafiką IL z WordPressa; ikona aplikacji jest generowana z tego samego źródła.
- Ujednolicono paletę portalu: #174875, #2d6d98, #f3f7fc, #dbe5ef.
- Sesja jest potwierdzana rzeczywistym zapytaniem do WordPressa (nie tylko samym otrzymaniem tokenu).
- Zapis edytowanego już opublikowanego wpisu nie wycofuje go automatycznie z publikacji.
- Dodano walidację JavaScript i zawartości APK do GitHub Actions.

## Funkcje
- Logowanie WordPress.com przez OAuth2; token szyfrowany w Android Keystore. Publiczny Client ID jest potrzebny tylko do pierwszego połączenia, później używa się przycisku logowania.
- Pulpit z kafelkami, artykuły (wyszukiwanie, opublikowane i szkice), edycja, publikacja z potwierdzeniem ID i statusu.
- Lokalne automatyczne szkice w pamięci aplikacji, przywracanie i kopiowanie tekstu.
- Szablony artykułów: aktualności, historia, OSP i wydarzenia, komunikacja.
- Dyktowanie po polsku przy użyciu dostępnego na urządzeniu rozpoznawania mowy.
- Tytuły, formatowanie HTML, zajawki, kategorie z WordPressa, tagi, zdjęcia wyróżniające.
- Wyszukiwanie i wybieranie obrazów z galerii WordPressa.
- Podstawowe lokalne sugestie redakcyjne i SEO bez modelu AI.
- Dodawanie i edycja własnych ofert z kategoriami w istniejącym katalogu Usługi w okolicy (strona ID 818). Istniejące ręczne oferty są rozpoznawane i wyświetlane jako podgląd; do ich edycji użyj WordPressa.

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