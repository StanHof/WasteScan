package com.example.wastescanner

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import java.text.Normalizer
import java.util.Locale

// ---------------------------------------------------------------------------
// Modele do parsowania dog_toxicity_database.json (Faza 0 planu).
// Gson mapuje pola po nazwie i ignoruje te, których tu nie odwzorowano
// (np. riskLevels, matchingNotes) - nie są potrzebne w runtime.
// ---------------------------------------------------------------------------

private data class ToxicitySourceJson(
    val name: String,
    val url: String
)

private data class ToxicitySubstanceJson(
    val id: String,
    val namePL: String,
    val nameEN: String,
    val aliases: List<String> = emptyList(),
    val category: String? = null,
    val riskLevel: Int,
    val mechanism: String? = null,
    val symptoms: List<String> = emptyList(),
    val notes: String? = null,
    val sources: List<ToxicitySourceJson> = emptyList()
)

private data class ToxicityDatabaseJson(
    @SerializedName("schemaVersion") val schemaVersion: String? = null,
    val substances: List<ToxicitySubstanceJson> = emptyList()
)

/**
 * Repozytorium bazy wiedzy o toksyczności składników dla psów.
 *
 * Realizuje kroki 2-4 z opisu Fazy 3:
 *  2. tokenizuje tekst odczytany z etykiety na listę znormalizowanych słów,
 *  3. porównuje tę listę ze wszystkimi aliasami/nazwami w bazie wiedzy,
 *  4. zwraca wszystkie wpisy z bazy, które zostały wykryte w tekście.
 *
 * Dopasowanie łączy dwie strategie:
 *  - DOKŁADNE dla numerów E (np. "E967") - zgodnie z matchingNotes w bazie wiedzy:
 *    różnica jednej cyfry zmienia substancję, więc fuzzy matching byłby tu niebezpieczny.
 *  - ROZMYTE (odległość Levenshteina) dla nazw słownych - żeby tolerować typowe błędy OCR
 *    (np. "ksylitoi" zamiast "ksylitol").
 */
class ToxicityRepository private constructor(
    private val substances: List<ToxicitySubstanceJson>
) {

    /** True, jeśli baza wiedzy wczytała się poprawnie i zawiera przynajmniej jeden wpis. */
    fun isDatabaseLoaded(): Boolean = substances.isNotEmpty()

    fun substanceCount(): Int = substances.size

    /**
     * Zwraca w pełni "zhydrowany" IngredientMatch dla znanego id z bazy wiedzy (mechanizm, objawy,
     * źródła) - używane przez CloudIngredientClassifier, gdzie Gemini zwraca tylko dopasowane id
     * + surowy tekst z etykiety, a merytoryczną treść świadomie bierzemy z lokalnej bazy, a nie z
     * odpowiedzi modelu, żeby uniknąć halucynacji cytowań/źródeł. Zwraca null, jeśli model podał
     * id, którego nie ma w bazie (np. halucynacja) - wywołujący powinien wtedy potraktować wpis
     * jako niezweryfikowany.
     */
    fun buildMatchFromKnownId(substanceId: String, rawText: String): IngredientMatch? {
        val substance = substances.firstOrNull { it.id == substanceId } ?: return null
        return toIngredientMatch(substance, rawText)
    }

    /**
     * Główna metoda: przyjmuje surowy tekst z OCR, zwraca listę dopasowanych składników
     * gotową do umieszczenia w IngredientSafetyReport.
     */
    fun findMatches(rawOcrText: String): List<IngredientMatch> {
        if (rawOcrText.isBlank()) return emptyList()

        val words = tokenize(rawOcrText)
        if (words.isEmpty()) return emptyList()

        val wordSet = words.toSet()
        val joinedText = words.joinToString(" ")

        val results = mutableListOf<IngredientMatch>()
        for (substance in substances) {
            val matchedAlias = findMatchingAlias(substance, words, wordSet, joinedText)
            if (matchedAlias != null) {
                results.add(toIngredientMatch(substance, matchedAlias))
            }
        }
        return results
    }

    private fun findMatchingAlias(
        substance: ToxicitySubstanceJson,
        words: List<String>,
        wordSet: Set<String>,
        joinedText: String
    ): String? {
        val candidateAliases = substance.aliases + substance.namePL + substance.nameEN
        for (alias in candidateAliases) {
            val normalizedAlias = normalize(alias)
            if (normalizedAlias.isBlank()) continue

            val aliasWords = normalizedAlias.split(" ").filter { it.isNotBlank() }

            if (aliasWords.size == 1) {
                val aliasWord = aliasWords.first()
                if (matchesSingleWord(aliasWord, words, wordSet)) return alias
            } else {
                // Alias wielowyrazowy: wymagamy dopasowania jako spójnego fragmentu tekstu.
                // Ograniczenie (do backlogu Fazy 5): nie obsługuje inwersji kolejności słów.
                if (joinedText.contains(normalizedAlias)) return alias
            }
        }
        return null
    }

    private fun matchesSingleWord(aliasWord: String, words: List<String>, wordSet: Set<String>): Boolean {
        val isENumber = E_NUMBER_REGEX.matches(aliasWord)

        // Numery E i bardzo krótkie tokeny (<=3 znaki) - tylko dopasowanie dokładne,
        // żeby uniknąć fałszywych trafień i pomyłek między podobnymi numerami E.
        if (isENumber || aliasWord.length <= 3) {
            return wordSet.contains(aliasWord)
        }

        // Dłuższe słowa - tolerancja na błędy OCR poprzez odległość Levenshteina.
        val maxDistance = if (aliasWord.length >= 7) 2 else 1
        return words.any { levenshtein(it, aliasWord) <= maxDistance }
    }

    private fun toIngredientMatch(substance: ToxicitySubstanceJson, matchedAlias: String): IngredientMatch {
        return IngredientMatch(
            rawText = matchedAlias,
            matchedSubstanceId = substance.id,
            matchedName = substance.namePL,
            riskLevel = RiskLevel.fromValue(substance.riskLevel),
            mechanism = substance.mechanism,
            symptoms = substance.symptoms,
            sources = substance.sources.map { IngredientSource(it.name, it.url) }
        )
    }

    companion object {
        private const val TAG = "ToxicityRepository"
        private const val ASSET_FILE_NAME = "dog_toxicity_database.json"
        private val E_NUMBER_REGEX = Regex("^e\\d{3,4}$")
        // Scala zapisy typu "E 967" / "E-967" do jednej formy "E967" przed normalizacją,
        // żeby tokenizacja nie rozbiła numeru E na dwa osobne słowa.
        private val E_NUMBER_SPACING_REGEX = Regex("(?i)\\bE[\\s-]?(\\d{3,4})\\b")

        @Volatile
        private var INSTANCE: ToxicityRepository? = null

        fun getInstance(context: Context): ToxicityRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: load(context).also { INSTANCE = it }
            }
        }

        private fun load(context: Context): ToxicityRepository {
            return try {
                val json = context.assets.open(ASSET_FILE_NAME).bufferedReader(Charsets.UTF_8).use { it.readText() }
                val database = Gson().fromJson(json, ToxicityDatabaseJson::class.java)
                if (database?.substances.isNullOrEmpty()) {
                    Log.e(TAG, "Baza wiedzy '$ASSET_FILE_NAME' wczytana, ale lista 'substances' jest pusta - sprawdź format pliku.")
                }
                ToxicityRepository(database?.substances ?: emptyList())
            } catch (e: Exception) {
                // Celowo NIE rzucamy dalej: brak/błąd pliku assets nie może crashować całej aplikacji.
                // Zamiast tego zwracamy pustą bazę - findMatches() zwróci wtedy po prostu 0 dopasowań,
                // a nie wywali apkę. Log.e zostawia jasny ślad w Logcat, żeby to łatwo zdiagnozować.
                Log.e(
                    TAG,
                    "Nie udało się wczytać '$ASSET_FILE_NAME' z assets. Sprawdź, czy plik znajduje się " +
                            "dokładnie w app/src/main/assets/$ASSET_FILE_NAME i czy jest poprawnym JSON-em.",
                    e
                )
                ToxicityRepository(emptyList())
            }
        }

        /** Usuwa polskie i inne znaki diakrytyczne oraz sprowadza tekst do małych liter. */
        internal fun normalize(text: String): String {
            val squashedENumbers = E_NUMBER_SPACING_REGEX.replace(text) { m -> "E${m.groupValues[1]}" }
            val lower = squashedENumbers.lowercase(Locale.ROOT)
            val polishReplaced = lower
                .replace('ą', 'a').replace('ć', 'c').replace('ę', 'e')
                .replace('ł', 'l').replace('ń', 'n').replace('ó', 'o')
                .replace('ś', 's').replace('ź', 'z').replace('ż', 'z')
            val decomposed = Normalizer.normalize(polishReplaced, Normalizer.Form.NFD)
            return decomposed.replace(Regex("\\p{Mn}+"), "")
        }

        /** Dzieli znormalizowany tekst na listę pojedynczych "słów" (kroku 2 z opisu Fazy 3). */
        internal fun tokenize(text: String): List<String> {
            return normalize(text).split(Regex("[^a-z0-9]+")).filter { it.isNotBlank() }
        }

        /** Klasyczna odległość edycyjna (programowanie dynamiczne), używana do fuzzy matchingu. */
        internal fun levenshtein(a: String, b: String): Int {
            if (a == b) return 0
            if (a.isEmpty()) return b.length
            if (b.isEmpty()) return a.length

            val prev = IntArray(b.length + 1) { it }
            val curr = IntArray(b.length + 1)

            for (i in 1..a.length) {
                curr[0] = i
                for (j in 1..b.length) {
                    val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                    curr[j] = minOf(
                        curr[j - 1] + 1,      // wstawienie
                        prev[j] + 1,          // usunięcie
                        prev[j - 1] + cost    // podmiana
                    )
                }
                System.arraycopy(curr, 0, prev, 0, curr.size)
            }
            return prev[b.length]
        }
    }
}