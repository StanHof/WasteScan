package com.example.wastescanner

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.FloatBuffer

/**
 * Implementacja TextRecognizerEngine na bazie PaddleOCR (PP-OCRv6_small, ONNX Runtime) -
 * alternatywa dla własnego pipeline'u EAST+CRNN (patrz TfliteTextRecognizerEngine.kt), wybrana po
 * ocenie wykonalności udokumentowanej w docs/paddleocr_evaluation.md (desktop: ~81% recall na
 * zestawie testowym z pełnym wsparciem polskich znaków diakrytycznych; on-device: potwierdzone
 * poprawne rozpoznawanie, zanim niezwiązany błąd w oficjalnym demo przerwał test).
 *
 * ŚWIADOMIE bez OpenCV (w przeciwieństwie do oficjalnego demo PaddleOCR) - z tego samego powodu,
 * dla którego EastTextDetector.kt też unika OpenCV: trzy kolejne awarie natywne ABI podczas
 * testowania demo (patrz docs/paddleocr_evaluation.md) potwierdziły ryzyko tej zależności.
 * Detekcja (DB postprocessing) i geometria są reimplementowane w czystym Kotlinie/Android SDK -
 * patrz PaddleOcrGeometry.kt, PaddleOcrDetPostProcess.kt, PaddleOcrPreprocess.kt.
 *
 * WYMAGANA KONFIGURACJA: modele w app/src/main/assets/paddleocr/{small,medium}/{det,rec}/inference.onnx +
 * assets/paddleocr/{small,medium}/rec/inference.yml (słownik znaków - patrz PaddleOcrCharacterDict.kt).
 */
class PaddleOcrTextRecognizerEngine(
    private val context: Context,
    // Katalog wariantu modelu: "paddleocr/small" (PP-OCRv6_small, ~31 MB) albo "paddleocr/medium"
    // (PP-OCRv6_medium, ~138 MB) - oba mają ten sam układ {det,rec}/inference.onnx + rec/inference.yml
    // i ten sam słownik znaków, więc cały pozostały pipeline jest wspólny (porównanie w benchmarku).
    modelDir: String = SMALL_MODEL_DIR,
    detModelAsset: String = "$modelDir/det/inference.onnx",
    recModelAsset: String = "$modelDir/rec/inference.onnx",
    recConfigAsset: String = "$modelDir/rec/inference.yml",
) : TextRecognizerEngine {

    companion object {
        private const val TAG = "PaddleOcrTextRecognizer"
        const val SMALL_MODEL_DIR = "paddleocr/small"
        const val MEDIUM_MODEL_DIR = "paddleocr/medium"
        private const val NUM_THREADS = 4

        // Domyślne wartości PaddleOCRConfig z oficjalnego demo (NIE wartości sugerowane przez
        // metadane modelu w inference.yml, które są inne - thresh=0.2/box_thresh=0.45/
        // unclip_ratio=1.4 - te z PaddleOCRConfig są tym, co faktycznie zweryfikowano jako
        // poprawnie działające na urządzeniu, patrz docs/paddleocr_evaluation.md).
        private const val DET_LIMIT_SIDE_LEN = 64
        private const val DET_LIMIT_TYPE = "min"
        private const val DET_MAX_SIDE_LIMIT = 4000
        private const val DET_THRESH = 0.3f
        private const val DET_BOX_THRESH = 0.6f
        private const val DET_UNCLIP_RATIO = 1.5f
        private const val DET_MAX_CANDIDATES = 3000
        private const val REC_SCORE_THRESH = 0.0f

        // Odpowiednik QuadTextCrop.VERTICAL_CROP_RATIO - bardzo wysokie/wąskie wycinki (typowe dla
        // pionowego tekstu w niektórych językach) obracane o 90 stopni przed rozpoznawaniem.
        private const val VERTICAL_CROP_RATIO = 1.5
    }

    private val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }

    private val detSession: OrtSession? = try {
        createSession(detModelAsset)
    } catch (e: Exception) {
        Log.e(TAG, "Nie udało się wczytać modelu detekcji '$detModelAsset' z assets.", e)
        null
    }

    private val recSession: OrtSession? = try {
        createSession(recModelAsset)
    } catch (e: Exception) {
        Log.e(TAG, "Nie udało się wczytać modelu rozpoznawania '$recModelAsset' z assets.", e)
        null
    }

    private val characterList: List<String> = try {
        PaddleOcrCharacterDict.load(context, recConfigAsset).also {
            Log.d(TAG, "Wczytano słownik znaków: ${it.size} pozycji.")
        }
    } catch (e: Exception) {
        Log.e(TAG, "Nie udało się wczytać słownika znaków z '$recConfigAsset'.", e)
        emptyList()
    }

    private fun createSession(assetPath: String): OrtSession {
        val bytes = context.assets.open(assetPath).use { it.readBytes() }
        val options = OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setIntraOpNumThreads(NUM_THREADS)
        }
        return env.createSession(bytes, options).also {
            Log.d(TAG, "Model '$assetPath' wczytany. Wejście: ${it.inputNames}, wyjście: ${it.outputNames}")
        }
    }

    override suspend fun recognizeWords(bitmap: Bitmap): List<String> = withContext(Dispatchers.Default) {
        val det = detSession
        val rec = recSession
        if (det == null || rec == null || characterList.isEmpty()) {
            Log.w(TAG, "Silnik PaddleOCR nieskonfigurowany (brak modeli lub słownika znaków) - zwracam pustą listę.")
            return@withContext emptyList()
        }

        val originalW = bitmap.width
        val originalH = bitmap.height

        val resized = PaddleOcrPreprocess.resizeToMultipleOf32(bitmap, DET_LIMIT_SIDE_LEN, DET_LIMIT_TYPE, DET_MAX_SIDE_LIMIT)
        val detTensor = PaddleOcrPreprocess.buildDetTensor(resized)
        if (resized !== bitmap) resized.recycle()

        val (detOutput, detShape) = try {
            runSession(det, detTensor.data, detTensor.shape)
        } catch (e: Exception) {
            Log.e(TAG, "Błąd inferencji modelu detekcji", e)
            return@withContext emptyList()
        }

        val mapHeight = detShape[2].toInt()
        val mapWidth = detShape[3].toInt()
        val boxes = PaddleOcrDetPostProcess.process(
            pred = detOutput,
            mapHeight = mapHeight,
            mapWidth = mapWidth,
            thresh = DET_THRESH,
            boxThresh = DET_BOX_THRESH,
            unclipRatio = DET_UNCLIP_RATIO,
            maxCandidates = DET_MAX_CANDIDATES,
            originalW = originalW,
            originalH = originalH,
        )
        Log.d(TAG, "Detekcja: ${boxes.size} regionów tekstu (mapa ${mapWidth}x$mapHeight, zdjęcie ${originalW}x$originalH)")

        val sortedBoxes = PaddleOcrDetPostProcess.sortInReadingOrder(boxes)
        val words = mutableListOf<String>()

        for (box in sortedBoxes) {
            val crop = try {
                cropQuad(bitmap, box)
            } catch (e: Exception) {
                Log.e(TAG, "Błąd wycinania regionu tekstu, pomijam go", e)
                continue
            } ?: continue

            val recTensor = PaddleOcrPreprocess.buildRecTensor(crop)
            crop.recycle()

            val (recOutput, recShape) = try {
                runSession(rec, recTensor.data, recTensor.shape)
            } catch (e: Exception) {
                Log.e(TAG, "Błąd inferencji modelu rozpoznawania", e)
                continue
            }

            val (text, confidence) = PaddleOcrCtcDecoder.decode(recOutput, recShape, characterList)
            if (text.isNotBlank() && confidence >= REC_SCORE_THRESH) {
                words.add(text)
            }
        }

        Log.d(TAG, "Rozpoznano ${words.size} słów: $words")
        words
    }

    /** Prostuje wykryty (ewentualnie obrócony) czworokąt do prostokątnej bitmapy metodą
     *  Matrix.setPolyToPoly (ten sam sposób co EastTextRegionDetector.perspectiveCrop - pełna
     *  transformacja perspektywiczna bez OpenCV), dopasowaną do konwencji punktów
     *  PaddleOcrGeometry.orderQuadPoints: [góra-lewo, góra-prawo, dół-prawo, dół-lewo]. */
    private fun cropQuad(source: Bitmap, box: PaddleOcrDetPostProcess.DetectedBox): Bitmap? {
        val p = box.points
        val widthTop = hypot(p[1].x - p[0].x, p[1].y - p[0].y)
        val widthBottom = hypot(p[2].x - p[3].x, p[2].y - p[3].y)
        val heightLeft = hypot(p[3].x - p[0].x, p[3].y - p[0].y)
        val heightRight = hypot(p[2].x - p[1].x, p[2].y - p[1].y)

        val outWidth = maxOf(widthTop, widthBottom).toInt().coerceAtLeast(1)
        val outHeight = maxOf(heightLeft, heightRight).toInt().coerceAtLeast(1)

        val srcPoints = floatArrayOf(
            p[0].x.toFloat(), p[0].y.toFloat(),
            p[1].x.toFloat(), p[1].y.toFloat(),
            p[2].x.toFloat(), p[2].y.toFloat(),
            p[3].x.toFloat(), p[3].y.toFloat(),
        )
        val dstPoints = floatArrayOf(
            0f, 0f,
            outWidth.toFloat(), 0f,
            outWidth.toFloat(), outHeight.toFloat(),
            0f, outHeight.toFloat(),
        )
        val matrix = Matrix()
        if (!matrix.setPolyToPoly(srcPoints, 0, dstPoints, 0, 4)) return null

        val output = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(source, matrix, paint)

        // Odpowiednik QuadTextCrop: bardzo wysokie/wąskie wycinki obracane o 90 stopni przed
        // rozpoznawaniem (typowe dla pionowo ułożonego tekstu w niektórych układach etykiet).
        if (outHeight.toDouble() / outWidth >= VERTICAL_CROP_RATIO) {
            val rotateMatrix = Matrix().apply { postRotate(-90f) }
            val rotated = Bitmap.createBitmap(output, 0, 0, outWidth, outHeight, rotateMatrix, true)
            output.recycle()
            return rotated
        }
        return output
    }

    private fun hypot(dx: Double, dy: Double): Double = kotlin.math.hypot(dx, dy)

    private fun runSession(session: OrtSession, input: FloatArray, shape: LongArray): Pair<FloatArray, LongArray> {
        val tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(input), shape)
        return try {
            val result = session.run(mapOf(session.inputNames.iterator().next() to tensor))
            try {
                val outputName = session.outputNames.iterator().next()
                val outputTensor = result.get(outputName).orElseThrow { Exception("Brak tensora wyjściowego") } as OnnxTensor
                val buffer = outputTensor.floatBuffer.duplicate().also { it.rewind() }
                val output = FloatArray(buffer.remaining())
                buffer.get(output)
                Pair(output, outputTensor.info.shape)
            } finally {
                result.close()
            }
        } finally {
            tensor.close()
        }
    }
}
