# Backend Asystenta IL

Opcjonalny Vercel Function dla propozycji redakcyjnych. Model może przygotować artykuł, kartę usługi lub audyt SEO, ale odpowiedź nie może zapisać ani opublikować treści. Aplikacja pokazuje propozycję, a zapis pozostaje osobnym, zatwierdzanym działaniem WordPress.

## Wdrożenie

Utwórz osobny projekt Vercel z Root Directory ustawionym na `il-redaktor-android/backend`. Nie podłączaj endpointu do istniejącej aplikacji dla mieszkańców. Ustaw po stronie Vercel:

- `GOOGLE_GENERATIVE_AI_API_KEY` – klucz Gemini Developer API. Nie umieszczaj go w APK, repozytorium ani ustawieniach aplikacji.
- `UPSTASH_REDIS_REST_URL` i `UPSTASH_REDIS_REST_TOKEN` – Redis dla limitów: do 20 zapytań na godzinę na token WordPressa i adres IP.

Endpoint weryfikuje sesję, wykonując tylko odczyt `posts.list` dla `ispinalokalnie.wordpress.com`. Token WordPressa nie jest zapisywany ani przekazywany do modelu; jest używany wyłącznie do tego sprawdzenia. Prompt i przekazany kontekst artykułów trafiają do Google Gemini API. Backend nie ma żadnego narzędzia publikowania.

Aplikacja domyślnie korzysta z `https://il-redaktor-assistant.vercel.app/api/assistant`. Podczas kompilacji można nadpisać ten adres przez `IL_ASSISTANT_ENDPOINT` albo właściwość Gradle `ilAssistantEndpoint`. Sam adres endpointu nie jest sekretem; każde żądanie sprawdza sesję WordPress, a backend ogranicza liczbę zapytań.

Panel „Wyszukiwanie internetowe” korzysta z trybu `research` tego samego endpointu. Gemini używa wbudowanego narzędzia Google Search grounding, a aplikacja pokazuje źródła URL zwrócone przez AI SDK i użyte frazy, jeśli są dostępne. To wyszukiwanie tylko do odczytu; nie zapisuje ani nie publikuje artykułów. Limit wyszukiwania wynosi 3 raporty na godzinę na konto i adres IP. Wyniki wymagają ręcznego sprawdzenia w oryginalnych dokumentach. Każde wyszukiwanie wymaga potwierdzenia w aplikacji, bo korzystanie z Google Search grounding może generować osobne koszty API, niezależne od planu Gemini używanego w aplikacji.

## Koszty i ograniczenia

Klucz do Gemini Developer API jest oddzielny od subskrypcji Gemini Pro. Model `gemini-3.5-flash-lite` ma aktualnie bezpłatny limit API; użycie po przejściu na płatny poziom jest rozliczane według cennika Google. Limity API mogą się zmieniać. Redis/Upstash i Vercel mogą wymagać osobnego konta i mogą naliczać opłaty zależne od użycia. Tryb bez skonfigurowanego Redis jest celowo wyłączony, zamiast działać bez ograniczeń kosztowych.
