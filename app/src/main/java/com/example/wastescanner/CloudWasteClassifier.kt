package com.example.wastescanner

import android.graphics.Bitmap
import com.example.wastescanner.BuildConfig
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.system.measureTimeMillis

private data class GeminiResponse(
    val label: String,
    val confidence: Float,
    val comment: String
)

class CloudWasteClassifier : ClassifierStrategy {

    private val apiKey = BuildConfig.GEMINI_API_KEY

    override suspend fun classify(bitmap: Bitmap): AnalysisReport = withContext(Dispatchers.IO) {
        var finalResults: List<ClassificationResult> = emptyList()
        var aiComment: String? = null

        val timeTaken = measureTimeMillis {
            try {
                // 1. Inicjalizacja modelu Gemini 1.5 Flash
                val generativeModel = GenerativeModel(
                    modelName = "gemini-2.5-flash",
                    apiKey = apiKey
                )

                // 2. Precyzyjny Prompt Inżynieryjny (wymuszający odpowiedź w formacie JSON)
                val prompt = """
                    Jesteś profesjonalnym ekspertem ds. recyklingu i sortowania odpadów w Polsce.
                    Przeanalizuj to zdjęcie.
                    
                    ZASADA SPECJALNA (ZABEZPIECZENIE):
                    Jeśli na zdjęciu NIE MA żadnego ewidentnego śmiecia ani odpadu (np. zdjęcie przedstawia człowieka, zwierzę, działający sprzęt domowy, krajobraz, puste biurko), MUSISZ zignorować normalne kategorie i zwrócić etykietę "Brak_Odpadu".
                    
                    Jeśli na zdjęciu JEST odpad, wybierz TYLKO JEDNĄ z poniższych kategorii:
                    [Plastik, Papier, Szkło, Bio, Metal, Zmieszane]
                    
                    Zwróć odpowiedź WYŁĄCZNIE w formacie JSON, dokładnie według poniższego schematu (nie dodawaj żadnego innego tekstu, znaczników markdown ani niczego poza czystym JSONem):
                    {
                      "label": "Nazwa_Kategorii",
                      "confidence": 0.99,
                      "comment": "Krótka porada do jakiego pojemnika to wyrzucić. (Jeśli to 'Brak Odpadu', napisz po prostu: 'To nie wygląda jak śmieć, nie wrzucaj tego do kosza!')."
                    }
                """.trimIndent()

                // 3. Wysłanie zdjęcia i tekstu do chmury Google
                val response = generativeModel.generateContent(
                    content {
                        image(bitmap)
                        text(prompt)
                    }
                )

                // 4. Odczytanie odpowiedzi
                val responseText = response.text ?: ""

                // Gemini czasem owija JSON w znaczniki ```json ... ```, dla bezpieczeństwa je usuwamy
                val cleanJson = responseText.replace("```json", "").replace("```", "").trim()

                // 5. Tłumaczenie tekstu na obiekt (Gson)
                val geminiResponse = Gson().fromJson(cleanJson, GeminiResponse::class.java)

                // 6. Przekazanie wyników do formatu zrozumiałego dla naszej aplikacji
                finalResults = listOf(
                    ClassificationResult(geminiResponse.label, geminiResponse.confidence)
                )
                aiComment = geminiResponse.comment

            } catch (e: Exception) {
                e.printStackTrace()
                aiComment = "Błąd połączenia z serwerem: ${e.localizedMessage}"
            }
        }

        AnalysisReport(
            results = finalResults,
            comment = aiComment,
            executionTimeMs = timeTaken
        )
    }
}