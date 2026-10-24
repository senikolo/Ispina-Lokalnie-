# Ispina Lokalnie — Android

Ispina Lokalnie to bezpłatna, społecznościowa inicjatywa poświęcona **Ispinie**, wsi w gminie Drwinia, w powiecie bocheńskim (województwo małopolskie). Portal łączy wiadomości dla mieszkańców, historię miejscowości, archiwalne fotografie i praktyczne informacje, w tym rozkłady autobusów. Aplikacja na Androida pomaga korzystać z tych materiałów na telefonie.

🌐 [Ispina Lokalnie – portal mieszkańców Ispiny](https://ispinalokalnie.com) · [Ispina – informacje o miejscowości i dane GUS](https://ispinalokalnie.wordpress.com/dane-o-ispinie/)


Aktualne wydanie stabilne: **1.83.20** (10 października 2026).


[Pobierz APK](https://github.com/senikolo/Ispina-Lokalnie-/releases/download/v1.83.20/Ispina-Lokalnie-Live-1.83.20.apk) · [Opis wydania](https://github.com/senikolo/Ispina-Lokalnie-/releases/tag/v1.83.20)


W wersji 1.83.20 dodano w aplikacji pozycję „Ciekawostki historyczne” z bezpośrednim przejściem do materiałów o Ispinie. Po wybraniu celu Planer automatycznie wyszukuje połączenia na teraz, bez dodatkowego klikania. Zachowano pełne nazwy przystanków, większe podpowiedzi, bezpieczniejszy wybór podczas przewijania oraz przycisk „Akceptuj” z animacją ptaszka. Utrzymano też czytelniejszy tryb Senior, spójne nazwy przystanków i wyniki dotyczące wybranego dnia.


## Instalacja


- Android 6.0 lub nowszy (minSdk 23, targetSdk 34).
- Pakiet produkcyjny: `com.ispina.mldcheck`, versionCode `18320`.
- Stabilną wersję 1.82.99 z tym samym podpisem można aktualizować bez usuwania aplikacji.
- Starsza publiczna wersja 1.82.98 ma inny certyfikat. Android nie pozwoli zastąpić jej tą APK bez zmiany instalacji. Przed usunięciem starszej aplikacji zapisz potrzebne ustawienia; odinstalowanie usuwa jej lokalne dane.
- Testowe wydania `com.ispina.mldtest6` są osobną aplikacją. To wydanie instaluje lub aktualizuje aplikację produkcyjną.


## Kod i sprawdzenie


Katalog `planner/` zawiera pełny kod interfejsu `assets/main_ui.js`, wyodrębnione moduły, dane testowe, 84 testy regresji i skrypty pakowania oraz kontroli APK. `planner/README.md` opisuje budowę i ograniczenia. Workflow sprawdza te testy; nie próbuje budować brakującego projektu Gradle. Starsze pliki XML w katalogu głównym pozostają historycznymi zasobami.


Archiwum `IL-1.83.19-ZRODLA-I-TESTY.zip` zawierające źródła interfejsu, moduły, testy i bazową APK R14.3 pozostaje dostępne przy wydaniu 1.83.19. Wydanie 1.83.20 udostępnia zaktualizowany plik APK. Repozytorium nie zawiera pełnych źródeł warstwy Java/Kotlin ani prywatnych kluczy.


Sprawdzono 84/84 testy oraz interfejs dotykowy w Chromium w trzech rozmiarach/trybach. APK ma poprawne podpisy v1/v2/v3, wyrównanie ZIP, integralność DEX oraz zgodne metadane. Nie wykonano instalacji na fizycznym telefonie; testy interfejsu korzystają z kontrolowanego mostu Androida.


SHA-256 APK: `c9c0a0107310c3229b421a87832de1f1fc738b07db7ab1dc41eea2ead417e664`
