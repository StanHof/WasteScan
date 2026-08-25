package com.example.wastescanner

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.example.wastescanner.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.system.measureTimeMillis

/**
 * Lokalny (on-device) klasyfikator bezpieczeństwa składu produktu dla psa.
 *
 * Faza 3 planu - pełny pipeline:
 *  1. Zdjęcie (bitmap) trafia do wstrzykniętego TextRecognizerEngine (rozpoznawanie tekstu
 *     on-device). Klasa nie wie i nie musi wiedzieć, czy to ML Kit czy własny model TFLite -
 *     to jest właśnie ten seam architektoniczny, który pozwala podmienić silnik OCR bez
 *     zmiany reszty pipeline'u.
 *  2 -> 4. Lista rozpoznanych słów przekazywana jest (jako połączony string, dla zgodności
 *     z istniejącym ToxicityRepository) do ToxicityRepository, które tokenizuje ją ponownie,
 *     porównuje z bazą wiedzy i zwraca wszystkie dopasowane składniki.
 *
 * WYMAGANA KONFIGURACJA PROJEKTU (poza tym plikiem):
 *  - Umieścić plik dog_toxicity_database.json w katalogu app/src/main/assets/.
 *  - Domyślny silnik to MlKitTextRecognizerEngine (wymaga zależności
 *    "com.google.mlkit:text-recognition:16.0.1" w build.gradle - jak dotychczas).
 *  - Żeby przełączyć się na własny model TFLite: przekaż w konstruktorze
 *    TfliteTextRecognizerEngine(context) zamiast domyślnego silnika (patrz przykład w
 *    MainActivity.kt / TfliteTextRecognizerEngine.kt po szczegóły konfiguracji modelu).
 */
class LocalIngredientClassifier(
    private val context: Context,
    private val textRecognizerEngine: TextRecognizerEngine = MlKitTextRecognizerEngine()
) : ClassifierStrategy {

    companion object {
        private const val TAG = "LocalIngredientClassifier"
    }

    private val repository by lazy { ToxicityRepository.getInstance(context) }

    override suspend fun analyzeIngredients(bitmap: Bitmap): IngredientSafetyReport =
        withContext(Dispatchers.Default) {
            var report: IngredientSafetyReport
            val timeTaken = measureTimeMillis {
                report = try {
                    if (BuildConfig.DEBUG) {
                        val debugPath = StorageManager.saveDebugOcrInputBitmap(context, bitmap)
                        Log.d(
                            TAG,
                            "OCR input: ${bitmap.width}x${bitmap.height}px, config=${bitmap.config}, " +
                                    "zapisano do podglądu: $debugPath (Device File Explorer -> " +
                                    "data/data/${context.packageName}/files/debug_ocr_input.jpg)"
                        )
                    }

                    val recognizedWords = textRecognizerEngine.recognizeWords(bitmap)
                    // Łączymy listę słów w jeden string - ToxicityRepository.findMatches() i tak
                    // tokenizuje go ponownie, więc oba silniki (ML Kit i TFLite) trafiają do
                    // identycznej dalszej ścieżki, niezależnie od tego, jak dokładnie segmentowały tekst.
                    val rawText = recognizedWords.joinToString(" ")
                    Log.d(TAG, "Silnik OCR rozpoznał ${recognizedWords.size} słów: $recognizedWords")

                    when {
                        rawText.isBlank() -> IngredientSafetyReport(
                            comment = "Nie udało się odczytać tekstu ze zdjęcia. Upewnij się, że etykieta " +
                                    "jest czytelna, dobrze oświetlona i wypełnia kadr, a następnie spróbuj ponownie."
                        )
                        else -> {
                            val matches = repository.findMatches(rawText)
                            when {
                                !repository.isDatabaseLoaded() -> IngredientSafetyReport(
                                    rawOcrText = rawText,
                                    comment = "BŁĄD KONFIGURACJI: baza wiedzy o toksyczności nie została wczytana " +
                                            "(0 wpisów). Sprawdź, czy plik dog_toxicity_database.json znajduje się " +
                                            "dokładnie w app/src/main/assets/ i czy jest poprawnym JSON-em. " +
                                            "Szczegóły błędu: Logcat, tag ToxicityRepository."
                                )
                                matches.isEmpty() -> IngredientSafetyReport(
                                    rawOcrText = rawText,
                                    comment = "Odczytano tekst, ale nie znaleziono w nim żadnego składnika z bazy " +
                                            "wiedzy (przeszukano ${repository.substanceCount()} wpisów). To NIE " +
                                            "oznacza automatycznie, że produkt jest bezpieczny - baza nie jest " +
                                            "wyczerpująca."
                                )
                                else -> IngredientSafetyReport.fromIngredients(ingredients = matches, rawOcrText = rawText)
                            }
                        }
                    }
                } catch (e: Exception) {
                    // Świadoma decyzja: żaden wyjątek z tego pipeline'u (OCR, wczytanie bazy,
                    // dopasowanie) nie może zrzucić całej aplikacji. Zamiast tego trafia do
                    // Logcat i do użytkownika jako czytelny komunikat w raporcie.
                    Log.e(TAG, "Błąd podczas lokalnej analizy składu", e)
                    IngredientSafetyReport(
                        comment = "Wystąpił nieoczekiwany błąd podczas analizy: ${e.localizedMessage ?: e.javaClass.simpleName}"
                    )
                }
            }
            report.copy(executionTimeMs = timeTaken)
        }
}