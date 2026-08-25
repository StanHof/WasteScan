package com.example.wastescanner

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content
import com.google.ai.client.generativeai.type.generationConfig
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.system.measureTimeMillis

/**
 * Chmurowy (Gemini) klasyfikator bezpieczeństwa składu produktu dla psa (Faza 2 planu).
 *
 * Pipeline:
 *  1. Zdjęcie + prompt (z całą zawartością dog_toxicity_database.json jako źródłem prawdy w
 *     kontekście) trafia do Gemini. Model sam wykonuje OCR i dopasowanie - w przeciwieństwie do
 *     trybu lokalnego nie ma tu oddzielnych etapów detekcji/segmentacji/rozpoznawania.
 *  2. Model zwraca ustrukturyzowany JSON (wymuszony przez generationConfig.responseMimeType =
 *     "application/json") z listą składników + swoją oceną ryzyka.
 *  3. ŚWIADOMA DECYZJA: dla składników, którym model przypisał id z bazy wiedzy, mechanizm/objawy/
 *     źródła NIE są brane z odpowiedzi modelu, tylko dociągane lokalnie przez
 *     ToxicityRepository.buildMatchFromKnownId() - LLM ma tendencję do halucynowania dokładnej
 *     treści cytowań, a to są dane bezpieczeństwa, więc merytoryczna treść musi pochodzić
 *     wyłącznie z zweryfikowanej bazy wiedzy, nie z regurgitacji modelu. Model odpowiada tu
 *     tylko za OCR + dopasowanie + ocenę składników spoza bazy.
 */
class CloudIngredientClassifier(private val context: Context) : ClassifierStrategy {

    companion object {
        private const val TAG = "CloudIngredientClassifier"
        private const val KNOWLEDGE_BASE_ASSET = "dog_toxicity_database.json"
    }

    private val apiKey = BuildConfig.GEMINI_API_KEY
    private val repository by lazy { ToxicityRepository.getInstance(context) }

    override suspend fun analyzeIngredients(bitmap: Bitmap): IngredientSafetyReport =
        withContext(Dispatchers.IO) {
            var report: IngredientSafetyReport
            val timeTaken = measureTimeMillis {
                report = try {
                    if (apiKey.isBlank()) {
                        IngredientSafetyReport(
                            comment = "Brak klucza API Gemini."
                        )
                    } else {
                        val knowledgeBaseJson = loadKnowledgeBaseJson()
                        val generativeModel = GenerativeModel(
                            modelName = "gemini-2.5-flash",
                            apiKey = apiKey,
                            generationConfig = generationConfig { responseMimeType = "application/json" }
                        )
                        val response = generativeModel.generateContent(
                            content {
                                image(bitmap)
                                text(buildPrompt(knowledgeBaseJson))
                            }
                        )
                        parseResponse(response.text)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Błąd podczas chmurowej analizy składu", e)
                    IngredientSafetyReport(
                        comment = "Błąd połączenia z serwerem: ${e.localizedMessage ?: e.javaClass.simpleName}"
                    )
                }
            }
            report.copy(executionTimeMs = timeTaken)
        }

    private fun loadKnowledgeBaseJson(): String =
        context.assets.open(KNOWLEDGE_BASE_ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() }

    private fun buildPrompt(knowledgeBaseJson: String): String = """
        Jesteś asystentem analizującym zdjęcie etykiety produktu spożywczego pod kątem bezpieczeństwa dla psów.

        Zadanie:
        1. Odczytaj z załączonego zdjęcia pełną listę składników produktu (OCR).
        2. Dla KAŻDEGO odczytanego składnika sprawdź, czy odpowiada (dokładnie albo jako alias/synonim,
           uwzględniając literówki OCR) jednej z pozycji w bazie wiedzy podanej niżej. Jeśli tak -
           w polu "matchedSubstanceId" podaj DOKŁADNIE wartość pola "id" tej pozycji z bazy.
        3. Jeśli rozpoznajesz składnik jako potencjalnie niebezpieczny dla psów, ale NIE występuje on
           w bazie wiedzy poniżej - również go zgłoś, zostaw "matchedSubstanceId" jako null i podaj
           własną ocenę "riskLevel", "matchedName", "mechanism" i "symptoms" na podstawie ogólnej
           wiedzy weterynaryjnej.
        4. NIE zgłaszaj składników bezpiecznych/neutralnych dla psów (np. cukier, mąka, woda) - lista
           "ingredients" ma zawierać WYŁĄCZNIE składniki ostrożne, niebezpieczne lub krytyczne
           (riskLevel >= 2).
        5. Jeśli na zdjęciu nie ma czytelnej listy składników, zwróć pustą listę "ingredients" i
           wyjaśnij to krótko w polu "comment".

        Skala riskLevel: 1 = Bezpieczne, 2 = Ostrożnie, 3 = Niebezpieczne, 4 = Krytyczne.

        BAZA WIEDZY O TOKSYCZNOŚCI DLA PSÓW (źródło prawdy, JSON):
        $knowledgeBaseJson

        Odpowiedz WYŁĄCZNIE poprawnym JSON-em o dokładnie takim kształcie (bez markdown, bez
        dodatkowego tekstu poza JSON-em):
        {
          "ingredients": [
            {
              "rawText": "tekst dokładnie tak, jak wystąpił na etykiecie",
              "matchedSubstanceId": "id z bazy wiedzy albo null",
              "matchedName": "nazwa składnika po polsku",
              "riskLevel": 1,
              "mechanism": "krótki opis mechanizmu działania (istotne tylko gdy matchedSubstanceId == null)",
              "symptoms": ["objaw1", "objaw2"]
            }
          ],
          "comment": "krótkie podsumowanie dla użytkownika po polsku, albo null"
        }
    """.trimIndent()

    private fun parseResponse(responseText: String?): IngredientSafetyReport {
        if (responseText.isNullOrBlank()) {
            return IngredientSafetyReport(comment = "Model nie zwrócił żadnej odpowiedzi.")
        }

        val parsed = try {
            Gson().fromJson(responseText, CloudResponseJson::class.java)
        } catch (e: Exception) {
            Log.e(TAG, "Nie udało się sparsować odpowiedzi modelu jako JSON: $responseText", e)
            return IngredientSafetyReport(
                comment = "Nie udało się przetworzyć odpowiedzi modelu (nieprawidłowy JSON).",
                rawOcrText = responseText
            )
        }

        val matches = parsed?.ingredients.orEmpty().mapNotNull { item -> toMatch(item) }

        return if (matches.isEmpty()) {
            IngredientSafetyReport(
                comment = parsed?.comment
                    ?: "Nie wykryto żadnego znanego ryzykownego składnika. To NIE oznacza automatycznie, że produkt jest bezpieczny."
            )
        } else {
            IngredientSafetyReport.fromIngredients(ingredients = matches, comment = parsed?.comment)
        }
    }

    /** Zna id z bazy -> hydratacja z lokalnej, zweryfikowanej bazy wiedzy (patrz komentarz klasy).
     *  Nieznane id (halucynacja modelu) albo brak id -> best-effort na podstawie samej odpowiedzi modelu. */
    private fun toMatch(item: CloudIngredientJson): IngredientMatch? {
        val knownId = item.matchedSubstanceId
        if (!knownId.isNullOrBlank()) {
            repository.buildMatchFromKnownId(knownId, item.rawText ?: item.matchedName ?: knownId)
                ?.let { return it }
            Log.w(TAG, "Model zwrócił matchedSubstanceId '$knownId', którego nie ma w bazie wiedzy - traktuję jako niezweryfikowany.")
        }

        val name = item.matchedName ?: item.rawText ?: return null
        return IngredientMatch(
            rawText = item.rawText ?: name,
            matchedSubstanceId = null,
            matchedName = name,
            riskLevel = RiskLevel.fromValue(item.riskLevel ?: RiskLevel.CAUTION.value),
            mechanism = item.mechanism,
            symptoms = item.symptoms.orEmpty()
        )
    }
}

private data class CloudIngredientJson(
    val rawText: String? = null,
    val matchedSubstanceId: String? = null,
    val matchedName: String? = null,
    val riskLevel: Int? = null,
    val mechanism: String? = null,
    val symptoms: List<String>? = null
)

private data class CloudResponseJson(
    val ingredients: List<CloudIngredientJson>? = null,
    val comment: String? = null
)
