package com.example.wastescanner

import android.content.Context

/**
 * Wczytuje słownik znaków (character_dict) wprost z pliku inference.yml modelu rozpoznawania -
 * PP-OCRv6 dołącza go bezpośrednio do metadanych modelu (assets/paddleocr/rec/inference.yml,
 * ok. 18 700 wpisów - pełny wielojęzyczny zestaw znaków, w tym polskie znaki diakrytyczne).
 * Port 1:1 parsera z ModelConfig.kt oficjalnego demo PaddleOCR (Apache 2.0) - był już czystym
 * Kotlinem, bez OpenCV, więc logika przeniesiona bez zmian (tylko drobne uproszczenie API).
 */
object PaddleOcrCharacterDict {

    fun load(context: Context, assetPath: String): List<String> {
        val content = context.assets.open(assetPath).bufferedReader(Charsets.UTF_8).use { it.readText() }
        val dict = extractCharacterDict(content)
        return if (dict.lastOrNull() != " ") dict + " " else dict
    }

    private fun leadingSpaces(line: String): Int = line.takeWhile { it == ' ' }.length

    private fun extractCharacterDict(content: String): List<String> {
        val lines = content.replace("\r\n", "\n").replace('\r', '\n').lines()
        val postProcessLine = lines.indexOfFirst { it.trim() == "PostProcess:" }
        require(postProcessLine >= 0) { "Brak sekcji PostProcess w inference.yml" }

        val postProcessIndent = leadingSpaces(lines[postProcessLine])
        val characterDictLine = findCharacterDictLine(lines, postProcessLine + 1, postProcessIndent)
        require(characterDictLine >= 0) { "Brak character_dict w inference.yml" }

        val characterDictIndent = leadingSpaces(lines[characterDictLine])
        val characters = mutableListOf<String>()
        var lineIndex = characterDictLine + 1
        while (lineIndex < lines.size) {
            val line = lines[lineIndex]
            val indent = leadingSpaces(line)
            val content2 = line.substring(indent)
            val trimmed = content2.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                lineIndex++
                continue
            }
            if (!content2.startsWith("-")) {
                if (indent <= characterDictIndent) break
                lineIndex++
                continue
            }
            characters.add(parseYamlListScalar(content2.substring(1)))
            lineIndex++
        }
        require(characters.isNotEmpty()) { "Pusty character_dict w inference.yml" }
        return characters
    }

    private fun findCharacterDictLine(lines: List<String>, startLine: Int, postProcessIndent: Int): Int {
        var lineIndex = startLine
        while (lineIndex < lines.size) {
            val line = lines[lineIndex]
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                lineIndex++
                continue
            }
            val indent = leadingSpaces(line)
            if (indent <= postProcessIndent) break
            if (trimmed == "character_dict:") return lineIndex
            lineIndex++
        }
        return -1
    }

    private fun parseYamlListScalar(rawValue: String): String {
        val value = rawValue.dropWhile { it == ' ' }
        if (value.length >= 2 && value.first() == '\'' && value.last() == '\'') {
            return value.substring(1, value.length - 1).replace("''", "'")
        }
        if (value.length >= 2 && value.first() == '"' && value.last() == '"') {
            return value.substring(1, value.length - 1)
                .replace("\\\"", "\"").replace("\\\\", "\\")
                .replace("\\n", "\n").replace("\\r", "\r").replace("\\t", "\t")
        }
        return value
    }
}

/**
 * Dekodowanie CTC (greedy: argmax per krok czasowy, zwinięcie powtórzeń, usunięcie "blank")
 * wyjścia modelu rozpoznawania PP-OCRv6. Port 1:1 CTCDecoder.kt oficjalnego demo - już czysty
 * Kotlin, bez OpenCV. UWAGA: w przeciwieństwie do TfliteTextRecognizerEngine.decodeCtc (gdzie
 * blank = OSTATNI indeks), tu blank = indeks 0, a character_dict jest przesunięty o 1
 * (charIdx = classIdx - 1) - zweryfikowane w kodzie źródłowym, nie zgadywane.
 */
object PaddleOcrCtcDecoder {
    private const val BLANK_IDX = 0

    fun decode(output: FloatArray, shape: LongArray, characterList: List<String>): Pair<String, Float> {
        val timeSteps = shape[1].toInt()
        val numClasses = shape[2].toInt()

        val indices = IntArray(timeSteps)
        val probs = FloatArray(timeSteps)
        for (t in 0 until timeSteps) {
            val offset = t * numClasses
            var maxIdx = 0
            var maxVal = output[offset]
            for (c in 1 until numClasses) {
                val v = output[offset + c]
                if (v > maxVal) {
                    maxVal = v
                    maxIdx = c
                }
            }
            indices[t] = maxIdx
            probs[t] = maxVal
        }

        val keptProbs = mutableListOf<Float>()
        val sb = StringBuilder()
        var prevIdx = -1
        for (t in 0 until timeSteps) {
            val idx = indices[t]
            if (idx != BLANK_IDX && idx != prevIdx) {
                val charIdx = idx - 1
                if (charIdx >= 0 && charIdx < characterList.size) {
                    sb.append(characterList[charIdx])
                    keptProbs.add(probs[t])
                }
            }
            prevIdx = idx
        }
        val confidence = if (keptProbs.isNotEmpty()) keptProbs.average().toFloat() else 0f
        return sb.toString() to confidence
    }
}
