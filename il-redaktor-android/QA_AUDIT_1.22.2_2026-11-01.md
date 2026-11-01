# Audyt i QA — IL Redaktor Smart 1.22.2

Data: 2026-11-01  
Repozytorium: senikolo/Ispina-Lokalnie-  
Gałąź: codex/il-redaktor-native-android-20261031  
PR: https://github.com/senikolo/Ispina-Lokalnie-/pull/9  
Wersja: versionName 1.22.2, versionCode 30, minSdk 26.

## Zakres poprawek

- Podczas budowania TypeScript przekształca skrypt interfejsu do składni ES2018 i uzupełnia brakujące metody. Android 8/API 26 potwierdził uruchomienie nowego pliku WebView.
- Odczyt orientacji EXIF korzysta z AndroidX ExifInterface 1.4.2, przetestowanej z JPEG-em zawierającym orientację 90°.

- Gotowość backendu sprawdza GET /api/assistant. Endpoint nie ujawnia sekretów i zwraca 503 bez pełnej konfiguracji. Android wyłącza AI online do czasu ready:true i pozwala ponowić sprawdzenie.
- Raport składa się z sześciu profili i uruchamia osobne wywołanie modelu dla każdego. Nie gwarantuje stałej liczby zapytań Google; interfejs i dokumentacja wyjaśniają ograniczenie.
- Źródła są przypisane do sekcji. Sekcje bez potwierdzonych źródeł nie pokazują niezweryfikowanej treści.
- Potwierdzenie wyszukiwania opisuje do sześciu wywołań Gemini oraz możliwe dodatkowe zapytania i koszty Google Search.
- CI sprawdza redirect OAuth statycznie; nie tworzy osieroconych rejestracji klientów na WordPress.com.
- Android 8/API 26 sprawdza zgodność bieżącego WebView, uruchomienie i zachowanie danych po reinstalacji. Emulator API 26 nie uruchamia historycznego APK 1.15 w swoim WebView; aktualizację z 1.15 sprawdzają API 30 i 33.

## Wyniki

Weryfikacja dla commitu `2726275720909868a29c9cccea0553995dd44262` zakończyła się sukcesem: [Android APK run 41283636982](https://github.com/senikolo/Ispina-Lokalnie-/actions/runs/41283636982) oraz [planner run 41283640793](https://github.com/senikolo/Ispina-Lokalnie-/actions/runs/41283640793).

| Obszar | Status |
|---|---|
| Testy backendu gotowości, CORS, limitów i źródeł | PASS |
| TypeScript backendu | PASS |
| Kompilacja APK, AndroidTest i apksigner | PASS |
| Android 8 / API 26: uruchomienie, funkcje edytora i reinstalacja danych | PASS |
| Android 11 / API 30: aktualizacja 1.15 → 1.22.2 | PASS |
| Android 13 / API 33: aktualizacja 1.15 → 1.22.2 | PASS |
| OAuth i publikacja na prawdziwym koncie WordPress | Nie wykonano; wymagane konto redaktora |
| Gemini i Google Search na produkcji | Nie wykonano; wymagany gotowy backend i limity Upstash |
| APK aktualizacyjny z istniejącym kluczem release | Nie wygenerowano; sekrety poprzedniego klucza nie są skonfigurowane. APK debug przeszedł weryfikację podpisu. |

## Konfiguracja i ograniczenia

Backend wymaga GOOGLE_GENERATIVE_AI_API_KEY, UPSTASH_REDIS_REST_URL i UPSTASH_REDIS_REST_TOKEN. Bez Upstash backend celowo zwraca stan niegotowości, a aplikacja wyłącza funkcje online. Poprawka nie jest potwierdzeniem działania live.

Test OAuth w CI sprawdza zgodność callbacku, manifestu i PKCE bez tworzenia klienta WordPress. Nie sprawdza zgody użytkownika ani callbacku na telefonie. Testy nie zapisują ani nie publikują artykułów.
