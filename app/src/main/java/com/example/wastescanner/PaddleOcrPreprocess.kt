package com.example.wastescanner

import android.graphics.Bitmap
import kotlin.math.ceil

/**
 * Przygotowanie tensorów wejściowych dla modeli detekcji i rozpoznawania PP-OCRv6 - zamiennik
 * DetPreprocessor.kt/RecPreprocessor.kt z oficjalnego demo, bez OpenCV (Bitmap.getPixels()
 * zamiast org.opencv.core.Mat). Dokładność numeryczna (kolejność kanałów, stałe normalizacji,
 * konwencja zaokrąglania) zweryfikowana 1:1 względem tamtego kodu źródłowego - patrz
 * docs/paddleocr_evaluation.md.
 */
object PaddleOcrPreprocess {

    data class TensorResult(val data: FloatArray, val shape: LongArray)

    // Zweryfikowane w app/src/main/assets/paddleocr/det/inference.yml (PreProcess.NormalizeImage):
    // mean/std zastosowane WPROST do kanałów w kolejności BGR (nie remapowane do RGB) - tak samo
    // jak w oryginalnym DetPreprocessor.kt. To jest świadomie takie samo (nietypowe) użycie stałych
    // ImageNet, bo właśnie taka kolejność dała poprawne wyniki w zweryfikowanym demo.
    private val DET_MEAN = floatArrayOf(0.485f, 0.456f, 0.406f) // [B, G, R]
    private val DET_STD = floatArrayOf(0.229f, 0.224f, 0.225f)  // [B, G, R]
    private const val DET_SCALE = 1f / 255f

    private const val REC_FIXED_HEIGHT = 48
    private const val REC_MAX_WIDTH = 3200

    /** Port 1:1 ImageUtils.resizeToMultipleOf32 (limitType "min"/"max"/"resize_long"). */
    fun resizeToMultipleOf32(bitmap: Bitmap, limitSideLen: Int, limitType: String, maxSideLimit: Int): Bitmap {
        val h = bitmap.height
        val w = bitmap.width
        var ratio = when (limitType.lowercase()) {
            "max" -> if (maxOf(h, w) > limitSideLen) limitSideLen.toDouble() / maxOf(h, w) else 1.0
            "min" -> if (minOf(h, w) < limitSideLen) limitSideLen.toDouble() / minOf(h, w) else 1.0
            "resize_long" -> limitSideLen.toDouble() / maxOf(h, w)
            else -> throw IllegalArgumentException("Unsupported det limit type: $limitType")
        }

        var newH = (h * ratio).toInt()
        var newW = (w * ratio).toInt()
        if (maxOf(newH, newW) > maxSideLimit) {
            ratio = maxSideLimit.toDouble() / maxOf(newH, newW)
            newH = (newH * ratio).toInt()
            newW = (newW * ratio).toInt()
        }

        newH = maxOf(PaddleOcrGeometry.roundHalfToEven(newH / 32.0) * 32, 32)
        newW = maxOf(PaddleOcrGeometry.roundHalfToEven(newW / 32.0) * 32, 32)
        return Bitmap.createScaledBitmap(bitmap, newW, newH, true)
    }

    /**
     * Buduje tensor wejściowy [1,3,H,W] dla modelu detekcji: BGR, znormalizowany ImageNet
     * mean/std (patrz uwaga o kolejności kanałów wyżej), układ CHW (płaszczyzny B,G,R po kolei).
     */
    fun buildDetTensor(resized: Bitmap): TensorResult {
        val w = resized.width
        val h = resized.height
        val pixels = IntArray(w * h)
        resized.getPixels(pixels, 0, w, 0, 0, w, h)

        val planeSize = w * h
        val data = FloatArray(3 * planeSize)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = ((p shr 16) and 0xFF) * DET_SCALE
            val g = ((p shr 8) and 0xFF) * DET_SCALE
            val b = (p and 0xFF) * DET_SCALE
            // Kolejność płaszczyzn B,G,R - zgodnie z img_mode: BGR w inference.yml modelu detekcji.
            data[i] = (b - DET_MEAN[0]) / DET_STD[0]
            data[planeSize + i] = (g - DET_MEAN[1]) / DET_STD[1]
            data[2 * planeSize + i] = (r - DET_MEAN[2]) / DET_STD[2]
        }
        return TensorResult(data, longArrayOf(1, 3, h.toLong(), w.toLong()))
    }

    /**
     * Wycinek słowa -> tensor [1,3,48,W] dla modelu rozpoznawania: RGB, znormalizowany do [-1,1]
     * przez x/127.5 - 1 (zweryfikowane w preprocess/RecPreprocessor.kt), skalowany do stałej
     * wysokości 48px z zachowaniem proporcji (szerokość ograniczona do 3200px). Batch=1 - patrz
     * PaddleOCRConfig.recBatchSize=1 w oficjalnym demo (ten sam domyślny rozmiar partii), więc
     * dopełnianie do wspólnej szerokości partii (jak w oryginalnym RecPreprocessor dla batch>1)
     * jest tu zbędne.
     */
    fun buildRecTensor(crop: Bitmap): TensorResult {
        val aspectRatio = if (crop.height > 0) crop.width.toDouble() / crop.height else 1.0
        val newW = ceil(REC_FIXED_HEIGHT * aspectRatio).toInt().coerceIn(1, REC_MAX_WIDTH)
        val resized = Bitmap.createScaledBitmap(crop, newW, REC_FIXED_HEIGHT, true)

        val pixels = IntArray(newW * REC_FIXED_HEIGHT)
        resized.getPixels(pixels, 0, newW, 0, 0, newW, REC_FIXED_HEIGHT)
        if (resized !== crop) resized.recycle()

        val planeSize = newW * REC_FIXED_HEIGHT
        val data = FloatArray(3 * planeSize)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = ((p shr 16) and 0xFF) / 127.5f - 1f
            val g = ((p shr 8) and 0xFF) / 127.5f - 1f
            val b = (p and 0xFF) / 127.5f - 1f
            // Kolejność płaszczyzn R,G,B - RecPreprocessor konwertuje BGR->RGB przed normalizacją.
            data[i] = r
            data[planeSize + i] = g
            data[2 * planeSize + i] = b
        }
        return TensorResult(data, longArrayOf(1, 3, REC_FIXED_HEIGHT.toLong(), newW.toLong()))
    }
}
