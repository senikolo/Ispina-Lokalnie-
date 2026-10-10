# Przygotowanie Ispina Lokalnie Live do IzzyOnDroid

**Data:** 10 października 2026 r.
**Status:** NIEGOTOWA DO ZGŁOSZENIA
**Najnowsze wydanie:** `v1.83.20` (`versionCode 18320`)
**Identyfikator:** `com.ispina.mldcheck`
**Wybrana licencja kodu:** `GPL-3.0-only`

## Co już pasuje

- APK `v1.83.20` jest dołączone do oznaczonego wydania GitHub, tak jak preferuje IzzyOnDroid. Ma około 7,44 MB, czyli mniej niż orientacyjny limit 30 MB dla pojedynczego APK.
- APK ma identyfikator `com.ispina.mldcheck`, versionCode 18320 i certyfikat wydawcy o odcisku SHA-256 `6D:75:BA:F7:F4:40:2F:F9:3C:93:50:5F:D6:D7:A5:C0:EA:B3:D1:C7:C2:AA:2A:39:EE:B5:E4:AE:16:6F:62:5F`. W zdekodowanym manifeście nie znaleziono flag `debuggable=true` ani `testOnly=true`.
- Repozytorium ma wybrany identyfikator SPDX `GPL-3.0-only` i pełny tekst GPLv3. To oznaczenie może objąć tylko kod i zasoby, do których autor ma prawa i które wolno udostępnić na tej licencji.
- Przygotowano szkic polskich i angielskich opisów oraz changelog Fastlane dla `versionCode 18320` w `fastlane/metadata/android/`.

IzzyOnDroid dystrybuuje APK podpisane przez autora i zwykle pobiera je z oznaczonych wydań. W odróżnieniu od głównego repozytorium F-Droid nie buduje standardowo APK samodzielnie. Nadal wymaga jednak, by kod aplikacji był wolny, otwarcie dostępny i poprawnie licencjonowany. Zgodność podpisu i rosnącego `versionCode` może zachować aktualizacje z obecnych instalacji, jeśli katalog będzie dystrybuował to samo wydanie podpisane dotychczasowym kluczem.

## Blokery przed zgłoszeniem

### 1. Brak wiarygodnych, pełnych źródeł Androida

Repozytorium zawiera kod JavaScript Planera i testy, ale nie ma kompletnego projektu Gradle ani oryginalnych źródeł Java/Kotlin wydania 1.83.20. APK zdekompilowano diagnostycznie: wynik to około 1 992 pliki Java, 83 błędy dekompilacji i niekompletny eksport Gradle. Wygenerowany eksport ma pusty zestaw zależności i nieprawidłowy `applicationId`; nie może być przedstawiony jako źródło autora. Zdekompilowane pliki nie zostały użyte do budowy ani do zastąpienia aplikacji.

Dodatkowo sprawdzono archiwum `Ispina-Lokalnie-1.82.98-USLUGI-CZYTELNE-source.zip` dostępne w materiałach projektu. Zawiera ono bazowy APK `1.82.95`, APK wynikowy `unsigned.apk`, pliki JavaScript i skrypty. `rebuild.py` modyfikuje manifest i `assets/main_ui.js` w istniejącym APK, a pozostałe elementy kopiuje z APK bazowego. To zestaw do odtworzenia konkretnej poprawki interfejsu, nie kompletny projekt Gradle ani źródła natywnej aplikacji. Nie może zastąpić kompilacji IL od zera dla IzzyOnDroid.

### 2. Pochodzenie kodu i aktualna polityka AI IzzyOnDroid

Polityka IzzyOnDroid opublikowana w dniu audytu mówi, że aplikacje tworzone w całości lub częściowo przez generatywne AI, w tym projekty „vibe-coded”, najpewniej zostaną odrzucone. Teksty dokumentacji i changelogów mogą być wspomagane przez LLM, ale sam kod ma być wolny od treści wygenerowanych przez LLM. Wcześniejsze prace nad IL obejmowały zmiany kodu przygotowywane z pomocą ChatGPT. Trzeba przejrzeć pochodzenie każdej części kodu i ustalić, czy wygenerowany kod znalazł się w aplikacji. Nie można deklarować zgodności, dopóki to nie zostanie wyjaśnione. Ten dokument i teksty Fastlane są metadanymi, nie kodem aplikacji.

### 3. Prawa do kodu i zasobów

Nie udokumentowano praw do wszystkich zdjęć, ikon, fontów, tekstów, rozkładów MLD i gminnych, transmisji radiowych i telewizyjnych ani danych z usług zewnętrznych. GPL dla kodu nie obejmuje automatycznie tych elementów. Nie wolno przypisywać im GPL bez potwierdzenia praw.

### 4. Własny aktualizator

W APK znaleziono sprawdzanie wydań GitHub i oferowanie pobrania APK poza katalogiem. Polityka IzzyOnDroid wymaga wyraźnej, równoważnej opcji zgody użytkownika z jasnym ostrzeżeniem, że pobranie z zewnątrz omija kontrole repozytorium. Nie zweryfikowano, czy obecny ekran aktualizacji spełnia ten warunek. Nie znaleziono automatycznej instalacji, ale przepływ trzeba sprawdzić na źródłach i urządzeniu.

### 5. Ikona i zrzuty ekranu

Nie ma potwierdzonego prawa do pliku PNG ikony. W Library znaleziono kilka prawdziwych zrzutów ekranu Planera, ale wszystkie powstały przed wydaniem 1.83.20; nie potwierdzają więc wyglądu aktualnej wersji i nie dodano ich do metadanych. Nowsze obrazy z 10 października pokazują inne treści, a nie ekrany aplikacji. Polityka wymaga ikony i zrzutów, gdy aplikacja ma ekrany.

### 6. Pozostały audyt

Statyczny przegląd APK wskazuje na AndroidX Core, WorkManager, Room, Kotlin/coroutines i Guava. Nie odtworzono pełnej listy wersji i licencji zależności. Nie znaleziono znanych sygnatur SDK AdMob, Firebase ani analityki, ale to nie wyklucza własnych mechanizmów śledzących ani skryptów serwisu WWW. Manifest deklaruje dostęp do Internetu i stanu sieci, wake lock, odbiór uruchomienia systemu, powiadomienia, wibracje oraz usługi foreground do synchronizacji i odtwarzania. Używane są m.in. Open-Meteo, WordPress/REST, dane GTFS MLD, wdrożony Google Apps Script oraz zewnętrzne usługi i strumienie audio/wideo. Warunki usług, ruch sieciowy i prawa do treści wymagają dalszego sprawdzenia.

Nie wykonano kompilacji Androida, testu instalacji/uruchomienia, audytu runtime ani weryfikacji powtarzalności. Testy Planera JavaScript (84/84) i przechodzący workflow GitHub Actions nie potwierdzają działania natywnej aplikacji.

## Metadane Fastlane

Pliki Fastlane są szkicem do recenzji, a nie gotowym wpisem. `en-US` jest wymagane jako język zapasowy. Opisy mają poniżej 80 i 4 000 znaków, a changelogi poniżej 500 bajtów ASCII. Brakuje jednak `images/icon.png` oraz co najmniej jednego aktualnego `images/phoneScreenshots/*.png`.

## Procedura

Po usunięciu powyższych blokerów preferowana procedura IzzyOnDroid to zgłoszenie nowej aplikacji w ich repozytorium Codeberg, z linkiem do publicznego repozytorium, wydań z podpisanym APK oraz metadanych Fastlane. Zgłoszenia nie wysłano.

## Oficjalne źródła

- https://izzyondroid.org/docs/general/AppInclusionPolicy/
- https://izzyondroid.org/docs/general/Fastlane/
- https://izzyondroid.org/faq/
- https://izzyondroid.org/contributing/
