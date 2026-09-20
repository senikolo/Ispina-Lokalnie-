# Ispina Lokalnie — aplikacja na Androida

Aplikacja na podstawie serwisu **ispinalokalnie.com** (portal mieszkańców Ispiny,
działający pod adresem `ispinalokalnie.wordpress.com`).

## Co robi aplikacja

To natywna "powłoka" strony w Kotlinie:

- **Menu boczne (szuflada)** odzwierciedla dokładnie nawigację strony:
  Strona główna, Aktualności/Wiadomości, Odjazdy autobusów, Komunikacja
  (Facebook), Dane o Ispinie, Historia Ispiny, Galeria, Kontakt, O mnie.
- Treść ładowana jest w **WebView** bezpośrednio z prawdziwych podstron
  serwisu — więc aktualności, rozkład autobusów i galeria zawsze są aktualne,
  tak jak na stronie.
- **Pasek postępu** ładowania, **pociągnij, aby odświeżyć** (swipe-to-refresh).
- Przycisk **wstecz** systemu Androida cofa w historii WebView (a nie od razu
  zamyka aplikację).
- Linki prowadzące poza serwis (np. do Facebooka) otwierają się w
  przeglądarce/aplikacji systemowej, a nie wewnątrz aplikacji.
- Ekran informujący o **braku internetu** z przyciskiem „Spróbuj ponownie”.

## Struktura projektu

```
IspinaLokalnie/
├── settings.gradle.kts
├── build.gradle.kts
├── app/
│   ├── build.gradle.kts
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/ispina/lokalnie/MainActivity.kt
│       └── res/
│           ├── layout/ (activity_main.xml, nav_header.xml)
│           ├── menu/drawer_menu.xml
│           ├── values/ (strings.xml, colors.xml, themes.xml)
│           ├── drawable/ (ikony menu + ikona aplikacji)
│           └── mipmap-anydpi-v26/ (adaptacyjna ikona)
```

## Jak zbudować i uruchomić

1. Zainstaluj **Android Studio** (najnowsza wersja, np. Koala/Ladybug).
2. Wybierz **File → Open** i wskaż rozpakowany folder `IspinaLokalnie`.
3. Poczekaj, aż Gradle pobierze zależności (wymaga internetu przy pierwszym
   otwarciu — potrzebne jest Android SDK 34 oraz JDK 17, Android Studio
   zwykle instaluje je automatycznie).
4. Podłącz telefon (z włączonym debugowaniem USB) lub uruchom emulator.
5. Kliknij **Run ▶** — aplikacja się zainstaluje i uruchomi.

Aby zbudować plik APK bez otwierania IDE:

```bash
./gradlew assembleDebug
# plik wynikowy: app/build/outputs/apk/debug/app-debug.apk
```

(Na Windows użyj `gradlew.bat assembleDebug`. To repo nie zawiera samego
Gradle Wrappera — jeśli go brakuje, w Android Studio wybierz
**File → Sync Project with Gradle Files**, a IDE go uzupełni.)

## Budowanie APK z samego telefonu (GitHub Actions)

Ten projekt zawiera gotowy plik `.github/workflows/build.yml`, który
automatycznie kompiluje aplikację "w chmurze" na serwerach GitHuba — nie
potrzebujesz komputera ani Android Studio. Kroki (wszystko da się zrobić z
przeglądarki lub apki GitHub na telefonie):

1. **Załóż konto na [github.com](https://github.com)**, jeśli jeszcze go nie masz
   (darmowe).
2. Utwórz **nowe puste repozytorium** (przycisk „+” → „New repository”),
   np. o nazwie `ispina-lokalnie-app`. Zostaw je publiczne lub prywatne —
   oba działają z Actions za darmo.
3. **Wgraj do niego zawartość rozpakowanego archiwum** `IspinaLokalnie`
   (czyli wszystkie foldery i pliki z tego ZIP-a, łącznie z ukrytym folderem
   `.github`). Najłatwiej z telefonu:
   - w apce **GitHub** nie da się wgrywać całych folderów, więc wygodniej
     zrobić to przez przeglądarkę mobilną w trybie „pulpitu” (desktop mode)
     na stronie repozytorium: przycisk **„Add file” → „Upload files”**,
     a następnie przeciągnąć/wskazać pliki z rozpakowanego ZIP-a (obsługuje
     przeciąganie całych folderów w większości przeglądarek na Androidzie).
   - alternatywnie: zainstaluj apkę **„Working Copy”** (iOS) lub
     **„MGit” / „Termux” + `git`** (Android) i zrób `git init`, `git add .`,
     `git commit`, `git push` z rozpakowanego folderu — jeśli wolisz
     terminal.
4. Po wgraniu plików GitHub **sam uruchomi workflow** (bo jest ustawiony na
   „push do main”). Przejdź do zakładki **„Actions”** w repozytorium i
   poczekaj (zwykle 2–5 minut), aż zadanie „Zbuduj APK” zmieni się na
   zielony ✔.
5. Kliknij zakończone zadanie, zjedź w dół do sekcji **„Artifacts”** i
   pobierz plik **`ispina-lokalnie-debug-apk`** (to spakowany `.zip`
   zawierający `app-debug.apk`).
6. Rozpakuj ten `.zip` na telefonie (menedżer plików zwykle to potrafi),
   otwórz `app-debug.apk` i zainstaluj — Android poprosi o zgodę na
   „instalację z nieznanego źródła” (to normalne dla plików spoza Sklepu
   Play).

Jeśli workflow zakończy się błędem, najczęstsza przyczyna to literówka w
nazwie folderu/pliku podczas ręcznego wgrywania przez przeglądarkę — upewnij
się, że struktura folderów (`app/`, `.github/workflows/build.yml` itd.)
została zachowana dokładnie tak jak w archiwum ZIP.

## Dalsze pomysły na rozbudowę

- Pobieranie aktualności przez WordPress.com REST API
  (`https://public-api.wordpress.com/rest/v1.1/sites/ispinalokalnie.wordpress.com/posts/`)
  i wyświetlanie ich jako natywna lista zamiast WebView — szybsze i działa
  częściowo offline (z cache'em).
- Powiadomienia push o nowych aktualnościach.
- Widget na ekran główny z najbliższymi odjazdami autobusów.
- Zapisywanie galerii offline / udostępnianie zdjęć.

## Uwaga

Projekt nie zawiera podpisanego, gotowego pliku `.apk` — Android wymaga
kompilacji w środowisku z Android SDK (Android Studio lub `gradlew`), którego
nie mam tutaj dostępnego (brak dostępu do sieci / SDK w tym środowisku).
Powyższe kroki pozwolą zbudować APK w kilka minut.
