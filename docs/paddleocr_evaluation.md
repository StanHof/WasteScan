# PaddleOCR jako alternatywne podejście lokalne — ocena wykonalności

## 1. Motywacja

Benchmark lokalnego pipeline'u (EAST + własna segmentacja + CRNN trenowany na syntetycznym
angielskim tekście mjsynth) na 8-zdjęciowym zestawie testowym dał recall na poziomie ~4% względem
oczekiwanych substancji z manifestu, wobec ~69% dla trybu chmurowego (Gemini). Ponieważ głównym
celem pracy jest porównanie podejścia lokalnego i chmurowego w aplikacji wykorzystującej uczenie
maszynowe, tak duża rozbieżność wymagała sprawdzenia, czy problem leży w samym pomyśle "OCR
on-device", czy tylko w konkretnym, dotychczas użytym modelu rozpoznawania. PaddleOCR (rodzina
modeli PP-OCR) wybrano jako kandydata do sprawdzenia, ponieważ jego detektor (DB) i rozpoznawanie
(nowsze wersje: SVTR) są architektonicznie inne niż EAST+CRNN, a model PP-OCRv6 jest trenowany na
znacznie szerszym, wielojęzycznym korpusie niż mjsynth.

Sprawdzenie przeprowadzono dwuetapowo, świadomie od najtańszego możliwego testu w górę: najpierw
czysto programistyczny test na komputerze (Etap 0), a dopiero po jego pozytywnym wyniku - próba na
realnym urządzeniu z Androidem (Etap 1).

## 2. Etap 0: test na komputerze (Python, `paddleocr`)

### Metoda

Instalacja pakietu `paddleocr` (wersja 3.7.0, z zależnością `paddlepaddle` 3.3.1) w izolowanym
środowisku wirtualnym (`venv`), uruchomienie `PaddleOCR(lang="pl", enable_mkldnn=False)` na tych
samych 8 zdjęciach, które składają się na zestaw testowy aplikacji
(`app/src/main/assets/benchmark/`).

**Uwaga praktyczna**: `enable_mkldnn=False` nie jest opcjonalne - domyślna konfiguracja (z
włączonym mkldnn/oneDNN) rzuca błąd `(Unimplemented) ConvertPirAttribute2RuntimeAttribute not
support...` na tym konkretnym zestawie CPU/paddlepaddle. Wyłączenie mkldnn w pełni rozwiązuje ten
problem.

### Weryfikacja pokrycia języka polskiego (w kodzie źródłowym, nie zgadywana)

Sprawdzono bezpośrednio w źródłach pakietu (`paddleocr/_utils/langs.py`,
`paddleocr/_pipelines/ocr.py`):

- `"pl"` znajduje się w zbiorze `LATIN_LANGS`, a stamtąd (poza wyjątkiem `"pi"`) trafia do
  `_PPOCRV6_LANGS`.
- Dla `ppocr_version == "PP-OCRv6"` funkcja `_get_ocr_model_names()` zwraca **dokładnie ten sam**
  model (`PP-OCRv6_medium_det`, `PP-OCRv6_medium_rec`) niezależnie od tego, który z ~50+ języków
  z `_PPOCRV6_LANGS` zostanie wybrany - w przeciwieństwie do PP-OCRv5, gdzie każdy język (grupa
  językowa) ma osobny model (`{lang}_PP-OCRv5_mobile_rec`).
- Wniosek: PP-OCRv6 to jeden, współdzielony model wielojęzyczny, więc mniejsze warianty
  (`PP-OCRv6_small`, `PP-OCRv6_tiny`) powinny obsługiwać polskie znaki diakrytyczne tak samo jak
  `medium`, różniąc się głównie pojemnością/dokładnością, a nie zestawem obsługiwanych znaków.

### Wyniki

**Test syntetyczny (analogiczny do `TfliteTextRecognizerEngine.runSyntheticSanityCheck()` w
aplikacji)**: tekst `"Zażółć gęślą jaźń - chlorowodorek"` wygenerowany programistycznie (bez szumu
aparatu) został rozpoznany **dokładnie poprawnie**, znak po znaku, włącznie ze wszystkimi polskimi
znakami diakrytycznymi.

**Recall na 8 zdjęciach testowych** (liczony przez `paddleocr_score.py`: dopasowanie
alias/substring z `dog_toxicity_database.json` względem `expectedSubstanceIds` z manifestu):

| Zdjęcie | Recall | Uwagi |
|---|---|---|
| paracetamol.jpg | 100% | |
| cheddar.jpg | 50% | znaleziono milk_dairy, nie znaleziono excess_salt |
| cocoamix.jpg | 100% | |
| creatin.jpg | 100% | w tym "Sukraloza" i opis kompleksów miedziowych chlorofili |
| instantsoup.jpg | 0% | gęsta, wielojęzyczna etykieta - patrz niżej |
| medikinet.jpg | 100% | poprawnie odczytane "metylofenidatu chlorowodorek" |
| peanutbutter.jpg | 100% | |
| windowwash.jpg | 100% | odczytano dosłownie "1,2-benzoizotiazol-3(2H)-on" |

Średnia recall (średnia z wartości per zdjęcie): **~81%**. Recall łączny (suma trafień / suma
oczekiwanych substancji, 10/14): **71%**.

### Porównanie z pozostałymi silnikami (ten sam zestaw 8 zdjęć)

| Silnik | Średni recall |
|---|---|
| Lokalny, własny (EAST + CRNN) | ~4% |
| Chmurowy (Gemini) | ~69% |
| PaddleOCR (desktop, `lang="pl"`, `PP-OCRv6_medium`) | ~81% |

### Zastrzeżenia

- Próbka 8 zdjęć / 14 oczekiwanych substancji jest mała - silny sygnał, nie dowód statystyczny.
- Test wykonano na CPU komputera z pełnowymiarowym modelem `PP-OCRv6_medium` (nie mobilnym
  `small`/`tiny`, jaki realnie trafiłby na telefon) - to raczej górna granica możliwości niż
  realistyczna prognoza dla urządzenia mobilnego.
- Metoda oceny (`paddleocr_score.py`) sprawdza dopasowanie substring/alias, nie pełną,
  tolerancyjną na literówki logikę Levenshteina używaną w `ToxicityRepository.kt` - realna
  aplikacja może wypaść nieco inaczej (raczej lepiej niż gorzej, biorąc pod uwagę, że fuzzy
  matching jest bardziej wybaczający niż dokładne dopasowanie substring).
- `instantsoup.jpg` (gęsta etykieta wielojęzyczna: polski, estoński, łotewski, litewski na jednym
  panelu) to jedyne wyraźnie słabe miejsce - model skonfigurowany na `lang="pl"` nie ma takiej
  ogólnej wielojęzycznej "inteligencji kontekstowej" jak model multimodalny ogólnego przeznaczenia
  (Gemini), który akurat na tym zdjęciu osiągnął 100%.

## 3. Etap 1: walidacja na realnym urządzeniu (oficjalne demo `ppocr-android`)

### Konfiguracja

Oficjalne demo Android z repozytorium PaddleOCR (`deploy/ppocr-android`), wykorzystujące ONNX
Runtime (nie Paddle Lite) + OpenCV, zbudowane z modelami `PP-OCRv6_small` (detekcja +
rozpoznawanie, pobrane z oficjalnych linków HuggingFace/BOS). Build: Gradle 8.9, JDK 17. Test na
realnym telefonie z Androidem 16 (arm64-v8a).

### Trzy kolejne niezgodności natywne - napotkane i zdiagnozowane po kolei

| # | Objaw | Przyczyna źródłowa | Poprawka |
|---|---|---|---|
| 1 | `dlopen failed: ... cannot locate symbol "__sfp_handle_exceptions"` | `com.quickbirdstudios:opencv:4.5.3` - zależność porzucona od 2021 (potwierdzone przez Maven Central: brak nowszych wydań w tej grupie). Jej `libopencv_java4.so` odwołuje się do symbolu Bionic libc usuniętego w nowszych wersjach Androida. | Podmieniono natywne `.so` (przez `packaging.jniLibs.pickFirsts`) na build z oficjalnego, wciąż utrzymywanego OpenCV 5.0.0, pod tą samą nazwą pliku. |
| 2 | `dlopen failed: ... cannot locate symbol "_ZTTNSt6__ndk1..."` | Ta sama porzucona zależność dołączała też własny, przestarzały `libc++_shared.so` (konwencja `__ndk1`, sprzed lat) - jedyny plik o tej nazwie w całym drzewie zależności, więc trafiał do finalnego APK. Nowy `libopencv_java4.so` (zbudowany współczesnym NDK) nie mógł się z nim skonsolidować. | Podmieniono `libc++_shared.so` (ten sam mechanizm `pickFirsts`) na plik z lokalnie zainstalowanego Android NDK 27.1.12297006. |
| 3 | `No implementation found for void org.opencv.core.Mat.n_delete(long)` + `OutOfMemoryError` | Dryf sygnatur JNI między klasami Javy `org.opencv.*` (skompilowanymi względem OpenCV 4.5.3, wciąż potrzebnymi - są szeroko używane w `DetectionEngine`, `DBPostProcessor`, `preprocess/*`) a podmienioną biblioteką natywną OpenCV 5.0.0 - "ta sama główna wersja" okazała się niewystarczającym założeniem przy skoku 4.x→5.x. | Zamiana natywnego `.so` na OpenCV **4.14.0** (ta sama linia 4.x, wciąż aktualna/współczesna, więc nadal odporna na problem #1) zamiast 5.0.0. To naprawiło rozpoznawanie, ale **nie** naprawiło konkretnie `n_delete` - obiekty `Mat` przestały być poprawnie zwalniane natywnie, co przy zdjęciu z dużą liczbą wykrytych regionów (`instantsoup.jpg`) doprowadziło do wyczerpania pamięci. |

### Wynik na realnym urządzeniu

- **`medikinet.jpg`** (etykieta prosta, mało tekstu): pipeline zadziałał **poprawnie od początku
  do końca**, rozpoznając m.in. "metylofenidatu chlorowodorek" - **100% trafień**, zgodnie z
  wynikiem z Etapu 0.
- **`instantsoup.jpg`** (etykieta gęsta, dużo wykrytych regionów tekstu): `OutOfMemoryError` w
  trakcie przetwarzania, spowodowany wyciekiem pamięci natywnej z problemu #3 powyżej - nie jest to
  błąd jakości OCR, tylko zarządzania pamięcią w tym konkretnym, "zszywanym" zestawie zależności.

## 4. Decyzja: zatrzymanie dalszego łatania tego demo (Opcja B)

Trzy kolejne, niezależne niezgodności ABI w jednej, porzuconej od 2021 roku zależności to
malejący zwrot z dalszego inwestowania czasu w łatanie punktowe. Co ważne - to nie jest
reprezentatywne dla realnej integracji: docelowa integracja wybrałaby **jedną, spójną, nowoczesną
wersję OpenCV od początku** (przetestowaną razem z resztą zależności), zamiast rekonstruować ją
kawałek po kawałku z 5-letniej biblioteki AAR.

**Co uznajemy za potwierdzone**:
- Jakość OCR PaddleOCR dla języka polskiego (w tym znaków diakrytycznych) jest wysoka - potwierdzone
  zarówno na komputerze, jak i na realnym urządzeniu (dla zdjęcia, które zdążyło się przetworzyć
  przed wyczerpaniem pamięci).
- Integracja na Androida jest wykonalna, ale wymaga świadomego doboru wersji zależności od
  początku, a nie "doklejania" do już istniejącego, nieaktualizowanego szkieletu demo.

**Co pozostaje otwarte** (celowo nierozstrzygnięte w tym dokumencie - do osobnej decyzji):
- Trening własnego modelu rozpoznawania (dotychczasowa infrastruktura EAST+TFLite zostaje, zmienia
  się tylko model rozpoznawania i dane treningowe).
- Tesseract (dojrzalszy silnik z gotowym modelem języka polskiego, inna architektura niż obecny
  CRNN).
- Właściwa integracja SDK PaddleOCR do aplikacji przez istniejący interfejs
  `TextRecognizerEngine` (ten sam punkt rozszerzenia, którego już używają
  `MlKitTextRecognizerEngine` i `TfliteTextRecognizerEngine`) - ale z samodzielnie dobranym,
  spójnym zestawem zależności zamiast fragmentu cudzego demo.
- ML Kit jako "za darmo" dostępny w kodzie punkt odniesienia (dotychczas nieużywany, mimo że
  `LocalIngredientClassifier` domyślnie go zakłada).

## 5. Materiały źródłowe / odtwarzalność

- Wersje: `paddleocr` 3.7.0 / `paddlepaddle` 3.3.1 (Etap 0); OpenCV 4.14.0 Android SDK; Android NDK
  27.1.12297006; ONNX Runtime (wersja z `ppocr-android`'s `libs.versions.toml`); modele
  `PP-OCRv6_small` (det + rec, pobrane z oficjalnych linków HuggingFace/BOS podanych w
  dokumentacji PaddleOCR: `deploy/ppocr-android`).
- Skrypty i surowe wyniki Etapu 0: `paddleocr_run_test.py`, `paddleocr_score.py`,
  `paddleocr_results.txt`, `manifest.json` - `C:\Users\stanh\Desktop\dataset\` (poza repozytorium
  git, jako dane robocze).
- Zestaw testowy używany w obu etapach: `app/src/main/assets/benchmark/` (8 zdjęć + manifest z
  oczekiwanymi substancjami).
- Fragment `app/build.gradle.kts` z ostatecznie działającej konfiguracji `packaging.jniLibs`
  (demo `ppocr-android`, do odtworzenia w razie powrotu do tego wątku):

  ```kotlin
  packaging {
      jniLibs {
          pickFirsts += "**/libopencv_java4.so"   // OpenCV 4.14.0, nie 4.5.3 ani 5.0.0
          pickFirsts += "**/libc++_shared.so"     // z lokalnego NDK 27.1.12297006
      }
  }
  ```
