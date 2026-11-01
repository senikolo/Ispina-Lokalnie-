# Backend Asystenta IL

Opcjonalna funkcja Vercel dla propozycji redakcyjnych i raportów wyszukiwania. Model może przygotować artykuł, kartę usługi lub audyt SEO, ale nie zapisuje ani nie publikuje treści.

## Konfiguracja

Utwórz osobny projekt Vercel z Root Directory il-redaktor-android/backend. Nie podłączaj endpointu do aplikacji dla mieszkańców. Ustaw:
- GOOGLE_GENERATIVE_AI_API_KEY – klucz Gemini Developer API. Nie umieszczaj go w APK, repozytorium ani ustawieniach aplikacji.
- UPSTASH_REDIS_REST_URL oraz UPSTASH_REDIS_REST_TOKEN – Redis/Upstash dla limitów.

GET /api/assistant jest sprawdzeniem gotowości: nie wymaga tokenu i nie zwraca wartości ani nazw brakujących zmiennych. Zwraca 200 i ready:true tylko wtedy, gdy wszystkie usługi są skonfigurowane; w przeciwnym razie zwraca 503. Aplikacja sprawdza endpoint przed włączeniem AI. Po zmianie zmiennych w Vercel należy utworzyć nowe wdrożenie.

Endpoint sprawdza sesję WordPress przez tylko odczyt posts.list dla ispinalokalnie.wordpress.com. Token nie jest zapisywany ani przekazywany modelowi. Prompt i kontekst artykułów trafiają do Google Gemini API. Backend nie ma narzędzia publikowania.

Domyślny adres aplikacji: https://il-redaktor-assistant.vercel.app/api/assistant. Można go nadpisać przy kompilacji przez IL_ASSISTANT_ENDPOINT albo właściwość Gradle ilAssistantEndpoint.

## Wyszukiwanie internetowe

Raport uruchamia sześć osobnych wywołań Gemini z Google Search grounding, po jednym dla profilu: przetargi, Gmina Drwinia/BIP, platformy postępowań, lokalne media, infrastruktura i usługi publiczne oraz odmiany nazwy i archiwa. Provider może wykonać jedno lub wiele zapytań w profilu; aplikacja nie obiecuje stałej liczby zapytań.

Wynik zawiera sekcje z nazwą profilu, statusem, treścią, źródłami i frazami, jeśli Google je udostępni. Interfejs przypisuje odnośniki do właściwej sekcji. Profile bez potwierdzonych źródeł są oznaczone i nie pokazują niezweryfikowanej treści. Jeżeli żaden profil nie zwróci źródeł, endpoint nie udaje zweryfikowanego raportu.

Limit wynosi 3 raporty na godzinę na konto WordPress i adres IP. Raport może wykonać do sześciu wywołań modelu, a każde może uruchomić wyszukiwanie; model i grounding mogą podlegać odrębnym opłatom. Przed wyszukiwaniem aplikacja wymaga potwierdzenia. Sprawdź aktualne ceny i limity dostawcy przed udostępnieniem funkcji.

## Testy

npm test uruchamia testy gotowości, CORS, sanitizacji źródeł i fraz oraz limitów bez połączenia z WordPress, Google ani Upstash. npm run typecheck sprawdza backend. CI wykonuje oba kroki przed kompilacją APK.
