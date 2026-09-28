# Jak bezpiecznie wgrać ten pakiet do GitHub

## Najważniejsze

Ten pakiet jest przygotowaniem infrastruktury GitHub/F-Droid i zawiera techniczną bazę źródłową z linii 1.80.x. **Nie zastępuj nim od razu działającej wersji produkcyjnej 1.82.x na branchu `main`.**

## Najbezpieczniejszy wariant

1. Otwórz repozytorium `senikolo/Ispina-Lokalnie-`.
2. Utwórz nową gałąź o nazwie `open-source-fdroid-prep` z `main`.
3. Przełącz się na tę gałąź.
4. Wgraj zawartość tego ZIP-a do katalogu głównego gałęzi.
5. Nie usuwaj jeszcze starych plików, dopóki build nowej struktury nie zostanie zweryfikowany.
6. W zakładce Actions uruchom build testowy.
7. Dopiero po przeniesieniu wszystkich zmian z 1.82.x i testach można rozważyć scalenie do `main`.

## Co jest w pakiecie

- pełna struktura projektu Android z ostatniej dostępnej pełnej bazy źródłowej,
- `.github/workflows/android.yml`,
- metadane `fastlane` pod F-Droid,
- `docs/F-DROID.md`,
- `.gitignore` chroniący przed przypadkowym wrzuceniem kluczy,
- checklista wydania.

## Czego celowo nie ma

- pliku `LICENSE` — wybór GPL-3.0-or-later albo Apache-2.0 powinien być świadomą decyzją właściciela projektu,
- klucza podpisującego APK,
- haseł i sekretów,
- deklaracji, że baza 1.80.x jest aktualną wersją 1.82.x.
