# Audyt i QA — IL Redaktor Smart 1.22.2

Data: 2026-11-01  
Repozytorium: senikolo/Ispina-Lokalnie-  
Gałąź: codex/il-redaktor-native-android-20261031  
PR: https://github.com/senikolo/Ispina-Lokalnie-/pull/9  
Wersja: versionName 1.22.2, versionCode 30, minSdk 26.

## Zakres poprawek

- Błąd składni JavaScript wykryty na Androidzie 8 jest naprawiany na etapie budowania: TypeScript transpile do ES2018 oraz polyfille brakujących metod. Test sprawdza wyjściowy skrypt bez uruchamiania zdalnych usług.

- Gotowość backendu sprawdza GET /api/assistant. Endpoint nie ujawnia sekretów i zwraca 503 bez pełnej konfiguracji. Android wyłącza AI online do czasu ready:true i pozwala ponowić sprawdzenie.
- Raport składa się z sześciu profili i uruchamia osobne wywołanie modelu dla każdego. Nie gwarantuje stałej liczby zapytań Google; interfejs i dokumentacja wyjaśniają ograniczenie.
- Źródła są przypisane do sekcji. Sekcje bez potwierdzonych źródeł nie pokazują niezweryfikowanej treści.
- Potwierdzenie wyszukiwania opisuje do sześciu wywołań Gemini oraz możliwe dodatkowe zapytania i koszty Google Search.
- CI sprawdza redirect OAuth statycznie; nie tworzy osieroconych rejestracji klientów na WordPress.com.
- WebView i ścieżka aktualizacji są sprawdzane na Androidzie 8/API 26, Androidzie 11/API 30 i Androidzie 13/API 33.

## Wyniki

Workflow 1.22.2 uruchamia testy backendu, typecheck, kompilację APK i emulatorów. Statusy końcowe należy odczytać z checków PR #9 dla tego commitu. Workflow 41246904027 dla wersji 1.22.1 przeszedł, ale nie jest dowodem działania nowych zmian.

| Obszar | Status |
|---|---|
| Testy backendu gotowości, CORS, limitów i źródeł | Oczekuje na workflow 1.22.2 |
| TypeScript backendu | Oczekuje na workflow 1.22.2 |
| Kompilacja APK i AndroidTest | Oczekuje na workflow 1.22.2 |
| Android 8 / API 26 | Oczekuje na workflow 1.22.2 |
| Android 11 / API 30 | Oczekuje na workflow 1.22.2 |
| Android 13 / API 33 | Oczekuje na workflow 1.22.2 |
| OAuth i publikacja na prawdziwym koncie WordPress | Nie wykonano; wymagane konto redaktora |
| Gemini i Google Search na produkcji | Nie wykonano; wymagany gotowy backend i limity Upstash |
| Zgodność instalacji z APK podpisanym wcześniejszym kluczem | Zależy od wcześniejszego klucza CI i testu na telefonie |

## Konfiguracja i ograniczenia

Backend wymaga GOOGLE_GENERATIVE_AI_API_KEY, UPSTASH_REDIS_REST_URL i UPSTASH_REDIS_REST_TOKEN. Bez Upstash backend celowo zwraca stan niegotowości, a aplikacja wyłącza funkcje online. Poprawka nie jest potwierdzeniem działania live.

Test OAuth w CI sprawdza zgodność callbacku, manifestu i PKCE bez tworzenia klienta WordPress. Nie sprawdza zgody użytkownika ani callbacku na telefonie. Testy nie zapisują ani nie publikują artykułów.
