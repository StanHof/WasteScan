package com.example.wastescanner

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Implementacja TextRecognizerEngine oparta o ML Kit Text Recognition.
 * Zachowana jako baseline do porównania z własnym modelem TFLite (Faza 5 planu).
 */
class MlKitTextRecognizerEngine : TextRecognizerEngine {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    override suspend fun recognizeWords(bitmap: Bitmap): List<String> {
        val rawText = recognizeRaw(bitmap)
        // ML Kit zwraca cały blok tekstu (z zachowaniem linii) - dzielimy go na pojedyncze
        // słowa tym samym sposobem, jakiego używa ToxicityRepository.tokenize(), żeby oba
        // silniki (ML Kit i TFLite) dostarczały dane w tym samym formacie do dalszego dopasowania.
        return ToxicityRepository.tokenize(rawText)
    }

    private suspend fun recognizeRaw(bitmap: Bitmap): String = suspendCancellableCoroutine { continuation ->
        val image = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(image)
            .addOnSuccessListener { visionText -> continuation.resume(visionText.text) }
            .addOnFailureListener { exception -> continuation.resumeWithException(exception) }
    }
}