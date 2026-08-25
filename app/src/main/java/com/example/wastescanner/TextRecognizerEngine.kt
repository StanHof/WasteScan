package com.example.wastescanner

import android.graphics.Bitmap

/**
 * Wąski kontrakt dla silnika rozpoznawania tekstu (OCR), niezależny od konkretnej biblioteki.
 *
 * Celowo zwraca List<String> (listę rozpoznanych słów), a nie surowy String - to jest właśnie
 * kontrakt, pod który docelowo zaprojektujesz swój wytrenowany model: zdjęcie wchodzi, lista
 * rozpoznanych słów wychodzi, dalej trafia bez zmian do ToxicityRepository.findMatches().
 *
 * Aktualne implementacje:
 *  - MlKitTextRecognizerEngine - obecny, działający silnik (ML Kit). Zostaje jako punkt
 *    odniesienia do porównania z własnym modelem (Faza 5 planu: benchmark dokładności/czasu).
 *  - TfliteTextRecognizerEngine - placeholder na bazie gotowego modelu CRNN z TF Hub,
 *    docelowo do podmiany na własny wytrenowany model o tej samej sygnaturze.
 */
interface TextRecognizerEngine {
    suspend fun recognizeWords(bitmap: Bitmap): List<String>
}