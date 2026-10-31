# Backend Asystenta IL

Opcjonalny Vercel Function dla propozycji redakcyjnych. Model może przygotować artykuł, kartę usługi lub audyt SEO, ale odpowiedź nie może zapisać ani opublikować treści. Aplikacja pokazuje propozycję, a zapis pozostaje osobnym, zatwierdzanym działaniem WordPress.

## Wdrożenie

Utwórz osobny projekt Vercel z Root Directory ustawionym na `il-redaktor-android/backend`. Nie podłączaj endpointu do istniejącej aplikacji dla mieszkańców. Ustaw po stronie Vercel:

- `GOOGLE_GENERATIVE_AI_API_KEY` – klucz Gemini Developer API. Nie umieszczaj go w APK, repozytorium ani ustawieniach aplikacji.
- `UPSTASH_REDIS_REST_URL` i `UPSTASH_REDIS_REST_TOKEN` – Redis dla limitów: do 20 zapytań na godzinę na token WordPressa i adres IP.

Endpoint weryfikuje sesję, wykonując tylko odczyt `posts.list` dla `ispinalokalnie.wordpress.com`. Token WordPressa nie jest zapisywany ani przekazywany do modelu; jest używany wyłącznie do tego sprawdzenia. Prompt i przekazany kontekst artykułów trafiają do Google Gemini API. Backend nie ma żadnego narzędzia publikowania.

Po wdrożeniu ustaw `IL_ASSISTANT_ENDPOINT` w środowisku kompilacji APK na HTTPS URL endpointu `/api/assistant`. Bez tej zmiennej aplikacja pozostaje w trybie podstawowym i nie wysyła tekstu do modelu.

## Koszty i ograniczenia

Klucz do Gemini Developer API jest oddzielny od subskrypcji Gemini Pro. Model `gemini-3.5-flash-lite` ma aktualnie bezpłatny limit API; użycie po przejściu na płatny poziom jest rozliczane według cennika Google. Limity API mogą się zmieniać. Redis/Upstash i Vercel mogą wymagać osobnego konta i mogą naliczać opłaty zależne od użycia. Tryb bez skonfigurowanego Redis jest celowo wyłączony, zamiast działać bez ograniczeń kosztowych.
