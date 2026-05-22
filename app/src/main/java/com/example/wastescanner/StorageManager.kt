package com.example.wastescanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.io.FileOutputStream

object StorageManager {
    private const val PREFS_NAME = "waste_scanner_prefs"
    private const val HISTORY_KEY = "scan_history"

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

    // 3. Tłumaczy całą listę historii na tekst (JSON) i zapisuje w telefonie
    fun saveHistory(context: Context, historyList: List<HistoryItem>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonString = Gson().toJson(historyList)
        prefs.edit().putString(HISTORY_KEY, jsonString).apply()
    }

    // 4. Przy uruchamianiu aplikacji: odczytuje tekst i odtwarza listę historii
    fun loadHistory(context: Context): List<HistoryItem> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonString = prefs.getString(HISTORY_KEY, null) ?: return emptyList()
        val type = object : TypeToken<List<HistoryItem>>() {}.type
        return try {
            Gson().fromJson(jsonString, type)
        } catch (e: Exception) {
            emptyList()
        }
    }
}