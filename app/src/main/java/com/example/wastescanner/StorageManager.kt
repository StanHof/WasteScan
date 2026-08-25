package com.example.wastescanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.FileOutputStream

object StorageManager {

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
}