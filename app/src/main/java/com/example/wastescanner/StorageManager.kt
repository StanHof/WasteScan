package com.example.wastescanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.io.FileOutputStream

object StorageManager {

    private const val TAG = "StorageManager"
    private const val BENCHMARK_ASSET_DIR = "benchmark"
    private const val BENCHMARK_MANIFEST_FILE = "manifest.json"

    // 1. Zapisuje zrobione zdjęcie jako plik JPG do ukrytego folderu aplikacji
    fun saveBitmap(context: Context, bitmap: Bitmap, fileName: String): String {
        val file = File(context.filesDir, "$fileName.jpg")
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        }
        return file.absolutePath // Zwracamy adres zapisanego pliku
    }

    // 2. Wczytuje zdjęcie z dysku na podstawie adresu
    fun loadBitmap(path: String?): Bitmap? {
        if (path == null) return null
        val file = File(path)
        return if (file.exists()) BitmapFactory.decodeFile(file.absolutePath) else null
    }

    // 3. Debug: zapisuje dowolną bitmapę pod wskazaną nazwą pliku - do wizualnej weryfikacji
    // (np. pojedynczych wycinków słów z segmentacji). Wywołanie musi być owinięte w
    // `if (BuildConfig.DEBUG)` w miejscu użycia. Ten sam zestaw nazw jest nadpisywany przy
    // każdym skanie, więc nie zaśmieca pamięci urządzenia.
    fun saveDebugBitmap(context: Context, bitmap: Bitmap, fileName: String): String {
        val file = File(context.filesDir, "$fileName.jpg")
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
        }
        return file.absolutePath
    }

    // 3b. Zachowane dla kompatybilności z istniejącymi wywołaniami.
    fun saveDebugOcrInputBitmap(context: Context, bitmap: Bitmap): String =
        saveDebugBitmap(context, bitmap, "debug_ocr_input")

    // 4. Serializuje historię skanów do CSV (eksport przez ScanHistoryScreen).
    fun generateCsvData(historyList: List<HistoryItem>): String {
        val builder = StringBuilder()
        builder.append("Data,Werdykt_ogolny,Liczba_skladnikow,Skladniki_ryzykowne,Czas_AI_ms,Komentarz\n")

        historyList.forEach { item ->
            val report = item.report
            val riskyIngredients = report.ingredients
                .filter { it.riskLevel != RiskLevel.SAFE }
                .joinToString(separator = "; ") { it.matchedName }
                .ifEmpty { "Brak" }
            val comment = report.comment?.replace(",", ";")?.replace("\n", " ") ?: "Brak"

            builder.append(
                "${item.dateString},${report.overallRisk.labelPL},${report.ingredients.size}," +
                        "$riskyIngredients,${report.executionTimeMs},$comment\n"
            )
        }
        return builder.toString()
    }

    // 5. Serializuje wyniki BenchmarkScreen (local vs. cloud na tych samych zdjęciach) do CSV,
    // format szeroki (jeden wiersz na zdjęcie, kolumny local/cloud obok siebie). Jeśli wynik ma
    // dopasowany ground truth z manifestu (patrz loadBenchmarkManifest), dolicza recall/precyzję
    // na poziomie rozpoznanych SUBSTANCJI (nie pojedynczych słów - do tego potrzebna byłaby
    // osobna transkrypcja wzorcowa, której ten ekran nie zbiera).
    fun generateBenchmarkCsvData(results: List<BenchmarkResult>): String {
        fun clean(text: String?) = (text ?: "").replace(",", ";").replace("\n", " ")
        fun riskyNames(report: IngredientSafetyReport) = report.ingredients
            .filter { it.riskLevel != RiskLevel.SAFE }
            .joinToString(separator = "; ") { it.matchedName }
            .ifEmpty { "Brak" }

        // Zwraca (recall%, precyzja%, falszywyAlarm) względem oczekiwanych id z manifestu.
        // Liczone WYŁĄCZNIE na bazie matchedSubstanceId (dopasowań do bazy wiedzy) - składniki
        // zgłoszone przez model spoza bazy (matchedSubstanceId == null) nie da się automatycznie
        // zweryfikować względem ground truth (który też jest wyrażony w id z bazy), więc są
        // pomijane w tym liczeniu, choć nadal widoczne w kolumnie "Wykryte substancje".
        fun score(report: IngredientSafetyReport, expected: List<String>?): Triple<String, String, String> {
            if (expected == null) return Triple("N/D", "N/D", "N/D")
            val expectedSet = expected.toSet()
            val detectedSet = report.ingredients.mapNotNull { it.matchedSubstanceId }.toSet()
            if (expectedSet.isEmpty()) {
                val falseAlarm = if (detectedSet.isNotEmpty()) "TAK" else "NIE"
                return Triple("N/D", "N/D", falseAlarm)
            }
            val overlap = (expectedSet intersect detectedSet).size
            val recall = overlap.toDouble() / expectedSet.size * 100
            val precision = if (detectedSet.isEmpty()) 0.0 else overlap.toDouble() / detectedSet.size * 100
            return Triple("%.0f".format(recall), "%.0f".format(precision), "-")
        }

        val builder = StringBuilder()
        builder.append(
            "ZdjecieID,Produkt,Oczekiwane_substancje,Local_Werdykt,Local_Wykryte_substancje," +
                    "Local_Wykryte_slowa,Local_Czas_ms,Local_Recall_proc,Local_Precyzja_proc,Local_Falszywy_alarm," +
                    "Cloud_Werdykt,Cloud_Wykryte_substancje,Cloud_Wykryte_slowa,Cloud_Czas_ms," +
                    "Cloud_Recall_proc,Cloud_Precyzja_proc,Cloud_Falszywy_alarm,Uwagi\n"
        )

        results.forEach { result ->
            val local = result.localReport
            val cloud = result.cloudReport
            val groundTruth = result.groundTruth
            val expectedIds = groundTruth?.expectedSubstanceIds

            val (localRecall, localPrecision, localFalseAlarm) = score(local, expectedIds)
            val (cloudRecall, cloudPrecision, cloudFalseAlarm) = score(cloud, expectedIds)

            builder.append(
                "${clean(result.imageLabel)},${clean(groundTruth?.productName)}," +
                        "${clean(expectedIds?.joinToString("; ") ?: "N/D (brak w manifeście)")}," +
                        "${local.overallRisk.labelPL},${riskyNames(local)},${clean(local.rawOcrText)}," +
                        "${local.executionTimeMs},$localRecall,$localPrecision,$localFalseAlarm," +
                        "${cloud.overallRisk.labelPL},${riskyNames(cloud)}," +
                        "N/D (tryb chmurowy nie zwraca pełnej listy słów),${cloud.executionTimeMs}," +
                        "$cloudRecall,$cloudPrecision,$cloudFalseAlarm,${clean(groundTruth?.notes)}\n"
            )
        }
        return builder.toString()
    }

    // 6. Wczytuje manifest zestawu testowego (ground truth) dla BenchmarkScreen z
    // app/src/main/assets/benchmark/manifest.json (tablica obiektów BenchmarkGroundTruthEntry -
    // patrz Models.kt). Zestaw testowy jest teraz częścią aplikacji (bundled asset), nie czymś
    // wybieranym za każdym razem z galerii/document pickera - dzięki temu jest wersjonowany razem
    // z kodem i odtwarzalny (ten sam zestaw za każdym uruchomieniem, na każdym urządzeniu).
    // Zwraca pustą listę (z logiem błędu) zamiast rzucać wyjątkiem, jeśli plik jest nieprawidłowy
    // albo jeszcze nie został dodany.
    fun loadBenchmarkManifest(context: Context): List<BenchmarkGroundTruthEntry> {
        return try {
            context.assets.open("$BENCHMARK_ASSET_DIR/$BENCHMARK_MANIFEST_FILE")
                .bufferedReader(Charsets.UTF_8).use { reader ->
                    val type = object : TypeToken<List<BenchmarkGroundTruthEntry>>() {}.type
                    Gson().fromJson<List<BenchmarkGroundTruthEntry>>(reader, type) ?: emptyList()
                }
        } catch (e: Exception) {
            Log.e(
                TAG,
                "Nie udało się wczytać manifestu benchmarku z assets/$BENCHMARK_ASSET_DIR/$BENCHMARK_MANIFEST_FILE " +
                        "- sprawdź, czy plik tam jest i czy jest poprawnym JSON-em.",
                e
            )
            emptyList()
        }
    }

    // 7. Wczytuje jedno zdjęcie zestawu testowego z assets/benchmark/<fileName> (fileName - patrz
    // BenchmarkGroundTruthEntry.fileName w manifeście). Zwraca null (z logiem błędu) zamiast
    // rzucać wyjątkiem, jeśli plik nie istnieje - BenchmarkScreen pomija wtedy to zdjęcie i
    // kontynuuje z resztą zestawu, zamiast przerywać cały przebieg.
    fun loadBenchmarkImage(context: Context, fileName: String): Bitmap? {
        return try {
            context.assets.open("$BENCHMARK_ASSET_DIR/$fileName").use { stream ->
                BitmapFactory.decodeStream(stream)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Nie udało się wczytać zdjęcia benchmarku 'assets/$BENCHMARK_ASSET_DIR/$fileName'.", e)
            null
        }
    }
}