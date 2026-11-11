# Ispina Lokalnie 1.80.66 PLANNER

Źródło robocze oparte na pełnym projekcie 1.80.60 FULL SYNC, przygotowane do bezpiecznej kompilacji Gradle.

Zmiany:
- applicationId/namespace: com.ispina.lokalnie (zgodność z działającą gałęzią 1.80.58),
- versionCode 18066 / versionName 1.80.66,
- ciaśniejszy układ Start/Cel i wycentrowany przycisk zamiany,
- subtelnie odróżnione kafelki szybkich kierunków,
- osobna stylizacja daty i godziny,
- osobne kolory kart wyników A4/A7/A65/gminna,
- ręczne „Odśwież wszystko” uruchamia także ScheduleSync,
- status cache MLD/gminnej w Ustawieniach,
- fallback linii gminnej oparty na nowszej trasie z nawrotem przez Gawłówek Dom Ludowy,
- endpoint Apps Script /exec pozostaje zapisany w SyncConfig.

Nie jest to jeszcze APK. Do wydania wymaga pełnej kompilacji Gradle i podpisania tym samym kluczem co działająca 1.80.58.
