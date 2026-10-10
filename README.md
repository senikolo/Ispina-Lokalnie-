# Ispina Lokalnie — Android

Aktualne wydanie stabilne: **1.83.19** (10 października 2026).

[Pobierz APK](https://github.com/senikolo/Ispina-Lokalnie-/releases/download/v1.83.19/Ispina-Lokalnie-Live-1.83.19.apk) · [Opis wydania i źródła](https://github.com/senikolo/Ispina-Lokalnie-/releases/tag/v1.83.19)

Planer pokazuje pełne nazwy przystanków, np. „Odjazdy będą z przystanku Ispina Most”. Dotknięcie podpowiedzi wybiera przystanek wstępnie, a przycisk „Akceptuj: [pełna nazwa]” zatwierdza wybór i uruchamia ptaszek. Większe wiersze, ochrona przed wyborem podczas przewijania i przycisk „Zmień” ograniczają pomyłki. Tryb Senior powiększa elementy. Wyniki dotyczą jednego wybranego dnia; wspólne przystanki linii MLD i gminnej mają spójne nazwy.

## Instalacja

- Android 6.0 lub nowszy (minSdk 23, targetSdk 34).
- Pakiet produkcyjny: `com.ispina.mldcheck`, versionCode `18319`.
- Stabilną wersję 1.82.99 z tym samym podpisem można aktualizować bez usuwania aplikacji.
- Starsza publiczna wersja 1.82.98 ma inny certyfikat. Android nie pozwoli zastąpić jej tą APK bez zmiany instalacji. Przed usunięciem starszej aplikacji zapisz potrzebne ustawienia; odinstalowanie usuwa jej lokalne dane.
- Testowe wydania `com.ispina.mldtest6` są osobną aplikacją. To wydanie instaluje lub aktualizuje aplikację produkcyjną.

## Kod i sprawdzenie

Katalog `planner/` zawiera pełny kod interfejsu `assets/main_ui.js`, wyodrębnione moduły, dane testowe, 84 testy regresji i skrypty pakowania oraz kontroli APK. `planner/README.md` opisuje budowę i ograniczenia. Workflow sprawdza te testy; nie próbuje budować brakującego projektu Gradle. Starsze pliki XML w katalogu głównym pozostają historycznymi zasobami.

Plik `IL-1.83.19-ZRODLA-I-TESTY.zip` w wydaniu zawiera również bazową APK R14.3 do odtworzenia pakietu. Repozytorium nie zawiera pełnych źródeł warstwy Java/Kotlin ani prywatnych kluczy. Natywny DEX pochodzi z działającej bazy R14.3; manifest, interfejs i metadane zostały przygotowane dla wydania produkcyjnego.

Sprawdzono 84/84 testy oraz interfejs dotykowy w Chromium w trzech rozmiarach/trybach. APK ma poprawne podpisy v1/v2/v3, wyrównanie ZIP, integralność DEX oraz zgodne metadane. Nie wykonano instalacji na fizycznym telefonie; testy interfejsu korzystają z kontrolowanego mostu Androida.
