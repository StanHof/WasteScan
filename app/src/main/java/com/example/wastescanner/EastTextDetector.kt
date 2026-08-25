package com.example.wastescanner

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.cos
import kotlin.math.sin

/**
 * Rotowana ramka tekstu wykryta przez EAST, we współrzędnych obrazu podanego do detectora
 * (przed przeskalowaniem z powrotem do oryginalnej rozdzielczości zdjęcia).
 */
data class RotatedTextBox(
    val centerX: Float,
    val centerY: Float,
    val width: Float,
    val height: Float,
    val angleDegrees: Float,
    val confidence: Float
) {
    /**
     * Zwraca 4 wierzchołki ramki - dokładnie ten sam wzór co OpenCV RotatedRect::points(),
     * żeby zachować identyczną kolejność i konwencję jak w oryginalnym algorytmie EAST.
     */
    fun corners(): Array<FloatArray> {
        val angleRad = Math.toRadians(angleDegrees.toDouble())
        val b = (cos(angleRad) * 0.5).toFloat()
        val a = (sin(angleRad) * 0.5).toFloat()

        val p0x = centerX - a * height - b * width
        val p0y = centerY + b * height - a * width
        val p1x = centerX + a * height - b * width
        val p1y = centerY - b * height - a * width
        val p2x = 2 * centerX - p0x
        val p2y = 2 * centerY - p0y
        val p3x = 2 * centerX - p1x
        val p3y = 2 * centerY - p1y

        return arrayOf(
            floatArrayOf(p0x, p0y),
            floatArrayOf(p1x, p1y),
            floatArrayOf(p2x, p2y),
            floatArrayOf(p3x, p3y)
        )
    }
}

/**
 * Detektor regionów tekstu oparty o model EAST (TFLite), wierny algorytmowi z oficjalnego
 * przykładu OpenCV (samples/dnn/text_detection.cpp - decodeBoundingBoxes), ale bez zależności
 * od biblioteki OpenCV: NMS i późniejsza transformacja perspektywy są zaimplementowane w
 * czystym Kotlinie / Android SDK (patrz EastTextRegionDetector.kt).
 *
 * Źródło modelu: https://tfhub.dev/sayakpaul/lite-model/east-text-detector/fp16/1
 * (stały rozmiar wejścia 320x320).
 *
 * WYMAGANA KONFIGURACJA: umieść plik `east_text_detector.tflite` w app/src/main/assets/.
 *
 * UWAGA: preprocessing (brak dzielenia przez 255, odjęcie stałych (123.68, 116.78, 103.94)
 * per kanał R/G/B) oraz wzór dekodowania geometrii są przepisane 1:1 z oficjalnego kodu
 * OpenCV - nie są zgadywane. Jedyna rzecz zweryfikowana w runtime (nie założona na sztywno)
 * to układ tensorów wyjściowych (NHWC vs NCHW) - patrz logowanie w konstruktorze.
 */
class EastTextDetector(context: Context, modelFileName: String = "east_text_detector.tflite") {

    companion object {
        private const val TAG = "EastTextDetector"
        private const val MEAN_R = 123.68f
        private const val MEAN_G = 116.78f
        private const val MEAN_B = 103.94f
        private const val DOWNSAMPLE_FACTOR = 4f // wejście/wyjście EAST: 320x320 -> 80x80
    }

    private val interpreter: Interpreter? = try {
        Interpreter(FileUtil.loadMappedFile(context, modelFileName)).also { loaded ->
            Log.d(TAG, "Model EAST wczytany. Wejście: ${loaded.getInputTensor(0).shape().toList()}")
            for (i in 0 until loaded.outputTensorCount) {
                Log.d(TAG, "  output[$i]: shape=${loaded.getOutputTensor(i).shape().toList()}, dataType=${loaded.getOutputTensor(i).dataType()}")
            }
        }
    } catch (e: Exception) {
        Log.e(TAG, "Nie udało się wczytać modelu EAST '$modelFileName' z assets - detekcja zwróci pustą listę.", e)
        null
    }

    /** Wymiary wejścia modelu odczytane w runtime (typowo 320x320) - do przeliczenia
     *  współrzędnych wykrytych ramek z powrotem na oryginalną rozdzielczość zdjęcia. */
    val inputWidth: Int = interpreter?.getInputTensor(0)?.shape()?.get(2) ?: 320
    val inputHeight: Int = interpreter?.getInputTensor(0)?.shape()?.get(1) ?: 320

    /**
     * Zwraca wykryte ramki tekstu we współrzędnych PRZESKALOWANEGO wejścia (inputWidth x
     * inputHeight modelu, zwykle 320x320) - przeskalowanie do oryginalnego zdjęcia wykonuje
     * wywołujący (EastTextRegionDetector), bo tylko on zna oryginalne wymiary.
     */
    fun detect(bitmap: Bitmap, scoreThreshold: Float = 0.5f, nmsIouThreshold: Float = 0.4f): List<RotatedTextBox> {
        val engine = interpreter ?: return emptyList()

        val inputShape = engine.getInputTensor(0).shape() // [1, height, width, 3]
        val inputHeight = inputShape[1]
        val inputWidth = inputShape[2]

        val inputBuffer = preprocess(bitmap, inputWidth, inputHeight)

        val scoresShape = engine.getOutputTensor(0).shape()
        val geometryShape = engine.getOutputTensor(1).shape()
        val scoresBuffer = ByteBuffer.allocateDirect(scoresShape.fold(1) { a, d -> a * d } * 4)
            .order(ByteOrder.nativeOrder())
        val geometryBuffer = ByteBuffer.allocateDirect(geometryShape.fold(1) { a, d -> a * d } * 4)
            .order(ByteOrder.nativeOrder())

        val outputs = mutableMapOf<Int, Any>(0 to scoresBuffer, 1 to geometryBuffer)
        engine.runForMultipleInputsOutputs(arrayOf(inputBuffer), outputs)

        scoresBuffer.rewind()
        geometryBuffer.rewind()

        val boxes = decodeBoundingBoxes(
            scoresBuffer.asFloatBuffer(), geometryShape, geometryBuffer.asFloatBuffer(),
            scoresShape, scoreThreshold
        )
        Log.d(TAG, "EAST: ${boxes.size} kandydatów przed NMS")

        val filtered = nonMaxSuppression(boxes, nmsIouThreshold)
        Log.d(TAG, "EAST: ${filtered.size} ramek po NMS")
        return filtered
    }

    /** Skalowanie do wymiarów modelu + odjęcie stałych R/G/B (BEZ dzielenia przez 255) -
     *  dokładnie jak blobFromImage(frame, 1.0, size, Scalar(123.68, 116.78, 103.94), true, false). */
    private fun preprocess(bitmap: Bitmap, inputWidth: Int, inputHeight: Int): ByteBuffer {
        val resized = Bitmap.createScaledBitmap(bitmap, inputWidth, inputHeight, true)
        val pixels = IntArray(inputWidth * inputHeight)
        resized.getPixels(pixels, 0, inputWidth, 0, 0, inputWidth, inputHeight)

        val buffer = ByteBuffer.allocateDirect(inputWidth * inputHeight * 3 * 4)
            .order(ByteOrder.nativeOrder())

        for (pixel in pixels) {
            val r = ((pixel shr 16) and 0xFF).toFloat()
            val g = ((pixel shr 8) and 0xFF).toFloat()
            val b = (pixel and 0xFF).toFloat()
            buffer.putFloat(r - MEAN_R)
            buffer.putFloat(g - MEAN_G)
            buffer.putFloat(b - MEAN_B)
        }
        buffer.rewind()
        return buffer
    }

    /**
     * Wierne tłumaczenie decodeBoundingBoxes() z samples/dnn/text_detection.cpp (OpenCV).
     * Zakłada układ NHWC (typowy dla TFLite): scores [1,H,W,1], geometry [1,H,W,5]
     * (kanały: 0=top, 1=right, 2=bottom, 3=left, 4=angle).
     */
    private fun decodeBoundingBoxes(
        scores: java.nio.FloatBuffer,
        geometryShape: IntArray,
        geometry: java.nio.FloatBuffer,
        scoresShape: IntArray,
        scoreThreshold: Float
    ): List<RotatedTextBox> {
        val height = scoresShape[1]
        val width = scoresShape[2]
        val results = mutableListOf<RotatedTextBox>()

        val scoresArray = FloatArray(scores.remaining()).also { scores.get(it) }
        val geometryArray = FloatArray(geometry.remaining()).also { geometry.get(it) }

        for (y in 0 until height) {
            for (x in 0 until width) {
                val scoreIdx = y * width + x
                val score = scoresArray[scoreIdx]
                if (score < scoreThreshold) continue

                val geomBase = (y * width + x) * 5
                val top = geometryArray[geomBase + 0]
                val right = geometryArray[geomBase + 1]
                val bottom = geometryArray[geomBase + 2]
                val left = geometryArray[geomBase + 3]
                val angle = geometryArray[geomBase + 4]

                val offsetX = x * DOWNSAMPLE_FACTOR
                val offsetY = y * DOWNSAMPLE_FACTOR
                val cosA = cos(angle)
                val sinA = sin(angle)
                val h = top + bottom
                val w = right + left

                val offX = offsetX + cosA * right + sinA * bottom
                val offY = offsetY - sinA * right + cosA * bottom

                val p1x = -sinA * h + offX
                val p1y = -cosA * h + offY
                val p3x = -cosA * w + offX
                val p3y = sinA * w + offY

                val centerX = 0.5f * (p1x + p3x)
                val centerY = 0.5f * (p1y + p3y)
                val angleDegrees = -angle * 180f / Math.PI.toFloat()

                results.add(RotatedTextBox(centerX, centerY, w, h, angleDegrees, score))
            }
        }
        return results
    }

    /**
     * Greedy NMS na bazie IoU osiowo wyrównanych prostokątów otaczających (bounding box)
     * każdej rotowanej ramki - uproszczenie względem OpenCV NMSBoxesRotated, świadomie
     * przyjęte, żeby uniknąć zależności od OpenCV. Wystarczające dla naszego przypadku
     * (odrzucanie mocno nakładających się duplikatów), niedokładne przy silnie obróconych,
     * blisko siebie leżących ramkach - do rewizji, jeśli to się okaże problemem w praktyce.
     */
    private fun nonMaxSuppression(boxes: List<RotatedTextBox>, iouThreshold: Float): List<RotatedTextBox> {
        val sorted = boxes.sortedByDescending { it.confidence }.toMutableList()
        val result = mutableListOf<RotatedTextBox>()
        while (sorted.isNotEmpty()) {
            val best = sorted.removeAt(0)
            result.add(best)
            sorted.removeAll { boundingBoxIou(best, it) > iouThreshold }
        }
        return result
    }

    private fun boundingBoxIou(a: RotatedTextBox, b: RotatedTextBox): Float {
        val aLeft = a.centerX - a.width / 2; val aRight = a.centerX + a.width / 2
        val aTop = a.centerY - a.height / 2; val aBottom = a.centerY + a.height / 2
        val bLeft = b.centerX - b.width / 2; val bRight = b.centerX + b.width / 2
        val bTop = b.centerY - b.height / 2; val bBottom = b.centerY + b.height / 2

        val interLeft = maxOf(aLeft, bLeft)
        val interTop = maxOf(aTop, bTop)
        val interRight = minOf(aRight, bRight)
        val interBottom = minOf(aBottom, bBottom)
        val interArea = maxOf(0f, interRight - interLeft) * maxOf(0f, interBottom - interTop)
        val unionArea = a.width * a.height + b.width * b.height - interArea
        return if (unionArea <= 0f) 0f else interArea / unionArea
    }
}