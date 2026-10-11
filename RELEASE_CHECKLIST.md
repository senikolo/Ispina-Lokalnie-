# Checklista przed zgłoszeniem do F-Droid

Ta lista dotyczy kandydata `2.0.0-preview`. Nie tworzy zgody na wydanie ani na zastąpienie produkcyjnego IL.

- [ ] GitHub Actions przechodzi testy planera i buduje warianty `fdroidDebug` oraz `fdroidRelease`.
- [ ] Testowy APK z artefaktu CI instaluje się obok produkcyjnego IL na Androidzie 11.
- [ ] Planer pobiera aktualny GTFS MLD, pokazuje datę danych i działa offline po synchronizacji.
- [ ] Planer poprawnie filtruje daty kursowania, wyjątki kalendarza i minimalny czas przesiadki.
- [ ] Linia gminna jest wyraźnie oznaczona jako robocza; szacowane czasy nie wyglądają na potwierdzone.
- [ ] Sprawdzono aktualności, historię, lektora, tryb Senior, wibrację, pogodę i linki zewnętrzne.
- [ ] Sprawdzono prośbę o zgodę na powiadomienia, przypomnienie o odpadach i odmowę zgody.
- [ ] Zweryfikowano warunki licencji danych, tekstów, nazw i znaków towarowych w `SOURCES.md`.
- [ ] Metadane F-Droid wskazują dokładny publiczny tag i budują kod ze źródeł.
- [ ] Zachowano osobny identyfikator aplikacji i nie użyto klucza podpisującego produkcyjne IL.
- [ ] Testy na Androidzie 11 zakończono przed utworzeniem wydania lub zgłoszeniem do oficjalnego repozytorium.
