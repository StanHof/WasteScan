package com.example.wastescanner

import android.graphics.Bitmap

/**
 * Poziom ryzyka danego składnika lub całego produktu dla psa.
 * Zgodny ze skalą zdefiniowaną w bazie wiedzy (dog_toxicity_database.json).
 */
enum class RiskLevel(val value: Int, val labelPL: String) {
    SAFE(1, "Bezpieczne"),
    CAUTION(2, "Ostrożnie"),
    DANGEROUS(3, "Niebezpieczne"),
    CRITICAL(4, "Krytyczne");

    companion object {
        fun fromValue(value: Int): RiskLevel =
            entries.firstOrNull { it.value == value } ?: SAFE
    }
}

/**
 * Pojedyncze źródło informacji o substancji (do wyświetlenia w rozwijanym widoku wyniku).
 */
data class IngredientSource(
    val name: String,
    val url: String
)

/**
 * Wynik dopasowania pojedynczego rozpoznanego składnika do bazy wiedzy.
 *
 * Faza 3: rozszerzone o matchedName/mechanism/symptoms/sources względem wersji z Fazy 1 -
 * potrzebne do rozwijanego widoku szczegółów na ResultScreen (klik -> mechanizm, objawy, źródło).
 *
 * @param rawText fragment tekstu tak, jak został odczytany na etykiecie (kontekst dla użytkownika)
 * @param matchedSubstanceId identyfikator z bazy wiedzy (np. "xylitol"), null jeśli nie rozpoznano
 * @param matchedName nazwa PL substancji z bazy wiedzy, używana jako główny nagłówek na liście
 * @param riskLevel poziom ryzyka tego konkretnego składnika
 * @param mechanism krótki opis mechanizmu działania (pole "mechanism" z bazy wiedzy)
 * @param symptoms lista obserwowalnych objawów (pole "symptoms" z bazy wiedzy)
 * @param sources lista źródeł z adresem URL (pole "sources" z bazy wiedzy)
 */
data class IngredientMatch(
    val rawText: String,
    val matchedSubstanceId: String?,
    val matchedName: String,
    val riskLevel: RiskLevel,
    val mechanism: String? = null,
    val symptoms: List<String> = emptyList(),
    val sources: List<IngredientSource> = emptyList()
)

/**
 * Pełny raport analizy bezpieczeństwa produktu dla psa.
 * Zastępuje dotychczasowy AnalysisReport z MVP klasyfikatora odpadów.
 *
 * @param ingredients lista wszystkich rozpoznanych składników z ich oceną ryzyka
 * @param overallRisk zagregowany poziom ryzyka całego produktu (zasada "najsłabszego ogniwa" -
 *        patrz metodologia_bazy_wiedzy.md, sekcja 5)
 * @param comment ludzki komentarz podsumowujący (np. z Gemini albo wygenerowany lokalnie)
 * @param rawOcrText surowy tekst odczytany z etykiety, zachowany do debugowania/audytu
 * @param executionTimeMs czas przetwarzania - używany do porównania local vs. cloud (Faza 5 planu)
 */
data class IngredientSafetyReport(
    val ingredients: List<IngredientMatch> = emptyList(),
    val overallRisk: RiskLevel = RiskLevel.SAFE,
    val comment: String? = null,
    val rawOcrText: String? = null,
    val executionTimeMs: Long = 0L
) {
    companion object {
        /**
         * Buduje raport, sortując składniki malejąco wg poziomu ryzyka (najpierw najgroźniejsze)
         * i automatycznie wyliczając overallRisk jako najwyższy wykryty poziom ("najsłabsze ogniwo").
         */
        fun fromIngredients(
            ingredients: List<IngredientMatch>,
            comment: String? = null,
            rawOcrText: String? = null,
            executionTimeMs: Long = 0L
        ): IngredientSafetyReport {
            val sorted = ingredients.sortedByDescending { it.riskLevel.value }
            val overall = sorted.firstOrNull()?.riskLevel ?: RiskLevel.SAFE
            return IngredientSafetyReport(sorted, overall, comment, rawOcrText, executionTimeMs)
        }
    }
}

/**
 * Wspólny kontrakt dla implementacji lokalnej (on-device OCR) i chmurowej (Gemini).
 * Zastępuje poprzednią sygnaturę `classify(bitmap): AnalysisReport` z MVP klasyfikatora odpadów.
 */
interface ClassifierStrategy {
    suspend fun analyzeIngredients(bitmap: Bitmap): IngredientSafetyReport
}